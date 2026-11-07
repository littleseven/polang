package com.mamba.picme.features.debug.openui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ParsePreviewSessionIdTest {

    @Test
    fun `deep link extracts session param`() {
        assertEquals(
            "6d38f3dd-7332-4f80-9fad-d5286fcbb71a",
            parsePreviewSessionId("polang://openui-preview?session=6d38f3dd-7332-4f80-9fad-d5286fcbb71a"),
        )
    }

    @Test
    fun `bare uuid passes through`() {
        assertEquals(
            "6d38f3dd-7332-4f80-9fad-d5286fcbb71a",
            parsePreviewSessionId("  6d38f3dd-7332-4f80-9fad-d5286fcbb71a  "),
        )
    }

    @Test
    fun `session param amid other query params`() {
        assertEquals("abcd1234-ef56", parsePreviewSessionId("polang://openui-preview?from=qr&session=abcd1234-ef56&x=1"))
    }

    @Test
    fun `blank and garbage rejected`() {
        assertNull(parsePreviewSessionId(""))
        assertNull(parsePreviewSessionId("   "))
        assertNull(parsePreviewSessionId("hello world"))
        assertNull(parsePreviewSessionId("polang://openui-preview"))
    }
}
