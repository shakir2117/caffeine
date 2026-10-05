package dev.caffeine.core

/** Pure state transitions used by the controller; no Android dependencies. */
object CaffeineLogic {

    fun nextOnTap(current: CaffeineState, settings: CaffeineSettings, nowElapsed: Long): CaffeineState {
        val normalized = settings.normalized()
        val next = DurationCycle.next(
            current = (current as? CaffeineState.Active)?.duration,
            cycle = normalized.enabledDurations,
            default = normalized.defaultDuration,
        )
        return next?.let { CaffeineState.Active.start(it, nowElapsed) } ?: CaffeineState.Off
    }
}

enum class StopReason { USER, EXPIRED, SCREEN_OFF, BATTERY_SAVER, LOW_BATTERY, SERVICE_LOST, START_DENIED }

sealed interface CaffeineEvent {
    /** The system refused to start the foreground service from the tile. */
    data object StartNotAllowed : CaffeineEvent
}
