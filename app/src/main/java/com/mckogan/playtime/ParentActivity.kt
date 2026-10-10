package com.mckogan.playtime

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.MediaStore
import android.provider.Settings
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.mckogan.playtime.Ui.add
import com.mckogan.playtime.Ui.dp

/** Parent controls. Only reachable through the PIN pad, and closes as soon as it leaves the screen. */
class ParentActivity : Activity() {

    private lateinit var store: Store

    /** True while we sent the parent to a system screen on purpose, so we don't close ourselves. */
    private var keepOpen = false
    private var stoppedAt = 0L

    /** Which tab is open (null = pick one on first render). */
    private var tab: String? = null
    /** Which Settings page is open (null = the Settings home). */
    private var settingsPage: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        Ui.applyTheme(this)
        super.onCreate(savedInstanceState)
        store = Store(this)
        tab = savedInstanceState?.getString(STATE_TAB)
        settingsPage = savedInstanceState?.getString(STATE_PAGE)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_TAB, tab)
        outState.putString(STATE_PAGE, settingsPage)
    }

    override fun onRestart() {
        super.onRestart()
        // Coming back long after visiting a system screen: ask for the PIN again.
        if (System.currentTimeMillis() - stoppedAt > Store.SETTINGS_UNLOCK_MS) finish()
    }

    override fun onResume() {
        super.onResume()
        keepOpen = false
        GuardService.start(this)
        Sync.start(this)
        if (!isFinishing) render()
    }

    override fun onStop() {
        super.onStop()
        stoppedAt = System.currentTimeMillis()
        // Screen off, Home, or another app: lock the parent area again.
        if (!keepOpen && !isChangingConfigurations) finish()
    }

    private fun render() {
        val root = Ui.column(this, 20)
        root.add(Ui.text(this, getString(R.string.parent_title), 28f, bold = true))

        val guardOn = GuardService.isReady(this)
        if (tab == null) tab = if (guardOn) TAB_KIDS else TAB_SETTINGS
        if (!guardOn) {
            root.add(Ui.text(this, getString(R.string.setup_needed_parent), 15f, Ui.DANGER, bold = true), topMarginDp = 12)
        }
        root.add(tabBar(), topMarginDp = 16)

        when (tab) {
            TAB_KIDS -> kidsTab(root)
            TAB_GAMES -> gamesTab(root)
            TAB_STATS -> statsTab(root)
            else -> settingsTab(root, guardOn)
        }

        root.add(Ui.button(this, getString(R.string.done), Ui.TEXT) { finish() }, topMarginDp = 32)

        setContentView(ScrollView(this).apply {
            setBackgroundColor(Ui.BG)
            addView(root)
        })
    }

    /** Parent-only stats: the last 7 days of kids' time, top games, and my apps. Loads from the cloud if synced. */
    private fun statsTab(root: LinearLayout) {
        val box = Ui.column(this)
        box.add(Ui.text(this, getString(R.string.stats_loading), 16f, Ui.MUTED, center = true), topMarginDp = 16)
        root.add(box, topMarginDp = 16)
        Stats.load(this) { week ->
            if (isFinishing || tab != TAB_STATS) return@load
            box.removeAllViews()
            fillStats(box, week)
        }
    }

    private fun fillStats(box: LinearLayout, week: Stats.Week) {
        if (!Stats.hasAnything(week)) {
            box.add(Ui.text(this, getString(R.string.stats_no_data), 16f, Ui.MUTED, center = true), topMarginDp = 16)
            return
        }
        if (!week.family) box.add(Ui.text(this, getString(R.string.stats_local), 14f, Ui.MUTED, center = true), topMarginDp = 8)

        box.add(section(getString(R.string.stats_daily)), topMarginDp = 16)
        dailyChart(box, week)

        box.add(section(getString(R.string.stats_games)), topMarginDp = 24)
        for (kid in store.kids()) gameBars(box, kid, week.games[kid.id].orEmpty())

        box.add(section(getString(R.string.stats_my_apps)), topMarginDp = 24)
        if (week.myApps.isEmpty()) box.add(Ui.text(this, getString(R.string.stats_none), 15f, Ui.MUTED), topMarginDp = 8)
        for ((pkg, minutes) in week.myApps) {
            val line = Ui.row(this)
            line.addView(Ui.text(this, label(pkg), 16f), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            line.addView(Ui.text(this, getString(R.string.minutes_short, minutes), 16f, bold = true))
            box.add(line, topMarginDp = 8)
        }

        box.add(section(getString(R.string.stats_extra)), topMarginDp = 24)
        box.add(Ui.text(this, getString(R.string.stats_extra_taken, week.extraYes), 16f), topMarginDp = 8)
        box.add(Ui.text(this, getString(R.string.stats_extra_declined, week.extraNo), 16f), topMarginDp = 6)
        box.add(Ui.text(this, getString(R.string.stats_more_asked, week.moreAsked), 16f), topMarginDp = 6)
        box.add(Ui.text(this, getString(R.string.stats_this_phone), 13f, Ui.MUTED), topMarginDp = 8)
    }

    /** One column per day for each kid, in their colors. All kids share one scale, so they compare fairly. */
    private fun dailyChart(box: LinearLayout, week: Stats.Week) {
        val max = week.daily.values.flatten().maxOrNull()?.coerceAtLeast(1L) ?: 1L
        for (kid in store.kids()) {
            val values = week.daily[kid.id] ?: continue
            box.add(Ui.text(this, kid.name, 18f, Ui.kid(kid.color), bold = true), topMarginDp = 12)
            val chart = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.BOTTOM
            }
            val labels = Ui.row(this)
            for ((i, ms) in values.withIndex()) {
                val column = Ui.column(this).apply { gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL }
                val minutes = (ms + 59_999) / 60_000
                column.addView(Ui.text(this, if (ms > 0) minutes.toString() else "", 11f, Ui.MUTED, center = true))
                val bar = View(this).apply { background = Ui.rounded(Ui.kid(kid.color), 6, this@ParentActivity) }
                val height = if (ms <= 0) dp(2) else (dp(110) * ms / max).toInt().coerceAtLeast(dp(4))
                column.addView(bar, LinearLayout.LayoutParams(dp(20), height))
                chart.addView(column, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                labels.addView(
                    Ui.text(this, Stats.weekday(week.days[i]), 12f, Ui.MUTED, center = true),
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
                )
            }
            box.add(chart, topMarginDp = 8)
            box.add(labels, topMarginDp = 4)
        }
    }

    /** A kid's top games for the week as horizontal bars, in their color. */
    private fun gameBars(box: LinearLayout, kid: Kid, games: List<Pair<String, Long>>) {
        if (games.isEmpty()) return
        val rows = Stats.topGames(games)
        val max = rows.maxOf { it.second }.coerceAtLeast(1L)
        box.add(Ui.text(this, kid.name, 16f, Ui.kid(kid.color), bold = true), topMarginDp = 12)
        for ((pkg, ms) in rows) {
            val line = Ui.row(this)
            val name = if (pkg.isEmpty()) getString(R.string.stats_other) else label(pkg)
            line.addView(Ui.text(this, name, 15f), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            line.addView(Ui.text(this, Ui.formatMinutes(this, ms), 14f, Ui.MUTED))
            box.add(line, topMarginDp = 6)
            box.add(Ui.progress(this, ms.toFloat() / max, Ui.kid(kid.color)), topMarginDp = 4)
        }
    }

    private fun tabBar(): LinearLayout {
        val bar = Ui.row(this)
        for ((id, label) in listOf(
            TAB_KIDS to R.string.tab_kids,
            TAB_GAMES to R.string.tab_games,
            TAB_STATS to R.string.tab_stats,
            TAB_SETTINGS to R.string.tab_settings,
        )) {
            val selected = tab == id
            bar.addView(
                Ui.button(this, getString(label), if (selected) Ui.ACCENT else Ui.TRACK, 15f) {
                    tab = id
                    settingsPage = null
                    render()
                }.apply {
                    if (!selected) setTextColor(Ui.TEXT)
                    setPadding(dp(4), dp(10), dp(4), dp(10))
                },
                weighted(leftMarginDp = if (id == TAB_KIDS) 0 else 6),
            )
        }
        return bar
    }

    /** "Parent playing": your own games aren't blocked or counted on this phone for a while. */
    private fun kidsTab(root: LinearLayout) {
        root.add(section(getString(R.string.section_children)), topMarginDp = 24)
        val active = store.activeKid()
        for (kid in store.kids()) root.add(kidCard(kid, active?.id == kid.id), topMarginDp = 10)
        root.add(Ui.button(this, getString(R.string.add_child), Ui.MUTED) { editKid(null) }, topMarginDp = 10)

        val modes = listOf(
            Triple(Store.LOCK_OFF, R.string.lock_off, "🔓"),
            Triple(Store.LOCK_PICTURE, R.string.lock_picture, "🧩"),
            Triple(Store.LOCK_NUMBER, R.string.lock_number, "🔢"),
            Triple(Store.LOCK_PARENT, R.string.lock_parent, "🔒"),
        )
        val open = store.sectionOpen(KEY_PROTECTION, false)
        val current = modes.first { it.first == store.kidLockMode }
        settingsGroup(root, R.string.section_protection) { group ->
            settingsRow(group, current.third, getString(current.second), if (open) "▾" else "▸") {
                store.setSectionOpen(KEY_PROTECTION, !open)
                render()
            }
            if (open) {
                for ((mode, labelRes, icon) in modes) {
                    settingsRow(group, icon, getString(labelRes), if (store.kidLockMode == mode) "✓" else "") {
                        store.kidLockMode = mode
                        store.setSectionOpen(KEY_PROTECTION, false)
                        render()
                    }
                }
            }
        }
        if (open) root.add(Ui.text(this, getString(R.string.protection_hint), 14f, Ui.MUTED), topMarginDp = 6)
    }

    /** One line per child: minutes played today and on which games (all family phones). */
    private fun gamesTab(root: LinearLayout) {
        settingsGroup(root, R.string.section_games) { group ->
            settingsRow(group, "🔍", getString(R.string.auto_games), getString(if (store.autoGames) R.string.value_on else R.string.value_off)) {
                store.autoGames = !store.autoGames
                render()
            }
            settingsRow(group, "🎮", getString(R.string.choose_games), store.games().size.toString()) { chooseGames() }
        }
        root.add(Ui.text(this, getString(R.string.auto_games_hint), 14f, Ui.MUTED), topMarginDp = 6)
        val timed = store.sortByRecentUse(store.games().map { it to label(it) }, { it.first }, { it.second })
        if (timed.isEmpty()) root.add(Ui.text(this, getString(R.string.no_games_timed), 16f, Ui.MUTED), topMarginDp = 8)
        for ((pkg, name) in timed) root.add(gameRow(pkg, name), topMarginDp = 8)

        // Allowed hours: games are blocked outside them, even with time left.
        settingsGroup(root, R.string.section_hours) { group ->
            settingsRow(group, "⏰", getString(R.string.hours_switch), getString(if (store.hoursEnabled) R.string.value_on else R.string.value_off)) {
                store.hoursEnabled = !store.hoursEnabled
                render()
            }
            if (store.hoursEnabled) {
                settingsRow(group, "▶️", getString(R.string.row_from), Ui.formatTimeOfDay(this, store.hoursFrom)) {
                    pickTime(store.hoursFrom) { store.hoursFrom = it }
                }
                settingsRow(group, "⏹", getString(R.string.row_to), Ui.formatTimeOfDay(this, store.hoursTo)) {
                    pickTime(store.hoursTo) { store.hoursTo = it }
                }
            }
        }
        root.add(Ui.text(this, getString(R.string.hours_hint), 14f, Ui.MUTED), topMarginDp = 6)

        // The parent's own apps (this phone only): "who's using? → 15/30 → want more?" friction.
        settingsGroup(root, R.string.section_my_apps) { group ->
            settingsRow(group, "📱", getString(R.string.my_apps_choose), store.myApps().size.toString()) { chooseMyApps() }
        }
        root.add(Ui.text(this, getString(R.string.my_apps_hint), 14f, Ui.MUTED), topMarginDp = 6)
        val mine = store.sortByRecentUse(store.myApps().map { it to label(it) }, { it.first }, { it.second })
        if (mine.isNotEmpty()) {
            root.add(Ui.text(this, mine.joinToString("\n") { "📱 ${it.second}" }, 16f), topMarginDp = 8)
        }
    }

    private fun settingsTab(root: LinearLayout, guardOn: Boolean) {
        val usage = GuardService.hasUsageAccess(this)
        val overlay = GuardService.canOverlay(this)
        val notif = Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val battery = getSystemService(PowerManager::class.java)!!.isIgnoringBatteryOptimizations(packageName)
        val allSet = usage && overlay && notif && battery
        val pinOn = store.protectSettings
        val synced = store.familyId != null

        root.add(statusCard(guardOn, pinOn, synced, allSet), topMarginDp = 16)

        val page = settingsPage
        if (page == null) {
            settingsHub(root, allSet, pinOn, synced)
            return
        }
        root.add(Ui.button(this, getString(R.string.settings_back), Ui.MUTED, 16f) {
            settingsPage = null
            render()
        }, topMarginDp = 16)
        when (page) {
            PAGE_PERMISSIONS -> permissionsPage(root, guardOn, usage, overlay, notif, battery)
            PAGE_SECURITY -> securityPage(root)
            PAGE_SYNC -> {
                pageTitle(root, R.string.section_sync)
                addSyncSection(root)
            }
            PAGE_VOICE -> voicePage(root)
            PAGE_PHONE -> phonePage(root)
            PAGE_UPDATE -> updatePage(root)
        }
    }

    /** At-a-glance summary at the top of Settings: is the guard on, is the PIN set, is it synced, are permissions OK. */
    private fun statusCard(guardOn: Boolean, pinOn: Boolean, synced: Boolean, allSet: Boolean): LinearLayout {
        val card = Ui.card(this)
        card.add(Ui.text(
            this,
            getString(if (guardOn) R.string.status_on else R.string.status_attention),
            22f,
            if (guardOn) Ui.TEXT else Ui.DANGER,
            bold = true,
        ))
        val chips = listOf(
            getString(if (guardOn) R.string.chip_guard_on else R.string.chip_guard_off) to guardOn,
            getString(if (pinOn) R.string.chip_pin_on else R.string.chip_pin_off) to pinOn,
            getString(if (synced) R.string.chip_sync_on else R.string.chip_sync_off) to synced,
            getString(if (allSet) R.string.chip_perms_on else R.string.chip_perms_off) to allSet,
        )
        for (pair in chips.chunked(2)) {
            val line = Ui.row(this)
            pair.forEachIndexed { i, (label, ok) ->
                line.addView(chip(label, ok), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (i > 0) marginStart = dp(8)
                })
            }
            card.add(line, topMarginDp = 10)
        }
        return card
    }

    private fun chip(label: String, ok: Boolean): TextView {
        val color = if (ok) OK_GREEN else Ui.DANGER
        return Ui.text(this, label, 14f, color, bold = true, center = true).apply {
            setPadding(dp(8), dp(10), dp(8), dp(10))
            background = Ui.rounded(Ui.blend(color, Ui.CARD, 0.18f), 999, this@ParentActivity)
        }
    }

    /** The Settings home: grouped rows, each showing its current value. Tapping one opens its page. */
    private fun settingsHub(root: LinearLayout, allSet: Boolean, pinOn: Boolean, synced: Boolean) {
        val version = packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
        settingsGroup(root, R.string.group_protection) { group ->
            settingsRow(group, "🔒", getString(R.string.row_pin), getString(if (pinOn) R.string.value_on else R.string.value_off)) {
                openPage(PAGE_SECURITY)
            }
            settingsRow(
                group, "🛡", getString(R.string.section_permissions),
                getString(if (allSet) R.string.value_all_set else R.string.value_needs_setup),
            ) { openPage(PAGE_PERMISSIONS) }
        }
        settingsGroup(root, R.string.group_sync_sound) { group ->
            settingsRow(
                group, "☁️", getString(R.string.section_sync),
                getString(if (synced) R.string.value_connected else R.string.value_not_connected),
            ) { openPage(PAGE_SYNC) }
            settingsRow(group, "🔊", getString(R.string.row_voice), getString(if (store.voiceReminders) R.string.value_on else R.string.value_off)) {
                openPage(PAGE_VOICE)
            }
            settingsRow(group, "🎨", getString(R.string.row_theme), themeLabel(store.theme)) { chooseTheme() }
        }
        settingsGroup(root, R.string.group_phone) { group ->
            settingsRow(group, "📱", getString(R.string.section_phone), "") { openPage(PAGE_PHONE) }
            settingsRow(group, "🔄", getString(R.string.section_update), getString(R.string.version_label, version)) {
                openPage(PAGE_UPDATE)
            }
        }
    }

    private fun openPage(page: String) {
        settingsPage = page
        render()
    }

    private fun settingsGroup(root: LinearLayout, titleRes: Int, rows: (LinearLayout) -> Unit) {
        root.add(Ui.text(this, getString(titleRes), 13f, Ui.MUTED, bold = true), topMarginDp = 22)
        val group = Ui.column(this).apply { background = Ui.rounded(Ui.CARD, 20, this@ParentActivity) }
        rows(group)
        root.add(group, topMarginDp = 8)
    }

    private fun settingsRow(group: LinearLayout, icon: String, title: String, value: String, onClick: () -> Unit) {
        if (group.childCount > 0) {
            group.addView(View(this).apply { setBackgroundColor(Ui.TRACK) }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).apply {
                marginStart = dp(62)
            })
        }
        val row = Ui.row(this).apply {
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setOnClickListener { onClick() }
        }
        row.addView(Ui.text(this, icon, 18f, center = true).apply {
            background = Ui.rounded(Ui.TRACK, 10, this@ParentActivity)
        }, LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginEnd = dp(12) })
        row.addView(Ui.text(this, title, 16f, bold = true), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        if (value.isNotEmpty()) row.addView(Ui.text(this, value, 15f, Ui.MUTED))
        row.addView(Ui.text(this, "›", 20f, Ui.MUTED).apply { setPadding(dp(8), 0, 0, 0) })
        group.addView(row)
    }

    private fun pageTitle(root: LinearLayout, titleRes: Int) {
        root.add(Ui.text(this, getString(titleRes), 22f, Ui.ACCENT, bold = true), topMarginDp = 16)
    }

    private fun permissionsPage(root: LinearLayout, guardOn: Boolean, usage: Boolean, overlay: Boolean, notif: Boolean, battery: Boolean) {
        pageTitle(root, R.string.section_permissions)
        root.add(
            Ui.text(
                this,
                getString(if (guardOn) R.string.guard_on else R.string.guard_off),
                16f,
                if (guardOn) Ui.TEXT else Ui.DANGER,
                bold = true,
            ),
            topMarginDp = 8,
        )
        root.add(
            permissionRow(getString(R.string.perm_usage), getString(R.string.perm_usage_hint), usage) {
                openSystem(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            },
            topMarginDp = 10,
        )
        root.add(
            permissionRow(getString(R.string.perm_overlay), getString(R.string.perm_overlay_hint), overlay) {
                openSystem(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            },
            topMarginDp = 10,
        )
        root.add(
            permissionRow(getString(R.string.perm_notif), getString(R.string.perm_notif_hint), notif) {
                openSystem(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                )
            },
            topMarginDp = 10,
        )
        root.add(
            permissionRow(getString(R.string.perm_battery), getString(R.string.perm_battery_hint), battery) {
                openSystem(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
                )
            },
            topMarginDp = 10,
        )
    }

    private fun securityPage(root: LinearLayout) {
        pageTitle(root, R.string.section_security)
        root.add(Ui.switch(this).apply {
            text = getString(R.string.lock_settings)
            textSize = 16f
            isChecked = store.protectSettings
            setOnCheckedChangeListener { _, checked -> store.protectSettings = checked }
        }, topMarginDp = 8)
        root.add(Ui.switch(this).apply {
            text = getString(R.string.use_fingerprint)
            textSize = 16f
            isChecked = store.useFingerprint
            setOnCheckedChangeListener { _, checked -> store.useFingerprint = checked }
        }, topMarginDp = 8)
        root.add(Ui.button(this, getString(R.string.change_pin), Ui.MUTED) {
            keepOpen = true
            startActivity(Intent(this, PinActivity::class.java).putExtra(PinActivity.EXTRA_MODE, PinActivity.MODE_CHANGE))
        }, topMarginDp = 8)
    }

    private fun voicePage(root: LinearLayout) {
        pageTitle(root, R.string.row_voice)
        val voiceRow = Ui.row(this)
        voiceRow.addView(Ui.switch(this).apply {
            text = getString(R.string.voice_reminders)
            textSize = 16f
            isChecked = store.voiceReminders
            setOnCheckedChangeListener { _, checked -> store.voiceReminders = checked }
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        voiceRow.addView(Ui.button(this, getString(R.string.voice_try), Ui.MUTED, 15f) { Voice.play(this, 5) })
        root.add(voiceRow, topMarginDp = 8)
        root.add(Ui.switch(this).apply {
            text = getString(R.string.extra_setting, Store.EXTRA_MINUTES)
            textSize = 16f
            isChecked = store.offerExtra
            setOnCheckedChangeListener { _, checked -> store.offerExtra = checked }
        }, topMarginDp = 8)
    }

    private fun phonePage(root: LinearLayout) {
        pageTitle(root, R.string.section_phone)
        root.add(Ui.button(this, getString(R.string.open_settings), Ui.MUTED) {
            openSystem(Intent(Settings.ACTION_SETTINGS))
        }, topMarginDp = 8)
        if (Build.VERSION.SDK_INT >= 33) {
            root.add(Ui.button(this, getString(R.string.language), Ui.MUTED) {
                openSystem(Intent(Settings.ACTION_APP_LOCALE_SETTINGS, Uri.parse("package:$packageName")))
            }, topMarginDp = 8)
        }
    }

    // Update: downloads the newest PlayTime.apk in the browser; tapping it installs over this version.
    private fun updatePage(root: LinearLayout) {
        pageTitle(root, R.string.section_update)
        val version = packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
        root.add(Ui.text(this, getString(R.string.version_label, version), 15f, Ui.MUTED), topMarginDp = 8)
        root.add(Ui.button(this, getString(R.string.update_app)) {
            openSystem(Intent(Intent.ACTION_VIEW, Uri.parse(UPDATE_URL)))
        }, topMarginDp = 8)
        root.add(Ui.text(this, getString(R.string.update_hint), 14f, Ui.MUTED), topMarginDp = 4)
    }

    private fun permissionRow(title: String, hint: String, granted: Boolean, turnOn: () -> Unit): LinearLayout {
        val card = Ui.card(this)
        card.add(Ui.text(this, (if (granted) "✅ " else "⬜ ") + title, 18f, bold = true))
        card.add(Ui.text(this, hint, 14f, Ui.MUTED), topMarginDp = 2)
        if (!granted) card.add(Ui.button(this, getString(R.string.turn_on), sizeSp = 16f, onClick = turnOn), topMarginDp = 10)
        return card
    }

    private fun addSyncSection(root: LinearLayout) {
        if (!Sync.isConfigured(this)) {
            root.add(Ui.text(this, getString(R.string.sync_not_available), 14f, Ui.MUTED), topMarginDp = 4)
            return
        }
        if (!Sync.isInFamily(this)) {
            root.add(Ui.text(this, getString(R.string.sync_intro), 14f, Ui.MUTED), topMarginDp = 4)
            root.add(Ui.button(this, getString(R.string.sync_create)) { createFamily() }, topMarginDp = 8)
            root.add(Ui.button(this, getString(R.string.sync_join), Ui.MUTED) { joinFamily() }, topMarginDp = 8)
            return
        }
        val phones = Sync.phoneCount
        root.add(
            Ui.text(
                this,
                if (phones > 0) resources.getQuantityString(R.plurals.sync_on, phones, phones) else getString(R.string.sync_on_unknown),
                16f,
                bold = true,
            ),
            topMarginDp = 4,
        )
        root.add(Ui.button(this, getString(R.string.sync_add_phone)) {
            toast(R.string.sync_working)
            Sync.newJoinCode(this, { showCode(it) }, { toast(R.string.sync_failed) })
        }, topMarginDp = 8)
        root.add(Ui.button(this, getString(R.string.sync_leave), Ui.MUTED) {
            AlertDialog.Builder(this)
                .setTitle(R.string.sync_leave_title)
                .setMessage(R.string.sync_leave_message)
                .setPositiveButton(R.string.sync_leave) { _, _ ->
                    Sync.leaveFamily(this)
                    render()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }, topMarginDp = 8)
    }

    private fun createFamily() {
        toast(R.string.sync_working)
        Sync.createFamily(this, { code ->
            if (!isFinishing) {
                render()
                showCode(code)
            }
        }, { toast(R.string.sync_failed) })
    }

    private fun showCode(code: String) {
        if (!isFinishing) Ui.showFamilyCode(this, code)
    }

    private fun joinFamily() {
        val input = EditText(this).apply {
            hint = getString(R.string.sync_code_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            filters = arrayOf(InputFilter.LengthFilter(Sync.CODE_LENGTH + 2))
            textDirection = View.TEXT_DIRECTION_LTR
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.sync_join)
            .setMessage(R.string.sync_join_message)
            .setView(Ui.column(this, 20).apply { addView(input) })
            .setPositiveButton(R.string.sync_join_button) { _, _ ->
                val code = input.text.toString().replace(" ", "")
                toast(R.string.sync_working)
                Sync.joinFamily(this, code, {
                    toast(R.string.sync_joined)
                    if (!isFinishing) render()
                }, { toast(R.string.sync_bad_code) })
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun toast(text: Int) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

    private fun pickTime(minutes: Int, onPicked: (Int) -> Unit) {
        TimePickerDialog(this, { _, hour, minute ->
            onPicked(hour * 60 + minute)
            render()
        }, minutes / 60, minutes % 60, android.text.format.DateFormat.is24HourFormat(this)).show()
    }

    // ---- Kid photo ----

    private var photoKidId: String? = null

    private fun choosePhoto(kid: Kid) {
        if (kid.photo != null) {
            AlertDialog.Builder(this)
                .setTitle(kid.name)
                .setItems(arrayOf(getString(R.string.photo_change), getString(R.string.photo_remove))) { _, which ->
                    if (which == 0) openPhotoPicker(kid) else {
                        store.setPhoto(kid.id, null)
                        render()
                    }
                }
                .show()
        } else {
            openPhotoPicker(kid)
        }
    }

    private fun openPhotoPicker(kid: Kid) {
        photoKidId = kid.id
        keepOpen = true
        // Android 13+ has a photo picker that needs no permission; older phones use the gallery.
        val intent = if (Build.VERSION.SDK_INT >= 33) {
            Intent(MediaStore.ACTION_PICK_IMAGES)
        } else {
            Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE)
        }
        runCatching { startActivityForResult(intent, REQUEST_PHOTO) }.onFailure { keepOpen = false }
    }

    @Deprecated("Activity.onActivityResult is fine for a no-AndroidX app")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_PHOTO) return
        val kidId = photoKidId ?: return
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) return
        val photo = Photos.fromUri(this, uri)
        if (photo == null) {
            toast(R.string.photo_failed)
        } else {
            store.setPhoto(kidId, photo)
        }
    }

    private fun section(title: String) = Ui.text(this, title, 20f, Ui.ACCENT, bold = true)

    /** ➕ / ➖: the time changes; the button bounces, the phone ticks, and a line says what it is now. */
    private fun adjustTime(kid: Kid, view: View, minutes: Int) {
        store.addBonus(kid.id, minutes)
        Ui.tick(this)
        Ui.bounce(view)
        // Redraw after the bounce has played, so the change is visible on the button first.
        window.decorView.postDelayed({ render() }, 150)
    }

    /** A game as a row: its app icon and name. */
    private fun gameRow(pkg: String, name: String): LinearLayout {
        val row = Ui.row(this)
        row.background = Ui.rounded(Ui.CARD, 16, this)
        row.setPadding(dp(12), dp(12), dp(12), dp(12))
        runCatching { packageManager.getApplicationIcon(pkg) }.getOrNull()?.let { icon ->
            row.addView(ImageView(this).apply { setImageDrawable(icon) }, LinearLayout.LayoutParams(dp(44), dp(44)))
        }
        row.addView(
            Ui.text(this, name, 18f, bold = true).apply { setPadding(dp(14), 0, 0, 0) },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        return row
    }

    /** One kid: photo, name, and four icon buttons. The time is on the main screen. */
    private fun kidCard(kid: Kid, playing: Boolean): LinearLayout {
        val card = Ui.card(this)

        val header = Ui.row(this)
        header.addView(Ui.kidAvatar(this, kid, Ui.kid(kid.color), 60).apply { setOnClickListener { choosePhoto(kid) } })
        header.addView(
            Ui.text(this, kid.name + if (playing) "  " + getString(R.string.playing_badge) else "", 24f, Ui.kid(kid.color), bold = true)
                .apply { setPadding(dp(14), 0, 0, 0) },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        card.add(header)
        val remaining = store.remainingMs(kid)
        card.add(
            Ui.text(
                this,
                if (remaining > 0) getString(R.string.time_left, Ui.formatMinutes(this, remaining)) else getString(R.string.done_today),
                16f,
                Ui.MUTED,
                bold = true,
            ),
            topMarginDp = 6,
        )
        if (store.missingSecret(kid)) {
            card.add(Ui.text(this, getString(R.string.no_secret_set), 14f, Ui.DANGER), topMarginDp = 6)
        }

        val icons = Ui.row(this)
        fun icon(label: String, description: String, color: Int, onClick: (View) -> Unit) =
            Ui.button(this, label, color, 18f) {}.apply {
                setOnClickListener { onClick(this) }
                contentDescription = description
                // Icon only: small padding, and never shorten the icon to "…".
                setPadding(0, dp(8), 0, dp(8))
                ellipsize = null
                maxLines = 1
            }

        icons.addView(icon("+5", getString(R.string.bonus_plus), Ui.ACCENT) { view -> adjustTime(kid, view, 5) }, weighted())
        icons.addView(icon("−5", getString(R.string.bonus_minus), Ui.TRACK) { view -> adjustTime(kid, view, -5) }, weighted(leftMarginDp = 8))
        icons.addView(icon("✏️", getString(R.string.edit), Ui.TRACK) { editKid(kid) }, weighted(leftMarginDp = 8))
        if (playing) {
            icons.addView(icon("⏹️", getString(R.string.stop_now), Ui.TRACK) {
                store.pause()
                render()
            }, weighted(leftMarginDp = 8))
        } else {
            icons.addView(icon("🔄", getString(R.string.reset_today), Ui.TRACK) {
                store.resetToday(kid.id)
                render()
            }, weighted(leftMarginDp = 8))
            icons.addView(icon("🗑️", getString(R.string.remove), Ui.TRACK) { confirmRemove(kid) }, weighted(leftMarginDp = 8))
        }
        card.add(icons, topMarginDp = 14)
        return card
    }

    private fun weighted(leftMarginDp: Int = 0) =
        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(leftMarginDp) }

    private fun editKid(kid: Kid?) {
        val name = EditText(this).apply {
            hint = getString(R.string.name_hint)
            setText(kid?.name ?: "")
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
        }
        val minutes = EditText(this).apply {
            hint = getString(R.string.minutes_hint)
            setText((kid?.dailyMinutes ?: Store.DEFAULT_MINUTES).toString())
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        val form = Ui.column(this, 20).apply {
            addView(name)
            addView(minutes)
        }
        val mode = store.kidLockMode
        var picture = kid?.secretPicture
        val number = EditText(this).apply {
            hint = getString(if (kid?.secretNumber != null) R.string.secret_number_keep else R.string.secret_number_hint)
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            filters = arrayOf(InputFilter.LengthFilter(Store.KID_NUMBER_LENGTH))
        }
        if (mode == Store.LOCK_PICTURE) {
            form.add(Ui.text(this, getString(R.string.secret_picture_label), 16f, bold = true), topMarginDp = 12)
            val grid = GridLayout(this).apply { columnCount = 6 }
            val cells = mutableListOf<TextView>()
            fun paint() = cells.forEach {
                it.background = Ui.rounded(Ui.fill(if (it.text == picture) Ui.ACCENT else Ui.TRACK), 12, this)
            }
            for (animal in Store.SECRET_PICTURES) {
                val cell = Ui.text(this, animal, 26f, center = true).apply {
                    Ui.pressable(this@ParentActivity, this)
                    setOnClickListener {
                        picture = animal
                        paint()
                        Ui.bounce(this)
                    }
                }
                cells += cell
                grid.addView(cell, GridLayout.LayoutParams().apply {
                    width = dp(44)
                    height = dp(44)
                    setMargins(dp(3), dp(3), dp(3), dp(3))
                })
            }
            paint()
            form.add(grid, topMarginDp = 6, fill = false)
        }
        if (mode == Store.LOCK_NUMBER) form.add(number, topMarginDp = 12)
        AlertDialog.Builder(this)
            .setTitle(if (kid == null) getString(R.string.add_child_title) else getString(R.string.edit_title, kid.name))
            .setView(form)
            .setPositiveButton(R.string.save) { _, _ ->
                val n = name.text.toString().trim()
                val m = minutes.text.toString().toIntOrNull()?.coerceIn(0, 24 * 60)
                if (n.isNotEmpty() && m != null) {
                    val id = if (kid == null) store.addKid(n, m) else kid.id.also { store.updateKid(it, n, m) }
                    if (mode == Store.LOCK_PICTURE && picture != kid?.secretPicture) store.setSecretPicture(id, picture)
                    val code = number.text.toString()
                    if (mode == Store.LOCK_NUMBER && code.length == Store.KID_NUMBER_LENGTH) store.setSecretNumber(id, code)
                }
                render()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmRemove(kid: Kid) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.remove_title, kid.name))
            .setPositiveButton(R.string.remove) { _, _ ->
                store.removeKid(kid.id)
                render()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun chooseGames() {
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = packageManager.queryIntentActivities(launcher, 0)
            .map { it.activityInfo.packageName }
            .filter { it != packageName }
            .distinct()
            .map { it to label(it) }
        val detected = store.detectedGames(refresh = true)
        val before = store.games()
        // Games first, then everything else; within each, recently used first, then A–Z.
        val sorted = store.sortByRecentUse(apps, { it.first }, { it.second }).sortedBy { it.first !in detected }
        val checked = sorted.map { it.first in before }.toBooleanArray()
        val names = sorted.map { (if (it.first in detected) "🤖 " else "") + it.second }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.which_apps))
            .setMultiChoiceItems(names, checked) { _, i, isChecked -> checked[i] = isChecked }
            .setPositiveButton(R.string.save) { _, _ ->
                sorted.forEachIndexed { i, (pkg, _) ->
                    if (checked[i] != (pkg in before)) store.setGameChoice(pkg, checked[i])
                }
                render()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun chooseMyApps() {
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = packageManager.queryIntentActivities(launcher, 0)
            .map { it.activityInfo.packageName }
            .filter { it != packageName }
            .distinct()
            .map { it to label(it) }
        val sorted = store.sortByRecentUse(apps, { it.first }, { it.second })
        val selected = store.myApps().toMutableSet()
        val checked = sorted.map { it.first in selected }.toBooleanArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.my_apps_choose)
            .setMultiChoiceItems(sorted.map { it.second }.toTypedArray(), checked) { _, i, isChecked ->
                if (isChecked) selected += sorted[i].first else selected -= sorted[i].first
            }
            .setPositiveButton(R.string.save) { _, _ ->
                store.setMyApps(selected)
                render()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun label(pkg: String): String = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg.substringAfterLast('.')) // Not installed on this phone: short package name.

    /** Opens a system screen; the Settings lock is lifted for 5 minutes so the parent isn't blocked. */
    private fun openSystem(intent: Intent) {
        keepOpen = true
        store.unlockSettings()
        startActivity(intent)
    }

    private fun themeLabel(theme: String) = getString(
        when (theme) {
            Ui.THEME_LIGHT -> R.string.theme_light
            Ui.THEME_DARK -> R.string.theme_dark
            else -> R.string.theme_system
        }
    )

    /** Follow phone / Light / Dark. Redraws this screen in the new colors right away. */
    private fun chooseTheme() {
        AlertDialog.Builder(this)
            .setSingleChoiceItems(THEMES.map { themeLabel(it) }.toTypedArray(), THEMES.indexOf(store.theme)) { dialog, which ->
                dialog.dismiss()
                if (THEMES[which] == store.theme) return@setSingleChoiceItems
                store.theme = THEMES[which]
                keepOpen = true
                recreate()
            }
            .show()
    }

    companion object {
        private const val TAB_KIDS = "kids"
        private const val TAB_GAMES = "games"
        private const val TAB_SETTINGS = "settings"
        private const val TAB_STATS = "stats"
        private const val STATE_TAB = "tab"
        private const val STATE_PAGE = "settings_page"
        private const val KEY_PROTECTION = "protection"
        private const val PAGE_PERMISSIONS = "permissions"
        private const val PAGE_SECURITY = "security"
        private const val PAGE_SYNC = "sync"
        private const val PAGE_VOICE = "voice"
        private const val PAGE_PHONE = "phone"
        private const val PAGE_UPDATE = "update"
        private val OK_GREEN = 0xFF2E9E5B.toInt()
        private val THEMES = listOf(Ui.THEME_SYSTEM, Ui.THEME_LIGHT, Ui.THEME_DARK)
        private const val REQUEST_PHOTO = 7

        /** The newest APK, published by CI to the public releases-only repository. */
        private const val UPDATE_URL =
            "https://github.com/mckogan2/play-time-releases/releases/latest/download/PlayTime.apk"
    }
}
