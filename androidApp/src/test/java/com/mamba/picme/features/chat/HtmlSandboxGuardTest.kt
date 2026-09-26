package com.mamba.picme.features.chat

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * HTML 卡沙箱防线防回归守卫（spec《HTML 卡双形态》§14）——源码静态扫描。
 *
 * 契约：`HtmlCard`（inline/预览卡）与 `HtmlFullpageViewer`（全屏查看器）渲染 AI 生成 HTML，
 * 沙箱红线为——**零 JS 桥接**（JS 拿不到任何原生对象）；查看器额外要求**禁 DOM Storage**。
 * 项目无 Robolectric，不引入新测试框架；先例见 `RemoteInferenceNoMediaUploadGuardTest`。
 *
 * 注：链接落地页浮层（`HtmlLinkPreviewOverlay`）渲染用户主动点击进入的外部网页，
 * 允许 DOM Storage，不在本守卫管辖范围。
 */
class HtmlSandboxGuardTest {

    private val cardSource =
        File("src/main/java/com/mamba/picme/features/chat/HtmlCard.kt")
    private val viewerSource =
        File("src/main/java/com/mamba/picme/features/chat/HtmlFullpageViewer.kt")

    /** JS 桥接强信号 token（带调用括号/注解前缀，避开文档注释中的文字提及）。 */
    private val jsBridgeTokens = listOf(
        "addJavascriptInterface(",
        "@JavascriptInterface",
    )

    @Test
    fun htmlCardAndViewerMustNotExposeJsBridge() {
        val offenders = mutableListOf<String>()
        listOf(cardSource, viewerSource).forEach { file ->
            assertTrue("守卫源码不存在：${file.absolutePath}（测试需在 :androidApp 模块根目录运行）", file.isFile)
            val text = file.readText()
            jsBridgeTokens.forEach { token ->
                if (token in text) offenders += "${file.name} → '$token'"
            }
        }
        if (offenders.isNotEmpty()) {
            fail(
                "HTML 卡沙箱红线违规：检测到 JS 桥接符号：\n" +
                    offenders.joinToString("\n") + "\n" +
                    "卡片/查看器渲染 AI 生成 HTML，JS 必须拿不到任何原生对象。"
            )
        }
    }

    @Test
    fun fullpageViewerMustKeepDomStorageDisabled() {
        assertTrue("守卫源码不存在：${viewerSource.absolutePath}（测试需在 :androidApp 模块根目录运行）", viewerSource.isFile)
        val text = viewerSource.readText()
        if ("domStorageEnabled = true" in text) {
            fail("HTML 卡沙箱红线违规：HtmlFullpageViewer 开启了 DOM Storage（应恒为 false）")
        }
        assertTrue(
            "HtmlFullpageViewer 未显式关闭 DOM Storage（期望存在 'domStorageEnabled = false'）",
            "domStorageEnabled = false" in text
        )
    }
}
