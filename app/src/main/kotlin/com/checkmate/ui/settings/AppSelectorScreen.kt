package com.checkmate.ui.settings

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
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
import com.checkmate.ui.theme.*

data class AppInfo(val name: String, val packageName: String)

@Composable
fun AppSelectorScreen(onBack: () -> Unit) {
    val context    = LocalContext.current
    val pm         = context.packageManager
    val savedBlocked = remember {
        (CheckmatePrefs.getString("blocked_apps", "") ?: "")
            .split(",").filter { it.isNotBlank() }.toMutableSet()
    }
    val blockedApps = remember { mutableStateSetOf<String>().apply { addAll(savedBlocked) } }

    val installedApps = remember {
        pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .filter { (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 }
            .filter { it.packageName != context.packageName }
            .map { AppInfo(pm.getApplicationLabel(it).toString(), it.packageName) }
            .sortedBy { it.name }
    }

    var search by remember { mutableStateOf("") }
    val filtered = installedApps.filter { it.name.contains(search, ignoreCase = true) }

    Column(modifier = Modifier.fillMaxSize().background(BgDark)) {
        // Top bar
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

        // Search
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
        Text(
            "  ${blockedApps.size} app(s) blocked during STUDY mode",
            fontSize = 12.sp,
            color    = White60,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        Spacer(Modifier.height(8.dp))

        LazyColumn(
            modifier       = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(filtered, key = { it.packageName }) { app ->
                val isBlocked = app.packageName in blockedApps
                Surface(
                    shape  = RoundedCornerShape(10.dp),
                    color  = if (isBlocked) AccentRed.copy(alpha = 0.08f) else BgCard,
                    border = BorderStroke(1.dp, if (isBlocked) AccentRed.copy(alpha = 0.4f) else White10),
                    onClick = {
                        if (isBlocked) blockedApps.remove(app.packageName)
                        else blockedApps.add(app.packageName)
                    }
                ) {
                    Row(
                        modifier          = Modifier.fillMaxWidth().padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(app.name, fontSize = 14.sp, color = White90)
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
    }
}
