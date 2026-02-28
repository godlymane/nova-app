package com.nova.companion.brain.context.collectors

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CallLog
import android.provider.Telephony
import androidx.core.content.ContextCompat
import com.nova.companion.brain.context.ContextSnapshot

object CommunicationCollector {

    fun collect(context: Context, snapshot: ContextSnapshot): ContextSnapshot {
        var updatedSnapshot = snapshot

        // Missed calls
        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_CALL_LOG
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            updatedSnapshot = collectMissedCalls(context, updatedSnapshot)
        }

        // Unread SMS
        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_SMS
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            updatedSnapshot = collectUnreadSms(context, updatedSnapshot)
        }

        return updatedSnapshot
    }

    private fun collectMissedCalls(context: Context, snapshot: ContextSnapshot): ContextSnapshot {
        return try {
            val since = System.currentTimeMillis() - 24 * 60 * 60 * 1000L // last 24h
            val cursor = context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME, CallLog.Calls.DATE),
                "${CallLog.Calls.TYPE} = ? AND ${CallLog.Calls.DATE} > ? AND ${CallLog.Calls.NEW} = ?",
                arrayOf(CallLog.Calls.MISSED_TYPE.toString(), since.toString(), "1"),
                "${CallLog.Calls.DATE} DESC"
            )

            var count = 0
            var lastPerson: String? = null
            cursor?.use { c ->
                count = c.count
                if (c.moveToFirst()) {
                    lastPerson = c.getString(1)?.takeIf { it.isNotBlank() } ?: c.getString(0)
                }
            }

            snapshot.copy(
                missedCalls = count,
                lastContactedPerson = lastPerson ?: snapshot.lastContactedPerson
            )
        } catch (e: Exception) {
            snapshot
        }
    }

    private fun collectUnreadSms(context: Context, snapshot: ContextSnapshot): ContextSnapshot {
        return try {
            val cursor = context.contentResolver.query(
                Telephony.Sms.Inbox.CONTENT_URI,
                arrayOf(Telephony.Sms._ID),
                "${Telephony.Sms.READ} = 0",
                null,
                null
            )
            val count = cursor?.count ?: 0
            cursor?.close()
            snapshot.copy(unreadSmsCount = count)
        } catch (e: Exception) {
            snapshot
        }
    }
}
