package com.checkmate.ui.negotiation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.checkmate.ui.theme.*
import kotlinx.coroutines.delay

/**
 * Proactive Execution Engine — Step 10 + Step 11 (Blueprint Part One, §16's "Talk to
 * Checkmate"). See [NegotiationViewModel]'s class doc: the Start / Snooze 5m / Dismiss
 * buttons still always resolve the transaction unconditionally, but as of Step 11 the
 * conversation itself can now also resolve it — a decisive intent parsed from the
 * student's own words (e.g. "reduce this to 30 minutes") runs through the same
 * PolicyValidator/ActionExecutor pipeline and closes the screen exactly like a button tap.
 */
@Composable
fun NegotiationScreen(
    navController: NavController,
    transactionId: String,
    taskId: String,
    lateMinutes: Int,
    vm: NegotiationViewModel = viewModel()
) {
    val state by vm.state.collectAsState()
    val context = LocalContext.current
    val listState = rememberLazyListState()

    LaunchedEffect(transactionId) {
        vm.init(context, transactionId, taskId, lateMinutes)
    }

    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) listState.animateScrollToItem(state.messages.size - 1)
    }

    // A resolved outcome (Started/Snoozed/Dismissed/etc.) is shown briefly, then this
    // screen closes itself — there's nothing further to negotiate once the transaction
    // has resolved.
    LaunchedEffect(state.resolution) {
        if (state.resolution != NegotiationResolution.NONE) {
            delay(1200)
            navController.popBackStack()
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(BgDark).imePadding()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { navController.popBackStack() }) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = White60)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    state.task?.let { "${it.subject} — ${it.topic}" } ?: "Talk to Checkmate",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = White90
                )
                if (lateMinutes > 0) {
                    Text("$lateMinutes min late", fontSize = 11.sp, color = AccentAmber)
                }
            }
            Icon(Icons.Default.Psychology, contentDescription = null, tint = AccentGreen, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
        }
        HorizontalDivider(color = White10, thickness = 0.5.dp)

        // BUGFIX: this used to be `if (state.loading) { Spinner(); return@Column }` — a
        // non-local early return out of Column's (inline) trailing lambda. state.loading
        // defaults to true and vm.init() (kicked off by the LaunchedEffect above) flips it
        // to false within milliseconds, so the very first recomposition of this screen
        // switched from "compose nothing below the spinner" to "compose the whole rest of
        // the Column for the first time" — a different set of child groups than the initial
        // composition produced. That shape is exactly what Google's own compose-runtime
        // tracker (b/203576696) documents as a trigger for composer-internal group-stack
        // corruption (IndexOutOfBoundsException in Stack.pop / ComposerImpl.exitGroup on
        // the next recompose) — which is the crash this was producing when opening
        // "Talk to Checkmate" from the notification. Fixed by making loading/loaded proper
        // if/else siblings so every composition of this Column emits the same group shape
        // for whichever branch is active, with no early exit.
        if (state.loading) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = AccentGreen)
            }
        } else {
            state.context?.let { ctx ->
                if (ctx.recentSkipRatePercent > 0 || ctx.streakDays > 0) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (ctx.recentSkipRatePercent > 0) {
                            ContextChip("Skip rate ${ctx.recentSkipRatePercent}%", AccentAmber)
                        }
                        if (ctx.streakDays > 0) {
                            ContextChip("Streak ${ctx.streakDays}d", AccentGreen)
                        }
                    }
                }
            }

            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(state.messages) { msg -> NegotiationBubble(msg) }
                if (state.isSending) {
                    item {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
                            Surface(
                                shape = RoundedCornerShape(topStart = 4.dp, topEnd = 14.dp, bottomEnd = 14.dp, bottomStart = 14.dp),
                                color = BgCard,
                                border = BorderStroke(0.5.dp, White10)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    CircularProgressIndicator(modifier = Modifier.size(14.dp), color = AccentGreen, strokeWidth = 2.dp)
                                    Spacer(Modifier.width(8.dp))
                                    Text("thinking…", fontSize = 13.sp, color = White60)
                                }
                            }
                        }
                    }
                }
                if (state.resolution != NegotiationResolution.NONE) {
                    item {
                        Text(
                            resolutionLabel(state.resolution),
                            fontSize = 12.sp,
                            color = White60,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                        )
                    }
                }
            }

            state.sttError?.let {
                Text(it, fontSize = 11.sp, color = AccentRed, modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp))
            }

            HorizontalDivider(color = White10, thickness = 0.5.dp)

            val actionsEnabled = state.resolution == NegotiationResolution.NONE
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { vm.onStart(context, transactionId, lateMinutes) },
                    enabled = actionsEnabled,
                    colors = ButtonDefaults.buttonColors(containerColor = AccentGreen, contentColor = Color.Black),
                    modifier = Modifier.weight(1f)
                ) { Text("Start", fontSize = 12.sp, fontWeight = FontWeight.Bold) }

                OutlinedButton(
                    onClick = { vm.onSnooze(context, transactionId, taskId, lateMinutes) },
                    enabled = actionsEnabled,
                    modifier = Modifier.weight(1f)
                ) { Text("Snooze 5m", fontSize = 12.sp, color = White90) }

                OutlinedButton(
                    onClick = { vm.onDismiss(context, transactionId) },
                    enabled = actionsEnabled,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentRed),
                    modifier = Modifier.weight(1f)
                ) { Text("Dismiss", fontSize = 12.sp) }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = state.inputText,
                    onValueChange = vm::setInput,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Say or type your response…", color = White30, fontSize = 13.sp) },
                    singleLine = false,
                    maxLines = 3,
                    enabled = actionsEnabled,
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AccentGreen,
                        unfocusedBorderColor = White30,
                        cursorColor = AccentGreen,
                        focusedTextColor = White90,
                        unfocusedTextColor = White90
                    )
                )
                if (state.sttAvailable) {
                    FloatingActionButton(
                        onClick = { if (state.isListening) vm.stopListening() else vm.startListening() },
                        containerColor = if (state.isListening) AccentRed else BgCardAlt,
                        contentColor = if (state.isListening) Color.White else AccentGreen,
                        modifier = Modifier.size(46.dp)
                    ) {
                        Icon(
                            if (state.isListening) Icons.Default.MicOff else Icons.Default.Mic,
                            contentDescription = if (state.isListening) "Stop listening" else "Start listening",
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                FloatingActionButton(
                    onClick = { vm.send(context) },
                    containerColor = AccentGreen,
                    contentColor = Color.Black,
                    modifier = Modifier.size(46.dp)
                ) {
                    Icon(Icons.Default.Send, contentDescription = "Send", modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

@Composable
private fun ContextChip(label: String, color: Color) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = color.copy(alpha = 0.15f),
        border = BorderStroke(0.5.dp, color.copy(alpha = 0.4f))
    ) {
        Text(label, fontSize = 11.sp, color = color, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
    }
}

private fun resolutionLabel(r: NegotiationResolution): String = when (r) {
    NegotiationResolution.STARTED -> "✓ Started — closing…"
    NegotiationResolution.SNOOZED -> "Snoozed 5 minutes — closing…"
    NegotiationResolution.DISMISSED -> "Dismissed — closing…"
    NegotiationResolution.ALREADY_RESOLVED -> "This prompt was already handled — closing…"
    NegotiationResolution.TASK_MISSING -> "This task no longer exists — closing…"
    // Step 11: reached when the conversation itself (not a button) resolved things via a
    // REDUCE_DURATION/RESCHEDULE_TASK/TAKE_SHORT_BREAK/KEEP_PLAN/REQUEST_GUARDIAN intent.
    NegotiationResolution.PLAN_ADJUSTED -> "✓ Updated — closing…"
    NegotiationResolution.NONE -> ""
}

@Composable
private fun NegotiationBubble(msg: NegotiationMessage) {
    val isUser = msg.role == "user"
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            shape = RoundedCornerShape(
                topStart = if (isUser) 14.dp else 4.dp,
                topEnd = if (isUser) 4.dp else 14.dp,
                bottomStart = 14.dp,
                bottomEnd = 14.dp
            ),
            color = if (isUser) AccentGreen.copy(alpha = 0.15f) else BgCard,
            border = BorderStroke(0.5.dp, if (isUser) AccentGreen.copy(alpha = 0.3f) else White10),
            modifier = Modifier.widthIn(max = 300.dp)
        ) {
            Text(
                text = msg.content,
                fontSize = 14.sp,
                color = White90,
                lineHeight = 20.sp,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
            )
        }
    }
}
