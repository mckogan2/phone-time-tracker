package com.mckogan.playtime

import android.annotation.SuppressLint
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
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.mckogan.playtime.Ui.add
import com.mckogan.playtime.Ui.dp
import kotlin.math.abs

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
            // Phone put down: stop the clock. Next game launch asks "Who's playing?" again,
            store.pause()
            // "Parent, no time" ends here too: the next person to unlock the phone may be a kid.
            if (store.parentUntilScreenOff) store.parentPlayingUntil = 0L
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
        notifications.createNotificationChannel(
            NotificationChannel(MY_APPS_CHANNEL_ID, getString(R.string.my_apps_channel), NotificationManager.IMPORTANCE_LOW)
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
        hideBubble()
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

        val parentPlaying = store.parentPlaying()
        val kid = if (parentPlaying) null else store.activeKid()
        val inGame = foregroundPkg in store.games()
        if (kid != null) {
            if (inGame && power.isInteractive) {
                store.addUsed(kid.id, delta, foregroundPkg)
                lastGameSeen = System.currentTimeMillis()
                warnIfLow(kid)
            } else {
                val since = maxOf(lastGameSeen, store.activeSince())
                if (System.currentTimeMillis() - since > AWAY_PAUSE_MS) store.pause()
            }
        }
        // The parent's own apps run on a wall-clock session; check each second while one is in front.
        val myApp = foregroundPkg?.takeIf { it in store.myApps() }
        if (myApp == null) allowedInFront = null

        if (changed || inGame || myApp != null || foregroundPkg in PROTECTED_PACKAGES) enforce()
        val activeKid = if (parentPlaying) null else store.activeKid()
        updateBubble(activeKid, activeKid != null && inGame && cover == null && power.isInteractive)
        updateNotification()
        updateMyAppNotifications()
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

        // The parent's own apps: "how long?" before, "want more?" after the chosen time. An app that is
        // also a kids' game counts as a game while a kid is playing (or a parent is playing).
        val asGame = pkg in store.games() && !store.myAppActive(pkg) &&
            (store.parentPlaying() || store.activeKid() != null)
        if (pkg in store.myApps() && !asGame) {
            if (store.myAppActive(pkg)) {
                allowedInFront = pkg
                hideCover()
            } else if (MyAppActivity.isShowing(pkg)) {
                hideCover()
            } else {
                // Time ran out (or "End now") in the last 10 minutes, here or while in another app →
                // "Want more?" with the wait. Longer ago → a normal fresh start.
                val until = store.myAppUntil(pkg)
                if (until > 0) store.startMyAppCooldown(pkg, from = until)
                val wantMore = store.inMyAppCooldown(pkg)
                allowedInFront = null
                store.endMyApp(pkg)
                block(
                    Intent(this, MyAppActivity::class.java)
                        .putExtra(MyAppActivity.EXTRA_APP, pkg)
                        .putExtra(MyAppActivity.EXTRA_MORE, wantMore),
                    appLabel(pkg),
                )
            }
            return
        }

        if (pkg !in store.games() || store.parentPlaying()) {
            hideCover()
            return
        }

        // Outside the allowed hours no one plays, even with time left.
        if (!store.gamesAllowedNow()) {
            if (store.activeKid() != null) {
                store.pause()
                Sync.flush(this)
            }
            block(
                Intent(this, MainActivity::class.java)
                    .putExtra(MainActivity.EXTRA_REASON, MainActivity.REASON_HOURS)
                    .putExtra(MainActivity.EXTRA_GAME, pkg),
                hoursClosedText(this, store),
            )
            updateNotification()
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
        Ui.dark = Ui.isDark(this)
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

    // ---- Floating time bubble ----

    private var bubble: TextView? = null
    private var bubbleParams: WindowManager.LayoutParams? = null

    /** The "⏸ 12′" pill over the game: shows minutes left, tap to pause, drag to move. */
    private fun updateBubble(kid: Kid?, show: Boolean) {
        if (!show || kid == null || !Settings.canDrawOverlays(this)) {
            hideBubble()
            return
        }
        val view = bubble ?: createBubble() ?: return
        val remaining = store.remainingMs(kid)
        view.text = "⏸ " + Ui.formatMinutes(this, remaining)
        view.background = Ui.rounded(if (remaining <= Store.MINUTE_MS) Ui.DANGER else kid.color, 24, this)
    }

    @SuppressLint("ClickableViewAccessibility") // Tap is handled in the touch listener (drag vs. tap).
    private fun createBubble(): TextView? {
        val view = Ui.text(this, "", 18f, 0xFFFFFFFF.toInt(), bold = true, center = true).apply {
            val p = dp(10)
            setPadding(p + p / 2, p, p + p / 2, p)
            elevation = dp(6).toFloat()
        }
        val rtl = resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = store.bubbleX.takeIf { it >= 0 }
                ?: if (rtl) dp(12) else resources.displayMetrics.widthPixels - dp(150)
            y = store.bubbleY.takeIf { it >= 0 } ?: dp(72)
        }
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var dragged = false
        view.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    startX = lp.x
                    startY = lp.y
                    dragged = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (dragged || abs(dx) > slop || abs(dy) > slop) {
                        dragged = true
                        lp.x = (startX + dx).toInt().coerceAtLeast(0)
                        lp.y = (startY + dy).toInt().coerceAtLeast(0)
                        runCatching { windows.updateViewLayout(view, lp) }
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (dragged) {
                        store.bubbleX = lp.x
                        store.bubbleY = lp.y
                    } else {
                        pauseFromBubble()
                    }
                }
            }
            true
        }
        if (runCatching { windows.addView(view, lp) }.isFailure) return null
        bubble = view
        bubbleParams = lp
        return view
    }

    private fun pauseFromBubble() {
        store.pause()
        Sync.flush(this)
        hideBubble()
        enforce()
        updateNotification()
    }

    private fun hideBubble() {
        bubble?.let { runCatching { windows.removeView(it) } }
        bubble = null
        bubbleParams = null
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
        if (remaining <= 0) return
        // More time than at the last reminder (a new day, or a parent added minutes): start over.
        if (warnedAtMinutes != Int.MAX_VALUE && remaining > warnedAtMinutes * Store.MINUTE_MS) warnedAtMinutes = Int.MAX_VALUE
        // Only a mark that is true right now ("5 minutes" between 4 and 5 left): after 3 bonus minutes
        // the kid hears "1 minute", never a wrong "5 minutes".
        val minutes = WARN_AT_MINUTES.firstOrNull {
            remaining <= it * Store.MINUTE_MS && remaining > (it - 1) * Store.MINUTE_MS && warnedAtMinutes > it
        } ?: return
        warnedAtMinutes = minutes
        val text = resources.getQuantityString(R.plurals.warn_left, minutes, kid.name, minutes)
        Toast.makeText(this, text, Toast.LENGTH_LONG).show()
        if (store.voiceReminders) Voice.play(this, minutes)
    }

    private fun notificationTitle(): String {
        if (store.parentPlaying()) return Ui.parentPlayingText(this, store)
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
        if (store.parentPlaying()) {
            val end = PendingIntent.getActivity(
                this, 2,
                Intent(this, MainActivity::class.java).setAction(MainActivity.ACTION_END_PARENT),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            builder.addAction(Notification.Action.Builder(null, getString(R.string.parent_playing_end), end).build())
        } else if (store.activeKid() != null) {
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

    /** The "My app" that was in front with an active session (to tell "ran out here" from "ran out elsewhere"). */
    private var allowedInFront: String? = null

    /** The end time each "My app" notification shows, so it's re-posted only when that changes. */
    private val myAppShown = mutableMapOf<String, Long>()

    /** One notification per "My app" with an active session: a live countdown to its end time. */
    private fun updateMyAppNotifications() {
        val apps = store.myApps().sorted()
        for ((index, pkg) in apps.withIndex()) {
            val id = MY_APP_NOTIFICATION_BASE + index
            val until = store.myAppUntil(pkg)
            if (!store.myAppActive(pkg)) {
                if (myAppShown.remove(pkg) != null) notifications.cancel(id)
                continue
            }
            if (myAppShown[pkg] == until) continue
            myAppShown[pkg] = until
            runCatching { notifications.notify(id, buildMyAppNotification(pkg, index, until)) }
        }
        // Apps removed from the list.
        for (pkg in myAppShown.keys.filter { it !in apps }) myAppShown.remove(pkg)
    }

    private fun buildMyAppNotification(pkg: String, index: Int, until: Long): Notification {
        val name = appLabel(pkg)
        val open = packageManager.getLaunchIntentForPackage(pkg)?.let {
            PendingIntent.getActivity(this, 100 + index, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val end = PendingIntent.getBroadcast(
            this, 200 + index,
            Intent(this, MyAppEndReceiver::class.java).putExtra(MyAppActivity.EXTRA_APP, pkg),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = Notification.Builder(this, MY_APPS_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(true)
            .setContentTitle(getString(R.string.myapp_notif_left, name))
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .setWhen(until)
            .setTimeoutAfter((until - System.currentTimeMillis()).coerceAtLeast(1L))
            .addAction(Notification.Action.Builder(null, getString(R.string.myapp_end_now), end).build())
        open?.let { builder.setContentIntent(it) }
        return builder.build()
    }

    private fun appLabel(pkg: String): String = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

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
        private val WARN_AT_MINUTES = intArrayOf(10, 5, 1)
        private const val CHANNEL_ID = "game_time"
        private const val NOTIFICATION_ID = 1
        private const val MY_APPS_CHANNEL_ID = "my_apps"
        private const val MY_APP_NOTIFICATION_BASE = 1000

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

        /** "Games start at 10:00" in the morning, "No more games today" in the evening. */
        fun hoursClosedText(context: Context, store: Store): String {
            val from = Ui.formatTimeOfDay(context, store.hoursFrom)
            return context.getString(
                if (store.beforeOpening()) R.string.hours_closed_morning else R.string.hours_closed_night,
                from,
            )
        }

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
