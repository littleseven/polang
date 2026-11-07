package com.mamba.picme.server.agent

import com.mamba.picme.agent.core.inference.remote.tool.CloudAgentToolContract
import org.junit.Assert.assertEquals
import org.junit.Test

/** 分层 runtime M1：composite build 接线冒烟——server 编译期可见 :shared jvm 变体。 */
class SharedModuleSmokeTest {

    @Test
    fun `shared contract constants are on the classpath`() {
        assertEquals("search_media", CloudAgentToolContract.TOOL_SEARCH_MEDIA)
        assertEquals("error:", CloudAgentToolContract.ERROR_PREFIX)
        assertEquals(32_000, CloudAgentToolContract.MAX_RESULT_CHARS)
    }
}
