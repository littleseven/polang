import SwiftUI

/// SF Symbol → Material Icon 映射
/// 统一双端字形：iOS 使用 Material Icons Round（与 Android Icons.Rounded.* 同源）
extension Image {
    init(matIcon sfName: String) {
        let matName = MaterialIconMap.map[sfName] ?? sfName
        self.init(matName, label: Text(matName))
    }
}

enum MaterialIconMap {
    static let map: [String: String] = [
        // Camera
        "chevron.left":             "mat_arrow_back",
        "arrow.clockwise":          "mat_refresh",
        "wand.and.stars":           "mat_autofix",
        "aspectratio":              "mat_aspect_ratio",
        "square.grid.3x3":          "mat_grid_on",
        "mountain.2":               "mat_landscape",
        "circle.lefthalf.filled":   "mat_filter_b_and_w",
        "slider.horizontal.3":      "mat_tune",
        "camera.rotate":            "mat_cameraswitch",
        "checkmark":                "mat_check",
        "check":                    "mat_check",
        "delete":                   "mat_delete",
        "photo.fill":               "mat_photo_library",
        "lock.fill":                "mat_lock",
        // Beauty panel
        "face.smiling":             "mat_face",
        "paintpalette":             "mat_color_lens",
        "eye":                      "mat_visibility",
        "person.crop.rectangle":    "mat_face_retouching",
        // FloatingBottomTab + Settings + Gallery
        "camera":                   "mat_camera_alt",
        "camera.fill":              "mat_camera_alt",
        "arrow.down.circle":        "mat_download",
        "download":                 "mat_download",
        "bubble.left":              "mat_chat_bubble",
        "bubble.left.fill":         "mat_chat_bubble",
        "tag":                      "mat_sell",
        "tag.fill":                 "mat_sell",
        "person.2":                 "mat_account_circle",
        "person.2.fill":            "mat_account_circle",
        "person.circle":            "mat_account_circle",
        "gearshape":                "mat_settings",
        "gearshape.fill":           "mat_settings",
        "magnifyingglass":          "mat_search",
        "checkmark.circle":         "mat_check_circle",
        "checkmark.circle.fill":    "mat_check_circle",
        "xmark":                    "mat_close",
        "close":                    "mat_close",
        "xmark.circle.fill":        "mat_close",
        "trash":                    "mat_delete",
        "trash.fill":               "mat_delete",
        "square.and.arrow.up":      "mat_share",
        "square.and.arrow.up.fill": "mat_share",
        "info.circle":              "mat_info",
        "info.circle.fill":         "mat_info",
        "ellipsis":                 "mat_more_horiz",
        "ellipsis.circle":          "mat_more_horiz",
        "play":                     "mat_play_arrow",
        "play.fill":                "mat_play_arrow",
        "play_arrow":               "mat_play_arrow",
        "play.circle":              "mat_play_circle",
        "play.circle.fill":         "mat_play_circle",
        "pause":                    "mat_pause",
        "pause.fill":               "mat_pause",
        "arrow.clockwise.circle":   "mat_replay",
        "checkmark.square":         "mat_select_all",
        "plus":                     "mat_add",
        "plus.circle":              "mat_add",
        "paperplane":               "mat_send",
        "paperplane.fill":          "mat_send",
        "line.3.horizontal.decrease": "mat_sort",
        "arrow.up.arrow.down":      "mat_sort",
        "doc.text":                 "mat_text_snippet",
        "sparkles":                 "mat_auto_awesome",
        "icloud.and.arrow.down":    "mat_cloud_download",
        "exclamationmark.triangle": "mat_error",
        "hourglass":                "mat_hourglass_empty",
        "circle.dashed":            "mat_radio_button_unchecked",
        "circle":                   "mat_radio_button_unchecked",
        "doc.on.doc":               "mat_content_copy",
        "doc.on.doc.fill":          "mat_content_copy",
        "arrow.triangle.2.circlepath": "mat_update",
        "creditcard":               "mat_badge",
        "xmark.circle":             "mat_cancel",
        // Settings category icons (direct name → mat_ prefix, 对齐 Android Icons.Rounded.*)
        "smart_toy":      "mat_smart_toy",
        "psychology":     "mat_psychology",
        "account_circle": "mat_account_circle",
        "forum":          "mat_forum",
        "photo_library":  "mat_photo_library",
        "camera_alt":     "mat_camera_alt",
        "cloud_download": "mat_cloud_download",
        "terminal":       "mat_terminal",
        "storage":        "mat_storage",
        "privacy_tip":    "mat_privacy_tip",
        "lock":           "mat_lock",
        "arrow_forward":  "mat_arrow_forward",
        "person":         "mat_person",
        "bug_report":     "mat_bug_report",
    ]
}

/// 渲染 Material 图标（template mode + 固定尺寸）
struct MatIcon: View {
    let name: String
    var size: CGFloat = 18

    var body: some View {
        Image(matIcon: name)
            .renderingMode(.template)
            .resizable()
            .aspectRatio(contentMode: .fit)
            .frame(width: size, height: size)
    }
}
