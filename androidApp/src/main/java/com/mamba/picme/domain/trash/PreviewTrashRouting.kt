package com.mamba.picme.domain.trash

/**
 * 预览页上滑删除的通路分流（纯函数，JVM 可测）：
 * API 30+ 系统回收站可用 → [Route.TRASH]（30 天可恢复）；
 * API < 30 无回收站授权接口 → [Route.LEGACY_DELETE]（降级走既有永久删除 + 系统授权框通路）。
 */
object PreviewTrashRouting {

    enum class Route { TRASH, LEGACY_DELETE }

    fun resolve(trashSupported: Boolean): Route =
        if (trashSupported) Route.TRASH else Route.LEGACY_DELETE
}
