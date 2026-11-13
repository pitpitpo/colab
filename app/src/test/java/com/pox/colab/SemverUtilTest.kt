package com.pox.colab

import com.pox.colab.util.SemverUtil
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
    }
}
