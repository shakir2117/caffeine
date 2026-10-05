package dev.caffeine.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.caffeine.core.CaffeineController

/**
 * Fired by an inexact `ELAPSED_REALTIME_WAKEUP` alarm at the session deadline. Only a safety
 * net: if the device slept through the in-process timer, this wakes it up to reconcile.
 * Runs in the app process, so if the process is already dead there is nothing to release.
 */
class DeadlineReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        CaffeineController.get(context).reconcile()
    }
}
