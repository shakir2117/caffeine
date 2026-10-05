package dev.caffeine.core

/** Tap-cycling rules for the tile. Pure, deterministic, unit-tested. */
object DurationCycle {

    /** Sorted, de-duplicated cycle, infinite last. */
    fun normalize(cycle: Collection<CaffeineDuration>): List<CaffeineDuration> = cycle.distinct().sorted()

    /**
     * The duration the next tap switches to, or `null` for "off".
     *
     * - Off -> [default] (or the shortest enabled one if the default is not enabled).
     * - Active -> the next *longer* enabled duration, so a duration removed from the cycle
     *   while active still advances sensibly.
     * - Active at the longest -> off.
     */
    fun next(
        current: CaffeineDuration?,
        cycle: List<CaffeineDuration>,
        default: CaffeineDuration,
    ): CaffeineDuration? {
        val ordered = normalize(cycle)
        if (ordered.isEmpty()) return null
        if (current == null) return if (default in ordered) default else ordered.first()
        return ordered.firstOrNull { it > current }
    }
}
