package com.pox.colab.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
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
    background = AmoledBlack,
    onBackground = OnBackgroundDark,
    surface = AmoledBlack,
    onSurface = OnSurfaceDark,
    surfaceVariant = SurfaceVariantDark,
    onSurfaceVariant = OnSurfaceVariantDark,
    surfaceDim = DarkSurfaceLow,
    surfaceBright = DarkSurfaceHigh,
    surfaceContainerLowest = DarkSurfaceLowest,
    surfaceContainerLow = DarkSurfaceLow,
    surfaceContainer = DarkSurface,
    surfaceContainerHigh = DarkSurfaceHigh,
    surfaceContainerHighest = DarkSurfaceHighest,
    outline = OutlineDark,
    outlineVariant = OutlineVariantDark
)

fun createPaletteColorScheme(
    palette: String,
    darkTheme: Boolean,
    pureBlack: Boolean
): ColorScheme {
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

    if (!darkTheme) {
        val (lightPrimary, lightOnPrimary, lightPrimaryContainer, lightOnPrimaryContainer) = when (palette) {
            "matrix_green" -> listOf(Color(0xFF166534), Color(0xFFFFFFFF), Color(0xFFBBF7D0), Color(0xFF052E16))
            "cyber_cyan" -> listOf(Color(0xFF0369A1), Color(0xFFFFFFFF), Color(0xFFBAE6FD), Color(0xFF082F49))
            "neon_violet" -> listOf(Color(0xFF7E22CE), Color(0xFFFFFFFF), Color(0xFFE9D5FF), Color(0xFF3B0764))
            "monochrome" -> listOf(Color(0xFF262626), Color(0xFFFFFFFF), Color(0xFFE5E5E5), Color(0xFF000000))
            else -> listOf(Color(0xFFB35C00), Color(0xFFFFFFFF), Color(0xFFFFDCC1), Color(0xFF2E1500))
        }

        return lightColorScheme(
            primary = lightPrimary,
            onPrimary = lightOnPrimary,
            primaryContainer = lightPrimaryContainer,
            onPrimaryContainer = lightOnPrimaryContainer,
            secondary = Color(0xFF715B2E),
            onSecondary = Color(0xFFFFFFFF),
            secondaryContainer = Color(0xFFFDE0A4),
            onSecondaryContainer = Color(0xFF261900),
            tertiary = Color(0xFF4C662B),
            onTertiary = Color(0xFFFFFFFF),
            tertiaryContainer = Color(0xFFCDEDA3),
            onTertiaryContainer = Color(0xFF102000),
            error = Color(0xFFBA1A1A),
            onError = Color(0xFFFFFFFF),
            errorContainer = Color(0xFFFFDAD6),
            onErrorContainer = Color(0xFF410002),
            background = Color(0xFFFBFDF8),
            onBackground = Color(0xFF191C19),
            surface = Color(0xFFFBFDF8),
            onSurface = Color(0xFF191C19),
            surfaceVariant = Color(0xFFE1E4DA),
            onSurfaceVariant = Color(0xFF44483F),
            surfaceContainerLowest = Color(0xFFFFFFFF),
            surfaceContainerLow = Color(0xFFF5F7F2),
            surfaceContainer = Color(0xFFEFF1EC),
            surfaceContainerHigh = Color(0xFFE9EBE6),
            surfaceContainerHighest = Color(0xFFE3E5E0),
            outline = Color(0xFF72796F),
            outlineVariant = Color(0xFFC2C9BD)
        )
    }

    val bg = if (pureBlack) Color(0xFF000000) else Color(0xFF0F0F0F)
    return DarkColorScheme.copy(
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
}

@Composable
fun ColabTheme(
    darkTheme: Boolean = true,
    pureBlack: Boolean = true,
    dynamicColor: Boolean = false,
    selectedPalette: String = "colab_amber",
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val dyn = if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            if (darkTheme && pureBlack) {
                dyn.copy(
                    background = Color(0xFF000000),
                    surface = Color(0xFF000000),
                    surfaceContainerLowest = Color(0xFF000000),
                    surfaceContainerLow = Color(0xFF0C0C0C),
                    surfaceContainer = Color(0xFF121212),
                    surfaceContainerHigh = Color(0xFF1A1A1A),
                    surfaceContainerHighest = Color(0xFF242424)
                )
            } else dyn
        }
        else -> createPaletteColorScheme(selectedPalette, darkTheme, pureBlack)
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        shapes = Shapes,
        content = content
    )
}
