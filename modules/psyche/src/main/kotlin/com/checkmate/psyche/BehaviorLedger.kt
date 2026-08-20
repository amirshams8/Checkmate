package com.checkmate.psyche

import android.content.Context
import android.util.Log
import com.checkmate.core.BehaviorSnapshot
import com.checkmate.core.CheckmatePrefs
import com.checkmate.planner.model.StudyTask
import com.checkmate.planner.model.TaskState
import com.checkmate.psyche.db.BehaviorDatabase
import com.checkmate.psyche.db.BehaviorEventEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.util.Calendar

@Serializable
data class TaskEvent(
    val subject:         String,
    val topic:           String,
    val state:           String,   // DONE / SKIPPED
    val timestamp:       Long,
    val focusMinutes:    Int = 0,
    val checksPassed:    Int = 0,
    val checksMissed:    Int = 0,
    // Mentor v2 (spec 2.2):
    val taskType:        String  = "OTHER", // mirrors StudyTask.TaskType.name — lets skip patterns be
                                             // queried per task-type (e.g. "PRACTICE skips in Physics")
                                             // rather than only aggregate skip rate.
    val distractionApp:  String? = null     // label of the app foregrounded right after this skip, if any.
                                             // Populated by the caller (HomeViewModel.markSkip) so guardian
                                             // alerts can say what caused the skip, not just that it happened.
)

data class AttentionStats(
    val checksPassed:    Int,
    val checksMissed:    Int,
    val avgFocusMinutes: Int
)

/**
 * Upgrade Blueprint Phase 0 item #3 ("Confirm Room is single source of truth").
 *
 * Storage moved from a single CheckmatePrefs JSON-blob string (rewritten whole on
 * every record() call, no query surface beyond "load everything, filter in
 * Kotlin") onto Room (BehaviorDatabase) — see that class's doc for the full
 * rationale. Room is now the durable source of truth; `cache` below is an
 * in-memory mirror only, kept so every existing synchronous caller (WorkModeManager,
 * PsycheEngine, HomeViewModel, BehaviorLedgerContextSource) keeps working exactly
 * as before — none of them needed to become suspend functions for this migration.
 */
object BehaviorLedger {

    private const val TAG = "BehaviorLedger"
    // Legacy CheckmatePrefs key — read exactly once, by migrateLegacyPrefsIfEmpty(),
    // to import pre-upgrade history into Room. Never written to again after this
    // change; kept around only as the migration source, not a live store.
    private const val LEGACY_KEY_EVENTS = "behavior_events"
    private const val MAX_EVENTS = 200
    private const val PATTERN_SKIP_THRESHOLD = 3

    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var db: BehaviorDatabase? = null
    // Oldest-first, capped at MAX_EVENTS — same ordering/cap contract the old
    // CheckmatePrefs blob had, so every read method below is byte-for-byte
    // identical in behavior regardless of which storage engine backs it.
    private val cache: MutableList<TaskEvent> = mutableListOf()
    @Volatile private var initialized = false

    /**
     * Must be called once at app startup (CheckmateApp.onCreate(), alongside
     * CheckmatePrefs.init() — same pattern). Opens Room, imports the legacy
     * CheckmatePrefs blob on first run after this upgrade if one exists, then
     * loads the in-memory cache from Room.
     *
     * Deliberately blocks the caller via runBlocking rather than firing an
     * async load: Application.onCreate() already runs CheckmatePrefs.init()
     * synchronously right before this, and every read method on this object
     * needs the cache populated immediately (WorkModeManager and others may
     * call BehaviorLedger getters before any coroutine would have finished
     * loading). At most 200 rows, so the one-time cost at process start is
     * negligible — the same trade-off CheckmatePrefs.init() already makes.
     */
    fun init(context: Context) {
        if (initialized) return
        val database = BehaviorDatabase.getInstance(context)
        db = database
        runBlocking(Dispatchers.IO) {
            migrateLegacyPrefsIfEmpty(context, database)
            val loaded = database.behaviorEventDao().getAll().takeLast(MAX_EVENTS).map { it.toTaskEvent() }
            synchronized(cache) {
                cache.clear()
                cache.addAll(loaded)
            }
        }
        initialized = true
        Log.d(TAG, "BehaviorLedger initialized, ${cache.size} events loaded from Room")
    }

    private suspend fun migrateLegacyPrefsIfEmpty(context: Context, database: BehaviorDatabase) {
        val dao = database.behaviorEventDao()
        if (dao.count() > 0) return // already migrated (or genuinely started empty on Room from day one)
        val legacyRaw = CheckmatePrefs.getString(LEGACY_KEY_EVENTS, null) ?: return
        val legacyEvents = try {
            json.decodeFromString<List<TaskEvent>>(legacyRaw)
        } catch (e: Exception) {
            Log.w(TAG, "Legacy behavior_events blob unreadable, skipping migration: ${e.message}")
            emptyList()
        }
        if (legacyEvents.isEmpty()) return
        legacyEvents.forEach { dao.insert(it.toEntity()) }
        Log.d(TAG, "Migrated ${legacyEvents.size} legacy events from CheckmatePrefs into Room")
    }

    // distractionApp is optional and only meaningful for SKIPPED events — callers recording a
    // DONE task can omit it. taskType defaults to task.taskType.name so existing call sites
    // (PsycheEngine.onTaskCompleted/onTaskSkipped) don't need to change at all.
    fun record(
        task: StudyTask,
        state: TaskState,
        checksPassed: Int = 0,
        checksMissed: Int = 0,
        distractionApp: String? = null
    ) {
        val event = TaskEvent(
            subject         = task.subject,
            topic           = task.topic,
            state           = state.name,
            timestamp       = System.currentTimeMillis(),
            focusMinutes    = task.focusMinutes,
            checksPassed    = checksPassed,
            checksMissed    = checksMissed,
            taskType        = task.taskType.name,
            distractionApp  = distractionApp
        )
        // Update the in-memory cache immediately so any synchronous read that
        // happens right after record() (same call stack, e.g. HomeViewModel
        // calling markDone() then immediately refreshing behavior-derived UI)
        // sees it — Room write below happens on a background dispatcher and is
        // the durable copy, but callers were never asynchronous before this
        // change and shouldn't have to become so now.
        synchronized(cache) {
            cache.add(event)
            if (cache.size > MAX_EVENTS) cache.removeAt(0)
        }
        val database = db
        if (database != null) {
            scope.launch {
                database.behaviorEventDao().insert(event.toEntity())
                database.behaviorEventDao().trimToNewest(MAX_EVENTS)
            }
        } else {
            Log.w(TAG, "record() called before BehaviorLedger.init() — event kept in cache only, not persisted")
        }
    }

    fun getSkipCountForSubject(subject: String, withinDays: Int = 7): Int {
        val cutoff = System.currentTimeMillis() - withinDays * 24 * 60 * 60 * 1000L
        return getEvents().count { it.subject == subject && it.state == "SKIPPED" && it.timestamp > cutoff }
    }

    /**
     * Mentor v2 (spec 2.2): skip count scoped to a specific task type within a subject —
     * e.g. getSkipCountByType("Physics", "PRACTICE", 7) answers "how many times has the
     * student skipped Physics *practice* tasks specifically this week," which the aggregate
     * getSkipCountForSubject() can't distinguish from skipped lectures/revision.
     */
    fun getSkipCountByType(subject: String, taskType: String, withinDays: Int = 7): Int {
        val cutoff = System.currentTimeMillis() - withinDays * 24 * 60 * 60 * 1000L
        return getEvents().count {
            it.subject == subject && it.taskType == taskType && it.state == "SKIPPED" && it.timestamp > cutoff
        }
    }

    fun getTotalSkipCount(withinDays: Int = 7): Int {
        val cutoff = System.currentTimeMillis() - withinDays * 24 * 60 * 60 * 1000L
        return getEvents().count { it.state == "SKIPPED" && it.timestamp > cutoff }
    }

    fun getRecentSkipRate(): Float {
        val recent = getEvents().takeLast(20)
        if (recent.isEmpty()) return 0f
        return recent.count { it.state == "SKIPPED" }.toFloat() / recent.size
    }

    fun getStreakDays(): Int {
        val events = getEvents().sortedByDescending { it.timestamp }
        var streak = 0
        val cal    = Calendar.getInstance()
        for (i in 0..30) {
            val dayStart = cal.clone() as Calendar
            dayStart.set(Calendar.HOUR_OF_DAY, 0)
            dayStart.set(Calendar.MINUTE, 0)
            dayStart.set(Calendar.SECOND, 0)
            val dayEnd = cal.clone() as Calendar
            dayEnd.set(Calendar.HOUR_OF_DAY, 23)
            dayEnd.set(Calendar.MINUTE, 59)

            val dayEvents = events.filter { it.timestamp in dayStart.timeInMillis..dayEnd.timeInMillis }
            if (dayEvents.isEmpty() && i > 0) break
            if (dayEvents.any { it.state == "DONE" }) streak++
            cal.add(Calendar.DAY_OF_YEAR, -1)
        }
        return streak
    }

    // Kept as-is: this is legitimate LLM-facing prose (fed into PsycheEngine's morning
    // message / weekly report generation, both user- or parent-facing tone/text, not a
    // planning decision) — outside Phase 0 item #2's scope, which targets the planner's
    // decision pipeline specifically. See getSnapshot() below for the structured
    // equivalent AdaptivePlanner now uses instead of this.
    fun getSummaryForPlanner(): String {
        val skipRate = getRecentSkipRate()
        val streak   = getStreakDays()
        return "Streak: ${streak}d, Recent skip rate: ${(skipRate * 100).toInt()}%, Total skips (7d): ${getTotalSkipCount()}"
    }

    /**
     * Mentor v2 (spec 3.7): same-day DONE tasks, most recent first. Kept for its existing
     * consumer, BehaviorLedgerContextSource (intervention-context pipeline) — see
     * getSnapshot().todayCompleted for the structured equivalent AdaptivePlanner now reads.
     */
    fun getTodayCompletedSummary(): String {
        val startOfDay = todayStartMillis()
        val todayDone = getEvents()
            .filter { it.state == "DONE" && it.timestamp >= startOfDay }
            .sortedByDescending { it.timestamp }
        if (todayDone.isEmpty()) return ""
        return todayDone.joinToString("\n") { "  ${it.subject}: ${it.topic} (${it.taskType})" }
    }

    fun getAttentionStats(): AttentionStats {
        val events = getEvents().filter { it.checksPassed + it.checksMissed > 0 }
        val passed = events.sumOf { it.checksPassed }
        val missed = events.sumOf { it.checksMissed }
        val avgFocus = if (events.isEmpty()) 0 else events.sumOf { it.focusMinutes } / events.size
        return AttentionStats(passed, missed, avgFocus)
    }

    /**
     * Upgrade Blueprint Phase 0 item #2 ("Stop treating the LLM as source of truth").
     * The structured, deterministic replacement for what AdaptivePlanner used to read as
     * a hand-built prose string (getSummaryForPlanner() + getTodayCompletedSummary()
     * concatenated together in PsycheEngine.refreshBehaviorSummaryCache()). Every field
     * here is a plain computed number or list — nothing is LLM output, nothing is free
     * text — so it can be serialized straight to JSON for the planner's prompt instead of
     * pre-digested into a sentence.
     */
    fun getSnapshot(): BehaviorSnapshot {
        val events = getEvents()
        val attn = getAttentionStats()
        val cutoff = System.currentTimeMillis() - 7 * 24 * 60 * 60 * 1000L
        val startOfDay = todayStartMillis()

        val todayCompleted = events
            .filter { it.state == "DONE" && it.timestamp >= startOfDay }
            .sortedByDescending { it.timestamp }
            .map { BehaviorSnapshot.CompletedItem(it.subject, it.topic, it.taskType) }

        val patterns = events
            .filter { it.state == "SKIPPED" && it.timestamp > cutoff }
            .groupBy { it.subject to it.taskType }
            .mapNotNull { (key, group) ->
                if (group.size >= PATTERN_SKIP_THRESHOLD) {
                    BehaviorSnapshot.SkipPattern(key.first, key.second, group.size)
                } else null
            }

        return BehaviorSnapshot(
            streakDays              = getStreakDays(),
            recentSkipRatePercent   = (getRecentSkipRate() * 100).toInt(),
            totalSkips7d            = getTotalSkipCount(),
            attentionChecksPassed   = attn.checksPassed,
            attentionChecksMissed   = attn.checksMissed,
            avgFocusMinutes         = attn.avgFocusMinutes,
            todayCompleted          = todayCompleted,
            subjectPatterns         = patterns
        )
    }

    private fun todayStartMillis(): Long =
        Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    private fun getEvents(): List<TaskEvent> = synchronized(cache) { cache.toList() }

    private fun TaskEvent.toEntity() = BehaviorEventEntity(
        subject = subject, topic = topic, state = state, timestamp = timestamp,
        focusMinutes = focusMinutes, checksPassed = checksPassed, checksMissed = checksMissed,
        taskType = taskType, distractionApp = distractionApp
    )

    private fun BehaviorEventEntity.toTaskEvent() = TaskEvent(
        subject = subject, topic = topic, state = state, timestamp = timestamp,
        focusMinutes = focusMinutes, checksPassed = checksPassed, checksMissed = checksMissed,
        taskType = taskType, distractionApp = distractionApp
    )
}
