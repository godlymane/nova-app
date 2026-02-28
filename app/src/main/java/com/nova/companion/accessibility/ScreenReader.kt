package com.nova.companion.accessibility

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

data class ScreenElement(
    val text: String?,
    val contentDescription: String?,
    val className: String,
    val isClickable: Boolean,
    val isEditable: Boolean,
    val isScrollable: Boolean,
    val isChecked: Boolean?,
    val bounds: Rect
)

object ScreenReader {

    fun readScreen(): String {
        val service = NovaAccessibilityService.instance ?: return "Accessibility service not running"
        val root = service.getRootNode() ?: return "Cannot read screen — no active window"

        val elements = mutableListOf<ScreenElement>()
        collectElements(root, elements)

        if (elements.isEmpty()) return "Screen appears empty"

        val sb = StringBuilder()
        val packageName = root.packageName?.toString() ?: "unknown"
        sb.appendLine("Current app: $packageName")
        sb.appendLine("Screen elements:")

        var index = 1
        for (element in elements) {
            val label = element.text ?: element.contentDescription ?: continue
            val type = when {
                element.isEditable -> "input field"
                element.isClickable && element.className.contains("Button") -> "button"
                element.isClickable -> "tappable"
                element.isScrollable -> "scrollable"
                element.isChecked == true -> "checked"
                element.isChecked == false -> "unchecked"
                else -> "text"
            }
            sb.appendLine("  $index. [$type] $label")
            index++
        }

        return sb.toString().take(2000)
    }

    fun readScreenCompact(): String {
        val service = NovaAccessibilityService.instance ?: return "Accessibility service not running"
        val root = service.getRootNode() ?: return "Cannot read screen"

        val interactive = mutableListOf<String>()
        NodeFinder.traverseTree(root) { node ->
            if (node.isClickable || node.isEditable) {
                val label = node.text?.toString()
                    ?: node.contentDescription?.toString()
                    ?: return@traverseTree
                if (label.isNotBlank()) {
                    val type = if (node.isEditable) "input" else "button"
                    interactive.add("[$type] $label")
                }
            }
        }

        return if (interactive.isEmpty()) {
            "No interactive elements found on screen"
        } else {
            "Interactive elements: ${interactive.joinToString(", ")}"
        }
    }

    private fun collectElements(node: AccessibilityNodeInfo, list: MutableList<ScreenElement>) {
        val text = node.text?.toString()
        val cd = node.contentDescription?.toString()
        val hasContent = !text.isNullOrBlank() || !cd.isNullOrBlank()
        val isInteractive = node.isClickable || node.isEditable || node.isScrollable

        if (hasContent || isInteractive) {
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            list.add(
                ScreenElement(
                    text = text,
                    contentDescription = cd,
                    className = node.className?.toString() ?: "unknown",
                    isClickable = node.isClickable,
                    isEditable = node.isEditable,
                    isScrollable = node.isScrollable,
                    isChecked = if (node.isCheckable) node.isChecked else null,
                    bounds = bounds
                )
            )
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectElements(child, list)
        }
    }
}
