package com.nova.companion.tools.tier3

import android.content.Context
import android.util.Log
import com.nova.companion.tools.NovaTool
import com.nova.companion.tools.ToolParam
import com.nova.companion.tools.ToolRegistry
import com.nova.companion.tools.ToolResult
import java.text.SimpleDateFormat
import java.util.*

/**
 * Timezone tool — answers "what time is it in New York / Tokyo / London" etc.
 * Works 100% offline using Java's TimeZone database (no API key needed).
 * Fixes the bug where timezone queries were routing to the weather tool.
 */
object GetTimeZoneToolExecutor {

    private const val TAG = "TimeZoneTool"

    // Map of common city/country names → Java timezone IDs
    private val TIMEZONE_MAP = mapOf(
        // Americas
        "new york" to "America/New_York",
        "nyc" to "America/New_York",
        "los angeles" to "America/Los_Angeles",
        "la" to "America/Los_Angeles",
        "chicago" to "America/Chicago",
        "toronto" to "America/Toronto",
        "vancouver" to "America/Vancouver",
        "mexico city" to "America/Mexico_City",
        "sao paulo" to "America/Sao_Paulo",
        "brazil" to "America/Sao_Paulo",
        "usa" to "America/New_York",
        "america" to "America/New_York",
        "canada" to "America/Toronto",
        "san francisco" to "America/Los_Angeles",
        "seattle" to "America/Los_Angeles",
        "miami" to "America/New_York",
        "boston" to "America/New_York",
        "dallas" to "America/Chicago",
        "denver" to "America/Denver",
        "phoenix" to "America/Phoenix",
        "las vegas" to "America/Los_Angeles",
        // Europe
        "london" to "Europe/London",
        "uk" to "Europe/London",
        "england" to "Europe/London",
        "paris" to "Europe/Paris",
        "france" to "Europe/Paris",
        "berlin" to "Europe/Berlin",
        "germany" to "Europe/Berlin",
        "moscow" to "Europe/Moscow",
        "russia" to "Europe/Moscow",
        "amsterdam" to "Europe/Amsterdam",
        "rome" to "Europe/Rome",
        "italy" to "Europe/Rome",
        "madrid" to "Europe/Madrid",
        "spain" to "Europe/Madrid",
        "zurich" to "Europe/Zurich",
        "switzerland" to "Europe/Zurich",
        "stockholm" to "Europe/Stockholm",
        "sweden" to "Europe/Stockholm",
        "oslo" to "Europe/Oslo",
        "norway" to "Europe/Oslo",
        "istanbul" to "Europe/Istanbul",
        "turkey" to "Europe/Istanbul",
        "athens" to "Europe/Athens",
        "greece" to "Europe/Athens",
        "warsaw" to "Europe/Warsaw",
        "poland" to "Europe/Warsaw",
        "prague" to "Europe/Prague",
        "vienna" to "Europe/Vienna",
        "austria" to "Europe/Vienna",
        "budapest" to "Europe/Budapest",
        // Asia
        "india" to "Asia/Kolkata",
        "bangalore" to "Asia/Kolkata",
        "mumbai" to "Asia/Kolkata",
        "delhi" to "Asia/Kolkata",
        "hyderabad" to "Asia/Kolkata",
        "kolkata" to "Asia/Kolkata",
        "chennai" to "Asia/Kolkata",
        "ist" to "Asia/Kolkata",
        "tokyo" to "Asia/Tokyo",
        "japan" to "Asia/Tokyo",
        "beijing" to "Asia/Shanghai",
        "shanghai" to "Asia/Shanghai",
        "china" to "Asia/Shanghai",
        "hong kong" to "Asia/Hong_Kong",
        "seoul" to "Asia/Seoul",
        "korea" to "Asia/Seoul",
        "singapore" to "Asia/Singapore",
        "dubai" to "Asia/Dubai",
        "uae" to "Asia/Dubai",
        "riyadh" to "Asia/Riyadh",
        "saudi" to "Asia/Riyadh",
        "bangkok" to "Asia/Bangkok",
        "thailand" to "Asia/Bangkok",
        "jakarta" to "Asia/Jakarta",
        "indonesia" to "Asia/Jakarta",
        "karachi" to "Asia/Karachi",
        "pakistan" to "Asia/Karachi",
        "colombo" to "Asia/Colombo",
        "sri lanka" to "Asia/Colombo",
        "dhaka" to "Asia/Dhaka",
        "bangladesh" to "Asia/Dhaka",
        "kathmandu" to "Asia/Kathmandu",
        "nepal" to "Asia/Kathmandu",
        "kabul" to "Asia/Kabul",
        "tehran" to "Asia/Tehran",
        "iran" to "Asia/Tehran",
        "kuala lumpur" to "Asia/Kuala_Lumpur",
        "malaysia" to "Asia/Kuala_Lumpur",
        "manila" to "Asia/Manila",
        "philippines" to "Asia/Manila",
        "taipei" to "Asia/Taipei",
        "taiwan" to "Asia/Taipei",
        // Africa
        "cairo" to "Africa/Cairo",
        "egypt" to "Africa/Cairo",
        "nairobi" to "Africa/Nairobi",
        "kenya" to "Africa/Nairobi",
        "johannesburg" to "Africa/Johannesburg",
        "south africa" to "Africa/Johannesburg",
        "lagos" to "Africa/Lagos",
        "nigeria" to "Africa/Lagos",
        "accra" to "Africa/Accra",
        "ghana" to "Africa/Accra",
        // Pacific / Oceania
        "sydney" to "Australia/Sydney",
        "australia" to "Australia/Sydney",
        "melbourne" to "Australia/Melbourne",
        "brisbane" to "Australia/Brisbane",
        "perth" to "Australia/Perth",
        "auckland" to "Pacific/Auckland",
        "new zealand" to "Pacific/Auckland",
        "honolulu" to "Pacific/Honolulu",
        "hawaii" to "Pacific/Honolulu"
    )

    fun register(registry: ToolRegistry) {
        registry.registerTool(NovaTool(
            name = "getTime",
            description = "Get the current time in any city or country. " +
                    "Use for questions like 'what time is it in New York', " +
                    "'current time in Tokyo', 'what's the time in London right now'. " +
                    "Also shows the time difference from India (IST).",
            parameters = mapOf(
                "location" to ToolParam(
                    type = "string",
                    description = "City or country name (e.g. 'New York', 'Tokyo', 'London')",
                    required = true
                )
            ),
            executor = { ctx, params -> execute(ctx, params) }
        ))
    }

    private fun execute(context: Context, params: Map<String, Any>): ToolResult {
        val location = (params["location"] as? String)?.trim()
            ?: return ToolResult(false, "Location required")

        val key = location.lowercase().trim()
        val tzId = TIMEZONE_MAP[key]
            ?: TIMEZONE_MAP.entries.firstOrNull { key.contains(it.key) || it.key.contains(key) }?.value
            ?: return ToolResult(false, "Don't know the timezone for \"$location\". Try a major city name.")

        return try {
            val tz = TimeZone.getTimeZone(tzId)
            val sdf = SimpleDateFormat("h:mm a, EEE MMM d", Locale.ENGLISH)
            sdf.timeZone = tz

            val now = Date()
            val timeStr = sdf.format(now)

            // Calculate offset vs IST (UTC+5:30)
            val istTz = TimeZone.getTimeZone("Asia/Kolkata")
            val offsetMillis = tz.getOffset(now.time) - istTz.getOffset(now.time)
            val offsetHours = offsetMillis / 3_600_000
            val offsetMins = Math.abs((offsetMillis % 3_600_000) / 60_000)

            val diffStr = when {
                offsetMillis == 0 -> "same as IST"
                offsetMillis > 0 -> "+${offsetHours}h${if (offsetMins > 0) " ${offsetMins}m" else ""} ahead of IST"
                else -> "${offsetHours}h${if (offsetMins > 0) " ${offsetMins}m" else ""} behind IST"
            }

            val displayName = location.replaceFirstChar { it.uppercase() }
            Log.i(TAG, "Time in $displayName ($tzId): $timeStr ($diffStr)")
            ToolResult(true, "It's $timeStr in $displayName — $diffStr")
        } catch (e: Exception) {
            Log.e(TAG, "Timezone lookup failed for $location", e)
            ToolResult(false, "Couldn't get time for $location: ${e.message}")
        }
    }
}
