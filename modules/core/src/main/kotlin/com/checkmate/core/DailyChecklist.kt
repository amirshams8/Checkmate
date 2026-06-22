package com.checkmate.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.util.Calendar
import java.util.UUID

@Serializable
data class ChecklistItem(
    val id:        String  = UUID.randomUUID().toString(),
    val label:     String,
    val isDone:    Boolean = false
)

/**
 * DailyChecklist — user-editable checklist persisted in SharedPrefs.
 * Two separate keys:
 *   - "checklist_template"  : the master list of items (survives across days)
 *   - "checklist_<dayKey>"  : today's done/not-done state (resets daily)
 */
object DailyChecklist {

    private val json = Json { ignoreUnknownKeys = true }

    fun getTemplate(): List<ChecklistItem> {
        val saved = CheckmatePrefs.getString("checklist_template", null) ?: return defaultTemplate()
        return try { json.decodeFromString(saved) } catch (_: Exception) { defaultTemplate() }
    }

    fun saveTemplate(items: List<ChecklistItem>) {
        CheckmatePrefs.putString("checklist_template", json.encodeToString(items))
    }

    private fun defaultTemplate(): List<ChecklistItem> = listOf(
        ChecklistItem(label = "Lecture"),
        ChecklistItem(label = "Notes Rev"),
        ChecklistItem(label = "DPP + HW"),
        ChecklistItem(label = "NCERT read"),
        ChecklistItem(label = "Short Notes")
    )

    fun getTodayItems(): List<ChecklistItem> {
        val template  = getTemplate()
        val key       = todayKey()
        val savedJson = CheckmatePrefs.getString("checklist_$key", null)

        if (savedJson == null) return template.map { it.copy(isDone = false) }

        val saved = try {
            json.decodeFromString<List<ChecklistItem>>(savedJson)
        } catch (_: Exception) {
            return template.map { it.copy(isDone = false) }
        }

        val savedMap = saved.associateBy { it.id }
        return template.map { templateItem ->
            savedMap[templateItem.id]?.copy(label = templateItem.label)
                ?: templateItem.copy(isDone = false)
        }
    }

    fun toggleItem(itemId: String) {
        val items  = getTodayItems().toMutableList()
        val idx    = items.indexOfFirst { it.id == itemId }
        if (idx < 0) return
        items[idx] = items[idx].copy(isDone = !items[idx].isDone)
        saveTodayItems(items)
    }

    fun saveTodayItems(items: List<ChecklistItem>) {
        CheckmatePrefs.putString("checklist_${todayKey()}", json.encodeToString(items))
    }

    fun getTodaySummaryText(): String {
        val items = getTodayItems()
        if (items.isEmpty()) return ""
        val done  = items.count { it.isDone }
        val lines = items.joinToString("\n") { item ->
            val icon = if (item.isDone) "✓" else "✗"
            "[$icon] ${item.label}"
        }
        return "Checklist: $done/${items.size}\n$lines"
    }

    private fun todayKey(): String {
        val c = Calendar.getInstance()
        return "${c.get(Calendar.YEAR)}_${c.get(Calendar.DAY_OF_YEAR)}"
    }
}
