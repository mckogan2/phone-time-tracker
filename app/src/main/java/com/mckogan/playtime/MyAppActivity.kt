package com.mckogan.playtime

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import com.mckogan.playtime.Ui.add
import com.mckogan.playtime.Ui.dp

/**
 * Friction for the parent's own apps (e.g. Instagram), on top of the app when it opens:
 *  - first open: "Who's using it?" → Parent (fingerprint/PIN) → 15 or 30 minutes;
 *  - when that time is up: "Want more?" → Yes → a 30-second wait you can't skip → 15 or 30 again.
 * "Close" always goes back to the home screen. No hard limits.
 */
class MyAppActivity : Activity() {

    private lateinit var store: Store
    private lateinit var pkg: String
    private lateinit var appName: String
    private val handler = Handler(Looper.getMainLooper())
    private var countdownLeft = 0
    private var waitingForParent = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        pkg = intent.getStringExtra(EXTRA_APP) ?: return finish()
        appName = runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault(pkg)
        if (intent.getBooleanExtra(EXTRA_MORE, false)) askMore() else askWho()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handler.removeCallbacksAndMessages(null)
        pkg = intent.getStringExtra(EXTRA_APP) ?: return
        appName = runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault(pkg)
        if (intent.getBooleanExtra(EXTRA_MORE, false)) askMore() else askWho()
    }

    override fun onResume() {
        super.onResume()
        GuardService.instance?.hideCover()
    }

    override fun onStop() {
        super.onStop()
        // Walking away mid-countdown cancels it; the next open asks again.
        handler.removeCallbacksAndMessages(null)
        if (!isFinishing && !waitingForParent) finish()
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

    private fun screen(build: LinearLayout.() -> Unit) {
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
        setContentView(root)
    }

    /** First open: only a parent goes on. */
    private fun askWho() = screen {
        add(Ui.text(this@MyAppActivity, getString(R.string.myapp_who), 20f, Ui.MUTED, center = true), topMarginDp = 8)
        add(Ui.button(this@MyAppActivity, getString(R.string.myapp_parent)) {
            waitingForParent = true
            startActivityForResult(
                Intent(this@MyAppActivity, PinActivity::class.java).putExtra(PinActivity.EXTRA_MODE, PinActivity.MODE_VERIFY),
                REQUEST_PARENT,
            )
        }, topMarginDp = 32)
        add(Ui.button(this@MyAppActivity, getString(R.string.myapp_close), Ui.MUTED) { close() }, topMarginDp = 12)
    }

    private fun chooseTime() = screen {
        add(Ui.text(this@MyAppActivity, getString(R.string.myapp_how_long), 20f, Ui.MUTED, center = true), topMarginDp = 8)
        val row = Ui.row(this@MyAppActivity)
        for ((i, minutes) in listOf(15, 30).withIndex()) {
            row.addView(Ui.button(this@MyAppActivity, getString(R.string.minutes_short, minutes), sizeSp = 22f) { start(minutes) },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (i > 0) marginStart = dp(12)
                })
        }
        add(row, topMarginDp = 32)
        add(Ui.button(this@MyAppActivity, getString(R.string.myapp_close), Ui.MUTED) { close() }, topMarginDp = 12)
    }

    /** Time's up: more costs a 30-second pause. */
    private fun askMore() = screen {
        add(Ui.text(this@MyAppActivity, getString(R.string.myapp_time_up), 20f, Ui.MUTED, center = true), topMarginDp = 8)
        add(Ui.button(this@MyAppActivity, getString(R.string.myapp_close)) { close() }, topMarginDp = 32)
        add(Ui.button(this@MyAppActivity, getString(R.string.myapp_more), Ui.MUTED) { countdown() }, topMarginDp = 12)
    }

    private fun countdown() {
        countdownLeft = COUNTDOWN_S
        screen {
            add(Ui.text(this@MyAppActivity, getString(R.string.myapp_breathe), 20f, Ui.MUTED, center = true), topMarginDp = 8)
            val number = Ui.text(this@MyAppActivity, countdownLeft.toString(), 64f, Ui.ACCENT, bold = true, center = true)
            add(number, topMarginDp = 24)
            add(Ui.button(this@MyAppActivity, getString(R.string.myapp_close)) { close() }, topMarginDp = 24)
            val tick = object : Runnable {
                override fun run() {
                    countdownLeft--
                    if (countdownLeft <= 0) {
                        chooseTime()
                    } else {
                        number.text = countdownLeft.toString()
                        handler.postDelayed(this, 1000)
                    }
                }
            }
            handler.postDelayed(tick, 1000)
        }
    }

    private fun start(minutes: Int) {
        store.setMyAppLeftMs(pkg, minutes * Store.MINUTE_MS)
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
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    companion object {
        const val EXTRA_APP = "app"
        const val EXTRA_MORE = "more"
        private const val REQUEST_PARENT = 1
        private const val COUNTDOWN_S = 30
    }
}
