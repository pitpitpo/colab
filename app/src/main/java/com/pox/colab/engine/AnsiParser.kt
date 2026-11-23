package com.pox.colab.engine

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration

/**
 * High-performance ANSI escape sequence parser and formatter for terminal rendering in Jetpack Compose.
 *
 * Supports:
 * - Standard 8 & 16 ANSI colors (30-37, 40-47, 90-97, 100-107)
 * - 256-color palette (38;5;N and 48;5;N)
 * - 24-bit TrueColor RGB (38;2;R;G;B and 48;2;R;G;B)
 * - Text attributes: Bold, Dim/Faint, Italic, Underline, Strikethrough, Inverse (swap fg/bg), Reset
 * - Carriage return (\r) line overwrite resolution for progress bars (pip, wget, tqdm)
 * - Plain text extraction via [stripAnsi]
 */
object AnsiParser {

    private val STANDARD_COLORS = arrayOf(
        Color(0xFF2E3440), // 0: Black
        Color(0xFFE06C75), // 1: Red
        Color(0xFF98C379), // 2: Green
        Color(0xFFE5C07B), // 3: Yellow
        Color(0xFF61AFEF), // 4: Blue
        Color(0xFFC678DD), // 5: Magenta
        Color(0xFF56B6C2), // 6: Cyan
        Color(0xFFABB2BF)  // 7: White
    )

    private val BRIGHT_COLORS = arrayOf(
        Color(0xFF5C6370), // 8: Bright Black / Gray
        Color(0xFFF07178), // 9: Bright Red
        Color(0xFFA5E075), // 10: Bright Green
        Color(0xFFFFD580), // 11: Bright Yellow
        Color(0xFF70BFFF), // 12: Bright Blue
        Color(0xFFD884F4), // 13: Bright Magenta
        Color(0xFF67D4E2), // 14: Bright Cyan
        Color(0xFFFFFFFF)  // 15: Bright White
    )

    // Pre-computed 256-color palette array for O(1) allocation-free color lookups
    private val COLOR_PALETTE_256: Array<Color> = Array(256) { idx ->
        when {
            idx in 0..7 -> STANDARD_COLORS[idx]
            idx in 8..15 -> BRIGHT_COLORS[idx - 8]
            idx in 16..231 -> {
                val i = idx - 16
                val r = (i / 36) % 6
                val g = (i / 6) % 6
                val b = i % 6
                val rVal = if (r == 0) 0 else 55 + r * 40
                val gVal = if (g == 0) 0 else 55 + g * 40
                val bVal = if (b == 0) 0 else 55 + b * 40
                Color(rVal, gVal, bVal)
            }
            idx in 232..255 -> {
                val gray = 8 + (idx - 232) * 10
                Color(gray, gray, gray)
            }
            else -> Color.Unspecified
        }
    }

    private val ANSI_REGEX = Regex(
        """\x1B(?:[@-Z\\-_]|\[[0-?]*[ -/]*[@-~]|\].*?(?:\x07|\x1B\\))"""
    )

    /**
     * Resolves carriage returns (`\r`) in a line string.
     * When text follows a carriage return without a newline, it overwrites the preceding text
     * from index 0, replicating real terminal VT100 cursor behavior for progress bars.
     */
    fun resolveCarriageReturns(raw: String): String {
        if (!raw.contains('\r')) return raw
        val parts = raw.split('\r')
        if (parts.size <= 1) return raw
        var current = parts[0]
        for (i in 1 until parts.size) {
            val next = parts[i]
            if (next.isEmpty()) continue
            current = if (next.length >= current.length) {
                next
            } else {
                next + current.substring(next.length)
            }
        }
        return current
    }

    /**
     * Strips all ANSI escape sequences and non-printable control sequences from [text],
     * returning plain text for clipboard operations or searches.
     */
    fun stripAnsi(text: String): String {
        if (text.isEmpty() || !text.contains('\u001b')) return text
        return ANSI_REGEX.replace(text, "")
    }

    fun colorFrom256(index: Int): Color {
        return if (index in 0..255) COLOR_PALETTE_256[index] else Color.Unspecified
    }

    /**
     * Parses an ANSI formatted string into a Jetpack Compose [AnnotatedString]
     * with rich [SpanStyle] attributes for colors, weights, decorations, and styles.
     */
    fun parseToAnnotatedString(
        rawText: String,
        defaultFg: Color,
        defaultBg: Color = Color.Transparent
    ): AnnotatedString {
        val text = resolveCarriageReturns(rawText)
        if (text.isEmpty()) return AnnotatedString("")
        if (!text.contains('\u001b')) {
            return AnnotatedString(text)
        }

        return buildAnnotatedString {
            var currentFg = defaultFg
            var currentBg = defaultBg
            var isBold = false
            var isDim = false
            var isItalic = false
            var isUnderline = false
            var isInverse = false
            var isStrikethrough = false

            fun currentSpanStyle(): SpanStyle? {
                val hasCustomFg = currentFg != defaultFg
                val hasCustomBg = currentBg != defaultBg && currentBg != Color.Transparent
                val hasAttrs = isBold || isDim || isItalic || isUnderline || isInverse || isStrikethrough

                if (!hasCustomFg && !hasCustomBg && !hasAttrs) return null

                val effectiveFg = when {
                    isInverse -> if (hasCustomBg) currentBg else defaultBg
                    isDim -> currentFg.copy(alpha = 0.6f)
                    else -> currentFg
                }

                val effectiveBg = when {
                    isInverse -> currentFg
                    hasCustomBg -> currentBg
                    else -> Color.Transparent
                }

                val textDecor = when {
                    isUnderline && isStrikethrough -> TextDecoration.combine(
                        listOf(TextDecoration.Underline, TextDecoration.LineThrough)
                    )
                    isUnderline -> TextDecoration.Underline
                    isStrikethrough -> TextDecoration.LineThrough
                    else -> null
                }

                return SpanStyle(
                    color = effectiveFg,
                    background = effectiveBg,
                    fontWeight = if (isBold) FontWeight.Bold else null,
                    fontStyle = if (isItalic) FontStyle.Italic else null,
                    textDecoration = textDecor
                )
            }

            var spanStartIndex = 0
            var i = 0
            val len = text.length

            while (i < len) {
                if (text[i] == '\u001b' && i + 1 < len) {
                    val nextChar = text[i + 1]
                    if (nextChar == '[') {
                        // CSI sequence: find terminating letter between 0x40 and 0x7E
                        var end = i + 2
                        while (end < len && text[end].code !in 0x40..0x7E) {
                            end++
                        }
                        if (end < len) {
                            val termChar = text[end]
                            val seq = text.substring(i + 2, end)

                            if (termChar == 'm') {
                                // Close existing span
                                val currentLen = length
                                val style = currentSpanStyle()
                                if (style != null && currentLen > spanStartIndex) {
                                    addStyle(style, spanStartIndex, currentLen)
                                }
                                spanStartIndex = currentLen

                                // Parse SGR parameters separated by semicolon
                                val params = if (seq.isEmpty()) {
                                    listOf(0)
                                } else {
                                    seq.split(';').mapNotNull { it.trim().toIntOrNull() }
                                }

                                var pIdx = 0
                                while (pIdx < params.size) {
                                    val code = params[pIdx]
                                    when (code) {
                                        0 -> {
                                            currentFg = defaultFg
                                            currentBg = defaultBg
                                            isBold = false
                                            isDim = false
                                            isItalic = false
                                            isUnderline = false
                                            isInverse = false
                                            isStrikethrough = false
                                        }
                                        1 -> isBold = true
                                        2 -> isDim = true
                                        3 -> isItalic = true
                                        4 -> isUnderline = true
                                        7 -> isInverse = true
                                        9 -> isStrikethrough = true
                                        22 -> { isBold = false; isDim = false }
                                        23 -> isItalic = false
                                        24 -> isUnderline = false
                                        27 -> isInverse = false
                                        29 -> isStrikethrough = false
                                        in 30..37 -> currentFg = STANDARD_COLORS[code - 30]
                                        38 -> {
                                            // Extended FG color
                                            if (pIdx + 2 < params.size && params[pIdx + 1] == 5) {
                                                // 256 color: 38;5;N
                                                currentFg = colorFrom256(params[pIdx + 2])
                                                pIdx += 2
                                            } else if (pIdx + 4 < params.size && params[pIdx + 1] == 2) {
                                                // 24-bit TrueColor: 38;2;R;G;B
                                                currentFg = Color(
                                                    params[pIdx + 2].coerceIn(0, 255),
                                                    params[pIdx + 3].coerceIn(0, 255),
                                                    params[pIdx + 4].coerceIn(0, 255)
                                                )
                                                pIdx += 4
                                            }
                                        }
                                        39 -> currentFg = defaultFg
                                        in 40..47 -> currentBg = STANDARD_COLORS[code - 40]
                                        48 -> {
                                            // Extended BG color
                                            if (pIdx + 2 < params.size && params[pIdx + 1] == 5) {
                                                currentBg = colorFrom256(params[pIdx + 2])
                                                pIdx += 2
                                            } else if (pIdx + 4 < params.size && params[pIdx + 1] == 2) {
                                                currentBg = Color(
                                                    params[pIdx + 2].coerceIn(0, 255),
                                                    params[pIdx + 3].coerceIn(0, 255),
                                                    params[pIdx + 4].coerceIn(0, 255)
                                                )
                                                pIdx += 4
                                            }
                                        }
                                        49 -> currentBg = defaultBg
                                        in 90..97 -> currentFg = BRIGHT_COLORS[code - 90]
                                        in 100..107 -> currentBg = BRIGHT_COLORS[code - 100]
                                    }
                                    pIdx++
                                }
                            }
                            // Move index past CSI sequence
                            i = end + 1
                            continue
                        }
                    } else if (nextChar == ']' || nextChar == '(' || nextChar == ')') {
                        // OSC or Character Set sequence: skip cleanly
                        var end = i + 2
                        while (end < len && text[end] != '\u0007' && text[end] != '\u001b') {
                            end++
                        }
                        if (end < len && text[end] == '\u001b' && end + 1 < len && text[end + 1] == '\\') {
                            end += 2
                        } else if (end < len && text[end] == '\u0007') {
                            end += 1
                        }
                        i = end
                        continue
                    }
                }
                append(text[i])
                i++
            }

            // Close trailing span style if applicable
            val currentLen = length
            val style = currentSpanStyle()
            if (style != null && currentLen > spanStartIndex) {
                addStyle(style, spanStartIndex, currentLen)
            }
        }
    }
}
