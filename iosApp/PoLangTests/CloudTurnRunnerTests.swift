import XCTest
import SharedKit
@testable import PoLang

// MARK: - CloudTurnEventMapper 契约测试（② 程序嗅探 + 事件映射 + done 性能装配）

final class CloudTurnRunnerTests: XCTestCase {

    // ── 程序嗅探（message 帧 content 分派） ──

    func testAssistantProgramContentSniffsToOpenUiCard() {
        let program = "// summary: 上周共 327 张照片\nroot = Card([Text(\"hi\")])"
        let events = CloudTurnEventMapper.mapEvent([
            "type": "message", "role": "assistant",
            "payload": ["content": program],
        ])
        guard case .openUiCard(let gotProgram, let fallbackSummary) = events.first else {
            return XCTFail("程序 content 应嗅探为 OpenUiCard: \(events)")
        }
        XCTAssertEqual(gotProgram, program)
        XCTAssertEqual(fallbackSummary, "上周共 327 张照片")
    }

    func testAssistantPlainTextFallsBackToAssistantText() {
        // 防跨线污染：LLM 模仿历史把程序当文本输出的门判由嗅探兜住；纯文本正常走 AssistantText
        let events = CloudTurnEventMapper.mapEvent([
            "type": "message", "role": "assistant",
            "payload": ["content": "今天天气不错，root 这个词出现在句子里但不是声明"],
        ])
        guard case .assistantText(let text) = events.first else {
            return XCTFail("纯文本应降级 AssistantText: \(events)")
        }
        XCTAssertTrue(text.contains("今天天气不错"))
    }

    func testUserEchoMessageSkipped() {
        XCTAssertTrue(CloudTurnEventMapper.mapEvent([
            "type": "message", "role": "user",
            "payload": ["content": "hi"],
        ]).isEmpty, "user 回声跳过（本地已先上屏）")
    }

    // ── html_card / render_ui_openui action 帧 ──

    func testHtmlCardEventParses() {
        let events = CloudTurnEventMapper.mapEvent([
            "type": "html_card", "payload": ["html": "<b>x</b>", "summary": "一段摘要"],
        ])
        guard case .htmlCard(let html, let summary) = events.first else {
            return XCTFail("应为 htmlCard: \(events)")
        }
        XCTAssertEqual(html, "<b>x</b>")
        XCTAssertEqual(summary, "一段摘要")
    }

    func testEmptyHtmlCardNotEmitted() {
        XCTAssertTrue(CloudTurnEventMapper.mapEvent([
            "type": "html_card", "payload": ["html": ""],
        ]).isEmpty, "html 为空不上屏空卡")
    }

    func testRenderUiOpenuiActionFrameEmitsCard() {
        let argsJson = #"{"program":"root = Card([Text(\"x\")])","fallbackSummary":"x"}"#
        let events = CloudTurnEventMapper.mapEvent([
            "type": "action", "id": 7,
            "payload": ["tool": "render_ui_openui", "argsJson": argsJson],
        ])
        guard case .openUiCard(let program, let fallbackSummary) = events.first else {
            return XCTFail("render_ui_openui action 应发卡: \(events)")
        }
        XCTAssertEqual(program, #"root = Card([Text("x")])"#)
        XCTAssertEqual(fallbackSummary, "x")
    }

    func testMalformedActionFrameIgnored() {
        // 坏帧（argsJson 非合法 JSON）记日志忽略，不拖垮整回合
        XCTAssertTrue(CloudTurnEventMapper.mapEvent([
            "type": "action", "id": 7,
            "payload": ["tool": "render_ui_openui", "argsJson": "{broken"],
        ]).isEmpty)
        // 其它工具 action 帧不出卡
        XCTAssertTrue(CloudTurnEventMapper.mapEvent([
            "type": "action", "id": 8,
            "payload": ["tool": "search_media", "argsJson": "{}"],
        ]).isEmpty)
    }

    // ── 帧级映射 ──

    func testOpenUiDeltaFrameMapsToDeltaEvent() {
        guard case .openUiCardDelta(let program) =
            CloudTurnEventMapper.map(frame: .openUiDelta(program: "root = Card")).first else {
            return XCTFail("openUiDelta 帧应映射 delta 事件")
        }
        XCTAssertEqual(program, "root = Card")
    }

    func testToolRequestFrameProducesNoEvent() {
        // 工具执行由宿主直驱（端执行 + 上行），不经事件流
        XCTAssertTrue(CloudTurnEventMapper.map(
            frame: .toolRequest(requestId: "r1", tool: "search_media", args: [:], timeoutMs: 1000)).isEmpty)
    }

    // ── done 性能装配（对齐 Android toLlmPerformanceOrNull 映射） ──

    func testDonePerformanceMapping() {
        let perf = CloudTurnEventMapper.performance(promptTokens: 100, completionTokens: 50, elapsedMs: 2000)
        XCTAssertEqual(perf?.promptLen, 100)
        XCTAssertEqual(perf?.decodeLen, 50)
        XCTAssertEqual(perf?.prefillTimeMs, 0, "prefill=0 隐藏（DeepSeek 先例映射）")
        XCTAssertEqual(perf?.decodeTimeMs, 2000, "decode=回合端到端耗时")
        XCTAssertEqual(perf!.decodeSpeed, 25.0, accuracy: 0.01, "speed=completion/耗时*1000")
    }

    func testDonePerformanceNilWhenNoUsage() {
        // 旧服务端无 usage（tokens 双 0/nil）→ nil，不装配全 0 指标行
        XCTAssertNil(CloudTurnEventMapper.performance(promptTokens: nil, completionTokens: nil, elapsedMs: nil))
        XCTAssertNil(CloudTurnEventMapper.performance(promptTokens: 0, completionTokens: 0, elapsedMs: 1200))
    }
}
