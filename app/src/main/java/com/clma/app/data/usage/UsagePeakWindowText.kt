package me.rerere.rikkahub.data.usage

import java.util.Locale

/** Result of reading a peak-window line. [error] is null when the text was understood. */
data class PeakWindowText(
    val windows: List<PriceWindowSpec>,
    val error: String?,
)

/**
 * The one-line text form of a peak-window list, so a settings field can hold it and an agent
 * can be handed the same string.
 *
 *   spec    := segment ("," segment)* [ " @" offset ]
 *   segment := [ days SP ] HH:MM "-" HH:MM
 *   days    := "*" | part ("," part)*        part := n | n "-" m     (ISO 1=Mon..7=Sun)
 *   offset  := ("+" | "-") HH [ ":" MM ]
 *
 *   "1-5 09:00-12:00, 14:00-18:00 @+08:00"
 *   "09:00-12:00"                            every day, UTC
 *
 * Messages are English and machine-shaped, same as UsagePriceTable rejections: this layer is
 * pure, so it has no locale, and the UI shows the sentence it gets.
 */
object UsagePeakWindowText {

    const val EXAMPLE = "1-5 09:00-12:00, 14:00-18:00 @+08:00"

    fun parse(text: String): PeakWindowText {
        val raw = text.trim()
        if (raw.isEmpty()) return PeakWindowText(emptyList(), null)

        val at = raw.lastIndexOf("@")
        val segmentsPart: String
        var offset = 0
        if (at >= 0) {
            segmentsPart = raw.substring(0, at).trim()
            val token = raw.substring(at + 1).trim()
            val value = parseOffset(token)
                ?: return PeakWindowText(emptyList(), "offset must look like +08:00 (got: $token)")
            offset = value
        } else {
            segmentsPart = raw
        }

        val windows = mutableListOf<PriceWindowSpec>()
        for (piece in segmentsPart.split(",")) {
            val segment = piece.trim()
            if (segment.isEmpty()) continue

            var days = emptyList<Int>()
            var times: String? = null
            for (token in segment.split(Regex("\\s+"))) {
                if (token.contains(":")) {
                    if (times != null) return PeakWindowText(emptyList(), "one time range per segment: $segment")
                    times = token
                } else {
                    days = parseDays(token)
                        ?: return PeakWindowText(emptyList(), "days must be 1..7 like 1-5 (got: $token)")
                }
            }
            val range = times ?: return PeakWindowText(emptyList(), "segment needs a time range: $segment")
            val dash = range.indexOf("-")
            if (dash <= 0 || dash == range.length - 1) {
                return PeakWindowText(emptyList(), "time range must look like 09:00-12:00 (got: $range)")
            }
            val start = parseMinute(range.substring(0, dash))
                ?: return PeakWindowText(emptyList(), "start time must be HH:MM within a day (got: ${range.substring(0, dash)})")
            val end = parseMinute(range.substring(dash + 1))
                ?: return PeakWindowText(emptyList(), "end time must be HH:MM within a day (got: ${range.substring(dash + 1)})")
            if (start == end) {
                return PeakWindowText(emptyList(), "a window cannot start and end at the same minute: $range")
            }
            windows += PriceWindowSpec(
                startMinute = start,
                endMinute = end,
                zoneOffsetMinutes = offset,
                daysOfWeek = days,
            )
        }
        if (windows.isEmpty()) return PeakWindowText(emptyList(), "no time range found")
        return PeakWindowText(windows, null)
    }

    /**
     * Prints the windows back. A single trailing offset is written because a provider prices
     * against one clock; windows stored with different offsets (only the agent tool can make
     * those) keep their own numbers and the offset of the first window is the one shown.
     */
    fun format(windows: List<PriceWindowSpec>): String {
        if (windows.isEmpty()) return ""
        val segments = windows.joinToString(", ") { w ->
            val days = formatDays(w.daysOfWeek)
            (if (days.isEmpty()) "" else "$days ") + formatMinute(w.startMinute) + "-" + formatMinute(w.endMinute)
        }
        val offset = windows.first().zoneOffsetMinutes
        return if (offset == 0) segments else "$segments @${formatOffset(offset)}"
    }

    fun formatMinute(minute: Int): String =
        String.format(Locale.ROOT, "%02d:%02d", minute / 60, minute % 60)

    fun formatOffset(minutes: Int): String {
        val sign = if (minutes < 0) "-" else "+"
        val absolute = kotlin.math.abs(minutes)
        return sign + String.format(Locale.ROOT, "%02d:%02d", absolute / 60, absolute % 60)
    }

    /** "+08:00", "-5", "+0" -> minutes. Null when it is not an offset inside a real day. */
    fun parseOffset(token: String): Int? {
        val match = Regex("^([+-])(\\d{1,2})(?::?(\\d{2}))?$").find(token.trim()) ?: return null
        val hours = match.groupValues[2].toIntOrNull() ?: return null
        val minutes = match.groupValues[3].ifEmpty { "0" }.toIntOrNull() ?: return null
        if (minutes > 59) return null
        val total = hours * 60 + minutes
        if (total > 840) return null
        return if (match.groupValues[1] == "-") -total else total
    }

    private fun parseMinute(token: String): Int? {
        val parts = token.trim().split(":")
        if (parts.size != 2) return null
        val hours = parts[0].toIntOrNull() ?: return null
        val minutes = parts[1].toIntOrNull() ?: return null
        if (hours !in 0..23 || minutes !in 0..59) return null
        return hours * 60 + minutes
    }

    /** "*" means every day and is stored as an empty set. "5-1" wraps through the week. */
    private fun parseDays(token: String): List<Int>? {
        if (token == "*") return emptyList()
        val days = sortedSetOf<Int>()
        for (part in token.split(",")) {
            if (part.isEmpty()) return null
            val bounds = part.split("-")
            when (bounds.size) {
                1 -> {
                    val day = bounds[0].toIntOrNull() ?: return null
                    if (day !in 1..7) return null
                    days += day
                }
                2 -> {
                    val from = bounds[0].toIntOrNull() ?: return null
                    val to = bounds[1].toIntOrNull() ?: return null
                    if (from !in 1..7 || to !in 1..7) return null
                    var day = from
                    while (true) {
                        days += day
                        if (day == to) break
                        day = day % 7 + 1
                    }
                }
                else -> return null
            }
        }
        return days.toList()
    }

    private fun formatDays(days: List<Int>): String {
        val sorted = days.distinct().sorted()
        if (sorted.isEmpty() || sorted == (1..7).toList()) return ""
        val out = mutableListOf<String>()
        var index = 0
        while (index < sorted.size) {
            var end = index
            while (end + 1 < sorted.size && sorted[end + 1] == sorted[end] + 1) end += 1
            if (end - index >= 2) {
                out += "${sorted[index]}-${sorted[end]}"
            } else {
                for (position in index..end) out += sorted[position].toString()
            }
            index = end + 1
        }
        return out.joinToString(",")
    }
}
