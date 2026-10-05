package com.mamba.picme.data.update

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import com.mamba.picme.domain.update.OtaDmStatus
import java.io.File

/**
 * 系统 DownloadManager 包装：OTA APK 后台下载。
 * 落盘：getExternalFilesDir(DIRECTORY_DOWNLOADS)/ota/polang-<versionCode>.apk
 * （app 外部私有目录，FileProvider external-files-path 直接可装，零拷贝）。
 */
class OtaDownloadManager(private val context: Context) {

    private val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

    /**
     * 入队下载。通知：进行中系统进度条（VISIBILITY_VISIBLE 完成后自动隐藏）；允许计量网络，禁漫游。
     * 成功入队后才清旧包（消除「先删旧包→enqueue 失败→旧包也丢」窗口）。
     *
     * @throws IllegalStateException 外部存储不可用
     * @throws IllegalArgumentException 非 http(s) URI
     */
    fun enqueue(url: String, versionCode: Long, title: String): Long {
        val request = DownloadManager.Request(Uri.parse(url)).apply {
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            setTitle(title)
            setAllowedOverMetered(true)
            setAllowedOverRoaming(false)
            setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, "ota/polang-$versionCode.apk")
        }
        val id = dm.enqueue(request)
        clearOldFiles(exceptVersionCode = versionCode)
        return id
    }

    /**
     * 查询下载状态快照；未知列状态按非破坏的 Running(-1f) 处理（Missing 会触发破坏性 DiscardStale）。
     *
     * 查询失败即抛（含 dm.query() 返回 null 时的 NPE），调用方须 runCatching；
     * 不要把异常改映射为 Missing——瞬时查询失败的正确归宿是 null→ProceedCheck。
     */
    fun status(downloadId: Long): OtaDmStatus {
        return dm.query(DownloadManager.Query().setFilterById(downloadId)).use { c ->
            if (!c.moveToFirst()) return OtaDmStatus.Missing
            when (c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))) {
                DownloadManager.STATUS_SUCCESSFUL -> OtaDmStatus.Successful
                DownloadManager.STATUS_RUNNING, DownloadManager.STATUS_PAUSED -> {
                    val total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                    val soFar = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                    OtaDmStatus.Running(if (total > 0) soFar.toFloat() / total else -1f)
                }
                DownloadManager.STATUS_FAILED -> OtaDmStatus.Failed(
                    c.getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)) ?: "unknown",
                )
                else -> OtaDmStatus.Running(-1f)
            }
        }
    }

    fun localFileFor(versionCode: Long): File =
        File(File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "ota"), "polang-$versionCode.apk")

    fun delete(downloadId: Long) {
        // markDeleted 非 public SDK（编译不过）；remove 为公开 API 等价物：删 DM 记录及落盘文件
        dm.remove(downloadId)
    }

    /** 清旧版本 APK 文件（保留 exceptVersionCode 指定版本）。 */
    fun clearOldFiles(exceptVersionCode: Long) {
        val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "ota")
        dir.listFiles()?.forEach { f ->
            val vc = Regex("polang-(\\d+)\\.apk").find(f.name)?.groupValues?.get(1)?.toLongOrNull()
            if (vc != null && vc != exceptVersionCode) f.delete()
        }
    }
}
