package com.mamba.picme.features.chat.openui

import com.mamba.picme.domain.chat.openui.OpenUiLibrary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * OpenUI Icon 渲染映射同源钉住（M2，spec《openui-component-library-m2-design》§4）：
 * shared schema ENUM 域（[OpenUiLibrary.iconNames]）的每一枚名字都必须能被端侧
 * [openUiIcon] 映射出图标——防止 schema 白名单与渲染映射脱节（校验放行但渲染丢组件）。
 */
class OpenUiIconMappingTest {

    @Test
    fun `every icon enum name resolves to a material icon`() {
        val nameProp = OpenUiLibrary.components.getValue("Icon").props.single { prop -> prop.name == "name" }
        assertEquals(OpenUiLibrary.iconNames, nameProp.enumValues)
        assertEquals(59, nameProp.enumValues.size)
        for (name in nameProp.enumValues) {
            assertNotNull("Icon name \"$name\" 无渲染映射（openUiIcon null）", openUiIcon(name))
        }
    }
}
