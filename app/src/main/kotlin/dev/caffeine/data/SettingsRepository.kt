package dev.caffeine.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.caffeine.core.CaffeineDuration
import dev.caffeine.core.CaffeineSettings
import dev.caffeine.core.CaffeineState
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "caffeine")

/** An active session as last written to disk, plus the boot it belongs to. */
class PersistedSession(val state: CaffeineState.Active, val bootBase: Long)

/**
 * Jetpack DataStore wrapper. Holds user settings and the last known session so a
 * system-restarted service can resume. `allowBackup=false` keeps all of it off cloud backup.
 */
class SettingsRepository(context: Context) {

    private val store = context.applicationContext.dataStore

    val settings: Flow<CaffeineSettings> = store.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { it.toSettings() }

    suspend fun update(transform: (CaffeineSettings) -> CaffeineSettings) {
        store.edit { prefs -> prefs.write(transform(prefs.toSettings()).normalized()) }
    }

    /**
     * @param bootBase `currentTimeMillis - elapsedRealtime`; identifies the boot the elapsed
     *   timestamps belong to, so a session is never resumed across a reboot.
     */
    suspend fun writeSession(state: CaffeineState, bootBase: Long) {
        store.edit { prefs ->
            when (state) {
                CaffeineState.Off -> {
                    prefs.remove(Keys.SESSION_ACTIVE)
                    prefs.remove(Keys.SESSION_DURATION)
                    prefs.remove(Keys.SESSION_STARTED)
                    prefs.remove(Keys.SESSION_DEADLINE)
                    prefs.remove(Keys.SESSION_BOOT_BASE)
                }
                is CaffeineState.Active -> {
                    prefs[Keys.SESSION_ACTIVE] = true
                    prefs[Keys.SESSION_DURATION] = state.duration.minutes
                    prefs[Keys.SESSION_STARTED] = state.startedAtElapsed
                    val deadline = state.deadlineElapsed
                    if (deadline == null) prefs.remove(Keys.SESSION_DEADLINE) else prefs[Keys.SESSION_DEADLINE] = deadline
                    prefs[Keys.SESSION_BOOT_BASE] = bootBase
                }
            }
        }
    }

    suspend fun readSession(): PersistedSession? {
        val prefs = store.data.first()
        if (prefs[Keys.SESSION_ACTIVE] != true) return null
        val duration = prefs[Keys.SESSION_DURATION]?.let { CaffeineDuration(it) } ?: return null
        val started = prefs[Keys.SESSION_STARTED] ?: return null
        val bootBase = prefs[Keys.SESSION_BOOT_BASE] ?: return null
        return PersistedSession(
            state = CaffeineState.Active(duration, started, prefs[Keys.SESSION_DEADLINE]),
            bootBase = bootBase,
        )
    }

    private object Keys {
        val ENABLED_DURATIONS = stringSetPreferencesKey("enabled_durations")
        val DEFAULT_DURATION = intPreferencesKey("default_duration")
        val STOP_ON_SCREEN_OFF = booleanPreferencesKey("stop_on_screen_off")
        val STOP_ON_BATTERY_SAVER = booleanPreferencesKey("stop_on_battery_saver")
        val STOP_ON_LOW_BATTERY = booleanPreferencesKey("stop_on_low_battery")
        val LOW_BATTERY_THRESHOLD = intPreferencesKey("low_battery_threshold")

        val SESSION_ACTIVE = booleanPreferencesKey("session_active")
        val SESSION_DURATION = intPreferencesKey("session_duration")
        val SESSION_STARTED = longPreferencesKey("session_started_elapsed")
        val SESSION_DEADLINE = longPreferencesKey("session_deadline_elapsed")
        val SESSION_BOOT_BASE = longPreferencesKey("session_boot_base")
    }

    private fun Preferences.toSettings(): CaffeineSettings {
        val defaults = CaffeineSettings.DEFAULT
        return CaffeineSettings(
            enabledDurations = this[Keys.ENABLED_DURATIONS]
                ?.mapNotNull { it.toIntOrNull() }
                ?.map { CaffeineDuration(it) }
                ?: defaults.enabledDurations,
            defaultDuration = this[Keys.DEFAULT_DURATION]?.let { CaffeineDuration(it) } ?: defaults.defaultDuration,
            stopOnScreenOff = this[Keys.STOP_ON_SCREEN_OFF] ?: defaults.stopOnScreenOff,
            stopOnBatterySaver = this[Keys.STOP_ON_BATTERY_SAVER] ?: defaults.stopOnBatterySaver,
            stopOnLowBattery = this[Keys.STOP_ON_LOW_BATTERY] ?: defaults.stopOnLowBattery,
            lowBatteryThreshold = this[Keys.LOW_BATTERY_THRESHOLD] ?: defaults.lowBatteryThreshold,
        ).normalized()
    }

    private fun MutablePreferences.write(settings: CaffeineSettings) {
        this[Keys.ENABLED_DURATIONS] = settings.enabledDurations.map { it.minutes.toString() }.toSet()
        this[Keys.DEFAULT_DURATION] = settings.defaultDuration.minutes
        this[Keys.STOP_ON_SCREEN_OFF] = settings.stopOnScreenOff
        this[Keys.STOP_ON_BATTERY_SAVER] = settings.stopOnBatterySaver
        this[Keys.STOP_ON_LOW_BATTERY] = settings.stopOnLowBattery
        this[Keys.LOW_BATTERY_THRESHOLD] = settings.lowBatteryThreshold
    }
}
