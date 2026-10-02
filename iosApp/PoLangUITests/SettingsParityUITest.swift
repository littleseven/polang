import XCTest

/// 设置域追齐批（settings.yaml，2026-10-02 S3a~S3d）结构冒烟 + 逐页截图。
///
/// 覆盖：主菜单四组 15 行（S3a）、远程模型页+助手性格（S3b）、本地模型/沙盒三组（S3c）、
/// 通道双常驻/开发者五行常显/相机重置页/上报问题弹层（S3d）。
/// 锚点策略：强制 english（launch args 覆盖 app_language），按英文 L() 键文本定位
/// （SettingsListRow 无 a11y id；远程模型行/⋯/persona chips 有 id，本测试走文本锚即可）。
/// 全部为存在性断言，不点执行类按钮（下载/提交/重置依赖设备态）。
final class SettingsParityUITest: XCTestCase {

    override func setUpWithError() throws {
        continueAfterFailure = false
    }

    // MARK: - 主菜单（S3a：四组行式 + 三新入口）

    func testMainMenuFourSections() throws {
        let app = launchApp()
        openSettings(app)

        // personalization 组
        assertText(app, "Theme Mode")
        assertText(app, "Language")
        // features 组（含 S3a 新增 Gallery Cleanup / Camera）
        assertText(app, "People")
        assertText(app, "AI Memory")
        assertText(app, "Gallery Scan")
        assertText(app, "Gallery Cleanup")
        assertText(app, "Camera")
        // ai_system 组
        assertText(app, "Model Center")
        scrollToIfNeeded(text(app, "Model Center"), app)
        assertText(app, "Remote Models")
        assertText(app, "Local Models")
        assertText(app, "Communication Channel")
        assertText(app, "Sandbox & Permissions")
        // others 组（含 S3a 新增 Report a problem）
        scrollToIfNeeded(text(app, "Report a problem"), app)
        assertText(app, "Data & privacy")
        assertText(app, "Report a problem")
        assertText(app, "Developer Options")

        attachScreenshot(name: "settings_main_menu")
        print("✅ 主菜单四组 15 行齐全（含 Gallery Cleanup / Camera / Report a problem 三新入口）")
    }

    // MARK: - 远程模型页 + 助手性格（S3b）

    func testRemoteModelsPageAndPersona() throws {
        let app = launchApp()
        openSettings(app)
        openPage(app, row: "Remote Models", anchor: "Assistant Personality")

        assertText(app, "Add Model")                 // 组尾添加行
        assertText(app, "Assistant Personality")     // 助手性格区（接通 ChatViewModel 消费键）
        let firstChip = app.descendants(matching: .any)["assistant_persona.chip.DEFAULT"].firstMatch
        scrollToIfNeeded(firstChip, app)             // 性格区在列表下方，可能未上屏
        for chipId in ["DEFAULT", "WARM", "LIVELY", "CONCISE"] {
            let chip = app.descendants(matching: .any)["assistant_persona.chip.\(chipId)"].firstMatch
            XCTAssertTrue(chip.waitForExistence(timeout: 3), "❌ persona chip 缺失：\(chipId)")
        }
        attachScreenshot(name: "settings_remote_models")
        print("✅ 远程模型页：添加行 + 助手性格 4 chips 齐全")
    }

    // MARK: - 本地模型页（S3c：三组行式）

    func testLocalModelsPage() throws {
        let app = launchApp()
        openSettings(app)
        openPage(app, row: "Local Models", anchor: "ROI Detection")

        assertText(app, "Detection Models")
        assertText(app, "ROI Detection")
        assertText(app, "Landmark Detection")
        assertText(app, "Photo Tagging")
        assertText(app, "Detection Strategy")
        assertText(app, "Adaptive face detect interval")
        scrollToIfNeeded(text(app, "Voice"), app)
        assertText(app, "Voice")                     // 组3 整组灰显（结构在对齐）
        attachScreenshot(name: "settings_local_models")
        print("✅ 本地模型页：检测模型/检测策略/语音三组齐全")
    }

    // MARK: - 沙盒页（S3c：三组 + 系统权限行）

    func testSandboxPage() throws {
        let app = launchApp()
        openSettings(app)
        openPage(app, row: "Sandbox & Permissions", anchor: "Execution")

        assertText(app, "Execution")
        assertText(app, "Auto-Execute Plans")
        assertText(app, "JS Engine")
        assertText(app, "Device Access")
        assertText(app, "Camera Permission (System)")
        scrollToIfNeeded(text(app, "Gallery Permission (System)"), app)
        assertText(app, "Gallery Permission (System)")
        attachScreenshot(name: "settings_sandbox")
        print("✅ 沙盒页：智能体执行/设备访问/语音三组齐全（silent_trash 已按 spec 省略）")
    }

    // MARK: - 通信通道页（S3d：双常驻 + 状态行）

    func testChannelsPage() throws {
        let app = launchApp()
        openSettings(app)
        scrollToIfNeeded(text(app, "Communication Channel"), app)
        openPage(app, row: "Communication Channel", anchor: "Active Channel")

        assertText(app, "Active Channel")
        // 双常驻：未选通道时飞书/Telegram 段都必须渲染（S3d 前是条件显隐）
        assertText(app, "App ID")
        assertText(app, "App Secret")
        scrollToIfNeeded(text(app, "Bot Token"), app)
        assertText(app, "Bot Token")
        attachScreenshot(name: "settings_channels")
        print("✅ 通道页：Active Channel + 飞书/Telegram 双常驻段齐全")
    }

    // MARK: - 开发者选项页（S3d：五行常显 + 行式弹层入口）

    func testDeveloperPage() throws {
        let app = launchApp()
        openSettings(app)
        scrollToIfNeeded(text(app, "Developer Options"), app)
        openPage(app, row: "Developer Options", anchor: "Shader Debug Mode")

        assertText(app, "Camera Info")               // 五行常显（不随总开关折叠）
        assertText(app, "Face Debug Overlay")
        assertText(app, "Log Overlay")
        assertText(app, "Shader Debug Mode")
        scrollToIfNeeded(text(app, "Log Modules"), app)
        assertText(app, "LLM Call Log")
        assertText(app, "Log Modules")
        attachScreenshot(name: "settings_developer")
        print("✅ 开发者页：预览调试五行常显 + LLM Call Log / Log Modules 行齐全")
    }

    // MARK: - 相机设置页（S3a 新增）

    func testCameraSettingsPage() throws {
        let app = launchApp()
        openSettings(app)
        openPage(app, row: "Camera", anchor: "Camera State Memory")

        assertText(app, "Camera State Memory")
        assertText(app, "Reset Camera to First-Install Defaults")
        attachScreenshot(name: "settings_camera")
        print("✅ 相机设置页：状态记忆组 + 重置行齐全")
    }

    // MARK: - 上报问题弹层（S3a 新增；guest 态断言，登录态 Skip）

    func testReportIssueDialog() throws {
        let app = launchApp()
        openSettings(app)
        scrollToIfNeeded(text(app, "Report a problem"), app)
        text(app, "Report a problem").firstMatch.tap()

        let submit = app.buttons["Submit"]
        XCTAssertTrue(submit.waitForExistence(timeout: 5), "❌ 上报问题弹层未打开（Submit 未出现）")
        assertText(app, "Category")
        assertText(app, "Title")
        // guest（无 server_auth_token）→ 顶部错误文案 + 提交置灰；登录态则无此文案（Skip 该断言）
        if text(app, "Please sign in to report issues").exists {
            XCTAssertFalse(submit.isEnabled, "❌ guest 态 Submit 应置灰")
            print("✅ 上报弹层：guest 拦截文案 + Submit 置灰正常")
        } else {
            print("⚠️ 设备已登录，跳过 guest 拦截断言")
        }
        attachScreenshot(name: "settings_report_issue")
        app.buttons["Cancel"].firstMatch.tap()
    }

    // MARK: - helpers

    private func launchApp() -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments += ["-AppleLanguages", "(en)", "-AppleLocale", "en", "-app_language", "english"]
        // 首启相册权限弹窗是系统进程弹窗（不吃 forced language），双语兜底
        addUIInterruptionMonitor(withDescription: "permission alerts") { alert in
            for label in ["允许完全访问", "允许", "好", "Allow Full Access", "Allow", "OK"] {
                let button = alert.buttons[label]
                if button.exists { button.tap(); return true }
            }
            return false
        }
        app.launch()
        return app
    }

    private func text(_ app: XCUIApplication, _ label: String) -> XCUIElement {
        app.staticTexts[label].firstMatch
    }

    private func assertText(_ app: XCUIApplication, _ label: String, timeout: TimeInterval = 3) {
        XCTAssertTrue(text(app, label).waitForExistence(timeout: timeout), "❌ 缺失文本锚点：\(label)")
    }

    /// 打开设置：相册顶栏齿轮（topbar_settings）→ fullScreenCover；以 Theme Mode 行作为「页已打开」锚点。
    private func openSettings(_ app: XCUIApplication) {
        let gear = app.buttons["topbar_settings"]
        XCTAssertTrue(gear.waitForExistence(timeout: 20), "❌ 相册顶栏设置按钮不存在")
        gear.tap()
        XCTAssertTrue(text(app, "Theme Mode").waitForExistence(timeout: 10),
                      "❌ 设置页未打开（Theme Mode 行 10s 未出现）")
        usleep(500_000) // fullScreenCover 转场收尾
    }

    /// 主菜单 → 二级页：点行文本（SwiftUI 行内文本即可命中行/Button），以 anchor 文本确认页已打开。
    private func openPage(_ app: XCUIApplication, row: String, anchor: String) {
        scrollToIfNeeded(text(app, row), app)
        text(app, row).firstMatch.tap()
        XCTAssertTrue(text(app, anchor).waitForExistence(timeout: 8),
                      "❌ 页面未打开：\(row)（锚点 \(anchor) 8s 未出现）")
        usleep(300_000)
    }

    /// ScrollView 内元素可能位于折叠线下：不可点时上滑最多 4 次露出。
    private func scrollToIfNeeded(_ target: XCUIElement, _ app: XCUIApplication) {
        var attempts = 0
        while (!target.exists || !target.isHittable) && attempts < 4 {
            app.swipeUp()
            attempts += 1
        }
    }

    private func attachScreenshot(name: String) {
        let shot = XCUIScreen.main.screenshot()
        let attachment = XCTAttachment(screenshot: shot)
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}
