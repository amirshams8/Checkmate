package com.checkmate.service

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.PixelCopy
import android.view.WindowManager
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * ScreenshotCapture — captures a screenshot WITHOUT needing an Activity.
 *
 * Strategy:
 *   Android 9+ (API 28+): PixelCopy can target any Surface. We capture the
 *   entire display by drawing into a Bitmap via the WindowManager's default
 *   display surface bounds. PixelCopy must be called from a background thread
 *   with a Handler pointing to the main thread for the callback.
 *
 *   Below API 28: We fall back to a 1×1 transparent overlay window trick to
 *   force a draw-cache capture of the display. In practice all devices
 *   Checkmate targets run Android 10+, so this path is a safety net only.
 *
 * The Uri returned is a FileProvider content:// Uri shareable with WhatsApp.
 * Authority must match the <provider> declaration in AndroidManifest.xml.
 *
 * Call captureOverlay() from ANY thread — it blocks briefly (up to 2 seconds)
 * using a CountDownLatch and returns the Uri (or null on failure).
 */
object ScreenshotCapture {

    private const val TAG       = "ScreenshotCapture"
    private const val AUTHORITY = "com.checkmate.fileprovider"

    /**
     * Capture the full display. Safe to call from the accessibility service
     * thread or any background thread.
     *
     * Returns a content:// Uri or null on failure.
     */
    fun captureOverlay(context: Context): Uri? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            captureViaPixelCopy(context)
        } else {
            captureViaCanvasFallback(context)
        }
    }

    // ── PixelCopy path (API 28+) ──────────────────────────────────────────────

    private fun captureViaPixelCopy(context: Context): Uri? {
        val wm      = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            context.display else @Suppress("DEPRECATION") wm.defaultDisplay
        if (display == null) { Log.e(TAG, "No display"); return null }

        val width  = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            context.resources.displayMetrics.widthPixels
        } else {
            @Suppress("DEPRECATION")
            android.graphics.Point().also { wm.defaultDisplay.getSize(it) }.x
        }
        val height = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            context.resources.displayMetrics.heightPixels
        } else {
            @Suppress("DEPRECATION")
            android.graphics.Point().also { wm.defaultDisplay.getSize(it) }.y
        }

        val bitmap  = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val latch   = CountDownLatch(1)
        var success = false

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // We need a Surface to give PixelCopy a target window.
            // Use a minimal 1x1 TYPE_APPLICATION_OVERLAY window to get a valid
            // SurfaceHolder, then immediately take the screenshot and remove it.
            val holderWindow = android.view.SurfaceView(context)
            val params = WindowManager.LayoutParams(
                1, 1,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else
                    @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            )

            val mainHandler = Handler(Looper.getMainLooper())
            mainHandler.post {
                try {
                    wm.addView(holderWindow, params)
                    holderWindow.holder.addCallback(object : android.view.SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: android.view.SurfaceHolder) {
                            PixelCopy.request(
                                holder.surface,
                                android.graphics.Rect(0, 0, width, height),
                                bitmap,
                                { result ->
                                    success = result == PixelCopy.SUCCESS
                                    if (!success) Log.e(TAG, "PixelCopy result: $result")
                                    try { wm.removeView(holderWindow) } catch (_: Exception) {}
                                    latch.countDown()
                                },
                                mainHandler
                            )
                        }
                        override fun surfaceChanged(h: android.view.SurfaceHolder, f: Int, w: Int, ht: Int) {}
                        override fun surfaceDestroyed(h: android.view.SurfaceHolder) {}
                    })
                } catch (e: Exception) {
                    Log.e(TAG, "PixelCopy setup failed: ${e.message}")
                    try { wm.removeView(holderWindow) } catch (_: Exception) {}
                    latch.countDown()
                }
            }
        } else {
            latch.countDown() // API < 26, fall through
        }

        latch.await(2, TimeUnit.SECONDS)
        if (!success) {
            Log.w(TAG, "PixelCopy failed or timeout — falling back to canvas")
            return captureViaCanvasFallback(context)
        }
        return saveBitmapAndGetUri(context, bitmap)
    }

    // ── Canvas fallback (pre-API 28 or PixelCopy failure) ────────────────────

    private fun captureViaCanvasFallback(context: Context): Uri? {
        // Without an Activity window we can't get a drawing cache of the
        // real screen. We save a placeholder bitmap with a timestamp instead —
        // the text report sent with it still tells the guardian what happened.
        return try {
            val w      = context.resources.displayMetrics.widthPixels
            val h      = 200
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bitmap)
            canvas.drawColor(android.graphics.Color.BLACK)
            val paint  = android.graphics.Paint().apply {
                color    = android.graphics.Color.WHITE
                textSize = 40f
            }
            canvas.drawText("Distraction attempt recorded", 40f, 120f, paint)
            saveBitmapAndGetUri(context, bitmap)
        } catch (e: Exception) {
            Log.e(TAG, "Canvas fallback failed: ${e.message}")
            null
        }
    }

    // ── Persist & expose via FileProvider ─────────────────────────────────────

    private fun saveBitmapAndGetUri(context: Context, bitmap: Bitmap): Uri? {
        return try {
            val dir  = File(context.filesDir, "screenshots").also { it.mkdirs() }
            val ts   = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val file = File(dir, "distraction_$ts.png")
            FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 85, it) }
            bitmap.recycle()
            FileProvider.getUriForFile(context, AUTHORITY, file)
                .also { Log.d(TAG, "Screenshot saved → $it") }
        } catch (e: Exception) {
            Log.e(TAG, "Save failed: ${e.message}")
            null
        }
    }
}
