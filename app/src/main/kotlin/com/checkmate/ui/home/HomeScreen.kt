package com.checkmate.ui.home

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.checkmate.core.DailyChecklist
import com.checkmate.planner.model.StudyTask
import com.checkmate.planner.model.TaskState
import com.checkmate.planner.model.TaskType
import com.checkmate.ui.theme.*

@Composable
fun HomeScreen(navController: NavController, vm: HomeViewModel) {
    val state   by vm.state.collectAsState()
    val context = LocalContext.current

    var showAddTaskDialog by remember { mutableStateOf(false) }
    var editingTask        by remember { mutableStateOf<StudyTask?>(null) }

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
                item { EmptyPlanCard(onPlan = { navController.navigate("planner") }, onAddCustom = { showAddTaskDialog = true }) }
            } else {
                items(state.tasks, key = { it.id }) { task ->
                    TaskCard(
                        task           = task,
                        isActive       = state.activeTaskId == task.id,
                        onStart        = { vm.startTask(context, task) },
                        onDone         = { vm.requestCompletion(context, task) },
                        onSkip         = { vm.markSkip(context, task) },
                        onPause        = { vm.pauseTask(context, task) },
                        onResume       = { vm.resumeTask(context, task) },
                        onRemove       = { vm.removeTask(task) },
                        onEditDuration = { editingTask = task }
                    )
                }
                item {
                    TextButton(onClick = { showAddTaskDialog = true }) {
                        Icon(Icons.Default.Add, null, modifier = Modifier.size(16.dp), tint = AccentGreen)
                        Spacer(Modifier.width(4.dp))
                        Text("Add Custom Task", color = AccentGreen, fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            // ── Daily Checklist ──────────────────────────────────────────────
            item { ChecklistSection() }

            item { Spacer(Modifier.height(72.dp)) }
        }

        FloatingActionButton(
            onClick        = { showAddTaskDialog = true },
            containerColor = AccentGreen,
            modifier       = Modifier
                .align(Alignment.BottomEnd)
                .padding(20.dp)
        ) {
            Icon(Icons.Default.Add, contentDescription = "Add custom task", tint = Color.Black)
        }
    }

    if (showAddTaskDialog) {
        AddCustomTaskDialog(
            onDismiss = { showAddTaskDialog = false },
            onConfirm = { subject, topic, duration, taskType ->
                vm.addCustomTask(context, subject, topic, duration, taskType)
                showAddTaskDialog = false
            }
        )
    }

    editingTask?.let { task ->
        EditDurationDialog(
            task      = task,
            onDismiss = { editingTask = null },
            onConfirm = { newDuration ->
                vm.editTaskDuration(task, newDuration)
                editingTask = null
            }
        )
    }

    // ── Blueprint 10.1: Intention Declaration + Session Check-In ──────────────
    state.intentionPromptTask?.let { task ->
        IntentionDialog(
            task      = task,
            onDismiss = { vm.dismissIntentionPrompt() },
            onConfirm = { intentionText -> vm.confirmIntentionAndStart(context, task, intentionText) }
        )
    }

    state.completionPromptTask?.let { task ->
        CompletionCheckDialog(
            task     = task,
            onSelect = { status -> vm.confirmCompletion(context, task, status) }
        )
    }
}

// ── Checklist Section ────────────────────────────────────────────────────────

@Composable
private fun ChecklistSection() {
    var expanded by remember { mutableStateOf(false) }

    // Load checklist items fresh each recomposition so toggle calls reflect immediately
    var items by remember { mutableStateOf(DailyChecklist.getTodayItems()) }

    // Refresh when section expands
    LaunchedEffect(expanded) {
        if (expanded) items = DailyChecklist.getTodayItems()
    }

    val doneCount  = items.count { it.isDone }
    val totalCount = items.size
    val allDone    = totalCount > 0 && doneCount == totalCount

    val arrowAngle by animateFloatAsState(
        targetValue   = if (expanded) 180f else 0f,
        animationSpec = tween(200),
        label         = "arrow"
    )

    Surface(
        shape  = RoundedCornerShape(14.dp),
        color  = BgCard,
        border = BorderStroke(
            1.dp,
            if (allDone) AccentGreen.copy(alpha = 0.5f) else White10
        )
    ) {
        Column {
            // Header row — always visible, tap to expand/collapse
            Row(
                modifier          = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint     = if (allDone) AccentGreen else White30,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Daily Checklist",
                        fontSize   = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color      = White90
                    )
                    Text(
                        "$doneCount / $totalCount done",
                        fontSize = 11.sp,
                        color    = if (allDone) AccentGreen else White60
                    )
                }
                Icon(
                    Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    tint     = White30,
                    modifier = Modifier.size(20.dp).rotate(arrowAngle)
                )
            }

            // Expandable checklist items
            AnimatedVisibility(
                visible = expanded,
                enter   = expandVertically() + fadeIn(),
                exit    = shrinkVertically() + fadeOut()
            ) {
                Column {
                    HorizontalDivider(color = White10, thickness = 0.5.dp)
                    items.forEach { item ->
                        Row(
                            modifier          = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    DailyChecklist.toggleItem(item.id)
                                    items = DailyChecklist.getTodayItems()
                                }
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked         = item.isDone,
                                onCheckedChange = {
                                    DailyChecklist.toggleItem(item.id)
                                    items = DailyChecklist.getTodayItems()
                                },
                                colors = CheckboxDefaults.colors(
                                    checkedColor   = AccentGreen,
                                    uncheckedColor = White30
                                )
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text      = item.label,
                                fontSize  = 14.sp,
                                color     = if (item.isDone) White60 else White90,
                                fontWeight = if (item.isDone) FontWeight.Normal else FontWeight.Medium
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                }
            }
        }
    }
}

// ── Blueprint 10.1: Intention Declaration + Session Check-In dialogs ──────────

/**
 * Pre-session "What will you study?" prompt. Shown from HomeScreen whenever
 * HomeState.intentionPromptTask is non-null (i.e. right after tapping "Start Now",
 * before the session actually launches). Pre-fills with the task's topic so a
 * student in a hurry can just tap Start Session, but free-text lets them commit
 * to something more specific (e.g. "finish rolling motion numericals, not just review").
 */
@Composable
private fun IntentionDialog(
    task:      StudyTask,
    onDismiss: () -> Unit,
    onConfirm: (intentionText: String) -> Unit
) {
    var intention by remember { mutableStateOf(task.topic) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor   = BgCard,
        title = {
            Text("What will you study?", fontWeight = FontWeight.Bold, color = White90)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "${task.subject} — ${task.topic} · ${task.durationMinutes}m",
                    fontSize = 12.sp, color = White60
                )
                OutlinedTextField(
                    value         = intention,
                    onValueChange = { intention = it },
                    label         = { Text("Your intention", color = White30) },
                    singleLine    = false,
                    modifier      = Modifier.fillMaxWidth(),
                    colors        = dialogFieldColors()
                )
                Text(
                    "Writing it down raises follow-through — you'll be asked how it went right after.",
                    fontSize = 11.sp, color = White30
                )
            }
        },
        confirmButton = {
            Button(
                onClick  = { onConfirm(intention) },
                colors   = ButtonDefaults.buttonColors(containerColor = AccentGreen)
            ) {
                Text("Start Session", fontWeight = FontWeight.Bold, color = Color.Black)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = White60) }
        }
    )
}

/**
 * Post-session "Did you finish it?" check-in. Shown from HomeScreen whenever
 * HomeState.completionPromptTask is non-null (i.e. right after tapping "Done",
 * before the session actually wraps up). Dismissing outside the dialog counts
 * as YES — the session is ending regardless of how this is answered, this is
 * purely a self-report accountability signal, not a gate on completion.
 */
@Composable
private fun CompletionCheckDialog(
    task:     StudyTask,
    onSelect: (status: String) -> Unit
) {
    AlertDialog(
        onDismissRequest = { onSelect("YES") },
        containerColor   = BgCard,
        title = {
            Text("Did you finish it?", fontWeight = FontWeight.Bold, color = White90)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("${task.subject} — ${task.topic}", fontSize = 13.sp, color = White60)
                if (task.intentionText.isNotBlank() && task.intentionText != task.topic) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Your intention: \"${task.intentionText}\"",
                        fontSize = 12.sp, color = White30
                    )
                }
            }
        },
        confirmButton = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment     = Alignment.CenterVertically
            ) {
                TextButton(onClick = { onSelect("NO") }) {
                    Text("No", color = AccentRed, fontWeight = FontWeight.SemiBold)
                }
                TextButton(onClick = { onSelect("PARTIAL") }) {
                    Text("Partial", color = AccentAmber, fontWeight = FontWeight.SemiBold)
                }
                Button(
                    onClick = { onSelect("YES") },
                    colors  = ButtonDefaults.buttonColors(containerColor = AccentGreen)
                ) {
                    Text("Yes", fontWeight = FontWeight.Bold, color = Color.Black)
                }
            }
        }
    )
}

// ── Rest of HomeScreen (unchanged) ───────────────────────────────────────────

@Composable
private fun AddCustomTaskDialog(
    onDismiss: () -> Unit,
    onConfirm: (subject: String, topic: String, durationMinutes: Int, taskType: TaskType) -> Unit
) {
    var subject  by remember { mutableStateOf("") }
    var topic    by remember { mutableStateOf("") }
    var duration by remember { mutableStateOf("30") }
    var taskType by remember { mutableStateOf(TaskType.OTHER) }

    val durationInt = duration.toIntOrNull()
    val isValid = subject.isNotBlank() && topic.isNotBlank() && durationInt != null && durationInt > 0

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor   = BgCard,
        title = {
            Text("Add Custom Task", fontWeight = FontWeight.Bold, color = White90)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Slots in alongside today's plan — nothing else is removed. Duration stays editable later.",
                    fontSize = 12.sp, color = White60
                )
                OutlinedTextField(
                    value           = subject,
                    onValueChange   = { subject = it },
                    label           = { Text("Subject", color = White30) },
                    singleLine      = true,
                    modifier        = Modifier.fillMaxWidth(),
                    colors          = dialogFieldColors()
                )
                OutlinedTextField(
                    value           = topic,
                    onValueChange   = { topic = it },
                    label           = { Text("Topic", color = White30) },
                    singleLine      = true,
                    modifier        = Modifier.fillMaxWidth(),
                    colors          = dialogFieldColors()
                )
                OutlinedTextField(
                    value           = duration,
                    onValueChange   = { input -> if (input.all { it.isDigit() } && input.length <= 3) duration = input },
                    label           = { Text("Duration (minutes)", color = White30) },
                    singleLine      = true,
                    modifier        = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    colors          = dialogFieldColors()
                )
                Text("Task type", fontSize = 12.sp, color = White60)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                ) {
                    TaskType.values().forEach { type ->
                        val selected = taskType == type
                        FilterChip(
                            selected = selected,
                            onClick  = { taskType = type },
                            label    = { Text(type.name, fontSize = 11.sp) },
                            colors   = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = AccentGreen,
                                selectedLabelColor     = Color.Black
                            )
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick  = { durationInt?.let { onConfirm(subject.trim(), topic.trim(), it, taskType) } },
                enabled  = isValid,
                colors   = ButtonDefaults.buttonColors(containerColor = AccentGreen)
            ) {
                Text("Add Task", fontWeight = FontWeight.Bold, color = Color.Black)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = White60) }
        }
    )
}

@Composable
private fun EditDurationDialog(
    task:      StudyTask,
    onDismiss: () -> Unit,
    onConfirm: (durationMinutes: Int) -> Unit
) {
    var duration by remember { mutableStateOf(task.durationMinutes.toString()) }
    val durationInt = duration.toIntOrNull()
    val isValid = durationInt != null && durationInt > 0

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor   = BgCard,
        title = {
            Text("Edit Duration", fontWeight = FontWeight.Bold, color = White90)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("${task.subject} — ${task.topic}", fontSize = 13.sp, color = White60)
                OutlinedTextField(
                    value           = duration,
                    onValueChange   = { input -> if (input.all { it.isDigit() } && input.length <= 3) duration = input },
                    label           = { Text("Duration (minutes)", color = White30) },
                    singleLine      = true,
                    modifier        = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    colors          = dialogFieldColors()
                )
            }
        },
        confirmButton = {
            Button(
                onClick  = { durationInt?.let(onConfirm) },
                enabled  = isValid,
                colors   = ButtonDefaults.buttonColors(containerColor = AccentGreen)
            ) {
                Text("Save", fontWeight = FontWeight.Bold, color = Color.Black)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = White60) }
        }
    )
}

@Composable
private fun dialogFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor   = AccentGreen,
    unfocusedBorderColor = White30,
    cursorColor          = AccentGreen,
    focusedLabelColor    = AccentGreen,
    focusedTextColor     = White90,
    unfocusedTextColor   = White90
)

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
    task:           StudyTask,
    isActive:       Boolean,
    onStart:        () -> Unit,
    onDone:         () -> Unit,
    onSkip:         () -> Unit,
    onPause:        () -> Unit,
    onResume:       () -> Unit,
    onRemove:       () -> Unit,
    onEditDuration: () -> Unit
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
                    if (task.isCustom) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = AccentBlue.copy(alpha = 0.15f)
                        ) {
                            Text(
                                "CUSTOM",
                                fontSize   = 10.sp,
                                color      = AccentBlue,
                                fontWeight = FontWeight.Bold,
                                modifier   = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                    Text("${task.durationMinutes}m", fontSize = 12.sp, color = White60, fontFamily = FontFamily.Monospace)
                    if (task.isCustom && task.state == TaskState.PENDING) {
                        IconButton(onClick = onEditDuration, modifier = Modifier.size(20.dp)) {
                            Icon(Icons.Default.Edit, null, tint = AccentBlue, modifier = Modifier.size(14.dp))
                        }
                    }
                    if (task.state == TaskState.PENDING) {
                        IconButton(onClick = onRemove, modifier = Modifier.size(20.dp)) {
                            Icon(Icons.Default.Close, null, tint = White30, modifier = Modifier.size(14.dp))
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            Text(
                text       = task.topic,
                fontSize   = 17.sp,
                fontWeight = FontWeight.SemiBold,
                color      = if (task.state == TaskState.DONE || task.state == TaskState.SKIPPED) White60 else White90
            )

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
private fun EmptyPlanCard(onPlan: () -> Unit, onAddCustom: () -> Unit) {
    Surface(shape = RoundedCornerShape(14.dp), color = BgCard, border = BorderStroke(1.dp, White10)) {
        Column(
            modifier            = Modifier.fillMaxWidth().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(Icons.Default.EventNote, null, tint = White30, modifier = Modifier.size(40.dp))
            Spacer(Modifier.height(12.dp))
            Text("No plan for today", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = White90)
            Spacer(Modifier.height(6.dp))
            Text("Set up your exam and subjects to generate a daily plan, or add a one-off task", fontSize = 13.sp, color = White60)
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onPlan, colors = ButtonDefaults.buttonColors(containerColor = AccentGreen)) {
                    Text("Create Plan", fontWeight = FontWeight.Bold, color = Color.Black)
                }
                OutlinedButton(
                    onClick = onAddCustom,
                    border  = BorderStroke(1.dp, White30),
                    colors  = ButtonDefaults.outlinedButtonColors(contentColor = White90)
                ) {
                    Text("Add Custom Task")
                }
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
