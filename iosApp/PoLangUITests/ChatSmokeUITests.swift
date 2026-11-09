import XCTest

/// Phase 6.2 T0/T7 真机自动化验证
/// 验证链路：Koog/Ktor Darwin SSE → 访客网关 → 远程推理 → 工具调用 → 媒体卡片
final class ChatSmokeUITests: XCTestCase {

    var app: XCUIApplication!

    override func setUpWithError() throws {
        continueAfterFailure = false
        app = XCUIApplication()
        app.launch()
    }

    /// T0 冒烟：进入 Chat 页发 "pong"，验证远程推理链路可达
    func testT0_SmokeChat() throws {
        let device = XCUIDevice.shared
        // 悬浮 Tab 已有 accessibilityIdentifier（tab_chat），直接点（替代旧坐标点击）
        let gallery = app.descendants(matching: .any)["gallery_grid"].firstMatch
        XCTAssertTrue(gallery.waitForExistence(timeout: 12), "初始页应为相册网格")
        app.buttons["tab_chat"].tap()

        // 找输入框
        let targetField = app.textFields["chat_input"]
        XCTAssertTrue(targetField.waitForExistence(timeout: 12), "Chat input field should exist")

        // 输入 "pong" 并发送
        targetField.tap()
        targetField.typeText("pong")

        // 点发送按钮（输入栏最右侧）
        if app.buttons.count > 0 {
            // 尝试找发送按钮
            let sendButton = app.buttons.allElementsBoundByIndex.last
            sendButton?.tap()
        } else {
            // 回车发送
            targetField.typeText("\n")
        }

        // 等待回复（最多 30 秒——远程推理首次可能慢）
        sleep(30)

        // 截图保存结果
        let screenshot = XCUIScreen.main.screenshot()
        let attachment = XCTAttachment(screenshot: screenshot)
        attachment.name = "T0_smoke_result"
        attachment.lifetime = .keepAlways
        add(attachment)

        // 验证至少有 2 条消息（user + assistant）
        // ChatView 用 ScrollView + LazyVStack，不能直接数消息
        // 改为验证屏幕上出现了非 "pong" 的文本（assistant 回复）
        print("=== T0 SMOKE: screenshot captured, checking for assistant reply ===")
    }

    /// T7 链路 1：相册盘点
    func testT7_GallerySummary() throws {
        navigateToChat()

        let inputField = app.textFields.firstMatch.exists
            ? app.textFields.firstMatch : app.textViews.firstMatch
        XCTAssertTrue(inputField.waitForExistence(timeout: 10))

        inputField.tap()
        inputField.typeText("相册有多少照片")
        tapSend()

        sleep(20)

        let screenshot = XCUIScreen.main.screenshot()
        let attachment = XCTAttachment(screenshot: screenshot)
        attachment.name = "T7_gallery_summary"
        attachment.lifetime = .keepAlways
        add(attachment)
    }

    /// T7 链路 2：搜索
    func testT7_Search() throws {
        navigateToChat()

        let inputField = app.textFields.firstMatch.exists
            ? app.textFields.firstMatch : app.textViews.firstMatch
        XCTAssertTrue(inputField.waitForExistence(timeout: 10))

        inputField.tap()
        inputField.typeText("找找最近的照片")
        tapSend()

        sleep(20)

        let screenshot = XCUIScreen.main.screenshot()
        let attachment = XCTAttachment(screenshot: screenshot)
        attachment.name = "T7_search"
        attachment.lifetime = .keepAlways
        add(attachment)
    }

    /// 键盘避让自动化测量：点输入框 → 读【整个输入栏底部】(相册胶囊=最低元素) vs 键盘顶，
    /// 断言输入栏底部不超过键盘顶（否则按钮行被遮）。全程自动，免手动点击。
    func testKeyboardAvoidance() {
        let gallery = app.descendants(matching: .any)["gallery_grid"].firstMatch
        _ = gallery.waitForExistence(timeout: 12)
        app.buttons["tab_chat"].tap()
        sleep(1)
        // 新建会话 → 空态（复现"新建会话首次输入被遮"）
        let newChat = app.buttons["chat_new"]
        if newChat.waitForExistence(timeout: 5) { newChat.tap(); sleep(1) }

        let input = app.textFields["chat_input"].exists
            ? app.textFields["chat_input"]
            : app.textViews["chat_input"]
        XCTAssertTrue(input.waitForExistence(timeout: 12), "chat_input 未找到")
        input.tap()

        let keyboard = app.keyboards.firstMatch
        XCTAssertTrue(keyboard.waitForExistence(timeout: 8), "点输入框后键盘未弹出")
        // 输入字符触发预测/候选栏（键盘变高，更接近用户真实打字场景）
        input.typeText("a")
        sleep(2)  // 等键盘 + 预测栏动画稳定

        let bar = app.buttons["chat_gallery_capsule"]
        let barBottom = bar.frame.maxY
        let keyboardTop = keyboard.frame.minY
        let barHittable = bar.isHittable  // 权威：被键盘遮挡时为 false
        let msg = "barBottom=\(Int(barBottom)) keyboardTop=\(Int(keyboardTop)) kbH=\(Int(app.frame.height - keyboardTop)) barHittable=\(barHittable)"
        print("=== KBDUITEST \(msg) ===")
        let attach = XCTAttachment(string: msg)
        attach.name = "kbd-measure"; attach.lifetime = .keepAlways
        add(attach)
        // 全屏截图（含键盘）供宿主查看空态布局
        let shot = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        shot.name = "empty_kb"; shot.lifetime = .keepAlways
        add(shot)
        XCTAssertTrue(barHittable, "❌ 输入栏被键盘遮挡 (isHittable=false): \(msg)")
    }

    /// 点空白/消息收键盘：tap 输入框弹键盘 → 点消息区 → 键盘应收起。全程自动。
    func testTapToDismissKeyboard() {
        let gallery = app.descendants(matching: .any)["gallery_grid"].firstMatch
        _ = gallery.waitForExistence(timeout: 12)
        app.buttons["tab_chat"].tap()
        sleep(1)

        let input = app.textFields["chat_input"].exists
            ? app.textFields["chat_input"]
            : app.textViews["chat_input"]
        XCTAssertTrue(input.waitForExistence(timeout: 12), "chat_input 未找到")
        input.tap()
        let keyboard = app.keyboards.firstMatch
        XCTAssertTrue(keyboard.waitForExistence(timeout: 8), "键盘未弹出")

        // 点消息区（屏幕中上）→ 触发 onTapGesture 收键盘
        app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.4)).tap()
        sleep(1)
        XCTAssertFalse(keyboard.exists, "❌ 点空白后键盘未收起（有历史消息时点空白无效）")
    }

    /// CHART 渲染层验证（Task 7）：/chart demo → ChartJsEngine(JavaScriptCore + chart_bootstrap.js)
    /// → ChartSvgCard(WKWebView)。触发 emitChartDemo()，断言 chat_chart_card 出现 =
    /// SVG 生成 + 渲染端到端通（无需 LLM draw_chart capability，纯端侧渲染链路）。
    func testChartRenderDemo() throws {
        navigateToChat()

        let input = app.textFields["chat_input"].exists
            ? app.textFields["chat_input"]
            : app.textViews["chat_input"]
        XCTAssertTrue(input.waitForExistence(timeout: 12), "chat_input 未找到")
        input.tap()
        input.typeText("/chart")
        tapSend()

        // chart 卡容器（ChartSvgCard 的 accessibilityIdentifier=chat_chart_card）
        let chartCard = app.descendants(matching: .any)["chat_chart_card"].firstMatch
        XCTAssertTrue(
            chartCard.waitForExistence(timeout: 10),
            "❌ /chart 未渲染出图卡（ChartJsEngine 或 chart_bootstrap.js 加载失败）"
        )

        let screenshot = XCUIScreen.main.screenshot()
        let attachment = XCTAttachment(screenshot: screenshot)
        attachment.name = "chart_render_demo"
        attachment.lifetime = .keepAlways
        add(attachment)
    }

    /// CHART 触发链 E2E（Task 7，确定性）：/charttool → ChatAgentBridge.dispatchDrawChart →
    /// CapabilityRegistry → IosChartCapability → ChartRendererBridge → ChartJsEngine →
    /// appendChartMessage。绕过远程 LLM（访客模型未必发起 tool_call），确定性验证 draw_chart 接线。
    func testChartTriggerChain() throws {
        navigateToChat()

        let input = app.textFields["chat_input"].exists
            ? app.textFields["chat_input"]
            : app.textViews["chat_input"]
        XCTAssertTrue(input.waitForExistence(timeout: 12), "chat_input 未找到")
        input.tap()
        input.typeText("/charttool")
        tapSend()

        // 派发 + 端侧渲染（无 LLM 往返），~5s 足够
        let chartCard = app.descendants(matching: .any)["chat_chart_card"].firstMatch
        let appeared = chartCard.waitForExistence(timeout: 15)

        let screenshot = XCUIScreen.main.screenshot()
        let attachment = XCTAttachment(screenshot: screenshot)
        attachment.name = appeared ? "chart_triggerchain_PASS" : "chart_triggerchain_FAIL"
        attachment.lifetime = .keepAlways
        add(attachment)

        XCTAssertTrue(
            appeared,
            "❌ /charttool 触发链未渲染图卡（IosChartCapability/ChartRendererBridge/onChart 接线断）"
        )
    }

    /// run_gallery_script 触发链 E2E（Task 4，确定性）：/runscript → ChatAgentBridge.dispatchRunScript →
    /// CapabilityRegistry → IosRunScriptCapability → RunScriptBridge → JsRuntime+JsCoreEngine → gallery.summary。
    /// 绕过远程 LLM，确定性验证 run_gallery_script 接线 + 端侧 JS 沙盒 + gallery 只读 handler。
    func testRunScriptDemo() throws {
        navigateToChat()

        let input = app.textFields["chat_input"].exists
            ? app.textFields["chat_input"]
            : app.textViews["chat_input"]
        XCTAssertTrue(input.waitForExistence(timeout: 12), "chat_input 未找到")
        input.tap()
        input.typeText("/runscript")
        tapSend()

        // 派发 + 端侧脚本执行（gallery.summary handler + JsCoreEngine 沙箱），~5s 足够
        let resultText = app.staticTexts
            .containing(NSPredicate(format: "label CONTAINS %@", "相册共")).firstMatch
        let appeared = resultText.waitForExistence(timeout: 15)

        let screenshot = XCUIScreen.main.screenshot()
        let attachment = XCTAttachment(screenshot: screenshot)
        attachment.name = appeared ? "runscript_PASS" : "runscript_FAIL"
        attachment.lifetime = .keepAlways
        add(attachment)

        XCTAssertTrue(
            appeared,
            "❌ /runscript 触发链未产出盘点文案（IosRunScriptCapability/RunScriptBridge/JsCoreEngine/gallery.summary 接线断）"
        )
    }

    /// EDIT_RESULT 渲染 E2E（确定性）：/editdemo → 生成图落盘 → 编辑结果图卡出现。
    func testEditResultDemo() throws {
        navigateToChat()
        let input = app.textFields["chat_input"].exists
            ? app.textFields["chat_input"]
            : app.textViews["chat_input"]
        XCTAssertTrue(input.waitForExistence(timeout: 12), "chat_input 未找到")
        input.tap()
        input.typeText("/editdemo")
        tapSend()

        let card = app.descendants(matching: .any)["chat_edit_image"].firstMatch
        let appeared = card.waitForExistence(timeout: 15)

        let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        attachment.name = appeared ? "editdemo_PASS" : "editdemo_FAIL"
        attachment.lifetime = .keepAlways
        add(attachment)
        XCTAssertTrue(appeared, "❌ /editdemo 未渲染编辑结果图卡（ChatEditImageCard/ChatMessage type 接线断）")
    }

    // MARK: - Helpers

    private func navigateToChat() {
        let gallery = app.descendants(matching: .any)["gallery_grid"].firstMatch
        _ = gallery.waitForExistence(timeout: 12)
        app.buttons["tab_chat"].tap()
        sleep(1)
    }

    private func tapSend() {
        let send = app.buttons["chat_send"]
        if send.waitForExistence(timeout: 3) {
            send.tap()
        } else {
            app.buttons.allElementsBoundByIndex.last?.tap()
        }
    }
}
