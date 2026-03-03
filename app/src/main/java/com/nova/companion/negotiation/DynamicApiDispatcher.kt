package com.nova.companion.negotiation

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * DynamicApiDispatcher — executes HTTP requests from ExternalApiSpec definitions
 * or raw LLM-generated HTTP instructions.
 *
 * This is the "bootstrapper's API gateway" — takes a spec + param values,
 * builds the HTTP request, fires it, and returns structured results.
 */
object DynamicApiDispatcher {

    private const val TAG = "ApiDispatcher"
    private const val MAX_RESPONSE_CHARS = 8_000  // Truncate large responses before feeding to LLM

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val gson = Gson()

    /**
     * Execute a registered API spec with the given parameters.
     *
     * @param spec  The API specification from ExternalApiRegistry
     * @param params  Map of parameter name → value (as provided by the LLM)
     * @return ApiResponse with status, body, and optional extracted data
     */
    suspend fun execute(
        spec: ExternalApiSpec,
        params: Map<String, String>
    ): ApiResponse = withContext(Dispatchers.IO) {
        try {
            // Resolve API key placeholders
            val resolvedParams = resolveParams(spec, params)

            // Build URL from template
            val url = buildUrl(spec.urlTemplate, resolvedParams, spec.parameters)
            Log.d(TAG, "Dispatching ${spec.method} $url")

            // Build headers
            val headers = spec.headers.mapValues { (_, v) ->
                interpolate(v, resolvedParams)
            }

            // Build request
            val requestBuilder = Request.Builder().url(url)
            headers.forEach { (k, v) -> requestBuilder.addHeader(k, v) }

            when (spec.method.uppercase()) {
                "GET" -> requestBuilder.get()
                "POST" -> {
                    val body = if (spec.bodyTemplate != null) {
                        interpolate(spec.bodyTemplate, resolvedParams)
                    } else {
                        // Auto-generate JSON body from params marked location=body
                        val bodyParams = spec.parameters
                            .filter { it.location == "body" }
                            .associate { it.name to (resolvedParams[it.name] ?: "") }
                        gson.toJson(bodyParams)
                    }
                    requestBuilder.post(body.toRequestBody("application/json".toMediaType()))
                }
                "PUT" -> {
                    val body = spec.bodyTemplate?.let { interpolate(it, resolvedParams) } ?: "{}"
                    requestBuilder.put(body.toRequestBody("application/json".toMediaType()))
                }
                "DELETE" -> requestBuilder.delete()
                else -> requestBuilder.get()
            }

            // Execute
            val response = client.newCall(requestBuilder.build()).execute()
            val responseBody = response.body?.string() ?: ""
            val statusCode = response.code
            response.close()

            if (!response.isSuccessful) {
                Log.w(TAG, "${spec.id} returned $statusCode: ${responseBody.take(200)}")
                return@withContext ApiResponse(
                    success = false,
                    statusCode = statusCode,
                    rawBody = responseBody.take(MAX_RESPONSE_CHARS),
                    error = "HTTP $statusCode"
                )
            }

            // Extract relevant data if extractor is defined
            val extracted = if (spec.responseExtractor != null) {
                extractFromJson(responseBody, spec.responseExtractor)
            } else {
                null
            }

            // Truncate raw body to avoid blowing up LLM context
            val truncated = if (responseBody.length > MAX_RESPONSE_CHARS) {
                responseBody.take(MAX_RESPONSE_CHARS) + "\n... [truncated, ${responseBody.length} chars total]"
            } else {
                responseBody
            }

            ApiResponse(
                success = true,
                statusCode = statusCode,
                rawBody = truncated,
                extractedData = extracted
            )
        } catch (e: Exception) {
            Log.e(TAG, "API dispatch failed for ${spec.id}", e)
            ApiResponse(
                success = false,
                statusCode = 0,
                error = "${e.javaClass.simpleName}: ${e.message}"
            )
        }
    }

    /**
     * Execute a raw HTTP request from LLM-generated instructions.
     * Used when the planner synthesizes a request on the fly for an API
     * not in the registry.
     */
    suspend fun executeRaw(
        method: String,
        url: String,
        headers: Map<String, String> = emptyMap(),
        body: String? = null
    ): ApiResponse = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Raw dispatch: $method $url")
            val requestBuilder = Request.Builder().url(url)
            headers.forEach { (k, v) -> requestBuilder.addHeader(k, v) }

            when (method.uppercase()) {
                "POST" -> requestBuilder.post(
                    (body ?: "").toRequestBody("application/json".toMediaType())
                )
                "PUT" -> requestBuilder.put(
                    (body ?: "").toRequestBody("application/json".toMediaType())
                )
                "DELETE" -> requestBuilder.delete()
                else -> requestBuilder.get()
            }

            val response = client.newCall(requestBuilder.build()).execute()
            val responseBody = response.body?.string() ?: ""
            val statusCode = response.code
            response.close()

            val truncated = if (responseBody.length > MAX_RESPONSE_CHARS) {
                responseBody.take(MAX_RESPONSE_CHARS) + "\n... [truncated]"
            } else {
                responseBody
            }

            ApiResponse(
                success = response.isSuccessful,
                statusCode = statusCode,
                rawBody = truncated,
                error = if (!response.isSuccessful) "HTTP $statusCode" else null
            )
        } catch (e: Exception) {
            Log.e(TAG, "Raw API dispatch failed", e)
            ApiResponse(success = false, statusCode = 0, error = "${e.javaClass.simpleName}: ${e.message}")
        }
    }

    // ── URL + Param Helpers ───────────────────────────────────────

    private fun resolveParams(spec: ExternalApiSpec, userParams: Map<String, String>): Map<String, String> {
        val resolved = mutableMapOf<String, String>()

        for (paramSpec in spec.parameters) {
            val value = userParams[paramSpec.name]
                ?: paramSpec.defaultValue
                ?: if (paramSpec.required) "" else continue

            // Resolve magic API key placeholders
            val finalValue = when {
                value.startsWith("__") && value.endsWith("__") -> {
                    val keyName = value.removePrefix("__").removeSuffix("__")
                    ExternalApiRegistry.resolveApiKey(keyName)
                }
                else -> value
            }

            resolved[paramSpec.name] = finalValue
        }

        // Also include any extra params the user passed that aren't in the spec
        userParams.forEach { (k, v) ->
            if (k !in resolved) resolved[k] = v
        }

        return resolved
    }

    private fun buildUrl(template: String, params: Map<String, String>, paramSpecs: List<ApiParamSpec>): String {
        // First, replace {param} placeholders in the URL template
        var url = interpolate(template, params)

        // Then add any remaining query params that weren't in the template
        val queryParams = paramSpecs
            .filter { it.location == "query" && !template.contains("{${it.name}}") }
            .mapNotNull { spec ->
                val value = params[spec.name] ?: return@mapNotNull null
                if (value.isBlank()) return@mapNotNull null
                "${URLEncoder.encode(spec.name, "UTF-8")}=${URLEncoder.encode(value, "UTF-8")}"
            }

        if (queryParams.isNotEmpty()) {
            val separator = if (url.contains("?")) "&" else "?"
            url += separator + queryParams.joinToString("&")
        }

        return url
    }

    private fun interpolate(template: String, params: Map<String, String>): String {
        var result = template
        params.forEach { (key, value) ->
            result = result.replace("{$key}", URLEncoder.encode(value, "UTF-8"))
        }
        return result
    }

    // ── JSON Extraction ──────────────────────────────────────────

    /**
     * Simple dot-path JSON extractor.
     * Supports: "field", "field.nested", "array[0].field", comma-separated multi-path.
     */
    private fun extractFromJson(json: String, paths: String): String {
        return try {
            val root = JsonParser.parseString(json)
            val results = paths.split(",").map { it.trim() }.mapNotNull { path ->
                try {
                    var current = root
                    val segments = path.split(".")
                    for (seg in segments) {
                        val arrayMatch = Regex("""(\w+)\[(\d+)]""").matchEntire(seg)
                        if (arrayMatch != null) {
                            val (field, index) = arrayMatch.destructured
                            current = current.asJsonObject.get(field)
                                .asJsonArray.get(index.toInt())
                        } else {
                            current = current.asJsonObject.get(seg) ?: return@mapNotNull null
                        }
                    }
                    current.toString().removeSurrounding("\"")
                } catch (_: Exception) {
                    null
                }
            }
            results.joinToString(" | ")
        } catch (e: Exception) {
            Log.w(TAG, "JSON extraction failed for paths=$paths", e)
            json.take(500) // fallback: return raw truncated
        }
    }
}

/**
 * Result of an API dispatch call.
 */
data class ApiResponse(
    val success: Boolean,
    val statusCode: Int = 0,
    val rawBody: String = "",
    val extractedData: String? = null,
    val error: String? = null
) {
    /** Best available content to feed back to the LLM */
    fun toToolMessage(): String = when {
        !success -> "API call failed: $error"
        extractedData != null -> extractedData
        else -> rawBody
    }
}
