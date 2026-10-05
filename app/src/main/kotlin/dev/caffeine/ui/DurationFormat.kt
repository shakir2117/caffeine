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
