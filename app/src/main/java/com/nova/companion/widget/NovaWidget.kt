package com.nova.companion.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.*
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.nova.companion.MainActivity

class NovaWidget : GlanceAppWidget() {

    companion object {
        const val PREFS_NAME = "nova_widget_prefs"
        const val KEY_LAST_RESPONSE = "last_response"
        const val KEY_AURA_STATE = "aura_state"

        suspend fun updateWidget(context: Context) {
            NovaWidget().updateAll(context)
        }

        fun saveState(context: Context, response: String? = null, auraState: String? = null) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().apply {
                response?.let { putString(KEY_LAST_RESPONSE, it) }
                auraState?.let { putString(KEY_AURA_STATE, it) }
                apply()
            }
        }
    }

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastResponse = prefs.getString(KEY_LAST_RESPONSE, "Say \u201cHey Nova\u201d to start\u2026") ?: ""
        val auraState = prefs.getString(KEY_AURA_STATE, "DORMANT") ?: "DORMANT"

        provideContent {
            WidgetContent(context, lastResponse, auraState)
        }
    }
}

// ── Cosmic Purple Palette ──────────────────────────────────────
private val WidgetBg      = Color(0xFF1A0A2E)
private val DotDormant    = Color(0xFF4C1D95)
private val DotActive     = Color(0xFF7C3AED)
private val DotSurge      = Color(0xFFD946EF)
private val TextPrimary   = Color(0xFFE9D5FF)
private val TextSecondary = Color(0xFFA78BCC)
private val MicBg         = Color(0xFF2D1B4E)

@Composable
private fun WidgetContent(context: Context, lastResponse: String, auraState: String) {
    val dotColor = when (auraState) {
        "LISTENING", "SPEAKING" -> DotSurge
        "THINKING" -> DotActive
        // Legacy compat
        "SURGE" -> DotSurge
        "ACTIVE" -> DotActive
        else -> DotDormant
    }

    val openIntent = Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
    }
    val micIntent = Intent(context, MainActivity::class.java).apply {
        putExtra("start_voice", true)
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
    }

    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(WidgetBg)
            .cornerRadius(16.dp)
            .padding(12.dp)
            .clickable(actionStartActivity(openIntent))
    ) {
        // ── Top row: status dot + "Nova" + mic button ──────────
        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Aura state dot
            Box(
                modifier = GlanceModifier
                    .size(10.dp)
                    .background(dotColor)
                    .cornerRadius(5.dp)
            ) {}

            Spacer(modifier = GlanceModifier.width(8.dp))

            Text(
                text = "Nova",
                style = TextStyle(
                    color = ColorProvider(TextPrimary),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
            )

            Spacer(modifier = GlanceModifier.defaultWeight())

            // Mic / talk button
            Box(
                modifier = GlanceModifier
                    .background(MicBg)
                    .cornerRadius(12.dp)
                    .padding(horizontal = 10.dp, vertical = 4.dp)
                    .clickable(actionStartActivity(micIntent))
            ) {
                Text(
                    text = "\u25CF Talk",
                    style = TextStyle(
                        color = ColorProvider(DotActive),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                )
            }
        }

        Spacer(modifier = GlanceModifier.height(8.dp))

        // ── Last response preview ──────────────────────────────
        Text(
            text = lastResponse,
            style = TextStyle(
                color = ColorProvider(TextSecondary),
                fontSize = 13.sp
            ),
            maxLines = 3
        )
    }
}
