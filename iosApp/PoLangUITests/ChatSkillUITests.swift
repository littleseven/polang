import XCTest

/// 端侧 load_skill 冒烟（2026-11-08 iOS 接线，feat/ios-load-skill）：
/// chat 发技能请求 → RemoteChatEngine 跑轮前 syncIfStale（/v1/skills）→ 技能清单段注入
/// system prompt（AIAgent 重建键）→ LLM 调 load_skill → 技能指令进上下文。
///
/// UI 层弱断言（消息发送 + 处理期出现 + 截图存档）；强验证在宿主侧拉 syslog 三信号：
/// `SkillRepository: skill sync: N skills` / `Built Koog AIAgent: … skills=true` / `load_skill`。
/// 注意环境前置：需已登录（server_auth_token；未登录访客会因 server isLlmPath 未放行
/// /v1/skills 而 401 降级——该缺口登 docs/06-QA/2026-11-08-ios-follow-load-skill.md 待裁决）。
final class ChatSkillUITests: XCTestCase {

    var app: XCUIApplication!

    override func setUpWithError() throws {
        continueAfterFailure = false
        app = XCUIApplication()
        app.launch()
    }

    /// 技能请求冒烟：英文输入（typeText ASCII 稳定），技能名 html-card 直击技能匹配。
    func testLoadSkillSmoke() throws {
        let gallery = app.descendants(matching: .any)["gallery_grid"].firstMatch
        XCTAssertTrue(gallery.waitForExistence(timeout: 12), "初始页应为相册网格")
        app.buttons["tab_chat"].tap()
        sleep(1)
        // 新会话归零（XCUITest 套路）：历史轮次的 unknown_skill 结果会污染本轮判断
        //（模型看到曾加载失败会直接换路不走技能），先 chat_new 干净开局
        let newChat = app.buttons["chat_new"]
        if newChat.waitForExistence(timeout: 5) {
            newChat.tap()
            sleep(1)
        }

        let input = app.textFields["chat_input"].exists
            ? app.textFields["chat_input"]
            : app.textViews["chat_input"]
        XCTAssertTrue(input.waitForExistence(timeout: 12), "chat_input 未找到")
        input.tap()

        input.typeText("Make an album summary card using the html-card skill")
        let send = app.buttons["chat_send"]
        XCTAssertTrue(send.waitForExistence(timeout: 3), "chat_send 未找到")
        send.tap()

        // 固定窗口等完整 LLM 多轮（第三/四轮实证：count 超基线断言会被用户气泡立即满足、
        // send 按钮回归不可靠——canSend=有内容才渲染。终验以宿主 syslog 三信号为准，
        // 本测试职责 = 驱动真实 chat 轮次并留档截图）：首访含 skill 全量同步（多技能正文
        // 并发拉取）+ load_skill + 技能指令执行多轮，100s 宽松覆盖。
        sleep(100)

        let screenshot = XCUIScreen.main.screenshot()
        let attachment = XCTAttachment(screenshot: screenshot)
        attachment.name = "loadskill_smoke_final"
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}
