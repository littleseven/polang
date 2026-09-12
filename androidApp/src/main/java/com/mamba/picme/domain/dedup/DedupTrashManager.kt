package com.mamba.picme.domain.dedup

import android.content.ContentValues
import android.content.Context
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log

class DedupTrashManager(private val context: Context) {

    val isSupported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    /**
     * MANAGE_MEDIA（媒体管理）权限持有检查。公开检查口 [MediaStore.canManageMedia] 为 API 31+
     * 新增（API 30 无公开检查口），故低版本保守返回 false，静默快路径整体 guard 到 API 31+。
     */
    fun canManageMedia(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && MediaStore.canManageMedia(context)

    /**
     * 静默移入回收站：持 MANAGE_MEDIA 后直写 IS_TRASHED=1 不弹系统授权框。
     * 只写 IS_TRASHED 单列——DATE_EXPIRES 属系统管理列，实测同写会导致整个 update 在
     * 部分 ROM（HyperOS）上失败；trash 行缺省保留期由系统兜底。逐 uri 独立执行，
     * 单个失败（行不存在/写被拒）记警告日志、不中断其余；返回成功集（可能为空集）。
     */
    fun silentTrash(uris: List<String>): List<String> {
        val trashed = mutableListOf<String>()
        for (uri in uris) {
            runCatching {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_TRASHED, 1)
                }
                val rows = context.contentResolver.update(Uri.parse(uri), values, null, null)
                if (rows > 0) {
                    trashed += uri
                } else {
                    Log.w(TAG, "silentTrash: update matched 0 rows, uri=$uri")
                }
            }.onFailure { e ->
                Log.w(TAG, "silentTrash: update failed, uri=$uri", e)
            }
        }
        return trashed
    }

    fun buildTrashIntent(uris: List<String>): IntentSender =
        MediaStore.createTrashRequest(
            context.contentResolver, uris.map { uri -> Uri.parse(uri) }, true
        ).intentSender

    fun buildRestoreIntent(uris: List<String>): IntentSender =
        MediaStore.createTrashRequest(
            context.contentResolver, uris.map { uri -> Uri.parse(uri) }, false
        ).intentSender

    /**
     * 授权后复查 uri 是否仍存在（未删净）。注意：部分 ROM（实测 HyperOS/Android 16）对已
     * trash 的媒体行做直接 item-URI 查询时仍返回该行（AOSP 默认查询会过滤 trash 行），
     * 因此必须读 [MediaStore.MediaColumns.IS_TRASHED] 区分「真没删」与「已进回收站」，
     * 否则会误判部分拒绝、整组保留不刷新。IS_TRASHED 为 API 29+ 列；本方法仅由 API 30+
     * 的回收站授权回流调用，列缺失时保守视为仍存在。
     */
    fun queryExisting(uris: List<String>): List<String> {
        val existing = mutableListOf<String>()
        val projection = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.IS_TRASHED)
        for (uri in uris) {
            runCatching {
                context.contentResolver.query(Uri.parse(uri), projection, null, null, null)?.use { cursor ->
                    if (!cursor.moveToFirst()) return@use
                    val trashedIndex = cursor.getColumnIndex(MediaStore.MediaColumns.IS_TRASHED)
                    val trashed = trashedIndex >= 0 && cursor.getInt(trashedIndex) != 0
                    if (!trashed) existing += uri
                }
            }
        }
        return existing
    }

    private companion object {
        const val TAG = "PoLang:DedupTrash"
    }
}
