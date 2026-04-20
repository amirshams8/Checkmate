package com.checkmate.ui.planner

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.checkmate.ui.theme.*

@Composable
fun PlannerScreen(navController: NavController, vm: PlannerViewModel = viewModel()) {
    val state   = vm.state.collectAsState().value
    val context = LocalContext.current

    LazyColumn(
        modifier            = Modifier.fillMaxSize().background(BgDark),
        contentPadding      = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Text("Study Plan", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = White90)
            Text("Configure your exam to generate an adaptive daily plan", fontSize = 13.sp, color = White60)
        }

        // ── Exam type ──
        item {
            SectionCard(title = "Exam") {
                val exams = listOf("NEET", "JEE", "UPSC", "CA", "GATE", "Custom")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    exams.forEach { exam ->
                        FilterChip(
                            selected = state.examType == exam,
                            onClick  = { vm.setExam(exam) },
                            label    = { Text(exam, fontSize = 12.sp) },
                            colors   = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = AccentGreen,
                                selectedLabelColor     = Color.Black
                            )
                        )
                    }
                }
            }
        }

        // ── Exam date ──
        item {
            SectionCard(title = "Exam Date") {
                OutlinedTextField(
                    value           = state.examDate,
                    onValueChange   = vm::setExamDate,
                    label           = { Text("DD/MM/YYYY", color = White30) },
                    singleLine      = true,
                    modifier        = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    colors          = plannerFieldColors()
                )
            }
        }

        // ── Study window ──
        item {
            SectionCard(title = "Study Window") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value         = state.studyStartTime,
                        onValueChange = vm::setStudyStart,
                        label         = { Text("Start (e.g. 06:00)", color = White30) },
                        singleLine    = true,
                        modifier      = Modifier.weight(1f),
                        colors        = plannerFieldColors()
                    )
                    OutlinedTextField(
                        value         = state.studyEndTime,
                        onValueChange = vm::setStudyEnd,
                        label         = { Text("End (e.g. 22:00)", color = White30) },
                        singleLine    = true,
                        modifier      = Modifier.weight(1f),
                        colors        = plannerFieldColors()
                    )
                }
            }
        }

        // ── Subjects + weightage ──
        item {
            SectionCard(title = "Subjects") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.subjects.forEachIndexed { index, subject ->
                        Row(
                            verticalAlignment     = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value         = subject.name,
                                onValueChange = { vm.updateSubjectName(index, it) },
                                label         = { Text("Subject", color = White30) },
                                singleLine    = true,
                                modifier      = Modifier.weight(2f),
                                colors        = plannerFieldColors()
                            )
                            OutlinedTextField(
                                value           = subject.weightage.toString(),
                                onValueChange   = { vm.updateSubjectWeight(index, it.toIntOrNull() ?: 1) },
                                label           = { Text("Weight", color = White30) },
                                singleLine      = true,
                                modifier        = Modifier.weight(1f),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                colors          = plannerFieldColors()
                            )
                            IconButton(onClick = { vm.removeSubject(index) }) {
                                Icon(Icons.Default.Close, null, tint = AccentRed, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                    TextButton(onClick = vm::addSubject) {
                        Icon(Icons.Default.Add, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Add Subject")
                    }
                }
            }
        }

        // ── Guardian WhatsApp ──
        item {
            SectionCard(title = "Guardian WhatsApp") {
                OutlinedTextField(
                    value           = state.guardianNumber,
                    onValueChange   = vm::setGuardianNumber,
                    label           = { Text("+91XXXXXXXXXX", color = White30) },
                    placeholder     = { Text("Enter guardian phone number", color = White30) },
                    singleLine      = true,
                    modifier        = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    leadingIcon     = { Icon(Icons.Default.Phone, null, tint = AccentGreen) },
                    colors          = plannerFieldColors()
                )
                if (state.guardianNumber.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Weekly reports will be sent to this number via WhatsApp",
                        fontSize = 11.sp,
                        color    = White60
                    )
                }
            }
        }

        // ── Attention cycle settings ──
        item {
            SectionCard(title = "Attention Cycle") {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Focus block: 30 min  →  Short break: 5 min", fontSize = 13.sp, color = White60)
                    Text("After 2 focus blocks: Long break 10 min",    fontSize = 13.sp, color = White60)
                    Text("✅ button confirms attention every 30 min",   fontSize = 13.sp, color = White60)
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Voice reminders", fontSize = 13.sp, color = White90, modifier = Modifier.weight(1f))
                        Switch(
                            checked         = state.ttsEnabled,
                            onCheckedChange = vm::setTtsEnabled,
                            colors          = SwitchDefaults.colors(
                                checkedThumbColor = AccentGreen,
                                checkedTrackColor = AccentGreen.copy(alpha = 0.3f)
                            )
                        )
                    }
                }
            }
        }

        // ── Generate Plan button ──
        item {
            Button(
                onClick  = { vm.generatePlan(context) },
                enabled  = !state.isGenerating,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape    = RoundedCornerShape(14.dp),
                colors   = ButtonDefaults.buttonColors(containerColor = AccentGreen)
            ) {
                if (state.isGenerating) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.Black, strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Generating plan…", fontWeight = FontWeight.Bold, color = Color.Black)
                } else {
                    Icon(Icons.Default.AutoAwesome, null, modifier = Modifier.size(20.dp), tint = Color.Black)
                    Spacer(Modifier.width(8.dp))
                    Text("Generate Today's Plan", fontWeight = FontWeight.Bold, color = Color.Black)
                }
            }
        }

        if (state.generatedTasks.isNotEmpty()) {
            item {
                Text("Today's Plan", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = White90)
            }
            items(state.generatedTasks) { task ->
                Surface(
                    shape  = RoundedCornerShape(10.dp),
                    color  = BgCard,
                    border = BorderStroke(1.dp, White10)
                ) {
                    Row(
                        modifier          = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(task.subject,  fontSize = 11.sp, color = AccentGreen, fontWeight = FontWeight.Bold)
                            Text(task.topic,    fontSize = 15.sp, color = White90)
                        }
                        Text("${task.durationMinutes}m", fontSize = 13.sp, color = White60)
                    }
                }
            }
            item {
                Button(
                    onClick  = { vm.savePlan(context); navController.navigate("home") },
                    modifier = Modifier.fillMaxWidth(),
                    colors   = ButtonDefaults.buttonColors(containerColor = AccentBlue)
                ) {
                    Text("Use This Plan", fontWeight = FontWeight.Bold)
                }
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Surface(
        shape  = RoundedCornerShape(12.dp),
        color  = BgCard,
        border = BorderStroke(0.5.dp, White10)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(title, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, color = White60)
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
private fun plannerFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor   = AccentGreen,
    unfocusedBorderColor = White30,
    cursorColor          = AccentGreen,
    focusedLabelColor    = AccentGreen,
    focusedTextColor     = White90,
    unfocusedTextColor   = White90
)
