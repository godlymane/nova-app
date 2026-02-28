package com.nova.companion.tools.tier1

import android.Manifest
import android.content.Context
import android.telephony.SmsManager
import android.util.Log
import com.nova.companion.tools.ContactLookupHelper
import com.nova.companion.tools.NovaTool
import com.nova.companion.tools.ToolParam
import com.nova.companion.tools.ToolPermissionHelper
import com.nova.companion.tools.ToolRegistry
import com.nova.companion.tools.ToolResult

object SendSmsToolExecutor {

    private const val TAG = "SendSmsTool"

    fun register(registry: ToolRegistry) {
        val tool = NovaTool(
            name = "sendSms",
            description = "Send an SMS text message to a contact. Look up the contact by name and send the message.",
            parameters = mapOf(
                "contact_name" to ToolParam(type = "string", description = "The name of the contact to send the SMS to", required = true),
                "message" to ToolParam(type = "string", description = "The text message content to send", required = true)
            ),
            executor = { ctx, params -> execute(ctx, params) }
        )
        registry.registerTool(tool)
    }

    private suspend fun execute(context: Context, params: Map<String, Any>): ToolResult {
        return try {
            val contactName = (params["contact_name"] as? String)?.trim()
                ?: return ToolResult(false, "Contact name is required")
            val message = (params["message"] as? String)?.trim()
                ?: return ToolResult(false, "Message is required")

            if (!ToolPermissionHelper.hasPermission(context, Manifest.permission.SEND_SMS))
                return ToolResult(false, "SMS permission not granted. Please enable it in Settings.")
            if (!ToolPermissionHelper.hasPermission(context, Manifest.permission.READ_CONTACTS))
                return ToolResult(false, "Contacts permission not granted. Please enable it in Settings.")

            val contacts = ContactLookupHelper.lookupByName(context, contactName)
            if (contacts.isEmpty()) return ToolResult(false, "I couldn't find $contactName in your contacts")

            val contact = contacts.first()
            val smsManager = SmsManager.getDefault()
            val parts = smsManager.divideMessage(message)
            if (parts.size > 1) {
                smsManager.sendMultipartTextMessage(contact.phoneNumber, null, parts, null, null)
            } else {
                smsManager.sendTextMessage(contact.phoneNumber, null, message, null, null)
            }

            Log.i(TAG, "SMS sent to ${contact.name} (${contact.phoneNumber})")
            ToolResult(true, "Sent text to ${contact.name}: $message")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send SMS", e)
            ToolResult(false, "Failed to send SMS: ${e.message}")
        }
    }
}