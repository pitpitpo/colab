package com.pox.colab.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.pox.colab.model.VMStatus

/**
 * Theme-aware semantic status colors.
 *
 * Terminal ANSI colors are tuned for a dark console canvas and must never be reused for
 * app chrome: `#3FB950` on the light `surfaceContainerLow` is only 2.36:1 and `#E3B341`
 * is 1.80:1. These dedicated variants are darkened for light surfaces so status text
 * clears WCAG AA 4.5:1 and status dots clear WCAG 1.4.11 3:1.
 */
private val StatusGreenDark = Color(0xFF3FB950)
private val StatusGreenLight = Color(0xFF1B6B33)
private val StatusAmberDark = Color(0xFFE3B341)
private val StatusAmberLight = Color(0xFF7A4E00)

/** True when the active color scheme paints on a light canvas. */
@Composable
fun isLightTheme(): Boolean = MaterialTheme.colorScheme.background.luminance() > 0.5f

/** Positive / running / connected. */
@Composable
fun statusSuccessColor(): Color =
    if (isLightTheme()) StatusGreenLight else StatusGreenDark

/** Pending / connecting / reconnecting / warning. */
@Composable
fun statusWarningColor(): Color =
    if (isLightTheme()) StatusAmberLight else StatusAmberDark

/** Failure states: the theme error token already passes AA in both modes. */
@Composable
fun statusErrorColor(): Color = MaterialTheme.colorScheme.error

/** Neutral / offline / disabled. `onSurfaceVariant` is 10.2:1 dark and 7.3:1 light. */
@Composable
fun statusNeutralColor(): Color = MaterialTheme.colorScheme.onSurfaceVariant

/** Canonical status color for a VM lifecycle state. */
@Composable
fun vmStatusColor(status: VMStatus): Color = when (status) {
    VMStatus.RUNNING -> statusSuccessColor()
    VMStatus.STARTING, VMStatus.RECONNECTING -> statusWarningColor()
    VMStatus.STOPPED -> statusNeutralColor()
    VMStatus.ERROR -> statusErrorColor()
}

/** Human readable status label, used for text labels and screen-reader descriptions. */
fun vmStatusLabel(status: VMStatus): String = when (status) {
    VMStatus.RUNNING -> "Running"
    VMStatus.STARTING -> "Starting"
    VMStatus.RECONNECTING -> "Reconnecting"
    VMStatus.STOPPED -> "Stopped"
    VMStatus.ERROR -> "Error"
}
