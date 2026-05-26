package com.checkmate.service

import android.content.Context
import android.net.Uri

/**
 * WhatsAppReportService — thin facade over GuardianNotifier.
 * Prefer calling GuardianNotifier directly in new code.
 * Kept for back-compat with any call sites using this object name.
 */
object WhatsAppReportService {

    /**
     * Sends a text report to the guardian.
     * Uses method A (whatsapp://send) with method B (wa.me) fallback.
     * Optionally attach a screenshot Uri from ScreenshotSharer.capture().
     */
    fun sendReport(context: Context, reportText: String, screenshotUri: Uri? = null) {
        GuardianNotifier.sendReport(context, reportText, screenshotUri)
    }
}
