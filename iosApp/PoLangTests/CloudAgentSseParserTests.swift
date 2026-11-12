import XCTest
@testable import PoLang

// MARK: - CloudAgentSseParser 契约测试（① SSE 帧全解析）
//
// 语义对齐 Android CloudSseParser：done 帧 usage/elapsed_ms/lang_version 载荷、
// openui_delta 流式帧、未知 kind 容错忽略、tool_request 参数字符串化。

final class CloudAgentSseParserTests: XCTestCase {

    private func parse(_ json: String) -> CloudAgentFrame? {
        CloudAgentSseParser.parseBlock(lines: ["data: \(json)"])
    }

    func testDoneFrameCarriesPerformanceAndLangVersion() {
        let frame = parse("""
        {"kind":"done","session_status":"completed","elapsed_ms":1840,"lang_version":2,
         "usage":{"prompt_tokens":128,"completion_tokens":64}}
        """)
        guard case .done(let sessionStatus, let prompt, let completion, let elapsed, let langVersion) = frame else {
            return XCTFail("应为 done 帧: \(String(describing: frame))")
        }
        XCTAssertEqual(sessionStatus, "completed")
        XCTAssertEqual(prompt, 128)
        XCTAssertEqual(completion, 64)
        XCTAssertEqual(elapsed, 1840)
        XCTAssertEqual(langVersion, 2)
    }

    func testDoneFrameToleratesLegacyServerWithoutUsage() {
        // 旧服务端无 usage/elapsed_ms/lang_version = nil 容错（不崩不误判）
        let frame = parse(#"{"kind":"done","session_status":"completed"}"#)
        guard case .done(let sessionStatus, let prompt, let completion, let elapsed, let langVersion) = frame else {
            return XCTFail("应为 done 帧")
        }
        XCTAssertEqual(sessionStatus, "completed")
        XCTAssertNil(prompt)
        XCTAssertNil(completion)
        XCTAssertNil(elapsed)
        XCTAssertNil(langVersion)
    }

    func testOpenUiDeltaFrameParsesProgramPrefix() {
        guard case .openUiDelta(let program) = parse(#"{"kind":"openui_delta","program":"root = Card([Text(\"hi")}"#) else {
            return XCTFail("应为 openUiDelta 帧")
        }
        XCTAssertEqual(program, #"root = Card([Text("hi"#)
    }

    func testOpenUiDeltaEmptyProgramIgnored() {
        // 空前缀无意义直接忽略（对齐 Android）
        XCTAssertNil(parse(#"{"kind":"openui_delta","program":""}"#))
    }

    func testToolRequestArgsStringified() {
        // 数值参数字符串化（SKIE 契约形态 args 全 String）
        guard case .toolRequest(let requestId, let tool, let args, let timeoutMs) =
            parse(#"{"kind":"tool_request","request_id":"r1","tool":"search_media","args":{"q":"cats","limit":5},"timeout_ms":9000}"#)
        else { return XCTFail("应为 toolRequest 帧") }
        XCTAssertEqual(requestId, "r1")
        XCTAssertEqual(tool, "search_media")
        XCTAssertEqual(args["q"], "cats")
        XCTAssertEqual(args["limit"], "5")
        XCTAssertEqual(timeoutMs, 9000)
    }

    func testUnknownKindIgnoredAndEventParses() {
        XCTAssertNil(parse(#"{"kind":"future_kind","x":1}"#), "未知 kind 容错忽略")
        guard case .event(let event) = parse(#"{"kind":"event","event":{"type":"message","role":"assistant","payload":{"content":"hi"}}}"#) else {
            return XCTFail("应为 event 帧")
        }
        XCTAssertEqual(event["type"] as? String, "message")
    }

    func testUnknownFieldsIgnored() {
        // 双向兼容惯例：未知字段忽略不崩
        guard case .done = parse(#"{"kind":"done","session_status":"ok","future_field":{"a":1}}"#) else {
            return XCTFail("未知字段应被忽略")
        }
    }
}
