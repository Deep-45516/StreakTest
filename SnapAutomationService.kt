package com.example.streaktest

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class SnapAutomationService : AccessibilityService() {

    companion object {
        private const val TAG = "StreakSnapAuto"
        private const val SNAPCHAT = "com.snapchat.android"
        private const val RECIPIENT_NAME = "Krisha"
        private const val RECIPIENT_USERNAME = "krisharkive2"

        // Coordinates measured from the user's 828x1792 screenshots.
        private const val SHORTCUT_X = 0.268f
        private const val SHORTCUT_Y = 0.115f
        private const val SEND_TO_X = 0.820f
        private const val SEND_TO_Y = 0.975f
    }

    // 0 composer -> 1 recipient list -> 2 shortcut results -> 3 done
    private var stage = 0
    private var stageStarted = 0L
    private var lastAction = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        stage = 0
        stageStarted = SystemClock.uptimeMillis()
        Log.d(TAG, "Service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.packageName?.toString() != SNAPCHAT) return

        val now = SystemClock.uptimeMillis()
        val root = rootInActiveWindow ?: return

        // Avoid stacking gestures while the UI is changing.
        if (now - lastAction < 1200L) return

        when (stage) {
            0 -> handleComposer(root, now)
            1 -> handleSendToList(root, now)
            2 -> handleRecipient(root, now)
            3 -> finishIfSelected(root, now)
        }
    }

    private fun handleComposer(root: AccessibilityNodeInfo, now: Long) {
        // If the Send To recipient screen is already open, don't tap the
        // composer button again.
        if (looksLikeSendToList(root)) {
            stage = 1
            stageStarted = now
            Log.d(TAG, "Stage 1: Send To list detected")
            return
        }

        // Bottom Send To button on the Snap composer.
        if (hasBottomSendTo(root)) {
            Log.d(TAG, "Stage 0: tapping composer Send To")
            tapRelative(SEND_TO_X, SEND_TO_Y)
            stage = 1
            stageStarted = now
            lastAction = now
            return
        }

        // Wait up to 8 seconds for Snapchat to finish loading the composer.
        if (now - stageStarted > 8000L) {
            Log.w(TAG, "Composer did not appear; resetting state")
            stageStarted = now
        }
    }

    private fun handleSendToList(root: AccessibilityNodeInfo, now: Long) {
        // We deliberately use a coordinate for the shortcut because Snapchat
        // renders the emoji shortcut as custom UI that may not expose a useful
        // accessibility text node.
        if (now - stageStarted < 2500L) return

        Log.d(TAG, "Stage 1: tapping 💯 shortcut")
        tapRelative(SHORTCUT_X, SHORTCUT_Y)
        stage = 2
        stageStarted = now
        lastAction = now
    }

    private fun handleRecipient(root: AccessibilityNodeInfo, now: Long) {
        // Give the shortcut-filtered list time to render.
        if (now - stageStarted < 2500L) return

        val recipient = findRecipient(root)
        if (recipient != null) {
            Log.d(TAG, "Stage 2: selecting Krisha")
            if (clickNodeOrParent(recipient)) {
                stage = 3
                stageStarted = now
                lastAction = now
            }
            return
        }

        // Retry for a while instead of clicking a random person.
        if (now - stageStarted > 10000L) {
            Log.w(TAG, "Krisha not exposed by accessibility; stopping safely")
            stage = 0
            stageStarted = now
        }
    }

    private fun finishIfSelected(root: AccessibilityNodeInfo, now: Long) {
        // Selecting the recipient is the final action on this phone: the user
        // observed that Snapchat sends immediately after selecting the person.
        // Wait briefly, then reset so a later test can start cleanly.
        if (now - stageStarted > 1800L) {
            Log.d(TAG, "Automation complete")
            stage = 0
            stageStarted = now
        }
    }

    private fun looksLikeSendToList(root: AccessibilityNodeInfo): Boolean {
        val hasAll = findExactText(root, "All") != null
        val hasNewFriends = findExactText(root, "New Friends") != null
        return hasAll && hasNewFriends
    }

    private fun hasBottomSendTo(root: AccessibilityNodeInfo): Boolean {
        val h = resources.displayMetrics.heightPixels
        val nodes = mutableListOf<AccessibilityNodeInfo>()
        collectNodes(root, nodes)
        for (node in nodes) {
            if (!node.isVisibleToUser || !node.isEnabled) continue
            val text = node.text?.toString()?.trim() ?: ""
            val desc = node.contentDescription?.toString()?.trim() ?: ""
            if (!text.equals("Send To", true) && !desc.equals("Send To", true)) continue
            val r = Rect()
            node.getBoundsInScreen(r)
            if (r.centerY() > h * 0.78f) return true
        }
        return false
    }

    private fun findRecipient(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        findTextContaining(root, RECIPIENT_NAME)?.let { return it }
        findTextContaining(root, RECIPIENT_USERNAME)?.let { return it }
        return null
    }

    private fun findExactText(root: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        val nodes = root.findAccessibilityNodeInfosByText(text)
        for (node in nodes) {
            if (node.isVisibleToUser && node.isEnabled &&
                node.text?.toString()?.trim()?.equals(text, true) == true) return node
        }
        return null
    }

    private fun findTextContaining(root: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        val lower = text.lowercase()
        val direct = root.findAccessibilityNodeInfosByText(text)
        for (node in direct) {
            val t = node.text?.toString()?.lowercase() ?: ""
            if (node.isVisibleToUser && node.isEnabled && t.contains(lower)) return node
        }

        val all = mutableListOf<AccessibilityNodeInfo>()
        collectNodes(root, all)
        for (node in all) {
            if (!node.isVisibleToUser || !node.isEnabled) continue
            val t = node.text?.toString()?.lowercase() ?: ""
            val d = node.contentDescription?.toString()?.lowercase() ?: ""
            if (t.contains(lower) || d.contains(lower)) return node
        }
        return null
    }

    private fun collectNodes(root: AccessibilityNodeInfo, output: MutableList<AccessibilityNodeInfo>) {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            output.add(node)
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let(queue::addLast)
            }
        }
    }

    private fun clickNodeOrParent(node: AccessibilityNodeInfo): Boolean {
        if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        var parent = node.parent
        repeat(5) {
            if (parent != null) {
                if (parent.isVisibleToUser && parent.isEnabled &&
                    parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
                parent = parent.parent
            }
        }
        return false
    }

    private fun tapRelative(xFraction: Float, yFraction: Float) {
        val metrics = resources.displayMetrics
        val x = metrics.widthPixels * xFraction
        val y = metrics.heightPixels * yFraction
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, 120L))
            .build()
        dispatchGesture(gesture, null, null)
    }

    override fun onInterrupt() {
        Log.d(TAG, "Service interrupted")
    }
}
