package dev.caffeine.core

data class CaffeineSettings(
    val enabledDurations: List<CaffeineDuration> = CaffeineDuration.DEFAULT_CYCLE,
    val defaultDuration: CaffeineDuration = CaffeineDuration.DEFAULT_CYCLE.first(),
    /** Matches the AOSP tile: pressing the power button ends the session. */
    val stopOnScreenOff: Boolean = true,
    val stopOnBatterySaver: Boolean = false,
    val stopOnLowBattery: Boolean = false,
    val lowBatteryThreshold: Int = 15,
) {
    /** Guarantees a non-empty sorted cycle, a default that is in the cycle, and a sane threshold. */
    fun normalized(): CaffeineSettings {
        val cycle = DurationCycle.normalize(enabledDurations).ifEmpty { CaffeineDuration.DEFAULT_CYCLE }
        val default = if (defaultDuration in cycle) defaultDuration else cycle.first()
        return copy(
            enabledDurations = cycle,
            defaultDuration = default,
            lowBatteryThreshold = lowBatteryThreshold.coerceIn(MIN_LOW_BATTERY, MAX_LOW_BATTERY),
        )
    }

    companion object {
        const val MIN_LOW_BATTERY = 5
        const val MAX_LOW_BATTERY = 50
        val DEFAULT = CaffeineSettings()
    }
}
