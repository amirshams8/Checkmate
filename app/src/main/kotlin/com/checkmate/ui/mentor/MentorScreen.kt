package com.checkmate.ui.mentor

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import com.checkmate.ui.theme.*

@Composable
fun MentorScreen(vm: MentorViewModel = viewModel()) {
    val state   by vm.state.collectAsState()
    val context = LocalContext.current
    val listState = rememberLazyListState()

    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty())
            listState.animateScrollToItem(state.messages.size - 1)
    }

    Column(modifier = Modifier.fillMaxSize().background(BgDark)) {
        // Header
        Row(
            modifier          = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Psychology, null, tint = AccentGreen, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(8.dp))
            Column {
                Text("Mentor AI", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = White90)
                Text("Knows your full situation. No fluff.", fontSize = 11.sp, color = White60)
            }
        }
        HorizontalDivider(color = White10, thickness = 0.5.dp)

        // Message list
        LazyColumn(
            state           = listState,
            modifier        = Modifier.weight(1f).fillMaxWidth(),
            contentPadding  = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(state.messages) { msg ->
                MentorBubble(msg)
            }
            if (state.isLoading) {
                item {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
                        Surface(
                            shape = RoundedCornerShape(topStart = 4.dp, topEnd = 14.dp, bottomEnd = 14.dp, bottomStart = 14.dp),
                            color = BgCard,
                            border = BorderStroke(0.5.dp, White10)
                        ) {
                            Row(
                                modifier          = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
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
            item { Spacer(Modifier.height(8.dp)) }
        }

        // Input row
        HorizontalDivider(color = White10, thickness = 0.5.dp)
        Row(
            modifier          = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value         = state.inputText,
                onValueChange = vm::setInput,
                modifier      = Modifier.weight(1f),
                placeholder   = { Text("Ask anything about your preparation…", color = White30, fontSize = 13.sp) },
                singleLine    = false,
                maxLines      = 4,
                shape         = RoundedCornerShape(14.dp),
                colors        = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor   = AccentGreen,
                    unfocusedBorderColor = White30,
                    cursorColor          = AccentGreen,
                    focusedTextColor     = White90,
                    unfocusedTextColor   = White90
                )
            )
            FloatingActionButton(
                onClick           = { vm.send(context) },
                containerColor    = AccentGreen,
                contentColor      = Color.Black,
                modifier          = Modifier.size(46.dp)
            ) {
                Icon(Icons.Default.Send, null, modifier = Modifier.size(20.dp))
            }
        }
    }
}

@Composable
private fun MentorBubble(msg: MentorMessage) {
    val isUser = msg.role == "user"
    Row(
        modifier              = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            shape  = RoundedCornerShape(
                topStart    = if (isUser) 14.dp else 4.dp,
                topEnd      = if (isUser) 4.dp  else 14.dp,
                bottomStart = 14.dp,
                bottomEnd   = 14.dp
            ),
            color  = if (isUser) AccentGreen.copy(alpha = 0.15f) else BgCard,
            border = BorderStroke(0.5.dp, if (isUser) AccentGreen.copy(alpha = 0.3f) else White10),
            modifier = Modifier.widthIn(max = 300.dp)
        ) {
            Text(
                text     = msg.content,
                fontSize = 14.sp,
                color    = White90,
                lineHeight = 20.sp,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
            )
        }
    }
}
