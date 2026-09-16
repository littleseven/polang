import Foundation
import AVFoundation

/// 相机采集（spike main.mm startCapture 的 Swift 版）：
/// 720p、YUV bi-planar、丢弃迟到帧、串行队列、Portrait 方向。
final class CaptureSessionController: NSObject {
    let session = AVCaptureSession()
    private let videoOutput = AVCaptureVideoDataOutput()
    private let queue = DispatchQueue(label: "polang.camera.capture")

    private(set) var currentPixelBuffer: CVPixelBuffer?
    /// 🔴 与 currentPixelBuffer 同源的时间戳（相机 PTS 毫秒）；与检测端 enqueue(timestampMs:) 同域。
    /// 渲染端用它与 latestWithinWindow 做 200ms 时间窗 join——此前渲染端误用墙钟(Date().timeIntervalSince1970)
    /// 与相机 PTS 比，差万亿 ms → 窗口恒 false → 106 点永远进不了渲染器 → 瘦脸无效。
    private(set) var currentTimestampMs: Int = 0
    private let bufferLock = NSLock()
    private(set) var frameCount: Int = 0

    var onFirstFrame: (() -> Void)?
    /// 🔴1: 帧回调——投递给 FaceLandmarkService
    var onFrame: ((CVPixelBuffer, Int) -> Void)?

    func checkAuthorizationAndStart() async -> Bool {
        let status = AVCaptureDevice.authorizationStatus(for: .video)
        print("[PoLang] camera.auth status=\(status.rawValue)")
        switch status {
        case .authorized:
            start(); return true
        case .notDetermined:
            let granted = await AVCaptureDevice.requestAccess(for: .video)
            if granted { start() }
            return granted
        default:
            return false
        }
    }

    private func start() {
        queue.async { [self] in
            session.beginConfiguration()
            session.sessionPreset = .hd1280x720
            // 🔴 翻转摄像头支持
            guard let device = AVCaptureDevice.default(.builtInWideAngleCamera,
                                                       for: .video, position: currentPosition),
                  let input = try? AVCaptureDeviceInput(device: device),
                  session.canAddInput(input), session.canAddOutput(videoOutput) else {
                session.commitConfiguration()
                DispatchQueue.main.async {
                    DebugOverlayState.shared.set("camera.error", "session config failed")
                }
                return
            }
            // 移除旧 input（翻转时）
            session.inputs.forEach { session.removeInput($0) }
            session.addInput(input)
            currentDeviceInput = input
            videoOutput.videoSettings = [
                kCVPixelBufferPixelFormatTypeKey as String:
                    kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange
            ]
            videoOutput.alwaysDiscardsLateVideoFrames = true
            videoOutput.setSampleBufferDelegate(self, queue: queue)
            if !session.outputs.contains(videoOutput) {
                session.addOutput(videoOutput)
            }
            if let conn = videoOutput.connection(with: .video) {
                conn.videoOrientation = .portrait
                if currentPosition == .front {
                    conn.isVideoMirrored = true
                } else {
                    conn.isVideoMirrored = false
                }
            }
            session.commitConfiguration()
            session.startRunning()
            DispatchQueue.main.async {
                DebugOverlayState.shared.set("camera.running", "yes")
                DebugOverlayState.shared.set("camera.position", self.currentPosition == .front ? "front" : "back")
            }
        }
    }

    /// 当前摄像头方向
    private var currentPosition: AVCaptureDevice.Position = .back
    private var currentDeviceInput: AVCaptureDeviceInput?

    /// 当前朝向（只读，供头像拍摄态记录/恢复，main-nav.yaml §3）
    var position: AVCaptureDevice.Position { currentPosition }

    /// 程序化切到指定朝向（已在该朝向则 no-op；无该朝向设备静默保持——
    /// 对齐 Android hasSystemFeature(FEATURE_CAMERA_FRONT) 检查，避免 flipCamera 无设备时
    /// 停在 stopRunning 态）。复用 flipCamera 通路（含人脸引擎 isFrontCamera 同步）。
    func switchToPosition(_ position: AVCaptureDevice.Position) {
        guard position != currentPosition else { return }
        guard AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: position) != nil else { return }
        flipCamera()
    }

    /// 把额外 output（如拍照 photoOutput）串行挂到 session。
    /// 🔴 必须走 capture 队列：与 start()/flipCamera() 的 beginConfiguration 块保序，
    /// 禁止从主线程直接 addOutput（与配置块竞态 → capturePhoto 无回调）
    func attachOutput(_ output: AVCaptureOutput) {
        queue.async { [self] in
            if !session.outputs.contains(output), session.canAddOutput(output) {
                session.addOutput(output)
            }
        }
    }

    /// 翻转摄像头
    func flipCamera() {
        currentPosition = currentPosition == .back ? .front : .back
        faceServiceIsFrontCamera?(currentPosition == .front)
        // 重启 session
        queue.async { [self] in
            session.stopRunning()
            session.beginConfiguration()
            session.inputs.forEach { session.removeInput($0) }
            guard let device = AVCaptureDevice.default(.builtInWideAngleCamera,
                                                       for: .video, position: currentPosition),
                  let input = try? AVCaptureDeviceInput(device: device),
                  session.canAddInput(input) else {
                session.commitConfiguration()
                return
            }
            session.addInput(input)
            currentDeviceInput = input
            if let conn = videoOutput.connection(with: .video) {
                conn.videoOrientation = .portrait
                conn.isVideoMirrored = currentPosition == .front
            }
            session.commitConfiguration()
            session.startRunning()
            DispatchQueue.main.async {
                DebugOverlayState.shared.set("camera.position", self.currentPosition == .front ? "front" : "back")
            }
        }
    }

    /// 前置摄像头状态回调（供 FaceLandmarkService 同步 isFrontCamera）
    var faceServiceIsFrontCamera: ((Bool) -> Void)?

    func stop() { queue.async { [self] in session.stopRunning() } }

    /// 🔴 isActive 门控恢复：已授权且已配置前提下恢复 running。对标 Android `isActivePage` 门控——
    /// 首次配置/授权仍走 `checkAuthorizationAndStart`（内部 `start()` 会完整配置 session），
    /// 后续进出相机页只 toggle `startRunning`/`stopRunning`，避免反复 `beginConfiguration` 重配。
    func resume() { queue.async { [self] in if !session.isRunning { session.startRunning() } } }

    fileprivate func swapBuffer(_ pb: CVPixelBuffer, timestampMs: Int) {
        bufferLock.lock()
        currentPixelBuffer = pb
        currentTimestampMs = timestampMs
        bufferLock.unlock()
        frameCount += 1
        if frameCount == 1 { DispatchQueue.main.async { self.onFirstFrame?() } }
        // 🔴1: 帧回调——投递给人脸检测
        onFrame?(pb, timestampMs)
    }

    func readBuffer() -> CVPixelBuffer? {
        bufferLock.lock(); defer { bufferLock.unlock() }
        return currentPixelBuffer
    }

    /// 🔴 原子读 (pixelBuffer, 相机PTS毫秒)：渲染端用它查 latestWithinWindow，
    /// 与检测端 latest.timestampMs 同域（都来自 CMSampleBuffer PTS）→ 200ms 窗口 join 才成立。
    func readFrame() -> (CVPixelBuffer, Int)? {
        bufferLock.lock(); defer { bufferLock.unlock() }
        guard let pb = currentPixelBuffer else { return nil }
        return (pb, currentTimestampMs)
    }

    // MARK: - 手势控制（Task 19）

    func focus(at point: CGPoint) {
        queue.async { [self] in
            guard let device = (session.inputs.first as? AVCaptureDeviceInput)?.device else { return }
            // 🔴9: lockForConfiguration 失败时不能裸写 setter
            do {
                try device.lockForConfiguration()
            } catch {
                DispatchQueue.main.async {
                    DebugOverlayState.shared.set("camera.focus", "lock failed")
                }
                return
            }
            defer { device.unlockForConfiguration() }
            if device.isFocusPointOfInterestSupported {
                device.focusPointOfInterest = point
                device.focusMode = .autoFocus
            }
            if device.isExposurePointOfInterestSupported {
                device.exposurePointOfInterest = point
                device.exposureMode = .autoExpose
            }
            let focusStr = String(format: "%.2f, %.2f", point.x, point.y)
            DispatchQueue.main.async {
                DebugOverlayState.shared.set("camera.focus", focusStr)
            }
        }
    }

    func setZoom(_ factor: CGFloat) {
        queue.async { [self] in
            guard let device = (session.inputs.first as? AVCaptureDeviceInput)?.device else { return }
            // 🔴9
            guard (try? device.lockForConfiguration()) != nil else { return }
            defer { device.unlockForConfiguration() }
            device.videoZoomFactor = max(1.0, min(factor, device.activeFormat.videoMaxZoomFactor))
            let zoomStr = String(format: "%.1f", device.videoZoomFactor)
            DispatchQueue.main.async {
                DebugOverlayState.shared.set("camera.zoom", zoomStr)
            }
        }
    }

    func setExposureBias(_ bias: Float) {
        queue.async { [self] in
            guard let device = (session.inputs.first as? AVCaptureDeviceInput)?.device else { return }
            // 🔴9
            guard (try? device.lockForConfiguration()) != nil else { return }
            defer { device.unlockForConfiguration() }
            device.setExposureTargetBias(bias)
            let expStr = String(format: "%.2f", bias)
            DispatchQueue.main.async {
                DebugOverlayState.shared.set("camera.exposure", expStr)
            }
        }
    }
}

extension CaptureSessionController: AVCaptureVideoDataOutputSampleBufferDelegate {
    func captureOutput(_ output: AVCaptureOutput,
                       didOutput sampleBuffer: CMSampleBuffer,
                       from connection: AVCaptureConnection) {
        guard let pb = CMSampleBufferGetImageBuffer(sampleBuffer) else { return }
        let ts = CMSampleBufferGetPresentationTimeStamp(sampleBuffer)
        let tsMs = Int(CMTimeGetSeconds(ts) * 1000)
        swapBuffer(pb, timestampMs: tsMs)
    }
}
