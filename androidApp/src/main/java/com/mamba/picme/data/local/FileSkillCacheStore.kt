package com.mamba.picme.data.local

import android.content.Context
import com.mamba.picme.agent.core.skill.SkillCacheStore
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * skill 本地缓存的文件实现（shared [SkillCacheStore] 的 Android 落地，
 * 2026-11-08 端侧 load_skill 执行器）。
 *
 * 单文件 `filesDir/skill_cache/snapshot.json`（SkillCacheSnapshot JSON，编解码收口在
 * shared `SkillCodec`——本类是刻意无知的读写管道）。原子写：临时文件 + rename，
 * 防同步中途进程被杀留下半截 JSON 污染下次启动缓存。
 */
class FileSkillCacheStore(
    context: Context,
) : SkillCacheStore {

    private val dir = File(context.filesDir, "skill_cache")
    private val file = File(dir, "snapshot.json")

    override suspend fun read(): String? = withContext(Dispatchers.IO) {
        runCatching { if (file.isFile) file.readText() else null }.getOrNull()
    }

    override suspend fun write(snapshotJson: String): Unit = withContext(Dispatchers.IO) {
        runCatching {
            dir.mkdirs()
            val tmp = File(dir, "snapshot.json.tmp")
            tmp.writeText(snapshotJson)
            if (!tmp.renameTo(file)) {
                // rename 失败（个别 ROM 跨挂载点）：退化为直写
                file.writeText(snapshotJson)
                tmp.delete()
            }
        }
    }
}
