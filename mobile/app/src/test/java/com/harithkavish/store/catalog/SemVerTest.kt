package com.harithkavish.store.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SemVerTest {

    @Test
    fun `normalizes a leading v and missing segments`() {
        assertEquals(SemVer.Triple(1, 2, 3), SemVer.normalize("v1.2.3"))
        assertEquals(SemVer.Triple(1, 0, 0), SemVer.normalize("1"))
        assertEquals(SemVer.Triple(0, 0, 0), SemVer.normalize(null))
        assertEquals(SemVer.Triple(0, 0, 0), SemVer.normalize(""))
    }

    @Test
    fun `detects a newer patch, minor and major`() {
        assertTrue(SemVer.isNewer("0.0.12", "0.0.11"))
        assertTrue(SemVer.isNewer("0.1.0", "0.0.99"))
        assertTrue(SemVer.isNewer("1.0.0", "0.9.9"))
        assertFalse(SemVer.isNewer("0.0.11", "0.0.11"))
        assertFalse(SemVer.isNewer("0.0.10", "0.0.11"))
    }

    @Test
    fun `isDifferent ignores direction`() {
        assertTrue(SemVer.isDifferent("0.0.10", "0.0.11"))
        assertTrue(SemVer.isDifferent("0.0.12", "0.0.11"))
        assertFalse(SemVer.isDifferent("0.0.11", "v0.0.11"))
    }

    @Test
    fun `tolerates a trailing pre-release suffix on the patch segment`() {
        assertEquals(SemVer.Triple(1, 2, 3), SemVer.normalize("1.2.3-debug"))
    }
}
