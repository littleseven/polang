package com.mamba.picme.data.download

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 校验 [LlmModelDownloadManager.decideFileResumeAction] 的续传盘点决策。
 *
 * 回归背景：并行分块下载用 setLength 预分配全长，进程被杀后留下「长度正确、
 * 内容空洞」的半截文件；若按长度信任 .part 断点续传，会把空洞文件当作已下载
 * 部分，产出损坏模型（glintr100/mobileclip 四项归零事故根因）。
 */
class FileResumeActionTest {

    @Test
    fun `dest file exists means verified complete, always skip`() {
        // 正式文件名提升前已过完整性校验，任何 part 状态都应跳过
        assertEquals(
            FileResumeAction.SKIP_COMPLETED,
            LlmModelDownloadManager.decideFileResumeAction(destExists = true, partLength = null, expectedSize = 100)
        )
        assertEquals(
            FileResumeAction.SKIP_COMPLETED,
            LlmModelDownloadManager.decideFileResumeAction(destExists = true, partLength = 50, expectedSize = 100)
        )
    }

    @Test
    fun `missing part restarts download`() {
        assertEquals(
            FileResumeAction.RESTART_DOWNLOAD,
            LlmModelDownloadManager.decideFileResumeAction(destExists = false, partLength = null, expectedSize = 100)
        )
    }

    @Test
    fun `shorter part is trusted as real single-stream prefix, resume from it`() {
        assertEquals(
            FileResumeAction.RESUME_PARTIAL,
            LlmModelDownloadManager.decideFileResumeAction(destExists = false, partLength = 50, expectedSize = 100)
        )
    }

    @Test
    fun `full-length part is untrusted (parallel preallocated holes), restart`() {
        // 关键回归：part 长度 == 期望大小 ≠ 内容完整（setLength 预分配），必须重下
        assertEquals(
            FileResumeAction.RESTART_DOWNLOAD,
            LlmModelDownloadManager.decideFileResumeAction(destExists = false, partLength = 100, expectedSize = 100)
        )
    }

    @Test
    fun `over-length or zero-length part is corrupt, restart`() {
        assertEquals(
            FileResumeAction.RESTART_DOWNLOAD,
            LlmModelDownloadManager.decideFileResumeAction(destExists = false, partLength = 150, expectedSize = 100)
        )
        assertEquals(
            FileResumeAction.RESTART_DOWNLOAD,
            LlmModelDownloadManager.decideFileResumeAction(destExists = false, partLength = 0, expectedSize = 100)
        )
    }

    @Test
    fun `unknown expected size cannot validate part, restart`() {
        assertEquals(
            FileResumeAction.RESTART_DOWNLOAD,
            LlmModelDownloadManager.decideFileResumeAction(destExists = false, partLength = 50, expectedSize = 0)
        )
    }
}
