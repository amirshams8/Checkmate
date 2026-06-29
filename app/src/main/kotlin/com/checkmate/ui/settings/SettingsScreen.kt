package com.checkmate.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.checkmate.core.AttentionCycleManager
import com.checkmate.core.CheckmatePrefs
import com.checkmate.service.FloatingAttentionService
import com.checkmate.ui.theme.*

@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    var showAppSelector     by remember { mutableStateOf(false) }
    var showWebsiteBlocker  by remember { mutableStateOf(false) }

    if (showAppSelector) {
        AppSelectorScreen(onBack = { showAppSelector = false })
        return
    }
    if (showWebsiteBlocker) {
        WebsiteBlockerScreen(onBack = { showWebsiteBlocker = false })
        return
    }

    Column(
        modifier        = Modifier.fillMaxSize().background(BgDark)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Spacer(Modifier.height(20.dp))
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            Text("Settings", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = White90)
        }
        Spacer(Modifier.height(8.dp))

        // ── LLM API Keys ──
        SettingSection("AI PROVIDERS") {
            LlmProviderSettings(context)
        }

        // ── Voice / TTS ──
        SettingSection("VOICE") {
            VoiceSettings(context)
        }

        // ── Work Mode ──
        SettingSection("WORK MODE") {
            FocusCycleSettings(context)
            HorizontalDivider(color = White10)
            SettingTile(
                title    = "Blocked Apps",
                subtitle = "Block distraction apps incl. Google & system apps",
                icon     = Icons.Default.Block
            ) { showAppSelector = true }
            HorizontalDivider(color = White10)
            SettingTile(
                title    = "Blocked Websites",
                subtitle = "Block distracting domains in any browser",
                icon     = Icons.Default.Language
            ) { showWebsiteBlocker = true }
            HorizontalDivider(color = White10)
            SettingTile(
                title    = "Accessibility Service",
                subtitle = "Required for app/site blocking and WhatsApp automation",
                icon     = Icons.Default.Accessibility
            ) {
                context.startActivity(
                    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }

        // ── Permissions ──
        SettingSection("PERMISSIONS") {
            SettingTile(
                title    = "Notification Listener",
                subtitle = "Required for WhatsApp message detection",
                icon     = Icons.Default.Notifications
            ) {
                context.startActivity(
                    Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
            HorizontalDivider(color = White10)
            SettingTile(
                title    = "Overlay Permission",
                subtitle = "Required for floating attention timer",
                icon     = Icons.Default.Layers
            ) {
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${context.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
            HorizontalDivider(color = White10)
            SettingTile(
                title    = "Usage Access",
                subtitle = "Required for app usage history (Digital Wellbeing-style stats)",
                icon     = Icons.Default.History
            ) {
                context.startActivity(
                    Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }

        Spacer(Modifier.height(32.dp))
    }
}

// ── Focus Cycle Settings (Floating bar + Pomodoro breaks toggles) ────────────

@Composable
private fun FocusCycleSettings(context: Context) {
    var barEnabled by remember {
        mutableStateOf(CheckmatePrefs.getBoolean(FloatingAttentionService.PREF_FOCUS_BAR_ENABLED, true))
    }
    var pomodoroEnabled by remember {
        mutableStateOf(CheckmatePrefs.getBoolean(AttentionCycleManager.PREF_POMODORO_ENABLED, true))
    }

    Column {
        // Floating Focus Bar toggle
        Row(
            modifier          = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                if (barEnabled) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                null, tint = AccentGreen, modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Floating Focus Bar", fontSize = 14.sp, color = White90)
                Text(
                    if (barEnabled)
                        "Shows the on-screen timer bar over other apps during focus sessions"
                    else
                        "Bar hidden — use the notification to mark done / take a break / confirm checks",
                    fontSize = 11.sp, color = White60
                )
            }
            Switch(
                checked         = barEnabled,
                onCheckedChange = {
                    barEnabled = it
                    CheckmatePrefs.putBoolean(FloatingAttentionService.PREF_FOCUS_BAR_ENABLED, it)
                },
                colors = SwitchDefaults.colors(
                    checkedThumbColor   = BgDark,
                    checkedTrackColor   = AccentGreen,
                    uncheckedThumbColor = White60,
                    uncheckedTrackColor = White10
                )
            )
        }

        HorizontalDivider(color = White10)

        // Pomodoro Breaks toggle
        Row(
            modifier          = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Timer, null, tint = AccentAmber, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Pomodoro Breaks", fontSize = 14.sp, color = White90)
                Text(
                    if (pomodoroEnabled)
                        "30 min focus / 5–10 min break cycles, with attention check-ins"
                    else
                        "Off — one continuous focus timer for the full task, no forced breaks or checks",
                    fontSize = 11.sp, color = White60
                )
            }
            Switch(
                checked         = pomodoroEnabled,
                onCheckedChange = {
                    pomodoroEnabled = it
                    CheckmatePrefs.putBoolean(AttentionCycleManager.PREF_POMODORO_ENABLED, it)
                },
                colors = SwitchDefaults.colors(
                    checkedThumbColor   = BgDark,
                    checkedTrackColor   = AccentGreen,
                    uncheckedThumbColor = White60,
                    uncheckedTrackColor = White10
                )
            )
        }
        Text(
            "Takes effect on your next focus session — won't change one already running.",
            fontSize = 10.sp, color = White30,
            modifier = Modifier.padding(start = 46.dp, end = 14.dp, bottom = 8.dp)
        )
    }
}

// ── LLM Provider Settings ─────────────────────────────────────────────────────

private val LLM_PROVIDERS = listOf("Groq", "Claude", "Gemini", "OpenRouter")

@Composable
private fun LlmProviderSettings(context: Context) {
    val providers = LLM_PROVIDERS

    var selectedProvider by remember {
        mutableStateOf(CheckmatePrefs.getString("llm_provider", "Groq") ?: "Groq")
    }

    // One key state per provider
    val keys = providers.associateWith { provider ->
        remember(provider) {
            mutableStateOf(
                CheckmatePrefs.getString("llm_key_${provider.lowercase()}", "") ?: ""
            )
        }
    }

    // Provider selector chips
    Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
        Text("Active Provider", fontSize = 12.sp, color = White60,
            modifier = Modifier.padding(bottom = 6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            providers.forEach { provider ->
                val selected = provider == selectedProvider
                FilterChip(
                    selected = selected,
                    onClick  = {
                        selectedProvider = provider
                        CheckmatePrefs.putString("llm_provider", provider)
                    },
                    label    = { Text(provider, fontSize = 12.sp) },
                    colors   = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = AccentGreen,
                        selectedLabelColor     = BgDark
                    )
                )
            }
        }
    }

    HorizontalDivider(color = White10)

    // API key field for the currently selected provider
    val currentKey = keys[selectedProvider]
    if (currentKey != null) {
        var showKey by remember { mutableStateOf(false) }
        var saved   by remember { mutableStateOf(false) }

        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Text("$selectedProvider API Key", fontSize = 12.sp, color = White60,
                modifier = Modifier.padding(bottom = 6.dp))
            OutlinedTextField(
                value         = currentKey.value,
                onValueChange = { currentKey.value = it; saved = false },
                modifier      = Modifier.fillMaxWidth(),
                singleLine    = true,
                placeholder   = { Text("Paste API key here", color = White30, fontSize = 13.sp) },
                visualTransformation = if (showKey) VisualTransformation.None
                                       else PasswordVisualTransformation(),
                trailingIcon  = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { showKey = !showKey }) {
                            Icon(
                                if (showKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                null, tint = White60
                            )
                        }
                        IconButton(onClick = {
                            CheckmatePrefs.putString(
                                "llm_key_${selectedProvider.lowercase()}",
                                currentKey.value.trim()
                            )
                            saved = true
                        }) {
                            Icon(
                                if (saved) Icons.Default.Check else Icons.Default.Save,
                                null,
                                tint = if (saved) AccentGreen else White60
                            )
                        }
                    }
                },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor   = AccentGreen,
                    unfocusedBorderColor = White30,
                    cursorColor          = AccentGreen,
                    focusedTextColor     = White90,
                    unfocusedTextColor   = White90
                )
            )
            if (saved) {
                Text("Saved ✓", fontSize = 11.sp, color = AccentGreen,
                    modifier = Modifier.padding(top = 4.dp))
            }
        }
    }

    HorizontalDivider(color = White10)

    // ── Guardian WhatsApp number ──────────────────────────────────────────────
    var guardianNumber by remember {
        mutableStateOf(CheckmatePrefs.getString("guardian_number", "") ?: "")
    }
    var guardianSaved by remember { mutableStateOf(false) }

    Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
        Text("Guardian WhatsApp Number", fontSize = 12.sp, color = White60,
            modifier = Modifier.padding(bottom = 6.dp))
        OutlinedTextField(
            value         = guardianNumber,
            onValueChange = { guardianNumber = it; guardianSaved = false },
            modifier      = Modifier.fillMaxWidth(),
            singleLine    = true,
            placeholder   = { Text("+91XXXXXXXXXX", color = White30, fontSize = 13.sp) },
            leadingIcon   = { Icon(Icons.Default.Phone, null, tint = White60) },
            trailingIcon  = {
                IconButton(onClick = {
                    CheckmatePrefs.putString("guardian_number", guardianNumber.trim())
                    guardianSaved = true
                }) {
                    Icon(
                        if (guardianSaved) Icons.Default.Check else Icons.Default.Save,
                        null,
                        tint = if (guardianSaved) AccentGreen else White60
                    )
                }
            },
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor   = AccentGreen,
                unfocusedBorderColor = White30,
                cursorColor          = AccentGreen,
                focusedTextColor     = White90,
                unfocusedTextColor   = White90
            )
        )
        if (guardianSaved) {
            Text("Saved ✓", fontSize = 11.sp, color = AccentGreen,
                modifier = Modifier.padding(top = 4.dp))
        }
    }

    HorizontalDivider(color = White10)

    // ── Guardian Telegram Chat ID ─────────────────────────────────────────────
    var telegramChatId by remember {
        mutableStateOf(CheckmatePrefs.getString("telegram_chat_id", "") ?: "")
    }
    var telegramSaved by remember { mutableStateOf(false) }

    Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
        Text("Guardian Telegram Chat ID", fontSize = 12.sp, color = White60,
            modifier = Modifier.padding(bottom = 6.dp))
        Text(
            "Guardian: open Telegram → message @userinfobot → copy the id: number here",
            fontSize = 11.sp,
            color    = White30,
            modifier = Modifier.padding(bottom = 6.dp)
        )
        OutlinedTextField(
            value         = telegramChatId,
            onValueChange = { telegramChatId = it; telegramSaved = false },
            modifier      = Modifier.fillMaxWidth(),
            singleLine    = true,
            placeholder   = { Text("e.g. 123456789", color = White30, fontSize = 13.sp) },
            leadingIcon   = { Icon(Icons.Default.Send, null, tint = White60) },
            trailingIcon  = {
                IconButton(onClick = {
                    CheckmatePrefs.putString("telegram_chat_id", telegramChatId.trim())
                    telegramSaved = true
                }) {
                    Icon(
                        if (telegramSaved) Icons.Default.Check else Icons.Default.Save,
                        null,
                        tint = if (telegramSaved) AccentGreen else White60
                    )
                }
            },
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor   = AccentGreen,
                unfocusedBorderColor = White30,
                cursorColor          = AccentGreen,
                focusedTextColor     = White90,
                unfocusedTextColor   = White90
            )
        )
        if (telegramSaved) {
            Text("Saved ✓", fontSize = 11.sp, color = AccentGreen,
                modifier = Modifier.padding(top = 4.dp))
        }
    }
}

// ── Voice / TTS Settings ──────────────────────────────────────────────────────

@Composable
private fun VoiceSettings(context: Context) {
    var ttsEnabled by remember {
        mutableStateOf(CheckmatePrefs.getBoolean("tts_enabled", true))
    }

    Row(
        modifier          = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.RecordVoiceOver, null, tint = AccentGreen,
            modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("Voice Feedback", fontSize = 14.sp, color = White90)
            Text(
                if (ttsEnabled) "Mentor speaks task summaries aloud" else "Voice feedback is off",
                fontSize = 11.sp, color = White60
            )
        }
        Switch(
            checked         = ttsEnabled,
            onCheckedChange = {
                ttsEnabled = it
                CheckmatePrefs.putBoolean("tts_enabled", it)
            },
            colors = SwitchDefaults.colors(
                checkedThumbColor   = BgDark,
                checkedTrackColor   = AccentGreen,
                uncheckedThumbColor = White60,
                uncheckedTrackColor = White10
            )
        )
    }
}

// ── Shared layout helpers ─────────────────────────────────────────────────────

@Composable
private fun SettingSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        Text(
            title,
            fontSize      = 11.sp,
            fontWeight    = FontWeight.Bold,
            letterSpacing = androidx.compose.ui.unit.TextUnit(2f, androidx.compose.ui.unit.TextUnitType.Sp),
            color         = White60,
            modifier      = Modifier.padding(top = 16.dp, bottom = 8.dp)
        )
        Surface(
            shape  = RoundedCornerShape(12.dp),
            color  = BgCard,
            border = BorderStroke(0.5.dp, White10)
        ) {
            Column(content = content)
        }
    }
}

@Composable
private fun SettingTile(title: String, subtitle: String, icon: ImageVector, onClick: () -> Unit) {
    Surface(onClick = onClick, color = androidx.compose.ui.graphics.Color.Transparent) {
        Row(
            modifier          = Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, null, tint = AccentGreen, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 14.sp, color = White90)
                Text(subtitle, fontSize = 11.sp, color = White60)
            }
            Icon(Icons.Default.ChevronRight, null, tint = White30, modifier = Modifier.size(16.dp))
        }
    }
}
