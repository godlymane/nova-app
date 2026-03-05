package com.nova.companion.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

// -- Core blacks (deep, not pure) --
val NovaBlack = Color(0xFF050508)
val NovaTrueBlack = Color(0xFF000000)
val NovaDarkGray = Color(0xFF1C1C1E)
val NovaBlue = Color(0xFF007AFF)
val NovaBlueDark = Color(0xFF0056B3)

// -- Surface colors (layered depth) --
val NovaSurface = Color(0xFF0A0A0E)
val NovaSurfaceElevated = Color(0xFF121216)
val NovaSurfaceCard = Color(0xFF16161A)
val NovaSurfaceVariant = Color(0xFF2C2C2E)
val NovaInputBackground = Color(0xFF14141A)

// -- Glass-morphism simulation --
val NovaGlassLight = Color(0x12FFFFFF)
val NovaGlassBorder = Color(0x0DFFFFFF)
val NovaGlassHighlight = Color(0x1AFFFFFF)

// -- Text colors --
val NovaTextPrimary = Color(0xFFF0F0F5)
val NovaTextSecondary = Color(0xFF8E8E93)
val NovaTextDim = Color(0xFF636366)
val NovaTextMuted = Color(0xFF48484A)

// -- Status colors --
val NovaGreen = Color(0xFF30D158)
val NovaGreenDim = Color(0xFF1A3D2A)
val NovaRed = Color(0xFFFF453A)
val NovaRedDim = Color(0xFF3D1A1A)
val NovaOrange = Color(0xFFFF9F0A)

// -- Purple palette --
val NovaPurpleCore = Color(0xFF9B59F5)
val NovaPurpleGlow = Color(0xFFB97BFF)
val NovaPurpleDeep = Color(0xFF5B21B6)
val NovaPurpleDark = Color(0xFF3B0F7A)
val NovaPurpleMagenta = Color(0xFFD946EF)
val NovaPurpleAmbient = Color(0xFF7C3AED)
val NovaPurpleElectric = Color(0xFFA855F7)
val NovaPurpleSubtle = Color(0xFF2D1B4E)
val NovaPurpleSurface = Color(0xFF1A0F2E)

// -- Premium accents --
val NovaGold = Color(0xFFFFD700)
val NovaGoldDim = Color(0xFFB8960F)
val NovaCyan = Color(0xFF00E5FF)
val NovaCyanDim = Color(0xFF006B7A)

// -- Gradient presets --
val UserBubbleGradient = Brush.linearGradient(
    colors = listOf(Color(0xFF4A1B8A), Color(0xFF7B3FD4), Color(0xFFA855F7))
)

val NovaBubbleGradient = Brush.verticalGradient(
    colors = listOf(Color(0xFF141418), Color(0xFF111115))
)

val PurpleAccentGradient = Brush.horizontalGradient(
    colors = listOf(NovaPurpleDeep, NovaPurpleCore, NovaPurpleGlow)
)

val GodModeGradient = Brush.horizontalGradient(
    colors = listOf(NovaGold, NovaPurpleCore, NovaCyan)
)

val TopBarFadeGradient = Brush.verticalGradient(
    colors = listOf(Color(0xFF050508), Color(0x00050508))
)

val CardBorderGradient = Brush.linearGradient(
    colors = listOf(Color(0x1AFFFFFF), Color(0x05FFFFFF), Color(0x0DFFFFFF))
)
