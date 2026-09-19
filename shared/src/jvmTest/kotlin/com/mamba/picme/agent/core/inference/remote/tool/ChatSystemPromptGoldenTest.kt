package com.mamba.picme.agent.core.inference.remote.tool

import ai.koog.agents.core.tools.reflect.asToolsByClass
import com.mamba.picme.agent.core.inference.remote.RemoteChatEngine
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Android chat system prompt 逐字节 golden（Phase 6.2 T3 注入点改造的护栏）。
 *
 * `AgentDependencies.chatPromptBuilder` 默认值 = `RemoteChatEngine::buildChatSystemPrompt`，
 * 本测试锁死该函数对 Android 工具清单的输出逐字节不变——任何 prompt 文本/拼接顺序/
 * ToolInventory 格式改动（含 chatPromptBuilder 接线改错默认值）都会变红。
 *
 * golden 生成/更新：`POLANG_WRITE_GOLDEN=1 ./gradlew :shared:jvmTest --tests "*ChatSystemPromptGoldenTest*"`
 * （仅限 deliberate prompt 变更后；生成结果必须人工 review 再提交）。
 * 注意必须用环境变量——`-Dpolang.writeGolden=true` 只作用于 Gradle 守护进程 JVM，
 * 不透传 fork 出的测试 JVM（实测 2026-09-19 写入未生效）。
 */
class ChatSystemPromptGoldenTest {

    @Test
    fun `android chat system prompt matches golden byte for byte`() {
        val actual = RemoteChatEngine.buildChatSystemPrompt(
            ChatToolService.getInstance().asToolsByClass().map { it.descriptor }
        )
        if (System.getProperty("polang.writeGolden") == "true" || System.getenv("POLANG_WRITE_GOLDEN") == "1") {
            File(GOLDEN_PATH).apply { parentFile.mkdirs() }.writeText(actual, Charsets.UTF_8)
        }
        assertEquals(golden().replace("\r\n", "\n"), actual)
    }

    private fun golden(): String =
        requireNotNull(javaClass.getResource("/golden/$GOLDEN_NAME")) {
            "golden 资源缺失：$GOLDEN_NAME（先以 -Dpolang.writeGolden=true 生成）"
        }.readText(Charsets.UTF_8)

    private companion object {
        const val GOLDEN_NAME = "chat_system_prompt_golden.txt"
        const val GOLDEN_PATH = "src/jvmTest/resources/golden/$GOLDEN_NAME"
    }
}
