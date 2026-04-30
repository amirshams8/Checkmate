package com.checkmate.ui.planner

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.checkmate.core.ConsultationProfile
import com.checkmate.core.ExamSyllabus
import com.checkmate.ui.theme.*

@Composable
fun DailyCheckInScreen(navController: NavController, vm: DailyCheckInViewModel = viewModel()) {
    val checkIn   by vm.checkIn.collectAsState()
    val submitted by vm.submitted.collectAsState()
    val profile   = ConsultationProfile.load()

    if (submitted) {
        LaunchedEffect(Unit) {
            navController.navigate("planner") { popUpTo("daily_checkin") { inclusive = true } }
        }
    }

    val subjects = ExamSyllabus.getAllSubjectsForExam(profile.examTarget)
        .ifEmpty { listOf("Biology", "Chemistry", "Physics") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgDark)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { navController.popBackStack() }) {
                Icon(Icons.Default.ArrowBack, null, tint = AccentGreen)
            }
            Column {
                Text("Daily Check-In", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = White90)
                Text("Tell the planner what you're working on today", fontSize = 12.sp, color = White60)
            }
        }

        // STEP 1: Today's topics
        Text("STEP 1 — TODAY'S TOPICS", fontSize = 11.sp, fontWeight = FontWeight.Bold,
            letterSpacing = 2.sp, color = White60,
            modifier = Modifier.padding(start = 20.dp, top = 8.dp, bottom = 6.dp))

        subjects.forEach { subject ->
            val chapters = ExamSyllabus.getChaptersForSubject(profile.examTarget, subject)
            val selected = checkIn.todayTopics[subject] ?: ""

            Surface(
                shape    = RoundedCornerShape(12.dp),
                color    = BgCard,
                border   = BorderStroke(0.5.dp, White10),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(subject, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = White90)
                    Spacer(Modifier.height(8.dp))
                    // Chip grid of chapters
                    val chunked = chapters.chunked(2)
                    chunked.forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                            row.forEach { chapter ->
                                val isSelected = selected == chapter
                                FilterChip(
                                    selected = isSelected,
                                    onClick  = { vm.setTopicForSubject(subject, chapter) },
                                    label    = { Text(chapter, fontSize = 11.sp) },
                                    modifier = Modifier.weight(1f),
                                    colors   = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = AccentGreen.copy(alpha = 0.2f),
                                        selectedLabelColor     = AccentGreen
                                    )
                                )
                            }
                            if (row.size == 1) Spacer(Modifier.weight(1f))
                        }
                        Spacer(Modifier.height(4.dp))
                    }
                }
            }
        }

        // STEP 2: Yesterday's ratings
        if (checkIn.todayTopics.isNotEmpty()) {
            Text("STEP 2 — HOW WAS YESTERDAY?", fontSize = 11.sp, fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp, color = White60,
                modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 6.dp))

            subjects.forEach { subject ->
                val rating = checkIn.yesterdayRatings[subject] ?: 0
                Surface(
                    shape    = RoundedCornerShape(12.dp),
                    color    = BgCard,
                    border   = BorderStroke(0.5.dp, White10),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                ) {
                    Row(
                        modifier          = Modifier.padding(12.dp).fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(subject, fontSize = 13.sp, color = White90, modifier = Modifier.width(90.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            (1..5).forEach { star ->
                                Text(
                                    if (star <= rating) "★" else "☆",
                                    fontSize = 22.sp,
                                    color    = if (star <= rating) AccentAmber else White30,
                                    modifier = Modifier.clickable { vm.setYesterdayRating(subject, star) }
                                )
                            }
                        }
                    }
                }
            }
        }

        // STEP 3: Quick wellbeing
        Text("STEP 3 — QUICK WELLBEING", fontSize = 11.sp, fontWeight = FontWeight.Bold,
            letterSpacing = 2.sp, color = White60,
            modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 6.dp))

        Surface(
            shape    = RoundedCornerShape(12.dp),
            color    = BgCard,
            border   = BorderStroke(0.5.dp, White10),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text("Stress: ${checkIn.stressLevel}/5", fontSize = 13.sp, color = White90)
                Slider(
                    value         = checkIn.stressLevel.toFloat(),
                    onValueChange = { vm.setStress(it.toInt()) },
                    valueRange    = 1f..5f, steps = 3,
                    colors        = SliderDefaults.colors(thumbColor = AccentAmber, activeTrackColor = AccentAmber)
                )
                Spacer(Modifier.height(8.dp))
                Text("Sleep last night: ${checkIn.sleepHours}h", fontSize = 13.sp, color = White90)
                Slider(
                    value         = checkIn.sleepHours,
                    onValueChange = { vm.setSleep((it * 2).toInt() / 2f) },
                    valueRange    = 3f..10f,
                    colors        = SliderDefaults.colors(thumbColor = AccentBlue, activeTrackColor = AccentBlue)
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        Button(
            onClick  = { vm.submit() },
            modifier = Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 16.dp),
            shape    = RoundedCornerShape(14.dp),
            colors   = ButtonDefaults.buttonColors(containerColor = AccentGreen)
        ) {
            Icon(Icons.Default.Check, null, modifier = Modifier.size(20.dp), tint = Color.Black)
            Spacer(Modifier.width(8.dp))
            Text("Submit & Generate Plan", fontWeight = FontWeight.Bold, color = Color.Black, fontSize = 15.sp)
        }
        Spacer(Modifier.height(32.dp))
    }
}
