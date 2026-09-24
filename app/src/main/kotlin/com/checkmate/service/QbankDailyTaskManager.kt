package com.checkmate.service

import android.content.Context
import com.checkmate.core.CheckmatePrefs
import com.checkmate.core.DebugTrail
import com.checkmate.testmate.TestmateApi
import com.checkmate.testmate.TestmateDailyTargetOutcome
import com.checkmate.testmate.TestmateQbankPracticeOutcome
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * "Daily Target of qs as per FT schedule should be scheduled on a Checkmate task —
 * continue practice qbank via API for all 4 subjects" (chat history). The P0c
 * bridge between Testmate's server-computed, FT-schedule-boosted daily coverage
 * target ([TestmateApi.fetchDailyTarget], lib/daily-target-engine.ts on the
 * Testmate side) and an actual Q-bank practice SESSION the student can tap
 * straight into from Checkmate — one per NEET subject that has a target today.
 *
 * Deliberately a separate object from [GapTaskManager]: that one drives the P0b
 * gap-REPAIR loop (POST /api/tests/targeted, WRONG_SKIPPED pool, keyed by
 * intervention_id off GapTaskLedger's active concept). This one drives Q-bank
 * COVERAGE practice ([TestmateApi.startQbankPractice], NEW pool, no
 * intervention_id at all — see that route's own boundary note) off the daily
 * per-subject breakdown instead. The two pipelines never touch the same session,
 * same separation the Testmate side already enforces server-side.
 *
 * Once per calendar day (mirrors [GapTaskManager.generateIfNeeded]'s gate and its
 * "only mark today done once something was actually decided, not on every empty/
 * failed attempt" bugfix — an unconfigured exam target or a transient network
 * error retries hourly via [RETRY_INTERVAL_MS] instead of locking out the rest of
 * the day):
 *
 *  1. [TestmateApi.fetchDailyTarget] — GET /api/qbank/daily-target.
 *  2. For each of the 4 NEET subjects (Physics/Chemistry/Botany/Zoology) with
 *     `subject_breakdown[].coverage_target > 0` and no session already recorded
 *     today, pick that subject's #1 chapter off `coverage_gaps` — already sorted
 *     most-urgent-first server-side (soonest FT study deadline, then most
 *     remaining questions), and already filtered to chapters with
 *     `remaining_questions > 0` (see lib/daily-target-engine.ts's own doc), so any
 *     entry found here is guaranteed to have real questions behind it.
 *  3. [TestmateApi.startQbankPractice] — POST /api/qbank/practice with
 *     `pool = NEW`, `question_count = ` that subject's coverage target.
 *     Idempotent per chapter/pool on the Testmate side (a still-live session for
 *     the same chapter is handed back instead of forking a duplicate), so a retry
 *     of this same call — e.g. from [StatsScreen] re-entering the screen — is safe.
 *  4. Persists `{subject, chapter, sessionId, testId, questionCount}` per subject
 *     so [todaysSessions] can drive a "Continue <Subject> Q-bank practice" entry —
 *     see StatsScreen's "Today's Q-bank Targets" card, which opens the session via
 *     the existing `test_web/{sessionId}` route (same one P0b targeted tests use).
 *
 * A subject with a target but nothing in `coverage_gaps` for it (syllabus_chapters
 * not yet seeded server-side, or that subject's bank is fully drained) is skipped
 * for the day rather than retried every cycle — there's no chapter to send.
 */
object QbankDailyTaskManager {

    private const val TAG = "QbankDailyTaskManager"

    // Same once-a-day-gate-only-marked-on-real-outcome pattern as
    // GapTaskManager's own PREF_LAST_ATTEMPT_MS bugfix note — see that object's
    // class doc for why an empty/failed run must not lock out the rest of the day.
    private const val PREF_LAST_GENERATED_DAY = "qbank_daily_last_generated_day"
    private const val PREF_LAST_ATTEMPT_MS = "qbank_daily_last_attempt_ms"
    private const val RETRY_INTERVAL_MS = 60L * 60_000L

    private const val PREF_SESSIONS_JSON = "qbank_daily_sessions_json"
    private const val PREF_SESSIONS_DAY = "qbank_daily_sessions_day"

    // Persisted the same way GapTaskManager.PREF_TESTMATE_LAST_ERROR is — visible in
    // Settings → Test Platform without needing adb logcat. Cleared on the next
    // successful attempt.
    const val PREF_LAST_ERROR = "qbank_daily_last_error"
    const val PREF_LAST_ERROR_AT = "qbank_daily_last_error_at"

    private val SUBJECTS = listOf("Physics", "Chemistry", "Botany", "Zoology")

    private val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private fun todayKey(): String = dayFormat.format(Date())

    data class QbankDailySession(
        val subject: String,
        val chapter: String,
        val sessionId: String,
        val testId: String,
        val questionCount: Int
    )

    /**
     * Today's already-created sessions, one per subject that got one — for
     * StatsScreen to render "Continue <Subject>" entries. Empty before the first
     * successful [generateIfNeeded] run of the day, or once a new day rolls over
     * (the stored list is day-stamped via [PREF_SESSIONS_DAY], same guard style as
     * TestmateWebScreen's PREF_HISTORY).
     */
    fun todaysSessions(): List<QbankDailySession> {
        if (CheckmatePrefs.getString(PREF_SESSIONS_DAY, null) != todayKey()) return emptyList()
        val raw = CheckmatePrefs.getString(PREF_SESSIONS_JSON, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                QbankDailySession(
                    subject = o.optString("subject"),
                    chapter = o.optString("chapter"),
                    sessionId = o.optString("sessionId"),
                    testId = o.optString("testId"),
                    questionCount = o.optInt("questionCount")
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun saveSessions(sessions: List<QbankDailySession>) {
        val arr = JSONArray()
        sessions.forEach { s ->
            arr.put(JSONObject().apply {
                put("subject", s.subject)
                put("chapter", s.chapter)
                put("sessionId", s.sessionId)
                put("testId", s.testId)
                put("questionCount", s.questionCount)
            })
        }
        CheckmatePrefs.putString(PREF_SESSIONS_JSON, arr.toString())
        CheckmatePrefs.putString(PREF_SESSIONS_DAY, todayKey())
    }

    /**
     * Entry point — called from [ReminderService]'s 15-min loop, and also directly
     * from [com.checkmate.ui.stats.StatsScreen] on screen entry so a session
     * created just now shows up without waiting for the next service cycle. Both
     * call sites are safe to call redundantly: the day-key gate and per-subject
     * "already have one today" check below make a repeat call a cheap no-op once
     * today's sessions exist.
     */
    suspend fun generateIfNeeded(context: Context) {
        val today = todayKey()
        if (CheckmatePrefs.getString(PREF_LAST_GENERATED_DAY, null) == today) return

        val lastAttempt = CheckmatePrefs.getLong(PREF_LAST_ATTEMPT_MS, 0L)
        if (System.currentTimeMillis() - lastAttempt < RETRY_INTERVAL_MS) return
        CheckmatePrefs.putLong(PREF_LAST_ATTEMPT_MS, System.currentTimeMillis())

        DebugTrail.d(TAG, "generateIfNeeded: ENTER day=$today")

        when (val outcome = TestmateApi.fetchDailyTarget()) {
            is TestmateDailyTargetOutcome.Error -> {
                DebugTrail.e(TAG, "generateIfNeeded: fetchDailyTarget failed: ${outcome.message}")
                recordError(outcome.message)
            }
            is TestmateDailyTargetOutcome.Success -> {
                val target = outcome.target
                if (!target.configured) {
                    // Not an error — the student just hasn't set exam_date/syllabus_deadline
                    // on Testmate yet (POST /api/qbank/exam-target). Nothing to schedule
                    // until they do; retry hourly rather than daily since this is cheap and
                    // the student may configure it mid-session.
                    DebugTrail.d(TAG, "generateIfNeeded: exam target not configured on Testmate yet — skip")
                    return
                }

                // coverage_gaps is already sorted most-urgent-first server-side (soonest
                // FT study deadline, then most remaining) — taking the first match per
                // subject is exactly "that subject's #1 gap", no re-sorting needed here.
                val topGapChapterBySubject: Map<String, String> = (target.coverageGaps ?: emptyList())
                    .groupBy { it.subject }
                    .mapValues { (_, gaps) -> gaps.first().chapter }

                val existing = todaysSessions().associateBy { it.subject }.toMutableMap()
                var anyCreated = false
                var lastFailure: String? = null

                for (subject in SUBJECTS) {
                    if (existing.containsKey(subject)) continue // already have today's session

                    val subjectCoverage = target.subjectBreakdown.find { it.subject == subject }
                    if (subjectCoverage == null || subjectCoverage.coverageTarget <= 0) continue

                    val chapter = topGapChapterBySubject[subject]
                    if (chapter == null) {
                        DebugTrail.d(TAG, "generateIfNeeded: no coverage_gaps chapter for $subject yet — skipping")
                        continue
                    }

                    when (val practiceOutcome = TestmateApi.startQbankPractice(
                        chapter = chapter,
                        questionCount = subjectCoverage.coverageTarget
                    )) {
                        is TestmateQbankPracticeOutcome.Success -> {
                            val r = practiceOutcome.result
                            existing[subject] = QbankDailySession(
                                subject = subject,
                                chapter = chapter,
                                sessionId = r.sessionId,
                                testId = r.testId,
                                questionCount = r.questionCount
                            )
                            anyCreated = true
                            DebugTrail.d(
                                TAG,
                                "generateIfNeeded: $subject -> chapter=$chapter session=${r.sessionId} " +
                                    "q=${r.questionCount} reused=${r.reused}"
                            )
                        }
                        is TestmateQbankPracticeOutcome.Error -> {
                            lastFailure = "$subject: ${practiceOutcome.message}"
                            DebugTrail.e(
                                TAG,
                                "generateIfNeeded: startQbankPractice failed for $subject/$chapter: ${practiceOutcome.message}"
                            )
                        }
                    }
                }

                if (anyCreated) {
                    saveSessions(existing.values.toList())
                }
                if (lastFailure != null) {
                    recordError(lastFailure)
                } else {
                    clearError()
                }
                // Mark today's gate done once every subject either got a session or was
                // legitimately skipped (no target, or no coverage_gaps chapter yet) — a
                // real Testmate error with NOTHING created leaves the gate open so the
                // hourly retry (above) tries again instead of waiting until tomorrow.
                if (anyCreated || lastFailure == null) {
                    CheckmatePrefs.putString(PREF_LAST_GENERATED_DAY, today)
                }
            }
        }
    }

    private fun recordError(message: String) {
        CheckmatePrefs.putString(PREF_LAST_ERROR, message)
        CheckmatePrefs.putLong(PREF_LAST_ERROR_AT, System.currentTimeMillis())
    }

    private fun clearError() {
        CheckmatePrefs.remove(PREF_LAST_ERROR)
        CheckmatePrefs.remove(PREF_LAST_ERROR_AT)
    }
}
