package com.pox.colab.util

object SemverUtil {
    fun isVersionNewer(latest: String, current: String): Boolean {
        val l = latest.trim().removePrefix("v").split(".").mapNotNull { it.takeWhile { c -> c.isDigit() }.toIntOrNull() }
        val c = current.trim().removePrefix("v").split(".").mapNotNull { it.takeWhile { ch -> ch.isDigit() }.toIntOrNull() }
        val maxLen = maxOf(l.size, c.size)
        for (i in 0 until maxLen) {
            val lPart = l.getOrElse(i) { 0 }
            val cPart = c.getOrElse(i) { 0 }
            if (lPart > cPart) return true
            if (lPart < cPart) return false
        }
        return false
    }
}
