@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package dev.caffeine.ui

import android.Manifest
import android.app.StatusBarManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.caffeine.R
import dev.caffeine.core.CaffeineController
import dev.caffeine.core.CaffeineDuration
import dev.caffeine.core.CaffeineSettings
import dev.caffeine.core.CaffeineState
import dev.caffeine.core.Sdk
import dev.caffeine.core.StopReason
import dev.caffeine.service.CaffeineNotifications
import dev.caffeine.tile.CaffeineTileService
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

@Composable
fun SettingsScreen(controller: CaffeineController) {
    val context = LocalContext.current
    val state by controller.state.collectAsStateWithLifecycle()
    val settings by controller.settings.collectAsStateWithLifecycle()

    var notificationsEnabled by remember { mutableStateOf(CaffeineNotifications.canPost(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        notificationsEnabled = CaffeineNotifications.canPost(context)
    }

    // Contextual POST_NOTIFICATIONS request: only when the user starts a session from here and
    // the notification would be hidden. Whatever they answer, the session starts.
    // Saved across rotation so a grant still starts the duration they picked.
    var pendingStartMinutes by rememberSaveable { mutableStateOf<Int?>(null) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        notificationsEnabled = granted
        pendingStartMinutes?.let { controller.start(CaffeineDuration(it)) }
        pendingStartMinutes = null
    }
    val startSession: (CaffeineDuration) -> Unit = { duration ->
        if (Sdk.isAtLeast33() && !hasNotificationPermission(context)) {
            pendingStartMinutes = duration.minutes
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            controller.start(duration)
        }
    }
    val updateSettings: ((CaffeineSettings) -> CaffeineSettings) -> Unit = { transform ->
        controller.updateSettings(transform)
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.app_name)) }) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            StatusCard(
                state = state,
                settings = settings,
                now = controller::now,
                onStart = { startSession(settings.defaultDuration) },
                onStop = { controller.stop(StopReason.USER) },
            )
            DurationsSection(settings, updateSettings)
            DefaultDurationSection(settings, updateSettings)
            AutoStopSection(settings, updateSettings)
            TileSection()
            if (Sdk.isAtLeast33() && !notificationsEnabled) {
                NotificationsSection(
                    onAllow = { permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) },
                    onOpenSettings = { openNotificationSettings(context) },
                )
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

// ---- sections --------------------------------------------------------------------------------

@Composable
private fun StatusCard(
    state: CaffeineState,
    settings: CaffeineSettings,
    now: () -> Long,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    val context = LocalContext.current
    val active = state as? CaffeineState.Active
    var nowElapsed by remember { mutableLongStateOf(now()) }
    LaunchedEffect(active) {
        while (active != null && !active.isIndefinite) {
            nowElapsed = now()
            delay(1_000)
        }
    }
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(if (active != null) R.string.status_active else R.string.status_off),
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                text = when {
                    active == null -> stringResource(R.string.status_off_hint)
                    active.isIndefinite -> stringResource(R.string.indefinitely)
                    else -> DurationFormat.remaining(context, active.remainingMillis(nowElapsed) ?: 0L)
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(4.dp))
            if (active == null) {
                Button(onClick = onStart) {
                    Text(stringResource(R.string.action_start_default, DurationFormat.duration(context, settings.defaultDuration)))
                }
            } else {
                OutlinedButton(onClick = onStop) { Text(stringResource(R.string.action_stop)) }
            }
        }
    }
}

@Composable
private fun DurationsSection(settings: CaffeineSettings, update: ((CaffeineSettings) -> CaffeineSettings) -> Unit) {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle(stringResource(R.string.section_durations))
        Text(stringResource(R.string.section_durations_hint), style = MaterialTheme.typography.bodyMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CaffeineDuration.SELECTABLE.forEach { duration ->
                val selected = duration in settings.enabledDurations
                val lastOne = selected && settings.enabledDurations.size == 1
                FilterChip(
                    selected = selected,
                    enabled = !lastOne,
                    onClick = {
                        update { current ->
                            val enabled = duration in current.enabledDurations
                            if (enabled && current.enabledDurations.size == 1) return@update current
                            val cycle = if (enabled) {
                                current.enabledDurations - duration
                            } else {
                                current.enabledDurations + duration
                            }
                            current.copy(enabledDurations = cycle)
                        }
                    },
                    label = { Text(DurationFormat.duration(context, duration)) },
                )
            }
        }
    }
}

@Composable
private fun DefaultDurationSection(settings: CaffeineSettings, update: ((CaffeineSettings) -> CaffeineSettings) -> Unit) {
    val context = LocalContext.current
    Column {
        SectionTitle(stringResource(R.string.section_default))
        settings.enabledDurations.forEach { duration ->
            val selected = duration == settings.defaultDuration
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(selected = selected, role = Role.RadioButton) {
                        update { it.copy(defaultDuration = duration) }
                    }
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = selected, onClick = null)
                Spacer(Modifier.width(8.dp))
                Text(DurationFormat.duration(context, duration), style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

@Composable
private fun AutoStopSection(settings: CaffeineSettings, update: ((CaffeineSettings) -> CaffeineSettings) -> Unit) {
    Column {
        SectionTitle(stringResource(R.string.section_auto_stop))
        SwitchRow(
            title = stringResource(R.string.auto_stop_screen_off),
            subtitle = stringResource(R.string.auto_stop_screen_off_hint),
            checked = settings.stopOnScreenOff,
        ) { value -> update { it.copy(stopOnScreenOff = value) } }
        SwitchRow(
            title = stringResource(R.string.auto_stop_battery_saver),
            subtitle = null,
            checked = settings.stopOnBatterySaver,
        ) { value -> update { it.copy(stopOnBatterySaver = value) } }
        SwitchRow(
            title = stringResource(R.string.auto_stop_low_battery),
            subtitle = stringResource(R.string.low_battery_threshold, settings.lowBatteryThreshold),
            checked = settings.stopOnLowBattery,
        ) { value -> update { it.copy(stopOnLowBattery = value) } }
        if (settings.stopOnLowBattery) {
            var sliderValue by remember(settings.lowBatteryThreshold) {
                mutableFloatStateOf(settings.lowBatteryThreshold.toFloat())
            }
            Slider(
                value = sliderValue,
                onValueChange = { sliderValue = it },
                onValueChangeFinished = {
                    val threshold = sliderValue.roundToInt()
                    update { it.copy(lowBatteryThreshold = threshold) }
                },
                valueRange = CaffeineSettings.MIN_LOW_BATTERY.toFloat()..CaffeineSettings.MAX_LOW_BATTERY.toFloat(),
                steps = (CaffeineSettings.MAX_LOW_BATTERY - CaffeineSettings.MIN_LOW_BATTERY) / 5 - 1,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        }
    }
}

@Composable
private fun TileSection() {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle(stringResource(R.string.section_tile))
        if (Sdk.isAtLeast33()) {
            Text(stringResource(R.string.tile_add_hint_33), style = MaterialTheme.typography.bodyMedium)
            AddTileButton()
        } else {
            // API 31-32 have no programmatic "add tile" request; explain the manual path.
            Text(stringResource(R.string.tile_add_hint_legacy), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@RequiresApi(33)
@Composable
private fun AddTileButton() {
    val context = LocalContext.current
    Button(onClick = { requestAddTile(context) }) { Text(stringResource(R.string.action_add_tile)) }
}

@Composable
private fun NotificationsSection(onAllow: () -> Unit, onOpenSettings: () -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.section_notifications), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.notifications_off_hint), style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onAllow) { Text(stringResource(R.string.action_allow_notifications)) }
                TextButton(onClick = onOpenSettings) { Text(stringResource(R.string.action_notification_settings)) }
            }
        }
    }
}

// ---- small building blocks ---------------------------------------------------------------------

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(bottom = 4.dp),
    )
}

@Composable
private fun SwitchRow(title: String, subtitle: String?, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(16.dp))
        Switch(checked = checked, onCheckedChange = null)
    }
}

// ---- platform helpers --------------------------------------------------------------------------

@RequiresApi(33)
private fun hasNotificationPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

/** Android 13+: system dialog asking the user to add our tile. Must be called from a visible Activity. */
@RequiresApi(33)
private fun requestAddTile(context: Context) {
    val statusBarManager = context.getSystemService(StatusBarManager::class.java) ?: return
    statusBarManager.requestAddTileService(
        ComponentName(context, CaffeineTileService::class.java),
        context.getString(R.string.tile_label),
        Icon.createWithResource(context, R.drawable.ic_caffeine),
        ContextCompat.getMainExecutor(context),
    ) { result ->
        val message = when (result) {
            StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED -> R.string.tile_added
            StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED -> R.string.tile_already_added
            StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED -> R.string.tile_not_added
            else -> R.string.tile_add_error
        }
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }
}

private fun openNotificationSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    launchSafely(context, intent)
}

private fun launchSafely(context: Context, intent: Intent) {
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, R.string.settings_unavailable, Toast.LENGTH_SHORT).show()
    }
}
