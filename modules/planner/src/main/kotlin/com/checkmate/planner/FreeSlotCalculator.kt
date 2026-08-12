package com.checkmate.planner

import com.checkmate.core.TimeSlot

/**
 * FreeSlotCalculator — Blueprint 4.1: computes today's free study windows as
 * (study window) minus (ConsultationProfile.blockedSlots), e.g. school/coaching
 * hours. Pure function, no I/O — AdaptivePlanner feeds it profile.blockedSlots
 * plus PlannerState.studyStartTime/studyEndTime and uses the result to assign
 * each generated StudyTask a scheduledStartTime (see assignScheduledTimes()
 * in AdaptivePlanner.kt).
 *
 * All times are handled as minutes-from-midnight (0-1439) internally so slot
 * math is plain integer arithmetic instead of repeated string parsing.
 */
object FreeSlotCalculator {

    /** A contiguous free window today, in minutes-from-midnight. */
    data class FreeSlot(val startMinute: Int, val endMinute: Int) {
        val durationMinutes: Int get() = (endMinute - startMinute).coerceAtLeast(0)
    }

    /**
     * Blueprint 4.1: studyWindow minus all blockedSlots, merging any overlapping
     * blocked ranges first so back-to-back or overlapping entries (e.g. school
     * ending late, coaching starting early) don't produce a spurious sliver of
     * "free" time between them.
     *
     * Blocked slots outside the study window are clipped to it; blocked slots
     * with unparseable or inverted (end <= start) times are dropped rather than
     * corrupting the rest of the computation — a single bad entry in the
     * student's profile shouldn't zero out the whole day's schedule.
     */
    fun computeFreeSlots(
        blockedSlots:   List<TimeSlot>,
        studyStartTime: String,
        studyEndTime:   String
    ): List<FreeSlot> {
        val windowStart = parseMinutesOrNull(studyStartTime) ?: DEFAULT_WINDOW_START
        val windowEnd   = parseMinutesOrNull(studyEndTime) ?: DEFAULT_WINDOW_END
        if (windowEnd <= windowStart) return emptyList()

        val blocked = blockedSlots
            .mapNotNull { slot ->
                val s = parseMinutesOrNull(slot.startTime) ?: return@mapNotNull null
                val e = parseMinutesOrNull(slot.endTime) ?: return@mapNotNull null
                if (e <= s) return@mapNotNull null
                val clippedStart = s.coerceIn(windowStart, windowEnd)
                val clippedEnd   = e.coerceIn(windowStart, windowEnd)
                if (clippedEnd <= clippedStart) null else clippedStart to clippedEnd
            }
            .sortedBy { it.first }
            .let { mergeOverlapping(it) }

        val free = mutableListOf<FreeSlot>()
        var cursor = windowStart
        for ((bStart, bEnd) in blocked) {
            if (bStart > cursor) free.add(FreeSlot(cursor, bStart))
            cursor = maxOf(cursor, bEnd)
        }
        if (cursor < windowEnd) free.add(FreeSlot(cursor, windowEnd))
        return free.filter { it.durationMinutes > 0 }
    }

    private fun mergeOverlapping(ranges: List<Pair<Int, Int>>): List<Pair<Int, Int>> {
        if (ranges.isEmpty()) return ranges
        val merged = mutableListOf(ranges.first())
        for (r in ranges.drop(1)) {
            val last = merged.last()
            if (r.first <= last.second) merged[merged.size - 1] = last.first to maxOf(last.second, r.second)
            else merged.add(r)
        }
        return merged
    }

    private fun parseMinutesOrNull(hhmm: String): Int? = try {
        val parts = hhmm.trim().split(":")
        val h = parts[0].trim().toInt()
        val m = parts[1].trim().toInt()
        if (h !in 0..23 || m !in 0..59) null else h * 60 + m
    } catch (_: Exception) { null }

    /** Public wrapper around parseMinutesOrNull — lets callers outside this object (e.g.
     *  HomeViewModel.findNextFreeSlot) turn a task's existing "HH:mm" scheduledStartTime
     *  back into minutes-from-midnight without duplicating the parsing rules here. */
    fun parseTimeOrNull(hhmm: String): Int? = parseMinutesOrNull(hhmm)

    /** Formats a minutes-from-midnight value back to "HH:mm" for StudyTask.scheduledStartTime. */
    fun formatMinutes(totalMinutes: Int): String {
        val clamped = totalMinutes.coerceIn(0, 24 * 60 - 1)
        val h = clamped / 60
        val m = clamped % 60
        return "%02d:%02d".format(h, m)
    }

    /**
     * Carves already-occupied (startMinute, endMinute) ranges — e.g. tasks today's plan
     * has already scheduled — out of a set of free slots. Same clip-and-merge approach as
     * computeFreeSlots() uses for blockedSlots, just applied to StudyTask occupancy instead
     * of ConsultationProfile entries, so HomeViewModel.addCustomTask can find room for a
     * newly-added task without double-booking a slot AdaptivePlanner already used.
     */
    fun subtractOccupied(freeSlots: List<FreeSlot>, occupied: List<Pair<Int, Int>>): List<FreeSlot> {
        if (occupied.isEmpty()) return freeSlots
        val merged = occupied.sortedBy { it.first }.let { mergeOverlapping(it) }
        val result = mutableListOf<FreeSlot>()
        for (slot in freeSlots) {
            var cursor = slot.startMinute
            for ((oStart, oEnd) in merged) {
                val s = oStart.coerceIn(slot.startMinute, slot.endMinute)
                val e = oEnd.coerceIn(slot.startMinute, slot.endMinute)
                if (e <= s) continue
                if (s > cursor) result.add(FreeSlot(cursor, s))
                cursor = maxOf(cursor, e)
            }
            if (cursor < slot.endMinute) result.add(FreeSlot(cursor, slot.endMinute))
        }
        return result.filter { it.durationMinutes > 0 }
    }

    /**
     * First-fit: the earliest free slot (slots are expected in chronological order, as
     * computeFreeSlots()/subtractOccupied() already return them) with at least
     * durationMinutes of room, returned as a minutes-from-midnight start. Null means
     * nothing today fits — callers should leave StudyTask.scheduledStartTime null rather
     * than fabricate a time, same contract as AdaptivePlanner.assignScheduledTimes().
     */
    fun firstFitStart(durationMinutes: Int, freeSlots: List<FreeSlot>): Int? =
        freeSlots.firstOrNull { it.durationMinutes >= durationMinutes }?.startMinute

    private const val DEFAULT_WINDOW_START = 9 * 60   // 09:00 — mirrors PlannerState's default
    private const val DEFAULT_WINDOW_END   = 21 * 60  // 21:00 — mirrors PlannerState's default
}
