package dev.caffeine.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import dev.caffeine.core.CaffeineController
import dev.caffeine.core.Sdk
import dev.caffeine.ui.theme.CaffeineTheme

/** Settings screen. Also the target of the tile's long-press (ACTION_QS_TILE_PREFERENCES). */
class MainActivity : ComponentActivity() {

    private var setupStarted = false

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { requestBatteryExemption() }

    private val batteryExemption = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupStarted = savedInstanceState?.getBoolean(STATE_SETUP_STARTED) == true
        enableEdgeToEdge()
        val controller = CaffeineController.get(this)
        setContent {
            CaffeineTheme {
                SettingsScreen(controller)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (isSetupIntent(intent)) beginSetup()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_SETUP_STARTED, setupStarted)
    }

    private fun beginSetup() {
        if (setupStarted) return
        setupStarted = true
        if (Sdk.isAtLeast33() && !hasNotificationPermission()) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            requestBatteryExemption()
        }
    }

    private fun hasNotificationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    /** System dialog for unrestricted battery use, which is the background-run prompt on stock Android. */
    private fun requestBatteryExemption() {
        val powerManager = getSystemService(PowerManager::class.java)
        if (powerManager.isIgnoringBatteryOptimizations(packageName)) return
        val request = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:$packageName"))
        try {
            batteryExemption.launch(request)
        } catch (_: ActivityNotFoundException) {
            // Some ROMs remove this screen. Settings is already open, so there is nothing else to show.
        }
    }

    companion object {
        const val EXTRA_SETUP = "dev.caffeine.extra.SETUP"
        private const val STATE_SETUP_STARTED = "setup_started"

        fun isSetupIntent(intent: Intent?): Boolean = intent?.getBooleanExtra(EXTRA_SETUP, false) == true
    }
}
