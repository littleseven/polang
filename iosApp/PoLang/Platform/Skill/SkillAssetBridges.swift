import Foundation
import SharedKit

/// `IosSkillTransportBridge` 的实现（URLSession async/await + 回调适配）：
/// GET picme-server `/v1/skills[/{name}]`，语义对齐 Android `SkillClient`（OkHttp，
/// 2026-11-08 端侧 load_skill 执行器 iOS 接线）。
///
/// SharedBridge 铁律（同 `UserTaskStoreBridge` / `PhSearchBridge`）：方法绝不抛异常
/// 跨边界，且 callback **必须**被调用（成功或失败）——网络失败 / 非 2xx / 空 body
/// 一律以 nil 回调（Kotlin 侧 `SkillRepository` 据此回落本地缓存，stale-while-revalidate）。
///
/// 鉴权（与 Android SkillClient 同口径）：X-App-Token（UserDefaults `server_auth_token`
/// 调用时读取——登录后生效）+ X-Device-Id（构造注入，App 启动期 DeviceIdStore 取得）
/// + X-Platform: ios。未登录仅带设备标识。
@objc final class SkillTransportBridge: NSObject, IosSkillTransportBridge {

    private let baseURL = URL(string: "https://api.polang.net")!
    private let deviceId: String

    /// 超时对齐 Android SkillClient（connect 10s / read 20s）；URLSession 无独立 connect
    /// 超时 API，以 request 级 20s 近似（IssueReportClient 同口径注释）。ephemeral：无磁盘
    /// 缓存干扰（skill 自有版本化缓存层，勿让 URLCache 叠床架屋）。
    private let session: URLSession = {
        let config = URLSessionConfiguration.ephemeral
        config.timeoutIntervalForRequest = 20
        config.timeoutIntervalForResource = 30
        return URLSession(configuration: config)
    }()

    init(deviceId: String) {
        self.deviceId = deviceId
        super.init()
    }

    /// path 形如 `/v1/skills` / `/v1/skills/{name}`（Kotlin 侧拼好；name 已过
    /// NAME_REGEX 闸 `^[a-z0-9][a-z0-9-]{0,63}$`，无 URL 注入面）。
    func fetch(path: String, callback: @escaping (String?) -> Void) {
        guard let url = URL(string: path, relativeTo: baseURL)?.absoluteURL else {
            callback(nil)
            return
        }
        var req = URLRequest(url: url)
        req.httpMethod = "GET"
        if let token = UserDefaults.standard.string(forKey: "server_auth_token"), !token.isEmpty {
            req.setValue(token, forHTTPHeaderField: "X-App-Token")
        }
        req.setValue(deviceId, forHTTPHeaderField: "X-Device-Id")
        req.setValue("ios", forHTTPHeaderField: "X-Platform")
        Task.detached(priority: .utility) { [session] in
            do {
                let (data, resp) = try await session.data(for: req)
                let status = (resp as? HTTPURLResponse)?.statusCode ?? -1
                // 空白判定对齐 Android SkillClient 的 isNotBlank()（回传原文而非 trim 后文本）
                guard (200..<300).contains(status),
                      let body = String(data: data, encoding: .utf8),
                      !body.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
                    callback(nil)
                    return
                }
                callback(body)
            } catch {
                callback(nil)
            }
        }
    }
}

/// `IosSkillCacheBridge` 的实现：Documents/skill_cache/snapshot.json 单文件 JSON 缓存，
/// 语义对齐 Android `FileSkillCacheStore`（filesDir/skill_cache/snapshot.json）。
/// 序列化收口在 shared `SkillCodec`——本类是刻意无知的读写管道。
///
/// 铁律：读失败返回 nil、写失败返回 false，绝不抛异常跨边界（缓存丢失不致命，
/// Kotlin 侧下次同步重建）；`.atomic` 原子写（Android tmp+rename 的 iOS 对等物）。
@objc final class SkillCacheBridge: NSObject, IosSkillCacheBridge {

    private let fileURL: URL = FileManager.default
        .urls(for: .documentDirectory, in: .userDomainMask)[0]
        .appendingPathComponent("skill_cache/snapshot.json")

    /// 读写串行化（Kotlin 侧从 Default dispatcher 协程调用，可能并发）。
    private let lock = NSLock()

    func read() -> String? {
        lock.lock()
        defer { lock.unlock() }
        guard let data = try? Data(contentsOf: fileURL), !data.isEmpty else {
            return nil
        }
        return String(data: data, encoding: .utf8)
    }

    func write(snapshotJson: String) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        guard let data = snapshotJson.data(using: .utf8) else {
            return false
        }
        do {
            try FileManager.default.createDirectory(
                at: fileURL.deletingLastPathComponent(),
                withIntermediateDirectories: true,
            )
            try data.write(to: fileURL, options: .atomic)
            return true
        } catch {
            return false
        }
    }
}
