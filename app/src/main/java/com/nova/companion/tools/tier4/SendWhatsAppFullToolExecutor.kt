package com.nova.companion.tools.tier4

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.nova.companion.accessibility.NovaAccessibilityService
import com.nova.companion.accessibility.UIAutomator
import com.nova.companion.tools.ContactLookupHelper
import com.nova.companion.tools.NovaTool
import com.nova.companion.tools.ToolParam
import com.nova.companion.tools.ToolPermissionHelper
import com.nova.companion.tools.ToolRegistry
import com.nova.companion.tools.ToolResult
import kotlinx.coroutines.delay
import java.net.URLEncoder

object SendWhatsAppFullToolExecutor {

    private const val TAG = "SendWhatsAppFullTool"

    fun register(registry: ToolRegistry) {
        val tool = NovaTool(
            name = "sendWhatsAppFull",
            description = "Send a WhatsApp message end-to-end — opens the chat, types the message, and taps send. Actually delivers the message unlike sendWhatsApp which only opens the chat. Always confirm with the user before using this.",
            parameters = mapOf(
                "contact_name" to ToolParam(type = "string", description = "Name of the contact to send the message to", required = true),
                "message" to ToolParam(type = "string", description = "The message to send", required = true)
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

            val contactName = (params["contact_name"] as? String)?.trim()
                ?: return ToolResult(false, "Contact name is required")
            val message = (params["message"] as? String)?.trim()
                ?: return ToolResult(false, "Message is required")

            if (!ToolPermissionHelper.hasPermission(context, Manifest.permission.READ_CONTACTS)) {
                return ToolResult(false, "Contacts permission not granted. Please enable it in Settings.")
            }

            val contacts = ContactLookupHelper.lookupByName(context, contactName)
            if (contacts.isEmpty()) return ToolResult(false, "I couldn't find $contactName in your contacts")

            val contact = contacts.first()
            val formattedNumber = ContactLookupHelper.formatPhoneForWhatsApp(contact.phoneNumber)
            val encodedMessage = URLEncoder.encode(message, "UTF-8")

            // Open WhatsApp with pre-filled message via wa.me link
            val waUri = Uri.parse("https://wa.me/$formattedNumber?text=$encodedMessage")
            val intent = Intent(Intent.ACTION_VIEW, waUri).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }

            val pm = context.packageManager
            val whatsappPackage = when {
                isPackageInstalled(pm, "com.whatsapp") -> "com.whatsapp"
                isPackageInstalled(pm, "com.whatsapp.w4b") -> "com.whatsapp.w4b"
                else -> null
            }
            if (whatsappPackage == null) return ToolResult(false, "WhatsApp is not installed")

            intent.setPackage(whatsappPackage)
            context.startActivity(intent)

            // Wait for WhatsApp to load
            delay(2000)

            // Wait for the send button to appear
            val sendFound = UIAutomator.waitForText("Send", 5000)

            if (!sendFound) {
                // Try content description fallback
                val tapped = UIAutomator.tapByDescription("Send")
                if (!tapped) {
                    return ToolResult(false, "WhatsApp opened but couldn't find the send button. The message is pre-filled — please tap send manually.")
                }
            } else {
                // Tap the send button by content description (more reliable than text)
                val tapped = UIAutomator.tapByDescription("Send")
                if (!tapped) {
                    // Fallback to text-based tap
                    UIAutomator.tapByText("Send")
                }
            }

            Log.i(TAG, "Message sent to ${contact.name} on WhatsApp")
            ToolResult(true, "Message sent to ${contact.name} on WhatsApp")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send WhatsApp message", e)
            ToolResult(false, "Failed to send WhatsApp message: ${e.message}")
        }
    }

    private fun isPackageInstalled(pm: android.content.pm.PackageManager, packageName: String): Boolean {
        return try { pm.getPackageInfo(packageName, 0); true } catch (e: android.content.pm.PackageManager.NameNotFoundException) { false }
    }
}
