package com.pox.colab.theme

import androidx.compose.ui.graphics.Color

// Official Google Colab Brand Colors
val ColabOrange = Color(0xFFF9AB00)
val ColabAmber = Color(0xFFE8710A)
val ColabYellow = Color(0xFFFDD663)

// Pure AMOLED Black Palette
val AmoledBlack = Color(0xFF000000)
val DarkSurfaceLowest = Color(0xFF000000)
val DarkSurfaceLow = Color(0xFF0C0C0C)
val DarkSurface = Color(0xFF121212)
val DarkSurfaceHigh = Color(0xFF1A1A1A)
val DarkSurfaceHighest = Color(0xFF242424)

// Material 3 Dark Semantic Colors
val PrimaryDark = Color(0xFFFFB77C)
val OnPrimaryDark = Color(0xFF4D2700)
val PrimaryContainerDark = Color(0xFF6E3900)
val OnPrimaryContainerDark = Color(0xFFFFDCC1)

val SecondaryDark = Color(0xFFE5BFA8)
val OnSecondaryDark = Color(0xFF432B1C)
val SecondaryContainerDark = Color(0xFF5B4130)
val OnSecondaryContainerDark = Color(0xFFFFDCC7)

val TertiaryDark = Color(0xFFC7CD9E)
val OnTertiaryDark = Color(0xFF303513)
val TertiaryContainerDark = Color(0xFF464C27)
val OnTertiaryContainerDark = Color(0xFFE4E9B8)

val ErrorDark = Color(0xFFFFB4AB)
val OnErrorDark = Color(0xFF690005)
val ErrorContainerDark = Color(0xFF93000A)
val OnErrorContainerDark = Color(0xFFFFDAD6)

val BackgroundDark = Color(0xFF000000)
val OnBackgroundDark = Color(0xFFEDE0D9)
val SurfaceDark = Color(0xFF000000)
val OnSurfaceDark = Color(0xFFEDE0D9)
val SurfaceVariantDark = Color(0xFF1E1E1E)
val OnSurfaceVariantDark = Color(0xFFD7C2B4)
val OutlineDark = Color(0xFF3D3D3D)
val OutlineVariantDark = Color(0xFF262626)

// Terminal ANSI standard colors
val TerminalGreen = Color(0xFF3FB950)
val TerminalRed = Color(0xFFF85149)
val TerminalYellow = Color(0xFFE3B341)
val TerminalCyan = Color(0xFF56D4DD)
val TerminalForeground = Color(0xFFC9D1D9)

fun resolveConsoleBackground(bgKey: String): Color {
    return when (bgKey) {
        "amoled_black" -> Color(0xFF000000)
        "deep_charcoal" -> Color(0xFF0F1117)
        "navy_terminal" -> Color(0xFF0A0F1D)
        "matrix_dark" -> Color(0xFF021206)
        "monokai_dark" -> Color(0xFF1E1F1C)
        else -> Color(0xFF000000)
    }
}
