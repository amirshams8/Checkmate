package com.checkmate.ui.consultation

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
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
import com.checkmate.core.TimeSlot
import com.checkmate.ui.theme.*

@Composable
fun ConsultationScreen(navController: NavController, vm: ConsultationViewModel = viewModel()) {
    val profile by vm.profile.collectAsState()
    val saved   by vm.saved.collectAsState()
    val context = LocalContext.current

    if (saved) {
        LaunchedEffect(Unit) { navController.navigate("planner") { popUpTo("consultation") { inclusive = true } } }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgDark)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        // Header
        Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { navController.popBackStack() }) {
                Icon(Icons.Default.ArrowBack, null, tint = AccentGreen)
            }
            Text("Student Profile", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = White90)
        }
        Text(
            "This drives every decision the planner makes.",
            fontSize = 13.sp, color = White60,
            modifier = Modifier.padding(horizontal = 20.dp)
        )
        Spacer(Modifier.height(12.dp))

        // ── ACADEMIC SECTION ──
        SectionHeader("ACADEMIC")
        ConsultCard {
            // Exam target chips
            Text("Exam Target", fontSize = 12.sp, color = White60)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("NEET", "JEE", "CUET").forEach { exam ->
                    FilterChip(
                        selected = profile.examTarget == exam,
                        onClick  = { vm.update { it.copy(examTarget = exam) } },
                        label    = { Text(exam, fontSize = 12.sp) },
                        colors   = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = AccentGreen,
                            selectedLabelColor     = Color.Black
                        )
                    )
                }
            }
            Spacer(Modifier.height(10.dp))

            // Exam date
            ConsultField(
                value         = profile.examDate,
                onValueChange = { vm.update { p -> p.copy(examDate = it) } },
                label         = "Exam Date (DD/MM/YYYY)",
                keyboardType  = KeyboardType.Number
            )
            Spacer(Modifier.height(10.dp))

            // Class
            Text("Current Class", fontSize = 12.sp, color = White60)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("11", "12", "Dropper").forEach { cls ->
                    FilterChip(
                        selected = profile.currentClass == cls,
                        onClick  = { vm.update { it.copy(currentClass = cls) } },
                        label    = { Text(cls, fontSize = 12.sp) },
                        colors   = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = AccentGreen,
                            selectedLabelColor     = Color.Black
                        )
                    )
                }
            }
            Spacer(Modifier.height(10.dp))

            ConsultField(
                value         = profile.coachingName,
                onValueChange = { vm.update { p -> p.copy(coachingName = it) } },
                label         = "Coaching Institute (optional)"
            )
        }

        // ── PERFORMANCE SECTION ──
        SectionHeader("PERFORMANCE")
        ConsultCard {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ConsultField(
                    value         = profile.targetScore.toString(),
                    onValueChange = { vm.update { p -> p.copy(targetScore = it.toIntOrNull() ?: 0) } },
                    label         = "Target Score",
                    keyboardType  = KeyboardType.Number,
                    modifier      = Modifier.weight(1f)
                )
                ConsultField(
                    value         = profile.currentMockScore.toString(),
                    onValueChange = { vm.update { p -> p.copy(currentMockScore = it.toIntOrNull() ?: 0) } },
                    label         = "Last Mock Score",
                    keyboardType  = KeyboardType.Number,
                    modifier      = Modifier.weight(1f)
                )
            }
            if (profile.currentMockScore > 0 && profile.targetScore > 0) {
                Spacer(Modifier.height(6.dp))
                val gap = profile.targetScore - profile.currentMockScore
                val gapColor = if (gap > 100) AccentRed else if (gap > 50) AccentAmber else AccentGreen
                Text(
                    "Gap: $gap marks${if (gap > 0) " — needs focused work" else " — on target!"}",
                    fontSize = 12.sp, color = gapColor
                )
            }
            Spacer(Modifier.height(10.dp))

            ConsultField(
                value         = profile.weakSubjects.joinToString(", "),
                onValueChange = { vm.update { p -> p.copy(weakSubjects = it.split(",").map { s -> s.trim() }.filter { s -> s.isNotBlank() }) } },
                label         = "Weak Subjects (comma separated)"
            )
            Spacer(Modifier.height(8.dp))
            ConsultField(
                value         = profile.weakTopics.joinToString(", "),
                onValueChange = { vm.update { p -> p.copy(weakTopics = it.split(",").map { s -> s.trim() }.filter { s -> s.isNotBlank() }) } },
                label         = "Weak Topics (comma separated)"
            )
        }

        // ── SCHEDULE SECTION ──
        SectionHeader("SCHEDULE")
        ConsultCard {
            Text("Blocked Time Slots", fontSize = 12.sp, color = White60)
            Text("School, coaching, meals — times you can't study", fontSize = 11.sp, color = White30)
            Spacer(Modifier.height(8.dp))
            profile.blockedSlots.forEachIndexed { index, slot ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "${slot.label}  ${slot.startTime}–${slot.endTime}",
                        fontSize = 13.sp, color = White90,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = { vm.removeBlockedSlot(index) }) {
                        Icon(Icons.Default.Close, null, tint = AccentRed, modifier = Modifier.size(16.dp))
                    }
                }
            }

            var slotLabel by remember { mutableStateOf("") }
            var slotStart by remember { mutableStateOf("") }
            var slotEnd   by remember { mutableStateOf("") }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                ConsultField(value = slotLabel, onValueChange = { slotLabel = it }, label = "Label", modifier = Modifier.weight(1.5f))
                ConsultField(value = slotStart, onValueChange = { slotStart = it }, label = "Start", modifier = Modifier.weight(1f))
                ConsultField(value = slotEnd,   onValueChange = { slotEnd = it },   label = "End",   modifier = Modifier.weight(1f))
                IconButton(onClick = {
                    if (slotLabel.isNotBlank() && slotStart.isNotBlank() && slotEnd.isNotBlank()) {
                        vm.addBlockedSlot(TimeSlot(slotLabel, slotStart, slotEnd))
                        slotLabel = ""; slotStart = ""; slotEnd = ""
                    }
                }) { Icon(Icons.Default.Add, null, tint = AccentGreen) }
            }
        }

        // ── WELLBEING SECTION ──
        SectionHeader("WELLBEING")
        ConsultCard {
            Text("Stress Level: ${profile.stressLevel}/5", fontSize = 13.sp, color = White90)
            Slider(
                value         = profile.stressLevel.toFloat(),
                onValueChange = { vm.update { p -> p.copy(stressLevel = it.toInt()) } },
                valueRange    = 1f..5f,
                steps         = 3,
                colors        = SliderDefaults.colors(thumbColor = AccentAmber, activeTrackColor = AccentAmber)
            )
            Spacer(Modifier.height(8.dp))
            Text("Sleep Hours: ${profile.sleepHours}h", fontSize = 13.sp, color = White90)
            Slider(
                value         = profile.sleepHours,
                onValueChange = { vm.update { p -> p.copy(sleepHours = (it * 2).toInt() / 2f) } },
                valueRange    = 3f..10f,
                colors        = SliderDefaults.colors(thumbColor = AccentBlue, activeTrackColor = AccentBlue)
            )
            Spacer(Modifier.height(8.dp))
            Text("Study Hours/Day: ${profile.studyHoursPerDay}h", fontSize = 13.sp, color = White90)
            Slider(
                value         = profile.studyHoursPerDay,
                onValueChange = { vm.update { p -> p.copy(studyHoursPerDay = (it * 2).toInt() / 2f) } },
                valueRange    = 2f..16f,
                colors        = SliderDefaults.colors(thumbColor = AccentGreen, activeTrackColor = AccentGreen)
            )
        }

        Spacer(Modifier.height(16.dp))
        Button(
            onClick  = { vm.save(context) },
            modifier = Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 16.dp),
            shape    = RoundedCornerShape(14.dp),
            colors   = ButtonDefaults.buttonColors(containerColor = AccentGreen)
        ) {
            Icon(Icons.Default.Save, null, modifier = Modifier.size(20.dp), tint = Color.Black)
            Spacer(Modifier.width(8.dp))
            Text("Save Profile", fontWeight = FontWeight.Bold, color = Color.Black, fontSize = 16.sp)
        }
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        title,
        fontSize      = 11.sp,
        fontWeight    = FontWeight.Bold,
        letterSpacing = 2.sp,
        color         = White60,
        modifier      = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 6.dp)
    )
}

@Composable
private fun ConsultCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        shape    = RoundedCornerShape(12.dp),
        color    = BgCard,
        border   = BorderStroke(0.5.dp, White10),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp), content = content)
    }
}

@Composable
private fun ConsultField(
    value:         String,
    onValueChange: (String) -> Unit,
    label:         String,
    keyboardType:  KeyboardType = KeyboardType.Text,
    modifier:      Modifier     = Modifier.fillMaxWidth()
) {
    OutlinedTextField(
        value           = value,
        onValueChange   = onValueChange,
        label           = { Text(label, color = White30, fontSize = 11.sp) },
        singleLine      = true,
        modifier        = modifier,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        colors          = OutlinedTextFieldDefaults.colors(
            focusedBorderColor   = AccentGreen,
            unfocusedBorderColor = White30,
            cursorColor          = AccentGreen,
            focusedTextColor     = White90,
            unfocusedTextColor   = White90
        )
    )
}
