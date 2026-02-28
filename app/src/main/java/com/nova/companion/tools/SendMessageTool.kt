package com.nova.companion.tools

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Telephony

object SendMessageTool {
    fun register(registry: ToolRegistry, context: Context) {
        val tool = NovaTool(
            name = "send_message",
            description = "Sends a message to a contact via SMS, WhatsApp, or other messaging apps.",
            parameters = mapOf(
                "contact" to ToolParam(
                    type = "string",
                    description = "Phone number or contact name to send the message to",
                    required = true
                ),
                "message" to ToolParam(
                    type = "string",
                    description = "The message text to send",
                    required = true
                ),
                "app" to ToolParam(
                    type = "string",
                    description = "Messaging app to use: 'sms', 'whatsapp', 'telegram', 'facebook', 'instagram'. Defaults to 'sms'",
                    required = false
                )
            ),
            executor = { ctx, params ->
                executeSendMessage(ctx, params)
            }
        )
        registry.registerTool(tool)
    }

    private suspend fun executeSendMessage(context: Context, params: Map<String, Any>): ToolResult {
        return try {
            val contact = (params["contact"] as? String)?.trim()
                ?: return ToolResult(false, "Contact parameter is required")

            if (contact.isEmpty()) {
                return ToolResult(false, "Contact cannot be empty")
            }

            val message = (params["message"] as? String)?.trim()
                ?: return ToolResult(false, "Message parameter is required")

            if (message.isEmpty()) {
                return ToolResult(false, "Message cannot be empty")
            }

            val app = (params["app"] as? String)?.trim()?.lowercase() ?: "sms"

            val intent = when (app) {
                "sms", "text", "texting" -> {
                    val phoneNumber = extractPhoneNumber(contact)
                    Intent(Intent.ACTION_SENDTO).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        data = Uri.parse("smsto:$phoneNumber")
                        putExtra("sms_body", message)
                    }
                }
                "whatsapp" -> {
                    val phoneNumber = extractPhoneNumber(contact)
                    val encodedMessage = Uri.encode(message)
                    Intent(Intent.ACTION_VIEW).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        data = Uri.parse("https://api.whatsapp.com/send?phone=$phoneNumber&text=$encodedMessage")
                        setPackage("com.whatsapp")
                    }
                }
                "whatsapp business" -> {
                    val phoneNumber = extractPhoneNumber(contact)
                    val encodedMessage = Uri.encode(message)
                    Intent(Intent.ACTION_VIEW).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        data = Uri.parse("https://api.whatsapp.com/send?phone=$phoneNumber&text=$encodedMessage")
                        setPackage("com.whatsapp.w4b")
                    }
                }
                "telegram" -> {
                    val phoneNumber = extractPhoneNumber(contact)
                    val encodedMessage = Uri.encode(message)
                    Intent(Intent.ACTION_VIEW).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        data = Uri.parse("tg://msg_url?url=$phoneNumber&text=$encodedMessage")
                    }
                }
                "facebook" -> {
                    Intent(Intent.ACTION_SEND).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        type = "text/plain"
                        `package` = "com.facebook.katana"
                        putExtra(Intent.EXTRA_TEXT, message)
                    }
                }
                "messenger" -> {
                    Intent(Intent.ACTION_SEND).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        type = "text/plain"
                        `package` = "com.facebook.orca"
                        putExtra(Intent.EXTRA_TEXT, message)
                    }
                }
                "instagram" -> {
                    Intent(Intent.ACTION_SEND).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        type = "text/plain"
                        `package` = "com.instagram.android"
                        putExtra(Intent.EXTRA_TEXT, message)
                    }
                }
                "twitter" -> {
                    val encodedMessage = Uri.encode(message)
                    Intent(Intent.ACTION_VIEW).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        data = Uri.parse("https://twitter.com/intent/tweet?text=$encodedMessage")
                    }
                }
                "email" -> {
                    Intent(Intent.ACTION_SEND).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        type = "message/rfc822"
                        putExtra(Intent.EXTRA_EMAIL, arrayOf(contact))
                        putExtra(Intent.EXTRA_SUBJECT, "")
                        putExtra(Intent.EXTRA_TEXT, message)
                    }
                }
                else -> {
                    return ToolResult(false, "Unsupported messaging app: '$app'. Supported apps: sms, whatsapp, telegram, facebook, messenger, instagram, twitter, email")
                }
            }

            try {
                context.startActivity(intent)
                val displayApp = app.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
                ToolResult(true, "Opening $displayApp to send message to $contact")
            } catch (e: Exception) {
                if (app.contains("whatsapp") || app == "telegram") {
                    return ToolResult(false, "The app is not installed on this device. Please install $app first.")
                }
                throw e
            }
        } catch (e: Exception) {
            ToolResult(false, "Failed to send message: ${e.message}")
        }
    }

    private fun extractPhoneNumber(contact: String): String {
        return contact
            .replace(Regex("[^0-9+]"), "")
            .let { if (!it.startsWith("+")) "+1$it" else it }
    }
}
