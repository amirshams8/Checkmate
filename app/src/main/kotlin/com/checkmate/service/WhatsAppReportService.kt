package com.checkmate.service

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.checkmate.automation.AutomationEngine
import com.checkmate.core.CheckmatePrefs

object WhatsAppReportService {

    /**
     * Opens WhatsApp chat with guardian via wa.me link,
     * then AutomationEngine types and sends the report.
     */
    fun sendReport(context: Context, reportText: String) {
        val guardianNumber = CheckmatePrefs.getString("guardian_number") ?: return
        val clean = guardianNumber.replace(Regex("[^0-9]"), "")
        val uri   = Uri.parse("https://wa.me/$clean")
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        // Queue the message into AutomationEngine to type+send once WhatsApp opens
        AutomationEngine.queueWhatsAppMessage(reportText)
        context.startActivity(intent)
    }
}
