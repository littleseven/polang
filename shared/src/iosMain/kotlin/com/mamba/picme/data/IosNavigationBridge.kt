package com.mamba.picme.data

/**
 * Swift → Kotlin 的导航桥协议（2026-09-16 主导航统一，main-nav.yaml §4）。
 *
 * 对齐 Android `NavigationCapability` 的 navigate_to 执行语义：ChatToolService
 * `navigate_to(destination)` 工具经 CapabilityRegistry 路由到 IosNavigationCapability，
 * 再由本桥落到 Swift UI 层真实切页/弹出。
 *
 * SharedBridge 铁律（同 [IosChartBridge] / [IosAiOptimizeBridge]）：
 * - Swift 实现侧绝不抛异常跨边界（逃逸会 signal 6 / SIGABRT）；
 * - UI 导航必须切主线程执行（capability 在 Kotlin 协程线程调用本桥）；
 * - 返回值为「目的地是否受支持且已受理」的同步判定，UI 切换异步生效。
 */
interface IosNavigationBridge {

    /**
     * 执行导航到指定目的地。
     *
     * Swift 实现语义（对齐 main-nav.yaml §4 destinations 矩阵）：
     * - `camera`   → 相机 fullScreenCover 弹出
     * - `gallery`  → 切主 Pager 页 0（相册）
     * - `settings` → 设置 fullScreenCover 弹出
     * - 其余（含 `debug`）→ 返回 false（iOS 无对应页）
     *
     * @param destination ChatToolService navigate_to 工具的 destination 原值
     * @return true = 目的地受支持且已受理；false = 不支持
     */
    fun navigateTo(destination: String): Boolean
}
