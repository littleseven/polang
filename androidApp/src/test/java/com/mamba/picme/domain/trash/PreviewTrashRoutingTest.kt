package com.mamba.picme.domain.trash

import org.junit.Assert.assertEquals
import org.junit.Test

class PreviewTrashRoutingTest {

    @Test
    fun `supported backend routes to system trash`() {
        assertEquals(PreviewTrashRouting.Route.TRASH, PreviewTrashRouting.resolve(trashSupported = true))
    }

    @Test
    fun `unsupported backend falls back to legacy delete flow`() {
        assertEquals(
            PreviewTrashRouting.Route.LEGACY_DELETE,
            PreviewTrashRouting.resolve(trashSupported = false)
        )
    }
}
