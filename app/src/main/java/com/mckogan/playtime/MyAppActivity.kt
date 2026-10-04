package com.mckogan.playtime

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import com.mckogan.playtime.Ui.add
import com.mckogan.playtime.Ui.dp

/**
 * Friction for the parent's own apps (e.g. Instagram), on top of the app when it opens:
 *  - first open: "Who's using it?" → Parent (fingerprint/PIN) → 1–30 minutes;
 *  - when that time is up: "Want more?" → Yes → a 30-second wait you can't skip → choose again.
 * "Close" always goes back to the home screen. No hard limits.
 */
class MyAppActivity : Activity() {

    private lateinit var store: Store
    private lateinit var pkg: String
    private lateinit var appName: String
    private val handler = Handler(Looper.getMainLooper())
    private var waitingForParent = false
    private var step = Step.NONE
    private var animator: ValueAnimator? = null

    private enum class Step { NONE, WHO, CHOOSE, MORE, COUNTDOWN }

    override fun onCreate(savedInstanceState: Bundle?) {
        Ui.applyTheme(this)
        super.onCreate(savedInstanceState)
        store = Store(this)
        pkg = intent.getStringExtra(EXTRA_APP) ?: return finish()
        appName = runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault(pkg)
        when {
            !intent.getBooleanExtra(EXTRA_MORE, false) -> askWho()
            // Already said "yes" and started the wait earlier: carry on with it.
            store.myAppWaitStart(pkg) > 0 -> countdown()
            else -> askMore()
        }
        // Get a fact ready in case the 30-second wait comes.
        Facts.refill(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val newPkg = intent.getStringExtra(EXTRA_APP) ?: return
        // Repeat request for the app already on screen (the guard can lag): keep the current step.
        if (newPkg == pkg && step != Step.NONE) return
        setIntent(intent)
        stopCountdown()
        pkg = newPkg
        appName = runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault(pkg)
        when {
            !intent.getBooleanExtra(EXTRA_MORE, false) -> askWho()
            // Already said "yes" and started the wait earlier: carry on with it.
            store.myAppWaitStart(pkg) > 0 -> countdown()
            else -> askMore()
        }
    }

    override fun onResume() {
        super.onResume()
        visibleFor = pkg
        resumed = true
        visibleAt = SystemClock.elapsedRealtime()
        GuardService.instance?.hideCover()
    }

    override fun onPause() {
        super.onPause()
        resumed = false
        visibleAt = SystemClock.elapsedRealtime()
    }

    override fun onStop() {
        super.onStop()
        // Walking away mid-countdown stops the ring; the wait itself keeps counting by the clock.
        if (!waitingForParent) {
            stopCountdown()
            if (!isFinishing) finish()
        }
    }

    @Deprecated("Activity.onBackPressed is fine for a no-AndroidX app")
    override fun onBackPressed() = close()

    @Deprecated("Activity.onActivityResult is fine for a no-AndroidX app")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_PARENT) return
        waitingForParent = false
        if (resultCode == RESULT_OK) chooseTime()
    }

    private fun screen(newStep: Step, build: LinearLayout.() -> Unit) {
        step = newStep
        val root = Ui.column(this, 28).apply {
            setBackgroundColor(Ui.BG)
            gravity = Gravity.CENTER_HORIZONTAL
        }
        runCatching { packageManager.getApplicationIcon(pkg) }.getOrNull()?.let { icon ->
            root.add(ImageView(this).apply {
                setImageDrawable(icon)
                layoutParams = LinearLayout.LayoutParams(dp(72), dp(72))
            }, topMarginDp = 40, fill = false)
        }
        root.add(Ui.text(this, appName, 26f, bold = true, center = true), topMarginDp = 12)
        root.build()
        // Scrolls on small screens (longer facts).
        setContentView(ScrollView(this).apply {
            setBackgroundColor(Ui.BG)
            isFillViewport = true
            addView(root)
        })
    }

    /**
     * First open. Only on the parent's list: straight to the parent check (fingerprint/PIN), then minutes.
     * Also a kids' game: "Parent or kid?" first; a kid goes to the kids' names and plays on their time.
     */
    private fun askWho() {
        val shared = pkg in store.games()
        screen(Step.WHO) {
            add(Ui.text(this@MyAppActivity, getString(R.string.myapp_who), 20f, Ui.MUTED, center = true), topMarginDp = 8)
            add(Ui.button(this@MyAppActivity, getString(R.string.myapp_parent)) { checkParent() }, topMarginDp = 32)
            if (shared) add(Ui.button(this@MyAppActivity, getString(R.string.myapp_kid)) { kidPlays() }, topMarginDp = 12)
            add(Ui.button(this@MyAppActivity, getString(R.string.myapp_close), Ui.MUTED) { close() }, topMarginDp = 12)
        }
        // The parent's own app: no question, the check pops up by itself (the screen stays as a fallback).
        if (!shared) checkParent()
    }

    private fun checkParent() {
        if (waitingForParent) return
        waitingForParent = true
        startActivityForResult(
            Intent(this, PinActivity::class.java).putExtra(PinActivity.EXTRA_MODE, PinActivity.MODE_VERIFY),
            REQUEST_PARENT,
        )
    }

    /** A kid wants this app as a game: the kids' names, then it opens on that kid's time. */
    private fun kidPlays() {
        startActivity(
            Intent(this, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_REASON, MainActivity.REASON_WHO)
                .putExtra(MainActivity.EXTRA_GAME, pkg)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        )
        finish()
    }

    private fun chooseTime() = screen(Step.CHOOSE) {
        add(Ui.text(this@MyAppActivity, getString(R.string.myapp_how_long), 20f, Ui.MUTED, center = true), topMarginDp = 8)
        // Two rows of three: 1 3 5 / 10 15 30 minutes.
        for ((r, minutesRow) in TIME_OPTIONS.chunked(3).withIndex()) {
            val row = Ui.row(this@MyAppActivity)
            for ((i, minutes) in minutesRow.withIndex()) {
                row.addView(Ui.button(this@MyAppActivity, getString(R.string.minutes_short, minutes), sizeSp = 20f) { start(minutes) },
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                        if (i > 0) marginStart = dp(10)
                    })
            }
            add(row, topMarginDp = if (r == 0) 32 else 10)
        }
        add(Ui.button(this@MyAppActivity, getString(R.string.myapp_close), Ui.MUTED) { close() }, topMarginDp = 12)
    }

    /** Time's up: more costs a 30-second pause. */
    private fun askMore() = screen(Step.MORE) {
        add(Ui.text(this@MyAppActivity, getString(R.string.myapp_time_up), 20f, Ui.MUTED, center = true), topMarginDp = 8)
        add(Ui.button(this@MyAppActivity, getString(R.string.myapp_close)) { close() }, topMarginDp = 32)
        add(Ui.button(this@MyAppActivity, getString(R.string.myapp_more), Ui.MUTED) { countdown() }, topMarginDp = 12)
    }

    /** 30 seconds you can't skip: a ring fills up while the seconds count down. */
    private fun countdown() {
        stopCountdown()
        // One wait between two sessions: it started the first time "yes" was tapped, and counts by the
        // clock even while away (reading the Wikipedia article, say). Done already → straight to minutes.
        val now = System.currentTimeMillis()
        val started = store.myAppWaitStart(pkg).takeIf { it in 1..now } ?: now.also { store.setMyAppWaitStart(pkg, it) }
        val waitMs = COUNTDOWN_S * 1000L
        val elapsed = now - started
        if (elapsed >= waitMs) {
            chooseTime()
            return
        }
        val startFraction = elapsed.toFloat() / waitMs
        screen(Step.COUNTDOWN) {
            add(Ui.text(this@MyAppActivity, getString(R.string.myapp_breathe), 20f, Ui.MUTED, center = true), topMarginDp = 8)
            val ringSize = dp(180)
            val ring = RingView(this@MyAppActivity)
            val number = Ui.text(this@MyAppActivity, COUNTDOWN_S.toString(), 56f, Ui.ACCENT, bold = true, center = true)
            val box = FrameLayout(this@MyAppActivity).apply {
                addView(ring, FrameLayout.LayoutParams(ringSize, ringSize))
                addView(number, FrameLayout.LayoutParams(ringSize, ringSize).apply { gravity = Gravity.CENTER })
            }
            addView(box, LinearLayout.LayoutParams(ringSize, ringSize).apply {
                topMargin = dp(24)
                gravity = Gravity.CENTER_HORIZONTAL
            })
            // Something worth reading while waiting (if one is ready).
            Facts.next(this@MyAppActivity)?.let { fact ->
                add(Ui.text(this@MyAppActivity, listOf(fact.emoji, fact.text).filter { it.isNotEmpty() }.joinToString(" "), 18f, center = true), topMarginDp = 24)
                if (fact.title.isNotEmpty()) {
                    // Tap to read the whole article (this also leaves the wait, like Close).
                    val link = Ui.text(this@MyAppActivity, getString(R.string.fact_source, fact.title), 14f, Ui.ACCENT, center = true).apply {
                        paintFlags = paintFlags or Paint.UNDERLINE_TEXT_FLAG
                        val p = dp(8)
                        setPadding(p, p, p, p)
                        setOnClickListener { openArticle(fact) }
                    }
                    add(link, topMarginDp = 2)
                }
            }
            add(Ui.button(this@MyAppActivity, getString(R.string.myapp_close)) { close() }, topMarginDp = 24)

            ring.progress = startFraction
            number.text = (COUNTDOWN_S - (startFraction * COUNTDOWN_S).toInt()).coerceAtLeast(1).toString()
            animator = ValueAnimator.ofFloat(startFraction, 1f).apply {
                duration = waitMs - elapsed
                interpolator = LinearInterpolator()
                addUpdateListener {
                    val f = it.animatedValue as Float
                    ring.progress = f
                    number.text = (COUNTDOWN_S - (f * COUNTDOWN_S).toInt()).coerceAtLeast(1).toString()
                }
                addListener(object : AnimatorListenerAdapter() {
                    private var cancelled = false
                    override fun onAnimationCancel(animation: Animator) {
                        cancelled = true
                    }
                    override fun onAnimationEnd(animation: Animator) {
                        if (!cancelled && !isFinishing) chooseTime()
                    }
                })
                start()
            }
        }
    }

    private fun openArticle(fact: Facts.Fact) {
        val url = fact.url.ifEmpty {
            val lang = if (resources.configuration.locales[0].language in setOf("iw", "he")) "he" else "en"
            "https://$lang.m.wikipedia.org/wiki/" + Uri.encode(fact.title.replace(' ', '_'))
        }
        store.endMyApp(pkg)
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        finish()
    }

        private fun stopCountdown() {
        animator?.cancel()
        animator = null
    }

    /** A grey circle with an orange arc that grows clockwise as [progress] goes 0 → 1. */
    private class RingView(context: Context) : View(context) {
        var progress = 0f
            set(value) {
                field = value
                invalidate()
            }
        private val stroke = 14 * context.resources.displayMetrics.density
        private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = stroke
            color = Ui.TRACK
        }
        private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = stroke
            strokeCap = Paint.Cap.ROUND
            color = Ui.ACCENT
        }
        private val bounds = RectF()

        override fun onDraw(canvas: Canvas) {
            val half = stroke / 2
            bounds.set(half, half, width - half, height - half)
            canvas.drawOval(bounds, track)
            canvas.drawArc(bounds, -90f, 360f * progress, false, arc)
        }
    }

    private fun start(minutes: Int) {
        store.startMyApp(pkg, minutes)
        packageManager.getLaunchIntentForPackage(pkg)?.let {
            startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        finish()
    }

    private fun close() {
        store.endMyApp(pkg)
        startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }

    override fun onDestroy() {
        stopCountdown()
        handler.removeCallbacksAndMessages(null)
        if (visibleFor == pkg && !resumed) visibleFor = null
        super.onDestroy()
    }

    companion object {
        @Volatile private var visibleFor: String? = null
        @Volatile private var resumed = false
        @Volatile private var visibleAt = 0L

        /**
         * True while this screen is up for [pkg] (or was a moment ago). Android reports the
         * front app with a delay, so the guard must not open this screen again on top of itself.
         */
        fun isShowing(pkg: String): Boolean =
            visibleFor == pkg && (resumed || SystemClock.elapsedRealtime() - visibleAt < LAG_GRACE_MS)

        private const val LAG_GRACE_MS = 5_000L

        const val EXTRA_APP = "app"
        const val EXTRA_MORE = "more"
        private const val REQUEST_PARENT = 1
        private const val COUNTDOWN_S = 30
        private val TIME_OPTIONS = listOf(1, 3, 5, 10, 15, 30)
    }
}
