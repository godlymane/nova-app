package com.nova.companion.accessibility

import android.view.accessibility.AccessibilityNodeInfo

object NodeFinder {

    fun findByText(root: AccessibilityNodeInfo?, text: String): List<AccessibilityNodeInfo> {
        if (root == null) return emptyList()
        return root.findAccessibilityNodeInfosByText(text) ?: emptyList()
    }

    fun findByViewId(root: AccessibilityNodeInfo?, viewId: String): List<AccessibilityNodeInfo> {
        if (root == null) return emptyList()
        return root.findAccessibilityNodeInfosByViewId(viewId) ?: emptyList()
    }

    fun findByContentDescription(root: AccessibilityNodeInfo?, description: String): List<AccessibilityNodeInfo> {
        if (root == null) return emptyList()
        val results = mutableListOf<AccessibilityNodeInfo>()
        traverseTree(root) { node ->
            val cd = node.contentDescription?.toString() ?: ""
            if (cd.contains(description, ignoreCase = true)) {
                results.add(node)
            }
        }
        return results
    }

    fun findByClassName(root: AccessibilityNodeInfo?, className: String): List<AccessibilityNodeInfo> {
        if (root == null) return emptyList()
        val results = mutableListOf<AccessibilityNodeInfo>()
        traverseTree(root) { node ->
            if (node.className?.toString() == className) {
                results.add(node)
            }
        }
        return results
    }

    fun findClickableByText(root: AccessibilityNodeInfo?, text: String): AccessibilityNodeInfo? {
        val nodes = findByText(root, text)
        for (node in nodes) {
            if (node.isClickable) return node
            var parent = node.parent
            var depth = 0
            while (parent != null && depth < 5) {
                if (parent.isClickable) return parent
                parent = parent.parent
                depth++
            }
        }
        return null
    }

    fun findFirstEditField(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (root == null) return null
        var result: AccessibilityNodeInfo? = null
        traverseTree(root) { node ->
            if (result == null && node.isEditable) {
                result = node
            }
        }
        return result
    }

    fun findEditFieldByLabel(root: AccessibilityNodeInfo?, label: String): AccessibilityNodeInfo? {
        if (root == null) return null
        val labelNodes = findByText(root, label)
        for (labelNode in labelNodes) {
            val parent = labelNode.parent ?: continue
            for (i in 0 until parent.childCount) {
                val sibling = parent.getChild(i) ?: continue
                if (sibling.isEditable) return sibling
                for (j in 0 until sibling.childCount) {
                    val child = sibling.getChild(j) ?: continue
                    if (child.isEditable) return child
                }
            }
        }
        return null
    }

    fun findScrollableNode(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (root == null) return null
        var result: AccessibilityNodeInfo? = null
        traverseTree(root) { node ->
            if (result == null && node.isScrollable) {
                result = node
            }
        }
        return result
    }

    fun traverseTree(root: AccessibilityNodeInfo?, visitor: (AccessibilityNodeInfo) -> Unit) {
        if (root == null) return
        visitor(root)
        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            traverseTree(child, visitor)
        }
    }

    fun getAllVisibleText(root: AccessibilityNodeInfo?): List<String> {
        if (root == null) return emptyList()
        val texts = mutableListOf<String>()
        traverseTree(root) { node ->
            node.text?.toString()?.takeIf { it.isNotBlank() }?.let { texts.add(it) }
            node.contentDescription?.toString()?.takeIf { it.isNotBlank() && node.text == null }?.let { texts.add(it) }
        }
        return texts.distinct()
    }
}
