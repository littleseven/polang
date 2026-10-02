import SwiftUI

// MARK: - Issue Report Client（spec settings.yaml §2 report_issue_dialog.submit）
// POST https://api.polang.net/v1/report-issue：header X-App-Token + X-Platform=ios；
// body {category,title,description}；成功解析 resp.issueId，失败取 resp.error。

struct IssueReportResponse: Decodable {
    let issueId: Int?
    let error: String?
}

enum IssueReportError: LocalizedError {
    case server(String)
    case badResponse

    var errorDescription: String? {
        switch self {
        case .server(let message): return message
        case .badResponse: return "bad_response"
        }
    }
}

/// 上报问题提交客户端（URLSession async/await）。
/// 超时：read 30s；connect 15s 无独立 URLSession API，以 request 级 30s 超时近似。
struct IssueReportClient {
    static let shared = IssueReportClient()
    private let baseURL = URL(string: "https://api.polang.net")!

    /// 成功返回 issueId；HTTP 非 2xx 抛 IssueReportError.server(resp.error)。
    func submit(token: String, category: String, title: String, description: String) async throws -> Int {
        var req = URLRequest(url: baseURL.appendingPathComponent("v1/report-issue"))
        req.httpMethod = "POST"
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.setValue(token, forHTTPHeaderField: "X-App-Token")
        req.setValue("ios", forHTTPHeaderField: "X-Platform")
        req.timeoutInterval = 30
        req.httpBody = try JSONSerialization.data(withJSONObject: [
            "category": category,
            "title": title,
            "description": description,
        ])

        let (data, resp) = try await URLSession.shared.data(for: req)
        let status = (resp as? HTTPURLResponse)?.statusCode ?? -1
        let decoder = JSONDecoder()
        decoder.keyDecodingStrategy = .convertFromSnakeCase
        let decoded = try? decoder.decode(IssueReportResponse.self, from: data)
        guard (200..<300).contains(status) else {
            throw IssueReportError.server(decoded?.error ?? "HTTP \(status)")
        }
        guard let issueId = decoded?.issueId else { throw IssueReportError.badResponse }
        return issueId
    }
}

// MARK: - 主菜单「Report a problem」行（自包含：行 + 对话框 + 提交）

/// spec §2 report_issue_dialog：行题 = 对话框题 = report_issue_title；
/// guest（server_auth_token 空）→ 顶部错误文案 + 输入禁用 + 提交置灰；title 空白时提交同样置灰。
struct ReportIssueEntryView: View {
    /// 提交结果反馈（成功/失败文案回传宿主页 toast 呈现）。
    var onFeedback: (String) -> Void

    @AppStorage("server_auth_token") private var authToken = ""
    @State private var showDialog = false

    var body: some View {
        SettingsListRow(
            title: L("Report a problem"),
            icon: .mat("bug_report"),
            iconBlock: .vibrantPink,
            showChevron: true,
            action: { showDialog = true }
        )
        .sheet(isPresented: $showDialog) {
            ReportIssueDialog(
                onDismiss: { showDialog = false },
                onFeedback: onFeedback
            )
        }
    }
}

// MARK: - 上报对话框（category chips + title 单行 + description 多行 3-6 行）

/// Android AlertDialog 等价物：含多行输入的表单弹层按平台原生差异层取 sheet（medium/large detent）。
private struct ReportIssueDialog: View {
    let onDismiss: () -> Void
    let onFeedback: (String) -> Void

    @AppStorage("server_auth_token") private var authToken = ""
    @Environment(\.colorScheme) private var cs
    private var s: SchemeColors { appScheme(cs) }

    private enum Category: String, CaseIterable, Identifiable {
        case crash, bug, ai, other
        var id: String { rawValue }
        var label: String {
            switch self {
            case .crash: return L("Crash / Freeze")
            case .bug: return L("Function bug")
            case .ai: return L("AI reply error")
            case .other: return L("Other")
            }
        }
    }

    @State private var category: Category = .other
    @State private var titleInput = ""
    @State private var descriptionInput = ""
    @State private var submitting = false

    private var isGuest: Bool { authToken.isEmpty }
    private var trimmedTitle: String { titleInput.trimmingCharacters(in: .whitespacesAndNewlines) }
    private var canSubmit: Bool { !isGuest && !trimmedTitle.isEmpty && !submitting }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: Spacing.md) {
                    if isGuest {
                        Text(L("Please sign in to report issues"))
                            .font(AppTypography.bodySmall.font)
                            .foregroundColor(s.error)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }

                    Text(L("Category"))
                        .font(.system(size: SettingsTokens.listTitleFontSize, weight: .medium))
                        .foregroundColor(s.onSurface)
                    FlowLayout(spacing: Spacing.sm) {
                        ForEach(Category.allCases) { option in
                            SettingsM3Chip(
                                label: option.label,
                                isSelected: category == option,
                                isDisabled: isGuest
                            ) { category = option }
                        }
                    }

                    VStack(alignment: .leading, spacing: Spacing.xs) {
                        Text(L("Title"))
                            .font(.system(size: SettingsTokens.listTitleFontSize, weight: .medium))
                            .foregroundColor(s.onSurface)
                        TextField(L("Briefly describe the problem"), text: $titleInput)
                            .font(AppTypography.bodyMedium.font)
                            .padding(Spacing.md)
                            .background(s.surfaceContainerHigh)
                            .clipShape(AppShapes.small)
                            .disabled(isGuest)
                        if !isGuest && trimmedTitle.isEmpty {
                            Text(L("Please enter an issue title"))
                                .font(AppTypography.bodySmall.font)
                                .foregroundColor(s.error)
                        }
                    }

                    VStack(alignment: .leading, spacing: Spacing.xs) {
                        Text(L("Description"))
                            .font(.system(size: SettingsTokens.listTitleFontSize, weight: .medium))
                            .foregroundColor(s.onSurface)
                        Text(L("Steps to reproduce, expected vs actual"))
                            .font(AppTypography.bodySmall.font)
                            .foregroundColor(s.onSurfaceVariant)
                        TextEditor(text: $descriptionInput)
                            .font(AppTypography.bodyMedium.font)
                            .padding(Spacing.sm)
                            .background(s.surfaceContainerHigh)
                            .clipShape(AppShapes.small)
                            .frame(minHeight: AppTypography.bodyMedium.lineHeight * 3,
                                   maxHeight: AppTypography.bodyMedium.lineHeight * 6)
                            .disabled(isGuest)
                    }
                }
                .padding(Spacing.lg)
            }
            .background(s.background.ignoresSafeArea())
            .navigationTitle(L("Report a problem"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                // 提交中禁用取消（spec：提交中按钮文案 Submit… 且禁用取消）
                ToolbarItem(placement: .cancellationAction) {
                    Button(L("Cancel")) { onDismiss() }
                        .disabled(submitting)
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(submitting ? L("Submit") + "…" : L("Submit")) {
                        Task { await submit() }
                    }
                    .disabled(!canSubmit)
                }
            }
        }
        .presentationDetents([.medium, .large])
    }

    private func submit() async {
        guard canSubmit else { return }
        submitting = true
        defer { submitting = false }
        do {
            let issueId = try await IssueReportClient.shared.submit(
                token: authToken,
                category: category.rawValue,
                title: trimmedTitle,
                description: descriptionInput
            )
            onFeedback(String(format: L("Reported (#%1$d)"), issueId))
            onDismiss()
        } catch let error as IssueReportError {
            if case .server(let message) = error {
                onFeedback(String(format: L("Report failed: %1$@"), message))
            } else {
                onFeedback(L("Failed to submit, please try again later"))
            }
        } catch {
            onFeedback(L("Failed to submit, please try again later"))
        }
    }
}

#Preview {
    ReportIssueEntryView { _ in }
}
