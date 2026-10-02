import SwiftUI

/// 相机设置页（spec settings.yaml §4b camera，S3a 新增）。
/// 单组「相机状态记忆」（组标题 + 组描述）+ 重置行（确认对话框 OK/Cancel）。
///
/// iOS 相机状态记忆 = CameraPreviewView 的 UserDefaults 水合键（比例/网格/变焦/镜头/白平衡/
/// 色温/关键点与调试 overlay/美颜参数）；重置 = 删键，下次进相机按出厂默认水合。
/// 相机为 fullScreenCover 路由、不可能与设置页同屏，故「立即生效」语义自然成立。
struct CameraSettingsView: View {
    @Environment(\.colorScheme) private var cs
    private var s: SchemeColors { appScheme(cs) }
    @State private var showResetConfirm = false

    /// 相机状态记忆持久化键全集（CameraPreviewView 水合读、相机链路写回；删键即回出厂默认）。
    /// 不含 camera_use_mnn——默认引擎选择属本地模型设置域，不随相机状态重置。
    private static let cameraMemoryKeys = [
        "camera_ratio",             // 画面比例（默认 full）
        "camera_grid",              // 构图网格（默认 off）
        "camera_zoom_preset",       // 变焦预设（默认 1x）
        "camera_lens_front",        // 前后镜头（默认后置）
        "camera_wb_mode",           // 白平衡模式（默认 auto）
        "camera_color_temperature", // 自定义色温
        "camera_show_landmarks",    // 关键点 overlay（默认关）
        "camera_debug_overlay",     // 调试 overlay（默认关）
        "beauty_slim_debug",        // 瘦脸强度
        "beauty_bigeyes_debug",     // 大眼强度
        "beauty_warp_strength",     // 形变倍率（默认 1x）
    ]

    var body: some View {
        ScrollView {
            VStack(spacing: SettingsTokens.listSectionSpacing) {
                SettingsM3Section(
                    title: L("Camera State Memory"),
                    desc: L("Remember the last camera parameters and toggles on next launch.")
                ) {
                    SettingsListRow(
                        title: L("Reset Camera to First-Install Defaults"),
                        subtitle: L("Restore all camera parameters to first-install defaults, effective immediately."),
                        icon: .sf("arrow.counterclockwise"),
                        action: { showResetConfirm = true }
                    )
                }
            }
            .padding(.horizontal, Spacing.md)
            .padding(.vertical, Spacing.sm)
        }
        .background(s.background.ignoresSafeArea())
        .navigationTitle(L("Camera"))
        .navigationBarTitleDisplayMode(.inline)
        .alert(L("Reset Camera?"), isPresented: $showResetConfirm) {
            Button(L("OK")) { resetCameraMemory() }
            Button(L("Cancel"), role: .cancel) {}
        } message: {
            Text(L("Saved lens, mode, filter, beauty, ratio, zoom, exposure, white balance and grid settings will all be restored to defaults."))
        }
    }

    /// 重置 = 删除全部相机状态记忆键（键缺省即出厂默认，下次开相机水合生效）。
    private func resetCameraMemory() {
        for key in Self.cameraMemoryKeys {
            UserDefaults.standard.removeObject(forKey: key)
        }
    }
}

#Preview {
    NavigationStack { CameraSettingsView() }
}
