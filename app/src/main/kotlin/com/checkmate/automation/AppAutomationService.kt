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

    private val TAG      = "WA_SEND"          // grep this tag in logcat
    private val WHATSAPP = "com.whatsapp"

    // FIX: was resetting only when pkg changed (never, since it's always com.whatsapp)
    // Now tracks last window class — resets whenever WA shows a new screen
    private var typingAttempted   = false
    private var lastWindowClass   = ""

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

        val hasPending = AutomationEngine.hasPendingMessage()
        Log.d(TAG, "WA event pkg=$pkg type=${event.eventType} class=${event.className} hasPending=$hasPending typingAttempted=$typingAttempted")

        if (!hasPending) return

        // ── Reset typingAttempted when WA shows a new window/screen ──────────
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val cls = event.className?.toString() ?: ""
            Log.d(TAG, "WA window changed: cls='$cls' lastWindowClass='$lastWindowClass'")

            if (cls != lastWindowClass) {
                Log.d(TAG, ">>> New WA window detected — resetting typingAttempted")
                typingAttempted = false
                lastWindowClass = cls
            } else {
                Log.d(TAG, "Same window class — typingAttempted stays $typingAttempted")
            }
        }

        if (typingAttempted) {
            Log.d(TAG, "typingAttempted=true — skipping this event")
            return
        }

        val root = rootInActiveWindow ?: run {
            Log.e(TAG, "rootInActiveWindow is NULL — cannot proceed")
            return
        }

        Log.d(TAG, "Attempting tryTypeAndSend immediately...")
        val found = tryTypeAndSend(root)
        if (!found) {
            Log.w(TAG, "Input field NOT found — scheduling retry in 1200ms")
            Handler(Looper.getMainLooper()).postDelayed({
                val freshRoot = rootInActiveWindow
                if (freshRoot == null) {
                    Log.e(TAG, "RETRY: rootInActiveWindow still null")
                    return@postDelayed
                }
                if (!AutomationEngine.hasPendingMessage()) {
                    Log.w(TAG, "RETRY: no pending message anymore (was it consumed?)")
                    return@postDelayed
                }
                Log.d(TAG, "RETRY: calling tryTypeAndSend after delay...")
                tryTypeAndSend(freshRoot)
            }, 1200)
        }
    }

    private fun tryTypeAndSend(root: AccessibilityNodeInfo): Boolean {
        val message = AutomationEngine.peekPendingWhatsAppMessage()
        if (message == null) {
            Log.e(TAG, "tryTypeAndSend: peekPendingWhatsAppMessage() returned null — nothing to send")
            return false
        }
        Log.d(TAG, "tryTypeAndSend: message='${message.take(60)}...'")

        // Dump top-level nodes BEFORE searching so we know what's visible
        Log.d(TAG, "--- NODE DUMP (depth 3) ---")
        dumpNodeTree(root, 0)
        Log.d(TAG, "--- END NODE DUMP ---")

        // 1. Find input field
        val inputNode =
            findNodeById(root, "com.whatsapp:id/entry")
                ?.also { Log.d(TAG, "Found input via id/entry") }
            ?: findNodeById(root, "com.whatsapp:id/conversation_entry")
                ?.also { Log.d(TAG, "Found input via id/conversation_entry") }
            ?: findNodeByClass(root, "android.widget.EditText")
                ?.also { Log.d(TAG, "Found input via EditText class fallback") }

        if (inputNode == null) {
            Log.e(TAG, "INPUT FIELD NOT FOUND — WA screen may not be a chat yet")
            return false
        }

        Log.d(TAG, "Input: id=${inputNode.viewIdResourceName} editable=${inputNode.isEditable} enabled=${inputNode.isEnabled}")
        typingAttempted = true

        // 2. Focus
        val clicked = inputNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        Log.d(TAG, "ACTION_CLICK on input: $clicked")

        // 3. Set text
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, message)
        }
        val textSet = inputNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        Log.d(TAG, "ACTION_SET_TEXT result: $textSet")

        if (!textSet) {
            Log.e(TAG, "SET_TEXT FAILED — WA may have blocked accessibility input. Node info: class=${inputNode.className} pkg=${inputNode.packageName} editable=${inputNode.isEditable}")
            // Don't consume — leave message queued so user can retry
            return true
        }

        AutomationEngine.consumePendingWhatsAppMessage()
        Log.d(TAG, "Message consumed from queue after successful SET_TEXT")

        // 4. Click send after delay
        Handler(Looper.getMainLooper()).postDelayed({
            val freshRoot = rootInActiveWindow ?: run {
                Log.e(TAG, "SEND: rootInActiveWindow null — cannot click send")
                return@postDelayed
            }
            val sendNode =
                findNodeById(freshRoot, "com.whatsapp:id/send")
                    ?.also { Log.d(TAG, "Send button found via id/send") }
                ?: findNodeByDesc(freshRoot, "Send")
                    ?.also { Log.d(TAG, "Send button found via contentDesc 'Send'") }
                ?: findNodeByDesc(freshRoot, "send")
                    ?.also { Log.d(TAG, "Send button found via contentDesc 'send'") }

            if (sendNode != null) {
                val sent = sendNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                Log.d(TAG, "SEND BUTTON CLICKED: $sent — id=${sendNode.viewIdResourceName}")
            } else {
                Log.e(TAG, "SEND BUTTON NOT FOUND after text set — dumping fresh nodes:")
                dumpNodeTree(freshRoot, 0)
            }
        }, 500)

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

    private fun dumpNodeTree(node: AccessibilityNodeInfo, depth: Int) {
        if (depth > 3) return
        Log.d(TAG, "  ".repeat(depth) +
            "id=${node.viewIdResourceName} " +
            "cls=${node.className} " +
            "desc='${node.contentDescription}' " +
            "editable=${node.isEditable} " +
            "clickable=${node.isClickable} " +
            "enabled=${node.isEnabled} " +
            "text='${node.text?.take(30)}'")
        for (i in 0 until node.childCount) node.getChild(i)?.let { dumpNodeTree(it, depth + 1) }
    }

    override fun onInterrupt() { Log.d(TAG, "Service interrupted") }
}
