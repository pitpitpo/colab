package com.pox.colab

import com.pox.colab.util.SemverUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SemverUtilTest {

    @Test
    fun isVersionNewer_standardSemanticVersions() {
        // Newer patch
        assertTrue(SemverUtil.isVersionNewer("v1.0.1", "v1.0.0"))
        // Newer minor
        assertTrue(SemverUtil.isVersionNewer("v1.1.0", "v1.0.9"))
        // Newer major
        assertTrue(SemverUtil.isVersionNewer("2.0.0", "1.9.9"))
        // Multi-digit segments must not compare lexicographically
        assertTrue(SemverUtil.isVersionNewer("1.10.0", "1.9.10"))
        assertTrue(SemverUtil.isVersionNewer("1.0.10", "1.0.9"))
    }

    @Test
    fun isVersionNewer_olderOrEqualVersions() {
        // Equal
        assertFalse(SemverUtil.isVersionNewer("v1.0.0", "v1.0.0"))
        assertFalse(SemverUtil.isVersionNewer("1.0.0", "1.0.0"))
        // Downgrade
        assertFalse(SemverUtil.isVersionNewer("v0.9.9", "v1.0.0"))
        assertFalse(SemverUtil.isVersionNewer("1.0.0", "1.0.1"))
        assertFalse(SemverUtil.isVersionNewer("1.1.0", "1.2.0"))
    }

    @Test
    fun isVersionNewer_differentSegmentLengths() {
        assertTrue(SemverUtil.isVersionNewer("1.0.0.1", "1.0.0"))
        assertFalse(SemverUtil.isVersionNewer("1.0", "1.0.0"))
        assertTrue(SemverUtil.isVersionNewer("1.1", "1.0.5"))
        assertFalse(SemverUtil.isVersionNewer("1", "1.0.0"))
    }

    @Test
    fun isVersionNewer_preReleaseRanksBelowItsRelease() {
        // A release candidate must never be offered as an upgrade over the release.
        assertFalse(SemverUtil.isVersionNewer("1.0.0-rc.1", "1.0.0"))
        assertFalse(SemverUtil.isVersionNewer("v2.0.0-beta.3", "v2.0.0"))
        // ...but it is newer than the previous release.
        assertTrue(SemverUtil.isVersionNewer("1.0.1-rc.1", "1.0.0"))
        // A newer pre-release of the same core is still newer.
        assertTrue(SemverUtil.isVersionNewer("1.0.0-rc.2", "1.0.0-rc.1"))
        // Numeric identifiers order numerically, alphanumeric identifiers lexically.
        assertTrue(SemverUtil.isVersionNewer("1.0.0-rc.10", "1.0.0-rc.9"))
        assertTrue(SemverUtil.isVersionNewer("1.0.0-beta", "1.0.0-alpha"))
        assertFalse(SemverUtil.isVersionNewer("1.0.0-alpha", "1.0.0-beta"))
        // Numeric identifiers rank below alphanumeric ones.
        assertFalse(SemverUtil.isVersionNewer("1.0.0-1", "1.0.0-alpha"))
        // Fewer identifiers rank below more identifiers when the prefix is equal.
        assertFalse(SemverUtil.isVersionNewer("1.0.0-rc", "1.0.0-rc.1"))
    }

    @Test
    fun isVersionNewer_buildMetadataIsIgnored() {
        assertFalse(SemverUtil.isVersionNewer("1.0.0+build.5", "1.0.0"))
        assertFalse(SemverUtil.isVersionNewer("v1.0.0+sha.abcdef", "1.0.0"))
        assertFalse(SemverUtil.isVersionNewer("1.0.0+build.5", "1.0.0+build.4"))
        assertTrue(SemverUtil.isVersionNewer("1.0.1+build.1", "1.0.0+build.9"))
        assertFalse(SemverUtil.isVersionNewer("1.0.0-rc.1+build.9", "1.0.0-rc.1+build.1"))
    }

    @Test
    fun isVersionNewer_toleratesFormatting() {
        assertTrue(SemverUtil.isVersionNewer("  v1.2.0  ", "1.1.9"))
        assertTrue(SemverUtil.isVersionNewer("V2.0.0", "v1.99.99"))
        assertTrue(SemverUtil.isVersionNewer("01.02.03", "1.2.2"))
        assertFalse(SemverUtil.isVersionNewer("01.02.03", "1.2.3"))
    }

    @Test
    fun isVersionNewer_invalidInputNeverTriggersAnUpdate() {
        assertFalse(SemverUtil.isVersionNewer("latest", "1.0.0"))
        assertFalse(SemverUtil.isVersionNewer("1.0.0", "latest"))
        assertFalse(SemverUtil.isVersionNewer("", "1.0.0"))
        assertFalse(SemverUtil.isVersionNewer("1.0.0", ""))
        assertFalse(SemverUtil.isVersionNewer("v", "1.0.0"))
        assertFalse(SemverUtil.isVersionNewer("1.x.0", "1.0.0"))
        assertFalse(SemverUtil.isVersionNewer("1..0", "1.0.0"))
        assertFalse(SemverUtil.isVersionNewer("1.0.0", "1.0.0-"))
    }

    @Test
    fun compare_reportsDirectionAndEquality() {
        assertEquals(0, SemverUtil.compare("1.0.0", "v1.0.0"))
        assertEquals(0, SemverUtil.compare("1.0.0+build.1", "1.0.0+build.2"))
        assertEquals(1, SemverUtil.compare("1.0.1", "1.0.0"))
        assertEquals(-1, SemverUtil.compare("1.0.0-rc.1", "1.0.0"))
        assertEquals(0, SemverUtil.compare("not-a-version", "1.0.0"))
    }
}
