package com.nova.companion.tools.tier1

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.nova.companion.tools.ContactLookupHelper
import com.nova.companion.tools.NovaTool
import com.nova.companion.tools.ToolParam
import com.nova.companion.tools.ToolPermissionHelper
import com.nova.companion.tools.ToolRegistry
import com.nova.companion.tools.ToolResult
import java.net.URLEncoder

object SendWhatsAppToolExecutor {

    private const val TAG = "SendWhatsAppTool"

    fun register(registry: ToolRegistry) {
        val tool = NovaTool(
            name = "sendWhatsApp",
            description = "Send a WhatsApp message to a contact. Opens WhatsApp with the message pre-filled.",
            parameters = mapOf(
                "contact_name" to ToolParam(type = "string", description = "The name of the contact to message on WhatsApp", required = true),
                "message" to ToolParam(type = "string", description = "The message to send via WhatsApp", required = true)
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

            if (!ToolPermissionHelper.hasPermission(context, Manifest.permission.READ_CONTACTS))
                return ToolResult(false, "Contacts permission not granted. Please enable it in Settings.")

            val contacts = ContactLookupHelper.lookupByName(context, contactName)
            if (contacts.isEmpty()) return ToolResult(false, "I couldn't find $contactName in your contacts")

            val contact = contacts.first()
            val formattedNumber = ContactLookupHelper.formatPhoneForWhatsApp(contact.phoneNumber)
            val encodedMessage = URLEncoder.encode(message, "UTF-8")

            val waUri = Uri.parse("https://wa.me/$formattedNumber?text=$encodedMessage")
            val intent = Intent(Intent.ACTION_VIEW, waUri).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }

            val pm = context.packageManager
            val whatsappPackage = when {
                isPackageInstalled(pm, "com.whatsapp") -> "com.whatsapp"
                isPackageInstalled(pm, "com.whatsapp.w4b") -> "com.whatsapp.w4b"
                else -> null
            }
            if (whatsappPackage == null) return ToolResult(false, "WhatsApp is not installed")

            intent.setPackage(whatsappPackage)
            context.startActivity(intent)

            Log.i(TAG, "Opening WhatsApp to message ${contact.name}")
            ToolResult(true, "Opening WhatsApp to send message to ${contact.name}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send WhatsApp message", e)
            ToolResult(false, "Failed to send WhatsApp message: ${e.message}")
        }
    }

    private fun isPackageInstalled(pm: android.content.pm.PackageManager, packageName: String): Boolean {
        return try { pm.getPackageInfo(packageName, 0); true } catch (e: android.content.pm.PackageManager.NameNotFoundException) { false }
    }
}