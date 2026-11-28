package com.clma.app.data.botmode.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class BotSettingsTest {
    @Test
    fun askUsesOnlySavedGrants() {
        val defaults = BotModeSettings()
        val profile = BotProfile(alwaysAllow = listOf("get_time_info"))
        assertTrue(botAutoVerdict("get_time_info", defaults, profile))
        assertFalse(botAutoVerdict("search_web", defaults, profile))
        assertFalse(botAutoVerdict("get_time_info", defaults, BotProfile()))
    }

    @Test
    fun autoOnlyApprovesKnownReadToolsAndExactGrants() {
        val defaults = BotModeSettings(approvalLevel = BotApprovalLevel.AUTO)
        val profile = BotProfile(alwaysAllow = listOf("update_note"))
        assertTrue(botAutoVerdict("search_web", defaults, profile))
        assertTrue(botAutoVerdict("current_conversation_search", defaults, profile))
        assertTrue(botAutoVerdict("update_note", defaults, profile))
        assertFalse(botAutoVerdict("update_other_note", defaults, profile))
        assertFalse(botAutoVerdict("mcp__abcdef01_docs__list_pages", defaults, profile))
        assertFalse(botAutoVerdict("mcp__abcdef01_docs__search_web", defaults, profile))
        assertFalse(botAutoVerdict("search_contacts", defaults, profile))
        assertFalse(botAutoVerdict("search_web", defaults, profile.copy(approvalLevel = BotApprovalLevel.ASK)))
    }

    @Test
    fun fullRespectsHumanQuestionsOutboundAndSafetyFloor() {
        val defaults = BotModeSettings(approvalLevel = BotApprovalLevel.FULL)
        val profile = BotProfile(alwaysAllow = listOf("ask_user", "request_secret", "send_sms", "github_issue_create"))
        assertTrue(botAutoVerdict("update_note", defaults, profile))
        assertFalse(botAutoVerdict("ask_user", defaults, profile))
        assertFalse(botAutoVerdict("request_secret", defaults, profile))
        assertFalse(botAutoVerdict("mcp__server__AskUserQuestion", defaults, profile))
        assertFalse(botAutoVerdict("send_sms", defaults, profile))
        assertTrue(botAutoVerdict("send_sms", defaults, profile.copy(outbound = BotOutboundPolicy.ALLOW)))
        assertFalse(botAutoVerdict("github_issue_create", defaults, profile, safetyFloor = true))
    }

    @Test
    fun outboundChoiceOverridesTierAndExactGrantsButKeepsConsentFloor() {
        val defaults = BotModeSettings(outbound = BotOutboundPolicy.ALLOW)
        val profile = BotProfile(outbound = BotOutboundPolicy.ASK, alwaysAllow = listOf("send_sms"))
        assertFalse(botAutoVerdict("send_sms", defaults, profile))
        assertTrue(botAutoVerdict("send_sms", defaults, BotProfile()))
        assertFalse(botAutoVerdict("send_sms", defaults, BotProfile(), safetyFloor = true))
    }

    @Test
    fun allowingOutboundDoesNotGrantOpaqueApiToolsAtAskTier() {
        val defaults = BotModeSettings(outbound = BotOutboundPolicy.ALLOW)
        val tool = "mcp__example__invoke_api_endpoint"
        val profile = BotProfile(alwaysAllow = listOf(tool))
        assertFalse(botAutoVerdict(tool, defaults, profile))
        assertTrue(botAutoVerdict(tool, defaults, profile.copy(approvalLevel = BotApprovalLevel.FULL)))
    }

    @Test
    fun classifierMatchesLocalConnectorAndMcpActions() {
        listOf(
            "send_sms", "send_email_intent", "gmail_send_email", "GMAIL_SEND_EMAIL",
            "mcp__12345678_slack__postMessage", "mcp__12345678_docs__create_comment",
            "mcp__email_provider__invoke_api_endpoint", "COMPOSIO_MULTI_EXECUTE_TOOL",
            "mcp__composio__COMPOSIO_REMOTE_WORKBENCH", "notification_reply", "ssh_upload",
        ).forEach { tool ->
            assertTrue(tool, BotToolNames.isOutbound(tool))
            assertFalse(tool, BotToolNames.isReadOnly(tool))
        }
        listOf(
            "search_sms", "memory_search", "mcp__server_with_underscores__get_post",
            "mcp__12345678_composio__GOOGLECALENDAR_EVENTS_LIST", "GMAIL_FETCH_MESSAGE_BY_MESSAGE_ID",
        ).forEach { tool ->
            assertTrue(tool, BotToolNames.isReadOnly(tool))
            assertFalse(tool, BotToolNames.isOutbound(tool))
        }
        listOf(
            "write_file", "mcp__abcdef01_mail__create_draft_email", "delete_comment",
            "post_notification", "termux_session_send",
        ).forEach { tool ->
            assertFalse(tool, BotToolNames.isOutbound(tool))
            assertFalse(tool, BotToolNames.isReadOnly(tool))
        }
    }

    @Test
    fun allowListDeduplicatesAndRefusesOverflow() {
        val almostFull = List(BOT_ALWAYS_ALLOW_MAX - 1) { "tool_$it" }
        val full = almostFull + "last"
        assertEquals(full, withAlwaysAllowTool(almostFull, "last"))
        assertEquals(full, withAlwaysAllowTool(full, "last"))
        assertNull(withAlwaysAllowTool(full, "overflow"))
        assertEquals(listOf("a", "b"), withAlwaysAllowTool(listOf("a", "a"), "b"))
    }

    @Test
    fun electingChiefDemotesOnlyOtherChiefsAndPreservesTheirProfiles() {
        val previous = BotProfile(chiefOfStaff = true, soul = "Keep this", alwaysAllow = listOf("search_web"))
        val teammate = BotProfile(reportsTo = "old")
        val newChief = BotProfile(chiefOfStaff = true)
        val result = withBotProfile(mapOf("old" to previous, "peer" to teammate), "new", newChief)
        assertEquals(newChief, result["new"])
        assertEquals(previous.copy(chiefOfStaff = false), result["old"])
        assertEquals(teammate, result["peer"])
        assertEquals(1, result.values.count { it.chiefOfStaff })
        assertEquals(result, withBotProfile(result, "peer", teammate))
    }

    @Test
    fun waitingTakesPriorityOverWorking() {
        assertEquals(BotActivity.IDLE, botActivity(generating = false, pending = false))
        assertEquals(BotActivity.WORKING, botActivity(generating = true, pending = false))
        assertEquals(BotActivity.WAITING, botActivity(generating = true, pending = true))
        assertEquals(BotActivity.WAITING, botActivity(generating = false, pending = false, parked = true))
    }

    @Test
    fun settingsAndProfilesRoundTripIndependently() {
        val defaults = BotModeSettings(
            approvalLevel = BotApprovalLevel.AUTO,
            outbound = BotOutboundPolicy.ALLOW,
            notifyReplies = false,
            notifyRoutineResults = false,
            notifyApprovals = false,
            showRoutineReliability = false,
        )
        val profiles = mapOf(
            "bot-a" to BotProfile(
                approvalLevel = BotApprovalLevel.FULL,
                outbound = BotOutboundPolicy.ASK,
                alwaysAllow = listOf("search_web"),
                soul = "Prefer short answers.\n回复简洁。",
                description = "Research",
                chiefOfStaff = true,
            ),
            "bot-b" to BotProfile(reportsTo = "bot-a"),
        )
        assertEquals(defaults, BotSettingsCodec.decodeSettings(BotSettingsCodec.encodeSettings(defaults)))
        assertEquals(profiles, BotSettingsCodec.decodeProfiles(BotSettingsCodec.encodeProfiles(profiles)))
    }

    @Test
    fun settingsCodecReadsAbsentValuesAndIgnoresFutureFields() {
        assertEquals(BotModeSettings(), BotSettingsCodec.decodeSettings(null))
        assertEquals(emptyMap<String, BotProfile>(), BotSettingsCodec.decodeProfiles(null))
        assertEquals(BotModeSettings(), BotSettingsCodec.decodeSettings("""{"approvalLevel":"FUTURE"}"""))
        assertEquals(
            mapOf("a" to BotProfile()),
            BotSettingsCodec.decodeProfiles("""{"a":{"outbound":"FUTURE","future":1}}"""),
        )
    }

    @Test
    fun activityOpensTheWaitingRunAndPrefersLiveStateForTheSameConversation() {
        val activeId = Uuid.random()
        val olderId = Uuid.random()
        val parkedId = Uuid.random()
        val working = LiveBotConversation(activeId, generating = true, pending = false)
        assertEquals(BotActivitySnapshot(BotActivity.WORKING), botActivitySnapshot(setOf(activeId), listOf(working), null))
        assertEquals(
            BotActivitySnapshot(BotActivity.WAITING, olderId),
            botActivitySnapshot(setOf(olderId), listOf(working), null),
        )
        assertEquals(
            BotActivitySnapshot(BotActivity.WAITING, parkedId),
            botActivitySnapshot(setOf(olderId), listOf(working.copy(pending = true)), parkedId),
        )
        assertEquals(
            BotActivitySnapshot(BotActivity.WAITING, activeId),
            botActivitySnapshot(emptySet(), listOf(working.copy(pending = true)), null),
        )
    }

    @Test
    fun profilePromptUsesOnlyActualTeammatesAndOmitsEmptySections() {
        val teammates = listOf(
            BotTeamMember("a", "Chief", "Self description"),
            BotTeamMember("b", "Research", "Reads reports"),
        )
        val prompt = requireNotNull(botProfilePrompt(
            "a", BotProfile(soul = " Keep it short ", chiefOfStaff = true, reportsTo = "b"), teammates,
        ))
        assertTrue(prompt.contains("## Standing instructions"))
        assertTrue(prompt.contains("\nKeep it short\n"))
        assertTrue(prompt.contains("You report to Research"))
        assertTrue(prompt.contains("Research (id b): Reads reports"))
        assertTrue(prompt.contains("ask_bot") && prompt.contains("delegate_bot") && prompt.contains("coordinate_bots"))
        assertFalse(prompt.contains("Self description"))
        assertNull(botProfilePrompt("a", BotProfile(reportsTo = "missing"), teammates))
        val headless = requireNotNull(botProfilePrompt("a", BotProfile(chiefOfStaff = true), teammates, canDelegate = false))
        assertFalse(headless.contains("delegate_bot"))
        assertTrue(headless.contains("Research (id b)"))
    }
}
