package com.checkmate.learning.repository

import androidx.room.TypeConverter
import com.checkmate.learning.model.ErrorType
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Room has no native column type for List<String> (LearningEvent.conceptIds,
 * Question.concepts, ErrorPattern.interventions), for Map<String, String>
 * (Question.options), or for enums (ErrorRecord.errorType, ErrorPattern.errorType)
 * — same gap :modules:psyche works around for its own Json-serialized fields.
 * JSON rather than a delimiter-joined string so option text containing a comma
 * or pipe can never corrupt the column.
 */
class Converters {
    private val json = Json { ignoreUnknownKeys = true }

    @TypeConverter
    fun fromStringList(value: List<String>): String = json.encodeToString(value)

    @TypeConverter
    fun toStringList(value: String): List<String> =
        if (value.isBlank()) emptyList() else json.decodeFromString(value)

    // Added for Question.options (Upgrade Blueprint Phase 1.6 evidence pipeline) —
    // nullable, unlike the List<String> pair above, since "no options" (numeric/
    // subjective questions) is a real, common case rather than "not yet imported."
    @TypeConverter
    fun fromOptionsMap(value: Map<String, String>?): String? =
        value?.let { json.encodeToString(it) }

    @TypeConverter
    fun toOptionsMap(value: String?): Map<String, String>? =
        value?.takeIf { it.isNotBlank() }?.let { json.decodeFromString(it) }

    // Added for ErrorRecord.errorType / ErrorPattern.errorType (Upgrade Blueprint Phase 1.6).
    @TypeConverter
    fun fromErrorType(value: ErrorType): String = value.name

    @TypeConverter
    fun toErrorType(value: String): ErrorType = ErrorType.valueOf(value)
}
