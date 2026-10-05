package com.mamba.picme.domain.usertask

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UserTaskMappingTest {

    @Test
    fun `能力集推导矩阵`() {
        assertEquals(setOf(UserTaskAction.CANCEL), UserTaskMapping.actionsFor(UserTaskStatus.PENDING))
        assertEquals(setOf(UserTaskAction.PAUSE, UserTaskAction.CANCEL), UserTaskMapping.actionsFor(UserTaskStatus.RUNNING))
        assertEquals(setOf(UserTaskAction.RESUME, UserTaskAction.CANCEL), UserTaskMapping.actionsFor(UserTaskStatus.PAUSED))
        assertEquals(setOf(UserTaskAction.RETRY), UserTaskMapping.actionsFor(UserTaskStatus.FAILED))
        assertTrue(UserTaskMapping.actionsFor(UserTaskStatus.COMPLETED).isEmpty())
        assertTrue(UserTaskMapping.actionsFor(UserTaskStatus.CANCELLED).isEmpty())
    }

    @Test
    fun `活动态判据与 destination 推导`() {
        assertTrue(UserTaskMapping.isActive(UserTaskStatus.PENDING))
        assertTrue(UserTaskMapping.isActive(UserTaskStatus.RUNNING))
        assertTrue(UserTaskMapping.isActive(UserTaskStatus.PAUSED))
        assertFalse(UserTaskMapping.isActive(UserTaskStatus.COMPLETED))
        assertFalse(UserTaskMapping.isActive(UserTaskStatus.FAILED))
        assertFalse(UserTaskMapping.isActive(UserTaskStatus.CANCELLED))
        assertEquals(UserTaskDestination.TAG_SCAN_CONTROL, UserTaskMapping.destinationFor(UserTaskKind.TAG_SCAN))
        assertEquals(UserTaskDestination.MODEL_CENTER, UserTaskMapping.destinationFor(UserTaskKind.MODEL_DOWNLOAD))
    }
}
