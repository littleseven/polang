import XCTest

/// 完整性闸门:iOS 侧 a11y 树 dump。把当前界面可访问元素 dump 成 JSON 打到 stdout
/// (标记 DUMP_JSON:::),由 completeness-check.sh 经 xcodebuild test 输出捕获 → refs/ios/。
/// 默认 dump 相机 idle 态;DUMP_STATE 环境变量传状态名(仅作标签)。
final class CompletenessDumpUITests: XCTestCase {

    var app: XCUIApplication!

    override func setUpWithError() throws {
        continueAfterFailure = false
        app = XCUIApplication()
        app.launchEnvironment["UITEST"] = "1"
        app.launch()
    }

    func testDumpCameraIdle() throws {
        // 进相机页（2026-09-16 导航统一：相机为 fullScreenCover 路由，底栏无相机项——
        // launch arg -openCamera 重启直弹相机 cover）
        app.terminate()
        app.launchArguments = ["-openCamera"]
        app.launch()
        XCTAssertTrue(
            app.descendants(matching: .any)["camera_preview"].firstMatch.waitForExistence(timeout: 15),
            "相机预览未出现"
        )
        sleep(2)  // 让 UI 稳定

        let nodes = collectNodes()
        let payload: [String: Any] = ["state": "idle", "nodes": nodes]
        let json = try JSONSerialization.data(withJSONObject: payload, options: [])
        let str = String(data: json, encoding: .utf8) ?? "{}"
        // 打到 stdout(设备测试 stdout 常不显示,作备份);同时写 Documents 供 devicectl 取回
        print("DUMP_JSON:::\(str):::END")
        let docs = try FileManager.default.url(for: .documentDirectory, in: .userDomainMask, appropriateFor: nil, create: true)
        try json.write(to: docs.appendingPathComponent("ios-a11y-idle.json"))
        XCTAssertTrue(nodes.count > 0, "未采集到任何 a11y 节点")
    }

    /// 仅截图 idle 态（Ardot 预览地面真值）：-openPanel none → 无面板启动，相机 idle。
    func testShotIdle() throws {
        app.terminate()
        app.launchArguments = ["-openPanel", "none", "-openCamera"]
        app.launch()
        XCTAssertTrue(
            app.descendants(matching: .any)["camera_preview"].firstMatch.waitForExistence(timeout: 15),
            "相机预览未出现(idle shot)"
        )
        sleep(2)
        let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        attachment.name = "ios-shot-idle"
        attachment.lifetime = .keepAlways
        add(attachment)
    }

    /// dump 美颜面板态(点 mat_autofix 开美颜面板 → FACE tab)
    func testDumpCameraBeautyFace() throws {
        // 相机 cover 路由（2026-09-16 导航统一）：-openCamera 重启直弹
        app.terminate()
        app.launchArguments = ["-openCamera"]
        app.launch()
        XCTAssertTrue(
            app.descendants(matching: .any)["camera_preview"].firstMatch.waitForExistence(timeout: 15),
            "相机预览未出现"
        )
        sleep(1)
        let beauty = app.buttons["mat_autofix"].firstMatch
        if beauty.waitForExistence(timeout: 5) { beauty.tap() }
        sleep(2)  // 等美颜面板展开
        let nodes = collectNodes()
        let payload: [String: Any] = ["state": "panel_beauty_face", "nodes": nodes]
        let json = try JSONSerialization.data(withJSONObject: payload, options: [])
        print("DUMP_JSON:::\(String(data: json, encoding: .utf8) ?? "{}"):::END")
        XCTAssertTrue(nodes.count > 0, "美颜面板未采集到节点")
    }

    /// 一次跑完 6 面板 dump + 截图:每面板经 -openPanel 启动参数重启(确定性)。
    /// 截图存 Documents/ios-shot-<state>.png(devicectl copy from 取回,供像素级视觉比对)。
    func testDumpAllPanels() throws {
        let panels: [(String, String)] = [
            ("beauty", "panel_beauty_face"),
            ("scene", "panel_scene"),   // 诊断:连续两轮挂在原第5位,前移验证是否位置相关
            ("pro", "panel_pro"),
            ("ratio", "panel_ratio"),
            ("grid", "panel_grid"),
            ("filter", "panel_filter"),
        ]
        for (arg, state) in panels {
            app.terminate()
            app.launchArguments = ["-openPanel", arg, "-openCamera"]
            app.launch()
            XCTAssertTrue(
                app.descendants(matching: .any)["camera_preview"].firstMatch.waitForExistence(timeout: 15),
                "相机预览未出现(\(state))"
            )
            sleep(2)
            let nodes = collectNodes()
            let payload: [String: Any] = ["state": state, "nodes": nodes]
            let json = try JSONSerialization.data(withJSONObject: payload, options: [])
            print("DUMP_JSON:::\(state):::\(String(data: json, encoding: .utf8) ?? "{}"):::END")
            // 截图经 XCTAttachment 落 xcresult(devicectl 取 runner Documents 不可行),再 xcresulttool 导出
            let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
            attachment.name = "ios-shot-\(state)"
            attachment.lifetime = .keepAlways
            add(attachment)
        }
    }

    /// 遍历 a11y 树,收集有 label 或 identifier 的元素。
    private func collectNodes() -> [[String: Any]] {
        var seen = Set<String>()
        var out: [[String: Any]] = []
        for el in app.descendants(matching: .any).allElementsBoundByIndex {
            let label = el.label ?? ""
            let id = el.identifier ?? ""
            let text = label.isEmpty ? id : label
            guard !text.isEmpty else { continue }
            let key = "\(text)|\(el.elementType.rawValue)"
            guard !seen.contains(key) else { continue }
            seen.insert(key)
            let f = el.frame
            out.append([
                "label": text, "role": roleString(el),
                "x": f.minX, "y": f.minY, "w": f.width, "h": f.height,
            ])
        }
        return out
    }

    private func roleString(_ el: XCUIElement) -> String {
        switch el.elementType {
        case .button: return "button"
        case .staticText: return "text"
        case .textField, .textView: return "input"
        case .slider: return "slider"
        default: return "generic"
        }
    }
}
