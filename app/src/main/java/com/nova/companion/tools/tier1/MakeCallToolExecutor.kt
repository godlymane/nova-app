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

object MakeCallToolExecutor {

    private const val TAG = "MakeCallTool"

    fun register(registry: ToolRegistry) {
        val tool = NovaTool(
            name = "makeCall",
            description = "Make a phone call to a contact. Look up the contact by name and initiate the call.",
            parameters = mapOf(
                "contact_name" to ToolParam(type = "string", description = "The name of the contact to call", required = true)
            ),
            executor = { ctx, params -> execute(ctx, params) }
        )
        registry.registerTool(tool)
    }

    private suspend fun execute(context: Context, params: Map<String, Any>): ToolResult {
        return try {
            val contactName = (params["contact_name"] as? String)?.trim()
                ?: return ToolResult(false, "Contact name is required")

            if (!ToolPermissionHelper.hasPermission(context, Manifest.permission.CALL_PHONE))
                return ToolResult(false, "Phone call permission not granted. Please enable it in Settings.")
            if (!ToolPermissionHelper.hasPermission(context, Manifest.permission.READ_CONTACTS))
                return ToolResult(false, "Contacts permission not granted. Please enable it in Settings.")

            val contacts = ContactLookupHelper.lookupByName(context, contactName)
            if (contacts.isEmpty()) return ToolResult(false, "I couldn't find $contactName in your contacts")

            val contact = contacts.first()
            val callIntent = Intent(Intent.ACTION_CALL, Uri.parse("tel:${contact.phoneNumber}")).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(callIntent)

            Log.i(TAG, "Calling ${contact.name} (${contact.phoneNumber})")
            ToolResult(true, "Calling ${contact.name} now")
        } catch (e: SecurityException) {
            Log.e(TAG, "Call permission denied", e)
            ToolResult(false, "Phone call permission was denied")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to make call", e)
            ToolResult(false, "Failed to make call: ${e.message}")
        }
    }
}