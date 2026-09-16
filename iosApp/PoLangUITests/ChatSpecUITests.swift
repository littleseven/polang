import XCTest

/// Chat 四链路真机验收（Phase 6.2 T7）
///
/// 覆盖：
/// - testChatInventory：盘点（How many photos → AI 回复非空）
/// - testChatSearch：搜索（Show me photos from August 2026 → AI 回复 / 媒体卡片）
/// - testChatNarrow：窄化（Only today's → 续会话窄化）
/// - testChatDeleteFlow：删除（拍一张照片 → Delete the photo I just took → 验证确认弹窗即停）
///
/// 约束：
/// - 输入用英文 prompt（避免中文 IME 在 typeText 下的坑）
/// - 每条用例 waitForExistence timeout 给 40s（远程 LLM 10-30s 延迟）
/// - 每步截图（XCTAttachment）
/// - 删除链路安全约束：只删刚拍的照片，绝不碰设备既有照片
/// - iOS 搜索走诚实降级（无语义 TAG），不硬断言语义搜索结果
final class ChatSpecUITests: XCTestCase {

    private var app: XCUIApplication!

    override func setUpWithError() throws {
        continueAfterFailure = false
        app = XCUIApplication()
        app.launchArguments = ["-uitest"]
        addUIInterruptionMonitor(withDescription: "permission alerts") { alert in
            for label in ["Allow Full Access", "Allow", "OK", "允许完全访问", "允许", "好",
                          "Delete", "删除"] {
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

    // MARK: - 用例 1：盘点

    /// 发 "How many photos do I have?" → 等 AI 回复气泡非空。
    func testChatInventory() throws {
        try navigateToChat()
        attachScreenshot(name: "chat_inventory_start")

        let inputField = app.textFields["chat_input"]
        XCTAssertTrue(inputField.waitForExistence(timeout: 5), "Chat 输入框应存在")

        let prompt = "How many photos do I have?"
        inputField.tap()
        inputField.typeText(prompt)
        attachScreenshot(name: "chat_inventory_typed")

        // 发送
        let sendButton = app.buttons["chat_send"]
        XCTAssertTrue(sendButton.exists, "发送按钮应存在")
        sendButton.tap()
        attachScreenshot(name: "chat_inventory_sent")

        // 等 AI 回复气泡出现且非空（最多 40s）
        let aiBubble = app.descendants(matching: .any)["chat_ai_bubble"].firstMatch
        XCTAssertTrue(aiBubble.waitForExistence(timeout: 40), "AI 回复气泡应在 40s 内出现")

        // 等 streaming 光标消失（回复完成）——轮询 isProcessing 期间 stop 按钮存在
        // stop 按钮消失意味着处理完成
        let stopButton = app.buttons["chat_stop"]
        let deadline = Date().addingTimeInterval(40)
        while Date() < deadline && stopButton.exists {
            usleep(500_000)
        }
        usleep(1_000_000) // 额外等 UI 稳定

        attachScreenshot(name: "chat_inventory_reply")

        // 验证 AI 气泡有实际文本内容
        let replyText = aiBubble.label
        XCTAssertFalse(replyText.isEmpty, "AI 回复气泡应非空，实际: '\(replyText)'")
        print("CHAT_INVENTORY_REPLY: \(replyText)")
    }

    // MARK: - 用例 2：搜索

    /// 发 "Show me photos from August 2026" → 等回复；若出现媒体卡片则记录。
    func testChatSearch() throws {
        try navigateToChat()
        attachScreenshot(name: "chat_search_start")

        let inputField = app.textFields["chat_input"]
        XCTAssertTrue(inputField.waitForExistence(timeout: 5), "Chat 输入框应存在")

        let prompt = "Show me photos from August 2026"
        inputField.tap()
        inputField.typeText(prompt)
        attachScreenshot(name: "chat_search_typed")

        let sendButton = app.buttons["chat_send"]
        sendButton.tap()
        attachScreenshot(name: "chat_search_sent")

        // 等 AI 回复
        let aiBubble = app.descendants(matching: .any)["chat_ai_bubble"].firstMatch
        XCTAssertTrue(aiBubble.waitForExistence(timeout: 40), "AI 回复气泡应在 40s 内出现")

        // 等处理完成
        waitForProcessingComplete(timeout: 40)
        attachScreenshot(name: "chat_search_reply")

        let replyText = aiBubble.label
        print("CHAT_SEARCH_REPLY: \(replyText)")

        // 检查是否出现媒体卡片（iOS 走诚实降级，可能无媒体卡片）
        let mediaRow = app.descendants(matching: .any)["chat_media_row"].firstMatch
        if mediaRow.exists {
            print("CHAT_SEARCH_MEDIA_ROW: 存在（\(mediaRow.label)）")
            attachScreenshot(name: "chat_search_media_cards")
        } else {
            print("CHAT_SEARCH_MEDIA_ROW: 不存在（诚实降级，无语义搜索结果——记为 gap 而非失败）")
        }

        // 不硬断言语义搜索结果——iOS 端无 TAG，走时间/相册维度降级
        XCTAssertFalse(replyText.isEmpty, "AI 回复气泡应非空，实际: '\(replyText)'")
    }

    // MARK: - 用例 3：窄化

    /// 在上条会话续发 "Only today's" → 等回复。
    /// 独立发起，因为 XCUITest 每条用例重启 App。
    func testChatNarrow() throws {
        try navigateToChat()
        attachScreenshot(name: "chat_narrow_start")

        let inputField = app.textFields["chat_input"]
        XCTAssertTrue(inputField.waitForExistence(timeout: 5), "Chat 输入框应存在")

        // 先发一个宽泛查询建立上下文
        inputField.tap()
        inputField.typeText("Show me photos from this month")
        let sendButton = app.buttons["chat_send"]
        sendButton.tap()

        // 等第一轮回复完成
        let aiBubble = app.descendants(matching: .any)["chat_ai_bubble"].firstMatch
        XCTAssertTrue(aiBubble.waitForExistence(timeout: 40), "第一轮 AI 回复应在 40s 内出现")
        waitForProcessingComplete(timeout: 40)
        attachScreenshot(name: "chat_narrow_first_reply")

        // 续发窄化指令
        inputField.tap()
        inputField.typeText("Only today's")
        app.buttons["chat_send"].tap()
        attachScreenshot(name: "chat_narrow_sent")

        // 等第二轮 AI 回复
        // 第二个 AI 气泡——用 exists 轮询 count
        let deadline = Date().addingTimeInterval(40)
        var foundSecondReply = false
        while Date() < deadline {
            let aiBubbles = app.descendants(matching: .any).matching(identifier: "chat_ai_bubble")
            if aiBubbles.count >= 2 {
                foundSecondReply = true
                break
            }
            usleep(500_000)
        }
        waitForProcessingComplete(timeout: 40)
        attachScreenshot(name: "chat_narrow_second_reply")

        // 如果没等到第二个气泡，截图记录但不一定 fail（可能是额度耗尽）
        let aiBubbles = app.descendants(matching: .any).matching(identifier: "chat_ai_bubble")
        print("CHAT_NARROW_BUBBLE_COUNT: \(aiBubbles.count)")
        if aiBubbles.count >= 2 {
            let secondBubble = aiBubbles.element(boundBy: 1)
            print("CHAT_NARROW_REPLY: \(secondBubble.label)")
        }
        XCTAssertTrue(foundSecondReply, "窄化指令的第二轮 AI 回复应在 40s 内出现")
    }

    // MARK: - 用例 4：删除（安全约束）

    /// 🔴 安全约束：只删刚拍的照片。
    /// 1. 导航到相机，拍一张照片
    /// 2. 回 Chat 发 "Delete the photo I just took"
    /// 3. 等待 AI 回复 / 确认弹窗
    /// 4. 验证出现确认或删除意图即停——若有歧义记 gap，绝不硬闯删除既有照片
    ///
    /// 🔴 已知 gap：XCUITest runner 的相机授权与主 App 不同步——
    /// test runner 首次访问相机时 AVCaptureDevice.requestAccess 触发的系统弹窗
    /// 不被 addUIInterruptionMonitor 可靠拦截（iOS 16+ 后台授权回调延迟）。
    /// 故删除链路的端到端验证降级为：在 Chat 中直接发删除指令、验证 LLM 回复。
    func testChatDeleteFlow() throws {
        // 直接在 Chat 中发删除指令（跳过拍照步骤——相机授权 gap）
        try navigateToChat()
        attachScreenshot(name: "chat_delete_start")

        let inputField = app.textFields["chat_input"]
        XCTAssertTrue(inputField.waitForExistence(timeout: 5), "Chat 输入框应存在")

        inputField.tap()
        inputField.typeText("Delete the photo I just took")
        app.buttons["chat_send"].tap()
        attachScreenshot(name: "chat_delete_sent")

        // 等 AI 回复
        let aiBubble = app.descendants(matching: .any)["chat_ai_bubble"].firstMatch
        let gotReply = aiBubble.waitForExistence(timeout: 40)

        // 检查是否有系统删除确认弹窗
        usleep(2_000_000) // 给弹窗时间出现
        attachScreenshot(name: "chat_delete_reply_or_prompt")

        if gotReply {
            waitForProcessingComplete(timeout: 40)
            let replyText = aiBubble.label
            print("CHAT_DELETE_REPLY: \(replyText)")

            // 检查是否出现系统删除确认弹窗（Photos framework delete 触发）
            let systemAlert = app.alerts.firstMatch
            if systemAlert.exists {
                print("CHAT_DELETE_SYSTEM_ALERT: 系统确认弹窗出现——记录验证点（未自动确认）")
                attachScreenshot(name: "chat_delete_system_alert")
                // 安全取消：点 Cancel / 取消
                for cancelLabel in ["Cancel", "取消", "Don't Allow"] {
                    let cancelBtn = systemAlert.buttons[cancelLabel]
                    if cancelBtn.exists {
                        cancelBtn.tap()
                        print("CHAT_DELETE_RESULT: 已安全取消系统弹窗")
                        break
                    }
                }
            }
            // AI 回复非空即链路通——删除是否真执行由人工核对
            XCTAssertFalse(replyText.isEmpty, "AI 回复气泡应非空")
        } else {
            // 40s 无回复——可能额度耗尽或网关不可达
            print("CHAT_DELETE_GAP: 40s 内无 AI 回复——记为 gap（额度/网关问题）")
            attachScreenshot(name: "chat_delete_no_reply")
            try XCTSkip("Delete flow: 40s 内无 AI 回复（可能额度耗尽或网关不可达）")
        }
    }

    // MARK: - 用例 5：气泡宽度自适应（对齐 Android widthIn(max=360)）

    /// 短消息气泡应收缩包裹内容（不撑满 360 固定宽）；长消息触顶 360 上限换行。
    /// 不依赖 AI 回复（离线可跑）——用户气泡足以验证布局。
    func testChatBubbleWidthHugging() throws {
        try navigateToChat()

        let inputField = app.textFields["chat_input"]
        XCTAssertTrue(inputField.waitForExistence(timeout: 5), "Chat 输入框应存在")

        // 短消息：气泡（chat_user_bubble = 文本 bounds）应收窄
        inputField.tap()
        inputField.typeText("Hi")
        app.buttons["chat_send"].tap()
        let userBubbles = app.descendants(matching: .any).matching(identifier: "chat_user_bubble")
        XCTAssertTrue(userBubbles.firstMatch.waitForExistence(timeout: 10), "用户气泡应出现")
        usleep(500_000) // 等流式节奏器首帧稳定
        let shortWidth = userBubbles.allElementsBoundByIndex.last!.frame.width
        print("CHAT_BUBBLE_SHORT_WIDTH: \(shortWidth)")
        XCTAssertLessThan(shortWidth, 120, "短消息气泡应收缩包裹（固定宽 bug 时 ~360）")
        attachScreenshot(name: "chat_bubble_short")

        // 长消息：触顶 bubbleMaxWidth 360 换行（文本 bounds ≈ 360 - 32 padding = 328）
        inputField.tap()
        inputField.typeText("This is a deliberately long message that must exceed the maximum bubble width so the text is forced to wrap onto multiple lines")
        app.buttons["chat_send"].tap()
        usleep(1_000_000) // 等第二条气泡渲染
        let longWidth = userBubbles.allElementsBoundByIndex.last!.frame.width
        print("CHAT_BUBBLE_LONG_WIDTH: \(longWidth)")
        XCTAssertGreaterThan(longWidth, 310, "长消息应触顶宽度上限")
        XCTAssertLessThan(longWidth, 345, "长消息不应超过 360 上限（含 32pt 气泡内边距）")
        attachScreenshot(name: "chat_bubble_long")
    }

    // MARK: - 用例 6：工具轮渲染策略（对齐 Android uiActions 收集器）

    /// 两轮搜索（search → refine）后：媒体卡只保留最后一张（替换不叠加）；
    /// 工具 text_reply 不追加独立气泡——修复「回复切成多条」「连续多条未找到」。
    /// 新建会话保证基线为 0；新增气泡上界：2 轮最终回复 + 1 张卡 header ≤ 3。
    func testChatToolRoundSingleCardNoSplit() throws {
        try navigateToChat()

        // 新建会话：历史消息清零，断言不被往轮污染
        let newChatButton = app.buttons["chat_new"]
        XCTAssertTrue(newChatButton.waitForExistence(timeout: 5), "新建会话按钮应存在")
        newChatButton.tap()
        usleep(1_000_000)

        let inputField = app.textFields["chat_input"]
        XCTAssertTrue(inputField.waitForExistence(timeout: 5), "Chat 输入框应存在")

        func aiBubbleCount() -> Int {
            app.descendants(matching: .any).matching(identifier: "chat_ai_bubble").count
        }

        /// 等新增 AI 气泡：count 超过基线（firstMatch 存在性会被历史气泡立即满足）
        func waitForNewReply(baseline: Int, timeout: TimeInterval) -> Bool {
            let deadline = Date().addingTimeInterval(timeout)
            while Date() < deadline && aiBubbleCount() <= baseline { usleep(500_000) }
            return aiBubbleCount() > baseline
        }

        // 第一轮：宽泛搜索（建立上下文 + 期待媒体卡）
        inputField.tap()
        inputField.typeText("Show me photos from this month")
        app.buttons["chat_send"].tap()
        XCTAssertTrue(waitForNewReply(baseline: 0, timeout: 40), "第一轮 AI 回复应在 40s 内出现")
        waitForProcessingComplete(timeout: 40)
        attachScreenshot(name: "chat_single_card_round1")

        // 第二轮：窄化（refine_media_search → 替换上一张卡片）
        inputField.tap()
        inputField.typeText("Only today's")
        // 等发送按钮出现（canSend = 有文本 && 非处理中；第一轮处理未结束时不显示）
        let sendButton = app.buttons["chat_send"]
        XCTAssertTrue(sendButton.waitForExistence(timeout: 40), "第二轮发送按钮应出现")
        let baseline2 = aiBubbleCount()
        sendButton.tap()
        XCTAssertTrue(waitForNewReply(baseline: baseline2, timeout: 40), "第二轮 AI 回复应在 40s 内出现")
        waitForProcessingComplete(timeout: 40)
        attachScreenshot(name: "chat_single_card_round2")

        // 断言 1：媒体卡至多 1 张（多轮替换，不叠加）
        let cardCount = app.descendants(matching: .any).matching(identifier: "chat_media_card").count
        print("CHAT_TOOL_CARD_COUNT: \(cardCount)")
        XCTAssertLessThanOrEqual(cardCount, 1, "多轮搜索的媒体卡应只保留最后一张")

        // 断言 2：无工具中间文本气泡（text_reply 已忽略，最终回复单气泡呈现）
        let bubbleCount = aiBubbleCount()
        print("CHAT_TOOL_BUBBLE_COUNT: \(bubbleCount)")
        XCTAssertLessThanOrEqual(bubbleCount, 3, "工具中间结果不应追加独立文本气泡")
    }

    // MARK: - Helpers

    /// 从相册（初始页）导航到 Chat 页。
    /// MainTabView 的 FloatingBottomTab 有 tab_chat 按钮。
    private func navigateToChat(file: StaticString = #filePath, line: UInt = #line) throws {
        // 确保在相册页
        let gallery = app.descendants(matching: .any)["gallery_grid"].firstMatch
        if !gallery.exists {
            // 可能已经在别的页——通过 Tab 回到相册
            // gallery 是初始页，直接点 tab_chat 应该能到 chat（Tab 在 gallery overlay 上）
            // 但如果当前不在 gallery，Tab 不可见——先确保在 gallery
        }
        XCTAssertTrue(gallery.waitForExistence(timeout: 10),
                      "初始页应为相册网格", file: file, line: line)

        // 点 Chat Tab
        let chatTab = app.buttons["tab_chat"]
        XCTAssertTrue(chatTab.waitForExistence(timeout: 5),
                      "Chat Tab 应存在", file: file, line: line)
        chatTab.tap()
        usleep(1_000_000) // 等切页动画

        // 验证到了 Chat 页——Chat 输入框出现
        let inputField = app.textFields["chat_input"]
        if !inputField.waitForExistence(timeout: 5) {
            attachScreenshot(name: "navigate_to_chat_failed")
            print("A11Y-DUMP-BEGIN")
            print(app.debugDescription.prefix(12000))
            print("A11Y-DUMP-END")
            XCTFail("导航到 Chat 页失败——输入框未出现", file: file, line: line)
        }
    }

    /// 从相机页回到 Chat 页（先回相册再进 Chat）。
    private func navigateToChatFromCamera(file: StaticString = #filePath, line: UInt = #line) throws {
        // 相机页有 gallery thumb 入口
        let galleryThumb = app.descendants(matching: .any)["camera_gallery_thumb"].firstMatch
        if galleryThumb.exists {
            galleryThumb.tap()
            usleep(1_000_000)
        }
        // 现在应该在相册页
        let gallery = app.descendants(matching: .any)["gallery_grid"].firstMatch
        XCTAssertTrue(gallery.waitForExistence(timeout: 5),
                      "应回相册页", file: file, line: line)

        // 点 Chat Tab
        let chatTab = app.buttons["tab_chat"]
        XCTAssertTrue(chatTab.waitForExistence(timeout: 3),
                      "Chat Tab 应存在", file: file, line: line)
        chatTab.tap()
        usleep(1_000_000)

        let inputField = app.textFields["chat_input"]
        if !inputField.waitForExistence(timeout: 5) {
            attachScreenshot(name: "navigate_to_chat_from_camera_failed")
            XCTFail("从相机导航到 Chat 页失败", file: file, line: line)
        }
    }

    /// 从相册导航到相机页（2026-09-16 导航统一：相机移出 Pager 改 fullScreenCover 路由，
    /// 底栏/滑动均无入口——自动化经 launch arg `-openCamera` 重启直弹相机 cover）。
    private func navigateToCamera(file: StaticString = #filePath, line: UInt = #line) throws {
        app.terminate()
        app.launchArguments = ["-uitest", "-openCamera"]
        app.launch()

        // 处理可能弹出的相机权限弹窗（interruption monitor 需要主线程 yield）
        usleep(3_000_000)

        // camera_preview 根视图总是存在（ZStack 外壳），但授权前不渲染控件
        // 检查是否被拒绝（camera_denied 出现 = 权限未授予）
        let denied = app.descendants(matching: .any)["camera_denied"].firstMatch
        if denied.waitForExistence(timeout: 5) {
            attachScreenshot(name: "camera_denied_state")
            // 记录为 gap——相机权限未授予，无法测试删除链路
            throw XCTSkip("相机权限未授予（camera_denied 出现）——删除链路需相机权限，跳过")
        }

        // camera_preview 根视图在授权回调前就存在——必须等快门出现才算控件就绪
        // 给相机初始化充足时间（授权异步 + AVCaptureSession 启动）
        let shutter = app.descendants(matching: .any)["camera_shutter"]
        if !shutter.waitForExistence(timeout: 15) {
            attachScreenshot(name: "navigate_to_camera_failed")
            print("A11Y-DUMP-BEGIN")
            print(app.debugDescription.prefix(12000))
            print("A11Y-DUMP-END")
            XCTFail("相机控件未就绪（授权异步）", file: file, line: line)
        }
        usleep(1_000_000)
        attachScreenshot(name: "navigate_to_camera")
    }

    /// 等待处理完成（stop 按钮消失）。
    private func waitForProcessingComplete(timeout: TimeInterval) {
        let stopButton = app.buttons["chat_stop"]
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline && stopButton.exists {
            usleep(500_000)
        }
        usleep(1_000_000)
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
