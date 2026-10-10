package com.mckogan.playtime

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import com.mckogan.playtime.Ui.add
import com.mckogan.playtime.Ui.dp

/**
 * The kids' screen. Also shown on top of a game when nobody has picked their name yet
 * ("Who's playing?") or when the playing kid's time has run out ("Time's up").
 */
class MainActivity : Activity() {

    private lateinit var store: Store
    private var reason: String? = null
    private var blockedGame: String? = null
    private var blockedKidId: String? = null
    private var createdDark = false
    private var spokenOfferFor: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        Ui.applyTheme(this)
        createdDark = Ui.dark
        super.onCreate(savedInstanceState)
        store = Store(this)
        if (!store.onboarded) {
            startActivity(Intent(this, WelcomeActivity::class.java))
            finish()
            return
        }
        handleIntent(intent)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        // The theme was changed in the parent area: redraw this screen in it.
        if (Ui.isDark(this) != createdDark) {
            recreate()
            return
        }
        Ui.dark = createdDark
        GuardService.start(this)
        GuardService.instance?.hideCover()
        Sync.start(this)
        Sync.flush(this)
        render()
    }

    private fun handleIntent(intent: Intent) {
        if (intent.action == ACTION_PAUSE) {
            store.pause()
            reason = null
            blockedGame = null
        } else if (intent.action == ACTION_END_PARENT) {
            store.parentPlayingUntil = 0L
            reason = null
            blockedGame = null
        } else {
            reason = intent.getStringExtra(EXTRA_REASON)
            blockedGame = intent.getStringExtra(EXTRA_GAME)
            blockedKidId = intent.getStringExtra(EXTRA_KID)
        }
    }

    @Deprecated("Activity.onBackPressed is fine for a no-AndroidX app")
    override fun onBackPressed() {
        // Never "back" into a game that was just blocked.
        if (reason != null) goHome() else super.onBackPressed()
    }

    private fun render() {
        val root = Ui.column(this, 20)
        val active = store.activeKid()

        val (title, subtitle) = when (reason) {
            REASON_WHO -> getString(R.string.who_title) to getString(R.string.who_subtitle)
            REASON_HOURS -> GuardService.hoursClosedText(this, store) to getString(R.string.hours_subtitle)
            REASON_TIME_UP -> {
                val name = store.kid(blockedKidId)?.name
                (name?.let { getString(R.string.time_up_name, it) } ?: getString(R.string.time_up_title)) to
                    getString(R.string.time_up_subtitle)
            }
            else -> getString(R.string.home_title) to
                (active?.let { getString(R.string.kid_is_playing, it.name) } ?: getString(R.string.tap_to_play))
        }
        val offerKid = store.kid(blockedKidId)?.takeIf {
            reason == REASON_TIME_UP && store.offerExtra && store.gamesAllowedNow() &&
                !store.extraOfferedToday(it.id) && store.remainingMs(it) <= 0
        }
        if (offerKid != null) {
            extraOffer(root, offerKid)
            setContentView(ScrollView(this).apply {
                setBackgroundColor(Ui.BG)
                isFillViewport = true
                addView(root)
            })
            return
        }

        root.add(Ui.text(this, title, 30f, bold = true, center = true))
        root.add(Ui.text(this, subtitle, 16f, Ui.MUTED, center = true), topMarginDp = 4)
        // Refresh: the time on this screen only updates when it is drawn again.
        root.add(Ui.button(this, "🔄", Ui.TRACK, 22f) {}.apply {
            contentDescription = getString(R.string.refresh_time)
            setPadding(dp(20), dp(10), dp(20), dp(10))
            setOnClickListener {
                Sync.flush(this@MainActivity)
                Ui.tick(this@MainActivity)
                Ui.bounce(this)
                Toast.makeText(this@MainActivity, getString(R.string.refresh_done), Toast.LENGTH_SHORT).show()
                window.decorView.postDelayed({ render() }, 150)
            }
        }, topMarginDp = 12)

        if (!GuardService.isReady(this)) {
            val banner = Ui.card(this, Ui.WARN_BG)
            banner.add(Ui.text(this, getString(R.string.setup_needed), 15f))
            root.add(banner, topMarginDp = 16)
        }

        if (!store.gamesAllowedNow() && reason != REASON_HOURS) {
            val banner = Ui.card(this, Ui.HOURS_BG)
            banner.add(Ui.text(this, GuardService.hoursClosedText(this, store), 17f, bold = true, center = true))
            root.add(banner, topMarginDp = 16)
        }

        if (store.parentPlaying()) {
            val banner = Ui.card(this, Ui.INFO_BG)
            banner.add(Ui.text(this, Ui.parentPlayingText(this, store), 16f, bold = true))
            banner.add(Ui.button(this, getString(R.string.parent_playing_end), Ui.MUTED, 16f) {
                store.parentPlayingUntil = 0L
                render()
            }, topMarginDp = 8)
            root.add(banner, topMarginDp = 16)
        }

        for (kid in store.kids()) root.add(kidCard(kid, active), topMarginDp = 16)

        // A game was opened and no one is playing yet: a parent can just play it (fingerprint, no time).
        val game = blockedGame
        if (reason == REASON_WHO && game != null) {
            root.add(Ui.button(this, getString(R.string.parent_plays_now), Ui.MUTED) {
                startActivityForResult(
                    Intent(this, PinActivity::class.java).putExtra(PinActivity.EXTRA_MODE, PinActivity.MODE_VERIFY),
                    REQUEST_PARENT,
                )
            }, topMarginDp = 16)
        }

        if (active != null && blockedGame == null) root.add(gamePicker(), topMarginDp = 20)

        val parents = Ui.text(this, getString(R.string.parents_button), 16f, Ui.MUTED, center = true).apply {
            val p = dp(16)
            setPadding(p, p, p, p)
            setOnClickListener {
                startActivity(
                    Intent(this@MainActivity, PinActivity::class.java)
                        .putExtra(PinActivity.EXTRA_MODE, PinActivity.MODE_PARENT)
                )
            }
        }
        root.add(parents, topMarginDp = 24)

        setContentView(ScrollView(this).apply {
            setBackgroundColor(Ui.BG)
            addView(root)
        })
    }

    /** Time's up: once a day, "3 more minutes?" with big ✅ / ❌ (and a voice, if recorded). */
    private fun extraOffer(root: LinearLayout, kid: Kid) {
        root.gravity = Gravity.CENTER_HORIZONTAL
        root.addView(Ui.kidAvatar(this, kid, Ui.kid(kid.color), 96), LinearLayout.LayoutParams(dp(96), dp(96)).apply {
            topMargin = dp(32)
            gravity = Gravity.CENTER_HORIZONTAL
        })
        root.add(Ui.text(this, getString(R.string.time_up_name, kid.name), 30f, bold = true, center = true), topMarginDp = 16)
        root.add(Ui.text(this, getString(R.string.extra_offer, Store.EXTRA_MINUTES), 24f, center = true), topMarginDp = 8)
        val row = Ui.row(this)
        row.addView(Ui.button(this, getString(R.string.extra_yes), Ui.kid(GREEN), 26f) {
            store.markExtraOffered(kid.id)
            store.recordExtra(taken = true)
            store.addBonus(kid.id, Store.EXTRA_MINUTES)
            Sync.flush(this)
            startPlaying(kid)
        }, LinearLayout.LayoutParams(0, dp(96), 1f))
        row.addView(Ui.button(this, getString(R.string.extra_no), Ui.DANGER, 26f) {
            store.markExtraOffered(kid.id)
            store.recordExtra(taken = false)
            render()
        }, LinearLayout.LayoutParams(0, dp(96), 1f).apply { marginStart = dp(16) })
        root.add(row, topMarginDp = 32)
        if (spokenOfferFor != kid.id) {
            spokenOfferFor = kid.id
            if (store.voiceReminders) Voice.playExtraOffer(this)
        }
    }

    private fun kidCard(kid: Kid, active: Kid?): LinearLayout {
        val remaining = store.remainingMs(kid)
        val total = kid.dailyMinutes * Store.MINUTE_MS
        val playing = active?.id == kid.id
        val done = remaining <= 0
        val card = Ui.card(this)

        // Big round avatar + name and time; tapping anywhere on the card means "I want to play".
        val top = Ui.row(this)
        top.addView(Ui.kidAvatar(this, kid, if (done) Ui.MUTED else Ui.kid(kid.color), 72))
        val info = Ui.column(this).apply { setPadding(dp(16), 0, dp(16), 0) }
        info.add(Ui.text(this, kid.name + if (playing) "  " + getString(R.string.playing_badge) else "", 26f, Ui.kid(kid.color), bold = true))
        info.add(
            Ui.text(
                this,
                if (done) getString(R.string.done_today) else getString(R.string.time_left, Ui.formatClock(remaining)),
                if (done) 20f else 32f,
                if (done) Ui.MUTED else Ui.TEXT,
                bold = true,
            ),
        )
        top.addView(info, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        if (!done && !playing) top.addView(Ui.text(this, "▶", 34f, Ui.kid(kid.color), bold = true))
        card.add(top)
        card.add(Ui.progress(this, if (total > 0) remaining.toFloat() / total else 0f, Ui.kid(kid.color)), topMarginDp = 16)

        when {
            playing -> card.add(Ui.button(this, getString(R.string.pause), Ui.MUTED) {
                store.pause()
                render()
            }, topMarginDp = 16)
            !done -> card.setOnClickListener { play(kid) }
            else -> card.alpha = 0.6f
        }
        return card
    }

    /** Tapping a name first asks "is it really you?" (if the parent turned that on). */
    private fun play(kid: Kid) {
        if (!store.gamesAllowedNow() && !store.parentPlaying()) {
            Toast.makeText(this, GuardService.hoursClosedText(this, store), Toast.LENGTH_LONG).show()
            return
        }
        if (store.kidLockMode == Store.LOCK_OFF) {
            startPlaying(kid)
        } else {
            startActivityForResult(
                Intent(this, KidCheckActivity::class.java).putExtra(KidCheckActivity.EXTRA_KID, kid.id),
                REQUEST_KID_CHECK,
            )
        }
    }

    @Deprecated("Activity.onActivityResult is fine for a no-AndroidX app")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_PARENT && resultCode == RESULT_OK) {
            store.startParentPlayingUntilScreenOff()
            val game = blockedGame
            reason = null
            blockedGame = null
            if (game != null && launch(game)) return
            render()
            return
        }
        if (requestCode != REQUEST_KID_CHECK || resultCode != RESULT_OK) return
        store.kid(data?.getStringExtra(KidCheckActivity.EXTRA_KID))?.let { startPlaying(it) }
    }

    private fun startPlaying(kid: Kid) {
        store.setActive(kid.id)
        val game = blockedGame
        reason = null
        blockedGame = null
        if (game != null && launch(game)) return
        render()
    }

    private fun gamePicker(): LinearLayout {
        val box = Ui.column(this)
        val games = store.sortByRecentUse(
            store.games().mapNotNull { pkg ->
                runCatching {
                    val info = packageManager.getApplicationInfo(pkg, 0)
                    Triple(pkg, packageManager.getApplicationLabel(info).toString(), packageManager.getApplicationIcon(info))
                }.getOrNull()
            },
            { it.first },
            { it.second },
        )

        if (games.isEmpty()) {
            box.add(Ui.text(this, getString(R.string.no_games_ask_parent), 16f, Ui.MUTED, center = true))
            return box
        }
        box.add(Ui.text(this, getString(R.string.pick_game), 20f, bold = true))
        for ((pkg, label, icon) in games) {
            val tile = Ui.row(this).apply {
                background = Ui.rounded(Ui.CARD, 16, this@MainActivity)
                val p = dp(12)
                setPadding(p, p, p, p)
                setOnClickListener { launch(pkg) }
            }
            tile.addView(ImageView(this).apply { setImageDrawable(icon) }, LinearLayout.LayoutParams(dp(48), dp(48)))
            tile.addView(Ui.text(this, label, 20f).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), 0, 0, 0)
            })
            box.add(tile, topMarginDp = 10)
        }
        return box
    }

    private fun launch(pkg: String): Boolean {
        val intent = packageManager.getLaunchIntentForPackage(pkg) ?: return false
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }

    private fun goHome() {
        startActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        reason = null
        blockedGame = null
    }

    companion object {
        const val ACTION_PAUSE = "com.mckogan.playtime.PAUSE"
        const val ACTION_END_PARENT = "com.mckogan.playtime.END_PARENT"
        const val EXTRA_REASON = "reason"
        const val EXTRA_GAME = "game"
        const val EXTRA_KID = "kid"
        const val REASON_WHO = "who"
        const val REASON_TIME_UP = "time_up"
        const val REASON_HOURS = "hours"
        private const val REQUEST_KID_CHECK = 1
        private const val GREEN = 0xFF2EB872.toInt()
        private const val REQUEST_PARENT = 2
    }
}
