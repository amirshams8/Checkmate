package com.checkmate.automation

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.checkmate.workmode.WorkModeManager
import java.util.ArrayDeque

class AppAutomationService : AccessibilityService() {

    private val TAG      = "CheckmateAutomation"
    private val WHATSAPP = "com.whatsapp"

    // Track whether we already attempted to type in this WhatsApp session
    // so we don't re-fire on every subsequent content-changed event
    private var typingAttempted = false
    private var lastWhatsAppPkg = ""

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "Automation service connected — watching: com.whatsapp, com.checkmate")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString() ?: return

        // ── Work Mode: block distraction apps ──────────────────────────────
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val blockedApps = WorkModeManager.getBlockedApps()
            if (pkg in blockedApps) {
                Log.d(TAG, "Blocking distraction app: $pkg")
                performGlobalAction(GLOBAL_ACTION_HOME)
                return
            }
        }

        // ── WhatsApp: type+send queued message ─────────────────────────────
        if (pkg != WHATSAPP) return
        if (!AutomationEngine.hasPendingMessage()) return

        Log.d(TAG, "WhatsApp event: type=${AccessibilityEvent.eventTypeToString(event.eventType)} class=${event.className}")

        // Reset typing flag when WhatsApp is freshly opened
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val className = event.className?.toString() ?: ""
            Log.d(TAG, "WhatsApp window state changed: $className")
            // Reset on any new WhatsApp window — Conversation is the chat screen
            if (pkg != lastWhatsAppPkg) {
                typingAttempted = false
                lastWhatsAppPkg = pkg
            }
            if (className.contains("Conversation", ignoreCase = true) ||
                className.contains("DeepLink", ignoreCase = true) ||
                className.contains("Main", ignoreCase = true)) {
                typingAttempted = false
            }
        }

        if (typingAttempted) return

        val root = rootInActiveWindow ?: run {
            Log.w(TAG, "rootInActiveWindow is null — skipping")
            return
        }

        // Try immediately
        val found = tryTypeAndSend(root)
        if (!found) {
            // WhatsApp chat UI sometimes takes ~800ms to fully inflate after DeepLink resolves.
            // Retry after a short delay if input field wasn't found on first attempt.
            Log.d(TAG, "Input field not found immediately — retrying in 800ms")
            Handler(Looper.getMainLooper()).postDelayed({
                val freshRoot = rootInActiveWindow
                if (freshRoot != null && AutomationEngine.hasPendingMessage()) {
                    tryTypeAndSend(freshRoot)
                }
            }, 800)
        }
    }

    /**
     * Finds the WhatsApp message input field, sets text, then clicks send.
     * Returns true if the input field was found (regardless of send success).
     */
    private fun tryTypeAndSend(root: AccessibilityNodeInfo): Boolean {
        val message = AutomationEngine.peekPendingWhatsAppMessage() ?: return false

        // 1. Find input field — try resource ID first, fall back to EditText class
        val inputNode = findNodeById(root, "com.whatsapp:id/entry")
            ?: findNodeById(root, "com.whatsapp:id/conversation_entry")
            ?: findNodeByClass(root, "android.widget.EditText")

        if (inputNode == null) {
            Log.w(TAG, "Input field not found. Dumping visible nodes:")
            dumpNodeTree(root, 0)
            return false
        }

        Log.d(TAG, "Found input field: id=${inputNode.viewIdResourceName} class=${inputNode.className}")
        typingAttempted = true

        // 2. Focus the field
        inputNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)

        // 3. Set text
        val args = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                message
            )
        }
        val textSet = inputNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        Log.d(TAG, "Text set result: $textSet — message: ${message.take(50)}")

        if (!textSet) {
            Log.e(TAG, "ACTION_SET_TEXT failed — accessibility permission may be missing or WhatsApp blocked it")
            return true // field was found, just couldn't set text
        }

        // Consume message only after successful text set
        AutomationEngine.consumePendingWhatsAppMessage()

        // 4. Click send after short delay (WhatsApp needs a tick to enable send button)
        Handler(Looper.getMainLooper()).postDelayed({
            val freshRoot = rootInActiveWindow ?: return@postDelayed
            val sendNode = findNodeById(freshRoot, "com.whatsapp:id/send")
                ?: findNodeByDesc(freshRoot, "Send")
                ?: findNodeByDesc(freshRoot, "send")

            if (sendNode != null) {
                val sent = sendNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                Log.d(TAG, "Send button clicked: $sent — id=${sendNode.viewIdResourceName}")
            } else {
                Log.e(TAG, "Send button not found after text set — dumping nodes:")
                dumpNodeTree(freshRoot, 0)
            }
        }, 400)

        return true
    }

    // ── Node finders ──────────────────────────────────────────────────────────

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

    /** Dumps top 3 levels of the node tree for debugging when field not found */
    private fun dumpNodeTree(node: AccessibilityNodeInfo, depth: Int) {
        if (depth > 3) return
        Log.d(TAG, "  ".repeat(depth) +
                "id=${node.viewIdResourceName} " +
                "class=${node.className} " +
                "desc=${node.contentDescription} " +
                "editable=${node.isEditable} " +
                "clickable=${node.isClickable}")
        for (i in 0 until node.childCount) node.getChild(i)?.let { dumpNodeTree(it, depth + 1) }
    }

    override fun onInterrupt() { Log.d(TAG, "Service interrupted") }
}
