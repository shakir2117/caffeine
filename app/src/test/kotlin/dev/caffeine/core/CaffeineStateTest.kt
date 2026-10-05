package dev.caffeine.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CaffeineStateTest {

    private val now = 1_000_000L

    @Test
    fun `timed session has an elapsed-realtime deadline`() {
        val active = CaffeineState.Active.start(CaffeineDuration.minutes(10), now)
        assertEquals(now, active.startedAtElapsed)
        assertEquals(now + 10 * 60_000L, active.deadlineElapsed)
        assertFalse(active.isIndefinite)
    }

    @Test
    fun `remaining counts down against the monotonic clock and never goes negative`() {
        val active = CaffeineState.Active.start(CaffeineDuration.minutes(5), now)
        assertEquals(5 * 60_000L, active.remainingMillis(now))
        assertEquals(60_000L, active.remainingMillis(now + 4 * 60_000L))
        assertEquals(0L, active.remainingMillis(now + 9 * 60_000L))
    }

    @Test
    fun `expires exactly at the deadline`() {
        val active = CaffeineState.Active.start(CaffeineDuration.minutes(1), now)
        assertFalse(active.isExpired(now + 59_999L))
        assertTrue(active.isExpired(now + 60_000L))
        assertTrue(active.isExpired(now + 60_001L))
    }

    @Test
    fun `indefinite session has no deadline and never expires`() {
        val active = CaffeineState.Active.start(CaffeineDuration.INFINITE, now)
        assertTrue(active.isIndefinite)
        assertNull(active.deadlineElapsed)
        assertNull(active.remainingMillis(now + Long.MAX_VALUE / 2))
        assertFalse(active.isExpired(Long.MAX_VALUE))
    }

    @Test
    fun `minute tick delay lands just after the label would change`() {
        // 12 min 30 s left: the "13 min" label becomes "12 min" in 30 s.
        assertEquals(30_000L + 50L, Ticks.untilNextMinuteChange(12 * 60_000L + 30_000L))
        // Exactly 12 min left: next change is a full minute away.
        assertEquals(60_000L + 50L, Ticks.untilNextMinuteChange(12 * 60_000L))
        // 1 ms left: fire immediately after.
        assertEquals(1L + 50L, Ticks.untilNextMinuteChange(1L))
        assertEquals(0L, Ticks.untilNextMinuteChange(0L))
    }

    @Test
    fun `second tick delay lands just after the clock would change`() {
        assertEquals(400L + 50L, Ticks.untilNextSecond(4_400L))
        assertEquals(1_000L + 50L, Ticks.untilNextSecond(5_000L))
        assertEquals(0L, Ticks.untilNextSecond(0L))
    }
}
