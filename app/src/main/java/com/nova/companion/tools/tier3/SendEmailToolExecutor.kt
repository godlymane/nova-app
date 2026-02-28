package com.nova.companion.tools.tier3

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.nova.companion.tools.NovaTool
import com.nova.companion.tools.ToolParam
import com.nova.companion.tools.ToolRegistry
import com.nova.companion.tools.ToolResult

object SendEmailToolExecutor {

    private const val TAG = "SendEmailTool"

    fun register(registry: ToolRegistry) {
        val tool = NovaTool(
            name = "sendEmail",
            description = "Compose an email with a recipient, subject, and body.",
            parameters = mapOf(
                "to" to ToolParam(type = "string", description = "Recipient email address", required = true),
                "subject" to ToolParam(type = "string", description = "Email subject line", required = false),
                "body" to ToolParam(type = "string", description = "Email body text", required = false)
            ),
            executor = { ctx, params -> execute(ctx, params) }
        )
        registry.registerTool(tool)
    }

    private suspend fun execute(context: Context, params: Map<String, Any>): ToolResult {
        return try {
            val to = (params["to"] as? String)?.trim()
                ?: return ToolResult(false, "Recipient email address is required")
            val subject = (params["subject"] as? String)?.trim()
            val body = (params["body"] as? String)?.trim()

            val intent = Intent(Intent.ACTION_SENDTO).apply {
                data = Uri.parse("mailto:")
                putExtra(Intent.EXTRA_EMAIL, arrayOf(to))
                subject?.let { putExtra(Intent.EXTRA_SUBJECT, it) }
                body?.let { putExtra(Intent.EXTRA_TEXT, it) }
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            if (intent.resolveActivity(context.packageManager) == null) {
                return ToolResult(false, "No email app found on this device")
            }

            context.startActivity(intent)

            val message = if (subject != null) {
                "Opening email to $to with subject: $subject"
            } else {
                "Opening email to $to"
            }
            Log.i(TAG, message)
            ToolResult(true, message)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to compose email", e)
            ToolResult(false, "Failed to open email: ${e.message}")
        }
    }
}
