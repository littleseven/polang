package com.mamba.picme.features.chat

import com.mamba.picme.domain.chat.EngineerTaskResolution
import com.mamba.picme.domain.chat.EngineerTaskState
import com.mamba.picme.domain.chat.EngineerTaskStatus
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EngineerTaskStateSerdeTest {

    private fun fullState() = EngineerTaskState(
        taskId = "task_abc",
        sourceText = "帮我修复登录崩溃",
        sid = "a1b2c3d4e5f6",
        status = EngineerTaskStatus.AWAITING_DELIVER,
        stage = "Bash",
        recentStages = listOf("Read", "Edit", "Bash"),
        turns = 7,
        costCents = 42,
        startedAtMs = 1000L,
        updatedAtMs = 2000L,
        fileChangeCount = 3,
        truncatedReason = null,
        errorSummary = null,
        resultSummary = "已修复登录崩溃",
        resolution = null,
        deliverBranch = null,
    )

    @Test
    fun `roundtrip preserves all fields`() {
        val metadata = JSONObject().put("engineer_task", fullState().toJson()).toString()
        assertEquals(fullState(), parseEngineerTaskState(metadata))
    }

    @Test
    fun `roundtrip preserves terminal resolution`() {
        val resolved = fullState().copy(
            status = EngineerTaskStatus.COMPLETED,
            resolution = EngineerTaskResolution.DELIVERED,
            deliverBranch = "fix/login",
        )
        val metadata = JSONObject().put("engineer_task", resolved.toJson()).toString()
        assertEquals(resolved, parseEngineerTaskState(metadata))
    }

    @Test
    fun `null and garbage metadata return null`() {
        assertNull(parseEngineerTaskState(null))
        assertNull(parseEngineerTaskState(""))
        assertNull(parseEngineerTaskState("not json"))
        assertNull(parseEngineerTaskState("""{"other":1}"""))
    }

    @Test
    fun `unknown status and resolution fall back safely`() {
        val json = fullState().toJson()
            .put("status", "NOT_A_STATUS")
            .put("resolution", "NOT_A_RESOLUTION")
        val parsed = parseEngineerTaskState(JSONObject().put("engineer_task", json).toString())
        assertEquals(EngineerTaskStatus.RUNNING, parsed?.status)
        assertNull(parsed?.resolution)
    }

    @Test
    fun `missing costCents stays null`() {
        val metadata = JSONObject().put("engineer_task", fullState().copy(costCents = null).toJson()).toString()
        assertNull(parseEngineerTaskState(metadata)?.costCents)
    }
}
