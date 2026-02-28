package com.nova.companion.accessibility

import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.delay

object UIAutomator {

    suspend fun tapByText(text: String): Boolean {
        val service = NovaAccessibilityService.instance ?: return false
        val root = service.getRootNode() ?: return false

        val clickable = NodeFinder.findClickableByText(root, text)
        if (clickable != null) {
            return clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }

        val textNodes = NodeFinder.findByText(root, text)
        if (textNodes.isNotEmpty()) {
            val node = textNodes.first()
            val rect = android.graphics.Rect()
            node.getBoundsInScreen(rect)
            val x = rect.centerX().toFloat()
            val y = rect.centerY().toFloat()
            return service.tapAtCoordinates(x, y)
        }

        return false
    }

    suspend fun tapByDescription(description: String): Boolean {
        val service = NovaAccessibilityService.instance ?: return false
        val root = service.getRootNode() ?: return false

        val nodes = NodeFinder.findByContentDescription(root, description)
        for (node in nodes) {
            if (node.isClickable) {
                return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            var parent = node.parent
            var depth = 0
            while (parent != null && depth < 5) {
                if (parent.isClickable) {
                    return parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                }
                parent = parent.parent
                depth++
            }
            val rect = android.graphics.Rect()
            node.getBoundsInScreen(rect)
            return service.tapAtCoordinates(rect.centerX().toFloat(), rect.centerY().toFloat())
        }
        return false
    }

    suspend fun typeText(text: String, clearFirst: Boolean = false): Boolean {
        val service = NovaAccessibilityService.instance ?: return false
        val root = service.getRootNode() ?: return false

        val target = findFocusedEditField(root) ?: NodeFinder.findFirstEditField(root) ?: return false

        target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        delay(200)

        if (clearFirst) {
            val selectArgs = Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, target.text?.length ?: 0)
            }
            target.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selectArgs)
        }

        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    suspend fun typeTextByLabel(label: String, text: String): Boolean {
        val service = NovaAccessibilityService.instance ?: return false
        val root = service.getRootNode() ?: return false

        val editField = NodeFinder.findEditFieldByLabel(root, label) ?: return false
        editField.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        editField.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        delay(200)

        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return editField.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    suspend fun scroll(direction: String): Boolean {
        val service = NovaAccessibilityService.instance ?: return false
        val root = service.getRootNode() ?: return false

        val scrollable = NodeFinder.findScrollableNode(root)

        if (scrollable != null) {
            val action = when (direction.lowercase()) {
                "down", "forward" -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                "up", "backward" -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                else -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            }
            val result = scrollable.performAction(action)
            if (result) return true
        }

        val displayMetrics = service.resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels.toFloat()
        val screenHeight = displayMetrics.heightPixels.toFloat()
        val centerX = screenWidth / 2
        val margin = screenHeight * 0.2f

        return when (direction.lowercase()) {
            "down", "forward" -> service.swipe(centerX, screenHeight - margin, centerX, margin, 400)
            "up", "backward" -> service.swipe(centerX, margin, centerX, screenHeight - margin, 400)
            "left" -> service.swipe(screenWidth - margin, screenHeight / 2, margin, screenHeight / 2, 400)
            "right" -> service.swipe(margin, screenHeight / 2, screenWidth - margin, screenHeight / 2, 400)
            else -> false
        }
    }

    suspend fun waitForText(text: String, timeoutMs: Long = 10000, pollIntervalMs: Long = 500): Boolean {
        val startTime = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTime < timeoutMs) {
            val service = NovaAccessibilityService.instance ?: return false
            val root = service.getRootNode()
            if (root != null) {
                val nodes = NodeFinder.findByText(root, text)
                if (nodes.isNotEmpty()) return true
            }
            delay(pollIntervalMs)
        }
        return false
    }

    fun pressBack() {
        NovaAccessibilityService.instance?.pressBack()
    }

    private fun findFocusedEditField(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var result: AccessibilityNodeInfo? = null
        NodeFinder.traverseTree(root) { node ->
            if (result == null && node.isEditable && node.isFocused) {
                result = node
            }
        }
        return result
    }
}
