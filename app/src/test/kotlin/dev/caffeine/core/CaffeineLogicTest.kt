package dev.caffeine.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CaffeineLogicTest {

    private val now = 50_000L
    private val settings = CaffeineSettings(
        enabledDurations = listOf(CaffeineDuration.minutes(10), CaffeineDuration.minutes(30), CaffeineDuration.INFINITE),
        defaultDuration = CaffeineDuration.minutes(30),
    )

    @Test
    fun `first tap starts the default duration with a deadline from now`() {
        val next = CaffeineLogic.nextOnTap(CaffeineState.Off, settings, now)
        assertTrue(next is CaffeineState.Active)
        next as CaffeineState.Active
        assertEquals(CaffeineDuration.minutes(30), next.duration)
        assertEquals(now + 30 * 60_000L, next.deadlineElapsed)
    }

    @Test
    fun `tapping while active restarts the clock with the next duration`() {
        val first = CaffeineLogic.nextOnTap(CaffeineState.Off, settings, now) as CaffeineState.Active
        val later = now + 7 * 60_000L
        val second = CaffeineLogic.nextOnTap(first, settings, later) as CaffeineState.Active
        assertEquals(CaffeineDuration.INFINITE, second.duration)
        assertEquals(later, second.startedAtElapsed)
        assertTrue(second.isIndefinite)
    }

    @Test
    fun `tapping at the last duration turns off`() {
        val infinite = CaffeineState.Active.start(CaffeineDuration.INFINITE, now)
        assertEquals(CaffeineState.Off, CaffeineLogic.nextOnTap(infinite, settings, now))
    }

    @Test
    fun `broken settings are normalized before cycling`() {
        val broken = CaffeineSettings(enabledDurations = emptyList(), defaultDuration = CaffeineDuration.minutes(999))
        val next = CaffeineLogic.nextOnTap(CaffeineState.Off, broken, now) as CaffeineState.Active
        assertEquals(CaffeineDuration.DEFAULT_CYCLE.first(), next.duration)
    }
}
