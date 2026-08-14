package com.checkmate.ui.settings

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.checkmate.core.CheckmatePrefs
import com.checkmate.ui.settings.WorkModeLockGate
import com.checkmate.ui.theme.*

// Common distracting sites shown as quick-add chips
private val PRESETS = listOf(
    "youtube.com", "instagram.com", "twitter.com", "x.com",
    "facebook.com", "reddit.com", "tiktok.com", "snapchat.com",
    "netflix.com", "twitch.tv", "discord.com", "pinterest.com"
)

@Composable
fun WebsiteBlockerScreen(onBack: () -> Unit) {

    var domains by remember {
        val saved = (CheckmatePrefs.getString("blocked_domains", "") ?: "")
            .split(",").filter { it.isNotBlank() }.toSet()
        mutableStateOf(saved)
    }
    var inputText by remember { mutableStateOf("") }
    var inputError by remember { mutableStateOf("") }
    val keyboard = LocalSoftwareKeyboardController.current

    fun persist() = CheckmatePrefs.putString("blocked_domains", domains.joinToString(","))

    fun addDomain(raw: String) {
        val clean = raw.trim().lowercase()
            .removePrefix("https://").removePrefix("http://")
            .removePrefix("www.").trimEnd('/')
        if (clean.isBlank()) { inputError = "Enter a domain"; return }
        if (!clean.contains('.')) { inputError = "Enter a valid domain (e.g. youtube.com)"; return }
        domains = domains + clean
        persist()
        inputText = ""
        inputError = ""
        keyboard?.hide()
    }

    Column(modifier = Modifier.fillMaxSize().background(BgDark)) {

        // ── Top bar ──────────────────────────────────────────────────────────
        Row(
            modifier          = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { persist(); onBack() }) {
                Icon(Icons.Default.ArrowBack, null, tint = AccentGreen)
            }
            Column {
                Text("Blocked Websites", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = White90)
                Text("Blocks domains in Chrome, Firefox, Edge + all major browsers",
                    fontSize = 11.sp, color = White60)
            }
        }

        // Editing is locked out while Work Mode is enforcing (active session
        // or the hardcoded daily window) unless a guardian PIN unlock is
        // active. See WorkModeLockGate in SettingsScreen.kt.
        WorkModeLockGate {

        // ── Add domain input ──────────────────────────────────────────────────
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            OutlinedTextField(
                value         = inputText,
                onValueChange = { inputText = it; inputError = "" },
                label         = { Text("youtube.com", color = White30) },
                placeholder   = { Text("Type a domain to block", color = White30) },
                modifier      = Modifier.fillMaxWidth(),
                singleLine    = true,
                isError       = inputError.isNotBlank(),
                supportingText = if (inputError.isNotBlank()) {{ Text(inputError, color = AccentRed) }} else null,
                leadingIcon   = { Icon(Icons.Default.Language, null, tint = White60) },
                trailingIcon  = {
                    IconButton(onClick = { addDomain(inputText) }) {
                        Icon(Icons.Default.Add, null, tint = AccentGreen)
                    }
                },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction    = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(onDone = { addDomain(inputText) }),
                colors        = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor   = AccentGreen,
                    unfocusedBorderColor = White30,
                    cursorColor          = AccentGreen,
                    focusedTextColor     = White90,
                    unfocusedTextColor   = White90
                )
            )
        }

        Spacer(Modifier.height(12.dp))

        // ── Quick-add preset chips ────────────────────────────────────────────
        Text(
            "  Quick add",
            fontSize   = 11.sp,
            color      = White60,
            fontWeight = FontWeight.Bold,
            modifier   = Modifier.padding(horizontal = 16.dp)
        )
        Spacer(Modifier.height(6.dp))
        androidx.compose.foundation.lazy.LazyRow(
            contentPadding      = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(PRESETS) { preset ->
                val alreadyAdded = preset in domains
                FilterChip(
                    selected = alreadyAdded,
                    onClick  = {
                        if (alreadyAdded) {
                            domains = domains - preset; persist()
                        } else {
                            domains = domains + preset; persist()
                        }
                    },
                    label    = { Text(preset, fontSize = 12.sp) },
                    colors   = FilterChipDefaults.filterChipColors(
                        selectedContainerColor    = AccentRed.copy(alpha = 0.15f),
                        selectedLabelColor        = AccentRed,
                        containerColor            = BgCard,
                        labelColor                = White60
                    ),
                    leadingIcon = if (alreadyAdded) {{
                        Icon(Icons.Default.Block, null,
                            tint = AccentRed, modifier = Modifier.size(14.dp))
                    }} else null
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        HorizontalDivider(color = White10, modifier = Modifier.padding(horizontal = 16.dp))
        Spacer(Modifier.height(8.dp))

        // ── Active blocked list ───────────────────────────────────────────────
        Text(
            "  ${domains.size} domain(s) blocked",
            fontSize = 11.sp,
            color    = White60,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        Spacer(Modifier.height(6.dp))

        LazyColumn(
            modifier            = Modifier.fillMaxSize(),
            contentPadding      = PaddingValues(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (domains.isEmpty()) {
                item {
                    Box(
                        modifier         = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("No domains blocked yet", fontSize = 14.sp, color = White30)
                    }
                }
            } else {
                items(domains.sorted(), key = { it }) { domain ->
                    Surface(
                        shape  = RoundedCornerShape(10.dp),
                        color  = AccentRed.copy(alpha = 0.06f),
                        border = BorderStroke(1.dp, AccentRed.copy(alpha = 0.3f))
                    ) {
                        Row(
                            modifier          = Modifier.fillMaxWidth().padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Language, null,
                                tint     = AccentRed.copy(alpha = 0.7f),
                                modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(10.dp))
                            Text(domain, fontSize = 14.sp, color = White90, modifier = Modifier.weight(1f))
                            IconButton(
                                onClick  = { domains = domains - domain; persist() },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(Icons.Default.Close, null,
                                    tint = White30, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
        } // WorkModeLockGate
    }
}
