package com.pox.colab.theme

import androidx.compose.ui.graphics.Color

// Official Google Colab Brand Accents
val ColabOrange = Color(0xFFE8710A)
val ColabYellow = Color(0xFFF9AB00)
val ColabAmber = Color(0xFFFFB74D)

// ==============================================================================
// Material 3 Semantic Color Tokens
// ==============================================================================

// Dark Theme Palette
val PrimaryDark = Color(0xFFFFB77C)
val OnPrimaryDark = Color(0xFF4D2700)
val PrimaryContainerDark = Color(0xFF6D3900)
val OnPrimaryContainerDark = Color(0xFFFFDCC2)

val SecondaryDark = Color(0xFFE5C18D)
val OnSecondaryDark = Color(0xFF422C05)
val SecondaryContainerDark = Color(0xFF5B4219)
val OnSecondaryContainerDark = Color(0xFFFFDDB3)

val TertiaryDark = Color(0xFFB8CEA2)
val OnTertiaryDark = Color(0xFF243516)
val TertiaryContainerDark = Color(0xFF394C2B)
val OnTertiaryContainerDark = Color(0xFFD4EABB)

val ErrorDark = Color(0xFFFFB4AB)
val OnErrorDark = Color(0xFF690005)
val ErrorContainerDark = Color(0xFF93000A)
val OnErrorContainerDark = Color(0xFFFFDAD6)

val BackgroundDark = Color(0xFF000000)
val OnBackgroundDark = Color(0xFFEDEDED)

val SurfaceDark = Color(0xFF000000)
val OnSurfaceDark = Color(0xFFEDEDED)
val SurfaceVariantDark = Color(0xFF1E1E1E)
val OnSurfaceVariantDark = Color(0xFFA0A0A0)

val SurfaceDimDark = Color(0xFF000000)
val SurfaceBrightDark = Color(0xFF202020)
val SurfaceContainerLowestDark = Color(0xFF000000)
val SurfaceContainerLowDark = Color(0xFF0D0D0D)
val SurfaceContainerDark = Color(0xFF141414)
val SurfaceContainerHighDark = Color(0xFF1C1C1C)
val SurfaceContainerHighestDark = Color(0xFF262626)

val OutlineDark = Color(0xFF555555)
val OutlineVariantDark = Color(0xFF262626)

// Light Theme Palette
val PrimaryLight = Color(0xFF8F4C00)
val OnPrimaryLight = Color(0xFFFFFFFF)
val PrimaryContainerLight = Color(0xFFFFDCC2)
val OnPrimaryContainerLight = Color(0xFF2E1500)

val SecondaryLight = Color(0xFF755B30)
val OnSecondaryLight = Color(0xFFFFFFFF)
val SecondaryContainerLight = Color(0xFFFFDDB3)
val OnSecondaryContainerLight = Color(0xFF291800)

val TertiaryLight = Color(0xFF516441)
val OnTertiaryLight = Color(0xFFFFFFFF)
val TertiaryContainerLight = Color(0xFFD4EABB)
val OnTertiaryContainerLight = Color(0xFF0F1F05)

val ErrorLight = Color(0xFFBA1A1A)
val OnErrorLight = Color(0xFFFFFFFF)
val ErrorContainerLight = Color(0xFFFFDAD6)
val OnErrorContainerLight = Color(0xFF410002)

val BackgroundLight = Color(0xFFFFF8F4)
val OnBackgroundLight = Color(0xFF211A15)

val SurfaceLight = Color(0xFFFFF8F4)
val OnSurfaceLight = Color(0xFF211A15)
val SurfaceVariantLight = Color(0xFFEBE0D8)
val OnSurfaceVariantLight = Color(0xFF4C453F)

val SurfaceDimLight = Color(0xFFE4DAD2)
val SurfaceBrightLight = Color(0xFFFFF8F4)
val SurfaceContainerLowestLight = Color(0xFFFFFFFF)
val SurfaceContainerLowLight = Color(0xFFFEF2E9)
val SurfaceContainerLight = Color(0xFFF8ECE3)
val SurfaceContainerHighLight = Color(0xFFF2E6DD)
val SurfaceContainerHighestLight = Color(0xFFECE0D7)

val OutlineLight = Color(0xFF7E756E)
val OutlineVariantLight = Color(0xFFCEC5BD)

// Terminal Functional Colors (for ANSI/Console outputs)
val TerminalBackground = Color(0xFF000000)
val TerminalForeground = Color(0xFFE6EDF3)
val TerminalGreen = Color(0xFF3FB950)
val TerminalRed = Color(0xFFF85149)
val TerminalCyan = Color(0xFF56D4DD)
val TerminalYellow = Color(0xFFE3B341)

// Console Background Palette Options
fun resolveConsoleBackground(bgName: String): Color {
    return when (bgName) {
        "pitch_black" -> Color(0xFF000000)
        "slate" -> Color(0xFF0D1117)
        "matrix" -> Color(0xFF051508)
        "amber" -> Color(0xFF150D05)
        else -> Color(0xFF000000)
    }
}
