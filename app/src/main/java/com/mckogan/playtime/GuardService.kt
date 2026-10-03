package com.mckogan.playtime

import android.app.AppOpsManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.Toast
import com.mckogan.playtime.Ui.add

/**
 * The guard. A foreground service that checks once a second which app is in front (Usage access),
 * counts the active kid's time while one of the chosen games is open, and puts Play Time on top
 * (Display over other apps) when a game may not be played.
 */
class GuardService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var store: Store
    private lateinit var power: PowerManager
    private lateinit var notifications: NotificationManager
    private lateinit var usage: UsageStatsManager
    private lateinit var windows: WindowManager

    private var foregroundPkg: String? = null
    private var foregroundSince = 0L
    private var lastQuery = 0L
    private var lastTick = 0L
    private var lastGameSeen = 0L
    private var lastBlockAt = 0L
    private var warnedKidId: String? = null
    private var warnedAtMinutes = Int.MAX_VALUE
    private var shownNotification: String? = null

    /** Tiny invisible window: lets Android 15+ allow us to open Play Time from the background. */
    private var anchor: View? = null

    /** Full-screen cover over a blocked app, in case opening Play Time is delayed or refused. */
    private var cover: View? = null

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

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        store = Store(this)
        power = getSystemService(PowerManager::class.java)!!
        notifications = getSystemService(NotificationManager::class.java)!!
        usage = getSystemService(UsageStatsManager::class.java)!!
        windows = getSystemService(WindowManager::class.java)!!
        notifications.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW)
        )
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        registerReceiver(screenOffReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF))
        lastTick = SystemClock.elapsedRealtime()
        handler.post(tick)
        Sync.start(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!isReady(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        instance = null
        handler.removeCallbacks(tick)
        runCatching { unregisterReceiver(screenOffReceiver) }
        hideCover()
        anchor?.let { runCatching { windows.removeView(it) } }
        anchor = null
        super.onDestroy()
    }

    private fun onTick() {
        val now = SystemClock.elapsedRealtime()
        val delta = (now - lastTick).coerceIn(0L, MAX_TICK_GAP_MS)
        lastTick = now

        ensureAnchor()
        val changed = pollForeground()

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
        if (changed || inGame || foregroundPkg in PROTECTED_PACKAGES) enforce()
        updateNotification()
        Sync.tick(this)
    }

    /** Reads the latest "app came to the front" event. Returns true if the front app changed. */
    @Suppress("DEPRECATION") // MOVE_TO_FOREGROUND == ACTIVITY_RESUMED, which needs API 29.
    private fun pollForeground(): Boolean {
        val now = System.currentTimeMillis()
        val from = if (lastQuery == 0L) now - FIRST_QUERY_MS else lastQuery - QUERY_OVERLAP_MS
        lastQuery = now
        val events = runCatching { usage.queryEvents(from, now) }.getOrNull() ?: return false
        val event = UsageEvents.Event()
        var latest: String? = null
        var latestAt = foregroundSince
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType != UsageEvents.Event.MOVE_TO_FOREGROUND) continue
            val pkg = event.packageName ?: continue
            if (pkg in IGNORED_PACKAGES || pkg == currentKeyboard()) continue
            if (event.timeStamp > latestAt) {
                latest = pkg
                latestAt = event.timeStamp
            }
        }
        if (latest == null) return false
        foregroundSince = latestAt
        if (latest == foregroundPkg) return false
        foregroundPkg = latest
        if (latest == packageName) hideCover()
        return true
    }

    /** Covers the foreground app with our own screen if it isn't allowed right now. */
    private fun enforce() {
        val pkg = foregroundPkg ?: return

        if (pkg in PROTECTED_PACKAGES && store.protectSettings && store.hasPin() && !store.settingsUnlocked()) {
            block(
                Intent(this, PinActivity::class.java).putExtra(PinActivity.EXTRA_MODE, PinActivity.MODE_SETTINGS),
                getString(R.string.pin_needed),
            )
            return
        }

        if (pkg !in store.games()) {
            hideCover()
            return
        }
        val kid = store.activeKid()
        val title = when {
            kid == null -> getString(R.string.who_title)
            store.remainingMs(kid) <= 0 -> {
                store.pause()
                Sync.flush(this)
                getString(R.string.time_up_name, kid.name).also {
                    Toast.makeText(this, it, Toast.LENGTH_LONG).show()
                }
            }
            else -> {
                hideCover()
                return
            }
        }
        block(
            Intent(this, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_REASON, if (kid == null) MainActivity.REASON_WHO else MainActivity.REASON_TIME_UP)
                .putExtra(MainActivity.EXTRA_GAME, pkg)
                .putExtra(MainActivity.EXTRA_KID, kid?.id),
            title,
        )
        updateNotification()
    }

    private fun block(intent: Intent, title: String) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        showCover(title, intent)
        // Avoid stacking several launches while the system is still switching apps.
        val now = SystemClock.elapsedRealtime()
        if (now - lastBlockAt < RELAUNCH_MS) return
        lastBlockAt = now
        runCatching { startActivity(intent) }
    }

    private fun overlayType() = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

    private fun ensureAnchor() {
        if (anchor != null || !Settings.canDrawOverlays(this)) return
        val view = View(this)
        val lp = WindowManager.LayoutParams(
            1, 1, overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START }
        if (runCatching { windows.addView(view, lp) }.isSuccess) anchor = view
    }

    private fun showCover(title: String, open: Intent) {
        if (!Settings.canDrawOverlays(this)) return
        hideCover()
        val box = Ui.column(this, 32).apply {
            setBackgroundColor(Ui.BG)
            gravity = Gravity.CENTER
        }
        box.add(Ui.text(this, title, 30f, bold = true, center = true))
        box.add(
            Ui.button(this, getString(R.string.open_app)) {
                runCatching { startActivity(open) }
            },
            topMarginDp = 24,
            fill = false,
        )
        (box.getChildAt(1).layoutParams as LinearLayout.LayoutParams).gravity = Gravity.CENTER_HORIZONTAL
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.OPAQUE,
        )
        if (runCatching { windows.addView(box, lp) }.isSuccess) cover = box
    }

    fun hideCover() {
        cover?.let { runCatching { windows.removeView(it) } }
        cover = null
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
                val text = resources.getQuantityString(R.plurals.warn_left, minutes, kid.name, minutes)
                Toast.makeText(this, text, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun notificationTitle(): String {
        val kid = store.activeKid() ?: return getString(R.string.guard_running)
        return getString(R.string.notif_playing, kid.name, Ui.formatMinutes(this, store.remainingMs(kid)))
    }

    private fun updateNotification() {
        if (notificationTitle() == shownNotification) return
        runCatching { notifications.notify(NOTIFICATION_ID, buildNotification()) }
    }

    private fun buildNotification(): Notification {
        val title = notificationTitle()
        shownNotification = title
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(title)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
        if (store.activeKid() != null) {
            val pause = PendingIntent.getActivity(
                this, 1,
                Intent(this, MainActivity::class.java).setAction(MainActivity.ACTION_PAUSE),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            builder.setContentText(getString(R.string.notif_pause_hint))
                .addAction(Notification.Action.Builder(null, getString(R.string.pause), pause).build())
        }
        return builder.build()
    }

    private fun currentKeyboard(): String? =
        Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            ?.substringBefore('/')

    companion object {
        private const val TICK_MS = 1000L
        private const val MAX_TICK_GAP_MS = 3000L
        private const val FIRST_QUERY_MS = 60_000L
        private const val QUERY_OVERLAP_MS = 2_000L
        private const val RELAUNCH_MS = 1_500L
        private const val AWAY_PAUSE_MS = 5 * Store.MINUTE_MS
        private val WARN_AT_MINUTES = intArrayOf(5, 1)
        private const val CHANNEL_ID = "game_time"
        private const val NOTIFICATION_ID = 1

        /** The running guard, if any (used to drop the cover once Play Time is open). */
        var instance: GuardService? = null
            private set

        /** Windows that pop over the game without the game really leaving the screen. */
        private val IGNORED_PACKAGES = setOf("com.android.systemui", "android")

        /** Apps that could switch the guard off or uninstall Play Time. */
        val PROTECTED_PACKAGES = setOf(
            "com.android.settings",
            "com.android.packageinstaller",
            "com.google.android.packageinstaller",
            "com.samsung.android.packageinstaller",
        )

        fun hasUsageAccess(context: Context): Boolean {
            val ops = context.getSystemService(AppOpsManager::class.java) ?: return false
            @Suppress("DEPRECATION") // unsafeCheckOpNoThrow needs API 29.
            val mode = ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
            return mode == AppOpsManager.MODE_ALLOWED
        }

        fun canOverlay(context: Context): Boolean = Settings.canDrawOverlays(context)

        fun isReady(context: Context): Boolean = hasUsageAccess(context) && canOverlay(context)

        /** Starts the guard if both permissions are on. Safe to call repeatedly. */
        fun start(context: Context) {
            if (!isReady(context)) return
            runCatching { context.startForegroundService(Intent(context, GuardService::class.java)) }
        }
    }
}
