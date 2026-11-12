@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.mamba.picme.data

import com.mamba.picme.domain.chat.openui.OpenUiContract
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSString
import platform.Foundation.NSUUID
import platform.Foundation.NSUserDomainMask
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.stringWithContentsOfFile
import platform.Foundation.writeToFile

/**
 * iOS 端 local:// handle 存储（OpenUI 云端回合，2026-11-12）——对位 Android
 * `data/local/LocalHandleStore`（Room local_handle 表）。
 *
 * 职责：云端 Agent 相册工具命中集注册 opaque 句柄（`handle: scan/<id>` 上行 token，
 * 无 uri——D2 结构保证），OpenUI 卡 `Query("local_handle")` 经 [payloadOf] 取回
 * `ScanPhotoEntry[]` payload 端侧补全（隐私闸对偶：GenUI 只生成骨架不生成数据）。
 *
 * 形态：Documents/OpenUiHandles/<handleId>.json 每句柄一文件（payload 可达数百 KB，
 * 不入 NSUserDefaults）；文件体 = [IosHandleFile] 信封（domain 校验 + payload 原文）。
 * 持久生命周期对齐 Android Room 行（不 GC，进程重启可解析）；解析失败返 null
 * （半信任输入防御，宿主统判降级）。
 *
 * 信号 6 铁律：一切 IO 异常兜底为 null / false，绝不逃逸到 Swift。
 */
class IosLocalHandleStore {

    @Serializable
    private data class IosHandleFile(val domain: String, val payload: String)

    private val json = Json { ignoreUnknownKeys = true }
    private val fileManager = NSFileManager.defaultManager
    private val dirPath: String by lazy {
        val docs = (NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true)
            .firstOrNull() as? String) ?: return@lazy ""
        val dir = "$docs/OpenUiHandles"
        if (!fileManager.fileExistsAtPath(dir)) {
            fileManager.createDirectoryAtPath(dir, withIntermediateDirectories = true, attributes = null, error = null)
        }
        dir
    }

    /**
     * 注册句柄（写入 payload），返回 opaque token（`handle: scan/<id>`，无 scheme）。
     * 写失败抛异常由调用方降级为不带 token 的纯文本（对齐 Android appendHandleSuffix）。
     */
    fun put(domain: String, payloadJson: String): String {
        val handleId = (NSUUID().UUIDString.replace("-", "").lowercase() + "000000000000").take(12)
        val body = json.encodeToString(IosHandleFile(domain, payloadJson))
        val ok = (body as NSString).writeToFile("$dirPath/$handleId.json", atomically = true, encoding = NSUTF8StringEncoding, error = null)
        check(ok) { "handle file write failed: domain=$domain" }
        return OpenUiContract.formatHandleToken(domain, handleId)
    }

    /** 解析句柄 → payload 原文；缺文件 / domain 不匹配 / 损坏文件一律 null。 */
    fun payloadOf(domain: String, handleId: String): String? {
        if (handleId.isBlank()) return null
        val path = "$dirPath/$handleId.json"
        if (!fileManager.fileExistsAtPath(path)) return null
        val raw = runCatching {
            NSString.stringWithContentsOfFile(path, encoding = NSUTF8StringEncoding, error = null)
        }.getOrNull() ?: return null
        val file = runCatching { json.decodeFromString<IosHandleFile>(raw) }.getOrNull() ?: return null
        return file.payload.takeIf { file.domain == domain }
    }
}
