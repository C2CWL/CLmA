package com.clma.app.data.ai.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class ToolDisplayNamesTest {

    @Test
    fun everyHighRiskToolHasADisplayName() {
        val missing = ToolApprovalDefaults.NO_ALWAYS_ALLOW.filter { ToolDisplayNames.resFor(it) == null }
        assertTrue("NO_ALWAYS_ALLOW without display name: $missing", missing.isEmpty())
    }

    @Test
    fun everyRecommendedDangerousToolHasADisplayName() {
        val missing = ToolApprovalDefaults.DANGEROUS_TOOLS.filter { ToolDisplayNames.resFor(it) == null }
        assertTrue("DANGEROUS_TOOLS without display name: $missing", missing.isEmpty())
    }

    /**
     * Every built-in tool declared as `Tool(name = "…")` in the app sources has a display name,
     * so a new tool cannot silently ship with only its raw id. Source scan because LocalTools
     * needs an Android Context; skipped when the sources are not reachable from the test's
     * working directory.
     */
    @Test
    fun everyLiteralToolInTheSourcesHasADisplayName() {
        val root = listOf("src/main/java", "app/src/main/java").map(::File).firstOrNull { it.isDirectory }
        assumeTrue("app sources not found from ${File(".").absolutePath}", root != null)
        val pattern = Regex("""\bTool\(\s*name\s*=\s*"([a-z0-9_]+)"""")
        val found = root!!.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file -> pattern.findAll(file.readText()).map { it.groupValues[1] } }
            .toSet()
        // A connection-test probe tool, never offered to an assistant.
        val notUserFacing = setOf("get_current_time")
        assertTrue("source scan found too few tools: ${found.size}", found.size > 100)
        val missing = (found - notUserFacing).filter { ToolDisplayNames.resFor(it) == null }.sorted()
        assertTrue("tools without display name: $missing", missing.isEmpty())
    }

    @Test
    fun constantNamedToolsHaveDisplayNames() {
        // Declared via constants rather than literals, so the source scan above misses them.
        val names = listOf(
            "browser_open", "browser_eval_js", "browser_done", "request_secret",
            "generate_image", "edit_image", "generate_video",
            "subagent_create", "subagent_update", "subagent_delete",
        )
        assertEquals(emptyList<String>(), names.filter { ToolDisplayNames.resFor(it) == null })
    }

    @Test
    fun unknownToolHasNoEntry() {
        assertNull(ToolDisplayNames.resFor("definitely_not_a_tool"))
        assertNull(ToolDisplayNames.resFor("mcp__abcd1234_srv__run"))
    }

    @Test
    fun mcpPartsSplitServerAndTool() {
        assertEquals(
            ToolDisplayNames.McpParts("github", "create_issue"),
            ToolDisplayNames.mcpParts("mcp__abcd1234_github__create_issue"),
        )
        assertEquals(
            ToolDisplayNames.McpParts("my_server", "run__x"),
            ToolDisplayNames.mcpParts("mcp__abcd1234_my_server__run__x"),
        )
        assertNull(ToolDisplayNames.mcpParts("mcp_add"))
        assertNull(ToolDisplayNames.mcpParts("mcp__abcd1234_server"))
        assertNull(ToolDisplayNames.mcpParts("mcp__abcd1234_srv__"))
    }
}
