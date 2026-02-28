package com.nova.companion.notification

/**
 * All proactive notification messages Nova can send.
 * Organized by notification type with random selection.
 */
object NovaMessages {

    val morning = listOf(
        "morning bro. whats the plan today?",
        "rise and grind. gym or code first?",
        "new day new gains. lock in."
    )

    val preGym = listOf(
        "gym time. what are we hitting today?",
        "dont skip today. even a light session counts.",
        "chest? back? legs? lets go."
    )

    val lunch = listOf(
        "you eaten yet? protein first bro.",
        "lunch time. dont skip meals on a cut.",
        "eat clean. you know the drill."
    )

    val dinner = listOf(
        "dinner time. hit your protein target?",
        "last meal of the day. make it count."
    )

    val night = listOf(
        "phone down. time to sleep. how was today?",
        "rest is gains bro. sleep well.",
        "tomorrow we go again. night."
    )

    val inactive = listOf(
        "bro where you been? dont ghost me lol"
    )

    val sundayReview = listOf(
        "weekly review. what did you ship this week?"
    )

    val mondayGoals = listOf(
        "new week. set 3 goals and lock in."
    )

    fun random(messages: List<String>): String {
        return messages.random()
    }
}
