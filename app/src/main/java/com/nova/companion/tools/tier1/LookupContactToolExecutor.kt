package com.nova.companion.tools.tier1

import android.Manifest
import android.content.Context
import android.util.Log
import com.nova.companion.tools.ContactLookupHelper
import com.nova.companion.tools.NovaTool
import com.nova.companion.tools.ToolParam
import com.nova.companion.tools.ToolPermissionHelper
import com.nova.companion.tools.ToolRegistry
import com.nova.companion.tools.ToolResult

object LookupContactToolExecutor {

    private const val TAG = "LookupContactTool"

    fun register(registry: ToolRegistry) {
        val tool = NovaTool(
            name = "lookupContact",
            description = "Look up a contact's phone number and details by their name.",
            parameters = mapOf(
                "contact_name" to ToolParam(type = "string", description = "The name of the contact to look up", required = true)
            ),
            executor = { ctx, params -> execute(ctx, params) }
        )
        registry.registerTool(tool)
    }

    private suspend fun execute(context: Context, params: Map<String, Any>): ToolResult {
        return try {
            val contactName = (params["contact_name"] as? String)?.trim()
                ?: return ToolResult(false, "Contact name is required")

            if (!ToolPermissionHelper.hasPermission(context, Manifest.permission.READ_CONTACTS))
                return ToolResult(false, "Contacts permission not granted. Please enable it in Settings.")

            val contacts = ContactLookupHelper.lookupByName(context, contactName)
            if (contacts.isEmpty()) return ToolResult(false, "No contacts found matching $contactName")

            val formatted = contacts.joinToString(", ") { "${it.name} — ${it.phoneNumber}" }
            Log.i(TAG, "Found ${contacts.size} contacts matching '$contactName'")
            ToolResult(true, "Found: $formatted")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to look up contact", e)
            ToolResult(false, "Failed to look up contact: ${e.message}")
        }
    }
}