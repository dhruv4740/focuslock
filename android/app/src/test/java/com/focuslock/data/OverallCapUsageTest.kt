package com.focuslock.data

import com.focuslock.data.OverallCapUsage.Event
import com.focuslock.data.OverallCapUsage.PAUSED
import com.focuslock.data.OverallCapUsage.RESUMED
import com.focuslock.data.OverallCapUsage.SCREEN_OFF
import org.junit.Assert.assertEquals
import org.junit.Test

class OverallCapUsageTest {
    private val min = 60_000L

    @Test
    fun `sums resumed to paused spans per package`() {
        val events = listOf(
            Event("a", RESUMED, 0), Event("a", PAUSED, 10 * min),
            Event("b", RESUMED, 10 * min), Event("b", PAUSED, 15 * min)
        )
        assertEquals(15.0, OverallCapUsage.foregroundMinutes(events, 20 * min) { false }, 0.001)
    }

    @Test
    fun `excluded packages do not count`() {
        val events = listOf(
            Event("bank", RESUMED, 0), Event("bank", PAUSED, 10 * min),
            Event("a", RESUMED, 10 * min), Event("a", PAUSED, 12 * min)
        )
        assertEquals(2.0, OverallCapUsage.foregroundMinutes(events, 20 * min) { it == "bank" }, 0.001)
    }

    @Test
    fun `an open span runs until now and screen-off closes it`() {
        val open = listOf(Event("a", RESUMED, 0))
        assertEquals(7.0, OverallCapUsage.foregroundMinutes(open, 7 * min) { false }, 0.001)
        val screenOff = listOf(Event("a", RESUMED, 0), Event("", SCREEN_OFF, 3 * min))
        assertEquals(3.0, OverallCapUsage.foregroundMinutes(screenOff, 60 * min) { false }, 0.001)
    }

    @Test
    fun `repeated resumes within one package do not double count`() {
        val events = listOf(Event("a", RESUMED, 0), Event("a", RESUMED, 2 * min), Event("a", PAUSED, 4 * min))
        assertEquals(4.0, OverallCapUsage.foregroundMinutes(events, 10 * min) { false }, 0.001)
    }

    @Test
    fun `a pause with no matching resume is ignored`() {
        val events = listOf(Event("a", PAUSED, 5 * min))
        assertEquals(0.0, OverallCapUsage.foregroundMinutes(events, 10 * min) { false }, 0.001)
    }
}
