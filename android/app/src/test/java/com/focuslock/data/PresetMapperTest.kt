package com.focuslock.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PresetMapperTest {

    private val targets = listOf("com.instagram.android", "app.morphe.android.youtube")

    @Test
    fun `bundleToLiveFields derives targetApps from non-off appModes`() {
        val bundle = mapOf(
            "name" to "Test",
            "appModes" to mapOf(
                "com.instagram.android" to "hard_block",
                "app.morphe.android.youtube" to "off"
            ),
            "hardBlockAfterLimit" to true
        )
        val fields = PresetMapper.bundleToLiveFields("test", bundle)
        assertEquals("test", fields["activePresetId"])
        assertEquals(listOf("com.instagram.android"), fields["targetApps"])
        assertEquals(true, fields["hardBlockAfterLimit"])
        assertFalse(fields.containsKey("dailyLimitMinutes"))
    }

    @Test
    fun `builtin strict hard-blocks every current target`() {
        val bundle = PresetMapper.builtInBundle(PresetMapper.BUILTIN_STRICT, targets, 0, emptyMap())!!
        @Suppress("UNCHECKED_CAST")
        val modes = bundle["appModes"] as Map<String, String>
        assertTrue(modes.values.all { it == "hard_block" })
        assertEquals(true, bundle["hardBlockAfterLimit"])
        // No configured daily limit falls back to the strict default.
        assertEquals(30, bundle["dailyLimitMinutes"])
    }

    @Test
    fun `builtin chill keeps existing reel limits and soft blocking`() {
        val limits = mapOf("instagram_reels" to 10)
        val bundle = PresetMapper.builtInBundle(PresetMapper.BUILTIN_CHILL, targets, 45, limits)!!
        assertEquals(limits, bundle["reelDailyLimit"])
        assertEquals(false, bundle["hardBlockAfterLimit"])
        assertFalse(bundle.containsKey("dailyLimitMinutes"))
    }

    @Test
    fun `unknown builtin id returns null`() {
        assertNull(PresetMapper.builtInBundle("nope", targets, 0, emptyMap()))
    }

    @Test
    fun `displayName prefers bundle name over id`() {
        assertEquals("My Mode", PresetMapper.displayName("x", mapOf("name" to "My Mode")))
        assertEquals("Chill", PresetMapper.displayName("chill", null))
    }
}
