import XCTest

/// TAG 扫描控制页 v4.1 结构冒烟（spec: docs/08-UI-SPECS/screens/tag-control.yaml）。
///
/// v4.1 四段骨架：progBar → top_bar → content（ringHero 全库环 + 六格成果面板 + 阶段行 ×4）→
/// bottom_bar 常驻主/次双钮。断言以 accessibilityIdentifier 为主（语言无关）：
/// - ring_hero / dial / 六格成果格（tag_result_cell_*）
/// - 阶段行 stage_<raw>_row（TagStage.rawValue 拼接）
/// - bottom 双钮 tag_scan_primary_btn / tag_scan_secondary_btn（idle 态 = 开始扫描/稍后）
/// 设备态相关：`scan_resume_unfinished_btn` 仅在存在未完成会话时渲染 → 独立用例，缺失即 Skip。
final class TagScanUITest: XCTestCase {

    /// 阶段行（TagStage.rawValue 拼接：stage_<raw>_row）。
    private let stageRowIds = ["stage_faces_row", "stage_people_row", "stage_content_row", "stage_aesthetic_row"]
    /// 六格成果面板（成果格=查看内容；阶段行=扫描控制）。
    private let resultCellIds = ["tag_result_cell_faces", "tag_result_cell_people", "tag_result_cell_cities",
                                 "tag_result_cell_group", "tag_result_cell_self", "tag_result_cell_tags"]

    override func setUpWithError() throws {
        continueAfterFailure = false
    }

    // MARK: - 结构冒烟（核心用例）

    /// v4.1 骨架关键元素齐全：progBar + ringHero + 六格成果格 + 4 行阶段行 + bottom 双钮。
    /// 全部为存在性断言（不点执行类按钮——扫描依赖媒体库与模型下载状态，超出结构冒烟范围）。
    func testV41SkeletonStructure() throws {
        let app = launchApp()
        openTagScanPage(app)

        XCTAssertTrue(element(app, "tag_scan_prog_bar").waitForExistence(timeout: 5),
                      "❌ 段1 progBar 缺失")
        XCTAssertTrue(element(app, "tag_scan_ring_hero").waitForExistence(timeout: 5),
                      "❌ 段3 ringHero 环卡缺失")
        XCTAssertTrue(element(app, "tag_scan_dial").exists, "❌ ringHero 仪表环缺失")

        for id in resultCellIds {
            XCTAssertTrue(element(app, id).waitForExistence(timeout: 3), "❌ 成果格 \(id) 缺失")
        }
        for id in stageRowIds {
            XCTAssertTrue(element(app, id).waitForExistence(timeout: 3), "❌ 阶段行 \(id) 缺失")
        }

        XCTAssertTrue(element(app, "tag_scan_primary_btn").waitForExistence(timeout: 3),
                      "❌ 段4 主钮缺失")
        XCTAssertTrue(element(app, "tag_scan_secondary_btn").exists, "❌ 段4 次钮缺失")

        attachScreenshot(name: "tagscan_v41_structure")
        print("✅ TAG 控制页 v4.1 骨架齐全（progBar / ringHero / 6×成果格 / 4×阶段行 / bottom 双钮）")
    }

    // MARK: - 阶段行交互（QUALITY 行 → 「后续版本」提示）

    /// iOS 无美学评分执行链（session_control_gap 台账）→ aesthetic 阶段行点按直弹「后续版本」。
    func testAestheticStageShowsComingSoonAlert() throws {
        let app = launchApp()
        openTagScanPage(app)

        let aestheticRow = element(app, "stage_aesthetic_row")
        XCTAssertTrue(aestheticRow.exists, "❌ stage_aesthetic_row 不存在")
        scrollToIfNeeded(aestheticRow, app)
        aestheticRow.tap()

        let okButton = app.alerts.firstMatch.buttons.firstMatch
        XCTAssertTrue(okButton.waitForExistence(timeout: 5), "❌ aesthetic 阶段行未弹「后续版本」提示")
        okButton.tap()

        print("✅ aesthetic 阶段行 → 「后续版本」提示链路正常")
    }

    // MARK: - 阶段动作 Sheet（区块阶段行 → StageActionSheet）

    /// faces 阶段行点按 → StageActionSheet 两档选项（增量扫描/全量扫描）。
    /// 不点「全量扫描」（会触发真实全量扫描，依赖媒体库与模型下载状态）。
    func testStageActionSheetOptions() throws {
        let app = launchApp()
        openTagScanPage(app)

        let facesRow = element(app, "stage_faces_row")
        XCTAssertTrue(facesRow.exists, "❌ stage_faces_row 不存在")
        facesRow.tap()

        // 选项卡有显式 accessibilityIdentifier（语言无关，与文件头部约定一致）
        let runNew = element(app, "tag_stage_option_new")
        XCTAssertTrue(runNew.waitForExistence(timeout: 5), "❌ StageActionSheet「增量扫描」选项缺失")
        let runFull = element(app, "tag_stage_option_full")
        XCTAssertTrue(runFull.exists, "❌ StageActionSheet「全量扫描」选项缺失")

        // 下滑关闭 Sheet，不触发执行
        app.swipeDown()
        print("✅ StageActionSheet 两档选项渲染正常")
    }

    // MARK: - 恢复卡（设备态相关）

    /// interrupted_card：存在未完成会话（进程死亡对账）且无活会话时渲染
    /// scan_resume_unfinished_btn。设备无未完成会话 → 不渲染，XCTSkip（不可伪造设备态）。
    /// 不点按：点按会触发真实扫描，依赖模型下载状态，超出结构冒烟范围。
    func testResumeUnfinishedCardIfExists() throws {
        let app = launchApp()
        openTagScanPage(app)

        let resumeBtn = element(app, "scan_resume_unfinished_btn")
        guard resumeBtn.waitForExistence(timeout: 4) else {
            throw XCTSkip("设备无未完成扫描会话（hasUnfinishedSession=false），恢复卡不渲染")
        }
        XCTAssertTrue(resumeBtn.exists, "❌ 恢复卡按钮存在性异常")
        print("✅ 检测到未完成会话，interrupted_card scan_resume_unfinished_btn 渲染正常")
    }

    // MARK: - 关闭返回（cover 态）

    /// 顶栏返回（topbar_back）→ fullScreenCover 关闭回到相册。
    func testCloseReturnsToGallery() throws {
        let app = launchApp()
        openTagScanPage(app)

        let backBtn = app.buttons["topbar_back"]
        XCTAssertTrue(backBtn.exists, "❌ 返回按钮不存在")
        backBtn.tap()
        usleep(800_000) // fullScreenCover 关闭转场

        XCTAssertFalse(element(app, "tag_scan_primary_btn").waitForExistence(timeout: 3),
                       "❌ 返回后仍在 TAG 控制页")
        print("✅ 返回按钮关闭控制页正常")
    }

    // MARK: - helpers

    private func launchApp() -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments += ["-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh-Hans"]
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

    /// SwiftUI 控件可能落在 buttons / otherElements 任一类别，统一按 id 查 any。
    private func element(_ app: XCUIApplication, _ id: String) -> XCUIElement {
        app.descendants(matching: .any)[id].firstMatch
    }

    /// 打开 TAG 控制页：相册顶栏扫描图标 → fullScreenCover；
    /// 以 tag_scan_primary_btn 作为「页已打开」锚点（bottom 双钮常驻恒渲染）。
    private func openTagScanPage(_ app: XCUIApplication) {
        let scanBtn = app.buttons["topbar_scan"]
        XCTAssertTrue(scanBtn.waitForExistence(timeout: 20), "❌ 相册顶栏扫描图标不存在")
        scanBtn.tap()
        XCTAssertTrue(element(app, "tag_scan_primary_btn").waitForExistence(timeout: 10),
                      "❌ TAG 控制页未打开（tag_scan_primary_btn 10s 未出现）")
        usleep(500_000) // fullScreenCover 转场收尾
    }

    /// ScrollView 内元素可能位于折叠线下（阶段行靠下）：不可点时上滑最多 3 次露出。
    private func scrollToIfNeeded(_ target: XCUIElement, _ app: XCUIApplication) {
        var attempts = 0
        while target.exists && !target.isHittable && attempts < 3 {
            app.swipeUp()
            attempts += 1
        }
    }

    private func attachScreenshot(name: String) {
        let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}
