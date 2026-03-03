package com.nova.companion.brain.context

import android.content.Context
import android.util.Log
import com.nova.companion.brain.emotion.NovaEmotionEngine
import com.nova.companion.data.NovaDatabase
import com.nova.companion.memory.MemoryManager
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * Unified context injection for ALL LLM paths.
 * Assembles: device state + memories + facts + emotion
 * into a compact string (~200-400 tokens) that goes between
 * system prompt and conversation history.
 *
 * This fixes the critical bug where cloud chat got ZERO context.
 */
object ContextInjector {

    private const val TAG = "ContextInjector"

    /**
     * Build the full injected context string for any LLM call.
     *
     * @param userMessage The current user message (used for memory recall)
     * @param memoryManager The memory manager instance for recall + facts
     * @param context Android context (for contact alias lookup)
     * @return Compact context string, or empty if nothing to inject
     */
    suspend fun buildInjectedContext(
        userMessage: String,
        memoryManager: MemoryManager,
        context: Context? = null
    ): String = coroutineScope {
        // Run all 4 context sources in parallel — saves ~150-250ms vs sequential
        val deviceDeferred = async {
            try {
                val snapshot = ContextEngine.currentContext.value
                val deviceContext = snapshot.toPromptString()
                if (deviceContext.isNotBlank()) "[RIGHT NOW]\n$deviceContext" else null
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get device context", e)
                null
            }
        }

        val memoryDeferred = async {
            try {
                val memoryContext = memoryManager.buildContext(userMessage)
                if (memoryContext.isNotBlank()) "[WHAT YOU REMEMBER]\n$memoryContext" else null
            } catch (e: Exception) {
                Log.e(TAG, "Failed to build memory context", e)
                null
            }
        }

        val emotionDeferred = async {
            try {
                val emotionStr = NovaEmotionEngine.getPromptInjection()
                if (emotionStr.isNotBlank()) "[YOUR MOOD]\n$emotionStr" else null
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get emotion state", e)
                null
            }
        }

        val aliasDeferred = if (context != null) async {
            try {
                val aliases = NovaDatabase.getInstance(context).contactAliasDao().getAll()
                if (aliases.isNotEmpty()) {
                    val aliasStr = aliases.joinToString(", ") { "\"${it.alias}\"=${it.contactName}" }
                    "[CONTACT ALIASES]\nUser's shortcut names: $aliasStr"
                } else null
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load contact aliases", e)
                null
            }
        } else null

        val result = listOfNotNull(
            deviceDeferred.await(),
            memoryDeferred.await(),
            emotionDeferred.await(),
            aliasDeferred?.await()
        ).joinToString("\n\n")

        if (result.isNotBlank()) {
            Log.d(TAG, "Injected context: ${result.length} chars")
        }
        result
    }
}
