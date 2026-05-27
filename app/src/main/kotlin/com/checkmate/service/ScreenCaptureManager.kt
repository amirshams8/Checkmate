package com.checkmate.service

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * ScreenCaptureManager — real screen capture via MediaProjection.
 *
 * Usage:
 *   1. Call createCaptureIntent(context) and launch it with startActivityForResult.
 *   2. In onActivityResult pass the resultCode + data to storeProjectionToken().
 *   3. Call capture(context) from any thread — blocks up to 2s, returns Uri or null.
 *   4. Call release() on Work Mode end to free the projection.
 */
object ScreenCaptureManager {

    private const val TAG       = "ScreenCaptureManager"
    private const val AUTHORITY = "com.checkmate.fileprovider"

    private var mediaProjection: MediaProjection? = null

    // ── Setup ─────────────────────────────────────────────────────────────────

    fun createCaptureIntent(context: Context): Intent {
        val mgr = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        return mgr.createScreenCaptureIntent()
    }

    fun storeProjectionToken(context: Context, resultCode: Int, data: Intent?) {
        if (data == null) { Log.w(TAG, "Projection data null — user denied"); return }
        release()
        val mgr = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mgr.getMediaProjection(resultCode, data)
        Log.d(TAG, "MediaProjection token stored")
    }

    fun isReady(): Boolean = mediaProjection != null

    fun release() {
        mediaProjection?.stop()
        mediaProjection = null
        Log.d(TAG, "MediaProjection released")
    }

    // ── Capture ───────────────────────────────────────────────────────────────

    /**
     * Captures the real screen. Safe to call from any thread.
     * Returns a FileProvider content:// Uri or null on failure.
     */
    fun capture(context: Context): Uri? {
        val projection = mediaProjection ?: run {
            Log.w(TAG, "No MediaProjection token — skipping screenshot")
            return null
        }

        val metrics = context.resources.displayMetrics
        val width   = metrics.widthPixels
        val height  = metrics.heightPixels
        val density = metrics.densityDpi

        val latch   = CountDownLatch(1)
        var bitmap: Bitmap? = null

        val imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        var virtualDisplay: VirtualDisplay? = null

        val handler = Handler(Looper.getMainLooper())

        imageReader.setOnImageAvailableListener({ reader ->
            try {
                val image  = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                val planes = image.planes
                val buffer = planes[0].buffer
                val pixelStride  = planes[0].pixelStride
                val rowStride    = planes[0].rowStride
                val rowPadding   = rowStride - pixelStride * width

                val bmp = Bitmap.createBitmap(
                    width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888
                )
                bmp.copyPixelsFromBuffer(buffer)
                // Crop to exact screen width (remove row padding)
                bitmap = Bitmap.createBitmap(bmp, 0, 0, width, height)
                bmp.recycle()
                image.close()
            } catch (e: Exception) {
                Log.e(TAG, "Image read failed: ${e.message}")
            } finally {
                latch.countDown()
            }
        }, handler)

        virtualDisplay = projection.createVirtualDisplay(
            "CheckmateCapture",
            width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader.surface,
            null, handler
        )

        latch.await(3, TimeUnit.SECONDS)

        virtualDisplay?.release()
        imageReader.close()

        val bmp = bitmap ?: run {
            Log.w(TAG, "Bitmap null after capture")
            return null
        }

        return saveBitmapAndGetUri(context, bmp)
    }

    // ── Persist ───────────────────────────────────────────────────────────────

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
