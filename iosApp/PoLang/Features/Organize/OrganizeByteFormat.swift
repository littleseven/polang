import Foundation

/// 整理中心字节格式化单一工具（🟡-12 收口：原 Hub/OrganizeHubView.OrganizeByteFormat 与
/// OrganizeCategoryScreen.OrganizeByteFormatter 两处重复实现合并，SwipeReviewScreen 的
/// SwipeByteFormatter 硬编码 GB/MB/KB 亦并入——fr 等语言 ByteCountFormatter 产出 Go/Mo/Ko，
/// 满足 [I18N]）。
/// 对齐 Android formatBytes 口径：自适应单位；0 值兜底 "0 B"（ByteCountFormatter 0 值文案不可控）。
enum OrganizeByteFormat {
    private static let formatter: ByteCountFormatter = {
        let f = ByteCountFormatter()
        f.countStyle = .file
        f.isAdaptive = true
        return f
    }()

    static func string(_ bytes: Int64) -> String {
        bytes > 0 ? formatter.string(fromByteCount: bytes) : "0 B"
    }
}
