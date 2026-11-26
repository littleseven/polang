package com.mamba.picme.server.agent

/**
 * OpenUI 校验栈过渡别名（U1 本地回合 OpenUI 统一，spec
 * 《2026-11-24-local-turn-openui-unification-design》）：校验器/布局门禁/prompt
 * 资产/回合守卫七件已上提 shared `com.mamba.picme.domain.chat.openui` 为端云单一
 * 事实源（OpenUiTurnNative 为薄壳对象委托，在其原文件），本文件以 typealias 维持
 * server 包内旧名——server 消费方（OpenUiTurnNative/ProgramGuard/AgentRunner/
 * AgentRoutes/AdminViews）与既有测试零改动、行为逐字节不变。后续各消费方切到
 * shared 名后逐件摘除。
 */
typealias RenderOpenUiValidator = com.mamba.picme.domain.chat.openui.RenderOpenUiValidator
typealias OpenUiCallTree = com.mamba.picme.domain.chat.openui.OpenUiCallTree
typealias OpenUiLayoutValidator = com.mamba.picme.domain.chat.openui.OpenUiLayoutValidator
typealias OpenUiLayoutNormalizer = com.mamba.picme.domain.chat.openui.OpenUiLayoutNormalizer
typealias OpenUiPromptAssets = com.mamba.picme.domain.chat.openui.OpenUiPromptAssets
internal typealias OpenUiStringScan = com.mamba.picme.domain.chat.openui.OpenUiStringScan
internal typealias ProgramGuard = com.mamba.picme.domain.chat.openui.OpenUiProgramGuard
