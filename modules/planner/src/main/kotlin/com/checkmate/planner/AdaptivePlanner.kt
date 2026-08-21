package com.checkmate.planner

import android.content.Context
import android.util.Log
import com.checkmate.core.BehaviorSnapshot
import com.checkmate.core.CheckmatePrefs
import com.checkmate.core.ConsultationProfile
import com.checkmate.core.ConsultationProfile.Companion.toPromptContext
import com.checkmate.core.CoachingPlannerEntry
import com.checkmate.core.DailyCheckIn
import com.checkmate.core.DailyChecklist
import com.checkmate.core.PYQWeightage
import com.checkmate.core.TodayContext
import com.checkmate.core.llm.LlmGateway
import com.checkmate.learning.model.ConceptSnapshot
import com.checkmate.learning.model.PrerequisiteRef
import com.checkmate.learning.model.RetentionDecisionSnapshot
import com.checkmate.learning.model.StudentModel
import com.checkmate.learning.student.StudentModelBuilder
import com.checkmate.planner.model.StudyTask
import com.checkmate.planner.model.SubjectConfig
import com.checkmate.planner.model.TaskType
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

object AdaptivePlanner {

    private const val TAG = "AdaptivePlanner"

    suspend fun generateDailyPlan(context: Context, config: PlannerState): List<StudyTask> {
        val daysLeft          = daysUntilExam(config.examDate)
        // Upgrade Blueprint Phase 0 item #2 ("Stop treating the LLM as source of truth"):
        // structured deterministic metrics, not a hand-built prose string — see
        // getBehaviorSnapshot() below.
        val behaviorSnapshot  = getBehaviorSnapshot()
        val studyWindowHours  = calculateStudyWindowHours(config.studyStartTime, config.studyEndTime)
        val profile           = ConsultationProfile.load()
        val checkIn           = DailyCheckIn.loadToday()
        val coachingContext   = CoachingPlannerEntry.upcomingContext(7)
        val pyqContext         = buildPyqContext(config.examType, checkIn)
        // FEATURE: Checklist → Planner — same-day lecture/DPP/notes completion
        // feeds tomorrow's plan so an incomplete checklist skews the next plan
        // toward catching up on fundamentals rather than piling on new PYQ topics.
        val checklistContext  = buildChecklistContext()
        // Mentor v2 (spec 3.7): read TodayContext directly (not folded into the cached
        // BehaviorSnapshot — see PsycheEngine.refreshBehaviorSummaryCache()'s doc on why)
        // so a mid-day regenerate always reflects the latest same-day free-text updates
        // even if refreshBehaviorSummaryCache() hasn't run since the last one (e.g. the
        // very first plan of the day).
        val todayContext      = TodayContext.getSummaryText()

        // Upgrade Blueprint Phase 2 wiring ("Planner reframe"): StudentModelBuilder.build()
        // was fully implemented back in Phase 1 (aggregates MasteryEngine/ErrorEngine/
        // RetentionEngine/KnowledgeGraph, itself fed by every report.md import via
        // TestResultNormalizer) but nothing ever called it — real test performance was
        // computed and persisted, then sat unread while the planner kept guessing from
        // PYQ weightage + behavior alone. This is the first consumer: one snapshot, reused
        // by both the LLM path (as structured JSON, same "deterministic data, not prose"
        // discipline as BEHAVIOR_METRICS below) and the rule-based fallback (prefers the
        // weakest/review-due concept per subject over blind PYQ rotation, when one exists).
        val studentModel = StudentModelBuilder.build(context)

        // Blueprint 4.1: today's free windows (study window minus school/coaching
        // blocked slots). Computed once, applied to whichever plan comes out below.
        val freeSlots = FreeSlotCalculator.computeFreeSlots(
            profile.blockedSlots, config.studyStartTime, config.studyEndTime
        )

        val llmPlan = tryLlmPlan(config, daysLeft, behaviorSnapshot, studyWindowHours, profile, checkIn, coachingContext, pyqContext, checklistContext, todayContext, studentModel)
        if (llmPlan.isNotEmpty()) return assignScheduledTimes(llmPlan, freeSlots)

        return assignScheduledTimes(ruleBasedPlan(config, daysLeft, studyWindowHours, studentModel), freeSlots)
    }

    /**
     * Blueprint 4.2: packs tasks sequentially into today's free slots in the
     * order they were generated — which is already priority order (highest
     * subject weightage / PYQ-driven reason first, from both the LLM and
     * rule-based paths) — so the hardest/highest-priority work naturally lands
     * in the earliest slots rather than needing a second re-sort here.
     *
     * A task that doesn't fit in the slot it's currently pointed at moves the
     * cursor to the next free slot; a task that doesn't fit anywhere (total
     * plan exceeds available free time) is returned with scheduledStartTime
     * left null — HomeScreen's timeline view groups those under "Unscheduled"
     * instead of silently dropping them.
     */
    private fun assignScheduledTimes(
        tasks: List<StudyTask>,
        freeSlots: List<FreeSlotCalculator.FreeSlot>
    ): List<StudyTask> {
        if (tasks.isEmpty() || freeSlots.isEmpty()) return tasks

        var slotIndex = 0
        var cursor = freeSlots[0].startMinute

        return tasks.map { task ->
            while (slotIndex < freeSlots.size && cursor + task.durationMinutes > freeSlots[slotIndex].endMinute) {
                slotIndex++
                cursor = if (slotIndex < freeSlots.size) freeSlots[slotIndex].startMinute else -1
            }
            if (slotIndex >= freeSlots.size || cursor < 0) {
                task
            } else {
                val scheduled = task.copy(scheduledStartTime = FreeSlotCalculator.formatMinutes(cursor))
                cursor += task.durationMinutes
                scheduled
            }
        }
    }

    /**
     * Upgrade Blueprint Phase 0 item #2 ("Stop treating the LLM as source of truth").
     * Reads the structured BehaviorSnapshot PsycheEngine.refreshBehaviorSummaryCache()
     * serializes into CheckmatePrefs (same cross-module bridge as before — :modules:planner
     * still can't depend on :modules:psyche directly, see PsycheEngine's own doc on why —
     * only the payload crossing that bridge changed, from hand-built prose to JSON).
     *
     * Falls back to BehaviorSnapshot.EMPTY (all zeros/empty lists) instead of a sentence
     * like "No behavior data yet" — the LLM gets the same JSON shape whether or not history
     * exists yet, rather than a special-cased string it would have to treat differently.
     */
    private fun getBehaviorSnapshot(): BehaviorSnapshot {
        val raw = CheckmatePrefs.getString("behavior_snapshot_json", null) ?: return BehaviorSnapshot.EMPTY
        return try {
            Json { ignoreUnknownKeys = true }.decodeFromString<BehaviorSnapshot>(raw)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to decode behavior snapshot, using empty: ${e.message}")
            BehaviorSnapshot.EMPTY
        }
    }

    private fun buildPyqContext(exam: String, checkIn: DailyCheckIn?): String {
        if (checkIn == null) return ""
        return checkIn.todayTopics.entries.mapNotNull { (subject, topic) ->
            val weight = PYQWeightage.findTopicWeightage(exam, topic)
            if (weight > 0f) "$subject/$topic: PYQ weight ${String.format("%.1f", weight)}%"
            else null
        }.joinToString("\n")
    }

    /**
     * Reads today's checklist completion (lecture/notes/DPP/etc.) so the LLM
     * planner can react to same-day execution gaps, not just multi-day behavior
     * trends. Returns "" when the checklist was never touched today — an
     * untouched checklist means "not used," not "0% complete," and treating it
     * as a crisis would produce false catch-up plans. Mirrors the same
     * empty-guard pattern used in buildPyqContext().
     */
    private fun buildChecklistContext(): String {
        val summary = DailyChecklist.getTodaySummaryText()
        if (summary.isBlank()) return ""

        val items = DailyChecklist.getTodayItems()
        if (items.isEmpty()) return ""

        val touched = items.any { it.isDone }
        // Checklist exists but nothing has been checked off yet today (e.g. it's
        // early morning) — not a signal of falling behind, so skip it.
        if (!touched) return ""

        val doneCount = items.count { it.isDone }
        val totalCount = items.size
        val incomplete = items.filter { !it.isDone }.map { it.label }

        return buildString {
            appendLine("Today's checklist: $doneCount/$totalCount complete")
            if (incomplete.isNotEmpty()) {
                appendLine("Not done yet: ${incomplete.joinToString()}")
            }
        }.trim()
    }

    // ── Upgrade Blueprint Phase 2 wiring: compact StudentModel → LLM prompt ──
    // StudentModel.concepts can grow to hundreds of rows over a full prep cycle
    // (:modules:learning's model classes are deliberately not @Serializable — see
    // StudentModel.kt's own class doc on why it stays a derived, non-persisted read
    // model — so this hand-builds a small, prompt-sized, @Serializable summary here
    // rather than dumping the whole thing). Same "own words / own shape" boundary
    // StudentModel.kt's class doc already draws around StudentModelBuilder itself.

    @Serializable
    private data class WeakPrerequisiteEntry(
        val label: String,
        /** Null means the prerequisite has never been personally attempted — not
         *  "0% mastery." See PrerequisiteRef's own doc: this can legitimately be a
         *  concept outside the student's `concepts` map. */
        val masteryPercent: Int?
    )

    @Serializable
    private data class ConceptEntry(
        val subject: String?,
        val chapter: String?,
        val topic: String?,
        val masteryPercent: Int,
        /** Named, not just flagged — see ConceptSnapshot.prerequisiteIssues'
         *  CORRECTNESS FIX note. Empty when this concept has no diagnosed weak
         *  prerequisite. */
        val weakPrerequisites: List<WeakPrerequisiteEntry>
    )

    @Serializable
    private data class ErrorEntry(
        val subject: String?,
        val chapter: String?,
        val topic: String?,
        val errorType: String,
        val occurrences: Int
    )

    @Serializable
    private data class StudentModelPromptSummary(
        val conceptsTracked: Int,
        val conceptsMastered: Int,
        val conceptsWeak: Int,
        val averageMasteryPercent: Int,
        val unresolvedErrorCount: Int,
        /** Lowest-mastery concepts first, capped — see class doc above. */
        val weakestConcepts: List<ConceptEntry>,
        /** RetentionEngine.decide() == REVIEW, sorted by forgettingRisk desc, capped. */
        val reviewDue: List<ConceptEntry>,
        /** StudentModel.unresolvedErrors, already occurrences-desc, capped. */
        val topErrorPatterns: List<ErrorEntry>
    )

    /**
     * Resolves a [PrerequisiteRef] to a display label plus, when available, that
     * prerequisite's own mastery — looked up from this same [StudentModel]'s
     * `concepts` map, which only has an entry if the student has actually
     * attempted it. A never-attempted prerequisite is a real, useful signal in its
     * own right ("introduce this topic first, not just review it") — surfaced as
     * `masteryPercent = null` rather than guessed at.
     */
    private fun resolvePrerequisite(model: StudentModel, ref: PrerequisiteRef): WeakPrerequisiteEntry {
        val label = ref.topic ?: ref.chapter ?: ref.subject ?: ref.conceptId
        val mastery = model.concepts[ref.conceptId]?.mastery
        return WeakPrerequisiteEntry(label, mastery?.let { (it * 100).toInt() })
    }

    /**
     * Compacts a full [StudentModel] into a small, prompt-sized summary: top-8
     * weakest concepts, top-8 review-due concepts, top-8 unresolved error
     * patterns — pre-sorted so the LLM sees the highest-signal rows first
     * instead of reasoning over a large, unranked blob.
     *
     * Returns null when the student has no tracked concepts yet (no report.md
     * ever imported) — same empty-guard pattern as [buildPyqContext]/
     * [buildChecklistContext] — so the prompt doesn't grow an empty/misleading
     * STUDENT_MODEL section before the first mock is imported.
     */
    private fun buildStudentModelSummary(model: StudentModel): StudentModelPromptSummary? {
        if (model.overall.conceptsTracked == 0) return null

        fun ConceptSnapshot.toEntry() = ConceptEntry(
            subject = subject,
            chapter = chapter,
            topic = topic,
            masteryPercent = (mastery * 100).toInt(),
            weakPrerequisites = prerequisiteIssues.map { resolvePrerequisite(model, it) }
        )

        val weakest = model.concepts.values
            .sortedBy { it.mastery }
            .take(8)
            .map { it.toEntry() }

        val reviewDue = model.concepts.values
            .filter { it.retentionDecision == RetentionDecisionSnapshot.REVIEW }
            .sortedByDescending { it.forgettingRisk }
            .take(8)
            .map { it.toEntry() }

        val topErrors = model.unresolvedErrors.take(8).map { pattern ->
            val concept = model.concepts[pattern.conceptId]
            ErrorEntry(
                subject = concept?.subject,
                chapter = concept?.chapter,
                topic = concept?.topic,
                errorType = pattern.errorType,
                occurrences = pattern.occurrences
            )
        }

        return StudentModelPromptSummary(
            conceptsTracked = model.overall.conceptsTracked,
            conceptsMastered = model.overall.conceptsMastered,
            conceptsWeak = model.overall.conceptsWeak,
            averageMasteryPercent = (model.overall.averageMastery * 100).toInt(),
            unresolvedErrorCount = model.overall.unresolvedErrorCount,
            weakestConcepts = weakest,
            reviewDue = reviewDue,
            topErrorPatterns = topErrors
        )
    }

    private suspend fun tryLlmPlan(
        config: PlannerState,
        daysLeft: Int,
        behaviorSnapshot: BehaviorSnapshot,
        studyWindowHours: Float,
        profile: com.checkmate.core.ConsultationProfile,
        checkIn: DailyCheckIn?,
        coachingContext: String,
        pyqContext: String,
        checklistContext: String,
        todayContext: String = "",
        // Upgrade Blueprint Phase 2 wiring — see buildStudentModelSummary() above.
        studentModel: StudentModel
    ): List<StudyTask> {
        val studentModelSummary = buildStudentModelSummary(studentModel)

        val systemPrompt = """
You are an adaptive study planner for competitive exam students.
Generate a focused daily study plan. Respond ONLY with a valid JSON array, no markdown, no explanation.
Format: [{"subject":"Biology","topic":"Cell Division","subtopic":"Mitosis stages","durationMinutes":45,"sessionType":"LEARN","priority":"HIGH","reason":"PYQ weight 9%, marked weak"}]
Rules:
- Max 5 tasks
- Total time must fit in ${studyWindowHours.toInt()} hours (minus breaks)
- Weight tasks by subject priority and PYQ weightage
- If exam < 30 days: revision-heavy
- If exam < 7 days: full revision only
- Keep durations in multiples of 30
- sessionType must be one of: LEARN, REVISE, PRACTICE, TEST_PREP
- reason must be specific: mention PYQ %, coaching test dates, weak topic flags
- If TODAY'S CHECKLIST STATUS shows incomplete fundamentals (lecture notes, DPP, NCERT reading)
  from earlier today, prioritize catching those up over introducing new topics — an unfinished
  checklist means the student is behind on today's baseline, not ready for additional load
- If TODAY'S LOGGED UPDATES mentions something already covered today (e.g. "back from coaching,
  did Physics 2hrs"), do not re-assign that same subject/topic — build on it or move to the next
  weak area instead
- BEHAVIOR_METRICS below is deterministic data computed directly from stored events, not a
  prose summary — treat every number and list in it as ground truth. subjectPatterns lists
  specific (subject, taskType) combinations the student has repeatedly skipped (occurrences >= 3
  in the last 7 days) — weight tasks in those combinations accordingly instead of only reacting
  to the aggregate recentSkipRatePercent
- STUDENT_MODEL, when present, is also deterministic — built from the student's actual imported
  test results (MasteryEngine/ErrorEngine/RetentionEngine). weakestConcepts and reviewDue are
  real measured gaps; topErrorPatterns are mistakes the student has actually made repeatedly.
  Prioritize these over PYQ-weightage-only guesses when they exist, and when you do pick a
  weakestConcepts/reviewDue/topErrorPatterns entry, say so specifically in "reason" (e.g. "42%
  mastery from last mock" or "3rd CALCULATION error in this concept") rather than a generic
  weak-topic flag
- When a weakestConcepts/reviewDue entry has a non-empty weakPrerequisites list, that concept's
  own failure has been traced to a SPECIFIC weaker prerequisite topic (name given, plus that
  prerequisite's own mastery — null means never attempted). Prefer assigning the prerequisite
  itself before the dependent topic when its masteryPercent is null or clearly lower, and name
  it explicitly in "reason" (e.g. "Rolling Motion attempts are failing — traces to Laws of Motion
  at 38% mastery, fix that first") rather than assigning the dependent topic again
""".trimIndent()

        val prompt = buildString {
            appendLine("Exam: ${config.examType} | Days until exam: $daysLeft")
            appendLine("Subjects (name:weight): ${config.subjects.joinToString { "${it.name}:${it.weightage}" }}")
            appendLine("Study window: ${config.studyStartTime}–${config.studyEndTime} (${studyWindowHours}h)")
            appendLine()
            appendLine("STUDENT PROFILE:")
            appendLine(profile.toPromptContext())
            appendLine()
            if (checkIn != null) {
                appendLine("TODAY'S TOPICS SELECTED:")
                checkIn.todayTopics.forEach { (subj, topic) -> appendLine("  $subj → $topic") }
                appendLine()
            }
            if (coachingContext.isNotBlank()) {
                appendLine("UPCOMING COACHING SCHEDULE:")
                appendLine(coachingContext)
                appendLine()
            }
            if (pyqContext.isNotBlank()) {
                appendLine("PYQ WEIGHTAGE FOR TODAY'S TOPICS:")
                appendLine(pyqContext)
                appendLine()
            }
            if (checklistContext.isNotBlank()) {
                appendLine("TODAY'S CHECKLIST STATUS:")
                appendLine(checklistContext)
                appendLine()
            }
            if (todayContext.isNotBlank()) {
                appendLine("TODAY'S LOGGED UPDATES (from Mentor chat / quick-log):")
                appendLine(todayContext)
                appendLine()
            }
            // Upgrade Blueprint Phase 0 item #2: structured JSON, not a prose sentence — see
            // getBehaviorSnapshot()/BehaviorSnapshot's own doc for why this crosses the
            // psyche->planner boundary as data instead of as text.
            appendLine("BEHAVIOR_METRICS (JSON, deterministic, authoritative):")
            appendLine(Json.encodeToString(behaviorSnapshot))
            appendLine()
            // Upgrade Blueprint Phase 2 wiring: same treatment for real test performance —
            // see buildStudentModelSummary() and the systemPrompt rule above.
            if (studentModelSummary != null) {
                appendLine("STUDENT_MODEL (JSON, deterministic, authoritative — from imported test reports):")
                appendLine(Json.encodeToString(studentModelSummary))
                appendLine()
            }
            appendLine("Generate today's plan.")
        }

        return try {
            val raw = LlmGateway.complete(prompt, systemPrompt)
            if (raw.isBlank()) return emptyList()
            val json  = raw.trim().removePrefix("```json").removeSuffix("```").trim()
            val arr   = JSONArray(json)
            val tasks = mutableListOf<StudyTask>()
            for (i in 0 until arr.length()) {
                tasks.add(arr.getJSONObject(i).toStudyTask())
            }
            Log.d(TAG, "LLM generated ${tasks.size} tasks")
            tasks
        } catch (e: Exception) {
            Log.w(TAG, "LLM plan failed: ${e.message}")
            emptyList()
        }
    }

    /**
     * Upgrade Blueprint Phase 0 item #1 ("Fix planner information loss"): the system
     * prompt above already asks the LLM for sessionType/priority/reason on every task (see
     * its own "Format:"/"Rules:" block), but the parse loop this replaced only ever read
     * subject/topic/durationMinutes back out — sessionType, priority, and reason were
     * generated, then silently thrown away. One canonical JSONObject -> StudyTask mapper,
     * so the field contract exists in exactly one place rather than being re-derived (and
     * re-forgotten) at any future call site that builds a StudyTask from raw LLM plan JSON.
     *
     * Deliberately tolerant of a missing/unrecognized sessionType or priority (optString
     * with a neutral default, not getString) — a malformed field on one task in the array
     * shouldn't fail parsing of the whole plan the way a missing *required* field
     * (subject/topic/durationMinutes, still via getString/getInt) correctly does.
     */
    private fun JSONObject.toStudyTask(): StudyTask {
        val subtopic = if (has("subtopic")) " — ${getString("subtopic")}" else ""
        return StudyTask(
            subject         = getString("subject"),
            topic           = getString("topic") + subtopic,
            durationMinutes = getInt("durationMinutes"),
            priority        = mapLlmPriority(optString("priority", "MEDIUM")),
            taskType        = mapLlmSessionType(optString("sessionType", "")),
            rationale       = optString("reason", "")
        )
    }

    private fun mapLlmPriority(raw: String): Int = when (raw.trim().uppercase()) {
        "HIGH"   -> 3
        "MEDIUM" -> 2
        "LOW"    -> 1
        else     -> 2 // unrecognized/missing — neutral middle, not silently lowest
    }

    // sessionType (LEARN/REVISE/PRACTICE/TEST_PREP, per the systemPrompt above) onto the
    // existing TaskType enum (LECTURE/PRACTICE/REVISION/READING/OTHER) — StudyTask.taskType's
    // own doc already called this mapping out as owed. TEST_PREP has no exact match; PRACTICE
    // is the closest existing semantic (timed/applied work, not new teaching).
    private fun mapLlmSessionType(raw: String): TaskType = when (raw.trim().uppercase()) {
        "LEARN"     -> TaskType.LECTURE
        "REVISE"    -> TaskType.REVISION
        "PRACTICE"  -> TaskType.PRACTICE
        "TEST_PREP" -> TaskType.PRACTICE
        else        -> TaskType.OTHER
    }

    // NOTE: previously took an unused `behaviorSummary: String` param (never referenced in the
    // body below — the actual behavior signal this function reads is CheckmatePrefs'
    // "recent_skip_rate", a couple of lines down). Dropped as dead code while touching this
    // call site for Upgrade Blueprint Phase 0 item #2.
    private fun ruleBasedPlan(
        config: PlannerState,
        daysLeft: Int,
        studyWindowHours: Float,
        // Upgrade Blueprint Phase 2 wiring — see the weakestBySubject block below.
        studentModel: StudentModel
    ): List<StudyTask> {
        val tasks        = mutableListOf<StudyTask>()
        val sorted       = config.subjects.sortedByDescending { it.weightage }
        val maxTasks     = if (daysLeft < 7) 5 else if (daysLeft < 30) 4 else 3
        val baseDuration = if (daysLeft < 30) 30 else 45

        val skipRateStr = CheckmatePrefs.getString("recent_skip_rate", "0") ?: "0"
        val skipRate    = skipRateStr.toFloatOrNull() ?: 0f
        val duration    = when {
            skipRate > 0.5f -> (baseDuration - 15).coerceAtLeast(20)
            skipRate < 0.1f -> (baseDuration + 15).coerceAtMost(60)
            else            -> baseDuration
        }

        // Upgrade Blueprint Phase 2 wiring: per subject, prefer the weakest tracked
        // concept StudentModel already knows about (real measured mastery from
        // imported test results) over blind PYQ-rotation topic selection — same
        // "don't guess what Room already knows" discipline as the LLM path above.
        // A concept only wins here if RetentionEngine.decide() actually flagged it
        // (TEACH/REVIEW) — a MOVE_ON concept (well-mastered, low forgetting risk)
        // falls through to normal rotation even if it happens to be this subject's
        // lowest-mastery row, since "lowest of the well-mastered" isn't a gap.
        // Falls straight through to the pre-existing rotation logic untouched when
        // this subject has no tracked concepts yet (e.g. no report.md imported) —
        // a fresh install behaves exactly as before this change.
        val weakestBySubject: Map<String, ConceptSnapshot> = studentModel.concepts.values
            .filter { it.subject != null }
            .groupBy { it.subject!! }
            .mapValues { (_, snapshots) -> snapshots.minByOrNull { it.mastery }!! } // groupBy guarantees a non-empty list per key

        val dayOfYear = Calendar.getInstance().get(Calendar.DAY_OF_YEAR)
        sorted.take(maxTasks).forEachIndexed { idx: Int, subj: SubjectConfig ->
            val weakest = weakestBySubject.entries
                .firstOrNull { it.key.equals(subj.name, ignoreCase = true) }
                ?.value
                ?.takeIf { it.retentionDecision != RetentionDecisionSnapshot.MOVE_ON }

            val (topic, rationale) = if (weakest != null) {
                val label = weakest.topic ?: weakest.chapter ?: subj.name
                val action = when (weakest.retentionDecision) {
                    RetentionDecisionSnapshot.TEACH  -> "Teach"
                    RetentionDecisionSnapshot.REVIEW -> "Review"
                    RetentionDecisionSnapshot.MOVE_ON -> "Revision" // unreachable, filtered above
                }
                val masteryPct = (weakest.mastery * 100).toInt()
                val reason = buildString {
                    append("Mastery $masteryPct% from imported test results")
                    if (weakest.errorCount > 0) append(", ${weakest.errorCount} recorded error(s)")
                    // Upgrade Blueprint Phase 2 wiring: name the specific weak
                    // prerequisite(s), not just flag that one exists — see
                    // ConceptSnapshot.prerequisiteIssues' CORRECTNESS FIX note.
                    if (weakest.prerequisiteIssues.isNotEmpty()) {
                        val names = weakest.prerequisiteIssues.joinToString {
                            it.topic ?: it.chapter ?: it.subject ?: it.conceptId
                        }
                        append(", traces to weak prerequisite(s): $names")
                    }
                }
                "$action: $label" to reason
            } else {
                val topTopics = PYQWeightage.getTopTopics(config.examType, subj.name, 6)
                val t = if (topTopics.isNotEmpty()) {
                    val picked = topTopics[(dayOfYear + idx) % topTopics.size]
                    if (daysLeft < 30) "Revision: ${picked.first}" else picked.first
                } else {
                    if (daysLeft < 30) "Revision: ${subj.name} Chapter ${(dayOfYear + idx) % 10 + 1}"
                    else "${subj.name} Chapter ${(dayOfYear + idx) % 10 + 1}"
                }
                t to ""
            }

            tasks.add(StudyTask(
                subject         = subj.name,
                topic           = topic,
                durationMinutes = if (idx == 0) duration + 15 else duration,
                priority        = sorted.size - idx,
                rationale       = rationale
            ))
        }
        return tasks
    }

    private fun daysUntilExam(dateStr: String): Int {
        return try {
            val sdf  = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
            val exam = sdf.parse(dateStr) ?: return 365
            val diff = exam.time - System.currentTimeMillis()
            (diff / (1000 * 60 * 60 * 24)).toInt().coerceAtLeast(0)
        } catch (_: Exception) { 365 }
    }

    private fun calculateStudyWindowHours(start: String, end: String): Float {
        return try {
            val (sh, sm) = start.split(":").map { it.toInt() }
            val (eh, em) = end.split(":").map { it.toInt() }
            val startMin = sh * 60 + sm
            val endMin   = eh * 60 + em
            ((endMin - startMin) / 60f).coerceAtLeast(1f)
        } catch (_: Exception) { 8f }
    }
}
