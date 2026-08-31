package com.checkmate.planner.intervention

import com.checkmate.core.CheckmatePrefs

/**
 * P0a continuation (REPLAN_DAY) — Upgrade Blueprint Phase 2.4/2.5.
 *
 * Once-a-day guard for REPLAN_DAY, consulted by
 * [LearningInterventionOrchestrator.executeTopCandidateLocked] BEFORE a REPLAN_DAY
 * candidate is even routed/validated (see that function's own
 * `RejectionSource.AlreadyReplannedToday` check) — same "day-key written after firing"
 * pattern [com.checkmate.service.ProactiveMentor]'s own once-a-day checks and
 * [GapTaskLedger.hasGeneratedToday]/[GapTaskLedger.markGeneratedToday] already use.
 *
 * [TaskEscrow] alone cannot provide this guard: escrow only blocks a SECOND concurrent
 * negotiation for the SAME still-open transaction — the moment the first REPLAN_DAY
 * transaction resolves (COMPLETED), its escrow key is free again, and nothing stops a
 * later run THE SAME DAY from re-acquiring it and replanning a second time. Since
 * REPLAN_DAY REPLACES the entire day's task list (see [PlanReplanner.replanToday]'s own
 * doc), a second same-day replan would silently wipe out whatever progress the student
 * has made against the first one — exactly the failure mode this ledger exists to
 * prevent. A fresh calendar day naturally clears the guard: [dayKey] changes, so
 * [hasReplannedToday] compares against a new value nobody has marked yet.
 *
 * Single-user app (see every other CheckmatePrefs-backed singleton in this package) — no
 * per-student keying needed.
 */
object ReplanDayLedger {

    private const val KEY_LAST_REPLANNED_DAY = "replan_day_last_replanned_day"

    /** True once [markReplannedToday] has already been called for [dayKey] — same
     *  "YYYY_dayOfYear" format [GapTaskLedger.todayKey]/[com.checkmate.planner.PlanStore
     *  .currentDayKey] both use, so callers can pass either interchangeably. */
    fun hasReplannedToday(dayKey: String): Boolean =
        CheckmatePrefs.getString(KEY_LAST_REPLANNED_DAY, null) == dayKey

    /** Called by [LearningInterventionOrchestrator.executeTopCandidateLocked] immediately
     *  after a REPLAN_DAY candidate's [ActionExecutor.execute] call returns — see that
     *  function's own call site right after `GapTaskLedger.recordServed`. */
    fun markReplannedToday(dayKey: String) {
        CheckmatePrefs.putString(KEY_LAST_REPLANNED_DAY, dayKey)
    }
}
