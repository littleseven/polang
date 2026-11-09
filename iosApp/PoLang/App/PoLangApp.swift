import SwiftUI

@main
struct PoLangApp: App {
    @StateObject private var container = AppContainer.shared
    @StateObject private var settings = AppSettings.shared

    init() {
        // -serverAuthToken <token>：测试注入登录态（DEBUG-only；邮箱验证码流程无法全自动，
        // token 复用生产 account 表 token_plain——test@polang.net，Android 端 debug 同源）
        #if DEBUG
        let launchArgs = ProcessInfo.processInfo.arguments
        if let idx = launchArgs.firstIndex(of: "-serverAuthToken"), idx + 1 < launchArgs.count {
            UserDefaults.standard.set(launchArgs[idx + 1], forKey: "server_auth_token")
        }
        #endif
    }

    var body: some Scene {
        WindowGroup {
            MainTabView()
                .environmentObject(container)
                .environmentObject(settings)
                .preferredColorScheme(settings.colorScheme)
                .environment(\.locale, settings.locale)
        }
    }
}

/// 全局应用设置（主题 + 语言），ObservableObject 确保变更全 App 即时生效。
/// 所有页面用 @EnvironmentObject AppSettings 读取，用 @AppStorage 持久化。
final class AppSettings: ObservableObject {
    static let shared = AppSettings()

    @Published var themeMode: String {
        didSet { UserDefaults.standard.set(themeMode, forKey: "theme_mode") }
    }
    @Published var appLanguage: String {
        didSet {
            UserDefaults.standard.set(appLanguage, forKey: "app_language")
            LanguageManager.shared.currentLanguage = appLanguage
        }
    }

    var colorScheme: ColorScheme? {
        switch themeMode {
        case "light": return .light
        case "dark": return .dark
        default: return nil
        }
    }

    var locale: Locale {
        switch appLanguage {
        case "english": return Locale(identifier: "en")
        case "chinese_simplified": return Locale(identifier: "zh-Hans")
        case "chinese_traditional": return Locale(identifier: "zh-Hant")
        case "spanish": return Locale(identifier: "es")
        case "french": return Locale(identifier: "fr")
        default: return Locale.current
        }
    }

    private init() {
        themeMode = UserDefaults.standard.string(forKey: "theme_mode") ?? "system"
        appLanguage = UserDefaults.standard.string(forKey: "app_language") ?? "system"
        // 初始化语言管理器（触发 Bundle swizzling + 恢复上次选择）
        _ = LanguageManager.shared
        // -galleryFace：自动拉真实相册照片验证人脸检测（裁决 UIImage 朝向假设），无需 UI 导航
        if ProcessInfo.processInfo.arguments.contains("-galleryFace") {
            Task.detached(priority: .utility) { await GalleryFaceAutoCheck.run() }
        }
        // -tagScan：自动触发 Pass1 增量扫描（扫描诊断用；走与 UI 相同的 start 路径，MainActor）
        if ProcessInfo.processInfo.arguments.contains("-tagScan") {
            Task.detached(priority: .utility) {
                try? await Task.sleep(nanoseconds: 2_000_000_000)
                await MainActor.run { TagScanOrchestrator.shared.start(mode: .incremental) }
            }
        }
    }
}
