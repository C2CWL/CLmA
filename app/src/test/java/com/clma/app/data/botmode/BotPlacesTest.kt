package com.clma.app.data.botmode

import kotlin.uuid.Uuid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class BotPlacesTest {
    @Test
    fun movingMembersKeepsOtherChangesMadeWhileEditing() {
        val bot = Uuid.random()
        val channel = Uuid.random()
        val otherChannel = Uuid.random()
        val work = BotPlace(name = "Work", botIds = setOf(bot), channelIds = setOf(channel))
        val project = BotPlace(name = "Project")
        val currentProject = project.copy(color = BotPlaceColor.GREEN, channelIds = setOf(otherChannel))
        val draft = project.copy(botIds = setOf(bot), channelIds = setOf(channel))

        val updated = listOf(work, currentProject).saveBotPlace(
            project, draft, setOf(bot), setOf(channel, otherChannel),
        )

        assertEquals(work.copy(botIds = emptySet(), channelIds = emptySet()), updated[0])
        assertEquals(currentProject.copy(botIds = setOf(bot), channelIds = setOf(channel, otherChannel)), updated[1])
    }

    @Test
    fun rejectsDuplicateNamesAndEditsToDeletedPlaces() {
        val work = BotPlace(name = "Work")
        val places = listOf(work)
        val removed = BotPlace(name = "Personal")

        assertSame(places, places.saveBotPlace(null, BotPlace(name = " work "), emptySet(), emptySet()))
        assertSame(places, places.saveBotPlace(removed, removed.copy(name = "Project"), emptySet(), emptySet()))
    }
}
