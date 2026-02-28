package com.nova.companion.tools

import android.content.Context
import android.database.Cursor
import android.provider.ContactsContract
import android.util.Log

object ContactLookupHelper {

    private const val TAG = "ContactLookup"

    data class ContactResult(val name: String, val phoneNumber: String)

    fun lookupByName(context: Context, name: String): List<ContactResult> {
        val results = mutableListOf<ContactResult>()
        var cursor: Cursor? = null

        try {
            val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
            val projection = arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            )
            val selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
            val selectionArgs = arrayOf("%$name%")
            val sortOrder = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"

            cursor = context.contentResolver.query(
                uri, projection, selection, selectionArgs, sortOrder
            )

            cursor?.let {
                val nameIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numberIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)

                while (it.moveToNext()) {
                    val contactName = if (nameIndex >= 0) it.getString(nameIndex) else null
                    val contactNumber = if (numberIndex >= 0) it.getString(numberIndex) else null

                    if (!contactName.isNullOrBlank() && !contactNumber.isNullOrBlank()) {
                        results.add(ContactResult(contactName, contactNumber.trim()))
                    }
                }
            }

            Log.d(TAG, "Found ${results.size} contacts matching '$name'")
        } catch (e: SecurityException) {
            Log.e(TAG, "READ_CONTACTS permission not granted", e)
        } catch (e: Exception) {
            Log.e(TAG, "Error looking up contact '$name'", e)
        } finally {
            cursor?.close()
        }

        return results
    }

    fun formatPhoneForWhatsApp(phone: String): String {
        val cleaned = phone.replace(Regex("[\\s\\-().]"), "")
        return if (cleaned.startsWith("+")) {
            cleaned
        } else {
            "+91$cleaned"
        }
    }
}