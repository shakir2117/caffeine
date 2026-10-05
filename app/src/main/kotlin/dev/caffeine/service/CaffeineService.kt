package dev.caffeine.service

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat
import dev.caffeine.core.CaffeineController
import dev.caffeine.core.CaffeineSettings
import dev.caffeine.core.CaffeineState
import dev.caffeine.core.Sdk
import dev.caffeine.core.StopReason
import dev.caffeine.core.Ticks
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Foreground service that owns the screen wake lock.
 *
 * ## Why a screen wake lock in a foreground service
 * `PowerManager.SCREEN_BRIGHT_WAKE_LOCK` is deprecated in favour of `FLAG_KEEP_SCREEN_ON`, but
 * it still works for normal apps holding `WAKE_LOCK`, it is exactly what the AOSP/Lineage
 * Caffeine tile uses, and it is the only mechanism that keeps the screen on *regardless of which
 * app is in front*. Alternatives evaluated and rejected:
 *  - `WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON` on our own Activity: only works while our
 *    Activity is resumed; useless for a "keep awake while I read something else" tile.
 *  - `FLAG_KEEP_SCREEN_ON` on a 1x1 `TYPE_APPLICATION_OVERLAY` window: needs `SYSTEM_ALERT_WINDOW`
 *    (a special-access permission the user must grant in Settings, scary for a utility this
 *    small, and Play restricts it), overlays are hidden by apps using `HIDE_OVERLAY_WINDOWS`,
 *    Android 12 blocks touch pass-through for untrusted overlays, and some ROMs disable overlays
 *    on the lock screen. Strictly worse than the wake lock for this job.
 *  - `Settings.System.SCREEN_OFF_TIMEOUT`: needs `WRITE_SETTINGS` (special access), changes a
 *    global user setting, and leaves it changed if the process dies. Rejected.
 *  - `PARTIAL_WAKE_LOCK`: keeps the CPU on, not the screen. Wrong tool (and it is the kind that
 *    Android vitals reports as "excessive wake locks"; screen wake locks are not).
 *
 * The foreground service exists so the process keeps a high priority for the whole session,
 * so the wake lock has a definite owner whose `onDestroy` releases it, and so the user has a
 * visible, system-enforced way (notification, FGS Task Manager) to see and stop it.
 *
 * ## Why `foregroundServiceType="specialUse"`
 * Android 14 requires a type and checks it against the manifest. Of the available types none
 * describes "keep the screen on": `mediaPlayback` would be a lie (and Android 17 ties media
 * types to audio behaviour), `dataSync`/`mediaProcessing` get 6-hour timeouts on Android 15+,
 * `shortService` is limited to ~3 minutes, `systemExempted` is for system apps only.
 * `specialUse` has no timeout, needs only the normal `FOREGROUND_SERVICE_SPECIAL_USE`
 * permission, and the mandatory `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` lets us state the purpose
 * in the manifest (Play Console asks for exactly that during review; de-Googled ROMs ignore it).
 *
 * ## Timer semantics
 * The deadline is `elapsedRealtime`-based (monotonic). Three mechanisms converge on it:
 *  1. `WakeLock.acquire(timeout)`: the lock auto-releases even if our coroutine is late.
 *  2. An in-process coroutine that updates the notification once a minute and stops at 0.
 *  3. An inexact `setAndAllowWhileIdle` alarm plus `ACTION_SCREEN_ON`, both of which call
 *     `controller.reconcile()`. These cover the case where the user turned the screen off
 *     without "stop on screen off" and the device dozed: the `uptimeMillis`-based handler delays
 *     used by (1) and (2) do not advance in deep sleep, but the `elapsedRealtime` deadline does.
 */
class CaffeineService : Service() {

    private val controller: CaffeineController by lazy { CaffeineController.get(this) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var powerManager: PowerManager
    private lateinit var alarmManager: AlarmManager
    private lateinit var wakeLock: PowerManager.WakeLock

    private var sessionJob: Job? = null
    private var observing = false
    private var receiverRegistered = false
    private var generation = 0
    private var lastStartId = 0
    private var pendingStop: Runnable? = null

    private val conditionsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val settings = controller.settings.value
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF ->
                    if (settings.stopOnScreenOff) controller.stop(StopReason.SCREEN_OFF)
                Intent.ACTION_SCREEN_ON -> controller.reconcile()
                PowerManager.ACTION_POWER_SAVE_MODE_CHANGED ->
                    if (settings.stopOnBatterySaver && powerManager.isPowerSaveMode) {
                        controller.stop(StopReason.BATTERY_SAVER)
                    }
                Intent.ACTION_BATTERY_CHANGED -> evaluateBattery(intent, settings)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @Suppress("DEPRECATION") // SCREEN_BRIGHT_WAKE_LOCK: see the class comment.
    override fun onCreate() {
        super.onCreate()
        powerManager = getSystemService(PowerManager::class.java)
        alarmManager = getSystemService(AlarmManager::class.java)
        wakeLock = powerManager
            .newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ON_AFTER_RELEASE, WAKE_LOCK_TAG)
            .apply { setReferenceCounted(false) }
        generation = controller.onServiceCreated()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        controller.onServiceStartCommand()
        cancelPendingStop()
        // Always promote to foreground first; Android 12+ ANRs an app whose service started via
        // startForegroundService() does not call startForeground() promptly.
        if (!goForeground()) {
            controller.onServiceStartDenied()
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_STOP) controller.stop(StopReason.USER)

        if (!observing) {
            observing = true
            val systemRestart = intent == null // START_STICKY redelivery after the process was killed
            scope.launch {
                if (systemRestart) controller.restoreAfterProcessDeath()
                controller.state.collect { onStateChanged(it) }
            }
        }
        // Sticky so a system kill (memory pressure) restarts us and we can resume the session.
        // Whether startForeground() is allowed on that restart on Android 12+ is handled above:
        // if it is rejected we clean up and report Off rather than crash.
        return START_STICKY
    }

    override fun onDestroy() {
        cancelPendingStop()
        releaseResources()
        scope.cancel()
        controller.onServiceDestroyed(generation)
        super.onDestroy()
    }

    // ---- state -----------------------------------------------------------------------------

    private fun onStateChanged(state: CaffeineState) {
        when (state) {
            CaffeineState.Off -> shutDown()
            is CaffeineState.Active -> applyActive(state)
        }
    }

    private fun applyActive(state: CaffeineState.Active) {
        // A stop posted on the previous Off must not run after this session has started.
        cancelPendingStop()
        val remaining = state.remainingMillis(controller.now())
        if (remaining != null && remaining <= 0L) {
            controller.stop(StopReason.EXPIRED)
            return
        }
        // Release before re-acquiring: a timed acquire() posts a delayed auto-release that would
        // otherwise still fire after the user extended the session.
        if (wakeLock.isHeld) wakeLock.release()
        if (remaining == null) acquireIndefinitely() else wakeLock.acquire(remaining)

        registerReceiver()
        scheduleDeadlineAlarm(state)
        if (stopIfConditionAlreadyMet()) return
        startTicker(state)
    }

    @SuppressLint("WakelockTimeout") // "Indefinitely" is an explicit user choice; released on stop/destroy.
    private fun acquireIndefinitely() = wakeLock.acquire()

    private fun startTicker(state: CaffeineState.Active) {
        sessionJob?.cancel()
        sessionJob = scope.launch {
            while (isActive) {
                val remaining = state.remainingMillis(controller.now())
                if (remaining == null) {
                    goForeground() // indefinite: one notification refresh, nothing to count down
                    return@launch
                }
                if (remaining <= 0L) {
                    controller.stop(StopReason.EXPIRED)
                    return@launch
                }
                goForeground() // refreshes the "N min left" text
                delay(Ticks.untilNextMinuteChange(remaining))
            }
        }
    }

    private fun shutDown() {
        releaseResources()
        cancelPendingStop()
        // stopSelf() is delivered later. Wait one loop turn so an Off -> Active tap in the
        // same frame can cancel it. stopSelf(startId) then ignores this stop if a newer
        // start command already arrived.
        val token = lastStartId
        val runnable = Runnable {
            pendingStop = null
            if (controller.state.value is CaffeineState.Active) return@Runnable
            stopForeground(STOP_FOREGROUND_REMOVE)
            controller.onServiceStopIssued()
            stopSelf(token)
        }
        pendingStop = runnable
        mainHandler.post(runnable)
    }

    private fun cancelPendingStop() {
        pendingStop?.let { mainHandler.removeCallbacks(it) }
        pendingStop = null
    }

    /** Idempotent: safe to call from both the Off transition and onDestroy. */
    private fun releaseResources() {
        sessionJob?.cancel()
        sessionJob = null
        if (::wakeLock.isInitialized && wakeLock.isHeld) wakeLock.release()
        if (receiverRegistered) {
            unregisterReceiver(conditionsReceiver)
            receiverRegistered = false
        }
        if (::alarmManager.isInitialized) alarmManager.cancel(deadlinePendingIntent())
    }

    // ---- foreground --------------------------------------------------------------------------

    /**
     * Also used to *update* the notification: re-calling startForeground() on an already
     * foreground service is allowed in every app state and sidesteps the POST_NOTIFICATIONS
     * check that NotificationManager.notify() would need.
     */
    private fun goForeground(): Boolean {
        val notification = CaffeineNotifications.build(
            this,
            controller.state.value as? CaffeineState.Active,
            controller.now(),
        )
        return try {
            if (Sdk.isAtLeast34()) {
                // MANIFEST means "the types declared in the manifest" (specialUse).
                // The constant is API 34; passing it on 31–33 throws IllegalArgumentException.
                startForeground(CaffeineNotifications.ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST)
            } else {
                startForeground(CaffeineNotifications.ID, notification)
            }
            true
        } catch (e: IllegalStateException) {
            // ForegroundServiceStartNotAllowedException (12+), MissingForegroundServiceTypeException
            // and InvalidForegroundServiceTypeException (14+) all extend IllegalStateException.
            Log.e(TAG, "startForeground rejected", e)
            false
        } catch (e: SecurityException) {
            // Android 14+: missing type-specific permission.
            Log.e(TAG, "startForeground denied", e)
            false
        }
    }

    // ---- auto-stop conditions ----------------------------------------------------------------

    private fun registerReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
        }
        // All four are protected system broadcasts, which are still delivered to NOT_EXPORTED
        // receivers (Android 13+ requires one of the two flags for non-system actions).
        val sticky = ContextCompat.registerReceiver(this, conditionsReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        receiverRegistered = true
        if (sticky != null) evaluateBattery(sticky, controller.settings.value)
    }

    private fun stopIfConditionAlreadyMet(): Boolean {
        val settings = controller.settings.value
        if (settings.stopOnBatterySaver && powerManager.isPowerSaveMode) {
            controller.stop(StopReason.BATTERY_SAVER)
            return true
        }
        return controller.state.value is CaffeineState.Off
    }

    private fun evaluateBattery(intent: Intent, settings: CaffeineSettings) {
        if (!settings.stopOnLowBattery) return
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        val percent = level * 100 / scale
        if (!charging && percent <= settings.lowBatteryThreshold) controller.stop(StopReason.LOW_BATTERY)
    }

    // ---- deadline alarm ----------------------------------------------------------------------

    private fun scheduleDeadlineAlarm(state: CaffeineState.Active) {
        val pendingIntent = deadlinePendingIntent()
        alarmManager.cancel(pendingIntent)
        val deadline = state.deadlineElapsed ?: return
        // Inexact on purpose: no SCHEDULE_EXACT_ALARM / USE_EXACT_ALARM permission needed. It may
        // fire minutes late in Doze, which only matters if the screen is already off anyway.
        alarmManager.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, deadline, pendingIntent)
    }

    private fun deadlinePendingIntent(): PendingIntent = PendingIntent.getBroadcast(
        this,
        0,
        Intent(this, DeadlineReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        private const val TAG = "CaffeineService"
        private const val WAKE_LOCK_TAG = "Caffeine:screen"
        const val ACTION_STOP = "dev.caffeine.action.STOP"

        fun startIntent(context: Context): Intent = Intent(context, CaffeineService::class.java)
        fun stopIntent(context: Context): Intent = Intent(context, CaffeineService::class.java).setAction(ACTION_STOP)
    }
}
