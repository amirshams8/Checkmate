package com.checkmate.ui.stats

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.checkmate.planner.PlanStore
import com.checkmate.ui.theme.*
import java.util.Calendar

@Composable
fun StatsScreen(navController: NavController? = null, vm: StatsViewModel = viewModel()) {
    val state by vm.state.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(Unit) { vm.loadAppUsage(context) }

    Column(
        modifier            = Modifier.fillMaxSize().background(BgDark).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Spacer(Modifier.height(20.dp))
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            Text("Stats", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = White90)
            Text("Your performance overview", fontSize = 13.sp, color = White60)
        }

        // ── Blueprint 10.3: Focus Score (single 0-100 number) ─────────────────
        Surface(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            shape    = RoundedCornerShape(14.dp),
            color    = BgCard,
            border   = BorderStroke(0.5.dp, White10)
        ) {
            Row(
                modifier          = Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Focus Score", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = White90)
                    Spacer(Modifier.height(2.dp))
                    Text("Work Mode adherence + session completion", fontSize = 11.sp, color = White60)
                }
                Text(
                    "${state.focusScore}",
                    fontSize   = 34.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    color      = focusScoreColor(state.focusScore)
                )
            }
        }

        Row(
            modifier              = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Fix: Icons.Default.LocalFire does not exist — correct name is LocalFireDepartment
            StatTile("Streak", "${state.streakDays}d", Icons.Default.LocalFireDepartment, AccentAmber, Modifier.weight(1f))
            StatTile("Today",  "${state.todayCompletion}%", Icons.Default.Today,     AccentGreen, Modifier.weight(1f))
            StatTile("Week",   "${state.weekCompletion}%",  Icons.Default.DateRange,  AccentBlue,  Modifier.weight(1f))
        }
        Surface(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            shape    = RoundedCornerShape(14.dp),
            color    = BgCard,
            border   = BorderStroke(0.5.dp, White10)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("This Week", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = White90)
                Spacer(Modifier.height(16.dp))
                WeeklyBarChart(state.weeklyData)
            }
        }
        // ── Consistency Calendar (green/yellow/red dots per day) ──────────────
        // "No tasks in plan" now counts as a missed (red) day, not a blank/neutral one —
        // see PlanStore.DayStudyStatus. This is also where an irregular-study streak
        // actually becomes visible to the student, not just implied by silence.
        if (state.consecutiveMissedDays >= 2) {
            Surface(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                shape    = RoundedCornerShape(14.dp),
                color    = AccentRed.copy(alpha = 0.12f),
                border   = BorderStroke(0.5.dp, AccentRed.copy(alpha = 0.4f))
            ) {
                Row(
                    modifier          = Modifier.fillMaxWidth().padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.WarningAmber, null, tint = AccentRed, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "${state.consecutiveMissedDays} days in a row with no plan or nothing completed. " +
                            "Your guardian has been notified.",
                        fontSize = 12.sp, color = White90
                    )
                }
            }
        }
        Surface(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            shape    = RoundedCornerShape(14.dp),
            color    = BgCard,
            border   = BorderStroke(0.5.dp, White10)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier              = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment     = Alignment.CenterVertically
                ) {
                    Text("Consistency", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = White90)
                    Text(state.consistencyMonthLabel, fontSize = 11.sp, color = White60)
                }
                Spacer(Modifier.height(12.dp))
                ConsistencyCalendar(state.consistencyMonth)
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LegendDot(AccentGreen, "Complete")
                    Spacer(Modifier.width(14.dp))
                    LegendDot(AccentAmber, "Partial")
                    Spacer(Modifier.width(14.dp))
                    LegendDot(AccentRed, "Missed")
                }
            }
        }
        Surface(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            shape    = RoundedCornerShape(14.dp),
            color    = BgCard,
            border   = BorderStroke(0.5.dp, White10)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("By Subject", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = White90)
                Spacer(Modifier.height(12.dp))
                state.subjectStats.forEach { (subject, pct) ->
                    SubjectBar(subject, pct)
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
        Surface(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            shape    = RoundedCornerShape(14.dp),
            color    = BgCard,
            border   = BorderStroke(0.5.dp, White10)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Attention Quality", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = White90)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    AttentionStat("Checks Passed", "${state.attentionChecksPassed}")
                    AttentionStat("Checks Missed", "${state.attentionChecksMissed}")
                    AttentionStat("Avg Focus",     "${state.avgFocusMinutes}m")
                }

                // ── Blueprint 2.6: actual focus time + pause count/rate ────────
                Spacer(Modifier.height(14.dp))
                HorizontalDivider(color = White10, thickness = 0.5.dp)
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    AttentionStat("Focus Today",  "${state.actualFocusMinutesToday}m")
                    AttentionStat("Pauses/Sesh",  "%.1f".format(state.avgPausesPerSession))
                    AttentionStat("Pause Rate",   "${state.pauseRatePercent}%")
                }
            }
        }

        // ── App Usage Today (Digital Wellbeing-style) ─────────────────────────
        Surface(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            shape    = RoundedCornerShape(14.dp),
            color    = BgCard,
            border   = BorderStroke(0.5.dp, White10)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier              = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment     = Alignment.CenterVertically
                ) {
                    Text("App Usage Today", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = White90)
                    if (state.hasUsageAccess) {
                        Text(
                            formatMinutes(state.totalScreenMinutesToday),
                            fontSize = 13.sp, color = AccentGreen, fontFamily = FontFamily.Monospace
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))

                if (!state.hasUsageAccess) {
                    Text(
                        "Grant Usage Access in Settings → Permissions to see app usage history.",
                        fontSize = 12.sp, color = White60
                    )
                } else if (state.appUsageToday.isEmpty()) {
                    Text("No significant usage recorded yet today.", fontSize = 12.sp, color = White60)
                } else {
                    val maxMinutes = state.appUsageToday.maxOf { it.second }.coerceAtLeast(1)
                    state.appUsageToday.forEach { (label, minutes) ->
                        UsageBar(label, minutes, maxMinutes)
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }
        }

        // ── Screen Time History (7 days) ──────────────────────────────────────
        if (state.hasUsageAccess && state.screenTimeHistory.isNotEmpty()) {
            Surface(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                shape    = RoundedCornerShape(14.dp),
                color    = BgCard,
                border   = BorderStroke(0.5.dp, White10)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Screen Time — Last 7 Days", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = White90)
                    Spacer(Modifier.height(16.dp))
                    ScreenTimeBarChart(state.screenTimeHistory)
                }
            }
        }

        // ── Testmate — take a test / view a result ────────────────────────────
        if (navController != null) {
            Surface(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                shape    = RoundedCornerShape(14.dp),
                color    = BgCard,
                border   = BorderStroke(0.5.dp, White10),
                onClick  = { navController.navigate("test_web") }
            ) {
                Row(
                    modifier          = Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Quiz, null, tint = AccentBlue, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Test Platform", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = White90)
                        Text("Take a test on Testmate", fontSize = 11.sp, color = White60)
                    }
                    Icon(Icons.Default.ChevronRight, null, tint = White30, modifier = Modifier.size(18.dp))
                }
            }
            Spacer(Modifier.height(8.dp))
            Surface(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                shape    = RoundedCornerShape(14.dp),
                color    = BgCard,
                border   = BorderStroke(0.5.dp, White10),
                onClick  = { navController.navigate("test_results") }
            ) {
                Row(
                    modifier          = Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.BarChart, null, tint = AccentGreen, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Test Results", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = White90)
                        Text("Look up a session result by ID", fontSize = 11.sp, color = White60)
                    }
                    Icon(Icons.Default.ChevronRight, null, tint = White30, modifier = Modifier.size(18.dp))
                }
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun StatTile(label: String, value: String, icon: androidx.compose.ui.graphics.vector.ImageVector, color: Color, modifier: Modifier) {
    Surface(modifier = modifier, shape = RoundedCornerShape(12.dp), color = BgCard, border = BorderStroke(0.5.dp, White10)) {
        Column(modifier = Modifier.padding(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null, tint = color, modifier = Modifier.size(22.dp))
            Spacer(Modifier.height(6.dp))
            Text(value, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = color)
            Text(label, fontSize = 11.sp, color = White60)
        }
    }
}

@Composable
private fun WeeklyBarChart(data: List<Pair<String, Int>>) {
    val maxVal = data.maxOfOrNull { it.second }?.coerceAtLeast(1) ?: 1
    Row(
        modifier              = Modifier.fillMaxWidth().height(100.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment     = Alignment.Bottom
    ) {
        data.forEach { (day, pct) ->
            Column(
                modifier            = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Bottom
            ) {
                Box(
                    modifier         = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 4.dp),
                    contentAlignment = Alignment.BottomCenter
                ) {
                    val barHeight = (pct.toFloat() / maxVal) * 80f
                    Box(
                        modifier = Modifier
                            .width(28.dp)
                            .height(barHeight.coerceAtLeast(4f).dp)
                            .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                            .background(if (pct >= 70) AccentGreen else if (pct >= 40) AccentAmber else AccentRed)
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(day, fontSize = 10.sp, color = White60, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

@Composable
private fun SubjectBar(subject: String, pct: Int) {
    Column {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(subject, fontSize = 13.sp, color = White90)
            Text("$pct%", fontSize = 13.sp, color = White60, fontFamily = FontFamily.Monospace)
        }
        Spacer(Modifier.height(4.dp))
        LinearProgressIndicator(
            progress   = { pct / 100f },
            modifier   = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)),
            color      = AccentGreen,
            trackColor = White10
        )
    }
}

@Composable
private fun AttentionStat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = White90)
        Text(label, fontSize = 11.sp, color = White60)
    }
}

// ── App usage helpers ─────────────────────────────────────────────────────────

@Composable
private fun UsageBar(label: String, minutes: Int, maxMinutes: Int) {
    Column {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, fontSize = 13.sp, color = White90, maxLines = 1)
            Text(formatMinutes(minutes), fontSize = 12.sp, color = White60, fontFamily = FontFamily.Monospace)
        }
        Spacer(Modifier.height(4.dp))
        LinearProgressIndicator(
            progress   = { (minutes.toFloat() / maxMinutes.toFloat()).coerceIn(0f, 1f) },
            modifier   = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)),
            color      = AccentBlue,
            trackColor = White10
        )
    }
}

@Composable
private fun ScreenTimeBarChart(data: List<Pair<String, Int>>) {
    val maxVal = data.maxOfOrNull { it.second }?.coerceAtLeast(1) ?: 1
    Row(
        modifier              = Modifier.fillMaxWidth().height(100.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment     = Alignment.Bottom
    ) {
        data.forEach { (day, minutes) ->
            Column(
                modifier            = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Bottom
            ) {
                Box(
                    modifier         = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 4.dp),
                    contentAlignment = Alignment.BottomCenter
                ) {
                    val barHeight = (minutes.toFloat() / maxVal) * 80f
                    Box(
                        modifier = Modifier
                            .width(28.dp)
                            .height(barHeight.coerceAtLeast(4f).dp)
                            .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                            .background(AccentBlue)
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(day, fontSize = 10.sp, color = White60, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

// ── Consistency calendar (green/yellow/red dots, GitHub-contribution-grid style) ───────────

@Composable
private fun ConsistencyCalendar(month: Map<Int, PlanStore.DayStudyStatus>) {
    if (month.isEmpty()) return

    // Figure out which weekday day 1 of the month falls on, so the grid lines up under the
    // right weekday header instead of always starting in the Monday column.
    val cal = Calendar.getInstance()
    val today = cal.get(Calendar.DAY_OF_MONTH)
    cal.set(Calendar.DAY_OF_MONTH, 1)
    // Calendar.DAY_OF_WEEK: SUNDAY=1..SATURDAY=7. Convert to a Monday-first 0..6 offset.
    val firstWeekday = (cal.get(Calendar.DAY_OF_WEEK) + 5) % 7
    val daysInMonth = month.size

    Column {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf("M", "T", "W", "T", "F", "S", "S").forEach {
                Text(
                    it, fontSize = 10.sp, color = White30,
                    modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        }
        Spacer(Modifier.height(6.dp))

        var day = 1
        val totalCells = firstWeekday + daysInMonth
        val rows = (totalCells + 6) / 7
        repeat(rows) { rowIndex ->
            Row(modifier = Modifier.fillMaxWidth()) {
                repeat(7) { col ->
                    val cellIndex = rowIndex * 7 + col
                    Box(
                        modifier         = Modifier.weight(1f).aspectRatio(1f).padding(2.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        if (cellIndex >= firstWeekday && day <= daysInMonth) {
                            val status = month[day] ?: PlanStore.DayStudyStatus.FUTURE
                            val isToday = day == today
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(dayStatusColor(status))
                                    .then(
                                        if (isToday) Modifier.border(1.dp, White60, RoundedCornerShape(6.dp))
                                        else Modifier
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    "$day",
                                    fontSize = 9.sp,
                                    color    = if (status == PlanStore.DayStudyStatus.FUTURE) White60 else Color.Black,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                            day++
                        }
                    }
                }
            }
        }
    }
}

private fun dayStatusColor(status: PlanStore.DayStudyStatus): Color = when (status) {
    PlanStore.DayStudyStatus.COMPLETE -> AccentGreen
    PlanStore.DayStudyStatus.PARTIAL  -> AccentAmber
    PlanStore.DayStudyStatus.MISSED   -> AccentRed
    PlanStore.DayStudyStatus.FUTURE   -> White10
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(9.dp).clip(RoundedCornerShape(3.dp)).background(color))
        Spacer(Modifier.width(6.dp))
        Text(label, fontSize = 11.sp, color = White60)
    }
}

private fun focusScoreColor(score: Int): Color = when {
    score >= 70 -> AccentGreen
    score >= 40 -> AccentAmber
    else        -> AccentRed
}

private fun formatMinutes(totalMinutes: Int): String {
    val h = totalMinutes / 60
    val m = totalMinutes % 60
    return if (h > 0) "${h}h ${m}m" else "${m}m"
}
