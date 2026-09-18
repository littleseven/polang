import XCTest

/// MNN 端侧推理 · 真机实拍验收（「0 的突破」可视化证据）。
///
/// 启动参数固定：相机 cover(`-openCamera`，2026-09-16 相机已路由化) + MNN 引擎(`-mnnEngine`) + 瘦脸40(`-slim 40`)。
/// 镜头前放置人脸照片 → MNN 两阶段端侧推理(RetinaFace det_500m → 2d106)检测人脸 →
/// BeautyRenderer 应用瘦脸形变。展开 DebugOverlay 使 `face.mnn: 106pts` 等遥测写入截图，
/// 作为「iOS 端 MNN 实时人脸检测 + 瘦脸」的客观证据。
///
/// 真值通道：app 内 DebugOverlay（face.mnn / face.engine.active / camera.fps）。
/// 采集方式：`XCUIScreen.main.screenshot()`——真屏 framebuffer 捕获（Metal 相机预览可见），
/// 与单元测试 host 的 drawHierarchy（Metal 可能黑屏）不同。
final class CameraMnnLiveUITests: XCTestCase {

    private var app: XCUIApplication!

    override func setUpWithError() throws {
        continueAfterFailure = false
        app = XCUIApplication()
        // 直达相机 cover（-openCamera）+ MNN 引擎 + 瘦脸强度 40（range -50..50，80% 满档，肉眼可辨）
        app.launchArguments = ["-openCamera", "-mnnEngine", "-slim", "40"]
        // 防御：相机权限弹窗（已授权时不出现）
        addUIInterruptionMonitor(withDescription: "permission alerts") { alert in
            for label in ["Allow", "OK", "允许", "好"] {
                let button = alert.buttons[label]
                if button.exists { button.tap(); return true }
            }
            return false
        }
        app.launch()
    }

    /// 主验收：MNN 实时检测镜头前人脸 + 瘦脸形变，截图含 DebugOverlay 遥测。
    func testCameraMnnLiveSlim() throws {
        // 1. 相机页就绪（授权 + 渲染）
        let preview = app.descendants(matching: .any)["camera_preview"].firstMatch
        XCTAssertTrue(preview.waitForExistence(timeout: 20), "相机预览应就绪（授权可能异步）")

        // 2. 轻点预览中心：触发对焦 + 驱动 interruption monitor（若权限弹窗）
        app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.6)).tap()

        // 3. 等 MNN 异步加载模型（initDetector 在 queue.async）+ 检测镜头前人脸
        sleep(8)

        // 4. DebugOverlay 默认展开，face.mnn / face.engine.active / camera.fps 等遥测已入屏
        sleep(1)  // 等遥测刷新

        // 5. 采集（MNN + 瘦脸 40）
        attach("camera_mnn_live_slim40")

        // 6. 再采一帧（证明持续检测、fps 在跳）
        sleep(3)
        attach("camera_mnn_live_slim40_b")
    }

    /// 瘦脸强度 A/B：slim40 固定，仅 warpStrength 不同（1.0 默认 vs 5.0 放大）。
    /// 诊断目标：若 hasFace=1、slim=-0.2 已确认（warp 在跑）但真机不可见，
    /// 则比较 warp1 vs warp5 的人脸宽度——若 warp5 明显收窄，证明是「强度不足」。
    func testSlimStrengthAB() throws {
        // A: 当前实例 = slim40 + warpStrength 默认(1.0)，先稳一帧
        let preview = app.descendants(matching: .any)["camera_preview"].firstMatch
        XCTAssertTrue(preview.waitForExistence(timeout: 20), "相机预览应就绪")
        app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.6)).tap()
        sleep(8)
        attach("slim40_warp1")

        // B: 重启 = slim40 + warpStrength 5.0（放大形变）
        app.terminate()
        app.launchArguments = ["-openCamera", "-mnnEngine", "-slim", "40", "-warpStrength", "5"]
        app.launch()
        XCTAssertTrue(preview.waitForExistence(timeout: 20), "相机预览应就绪（重启后）")
        app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.6)).tap()
        sleep(8)
        attach("slim40_warp5")
    }

    /// 瘦脸 开/关 对照（默认 warpStrength=4.0）：
    /// A/B = slim40 同设置两帧（噪声/对齐基线），C = slim0（无形变）。
    /// 客观判定：|A−C|（瘦脸信号）应远大于 |A−B|（噪声底），且集中于下颌行。
    func testSlimOnOffDefault() throws {
        let preview = app.descendants(matching: .any)["camera_preview"].firstMatch
        XCTAssertTrue(preview.waitForExistence(timeout: 20), "相机预览应就绪")
        app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.6)).tap()
        sleep(8)
        attach("slim40_on_A")
        sleep(3)
        attach("slim40_on_B")   // 同设置第二帧 = 噪声/对齐底

        // C: slim0（无形变基线）
        app.terminate()
        app.launchArguments = ["-openCamera", "-mnnEngine", "-slim", "0"]
        app.launch()
        XCTAssertTrue(preview.waitForExistence(timeout: 20), "相机预览应就绪（重启后）")
        app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.6)).tap()
        sleep(8)
        attach("slim0_off_C")
    }

    /// 导出 DebugOverlay 全部遥测行（纯文本，无图像）→ 判定 model missing / engine / hasFace。
    /// 轮询采样 ~18s：记录 `beauty.hasFace` / `face.mnn` 出现过的取值集合（峰值），
    /// 即使检测间歇也捕获；末尾附最终全量遥测。
    func testDumpOverlayTelemetry() throws {
        let preview = app.descendants(matching: .any)["camera_preview"].firstMatch
        XCTAssertTrue(preview.waitForExistence(timeout: 20), "相机预览应就绪")
        app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.6)).tap()
        sleep(4)  // 等 MNN 异步加载 + 首检测帧 + 遥测刷新

        func readEntry(_ key: String) -> String {
            let e = app.staticTexts["debug_entry_\(key)"]
            return e.exists ? e.label : "\(key): <none>"
        }
        func readSummary() -> String {
            let s = app.descendants(matching: .any)["debug_summary"].firstMatch
            return s.exists ? s.label : "<no summary>"
        }

        var hasFaceSeen = Set<String>()
        var faceMnnSeen = Set<String>()
        for _ in 0..<10 {           // 每 ~1.8s 采一次，共 ~18s
            hasFaceSeen.insert(readEntry("beauty.hasFace"))
            faceMnnSeen.insert(readEntry("face.mnn"))
            sleep(2)
        }

        var lines: [String] = []
        let sep = " | "
        lines.append("SUMMARY: \(readSummary())")
        lines.append("PEAK beauty.hasFace: \(hasFaceSeen.sorted().joined(separator: sep))")
        lines.append("PEAK face.mnn: \(faceMnnSeen.sorted().joined(separator: sep))")
        lines.append("---- final full dump ----")
        let entries = app.staticTexts.matching(NSPredicate(format: "identifier BEGINSWITH 'debug_entry_'"))
        var finalLines: [String] = []
        for i in 0..<entries.count {
            let e = entries.element(boundBy: i)
            if e.exists { finalLines.append(e.label) }
        }
        lines.append(contentsOf: finalLines.sorted())
        let att = XCTAttachment(string: lines.joined(separator: "\n"))
        att.name = "overlay_dump.txt"; att.lifetime = .keepAlways
        add(att)
    }

    /// 触发 MNN 离线自检（-mnnSelfTest）：bundle 内固定人脸图 face_test.jpg → 两阶段检测 →
    /// 写 Documents/mnn-verify.txt。判定「retina:no-face」是检测根本失效还是仅相机输入问题。
    /// 自检成功(faceFound=1)→ 检测链路正常，live 无脸=相机输入/取景问题；
    /// 自检失败(faceFound=0)→ 检测本身在 iOS 上失效（预处理/格式/模型），需深查。
    func testRunMnnSelfTest() throws {
        app.terminate()
        app.launchArguments = ["-openCamera", "-mnnEngine", "-mnnSelfTest"]
        app.launch()
        let preview = app.descendants(matching: .any)["camera_preview"].firstMatch
        _ = preview.waitForExistence(timeout: 20)
        sleep(10)  // 等自检（.userInitiated 队列）跑完写 mnn-verify.txt
    }

    // MARK: - helpers

    private func attach(_ name: String) {
        let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}
