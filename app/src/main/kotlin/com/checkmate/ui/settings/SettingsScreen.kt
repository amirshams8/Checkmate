package com.checkmate.ui.settings

import android.app.admin.DevicePolicyManager
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
import androidx.compose.runtime.LaunchedEffect
import com.checkmate.admin.CheckmateDeviceAdminReceiver
import com.checkmate.core.AttentionCycleManager
import com.checkmate.core.CheckmatePrefs
import com.checkmate.service.FloatingAttentionService
import com.checkmate.service.GuardianNotifier
import com.checkmate.ui.theme.*
import com.checkmate.workmode.UninstallGuard
import com.checkmate.workmode.WorkModeManager
import com.checkmate.workmode.WorkModeSchedule
import kotlinx.coroutines.delay

// ── Guardian lock gate for Work Mode settings ─────────────────────────────────
//
// Wraps any Work Mode-related settings (Blocked Apps, Blocked Websites, the
// Focus Cycle toggles) that must not be editable by the student while
// blocking is enforced — either because a focus session is running or
// because the hardcoded 19:00-02:00 window (WorkModeSchedule) is live.
// Shows a PIN-unlock prompt instead of [content] until a correct guardian
// PIN is entered, reusing UninstallGuard's existing PIN/hash/lockout flow
// so there's still exactly one PIN, and the student never sees it either
// way.
@Composable
fun WorkModeLockGate(content: @Composable () -> Unit) {
    val context = LocalContext.current

    // Poll every second - settingsLocked() depends on wall-clock time (the
    // schedule) and on UninstallGuard's unlock-window expiry, neither of
    // which trigger recomposition on their own.
    var tick by remember { mutableStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            tick = System.currentTimeMillis()
        }
    }
    val locked = remember(tick) { WorkModeManager.settingsLocked() }

    if (!locked) {
        content()
        return
    }

    var pin by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    var statusColor by remember { mutableStateOf(AccentRed) }

    Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Lock, null, tint = AccentAmber, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Locked by guardian", fontSize = 13.sp, color = White90, fontWeight = FontWeight.Medium)
        }
        Text(
            "Blocked Apps, Blocked Websites, and Focus Cycle settings are locked while " +
                "a focus session or the daily ${WorkModeSchedule.LABEL} window is active.",
            fontSize = 11.sp, color = White60,
            modifier = Modifier.padding(top = 4.dp, bottom = 10.dp)
        )
        OutlinedTextField(
            value         = pin,
            onValueChange = { if (it.length <= 6) { pin = it; status = null } },
            label         = { Text("Guardian PIN", color = White30) },
            singleLine    = true,
            modifier      = Modifier.fillMaxWidth(),
            visualTransformation = PasswordVisualTransformation(),
            leadingIcon   = { Icon(Icons.Default.LockOpen, null, tint = White60) },
            trailingIcon  = {
                IconButton(onClick = {
                    when (val result = UninstallGuard.unlockWithPin(pin)) {
                        is UninstallGuard.UnlockResult.Success -> {
                            status = "Unlocked for 2 minutes"
                            statusColor = AccentGreen
                            pin = ""
                            tick = System.currentTimeMillis()
                        }
                        is UninstallGuard.UnlockResult.WrongPin -> {
                            status = "Incorrect PIN"
                            statusColor = AccentRed
                        }
                        is UninstallGuard.UnlockResult.NoPinConfigured -> {
                            status = "No PIN generated yet - see Security settings"
                            statusColor = AccentAmber
                        }
                        is UninstallGuard.UnlockResult.LockedOut -> {
                            status = "Too many wrong attempts - locked for ${result.secondsLeft / 60}m"
                            statusColor = AccentRed
                            if (result.justTriggered) GuardianNotifier.notifyPinBruteForce(context)
                        }
                    }
                }) {
                    Icon(Icons.Default.LockOpen, null, tint = AccentGreen)
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
        status?.let {
            Text(it, fontSize = 11.sp, color = statusColor, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

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
            Text(
                "Hardcoded daily block window: ${WorkModeSchedule.LABEL}. " +
                    "This window isn't editable from the app — see your guardian to change it.",
                fontSize = 11.sp, color = White60,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
            )
            HorizontalDivider(color = White10)
            // Blocked Apps, Blocked Websites, and the Focus Cycle toggles below are
            // read-only for the student while Work Mode is enforcing (an active
            // session OR the hardcoded window) — closes the loophole where blocked
            // apps get removed from the list mid-session. Only a guardian PIN
            // unlock (same PIN used for uninstall protection) lifts the lock.
            WorkModeLockGate {
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
            }
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

        // ── Security / Uninstall Protection ──
        SettingSection("SECURITY") {
            UninstallProtectionSettings(context)
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

// ── Security: Device Admin activation + Guardian PIN (uninstall protection) ──

@Composable
private fun UninstallProtectionSettings(context: Context) {
    val dpm = remember { context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager }
    val adminComponent = remember { CheckmateDeviceAdminReceiver.componentName(context) }
    var isAdminActive by remember { mutableStateOf(dpm.isAdminActive(adminComponent)) }

    // Device Admin activation tile
    Row(
        modifier          = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            if (isAdminActive) Icons.Default.GppGood else Icons.Default.GppMaybe,
            null,
            tint = if (isAdminActive) AccentGreen else AccentAmber,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("Device Admin Protection", fontSize = 14.sp, color = White90)
            Text(
                if (isAdminActive)
                    "Active — plain Uninstall is hidden on the App Info screen"
                else
                    "Not active — Checkmate can be uninstalled like any normal app",
                fontSize = 11.sp, color = White60
            )
        }
        if (!isAdminActive) {
            TextButton(onClick = {
                val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                    putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
                    putExtra(
                        DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                        "Required so Checkmate can't be casually uninstalled mid-session."
                    )
                }
                context.startActivity(intent)
            }) { Text("Activate", color = AccentGreen, fontSize = 13.sp) }
        }
    }

    HorizontalDivider(color = White10)

    SettingTile(
        title    = "Refresh Protection Status",
        subtitle = "Tap after activating device admin in the system dialog",
        icon     = Icons.Default.Refresh
    ) {
        isAdminActive = dpm.isAdminActive(adminComponent)
    }

    HorizontalDivider(color = White10)

    // Guardian PIN — generated device-side, hashed on-device, plaintext sent
    // ONLY to the guardian's Telegram. Whoever taps "Generate" never sees it,
    // so tapping it from the student's side can't self-authorize anything.
    val pinConfigured by remember { mutableStateOf(UninstallGuard.hasPinConfigured()) }
    var genStatus by remember { mutableStateOf<String?>(null) }
    var genInFlight by remember { mutableStateOf(false) }

    Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
        Text("Guardian PIN", fontSize = 12.sp, color = White60, modifier = Modifier.padding(bottom = 6.dp))
        Text(
            if (pinConfigured)
                "A PIN is set. It was sent only to the guardian's Telegram — nobody on this screen can see it."
            else
                "No PIN yet — the watchdog will block every uninstall/disable attempt until one is generated.",
            fontSize = 11.sp, color = White30, modifier = Modifier.padding(bottom = 8.dp)
        )
        Button(
            onClick = {
                genInFlight = true
                GuardianNotifier.generateAndSendGuardianPin(context) { success, message ->
                    genStatus = message
                    genInFlight = false
                }
            },
            enabled  = !genInFlight,
            modifier = Modifier.fillMaxWidth(),
            colors   = ButtonDefaults.buttonColors(containerColor = AccentGreen)
        ) {
            Icon(Icons.Default.Send, null, modifier = Modifier.size(18.dp), tint = androidx.compose.ui.graphics.Color.Black)
            Spacer(Modifier.width(8.dp))
            Text(
                if (pinConfigured) "Generate New PIN → Guardian's Telegram" else "Generate PIN → Guardian's Telegram",
                color = androidx.compose.ui.graphics.Color.Black, fontSize = 13.sp
            )
        }
        genStatus?.let {
            Text(it, fontSize = 11.sp, color = White60, modifier = Modifier.padding(top = 6.dp))
        }
    }

    HorizontalDivider(color = White10)

    // Unlock — only the person who has the PIN from Telegram (the guardian)
    // can pass this. Wrong-PIN attempts are counted; 5 failures locks the
    // unlock field for 10 minutes and alerts the guardian.
    var unlockPin by remember { mutableStateOf("") }
    var unlockStatus by remember { mutableStateOf<String?>(null) }
    var unlockStatusColor by remember { mutableStateOf(White60) }

    Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
        Text("Unlock Protection", fontSize = 12.sp, color = White60, modifier = Modifier.padding(bottom = 6.dp))
        Text(
            "Guardian enters the PIN they received on Telegram to pass the watchdog for 2 minutes.",
            fontSize = 11.sp, color = White30, modifier = Modifier.padding(bottom = 6.dp)
        )
        OutlinedTextField(
            value         = unlockPin,
            onValueChange = { if (it.length <= 6) unlockPin = it },
            modifier      = Modifier.fillMaxWidth(),
            singleLine    = true,
            placeholder   = { Text("6-digit guardian PIN", color = White30, fontSize = 13.sp) },
            leadingIcon   = { Icon(Icons.Default.LockOpen, null, tint = White60) },
            trailingIcon  = {
                IconButton(onClick = {
                    when (val result = UninstallGuard.unlockWithPin(unlockPin)) {
                        is UninstallGuard.UnlockResult.Success -> {
                            unlockStatus = "Unlocked for 2 minutes"
                            unlockStatusColor = AccentGreen
                            unlockPin = ""
                        }
                        is UninstallGuard.UnlockResult.WrongPin -> {
                            unlockStatus = "Incorrect PIN"
                            unlockStatusColor = AccentRed
                        }
                        is UninstallGuard.UnlockResult.NoPinConfigured -> {
                            unlockStatus = "No PIN generated yet"
                            unlockStatusColor = AccentAmber
                        }
                        is UninstallGuard.UnlockResult.LockedOut -> {
                            unlockStatus = "Too many wrong attempts — locked for ${result.secondsLeft / 60}m"
                            unlockStatusColor = AccentRed
                            if (result.justTriggered) GuardianNotifier.notifyPinBruteForce(context)
                        }
                    }
                }) {
                    Icon(Icons.Default.LockOpen, null, tint = AccentGreen)
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
        unlockStatus?.let {
            Text(it, fontSize = 11.sp, color = unlockStatusColor, modifier = Modifier.padding(top = 4.dp))
        }
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
