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
import com.checkmate.learning.analytics.PerformanceAnalyzer
import com.checkmate.learning.analytics.ScoreGainEstimator
import com.checkmate.learning.analytics.ScorePredictor
import com.checkmate.learning.analytics.SubjectScore
import com.checkmate.learning.testmate.TestResultNormalizer
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
 *
 * Test-report wiring pass: a successful (or already-imported) report import now
 * also renders a [PerformanceReportCard] — the first screen showing the
 * StudentModel -> PerformanceAnalyzer pipeline's actual output, not just raw
 * import counts. Deliberately faithful to what the analyzer produced: no
 * cross-test history section here (PerformanceReport is a derived view over
 * current Room state only — see TestResultsViewModel's own doc — not a stored
 * snapshot), so "Trend" below reflects this student's overall recent-vs-lifetime
 * accuracy shift across every concept, not a literal previous-test-vs-this-test
 * comparison. That comparison needs a persisted report history, which is
 * explicitly out of scope for this pass.
 *
 * ScoreGainEstimator wiring pass (Blueprint §2.2): "Biggest opportunities" used
 * to render [PerformanceAnalyzer.TopicImpact] directly — three concepts at
 * identical "13% mastery" with no way to tell why one outranks another beyond
 * list order. [OpportunityRow] now renders [ScoreGainEstimator.ScoreGainEstimate]
 * instead: mastery is still shown (nothing here hides the underlying number),
 * alongside marks-at-stake, the ranked expectedGain itself, and a confidence
 * label — the blueprint's own "Repair rolling motion — +4.2 marks" framing,
 * not a re-sorted mastery list.
 *
 * ScorePredictor wiring pass (Blueprint §2.3): [PerformanceReportCard] now also
 * renders an [ExpectedScoreCard] — Expected/Range/Target/Gap plus a bottleneck
 * breakdown, per the blueprint's own worked example. Explicitly labeled
 * "Estimated performance" in the UI itself (not just in code comments), per
 * [ScorePredictor.ExpectedScore]'s own "no false precision" caution — this is a
 * first-pass heuristic model, not a validated predictor, and the screen should
 * never imply otherwise.
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

        // Test-report wiring: the analyzer output, rendered right under the raw
        // import counts above it — same test, next level of detail down.
        if (state.analyzing) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), color = AccentGreen, strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text("Building performance report…", fontSize = 12.sp, color = White60)
            }
        }
        state.analysisError?.let { err ->
            Text(
                err, fontSize = 12.sp, color = AccentAmber,
                modifier = Modifier.padding(top = 10.dp)
            )
        }
        val report = state.performanceReport
        val importResult = state.importResult
        if (report != null && importResult != null) {
            Spacer(Modifier.height(12.dp))
            PerformanceReportCard(importResult, report, state.scoreGainEstimates, state.expectedScore)
        }
    }
}

@Composable
private fun ImportResultCard(
    result: TestResultNormalizer.NormalizeResult,
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

/**
 * Test-report wiring pass, updated by the ScoreGainEstimator wiring pass. Four
 * layers, per the reviewed mockup: overall score (from
 * [TestResultNormalizer.NormalizeResult], the report's own literal numbers),
 * subject performance (real +4/-1 scoring via [SubjectScore]), biggest
 * opportunities (top [ScoreGainEstimator.ScoreGainEstimate]s, already sorted by
 * expectedGain descending — first 5 shown), and overall trend. `report` is still
 * passed through for the Trend section (overallTrend/overallTrendDelta live only
 * on [PerformanceAnalyzer.PerformanceReport]) even though topicImpacts itself is
 * no longer rendered directly here — [estimates] is the ranked view built on top
 * of it. Deliberately plain: no charts, no color-coded deltas beyond what
 * MiniStat/scoreColor/confidence coloring already give the rest of this screen —
 * render what the analyzer/estimator produced, not a reinterpretation of it.
 */
@Composable
private fun PerformanceReportCard(
    importResult: TestResultNormalizer.NormalizeResult,
    report: PerformanceAnalyzer.PerformanceReport,
    estimates: List<ScoreGainEstimator.ScoreGainEstimate>,
    expectedScore: ScorePredictor.ExpectedScore?
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape    = RoundedCornerShape(14.dp),
        color    = BgCard,
        border   = BorderStroke(0.5.dp, AccentGreen)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                importResult.title ?: "Test Result",
                fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = White90
            )
            Spacer(Modifier.height(4.dp))

            val obtained = importResult.scoreObtained
            val total = importResult.scoreTotal
            val percent = importResult.scorePercent
            if (obtained != null && total != null) {
                Text(
                    "%.0f/%.0f".format(obtained, total),
                    fontSize = 26.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace,
                    color = percent?.let { scoreColor(it) } ?: White90
                )
                percent?.let {
                    Text(
                        "%.1f%%".format(it), fontSize = 13.sp, color = White60,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }

            if (importResult.subjectScores.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                HorizontalDivider(color = White10, thickness = 0.5.dp)
                Spacer(Modifier.height(12.dp))
                Text("Subject performance", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = White90)
                Spacer(Modifier.height(8.dp))
                importResult.subjectScores.forEach { s -> SubjectScoreRow(s) }
            }

            val opportunities = estimates.filter { it.expectedGain > 0.0 }.take(5)
            if (opportunities.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                HorizontalDivider(color = White10, thickness = 0.5.dp)
                Spacer(Modifier.height(12.dp))
                Text("Biggest opportunities", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = White90)
                Spacer(Modifier.height(8.dp))
                opportunities.forEachIndexed { index, estimate -> OpportunityRow(index + 1, estimate) }
            }

            expectedScore?.let { es ->
                Spacer(Modifier.height(16.dp))
                HorizontalDivider(color = White10, thickness = 0.5.dp)
                Spacer(Modifier.height(12.dp))
                ExpectedScoreSection(es)
            }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = White10, thickness = 0.5.dp)
            Spacer(Modifier.height(12.dp))
            Text("Trend", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = White90)
            Spacer(Modifier.height(6.dp))
            Text(trendLabel(report.overallTrend, report.overallTrendDelta), fontSize = 12.sp, color = White60)
        }
    }
}

/**
 * ScorePredictor wiring pass (Blueprint §2.3): "Expected: 638, Range: 617–659,
 * Target: 680, Gap: 42" plus the bottleneck breakdown, per the blueprint's own
 * worked example. "Estimated performance" is shown as its own label (not buried
 * in a tooltip or code comment) — [ScorePredictor.ExpectedScore]'s own class doc
 * is explicit that this is a first-pass heuristic, not a validated predictor,
 * and the UI shouldn't imply more certainty than the model actually has.
 */
@Composable
private fun ExpectedScoreSection(es: ScorePredictor.ExpectedScore) {
    Text("Expected score", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = White90)
    Text(
        "Estimated performance — not a guarantee",
        fontSize = 10.sp, color = White60, modifier = Modifier.padding(top = 1.dp, bottom = 8.dp)
    )
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        MiniStat("Expected", "%.0f".format(es.expected), White90)
        MiniStat("Range", "%.0f–%.0f".format(es.rangeLow, es.rangeHigh), White60)
        MiniStat("Target", "${es.target}", AccentGreen)
        MiniStat("Gap", "%.0f".format(es.gap), if (es.gap > 0.0) AccentAmber else AccentGreen)
    }
    if (es.bottlenecks.isNotEmpty()) {
        Spacer(Modifier.height(12.dp))
        Text(
            "Where the gap comes from", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = White60
        )
        Spacer(Modifier.height(6.dp))
        es.bottlenecks.forEach { b -> BottleneckRow(b) }
    }
}

@Composable
private fun BottleneckRow(b: ScorePredictor.BottleneckContribution) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            b.label.replaceFirstChar { it.uppercase() },
            fontSize = 12.sp, color = White90, modifier = Modifier.weight(1f)
        )
        Text(
            "+%.0f marks".format(b.marks),
            fontSize = 12.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace,
            color = AccentAmber
        )
    }
}

@Composable
private fun SubjectScoreRow(score: SubjectScore) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(score.subject, fontSize = 13.sp, color = White90)
        Text(
            "${score.marksObtained}/${score.marksTotal}",
            fontSize = 12.sp, color = White60, fontFamily = FontFamily.Monospace
        )
    }
}

/**
 * ScoreGainEstimator wiring pass. Replaces the old single-line "13% mastery"
 * row — top line is the ranked headline (topic + expectedGain, the number this
 * list is actually sorted by); second line keeps mastery visible (nothing here
 * hides it) alongside marks-at-stake and a confidence label, so a student can
 * see both "how ranked" and "how sure" in one glance, per
 * ScoreGainEstimator.EstimateConfidence's own "well-evidenced vs single
 * hand-typed guess" framing.
 */
@Composable
private fun OpportunityRow(rank: Int, estimate: ScoreGainEstimator.ScoreGainEstimate) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "$rank. ${estimate.topic ?: estimate.chapter ?: "Unknown topic"}",
                fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = White90,
                modifier = Modifier.weight(1f)
            )
            Text(
                "+%.1f marks".format(estimate.expectedGain),
                fontSize = 13.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace,
                color = AccentGreen
            )
        }
        Spacer(Modifier.height(2.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "%.0f%% mastery · ~%.0f marks at stake".format(estimate.mastery * 100, estimate.marksAtStake),
                fontSize = 11.sp, color = White60, fontFamily = FontFamily.Monospace
            )
            Text(
                confidenceLabel(estimate.confidence),
                fontSize = 11.sp, color = confidenceColor(estimate.confidence)
            )
        }
    }
}

private fun confidenceLabel(confidence: ScoreGainEstimator.EstimateConfidence): String = when (confidence) {
    ScoreGainEstimator.EstimateConfidence.LOW -> "Low confidence"
    ScoreGainEstimator.EstimateConfidence.MEDIUM -> "Medium confidence"
    ScoreGainEstimator.EstimateConfidence.HIGH -> "High confidence"
}

private fun confidenceColor(confidence: ScoreGainEstimator.EstimateConfidence): androidx.compose.ui.graphics.Color = when (confidence) {
    ScoreGainEstimator.EstimateConfidence.LOW -> White60
    ScoreGainEstimator.EstimateConfidence.MEDIUM -> AccentAmber
    ScoreGainEstimator.EstimateConfidence.HIGH -> AccentGreen
}

private fun trendLabel(trend: PerformanceAnalyzer.PerformanceTrend, delta: Double): String = when (trend) {
    PerformanceAnalyzer.PerformanceTrend.IMPROVING -> "Improving — recent accuracy is %.0f%% above your overall average.".format(delta * 100)
    PerformanceAnalyzer.PerformanceTrend.DECLINING -> "Declining — recent accuracy is %.0f%% below your overall average.".format(-delta * 100)
    PerformanceAnalyzer.PerformanceTrend.STABLE -> "Stable — recent accuracy is in line with your overall average."
    PerformanceAnalyzer.PerformanceTrend.INSUFFICIENT_DATA -> "Not enough attempts yet to call a trend."
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
