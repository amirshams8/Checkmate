package com.checkmate.automation

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.checkmate.workmode.WorkModeManager
import java.util.ArrayDeque

class AppAutomationService : AccessibilityService() {

    private val TAG      = "WA_SEND"
    private val WHATSAPP = "com.whatsapp"

    private var typingAttempted = false
    private var lastWindowClass = ""

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "=== AutomationService CONNECTED ===")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString() ?: return

        // Work Mode block
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val blockedApps = WorkModeManager.getBlockedApps()
            if (pkg in blockedApps) {
                performGlobalAction(GLOBAL_ACTION_HOME)
                return
            }
        }

        if (pkg != WHATSAPP) return
        if (!AutomationEngine.hasPendingMessage()) return

        Log.d(TAG, "WA event type=${event.eventType} class=${event.className} typingAttempted=$typingAttempted")

        // Only act on window state changes — not content changes (fires hundreds of times)
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        val cls = event.className?.toString() ?: ""
        if (cls != lastWindowClass) {
            Log.d(TAG, "New WA window '$cls' — resetting typingAttempted")
            typingAttempted = false
            lastWindowClass = cls
        }

        if (typingAttempted) return

        val root = rootInActiveWindow ?: run {
            Log.e(TAG, "rootInActiveWindow null")
            return
        }

        val found = tryTypeAndSend(root)
        if (!found) {
            Log.w(TAG, "Input not found — retrying in 1200ms")
            Handler(Looper.getMainLooper()).postDelayed({
                if (!AutomationEngine.hasPendingMessage()) return@postDelayed
                rootInActiveWindow?.let { tryTypeAndSend(it) }
            }, 1200)
        }
    }

    private fun tryTypeAndSend(root: AccessibilityNodeInfo): Boolean {
        // Peek — do NOT consume until SET_TEXT succeeds
        val message = AutomationEngine.peekPendingWhatsAppMessage() ?: return false
        Log.d(TAG, "tryTypeAndSend msg='${message.take(40)}'")

        val inputNode =
            findNodeById(root, "com.whatsapp:id/entry")
                ?.also { Log.d(TAG, "input found via id/entry") }
            ?: findNodeById(root, "com.whatsapp:id/conversation_entry")
                ?.also { Log.d(TAG, "input found via id/conversation_entry") }
            ?: findNodeByClass(root, "android.widget.EditText")
                ?.also { Log.d(TAG, "input found via EditText fallback") }

        if (inputNode == null) {
            Log.e(TAG, "input field not found")
            return false
        }

        typingAttempted = true
        inputNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)

        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, message)
        }
        val textSet = inputNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        Log.d(TAG, "ACTION_SET_TEXT=$textSet")

        if (!textSet) {
            Log.e(TAG, "SET_TEXT failed — message kept in queue")
            return true
        }

        // Consume only after successful text set
        AutomationEngine.consumePendingWhatsAppMessage()

        Handler(Looper.getMainLooper()).postDelayed({
            val freshRoot = rootInActiveWindow ?: run {
                Log.e(TAG, "send: root null")
                return@postDelayed
            }
            val sendNode =
                findNodeById(freshRoot, "com.whatsapp:id/send")
                    ?.also { Log.d(TAG, "send btn via id/send") }
                ?: findNodeByDesc(freshRoot, "Send")
                    ?.also { Log.d(TAG, "send btn via desc") }

            if (sendNode != null) {
                val sent = sendNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                Log.d(TAG, "send clicked=$sent")
            } else {
                Log.e(TAG, "send button not found")
            }
        }, 500)

        return true
    }

    private fun findNodeById(root: AccessibilityNodeInfo, id: String): AccessibilityNodeInfo? =
        root.findAccessibilityNodeInfosByViewId(id)?.firstOrNull()

    private fun findNodeByClass(root: AccessibilityNodeInfo, className: String): AccessibilityNodeInfo? {
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.add(root)
        while (stack.isNotEmpty()) {
            val node = stack.removeFirst()
            if (node.className?.toString() == className && node.isEditable) return node
            for (i in 0 until node.childCount) node.getChild(i)?.let { stack.add(it) }
        }
        return null
    }

    private fun findNodeByDesc(root: AccessibilityNodeInfo, desc: String): AccessibilityNodeInfo? {
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.add(root)
        while (stack.isNotEmpty()) {
            val node = stack.removeFirst()
            if (node.contentDescription?.toString()?.contains(desc, ignoreCase = true) == true
                && node.isClickable) return node
            for (i in 0 until node.childCount) node.getChild(i)?.let { stack.add(it) }
        }
        return null
    }

    override fun onInterrupt() { Log.d(TAG, "interrupted") }
}
