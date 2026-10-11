#!/usr/bin/env python3
"""UiDriverClient - PC端 AccessibilityService 驱动客户端."""

import argparse
import json
import socket
import subprocess
import sys
import time
from dataclasses import dataclass
from typing import Any, Optional


@dataclass
class Bounds:
    left: int
    top: int
    right: int
    bottom: int

    @property
    def center_x(self) -> int:
        return (self.left + self.right) // 2

    @property
    def center_y(self) -> int:
        return (self.top + self.bottom) // 2


@dataclass
class UiNode:
    id: str
    package_name: Optional[str]
    class_name: Optional[str]
    text: Optional[str]
    content_description: Optional[str]
    hint: Optional[str]
    bounds: Bounds
    clickable: bool
    long_clickable: bool
    scrollable: bool
    enabled: bool
    checked: bool
    selected: bool
    focused: bool
    children: list["UiNode"]

    @staticmethod
    def from_json(data: dict) -> "UiNode":
        bounds = data.get("bounds", {})
        return UiNode(
            id=data.get("id", ""),
            package_name=data.get("packageName"),
            class_name=data.get("className"),
            text=data.get("text"),
            content_description=data.get("contentDescription"),
            hint=data.get("hint"),
            bounds=Bounds(
                left=bounds.get("left", 0),
                top=bounds.get("top", 0),
                right=bounds.get("right", 0),
                bottom=bounds.get("bottom", 0),
            ),
            clickable=data.get("clickable", False),
            long_clickable=data.get("longClickable", False),
            scrollable=data.get("scrollable", False),
            enabled=data.get("enabled", False),
            checked=data.get("checked", False),
            selected=data.get("selected", False),
            focused=data.get("focused", False),
            children=[UiNode.from_json(c) for c in data.get("children", [])],
        )


class UiDriverError(Exception):
    pass


class UiDriverClient:
    def __init__(
        self,
        device: Optional[str] = None,
        local_port: int = 27183,
        remote_port: int = 27183,
    ):
        self.device = device
        self.local_port = local_port
        self.remote_port = remote_port
        self._socket: Optional[socket.socket] = None
        self._reader: Optional[Any] = None
        self._writer: Optional[Any] = None
        self._seq = 0

    def __enter__(self) -> "UiDriverClient":
        self._ensure_adb_forward()
        self._ensure_accessibility_service_enabled()
        self._connect()
        if not self.ping():
            raise UiDriverError(
                "Ping PoLangAccessibilityService failed. "
                "Make sure the app is in the foreground and the accessibility service is running."
            )
        return self

    def __exit__(self, exc_type, exc_val, exc_tb) -> None:
        self.close()

    def _ensure_adb_forward(self) -> None:
        cmd = ["adb"]
        if self.device:
            cmd.extend(["-s", self.device])
        cmd.extend(["forward", f"tcp:{self.local_port}", f"tcp:{self.remote_port}"])
        result = subprocess.run(cmd, capture_output=True, text=True)
        if result.returncode != 0:
            raise UiDriverError(f"adb forward failed: {result.stderr}")

    def _ensure_accessibility_service_enabled(self) -> None:
        expected_service = "com.mamba.picme/.accessibility.PoLangAccessibilityService"

        def run_adb(*args: str) -> subprocess.CompletedProcess[str]:
            cmd = ["adb"]
            if self.device:
                cmd.extend(["-s", self.device])
            cmd.extend(args)
            return subprocess.run(cmd, capture_output=True, text=True)

        result = run_adb("shell", "settings", "get", "secure", "enabled_accessibility_services")
        if result.returncode != 0:
            raise UiDriverError(f"Failed to check accessibility services: {result.stderr}")

        services = result.stdout.strip()
        if expected_service in services:
            return

        # Service not enabled; try to enable it automatically.
        result = run_adb(
            "shell", "settings", "put", "secure", "enabled_accessibility_services", expected_service
        )
        if result.returncode != 0:
            raise UiDriverError(
                f"PoLangAccessibilityService is not enabled and auto-enable failed: {result.stderr}\n"
                f"Please enable it manually:\n"
                f"  adb shell settings put secure enabled_accessibility_services {expected_service}"
            )

        # Give the service a moment to start.
        time.sleep(1)

    def _connect(self) -> None:
        self._socket = socket.create_connection(("127.0.0.1", self.local_port), timeout=5.0)
        self._reader = self._socket.makefile("r")
        self._writer = self._socket.makefile("w")

    def _call(self, method: str, params: Optional[dict] = None) -> dict:
        self._seq += 1
        request = {
            "jsonrpc": "2.0",
            "id": self._seq,
            "method": method,
            "params": params or {},
        }
        self._writer.write(json.dumps(request, ensure_ascii=False) + "\n")
        self._writer.flush()
        line = self._reader.readline()
        if not line:
            raise UiDriverError("Empty response from server")
        response = json.loads(line)
        if "error" in response:
            raise UiDriverError(f"RPC error: {response['error']}")
        return response.get("result", {})

    def ping(self) -> bool:
        result = self._call("ping")
        return result.get("pong", False)

    def dump_ui(self, package: Optional[str] = None, max_depth: int = 50) -> UiNode:
        params: dict[str, Any] = {"maxDepth": max_depth}
        if package is not None:
            params["package"] = package
        result = self._call("ui.dump", params)
        return UiNode.from_json(result.get("nodes", {}))

    def find_nodes(
        self,
        text: Optional[str] = None,
        content_description: Optional[str] = None,
        class_name: Optional[str] = None,
        clickable: Optional[bool] = None,
        scrollable: Optional[bool] = None,
    ) -> list[UiNode]:
        params: dict[str, Any] = {}
        if text is not None:
            params["text"] = text
        if content_description is not None:
            params["contentDescription"] = content_description
        if class_name is not None:
            params["className"] = class_name
        if clickable is not None:
            params["clickable"] = clickable
        if scrollable is not None:
            params["scrollable"] = scrollable
        result = self._call("ui.find", params)
        return [UiNode.from_json(n) for n in result.get("nodes", [])]

    def click(
        self,
        text: Optional[str] = None,
        content_description: Optional[str] = None,
        bounds: Optional[Bounds] = None,
    ) -> bool:
        params: dict[str, Any] = {}
        if text is not None:
            params["text"] = text
        if content_description is not None:
            params["contentDescription"] = content_description
        if bounds is not None:
            params["bounds"] = {
                "left": bounds.left,
                "top": bounds.top,
                "right": bounds.right,
                "bottom": bounds.bottom,
            }
        result = self._call("action.click", params)
        return result.get("success", False)

    def long_click(
        self,
        text: Optional[str] = None,
        content_description: Optional[str] = None,
        bounds: Optional[Bounds] = None,
    ) -> bool:
        params: dict[str, Any] = {}
        if text is not None:
            params["text"] = text
        if content_description is not None:
            params["contentDescription"] = content_description
        if bounds is not None:
            params["bounds"] = {
                "left": bounds.left,
                "top": bounds.top,
                "right": bounds.right,
                "bottom": bounds.bottom,
            }
        result = self._call("action.longClick", params)
        return result.get("success", False)

    def swipe(
        self, start: tuple[int, int], end: tuple[int, int], duration_ms: int = 300
    ) -> bool:
        result = self._call(
            "action.swipe",
            {
                "start": {"x": start[0], "y": start[1]},
                "end": {"x": end[0], "y": end[1]},
                "durationMs": duration_ms,
            },
        )
        return result.get("success", False)

    def input_text(
        self,
        value: str,
        text: Optional[str] = None,
        content_description: Optional[str] = None,
        bounds: Optional[Bounds] = None,
    ) -> bool:
        params: dict[str, Any] = {"value": value}
        if text is not None:
            params["text"] = text
        if content_description is not None:
            params["contentDescription"] = content_description
        if bounds is not None:
            params["bounds"] = {
                "left": bounds.left,
                "top": bounds.top,
                "right": bounds.right,
                "bottom": bounds.bottom,
            }
        result = self._call("action.input", params)
        return result.get("success", False)

    def press_back(self) -> bool:
        result = self._call("action.pressBack")
        return result.get("success", False)

    def wait_for_idle(self, timeout_ms: int = 5000) -> bool:
        result = self._call("action.waitForIdle", {"timeoutMs": timeout_ms})
        return result.get("success", False)

    def wait_for(
        self,
        text: Optional[str] = None,
        content_description: Optional[str] = None,
        predicate: Optional[callable] = None,
        timeout_ms: int = 5000,
        poll_ms: int = 200,
    ) -> Optional[UiNode]:
        deadline = time.time() + timeout_ms / 1000.0
        while time.time() < deadline:
            if predicate is not None:
                node = self.find_node(predicate)
                if node is not None:
                    return node
            elif content_description is not None:
                nodes = self.find_nodes(content_description=content_description)
                if nodes:
                    return nodes[0]
            elif text is not None:
                nodes = self.find_nodes(text=text)
                if nodes:
                    return nodes[0]
            time.sleep(poll_ms / 1000.0)
        return None

    def find_node(
        self, predicate: callable, root: Optional[UiNode] = None
    ) -> Optional[UiNode]:
        """Depth-first search for the first node that satisfies ``predicate``."""
        tree = root if root is not None else self.dump_ui(package="com.mamba.picme")
        stack = [tree]
        while stack:
            node = stack.pop()
            if predicate(node):
                return node
            # Iterate in original order by reversing children before pushing.
            stack.extend(reversed(node.children))
        return None

    def close(self) -> None:
        try:
            if self._reader:
                self._reader.close()
            if self._writer:
                self._writer.close()
            if self._socket:
                self._socket.close()
        except Exception:
            pass


def format_ui_tree(node: UiNode, indent: int = 0) -> str:
    prefix = "  " * indent
    label = node.text or node.content_description or node.class_name or "Unknown"
    info = []
    if node.clickable:
        info.append("clickable")
    if node.scrollable:
        info.append("scrollable")
    info.append(
        f"bounds=({node.bounds.left},{node.bounds.top},{node.bounds.right},{node.bounds.bottom})"
    )
    lines = [f"{prefix}[{node.class_name or 'Node'}] {label} {', '.join(info)}"]
    for child in node.children:
        lines.extend(format_ui_tree(child, indent + 1).split("\n"))
    return "\n".join(lines)


def _parse_bounds(value: str) -> Bounds:
    data = json.loads(value)
    return Bounds(
        left=int(data["left"]),
        top=int(data["top"]),
        right=int(data["right"]),
        bottom=int(data["bottom"]),
    )


def _build_locator_args(parser: argparse.ArgumentParser) -> None:
    parser.add_argument("--text", help="Match node text (partial match)")
    parser.add_argument(
        "--content-description", help="Match node contentDescription (partial match)"
    )
    parser.add_argument("--class-name", help="Match node className (partial match)")
    parser.add_argument("--bounds", type=_parse_bounds, help="Match exact bounds as JSON")


def _locator_from_args(args: argparse.Namespace) -> dict[str, Any]:
    result: dict[str, Any] = {}
    if args.text:
        result["text"] = args.text
    if args.content_description:
        result["content_description"] = args.content_description
    if args.class_name:
        result["class_name"] = args.class_name
    if args.bounds:
        result["bounds"] = args.bounds
    return result


if __name__ == "__main__":
    parser = argparse.ArgumentParser(
        prog="ui_driver.py",
        description="PC-side AccessibilityService UI automation driver",
    )
    subparsers = parser.add_subparsers(dest="command", required=True)

    dump_parser = subparsers.add_parser("dump", help="Dump current UI tree")
    dump_parser.add_argument("--package", default="com.mamba.picme", help="Target package")
    dump_parser.add_argument("--max-depth", type=int, default=50, help="Max tree depth")

    click_parser = subparsers.add_parser("click", help="Click a node")
    _build_locator_args(click_parser)

    long_click_parser = subparsers.add_parser("long-click", help="Long-click a node")
    _build_locator_args(long_click_parser)

    input_parser = subparsers.add_parser("input", help="Input text into a node")
    _build_locator_args(input_parser)
    input_parser.add_argument("--value", required=True, help="Text to input")

    swipe_parser = subparsers.add_parser("swipe", help="Swipe on screen")
    swipe_parser.add_argument("--start-x", type=int, required=True)
    swipe_parser.add_argument("--start-y", type=int, required=True)
    swipe_parser.add_argument("--end-x", type=int, required=True)
    swipe_parser.add_argument("--end-y", type=int, required=True)
    swipe_parser.add_argument("--duration", type=int, default=300, help="Duration in ms")

    back_parser = subparsers.add_parser("back", help="Press back button")

    find_parser = subparsers.add_parser("find", help="Find nodes matching criteria")
    _build_locator_args(find_parser)

    args = parser.parse_args()

    with UiDriverClient() as client:
        if args.command == "dump":
            tree = client.dump_ui(package=args.package, max_depth=args.max_depth)
            print(format_ui_tree(tree))

        elif args.command == "click":
            locator = _locator_from_args(args)
            ok = client.click(**locator)
            print(f"click result: {ok}")

        elif args.command == "long-click":
            locator = _locator_from_args(args)
            ok = client.long_click(**locator)
            print(f"long-click result: {ok}")

        elif args.command == "input":
            locator = _locator_from_args(args)
            ok = client.input_text(args.value, **locator)
            print(f"input result: {ok}")

        elif args.command == "swipe":
            ok = client.swipe(
                start=(args.start_x, args.start_y),
                end=(args.end_x, args.end_y),
                duration_ms=args.duration,
            )
            print(f"swipe result: {ok}")

        elif args.command == "back":
            ok = client.press_back()
            print(f"back result: {ok}")

        elif args.command == "find":
            locator = _locator_from_args(args)
            nodes = client.find_nodes(**locator)
            for node in nodes:
                print(format_ui_tree(node))
