package com.clma.app.skills

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GitHubSkillImporterParseTest {

    @Test
    fun `repo root url`() {
        val ref = GitHubSkillImporter.parse("https://github.com/owner/repo")
        assertEquals("owner", ref?.owner)
        assertEquals("repo", ref?.repo)
        assertNull(ref?.branch)
        assertNull(ref?.path)
    }

    @Test
    fun `tree url with branch and subdir`() {
        val ref = GitHubSkillImporter.parse(
            "https://github.com/owner/repo/tree/main/skills/demo",
        )
        assertEquals("owner", ref?.owner)
        assertEquals("repo", ref?.repo)
        assertEquals("main", ref?.branch)
        assertEquals("skills/demo", ref?.path)
    }

    @Test
    fun `blob url treated as containing dir`() {
        val ref = GitHubSkillImporter.parse(
            "https://github.com/owner/repo/blob/v1.0/skills/demo/SKILL.md",
        )
        assertEquals("v1.0", ref?.branch)
        assertEquals("skills/demo/SKILL.md", ref?.path)
    }

    // JVM 方法名不允许出现 '.', 所以这里的 ".git" 不能写成反引号原名,
    // 否则 Kotlin 编译期直接报 "Name contains illegal characters: .."。
    @Test
    fun `strips git suffix and www`() {
        val ref = GitHubSkillImporter.parse("https://www.github.com/owner/repo.git")
        assertEquals("owner", ref?.owner)
        assertEquals("repo", ref?.repo)
    }

    @Test
    fun `rejects non-github hosts and raw urls`() {
        assertNull(GitHubSkillImporter.parse("https://gitlab.com/owner/repo"))
        assertNull(GitHubSkillImporter.parse("https://raw.githubusercontent.com/owner/repo/main/SKILL.md"))
        assertNull(GitHubSkillImporter.parse("https://example.com/skill.md"))
        assertNull(GitHubSkillImporter.parse("not-a-url"))
    }
}
