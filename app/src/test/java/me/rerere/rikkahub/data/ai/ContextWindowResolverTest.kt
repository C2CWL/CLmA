package me.rerere.rikkahub.data.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The model window is one number with one source list. Both the `<context_reminder>` threshold and
 * automatic compaction divide by it, so a source that only one of them consulted used to make the
 * reminder unreachable while compaction still ran — regression coverage for exactly that.
 *
 * Kept free of `Settings` on purpose: `bin/kcheck-context-window.sh` compiles and runs this file
 * against the real [ContextWindowResolver] and the real registry on a bare JVM, with no Android
 * SDK. The `Settings`-level wiring lives in `ContextWindowWiringTest`.
 */
class ContextWindowResolverTest {

    @Test
    fun `nothing known means no window`() {
        assertNull(ContextWindowResolver.resolve(null, null, null))
    }

    @Test
    fun `a non-positive value counts as unknown, not as a window`() {
        assertNull(ContextWindowResolver.resolve(0, null, null))
        assertNull(ContextWindowResolver.resolve(null, -1, null))
        assertNull(ContextWindowResolver.resolve(null, null, 0))
    }

    @Test
    fun `the declared ceiling outranks provider metadata and the registry`() {
        assertEquals(
            372_000,
            ContextWindowResolver.resolve(
                declaredCeiling = 372_000,
                providerWindow = 128_000,
                registryWindow = 1_000_000,
            ),
        )
    }

    @Test
    fun `provider metadata outranks the registry`() {
        assertEquals(
            128_000,
            ContextWindowResolver.resolve(
                declaredCeiling = null,
                providerWindow = 128_000,
                registryWindow = 1_000_000,
            ),
        )
    }

    @Test
    fun `the registry fills a window nobody published`() {
        assertEquals(
            1_000_000,
            ContextWindowResolver.resolve(
                declaredCeiling = null,
                providerWindow = null,
                registryWindow = 1_000_000,
            ),
        )
    }
}
