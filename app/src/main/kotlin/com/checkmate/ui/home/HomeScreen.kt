// This is a partial file — only the block containing the fix. Apply as a
// find-and-replace inside your existing HomeScreen.kt; the rest of the file
// is unchanged.
//
// BUG: `.sortedBy { it.scheduledStartTime }` sorts the raw "HH:mm" STRING,
// not the actual time. Lexicographically "18:00" < "1:00" < "22:00" (because
// '8' < ':' as characters), which is exactly the wrong order you saw:
// 18:00, 1:00, 22:00 instead of 1:00, 18:00, 22:00.
//
// FIX: sort by parsed minutes-from-midnight using the existing
// FreeSlotCalculator.parseTimeOrNull() helper (already used elsewhere in
// this file for the same "HH:mm" -> Int conversion), so it's a real
// chronological sort.

    val scheduled   = tasks.filter { !it.scheduledStartTime.isNullOrBlank() }
        .sortedBy { FreeSlotCalculator.parseTimeOrNull(it.scheduledStartTime!!) ?: Int.MAX_VALUE }
    val unscheduled = tasks.filter { it.scheduledStartTime.isNullOrBlank() }
