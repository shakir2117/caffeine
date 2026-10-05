package dev.caffeine.core

/**
 * The single source of truth for "is the screen being kept awake".
 * All times are `SystemClock.elapsedRealtime()` millis (monotonic, survives clock changes).
 */
sealed interface CaffeineState {

    data object Off : CaffeineState

    data class Active(
        val duration: CaffeineDuration,
        val startedAtElapsed: Long,
        /** `null` for an indefinite session. */
        val deadlineElapsed: Long?,
    ) : CaffeineState {

        val isIndefinite: Boolean get() = deadlineElapsed == null

        /** Milliseconds left, never negative; `null` for an indefinite session. */
        fun remainingMillis(nowElapsed: Long): Long? =
            deadlineElapsed?.let { (it - nowElapsed).coerceAtLeast(0L) }

        fun isExpired(nowElapsed: Long): Boolean =
            deadlineElapsed != null && nowElapsed >= deadlineElapsed

        companion object {
            fun start(duration: CaffeineDuration, nowElapsed: Long): Active = Active(
                duration = duration,
                startedAtElapsed = nowElapsed,
                deadlineElapsed = if (duration.isInfinite) null else nowElapsed + duration.timeoutMillis,
            )
        }
    }
}

/** Delays until a countdown label changes, plus a small margin so the tick is not early. */
object Ticks {
    private const val SECOND = 1_000L
    private const val MINUTE = 60_000L
    private const val MARGIN = 50L

    fun untilNextSecond(remainingMillis: Long): Long {
        if (remainingMillis <= 0L) return 0L
        return ((remainingMillis - 1) % SECOND) + 1 + MARGIN
    }

    fun untilNextMinuteChange(remainingMillis: Long): Long {
        if (remainingMillis <= 0L) return 0L
        return ((remainingMillis - 1) % MINUTE) + 1 + MARGIN
    }
}
