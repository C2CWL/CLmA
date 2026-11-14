package com.clma.app.ui.components.message

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerToolLoadingTest {
    @Test
    fun `unfinished historical and interrupted tools stay static`() {
        assertFalse(serverToolLoading(generationActive = false, isFinished = false))
        assertFalse(serverToolLoading(generationActive = false, isFinished = true))
    }

    @Test
    fun `only unfinished tools in a generating message animate`() {
        assertTrue(serverToolLoading(generationActive = true, isFinished = false))
        assertFalse(serverToolLoading(generationActive = true, isFinished = true))
    }
}
