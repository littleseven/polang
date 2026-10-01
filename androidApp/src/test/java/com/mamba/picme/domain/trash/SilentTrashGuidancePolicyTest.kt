package com.mamba.picme.domain.trash

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SilentTrashGuidancePolicyTest {

    @Test
    fun `asks once on api 31+ when silent path unavailable`() {
        assertTrue(
            SilentTrashGuidancePolicy.shouldAsk(
                apiLevel = 34,
                alreadyAsked = false,
                silentTrashEnabled = false,
                canManageMedia = false,
            )
        )
    }

    @Test
    fun `never asks below api 31`() {
        assertFalse(
            SilentTrashGuidancePolicy.shouldAsk(
                apiLevel = 30,
                alreadyAsked = false,
                silentTrashEnabled = false,
                canManageMedia = false,
            )
        )
    }

    @Test
    fun `never asks after already asked`() {
        assertFalse(
            SilentTrashGuidancePolicy.shouldAsk(
                apiLevel = 34,
                alreadyAsked = true,
                silentTrashEnabled = false,
                canManageMedia = false,
            )
        )
    }

    @Test
    fun `never asks when silent path already effective`() {
        assertFalse(
            SilentTrashGuidancePolicy.shouldAsk(
                apiLevel = 34,
                alreadyAsked = false,
                silentTrashEnabled = true,
                canManageMedia = true,
            )
        )
    }

    @Test
    fun `asks when toggle on but permission missing`() {
        assertTrue(
            SilentTrashGuidancePolicy.shouldAsk(
                apiLevel = 34,
                alreadyAsked = false,
                silentTrashEnabled = true,
                canManageMedia = false,
            )
        )
    }

    @Test
    fun `asks when permission held but toggle off`() {
        assertTrue(
            SilentTrashGuidancePolicy.shouldAsk(
                apiLevel = 34,
                alreadyAsked = false,
                silentTrashEnabled = false,
                canManageMedia = true,
            )
        )
    }
}
