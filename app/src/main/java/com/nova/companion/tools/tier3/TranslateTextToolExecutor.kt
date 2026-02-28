package com.nova.companion.tools.tier3

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.nova.companion.tools.NovaTool
import com.nova.companion.tools.ToolParam
import com.nova.companion.tools.ToolRegistry
import com.nova.companion.tools.ToolResult
import java.net.URLEncoder

object TranslateTextToolExecutor {

    private const val TAG = "TranslateTextTool"

    fun register(registry: ToolRegistry) {
        val tool = NovaTool(
            name = "translateText",
            description = "Translate text to another language using Google Translate.",
            parameters = mapOf(
                "text" to ToolParam(type = "string", description = "Text to translate", required = true),
                "target_language" to ToolParam(type = "string", description = "Target language code like hi, te, es. Default: en", required = false),
                "source_language" to ToolParam(type = "string", description = "Source language code. Default: auto-detect", required = false)
            ),
            executor = { ctx, params -> execute(ctx, params) }
        )
        registry.registerTool(tool)
    }

    private suspend fun execute(context: Context, params: Map<String, Any>): ToolResult {
        return try {
            val text = (params["text"] as? String)?.trim()
                ?: return ToolResult(false, "Text to translate is required")
            val targetLang = (params["target_language"] as? String)?.trim()?.lowercase() ?: "en"
            val sourceLang = (params["source_language"] as? String)?.trim()?.lowercase() ?: "auto"

            val encoded = URLEncoder.encode(text, "UTF-8")
            val translateAppPackage = "com.google.android.apps.translate"

            val intent = if (isAppInstalled(context, translateAppPackage)) {
                Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("google.translate://translate?sl=$sourceLang&tl=$targetLang&text=$encoded")
                )
            } else {
                Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://translate.google.com/?sl=$sourceLang&tl=$targetLang&text=$encoded")
                )
            }

            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)

            val langName = LANGUAGE_NAMES[targetLang] ?: targetLang
            val message = "Translating to $langName"
            Log.i(TAG, "$message — text: ${text.take(50)}")
            ToolResult(true, message)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open translator", e)
            ToolResult(false, "Failed to translate: ${e.message}")
        }
    }

    private fun isAppInstalled(context: Context, packageName: String): Boolean {
        return try {
            context.packageManager.getLaunchIntentForPackage(packageName) != null
        } catch (e: Exception) {
            false
        }
    }

    private val LANGUAGE_NAMES = mapOf(
        "en" to "English",
        "hi" to "Hindi",
        "te" to "Telugu",
        "ta" to "Tamil",
        "kn" to "Kannada",
        "ml" to "Malayalam",
        "mr" to "Marathi",
        "bn" to "Bengali",
        "gu" to "Gujarati",
        "pa" to "Punjabi",
        "ur" to "Urdu",
        "es" to "Spanish",
        "fr" to "French",
        "de" to "German",
        "it" to "Italian",
        "pt" to "Portuguese",
        "ru" to "Russian",
        "ja" to "Japanese",
        "ko" to "Korean",
        "zh" to "Chinese",
        "ar" to "Arabic"
    )
}
