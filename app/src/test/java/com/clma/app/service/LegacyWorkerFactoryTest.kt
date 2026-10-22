package com.clma.app.service

import com.clma.app.workflow.trigger.WorkflowTimeCronWorker
import org.junit.Assert.assertEquals
import org.junit.Test

class LegacyWorkerFactoryTest {

    @Test
    fun `every pre-rename worker name maps to the moved worker class`() {
        assertEquals(
            mapOf(
                "me.rerere.rikkahub.service.CronJobWorker" to "com.clma.app.service.CronJobWorker",
                "me.rerere.rikkahub.service.TelegramBotHealthWorker" to "com.clma.app.service.TelegramBotHealthWorker",
                "me.rerere.rikkahub.workflow.trigger.WorkflowTimeCronWorker" to "com.clma.app.workflow.trigger.WorkflowTimeCronWorker",
            ),
            LegacyWorkerFactory.LEGACY_WORKER_CLASS_NAMES,
        )
    }

    @Test
    fun `mapped targets are the real worker classes`() {
        val targets = LegacyWorkerFactory.LEGACY_WORKER_CLASS_NAMES.values.toSet()
        assertEquals(
            setOf(
                CronJobWorker::class.java.name,
                TelegramBotHealthWorker::class.java.name,
                WorkflowTimeCronWorker::class.java.name,
            ),
            targets,
        )
    }
}
