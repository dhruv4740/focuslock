package com.focuslock.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    @Test
    fun `parseRelease reads apk and sha256 assets plus notes`() {
        val body = """
            {
              "tag_name": "v0.4.0",
              "body": "Bug fixes and improvements.",
              "assets": [
                {"name": "focuslock-v0.4.0.apk", "browser_download_url": "https://example.com/app.apk"},
                {"name": "focuslock-v0.4.0.apk.sha256", "browser_download_url": "https://example.com/app.apk.sha256"},
                {"name": "readme.txt", "browser_download_url": "https://example.com/readme.txt"}
              ]
            }
        """.trimIndent()
        val info = UpdateChecker.parseRelease(body)
        assertEquals("v0.4.0", info?.tagName)
        assertEquals("https://example.com/app.apk", info?.apkUrl)
        assertEquals("https://example.com/app.apk.sha256", info?.shaUrl)
        assertEquals("Bug fixes and improvements.", info?.notes)
    }

    @Test
    fun `parseRelease with missing assets leaves urls null`() {
        val body = """{"tag_name": "v0.4.0", "body": "notes"}"""
        val info = UpdateChecker.parseRelease(body)
        assertEquals("v0.4.0", info?.tagName)
        assertNull(info?.apkUrl)
        assertNull(info?.shaUrl)
    }

    @Test
    fun `parseRelease with missing body defaults notes to empty string`() {
        val body = """{"tag_name": "v0.4.0", "assets": []}"""
        val info = UpdateChecker.parseRelease(body)
        assertEquals("", info?.notes)
    }

    @Test
    fun `parseRelease with empty tag returns null`() {
        val body = """{"tag_name": "", "body": "notes"}"""
        assertNull(UpdateChecker.parseRelease(body))
    }
}
