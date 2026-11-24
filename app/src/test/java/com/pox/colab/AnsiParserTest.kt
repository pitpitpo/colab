package com.pox.colab

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import com.pox.colab.engine.AnsiParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnsiParserTest {

    @Test
    fun stripAnsi_removesEscapeSequences() {
        val colored = "\u001b[32mroot@colab\u001b[0m:\u001b[34m/content\u001b[0m# ls -la"
        assertEquals("root@colab:/content# ls -la", AnsiParser.stripAnsi(colored))

        val plain = "Normal text without escapes"
        assertEquals(plain, AnsiParser.stripAnsi(plain))

        val empty = ""
        assertEquals("", AnsiParser.stripAnsi(empty))
    }

    @Test
    fun resolveCarriageReturns_overwritesPreviousText() {
        val progressBar = "Downloading 10%\rDownloading 50%\rDownloading 100%"
        assertEquals("Downloading 100%", AnsiParser.resolveCarriageReturns(progressBar))

        val partialOverwrite = "Hello World\r12345"
        assertEquals("12345 World", AnsiParser.resolveCarriageReturns(partialOverwrite))

        val noCr = "Just standard text\nwith newlines"
        assertEquals(noCr, AnsiParser.resolveCarriageReturns(noCr))
    }

    @Test
    fun parseToAnnotatedString_plainText() {
        val plain = "Plain terminal output line"
        val result = AnsiParser.parseToAnnotatedString(plain, defaultFg = Color.White, defaultBg = Color.Black)
        assertEquals(plain, result.text)
        assertTrue(result.spanStyles.isEmpty())
    }

    @Test
    fun parseToAnnotatedString_standardColorsAndStyles() {
        val ansiInput = "\u001b[1mBold \u001b[31mRed \u001b[4mUnderline\u001b[0m Plain"
        val result = AnsiParser.parseToAnnotatedString(ansiInput, defaultFg = Color.White, defaultBg = Color.Black)

        assertEquals("Bold Red Underline Plain", result.text)
        assertTrue(result.spanStyles.isNotEmpty())

        // Verify bold span
        val boldSpan = result.spanStyles.find { it.style.fontWeight == FontWeight.Bold }
        assertNotNull("Expected bold span", boldSpan)

        // Verify underline span
        val underlineSpan = result.spanStyles.find { it.style.textDecoration == TextDecoration.Underline }
        assertNotNull("Expected underline span", underlineSpan)
    }

    @Test
    fun parseToAnnotatedString_256Colors() {
        // Color 196 in 256 palette is bright red
        val ansiInput = "\u001b[38;5;196mRed256\u001b[0m"
        val result = AnsiParser.parseToAnnotatedString(ansiInput, defaultFg = Color.White, defaultBg = Color.Black)
        assertEquals("Red256", result.text)
        assertEquals(1, result.spanStyles.size)
        assertEquals(Color(255, 0, 0), result.spanStyles[0].style.color)
    }

    @Test
    fun parseToAnnotatedString_24BitTrueColor() {
        val ansiInput = "\u001b[38;2;123;45;67mTrueColor\u001b[0m"
        val result = AnsiParser.parseToAnnotatedString(ansiInput, defaultFg = Color.White, defaultBg = Color.Black)
        assertEquals("TrueColor", result.text)
        assertEquals(1, result.spanStyles.size)
        assertEquals(Color(123, 45, 67), result.spanStyles[0].style.color)
    }

    @Test
    fun parseToAnnotatedString_stripsNonSgrOscAndCursorCommands() {
        val oscTitleAndCursor = "\u001b]0;My Terminal Title\u0007\u001b[?25hPrompt> \u001b[?25l"
        val result = AnsiParser.parseToAnnotatedString(oscTitleAndCursor, defaultFg = Color.White, defaultBg = Color.Black)
        assertEquals("Prompt> ", result.text)
    }
}
