package com.nova.companion.workflows

/**
 * Central registry of all built-in workflow templates.
 * Add new workflows here — they become available automatically.
 */
class WorkflowRegistry {

    private val templates = mutableListOf<WorkflowTemplate>()

    init {
        registerAll()
    }

    fun getAll(): List<WorkflowTemplate> = templates.toList()

    fun findById(id: String): WorkflowTemplate? = templates.find { it.id == id }

    private fun registerAll() {

        // ════════════════════════════════════════
        // FITNESS WORKFLOWS
        // ════════════════════════════════════════

        register(
            WorkflowTemplate(
                id = "gym_checkin",
                name = "Gym Check-in",
                triggers = listOf("at the gym", "just got to gym", "heading to gym", "gym time", "going gym", "gym session"),
                steps = listOf(
                    WorkflowStep("message", "Gym mode activated. What's today's session?"),
                    WorkflowStep("log", "gym_checkin:{date}"),
                    WorkflowStep("message", "Track your lifts — I'll remember your PRs.", delayMs = 500)
                ),
                category = "fitness"
            )
        )

        register(
            WorkflowTemplate(
                id = "morning_routine",
                name = "Morning Routine",
                triggers = listOf("good morning", "gm", "morning nova", "just woke up", "woke up"),
                steps = listOf(
                    WorkflowStep("message", "Morning. Sleep well?"),
                    WorkflowStep("log", "morning_checkin:{date}"),
                    WorkflowStep("message", "Here's what we're locking in today:", delayMs = 800),
                    WorkflowStep("daily_brief", "top_priorities")
                ),
                category = "productivity"
            )
        )

        register(
            WorkflowTemplate(
                id = "workout_complete",
                name = "Workout Complete",
                triggers = listOf("workout done", "finished gym", "gym done", "just finished working out", "done training", "post workout"),
                steps = listOf(
                    WorkflowStep("message", "Good work. Log the session:"),
                    WorkflowStep("log", "workout_complete:{date}"),
                    WorkflowStep("message", "Protein in? You've got 30 min post-workout window.", delayMs = 500)
                ),
                category = "fitness"
            )
        )

        register(
            WorkflowTemplate(
                id = "cutting_update",
                name = "Cutting Phase Update",
                triggers = listOf("weigh in", "weight update", "current weight", "weighed myself", "morning weight"),
                steps = listOf(
                    WorkflowStep("message", "Got it. Logging your weight."),
                    WorkflowStep("log", "weight_log:{value}:{date}"),
                    WorkflowStep("message", "Trend looks like: [weight_trend]. Keep deficit tight.", delayMs = 400)
                ),
                category = "fitness"
            )
        )

        // ════════════════════════════════════════
        // BUSINESS / PRODUCTIVITY WORKFLOWS
        // ════════════════════════════════════════

        register(
            WorkflowTemplate(
                id = "focus_mode",
                name = "Deep Focus Session",
                triggers = listOf("focus mode", "deep work", "locking in", "locked in", "focus session", "no distractions", "grind time"),
                steps = listOf(
                    WorkflowStep("message", "Focus mode on. What's the one thing you're finishing?"),
                    WorkflowStep("log", "focus_session_start:{date}"),
                    WorkflowStep("message", "Timer running. I'll check in when you resurface.", delayMs = 600)
                ),
                category = "productivity"
            )
        )

        register(
            WorkflowTemplate(
                id = "blayzex_update",
                name = "Blayzex Business Update",
                triggers = listOf("blayzex update", "startup update", "business update", "sales update", "revenue update"),
                steps = listOf(
                    WorkflowStep("message", "Blayzex check-in. What moved today?"),
                    WorkflowStep("log", "business_update:{date}"),
                    WorkflowStep("message", "Got it. What's the bottleneck right now?", delayMs = 400)
                ),
                category = "business"
            )
        )

        register(
            WorkflowTemplate(
                id = "daily_planning",
                name = "Daily Planning",
                triggers = listOf("plan my day", "daily plan", "what should i do today", "help me plan", "schedule today"),
                steps = listOf(
                    WorkflowStep("message", "Let's plan it out. Top 3 things that have to happen today?"),
                    WorkflowStep("log", "daily_plan:{date}"),
                    WorkflowStep("message", "Set. I'll remind you if you go off track.", delayMs = 600)
                ),
                category = "productivity"
            )
        )

        register(
            WorkflowTemplate(
                id = "weekly_review",
                name = "Weekly Review",
                triggers = listOf("weekly review", "week recap", "how was my week", "end of week", "week summary"),
                steps = listOf(
                    WorkflowStep("message", "Week review. What did you actually ship?"),
                    WorkflowStep("recall_memories", "fitness,business,goals"),
                    WorkflowStep("message", "What's the one thing you're carrying into next week?", delayMs = 500)
                ),
                category = "productivity"
            )
        )

        // ════════════════════════════════════════
        // EMOTIONAL / MENTAL WORKFLOWS
        // ════════════════════════════════════════

        register(
            WorkflowTemplate(
                id = "stress_check",
                name = "Stress Check-in",
                triggers = listOf("feeling stressed", "stressed out", "anxious", "overwhelmed", "too much", "breaking down", "can't handle"),
                steps = listOf(
                    WorkflowStep("message", "I hear you. What's the loudest thing in your head right now?"),
                    WorkflowStep("log", "stress_checkin:{date}"),
                    WorkflowStep("message", "Got it. Let's break it down — what's actually in your control?", delayMs = 800)
                ),
                category = "emotional"
            )
        )

        register(
            WorkflowTemplate(
                id = "motivation_boost",
                name = "Motivation Boost",
                triggers = listOf("not motivated", "unmotivated", "no motivation", "feeling lazy", "don't want to", "procrastinating"),
                steps = listOf(
                    WorkflowStep("message", "You don't need motivation. You need a start."),
                    WorkflowStep("message", "What's the smallest possible action you could take right now?", delayMs = 400)
                ),
                category = "emotional"
            )
        )

        register(
            WorkflowTemplate(
                id = "night_reflect",
                name = "Night Reflection",
                triggers = listOf("good night", "gn nova", "going to sleep", "heading to bed", "night reflection", "end of day"),
                steps = listOf(
                    WorkflowStep("message", "Before you sleep — what was today's win?"),
                    WorkflowStep("log", "night_reflection:{date}"),
                    WorkflowStep("message", "Locked in. Sleep well, come back ready.", delayMs = 600)
                ),
                category = "emotional"
            )
        )

        // ════════════════════════════════════════
        // LEARNING / CODING WORKFLOWS
        // ════════════════════════════════════════

        register(
            WorkflowTemplate(
                id = "coding_session",
                name = "Coding Session Start",
                triggers = listOf("coding session", "coding now", "starting to code", "building now", "dev mode", "shipping today"),
                steps = listOf(
                    WorkflowStep("message", "Dev mode on. What are you building?"),
                    WorkflowStep("log", "coding_session:{date}"),
                    WorkflowStep("message", "Block the noise. Ship the thing.", delayMs = 400)
                ),
                category = "coding"
            )
        )

        register(
            WorkflowTemplate(
                id = "debug_mode",
                name = "Debug Help",
                triggers = listOf("got a bug", "debugging", "something broke", "error in my code", "can't fix this", "help debug"),
                steps = listOf(
                    WorkflowStep("message", "Paste the error + relevant code. I'll go through it."),
                    WorkflowStep("log", "debug_session:{date}")
                ),
                category = "coding"
            )
        )

        // ════════════════════════════════════════
        // QUICK UTILITIES
        // ════════════════════════════════════════

        register(
            WorkflowTemplate(
                id = "quick_reminder",
                name = "Quick Reminder",
                triggers = listOf("remind me", "set a reminder", "don't let me forget", "remind me to"),
                steps = listOf(
                    WorkflowStep("message", "What do you need to remember, and when?")
                ),
                category = "productivity"
            )
        )

        register(
            WorkflowTemplate(
                id = "diet_check",
                name = "Diet Check-in",
                triggers = listOf("diet check", "what should i eat", "macro check", "calories today", "meal check"),
                steps = listOf(
                    WorkflowStep("message", "What have you eaten today?"),
                    WorkflowStep("log", "diet_checkin:{date}")
                ),
                category = "fitness"
            )
        )
    }

    private fun register(template: WorkflowTemplate) {
        templates.add(template)
    }
}
