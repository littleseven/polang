package com.mamba.picme.features.chat.a2ui

import androidx.a2ui.compose.ui.toJsonSchemaString
import androidx.a2ui.model.catalog.functions.A2uiUrlOpener
import com.mamba.picme.domain.chat.a2ui.A2uiCardContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PolangA2uiCatalogTest {

    private val catalog = polangA2uiCatalog(A2uiUrlOpener { })

    @Test
    fun catalogId_isPolangContractId() {
        assertEquals(A2uiCardContract.CATALOG_ID, catalog.id)
    }

    @Test
    fun barChart_isRegistered() {
        assertNotNull(catalog.components[PolangBarChartComponent.NAME])
    }

    @Test
    fun catalog_containsSixWhitelistedComponents() {
        // T4 移交硬约束：catalog 必须覆盖 Text/Row/Column/Card/Button/BarChart 六个名字
        val whitelist = listOf("Text", "Row", "Column", "Card", "Button", "BarChart")
        for (name in whitelist) {
            assertNotNull("catalog.components 缺少白名单组件 $name", catalog.components[name])
        }
    }

    @Test
    fun jsonSchema_containsBarChartAndMaterialBasics() {
        val schema = catalog.toJsonSchemaString()
        assertTrue(schema.contains("BarChart"))
        assertTrue(schema.contains("Text"))
        assertTrue(schema.contains("buckets"))
    }
}
