package com.checkmate.ui.settings

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.checkmate.core.CheckmatePrefs
import com.checkmate.ui.settings.WorkModeLockGate
import com.checkmate.ui.theme.*

data class AppInfo(
    val name:        String,
    val packageName: String,
    val isSystem:    Boolean
)

@Composable
fun AppSelectorScreen(onBack: () -> Unit) {
    val context      = LocalContext.current
    val pm           = context.packageManager
    val savedBlocked = remember {
        (CheckmatePrefs.getString("blocked_apps", "") ?: "")
            .split(",").filter { it.isNotBlank() }.toMutableSet()
    }
    var blockedApps  by remember { mutableStateOf(savedBlocked.toSet()) }
    var search       by remember { mutableStateOf("") }
    var showSystem   by remember { mutableStateOf(false) }

    // Load all apps that have a launcher entry — this includes Google/system apps
    // (YouTube, Chrome, Gmail, etc.) while excluding headless system internals
    // (com.android.phone, com.android.providers.* etc.) that have no UI to launch.
    val allApps = remember {
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        pm.queryIntentActivities(launcherIntent, 0)
            .map { it.activityInfo.applicationInfo }
            .distinctBy { it.packageName }
            .filter { it.packageName != context.packageName }
            .map { info ->
                AppInfo(
                    name        = pm.getApplicationLabel(info).toString(),
                    packageName = info.packageName,
                    isSystem    = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                )
            }
            .sortedWith(compareBy({ it.isSystem }, { it.name }))
    }

    val userApps   = remember(allApps) { allApps.filter { !it.isSystem } }
    val systemApps = remember(allApps) { allApps.filter {  it.isSystem } }

    val displayList = remember(showSystem, search, allApps) {
        val base = if (showSystem) allApps else userApps
        if (search.isBlank()) base
        else base.filter { it.name.contains(search, ignoreCase = true) ||
                           it.packageName.contains(search, ignoreCase = true) }
    }

    Column(modifier = Modifier.fillMaxSize().background(BgDark)) {

        // ── Top bar ──────────────────────────────────────────────────────────
        Row(
            modifier          = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = {
                CheckmatePrefs.putString("blocked_apps", blockedApps.joinToString(","))
                onBack()
            }) {
                Icon(Icons.Default.ArrowBack, null, tint = AccentGreen)
            }
            Text("Blocked Apps", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = White90)
        }

        // Editing is locked out while Work Mode is enforcing (active session
        // or the hardcoded daily window) unless a guardian PIN unlock is
        // active — this is the fix for "remove the app from the block list,
        // then open it" mid-session. See WorkModeLockGate in SettingsScreen.kt.
        WorkModeLockGate {

        // ── Search ───────────────────────────────────────────────────────────
        OutlinedTextField(
            value         = search,
            onValueChange = { search = it },
            label         = { Text("Search apps", color = White30) },
            modifier      = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            singleLine    = true,
            leadingIcon   = { Icon(Icons.Default.Search, null, tint = White60) },
            colors        = OutlinedTextFieldDefaults.colors(
                focusedBorderColor   = AccentGreen,
                unfocusedBorderColor = White30,
                cursorColor          = AccentGreen,
                focusedTextColor     = White90,
                unfocusedTextColor   = White90
            )
        )

        Spacer(Modifier.height(8.dp))

        // ── Stats + system toggle ────────────────────────────────────────────
        Row(
            modifier          = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                "${blockedApps.size} app(s) blocked · ${systemApps.size} system/Google apps available",
                fontSize = 11.sp,
                color    = White60
            )
        }

        Spacer(Modifier.height(4.dp))

        // ── Show system apps toggle ──────────────────────────────────────────
        Row(
            modifier          = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .clickable { showSystem = !showSystem }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(
                    "Include Google & System Apps",
                    fontSize   = 13.sp,
                    color      = White90,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    "YouTube, Chrome, Instagram (pre-installed), etc.",
                    fontSize = 11.sp,
                    color    = White60
                )
            }
            Switch(
                checked         = showSystem,
                onCheckedChange = { showSystem = it },
                colors          = SwitchDefaults.colors(
                    checkedThumbColor       = AccentGreen,
                    checkedTrackColor       = AccentGreen.copy(alpha = 0.3f),
                    uncheckedThumbColor     = White60,
                    uncheckedTrackColor     = White10
                )
            )
        }

        HorizontalDivider(color = White10, modifier = Modifier.padding(horizontal = 16.dp))
        Spacer(Modifier.height(8.dp))

        // ── App list ─────────────────────────────────────────────────────────
        LazyColumn(
            modifier            = Modifier.fillMaxSize(),
            contentPadding      = PaddingValues(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(displayList, key = { it.packageName }) { app ->
                val isBlocked = app.packageName in blockedApps
                Surface(
                    shape  = RoundedCornerShape(10.dp),
                    color  = when {
                        isBlocked  -> AccentRed.copy(alpha = 0.08f)
                        app.isSystem -> BgCardAlt
                        else       -> BgCard
                    },
                    border = BorderStroke(1.dp, when {
                        isBlocked  -> AccentRed.copy(alpha = 0.4f)
                        app.isSystem -> AccentBlue.copy(alpha = 0.2f)
                        else       -> White10
                    }),
                    onClick = {
                        blockedApps = if (isBlocked)
                            blockedApps - app.packageName
                        else
                            blockedApps + app.packageName
                        // Persist immediately so the accessibility service sees the update
                        CheckmatePrefs.putString("blocked_apps", blockedApps.joinToString(","))
                    }
                ) {
                    Row(
                        modifier          = Modifier.fillMaxWidth().padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(app.name, fontSize = 14.sp, color = White90)
                                if (app.isSystem) {
                                    Spacer(Modifier.width(6.dp))
                                    Surface(
                                        shape = RoundedCornerShape(4.dp),
                                        color = AccentBlue.copy(alpha = 0.15f)
                                    ) {
                                        Text(
                                            "SYSTEM",
                                            fontSize = 9.sp,
                                            color    = AccentBlue,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                        )
                                    }
                                }
                            }
                            Text(app.packageName, fontSize = 10.sp, color = White30)
                        }
                        if (isBlocked) {
                            Icon(Icons.Default.Block, null, tint = AccentRed, modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
        } // WorkModeLockGate
    }
}
