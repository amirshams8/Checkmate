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
import com.checkmate.core.CheckmatePrefs
import com.checkmate.ui.settings.AppSelectorScreen
import com.checkmate.ui.theme.*

@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    var showAppSelector by remember { mutableStateOf(false) }

    if (showAppSelector) {
        AppSelectorScreen(onBack = { showAppSelector = false })
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
            SettingTile(
                title    = "Blocked Apps",
                subtitle = "Select distraction apps to block during study",
                icon     = Icons.Default.Block
            ) { showAppSelector = true }
            SettingTile(
                title    = "Accessibility Service",
                subtitle = "Required for app blocking and WhatsApp automation",
                icon     = Icons.Default.Accessibility
            ) {
                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            }
        }

        // ── Permissions ──
        SettingSection("PERMISSIONS") {
            SettingTile(
                title    = "Notification Listener",
                subtitle = "Required for WhatsApp message detection",
                icon     = Icons.Default.Notifications
            ) {
                val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            }
            SettingTile(
                title    = "Overlay Permission",
                subtitle = "Required for floating attention timer",
                icon     = Icons.Default.Layers
            ) {
                val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${context.packageName}"))
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            }
        }

        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun SettingSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        Text(
            title,
            fontSize  = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = androidx.compose.ui.unit.TextUnit(2f, androidx.compose.ui.unit.TextUnitType.Sp),
            color     = White60,
            modifier  = Modifier.padding(top = 16.dp, bottom = 8.dp)
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

@Composable
private fun LlmProviderSettings(context: Context) {
    val providers = listOf("OpenRouter", "Groq", "Claude", "Gemini")
    var selectedProvider by remember {
        mutableStateOf(CheckmatePrefs.getString("llm_provider", "Groq") ?: "Groq")
    }
    var apiKey by remember {
        mutableStateOf(CheckmatePrefs.getString("llm_key_${selectedProvider.lowercase()}", "") ?: "")
    }
    var showKey by remember { mutableStateOf(false) }

    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Active Provider", fontSize = 12.sp, color = White60)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            providers.forEach { p ->
                FilterChip(
                    selected = selectedProvider == p,
                    onClick  = {
                        selectedProvider = p
                        CheckmatePrefs.putString("llm_provider", p)
                        apiKey = CheckmatePrefs.getString("llm_key_${p.lowercase()}", "") ?: ""
                    },
                    label    = { Text(p, fontSize = 11.sp) },
                    colors   = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = AccentGreen,
                        selectedLabelColor     = androidx.compose.ui.graphics.Color.Black
                    )
                )
            }
        }
        OutlinedTextField(
            value         = apiKey,
            onValueChange = {
                apiKey = it
                CheckmatePrefs.putString("llm_key_${selectedProvider.lowercase()}", it)
            },
            label         = { Text("$selectedProvider API Key (BYOK)", color = White30) },
            singleLine    = true,
            modifier      = Modifier.fillMaxWidth(),
            visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon  = {
                IconButton(onClick = { showKey = !showKey }) {
                    Icon(if (showKey) Icons.Default.VisibilityOff else Icons.Default.Visibility, null, tint = White60)
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
        Text("Leave blank to use rule-based fallback (works offline)", fontSize = 11.sp, color = White30)
    }
}

@Composable
private fun VoiceSettings(context: Context) {
    var ttsEnabled by remember { mutableStateOf(CheckmatePrefs.getBoolean("tts_enabled", true)) }
    var elevenKey  by remember { mutableStateOf(CheckmatePrefs.getString("elevenlabs_key", "") ?: "") }
    var showElevenKey by remember { mutableStateOf(false) }

    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Voice Reminders", fontSize = 14.sp, color = White90)
                Text("Android TTS with ElevenLabs fallback", fontSize = 11.sp, color = White60)
            }
            Switch(
                checked         = ttsEnabled,
                onCheckedChange = {
                    ttsEnabled = it
                    CheckmatePrefs.putBoolean("tts_enabled", it)
                },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = AccentGreen,
                    checkedTrackColor = AccentGreen.copy(alpha = 0.3f)
                )
            )
        }
        OutlinedTextField(
            value         = elevenKey,
            onValueChange = {
                elevenKey = it
                CheckmatePrefs.putString("elevenlabs_key", it)
            },
            label         = { Text("ElevenLabs Key (optional, BYOK)", color = White30) },
            singleLine    = true,
            modifier      = Modifier.fillMaxWidth(),
            visualTransformation = if (showElevenKey) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon  = {
                IconButton(onClick = { showElevenKey = !showElevenKey }) {
                    Icon(if (showElevenKey) Icons.Default.VisibilityOff else Icons.Default.Visibility, null, tint = White60)
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
    }
}
