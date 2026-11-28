package com.clma.app.data.botmode.routine

import org.junit.Assert.assertEquals
import org.junit.Test

class RoutineCodecTest {
    @Test fun roundTripPreservesOptionsAndDeferredWork() {
        val snapshot = RoutineSnapshot(
            routines = mapOf("job" to RoutineState(
                options = RoutineOptions(
                    interval = RoutineInterval(30, 1_800_000, setOf(1, 3, 5), 540, 1080),
                    overlap = RoutineOverlap.QUEUE,
                    condition = "Today is Wednesday",
                    changedUrl = "https://example.com/",
                    sameConversation = true,
                ),
                conversationId = "topic",
                pageHash = "hash",
                checkPacing = RoutineCheckPacing(listOf(600_000), 2, 1_000_000),
                queuedSlot = 3_600_000,
                queuedManual = true,
                quota = QuotaRetry("run", 7_200_000, "next hash", "https://example.com/", manual = true),
            )),
            checkBacks = mapOf("check" to CheckBack("check", "topic", "bot", 120_000, "查看价格", "It changed")),
            backupModels = mapOf("bot" to "backup-model"),
        )
        assertEquals(snapshot, RoutineCodec.decode(RoutineCodec.encode(snapshot)))
    }

    @Test fun missingAndUnknownFieldsAreCompatible() {
        assertEquals(RoutineSnapshot(), RoutineCodec.decode("{}"))
        assertEquals(
            RoutineSnapshot(routines = mapOf("job" to RoutineState())),
            RoutineCodec.decode("""{"routines":{"job":{"extra":false}},"future":[] }"""),
        )
    }
}
