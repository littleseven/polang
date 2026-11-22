package com.mamba.picme.domain.organize

import org.junit.Assert.assertEquals
import org.junit.Test

class AppSourceTest {

    @Test
    fun `camera dcim is protected`() {
        assertEquals(AppSource.CAMERA, appSourceOf("DCIM/Camera"))
        assertEquals(SourceTier.PROTECTED, AppSource.CAMERA.defaultTier)
    }

    @Test
    fun `wechat dirs both legacy and scoped storage`() {
        assertEquals(AppSource.WECHAT, appSourceOf("Pictures/WeChat"))
        assertEquals(AppSource.WECHAT, appSourceOf("Android/media/com.tencent.mm/MicroMsg/WeiXin"))
    }

    @Test
    fun `overseas social apps`() {
        assertEquals(AppSource.WHATSAPP, appSourceOf("Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images"))
        assertEquals(AppSource.TELEGRAM, appSourceOf("Pictures/Telegram"))
        assertEquals(AppSource.INSTAGRAM, appSourceOf("Pictures/Instagram"))
    }

    @Test
    fun `screenshots including chinese oem dir`() {
        assertEquals(AppSource.SCREENSHOTS, appSourceOf("Pictures/Screenshots"))
        assertEquals(AppSource.SCREENSHOTS, appSourceOf("DCIM/Screenshots"))
        assertEquals(AppSource.SCREENSHOTS, appSourceOf("Pictures/截屏"))
    }

    @Test
    fun `download and fallback`() {
        assertEquals(AppSource.DOWNLOAD, appSourceOf("Download"))
        assertEquals(AppSource.OTHER, appSourceOf("Pictures/UnknownApp"))
        assertEquals(AppSource.OTHER, appSourceOf(null))
    }

    @Test
    fun `absolute data path fallback (API under 29)`() {
        assertEquals(AppSource.WECHAT, appSourceOf("/storage/emulated/0/Pictures/WeChat/img.jpg"))
        assertEquals(AppSource.CAMERA, appSourceOf("/storage/emulated/0/DCIM/Camera/IMG_1.jpg"))
    }

    @Test
    fun `qq and messenger are low value sources`() {
        assertEquals(AppSource.QQ, appSourceOf("Pictures/QQ Images"))
        // QQ 双关键词另一翼：scoped storage 形态
        assertEquals(AppSource.QQ, appSourceOf("Android/media/com.tencent.mobileqq/MobileQQ/download"))
        assertEquals(SourceTier.LOW_VALUE, AppSource.QQ.defaultTier)
        assertEquals(AppSource.MESSENGER, appSourceOf("Android/media/com.facebook.orca"))
        // MESSENGER 裸关键词（对称另一翼）
        assertEquals(AppSource.MESSENGER, appSourceOf("Pictures/Messenger"))
        assertEquals(SourceTier.LOW_VALUE, AppSource.MESSENGER.defaultTier)
    }

    @Test
    fun `path matching is case insensitive`() {
        assertEquals(AppSource.CAMERA, appSourceOf("dcim/camera"))
    }
}
