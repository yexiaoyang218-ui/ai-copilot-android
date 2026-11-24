package com.aisocial.copilot.overlay

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Writes the reply the user tapped into the chat app's input box.
 *
 * Deliberately passive:
 *  - it subscribes to no content-reading events;
 *  - it never reads, stores or uploads anything it can see;
 *  - it only acts when [fill] is called from the floating panel after an explicit tap;
 *  - it never presses "send".
 *
 * If the target app rejects ACTION_SET_TEXT we fall back to pasting whatever is
 * already on the clipboard (the overlay puts the reply there first).
 */
class ReplyFillService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Intentionally empty: we do not react to window/content changes.
    }

    override fun onInterrupt() = Unit

    private fun focusedEditable(): AccessibilityNodeInfo? {
        val root = runCatching { rootInActiveWindow }.getOrNull() ?: return null
        val focused = runCatching { root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }.getOrNull()
        if (focused != null && focused.isEditable) return focused
        return findEditable(root, 0)
    }

    private fun findEditable(node: AccessibilityNodeInfo?, depth: Int): AccessibilityNodeInfo? {
        node ?: return null
        if (depth > 24) return null
        if (runCatching { node.isEditable }.getOrDefault(false)) return node
        val count = runCatching { node.childCount }.getOrDefault(0)
        for (index in 0 until count) {
            val child = runCatching { node.getChild(index) }.getOrNull() ?: continue
            findEditable(child, depth + 1)?.let { return it }
        }
        return null
    }

    private fun applyText(text: String): Boolean {
        val target = focusedEditable() ?: return false
        val args = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                text
            )
        }
        if (runCatching {
                target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            }.getOrDefault(false)
        ) {
            return true
        }
        // Some chat apps ignore ACTION_SET_TEXT but honour focus + paste.
        runCatching { target.performAction(AccessibilityNodeInfo.ACTION_FOCUS) }
        runCatching { target.performAction(AccessibilityNodeInfo.ACTION_PASTE) }
        return false
    }

    companion object {

        @Volatile
        private var instance: ReplyFillService? = null

        val isConnected: Boolean get() = instance != null

        /**
         * Fills the currently focused editable field.
         * @return true when the text was written through ACTION_SET_TEXT.
         */
        fun fill(text: String): Boolean {
            val service = instance ?: return false
            return runCatching { service.applyText(text) }.getOrDefault(false)
        }
    }
}
