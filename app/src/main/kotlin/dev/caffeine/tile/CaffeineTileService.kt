package dev.caffeine.tile

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Quick Settings tile. Holds no state of its own: it renders [CaffeineController.state] and
 * forwards taps. Long-press is handled by the ACTION_QS_TILE_PREFERENCES intent filter on
 * MainActivity, so there is no code for it here.
 */
class CaffeineTileService : TileService() {

    private val controller: CaffeineController by lazy { CaffeineController.get(this) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var listenJob: Job? = null
    private var publishJob: Job? = null
    private var latestState: CaffeineState = CaffeineState.Off

    override fun onTileAdded() {
        super.onTileAdded()
        publish(controller.state.value)
    }

    override fun onStartListening() {
        super.onStartListening()
        controller.reconcile() // the device may have slept past the deadline
        listenJob?.cancel()
        listenJob = scope.launch {
            launch {
                controller.events.collect { event ->
                    if (event is CaffeineEvent.StartNotAllowed) onStartNotAllowed()
                }
            }
            // collectLatest cancels the countdown loop whenever the state changes, so there is
            // exactly one ticker per visible timed session and none once the panel closes.
            controller.state.collectLatest { state ->
                publish(state)
                val active = state as? CaffeineState.Active ?: return@collectLatest
                while (true) {
                    val remaining = active.remainingMillis(controller.now()) ?: return@collectLatest
                    delay(Ticks.untilNextMinuteChange(remaining))
                    controller.reconcile()
                    publish(controller.state.value)
                }
            }
        }
    }

    override fun onStopListening() {
        listenJob?.cancel()
        listenJob = null
        super.onStopListening()
    }

    override fun onDestroy() {
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
        controller.toggleFromTile()
        publish(controller.state.value)
    }

    /**
     * SystemUI often keeps the first subtitle when [Tile.updateTile] is called again while the
     * tile stays active, which is exactly a second tap that lengthens the session. Push the
     * latest state immediately, then once more after a short delay so the second tap lands.
     */
    private fun publish(state: CaffeineState) {
        latestState = state
        render(state)
        publishJob?.cancel()
        publishJob = scope.launch {
            delay(TILE_REFRESH_DELAY_MS)
            render(latestState)
        }
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
                val status = if (remaining == null) {
                    getString(R.string.indefinitely)
                } else {
                    DurationFormat.remaining(this, remaining)
                }
                // The label has to change too. A subtitle-only update is what SystemUI drops.
                tile.state = Tile.STATE_ACTIVE
                tile.label = status
                tile.subtitle = status
                tile.stateDescription = status
                tile.contentDescription = "$name, $status"
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
        private const val TILE_REFRESH_DELAY_MS = 150L
        private const val REQUEST_SETTINGS = 1
        private const val REQUEST_SETUP = 2
    }
}
