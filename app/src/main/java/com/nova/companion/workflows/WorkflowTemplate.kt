package com.nova.companion.workflows

/**
 * A reusable workflow template that Nova can execute automatically.
 *
 * @param id           Unique identifier (e.g. "gym_checkin")
 * @param name         Human-readable name
 * @param triggers     Keywords/phrases that activate this workflow
 * @param steps        Ordered list of steps to execute
 * @param category     Grouping for display (fitness, business, productivity, etc.)
 */
data class WorkflowTemplate(
    val id: String,
    val name: String,
    val triggers: List<String>,
    val steps: List<WorkflowStep>,
    val category: String = "general"
)

/**
 * A single step within a workflow.
 *
 * @param action       What to do ("message", "search", "reminder", "log", "api_call")
 * @param content      The content or query for this step
 * @param delayMs      Optional delay before executing this step
 */
data class WorkflowStep(
    val action: String,
    val content: String,
    val delayMs: Long = 0L
)
