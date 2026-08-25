package com.checkmate.ui.testresults

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.webkit.CookieManager
import android.webkit.MimeTypeMap
import android.webkit.URLUtil
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
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
 *
 * File downloads (answer-key PDFs, exported reports, etc.) don't work out
 * of the box in a plain WebView — the browser normally hands those off to
 * the OS, but a WebView just silently swallows the navigation. We wire a
 * DownloadListener to Android's DownloadManager and forward the WebView's
 * session cookie + user agent so authenticated downloads still work.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun TestmateWebScreen(navController: NavController) {
    val context = LocalContext.current
    // Plain (non-Compose-state) holder for the WebView instance — it must
    // never be read from inside composition. The crash this replaced came
    // from doing exactly that: a `mutableStateOf<WebView?>` written inside
    // AndroidView's factory (which runs during composition) and read in
    // the same pass by BackHandler's `enabled`, which corrupts Compose's
    // slot table (IndexOutOfBoundsException in Stack.pop).
    val webViewHolder = remember { arrayOfNulls<WebView>(1) }
    var canGoBack by remember { mutableStateOf(false) }
    var title by remember { mutableStateOf("Test Platform") }
    var loading by remember { mutableStateOf(true) }

    val baseUrl = remember {
        CheckmatePrefs.getString(TestmateApi.PREF_BASE_URL, null)?.trim()?.trimEnd('/')
    }

    // canGoBack is only ever updated here, from a WebView callback — never
    // read directly off the live WebView object during composition.
    BackHandler(enabled = canGoBack) {
        webViewHolder[0]?.goBack()
    }

    Column(modifier = Modifier.fillMaxSize().background(BgDark)) {
        Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = {
                if (canGoBack) webViewHolder[0]?.goBack() else navController.popBackStack()
            }) {
                Icon(Icons.Default.ArrowBack, null, tint = AccentGreen)
            }
            Text(title, fontSize = 18.sp, color = White90, modifier = Modifier.weight(1f))
            if (loading) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), color = AccentGreen, strokeWidth = 2.dp)
            }
            IconButton(onClick = { webViewHolder[0]?.reload() }) {
                Icon(Icons.Default.Refresh, null, tint = AccentGreen)
            }
        }

        if (baseUrl.isNullOrBlank()) {
            Column(modifier = Modifier.fillMaxWidth().padding(24.dp)) {
                Text(
                    "Set the Testmate base URL in Settings → Test Platform first.",
                    fontSize = 13.sp, color = White60
                )
            }
        } else {
            var urlInput by remember { mutableStateOf(baseUrl) }
            var urlError by remember { mutableStateOf<String?>(null) }

            // Address bar — lets a pasted link (e.g. a group test session URL like
            // https://testmate2.vercel.app/test/<session-id>) be loaded directly,
            // instead of being stuck on whatever baseUrl was saved in Settings.
            // Still gated by the same allow-list as the Settings field
            // (TestmateApi.isAllowedBaseUrl) — this only ever navigates within
            // Testmate's own hosts, never an arbitrary URL.
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value         = urlInput,
                    onValueChange = { urlInput = it; urlError = null },
                    modifier      = Modifier.weight(1f),
                    singleLine    = true,
                    textStyle    = androidx.compose.ui.text.TextStyle(fontSize = 12.sp),
                    placeholder  = { Text("Paste a Testmate link (e.g. group test session)", color = White30, fontSize = 12.sp) },
                    isError      = urlError != null,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor   = AccentGreen,
                        unfocusedBorderColor = White30,
                        cursorColor          = AccentGreen,
                        focusedTextColor     = White90,
                        unfocusedTextColor   = White90,
                        errorBorderColor     = AccentRed
                    )
                )
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = {
                    val candidate = urlInput.trim()
                    if (TestmateApi.isAllowedBaseUrl(candidate)) {
                        urlError = null
                        loading = true
                        webViewHolder[0]?.loadUrl(candidate)
                    } else {
                        urlError = "Only testmate2.com, testmate2.vercel.app, or the Testmate Supabase auth link are accepted"
                    }
                }) {
                    Icon(Icons.Default.ArrowForward, null, tint = AccentGreen)
                }
            }
            urlError?.let {
                Text(it, fontSize = 10.sp, color = AccentRed,
                    modifier = Modifier.padding(horizontal = 16.dp, bottom = 4.dp))
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
                                canGoBack = view.canGoBack()
                                // Keep the address bar in sync with wherever navigation
                                // (including in-page links, not just the Go button) ends up.
                                url?.takeIf { it.isNotBlank() }?.let { urlInput = it }
                            }

                            override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                                canGoBack = view.canGoBack()
                            }
                        }
                        setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
                            downloadFile(ctx, url, userAgent, contentDisposition, mimeType)
                        }
                        webViewHolder[0] = this
                        loadUrl(baseUrl)
                    }
                }
            )
        }
    }
}

/**
 * Hands a WebView download off to the system DownloadManager so it lands
 * in the device's normal Downloads folder with a notification, progress
 * bar, and retry-on-failure — same as any browser download. Cookies are
 * copied over explicitly because DownloadManager makes its own network
 * request outside the WebView, so it doesn't automatically inherit the
 * WebView's session.
 */
private fun downloadFile(
    context: Context,
    url: String,
    userAgent: String?,
    contentDisposition: String?,
    mimeType: String?
) {
    try {
        val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
        val cookie = CookieManager.getInstance().getCookie(url)
        val resolvedMimeType = mimeType?.takeIf { it.isNotBlank() }
            ?: MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(MimeTypeMap.getFileExtensionFromUrl(url))
            ?: "application/octet-stream"

        val request = DownloadManager.Request(Uri.parse(url)).apply {
            if (!cookie.isNullOrBlank()) addRequestHeader("cookie", cookie)
            if (!userAgent.isNullOrBlank()) addRequestHeader("User-Agent", userAgent)
            setMimeType(resolvedMimeType)
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            setAllowedOverRoaming(true)
        }

        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        downloadManager.enqueue(request)
        Toast.makeText(context, "Downloading $fileName…", Toast.LENGTH_SHORT).show()
    } catch (e: Exception) {
        Toast.makeText(context, "Download failed: ${e.message}", Toast.LENGTH_LONG).show()
    }
}
