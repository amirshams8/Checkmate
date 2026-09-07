package com.checkmate.learning.tutor

import android.util.Log
import com.checkmate.core.CheckmatePrefs
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Upgrade Blueprint Phase 3, P3.2 — durable handoff for the [TutorDiagnosticFinding]
 * [com.checkmate.service.TutorCycleManager]'s DIAGNOSE step enriches, so its EXPLAIN step
 * (a separate [com.checkmate.service.ReminderService] 15-min-loop tick — possibly minutes,
 * or a process restart, apart) can read it back without re-running an LLM diagnosis.
 *
 * Same CheckmatePrefs-blob, single-slot pattern [TutorSessionLedger] itself already
 * establishes, and tied to the exact same "one tutor session in flight at a time"
 * invariant that file's own class doc justifies — so a single unkeyed slot (not a
 * per-conceptId map) is correct here too. [current]'s caller is responsible for checking
 * `finding.conceptId` still matches the active session before trusting it (see
 * [com.checkmate.service.TutorCycleManager]'s own EXPLAIN step) — this object doesn't
 * enforce that itself, same "ledger is a thin read/write wrapper, not a decision-maker"
 * split [TutorSessionLedger] models between itself and [TutorStateMachine].
 *
 * NOT unit-tested at the JVM level, same convention [TutorSessionLedger]'s own class doc
 * documents (`CheckmatePrefs.ready()` needs `CheckmatePrefs.init(context)`, which plain JVM
 * tests don't run) — the pure logic that PRODUCES what gets stored here
 * ([TutorDiagnosticValidator]) is exhaustively tested instead (see
 * `TutorDiagnosticValidatorTest`).
 */
object TutorDiagnosticFindingLedger {

    private const val TAG = "TutorDiagnosticFindingLedger"
    private val json = Json { ignoreUnknownKeys = true }
    private const val KEY_ACTIVE_FINDING = "tutor_active_diagnostic_finding"

    /** Current stored finding, if any — null once [clear]ed, or if none was ever stored
     *  (e.g. [TutorDiagnosticContextBuilder.build] returned null for this session's
     *  concept, so DIAGNOSE had no evidence to enrich in the first place). */
    fun current(): TutorDiagnosticFinding? =
        CheckmatePrefs.getString(KEY_ACTIVE_FINDING, null)?.let {
            runCatching { json.decodeFromString<TutorDiagnosticFinding>(it) }
                .onFailure { e -> Log.w(TAG, "failed to decode active diagnostic finding, discarding", e) }
                .getOrNull()
        }

    fun store(finding: TutorDiagnosticFinding) {
        CheckmatePrefs.putString(KEY_ACTIVE_FINDING, json.encodeToString(finding))
    }

    /** Call once the finding has been consumed (or the session it belonged to ends) —
     *  explicit clear, not automatic on read, same discipline [TutorSessionLedger.clear]'s
     *  own doc documents: a caller may still want to re-read it (e.g. EXPLAIN running
     *  again after a VERIFY failure loops back, per [TutorStateMachine]'s own routing)
     *  before it's gone. */
    fun clear() {
        CheckmatePrefs.remove(KEY_ACTIVE_FINDING)
    }
}
