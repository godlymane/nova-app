package com.nova.companion.tools.tier3

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import android.util.Log
import com.nova.companion.tools.NovaTool
import com.nova.companion.tools.ToolParam
import com.nova.companion.tools.ToolRegistry
import com.nova.companion.tools.ToolResult

/**
 * SearchContactsToolExecutor — Smart contact lookup with fuzzy matching.
 *
 * Reads Android ContactsContract to find contacts by:
 * - Exact or partial name match
 * - Phone number fragment
 * - Nickname or company name
 *
 * Also handles opening a contact card or initiating a call/message.
 */
object SearchContactsToolExecutor {

    private const val TAG = "ContactsTool"
    private const val MAX_RESULTS = 10

    fun register(registry: ToolRegistry) {
        val tool = NovaTool(
            name = "searchContacts",
            description = "Search your contacts by name, phone number, or company. " +
                "Returns name, phone number(s), and email if available. " +
                "Use action='call' to call a contact, action='message' to open messages. " +
                "Examples: 'find Rahul in contacts', 'what is Priya's number', " +
                "'call Arjun', 'message Aditya', 'who is +91987654321'",
            parameters = mapOf(
                "query" to ToolParam(
                    type = "string",
                    description = "Name, partial name, phone number, or company to search for",
                    required = true
                ),
                "action" to ToolParam(
                    type = "string",
                    description = "'search' (default), 'call', or 'message'",
                    required = false
                )
            ),
            executor = { ctx, params -> execute(ctx, params) }
        )
        registry.registerTool(tool)
    }

    private fun execute(context: Context, params: Map<String, Any>): ToolResult {
        val query = (params["query"] as? String)?.trim()
            ?: return ToolResult(false, "A name or number to search for is required")
        val action = (params["action"] as? String)?.lowercase() ?: "search"

        return try {
            val contacts = findContacts(context, query)

            if (contacts.isEmpty()) {
                return ToolResult(true, "No contacts found matching '$query'")
            }

            when (action) {
                "call" -> {
                    val top = contacts.first()
                    val phone = top.phones.firstOrNull()
                        ?: return ToolResult(false, "${top.name} has no phone number saved")
                    val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${phone.replace(" ", "")}")).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                    Log.i(TAG, "Dialing ${top.name} at $phone")
                    ToolResult(true, "Calling ${top.name} at $phone")
                }

                "message" -> {
                    val top = contacts.first()
                    val phone = top.phones.firstOrNull()
                        ?: return ToolResult(false, "${top.name} has no phone number saved")
                    val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${phone.replace(" ", "")}")).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                    Log.i(TAG, "Opening SMS to ${top.name}")
                    ToolResult(true, "Opening messages to ${top.name}")
                }

                else -> {
                    // Return contact info
                    val lines = contacts.take(5).map { c ->
                        buildString {
                            append("• ${c.name}")
                            if (c.phones.isNotEmpty()) append(" — ${c.phones.joinToString(", ")}")
                            if (c.emails.isNotEmpty()) append(" | ${c.emails.first()}")
                            if (!c.company.isNullOrBlank()) append(" (${c.company})")
                        }
                    }
                    val total = if (contacts.size > 5) " (showing 5 of ${contacts.size})" else ""
                    ToolResult(true, "Contacts matching '$query'$total:\n${lines.joinToString("\n")}")
                }
            }

        } catch (e: SecurityException) {
            Log.e(TAG, "Contacts permission denied", e)
            ToolResult(false, "Contacts permission not granted. Enable it in Settings > Apps > Nova > Permissions.")
        } catch (e: Exception) {
            Log.e(TAG, "Contact search failed", e)
            ToolResult(false, "Failed to search contacts: ${e.message}")
        }
    }

    data class ContactInfo(
        val id: Long,
        val name: String,
        val phones: List<String>,
        val emails: List<String>,
        val company: String?
    )

    private fun findContacts(context: Context, query: String): List<ContactInfo> {
        val results = mutableListOf<ContactInfo>()
        val resolver = context.contentResolver

        // Search across display name, phone number, and organization
        val uri = Uri.withAppendedPath(
            ContactsContract.CommonDataKinds.Phone.CONTENT_FILTER_URI,
            Uri.encode(query)
        )

        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )

        // First pass: use Phone content filter (fastest, handles partial name + number)
        val phoneIds = mutableMapOf<Long, Pair<String, MutableList<String>>>() // id -> (name, phones)
        try {
            resolver.query(uri, projection, null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.CONTACT_ID))
                    val name = cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)) ?: ""
                    val phone = cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)) ?: ""
                    phoneIds.getOrPut(id) { Pair(name, mutableListOf()) }.second.add(phone)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Phone filter search failed, trying direct query", e)
        }

        // Fallback: direct name search if Phone filter gave no results
        if (phoneIds.isEmpty()) {
            val contactUri = ContactsContract.Contacts.CONTENT_URI
            val contactProjection = arrayOf(
                ContactsContract.Contacts._ID,
                ContactsContract.Contacts.DISPLAY_NAME
            )
            val selection = "${ContactsContract.Contacts.DISPLAY_NAME} LIKE ?"
            val selArgs = arrayOf("%$query%")

            resolver.query(contactUri, contactProjection, selection, selArgs,
                "${ContactsContract.Contacts.DISPLAY_NAME} ASC")?.use { cursor ->
                while (cursor.moveToNext() && phoneIds.size < MAX_RESULTS) {
                    val id = cursor.getLong(cursor.getColumnIndexOrThrow(ContactsContract.Contacts._ID))
                    val name = cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.Contacts.DISPLAY_NAME)) ?: ""
                    phoneIds[id] = Pair(name, mutableListOf())
                }
            }

            // Load phones for these contacts
            for (id in phoneIds.keys) {
                val phoneProjection = arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER)
                val phoneSel = "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?"
                resolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    phoneProjection, phoneSel, arrayOf(id.toString()), null)?.use { c ->
                    while (c.moveToNext()) {
                        val ph = c.getString(0) ?: ""
                        if (ph.isNotBlank()) phoneIds[id]?.second?.add(ph)
                    }
                }
            }
        }

        // Load emails for all found contacts
        for ((id, namePhones) in phoneIds.entries.take(MAX_RESULTS)) {
            val emails = mutableListOf<String>()
            val emailProjection = arrayOf(ContactsContract.CommonDataKinds.Email.ADDRESS)
            val emailSel = "${ContactsContract.CommonDataKinds.Email.CONTACT_ID} = ?"
            try {
                resolver.query(ContactsContract.CommonDataKinds.Email.CONTENT_URI,
                    emailProjection, emailSel, arrayOf(id.toString()), null)?.use { c ->
                    while (c.moveToNext() && emails.size < 2) {
                        val email = c.getString(0) ?: ""
                        if (email.isNotBlank()) emails.add(email)
                    }
                }
            } catch (_: Exception) {}

            // Load company / organization
            var company: String? = null
            val orgSel = "${ContactsContract.Data.CONTACT_ID} = ? AND " +
                "${ContactsContract.Data.MIMETYPE} = '${ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE}'"
            try {
                resolver.query(ContactsContract.Data.CONTENT_URI,
                    arrayOf(ContactsContract.CommonDataKinds.Organization.COMPANY),
                    orgSel, arrayOf(id.toString()), null)?.use { c ->
                    if (c.moveToFirst()) {
                        company = c.getString(0)?.takeIf { it.isNotBlank() }
                    }
                }
            } catch (_: Exception) {}

            results.add(ContactInfo(
                id = id,
                name = namePhones.first,
                phones = namePhones.second.distinct(),
                emails = emails,
                company = company
            ))
        }

        // Sort: prefer exact/prefix matches over partial matches
        return results.sortedByDescending { contact ->
            when {
                contact.name.equals(query, ignoreCase = true) -> 3
                contact.name.startsWith(query, ignoreCase = true) -> 2
                else -> 1
            }
        }
    }
}
