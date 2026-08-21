package com.checkmate.learning.repository

import androidx.room.TypeConverter
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Room has no native column type for List<String> (LearningEvent.conceptIds,
 * Question.concepts) — same gap :modules:psyche works around for its own
 * Json-serialized fields. JSON array rather than a delimiter-joined string so a
 * concept id containing a comma can never corrupt the column.
 */
class Converters {
    private val json = Json { ignoreUnknownKeys = true }

    @TypeConverter
    fun fromStringList(value: List<String>): String = json.encodeToString(value)

    @TypeConverter
    fun toStringList(value: String): List<String> =
        if (value.isBlank()) emptyList() else json.decodeFromString(value)
}
