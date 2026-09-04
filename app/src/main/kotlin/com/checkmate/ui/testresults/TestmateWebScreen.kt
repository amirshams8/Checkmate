package com.checkmate.ui.testresults

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Base64
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.MimeTypeMap
import android.webkit.URLUtil
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.navigation.NavController
import com.checkmate.core.CheckmatePrefs
import com.checkmate.testmate.TestmateApi
import com.checkmate.ui.theme.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

private const val PREF_HISTORY = "testmate_webview_history"
private const val HISTORY_LIMIT = 30

/**
 * One open WebView "tab" — each gets its own [WebView] instance (kept alive in
 * [TestmateWebScreen]'s tabWebViews cache so switching tabs doesn't reload them),
 * so each tab's back/forward stack is naturally independent, same as a real browser.
 */
private class TabState(val id: String, initialUrl: String) {
    var url by mutableStateOf(initialUrl)
    var title by mutableStateOf("Test Platform")
    var canGoBack by mutableStateOf(false)
    var loading by mutableStateOf(true)
}

private data class HistoryEntry(val url: String, val title: String)

private fun loadHistory(): List<HistoryEntry> {
    val raw = CheckmatePrefs.getString(PREF_HISTORY, null) ?: return emptyList()
    return try {
        val arr = JSONArray(raw)
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            HistoryEntry(o.optString("url"), o.optString("title"))
        }
    } catch (_: Exception) {
        emptyList()
    }
}

private fun saveHistory(entries: List<HistoryEntry>) {
    val arr = JSONArray()
    entries.take(HISTORY_LIMIT).forEach {
        arr.put(JSONObject().apply { put("url", it.url); put("title", it.title) })
    }
    CheckmatePrefs.putString(PREF_HISTORY, arr.toString())
}

/** Most-recent-first, de-duped by URL (a revisit moves the entry back to the top). */
private fun recordVisit(url: String, title: String) {
    if (url.isBlank()) return
    val updated = listOf(HistoryEntry(url, title.ifBlank { url })) +
        loadHistory().filterNot { it.url == url }
    saveHistory(updated.take(HISTORY_LIMIT))
}

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
 *
 * "Copy report for LLM" / "Download .md" style buttons build the file
 * client-side as a JS Blob (`URL.createObjectURL(blob)`), which produces a
 * `blob:` URL rather than a real network resource. DownloadManager can only
 * fetch http/https — it has no way to reach bytes that only exist inside
 * the page's JS heap — so those downloads are pulled out via JS instead
 * (fetch the blob, base64-encode it, hand it to Kotlin through
 * [BlobDownloadInterface]) and written to disk directly.
 *
 * Multi-tab + history: several WebViews can be open at once (e.g. a group
 * session in one tab, a previous test result in another) via the tab
 * switcher grid, and every page visited across every tab is logged to a
 * small persisted history list (CheckmatePrefs, capped at [HISTORY_LIMIT])
 * so an old test result page can be reopened without re-navigating from
 * scratch. This is a lightweight approximation of a real browser's tab
 * strip — not Chrome's tab-group/thumbnail machinery.
 *
 * [initialSessionId] — P0b: when set (navigated here via "test_web/{sessionId}",
 * see MainScreen's NavHost), the first tab opens directly at
 * "$baseUrl/test/$initialSessionId" — the student's assigned Testmate
 * targeted-repair test — instead of the bare Testmate homepage. Null for the
 * plain "test_web" route (bottom-nav / manual entry), which is unchanged.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun TestmateWebScreen(navController: NavController, initialSessionId: String? = null) {
    val context = LocalContext.current
    // Cache of live WebView instances keyed by tab id — never read during
    // composition, only from click handlers / WebView callbacks. Reused
    // across tab switches (reparented into whichever AndroidView is active)
    // so a tab's page and its own back/forward stack survive switching away
    // and back, instead of reloading from scratch every time.
    val tabWebViews = remember { mutableMapOf<String, WebView>() }

    val baseUrl = remember {
        CheckmatePrefs.getString(TestmateApi.PREF_BASE_URL, null)?.trim()?.trimEnd('/')
    }

    // P0b: land directly on the assigned repair test when we were navigated here
    // with a sessionId, rather than opening the bare base URL and making the
    // student find their way to it themselves.
    val initialUrl = remember {
        if (!baseUrl.isNullOrBlank() && !initialSessionId.isNullOrBlank())
            "$baseUrl/test/$initialSessionId"
        else baseUrl
    }

    val tabs = remember {
        mutableStateListOf<TabState>().apply {
            if (!initialUrl.isNullOrBlank()) add(TabState(id = "tab-0", initialUrl = initialUrl))
        }
    }
    var activeTabId by remember { mutableStateOf(tabs.firstOrNull()?.id ?: "") }
    var nextTabIndex by remember { mutableStateOf(1) }
    val activeTab = tabs.find { it.id == activeTabId }

    var showTabSwitcher by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }
    var history by remember { mutableStateOf(loadHistory()) }

    fun openNewTab(url: String) {
        val id = "tab-${nextTabIndex++}"
        tabs.add(TabState(id = id, initialUrl = url))
        activeTabId = id
        showTabSwitcher = false
    }

    fun closeTab(id: String) {
        tabWebViews.remove(id)?.destroy()
        tabs.removeAll { it.id == id }
        if (activeTabId == id) {
            activeTabId = tabs.lastOrNull()?.id ?: ""
        }
        if (tabs.isEmpty()) {
            showTabSwitcher = false
            navController.popBackStack()
        }
    }

    // Hardware/gesture back: go back within the active tab's own history first,
    // only leaving the screen once that tab has nowhere left to go back to.
    BackHandler(enabled = activeTab?.canGoBack == true) {
        tabWebViews[activeTabId]?.goBack()
    }

    Column(modifier = Modifier.fillMaxSize().background(BgDark)) {
        Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = {
                if (activeTab?.canGoBack == true) tabWebViews[activeTabId]?.goBack()
                else navController.popBackStack()
            }) {
                Icon(Icons.Default.ArrowBack, null, tint = AccentGreen)
            }
            Text(
                activeTab?.title ?: "Test Platform",
                fontSize = 18.sp, color = White90, modifier = Modifier.weight(1f),
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            if (activeTab?.loading == true) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), color = AccentGreen, strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            IconButton(onClick = { tabWebViews[activeTabId]?.reload() }) {
                Icon(Icons.Default.Refresh, null, tint = AccentGreen)
            }
            IconButton(onClick = { openNewTab(baseUrl ?: activeTab?.url ?: "") }) {
                Icon(Icons.Default.Add, null, tint = AccentGreen)
            }
            IconButton(onClick = { showTabSwitcher = true }) {
                TabCountBadge(count = tabs.size)
            }
        }

        if (baseUrl.isNullOrBlank()) {
            Column(modifier = Modifier.fillMaxWidth().padding(24.dp)) {
                Text(
                    "Set the Testmate base URL in Settings → Test Platform first.",
                    fontSize = 13.sp, color = White60
                )
            }
        } else if (activeTab != null) {
            var urlError by remember(activeTab.id) { mutableStateOf<String?>(null) }

            // Address bar — lets a pasted link (e.g. a group test session URL like
            // https://testmate2.vercel.app/test/<session-id>) be loaded directly in
            // the active tab. Bound directly to activeTab.url (a field on the stable
            // TabState object, not a separate recomposition-scoped variable) so it
            // stays correct even when a cached WebView from an earlier composition
            // updates it via onPageFinished. Still gated by the same allow-list as
            // the Settings field (TestmateApi.isAllowedBaseUrl) — this only ever
            // navigates within Testmate's own hosts, never an arbitrary URL.
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value         = activeTab.url,
                    onValueChange = { activeTab.url = it; urlError = null },
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
                    val candidate = activeTab.url.trim()
                    if (TestmateApi.isAllowedBaseUrl(candidate)) {
                        urlError = null
                        activeTab.loading = true
                        tabWebViews[activeTabId]?.loadUrl(candidate)
                    } else {
                        urlError = "Only testmate2.com, testmate2.vercel.app, or the Testmate Supabase auth link are accepted"
                    }
                }) {
                    Icon(Icons.Default.ArrowForward, null, tint = AccentGreen)
                }
                IconButton(onClick = { history = loadHistory(); showHistory = true }) {
                    Icon(Icons.Default.History, null, tint = White60)
                }
            }
            urlError?.let {
                Text(it, fontSize = 10.sp, color = AccentRed,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 4.dp))
            }

            // key(activeTab.id) forces a fresh AndroidView per active tab instead of
            // mutating one in place — factory then either reparents the cached WebView
            // for that tab (switching back to a tab you already had open) or creates a
            // brand-new one (first time this tab is shown).
            key(activeTab.id) {
                AndroidView(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    factory = { ctx ->
                        val cached = tabWebViews[activeTab.id]
                        if (cached != null) {
                            (cached.parent as? ViewGroup)?.removeView(cached)
                            cached
                        } else {
                            CookieManager.getInstance().setAcceptCookie(true)
                            WebView(ctx).apply {
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                                addJavascriptInterface(BlobDownloadInterface(ctx), "AndroidBlobDownload")
                                webViewClient = object : WebViewClient() {
                                    override fun onPageFinished(view: WebView, url: String?) {
                                        activeTab.loading = false
                                        val pageTitle = view.title?.takeIf { it.isNotBlank() } ?: "Test Platform"
                                        activeTab.title = pageTitle
                                        activeTab.canGoBack = view.canGoBack()
                                        url?.takeIf { it.isNotBlank() }?.let {
                                            activeTab.url = it
                                            recordVisit(it, pageTitle)
                                        }
                                    }

                                    override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                                        activeTab.canGoBack = view.canGoBack()
                                    }
                                }
                                setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
                                    if (url.startsWith("blob:")) {
                                        // DownloadManager can't fetch blob: URLs — pull the bytes
                                        // out of the page's JS heap instead and hand them back to
                                        // Kotlin as base64 through BlobDownloadInterface.
                                        val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
                                        val escapedFileName = fileName.replace("\\", "\\\\").replace("'", "\\'")
                                        val js = """
                                            (function() {
                                                fetch('$url')
                                                    .then(function(res) { return res.blob(); })
                                                    .then(function(blob) {
                                                        var reader = new FileReader();
                                                        reader.onloadend = function() {
                                                            AndroidBlobDownload.onBase64Data(reader.result, '$escapedFileName');
                                                        };
                                                        reader.onerror = function() {
                                                            AndroidBlobDownload.onError('Could not read blob data');
                                                        };
                                                        reader.readAsDataURL(blob);
                                                    })
                                                    .catch(function(e) {
                                                        AndroidBlobDownload.onError(e && e.message ? e.message : 'blob fetch failed');
                                                    });
                                            })();
                                        """.trimIndent()
                                        evaluateJavascript(js, null)
                                    } else {
                                        downloadFile(ctx, url, userAgent, contentDisposition, mimeType)
                                    }
                                }
                                tabWebViews[activeTab.id] = this
                                loadUrl(activeTab.url)
                            }
                        }
                    }
                )
            }
        }
    }

    if (showTabSwitcher) {
        TabSwitcherOverlay(
            tabs = tabs,
            activeTabId = activeTabId,
            onSelect = { activeTabId = it; showTabSwitcher = false },
            onClose = { closeTab(it) },
            onNewTab = { openNewTab(baseUrl ?: "") },
            onDismiss = { showTabSwitcher = false }
        )
    }

    if (showHistory) {
        HistoryDialog(
            entries = history,
            onOpen = { entry ->
                showHistory = false
                if (activeTab != null && TestmateApi.isAllowedBaseUrl(entry.url)) {
                    activeTab.loading = true
                    tabWebViews[activeTabId]?.loadUrl(entry.url)
                } else if (TestmateApi.isAllowedBaseUrl(entry.url)) {
                    openNewTab(entry.url)
                }
            },
            onClear = {
                saveHistory(emptyList())
                history = emptyList()
            },
            onDismiss = { showHistory = false }
        )
    }
}

/** Small rounded-square tab-count indicator, same idea as Chrome's tab-switcher icon. */
@Composable
private fun TabCountBadge(count: Int) {
    Box(
        modifier = Modifier
            .size(24.dp)
            .clip(RoundedCornerShape(6.dp))
            .border(1.5.dp, AccentGreen, RoundedCornerShape(6.dp)),
        contentAlignment = Alignment.Center
    ) {
        Text(count.toString(), fontSize = 12.sp, color = AccentGreen, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun TabSwitcherOverlay(
    tabs: List<TabState>,
    activeTabId: String,
    onSelect: (String) -> Unit,
    onClose: (String) -> Unit,
    onNewTab: () -> Unit,
    onDismiss: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize().background(BgDark)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onNewTab) {
                Icon(Icons.Default.Add, null, tint = AccentGreen)
            }
            Text(
                "${tabs.size} tab${if (tabs.size == 1) "" else "s"}",
                fontSize = 16.sp, color = White90, fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f).padding(start = 8.dp)
            )
            IconButton(onClick = onDismiss) {
                Icon(Icons.Default.Close, null, tint = White60)
            }
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.weight(1f)
        ) {
            items(tabs, key = { it.id }) { tab ->
                val selected = tab.id == activeTabId
                Surface(
                    onClick = { onSelect(tab.id) },
                    shape  = RoundedCornerShape(12.dp),
                    color  = BgCard,
                    border = BorderStroke(if (selected) 1.5.dp else 0.5.dp, if (selected) AccentGreen else White10)
                ) {
                    Column(modifier = Modifier.padding(10.dp).height(90.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                tab.title, fontSize = 13.sp, color = White90, fontWeight = FontWeight.Medium,
                                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
                            )
                            IconButton(onClick = { onClose(tab.id) }, modifier = Modifier.size(20.dp)) {
                                Icon(Icons.Default.Close, null, tint = White60, modifier = Modifier.size(16.dp))
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            tab.url, fontSize = 10.sp, color = White30,
                            maxLines = 2, overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryDialog(
    entries: List<HistoryEntry>,
    onOpen: (HistoryEntry) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = BgCard,
        title = { Text("History", color = White90, fontSize = 16.sp) },
        text = {
            if (entries.isEmpty()) {
                Text("No pages visited yet.", fontSize = 12.sp, color = White30)
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                    items(entries, key = { it.url }) { entry ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp)
                        ) {
                            Surface(onClick = { onOpen(entry) }, color = Color.Transparent) {
                                Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                    Text(
                                        entry.title, fontSize = 13.sp, color = White90,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        entry.url, fontSize = 10.sp, color = White30,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            HorizontalDivider(color = White10)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close", color = AccentGreen) }
        },
        dismissButton = {
            if (entries.isNotEmpty()) {
                TextButton(onClick = onClear) { Text("Clear", color = AccentRed) }
            }
        }
    )
}

/**
 * Hands a WebView download off to the system DownloadManager so it lands
 * in the device's normal Downloads folder with a notification, progress
 * bar, and retry-on-failure — same as any browser download. Cookies are
 * copied over explicitly because DownloadManager makes its own network
 * request outside the WebView, so it doesn't automatically inherit the
 * WebView's session.
 *
 * Only used for real http/https download URLs. `blob:` URLs (client-built
 * files like the "Download .md" report) are handled separately — see
 * [BlobDownloadInterface] — since DownloadManager cannot fetch them.
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

/**
 * JS bridge for "blob:" downloads (e.g. Testmate's "Download .md" report
 * button, which builds the file client-side via `URL.createObjectURL`).
 * The page reads the blob back out with `fetch` + `FileReader.readAsDataURL`
 * and posts the resulting base64 data URL here, since a `blob:` URL only
 * has meaning inside that page's JS heap and DownloadManager has no way to
 * fetch it over the network.
 *
 * Callbacks land on the WebView's internal JS thread, not the main thread,
 * so file I/O happens here directly (small report files) and any UI
 * feedback (Toast) is posted back to the main looper.
 */
private class BlobDownloadInterface(private val context: Context) {

    @JavascriptInterface
    fun onBase64Data(dataUrl: String, fileName: String) {
        try {
            // dataUrl looks like "data:text/markdown;base64,SGVsbG8...".
            val base64 = dataUrl.substringAfter(",", dataUrl)
            val bytes = Base64.decode(base64, Base64.DEFAULT)
            saveBytesToDownloads(context, bytes, fileName)
            postToast("Downloaded $fileName")
        } catch (e: Exception) {
            postToast("Download failed: ${e.message}")
        }
    }

    @JavascriptInterface
    fun onError(message: String?) {
        postToast("Download failed: ${message ?: "unknown error"}")
    }

    private fun postToast(message: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }
}

/**
 * Writes raw bytes straight to the device's Downloads folder. Uses
 * MediaStore on API 29+ (scoped storage — no legacy storage permission
 * needed), and falls back to the plain external Downloads directory +
 * `DownloadManager.addCompletedDownload` (so it still shows up in the
 * system Downloads app/notification) below that, matching the existing
 * pre-Android-10 WRITE_EXTERNAL_STORAGE allowance already declared in the
 * manifest for [downloadFile].
 */
private fun saveBytesToDownloads(context: Context, bytes: ByteArray, fileName: String) {
    val mimeType = MimeTypeMap.getSingleton()
        .getMimeTypeFromExtension(fileName.substringAfterLast('.', ""))
        ?: "text/markdown"

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("Could not create download entry")
        resolver.openOutputStream(uri)?.use { it.write(bytes) }
            ?: throw IllegalStateException("Could not open output stream")
    } else {
        @Suppress("DEPRECATION")
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        downloadsDir.mkdirs()
        val file = File(downloadsDir, fileName)
        FileOutputStream(file).use { it.write(bytes) }

        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        @Suppress("DEPRECATION")
        downloadManager.addCompletedDownload(
            fileName, fileName, true, mimeType, file.absolutePath, bytes.size.toLong(), true
        )
    }
}
