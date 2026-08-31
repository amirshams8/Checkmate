package com.checkmate.planner.intervention

import com.checkmate.core.CheckmatePrefs
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * P0a continuation (REDUCE_DIFFICULTY/INCREASE_DIFFICULTY) — Upgrade Blueprint
 * Phase 2.4/2.5.
 *
 * CheckmatePrefs-backed store of one [DifficultyDirection] per concept — same
 * "SharedPreferences-backed singleton" pattern [PlanStore]/[GapTaskLedger] already
 * establish for this codebase's local-storage-only modules. Unlike [GapTaskLedger] (which
 * tracks exactly ONE active concept at a time, by design — see that class's own doc),
 * this deliberately tracks a whole map: REDUCE_DIFFICULTY/INCREASE_DIFFICULTY can fire for
 * any number of DIFFERENT concepts independently of which single concept
 * [GapTaskLedger] currently considers "active" for the gap-repair streak — a difficulty
 * preference is a standing, per-concept fact, not a single-slot pointer.
 *
 * Single-user app (see every other CheckmatePrefs-backed singleton in this package) — no
 * per-student keying needed.
 */
object ConceptDifficultyLedger {

    private const val KEY = "concept_difficulty_ledger"

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class Entry(val direction: String, val updatedAt: Long)

    /** The recorded preference for [conceptId], or null if it has never been adjusted, or
     *  if the stored value doesn't match a known [DifficultyDirection] (defensive against
     *  a future enum rename leaving a stale string behind — same "don't crash on
     *  unrecognized persisted data" posture [GapTaskLedger.WarningLogEntry] decoding
     *  already takes via its own try/catch). */
    fun current(conceptId: String): DifficultyDirection? {
        val raw = loadAll()[conceptId]?.direction ?: return null
        return DifficultyDirection.entries.find { it.name == raw }
    }

    /** Overwrites [conceptId]'s entry — see [DifficultyMutator.adjust]'s own doc on why
     *  there is exactly one active direction per concept, not a history. */
    fun set(conceptId: String, direction: DifficultyDirection, now: Long) {
        val updated: Map<String, Entry> = loadAll() + (conceptId to Entry(direction.name, now))
        CheckmatePrefs.putString(KEY, json.encodeToString(updated))
    }

    private fun loadAll(): Map<String, Entry> {
        val raw = CheckmatePrefs.getString(KEY, null) ?: return emptyMap()
        return try {
            json.decodeFromString(raw)
        } catch (_: Exception) {
            emptyMap()
        }
    }
}
