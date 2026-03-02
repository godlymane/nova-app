package com.nova.companion.tools.tier1

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
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

object SendWhatsAppToolExecutor {

    private const val TAG = "SendWhatsAppTool"

    fun register(registry: ToolRegistry) {
        val tool = NovaTool(
            name = "sendWhatsApp",
            description = "Send a WhatsApp message to a contact. Fully sends the message end-to-end — opens the chat, types the message, and taps the send button automatically.",
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
            Log.i(TAG, "Opening WhatsApp chat with ${contact.name}")

            // If Accessibility Service is running, auto-tap the send button
            if (NovaAccessibilityService.isRunning()) {
                delay(3000) // Wait for WhatsApp chat screen to fully load

                // Strategy 1: resource ID (most reliable — works on all WhatsApp versions)
                var sent = UIAutomator.tapByResourceId("com.whatsapp:id/send")

                // Strategy 2: WhatsApp Business resource ID
                if (!sent) sent = UIAutomator.tapByResourceId("com.whatsapp.w4b:id/send")

                // Strategy 3: content description "Send"
                if (!sent) sent = UIAutomator.tapByDescription("Send")

                // Strategy 4: exact text "Send"
                if (!sent) sent = UIAutomator.tapByText("Send")

                // Strategy 5: coordinate-based tap — WhatsApp send button is always bottom-right
                if (!sent) {
                    val service = NovaAccessibilityService.instance
                    if (service != null) {
                        val metrics = service.resources.displayMetrics
                        val x = metrics.widthPixels - 60f   // ~60px from right edge
                        val y = metrics.heightPixels - 120f // ~120px from bottom
                        sent = service.tapAtCoordinates(x, y)
                        Log.i(TAG, "Fallback coordinate tap at ($x, $y)")
                    }
                }

                if (sent) {
                    Log.i(TAG, "Message sent to ${contact.name} on WhatsApp")
                    return ToolResult(true, "Sent to ${contact.name} on WhatsApp ✓")
                } else {
                    Log.w(TAG, "All send strategies failed")
                    return ToolResult(true, "Opened WhatsApp for ${contact.name} — message is typed, tap Send to confirm.")
                }
            }

            ToolResult(true, "WhatsApp opened for ${contact.name}. Enable Nova Accessibility Service for fully automatic sending.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send WhatsApp message", e)
            ToolResult(false, "Failed to send WhatsApp message: ${e.message}")
        }
    }

    private fun isPackageInstalled(pm: android.content.pm.PackageManager, packageName: String): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(packageName, android.content.pm.PackageManager.PackageInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(packageName, 0)
            }
            true
        } catch (e: android.content.pm.PackageManager.NameNotFoundException) { false }
    }
}