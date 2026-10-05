package dev.caffeine.ui

import android.content.Context
import dev.caffeine.R
import dev.caffeine.core.CaffeineDuration
import kotlin.math.ceil

/** Human-readable durations shared by the tile, the notification and the settings screen. */
object DurationFormat {

    fun duration(context: Context, duration: CaffeineDuration): String = when {
        duration.isInfinite -> context.getString(R.string.duration_infinite)
        duration.minutes % 60 == 0 -> {
            val hours = duration.minutes / 60
            context.resources.getQuantityString(R.plurals.duration_hours, hours, hours)
        }
        else -> context.resources.getQuantityString(R.plurals.duration_minutes, duration.minutes, duration.minutes)
    }

    /** "4:59 left", or "1:05:03 left" once an hour or more is left. Rounds up so it never shows 0 early. */
    fun clock(context: Context, remainingMillis: Long): String {
        val totalSeconds = ceil(remainingMillis / 1_000.0).toLong().coerceAtLeast(0L)
        val hours = totalSeconds / 3_600
        val minutes = (totalSeconds % 3_600) / 60
        val seconds = totalSeconds % 60
        val text = if (hours > 0) {
            "%d:%02d:%02d".format(hours, minutes, seconds)
        } else {
            "%d:%02d".format(minutes, seconds)
        }
        return context.getString(R.string.remaining_clock, text)
    }

    /** "12 min left" style text; rounds *up* so the label never shows 0 while time remains. */
    fun remaining(context: Context, remainingMillis: Long): String {
        val totalMinutes = ceil(remainingMillis / 60_000.0).toInt()
        return when {
            totalMinutes < 1 -> context.getString(R.string.remaining_under_minute)
            totalMinutes < 60 -> context.resources.getQuantityString(R.plurals.remaining_minutes, totalMinutes, totalMinutes)
            else -> {
                val hours = totalMinutes / 60
                val minutes = totalMinutes % 60
                if (minutes == 0) {
                    context.resources.getQuantityString(R.plurals.remaining_hours, hours, hours)
                } else {
                    context.getString(R.string.remaining_hours_minutes, hours, minutes)
                }
            }
        }
    }
}
