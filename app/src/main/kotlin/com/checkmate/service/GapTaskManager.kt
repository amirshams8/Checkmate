package com.checkmate.service

import android.content.Context
import android.util.Log
import com.checkmate.core.CheckmatePrefs
import com.checkmate.core.ConsultationProfile
import com.checkmate.core.llm.LlmGateway
import com.checkmate.learning.analytics.ConceptWeightage
import com.checkmate.learning.analytics.PerformanceAnalyzer
import com.checkmate.learning.analytics.ScoreGainEstimator
import com.checkmate.learning.analytics.ScorePredictor
import com.checkmate.learning.engine.LearningDecisionEngine
import com.checkmate.learning.engine.MasteryEngine
import com.checkmate.learning.model.LearningIds
import com.checkmate.learning.repository.LearningDatabase
import com.checkmate.learning.student.StudentModelBuilder
import com.checkmate.learning.tutor.TutorSessionLedger
import com.checkmate.planner.PlanStore
import com.checkmate.planner.intervention.GapTaskLedger
import com.checkmate.planner.intervention.LearningInterventionOrchestrator
import com.checkmate.planner.model.StudyTask
import com.checkmate.planner.model.TaskState
import com.checkmate.psyche.BehaviorLedger
import com.checkmate.testmate.TestmateApi
import com.checkmate.testmate.TestmateQuestionPool
import com.checkmate.testmate.TestmateResultOutcome
import com.checkmate.testmate.TestmateTargetedTestOutcome
import com.checkmate.ui.mentor.MentorViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * "One gap-repair task a day, cycling through every gap the test surfaced, until each one
 * is actually done — and warn the student, with escalating persuasion, when one is being
 * ignored." Three entry points now, all called from [ReminderService]'s existing 15-min
 * loop (same wiring pattern as every [ProactiveMentor] check already there):
 *
 * - [generateIfNeeded] — the daily trigger [LearningInterventionOrchestrator]'s own class
 *   doc always said was still missing, now filled by a genuine day-level schedule instead
 *   of only firing on fresh test import ([com.checkmate.ui.testresults.TestResultsViewModel]
 *   still owns that import-time trigger; this is the *ongoing* one). Also now requests the
 *   P0b Testmate targeted test for whichever concept ends up active (see
 *   [createTargetedTestIfNeeded]).
 * - [escalationCheckIfNeeded] — reads [GapTaskLedger]'s streak for whichever concept is
 *   currently being served and, once it's gone unaddressed for more than a day, sends an
 *   escalating warning via the SAME delivery path [ProactiveMentor] already uses
 *   ([MentorViewModel.appendProactiveMessage] + [MentorNotifier.notify]).
 * - [evidencePollIfNeeded] — P0b: the actual return arrow. Every 15-min cycle (NOT
 *   once-a-day gated, unlike the two above — the student could submit the Testmate test at
 *   any time), checks whether the active concept's targeted-test result is in yet and, if
 *   so, hands it to [TargetedTestEvidenceImporter] so mastery moves off REAL evidence
 *   instead of only the task's DONE flag.
 *
 * Neither [generateIfNeeded] nor [escalationCheckIfNeeded] needs its own "is there anything
 * to analyze" gate beyond checking `studentModel.concepts.isEmpty()` — an empty StudentModel
 * (no test ever imported) just means [LearningDecisionEngine.decideFromReport] returns no
 * candidates and nothing happens, same as the import-time trigger's own behavior.
 */
object GapTaskManager {

    private const val TAG = "GapTaskManager"
    private const val TARGETED_TEST_QUESTION_COUNT = 15

    // Persisted (not just logged) so a create-test failure is visible in Settings → Test
    // Platform without needing adb logcat — Log.w/Log.e alone rotate out of the buffer
    // within hours, which is exactly what made a 2-day-old missing token invisible on
    // device. Cleared on the next successful attempt, or manually from Settings.
    const val PREF_TESTMATE_LAST_ERROR = "gap_task_testmate_last_error"
    const val PREF_TESTMATE_LAST_ERROR_AT = "gap_task_testmate_last_error_at"

    // BUGFIX (topic-"null" 422 loop, one-time data repair): once-ever guard for
    // repairLegacyNullTopicsIfNeeded — the repair itself is a cheap single UPDATE per
    // table, but there's no reason to run it on every generateIfNeeded call for the rest
    // of this install's life once it's confirmed done.
    private const val PREF_REPAIRED_LEGACY_NULL_TOPICS = "gap_task_repaired_legacy_null_topics_v1"

    // ── Daily generation ─────────────────────────────────────────────────────

    /**
     * Once per calendar day (guarded by [GapTaskLedger.hasGeneratedToday]): resolves whether
     * yesterday's gap-task actually got finished (see [resolveActiveConceptState]), then runs
     * the exact same StudentModel -> PerformanceReport -> ScoreGainEstimator -> ExpectedScore
     * -> DecisionReport pipeline [com.checkmate.ui.testresults.TestResultsViewModel] runs
     * after a fresh import, sourcing `examType`/`targetScore` from [ConsultationProfile]
     * instead of a just-parsed report since there may be no fresh import today at all.
     * [LearningInterventionOrchestrator] itself handles skipping already-covered concepts
     * and picking up where yesterday left off. Once that's done, also requests (or confirms)
     * the P0b Testmate targeted test for whichever concept is now active — see
     * [createTargetedTestIfNeeded]. Marks today done, success or failure either way (a failed
     * analysis run shouldn't retry every 15 minutes for the rest of the day).
     */
    /**
     * BUGFIX (unsynchronized ledger race): this whole body used to run un-serialized
     * against the two other call chains that also read-then-write [GapTaskLedger]'s
     * active-round/session state ([LearningInterventionOrchestrator.executeTopCandidate]
     * called directly from [com.checkmate.ui.testresults.TestResultsViewModel], and this
     * same function called from [com.checkmate.ui.home.HomeViewModel.confirmCompletion]).
     * See [GapTaskLedger.withLock]'s own doc for the concrete failure this closes.
     */
    suspend fun generateIfNeeded(context: Context) = GapTaskLedger.withLock {
        generateIfNeededLocked(context)
    }

    private suspend fun generateIfNeededLocked(context: Context) {
        // BUGFIX (round-advance blocked by once-a-day gate): resolveActiveConceptState
        // (and everything downstream of it — resolveDoneConcept's resetForNextRound, and
        // createTargetedTestIfNeeded requesting the NEXT round's session) must run every
        // 15-min cycle, same cadence as evidencePollIfNeeded, NOT only once per calendar
        // day. The active concept's task can legitimately go DONE-with-evidence-imported
        // more than once in a single day (a fast student, or a same-day repro/test cycle),
        // and each time it does, the loop needs to advance the round and request the next
        // Testmate retest THE SAME DAY — not sit inert until hasGeneratedToday resets
        // tomorrow. Confirmed live: with this gated, marking the round-2 task Done and
        // re-importing its evidence same-day did nothing at all until
        // gap_task_last_generated_day was manually deleted from prefs to force a fresh
        // generateIfNeeded run — the ONLY code path that ever calls
        // resolveActiveConceptState/createTargetedTestIfNeeded. Both are cheap, ledger-
        // driven, and safely no-op with nothing active, so running them unconditionally
        // here is safe — unlike the StudentModel/PerformanceAnalyzer pipeline below, which
        // stays gated since ranking a brand-new task is the genuinely expensive, once-a-day
        // part.
        resolveActiveConceptState(context)
        createTargetedTestIfNeeded()

        val todayKey = GapTaskLedger.todayKey()
        if (GapTaskLedger.hasGeneratedToday(todayKey)) return

        repairLegacyNullTopicsIfNeeded(context)

        try {
            val studentModel = withContext(Dispatchers.IO) { StudentModelBuilder.build(context) }
            if (studentModel.concepts.isEmpty()) return

            val profile = ConsultationProfile.load()
            val report = PerformanceAnalyzer.analyze(studentModel, profile.examTarget)
            val estimates = ScoreGainEstimator.rankFromReport(report, studentModel)
            val expectedScore = ScorePredictor.predictFromReport(report, studentModel, profile.targetScore)
            val decisionReport = LearningDecisionEngine.decideFromReport(
                report, studentModel, estimates, expectedScore
            )
            val orchestrationResult =
                LearningInterventionOrchestrator.from(context).executeTopCandidate(decisionReport)
            // Phase 3 execution bridge: only a genuinely NEW task (Created) means a fresh
            // teaching cycle is starting for this candidate — see TutorSessionLedger.start's
            // own "terminal session is free, non-terminal is AlreadyActive" semantics, and
            // startFromCandidate's own doc for which intents this actually applies to.
            (orchestrationResult.outcome as? LearningInterventionOrchestrator.OrchestrationOutcome.Created)
                ?.let { created -> TutorSessionLedger.startFromCandidate(created.candidate, System.currentTimeMillis()) }
            createTargetedTestIfNeeded()
        } catch (e: Exception) {
            Log.e(TAG, "generateIfNeeded failed: ${e.message}", e)
        } finally {
            GapTaskLedger.markGeneratedToday(todayKey)
        }
    }

    /**
     * If [GapTaskLedger]'s active concept's task has actually reached DONE, resolves whether
     * the concept is truly finished (see [resolveDoneConcept]) BEFORE re-ranking — otherwise
     * today's run would still see it as active (streak intact) for the few seconds between
     * "the student finished it" and "the ledger found out," and [LearningInterventionOrchestrator]
     * would have no way to know without this being called first. Safe to call with nothing
     * active (both lookups return null and this no-ops).
     */
    private suspend fun resolveActiveConceptState(context: Context) {
        // DIAGNOSTIC (round-2 redirect-to-old-result investigation): every early return
        // below used to be silent — logcat showed createTargetedTestIfNeeded either firing
        // or not, with no way to tell WHY resolveDoneConcept never ran when it should have.
        // Logging each branch so the next repro run pinpoints the actual break instead of
        // requiring another guess-and-rebuild cycle.
        val conceptId = GapTaskLedger.activeConceptId() ?: run {
            Log.d(TAG, "resolveActiveConceptState: no active concept — no-op")
            return
        }
        val taskId = GapTaskLedger.activeTaskId() ?: run {
            Log.d(TAG, "resolveActiveConceptState: concept=$conceptId has no active taskId — no-op")
            return
        }
        val dayKey = GapTaskLedger.activeTaskDayKey() ?: run {
            Log.d(TAG, "resolveActiveConceptState: concept=$conceptId taskId=$taskId has no dayKey — no-op")
            return
        }
        val task = findTask(taskId, dayKey) ?: run {
            Log.d(TAG, "resolveActiveConceptState: concept=$conceptId taskId=$taskId dayKey=$dayKey — " +
                "task not found in PlanStore.todayTasks or loadDay($dayKey) — no-op")
            return
        }
        Log.d(TAG, "resolveActiveConceptState: concept=$conceptId taskId=$taskId state=${task.state}")
        if (task.state == TaskState.DONE) {
            resolveDoneConcept(context, conceptId, dayKey)
        }
    }

    /**
     * BUGFIX (topic-"null" 422 loop, one-time data repair): a JSON `null` topic used to
     * survive TestmateApi.parseResult's bare optString() calls as the literal string
     * "null" instead of a real Kotlin null — see that class's parseResult doc and
     * GapTaskLedger.sanitizeTopicOrChapter's doc for the full chain. That fix stops NEW
     * corruption, but does nothing for Question/Concept rows already written to Room
     * before this build — MasteryEngine.recomputeAll re-derives Concept.topic from a
     * sample Question row on every single recompute, so an already-poisoned row keeps
     * re-poisoning GapTaskLedger's active topic (and therefore the Testmate request
     * payload) forever, purely patching the client never fixes it. Runs once ever per
     * install (see [PREF_REPAIRED_LEGACY_NULL_TOPICS]) since it's a permanent repair, not
     * an ongoing condition — every future write already goes through the parseResult fix
     * plus the GapTaskLedger/TestmateApi sanitize guards.
     */
    private suspend fun repairLegacyNullTopicsIfNeeded(context: Context) {
        if (CheckmatePrefs.getBoolean(PREF_REPAIRED_LEGACY_NULL_TOPICS, false)) return
        try {
            val db = LearningDatabase.getInstance(context)
            val questionsFixed = withContext(Dispatchers.IO) { db.questionDao().repairLiteralNullTopics() }
            val conceptsFixed = withContext(Dispatchers.IO) { db.conceptDao().repairLiteralNullTopics() }
            Log.d(TAG, "repairLegacyNullTopicsIfNeeded: fixed $questionsFixed question row(s), " +
                "$conceptsFixed concept row(s) with literal topic=\"null\"")
            CheckmatePrefs.putBoolean(PREF_REPAIRED_LEGACY_NULL_TOPICS, true)
        } catch (e: Exception) {
            // Non-fatal and safe to retry tomorrow — leaving the flag unset means this just
            // runs again on the next generateIfNeeded call instead of silently giving up.
            Log.e(TAG, "repairLegacyNullTopicsIfNeeded failed: ${e.message}", e)
        }
    }

    /**
     * BUGFIX (P0b re-intervention loop): a task reaching DONE only means the student finished
     * the *review task* — it says nothing about whether the concept itself is actually
     * mastered. The previous behavior called [GapTaskLedger.markCovered] unconditionally as
     * soon as the task hit DONE, which permanently retired the concept even when the Testmate
     * retest evidence for that exact concept came back still below
     * [MasteryEngine.MASTERY_THRESHOLD]. Once covered, [LearningInterventionOrchestrator]
     * never re-ranks that concept again no matter how weak it stays — this is what silently
     * broke the "retest -> still weak -> another targeted retest" cycle the whole P0b loop
     * exists for (confirmed live: report -> targeted test -> retest submitted -> evidence
     * imported -> mastery recomputed still below threshold -> no next retest was ever
     * requested, because the concept had already been marked covered the moment its task
     * card was checked off).
     *
     * Now: only mark covered once P0b evidence has actually been imported for this concept
     * AND mastery has genuinely cleared the bar.
     * - No evidence imported yet -> nothing to recheck against, same cover-on-DONE behavior
     *   as before this fix.
     * - Evidence imported and mastery >= threshold -> genuinely done, cover it.
     * - Evidence imported and mastery still < threshold, OR no mastery row found at all ->
     *   concept stays active; only its P0b session fields reset (see
     *   [GapTaskLedger.resetForNextRound]) so [createTargetedTestIfNeeded] requests a
     *   brand-new Testmate retest for the SAME concept on the next run instead of the loop
     *   silently stopping.
     *
     * BUGFIX (false-covered on conceptId drift): "no mastery row at all" used to be folded
     * into the same branch as "mastery cleared the bar" — markCovered either way. That's
     * only safe if a missing row genuinely means "nothing to check," but by the time this
     * runs [GapTaskLedger.isActiveEvidenceImported] is already true, meaning REAL P0b
     * evidence WAS imported for this exact round. [MasteryEngine.recomputeAll] re-derives
     * its grouping key ([com.checkmate.learning.graph.KnowledgeGraph.conceptId]) from each
     * [com.checkmate.learning.model.Question]'s own chapter/topic tag on every single run —
     * see that class's own doc — so a retest's Testmate-tagged questions whose chapter/topic
     * string isn't byte-for-byte identical to the original import's produces a brand-new
     * hash bucket instead of updating this one, leaving a lookup for the OLD active
     * conceptId returning null even though the real concept is nowhere near mastered.
     * Confirmed live: this exact path fired while real mastery was still 0.744 (well under
     * [MasteryEngine.MASTERY_THRESHOLD]), [GapTaskLedger.markCovered] wiped the still-
     * unresolved concept, and the identical real gap resurfaced minutes later under a fresh
     * conceptId — eventually exhausting Testmate's wrong/skipped question pool for that
     * chapter (repeated fresh-round requests instead of one continuing round). Missing
     * mastery data with evidence already imported is now treated the same as "still below
     * threshold" — one extra retest round is far cheaper than silently abandoning a real gap.
     *
     * BUGFIX (revert-to-PENDING silent no-op via day-key mismatch): [dayKey] is now required
     * from the caller (both already have it in scope) instead of this function reaching for
     * [PlanStore]'s today-only [PlanStore.markTask] — see [PlanStore.markTaskInDay]'s own doc
     * for the full mechanism. Without the correct day, the "evidence not imported, revert to
     * PENDING" branch below silently failed to revert anything whenever the active task
     * wasn't from strictly today, leaving [LearningInterventionOrchestrator.executeTopCandidate]'s
     * AlreadyActive guard blind to the true PENDING intent and creating a duplicate task for
     * the same still-unresolved concept — confirmed live via the round-stuck-at-1 /
     * redirect-to-old-result repro.
     */
    private suspend fun resolveDoneConcept(context: Context, conceptId: String, dayKey: String) {
        if (!GapTaskLedger.isActiveEvidenceImported()) {
            val round = GapTaskLedger.activeTestmateRound()
            val sessionId = GapTaskLedger.activeTestmateSessionId()

            if (sessionId == null) {
                // Genuinely nothing to check yet — no targeted test was ever requested for
                // this concept (round 1, no session on file). Covering is correct here.
                Log.d(TAG, "resolveDoneConcept: concept=$conceptId no session ever created " +
                    "(round=$round) — nothing to check, covering")
                GapTaskLedger.markCovered(conceptId)
                return
            }

            // BUGFIX (P0b premature-cover): a session exists (round=$round) but its evidence
            // hasn't been imported yet — the review task was marked DONE before
            // evidencePollIfNeeded's 15-min cycle caught up. Confirmed live (see repro log
            // 08-30 22:26:16): covering unconditionally here closed round 2 of concept
            // neet-biomolecules-ii-...-56718eb2 with no evidence check at all, right after
            // round 1 correctly found mastery still below threshold — exactly the silent-
            // abandon path this diagnostic was added to catch. Defer instead: put the task
            // back to PENDING so the next evidencePollIfNeeded/generateIfNeeded pass
            // re-resolves it once real evidence has actually landed, rather than guessing
            // blind. escalationCheckIfNeeded and resolveActiveConceptState both call this
            // function again on their own next pass, so this concept won't get stuck.
            val taskId = GapTaskLedger.activeTaskId()
            if (taskId == null) {
                Log.w(TAG, "resolveDoneConcept: concept=$conceptId evidence not imported and " +
                    "no activeTaskId to revert — leaving as-is for next pass")
                return
            }
            Log.d(TAG, "resolveDoneConcept: concept=$conceptId evidence NOT imported yet " +
                "(round=$round, sessionId=$sessionId) — deferring resolution instead of " +
                "covering (see BUGFIX note), reverting taskId=$taskId (dayKey=$dayKey) to PENDING")
            // BUGFIX: was PlanStore.markTask(taskId, PENDING) — today-list-only, silently
            // no-op'd for a task whose dayKey wasn't today's (see PlanStore.markTaskInDay doc
            // and this function's own class doc). Logging the outcome so a future miss (task
            // genuinely missing from both today's list AND plan_$dayKey) is visible instead
            // of silently trusted.
            val reverted = PlanStore.markTaskInDay(dayKey, taskId, TaskState.PENDING)
            if (!reverted) {
                Log.w(TAG, "resolveDoneConcept: concept=$conceptId taskId=$taskId dayKey=$dayKey " +
                    "— revert-to-PENDING found no matching task in plan_$dayKey either; " +
                    "AlreadyActive guard may still see this as DONE next run")
            }
            return
        }

        val mastery = try {
            withContext(Dispatchers.IO) {
                LearningDatabase.getInstance(context).masteryDao()
                    .getByConcept(LearningIds.LOCAL_STUDENT_ID, conceptId)
            }
        } catch (e: Exception) {
            Log.e(TAG, "resolveDoneConcept mastery lookup failed for concept=$conceptId: ${e.message}", e)
            return // retry this check on the next generateIfNeeded run rather than guessing
        }

        if (mastery == null) {
            // BUGFIX (false-covered on conceptId drift): see doc above — evidence was
            // already confirmed imported for this round, so a missing row here means the
            // recomputed mastery likely landed under a different conceptId, not that
            // there's nothing left to check. Treat as unresolved, not done.
            Log.w(TAG, "resolveDoneConcept: concept=$conceptId has evidence imported but NO " +
                "mastery row — likely conceptId drift (see BUGFIX doc). Treating as unresolved " +
                "instead of covering.")
            GapTaskLedger.resetForNextRound()
            // BUGFIX (Branch B never reverted DONE task): resetForNextRound() alone doesn't
            // move the task's own state — only the evidence-not-imported branch above did
            // that. Without this, a task marked DONE stays DONE while the ledger considers
            // the concept unresolved, so the AlreadyActive guard sees "already active" AND
            // "already done" at once and the retest loop stalls with the task stuck on the
            // plan as complete. Mirror Branch A's revert-to-PENDING here.
            val taskId = GapTaskLedger.activeTaskId()
            if (taskId != null) {
                PlanStore.markTaskInDay(dayKey, taskId, TaskState.PENDING)
            }
        } else if (mastery.mastery >= MasteryEngine.MASTERY_THRESHOLD) {
            GapTaskLedger.markCovered(conceptId)
        } else {
            Log.d(TAG, "resolveDoneConcept: concept=$conceptId still below mastery threshold " +
                "(${mastery.mastery}) after evidence — requesting another targeted retest round")
            GapTaskLedger.resetForNextRound()
            // BUGFIX (Branch B never reverted DONE task): same gap as above — mastery below
            // threshold means the concept isn't actually resolved, but without reverting the
            // task itself, it stays DONE on the plan even as the ledger starts a new round.
            val taskId = GapTaskLedger.activeTaskId()
            if (taskId != null) {
                PlanStore.markTaskInDay(dayKey, taskId, TaskState.PENDING)
            }
        }
    }

    /** Checks today's live list first (cheap, common case), falls back to [PlanStore.loadDay]
     *  for a task created on an earlier day that hasn't rolled into today's plan. */
    private fun findTask(taskId: String, dayKey: String): StudyTask? =
        PlanStore.todayTasks.value.find { it.id == taskId }
            ?: PlanStore.loadDay(dayKey).find { it.id == taskId }

    // ── P0b: Testmate targeted-test creation ────────────────────────────────

    /**
     * Requests a Testmate targeted-repair test for the currently active gap concept, but
     * only the FIRST time — [GapTaskLedger.activeTestmateSessionId] being non-null already
     * means either this call already succeeded for this concept, or the concept is being
     * re-served after [GapTaskLedger.recordServed] deliberately preserved that session
     * across days (see its own doc). No-ops with nothing active, or with no
     * [GapTaskLedger.activeChapter] to target (shouldn't happen for a real
     * [LearningDecisionEngine.CandidateIntervention], but this stays defensive rather than
     * crashing the whole generation pass over it). A failed request is NOT retried until the
     * next [generateIfNeeded] run (i.e. tomorrow) — [evidencePollIfNeeded] only polls a
     * session that was actually recorded, so a create failure just means one day's targeted
     * test is missing, not a retry storm.
     */
    private suspend fun createTargetedTestIfNeeded() {
        val conceptId = GapTaskLedger.activeConceptId() ?: return
        val existingSession = GapTaskLedger.activeTestmateSessionId()
        // DIAGNOSTIC: makes the guard's decision visible — was there already a session on
        // file (and if so which round), or did this genuinely start from a clean slate.
        Log.d(TAG, "createTargetedTestIfNeeded: concept=$conceptId existingSession=$existingSession " +
            "round=${GapTaskLedger.activeTestmateRound()} evidenceImported=${GapTaskLedger.isActiveEvidenceImported()}")
        if (existingSession != null) return
        val chapter = GapTaskLedger.activeChapter() ?: run {
            Log.w(TAG, "createTargetedTestIfNeeded: no chapter recorded for concept=$conceptId, skipping")
            return
        }
        val topic = GapTaskLedger.activeTopic()

        // Same verbose-chapter-name problem already diagnosed for subject resolution
        // (see ConceptWeightage's ALIASES doc): GapTaskLedger.activeChapter() is whatever
        // free-text chapter label Testmate's own report exported (e.g. "Biomolecules-II
        // (Proteins, types & functions), Lipids, Nucleic acids, Enzymes, Cofactors").
        // This resolver was originally routed into the Testmate API call on the assumption
        // that Testmate's question bank used Checkmate's own canonical chapter names —
        // that assumption turned out to be wrong (see BUGFIX below) — but it's kept computed
        // here purely for the diagnostic log line, since knowing what Checkmate's own
        // weightage system considers this chapter is still useful context when debugging.
        val exam = ConsultationProfile.load().examTarget
        val subject = GapTaskLedger.activeSubject()
        val resolution = ConceptWeightage.resolveWeightage(exam, subject, chapter, topic ?: chapter)
        val canonicalChapter = resolution.matchedKey ?: chapter
        if (canonicalChapter != chapter) {
            Log.d(TAG, "createTargetedTestIfNeeded: chapter '$chapter' resolves internally to " +
                "'$canonicalChapter' (method=${resolution.method}) — sending raw label to Testmate, see BUGFIX")
        }

        // BUGFIX: this used to send canonicalChapter (above) to Testmate instead of the raw
        // chapter string, on the assumption that Testmate's questions.chapter column used the
        // same canonical names Checkmate's own ConceptWeightage resolver produces. Live data
        // disproved that: Testmate stores the exact raw, unedited chapter label from each
        // source PDF at import time (e.g. "The Living World" — 18 questions in Testmate;
        // Checkmate's canonicalized "Diversity In Living World" — zero. Same story for
        // "Biomolecules-II (Proteins, types & functions), Lipids, Nucleic acids, Enzymes,
        // Cofactors" — 33 questions raw; canonicalized to "Cell Structure And Function" — zero).
        // route.ts does an exact .eq('chapter', chapter) match, so sending the canonicalized
        // name was silently routing every one of these lookups at a chapter Testmate has never
        // heard of. Sending the raw, uncanonicalized chapter is what actually matches Testmate's
        // own tags.

        // BUGFIX: GapTaskLedger.recordServed sets activeTopic() to the RAW (pre-canonicalization)
        // chapter string as its "no real topic" sentinel (candidate.topic ?: candidate.chapter —
        // see that function's own doc, matching Concept.kt's "topic then equals chapter" convention).
        // Only forward topic when it's a genuinely distinct value from the raw chapter; otherwise
        // sending chapter alone is correct, same as if no topic had ever been recorded.
        //
        // BUGFIX (topic-"null" 422 loop): GapTaskLedger.activeTopic() now sanitizes the literal
        // string "null" itself, so this filter is redundant for anything served from here on.
        // Kept anyway as defense-in-depth for [topic] values that reach this function some other
        // way, and because "it != chapter" alone was never a substitute for a blank/literal-null
        // check to begin with.
        val topicForApi = topic?.takeIf { it != chapter && !it.equals("null", ignoreCase = true) }

        // BUGFIX (P0b re-intervention loop, part 2): TestmateApi.createTargetedTest is
        // idempotent BY intervention_id — Testmate returns the same test/session on a
        // repeated call with the same id rather than creating a new one (see that
        // function's own doc). conceptId never changes between rounds, so round 2's
        // request here was sending the EXACT SAME intervention_id as round 1's, and
        // Testmate correctly handed back round 1's session — already completed by the
        // student. That's what made "Take Test" land on an old result page: it wasn't a
        // stale UI/WebView issue, Testmate genuinely never created a new session. Round
        // 1 keeps the bare conceptId (no behavior change for sessions already in flight);
        // round 2+ gets a distinct suffix from GapTaskLedger's round counter (bumped in
        // resetForNextRound) so each round is a genuinely separate intervention as far as
        // Testmate's idempotency check is concerned.
        val round = GapTaskLedger.activeTestmateRound()
        val interventionId = if (round <= 1) conceptId else "$conceptId-r$round"
        if (round > 1) {
            Log.d(TAG, "createTargetedTestIfNeeded: round=$round for concept=$conceptId — " +
                "using intervention_id=$interventionId so Testmate issues a fresh session " +
                "instead of replaying round 1's completed one")
        }

        val outcome = try {
            TestmateApi.createTargetedTest(
                interventionId = interventionId,
                chapter = chapter,
                topic = topicForApi,
                questionCount = TARGETED_TEST_QUESTION_COUNT,
                pool = TestmateQuestionPool.WRONG_SKIPPED
            )
        } catch (e: Exception) {
            Log.e(TAG, "createTargetedTest threw: ${e.message}", e)
            recordTestmateError("Unexpected error: ${e.message ?: "unknown"}")
            return
        }

        when (outcome) {
            is TestmateTargetedTestOutcome.Success -> {
                GapTaskLedger.recordTestmateSession(outcome.test.testId, outcome.test.sessionId)
                Log.d(TAG, "targeted test ready: concept=$conceptId session=${outcome.test.sessionId}")
                clearTestmateError()
            }
            is TestmateTargetedTestOutcome.Error -> {
                Log.w(TAG, "createTargetedTest error for concept=$conceptId: ${outcome.message}")
                recordTestmateError(outcome.message)
            }
        }
    }

    /** Persists the failure so it survives past logcat's buffer — see [PREF_TESTMATE_LAST_ERROR]. */
    private fun recordTestmateError(message: String) {
        CheckmatePrefs.putString(PREF_TESTMATE_LAST_ERROR, message)
        CheckmatePrefs.putLong(PREF_TESTMATE_LAST_ERROR_AT, System.currentTimeMillis())
    }

    private fun clearTestmateError() {
        CheckmatePrefs.remove(PREF_TESTMATE_LAST_ERROR)
        CheckmatePrefs.remove(PREF_TESTMATE_LAST_ERROR_AT)
    }

    // ── P0b: evidence import ─────────────────────────────────────────────────

    /**
     * Every 15-min cycle: if the active concept has a Testmate session ([createTargetedTestIfNeeded]
     * already ran) whose evidence hasn't been imported yet, fetches the result and, once it's
     * actually there (the student has submitted — an [TestmateResultOutcome.Error], including
     * Testmate's "no result yet" 404, just means try again next cycle), hands the per-question
     * breakdown to [TargetedTestEvidenceImporter]. This is deliberately NOT gated to once/day
     * like [generateIfNeeded]/[escalationCheckIfNeeded] — the student can submit the test at any
     * time, and the whole point of P0b is that mastery reflects that as soon as it's known, not
     * up to 24 hours later.
     */
    /** BUGFIX (unsynchronized ledger race): see [GapTaskLedger.withLock]'s doc — this
     *  reads then, once the student's submitted result lands, writes
     *  [GapTaskLedger.markActiveEvidenceImported], the exact read-then-write shape the
     *  other two entry points also race against. */
    suspend fun evidencePollIfNeeded(context: Context) = GapTaskLedger.withLock {
        evidencePollIfNeededLocked(context)
    }

    private suspend fun evidencePollIfNeededLocked(context: Context) {
        if (GapTaskLedger.isActiveEvidenceImported()) return
        val sessionId = GapTaskLedger.activeTestmateSessionId() ?: return

        val outcome = try {
            TestmateApi.fetchResult(sessionId)
        } catch (e: Exception) {
            Log.w(TAG, "evidencePollIfNeeded fetch threw: ${e.message}")
            return
        }
        val result = when (outcome) {
            is TestmateResultOutcome.Success -> outcome.result
            is TestmateResultOutcome.Error -> return // not submitted yet (or a real failure either way) — retry next cycle
        }
        if (result.breakdown.isEmpty()) return // submitted but no per-question data yet — treat like not-ready

        try {
            val exam = ConsultationProfile.load().examTarget
            val chapter = GapTaskLedger.activeChapter()
            val topic = GapTaskLedger.activeTopic()
            TargetedTestEvidenceImporter.import(
                context = context,
                sessionId = sessionId,
                exam = exam,
                chapter = chapter,
                topic = topic,
                result = result
            )
            GapTaskLedger.markActiveEvidenceImported(
                attemptCount = result.attemptedCount,
                correctCount = result.correctCount
            )
        } catch (e: Exception) {
            Log.e(TAG, "evidence import failed: ${e.message}", e)
        }
    }

    // ── Escalating skip/stall warning ───────────────────────────────────────

    private val TIER_1_SYSTEM_PROMPT = """
You are a strict but adaptive study coach warning a student about ONE specific unfinished
task, not their whole day. Rules:
- 1-3 sentences, direct and consequence-based, no generic praise or "you can do it"
- Name the specific concept and why it matters (marks at stake), not vague encouragement
- Tone: firm reminder, not yet an ultimatum
""".trimIndent()

    private val TIER_2_SYSTEM_PROMPT = """
You are a strict but adaptive study coach. This specific gap has been ignored for several
days running — this is the escalated warning, not the first nudge. Rules:
- 3-5 sentences, still no fluff, but now make the full case
- State plainly how many days this has been unaddressed
- Connect this ONE concept to the real exam-day consequence (marks lost, gap to target score)
- Reference the student's own skip pattern if given one, as evidence this is a pattern, not a one-off
- End with a direct, specific ask — not "you should study" but "do this today"
""".trimIndent()

    /**
     * Once per calendar day (guarded by [GapTaskLedger.hasEscalatedToday]). Depth scales with
     * [GapTaskLedger.activeDaysServed]: day 1 gets no escalation at all (the task just showed
     * up today — that's the normal experience, not a warning), day 2-3 names the concrete
     * consequence, day 4+ is the full persuasive case tying this one concept to the exam-day
     * outcome and the student's own skip pattern (see [BehaviorLedger.getSnapshot]). Skips
     * entirely while the task is ACTIVE/PAUSED (the student is mid-session on it right now —
     * interrupting with a warning would be counterproductive) and resolves it (see
     * [resolveDoneConcept]) instead of warning if it turns out to already be DONE
     * (belt-and-suspenders alongside [resolveActiveConceptState], which normally catches this
     * first).
     */
    /** BUGFIX (unsynchronized ledger race): can call [resolveDoneConcept] ->
     *  [GapTaskLedger.resetForNextRound] and [createTargetedTestIfNeeded] on the same
     *  active-concept state the other two entry points touch — see
     *  [GapTaskLedger.withLock]'s doc. */
    suspend fun escalationCheckIfNeeded(context: Context) = GapTaskLedger.withLock {
        escalationCheckIfNeededLocked(context)
    }

    private suspend fun escalationCheckIfNeededLocked(context: Context) {
        val todayKey = GapTaskLedger.todayKey()
        if (GapTaskLedger.hasEscalatedToday(todayKey)) return

        val daysServed = GapTaskLedger.activeDaysServed()
        if (daysServed < 2) return // day 1: normal experience, no warning

        val conceptId = GapTaskLedger.activeConceptId() ?: return
        val taskId = GapTaskLedger.activeTaskId() ?: return
        val dayKey = GapTaskLedger.activeTaskDayKey() ?: return
        val task = findTask(taskId, dayKey)

        if (task == null || task.state == TaskState.DONE) {
            if (task?.state == TaskState.DONE) {
                resolveDoneConcept(context, conceptId, dayKey)
                // BUGFIX (r1-result-on-r2-screen): resolveDoneConcept can call
                // GapTaskLedger.resetForNextRound() (still-below-mastery / conceptId-drift
                // branches), which clears the active session so a new round can be
                // requested — but it doesn't request one itself. generateIfNeeded() always
                // pairs its resolveDoneConcept call with createTargetedTestIfNeeded() right
                // after (see that function's own BUGFIX note); this path didn't, so a round
                // reset discovered here sat un-actioned until the next generateIfNeeded
                // cycle, by which point a fresh re-rank can pick a different top concept and
                // recordServed's isNewConcept branch silently abandons this round's request —
                // leaving the old round-1 Testmate session as the only one on file even
                // though the ledger had already moved to round 2 (confirmed via repro log:
                // resetForNextRound round 1->2 for biomolecules-ii, immediately followed by
                // recordServed picking up an unrelated concept with no
                // createTargetedTestIfNeeded ever firing for biomolecules' round 2). Calling
                // it here too closes that gap the same way generateIfNeeded already does;
                // it's a no-op whenever resolveDoneConcept covered the concept outright or
                // deferred it back to PENDING, since both leave no active concept id.
                createTargetedTestIfNeeded()
            }
            GapTaskLedger.markEscalatedToday(todayKey)
            return
        }
        if (task.state != TaskState.PENDING && task.state != TaskState.SKIPPED) {
            return // ACTIVE/PAUSED right now — don't interrupt an in-progress session
        }

        val tier = if (daysServed >= 4) 2 else 1
        val message = try {
            buildEscalationMessage(tier, daysServed)
        } catch (e: Exception) {
            Log.e(TAG, "escalation message build failed: ${e.message}", e)
            GapTaskLedger.markEscalatedToday(todayKey)
            return
        }

        MentorViewModel.appendProactiveMessage(message)
        MentorNotifier.notify(context, message)
        GapTaskLedger.logWarningSent(conceptId, todayKey, daysServed, tier)
        GapTaskLedger.markEscalatedToday(todayKey)
        Log.d(TAG, "escalation sent: tier=$tier daysServed=$daysServed concept=$conceptId")
    }

    private suspend fun buildEscalationMessage(tier: Int, daysServed: Int): String {
        val subject = GapTaskLedger.activeSubject()?.takeIf { it.isNotBlank() } ?: "this subject"
        val topic = GapTaskLedger.activeTopic()?.takeIf { it.isNotBlank() } ?: subject
        val rationale = GapTaskLedger.activeRationale()?.takeIf { it.isNotBlank() }
            ?: "$topic still needs work."
        val marksAtStake = GapTaskLedger.activeExpectedGain()

        val patternLine = BehaviorLedger.getSnapshot().subjectPatterns
            .filter { it.subject.equals(subject, ignoreCase = true) }
            .maxByOrNull { it.occurrences }
            ?.let { "You've skipped $subject ${it.taskType} tasks ${it.occurrences} times recently — this fits the pattern." }
            ?: ""

        val ruleMsg = when (tier) {
            2 -> "Day $daysServed unaddressed: $topic. $rationale $patternLine " +
                "This gap alone is worth roughly ${"%.1f".format(marksAtStake)} marks — " +
                "left alone, it stays a hole on exam day. Do it today."
            else -> "$topic has gone $daysServed days without being done. $rationale Do it today."
        }

        val systemPrompt = if (tier == 2) TIER_2_SYSTEM_PROMPT else TIER_1_SYSTEM_PROMPT
        val userPrompt = "Concept: $topic ($subject). Days unaddressed: $daysServed. " +
            "Why it matters: $rationale Approx marks at stake: ${"%.1f".format(marksAtStake)}. " +
            "$patternLine Write the warning message now."

        return try {
            val llmMsg = LlmGateway.complete(userPrompt, systemPrompt)
            if (llmMsg.isBlank()) ruleMsg else llmMsg
        } catch (_: Exception) {
            ruleMsg
        }
    }
}
