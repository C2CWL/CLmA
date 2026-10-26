package com.clma.app.subagent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.int
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure half of `mission_dispatch`: task parsing, the 1..6 ceiling, label derivation, the
 * result cap and the aggregate envelope. No engine, no Android — a batch that fans out six runs
 * is too expensive to only ever exercise in CI.
 */
class MissionDispatchTest {

    private fun tasks(json: String) = parseMissionTasks(Json.parseToJsonElement(json))

    @Test
    fun acceptsBetweenOneAndSixTasks() {
        val one = tasks("""[{"prompt":"a"}]""")
        assertTrue(one is MissionTaskParseResult.Ok)
        assertEquals(1, (one as MissionTaskParseResult.Ok).tasks.size)

        val six = tasks((1..6).joinToString(",", "[", "]") { """{"prompt":"task $it"}""" })
        assertTrue(six is MissionTaskParseResult.Ok)
        assertEquals(6, (six as MissionTaskParseResult.Ok).tasks.size)
    }

    @Test
    fun rejectsMoreThanSixTasksRatherThanTrimming() {
        val seven = tasks((1..7).joinToString(",", "[", "]") { """{"prompt":"task $it"}""" })
        assertTrue(seven is MissionTaskParseResult.Reject)
        assertEquals("invalid_tasks", (seven as MissionTaskParseResult.Reject).error)
        assertTrue(seven.detail.contains("got 7"))
    }

    @Test
    fun rejectsAnEmptyOrMissingTaskList() {
        assertTrue(tasks("[]") is MissionTaskParseResult.Reject)
        assertTrue(parseMissionTasks(null) is MissionTaskParseResult.Reject)
        assertTrue(parseMissionTasks(Json.parseToJsonElement("""{"prompt":"x"}""")) is MissionTaskParseResult.Reject)
    }

    @Test
    fun rejectsAnEntryWithoutAUsablePrompt() {
        assertTrue(tasks("""[{"title":"no prompt"}]""") is MissionTaskParseResult.Reject)
        assertTrue(tasks("""[{"prompt":"   "}]""") is MissionTaskParseResult.Reject)
        assertTrue(tasks("""["just a string"]""") is MissionTaskParseResult.Reject)
    }

    @Test
    fun keepsTitleAndAgentButDropsBlankOnes() {
        val ok = tasks(
            """[{"prompt":"  look it up  ","title":"  lookup ","agent":" Research "},
               |{"prompt":"other","title":"   ","agent":""}]""".trimMargin()
        ) as MissionTaskParseResult.Ok
        assertEquals("look it up", ok.tasks[0].prompt)
        assertEquals("lookup", ok.tasks[0].title)
        assertEquals("Research", ok.tasks[0].agent)
        assertNull(ok.tasks[1].title)
        assertNull(ok.tasks[1].agent)
    }

    @Test
    fun clampsConcurrencyIntoOneToSix() {
        assertEquals(3, clampMissionConcurrency(null))
        assertEquals(1, clampMissionConcurrency(0))
        assertEquals(1, clampMissionConcurrency(-4))
        assertEquals(4, clampMissionConcurrency(4))
        assertEquals(6, clampMissionConcurrency(9))
    }

    @Test
    fun labelPrefersTitleThenTheFirstPromptLine() {
        assertEquals("short", missionLabel(MissionTask("prompt", title = "short"), 0))

        val longTitle = "t".repeat(SubAgentDefaults.MAX_LABEL_LENGTH + 20)
        assertEquals(
            SubAgentDefaults.MAX_LABEL_LENGTH,
            missionLabel(MissionTask("prompt", title = longTitle), 0).length,
        )

        assertEquals("first line", missionLabel(MissionTask("\n  first line  \nsecond"), 2))
        assertEquals("mission-3", missionLabel(MissionTask("\n\n"), 2))
    }

    @Test
    fun excerptLeavesShortResultsAloneAndCapsLongOnes() {
        assertNull(missionExcerpt(null))
        assertEquals("done", missionExcerpt("done"))

        val long = "x".repeat(MissionLimits.RESULT_EXCERPT_CHARS + 500)
        val capped = missionExcerpt(long)!!
        assertTrue(capped.startsWith("x".repeat(MissionLimits.RESULT_EXCERPT_CHARS)))
        assertTrue(capped.contains("truncated at ${MissionLimits.RESULT_EXCERPT_CHARS}"))
        assertTrue(capped.length < long.length)
    }

    @Test
    fun envelopeCountsOutcomesAndOmitsAbsentFields() {
        val body = encodeMissionOutcomes(
            listOf(
                MissionOutcome(
                    index = 0,
                    label = "ok",
                    status = SubAgentStatus.SUCCEEDED.name,
                    runId = "run-1",
                    result = "fine",
                    tokensIn = 11,
                    tokensOut = 22,
                ),
                MissionOutcome(
                    index = 1,
                    label = "bad",
                    status = "REJECTED",
                    error = "no_recursion",
                    detail = "nope",
                ),
            ),
            maxConcurrency = 2,
        ).let { Json.parseToJsonElement(it).jsonObject }

        assertEquals(2, body["dispatched"]!!.jsonPrimitive.int)
        assertEquals(1, body["succeeded"]!!.jsonPrimitive.int)
        assertEquals(1, body["failed"]!!.jsonPrimitive.int)
        assertEquals(2, body["max_concurrency"]!!.jsonPrimitive.int)

        val results = body["results"]!!.jsonArray
        val first = results[0].jsonObject
        assertEquals("run-1", first["id"]!!.jsonPrimitive.content)
        assertNull(first["truncated"])
        assertNull(first["agent"])
        assertEquals("no_recursion", results[1].jsonObject["error"]!!.jsonPrimitive.content)
        assertEquals(true, first.containsKey("tokens_in"))
    }

    @Test
    fun envelopeMarksTruncatedResults() {
        val body = encodeMissionOutcomes(
            listOf(
                MissionOutcome(
                    index = 0,
                    label = "long",
                    status = SubAgentStatus.SUCCEEDED.name,
                    runId = "run-2",
                    result = "short",
                    truncated = true,
                ),
            ),
            maxConcurrency = 1,
        ).let { Json.parseToJsonElement(it).jsonObject }
        assertEquals(
            true,
            body["results"]!!.jsonArray[0].jsonObject["truncated"]!!.jsonPrimitive.booleanOrNull,
        )
    }
}
