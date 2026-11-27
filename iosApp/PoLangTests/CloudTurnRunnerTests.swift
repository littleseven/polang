import XCTest
import SharedKit
@testable import PoLang

// MARK: - AgUiCloudTurnTranslator 契约测试（AG-UI v2，2026-11-28 M3）
//
// 全管线：SSE block 文本 → shared AgUiSseParser（SharedKit 导出验证）→ 翻译器 →
// CloudTurnEvent / 设备工具桥回调。语义对位 Android CloudAgentTurnRunnerAgUiTest
// （iOS 子集：无步骤时间线，TOOL_CALL_RESULT 不上屏）。

final class CloudTurnRunnerTests: XCTestCase {

    private struct TurnOutput {
        var events: [CloudTurnEvent] = []
        var toolRequests: [(requestId: String, tool: String, args: [String: String])] = []
    }

    /// SSE block 序列（不含块间空行）逐块过 shared parser + 翻译器。
    private func translate(_ blocks: [String]) -> TurnOutput {
        let translator = AgUiCloudTurnTranslator()
        var output = TurnOutput()
        for block in blocks {
            guard let frame = AgUiSseParser.shared.parseBlock(block: block) else { continue }
            translator.onFrame(
                frame,
                onEvent: { output.events.append($0) },
                onToolRequest: { output.toolRequests.append(($0, $1, $2)) }
            )
        }
        return output
    }

    private func textBlock(_ messageId: String, _ role: String, _ chunks: [String]) -> [String] {
        var blocks = [
            #"data: {"type":"TEXT_MESSAGE_START","messageId":"\#(messageId)","role":"\#(role)"}"#,
        ]
        for chunk in chunks {
            let escaped = chunk
                .replacingOccurrences(of: "\\", with: "\\\\")
                .replacingOccurrences(of: "\"", with: "\\\"")
                .replacingOccurrences(of: "\n", with: "\\n")
            blocks.append(#"data: {"type":"TEXT_MESSAGE_CONTENT","messageId":"\#(messageId)","delta":"\#(escaped)"}"#)
        }
        blocks.append(#"data: {"type":"TEXT_MESSAGE_END","messageId":"\#(messageId)"}"#)
        return blocks
    }

    private let runFinished = #"data: {"type":"RUN_FINISHED","threadId":"s_1","runId":"run-abc"}"#

    func testAssistantTextBlockAggregates() {
        let out = translate(
            [#"data: {"type":"RUN_STARTED","threadId":"s_1","runId":"run-abc"}"#]
                + textBlock("m1", "assistant", ["你有 42 张照片"])
                + [runFinished]
        )
        XCTAssertEqual(out.events, [
            .assistantText("你有 42 张照片"),
            .done(promptTokens: nil, completionTokens: nil, elapsedMs: nil, langVersion: nil),
        ])
        XCTAssertTrue(out.toolRequests.isEmpty)
    }

    func testUserRoleBlockSkipped() {
        let out = translate(textBlock("u1", "user", ["hi"]) + [runFinished])
        XCTAssertEqual(out.events, [
            .done(promptTokens: nil, completionTokens: nil, elapsedMs: nil, langVersion: nil),
        ])
    }

    func testStreamedProgramChunksEmitDeltaThenCard() {
        let program = "// summary: 统计\nroot = Card([Text(\"hi\")])"
        let out = translate(
            textBlock("stream-1", "assistant", ["// summary: 统计\nroot = Card([", "Text(\"hi\")])"])
                + [runFinished]
        )
        XCTAssertEqual(out.events, [
            .openUiCardDelta(program: program),
            .openUiCard(program: program, fallbackSummary: "统计"),
            .done(promptTokens: nil, completionTokens: nil, elapsedMs: nil, langVersion: nil),
        ])
    }

    func testSingleChunkProgramEmitsCardWithoutDelta() {
        let program = "// summary: 统计\nroot = Card([Text(\"hi\")])"
        let out = translate(textBlock("m1", "assistant", [program]) + [runFinished])
        XCTAssertEqual(out.events.first, .openUiCard(program: program, fallbackSummary: "统计"))
        XCTAssertFalse(out.events.contains { if case .openUiCardDelta = $0 { return true }; return false })
    }

    func testMultiChunkPlainTextDoesNotEmitProgramDelta() {
        let out = translate(textBlock("m1", "assistant", ["你好，", "这是回答。"]) + [runFinished])
        XCTAssertEqual(out.events.first, .assistantText("你好，这是回答。"))
    }

    func testRenderUiOpenUiToolArgsEmitCard() {
        let out = translate([
            #"data: {"type":"TOOL_CALL_START","toolCallId":"act-4","toolCallName":"render_ui_openui"}"#,
            #"data: {"type":"TOOL_CALL_ARGS","toolCallId":"act-4","delta":"{\"program\":\"root = Card([])\",\"fallbackSummary\":\"327 张\"}"}"#,
            #"data: {"type":"TOOL_CALL_END","toolCallId":"act-4"}"#,
            runFinished,
        ])
        XCTAssertEqual(out.events.first, .openUiCard(program: "root = Card([])", fallbackSummary: "327 张"))
    }

    func testMalformedToolArgsTolerated() {
        let out = translate([
            #"data: {"type":"TOOL_CALL_START","toolCallId":"act-5","toolCallName":"render_ui_openui"}"#,
            #"data: {"type":"TOOL_CALL_ARGS","toolCallId":"act-5","delta":"not-json"}"#,
            #"data: {"type":"TOOL_CALL_END","toolCallId":"act-5"}"#,
            runFinished,
        ])
        XCTAssertEqual(out.events, [
            .done(promptTokens: nil, completionTokens: nil, elapsedMs: nil, langVersion: nil),
        ])
    }

    func testToolCallResultProducesNoEvent() {
        // iOS 无步骤时间线（M1 登记缺口）：observation 对应的 TOOL_CALL_RESULT 不上屏
        let out = translate([
            #"data: {"type":"TOOL_CALL_RESULT","messageId":"act-11-result","toolCallId":"act-11","content":"找到 3 张"}"#,
            runFinished,
        ])
        XCTAssertEqual(out.events, [
            .done(promptTokens: nil, completionTokens: nil, elapsedMs: nil, langVersion: nil),
        ])
    }

    func testDeviceToolRequestCustomFrameRoutedToBridge() {
        let out = translate([
            #"data: {"type":"CUSTOM","name":"device_tool_request","value":{"request_id":"tr_1","tool":"search_media","args":{"query":"猫","limit":5},"timeout_ms":120000}}"#,
            runFinished,
        ])
        XCTAssertEqual(out.toolRequests.count, 1)
        XCTAssertEqual(out.toolRequests.first?.requestId, "tr_1")
        XCTAssertEqual(out.toolRequests.first?.tool, "search_media")
        XCTAssertEqual(out.toolRequests.first?.args["query"], "猫")
        // 数值参数字符串化（v1 tool_request 同口径）
        XCTAssertEqual(out.toolRequests.first?.args["limit"], "5")
    }

    func testUnknownCustomNameIgnored() {
        let out = translate([
            #"data: {"type":"CUSTOM","name":"future_custom","value":{"x":1}}"#,
            runFinished,
        ])
        XCTAssertTrue(out.toolRequests.isEmpty)
    }

    func testRunFinishedMetadataMapsToDone() {
        let out = translate([
            #"data: {"type":"RUN_FINISHED","threadId":"s_1","runId":"run-abc","metadata":{"prompt_tokens":1280,"completion_tokens":96,"elapsed_ms":8432,"openui_lang_version":2}}"#,
        ])
        XCTAssertEqual(out.events, [
            .done(promptTokens: 1280, completionTokens: 96, elapsedMs: 8432, langVersion: 2),
        ])
    }

    func testRunErrorMapsToErrorEvent() {
        let out = translate([
            #"data: {"type":"RUN_ERROR","message":"quota exhausted","code":"internal_error"}"#,
        ])
        XCTAssertEqual(out.events, [.error("quota exhausted")])
    }

    func testUnknownTypeTolerated() {
        // 服务端后续新增事件族旧端零感知（shared parser 容错 nil）
        let out = translate([
            #"data: {"type":"STEP_STARTED","name":"future"}"#,
            runFinished,
        ])
        XCTAssertEqual(out.events, [
            .done(promptTokens: nil, completionTokens: nil, elapsedMs: nil, langVersion: nil),
        ])
    }

    // MARK: - done 性能装配（沿用 v1 CloudTurnEventMapper 口径）

    func testPerformanceMapping() {
        let perf = AgUiCloudTurnTranslator.performance(promptTokens: 100, completionTokens: 50, elapsedMs: 2000)
        XCTAssertEqual(perf?.promptLen, 100)
        XCTAssertEqual(perf?.decodeLen, 50)
        XCTAssertEqual(perf?.decodeTimeMs, 2000)
        XCTAssertEqual(perf?.decodeSpeed ?? 0, 25, accuracy: 0.001)
        XCTAssertNil(AgUiCloudTurnTranslator.performance(promptTokens: nil, completionTokens: nil, elapsedMs: nil))
        XCTAssertNil(AgUiCloudTurnTranslator.performance(promptTokens: 0, completionTokens: 0, elapsedMs: 1200))
    }
}
