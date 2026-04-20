package com.checkmate.automation

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.checkmate.core.CheckmatePrefs
import com.checkmate.workmode.WorkModeManager
import java.util.ArrayDeque

class AppAutomationService : AccessibilityService() {

    private val TAG      = "CheckmateAutomation"
    private val WHATSAPP = "com.whatsapp"

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "Automation service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val pkg = event.packageName?.toString() ?: return

        // ── Work Mode: block distraction apps ──
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val blockedApps = WorkModeManager.getBlockedApps()
            if (pkg in blockedApps) {
                Log.d(TAG, "Blocking app: $pkg")
                performGlobalAction(GLOBAL_ACTION_HOME)
                return
            }
        }

        // ── WhatsApp: type+send queued message ──
        if (pkg == WHATSAPP && AutomationEngine.hasPendingMessage()) {
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
                event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
                val root = rootInActiveWindow ?: return
                tryTypeAndSend(root)
            }
        }
    }

    private fun tryTypeAndSend(root: AccessibilityNodeInfo) {
        val message = AutomationEngine.consumePendingWhatsAppMessage() ?: return

        // Find input field
        val inputNode = findNodeById(root, "com.whatsapp:id/entry")
            ?: findNodeByClass(root, "android.widget.EditText")
            ?: return

        // Focus and type
        inputNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        val args = android.os.Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, message)
        inputNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)

        // Small delay then find send button
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            val freshRoot = rootInActiveWindow ?: return@postDelayed
            val sendNode  = findNodeById(freshRoot, "com.whatsapp:id/send")
                ?: findNodeByDesc(freshRoot, "Send")
            sendNode?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            Log.d(TAG, "WhatsApp message sent")
        }, 600)
    }

    // ── Node finder helpers (ported from JarvisMini NodeFinder) ──

    private fun findNodeById(root: AccessibilityNodeInfo, id: String): AccessibilityNodeInfo? {
        val results = root.findAccessibilityNodeInfosByViewId(id)
        return results?.firstOrNull()
    }

    private fun findNodeByClass(root: AccessibilityNodeInfo, className: String): AccessibilityNodeInfo? {
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.add(root)
        while (stack.isNotEmpty()) {
            val node = stack.removeFirst()
            if (node.className?.toString() == className) return node
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

    override fun onInterrupt() { Log.d(TAG, "Service interrupted") }
}
