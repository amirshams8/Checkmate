package com.checkmate.ui.home

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
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
import com.checkmate.planner.model.StudyTask
import com.checkmate.planner.model.TaskState
import com.checkmate.ui.theme.*

@Composable
fun HomeScreen(navController: NavController, vm: HomeViewModel = viewModel()) {
    val state by vm.state.collectAsState()
    val context = LocalContext.current

    Box(modifier = Modifier.fillMaxSize().background(BgDark)) {
        LazyColumn(
            modifier            = Modifier.fillMaxSize(),
            contentPadding      = PaddingValues(horizontal = 16.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                HomeHeader(
                    completedCount = state.completedToday,
                    totalCount     = state.tasks.size,
                    streakDays     = state.streakDays
                )
            }
            item {
                DayProgressBar(completed = state.completedToday, total = state.tasks.size)
            }
            if (state.psycheMessage.isNotBlank()) {
                item { PsycheMessageCard(message = state.psycheMessage) }
            }
            if (state.tasks.isEmpty()) {
                item { EmptyPlanCard(onPlan = { navController.navigate("planner") }) }
            } else {
                items(state.tasks, key = { it.id }) { task ->
                    TaskCard(
                        task     = task,
                        isActive = state.activeTaskId == task.id,
                        onStart  = { vm.startTask(context, task) },
                        onDone   = { vm.markDone(context, task) },
                        onSkip   = { vm.markSkip(context, task) }
                    )
                }
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

@Composable
private fun HomeHeader(completedCount: Int, totalCount: Int, streakDays: Int) {
    Row(
        modifier              = Modifier.fillMaxWidth(),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column {
            Text("Today", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = White90)
            Text("$completedCount of $totalCount tasks done", fontSize = 14.sp, color = White60)
        }
        if (streakDays > 0) {
            Surface(shape = RoundedCornerShape(20.dp), color = AccentGreen.copy(alpha = 0.12f)) {
                Row(
                    modifier          = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.LocalFireDepartment, null, tint = AccentAmber, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("$streakDays day streak", fontSize = 12.sp, color = AccentAmber, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun DayProgressBar(completed: Int, total: Int) {
    val progress = if (total == 0) 0f else completed.toFloat() / total.toFloat()
    val animatedProgress by animateFloatAsState(
        targetValue   = progress,
        animationSpec = tween(600, easing = FastOutSlowInEasing),
        label         = "progress"
    )
    Column {
        LinearProgressIndicator(
            progress   = { animatedProgress },
            modifier   = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
            color      = AccentGreen,
            trackColor = White10,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text       = "${(progress * 100).toInt()}% complete",
            fontSize   = 11.sp,
            color      = White30,
            fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
private fun PsycheMessageCard(message: String) {
    Surface(
        shape  = RoundedCornerShape(10.dp),
        color  = BgCardAlt,
        border = BorderStroke(0.5.dp, AccentBlue.copy(alpha = 0.3f))
    ) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Psychology, null, tint = AccentBlue, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Text(message, fontSize = 13.sp, color = White90, lineHeight = 18.sp)
        }
    }
}

@Composable
private fun TaskCard(
    task: StudyTask,
    isActive: Boolean,
    onStart: () -> Unit,
    onDone: () -> Unit,
    onSkip: () -> Unit
) {
    val borderColor = when {
        isActive                        -> AccentGreen
        task.state == TaskState.DONE    -> AccentGreen.copy(alpha = 0.3f)
        task.state == TaskState.SKIPPED -> AccentRed.copy(alpha = 0.3f)
        else                            -> White10
    }

    Surface(
        shape  = RoundedCornerShape(14.dp),
        color  = if (isActive) BgCardAlt else BgCard,
        border = BorderStroke(1.dp, borderColor)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {

            // ── Header row ────────────────────────────────────────────────
            Row(
                modifier              = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment     = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(subjectColor(task.subject)))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text          = task.subject.uppercase(),
                        fontSize      = 11.sp,
                        fontWeight    = FontWeight.Bold,
                        letterSpacing = 2.sp,
                        color         = subjectColor(task.subject),
                        fontFamily    = FontFamily.Monospace
                    )
                }
                Text("${task.durationMinutes}m", fontSize = 12.sp, color = White60, fontFamily = FontFamily.Monospace)
            }

            Spacer(Modifier.height(8.dp))

            // ── Topic ─────────────────────────────────────────────────────
            Text(
                text       = task.topic,
                fontSize   = 17.sp,
                fontWeight = FontWeight.SemiBold,
                color      = if (task.state == TaskState.DONE || task.state == TaskState.SKIPPED) White60 else White90
            )

            // ── Action area ───────────────────────────────────────────────
            // FIX: The original code used `return@Column` to bail out early when
            // a task was DONE or SKIPPED. This caused two crashes confirmed in logcat:
            //
            //   PID 29484 @ 02:26:09 — IndexOutOfBoundsException: Index -1 out of
            //   bounds for length 0 at androidx.compose.runtime.Stack.pop (Stack.kt:26)
            //   via ComposerImpl.exitGroup → end → endGroup → endRoot
            //
            //   PID 15159 @ 02:26:29 — ArrayIndexOutOfBoundsException: length=0;
            //   index=-5 at SlotReader.groupKey (SlotTable.kt:957) inside LazyList
            //   measurement (LazyListMeasure.kt:195)
            //
            // Root cause: `return@Column` exits the Column lambda early, leaving
            // Compose's internal slot table with unmatched group push/pop entries.
            // On the next recomposition or LazyColumn re-measure the runtime tries
            // to pop a group that was never pushed → crash.
            //
            // Fix: replace early-return with if/else so every code path emits the
            // same number of Compose group open/close pairs.
            if (task.state == TaskState.DONE || task.state == TaskState.SKIPPED) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text     = if (task.state == TaskState.DONE) "✓ Completed" else "✗ Skipped",
                    fontSize = 12.sp,
                    color    = if (task.state == TaskState.DONE) AccentGreen else AccentRed
                )
            } else {
                Spacer(Modifier.height(12.dp))

                if (isActive) {
                    // Running task → Done + Skip
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick  = onDone,
                            colors   = ButtonDefaults.buttonColors(containerColor = AccentGreen),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Check, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Done", fontWeight = FontWeight.Bold)
                        }
                        OutlinedButton(
                            onClick = onSkip,
                            border  = BorderStroke(1.dp, AccentRed.copy(alpha = 0.5f)),
                            colors  = ButtonDefaults.outlinedButtonColors(contentColor = AccentRed)
                        ) { Text("Skip") }
                    }
                } else {
                    // FIX: Previously Skip was only shown when isActive=true, so tapping
                    // Skip on a PENDING task did nothing (button wasn't even rendered).
                    // Now PENDING tasks show Start + Skip side by side.
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick  = onStart,
                            colors   = ButtonDefaults.buttonColors(containerColor = AccentGreen),
                            modifier = Modifier.weight(1f),
                            enabled  = task.state == TaskState.PENDING
                        ) {
                            Icon(Icons.Default.PlayArrow, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Start Now", fontWeight = FontWeight.Bold)
                        }
                        OutlinedButton(
                            onClick  = onSkip,
                            border   = BorderStroke(1.dp, AccentRed.copy(alpha = 0.5f)),
                            colors   = ButtonDefaults.outlinedButtonColors(contentColor = AccentRed),
                            enabled  = task.state == TaskState.PENDING
                        ) { Text("Skip") }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyPlanCard(onPlan: () -> Unit) {
    Surface(shape = RoundedCornerShape(14.dp), color = BgCard, border = BorderStroke(1.dp, White10)) {
        Column(
            modifier            = Modifier.fillMaxWidth().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(Icons.Default.EventNote, null, tint = White30, modifier = Modifier.size(40.dp))
            Spacer(Modifier.height(12.dp))
            Text("No plan for today", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = White90)
            Spacer(Modifier.height(6.dp))
            Text("Set up your exam and subjects to generate a daily plan", fontSize = 13.sp, color = White60)
            Spacer(Modifier.height(16.dp))
            Button(onClick = onPlan, colors = ButtonDefaults.buttonColors(containerColor = AccentGreen)) {
                Text("Create Plan", fontWeight = FontWeight.Bold, color = Color.Black)
            }
        }
    }
}

private fun subjectColor(subject: String) = when (subject.lowercase()) {
    "biology"       -> Color(0xFF00C896)
    "chemistry"     -> Color(0xFF4A9EFF)
    "physics"       -> Color(0xFFFFB347)
    "math", "maths" -> Color(0xFFFF4757)
    else            -> Color(0xFF9090A8)
}
