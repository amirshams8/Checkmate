package com.checkmate.ui.testresults

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.checkmate.testmate.TestmateWeakArea
import com.checkmate.ui.theme.*

/**
 * Phase 6 (test-platform-blueprint.md) surface: pulls a single Testmate
 * session result by ID and renders it natively, per spec §4's
 * score/weak-chapters/weak-topics shape. Base URL + token are configured in
 * Settings → Test Platform.
 *
 * "Import report.md" section below is separate from the fetch-by-session-ID
 * flow above — the aggregate fetch shows score/weak-chapters/weak-topics for
 * display only (TestmateApi's /results endpoint carries no per-question
 * detail); the file import is what actually feeds the learning module
 * (MasteryEngine/ErrorEngine/RetentionEngine via TestResultNormalizer). The
 * two are independent: fetch a result to look at it, and/or import its
 * report.md (downloaded from TestmateWebScreen's WebView) to let Checkmate
 * learn from it.
 */
@Composable
fun TestResultsScreen(navController: NavController, vm: TestResultsViewModel = viewModel()) {
    val state by vm.state.collectAsState()
    val context = LocalContext.current

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri -> uri?.let { vm.importReport(context, it) } }

    Column(
        modifier = Modifier.fillMaxSize().background(BgDark).verticalScroll(rememberScrollState())
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { navController.popBackStack() }) {
                Icon(Icons.Default.ArrowBack, null, tint = AccentGreen)
            }
            Text("Test Results", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = White90)
        }

        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            Text("Session ID", fontSize = 12.sp, color = White60, modifier = Modifier.padding(bottom = 6.dp))
            OutlinedTextField(
                value         = state.sessionId,
                onValueChange = vm::onSessionIdChange,
                modifier      = Modifier.fillMaxWidth(),
                singleLine    = true,
                placeholder   = { Text("Paste the Testmate session ID", color = White30, fontSize = 13.sp) },
                leadingIcon   = { Icon(Icons.Default.Quiz, null, tint = White60) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor   = AccentGreen,
                    unfocusedBorderColor = White30,
                    cursorColor          = AccentGreen,
                    focusedTextColor     = White90,
                    unfocusedTextColor   = White90
                )
            )
            Spacer(Modifier.height(10.dp))
            Button(
                onClick  = { vm.fetch() },
                enabled  = state.sessionId.isNotBlank() && !state.loading,
                modifier = Modifier.fillMaxWidth(),
                colors   = ButtonDefaults.buttonColors(containerColor = AccentGreen)
            ) {
                if (state.loading) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), color = androidx.compose.ui.graphics.Color.Black, strokeWidth = 2.dp)
                } else {
                    Text("Fetch Result", color = androidx.compose.ui.graphics.Color.Black, fontSize = 13.sp)
                }
            }
        }

        state.error?.let { err ->
            Text(
                err, fontSize = 12.sp, color = AccentRed,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
            )
        }

        state.result?.let { result ->
            Spacer(Modifier.height(16.dp))
            ResultSummaryCard(result)
            if (result.weakChapters.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                WeakAreasCard("Weak Chapters", result.weakChapters)
            }
            if (result.weakTopics.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                WeakAreasCard("Weak Topics", result.weakTopics)
            }
        }

        Spacer(Modifier.height(24.dp))
        HorizontalDivider(color = White10, thickness = 0.5.dp, modifier = Modifier.padding(horizontal = 16.dp))
        Spacer(Modifier.height(20.dp))

        ImportReportSection(
            state    = state,
            onPick   = { importLauncher.launch("*/*") },
            onDismiss = { vm.dismissImportResult() }
        )

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ImportReportSection(
    state: TestResultsState,
    onPick: () -> Unit,
    onDismiss: () -> Unit
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        Text("Import report.md", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = White90)
        Text(
            "Feeds this test's per-question data into Checkmate's learning engine " +
                "(mastery, error patterns, retention) — download the report from the " +
                "Test Platform tab first, then pick it here.",
            fontSize = 12.sp, color = White60, modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
        )
        Button(
            onClick  = onPick,
            enabled  = !state.importing,
            modifier = Modifier.fillMaxWidth(),
            colors   = ButtonDefaults.buttonColors(containerColor = BgCard),
            border   = BorderStroke(0.5.dp, White30)
        ) {
            if (state.importing) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), color = AccentGreen, strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text("Importing…", color = White90, fontSize = 13.sp)
            } else {
                Icon(Icons.Default.UploadFile, null, tint = AccentGreen, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Pick report.md", color = White90, fontSize = 13.sp)
            }
        }

        state.importError?.let { err ->
            Text(
                err, fontSize = 12.sp, color = AccentRed,
                modifier = Modifier.padding(top = 10.dp)
            )
        }

        state.importResult?.let { result ->
            Spacer(Modifier.height(12.dp))
            ImportResultCard(result, onDismiss)
        }
    }
}

@Composable
private fun ImportResultCard(
    result: com.checkmate.learning.testmate.TestResultNormalizer.NormalizeResult,
    onDismiss: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape    = RoundedCornerShape(14.dp),
        color    = BgCard,
        border   = BorderStroke(0.5.dp, if (result.alreadyImported) AccentAmber else AccentGreen)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    if (result.alreadyImported) "Already imported" else "Imported",
                    fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                    color = if (result.alreadyImported) AccentAmber else AccentGreen
                )
                IconButton(onClick = onDismiss, modifier = Modifier.size(20.dp)) {
                    Icon(Icons.Default.Close, null, tint = White60, modifier = Modifier.size(16.dp))
                }
            }
            if (result.alreadyImported) {
                Text(
                    "This test was already imported — no duplicate attempts written.",
                    fontSize = 12.sp, color = White60, modifier = Modifier.padding(top = 6.dp)
                )
            } else {
                Spacer(Modifier.height(10.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    MiniStat("Questions", "${result.questionsWritten}", AccentGreen)
                    MiniStat("Attempts", "${result.attemptsWritten}", AccentGreen)
                    MiniStat("Errors", "${result.errorsClassified}", AccentRed)
                    MiniStat("Concepts", "${result.conceptsRecomputed}", AccentAmber)
                }
            }
            if (result.warnings.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                result.warnings.forEach { w ->
                    Text("⚠ $w", fontSize = 11.sp, color = AccentAmber, modifier = Modifier.padding(top = 2.dp))
                }
            }
        }
    }
}

@Composable
private fun ResultSummaryCard(result: com.checkmate.testmate.TestmateResult) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        shape    = RoundedCornerShape(14.dp),
        color    = BgCard,
        border   = BorderStroke(0.5.dp, White10)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(result.testTitle, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = White90)
            Spacer(Modifier.height(4.dp))
            Text(
                "%.0f/%.0f (%.0f%%)".format(result.score, result.totalMarks, result.accuracyPct),
                fontSize = 26.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace,
                color = scoreColor(result.accuracyPct)
            )
            result.rankInSession?.let {
                Text("Rank #$it", fontSize = 12.sp, color = AccentAmber, modifier = Modifier.padding(top = 2.dp))
            }
            Spacer(Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                MiniStat("Correct", "${result.correctCount}", AccentGreen)
                MiniStat("Wrong", "${result.incorrectCount}", AccentRed)
                MiniStat("Skipped", "${result.skippedCount}", White60)
                MiniStat("Avg time", "${result.avgTimePerQuestion.toInt()}s", White60)
            }
        }
    }
}

@Composable
private fun WeakAreasCard(title: String, areas: List<TestmateWeakArea>) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        shape    = RoundedCornerShape(14.dp),
        color    = BgCard,
        border   = BorderStroke(0.5.dp, White10)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = White90)
            Spacer(Modifier.height(10.dp))
            areas.forEach { area ->
                Column(modifier = Modifier.padding(bottom = 8.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(area.name, fontSize = 13.sp, color = White90)
                        Text(
                            "%.0f%% (%d)".format(area.accuracyPct, area.attempted),
                            fontSize = 12.sp, color = White60, fontFamily = FontFamily.Monospace
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress   = { (area.accuracyPct / 100f).toFloat().coerceIn(0f, 1f) },
                        modifier   = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)),
                        color      = AccentRed,
                        trackColor = White10
                    )
                }
            }
        }
    }
}

@Composable
private fun MiniStat(label: String, value: String, color: androidx.compose.ui.graphics.Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = color)
        Text(label, fontSize = 10.sp, color = White60)
    }
}

private fun scoreColor(accuracyPct: Double): androidx.compose.ui.graphics.Color = when {
    accuracyPct >= 70 -> AccentGreen
    accuracyPct >= 40 -> AccentAmber
    else              -> AccentRed
}
