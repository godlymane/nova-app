package com.nova.companion.tools.tier4

import android.content.Context
import android.util.Log
import com.nova.companion.accessibility.NovaAccessibilityService
import com.nova.companion.accessibility.UIAutomator
import com.nova.companion.tools.NovaTool
import com.nova.companion.tools.ToolParam
import com.nova.companion.tools.ToolRegistry
import com.nova.companion.tools.ToolResult
import kotlinx.coroutines.delay
import org.json.JSONObject

object AutoFillFormToolExecutor {

    private const val TAG = "AutoFillFormTool"

    fun register(registry: ToolRegistry) {
        val tool = NovaTool(
            name = "autoFillForm",
            description = "Automatically fill out a form on the current screen by matching field labels to values.",
            parameters = mapOf(
                "fields" to ToolParam(type = "string", description = "JSON string of label-value pairs, e.g. {\"Name\": \"John\", \"Email\": \"john@email.com\"}", required = true)
            ),
            executor = { ctx, params -> execute(ctx, params) }
        )
        registry.registerTool(tool)
    }

    private suspend fun execute(context: Context, params: Map<String, Any>): ToolResult {
        return try {
            if (!NovaAccessibilityService.isRunning()) {
                return ToolResult(false, "Accessibility service not enabled. Please enable Nova in Settings > Accessibility.")
            }

            val fieldsJson = (params["fields"] as? String)?.trim()
                ?: return ToolResult(false, "Fields JSON string is required")

            val jsonObject = try {
                JSONObject(fieldsJson)
            } catch (e: Exception) {
                return ToolResult(false, "Invalid JSON format for fields: ${e.message}")
            }

            var filledCount = 0
            val failedFields = mutableListOf<String>()

            val keys = jsonObject.keys()
            while (keys.hasNext()) {
                val label = keys.next()
                val value = jsonObject.getString(label)

                val typed = UIAutomator.typeTextByLabel(label, value)
                if (typed) {
                    filledCount++
                } else {
                    failedFields.add(label)
                }
                delay(300)
            }

            val totalFields = jsonObject.length()
            if (failedFields.isEmpty()) {
                Log.i(TAG, "Filled all $filledCount fields")
                ToolResult(true, "Filled $filledCount field(s) successfully")
            } else if (filledCount > 0) {
                Log.w(TAG, "Filled $filledCount/$totalFields, failed: $failedFields")
                ToolResult(true, "Filled $filledCount of $totalFields field(s). Could not find: ${failedFields.joinToString(", ")}")
            } else {
                Log.w(TAG, "Could not fill any fields")
                ToolResult(false, "Could not find any of the form fields on screen: ${failedFields.joinToString(", ")}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fill form", e)
            ToolResult(false, "Failed to fill form: ${e.message}")
        }
    }
}
