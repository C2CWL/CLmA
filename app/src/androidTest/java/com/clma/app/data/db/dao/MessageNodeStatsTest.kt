package com.clma.app.data.db.dao

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.requery.android.database.sqlite.RequerySQLiteOpenHelperFactory
import kotlinx.coroutines.runBlocking
import com.clma.app.data.db.AppDatabase
import com.clma.app.data.db.entity.ConversationEntity
import com.clma.app.data.db.entity.MessageNodeEntity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MessageNodeStatsTest {
    private lateinit var database: AppDatabase
    private lateinit var dao: MessageNodeDAO
    private var nodeIndex = 0

    @Before
    fun setUp() = runBlocking {
        database = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            AppDatabase::class.java,
        ).openHelperFactory(RequerySQLiteOpenHelperFactory()).build()
        dao = database.messageNodeDao()
        database.conversationDao().insert(
            ConversationEntity(
                id = "stats-test",
                assistantId = "assistant",
                title = "Stats test",
                nodes = "[]",
                createAt = 0,
                updateAt = 0,
                chatSuggestions = "[]",
                isPinned = false,
            )
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    /**
     * A node's `messages` array holds the *branches of one message*, and `select_index` picks the
     * one the user sees - so the statistics must count exactly one message per node. Counting the
     * whole array made every regeneration add its discarded answer's tokens to the total.
     */
    @Test
    fun malformedRowsAreSkippedAndOnlyTheSelectedBranchIsCounted() = runBlocking {
        insertMessages("""[{"role":"user"""")

        insertMessages("""[{"role":"user","createdAt":"2026-08-12T09:00:00"}]""")

        // Three regenerated answers; only the third one is selected and only it may be counted.
        insertMessages(
            """[
                {"role":"assistant","createdAt":"2026-08-12T09:01:00",
                 "usage":{"promptTokens":500,"completionTokens":100,"cachedTokens":50}},
                {"role":"assistant","createdAt":"2026-08-12T09:02:00",
                 "usage":{"promptTokens":600,"completionTokens":120,"cachedTokens":60}},
                {"role":"assistant","createdAt":"2026-08-12T09:03:00",
                 "usage":{"promptTokens":700,"completionTokens":140,"cachedTokens":70}}
            ]""".trimIndent(),
            selectIndex = 2,
        )

        insertMessages("not json")

        insertMessages("[]")

        insertMessages("""[{"role":"user","createdAt":"2026-08-13T09:00:00"}]""")

        // A corrupted select_index falls back to the first branch instead of dropping the node.
        insertMessages(
            """[
                {"role":"assistant","createdAt":"2026-08-13T09:01:00",
                 "usage":{"promptTokens":10,"completionTokens":1,"cachedTokens":0}},
                {"role":"assistant","createdAt":"2026-08-13T09:02:00",
                 "usage":{"promptTokens":20,"completionTokens":2,"cachedTokens":1}}
            ]""".trimIndent(),
            selectIndex = 5,
        )

        assertEquals(MessageTokenStats(4, 710, 141, 70), dao.getTokenStats())
        assertEquals(
            mapOf("2026-08-12" to 1, "2026-08-13" to 1),
            dao.getMessageCountPerDay("2026-08-12").associate { it.day to it.count },
        )
        assertEquals(8, dao.getNodesOfConversation("stats-test").size)
    }

    @Test
    fun onlyMalformedOrEmptyRowsProduceEmptyStats() = runBlocking {
        insertMessages("")
        insertMessages("[")
        insertMessages("not json")
        insertMessages("[]")

        assertEquals(MessageTokenStats(), dao.getTokenStats())
        assertEquals(emptyList<MessageDayCount>(), dao.getMessageCountPerDay("2026-08-12"))
    }

    @Test
    fun emptyDatabaseProducesEmptyStats() = runBlocking {
        assertEquals(MessageTokenStats(), dao.getTokenStats())
        assertEquals(emptyList<MessageDayCount>(), dao.getMessageCountPerDay("2026-08-12"))
    }

    private suspend fun insertMessages(messages: String, selectIndex: Int = 0) {
        val index = nodeIndex++
        dao.insert(
            MessageNodeEntity(
                id = "node-$index",
                conversationId = "stats-test",
                nodeIndex = index,
                messages = messages,
                selectIndex = selectIndex,
            )
        )
    }
}
