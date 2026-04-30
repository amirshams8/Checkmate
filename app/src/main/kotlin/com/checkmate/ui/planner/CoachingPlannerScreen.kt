package com.checkmate.ui.planner

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.checkmate.core.CoachingPlannerEntry
import com.checkmate.ui.theme.*

@Composable
fun CoachingPlannerScreen(navController: NavController, vm: CoachingPlannerViewModel = viewModel()) {
    val state   by vm.state.collectAsState()
    val context = LocalContext.current

    Column(modifier = Modifier.fillMaxSize().background(BgDark)) {
        // Header
        Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { navController.popBackStack() }) {
                Icon(Icons.Default.ArrowBack, null, tint = AccentGreen)
            }
            Column {
                Text("Coaching Schedule", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = White90)
                Text("Paste your test/lecture schedule", fontSize = 12.sp, color = White60)
            }
        }

        LazyColumn(
            modifier        = Modifier.fillMaxSize(),
            contentPadding  = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Input section
            item {
                Surface(
                    shape  = RoundedCornerShape(12.dp),
                    color  = BgCard,
                    border = BorderStroke(0.5.dp, White10)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text("Paste Schedule Text", fontSize = 12.sp, color = White60)
                        Text(
                            "Copy from PDF/WhatsApp. Example: \"Physics Electrostatics test on 15 May\"",
                            fontSize = 11.sp, color = White30
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value         = state.rawInput,
                            onValueChange = vm::setRawInput,
                            modifier      = Modifier.fillMaxWidth().height(120.dp),
                            placeholder   = { Text("Paste schedule here...", color = White30) },
                            colors        = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor   = AccentGreen,
                                unfocusedBorderColor = White30,
                                cursorColor          = AccentGreen,
                                focusedTextColor     = White90,
                                unfocusedTextColor   = White90
                            )
                        )
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick  = { vm.extractSchedule(context) },
                            enabled  = !state.isExtracting && state.rawInput.isNotBlank(),
                            modifier = Modifier.fillMaxWidth(),
                            colors   = ButtonDefaults.buttonColors(containerColor = AccentGreen)
                        ) {
                            if (state.isExtracting) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Color.Black, strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                                Text("Extracting…", color = Color.Black, fontWeight = FontWeight.Bold)
                            } else {
                                Icon(Icons.Default.AutoAwesome, null, modifier = Modifier.size(18.dp), tint = Color.Black)
                                Spacer(Modifier.width(6.dp))
                                Text("Extract with AI", color = Color.Black, fontWeight = FontWeight.Bold)
                            }
                        }
                        if (state.error.isNotBlank()) {
                            Spacer(Modifier.height(6.dp))
                            Text(state.error, fontSize = 12.sp, color = AccentRed)
                        }
                    }
                }
            }

            // Existing entries
            if (state.entries.isNotEmpty()) {
                item {
                    Text("Saved Schedule (${state.entries.size})",
                        fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = White90)
                }
                items(state.entries) { entry ->
                    Surface(
                        shape  = RoundedCornerShape(10.dp),
                        color  = BgCard,
                        border = BorderStroke(1.dp, if (entry.type == "test") AccentRed.copy(alpha = 0.3f) else White10)
                    ) {
                        Row(
                            modifier          = Modifier.fillMaxWidth().padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                if (entry.type == "test") Icons.Default.Quiz else Icons.Default.Book,
                                null,
                                tint     = if (entry.type == "test") AccentRed else AccentBlue,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text("${entry.subject} — ${entry.chapter}", fontSize = 13.sp, color = White90, fontWeight = FontWeight.SemiBold)
                                Text("${entry.type.uppercase()}  •  ${entry.date}", fontSize = 11.sp, color = White60)
                            }
                            IconButton(onClick = { vm.deleteEntry(entry) }) {
                                Icon(Icons.Default.Delete, null, tint = White30, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}
