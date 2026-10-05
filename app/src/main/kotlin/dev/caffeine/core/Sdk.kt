package dev.caffeine.core

import android.os.Build
import androidx.annotation.ChecksSdkIntAtLeast

/**
 * The only place in the app that reads [Build.VERSION.SDK_INT].
 * Each check documents the platform behavior it gates.
 */
object Sdk {

    /**
     * Android 13: POST_NOTIFICATIONS is a runtime permission and
     * [android.app.StatusBarManager.requestAddTileService] exists.
     */
    @ChecksSdkIntAtLeast(api = Build.VERSION_CODES.TIRAMISU)
    fun isAtLeast33(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    /**
     * Android 14: `TileService.startActivityAndCollapse(Intent)` throws for apps targeting 34+;
     * the `PendingIntent` overload must be used instead.
     */
    @ChecksSdkIntAtLeast(api = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    fun isAtLeast34(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
}
