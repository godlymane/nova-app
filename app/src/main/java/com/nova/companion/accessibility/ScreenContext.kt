package com.nova.companion.accessibility

import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Tracks what the user is currently doing on their phone.
 * Updated by NovaAccessibilityService on every window/content change.
 *
 * Nova uses this to be context-aware — e.g., if the user is on Instagram
 * and says "post this as a story", Nova knows what app they're in.
 */
object ScreenContext {

    private const val TAG = "ScreenContext"
    private const val MAX_TEXT_CHARS = 500 // Cap visible text to avoid huge prompts

    data class State(
        val currentPackage: String = "",
        val currentAppName: String = "",
        val activityClass: String = "",
        val visibleText: String = "",
        val lastUpdated: Long = 0L
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    // Well-known app names for quick lookup
    private val APP_NAMES = mapOf(
        "com.instagram.android" to "Instagram",
        "com.whatsapp" to "WhatsApp",
        "com.google.android.youtube" to "YouTube",
        "com.twitter.android" to "Twitter/X",
        "com.twitter.android.lite" to "Twitter/X Lite",
        "com.snapchat.android" to "Snapchat",
        "com.facebook.katana" to "Facebook",
        "com.facebook.orca" to "Messenger",
        "com.spotify.music" to "Spotify",
        "com.google.android.apps.maps" to "Google Maps",
        "com.google.android.gm" to "Gmail",
        "com.google.android.apps.messaging" to "Messages",
        "com.google.android.dialer" to "Phone",
        "com.google.android.contacts" to "Contacts",
        "com.google.android.calendar" to "Calendar",
        "com.google.android.apps.photos" to "Google Photos",
        "com.android.chrome" to "Chrome",
        "com.google.android.apps.docs" to "Google Docs",
        "com.google.android.apps.nbu.files" to "Files",
        "com.amazon.mShop.android.shopping" to "Amazon",
        "com.zhiliaoapp.musically" to "TikTok",
        "org.telegram.messenger" to "Telegram",
        "com.discord" to "Discord",
        "com.slack" to "Slack",
        "com.reddit.frontpage" to "Reddit",
        "com.linkedin.android" to "LinkedIn",
        "com.netflix.mediaclient" to "Netflix",
        "com.google.android.apps.youtube.music" to "YouTube Music",
        "com.samsung.android.messaging" to "Samsung Messages",
        "com.samsung.android.dialer" to "Samsung Phone",
        "com.samsung.android.contacts" to "Samsung Contacts",
        "com.nova.companion" to "Nova"
    )

    /**
     * Called by NovaAccessibilityService when the foreground window changes.
     */
    fun onWindowChanged(packageName: String, className: String) {
        if (packageName == "com.nova.companion") return // Ignore ourselves

        val appName = APP_NAMES[packageName] ?: packageName.substringAfterLast(".")
            .replaceFirstChar { it.uppercase() }

        _state.value = _state.value.copy(
            currentPackage = packageName,
            currentAppName = appName,
            activityClass = className,
            lastUpdated = System.currentTimeMillis()
        )
        Log.d(TAG, "Window changed: $appName ($packageName)")
    }

    /**
     * Called by NovaAccessibilityService to update visible screen text.
     * Extracts text from the accessibility tree for context.
     */
    fun updateVisibleText(rootNode: AccessibilityNodeInfo?) {
        if (rootNode == null) return

        try {
            val textParts = mutableListOf<String>()
            extractText(rootNode, textParts, depth = 0, maxDepth = 8)

            val visibleText = textParts
                .filter { it.isNotBlank() && it.length > 1 }
                .distinct()
                .joinToString(" | ")
                .take(MAX_TEXT_CHARS)

            _state.value = _state.value.copy(
                visibleText = visibleText,
                lastUpdated = System.currentTimeMillis()
            )
        } catch (e: Exception) {
            Log.w(TAG, "Error extracting screen text", e)
        }
    }

    private fun extractText(
        node: AccessibilityNodeInfo,
        results: MutableList<String>,
        depth: Int,
        maxDepth: Int
    ) {
        if (depth > maxDepth) return

        node.text?.toString()?.trim()?.let { text ->
            if (text.isNotBlank() && text.length in 2..200) {
                results.add(text)
            }
        }
        node.contentDescription?.toString()?.trim()?.let { desc ->
            if (desc.isNotBlank() && desc.length in 2..100) {
                results.add(desc)
            }
        }

        for (i in 0 until node.childCount) {
            try {
                node.getChild(i)?.let { child ->
                    extractText(child, results, depth + 1, maxDepth)
                }
            } catch (_: Exception) {}
        }
    }

    /**
     * Get a concise summary of what the user is currently doing.
     * Injected into voice/text system prompts for context awareness.
     */
    fun getSummary(): String {
        val s = _state.value
        if (s.currentPackage.isBlank() || s.currentPackage == "com.nova.companion") return ""

        val age = System.currentTimeMillis() - s.lastUpdated
        if (age > 60_000) return "" // Stale (>60s old), don't include

        return buildString {
            append("User is currently on ${s.currentAppName}")
            if (s.visibleText.isNotBlank()) {
                append(". Visible content: ${s.visibleText}")
            }
        }
    }
}
