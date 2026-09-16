import SwiftUI
import Photos
import AVFoundation
import AudioToolbox
import UIKit

/// 快门按钮（对标 Android CameraControls.kt:196-228）
/// 76pt 外径，4pt 白色边框，6pt 内边距，拍照=白填，录像=红填（Phase 6）
struct ShutterButton: View {
    let action: () -> Void

    @State private var isPressed = false
    @State private var lastClickTime: Date = .distantPast

    var body: some View {
        Button(action: tap) {
            Circle()
                .stroke(Color.white, lineWidth: 4)
                .frame(width: ShutterTokens.diameter, height: ShutterTokens.diameter)
                .overlay(
                    Circle()
                        .fill(isPressed ? Color.white.opacity(0.8) : Color.white)
                        .frame(width: ShutterTokens.innerDiameter, height: ShutterTokens.innerDiameter)
                )
                .scaleEffect(isPressed ? 0.9 : 1.0)
                .animation(.easeInOut(duration: 0.1), value: isPressed)
        }
        .accessibilityIdentifier("camera_shutter")
        .buttonStyle(.plain)
        .simultaneousGesture(
            DragGesture(minimumDistance: 0)
                .onChanged { _ in isPressed = true }
                .onEnded { _ in isPressed = false }
        )
    }

    private func tap() {
        // 500ms 防抖（对标 Android CameraControls.kt:210-214）
        let now = Date()
        guard now.timeIntervalSince(lastClickTime) > 0.5 else { return }
        lastClickTime = now
        // 快门反馈三件套（对标 Android: LONG_PRESS haptic + CLICK 音效；黑闪在 CameraPreviewView）
        UIImpactFeedbackGenerator(style: .medium).impactOccurred()
        AudioServicesPlaySystemSound(1108) // 系统拍照快门音
        action()
    }
}

/// 拍照保存管理器（触发系统 AddOnly 授权流）
enum PhotoSaver {
    /// 存 UIImage：EXIF 方向由系统正确写入（勿传 UIImage 的 .cgImage，会丢方向）
    static func saveToLibrary(_ image: UIImage) async throws {
        try await PHPhotoLibrary.shared().performChanges {
            PHAssetChangeRequest.creationRequestForAsset(from: image)
        }
    }

    static func saveToLibrary(_ image: CGImage) async throws {
        try await saveToLibrary(UIImage(cgImage: image))
    }
}

/// 拍照流程封装（capture → renderToImage → save，全异步）
@MainActor
final class CaptureFlow: ObservableObject {
    @Published var isCapturing = false
    @Published var lastError: String?

    private let photoController: PhotoCaptureController
    private let renderer: BeautyRenderer?
    /// 拍照裁剪目标 h/w（nil=不裁 FULL；4:3/16:9 居中裁到目标比例）
    private let cropHPerW: CGFloat?

    /// 保存成功回调（主线程，供相机页刷新相册入口缩略图）
    var onSaved: (() -> Void)?
    /// 拍照/保存失败回调（主线程；相机页用于头像拍摄失败收尾，对齐 Android finish(success=false)）
    var onFailure: (() -> Void)?

    init(photoController: PhotoCaptureController, renderer: BeautyRenderer?, cropHPerW: CGFloat? = nil) {
        self.photoController = photoController
        self.renderer = renderer
        self.cropHPerW = cropHPerW
    }

    /// 居中裁剪到目标 h/w 比例（对标 Android 拍照 aspect crop）
    private func croppedToRatio(_ img: CGImage) -> CGImage {
        guard let hPerW = cropHPerW else { return img }
        let w = CGFloat(img.width), h = CGFloat(img.height)
        let cur = h / w
        if abs(cur - hPerW) < 0.01 { return img }
        var cw = w, ch = h
        if cur > hPerW { ch = w * hPerW } else { cw = h / hPerW }
        let rect = CGRect(x: (w - cw) / 2, y: (h - ch) / 2, width: cw, height: ch)
        return img.cropping(to: rect) ?? img
    }

    func captureAndSave() {
        guard !isCapturing else { return }
        isCapturing = true
        let shutterStart = Date()
        DebugOverlayState.shared.set("camera.shutter", "capturing")
        print("[PoLang] shutter.pressed at \(shutterStart.timeIntervalSince1970)")

        Task {
            defer {
                // 只复位忙碌标志——终态(saved/error)保留上屏供自动化/肉眼验收，下次拍照由 capturing 覆盖
                Task { @MainActor in self.isCapturing = false }
            }

            guard let photo = await photoController.capture() else {
                print("[PoLang] shutter.FAIL: capture() returned nil")
                await setError("capture failed")
                return
            }
            let captureMs = Date().timeIntervalSince(shutterStart) * 1000
            print("[PoLang] shutter.captured in \(String(format: "%.1f", captureMs))ms")

            guard let pixelBuffer = PhotoCaptureController.pixelBuffer(from: photo) else {
                print("[PoLang] shutter.FAIL: pixelBuffer conversion nil")
                await setError("pixel buffer failed")
                return
            }

            let renderStart = Date()
            let renderedImage = await Task.detached(priority: .userInitiated) { [renderer] () -> CGImage? in
                renderer?.renderToImage(pixelBuffer: pixelBuffer)
            }.value
            let renderMs = Date().timeIntervalSince(renderStart) * 1000

            guard let image = renderedImage else {
                print("[PoLang] shutter.FAIL: renderToImage nil → 兜底保存原图")
                // 兜底：美颜渲染失败不丢照片——直接保存未美颜原图
                // （传 UIImage 保 EXIF 方向；renderToImage 出的 CGImage 已归一化，方向正确）
                if let data = photo.fileDataRepresentation(),
                   let uiImage = UIImage(data: data) {
                    do {
                        try await PhotoSaver.saveToLibrary(uiImage)
                        print("[PoLang] shutter.SAVED(raw) 原图兜底")
                        DebugOverlayState.shared.set("camera.shutter", "saved(raw)")
                        onSaved?()
                    } catch {
                        print("[PoLang] shutter.SAVE_ERROR(raw): \(error.localizedDescription)")
                        await setError("save raw: \(error.localizedDescription)")
                    }
                } else {
                    await setError("render failed")
                }
                return
            }
            print("[PoLang] shutter.rendered in \(String(format: "%.1f", renderMs))ms → CGImage \(image.width)x\(image.height)")

            // 保存前确保 AddOnly 授权（被拒直接报错上屏，不静默失败）
            var addStatus = PHPhotoLibrary.authorizationStatus(for: .addOnly)
            if addStatus == .notDetermined {
                addStatus = await PHPhotoLibrary.requestAuthorization(for: .addOnly)
            }
            guard addStatus == .authorized || addStatus == .limited else {
                print("[PoLang] shutter.SAVE_DENIED: addOnly status=\(addStatus.rawValue)")
                await setError("photos add permission denied")
                return
            }

            do {
                try await PhotoSaver.saveToLibrary(croppedToRatio(image))
                let totalMs = Date().timeIntervalSince(shutterStart) * 1000
                print("[PoLang] shutter.SAVED total=\(String(format: "%.1f", totalMs))ms (capture+\(String(format: "%.1f", captureMs))+render+\(String(format: "%.1f", renderMs)))")
                DebugOverlayState.shared.set("camera.shutter", "saved")
                onSaved?()
            } catch {
                print("[PoLang] shutter.SAVE_ERROR: \(error.localizedDescription)")
                await setError("save: \(error.localizedDescription)")
            }
        }
    }

    @MainActor
    private func setError(_ msg: String) {
        lastError = msg
        DebugOverlayState.shared.set("camera.shutter", "error: \(msg)")
        onFailure?()
    }
}
