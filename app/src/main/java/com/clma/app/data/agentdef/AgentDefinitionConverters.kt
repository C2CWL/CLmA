package me.rerere.rikkahub.data.agentdef

import androidx.room.TypeConverter
import me.rerere.rikkahub.data.ai.tools.LenientLocalToolListSerializer
import me.rerere.rikkahub.data.ai.tools.LocalToolOption
import me.rerere.rikkahub.utils.JsonInstant

/**
 * P2-06 — the column encodings for [AgentDefinition]'s structured fields.
 *
 * Everything is stored as TEXT rather than as a Room-parsed shape for one reason: the values
 * are polymorphic / set-valued and the SQLite columns have to stay stable across schema
 * versions. [localTools] reuses the *same* [LenientLocalToolListSerializer] as
 * `Assistant.localTools`, so the two encodings cannot drift and an unknown tool subclass
 * written by a newer build is dropped element-wise instead of failing the whole row (which
 * would make the definition unreadable — worse than losing one toggle).
 *
 * A null column means "inherit the parent", so the converters must round-trip null as null:
 * `encodeToString(null)` would write the four characters `null`, which then decodes to a
 * non-null value on a lenient parser.
 */
class AgentDefinitionConverters {

    @TypeConverter
    fun localToolsToJson(value: List<LocalToolOption>?): String? =
        value?.let { JsonInstant.encodeToString(LenientLocalToolListSerializer, it) }

    @TypeConverter
    fun jsonToLocalTools(value: String?): List<LocalToolOption>? =
        value?.let { JsonInstant.decodeFromString(LenientLocalToolListSerializer, it) }

    @TypeConverter
    fun stringSetToJson(value: Set<String>?): String? =
        value?.let { JsonInstant.encodeToString(it) }

    @TypeConverter
    fun jsonToStringSet(value: String?): Set<String>? =
        value?.let { JsonInstant.decodeFromString<Set<String>>(it) }
}
