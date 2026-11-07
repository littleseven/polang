package com.mamba.picme.server.admin

import org.junit.Assert.assertTrue
import org.junit.Test

class QrCodeSvgTest {

    @Test
    fun `svg renders inline qr with viewbox path and quiet zone`() {
        val svg = QrCodeSvg.svg("polang://openui-preview?session=abcd1234")
        assertTrue(svg.startsWith("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 "))
        assertTrue(svg.contains("shape-rendering=\"crispEdges\""))
        assertTrue(svg.contains("<rect width=\"100%\" height=\"100%\" fill=\"#fff\"/>"))
        assertTrue(svg.contains("<path d=\"M")) // 黑块逐格合并
        assertTrue(svg.trimEnd().endsWith("</svg>"))
        // 默认尺寸 168 上屏
        assertTrue(svg.contains("width=\"168\" height=\"168\""))
    }

    @Test
    fun `svg output differs by content and keeps square modules`() {
        val a = QrCodeSvg.svg("polang://openui-preview?session=aaa")
        val b = QrCodeSvg.svg("polang://openui-preview?session=bbb")
        assertTrue(a != b)
        // 模块格 1x1 方块路径
        assertTrue(a.contains("h1v1h-1z"))
    }
}
