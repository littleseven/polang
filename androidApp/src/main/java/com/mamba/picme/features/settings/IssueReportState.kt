package com.mamba.picme.features.settings

/** 问题上报 UI 状态（自 ChatViewModel 迁入，入口移至设置页后由 [ReportIssueEntry] 自持有）。 */
internal sealed interface IssueReportState {
    data object Idle : IssueReportState
    data object Submitting : IssueReportState
    data class Success(val issueId: Int) : IssueReportState
    data class Error(val message: String) : IssueReportState
}
