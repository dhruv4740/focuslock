package com.focuslock.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {

    @Test
    fun `lowercase v prefix is newer`() {
        assertTrue(UpdateChecker.isRemoteNewer("v1.2.3", "1.2.2"))
    }

    @Test
    fun `uppercase V prefix is parsed`() {
        assertTrue(UpdateChecker.isRemoteNewer("V0.3.0", "0.2.0"))
    }

    @Test
    fun `pre-release suffix is ignored for comparison`() {
        assertTrue(UpdateChecker.isRemoteNewer("v0.4.0-beta", "0.3.0"))
        assertFalse(UpdateChecker.isRemoteNewer("v0.3.0-beta", "0.3.0"))
    }

    @Test
    fun `numeric segments compare as numbers not strings`() {
        assertTrue(UpdateChecker.isRemoteNewer("v0.10.0", "0.9.9"))
    }

    @Test
    fun `differing segment counts compare correctly`() {
        assertTrue(UpdateChecker.isRemoteNewer("v1.2.1", "1.2"))
        assertFalse(UpdateChecker.isRemoteNewer("v1.2", "1.2.1"))
    }

    @Test
    fun `equal versions are not newer`() {
        assertFalse(UpdateChecker.isRemoteNewer("v1.2.3", "1.2.3"))
    }

    @Test
    fun `garbage tag is not newer`() {
        assertFalse(UpdateChecker.isRemoteNewer("not-a-version", "0.2.0"))
    }
}
