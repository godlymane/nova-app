package com.nova.companion.workflows

/**
 * Matches user messages to workflow templates using keyword/trigger matching.
 */
class WorkflowMatcher(private val registry: WorkflowRegistry) {

    /**
     * Find the best-matching workflow for a user message.
     * Returns null if no trigger matches.
     */
    fun match(userMessage: String): WorkflowTemplate? {
        val msg = userMessage.lowercase().trim()

        // Score each template by how many triggers match
        val scored = registry.getAll().mapNotNull { template ->
            val matchCount = template.triggers.count { trigger ->
                msg.contains(trigger.lowercase())
            }
            if (matchCount > 0) template to matchCount else null
        }

        // Return the highest-scoring match
        return scored.maxByOrNull { it.second }?.first
    }

    /**
     * Get all workflows that match a message (for debug/logging).
     */
    fun matchAll(userMessage: String): List<WorkflowTemplate> {
        val msg = userMessage.lowercase().trim()
        return registry.getAll().filter { template ->
            template.triggers.any { trigger -> msg.contains(trigger.lowercase()) }
        }
    }
}
