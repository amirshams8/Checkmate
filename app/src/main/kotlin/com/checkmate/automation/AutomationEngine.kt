package com.checkmate.automation

import android.util.Log

object AutomationEngine {

    private const val TAG = "AutomationEngine"
    private var pendingWhatsAppMessage: String? = null

    fun queueWhatsAppMessage(text: String) {
        pendingWhatsAppMessage = text
        Log.d(TAG, "WhatsApp message queued: ${text.take(60)}")
    }

    /** Peek without consuming — used to check if message exists before committing */
    fun peekPendingWhatsAppMessage(): String? = pendingWhatsAppMessage

    /** Consume — call only after successful ACTION_SET_TEXT */
    fun consumePendingWhatsAppMessage(): String? {
        val msg = pendingWhatsAppMessage
        pendingWhatsAppMessage = null
        Log.d(TAG, "WhatsApp message consumed")
        return msg
    }

    fun hasPendingMessage(): Boolean = pendingWhatsAppMessage != null
}
