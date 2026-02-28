package com.nova.companion.brain.proactive.triggers

import com.nova.companion.brain.context.ContextSnapshot
import com.nova.companion.brain.proactive.ProactiveMessage

class CommunicationTrigger {

    fun evaluate(snapshot: ContextSnapshot): ProactiveMessage? {
        val missedCalls = snapshot.missedCalls
        val unreadSms = snapshot.unreadSmsCount
        val lastPerson = snapshot.lastContactedPerson

        // Multiple missed calls from same person
        if (missedCalls >= 2 && lastPerson != null) {
            return ProactiveMessage(
                message = "You have $missedCalls missed calls from $lastPerson. Want to call back?",
                priority = ProactiveMessage.Priority.HIGH,
                triggerType = "communication_missed_calls"
            )
        }

        // Single missed call
        if (missedCalls == 1 && lastPerson != null) {
            return ProactiveMessage(
                message = "You missed a call from $lastPerson.",
                priority = ProactiveMessage.Priority.NORMAL,
                triggerType = "communication_missed_call"
            )
        }

        // Many unread messages
        if (unreadSms >= 5) {
            return ProactiveMessage(
                message = "You have $unreadSms unread messages.",
                priority = ProactiveMessage.Priority.NORMAL,
                triggerType = "communication_unread_sms"
            )
        }

        return null
    }
}
