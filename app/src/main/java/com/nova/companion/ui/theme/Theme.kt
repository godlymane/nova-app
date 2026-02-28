package com.nova.companion.ui.theme

import android.app.Activity
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// Legacy colors (kept for backward compat with NovaTestScreen)
val NovaPurple = Color(0xFF6C3AED)
val NovaPurpleLight = Color(0xFF8B5CF6)
val NovaDark = Color(0xFF1A1A2E)
val NovaDarkSurface = Color(0xFF16213E)
val NovaAccent = Color(0xFFA78BFA)
val NovaText = Color(0xFFE2E8F0)

private val NovaDarkColorScheme = darkColorScheme(
    primary = NovaBlue,
    onPrimary = Color.White,
    primaryContainer = NovaBlueDark,
    onPrimaryContainer = Color.White,
    secondary = NovaTextSecondary,
    onSecondary = Color.White,
    background = NovaBlack,
    onBackground = NovaTextPrimary,
    surface = NovaSurface,
    onSurface = NovaTextPrimary,
    surfaceVariant = NovaSurfaceVariant,
    onSurfaceVariant = NovaTextSecondary,
    outline = NovaTextDim,
    error = NovaRed,
    onError = Color.White
)

@Composable
fun NovaTheme(content: @Composable () -> Unit) {
    val view = LocalView.current

    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = NovaBlack.toArgb()
            window.navigationBarColor = NovaBlack.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
            WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = false
        }
    }

    MaterialTheme(
        colorScheme = NovaDarkColorScheme,
        typography = NovaTypography,
        content = content
    )
}
