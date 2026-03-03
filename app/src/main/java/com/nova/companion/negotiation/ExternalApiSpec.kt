package com.nova.companion.negotiation

/**
 * Describes an external API endpoint that Nova can call dynamically.
 *
 * These specs are loaded at runtime from bundled JSON or fetched from a
 * remote registry.  The MasterPlanner uses them to choose the cheapest
 * resolution strategy for a user goal.
 */
data class ExternalApiSpec(
    /** Unique id used by the planner (e.g. "openweathermap_current") */
    val id: String,
    /** Human-readable name shown in logs / debug UI */
    val name: String,
    /** One-line description the LLM sees when picking tools */
    val description: String,
    /** HTTP method: GET, POST, PUT, DELETE */
    val method: String = "GET",
    /** URL template — placeholders use {param_name} syntax */
    val urlTemplate: String,
    /** Static headers (auth, content-type, etc.) — may contain {param_name} placeholders */
    val headers: Map<String, String> = emptyMap(),
    /** Parameter definitions the LLM must fill */
    val parameters: List<ApiParamSpec> = emptyList(),
    /** Optional JSON body template (POST/PUT). Placeholders use {param_name}. */
    val bodyTemplate: String? = null,
    /** JSONPath-like expression to extract the useful part of the response */
    val responseExtractor: String? = null,
    /** Cost tier: FREE, CHEAP (< $0.001/call), PAID */
    val costTier: CostTier = CostTier.FREE,
    /** Rate limit — calls per minute (0 = unlimited) */
    val rateLimit: Int = 60,
    /** Whether an API key is required (key name in CloudConfig / BuildConfig) */
    val apiKeyField: String? = null,
    /** Tags for intent matching ("weather", "news", "search", "finance", etc.) */
    val tags: Set<String> = emptySet()
)

data class ApiParamSpec(
    val name: String,
    val type: String = "string",         // string, integer, number, boolean
    val description: String,
    val required: Boolean = true,
    val defaultValue: String? = null,
    /** Where the param goes: "query", "path", "header", "body" */
    val location: String = "query"
)

enum class CostTier {
    FREE,   // No API key or free-tier key
    CHEAP,  // < $0.001 per call
    PAID    // Metered / significant cost
}
