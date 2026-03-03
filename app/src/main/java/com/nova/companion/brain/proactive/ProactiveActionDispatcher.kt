package com.nova.companion.brain.proactive

import android.content.Context
import android.util.Log
import com.nova.companion.notification.NovaNotificationHelper

/**
 * Dispatches [ProactiveDecision]s produced by the LLM inference engine.
 *
 * - Notification → shows an Android notification via [NovaNotificationHelper]
 * - Action → delegates to the appropriate background handler
 * - None → no-op
 *
 * Actions are intentionally limited to safe, non-destructive operations
 * that Nova can perform without user confirmation.
 */
object ProactiveActionDispatcher {

    private const val TAG = "ProactiveDispatch"

    /**
     * Dispatch a proactive decision. Returns true if something was dispatched.
     */
    fun dispatch(context: Context, decision: ProactiveDecision): Boolean {
        return when (decision) {
            is ProactiveDecision.Notification -> {
                dispatchNotification(context, decision)
                true
            }

            is ProactiveDecision.Action -> {
                dispatchAction(context, decision)
            }

            is ProactiveDecision.None -> {
                Log.d(TAG, "LLM decided: no action needed")
                false
            }
        }
    }

    private fun dispatchNotification(context: Context, decision: ProactiveDecision.Notification) {
        Log.i(TAG, "Dispatching notification: ${decision.title} — ${decision.body.take(80)}")
        NovaNotificationHelper.showNotification(
            context = context,
            notificationId = NovaNotificationHelper.NOTIFICATION_ID_PROACTIVE,
            message = decision.body,
            title = decision.title
        )
    }

    private fun dispatchAction(context: Context, decision: ProactiveDecision.Action): Boolean {
        Log.i(TAG, "Dispatching action: ${decision.action} with args: ${decision.args}")

        return when (decision.action.lowercase()) {
            // Future action handlers can be added here.
            // For now, surface unrecognized actions as notifications
            // so the LLM's suggestions aren't silently dropped.
            else -> {
                // Convert unknown actions into a notification prompt
                val body = buildActionNotificationBody(decision)
                if (body != null) {
                    NovaNotificationHelper.showNotification(
                        context = context,
                        notificationId = NovaNotificationHelper.NOTIFICATION_ID_PROACTIVE,
                        message = body,
                        title = "Nova"
                    )
                    true
                } else {
                    Log.w(TAG, "Unhandled action type: ${decision.action}")
                    false
                }
            }
        }
    }

    /**
     * Convert an action decision into a notification body when we can't
     * execute the action directly. This way the LLM's suggestion still
     * reaches the user.
     */
    private fun buildActionNotificationBody(decision: ProactiveDecision.Action): String? {
        val query = decision.args["query"] as? String
        val message = decision.args["message"] as? String
        val contact = decision.args["contact"] as? String

        return when (decision.action.lowercase()) {
            "scrape_web", "web_search" -> {
                query?.let { "yo want me to look up: $it?" }
            }

            "call", "dial" -> {
                contact?.let { "want me to call $it?" }
            }

            "send_message", "text" -> {
                if (contact != null && message != null) {
                    "want me to text $contact: \"$message\"?"
                } else contact?.let { "want me to text $it?" }
            }

            "reminder", "set_reminder" -> {
                message?.let { "reminder: $it" }
            }

            else -> {
                // Generic fallback — present the action as a suggestion
                val argSummary = decision.args.entries
                    .take(2)
                    .joinToString(", ") { "${it.key}: ${it.value}" }
                "suggestion: ${decision.action}${if (argSummary.isNotBlank()) " ($argSummary)" else ""}"
            }
        }
    }
}
