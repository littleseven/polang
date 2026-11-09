import XCTest

/// 端侧 load_skill 冒烟（2026-11-08 iOS 接线，feat/ios-load-skill）：
/// chat 发技能请求 → RemoteChatEngine 跑轮前 syncIfStale（/v1/skills）→ 技能清单段注入
/// system prompt（AIAgent 重建键）→ LLM 调 load_skill → 技能指令进上下文。
///
/// UI 层弱断言（消息发送 + 固定窗口 + 截图存档）；强验证在宿主侧拉 syslog 信号：
/// /v1/skills 请求（访客 X-Device-Id 或登录 X-App-Token）→ `Executing tool load_skill` →
/// 技能正文（非 unknown_skill）→ 技能规范执行产物。
/// 环境前置：server 0.9.44+ 已放行 /v1/skills 访客（未登录亦可跑 testLoadSkillSmoke）。
final class ChatSkillUITests: XCTestCase {

    /// 生产 account 表 test@polang.net 的 token_plain（Android debug 同源；邮箱验证码
    /// 流程无法全自动，登录态经 PoLangApp DEBUG 钩子 -serverAuthToken 注入）。
    private static let testAccountToken =
        "pl-62d9665becc9708b4d1d5dfe1e19e74388082a9f9c03d4f6ccd600097a731d37"

    var app: XCUIApplication!

    override func setUpWithError() throws {
        continueAfterFailure = false
        app = XCUIApplication()
        app.launch()
    }

    /// 访客态技能冒烟：英文输入（typeText ASCII 稳定），技能名 html-card 直击技能匹配。
    func testLoadSkillSmoke() throws {
        try driveSkillRequest()
    }

    /// 登录态技能冒烟（X-App-Token 鉴权路径）：-serverAuthToken 注入 test@polang.net
    /// 登录态后走同一链路——skill 同步带账号 token（server 0.9.44 前访客 401 的补充验证）。
    func testLoadSkillSmokeLoggedIn() throws {
        app.terminate()
        app.launchArguments = ["-serverAuthToken", Self.testAccountToken]
        app.launch()
        try driveSkillRequest()
    }

    // MARK: - 共用驱动（弱断言：驱动真实 chat 轮次 + 截图留档；终验靠宿主 syslog）

    private func driveSkillRequest() throws {
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

        // 固定窗口等完整 LLM 多轮（第三/四轮实证：count 超基线断言会被输入文本/用户气泡
        // 立即满足、send 按钮回归不可靠——canSend=有内容才渲染。终验以宿主 syslog 为准）：
        // 首访含 skill 全量同步（多技能正文并发拉取）+ load_skill + 技能指令执行多轮，
        // 100s 宽松覆盖。
        sleep(100)

        let screenshot = XCUIScreen.main.screenshot()
        let attachment = XCTAttachment(screenshot: screenshot)
        attachment.name = "loadskill_smoke_\(app.launchArguments.isEmpty ? "guest" : "loggedin")"
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}
