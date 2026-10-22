package me.rerere.rikkahub.data.search

import me.rerere.search.SearchServiceOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Guards `SearchServiceOptions.create`, the explicit replacement for the
 * reflective `primaryConstructor.callBy(emptyMap())` that used to build new
 * search providers (and crashed release builds once R8 stripped the
 * default-argument constructor).
 */
class SearchServiceOptionsFactoryTest {
    @Test
    fun `factory covers every advertised provider type`() {
        SearchServiceOptions.TYPES.keys.forEach { type ->
            assertEquals(type, SearchServiceOptions.create(type)::class)
        }
    }

    @Test
    fun `factory returns a distinct instance on every call`() {
        SearchServiceOptions.TYPES.keys.forEach { type ->
            val first = SearchServiceOptions.create(type)
            val second = SearchServiceOptions.create(type)
            assertEquals(type, first::class)
            assertNotEquals(first.id, second.id)
        }
    }
}
