package dev.caffeine.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DurationCycleTest {

    private val m5 = CaffeineDuration.minutes(5)
    private val m10 = CaffeineDuration.minutes(10)
    private val m30 = CaffeineDuration.minutes(30)
    private val h1 = CaffeineDuration.minutes(60)
    private val inf = CaffeineDuration.INFINITE
    private val aosp = listOf(m5, m10, m30, h1, inf)

    @Test
    fun `off goes to the default`() {
        assertEquals(m5, DurationCycle.next(null, aosp, default = m5))
        assertEquals(m30, DurationCycle.next(null, aosp, default = m30))
    }

    @Test
    fun `off goes to the shortest enabled when the default is not enabled`() {
        assertEquals(m10, DurationCycle.next(null, listOf(m10, h1), default = m5))
    }

    @Test
    fun `cycles upward through the AOSP list then off`() {
        var current: CaffeineDuration? = null
        val visited = mutableListOf<CaffeineDuration?>()
        repeat(6) {
            current = DurationCycle.next(current, aosp, default = m5)
            visited += current
        }
        assertEquals(listOf(m5, m10, m30, h1, inf, null), visited)
    }

    @Test
    fun `infinite is the last stop and then off`() {
        assertEquals(inf, DurationCycle.next(h1, aosp, default = m5))
        assertNull(DurationCycle.next(inf, aosp, default = m5))
    }

    @Test
    fun `a duration removed from the cycle while active advances to the next longer one`() {
        // Active at 10 min, but 10 min is no longer in the cycle.
        assertEquals(m30, DurationCycle.next(m10, listOf(m5, m30, inf), default = m5))
        // Active at 2 h, nothing longer except infinite.
        assertEquals(inf, DurationCycle.next(CaffeineDuration.minutes(120), listOf(m5, inf), default = m5))
        // Active at infinite, infinite removed: off.
        assertNull(DurationCycle.next(inf, listOf(m5, m10), default = m5))
    }

    @Test
    fun `unordered or duplicated cycles are normalized`() {
        assertEquals(listOf(m5, m10, h1, inf), DurationCycle.normalize(listOf(inf, h1, m5, m10, m5)))
        assertEquals(m10, DurationCycle.next(m5, listOf(inf, h1, m10, m10), default = m5))
    }

    @Test
    fun `empty cycle is always off`() {
        assertNull(DurationCycle.next(null, emptyList(), default = m5))
        assertNull(DurationCycle.next(m5, emptyList(), default = m5))
    }

    @Test
    fun `infinite sorts after every finite duration`() {
        val huge = CaffeineDuration.minutes(Int.MAX_VALUE)
        assertEquals(listOf(m5, huge, inf), listOf(inf, huge, m5).sorted())
    }
}
