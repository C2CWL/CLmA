// Adapted from OpenMausBot (https://github.com/milind-soni/OpenMausBot), server/usage-caps.ts, server/usage-caps-config.ts, server/host/usage-cap-gate.ts,
// Copyright 2026 Milind Soni and OpenMausBot contributors, Apache License 2.0
// (third_party/openmausbot/LICENSE). Modified: ported to Kotlin and changed for CLmA.
package com.clma.app.data.botmode

import java.time.Instant
import java.time.ZoneOffset
import kotlinx.serialization.Serializable

@Serializable
data class BotUsageCaps(
    val dailyTokens: Long = 0,
    val monthlyTokens: Long = 0,
    val dailyUsd: Double = 0.0,
    val monthlyUsd: Double = 0.0,
    val stopsInteractive: Boolean = false,
)

data class BotUsageTotal(val tokens: Long, val usd: Double)

internal suspend fun BotUsageCaps.reachedUntil(
    unattended: Boolean,
    now: Instant = Instant.now(),
    usageSince: suspend (Long) -> BotUsageTotal,
): Instant? {
    if (!unattended && !stopsInteractive) return null
    val day = now.atOffset(ZoneOffset.UTC).toLocalDate()
    val dayStart = day.atStartOfDay(ZoneOffset.UTC).toInstant()
    val monthStart = day.withDayOfMonth(1).atStartOfDay(ZoneOffset.UTC).toInstant()

    suspend fun reached(tokens: Long, usd: Double, start: Instant): Boolean {
        if (tokens <= 0 && usd <= 0) return false
        val used = usageSince(start.toEpochMilli())
        return (tokens > 0 && used.tokens >= tokens) || (usd > 0 && used.usd >= usd)
    }

    // Both windows must clear if both caps have been reached.
    if (reached(monthlyTokens, monthlyUsd, monthStart)) {
        return day.withDayOfMonth(1).plusMonths(1).atStartOfDay(ZoneOffset.UTC).toInstant()
    }
    if (reached(dailyTokens, dailyUsd, dayStart)) {
        return day.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant()
    }
    return null
}
