package com.checkmate.service

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.util.Log
import android.view.PixelCopy
import android.view.View
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * ScreenshotSharer — captures the current window/view as a PNG and returns
 * a FileProvider content:// Uri that WhatsApp can read.
 *
 * Usage (from any Composable's button click, in a coroutine or callback):
 *
 *   val context = LocalContext.current
 *   val activity = context as Activity
 *   ScreenshotSharer.captureAndShare(activity) { uri ->
 *       if (uri != null) {
 *           GuardianNotifier.sendReport(context, reportText, uri)
 *       }
 *   }
 *
 * Or to capture and return the Uri for later use:
 *
 *   val uri = ScreenshotSharer.capture(activity)
 *   GuardianNotifier.sendReport(context, reportText, uri)
 */
object ScreenshotSharer {

    private const val TAG       = "ScreenshotSharer"
    private const val AUTHORITY = "com.checkmate.fileprovider"

    /**
     * Capture the current window as a PNG.
     * On API 26+ uses PixelCopy (exact pixels, includes SurfaceViews).
     * On older APIs falls back to View.drawToBitmap (Canvas method).
     *
     * @param activity  The current Activity — needed for window reference
     * @param callback  Invoked on the main thread with the content Uri, or null on failure
     */
    fun captureAndShare(activity: Activity, callback: (Uri?) -> Unit) {
        val window = activity.window ?: run { callback(null); return }
        val decorView = window.decorView

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val bitmap = Bitmap.createBitmap(
                decorView.width, decorView.height, Bitmap.Config.ARGB_8888
            )
            PixelCopy.request(window, bitmap, { copyResult ->
                if (copyResult == PixelCopy.SUCCESS) {
                    val uri = saveBitmapAndGetUri(activity, bitmap)
                    activity.runOnUiThread { callback(uri) }
                } else {
                    Log.e(TAG, "PixelCopy failed: $copyResult — falling back to drawToBitmap")
                    val uri = captureViaCanvas(activity, decorView)
                    activity.runOnUiThread { callback(uri) }
                }
            }, android.os.Handler(android.os.Looper.getMainLooper()))
        } else {
            val uri = captureViaCanvas(activity, decorView)
            callback(uri)
        }
    }

    /**
     * Blocking version — call from a background thread / coroutine.
     * Returns content:// Uri or null.
     */
    fun capture(activity: Activity): Uri? {
        val decorView = activity.window?.decorView ?: return null
        return captureViaCanvas(activity, decorView)
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    private fun captureViaCanvas(context: Context, view: View): Uri? {
        return try {
            view.isDrawingCacheEnabled = true
            view.buildDrawingCache()
            val cached = view.drawingCache
            val bitmap = if (cached != null) Bitmap.createBitmap(cached) else return null
            view.isDrawingCacheEnabled = false
            saveBitmapAndGetUri(context, bitmap)
        } catch (e: Exception) {
            Log.e(TAG, "Canvas capture failed: ${e.message}")
            null
        }
    }

    private fun saveBitmapAndGetUri(context: Context, bitmap: Bitmap): Uri? {
        return try {
            // Save into files/screenshots/ — declared in file_paths.xml
            val dir = File(context.filesDir, "screenshots").also { it.mkdirs() }
            val ts  = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val file = File(dir, "checkmate_$ts.png")

            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 90, out)
            }
            bitmap.recycle()

            // Produce a content:// Uri via FileProvider
            FileProvider.getUriForFile(context, AUTHORITY, file)
                .also { Log.d(TAG, "Screenshot saved: $file → $it") }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save screenshot: ${e.message}")
            null
        }
    }

    /**
     * Delete screenshots older than 24 hours to avoid storage bloat.
     * Call this from CheckmateApp.onCreate() or a WorkManager task.
     */
    fun pruneOldScreenshots(context: Context) {
        val dir = File(context.filesDir, "screenshots")
        if (!dir.exists()) return
        val cutoff = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
        dir.listFiles()?.filter { it.lastModified() < cutoff }?.forEach {
            it.delete()
            Log.d(TAG, "Pruned screenshot: ${it.name}")
        }
    }
}
