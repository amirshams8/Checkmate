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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.checkmate.ui.theme.*

@Composable
fun StatsScreen(vm: StatsViewModel = viewModel()) {
    val state by vm.state.collectAsState()

    Column(
        modifier        = Modifier.fillMaxSize().background(BgDark)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Spacer(Modifier.height(20.dp))

        // Header
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            Text("Stats", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = White90)
            Text("Your performance overview", fontSize = 13.sp, color = White60)
        }

        // ── Summary row ──
        Row(
            modifier              = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            StatTile("Streak", "${state.streakDays}d", Icons.Default.LocalFire, AccentAmber, Modifier.weight(1f))
            StatTile("Today", "${state.todayCompletion}%", Icons.Default.Today, AccentGreen, Modifier.weight(1f))
            StatTile("Week", "${state.weekCompletion}%", Icons.Default.DateRange, AccentBlue, Modifier.weight(1f))
        }

        // ── Weekly bar chart ──
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

        // ── Subject breakdown ──
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

        // ── Attention stats ──
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
                    AttentionStat("Avg Focus", "${state.avgFocusMinutes}m")
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
                modifier              = Modifier.weight(1f),
                horizontalAlignment   = Alignment.CenterHorizontally,
                verticalArrangement   = Arrangement.Bottom
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp),
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
        Row(
            modifier             = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
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
