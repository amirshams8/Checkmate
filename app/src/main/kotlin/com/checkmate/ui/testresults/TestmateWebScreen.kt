package com.checkmate.ui.testresults

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.navigation.NavController
import com.checkmate.core.CheckmatePrefs
import com.checkmate.testmate.TestmateApi
import com.checkmate.ui.theme.*

/**
 * Standalone WebView wrapper around the actual Testmate web app — this is
 * the "load the web and let it do the rest" path: test-taking, group-mode
 * timers, leaderboard, everything Testmate already has, none of it rebuilt
 * natively. Login is Testmate's own cookie session (typed once here, then
 * persisted by the system WebView's cookie jar across app restarts) — not
 * the Bearer device-token flow, which is a separate, headless path used
 * only by TestResultsScreen/TestmateApi for pulling a result summary.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun TestmateWebScreen(navController: NavController) {
    val context = LocalContext.current
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var title by remember { mutableStateOf("Test Platform") }
    var loading by remember { mutableStateOf(true) }

    val baseUrl = remember {
        CheckmatePrefs.getString(TestmateApi.PREF_BASE_URL, null)?.trim()?.trimEnd('/')
    }

    BackHandler(enabled = webViewRef?.canGoBack() == true) {
        webViewRef?.goBack()
    }

    Column(modifier = Modifier.fillMaxSize().background(BgDark)) {
        Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = {
                if (webViewRef?.canGoBack() == true) webViewRef?.goBack() else navController.popBackStack()
            }) {
                Icon(Icons.Default.ArrowBack, null, tint = AccentGreen)
            }
            Text(title, fontSize = 18.sp, color = White90, modifier = Modifier.weight(1f))
            if (loading) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), color = AccentGreen, strokeWidth = 2.dp)
            }
        }

        if (baseUrl.isNullOrBlank()) {
            Column(modifier = Modifier.fillMaxWidth().padding(24.dp)) {
                Text(
                    "Set the Testmate base URL in Settings → Test Platform first.",
                    fontSize = 13.sp, color = White60
                )
            }
            return@Column
        }

        AndroidView(
            modifier = Modifier.fillMaxWidth().weight(1f),
            factory = { ctx ->
                CookieManager.getInstance().setAcceptCookie(true)
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, url: String?) {
                            loading = false
                            title = view.title?.takeIf { it.isNotBlank() } ?: "Test Platform"
                        }
                    }
                    webViewRef = this
                    loadUrl(baseUrl)
                }
            }
        )
    }
}
