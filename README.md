# Caffeine

A Quick Settings tile that keeps the screen awake for a duration you choose. It follows the
AOSP/LineageOS Caffeine tile: tap to cycle durations, long-press for settings.

Kotlin, Compose, Material 3, DataStore. No network, no analytics, no Play Services.
Android 12 (API 31) through Android 17 (API 37).

The app has **no launcher icon**. Add the tile from the Quick Settings editor (the pencil).
Long-press the tile to open settings. On Android 13 and newer, settings also has an Add tile button.

## Build

JDK 17. The Gradle wrapper is included (`gradle/wrapper`, Gradle 9.6.0). Versions live in
`gradle/libs.versions.toml`: AGP 9.4.0 with built-in Kotlin 2.4.20, Compose BOM 2026.09.00.

```
./gradlew :app:lintRelease :app:testDebugUnitTest :app:assembleRelease
```

`minSdk` 31, `compileSdk` / `targetSdk` 37. Release builds use R8 and resource shrinking.
`local.properties` is machine-specific and is not in git.

## What a session does

The first tap starts the default duration (5 minutes). Each later tap moves to the next *longer*
enabled duration, then off. Shorter durations are used only if you lower the default in settings.

| Duration in the default cycle | Timeout |
|---|---|
| 5 min, 10 min, 30 min, 1 hour | Wake lock acquired for that long |
| Indefinitely | Wake lock with no timeout, released when you stop |

Settings can also enable 15 minutes, 2 hours, and 4 hours. The last enabled duration cannot be turned off.

Auto-stop defaults: screen off **on** (power button ends the session), Battery Saver **off**,
low battery **off**. Low battery only triggers while the device is not charging.

A reboot never resumes a session. A `START_STICKY` restart after a low-memory kill does resume
when the deadline is still in the future and belongs to the same boot.

## Permissions

Install grants only normal permissions plus the Android 13 notification prompt:

| Permission | Why |
|---|---|
| `WAKE_LOCK` | Hold a screen wake lock. Normal permission, granted at install. |
| `FOREGROUND_SERVICE` | Own that lock inside a foreground service. Normal permission. |
| `FOREGROUND_SERVICE_SPECIAL_USE` | The Android 14 type for this service. Normal permission. |
| `POST_NOTIFICATIONS` | Optional. Asked only when you start a session from settings. Deny it and the session still starts; the shade countdown is hidden. |

The app does **not** request a background-run permission, battery-optimization exemption,
exact alarms, overlay, or boot receiver. Stock Android does not need any of those for this
tile: the screen is on, so the process is not a Doze candidate, and the foreground-service
notification is the system's record of the session.

## Project structure

```
caffeine/
├── settings.gradle.kts
├── build.gradle.kts                      # AGP 9.4 (built-in Kotlin) + Compose compiler plugin
├── gradle.properties
├── gradle/
│   ├── libs.versions.toml
│   └── wrapper/                          # Gradle 9.6.0, jar included
└── app/
    ├── build.gradle.kts                  # minSdk 31, compile/target 37, R8, lint abortOnError
    ├── proguard-rules.pro
    └── src/
        ├── main/kotlin/dev/caffeine/
        │   ├── CaffeineApp.kt            # notification channel, warms the controller
        │   ├── core/                     # durations, cycle, state, settings, controller
        │   ├── data/SettingsRepository.kt
        │   ├── service/                  # wake lock, notification, deadline alarm
        │   ├── tile/CaffeineTileService.kt
        │   └── ui/                       # settings screen, opened from the tile long-press
        └── test/kotlin/dev/caffeine/core/
```

`Sdk.kt` is the only place that reads `Build.VERSION.SDK_INT`.

## Architecture

`CaffeineController` is the process-wide source of truth. The tile, the service, and the
settings screen read `StateFlow<CaffeineState>` and call `toggleFromTile()`, `start()`, or
`stop()`. Mutations run on the main thread.

The controller starts the foreground service when a session becomes active and the service is
not already running. If a stop has already called `stopSelf()` but the process has not died yet,
the next start issues a new `startForegroundService()` so the old stop cannot tear the new
session down. Rapid taps while the service is healthy do not stack start calls; the service
re-acquires the wake lock with the latest timeout.

DataStore stores settings and the last session so a `START_STICKY` restart can resume. The tile
never reads disk to decide what to show. If this process is not holding the lock, the state is Off.

### Why a screen wake lock

`SCREEN_BRIGHT_WAKE_LOCK | ON_AFTER_RELEASE` is deprecated and is what the AOSP tile uses. It is
the only option here that keeps the screen on no matter which app is in front, without a special
permission:

| Alternative | Why it is not used |
|---|---|
| `FLAG_KEEP_SCREEN_ON` on our Activity | Works only while that Activity is resumed. |
| Overlay with `FLAG_KEEP_SCREEN_ON` | Needs `SYSTEM_ALERT_WINDOW`. |
| Writing `SCREEN_OFF_TIMEOUT` | Needs `WRITE_SETTINGS` and can leave the user's setting changed. |
| `PARTIAL_WAKE_LOCK` | Keeps the CPU on, not the screen. |

The lock is not reference-counted. It is released by its own timeout, before every re-acquire,
when the session turns off, and in `onDestroy`. `ACTION_SCREEN_ON` and an inexact
`ELAPSED_REALTIME_WAKEUP` alarm call `reconcile()`, because the in-process timers use
`uptimeMillis` and do not advance in deep sleep. Below Android 14, `startForeground()` is the
two-argument call. On API 34 and above it passes `FOREGROUND_SERVICE_TYPE_MANIFEST`.

### Why `specialUse`

Android 14 checks the foreground-service type. `mediaPlayback` would be a lie, `dataSync` and
`mediaProcessing` time out after 6 hours on Android 15, `shortService` lasts about 3 minutes,
and `systemExempted` is for system apps. `specialUse` has no timeout. The manifest property
`PROPERTY_SPECIAL_USE_FGS_SUBTYPE` states the purpose for Play review. `onTimeout()` is not
overridden; the platform only calls it for timed types.

If the system refuses the service start, state returns to Off, the tile shows a toast, and
`startActivityAndCollapse` opens settings (`PendingIntent` on API 34+, `Intent` on 31–33).

## Compatibility

| Android | What changes | What the app does |
|---|---|---|
| **12 / API 31** | Foreground services cannot start from the background. PendingIntent mutability is required. | Start from the tile, the settings screen, or the notification action. Both start calls are wrapped. `FLAG_IMMUTABLE`. `FOREGROUND_SERVICE_IMMEDIATE`. |
| **13 / API 33** | Notification permission. Foreground-service notifications can be swiped away. Task Manager Stop kills the process. | Permission is optional. Swiping the notification does not stop the session. Task Manager Stop ends it. Add-tile API on 33+; editor instructions on 31–32. |
| **14 / API 34** | Foreground-service type is mandatory. `startActivityAndCollapse(Intent)` throws for targetSdk 34+. | `specialUse` plus the subtype property. `PendingIntent` overload from `Sdk.isAtLeast34()`. |
| **15 / API 35** | Some foreground-service types gain a 6-hour limit. Edge-to-edge is enforced. | `specialUse` has no timeout. `enableEdgeToEdge()` and Scaffold insets. No boot receiver, no overlay. |
| **16 / API 36** | Predictive back. Edge-to-edge opt-out is gone. | `enableOnBackInvokedCallback=true`. Already edge-to-edge. |
| **17 / API 37** | Stricter background audio and local network. | Neither applies. No audio, no network. |

Other behaviour:

* **Service killed.** `START_STICKY` restarts the process and `restoreAfterProcessDeath()` resumes a same-boot session that has not expired. `onDestroy` while active marks the session Off. There is no "allow background activity" prompt.
* **Reboot.** No boot receiver. The saved session is tagged with `currentTimeMillis − elapsedRealtime` and ignored on the next boot.
* **Screen timeout.** Ignored while the wake lock is held. `ON_AFTER_RELEASE` pokes user activity so the screen does not turn off the instant the lock is released.
* **Doze.** Does not start while the screen is on. If stop-on-screen-off is disabled and the screen is off, the deadline alarm and `ACTION_SCREEN_ON` still end an expired session.
* **Work profile.** State and DataStore are per user. Whether that profile's tile appears is up to the ROM.

## Check on a device

These were not run in the environment that wrote the code. Confirm on a stock device and one
AOSP-based ROM, at least on API 31, 33, 34, and the latest.

1. **Sticky restart.** `adb shell kill -9 $(adb shell pidof dev.caffeine)` during a session. Expected: the service restarts and the session resumes. If `startForeground` throws `ForegroundServiceStartNotAllowedException`, the app goes Off without crashing.
2. **API 31 `startForeground`.** The two-argument overload is used below API 34. Confirm it does not throw.
3. **Compose compiler.** AGP 9's Kotlin must match `kotlin = 2.4.20` in the version catalog.
4. **Off subtitle.** The tile sets subtitle "Off". If the ROM already draws its own on/off label, clear the subtitle in `render()` for the Off branch.

Manual checks:

- [ ] No Caffeine icon in the app drawer. The tile is in the Quick Settings editor.
- [ ] Long-press opens settings. On 33+, Add tile shows the system dialog.
- [ ] Tile is inactive with subtitle Off. Lock-screen tap works without unlocking.
- [ ] Default cycle: 5 min → 10 → 30 → 1 h → Indefinitely → Off.
- [ ] Six rapid taps end Off, with no leftover notification or `Caffeine:screen` wake lock.
- [ ] Disabling 10 min skips it. One enabled duration cannot be deselected.
- [ ] Subtitle counts down once a minute while Quick Settings stays open.
- [ ] `adb shell dumpsys power` shows `SCREEN_BRIGHT_WAKE_LOCK 'Caffeine:screen'` while active and nothing after stop.
- [ ] A 5 minute session outlasts the system screen timeout, then the screen times out normally.
- [ ] Changing the wall clock does not change remaining time.
- [ ] Indefinite stays on until you stop it.
- [ ] Notification shows the countdown and Stop. Stop clears the notification. Tapping the body opens settings.
- [ ] On 13+, swiping the notification leaves the session running. Task Manager Stop turns the tile Off.
- [ ] On 33+, the notification dialog appears when starting from settings, not on first open of settings. Deny still starts the session.
- [ ] Screen-off, Battery Saver, and low-battery stops follow the switches. Low battery ignores a charging device (`dumpsys battery set level` / `unplug`, then `dumpsys battery reset`).
- [ ] Reboot ends the session. With screen-off stop disabled, Doze past the deadline ends it after the screen turns on.
- [ ] Settings follows light, dark, and dynamic color, and scrolls clear of the system bars. Rotation keeps state.
- [ ] `lintRelease` reports 0 errors, unit tests pass, and `assembleRelease` succeeds.
- [ ] `dumpsys package dev.caffeine` lists only `WAKE_LOCK`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE`, and `POST_NOTIFICATIONS`.
