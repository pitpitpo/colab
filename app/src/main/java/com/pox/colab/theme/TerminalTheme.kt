package com.pox.colab.theme

import androidx.compose.ui.graphics.Color

data class TerminalTheme(
    val key: String,
    val name: String,
    val background: Color,
    val foreground: Color,
    val cursorColor: Color,
    val accentColor: Color,
    val panelBg: Color
)

object TerminalThemes {

    val AMOLED_BLACK = TerminalTheme(
        key = "amoled_black",
        name = "AMOLED Pure Black",
        background = Color(0xFF000000),
        foreground = Color(0xFFEEEEEE),
        cursorColor = Color(0xFFF9AB00),
        accentColor = Color(0xFFF9AB00),
        panelBg = Color(0xFF0D0D0D)
    )

    val MONOKAI_PRO = TerminalTheme(
        key = "monokai_dark",
        name = "Monokai Pro",
        background = Color(0xFF1E1F1C),
        foreground = Color(0xFFF8F8F2),
        cursorColor = Color(0xFFFD971F),
        accentColor = Color(0xFFA6E22E),
        panelBg = Color(0xFF272822)
    )

    val MATRIX_GREEN = TerminalTheme(
        key = "matrix_dark",
        name = "Matrix Green",
        background = Color(0xFF021206),
        foreground = Color(0xFF00FF66),
        cursorColor = Color(0xFF00FF66),
        accentColor = Color(0xFF00FF66),
        panelBg = Color(0xFF05200C)
    )

    val DRACULA = TerminalTheme(
        key = "dracula",
        name = "Dracula Dark",
        background = Color(0xFF282A36),
        foreground = Color(0xFFF8F8F2),
        cursorColor = Color(0xFFFF79C6),
        accentColor = Color(0xFFBD93F9),
        panelBg = Color(0xFF343746)
    )

    val SOLARIZED_DARK = TerminalTheme(
        key = "solarized_dark",
        name = "Solarized Dark",
        background = Color(0xFF002B36),
        foreground = Color(0xFF839496),
        cursorColor = Color(0xFF2AA198),
        accentColor = Color(0xFF268BD2),
        panelBg = Color(0xFF073642)
    )

    val CYBERPUNK_AMBER = TerminalTheme(
        key = "cyberpunk_amber",
        name = "Cyberpunk Amber CRT",
        background = Color(0xFF120C00),
        foreground = Color(0xFFFFB000),
        cursorColor = Color(0xFFFFB000),
        accentColor = Color(0xFFFF9100),
        panelBg = Color(0xFF1C1400)
    )

    val DEEP_CHARCOAL = TerminalTheme(
        key = "deep_charcoal",
        name = "Deep Charcoal",
        background = Color(0xFF0F1117),
        foreground = Color(0xFFC9D1D9),
        cursorColor = Color(0xFF58A6FF),
        accentColor = Color(0xFF58A6FF),
        panelBg = Color(0xFF161B22)
    )

    val NAVY_TERMINAL = TerminalTheme(
        key = "navy_terminal",
        name = "Navy Terminal",
        background = Color(0xFF0A0F1D),
        foreground = Color(0xFFE2E8F0),
        cursorColor = Color(0xFF38BDF8),
        accentColor = Color(0xFF38BDF8),
        panelBg = Color(0xFF111827)
    )

    val ALL_THEMES = listOf(
        AMOLED_BLACK,
        MONOKAI_PRO,
        MATRIX_GREEN,
        DRACULA,
        SOLARIZED_DARK,
        CYBERPUNK_AMBER,
        DEEP_CHARCOAL,
        NAVY_TERMINAL
    )

    fun resolve(key: String): TerminalTheme {
        return ALL_THEMES.find { it.key == key } ?: AMOLED_BLACK
    }
}
