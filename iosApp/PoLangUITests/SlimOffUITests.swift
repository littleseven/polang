import XCTest

/// 瘦脸 A/B 对照 · 关闭态（slim 0）。
///
/// 与 CameraMnnLiveUITests（slim 40）构成同一张静止人脸的 A/B：
/// slim 0 → BeautyRenderer.Params.slimFace=0 → warp.metal 中 `abs(slimFace)>0.001` 不成立 → 不做形变（基线）。
/// slim 40 → shaderSlimFace = -(40/50×1.35) = -1.08 clamp -1.0（满档形变）。
/// 镜头前人脸照片静止不动 → 两次启动间画面一致，仅瘦脸强度不同 → 人脸宽度差异 = 瘦脸形变的客观证据。
final class SlimOffUITests: XCTestCase {

    private var app: XCUIApplication!

    override func setUpWithError() throws {
        continueAfterFailure = false
        app = XCUIApplication()
        // 同一相机 cover（-openCamera，2026-09-16 相机已路由化）+ 同一 MNN 引擎，仅瘦脸强度置 0（基线 / 无形变）
        app.launchArguments = ["-openCamera", "-mnnEngine", "-slim", "0"]
        addUIInterruptionMonitor(withDescription: "permission alerts") { alert in
            for label in ["Allow", "OK", "允许", "好"] {
                let button = alert.buttons[label]
                if button.exists { button.tap(); return true }
            }
            return false
        }
        app.launch()
    }

    func testSlimOffBaseline() throws {
        let preview = app.descendants(matching: .any)["camera_preview"].firstMatch
        XCTAssertTrue(preview.waitForExistence(timeout: 20), "相机预览应就绪")
        app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.6)).tap()
        sleep(8)  // 等 MNN 异步加载 + 检测同一张人脸
        // DebugOverlay 默认展开，无需点击
        sleep(1)  // 等遥测刷新
        let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        attachment.name = "slim0_face"
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}
