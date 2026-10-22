package me.rerere.rikkahub.workflow.execution

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-07 — `ActionTemplates` is the only place a workflow can splice one action's output into
 * the next action's arguments, so every safety property claimed in its KDoc is pinned here:
 * single-pass substitution, strings only, backward references only, a loud named error for
 * every failure, and a secret policy that is exactly "web_fetch request headers".
 */
class ActionTemplatesTest {

    private fun ok(outcome: ActionTemplates.Outcome): ActionTemplates.Outcome.Ok =
        outcome as? ActionTemplates.Outcome.Ok
            ?: throw AssertionError("expected Ok but got $outcome")

    private fun err(outcome: ActionTemplates.Outcome): ActionTemplates.Outcome.Err =
        outcome as? ActionTemplates.Outcome.Err
            ?: throw AssertionError("expected Err but got $outcome")

    private fun run(
        args: JsonObject,
        actionIndex: Int = 1,
        tool: String = "web_fetch",
        outputs: List<String> = listOf("hello"),
        secrets: Map<String, String> = emptyMap(),
        known: Set<String> = emptySet(),
    ): ActionTemplates.Outcome = ActionTemplates.resolve(
        args = args,
        actionIndex = actionIndex,
        toolName = tool,
        outputs = outputs,
        secrets = secrets,
        knownSecretNames = known,
    )

    private fun text(value: JsonObject, key: String): String =
        value[key]!!.jsonPrimitive.content

    // -- the no-op path ------------------------------------------------------

    @Test
    fun `no reference returns the same instance so the disabled path is untouched`() {
        val args = buildJsonObject { put("url", "https://example.com/api") }
        val outcome = ok(run(args))
        assertSame(args, outcome.args)
        assertEquals(0, outcome.usedRefs)
    }

    @Test
    fun `a literal string that merely looks brace-ish is not a reference`() {
        val args = buildJsonObject { put("note", "{ not a template }") }
        val outcome = ok(run(args))
        assertSame(args, outcome.args)
    }

    // -- action references ---------------------------------------------------

    @Test
    fun `whole text of an earlier action is substituted inline`() {
        val args = buildJsonObject { put("body", "prefix-{{actions[0].text}}-suffix") }
        val outcome = ok(run(args, actionIndex = 1, outputs = listOf("MID")))
        assertEquals("prefix-MID-suffix", text(outcome.args, "body"))
        assertEquals(1, outcome.usedRefs)
    }

    @Test
    fun `json path walks objects and array indices`() {
        val json = """{"data":{"items":[{"id":"a"},{"id":"b"}]}}"""
        val args = buildJsonObject { put("q", "{{actions[0].json.data.items[1].id}}") }
        val outcome = ok(run(args, actionIndex = 1, outputs = listOf(json)))
        assertEquals("b", text(outcome.args, "q"))
    }

    @Test
    fun `bare json reference hands back the whole document`() {
        val json = """{"a":1}"""
        val args = buildJsonObject { put("q", "{{actions[0].json}}") }
        val outcome = ok(run(args, actionIndex = 1, outputs = listOf(json)))
        assertEquals(json, text(outcome.args, "q"))
    }

    @Test
    fun `a null json leaf becomes the literal null string`() {
        val json = """{"a":null}"""
        val args = buildJsonObject { put("q", "{{actions[0].json.a}}") }
        val outcome = ok(run(args, actionIndex = 1, outputs = listOf(json)))
        assertEquals("null", text(outcome.args, "q"))
    }

    @Test
    fun `references are found at any depth inside objects and arrays`() {
        val args = buildJsonObject {
            putJsonObject("outer") {
                putJsonArray("inner") {
                    add(JsonPrimitive("{{actions[0].text}}"))
                }
            }
        }
        val outcome = ok(run(args, actionIndex = 1, outputs = listOf("deep")))
        val outer = outcome.args["outer"]!! as JsonObject
        val inner = outer["inner"]!! as JsonArray
        assertEquals("deep", inner[0].jsonPrimitive.content)
    }

    @Test
    fun `two references to the same source are both substituted`() {
        val args = buildJsonObject {
            put("a", "{{actions[0].text}}")
            put("b", "x{{actions[0].text}}y")
        }
        val outcome = ok(run(args, actionIndex = 1, outputs = listOf("V")))
        assertEquals("V", text(outcome.args, "a"))
        assertEquals("xVy", text(outcome.args, "b"))
        assertEquals(2, outcome.usedRefs)
    }

    // -- safety properties ---------------------------------------------------

    @Test
    fun `substitution is single pass so hostile output cannot re-expand`() {
        val args = buildJsonObject { put("q", "{{actions[1].text}}") }
        val outcome = ok(
            run(args, actionIndex = 2, outputs = listOf("seed", "{{actions[0].text}}")),
        )
        assertEquals("{{actions[0].text}}", text(outcome.args, "q"))
    }

    @Test
    fun `only strings are templated - numbers and booleans survive untouched`() {
        val args = buildJsonObject {
            put("n", 3)
            put("flag", true)
            put("s", "{{actions[0].text}}")
        }
        val outcome = ok(run(args, actionIndex = 1, outputs = listOf("V")))
        assertFalse(outcome.args["n"]!!.jsonPrimitive.isString)
        assertFalse(outcome.args["flag"]!!.jsonPrimitive.isString)
        assertEquals("3", text(outcome.args, "n"))
        assertEquals("V", text(outcome.args, "s"))
    }

    @Test
    fun `a template can never introduce a new key`() {
        val args = buildJsonObject { put("q", "{{actions[0].text}}") }
        val outcome = ok(run(args, actionIndex = 1, outputs = listOf("""{"evil":"x"}""")))
        assertEquals(setOf("q"), outcome.args.keys)
    }

    // -- error paths ---------------------------------------------------------

    @Test
    fun `forward reference is rejected`() {
        val args = buildJsonObject { put("q", "{{actions[1].text}}") }
        assertEquals("forward_reference", err(run(args, actionIndex = 0, outputs = emptyList())).code)
    }

    @Test
    fun `self reference is rejected`() {
        val args = buildJsonObject { put("q", "{{actions[0].text}}") }
        assertEquals("forward_reference", err(run(args, actionIndex = 0, outputs = emptyList())).code)
    }

    @Test
    fun `referencing an action that produced no text is an error not an empty string`() {
        val args = buildJsonObject { put("q", "{{actions[1].text}}") }
        val outcome = err(run(args, actionIndex = 2, outputs = listOf("only-one")))
        assertEquals("unknown_action_output", outcome.code)
    }

    @Test
    fun `a missing json path is an error not an empty string`() {
        val args = buildJsonObject { put("q", "{{actions[0].json.nope}}") }
        val outcome = err(run(args, actionIndex = 1, outputs = listOf("""{"a":1}""")))
        assertEquals("json_path_not_found", outcome.code)
    }

    @Test
    fun `a non-json output cannot be walked`() {
        val args = buildJsonObject { put("q", "{{actions[0].json.a}}") }
        val outcome = err(run(args, actionIndex = 1, outputs = listOf("not json at all")))
        assertEquals("json_path_not_found", outcome.code)
    }

    @Test
    fun `unknown reference form is rejected`() {
        val args = buildJsonObject { put("q", "{{foo}}") }
        assertEquals("unknown_reference", err(run(args)).code)
    }

    @Test
    fun `missing bracket is rejected`() {
        val args = buildJsonObject { put("q", "{{actions[0.text}}") }
        assertEquals("bad_action_reference", err(run(args)).code)
    }

    @Test
    fun `non numeric action index is rejected`() {
        val args = buildJsonObject { put("q", "{{actions[x].text}}") }
        assertEquals("bad_action_index", err(run(args)).code)
    }

    @Test
    fun `bad action field is rejected`() {
        val args = buildJsonObject { put("q", "{{actions[0].body}}") }
        assertEquals("bad_action_field", err(run(args, actionIndex = 1, outputs = listOf("x"))).code)
    }

    @Test
    fun `unparseable json path is rejected`() {
        val args = buildJsonObject { put("q", "{{actions[0].json..a}}") }
        assertEquals("bad_json_path", err(run(args, actionIndex = 1, outputs = listOf("{}"))).code)
    }

    @Test
    fun `json path deeper than the cap is rejected`() {
        val deep = ".a".repeat(ActionTemplates.MAX_PATH_DEPTH + 1)
        val args = buildJsonObject { put("q", "{{actions[0].json$deep}}") }
        val outcome = err(run(args, actionIndex = 1, outputs = listOf("{}")))
        assertEquals("json_path_too_deep", outcome.code)
    }

    @Test
    fun `more references than the cap is rejected`() {
        val many = (1..ActionTemplates.MAX_REFS_PER_ACTION + 1)
            .joinToString("") { "{{actions[0].text}}" }
        val args = buildJsonObject { put("q", many) }
        assertEquals("too_many_refs", err(run(args, actionIndex = 1, outputs = listOf("x"))).code)
    }

    @Test
    fun `a huge resolved value is rejected`() {
        val big = "x".repeat(ActionTemplates.MAX_VALUE_CHARS + 1)
        val args = buildJsonObject { put("q", "{{actions[0].text}}") }
        assertEquals("value_too_large", err(run(args, actionIndex = 1, outputs = listOf(big))).code)
    }

    @Test
    fun `the cap allows exactly the maximum and rejects one more`() {
        val atCap = "x".repeat(ActionTemplates.MAX_VALUE_CHARS)
        val args = buildJsonObject { put("q", "{{actions[0].text}}") }
        val outcome = ok(run(args, actionIndex = 1, outputs = listOf(atCap)))
        assertEquals(ActionTemplates.MAX_VALUE_CHARS, text(outcome.args, "q").length)
    }

    // -- secrets -------------------------------------------------------------

    @Test
    fun `secret name character set is validated`() {
        val args = buildJsonObject { put("q", "{{secret:bad-name}}") }
        assertEquals("bad_secret_name", err(run(args, tool = "web_fetch")).code)
    }

    @Test
    fun `an empty secret name is rejected`() {
        val args = buildJsonObject { put("q", "{{secret:}}") }
        assertEquals("bad_secret_name", err(run(args, tool = "web_fetch")).code)
    }

    @Test
    fun `a secret outside web_fetch headers is rejected`() {
        val args = buildJsonObject { put("command", "{{secret:TOKEN}}") }
        val outcome = err(run(args, tool = "termux_run_command", known = setOf("TOKEN")))
        assertEquals("secret_not_allowed", outcome.code)
    }

    @Test
    fun `a secret in a web_fetch non-header argument is rejected`() {
        val args = buildJsonObject { put("url", "{{secret:TOKEN}}") }
        val outcome = err(run(args, tool = "web_fetch", known = setOf("TOKEN")))
        assertEquals("secret_not_allowed", outcome.code)
    }

    @Test
    fun `a secret is substituted inside a web_fetch header`() {
        val args = buildJsonObject {
            putJsonObject("headers") { put("Authorization", "Bearer {{secret:TOKEN}}") }
        }
        val outcome = ok(
            run(
                args,
                tool = "web_fetch",
                known = setOf("TOKEN"),
                secrets = mapOf("TOKEN" to "abc123"),
            ),
        )
        val headers = outcome.args["headers"]!! as JsonObject
        assertEquals("Bearer abc123", headers["Authorization"]!!.jsonPrimitive.content)
    }

    @Test
    fun `an unknown stored secret is reported, not silently emptied`() {
        val args = buildJsonObject {
            putJsonObject("headers") { put("X-Key", "{{secret:MISSING}}") }
        }
        val outcome = err(run(args, tool = "web_fetch", known = setOf("OTHER")))
        assertEquals("secret_not_found", outcome.code)
    }

    @Test
    fun `secretRefAllowed is exactly web_fetch headers`() {
        assertTrue(ActionTemplates.secretRefAllowed("web_fetch", listOf("headers", "Authorization")))
        assertFalse(ActionTemplates.secretRefAllowed("web_fetch", listOf("headers")))
        assertFalse(ActionTemplates.secretRefAllowed("web_fetch", listOf("body")))
        assertFalse(ActionTemplates.secretRefAllowed("web_fetch", listOf("headers", "a", "b")))
        assertFalse(ActionTemplates.secretRefAllowed("web_extract", listOf("headers", "Authorization")))
        assertFalse(ActionTemplates.secretRefAllowed("termux_run_command", listOf("headers", "X")))
    }

    @Test
    fun `referencedSecretNames collects only well formed names`() {
        val args = buildJsonObject {
            put("a", "{{secret:GOOD_1}}")
            put("b", "{{secret:bad-name}}")
            put("c", "{{actions[0].text}}")
        }
        assertEquals(setOf("GOOD_1"), ActionTemplates.referencedSecretNames(args))
    }

    @Test
    fun `referencedSecretNames is empty when nothing references a secret`() {
        val args = buildJsonObject { put("q", "{{actions[0].text}}") }
        assertTrue(ActionTemplates.referencedSecretNames(args).isEmpty())
    }
}
