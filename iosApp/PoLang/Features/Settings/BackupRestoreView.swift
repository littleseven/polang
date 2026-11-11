import SwiftUI
import UniformTypeIdentifiers

// MARK: - 备份导出文件载荷

/// SwiftUI fileExporter 载荷：内存 Data → 用户选定位置落盘（iOS 16 无 URL item 重载，
/// FileDocument 通道等价实现「构建 → 选位置」两段式，替代 Android SAF CreateDocument）。
struct BackupExportDocument: FileDocument {
    static let readableContentTypes: [UTType] = [.json]

    let data: Data

    init(data: Data) {
        self.data = data
    }

    init(configuration: ReadConfiguration) throws {
        data = configuration.file.regularFileContents ?? Data()
    }

    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper {
        FileWrapper(regularFileWithContents: data)
    }
}

// MARK: - 备份恢复页

/// 备份恢复页（对齐 Android BackupRestoreActivity 交互：说明 + 导出/导入双按钮 +
/// busy 态（按钮置灰 + ProgressView）+ 完成后多行状态汇总）。
/// 平台差异（C7 台账）：文件通道用 SwiftUI 原生 fileExporter/fileImporter；
/// 恢复前多一道确认 alert（HIG，Android 无）。
struct BackupRestoreView: View {
    @Environment(\.colorScheme) private var cs
    private var s: SchemeColors { appScheme(cs) }

    @State private var isBusy = false
    @State private var busyText: String?
    @State private var statusText: String?
    @State private var statusIsError = false
    @State private var showExporter = false
    @State private var showImporter = false
    @State private var showRestoreConfirm = false
    /// fileImporter 选取即读出的备份内容（安全作用域内先行读取，确认后直接消费，避免二次取权）
    @State private var pendingImportData: Data?
    /// 导出载荷与文件名（构建完成后驱动 fileExporter）
    @State private var exportDocument: BackupExportDocument?
    @State private var exportFilename = "picme_tag_backup.json"
    @State private var pendingExportSummary: BackupExportSummary?

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: SettingsTokens.listSectionSpacing) {
                VStack(alignment: .leading, spacing: Spacing.sm) {
                    Text(L("Backup & Restore"))
                        .font(AppTypography.titleSmall.font)
                        .foregroundColor(s.onSurface)
                    Text(L("Export or import TAG scans, face clusters, OCR and settings."))
                        .font(AppTypography.bodySmall.font)
                        .foregroundColor(s.onSurfaceVariant)
                    SettingsListSection {
                        VStack(spacing: 0) {
                            exportButton
                            SettingsListDivider()
                            importButton
                        }
                    }
                }
                if isBusy { busyRow }
                if statusText != nil { statusRow }
            }
            .padding(.horizontal, Spacing.md)
            .padding(.vertical, Spacing.sm)
        }
        .background(s.background.ignoresSafeArea())
        .navigationTitle(L("Backup & Restore"))
        .navigationBarTitleDisplayMode(.inline)
        .fileExporter(
            isPresented: $showExporter,
            document: exportDocument,
            contentType: .json,
            defaultFilename: exportFilename
        ) { result in
            handleExportCompletion(result)
        }
        .fileImporter(isPresented: $showImporter, allowedContentTypes: [.json]) { result in
            handleImportPick(result)
        }
        .confirmationDialog(L("Import Backup"), isPresented: $showRestoreConfirm, titleVisibility: .visible) {
            Button(L("Import")) { startRestore() }
            Button(L("Cancel"), role: .cancel) { pendingImportData = nil }
        } message: {
            Text(L("Importing will merge the backup into your data. Existing entries with the same name will be overwritten."))
        }
    }

    // MARK: 操作按钮（busy 态置灰互斥，导出/导入不可并发）

    private var exportButton: some View {
        actionButton(
            title: L("Export Backup"),
            systemIcon: "icloud.and.arrow.up",
            action: startExport
        )
    }

    private var importButton: some View {
        actionButton(
            title: L("Import Backup"),
            systemIcon: "icloud.and.arrow.down",
            action: { showImporter = true }
        )
    }

    private func actionButton(title: String, systemIcon: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: Spacing.sm) {
                Image(systemName: systemIcon)
                    .font(.system(size: 16, weight: .medium))
                    .foregroundColor(s.primary)
                Text(title)
                    .font(.system(size: SettingsTokens.listTitleFontSize))
                    .foregroundColor(s.onSurface)
                Spacer()
                if isBusy { ProgressView() }
            }
            .frame(minHeight: SettingsTokens.listRowHeight)
            .padding(.horizontal, SettingsTokens.listRowPaddingH)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(isBusy)
        .opacity(isBusy ? 0.55 : 1)
    }

    private var busyRow: some View {
        HStack(spacing: Spacing.sm) {
            ProgressView()
            Text(busyText ?? "")
                .font(AppTypography.bodySmall.font)
                .foregroundColor(s.onSurfaceVariant)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, Spacing.md)
    }

    private var statusRow: some View {
        Text(statusText ?? "")
            .font(AppTypography.bodySmall.font)
            .foregroundColor(statusIsError ? s.error : s.onSurfaceVariant)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, Spacing.md)
    }

    // MARK: 导出流（构建 → fileExporter 选位置落盘）

    private func startExport() {
        guard !isBusy else { return }
        isBusy = true
        busyText = L("Exporting backup…")
        statusText = nil
        Task {
            do {
                // 后台一次性构建整个容器 JSON（v1 口径；流式导出留优化）
                let exported = try await Task.detached(priority: .userInitiated) {
                    try BackupRestoreService.shared.exportBackup()
                }.value
                exportDocument = BackupExportDocument(data: exported.data)
                exportFilename = exported.filename
                pendingExportSummary = exported.summary
                showExporter = true
            } catch {
                statusIsError = true
                statusText = String(format: L("Export failed: %1$@"), error.localizedDescription)
            }
            isBusy = false
            busyText = nil
        }
    }

    private func handleExportCompletion(_ result: Result<URL, Error>) {
        switch result {
        case .success:
            statusIsError = false
            var text = L("Backup exported successfully")
            if let summary = pendingExportSummary {
                text += "\n" + String(
                    format: L("Tags %1$d · Media %2$d · Persons %3$d\nSessions %4$d · Messages %5$d"),
                    summary.tagCount, summary.mediaCount, summary.personCount,
                    summary.chatSessionCount, summary.chatMessageCount)
            }
            statusText = text
        case .failure(let error):
            // 用户取消导出不算失败，静默回落
            if error is CancellationError { break }
            statusIsError = true
            statusText = String(format: L("Export failed: %1$@"), error.localizedDescription)
        }
        pendingExportSummary = nil
    }

    // MARK: 导入流（fileImporter 选取 → 确认 alert → 恢复）

    private func handleImportPick(_ result: Result<URL, Error>) {
        switch result {
        case .success(let url):
            let scoped = url.startAccessingSecurityScopedResource()
            defer { if scoped { url.stopAccessingSecurityScopedResource() } }
            if let data = try? Data(contentsOf: url) {
                pendingImportData = data
                showRestoreConfirm = true
            } else {
                statusIsError = true
                statusText = String(format: L("Backup file not readable: %1$@"), url.lastPathComponent)
            }
        case .failure:
            break // 用户取消选取
        }
    }

    private func startRestore() {
        guard let data = pendingImportData, !isBusy else { return }
        pendingImportData = nil
        isBusy = true
        busyText = L("Importing backup…")
        statusText = nil
        Task {
            do {
                let summary = try await Task.detached(priority: .userInitiated) {
                    try BackupRestoreService.shared.restoreBackup(from: data)
                }.value
                statusIsError = false
                statusText = String(format: L("Restore complete. Matched %1$d media."), summary.matchedMediaCount)
                    + "\n" + String(
                        format: L("Matched %1$d · Unmatched %2$d\nTags %3$d · Persons %4$d\nTasks %5$d · Embeddings %6$d\nSessions %7$d · Messages %8$d\nFeedback %9$d"),
                        summary.matchedMediaCount, summary.unmatchedUriCount,
                        summary.restoredTagCount, summary.restoredPersonCount,
                        summary.restoredScanTaskCount, summary.restoredFaceEmbeddingCount,
                        summary.restoredChatSessionCount, summary.restoredChatMessageCount,
                        summary.restoredMediaFeedbackCount)
            } catch {
                statusIsError = true
                statusText = String(format: L("Import failed: %1$@"), error.localizedDescription)
            }
            isBusy = false
            busyText = nil
        }
    }
}
