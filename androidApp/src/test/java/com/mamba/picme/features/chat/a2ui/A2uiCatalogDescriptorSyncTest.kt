package com.mamba.picme.features.chat.a2ui

import androidx.a2ui.compose.ui.toJsonSchemaString
import androidx.a2ui.model.catalog.functions.A2uiUrlOpener
import com.mamba.picme.domain.chat.a2ui.A2uiCardContract
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * polang A2UI catalog 描述文件对账：[A2uiCardContract.CATALOG_ID] 是可解引用 URL，
 * 本测试把运行态 catalog（[polangA2uiCatalog]）经 A2UI SDK 序列化为 JSON schema，
 * 与官网静态文件 `docs-site/a2ui/catalog/v1.json`（部署后 = CATALOG_ID 的 GET 响应体）对账。
 *
 * catalog 变更导致漂移时重新生成官网文件：
 * ```
 * REGEN_A2UI_CATALOG=true JITPACK=true ./gradlew :androidApp:testDebugUnitTest \
 *   --tests "com.mamba.picme.features.chat.a2ui.A2uiCatalogDescriptorSyncTest"
 * ```
 * 再执行 `scripts/deploy-docs-site.sh` 发布（部署后 curl 验证 CATALOG_ID 返回 200）。
 * （再生成走环境变量而非 -D 系统属性：Gradle 测试跑在独立 worker JVM，-D 不透传。）
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = A2uiTestApplication::class)
class A2uiCatalogDescriptorSyncTest {

    private val prettyJson = Json { prettyPrint = true }

    @Test
    fun descriptor_embedsCatalogId() {
        val generated = generateDescriptor()
        assertTrue(
            "序列化产物必须内嵌 catalogId（${A2uiCardContract.CATALOG_ID}），否则官网文件与契约脱节",
            generated.contains(A2uiCardContract.CATALOG_ID),
        )
    }

    @Test
    fun descriptor_matchesPublishedFile() {
        val file = descriptorFile()
        val generated = generateDescriptor()
        if (System.getenv("REGEN_A2UI_CATALOG") == "true") {
            file.parentFile.mkdirs()
            file.writeText(generated + "\n")
        }
        assertTrue(
            "官网 catalog 描述文件不存在：${file.path}——以 REGEN_A2UI_CATALOG=true 重跑本测试生成",
            file.isFile,
        )
        assertEquals(
            "运行态 catalog 与官网文件漂移——以 REGEN_A2UI_CATALOG=true 重跑本测试重新生成，" +
                "review diff 后经 deploy-docs-site.sh 发布（文件：${file.path}）",
            canonicalize(file.readText()),
            generated,
        )
    }

    /** 序列化运行态 catalog → 规范化（键排序 + 2 空格缩进）pretty JSON。 */
    private fun generateDescriptor(): String {
        val catalog = polangA2uiCatalog(A2uiUrlOpener { })
        return canonicalize(catalog.toJsonSchemaString())
    }

    /** 解析为 JsonElement 递归排序键后 pretty-print：与序列化器输出的键序/格式解耦。 */
    private fun canonicalize(raw: String): String {
        val element = Json.parseToJsonElement(raw)
        return prettyJson.encodeToString(JsonElement.serializer(), sortKeys(element)).trim()
    }

    private fun sortKeys(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> JsonObject(
            element.toSortedMap().mapValues { entry -> sortKeys(entry.value) },
        )
        is JsonArray -> JsonArray(element.map { item -> sortKeys(item) })
        else -> element
    }

    /** Gradle test workingDir = androidApp 模块目录；向上找 settings.gradle.kts 定位仓库根。 */
    private fun descriptorFile(): File {
        var dir = File("").absoluteFile
        while (!File(dir, "settings.gradle.kts").isFile) {
            dir = dir.parentFile ?: error("未定位仓库根（settings.gradle.kts），cwd=${File("").absolutePath}")
        }
        return File(dir, "docs-site/a2ui/catalog/v1.json")
    }
}
