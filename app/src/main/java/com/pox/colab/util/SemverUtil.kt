package com.pox.colab.util

/**
 * Semantic-version comparison following SemVer 2.0.0 precedence rules.
 *
 * Supported input: optional `v`/`V` prefix, dotted numeric core (any number of segments,
 * missing segments treated as 0), `-` pre-release identifiers and `+` build metadata.
 * Build metadata is ignored for precedence; pre-release identifiers order before the
 * corresponding release. Unparseable input is treated as "not newer" so a malformed
 * GitHub tag can never trigger a bogus update prompt.
 */
object SemverUtil {

    private data class ParsedVersion(
        val core: List<Int>,
        val preRelease: List<String>?
    )

    /** True when [latest] has strictly higher SemVer precedence than [current]. */
    fun isVersionNewer(latest: String, current: String): Boolean = compare(latest, current) > 0

    /**
     * @return 1 when [latest] is newer, -1 when older, 0 when equal or when either side
     * cannot be parsed.
     */
    fun compare(latest: String, current: String): Int {
        val left = parse(latest) ?: return 0
        val right = parse(current) ?: return 0

        val coreLength = maxOf(left.core.size, right.core.size)
        for (index in 0 until coreLength) {
            val l = left.core.getOrElse(index) { 0 }
            val r = right.core.getOrElse(index) { 0 }
            if (l != r) return l.compareTo(r)
        }

        val lPre = left.preRelease
        val rPre = right.preRelease
        return when {
            lPre == null && rPre == null -> 0
            lPre == null -> 1   // 1.0.0 > 1.0.0-rc.1
            rPre == null -> -1
            else -> comparePreRelease(lPre, rPre)
        }
    }

    private fun parse(raw: String): ParsedVersion? {
        var text = raw.trim()
        if (text.isEmpty()) return null

        // Accept the "v1.2.3" shape used by GitHub tags and by VERSION_NAME.
        if (text.startsWith("v", ignoreCase = true)) {
            text = text.substring(1).trim()
        }
        if (text.isEmpty()) return null

        // Build metadata never participates in precedence.
        text = text.substringBefore('+').trim()

        val corePart = text.substringBefore('-').trim()
        val prePart = if (text.contains('-')) text.substringAfter('-').trim() else null
        if (corePart.isEmpty()) return null

        val core = corePart.split('.').map { segment ->
            if (segment.isEmpty() || !segment.all { it.isDigit() }) return null
            segment.toIntOrNull() ?: return null
        }

        val preRelease = when {
            prePart.isNullOrEmpty() -> null
            else -> {
                val identifiers = prePart.split('.')
                if (identifiers.any { it.isEmpty() }) null else identifiers
            }
        }

        return ParsedVersion(core, preRelease)
    }

    private fun comparePreRelease(left: List<String>, right: List<String>): Int {
        val length = maxOf(left.size, right.size)
        for (index in 0 until length) {
            // A shorter set of identifiers has lower precedence when all preceding are equal.
            val l = left.getOrNull(index) ?: return -1
            val r = right.getOrNull(index) ?: return 1

            val lNumeric = l.toIntOrNull()
            val rNumeric = r.toIntOrNull()
            val comparison = when {
                lNumeric != null && rNumeric != null -> lNumeric.compareTo(rNumeric)
                lNumeric != null -> -1   // numeric identifiers rank below alphanumeric ones
                rNumeric != null -> 1
                else -> l.compareTo(r)
            }
            if (comparison != 0) return comparison
        }
        return 0
    }
}
