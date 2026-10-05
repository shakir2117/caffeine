package dev.caffeine.core

import org.junit.Assert.assertEquals
import org.junit.Test

class CaffeineSettingsTest {

    @Test
    fun `defaults match the AOSP tile`() {
        val s = CaffeineSettings.DEFAULT.normalized()
        assertEquals(CaffeineDuration.DEFAULT_CYCLE, s.enabledDurations)
        assertEquals(CaffeineDuration.minutes(5), s.defaultDuration)
        assertEquals(true, s.stopOnScreenOff)
    }

    @Test
    fun `default duration is forced into the enabled cycle`() {
        val s = CaffeineSettings(
            enabledDurations = listOf(CaffeineDuration.minutes(30), CaffeineDuration.minutes(10)),
            defaultDuration = CaffeineDuration.minutes(5),
        ).normalized()
        assertEquals(listOf(CaffeineDuration.minutes(10), CaffeineDuration.minutes(30)), s.enabledDurations)
        assertEquals(CaffeineDuration.minutes(10), s.defaultDuration)
    }

    @Test
    fun `empty cycle falls back to the default cycle`() {
        val s = CaffeineSettings(enabledDurations = emptyList()).normalized()
        assertEquals(CaffeineDuration.DEFAULT_CYCLE, s.enabledDurations)
    }

    @Test
    fun `low battery threshold is clamped`() {
        assertEquals(CaffeineSettings.MIN_LOW_BATTERY, CaffeineSettings(lowBatteryThreshold = -3).normalized().lowBatteryThreshold)
        assertEquals(CaffeineSettings.MAX_LOW_BATTERY, CaffeineSettings(lowBatteryThreshold = 90).normalized().lowBatteryThreshold)
        assertEquals(20, CaffeineSettings(lowBatteryThreshold = 20).normalized().lowBatteryThreshold)
    }
}
