import SwiftUI

// MARK: - OpenUI 卡内视觉 token（对齐 androidApp OpenUiCardTokens，PARITY 双端同刻度）

/// 字阶 / 间距阶梯 / 图表色板 / 分隔线 / 圆角 / tone 色板 / 图标 SF Symbols 映射。
/// 视觉细节 SSOT = Android 定稿 tokens；iOS 侧为镜像常量（与 ChatBubbleTokens 同层，不进
/// design-tokens.json 生成物——OpenUI 渲染器专属样式面，双端各自收口）。
enum OpenUiTokens {

    // ── 字阶（HEADLINE 20/Bold、SUBHEAD 15/SemiBold、BODY 14、CAPTION 12、DISPLAY 32 Bold tnum） ──

    static func font(role: String?) -> Font {
        switch role {
        case "HEADLINE": return .system(size: 20, weight: .bold)
        case "SUBHEAD": return .system(size: 15, weight: .semibold)
        case "CAPTION": return .system(size: 12)
        case "DISPLAY": return .system(size: 32, weight: .bold).monospacedDigit()
        default: return .system(size: 14)
        }
    }

    static func lineHeight(role: String?) -> CGFloat {
        switch role {
        case "HEADLINE": return 26
        case "SUBHEAD": return 21
        case "CAPTION": return 16
        case "DISPLAY": return 38
        default: return 20
        }
    }

    // ── 间距阶梯（七档 t-shirt scale + legacy 别名，8pt 网格） ──

    static func spacing(_ raw: String?) -> CGFloat {
        switch raw {
        case "none": return 0
        case "xs", "COMPACT": return 4
        case "s": return 8
        case "l", "COMFORTABLE": return 16
        case "xl": return 24
        case "2xl": return 32
        default: return 12 // m / STANDARD / 缺省 / 未知
        }
    }

    // ── 细节 token ──

    static let dividerAlpha: Double = 0.08
    static let imageCornerRadius: CGFloat = 12
    static let barCornerRadius: CGFloat = 4
    static let barTrackAlpha: Double = 0.06
    static let pieRingWidth: CGFloat = 26
    static let photoGridGap: CGFloat = 2
    static let photoGridCornerRadius: CGFloat = 12
    static let avatarRowSize: CGFloat = 56
    static let timelineThumbSize: CGFloat = 48
    static let listLeadingImageSize: CGFloat = 48

    /// 图表 6 色相离散色板（蓝/橙/绿/紫/红/青，Material 300 系 tone——深底可读）。
    static let chartPalette: [Color] = [
        Color(red: 0x64 / 255, green: 0xB5 / 255, blue: 0xF6 / 255),
        Color(red: 0xFF / 255, green: 0xB7 / 255, blue: 0x4D / 255),
        Color(red: 0x81 / 255, green: 0xC7 / 255, blue: 0x84 / 255),
        Color(red: 0xBA / 255, green: 0x68 / 255, blue: 0xC8 / 255),
        Color(red: 0xE5 / 255, green: 0x73 / 255, blue: 0x73 / 255),
        Color(red: 0x4D / 255, green: 0xD0 / 255, blue: 0xE1 / 255),
    ]
    static var chartSingleSeries: Color { chartPalette[0] }

    // ── tone / variant 色板（Tag/Callout/TextCallout 等语义容器色） ──

    struct ToneColors { let fg: Color; let bg: Color }

    /// 语义 tone（统一收小写官方五档 + Callout 大写五档同色板）。
    static func tone(_ raw: String?) -> ToneColors {
        switch raw?.lowercased() {
        case "info":
            return ToneColors(fg: Color(red: 0x1B / 255, green: 0x6C / 255, blue: 0xE8 / 255),
                              bg: Color(red: 0x64 / 255, green: 0xB5 / 255, blue: 0xF6 / 255).opacity(0.18))
        case "success":
            return ToneColors(fg: Color(red: 0x2E / 255, green: 0x7D / 255, blue: 0x4F / 255),
                              bg: Color(red: 0x81 / 255, green: 0xC7 / 255, blue: 0x84 / 255).opacity(0.18))
        case "warning", "warn":
            return ToneColors(fg: Color(red: 0xB2 / 255, green: 0x6A / 255, blue: 0x00 / 255),
                              bg: Color(red: 0xFF / 255, green: 0xB7 / 255, blue: 0x4D / 255).opacity(0.20))
        case "error", "danger":
            return ToneColors(fg: Color(red: 0xC6 / 255, green: 0x28 / 255, blue: 0x28 / 255),
                              bg: Color(red: 0xEF / 255, green: 0x9A / 255, blue: 0x9A / 255).opacity(0.20))
        default: // neutral / NEUTRAL / 缺省
            return ToneColors(fg: Color(.label).opacity(0.75),
                              bg: Color(.secondarySystemBackground))
        }
    }

    /// colorRole（Text/Icon 语义色板：PRIMARY/SURFACE/ERROR/ON_SURFACE）。
    static func colorRole(_ raw: String?) -> Color {
        switch raw {
        case "PRIMARY": return .accentColor
        case "SURFACE": return Color(.secondarySystemBackground)
        case "ERROR": return .red
        default: return Color(.label)
        }
    }

    // ── 图标名 → SF Symbols（59 枚举词表 OpenUiLibrary.iconNames 的 iOS 语义映射） ──

    static func systemImage(named name: String?) -> String {
        switch name {
        case "accountCircle": return "person.crop.circle"
        case "add": return "plus"
        case "arrowBack": return "chevron.left"
        case "arrowForward": return "chevron.right"
        case "attachFile": return "paperclip"
        case "calendarToday", "event": return "calendar"
        case "call": return "phone"
        case "camera": return "camera.fill"
        case "check": return "checkmark"
        case "close": return "xmark"
        case "delete": return "trash"
        case "download": return "arrow.down.circle"
        case "edit": return "pencil"
        case "error": return "exclamationmark.circle"
        case "fastForward": return "forward.fill"
        case "favorite": return "heart.fill"
        case "favoriteOff": return "heart"
        case "folder": return "folder"
        case "help": return "questionmark.circle"
        case "home": return "house"
        case "info": return "info.circle"
        case "locationOn": return "mappin"
        case "lock": return "lock"
        case "lockOpen": return "lock.open"
        case "mail": return "envelope"
        case "menu": return "line.3.horizontal"
        case "moreHoriz": return "ellipsis"
        case "moreVert": return "ellipsis"
        case "notifications": return "bell"
        case "notificationsOff": return "bell.slash"
        case "pause": return "pause.fill"
        case "payment": return "creditcard"
        case "person": return "person.fill"
        case "phone": return "phone.fill"
        case "photo": return "photo"
        case "play": return "play.fill"
        case "print": return "printer"
        case "refresh": return "arrow.clockwise"
        case "rewind": return "backward.fill"
        case "search": return "magnifyingglass"
        case "send": return "paperplane.fill"
        case "settings": return "gearshape"
        case "share": return "square.and.arrow.up"
        case "shoppingCart": return "cart"
        case "skipNext": return "forward.end.fill"
        case "skipPrevious": return "backward.end.fill"
        case "star": return "star.fill"
        case "starHalf": return "star.leadinghalf.filled"
        case "starOff": return "star"
        case "stop": return "stop.fill"
        case "upload": return "arrow.up.circle"
        case "visibility": return "eye"
        case "visibilityOff": return "eye.slash"
        case "volumeDown": return "speaker.wave.1"
        case "volumeMute", "volumeOff": return "speaker.slash"
        case "volumeUp": return "speaker.wave.3"
        case "warning": return "exclamationmark.triangle"
        default: return "circle"
        }
    }
}
