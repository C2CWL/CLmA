// Adapted from OpenMausBot (https://github.com/milind-soni/OpenMausBot), src/lib/routine-calendar.ts, server/routines.ts,
// Copyright 2026 Milind Soni and OpenMausBot contributors, Apache License 2.0
// (third_party/openmausbot/LICENSE). Modified: ported to Kotlin and changed for CLmA.
package com.clma.app.data.botmode

import com.clma.app.data.db.entity.ScheduledJobEntity
import com.clma.app.service.CronJobScheduler
import java.time.ZoneId

internal fun ScheduledJobEntity.calendarNextRun(): Long? =
    nextRunAtMs?.takeIf {
        enabled && (scheduleType == "cron" || scheduleType == "once") &&
            (scheduleType != "once" || lastRunAtMs == null) &&
            (maxRuns == null || runsSoFar < maxRuns) &&
            (endAtUnixMs == null || it <= endAtUnixMs)
    }

/** Start at the persisted slot so the agenda follows the scheduler's actual first delivery. */
internal fun calendarSlots(
    job: ScheduledJobEntity,
    fromMs: Long,
    untilMs: Long,
    zone: ZoneId,
): List<Long> = buildList {
    val scheduled = job.calendarNextRun() ?: return@buildList
    var slot = if (scheduled < fromMs && job.scheduleType == "cron") {
        CronJobScheduler.nextRunMs(job, fromMs, zone) ?: return@buildList
    } else scheduled
    val limit = job.maxRuns?.minus(job.runsSoFar) ?: Int.MAX_VALUE
    while (slot in fromMs until untilMs && size < limit) {
        add(slot)
        if (job.scheduleType != "cron") break
        val next = CronJobScheduler.nextRunMs(job, slot + 1, zone) ?: break
        if (next <= slot) break
        slot = next
    }
}
