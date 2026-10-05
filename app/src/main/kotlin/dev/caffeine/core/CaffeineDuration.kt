package dev.caffeine.core

/**
 * A keep-awake duration in whole minutes. [INFINITE] means "until stopped".
 * Pure Kotlin (no Android types) so the cycling logic is unit-testable on the JVM.
 */
@JvmInline
value class CaffeineDuration(val minutes: Int) : Comparable<CaffeineDuration> {

    val isInfinite: Boolean get() = minutes == INFINITE_MINUTES

    /** Wake-lock timeout in milliseconds. Only meaningful when [isInfinite] is false. */
    val timeoutMillis: Long get() = minutes * 60_000L

    override fun compareTo(other: CaffeineDuration): Int = sortKey.compareTo(other.sortKey)

    private val sortKey: Long get() = if (isInfinite) Long.MAX_VALUE else minutes.toLong()

    override fun toString(): String = if (isInfinite) "infinite" else "${minutes}min"

    companion object {
        const val INFINITE_MINUTES = -1
        val INFINITE = CaffeineDuration(INFINITE_MINUTES)

        fun minutes(minutes: Int): CaffeineDuration {
            require(minutes > 0) { "minutes must be positive, was $minutes" }
            return CaffeineDuration(minutes)
        }

        /** Everything the user can put into the tile cycle from Settings. */
        val SELECTABLE: List<CaffeineDuration> =
            listOf(5, 10, 15, 30, 60, 120, 240).map(::minutes) + INFINITE

        /** The AOSP tile cycle: 5 min -> 10 min -> 30 min -> 1 h -> infinite -> off. */
        val DEFAULT_CYCLE: List<CaffeineDuration> =
            listOf(5, 10, 30, 60).map(::minutes) + INFINITE
    }
}
