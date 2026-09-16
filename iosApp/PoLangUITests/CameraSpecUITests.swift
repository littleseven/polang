import XCTest

/// 规格书驱动的相机屏 UITest（camera.yaml 验收点翻译）
///
/// 覆盖范围（映射到 camera.yaml 章节号）：
/// - §3/§5/§7 核心控件存在性
/// - §7 变焦预设切换（zoom_presets）
/// - §7 镜头翻转（flip_camera）
/// - §8 美颜面板开合与 Tab 切换（beauty_panel）
/// - §9 滤镜面板开合与选择（filter_panel）
/// - §5 对焦手势（focus_ring）
/// - §7 模式选择器（mode_selector）
///
/// 真值通道：app 内 DebugOverlay（camera.shutter / camera.fps 等）
/// 约束：不改 App 源码；标识符缺失项记入 gap 清单
final class CameraSpecUITests: XCTestCase {

    private var app: XCUIApplication!

    override func setUpWithError() throws {
        continueAfterFailure = false
        app = XCUIApplication()
        app.launchArguments = ["-uitest", "-openCamera"]
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

    // MARK: - §3/§5/§7 核心控件存在性

    /// camera.yaml §3（顶部控件组 back/reset/beauty/filter）
    /// camera.yaml §5（预览区 camera_surface）
    /// camera.yaml §7（底部控件区 zoom_presets/shutter_row/gallery_thumbnail/flip_camera）
    /// 验证已实现的核心控件在相机页就绪后全部存在于无障碍树。
    func testCameraCoreControlsExist() throws {
        try navigateToCamera()

        // §5 预览层（MetalViewRepresentable 叶子标识符）
        try requireElement("camera_preview", timeout: 12, "预览层应存在")

        // §7 快门按钮
        try requireElement("camera_shutter", timeout: 3, "快门按钮应存在")

        // §7 变焦预设条容器
        try requireElement("camera_zoom_bar", timeout: 3, "变焦条应存在")

        // §7 相册缩略图入口
        try requireElement("camera_gallery_thumb", timeout: 3, "相册缩略图入口应存在")

        // §7 镜头翻转按钮
        try requireElement("camera_flip", timeout: 3, "镜头翻转按钮应存在")

        // §3/§8 顶部右侧美颜入口（CircleIconButton → MatIcon label: "mat_autofix"）
        try requireElement("mat_autofix", timeout: 3, "美颜入口按钮应存在")

        // §3/§9 顶部右侧滤镜入口（CircleIconButton → MatIcon label: "mat_filter_b_and_w"）
        try requireElement("mat_filter_b_and_w", timeout: 3, "滤镜入口按钮应存在")

        attachScreenshot(name: "camera_core_controls")
    }

    // MARK: - §7 变焦预设切换

    /// camera.yaml §7 zoom_presets：0.6x / 1x / 2x / 3.2x 预设按钮
    /// 验证预设按钮可点击切换且不崩溃。
    func testZoomPresetSwitching() throws {
        try navigateToCamera()
        try requireElement("camera_zoom_bar", timeout: 3, "变焦条应存在")

        // 变焦预设按钮通过 Text label 查询（父 HStack 的 accessibilityIdentifier 传播到子 Button，
        // 但 Button 的 accessibilityLabel 仍为 Text 内容 "2x" / "1x"）
        let button2x = app.buttons["2x"]
        XCTAssertTrue(button2x.waitForExistence(timeout: 3), "2x 变焦按钮应存在")
        button2x.tap()
        usleep(500_000)
        attachScreenshot(name: "zoom_2x")

        // 回到 1x
        let button1x = app.buttons["1x"]
        XCTAssertTrue(button1x.waitForExistence(timeout: 3), "1x 变焦按钮应存在")
        button1x.tap()
        usleep(500_000)
        attachScreenshot(name: "zoom_1x_restored")

        // 变焦条仍存在
        try requireElement("camera_zoom_bar", timeout: 3, "切换后变焦条应仍存在")
    }

    // MARK: - §7 镜头翻转

    /// camera.yaml §7 flip_camera：翻转摄像头按钮存在且可点击
    func testFlipCameraToggle() throws {
        try navigateToCamera()

        let flip = element("camera_flip")
        XCTAssertTrue(flip.waitForExistence(timeout: 3), "翻转按钮应存在")

        flip.tap()
        usleep(1_500_000) // 等待翻转动画完成
        attachScreenshot(name: "flip_camera")

        // 翻转后预览层仍存在
        try requireElement("camera_preview", timeout: 12, "翻转后预览层应仍存在")
    }

    // MARK: - §8 美颜面板开合与 Tab 切换

    /// camera.yaml §8 beauty_panel：
    /// - 美颜入口按钮触发面板开/关
    /// - 面板含 4 条 FACE Tab 滑杆
    /// - 底部 Tab 栏可切换 FACE / MAKEUP
    func testBeautyPanelOpenAndClose() throws {
        try navigateToCamera()

        // 打开美颜面板
        let beautyButton = app.buttons["mat_autofix"]
        XCTAssertTrue(beautyButton.waitForExistence(timeout: 3), "美颜入口按钮应存在")
        beautyButton.tap()
        usleep(800_000) // 等待面板滑入动画
        attachScreenshot(name: "beauty_panel_open")

        // §8 face_tab_sliders：面板内应有滑杆（磨皮/美白/瘦脸/大眼 4 条）
        let sliders = app.sliders
        XCTAssertTrue(sliders.firstMatch.waitForExistence(timeout: 3), "美颜面板应包含滑杆")
        XCTAssertTrue(sliders.count >= 4, "美颜面板应至少 4 条滑杆，实际 \(sliders.count)")

        // §8 tab_bar：FACE Tab 按钮（MatIcon label: "mat_face"）
        let faceTab = app.buttons["mat_face"]
        XCTAssertTrue(faceTab.waitForExistence(timeout: 3), "FACE Tab 按钮应存在")

        // §8 tab_bar：MAKEUP Tab 按钮（MatIcon label: "mat_color_lens"）
        let makeupTab = app.buttons["mat_color_lens"]
        XCTAssertTrue(makeupTab.waitForExistence(timeout: 3), "MAKEUP Tab 按钮应存在")

        // 切换到 MAKEUP Tab
        makeupTab.tap()
        usleep(500_000)
        attachScreenshot(name: "beauty_makeup_tab")

        // 切回 FACE Tab
        faceTab.tap()
        usleep(500_000)

        // 关闭面板：再点美颜入口
        beautyButton.tap()
        usleep(800_000)
        attachScreenshot(name: "beauty_panel_closed")

        // 滑杆应消失
        XCTAssertFalse(sliders.firstMatch.waitForExistence(timeout: 2), "面板关闭后滑杆应消失")
    }

    // MARK: - §9 滤镜面板开合与选择

    /// camera.yaml §9 filter_panel：
    /// - 滤镜入口按钮触发面板打开
    /// - 5 列网格含色调滤镜（9 款）+ 风格滤镜占位（5 款）
    /// - 点击滤镜项可选中
    ///
    /// 注：滤镜面板（53% 屏高）打开后覆盖了入口按钮（位于 37.6% 屏高），
    /// 导致无法通过二次点击关闭面板。close 路径的测试见 gap 清单。
    func testFilterPanelOpenAndSelection() throws {
        try navigateToCamera()

        // 打开滤镜面板
        let filterButton = app.buttons["mat_filter_b_and_w"]
        XCTAssertTrue(filterButton.waitForExistence(timeout: 3), "滤镜入口按钮应存在")
        filterButton.tap()
        usleep(800_000)

        // §9 滤镜面板容器
        try requireElement("filter_selector", timeout: 3, "滤镜面板应出现")
        attachScreenshot(name: "filter_panel_open")

        // §9 filter_list color_filters：验证色调滤镜项存在
        try requireElement("filter_filter_none", timeout: 3, "原图滤镜项应存在")
        try requireElement("filter_filter_leica_classic", timeout: 3, "经典滤镜项应存在")
        try requireElement("filter_filter_leica_vibrant", timeout: 3, "鲜艳滤镜项应存在")

        // §9 filter_list style_filters：验证风格滤镜占位存在
        try requireElement("filter_style_toon", timeout: 3, "卡通风格滤镜占位应存在")

        // 选中"经典"滤镜（验证 tap 不崩溃）
        let leicaClassic = element("filter_filter_leica_classic")
        leicaClassic.tap()
        usleep(500_000)
        attachScreenshot(name: "filter_leica_classic_selected")

        // 选中"原图"恢复默认
        element("filter_filter_none").tap()
        usleep(300_000)
    }

    // MARK: - §5 对焦手势

    /// camera.yaml §5 focus_ring：点击预览触发对焦十字星
    /// 注：FocusCrosshairView 无标识符，仅验证点击不崩溃且预览层持续存在。
    /// focus_ring 的标识符缺失记入 gap 清单。
    func testFocusTapNoCrash() throws {
        try navigateToCamera()
        try requireElement("camera_preview", timeout: 12, "预览层应存在")

        // 点击预览中心区域触发对焦
        let center = app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5))
        center.tap()
        usleep(500_000)
        attachScreenshot(name: "focus_tap")

        // 预览层仍存在（点击不导致崩溃）
        try requireElement("camera_preview", timeout: 12, "对焦点击后预览层应仍存在")
    }

    // MARK: - §7 模式选择器

    /// camera.yaml §7 mode_selector：VIDEO / PHOTO / DOCUMENT 三模式标签
    /// iOS 当前模式标签为中文硬编码（"视频"/"照片"/"文档"），spec 要求 i18n → 见 gap 清单
    func testModeSelectorLabels() throws {
        try navigateToCamera()

        // PHOTO（默认选中）
        let photoLabel = app.staticTexts["照片"]
        XCTAssertTrue(photoLabel.waitForExistence(timeout: 5), "PHOTO 模式标签应存在")

        // VIDEO
        let videoLabel = app.staticTexts["视频"]
        XCTAssertTrue(videoLabel.waitForExistence(timeout: 3), "VIDEO 模式标签应存在")

        // DOCUMENT
        let docLabel = app.staticTexts["文档"]
        XCTAssertTrue(docLabel.waitForExistence(timeout: 3), "DOCUMENT 模式标签应存在")

        attachScreenshot(name: "mode_selector")

        // 点击 VIDEO 模式不崩溃
        videoLabel.tap()
        usleep(300_000)
        attachScreenshot(name: "mode_video_selected")
    }

    // MARK: - helpers

    private func navigateToCamera(file: StaticString = #filePath, line: UInt = #line) throws {
        // 2026-09-16 导航统一：相机移出 Pager 改 fullScreenCover（无滑动/底栏入口），
        // setUp 已带 `-openCamera` 启动直弹相机 cover——此处只等相机就绪
        try requireElement("camera_preview", timeout: 12, "应到相机页", file: file, line: line)
        usleep(1_000_000)
        attachScreenshot(name: "navigate_to_camera")
        let shutter = app.descendants(matching: .any)["camera_shutter"]
        if !shutter.waitForExistence(timeout: 8) {
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
