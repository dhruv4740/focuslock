package com.focuslock.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUsageStoreLogicTest {

    @Test
    fun `mergeSession accumulates minutes per package`() {
        val day = AppUsageStore.mergeSession(emptyMap(), "com.instagram.android", 2.5)
        val day2 = AppUsageStore.mergeSession(day, "com.instagram.android", 1.5)
        assertEquals(4.0, day2["com.instagram.android"]!!, 0.0001)
    }

    @Test
    fun `serialize and parse round-trip`() {
        val day = mapOf("com.instagram.android" to 12.25, "app.morphe.android.youtube" to 3.75)
        val parsed = AppUsageStore.parseDay(AppUsageStore.serializeDay(day))
        assertEquals(12.25, parsed["com.instagram.android"]!!, 0.0001)
        assertEquals(3.75, parsed["app.morphe.android.youtube"]!!, 0.0001)
    }

    @Test
    fun `parseDay tolerates null empty and garbage`() {
        assertTrue(AppUsageStore.parseDay(null).isEmpty())
        assertTrue(AppUsageStore.parseDay("").isEmpty())
        assertTrue(AppUsageStore.parseDay("not-json").isEmpty())
    }

    @Test
    fun `pruneCutoff keeps exactly KEEP_DAYS of history`() {
        // Cutoff for 2026-07-19 with KEEP_DAYS=7 is 2026-07-13; strictly-older keys get removed.
        val cutoff = AppUsageStore.pruneCutoff("2026-07-19")
        assertEquals("2026-07-13", cutoff)
        assertTrue("2026-07-12" < cutoff)
        assertTrue("2026-07-13" >= cutoff)
    }
}
