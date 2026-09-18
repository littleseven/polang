import XCTest

/// 规格书驱动的相册屏 UITest（gallery-grid.yaml 验收点翻译）
///
/// 覆盖范围（映射到 gallery-grid.yaml 章节号）：
/// - §4 顶栏控件存在性（AppTopBar 操作组）
/// - §12 悬浮底部 Tab（FloatingBottomTab）
/// - §13-17 大图浏览（MediaPager 打开/顶栏/底栏/关闭）
/// - §7 缩略图选择模式（长按触发选择 → 操作栏 morph）
///
/// 约束：不改 App 源码；标识符缺失项记入 gap 清单
final class GallerySpecUITests: XCTestCase {

    private var app: XCUIApplication!

    override func setUpWithError() throws {
        continueAfterFailure = false
        app = XCUIApplication()
        app.launchArguments = ["-uitest"]
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

    // MARK: - §4 顶栏控件存在性

    /// gallery-grid.yaml §4 top_bar.actions：
    /// 模型中心 / TAG扫描 / 搜索入口 / 分组菜单 / 设置
    func testGalleryTopBarControlsExist() throws {
        try requireElement("gallery_grid", timeout: 10, "初始页应为相册网格")

        // §4 (a) 模型中心（Phase 6 管线灰置，但控件存在）
        try requireElement("topbar_model_center", timeout: 5, "模型中心按钮应存在")

        // §4 (b) TAG 扫描开关
        try requireElement("topbar_scan", timeout: 3, "TAG扫描按钮应存在")

        // §4 (c) 搜索入口
        try requireElement("topbar_search", timeout: 3, "搜索按钮应存在")

        // §4 (d) 分组模式菜单
        try requireElement("topbar_grouping", timeout: 3, "分组菜单应存在")

        // §4 (e) 设置
        try requireElement("topbar_settings", timeout: 3, "设置按钮应存在")

        attachScreenshot(name: "gallery_topbar")
    }

    // MARK: - §12 悬浮底部 Tab

    /// main-nav.yaml §1 bottom_bar：相册/整理/聊天/人物/回忆 5 项
    ///（2026-09-16 主导航统一：相机路由化移出底栏，标签项并入整理页）
    func testFloatingBottomTabControlsExist() throws {
        try requireElement("gallery_grid", timeout: 10, "初始页应为相册网格")

        try requireElement("tab_gallery", timeout: 5, "相册 Tab 应存在")
        try requireElement("tab_organize", timeout: 3, "整理 Tab 应存在")
        try requireElement("tab_chat", timeout: 3, "聊天 Tab 应存在")
        try requireElement("tab_person", timeout: 3, "人物 Tab 应存在")
        try requireElement("tab_memories", timeout: 3, "回忆 Tab 应存在")

        attachScreenshot(name: "floating_bottom_tab")
    }

    // MARK: - §13-17 大图浏览（MediaPager）

    /// gallery-grid.yaml §13 打开转场 / §17 顶栏 / §18 底栏 / §14 关闭转场
    /// 点击缩略图打开大图页 → 验证控件 → 返回关闭
    ///
    /// 注：media_pager ZStack 的 accessibilityIdentifier 传播覆盖所有子元素标识符
    /// （pager_back/pager_info 等均被覆盖为 media_pager），改用 SF Symbol 系统标签查询。
    /// 标签随系统语言变化，此处双语兜底。
    func testMediaPagerOpenAndClose() throws {
        try requireElement("gallery_grid", timeout: 10, "初始页应为相册网格")

        // 找到第一个缩略图 cell（identifier 格式 cell_<localIdentifier>）
        let firstCell = app.descendants(matching: .any)
            .matching(NSPredicate(format: "identifier BEGINSWITH 'cell_'")).firstMatch
        guard firstCell.waitForExistence(timeout: 5) else {
            throw XCTSkip("相册无照片，跳过大图浏览测试")
        }

        // §13 打开转场：点击缩略图
        firstCell.tap()

        // §17 media_pager 应出现
        try requireElement("media_pager", timeout: 5, "大图页应出现")
        usleep(1_000_000) // 等待 fullScreenCover 转场完成
        attachScreenshot(name: "media_pager_open")

        // §17 顶栏控件：用 SF Symbol 系统标签查询（identifier 被容器覆盖）
        // pager_back → Image(chevron.left) → label: '返回'/'Back'
        let backButton = findPagerButton(labels: ["返回", "Back"])
        XCTAssertTrue(backButton.exists, "返回按钮应存在")

        // pager_info → Image(info.circle) → label: '简介'/'Info'
        let infoButton = findPagerButton(labels: ["简介", "Info"])
        XCTAssertTrue(infoButton.exists, "信息按钮应存在")

        // pager_more → Image(ellipsis) → label: '更多'/'More'
        let moreButton = findPagerButton(labels: ["更多", "More"])
        XCTAssertTrue(moreButton.exists, "更多按钮应存在")

        // §18 底栏控件
        // pager_share → label: '发送'/'Send'
        let shareButton = findPagerButton(labels: ["发送", "Send"])
        XCTAssertTrue(shareButton.exists, "分享按钮应存在")

        // pager_delete → label: '删除'/'Delete'
        let deleteButton = findPagerButton(labels: ["删除", "Delete"])
        XCTAssertTrue(deleteButton.exists, "删除按钮应存在")

        // §14 关闭转场：点返回
        backButton.tap()
        usleep(500_000)

        // 大图页应消失
        XCTAssertFalse(element("media_pager").waitForExistence(timeout: 3),
                       "返回后大图页应关闭")
        attachScreenshot(name: "media_pager_closed")
    }

    // MARK: - §7 缩略图选择模式（长按触发）

    /// gallery-grid.yaml §7 thumbnail_item.selection_state：
    /// 长按缩略图进入选择模式 → 顶栏 morph 为选择操作栏 → 退出
    func testSelectionModeLongPress() throws {
        try requireElement("gallery_grid", timeout: 10, "初始页应为相册网格")

        // 找到第一个缩略图
        let firstCell = app.descendants(matching: .any)
            .matching(NSPredicate(format: "identifier BEGINSWITH 'cell_'")).firstMatch
        guard firstCell.waitForExistence(timeout: 5) else {
            throw XCTSkip("相册无照片，跳过选择模式测试")
        }

        // §7 selection_state.trigger: long_press
        firstCell.press(forDuration: 1.0)
        usleep(500_000)

        // 选择态顶栏应出现（AppTopBar morph: 返回 + 全选 + 分享 + 删除）
        try requireElement("topbar_back", timeout: 3, "选择态返回按钮应存在")
        try requireElement("topbar_select_all", timeout: 3, "全选按钮应存在")
        try requireElement("topbar_share", timeout: 3, "分享按钮应存在")
        try requireElement("topbar_delete", timeout: 3, "删除按钮应存在")
        attachScreenshot(name: "selection_mode_active")

        // 退出选择模式：点返回
        element("topbar_back").tap()
        usleep(500_000)

        // 选择态操作栏应消失
        XCTAssertFalse(element("topbar_select_all").waitForExistence(timeout: 2),
                       "退出选择模式后操作栏应消失")
    }

    // MARK: - §4 模型中心导航闭环

    /// gallery-grid.yaml §4 top_bar.actions 模型中心：
    /// 顶栏进入模型中心 → 自绘返回键存在（model_center_back）→ 点击返回相册。
    /// 回归：模型中心原为 fullScreenCover 内 NavigationStack 根视图，无系统 back 且
    /// cover 不可下滑关闭 → 进入后无法返回；修复 = 页内自绘返回（对齐 Android
    /// ModelCenterScreen AppTopBarNavBack）。
    func testModelCenterBackNavigation() throws {
        try requireElement("gallery_grid", timeout: 10, "初始页应为相册网格")

        // 进入模型中心
        element("topbar_model_center").tap()

        // 返回按钮应存在（修复前缺失 → 无法返回）
        try requireElement("model_center_back", timeout: 5, "模型中心返回按钮应存在")
        usleep(800_000) // 等待 fullScreenCover 转场完成
        attachScreenshot(name: "model_center_open")

        // 点击返回 → 模型中心关闭、回到相册网格
        element("model_center_back").tap()
        usleep(800_000)
        XCTAssertFalse(element("model_center_back").waitForExistence(timeout: 3),
                       "返回后模型中心应关闭")
        try requireElement("gallery_grid", timeout: 5, "返回后应回到相册网格")
        attachScreenshot(name: "model_center_closed")
    }

    // MARK: - helpers

    /// media_pager 容器标识符传播覆盖所有子按钮标识符，
    /// 改用 SF Symbol 系统标签查询（双语兜底：中文/英文）。
    private func findPagerButton(labels: [String]) -> XCUIElement {
        for label in labels {
            let btn = app.buttons[label]
            if btn.exists { return btn }
        }
        return app.buttons[labels.first ?? ""]
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
