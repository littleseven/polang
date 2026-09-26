package com.mamba.picme.agent.core.intent

import ai.koog.agents.core.tools.reflect.asToolsByClass
import com.mamba.picme.agent.core.inference.remote.tool.ChatToolService
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 意图契约表工具名 ⊆ chat 反射工具清单 的防腐守卫（spec §3.1 CI 防线）。
 *
 * 契约表（ChatIntentContract）的 allowedTools/forbiddenTools 是手写字符串：工具 rename/删除时
 * 拼错的契约项不会编译报错、静默腐烂。本测试用 Koog JVM 反射真源（与 Android 组合根同一
 * 扫描函数，对齐 `ChatToolManifestConsistencyTest` 先例）校验每个契约工具名真实存在。
 *
 * 只能放 jvmTest：reflect 包 JVM-only。
 */
class ChatIntentContractToolNamesTest {

    private val reflectedToolNames: Set<String> =
        ChatToolService.getInstance().asToolsByClass()
            .map { it.descriptor.name }
            .toSet()

    @Test
    fun `every contract tool name exists in reflected chat tool inventory`() {
        val contractNames = ChatIntentContract.intents
            .flatMap { def -> def.allowedTools + def.forbiddenTools }
            .toSet()
        val missing = contractNames - reflectedToolNames
        assertTrue(
            "契约表引用了不存在的工具名: $missing（实际工具面: $reflectedToolNames）",
            missing.isEmpty(),
        )
    }
}
