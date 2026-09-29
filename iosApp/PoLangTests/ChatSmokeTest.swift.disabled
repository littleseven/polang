import XCTest
@testable import PoLang
import SharedKit

/// Phase 6.2 T0/T7 真机冒烟测试
///
/// 直接调 ChatAgentBridge 验证 Koog/Ktor Darwin 引擎在 iOS 真机端到端可用。
final class ChatSmokeTest: XCTestCase {

    /// T0 冒烟：发送 "pong"，验证远程推理全链路
    func testKoogSmoke_PongReceivesReply() async throws {
        let bridge = try makeBridge()

        let expectation = XCTestExpectation(description: "Receive chat reply")
        var receivedText = ""
        var receivedError: String?

        bridge.sendMessage(
            input: "Reply with exactly: pong",
            persona: "DEFAULT", replyLanguage: "SIMPLIFIED_CHINESE",
            onText: { snapshot in
                receivedText = snapshot
                print("📡 onText snapshot: \(snapshot.prefix(100))")
            },
            onToolCall: { print("🔧 onToolCall") },
            onComplete: { summary, errorMessage in
                print("✅ onComplete: summary=\(summary.prefix(100)), error=\(errorMessage ?? "nil")")
                receivedText = summary
                receivedError = errorMessage
                expectation.fulfill()
            }
        )

        await fulfillment(of: [expectation], timeout: 60)

        XCTAssertNil(receivedError, "Should not receive error: \(receivedError ?? "")")
        XCTAssertFalse(receivedText.isEmpty, "Should receive non-empty reply")
        print("🎯 T0 SMOKE PASSED: received reply '\(receivedText.prefix(50))'")
    }

    /// T7 盘点链路：「相册有多少照片」→ get_gallery_summary 工具调用
    func testT7_GallerySummary() async throws {
        let bridge = try makeBridge()

        // 切场景到 CHAT（正常由 Tab 切换触发）
        let orchestrator = AgentOrchestrator.companion.getInstance()
        orchestrator.transitionToScene(
            scene: SceneManager.Scene.chat, saveToHistory: false)

        let expectation = XCTestExpectation(description: "Gallery summary")
        var summary = ""
        var error: String?

        bridge.sendMessage(
            input: "我的相册里有多少张照片？",
            persona: "DEFAULT", replyLanguage: "SIMPLIFIED_CHINESE",
            onText: { _ in },
            onToolCall: { print("🔧 tool call in gallery test") },
            onComplete: { result, err in
                summary = result
                error = err
                expectation.fulfill()
            }
        )

        await fulfillment(of: [expectation], timeout: 60)

        XCTAssertNil(error, "Should not error: \(error ?? "")")
        XCTAssertFalse(summary.isEmpty, "Should get gallery summary")
        print("🎯 T7 Gallery Summary: '\(summary.prefix(80))'")
    }

    // MARK: - Helpers

    /// 确保 AppContainer 已初始化（触发 IosAgentComposition.initialize），返回 chatBridge
    private func makeBridge() throws -> ChatAgentBridge {
        _ = AppContainer.shared  // 触发 @MainActor init → setupAgentComposition
        return try XCTUnwrap(
            IosAgentComposition.shared.chatBridge,
            "ChatAgentBridge should be initialized"
        )
    }
}
