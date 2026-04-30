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
    val state   by vm.state.collectAsState()
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
            item { DayProgressBar(completed = state.completedToday, total = state.tasks.size) }
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
                        onSkip   = { vm.markSkip(context, task) },
                        onPause  = { vm.pauseTask(context, task) },
                        onResume = { vm.resumeTask(context, task) }
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
    task:     StudyTask,
    isActive: Boolean,
    onStart:  () -> Unit,
    onDone:   () -> Unit,
    onSkip:   () -> Unit,
    onPause:  () -> Unit,
    onResume: () -> Unit
) {
    val isPaused    = task.state == TaskState.PAUSED
    val borderColor = when {
        isPaused                        -> AccentAmber
        isActive                        -> AccentGreen
        task.state == TaskState.DONE    -> AccentGreen.copy(alpha = 0.3f)
        task.state == TaskState.SKIPPED -> AccentRed.copy(alpha = 0.3f)
        else                            -> White10
    }

    Surface(
        shape  = RoundedCornerShape(14.dp),
        color  = if (isActive || isPaused) BgCardAlt else BgCard,
        border = BorderStroke(1.dp, borderColor)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {

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
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (isPaused) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = AccentAmber.copy(alpha = 0.15f)
                        ) {
                            Text(
                                "⏸ PAUSED",
                                fontSize   = 10.sp,
                                color      = AccentAmber,
                                fontWeight = FontWeight.Bold,
                                modifier   = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                    Text("${task.durationMinutes}m", fontSize = 12.sp, color = White60, fontFamily = FontFamily.Monospace)
                }
            }

            Spacer(Modifier.height(8.dp))

            Text(
                text       = task.topic,
                fontSize   = 17.sp,
                fontWeight = FontWeight.SemiBold,
                color      = if (task.state == TaskState.DONE || task.state == TaskState.SKIPPED) White60 else White90
            )

            // Action area — fixed with if/else to avoid Compose slot table crash
            if (task.state == TaskState.DONE || task.state == TaskState.SKIPPED) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text     = if (task.state == TaskState.DONE) "✓ Completed" else "✗ Skipped",
                    fontSize = 12.sp,
                    color    = if (task.state == TaskState.DONE) AccentGreen else AccentRed
                )
            } else {
                Spacer(Modifier.height(12.dp))

                if (isPaused) {
                    // Paused task → Resume + Skip
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick  = onResume,
                            colors   = ButtonDefaults.buttonColors(containerColor = AccentAmber),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.PlayArrow, null, modifier = Modifier.size(18.dp), tint = Color.Black)
                            Spacer(Modifier.width(6.dp))
                            Text("Resume", fontWeight = FontWeight.Bold, color = Color.Black)
                        }
                        OutlinedButton(
                            onClick = onSkip,
                            border  = BorderStroke(1.dp, AccentRed.copy(alpha = 0.5f)),
                            colors  = ButtonDefaults.outlinedButtonColors(contentColor = AccentRed)
                        ) { Text("Skip") }
                    }
                } else if (isActive) {
                    // Running task → Pause + Done + Skip
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = onPause,
                            border  = BorderStroke(1.dp, AccentAmber.copy(alpha = 0.6f)),
                            colors  = ButtonDefaults.outlinedButtonColors(contentColor = AccentAmber)
                        ) {
                            Icon(Icons.Default.Pause, null, modifier = Modifier.size(16.dp))
                        }
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
                    // Pending task → Start + Skip
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
