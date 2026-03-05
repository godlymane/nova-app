package com.nova.companion.tools.tier4

import android.content.Context
import android.util.Log
import com.nova.companion.cloud.OpenAIClient
import com.nova.companion.tools.NovaTool
import com.nova.companion.tools.ToolParam
import com.nova.companion.tools.ToolRegistry
import com.nova.companion.tools.ToolResult
import com.nova.companion.vision.BitmapEncoder
import com.nova.companion.vision.ScreenshotService

object AnalyzeScreenToolExecutor {

    private const val TAG = "AnalyzeScreen"

    fun register(registry: ToolRegistry) {
        val tool = NovaTool(
            name = "analyzeScreen",
            description = "Capture a screenshot of the current screen and analyze it with GPT-4o vision. Use this to understand what's on screen, read text from images, identify UI elements, describe app state, or answer questions about what the user is looking at.",
            parameters = mapOf(
                "query" to ToolParam("string", "What to analyze or look for on screen (e.g. 'what app is open?', 'read the text on screen', 'describe what you see')", false)
            ),
            executor = { ctx, params -> execute(ctx, params) }
        )
        registry.registerTool(tool)
    }

    private suspend fun execute(context: Context, params: Map<String, Any>): ToolResult {
        val query = params["query"] as? String ?: "Describe what you see on this screen in detail."

        if (!ScreenshotService.isRunning()) {
            return ToolResult(false, "Screen capture not active. Please enable it in Settings > Vision to use screen analysis.")
        }

        return try {
            val bitmap = ScreenshotService.captureScreenshot()
                ?: return ToolResult(false, "Failed to capture screenshot. The screen may be off or the service may need restart.")

            val base64 = try {
                BitmapEncoder.encodeToBase64(bitmap)
            } finally {
                bitmap.recycle()
            }

            val systemPrompt = """
You are Nova's vision system analyzing an Android phone screenshot.
Provide a clear, concise analysis based on the user's query.

Rules:
- Be specific about what you see: app names, text content, UI elements
- If asked to read text, transcribe it accurately
- If asked about UI state, describe buttons, toggles, input fields
- Keep responses under 200 words unless the query requires more detail
- Do not hallucinate or guess about content you cannot clearly see
            """.trimIndent()

            val response = OpenAIClient.visionCompletion(
                systemPrompt = systemPrompt,
                userText = query,
                imageBase64 = base64,
                context = context
            )

            if (response != null) {
                // The vision API returns JSON due to response_format, try to extract text
                val text = try {
                    val json = org.json.JSONObject(response)
                    json.optString("analysis", json.optString("description", response))
                } catch (_: Exception) {
                    response
                }
                ToolResult(true, text)
            } else {
                ToolResult(false, "Vision analysis failed. Check OpenAI API key in settings.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Screen analysis error", e)
            ToolResult(false, "Screen analysis error: ${e.message}")
        }
    }
}
