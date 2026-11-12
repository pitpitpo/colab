package com.pox.colab.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = PrimaryDark,
    onPrimary = OnPrimaryDark,
    primaryContainer = PrimaryContainerDark,
    onPrimaryContainer = OnPrimaryContainerDark,
    secondary = SecondaryDark,
    onSecondary = OnSecondaryDark,
    secondaryContainer = SecondaryContainerDark,
    onSecondaryContainer = OnSecondaryContainerDark,
    tertiary = TertiaryDark,
    onTertiary = OnTertiaryDark,
    tertiaryContainer = TertiaryContainerDark,
    onTertiaryContainer = OnTertiaryContainerDark,
    error = ErrorDark,
    onError = OnErrorDark,
    errorContainer = ErrorContainerDark,
    onErrorContainer = OnErrorContainerDark,
    background = BackgroundDark,
    onBackground = OnBackgroundDark,
    surface = SurfaceDark,
    onSurface = OnSurfaceDark,
    surfaceVariant = SurfaceVariantDark,
    onSurfaceVariant = OnSurfaceVariantDark,
    surfaceDim = SurfaceDimDark,
    surfaceBright = SurfaceBrightDark,
    surfaceContainerLowest = SurfaceContainerLowestDark,
    surfaceContainerLow = SurfaceContainerLowDark,
    surfaceContainer = SurfaceContainerDark,
    surfaceContainerHigh = SurfaceContainerHighDark,
    surfaceContainerHighest = SurfaceContainerHighestDark,
    outline = OutlineDark,
    outlineVariant = OutlineVariantDark
)

private val LightColorScheme = lightColorScheme(
    primary = PrimaryLight,
    onPrimary = OnPrimaryLight,
    primaryContainer = PrimaryContainerLight,
    onPrimaryContainer = OnPrimaryContainerLight,
    secondary = SecondaryLight,
    onSecondary = OnSecondaryLight,
    secondaryContainer = SecondaryContainerLight,
    onSecondaryContainer = OnSecondaryContainerLight,
    tertiary = TertiaryLight,
    onTertiary = OnTertiaryLight,
    tertiaryContainer = TertiaryContainerLight,
    onTertiaryContainer = OnTertiaryContainerLight,
    error = ErrorLight,
    onError = OnErrorLight,
    errorContainer = ErrorContainerLight,
    onErrorContainer = OnErrorContainerLight,
    background = BackgroundLight,
    onBackground = OnBackgroundLight,
    surface = SurfaceLight,
    onSurface = OnSurfaceLight,
    surfaceVariant = SurfaceVariantLight,
    onSurfaceVariant = OnSurfaceVariantLight,
    surfaceDim = SurfaceDimLight,
    surfaceBright = SurfaceBrightLight,
    surfaceContainerLowest = SurfaceContainerLowestLight,
    surfaceContainerLow = SurfaceContainerLowLight,
    surfaceContainer = SurfaceContainerLight,
    surfaceContainerHigh = SurfaceContainerHighLight,
    surfaceContainerHighest = SurfaceContainerHighestLight,
    outline = OutlineLight,
    outlineVariant = OutlineVariantLight
)

fun createPaletteColorScheme(
    palette: String,
    darkTheme: Boolean,
    pureBlack: Boolean
): androidx.compose.material3.ColorScheme {
    val (primary, onPrimary, primaryContainer, onPrimaryContainer) = when (palette) {
        "matrix_green" -> listOf(
            Color(0xFF4ADE80), Color(0xFF052E16), Color(0xFF14532D), Color(0xFFBBF7D0)
        )
        "cyber_cyan" -> listOf(
            Color(0xFF38BDF8), Color(0xFF082F49), Color(0xFF0369A1), Color(0xFFBAE6FD)
        )
        "neon_violet" -> listOf(
            Color(0xFFC084FC), Color(0xFF3B0764), Color(0xFF6B21A8), Color(0xFFE9D5FF)
        )
        "monochrome" -> listOf(
            Color(0xFFFFFFFF), Color(0xFF000000), Color(0xFF262626), Color(0xFFE5E5E5)
        )
        else -> listOf(
            PrimaryDark, OnPrimaryDark, PrimaryContainerDark, OnPrimaryContainerDark
        )
    }

    return if (darkTheme) {
        val bg = if (pureBlack) Color(0xFF000000) else BackgroundDark
        DarkColorScheme.copy(
            primary = primary,
            onPrimary = onPrimary,
            primaryContainer = primaryContainer,
            onPrimaryContainer = onPrimaryContainer,
            background = bg,
            surface = bg,
            surfaceContainerLowest = if (pureBlack) Color(0xFF000000) else Color(0xFF0A0A0A),
            surfaceContainerLow = if (pureBlack) Color(0xFF0C0C0C) else Color(0xFF141414),
            surfaceContainer = if (pureBlack) Color(0xFF121212) else Color(0xFF1A1A1A),
            surfaceContainerHigh = if (pureBlack) Color(0xFF1A1A1A) else Color(0xFF222222),
            surfaceContainerHighest = if (pureBlack) Color(0xFF242424) else Color(0xFF2C2C2C),
            outlineVariant = Color(0xFF262626)
        )
    } else {
        LightColorScheme.copy(
            primary = when (palette) {
                "matrix_green" -> Color(0xFF15803D)
                "cyber_cyan" -> Color(0xFF0284C7)
                "neon_violet" -> Color(0xFF7E22CE)
                "monochrome" -> Color(0xFF171717)
                else -> PrimaryLight
            }
        )
    }
}

@Composable
fun ColabTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    palette: String = "colab_amber",
    pureBlack: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            val dynamic = if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            if (darkTheme && pureBlack) {
                dynamic.copy(
                    background = Color(0xFF000000),
                    surface = Color(0xFF000000),
                    surfaceContainerLowest = Color(0xFF000000)
                )
            } else {
                dynamic
            }
        }
        else -> createPaletteColorScheme(palette, darkTheme, pureBlack)
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        shapes = Shapes,
        content = content
    )
}
