package me.rerere.rikkahub.data.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class ModelRouterTest {

    private data class FakeModel(val id: Uuid, val usable: Boolean = true)

    @Test
    fun `putCooldown then isInCooldown returns true within window`() {
        var t = 0L
        val router = ModelRouter(nowMs = { t })
        val id = Uuid.random()
        router.putCooldown(id, durationMs = 5_000)
        assertTrue(router.isInCooldown(id))
    }

    @Test
    fun `isInCooldown returns false for unknown model`() {
        var t = 0L
        val router = ModelRouter(nowMs = { t })
        assertFalse(router.isInCooldown(Uuid.random()))
    }

    @Test
    fun `isInCooldown lazily removes expired entry and returns false`() {
        var t = 0L
        val router = ModelRouter(nowMs = { t })
        val id = Uuid.random()
        router.putCooldown(id, durationMs = 5_000)
        // advance time past the cooldown window
        t = 6_000
        assertFalse(router.isInCooldown(id))
        // a second check should still return false (entry was removed)
        assertFalse(router.isInCooldown(id))
    }

    @Test
    fun `isInCooldown returns true exactly at boundary minus one`() {
        var t = 0L
        val router = ModelRouter(nowMs = { t })
        val id = Uuid.random()
        router.putCooldown(id, durationMs = 5_000)
        // At t=4999, still in cooldown
        t = 4_999
        assertTrue(router.isInCooldown(id))
    }

    @Test
    fun `isInCooldown returns false exactly at boundary`() {
        var t = 0L
        val router = ModelRouter(nowMs = { t })
        val id = Uuid.random()
        router.putCooldown(id, durationMs = 5_000)
        // At t=5000, cooldown expired
        t = 5_000
        assertFalse(router.isInCooldown(id))
    }

    @Test
    fun `default cooldown duration is 5 minutes`() {
        var t = 0L
        val router = ModelRouter(nowMs = { t })
        val id = Uuid.random()
        router.putCooldown(id) // default
        // At 4:59, still cooling
        t = 4 * 60_000 + 59_000
        assertTrue(router.isInCooldown(id))
        // At 5:00, expired
        t = 5 * 60_000
        assertFalse(router.isInCooldown(id))
    }

    // --- nextRouteCandidate ---

    @Test
    fun `returns first favorite not excluded and not in cooldown`() {
        var t = 0L
        val router = ModelRouter(nowMs = { t })
        val a = Uuid.random()
        val b = Uuid.random()
        val c = Uuid.random()
        val models = mapOf(a to FakeModel(a), b to FakeModel(b), c to FakeModel(c))
        val result = router.nextRouteCandidate(
            favorites = listOf(a, b, c),
            findModel = { models[it] },
            isUsable = { it.usable },
            exclude = emptySet(),
        )
        assertEquals(a, result?.id)
    }

    @Test
    fun `skips excluded models`() {
        var t = 0L
        val router = ModelRouter(nowMs = { t })
        val a = Uuid.random()
        val b = Uuid.random()
        val models = mapOf(a to FakeModel(a), b to FakeModel(b))
        val result = router.nextRouteCandidate(
            favorites = listOf(a, b),
            findModel = { models[it] },
            isUsable = { it.usable },
            exclude = setOf(a),
        )
        assertEquals(b, result?.id)
    }

    @Test
    fun `skips models in cooldown`() {
        var t = 0L
        val router = ModelRouter(nowMs = { t })
        val a = Uuid.random()
        val b = Uuid.random()
        router.putCooldown(a)
        val models = mapOf(a to FakeModel(a), b to FakeModel(b))
        val result = router.nextRouteCandidate(
            favorites = listOf(a, b),
            findModel = { models[it] },
            isUsable = { it.usable },
            exclude = emptySet(),
        )
        assertEquals(b, result?.id)
    }

    @Test
    fun `skips unusable models`() {
        var t = 0L
        val router = ModelRouter(nowMs = { t })
        val a = Uuid.random()
        val b = Uuid.random()
        val models = mapOf(a to FakeModel(a, usable = false), b to FakeModel(b))
        val result = router.nextRouteCandidate(
            favorites = listOf(a, b),
            findModel = { models[it] },
            isUsable = { it.usable },
            exclude = emptySet(),
        )
        assertEquals(b, result?.id)
    }

    @Test
    fun `skips models not found by findModel`() {
        var t = 0L
        val router = ModelRouter(nowMs = { t })
        val a = Uuid.random()
        val b = Uuid.random()
        val models = mapOf(b to FakeModel(b)) // a not in map
        val result = router.nextRouteCandidate(
            favorites = listOf(a, b),
            findModel = { models[it] },
            isUsable = { it.usable },
            exclude = emptySet(),
        )
        assertEquals(b, result?.id)
    }

    @Test
    fun `returns null when all favorites are excluded`() {
        var t = 0L
        val router = ModelRouter(nowMs = { t })
        val a = Uuid.random()
        val b = Uuid.random()
        val models = mapOf(a to FakeModel(a), b to FakeModel(b))
        val result = router.nextRouteCandidate(
            favorites = listOf(a, b),
            findModel = { models[it] },
            isUsable = { it.usable },
            exclude = setOf(a, b),
        )
        assertNull(result)
    }

    @Test
    fun `returns null when favorites list is empty`() {
        var t = 0L
        val router = ModelRouter(nowMs = { t })
        val result = router.nextRouteCandidate(
            favorites = emptyList(),
            findModel = { null },
            isUsable = { true },
            exclude = emptySet(),
        )
        assertNull(result)
    }

    @Test
    fun `returns null when all favorites are in cooldown`() {
        var t = 0L
        val router = ModelRouter(nowMs = { t })
        val a = Uuid.random()
        val b = Uuid.random()
        router.putCooldown(a)
        router.putCooldown(b)
        val models = mapOf(a to FakeModel(a), b to FakeModel(b))
        val result = router.nextRouteCandidate(
            favorites = listOf(a, b),
            findModel = { models[it] },
            isUsable = { it.usable },
            exclude = emptySet(),
        )
        assertNull(result)
    }

    @Test
    fun `cooldown expiry frees model for routing`() {
        var t = 0L
        val router = ModelRouter(nowMs = { t })
        val a = Uuid.random()
        val b = Uuid.random()
        router.putCooldown(a, durationMs = 5_000)
        val models = mapOf(a to FakeModel(a), b to FakeModel(b))

        // During cooldown, a is skipped
        t = 3_000
        assertEquals(b, router.nextRouteCandidate(
            favorites = listOf(a, b),
            findModel = { models[it] },
            isUsable = { it.usable },
            exclude = emptySet(),
        )?.id)

        // After cooldown, a is available again
        t = 5_001
        assertEquals(a, router.nextRouteCandidate(
            favorites = listOf(a, b),
            findModel = { models[it] },
            isUsable = { it.usable },
            exclude = emptySet(),
        )?.id)
    }

    @Test
    fun `exclude takes priority over cooldown check`() {
        var t = 0L
        val router = ModelRouter(nowMs = { t })
        val a = Uuid.random()
        val b = Uuid.random()
        // a is in cooldown AND excluded; b is excluded
        router.putCooldown(a)
        val models = mapOf(a to FakeModel(a), b to FakeModel(b))
        val result = router.nextRouteCandidate(
            favorites = listOf(a, b),
            findModel = { models[it] },
            isUsable = { it.usable },
            exclude = setOf(b),
        )
        // a is in cooldown so skipped; b is excluded so skipped → null
        assertNull(result)
    }
}
