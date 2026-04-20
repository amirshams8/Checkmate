package com.checkmate.automation

import android.util.Log

/**
 * Central automation coordinator.
 * Queues WhatsApp messages to be typed+sent by AppAutomationService
 * once the target chat is open.
 */
object AutomationEngine {

    private const val TAG = "AutomationEngine"
    private var pendingWhatsAppMessage: String? = null

    fun queueWhatsAppMessage(text: String) {
        pendingWhatsAppMessage = text
        Log.d(TAG, "WhatsApp message queued: ${text.take(40)}")
    }

    fun consumePendingWhatsAppMessage(): String? {
        val msg = pendingWhatsAppMessage
        pendingWhatsAppMessage = null
        return msg
    }

    fun hasPendingMessage(): Boolean = pendingWhatsAppMessage != null
}
