package com.clma.app.data.ai.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * The per-chat allow-list behind 💬 "Allow for this chat" and the in-chat "本对话授权" preset
 * sheet. It is a process-wide singleton, so every test uses fresh random conversation ids.
 */
class ToolApprovalAllowListTest {

    private val list = ToolApprovalAllowList

    @Test
    fun grantRevokeAndListAreScopedToOneConversation() {
        val a = Uuid.random()
        val b = Uuid.random()
        list.grantForChat(a, "eval_javascript")
        list.grantForChat(a, "mcp__abcd1234_srv__run")

        assertTrue(list.isAllowedForChat(a, "eval_javascript"))
        assertFalse(list.isAllowedForChat(b, "eval_javascript"))
        assertEquals(listOf("eval_javascript", "mcp__abcd1234_srv__run"), list.listForChat(a))
        assertEquals(emptyList<String>(), list.listForChat(b))

        list.revokeForChat(a, "eval_javascript")
        assertFalse(list.isAllowedForChat(a, "eval_javascript"))
        assertEquals(listOf("mcp__abcd1234_srv__run"), list.listForChat(a))
    }

    @Test
    fun clearChatDropsOnlyThatConversation() {
        val a = Uuid.random()
        val b = Uuid.random()
        list.grantForChat(a, "nfc_write_tag")
        list.grantForChat(b, "nfc_write_tag")
        list.clearChat(a)
        assertEquals(emptyList<String>(), list.listForChat(a))
        assertEquals(listOf("nfc_write_tag"), list.listForChat(b))
        list.clearChat(b)
    }

    @Test
    fun versionBumpsOnlyOnRealChanges() {
        val a = Uuid.random()
        val v0 = list.version.value
        list.grantForChat(a, "keystore_decrypt")
        val v1 = list.version.value
        assertTrue(v1 > v0)

        list.grantForChat(a, "keystore_decrypt") // already granted
        assertEquals(v1, list.version.value)

        list.revokeForChat(a, "not_granted")
        assertEquals(v1, list.version.value)

        list.revokeForChat(a, "keystore_decrypt")
        val v2 = list.version.value
        assertTrue(v2 > v1)

        list.clearChat(a) // nothing left to clear
        assertEquals(v2, list.version.value)

        list.grantForChat(a, "x")
        val v3 = list.version.value
        list.clearChat(a)
        assertTrue(list.version.value > v3)
    }

    @Test
    fun blankNamesAreIgnored() {
        val a = Uuid.random()
        val v0 = list.version.value
        list.grantForChat(a, "  ")
        assertEquals(emptyList<String>(), list.listForChat(a))
        assertEquals(v0, list.version.value)
    }

    @Test
    fun grantAllAddsEverythingAndBumpsOnce() {
        val a = Uuid.random()
        list.grantForChat(a, "eval_javascript")
        val v0 = list.version.value

        val added = list.grantAllForChat(
            a,
            listOf("eval_javascript", "mcp_add", "", "mcp__abcd1234_srv__run", "mcp_add"),
        )
        assertEquals(2, added)
        assertEquals(v0 + 1, list.version.value)
        assertEquals(
            listOf("eval_javascript", "mcp__abcd1234_srv__run", "mcp_add"),
            list.listForChat(a),
        )

        // Nothing new → no bump.
        assertEquals(0, list.grantAllForChat(a, listOf("mcp_add")))
        assertEquals(v0 + 1, list.version.value)
        list.clearChat(a)
    }

    @Test
    fun presetGrantMakesPolicyAutoApproveFloorAndMcpInManual() {
        val a = Uuid.random()
        list.grantAllForChat(a, listOf("grant_directory_access", "mcp__abcd1234_srv__run"))
        for (tool in listOf("grant_directory_access", "mcp__abcd1234_srv__run")) {
            assertTrue(
                ToolApprovalPolicy.shouldAutoApprove(
                    toolName = tool,
                    blacklist = emptySet(),
                    mode = ApprovalMode.MANUAL,
                    headless = false,
                    allowedForChat = list.isAllowedForChat(a, tool),
                )
            )
        }
        // A different chat still asks.
        assertFalse(
            ToolApprovalPolicy.shouldAutoApprove(
                toolName = "grant_directory_access",
                blacklist = emptySet(),
                mode = ApprovalMode.MANUAL,
                headless = false,
                allowedForChat = list.isAllowedForChat(Uuid.random(), "grant_directory_access"),
            )
        )
        list.clearChat(a)
    }
}
