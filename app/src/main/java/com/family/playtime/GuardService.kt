package com.family.playtime

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast

/**
 * The guard. Watches which app is in front, counts the active kid's time while one of the
 * chosen games is open, and puts the Play Time screen on top when a game may not be played.
 */
class GuardService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var store: Store
    private lateinit var power: PowerManager
    private lateinit var notifications: NotificationManager

    private var foregroundPkg: String? = null
    private var lastTick = 0L
    private var lastGameSeen = 0L
    private var lastBlockAt = 0L
    private var warnedKidId: String? = null
    private var warnedAtMinutes = Int.MAX_VALUE
    private var shownNotification: String? = null

    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // Phone put down: stop the clock. Next game launch asks "Who's playing?" again.
            store.pause()
            updateNotification()
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            onTick()
            handler.postDelayed(this, TICK_MS)
        }
    }

    override fun onServiceConnected() {
        store = Store(this)
        power = getSystemService(PowerManager::class.java)!!
        notifications = getSystemService(NotificationManager::class.java)!!
        notifications.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Game time", NotificationManager.IMPORTANCE_LOW)
        )
        registerReceiver(screenOffReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF))
        lastTick = SystemClock.elapsedRealtime()
        handler.post(tick)
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        runCatching { unregisterReceiver(screenOffReceiver) }
        runCatching { notifications.cancel(NOTIFICATION_ID) }
        super.onDestroy()
    }

    override fun onInterrupt() = Unit

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg in IGNORED_PACKAGES || pkg == currentKeyboard()) return
        foregroundPkg = pkg
        enforce()
    }

    private fun onTick() {
        val now = SystemClock.elapsedRealtime()
        val delta = (now - lastTick).coerceIn(0L, MAX_TICK_GAP_MS)
        lastTick = now

        val kid = store.activeKid()
        val inGame = foregroundPkg in store.games()
        if (kid != null) {
            if (inGame && power.isInteractive) {
                store.addUsed(kid.id, delta)
                lastGameSeen = System.currentTimeMillis()
                warnIfLow(kid)
            } else {
                val since = maxOf(lastGameSeen, store.activeSince())
                if (System.currentTimeMillis() - since > AWAY_PAUSE_MS) store.pause()
            }
        }
        if (inGame) enforce()
        updateNotification()
    }

    /** Covers the foreground app with our own screen if it isn't allowed right now. */
    private fun enforce() {
        val pkg = foregroundPkg ?: return

        if (pkg in PROTECTED_PACKAGES && store.protectSettings && store.hasPin() && !store.settingsUnlocked()) {
            show(Intent(this, PinActivity::class.java).putExtra(PinActivity.EXTRA_MODE, PinActivity.MODE_SETTINGS))
            return
        }

        if (pkg !in store.games()) return
        val kid = store.activeKid()
        val reason = when {
            kid == null -> MainActivity.REASON_WHO
            store.remainingMs(kid) <= 0 -> {
                store.pause()
                Toast.makeText(this, "Time's up, ${kid.name}!", Toast.LENGTH_LONG).show()
                MainActivity.REASON_TIME_UP
            }
            else -> return
        }
        show(
            Intent(this, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_REASON, reason)
                .putExtra(MainActivity.EXTRA_GAME, pkg)
                .putExtra(MainActivity.EXTRA_KID, kid?.id)
        )
        updateNotification()
    }

    private fun show(intent: Intent) {
        // Avoid stacking several launches while the system is still switching apps.
        val now = SystemClock.elapsedRealtime()
        if (now - lastBlockAt < 700) return
        lastBlockAt = now
        startActivity(
            intent.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
        )
    }

    private fun warnIfLow(kid: Kid) {
        if (warnedKidId != kid.id) {
            warnedKidId = kid.id
            warnedAtMinutes = Int.MAX_VALUE
        }
        val remaining = store.remainingMs(kid)
        for (minutes in WARN_AT_MINUTES) {
            if (remaining <= minutes * Store.MINUTE_MS && warnedAtMinutes > minutes && remaining > 0) {
                warnedAtMinutes = minutes
                val unit = if (minutes == 1) "minute" else "minutes"
                Toast.makeText(this, "${kid.name}: $minutes $unit left!", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun updateNotification() {
        val kid = store.activeKid()
        if (kid == null) {
            if (shownNotification != null) {
                notifications.cancel(NOTIFICATION_ID)
                shownNotification = null
            }
            return
        }
        val text = "${kid.name} is playing — ${Ui.formatMinutes(store.remainingMs(kid))} left"
        if (text == shownNotification) return
        shownNotification = text

        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val pause = PendingIntent.getActivity(
            this, 1,
            Intent(this, MainActivity::class.java).setAction(MainActivity.ACTION_PAUSE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(text)
            .setContentText("Tap Pause to save the rest for later")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "⏸ Pause", pause).build())
            .build()
        runCatching { notifications.notify(NOTIFICATION_ID, notification) }
    }

    private fun currentKeyboard(): String? =
        Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            ?.substringBefore('/')

    companion object {
        private const val TICK_MS = 1000L
        private const val MAX_TICK_GAP_MS = 3000L
        private const val AWAY_PAUSE_MS = 5 * Store.MINUTE_MS
        private val WARN_AT_MINUTES = intArrayOf(5, 1)
        private const val CHANNEL_ID = "game_time"
        private const val NOTIFICATION_ID = 1

        /** Windows that pop over the game without the game really leaving the screen. */
        private val IGNORED_PACKAGES = setOf("com.android.systemui", "android")

        /** Apps that could switch the guard off or uninstall Play Time. */
        val PROTECTED_PACKAGES = setOf(
            "com.android.settings",
            "com.android.packageinstaller",
            "com.google.android.packageinstaller",
        )

        fun isEnabled(context: Context): Boolean {
            val me = ComponentName(context, GuardService::class.java)
            val enabled = Settings.Secure.getString(
                context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
        }
    }
}
