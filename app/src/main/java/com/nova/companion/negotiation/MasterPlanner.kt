package com.nova.companion.negotiation

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.nova.companion.cloud.CloudConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * MasterPlanner — Nova's goal decomposition + cost-aware execution engine.
 *
 * Takes a complex user goal, breaks it down into steps, and resolves each
 * step using the cheapest available method:
 *
 *   FREE API  >  Web Scraping  >  UI Automation  >  Paid API
 *
 * Uses GPT-4o-mini (cheap) for planning and extraction. Only escalates
 * to GPT-4o when the plan itself is too complex.
 */
object MasterPlanner {

    private const val TAG = "MasterPlanner"
    private const val PLANNER_MODEL = "gpt-4o-mini"       // Cheap planner
    private const val FALLBACK_MODEL = "gpt-4o"            // Expensive fallback
    private const val MAX_PLAN_STEPS = 6                    // Cap step count
    private const val MAX_EXECUTION_ROUNDS = 8              // Max iterations

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    // ── System Prompt ────────────────────────────────────────────

    private fun plannerSystemPrompt(availableApis: List<String>): String = """
You are Nova's MasterPlanner. You decompose complex user goals into executable steps.

## COST HIERARCHY (always prefer cheaper methods)
1. FREE API — registered API endpoints (cheapest, fastest, most reliable)
2. WEB_SCRAPE — search the web + extract data from HTML (free but slower)
3. DEVICE_TOOL — use an on-device tool (sendSms, openApp, etc.)
4. UI_AUTOMATION — control the phone's UI via taps/types (slowest, last resort)
5. LLM_GENERATE — generate content directly (when no external data needed)

## AVAILABLE FREE APIs
${availableApis.joinToString("\n") { "- $it" }}

## AVAILABLE DEVICE TOOLS
- openApp, sendSms, makeCall, setAlarm, setTimer, createCalendarEvent
- lookupContact, sendWhatsApp, sendEmail, searchWeb, openUrl
- getWeather, getDirections, playSpotify, playYouTube
- toggleWifi, toggleBluetooth, setVolume, setBrightness

## RESPONSE FORMAT
Return a JSON object with this exact structure:
{
  "analysis": "1-2 sentence analysis of what the user wants",
  "steps": [
    {
      "id": 1,
      "action": "FREE_API|WEB_SCRAPE|DEVICE_TOOL|UI_AUTOMATION|LLM_GENERATE",
      "tool": "tool_id or api_id",
      "description": "what this step does",
      "params": { "key": "value" },
      "depends_on": null,
      "output_key": "step1_result"
    }
  ],
  "final_synthesis": "How to combine step results into a final answer for the user"
}

## RULES
1. Use the MINIMUM number of steps. If one API call solves it, use one step.
2. ALWAYS prefer FREE_API over WEB_SCRAPE. Only scrape if no API covers the need.
3. For simple questions that need no external data, use a single LLM_GENERATE step.
4. "depends_on" references the "id" of a previous step whose output this step needs.
5. "output_key" is a label for this step's result, used by later steps.
6. Respond with ONLY the JSON object. No markdown fences, no explanation.
""".trimIndent()

    // ── Plan Generation ──────────────────────────────────────────

    /**
     * Generate an execution plan for a user goal.
     *
     * @param goal The user's natural-language request
     * @param context Android context (for network checks)
     * @return ExecutionPlan or null if planning fails
     */
    suspend fun plan(goal: String, context: Context): ExecutionPlan? {
        if (!CloudConfig.isOnline(context)) {
            Log.w(TAG, "Offline — cannot plan")
            return null
        }

        val apiKey = CloudConfig.openAiApiKey
        if (apiKey.isBlank()) return null

        // Build available API descriptions
        val availableApis = ExternalApiRegistry.all()
            .filter { ExternalApiRegistry.isAvailable(it) }
            .map { "${it.id}: ${it.description} [${it.costTier}] tags=${it.tags}" }

        val systemPrompt = plannerSystemPrompt(availableApis)
        val userPrompt = "User goal: $goal"

        val planJson = callLlm(systemPrompt, userPrompt, apiKey, PLANNER_MODEL, context)
            ?: return null

        return parsePlan(planJson, goal)
    }

    // ── Plan Execution ───────────────────────────────────────────

    /**
     * Execute a plan step by step, feeding outputs forward.
     *
     * @param plan The execution plan from [plan]
     * @param context Android context
     * @param onDeviceTool Callback to execute on-device tools (from ToolRegistry)
     * @param onUiAutomation Callback to execute UI automation (via AgentExecutor)
     * @param onProgress Called with status updates for each step
     * @return Final result string to show the user
     */
    suspend fun execute(
        plan: ExecutionPlan,
        context: Context,
        onDeviceTool: suspend (String, Map<String, Any>) -> com.nova.companion.tools.ToolResult,
        onUiAutomation: suspend (String) -> String,
        onProgress: (String) -> Unit = {}
    ): String {
        val stepResults = mutableMapOf<String, String>()  // output_key -> result
        val apiKey = CloudConfig.openAiApiKey

        for (step in plan.steps) {
            onProgress("Step ${step.id}/${plan.steps.size}: ${step.description}")
            Log.d(TAG, "Executing step ${step.id}: ${step.action} / ${step.tool}")

            // Resolve params — substitute {output_key} references from previous steps
            val resolvedParams = step.params.mapValues { (_, value) ->
                if (value.startsWith("{") && value.endsWith("}")) {
                    val ref = value.removeSurrounding("{", "}")
                    stepResults[ref] ?: value
                } else value
            }

            val result: String = try {
                when (step.action) {
                    "FREE_API" -> executeApiStep(step.tool, resolvedParams)
                    "WEB_SCRAPE" -> executeScrapeStep(resolvedParams, apiKey, context)
                    "DEVICE_TOOL" -> executeDeviceToolStep(step.tool, resolvedParams, onDeviceTool)
                    "UI_AUTOMATION" -> onUiAutomation(step.description)
                    "LLM_GENERATE" -> executeLlmGenerateStep(resolvedParams, stepResults, apiKey, context)
                    else -> "Unknown action: ${step.action}"
                }
            } catch (e: Exception) {
                Log.e(TAG, "Step ${step.id} failed", e)
                "Step failed: ${e.message}"
            }

            stepResults[step.outputKey] = result
            Log.d(TAG, "Step ${step.id} result: ${result.take(200)}")
        }

        // Final synthesis — combine all step results into a user-facing answer
        return synthesize(plan, stepResults, apiKey, context)
    }

    // ── Step Executors ───────────────────────────────────────────

    private suspend fun executeApiStep(apiId: String, params: Map<String, String>): String {
        val spec = ExternalApiRegistry.get(apiId)
            ?: return "API '$apiId' not found in registry"

        val response = DynamicApiDispatcher.execute(spec, params)
        return response.toToolMessage()
    }

    private suspend fun executeScrapeStep(
        params: Map<String, String>,
        apiKey: String,
        context: Context
    ): String {
        val query = params["query"] ?: params["url"] ?: return "No query or URL provided for scrape"

        val result = if (query.startsWith("http")) {
            WebScraperTool.fetchAndClean(query, params["selector"])
        } else {
            WebScraperTool.searchAndScrape(query)
        }

        if (!result.success) return result.toToolMessage()

        // If there's an extraction goal, use LLM to extract structured data
        val extractionGoal = params["extract"]
        if (extractionGoal != null && apiKey.isNotBlank()) {
            val extractionPrompt = WebScraperTool.buildExtractionPrompt(result.text, extractionGoal)
            val extracted = callLlm(
                "You extract structured data from web page text. Return ONLY JSON.",
                extractionPrompt, apiKey, PLANNER_MODEL, context
            )
            return extracted ?: result.toToolMessage()
        }

        return result.toToolMessage()
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun executeDeviceToolStep(
        toolName: String,
        params: Map<String, String>,
        onDeviceTool: suspend (String, Map<String, Any>) -> com.nova.companion.tools.ToolResult
    ): String {
        val result = onDeviceTool(toolName, params as Map<String, Any>)
        return if (result.success) result.message else "Tool $toolName failed: ${result.message}"
    }

    private suspend fun executeLlmGenerateStep(
        params: Map<String, String>,
        stepResults: Map<String, String>,
        apiKey: String,
        context: Context
    ): String {
        val prompt = params["prompt"] ?: "Generate a response"

        // Inject previous step results as context
        val contextBlock = if (stepResults.isNotEmpty()) {
            "\n\n## Context from previous steps:\n" +
            stepResults.entries.joinToString("\n") { (k, v) -> "- $k: ${v.take(500)}" }
        } else ""

        return callLlm(
            "You are Nova, a helpful AI assistant. Be concise.",
            prompt + contextBlock, apiKey, PLANNER_MODEL, context
        ) ?: "Failed to generate response"
    }

    // ── Final Synthesis ──────────────────────────────────────────

    private suspend fun synthesize(
        plan: ExecutionPlan,
        stepResults: Map<String, String>,
        apiKey: String,
        context: Context
    ): String {
        if (plan.steps.size == 1) {
            // Single step — just return the result directly
            return stepResults.values.firstOrNull() ?: "No result"
        }

        // Multi-step — ask LLM to synthesize
        val synthesisPrompt = buildString {
            appendLine("The user asked: \"${plan.originalGoal}\"")
            appendLine()
            appendLine("Here are the results from each step:")
            stepResults.forEach { (key, value) ->
                appendLine("## $key")
                appendLine(value.take(1000))
                appendLine()
            }
            appendLine("Synthesis instruction: ${plan.finalSynthesis}")
            appendLine()
            appendLine("Combine these results into a clear, concise answer for the user.")
            appendLine("Respond in Nova's style — casual bro-talk, short, no emojis. Just give the answer.")
        }

        return callLlm(
            "You are Nova. Synthesize step results into a final answer. Be concise, casual, direct.",
            synthesisPrompt, apiKey, PLANNER_MODEL, context
        ) ?: stepResults.values.joinToString("\n\n") // Fallback: raw concatenation
    }

    // ── LLM Call Helper ──────────────────────────────────────────

    private suspend fun callLlm(
        system: String,
        user: String,
        apiKey: String,
        model: String = PLANNER_MODEL,
        context: Context
    ): String? = withContext(Dispatchers.IO) {
        val body = JsonObject().apply {
            addProperty("model", model)
            add("messages", JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("role", "system")
                    addProperty("content", system)
                })
                add(JsonObject().apply {
                    addProperty("role", "user")
                    addProperty("content", user)
                })
            })
            addProperty("max_tokens", 1024)
            addProperty("temperature", 0.3)
            if (model == PLANNER_MODEL) {
                add("response_format", JsonObject().apply {
                    addProperty("type", "json_object")
                })
            }
        }

        val request = Request.Builder()
            .url("https://api.openai.com/v1/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        try {
            val response = client.newCall(request).execute()
            val responseBody = response.body?.string()
            response.close()

            if (!response.isSuccessful) {
                Log.e(TAG, "Planner LLM call failed: ${response.code}")
                return@withContext null
            }

            val json = JsonParser.parseString(responseBody).asJsonObject
            val content = json.getAsJsonArray("choices")
                ?.get(0)?.asJsonObject
                ?.getAsJsonObject("message")
                ?.get("content")?.asString

            // Track tokens
            val tokens = json.getAsJsonObject("usage")?.get("total_tokens")?.asInt ?: 0
            CloudConfig.trackOpenAiTokens(context, tokens)

            content
        } catch (e: Exception) {
            Log.e(TAG, "Planner LLM call error", e)
            null
        }
    }

    // ── Plan Parsing ─────────────────────────────────────────────

    private fun parsePlan(raw: String, originalGoal: String): ExecutionPlan? {
        return try {
            val cleaned = raw.trim()
                .removePrefix("```json").removePrefix("```")
                .removeSuffix("```").trim()

            val json = JsonParser.parseString(cleaned).asJsonObject
            val analysis = json.get("analysis")?.asString ?: ""
            val finalSynthesis = json.get("final_synthesis")?.asString ?: "Return the combined results"

            val stepsArray = json.getAsJsonArray("steps") ?: return null
            val steps = (0 until stepsArray.size().coerceAtMost(MAX_PLAN_STEPS)).map { i ->
                val stepJson = stepsArray.get(i).asJsonObject
                PlanStep(
                    id = stepJson.get("id")?.asInt ?: (i + 1),
                    action = stepJson.get("action")?.asString ?: "LLM_GENERATE",
                    tool = stepJson.get("tool")?.asString ?: "",
                    description = stepJson.get("description")?.asString ?: "",
                    params = parseParams(stepJson.getAsJsonObject("params")),
                    dependsOn = stepJson.get("depends_on")?.let {
                        if (it.isJsonNull) null else it.asInt
                    },
                    outputKey = stepJson.get("output_key")?.asString ?: "step${i + 1}_result"
                )
            }

            ExecutionPlan(
                originalGoal = originalGoal,
                analysis = analysis,
                steps = steps,
                finalSynthesis = finalSynthesis
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse plan: ${raw.take(300)}", e)
            null
        }
    }

    private fun parseParams(json: JsonObject?): Map<String, String> {
        if (json == null) return emptyMap()
        return json.entrySet().associate { (k, v) ->
            k to (if (v.isJsonPrimitive) v.asString else v.toString())
        }
    }
}

// ── Data Models ──────────────────────────────────────────────────

data class ExecutionPlan(
    val originalGoal: String,
    val analysis: String,
    val steps: List<PlanStep>,
    val finalSynthesis: String
)

data class PlanStep(
    val id: Int,
    /** FREE_API, WEB_SCRAPE, DEVICE_TOOL, UI_AUTOMATION, LLM_GENERATE */
    val action: String,
    /** api_id, tool_name, or empty for LLM_GENERATE */
    val tool: String,
    val description: String,
    val params: Map<String, String>,
    val dependsOn: Int? = null,
    val outputKey: String
)
