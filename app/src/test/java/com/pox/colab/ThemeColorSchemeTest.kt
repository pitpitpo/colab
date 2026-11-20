package com.pox.colab

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import com.pox.colab.theme.AmoledBlack
import com.pox.colab.theme.createPaletteColorScheme
import com.pox.colab.ui.component.expressive.ExpressiveShapes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min

class ThemeColorSchemeTest {

    private val palettes = listOf(
        "colab_amber",
        "matrix_green",
        "cyber_cyan",
        "neon_violet",
        "monochrome"
    )

    private fun contrastRatio(foreground: Color, background: Color): Double {
        val l1 = foreground.luminance().toDouble()
        val l2 = background.luminance().toDouble()
        val lighter = max(l1, l2)
        val darker = min(l1, l2)
        return (lighter + 0.05) / (darker + 0.05)
    }

    @Test
    fun allPalettesMeetWcagAaContrastInDarkAmoledAndLightModes() {
        val configs = listOf(
            Triple(true, true, "dark_amoled"),
            Triple(true, false, "dark_tonal"),
            Triple(false, false, "light")
        )

        for (palette in palettes) {
            for ((dark, pureBlack, modeName) in configs) {
                val scheme = createPaletteColorScheme(palette, darkTheme = dark, pureBlack = pureBlack)

                val pairs = listOf(
                    "onBackground/background" to (scheme.onBackground to scheme.background),
                    "onSurface/surface" to (scheme.onSurface to scheme.surface),
                    "onSurface/surfaceContainer" to (scheme.onSurface to scheme.surfaceContainer),
                    "onSurface/surfaceContainerHigh" to (scheme.onSurface to scheme.surfaceContainerHigh),
                    "onSurfaceVariant/surfaceContainerLow" to (scheme.onSurfaceVariant to scheme.surfaceContainerLow),
                    "onSurfaceVariant/surfaceContainer" to (scheme.onSurfaceVariant to scheme.surfaceContainer),
                    "onSurfaceVariant/surfaceContainerHigh" to (scheme.onSurfaceVariant to scheme.surfaceContainerHigh),
                    "onPrimary/primary" to (scheme.onPrimary to scheme.primary),
                    "onPrimaryContainer/primaryContainer" to (scheme.onPrimaryContainer to scheme.primaryContainer),
                    "onSecondary/secondary" to (scheme.onSecondary to scheme.secondary),
                    "onSecondaryContainer/secondaryContainer" to (scheme.onSecondaryContainer to scheme.secondaryContainer),
                    "onTertiary/tertiary" to (scheme.onTertiary to scheme.tertiary),
                    "onTertiaryContainer/tertiaryContainer" to (scheme.onTertiaryContainer to scheme.tertiaryContainer),
                    "onError/error" to (scheme.onError to scheme.error),
                    "onErrorContainer/errorContainer" to (scheme.onErrorContainer to scheme.errorContainer)
                )

                for ((label, pair) in pairs) {
                    val ratio = contrastRatio(pair.first, pair.second)
                    assertTrue(
                        "$palette ($modeName) failed WCAG AA on $label: ratio=$ratio",
                        ratio >= 4.5
                    )
                }

                if (dark && pureBlack) {
                    assertEquals(AmoledBlack, scheme.background)
                    assertEquals(AmoledBlack, scheme.surface)
                    assertEquals(AmoledBlack, scheme.surfaceContainerLowest)
                }
            }
        }
    }

    @Test
    fun tonalSurfaceHierarchyHasMonotonicVisualSeparationAcrossAllModes() {
        val configs = listOf(
            Triple(true, true, "dark_amoled"),
            Triple(true, false, "dark_tonal"),
            Triple(false, false, "light")
        )

        for (palette in palettes) {
            for ((dark, pureBlack, modeName) in configs) {
                val scheme = createPaletteColorScheme(palette, darkTheme = dark, pureBlack = pureBlack)
                val levels = listOf(
                    scheme.surfaceContainerLowest,
                    scheme.surfaceContainerLow,
                    scheme.surfaceContainer,
                    scheme.surfaceContainerHigh,
                    scheme.surfaceContainerHighest
                )
                assertEquals(
                    "$palette ($modeName) tonal surface container steps must all be distinct",
                    levels.size,
                    levels.distinct().size
                )
                for (i in 0 until levels.lastIndex) {
                    val lCurr = levels[i].luminance()
                    val lNext = levels[i + 1].luminance()
                    if (dark) {
                        assertTrue(
                            "$palette ($modeName) dark tonal ladder must ascend in luminance at step $i ($lCurr -> $lNext)",
                            lNext > lCurr
                        )
                    } else {
                        assertTrue(
                            "$palette ($modeName) light tonal ladder must descend in luminance at step $i ($lCurr -> $lNext)",
                            lNext < lCurr
                        )
                    }
                }
            }
        }
    }

    @Test
    fun distinctPalettesProduceHarmonizedSecondaryAndTertiaryRoles() {
        val amber = createPaletteColorScheme("colab_amber", darkTheme = true, pureBlack = true)
        val cyan = createPaletteColorScheme("cyber_cyan", darkTheme = true, pureBlack = true)
        val violet = createPaletteColorScheme("neon_violet", darkTheme = true, pureBlack = true)
        val green = createPaletteColorScheme("matrix_green", darkTheme = true, pureBlack = true)

        assertNotEquals(amber.secondary, cyan.secondary)
        assertNotEquals(amber.tertiary, violet.tertiary)
        assertNotEquals(cyan.secondaryContainer, green.secondaryContainer)
        assertNotEquals(amber.onSurfaceVariant, cyan.onSurfaceVariant)
    }

    @Test
    fun expressiveGroupedItemShapesFollowAndroid16Geometry() {
        val single = ExpressiveShapes.groupedItem(index = 0, count = 1, outer = 24.dp, inner = 6.dp)
        assertEquals(RoundedCornerShape(24.dp), single)

        val first = ExpressiveShapes.groupedItem(index = 0, count = 3, outer = 24.dp, inner = 6.dp)
        assertEquals(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp, bottomStart = 6.dp, bottomEnd = 6.dp), first)

        val middle = ExpressiveShapes.groupedItem(index = 1, count = 3, outer = 24.dp, inner = 6.dp)
        assertEquals(RoundedCornerShape(6.dp), middle)

        val last = ExpressiveShapes.groupedItem(index = 2, count = 3, outer = 24.dp, inner = 6.dp)
        assertEquals(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp, bottomStart = 24.dp, bottomEnd = 24.dp), last)
    }
}
