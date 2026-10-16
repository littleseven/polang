package com.mamba.picme.server.analytics

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * formatCostCny 参数化测试：成本展示精度。
 * DeepSeek 等低成本模型单次调用 cost 常在 ¥0.001 量级，%.2f 会四舍五入成 "0.00"。
 */
@RunWith(Parameterized::class)
class FormatCostCnyTest(private val cost: Double, private val expected: String) {

    companion object {
        @Parameterized.Parameters(name = "{0} -> {1}")
        @JvmStatic
        fun data(): Collection<Array<out Any>> = listOf(
            arrayOf<Any>(0.0044, "0.0044"),
            arrayOf<Any>(0.0007, "0.0007"),
            arrayOf<Any>(12.34, "12.34"),
            arrayOf<Any>(0.0, "0.00"),
        )
    }

    @Test
    fun formatCostCny() {
        assertEquals(expected, formatCostCny(cost))
    }
}
