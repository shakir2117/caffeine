package dev.caffeine.tile

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Handler
import android.os.Looper
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import android.widget.Toast
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import dev.caffeine.R
import dev.caffeine.core.CaffeineController
import dev.caffeine.core.CaffeineEvent
import dev.caffeine.core.CaffeineState
import dev.caffeine.core.Sdk
import dev.caffeine.core.Ticks
import dev.caffeine.ui.DurationFormat
import dev.caffeine.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Quick Settings tile. Holds no state of its own: it renders [CaffeineController.state] and
 * forwards taps. Long-press is handled by the ACTION_QS_TILE_PREFERENCES intent filter on
 * MainActivity, so there is no code for it here.
 */
class CaffeineTileService : TileService() {

    private val controller: CaffeineController by lazy { CaffeineController.get(this) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val ticker = Executors.newSingleThreadScheduledExecutor()
    private var listenJob: Job? = null
    private var tick: ScheduledFuture<*>? = null
    private var listening = false

    override fun onTileAdded() {
        super.onTileAdded()
        render(controller.state.value)
    }

    override fun onStartListening() {
        super.onStartListening()
        listening = true
        controller.reconcile() // the device may have slept past the deadline
        listenJob?.cancel()
        listenJob = scope.launch {
            controller.events.collect { event ->
                if (event is CaffeineEvent.StartNotAllowed) onStartNotAllowed()
            }
        }
        updateTile()
    }

    override fun onStopListening() {
        listening = false
        tick?.cancel(false)
        tick = null
        listenJob?.cancel()
        listenJob = null
        super.onStopListening()
    }

    override fun onDestroy() {
        listening = false
        tick?.cancel(false)
        tick = null
        ticker.shutdownNow()
        scope.cancel()
        super.onDestroy()
    }

    override fun onClick() {
        super.onClick()
        if (controller.isIntroPending()) {
            openSettingsAndCollapse(setup = true)
            controller.markIntroDone()
            return
        }
        // Works on the lock screen too, like the AOSP tile: nothing sensitive is exposed.
        val next = controller.toggleFromTile()
        Log.i(TAG, "tap -> $next")
        // Paint the new minutes before this click returns. The next paint waits until
        // that minute label changes, so the tile never shows a seconds clock.
        updateTile()
    }

    /**
     * Paint the live session. A timed session is painted again when "5 min left" becomes
     * "4 min left", not every second.
     */
    private fun updateTile() {
        val state = controller.state.value
        render(state)
        tick?.cancel(false)
        tick = null
        if (!listening) return
        val remaining = (state as? CaffeineState.Active)?.remainingMillis(controller.now()) ?: return
        if (remaining <= 0L) {
            controller.reconcile()
            render(controller.state.value)
            return
        }
        tick = ticker.schedule({
            mainHandler.post { if (listening) updateTile() }
        }, Ticks.untilNextMinuteChange(remaining), TimeUnit.MILLISECONDS)
    }

    private fun render(state: CaffeineState) {
        val tile = qsTile ?: return
        val name = getString(R.string.tile_label)
        tile.icon = Icon.createWithResource(this, R.drawable.ic_caffeine)
        when (state) {
            CaffeineState.Off -> {
                tile.state = Tile.STATE_INACTIVE
                tile.label = name
                tile.subtitle = getString(R.string.tile_off)
                tile.stateDescription = getString(R.string.tile_off)
                tile.contentDescription = name
            }
            is CaffeineState.Active -> {
                val remaining = state.remainingMillis(controller.now())
                if (remaining == null) {
                    val status = getString(R.string.indefinitely)
                    tile.state = Tile.STATE_ACTIVE
                    tile.label = status
                    tile.subtitle = null
                    tile.stateDescription = status
                    tile.contentDescription = "$name, $status"
                } else {
                    val title = DurationFormat.remaining(this, remaining)
                    tile.state = Tile.STATE_ACTIVE
                    tile.label = title
                    tile.subtitle = null
                    tile.stateDescription = title
                    tile.contentDescription = "$name, $title"
                }
            }
        }
        tile.updateTile()
    }

    private fun onStartNotAllowed() {
        Toast.makeText(this, R.string.toast_start_failed, Toast.LENGTH_LONG).show()
        openSettingsAndCollapse(setup = false)
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openSettingsAndCollapse(setup: Boolean) {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(MainActivity.EXTRA_SETUP, setup)
        if (Sdk.isAtLeast34()) {
            val pendingIntent = PendingIntent.getActivity(
                this,
                if (setup) REQUEST_SETUP else REQUEST_SETTINGS,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            startActivityAndCollapse(pendingIntent)
        } else {
            @Suppress("DEPRECATION") // Intent overload is the only one on API 31-33.
            startActivityAndCollapse(intent)
        }
    }

    companion object {
        private const val TAG = "CaffeineTile"
        private const val REQUEST_SETTINGS = 1
        private const val REQUEST_SETUP = 2
    }
}
