import XCTest

/// 相机+相册屏 UI 自动化回归（替代手工测试循环）
///
/// 真值通道：app 内 DebugOverlay 画屏状态（camera.shutter: capturing/saved/error）
/// 覆盖：滑页切换 / 悬浮Tab导航 / 快门拍照保存 / 相册入口
/// 运行：xcodebuild test -workspace PoLang.xcworkspace -scheme PoLang \
///        -destination 'id=<UDID>' -only-testing:PoLangUITests
final class PoLangUITests: XCTestCase {

    private var app: XCUIApplication!

    override func setUpWithError() throws {
        continueAfterFailure = false
        app = XCUIApplication()
        app.launchArguments = ["-uitest"]
        // 系统权限弹窗（相册添加/相机）自动允许
        addUIInterruptionMonitor(withDescription: "permission alerts") { alert in
            for label in ["Allow Full Access", "Allow", "OK", "允许完全访问", "允许", "好"] {
                let button = alert.buttons[label]
                if button.exists { button.tap(); return true }
            }
            return false
        }
        app.launch()
    }

    override func tearDownWithError() throws {
        app = nil
    }

    // MARK: - 用例 1：左右滑切页（对标 Android HorizontalPager）

    func testSwipeBetweenPages() throws {
        try requireElement("gallery_grid", timeout: 10, "初始页应为相册网格")

        // 相册 → 左滑 → 整理页（page 1）
        app.swipeLeft()
        try requireElement("organize_segment_organize", timeout: 12, "相册左滑应到整理页")
        attachScreenshot(name: "swipe_organize")

        // 整理 → 左滑 → Chat 页（page 2）
        app.swipeLeft()
        try requireElement("chat_input", timeout: 12, "整理左滑应到 Chat 页")
        attachScreenshot(name: "swipe_chat")

        // Chat → 右滑两次 → 回相册（页序 Gallery(0)/Organize(1)/Chat(2)/Person(3)/Memories(4)；
        // 相机已移出 Pager 改 fullScreenCover 路由，不在滑动序列中）
        app.swipeRight()
        app.swipeRight()
        try requireElement("gallery_grid", timeout: 12, "右滑两次应回相册页")
    }

    // MARK: - 用例 2：人物页悬浮 Tab 可跳出（回归：早期 chat 占位页点 Tab 无反应）

    func testTabNavigationFromPlaceholder() throws {
        try requireElement("gallery_grid", timeout: 12, "初始页应为相册网格")

        // 到人物页（page 3，真实人物页非占位），点回忆 Tab 必须能跳到回忆页
        app.swipeLeft()
        app.swipeLeft()
        app.swipeLeft()
        try requireElement("person_root", timeout: 12, "应在人物页")
        app.buttons["tab_memories"].tap()
        try requireElement("memories_root", timeout: 12, "人物页点回忆 Tab 应跳回忆页")

        // 回忆页点聊天 Tab 验证跳转到真实 Chat 页（聊天页沉浸式不渲染底 bar）
        app.buttons["tab_chat"].tap()
        try requireElement("chat_input", timeout: 12, "点 chat Tab 应到 Chat 页")
    }

    // MARK: - 用例 3：快门拍照 → 保存成功（真值：DebugOverlay camera.shutter）

    func testShutterCaptureSaves() throws {
        try navigateToCamera()

        element("camera_shutter").tap()

        // 展开 DebugOverlay 详情（点摘要行）
        let summary = app.staticTexts["debug_summary"]
        if summary.waitForExistence(timeout: 3) { summary.tap() }

        // 等待 camera.shutter 终态（saved 或 error），最多 12s
        let entry = app.staticTexts["debug_entry_camera.shutter"]
        XCTAssertTrue(entry.waitForExistence(timeout: 12), "camera.shutter 状态未上屏——快门链路未触发")
        // 等状态离开 capturing
        let savedPredicate = NSPredicate(format: "label CONTAINS[c] 'saved'")
        let errorPredicate = NSPredicate(format: "label CONTAINS[c] 'error'")
        let deadline = Date().addingTimeInterval(12)
        var finalLabel = entry.label
        while Date() < deadline {
            finalLabel = entry.label
            if savedPredicate.evaluate(with: entry) || errorPredicate.evaluate(with: entry) { break }
            usleep(300_000)
        }
        attachScreenshot(name: "shutter_result")
        XCTAssertTrue(savedPredicate.evaluate(with: entry),
                      "拍照未保存成功，终态: \(finalLabel)")
    }

    // MARK: - 用例 4：相机页相册入口（缩略图显示 + 点击进相册）

    func testGalleryEntryFromCamera() throws {
        try navigateToCamera()

        let thumb = element("camera_gallery_thumb")
        attachScreenshot(name: "camera_gallery_thumb")

        thumb.tap()
        try requireElement("gallery_grid", timeout: 12, "点相册入口应切到相册页")
        usleep(800_000)
        attachScreenshot(name: "gallery_after_entry") // 左上角最新格可肉眼核对拍照方向
    }

    // MARK: - helpers

    private func navigateToCamera(file: StaticString = #filePath, line: UInt = #line) throws {
        // 2026-09-16 导航统一：相机移出 Pager 改 fullScreenCover（无滑动/底栏入口），
        // 自动化经 launch arg `-openCamera` 重启直弹相机 cover
        app.terminate()
        app.launchArguments = ["-uitest", "-openCamera"]
        app.launch()
        try requireElement("camera_preview", timeout: 12, "应到相机页", file: file, line: line)
        // camera_preview 根视图在授权回调前就存在——必须等快门出现才算控件就绪
        // 等待前先存档：若快门缺席，截图直接显示当时屏幕真容（权限弹窗/权限页/控件未渲染）
        usleep(1_000_000)
        attachScreenshot(name: "navigate_to_camera")
        let shutter = app.descendants(matching: .any)["camera_shutter"]
        if !shutter.waitForExistence(timeout: 8) {
            // 控件肉眼可见但 a11y 树查不到——dump 无障碍树定位暴露层断点
            print("A11Y-DUMP-BEGIN")
            print(app.debugDescription.prefix(12000))
            print("A11Y-DUMP-END")
            XCTFail("相机控件未就绪（授权异步）", file: file, line: line)
        }
    }

    private func element(_ id: String) -> XCUIElement {
        app.descendants(matching: .any)[id].firstMatch
    }

    private func requireElement(_ id: String, timeout: TimeInterval, _ message: String,
                                file: StaticString = #filePath, line: UInt = #line) throws {
        let query = app.descendants(matching: .any)[id]
        XCTAssertTrue(query.waitForExistence(timeout: timeout), message, file: file, line: line)
    }

    private func attachScreenshot(name: String) {
        let shot = XCUIScreen.main.screenshot()
        let attachment = XCTAttachment(screenshot: shot)
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}
