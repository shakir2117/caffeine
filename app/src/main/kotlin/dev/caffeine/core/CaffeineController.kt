package dev.caffeine.core

import android.app.Application
import android.app.ForegroundServiceStartNotAllowedException
import android.content.Context
import android.content.Context.MODE_PRIVATE
import android.os.SystemClock
import android.util.Log
import dev.caffeine.data.SettingsRepository
import dev.caffeine.service.CaffeineService
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Process-wide single source of truth shared by the tile, the foreground service and the UI.
 *
 * Threading: every mutating call happens on the main thread (TileService callbacks, Service
 * callbacks, Compose click handlers and BroadcastReceivers all run there), so state changes
 * are naturally serialized. [state] is the in-memory truth; DataStore is only a mirror used
 * to let a system-restarted service resume, never to decide what the tile shows.
 *
 * Why in-memory truth is correct across process death: the wake lock lives in this process.
 * If the process dies the screen is no longer kept awake, so a fresh process correctly starts
 * as [CaffeineState.Off]. The only path back to Active is the service's START_STICKY restart,
 * which calls [restoreAfterProcessDeath] and re-acquires the lock.
 */
class CaffeineController private constructor(private val app: Application) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val repository = SettingsRepository(app)

    /** Eagerly shared so a tile tap never has to wait for disk. Loaded in Application.onCreate. */
    val settings: StateFlow<CaffeineSettings> =
        repository.settings.stateIn(scope, SharingStarted.Eagerly, CaffeineSettings.DEFAULT)

    private val _state = MutableStateFlow<CaffeineState>(CaffeineState.Off)
    val state: StateFlow<CaffeineState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<CaffeineEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<CaffeineEvent> = _events.asSharedFlow()

    @Volatile
    var isServiceAlive: Boolean = false
        private set

    /** Set between startForegroundService() and the service's onCreate() to coalesce rapid taps. */
    private var startRequested = false

    /**
     * True after this process's service has called stopSelf() and before a later start command
     * or a fresh instance clears it. A tile tap in that window must start the service again;
     * the previous instance is going away and will not keep the new session.
     */
    private var serviceStopIssued = false

    /** Bumped in [onServiceCreated]. A destroyed instance only counts if it is still current. */
    private var serviceGeneration = 0

    /** Monotonic clock for all timer math. Wall-clock changes never affect sessions. */
    fun now(): Long = SystemClock.elapsedRealtime()

    /**
     * True until the user has tapped the tile once. That first tap opens settings so they can
     * answer the notification and battery prompts; it does not start a session.
     */
    fun isIntroPending(): Boolean =
        !app.getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_INTRO_DONE, false)

    fun markIntroDone() {
        app.getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_INTRO_DONE, true).commit()
    }

    /** Tile tap: Off -> default -> next longer -> ... -> Off. */
    fun toggleFromTile(): CaffeineState = apply(CaffeineLogic.nextOnTap(_state.value, settings.value, now()))

    fun start(duration: CaffeineDuration): CaffeineState = apply(CaffeineState.Active.start(duration, now()))

    fun stop(reason: StopReason) {
        if (_state.value is CaffeineState.Off) return
        Log.i(TAG, "stop: $reason")
        apply(CaffeineState.Off)
    }

    /**
     * Re-checks a timed session against the monotonic clock. Called whenever the device may
     * have slept through a timer (screen on, deadline alarm, tile starts listening).
     */
    fun reconcile() {
        val active = _state.value as? CaffeineState.Active ?: return
        if (active.isExpired(now())) stop(StopReason.EXPIRED)
    }

    private fun apply(next: CaffeineState): CaffeineState {
        _state.value = next
        persist(next)
        if (next is CaffeineState.Active && serviceNeeded()) {
            startRequested = true
            if (!launchService()) {
                startRequested = false
                _state.value = CaffeineState.Off
                persist(CaffeineState.Off)
                _events.tryEmit(CaffeineEvent.StartNotAllowed)
            }
        }
        return _state.value
    }

    /** Start when nothing is running, or when the running instance has already asked to stop. */
    private fun serviceNeeded(): Boolean = !startRequested && (!isServiceAlive || serviceStopIssued)

    fun updateSettings(transform: (CaffeineSettings) -> CaffeineSettings) {
        scope.launch { repository.update(transform) }
    }

    /**
     * Starting an FGS from TileService.onClick works on stock Android 12+ because SystemUI binds
     * the tile with foreground-service-level importance while the panel is open, so the app is
     * not "in the background" for the purposes of the Android 12 restriction. It is not on the
     * documented exemption list, so the failure is handled explicitly: the tile shows a toast and
     * opens the settings screen, from which a visible Activity can always start the service.
     */
    private fun launchService(): Boolean = try {
        app.startForegroundService(CaffeineService.startIntent(app))
        true
    } catch (e: ForegroundServiceStartNotAllowedException) {
        Log.w(TAG, "Foreground service start not allowed from this context", e)
        false
    } catch (e: IllegalStateException) {
        Log.w(TAG, "Foreground service start rejected", e)
        false
    } catch (e: SecurityException) {
        Log.w(TAG, "Foreground service start denied", e)
        false
    }

    // ---- Service lifecycle hooks -------------------------------------------------------------

    internal fun onServiceCreated(): Int {
        serviceGeneration += 1
        isServiceAlive = true
        startRequested = false
        serviceStopIssued = false
        return serviceGeneration
    }

    /** A start command arrived, including one that replaced a service already stopping. */
    internal fun onServiceStartCommand() {
        isServiceAlive = true
        startRequested = false
        serviceStopIssued = false
    }

    internal fun onServiceStopIssued() {
        serviceStopIssued = true
    }

    /** The service could not call startForeground(); it is about to stop itself. */
    internal fun onServiceStartDenied() {
        Log.w(TAG, "startForeground rejected; marking Off")
        _state.value = CaffeineState.Off
        persist(CaffeineState.Off)
        _events.tryEmit(CaffeineEvent.StartNotAllowed)
    }

    internal fun onServiceDestroyed(generation: Int) {
        if (generation != serviceGeneration) return
        isServiceAlive = false
        if (startRequested) return
        serviceStopIssued = false
        if (_state.value is CaffeineState.Active) {
            // Task Manager "Stop" or any system stop: the wake lock is gone, so the session is Off.
            Log.w(TAG, "Service destroyed while active: ${StopReason.SERVICE_LOST}")
            _state.value = CaffeineState.Off
            persist(CaffeineState.Off)
        }
    }

    /**
     * Called by the service when the system restarted it (START_STICKY, null intent) in a fresh
     * process. Resumes the persisted session only if it belongs to this boot and has time left.
     * Reboot is therefore never resumed (by design); a session that expired while the process
     * was dead is dropped.
     */
    internal suspend fun restoreAfterProcessDeath(): CaffeineState {
        if (_state.value !is CaffeineState.Off) return _state.value
        val session = repository.readSession() ?: return CaffeineState.Off
        val sameBoot = abs(session.bootBase - bootBase()) < BOOT_BASE_TOLERANCE_MS
        if (!sameBoot || session.state.isExpired(now())) {
            persist(CaffeineState.Off)
            return CaffeineState.Off
        }
        Log.i(TAG, "Resuming session after process death: ${session.state}")
        _state.value = session.state
        return session.state
    }

    private fun bootBase(): Long = System.currentTimeMillis() - now()

    private fun persist(state: CaffeineState) {
        val bootBase = bootBase()
        scope.launch { repository.writeSession(state, bootBase) }
    }

    companion object {
        private const val TAG = "CaffeineController"
        private const val PREFS = "caffeine_flags"
        private const val KEY_INTRO_DONE = "intro_done"

        /** Wall-clock drift tolerated when deciding whether persisted elapsed timestamps belong to this boot. */
        private const val BOOT_BASE_TOLERANCE_MS = 2 * 60_000L

        @Volatile
        private var instance: CaffeineController? = null

        fun get(context: Context): CaffeineController =
            instance ?: synchronized(this) {
                instance ?: CaffeineController(context.applicationContext as Application).also { instance = it }
            }
    }
}
