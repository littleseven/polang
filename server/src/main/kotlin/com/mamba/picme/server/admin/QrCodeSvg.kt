package com.mamba.picme.server.admin

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * 二维码 SVG 生成（OpenUI 手机扫码预览，0.9.35）。
 *
 * zxing core 出 BitMatrix，黑块逐格合并为单个 `<path>` 输出内联 SVG——不引
 * zxing-javase/AWT（headless 盒子红线），SVG 体积随模块数线性、可随页面 SSR。
 */
object QrCodeSvg {

    /** 内容编码为 QR 内联 SVG（EC level M 抗污损，quiet zone 1 模块——页面白底已留白）。 */
    fun svg(content: String, sizePx: Int = 168): String {
        val matrix = QRCodeWriter().encode(
            content,
            BarcodeFormat.QR_CODE,
            0,
            0,
            mapOf(
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                EncodeHintType.MARGIN to 1,
            ),
        )
        return buildString(matrix.width * matrix.height / 2) {
            append("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 ")
            append(matrix.width).append(' ').append(matrix.height)
            append("\" width=\"").append(sizePx).append("\" height=\"").append(sizePx)
            append("\" shape-rendering=\"crispEdges\">")
            append("<rect width=\"100%\" height=\"100%\" fill=\"#fff\"/><path d=\"")
            for (y in 0 until matrix.height) {
                for (x in 0 until matrix.width) {
                    if (matrix.get(x, y)) append('M').append(x).append(' ').append(y).append("h1v1h-1z")
                }
            }
            append("\" fill=\"#1a1a1a\"/></svg>")
        }
    }
}
