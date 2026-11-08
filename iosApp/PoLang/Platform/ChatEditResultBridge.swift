import Foundation

/// 编辑器 → chat 回链：`PhotoEditorScreen`（MainTabView fullScreenCover）完成保存后，
/// 把编辑结果文件路径经本桥送回 `ChatViewModel` 追加 EDIT_RESULT 消息。
///
/// 范式同 `ChartRendererBridge.onChart`（宿主页面持不到 ChatViewModel 的 @StateObject，
/// 用静态闭包反向注入；ChatViewModel.configure 设置，MainTabView 调用）。
enum ChatEditResultBridge {
    /// 编辑结果图片文件路径（Documents/chat_edits/xxx.jpg）。
    static var onEditResult: ((String) -> Void)?
}
