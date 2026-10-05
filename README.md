# Caffeine

Caffeine keeps your screen on for as long as you choose. It lives in Quick Settings, like the tile on LineageOS and other Android builds. There is no app icon in the launcher.

It works on Android 12 and newer. It does not use the internet, and it does not ask you to allow background running or to turn off battery optimization.

## Add the tile

1. Pull down Quick Settings.
2. Tap the pencil to edit the tiles.
3. Drag **Caffeine** into the panel.

On Android 13 and newer you can also long-press the tile, open settings, and tap **Add tile**.

## Turn it on

Tap the tile. The first tap starts at 5 minutes. Each tap after that moves to the next longer time, then turns Caffeine off:

**5 min → 10 min → 30 min → 1 hour → Indefinitely → Off**

The tile shows how much time is left. While Quick Settings stays open, that label updates about once a minute.

**Indefinitely** keeps the screen on until you stop it yourself.

You can change which times appear, and which time the first tap uses, in settings. The last remaining time cannot be removed.

## Settings

Long-press the tile to open settings. From there you can:

- Start or stop a session without using the tile.
- Choose which durations are in the tap cycle. Extra choices are 15 minutes, 2 hours, and 4 hours.
- Pick what the first tap starts with.
- Stop automatically when the screen turns off, when Battery Saver turns on, or when the battery is low and the phone is not charging.

By default, pressing the power button ends the session. Battery Saver and low-battery stops are off.

## Notification

While Caffeine is on, a silent notification shows the time left and a **Stop** button. Tapping the notification opens settings.

On Android 13 and newer, Android may ask for notification permission when you start a session from settings. You can say no. Caffeine still keeps the screen on. You just will not see the countdown in the notification shade.

Swiping the notification away does not stop the session.

## When it stops

Caffeine turns off when:

- you tap the tile through to Off, or tap Stop
- the timer runs out
- you turn the screen off, if that option is on
- Battery Saver or a low battery triggers a stop you enabled

A restart of the phone always turns Caffeine off. It does not come back on by itself after a reboot.
