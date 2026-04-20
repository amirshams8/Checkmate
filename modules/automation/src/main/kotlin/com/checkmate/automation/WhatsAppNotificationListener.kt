package com.checkmate.automation

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.checkmate.core.CheckmatePrefs

/**
 * Listens to WhatsApp notifications to detect guardian replies.
 * e.g. Guardian replies "OK" or "Call me" → logged / acted on.
 */
class WhatsAppNotificationListener : NotificationListenerService() {

    private const val TAG      = "WA_Listener"
    private const val WHATSAPP = "com.whatsapp"

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn?.packageName != WHATSAPP) return

        val extras = sbn.notification?.extras ?: return
        val text   = extras.getCharSequence("android.text")?.toString() ?: return
        val title  = extras.getCharSequence("android.title")?.toString() ?: return

        val guardianNumber = CheckmatePrefs.getString("guardian_number", null) ?: return
        Log.d(TAG, "WA notification from $title: $text")

        // Store last guardian message for potential reaction
        if (isGuardianMessage(title, guardianNumber)) {
            CheckmatePrefs.putString("last_guardian_reply", text)
            CheckmatePrefs.putLong("last_guardian_reply_at", System.currentTimeMillis())
            Log.d(TAG, "Guardian replied: $text")
        }
    }

    private fun isGuardianMessage(title: String, guardianNumber: String): Boolean {
        val clean = guardianNumber.replace(Regex("[^0-9]"), "")
        return title.contains(clean) || title.contains("Guardian", ignoreCase = true)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {}
}
