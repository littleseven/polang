package com.mamba.picme.agent.core.skill

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Swift（URLSession 鉴权 GET）→ Kotlin 的 skill 资产传输桥协议（2026-11-08 端侧
 * load_skill 执行器 iOS 接线）。由 iosApp `SkillTransportBridge`（Swift/NSObject）实现；
 * 语义对齐 Android `SkillClient`（OkHttp → picme-server `/v1/skills`）。
 *
 * SharedBridge 铁律（同 [com.mamba.picme.domain.usertask.IosUserTaskStoreBridge]）：
 * Swift 实现侧绝不抛异常跨边界——网络失败/非 2xx/空 body 一律以 null 回调
 * （[SkillRepository] 据此回落本地缓存，stale-while-revalidate）。鉴权 header
 *（X-App-Token / X-Device-Id / X-Platform=ios）由 Swift 实现自加——token/deviceId
 * 与 Android 组合根 provider 同源（server_auth_token / 设备标识）。
 *
 * [path] 为相对路径（`/v1/skills` 或 `/v1/skills/{name}`），base url 由 Swift 侧持有
 *（与 PoLangAuthClient/IssueReportClient 同惯例）；callback 保证恰回调一次。
 */
interface IosSkillTransportBridge {
    /** 鉴权 GET；失败/非 2xx/空 body 以 null 回调。 */
    fun fetch(path: String, callback: (String?) -> Unit)
}

/**
 * Swift（Documents JSON 文件）→ Kotlin 的 skill 缓存桥协议。由 iosApp `SkillCacheBridge`
 * （Swift/NSObject）实现；语义对齐 Android `FileSkillCacheStore`（filesDir/skill_cache/
 * snapshot.json）。读失败返回 null、写失败返回 false，绝不抛异常跨边界；序列化/反序列化
 * 收口在 shared `SkillCodec`——桥是刻意无知的读写管道。
 */
interface IosSkillCacheBridge {
    /** 读取缓存快照 JSON；无缓存/读取失败返回 null。 */
    fun read(): String?

    /** 覆盖写入缓存快照 JSON（原子写）；返回是否成功。 */
    fun write(snapshotJson: String): Boolean
}

/**
 * [SkillTransport] 的 iOS 实现：非挂起 Swift 桥回调 → suspend 协程的适配层
 * （Swift 无法实现 Kotlin suspend 接口成员，同 [IosUserTaskStore] 适配先例）。
 * 失败语义与契约一致：一切异常映射 null，CancellationException 原样 rethrow。
 */
class IosSkillTransport(
    private val bridge: IosSkillTransportBridge,
) : SkillTransport {

    override suspend fun fetchManifestJson(): String? = fetchViaBridge("/v1/skills")

    override suspend fun fetchSkillJson(name: String): String? = fetchViaBridge("/v1/skills/$name")

    private suspend fun fetchViaBridge(path: String): String? = try {
        suspendCancellableCoroutine { continuation ->
            bridge.fetch(path) { body -> continuation.resume(body) {} }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }
}

/**
 * [SkillCacheStore] 的 iOS 实现：同步 Swift 桥（NSLock 串行化，量级小）直包装；
 * 写失败返回 false 静默——[SkillRepository.persistLocked] 的 runCatching 兜底语义
 * 与 Android「缓存丢失不致命，下次同步重建」一致。
 */
class IosSkillCacheStore(
    private val bridge: IosSkillCacheBridge,
) : SkillCacheStore {

    override suspend fun read(): String? = bridge.read()

    override suspend fun write(snapshotJson: String) {
        bridge.write(snapshotJson)
    }
}

/**
 * iOS 工厂：封装 [SkillRepository] 构造（对齐 `createUserTaskRegistry` 先例——组合根
 * 参数经 iosMain 工厂收口，Swift 不直接拼 Kotlin 构造参数）。
 */
fun createSkillRepository(
    transportBridge: IosSkillTransportBridge,
    cacheBridge: IosSkillCacheBridge,
): SkillRepository = SkillRepository(
    transport = IosSkillTransport(transportBridge),
    store = IosSkillCacheStore(cacheBridge),
)
