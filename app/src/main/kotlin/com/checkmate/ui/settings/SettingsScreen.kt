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
        }

        Spacer(Modifier.height(32.dp))
    }
}

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

// These composables keep their full implementations from the existing file.
// Only the shell and WORK MODE section changed — paste your existing
// LlmProviderSettings() and VoiceSettings() implementations below unchanged.
