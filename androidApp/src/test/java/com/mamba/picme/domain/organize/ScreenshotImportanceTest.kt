package com.mamba.picme.domain.organize

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenshotImportanceTest {

    private fun shot(
        fileName: String? = null,
        ocrText: String? = null,
        relativePath: String? = "Pictures/Screenshots/",
    ) = OrganizeItem(
        uri = "a", isVideo = false, captureDate = 1_000L, sizeBytes = 1,
        relativePath = relativePath, ocrText = ocrText, pixelArea = null,
        labels = null, hasFace = false, aestheticScore = null, faceQualityScore = null,
        displayName = fileName,
    )

    @Test
    fun `package name hits each tier`() {
        assertEquals(
            ScreenshotImportance.ProtectedFinance("com.icbc.mobile"),
            screenshotImportanceOf(shot(fileName = "Screenshot_2025-06-12-08-29-24-341_com.icbc.mobile.jpg")),
        )
        assertEquals(
            ScreenshotImportance.ProtectedHealth("com.haodf.android"),
            screenshotImportanceOf(shot(fileName = "Screenshot_2025-06-12-08-29-24-341_com.haodf.android.jpg")),
        )
        assertEquals(
            ScreenshotImportance.ProtectedGov("com.tmri.app.main"),
            screenshotImportanceOf(shot(fileName = "Screenshot_2025-06-12-08-29-24-341_com.tmri.app.main.jpg")),
        )
        assertEquals(
            ScreenshotImportance.LowValue("com.sankuai.meituan"),
            screenshotImportanceOf(shot(fileName = "Screenshot_2025-06-12-08-29-24-341_com.sankuai.meituan.jpg")),
        )
    }

    @Test
    fun `miui edit variant package parsed`() {
        // 设备实测形态：-edit_ 变体（-edit_ 前缀被 `_` 分隔自然切掉）+ 支付宝混合大小写包名
        assertEquals(
            ScreenshotImportance.ProtectedFinance("com.eg.android.alipaygphone"),
            screenshotImportanceOf(
                shot(fileName = "Screenshot_2025-06-12-08-46-04-915-edit_com.eg.android.AlipayGphone.jpg")
            ),
        )
    }

    @Test
    fun `mixed case package and extension matched case-insensitively`() {
        assertEquals(
            ScreenshotImportance.ProtectedFinance("com.icbc.mobile"),
            screenshotImportanceOf(shot(fileName = "Screenshot_2025-06-12-08-29-24-341_Com.ICBC.Mobile.JPG")),
        )
    }

    @Test
    fun `no package segment falls back to ocr keywords`() {
        // 尾段为纯序号（无 `.`）→ 非包名形状 → OCR 兜底
        assertEquals(
            ScreenshotImportance.ProtectedFinance("转账"),
            screenshotImportanceOf(
                shot(fileName = "Screenshot_2025-06-12-08-29-24-341.jpg", ocrText = "向张三转账 ¥500.00"),
            ),
        )
        assertEquals(
            ScreenshotImportance.ProtectedHealth("挂号"),
            screenshotImportanceOf(shot(fileName = "Screenshot_20240101_082924.png", ocrText = "门诊挂号单")),
        )
        assertEquals(
            ScreenshotImportance.ProtectedGov("身份证"),
            screenshotImportanceOf(shot(fileName = null, ocrText = "姓名 身份证号码")),
        )
        assertEquals(
            ScreenshotImportance.LowValue("外卖"),
            screenshotImportanceOf(shot(fileName = null, ocrText = "外卖订单 满减优惠")),
        )
        // 包名在词表外（微信）→ OCR 兜底仍生效
        assertEquals(
            ScreenshotImportance.ProtectedFinance("银行"),
            screenshotImportanceOf(shot(fileName = "Screenshot_1_com.tencent.mm.jpg", ocrText = "银行卡余额")),
        )
    }

    @Test
    fun `no signal is neutral`() {
        assertEquals(
            ScreenshotImportance.Neutral,
            screenshotImportanceOf(shot(fileName = null, ocrText = null)),
        )
        // 包名/OCR 均未命中词表
        assertEquals(
            ScreenshotImportance.Neutral,
            screenshotImportanceOf(shot(fileName = "Screenshot_1_com.tencent.mm.jpg", ocrText = "hello world")),
        )
        // OCR 文本不含任何关键词
        assertEquals(
            ScreenshotImportance.Neutral,
            screenshotImportanceOf(shot(fileName = "Screenshot_20240101_082924.jpg", ocrText = "abcdefg")),
        )
    }

    @Test
    fun `non screenshot path is always neutral`() {
        // camera 路径：即使文件名携带包名 + OCR 含保护关键词也恒 NEUTRAL（避免误解析非截图）
        assertEquals(
            ScreenshotImportance.Neutral,
            screenshotImportanceOf(
                shot(
                    fileName = "Screenshot_2025-06-12-08-29-24-341_cn.futu.trader.jpg",
                    ocrText = "转账 银行",
                    relativePath = "DCIM/Camera/",
                )
            ),
        )
        assertEquals(
            ScreenshotImportance.Neutral,
            screenshotImportanceOf(shot(ocrText = "转账", relativePath = null)),
        )
    }

    @Test
    fun `signal conflict resolves to protected tier`() {
        // 包名低价值（美团）+ OCR 保护（银行）→ 保护优先（保守偏置）
        assertEquals(
            ScreenshotImportance.ProtectedFinance("银行"),
            screenshotImportanceOf(
                shot(fileName = "Screenshot_1_com.sankuai.meituan.jpg", ocrText = "银行卡扣款通知"),
            ),
        )
        // 反向：包名保护 + OCR 低价值 → 包名保护（主信号）
        assertEquals(
            ScreenshotImportance.ProtectedFinance("cn.futu.trader"),
            screenshotImportanceOf(
                shot(fileName = "Screenshot_1_cn.futu.trader.jpg", ocrText = "外卖满减广告"),
            ),
        )
    }

    @Test
    fun `package prefix match not equality`() {
        // 前缀匹配：com.unionpay 命中 com.unionpay.activity；词条不要求整串相等
        assertEquals(
            ScreenshotImportance.ProtectedFinance("com.unionpay.activity"),
            screenshotImportanceOf(shot(fileName = "Screenshot_1_com.unionpay.activity.jpg")),
        )
        // 前缀语义按词表词条 startsWith：词条本身即前缀契约（com.icbc 覆盖工行系 com.icbc.ccd 等）
        assertEquals(
            ScreenshotImportance.ProtectedFinance("com.icbc.ccd"),
            screenshotImportanceOf(shot(fileName = "Screenshot_1_com.icbc.ccd.jpg")),
        )
    }

    @Test
    fun `isProtectedTier derives from tier kind`() {
        assertTrue(ScreenshotImportance.ProtectedFinance("x").isProtectedTier)
        assertTrue(ScreenshotImportance.ProtectedHealth("x").isProtectedTier)
        assertTrue(ScreenshotImportance.ProtectedGov("x").isProtectedTier)
        assertFalse(ScreenshotImportance.LowValue("x").isProtectedTier)
        assertFalse(ScreenshotImportance.Neutral.isProtectedTier)
    }
}
