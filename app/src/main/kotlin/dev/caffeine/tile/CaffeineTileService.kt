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

    override fun onTileAdded() {
        super.onTileAdded()
        render(controller.state.value)
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
                render(state)
                val active = state as? CaffeineState.Active ?: return@collectLatest
                while (true) {
                    val remaining = active.remainingMillis(controller.now()) ?: return@collectLatest
                    delay(Ticks.untilNextMinuteChange(remaining))
                    controller.reconcile()
                    render(controller.state.value)
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
        // Works on the lock screen too, like the AOSP tile: nothing sensitive is exposed.
        render(controller.toggleFromTile())
    }

    private fun render(state: CaffeineState) {
        val tile = qsTile ?: return
        val label = getString(R.string.tile_label)
        tile.label = label
        tile.icon = Icon.createWithResource(this, R.drawable.ic_caffeine)
        when (state) {
            CaffeineState.Off -> {
                tile.state = Tile.STATE_INACTIVE
                tile.subtitle = getString(R.string.tile_off)
                tile.stateDescription = getString(R.string.tile_off)
            }
            is CaffeineState.Active -> {
                val remaining = state.remainingMillis(controller.now())
                val subtitle = if (remaining == null) getString(R.string.indefinitely) else DurationFormat.remaining(this, remaining)
                tile.state = Tile.STATE_ACTIVE
                tile.subtitle = subtitle
                tile.stateDescription = subtitle
            }
        }
        tile.contentDescription = label
        tile.updateTile()
    }

    private fun onStartNotAllowed() {
        Toast.makeText(this, R.string.toast_start_failed, Toast.LENGTH_LONG).show()
        openSettingsAndCollapse()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openSettingsAndCollapse() {
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Sdk.isAtLeast34()) {
            val pendingIntent = PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            startActivityAndCollapse(pendingIntent)
        } else {
            @Suppress("DEPRECATION") // Intent overload is the only one on API 31-33.
            startActivityAndCollapse(intent)
        }
    }
}
