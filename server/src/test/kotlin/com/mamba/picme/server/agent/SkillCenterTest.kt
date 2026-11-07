package com.mamba.picme.server.agent

import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SkillCenterTest {

    private lateinit var root: File

    @Before
    fun setUp() {
        root = kotlin.io.path.createTempDirectory(prefix = "skill-center-test").toFile()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun writeSkill(dirName: String, content: String) {
        val dir = File(root, dirName).apply { mkdirs() }
        File(dir, SkillCenter.SKILL_FILE).writeText(content)
    }

    private fun validSkill(name: String, description: String = "测试技能 $name", body: String = "正文指令") =
        "---\nname: $name\ndescription: $description\n---\n$body"

    @Test
    fun `manifest lists valid skills sorted by name`() {
        writeSkill("beta-skill", validSkill("beta-skill"))
        writeSkill("alpha-skill", validSkill("alpha-skill"))
        val manifest = SkillCenter(root).manifest()
        assertEquals(listOf("alpha-skill", "beta-skill"), manifest.map { it.name })
    }

    @Test
    fun `manifest skips invalid frontmatter`() {
        writeSkill("no-frontmatter", "直接正文")
        writeSkill("name-mismatch", validSkill("other-name"))
        writeSkill("missing-description", "---\nname: missing-description\n---\n正文")
        writeSkill("ok-skill", validSkill("ok-skill"))
        assertEquals(listOf("ok-skill"), SkillCenter(root).manifest().map { it.name })
    }

    @Test
    fun `content returns body without frontmatter`() {
        writeSkill("html-card", validSkill("html-card", body = "第一段\n\n第二段"))
        assertEquals("第一段\n\n第二段", SkillCenter(root).content("html-card"))
    }

    @Test
    fun `content rejects unknown and malformed names`() {
        writeSkill("html-card", validSkill("html-card"))
        val center = SkillCenter(root)
        assertNull(center.content("no-such-skill"))
        assertNull(center.content("../etc"))
        assertNull(center.content("HTML-CARD"))
        assertNull(center.content("html_card"))
        assertNull(center.content(""))
    }

    @Test
    fun `content truncates oversized body`() {
        writeSkill("big-skill", validSkill("big-skill", body = "x".repeat(SkillCenter.MAX_CONTENT_CHARS + 100)))
        val content = SkillCenter(root).content("big-skill")
        assertNotNull(content)
        assertTrue(content!!.length < SkillCenter.MAX_CONTENT_CHARS + 100)
        assertTrue(content.endsWith("[truncated: skill big-skill exceeds ${SkillCenter.MAX_CONTENT_CHARS} chars]"))
    }

    @Test
    fun `upsert creates loadable skill and overwrites`() {
        val center = SkillCenter(root)
        assertNull(center.upsert("vote-card", "投票卡撰写规范", "正文 v1"))
        assertEquals("正文 v1", center.content("vote-card"))
        assertEquals(listOf("vote-card"), center.manifest().map { it.name })
        assertNull(center.upsert("vote-card", "投票卡撰写规范 v2", "正文 v2"))
        assertEquals("正文 v2", center.content("vote-card"))
        assertEquals("投票卡撰写规范 v2", center.manifest().single().description)
    }

    @Test
    fun `upsert rejects invalid input`() {
        val center = SkillCenter(root)
        assertNotNull(center.upsert("Bad_Name", "d", "b"))
        assertNotNull(center.upsert("../escape", "d", "b"))
        assertNotNull(center.upsert("ok-name", "", "b"))
        assertNotNull(center.upsert("ok-name", "多行\n描述", "b"))
        assertNotNull(center.upsert("ok-name", "d", "  "))
        assertTrue(center.manifest().isEmpty())
    }

    @Test
    fun `delete removes skill and rejects bad names`() {
        val center = SkillCenter(root)
        assertNull(center.upsert("vote-card", "d", "b"))
        assertTrue(center.delete("vote-card"))
        assertTrue(center.manifest().isEmpty())
        assertTrue(!center.delete("vote-card"))
        assertTrue(!center.delete("../x"))
    }

    @Test
    fun `listDetailed exposes body and metadata`() {
        val center = SkillCenter(root)
        assertNull(center.upsert("html-card", "HTML 规范", "第一段"))
        val detail = center.detail("html-card")
        assertNotNull(detail)
        assertEquals("第一段", detail!!.body)
        assertTrue(detail.rawChars > 0)
        assertNull(center.detail("missing"))
    }

    @Test
    fun `promptSection is empty without skills and lists name plus description`() {
        val empty = SkillCenter(root)
        assertEquals("", empty.promptSection())
        writeSkill("html-card", validSkill("html-card", description = "HTML 沙箱卡撰写规范"))
        val section = SkillCenter(root).promptSection()
        assertTrue(section.contains("load_skill"))
        assertTrue(section.contains("- html-card: HTML 沙箱卡撰写规范"))
    }

    // ── OverlaySkillCenter（技能调试 playground 草稿覆盖）──

    private fun draft(name: String, description: String = "草稿 $name", body: String = "草稿正文") =
        SkillCenter.SkillDetail(
            meta = SkillCenter.SkillMeta(name, description),
            body = body, updatedAtMs = 0L, rawChars = body.length,
        )

    @Test
    fun `overlay replaces same-name disk skill in manifest and content`() {
        writeSkill("html-card", validSkill("html-card", description = "磁盘版描述", body = "磁盘版正文"))
        val overlay = OverlaySkillCenter(SkillCenter(root), draft("html-card", description = "草稿版描述", body = "草稿版正文"))
        val manifest = overlay.manifest()
        assertEquals(listOf("html-card"), manifest.map { it.name })
        assertEquals("草稿版描述", manifest.single().description)
        assertEquals("草稿版正文", overlay.content("html-card"))
        // promptSection 经 manifest 虚分派自动生效
        assertTrue(overlay.promptSection().contains("- html-card: 草稿版描述"))
    }

    @Test
    fun `overlay adds brand-new skill and passes through disk skills`() {
        writeSkill("html-card", validSkill("html-card", body = "磁盘版正文"))
        val overlay = OverlaySkillCenter(SkillCenter(root), draft("vote-card"))
        assertEquals(listOf("html-card", "vote-card"), overlay.manifest().map { it.name })
        assertEquals("磁盘版正文", overlay.content("html-card"))
        assertEquals("草稿正文", overlay.content("vote-card"))
    }

    @Test
    fun `overlay does not touch disk assets`() {
        writeSkill("html-card", validSkill("html-card", body = "磁盘版正文"))
        val base = SkillCenter(root)
        OverlaySkillCenter(base, draft("html-card", body = "草稿版正文")).content("html-card")
        // 磁盘真相不变
        assertEquals("磁盘版正文", base.content("html-card"))
        assertEquals("磁盘版正文", base.detail("html-card")!!.body)
    }

    @Test
    fun `overlay truncates oversized draft body`() {
        val big = draft("big-skill", body = "x".repeat(SkillCenter.MAX_CONTENT_CHARS + 100))
        val content = OverlaySkillCenter(SkillCenter(root), big).content("big-skill")
        assertNotNull(content)
        assertTrue(content!!.length < SkillCenter.MAX_CONTENT_CHARS + 100)
        assertTrue(content.endsWith("[truncated: skill big-skill exceeds ${SkillCenter.MAX_CONTENT_CHARS} chars]"))
    }
}
