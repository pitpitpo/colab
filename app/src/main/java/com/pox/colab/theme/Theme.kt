package com.pox.colab.theme

import android.os.Build
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private data class PaletteTokens(
    val primary: Color,
    val onPrimary: Color,
    val primaryContainer: Color,
    val onPrimaryContainer: Color,
    val secondary: Color,
    val onSecondary: Color,
    val secondaryContainer: Color,
    val onSecondaryContainer: Color,
    val tertiary: Color,
    val onTertiary: Color,
    val tertiaryContainer: Color,
    val onTertiaryContainer: Color,
    val onSurface: Color,
    val onSurfaceVariant: Color,
    val outline: Color,
    val outlineVariant: Color,
    val inversePrimary: Color
)

private fun darkPaletteTokens(palette: String): PaletteTokens = when (palette) {
    "matrix_green" -> PaletteTokens(
        primary = Color(0xFF4CF089),
        onPrimary = Color(0xFF003917),
        primaryContainer = Color(0xFF005B29),
        onPrimaryContainer = Color(0xFFB5FFCA),
        secondary = Color(0xFF9EE0B6),
        onSecondary = Color(0xFF0E3820),
        secondaryContainer = Color(0xFF214E33),
        onSecondaryContainer = Color(0xFFC5F7D7),
        tertiary = Color(0xFF7AD7F0),
        onTertiary = Color(0xFF003642),
        tertiaryContainer = Color(0xFF004F60),
        onTertiaryContainer = Color(0xFFB8EEFF),
        onSurface = Color(0xFFE3ECE5),
        onSurfaceVariant = Color(0xFFB8C9BC),
        outline = Color(0xFF4B5C50),
        outlineVariant = Color(0xFF253029),
        inversePrimary = Color(0xFF0A6E34)
    )
    "cyber_cyan" -> PaletteTokens(
        primary = Color(0xFF56D4FF),
        onPrimary = Color(0xFF003548),
        primaryContainer = Color(0xFF004F6C),
        onPrimaryContainer = Color(0xFFC0ECFF),
        secondary = Color(0xFFA0CEE8),
        onSecondary = Color(0xFF0F3346),
        secondaryContainer = Color(0xFF244A5F),
        onSecondaryContainer = Color(0xFFCBE9FF),
        tertiary = Color(0xFFD0B8FF),
        onTertiary = Color(0xFF371C6B),
        tertiaryContainer = Color(0xFF4E3484),
        onTertiaryContainer = Color(0xFFECDCFF),
        onSurface = Color(0xFFE2E9EF),
        onSurfaceVariant = Color(0xFFB8C7D2),
        outline = Color(0xFF4A5863),
        outlineVariant = Color(0xFF252E36),
        inversePrimary = Color(0xFF00668A)
    )
    "neon_violet" -> PaletteTokens(
        primary = Color(0xFFD8B4FF),
        onPrimary = Color(0xFF3E1075),
        primaryContainer = Color(0xFF582A94),
        onPrimaryContainer = Color(0xFFF0DBFF),
        secondary = Color(0xFFCBBCE6),
        onSecondary = Color(0xFF322749),
        secondaryContainer = Color(0xFF493D63),
        onSecondaryContainer = Color(0xFFEADDFF),
        tertiary = Color(0xFFFFB0CE),
        onTertiary = Color(0xFF531733),
        tertiaryContainer = Color(0xFF702E4A),
        onTertiaryContainer = Color(0xFFFFD8E6),
        onSurface = Color(0xFFEAE2F0),
        onSurfaceVariant = Color(0xFFC8BFD2),
        outline = Color(0xFF544B60),
        outlineVariant = Color(0xFF2C2634),
        inversePrimary = Color(0xFF7244B2)
    )
    "monochrome" -> PaletteTokens(
        primary = Color(0xFFF5F5F5),
        onPrimary = Color(0xFF141414),
        primaryContainer = Color(0xFF333333),
        onPrimaryContainer = Color(0xFFF0F0F0),
        secondary = Color(0xFFC9C9C9),
        onSecondary = Color(0xFF242424),
        secondaryContainer = Color(0xFF3E3E3E),
        onSecondaryContainer = Color(0xFFEAEAEA),
        tertiary = Color(0xFFB0B0B0),
        onTertiary = Color(0xFF202020),
        tertiaryContainer = Color(0xFF363636),
        onTertiaryContainer = Color(0xFFE0E0E0),
        onSurface = Color(0xFFEDEDED),
        onSurfaceVariant = Color(0xFFBEBEBE),
        outline = Color(0xFF545454),
        outlineVariant = Color(0xFF2B2B2B),
        inversePrimary = Color(0xFF2A2A2A)
    )
    else -> PaletteTokens(
        primary = Color(0xFFFFB35C),
        onPrimary = Color(0xFF4A2500),
        primaryContainer = Color(0xFF703B00),
        onPrimaryContainer = Color(0xFFFFDFC0),
        secondary = Color(0xFFE8C09E),
        onSecondary = Color(0xFF452A11),
        secondaryContainer = Color(0xFF5E3F24),
        onSecondaryContainer = Color(0xFFFFDFC4),
        tertiary = Color(0xFFC9D48C),
        onTertiary = Color(0xFF313808),
        tertiaryContainer = Color(0xFF474F1E),
        onTertiaryContainer = Color(0xFFE6F0A6),
        onSurface = Color(0xFFF2E5DC),
        onSurfaceVariant = Color(0xFFD8C3B4),
        outline = Color(0xFF594C43),
        outlineVariant = Color(0xFF2E2620),
        inversePrimary = Color(0xFF9A4F00)
    )
}

private fun lightPaletteTokens(palette: String): PaletteTokens = when (palette) {
    "matrix_green" -> PaletteTokens(
        primary = Color(0xFF0A6E34),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFB5FFCA),
        onPrimaryContainer = Color(0xFF00210C),
        secondary = Color(0xFF3B664B),
        onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFFC5F7D7),
        onSecondaryContainer = Color(0xFF072112),
        tertiary = Color(0xFF00677D),
        onTertiary = Color(0xFFFFFFFF),
        tertiaryContainer = Color(0xFFB8EEFF),
        onTertiaryContainer = Color(0xFF001F27),
        onSurface = Color(0xFF151D17),
        onSurfaceVariant = Color(0xFF3E4A41),
        outline = Color(0xFF6E7B71),
        outlineVariant = Color(0xFFBFCCB9),
        inversePrimary = Color(0xFF4CF089)
    )
    "cyber_cyan" -> PaletteTokens(
        primary = Color(0xFF00668A),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFC0ECFF),
        onPrimaryContainer = Color(0xFF001E2C),
        secondary = Color(0xFF3A6277),
        onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFFCBE9FF),
        onSecondaryContainer = Color(0xFF071E2B),
        tertiary = Color(0xFF644A9B),
        onTertiary = Color(0xFFFFFFFF),
        tertiaryContainer = Color(0xFFECDCFF),
        onTertiaryContainer = Color(0xFF200554),
        onSurface = Color(0xFF151C21),
        onSurfaceVariant = Color(0xFF3F4950),
        outline = Color(0xFF6F7A82),
        outlineVariant = Color(0xFFBFC8D0),
        inversePrimary = Color(0xFF56D4FF)
    )
    "neon_violet" -> PaletteTokens(
        primary = Color(0xFF7244B2),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFF0DBFF),
        onPrimaryContainer = Color(0xFF28005C),
        secondary = Color(0xFF60537A),
        onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFFEADDFF),
        onSecondaryContainer = Color(0xFF1E1336),
        tertiary = Color(0xFF8C4562),
        onTertiary = Color(0xFFFFFFFF),
        tertiaryContainer = Color(0xFFFFD8E6),
        onTertiaryContainer = Color(0xFF380920),
        onSurface = Color(0xFF1D1A22),
        onSurfaceVariant = Color(0xFF494352),
        outline = Color(0xFF7A7384),
        outlineVariant = Color(0xFFCAC2D4),
        inversePrimary = Color(0xFFD8B4FF)
    )
    "monochrome" -> PaletteTokens(
        primary = Color(0xFF222222),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFE2E2E2),
        onPrimaryContainer = Color(0xFF121212),
        secondary = Color(0xFF555555),
        onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFFE6E6E6),
        onSecondaryContainer = Color(0xFF1A1A1A),
        tertiary = Color(0xFF454545),
        onTertiary = Color(0xFFFFFFFF),
        tertiaryContainer = Color(0xFFDCDCDC),
        onTertiaryContainer = Color(0xFF161616),
        onSurface = Color(0xFF181818),
        onSurfaceVariant = Color(0xFF464646),
        outline = Color(0xFF747474),
        outlineVariant = Color(0xFFC8C8C8),
        inversePrimary = Color(0xFFF5F5F5)
    )
    else -> PaletteTokens(
        primary = Color(0xFF9A4F00),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFFFDFC0),
        onPrimaryContainer = Color(0xFF311500),
        secondary = Color(0xFF76563C),
        onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFFFFDFC4),
        onSecondaryContainer = Color(0xFF2B1605),
        tertiary = Color(0xFF5E662C),
        onTertiary = Color(0xFFFFFFFF),
        tertiaryContainer = Color(0xFFE6F0A6),
        onTertiaryContainer = Color(0xFF1B2000),
        onSurface = Color(0xFF211A15),
        onSurfaceVariant = Color(0xFF534338),
        outline = Color(0xFF857367),
        outlineVariant = Color(0xFFD9C3B4),
        inversePrimary = Color(0xFFFFB35C)
    )
}

fun createPaletteColorScheme(
    palette: String,
    darkTheme: Boolean,
    pureBlack: Boolean
): ColorScheme {
    if (!darkTheme) {
        val t = lightPaletteTokens(palette)
        val surfaceBase = when (palette) {
            "matrix_green" -> Color(0xFFF3FBF4)
            "cyber_cyan" -> Color(0xFFF2F9FD)
            "neon_violet" -> Color(0xFFFAF5FF)
            "monochrome" -> Color(0xFFF7F7F7)
            else -> Color(0xFFFFF7F1)
        }
        val containerLow = when (palette) {
            "matrix_green" -> Color(0xFFEAF5EC)
            "cyber_cyan" -> Color(0xFFE9F2F8)
            "neon_violet" -> Color(0xFFF3ECFA)
            "monochrome" -> Color(0xFFEFEFEF)
            else -> Color(0xFFFAEEE4)
        }
        val container = when (palette) {
            "matrix_green" -> Color(0xFFE2EFE5)
            "cyber_cyan" -> Color(0xFFE1ECF4)
            "neon_violet" -> Color(0xFFECE3F6)
            "monochrome" -> Color(0xFFE8E8E8)
            else -> Color(0xFFF4E6DA)
        }
        val containerHigh = when (palette) {
            "matrix_green" -> Color(0xFFDAE9DD)
            "cyber_cyan" -> Color(0xFFD8E6F0)
            "neon_violet" -> Color(0xFFE4D9F1)
            "monochrome" -> Color(0xFFE0E0E0)
            else -> Color(0xFFEDDDD0)
        }
        val containerHighest = when (palette) {
            "matrix_green" -> Color(0xFFD1E2D5)
            "cyber_cyan" -> Color(0xFFCFE0EC)
            "neon_violet" -> Color(0xFFDDD0EC)
            "monochrome" -> Color(0xFFD8D8D8)
            else -> Color(0xFFE6D4C6)
        }

        return lightColorScheme(
            primary = t.primary,
            onPrimary = t.onPrimary,
            primaryContainer = t.primaryContainer,
            onPrimaryContainer = t.onPrimaryContainer,
            secondary = t.secondary,
            onSecondary = t.onSecondary,
            secondaryContainer = t.secondaryContainer,
            onSecondaryContainer = t.onSecondaryContainer,
            tertiary = t.tertiary,
            onTertiary = t.onTertiary,
            tertiaryContainer = t.tertiaryContainer,
            onTertiaryContainer = t.onTertiaryContainer,
            error = Color(0xFFBA1A1A),
            onError = Color(0xFFFFFFFF),
            errorContainer = Color(0xFFFFDAD6),
            onErrorContainer = Color(0xFF410002),
            background = surfaceBase,
            onBackground = t.onSurface,
            surface = surfaceBase,
            onSurface = t.onSurface,
            surfaceVariant = containerHighest,
            onSurfaceVariant = t.onSurfaceVariant,
            surfaceTint = t.primary,
            inverseSurface = Color(0xFF313033),
            inverseOnSurface = Color(0xFFF4EFF4),
            inversePrimary = t.inversePrimary,
            surfaceDim = containerHigh,
            surfaceBright = surfaceBase,
            surfaceContainerLowest = Color(0xFFFFFFFF),
            surfaceContainerLow = containerLow,
            surfaceContainer = container,
            surfaceContainerHigh = containerHigh,
            surfaceContainerHighest = containerHighest,
            outline = t.outline,
            outlineVariant = t.outlineVariant,
            scrim = Color(0xFF000000)
        )
    }

    val t = darkPaletteTokens(palette)
    val bg = if (pureBlack) AmoledBlack else Color(0xFF101116)
    return darkColorScheme(
        primary = t.primary,
        onPrimary = t.onPrimary,
        primaryContainer = t.primaryContainer,
        onPrimaryContainer = t.onPrimaryContainer,
        secondary = t.secondary,
        onSecondary = t.onSecondary,
        secondaryContainer = t.secondaryContainer,
        onSecondaryContainer = t.onSecondaryContainer,
        tertiary = t.tertiary,
        onTertiary = t.onTertiary,
        tertiaryContainer = t.tertiaryContainer,
        onTertiaryContainer = t.onTertiaryContainer,
        error = ErrorDark,
        onError = OnErrorDark,
        errorContainer = ErrorContainerDark,
        onErrorContainer = OnErrorContainerDark,
        background = bg,
        onBackground = t.onSurface,
        surface = bg,
        onSurface = t.onSurface,
        surfaceVariant = if (pureBlack) DarkSurfaceHigh else Color(0xFF23252E),
        onSurfaceVariant = t.onSurfaceVariant,
        surfaceTint = t.primary,
        inverseSurface = Color(0xFFEBE6E3),
        inverseOnSurface = Color(0xFF1D1B1A),
        inversePrimary = t.inversePrimary,
        surfaceDim = if (pureBlack) AmoledBlack else Color(0xFF0C0D12),
        surfaceBright = if (pureBlack) Color(0xFF2C2C2C) else Color(0xFF353844),
        surfaceContainerLowest = if (pureBlack) DarkSurfaceLowest else Color(0xFF0A0B0F),
        surfaceContainerLow = if (pureBlack) Color(0xFF101012) else Color(0xFF161820),
        surfaceContainer = if (pureBlack) Color(0xFF161619) else Color(0xFF1D202A),
        surfaceContainerHigh = if (pureBlack) Color(0xFF202025) else Color(0xFF262A36),
        surfaceContainerHighest = if (pureBlack) Color(0xFF2B2B31) else Color(0xFF303544),
        outline = t.outline,
        outlineVariant = t.outlineVariant,
        scrim = Color(0xFF000000)
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
                    background = AmoledBlack,
                    surface = AmoledBlack,
                    surfaceDim = AmoledBlack,
                    surfaceContainerLowest = DarkSurfaceLowest,
                    surfaceContainerLow = Color(0xFF101012),
                    surfaceContainer = Color(0xFF161619),
                    surfaceContainerHigh = Color(0xFF202025),
                    surfaceContainerHighest = Color(0xFF2B2B31)
                )
            } else {
                dyn
            }
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
