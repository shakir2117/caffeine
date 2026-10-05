package dev.caffeine.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.caffeine.core.CaffeineController
import dev.caffeine.ui.theme.CaffeineTheme

/** Settings screen. Also the target of the tile's long-press (ACTION_QS_TILE_PREFERENCES). */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val controller = CaffeineController.get(this)
        setContent {
            CaffeineTheme {
                SettingsScreen(controller)
            }
        }
    }
}
