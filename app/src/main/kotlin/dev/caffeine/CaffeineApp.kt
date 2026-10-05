package dev.caffeine

import android.app.Application
import dev.caffeine.core.CaffeineController
import dev.caffeine.service.CaffeineNotifications

class CaffeineApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CaffeineNotifications.ensureChannel(this)
        // Warm the controller so settings are loaded from DataStore long before the first tile tap.
        CaffeineController.get(this)
    }
}
