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
import android.view.View
import android.widget.EditText
import android.widget.GridLayout
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
    private var showPermissions = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
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
        if (!keepOpen) finish()
    }

    private fun render() {
        val root = Ui.column(this, 20)
        root.add(Ui.text(this, getString(R.string.parent_title), 28f, bold = true))
        root.add(parentPlayingCard(), topMarginDp = 12)

        val guardOn = GuardService.isReady(this)
        if (tab == null) tab = if (guardOn) TAB_KIDS else TAB_SETTINGS
        if (!guardOn) {
            root.add(Ui.text(this, getString(R.string.setup_needed_parent), 15f, Ui.DANGER, bold = true), topMarginDp = 12)
        }
        root.add(tabBar(), topMarginDp = 16)

        when (tab) {
            TAB_KIDS -> kidsTab(root)
            TAB_GAMES -> gamesTab(root)
            else -> settingsTab(root, guardOn)
        }

        root.add(Ui.button(this, getString(R.string.done), Ui.TEXT) { finish() }, topMarginDp = 32)

        setContentView(ScrollView(this).apply {
            setBackgroundColor(Ui.BG)
            addView(root)
        })
    }

    private fun tabBar(): LinearLayout {
        val bar = Ui.row(this)
        for ((id, label) in listOf(TAB_KIDS to R.string.tab_kids, TAB_GAMES to R.string.tab_games, TAB_SETTINGS to R.string.tab_settings)) {
            val selected = tab == id
            bar.addView(
                Ui.button(this, getString(label), if (selected) Ui.ACCENT else Ui.TRACK, 15f) {
                    tab = id
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
    private fun parentPlayingCard(): LinearLayout {
        val card = Ui.card(this, 0xFFE6F0FF.toInt())
        if (store.parentPlaying()) {
            card.add(Ui.text(this, getString(R.string.parent_playing_until, Ui.formatTime(this, store.parentPlayingUntil)), 16f, bold = true))
            card.add(Ui.button(this, getString(R.string.parent_playing_end), Ui.MUTED, 15f) {
                store.parentPlayingUntil = 0L
                render()
            }, topMarginDp = 8)
        } else {
            card.add(Ui.text(this, getString(R.string.parent_playing), 16f, bold = true))
            card.add(Ui.text(this, getString(R.string.parent_playing_hint), 14f, Ui.MUTED), topMarginDp = 2)
            val row = Ui.row(this)
            for ((i, minutes) in listOf(15, 30, 60).withIndex()) {
                row.addView(Ui.button(this, getString(R.string.minutes_short, minutes), Ui.ACCENT, 15f) {
                    store.parentPlayingUntil = System.currentTimeMillis() + minutes * Store.MINUTE_MS
                    store.pause()
                    render()
                }, weighted(leftMarginDp = if (i == 0) 0 else 6))
            }
            card.add(row, topMarginDp = 8)
        }
        return card
    }

    private fun kidsTab(root: LinearLayout) {
        root.add(section(getString(R.string.section_today)), topMarginDp = 20)
        root.add(todaySummary(), topMarginDp = 6)

        root.add(section(getString(R.string.section_children)), topMarginDp = 24)
        val active = store.activeKid()
        for (kid in store.kids()) root.add(kidCard(kid, active?.id == kid.id), topMarginDp = 10)
        root.add(Ui.button(this, getString(R.string.add_child), Ui.MUTED) { editKid(null) }, topMarginDp = 10)

        root.add(section(getString(R.string.section_protection)), topMarginDp = 24)
        root.add(Ui.text(this, getString(R.string.protection_hint), 14f, Ui.MUTED), topMarginDp = 4)
        val modes = listOf(
            Store.LOCK_OFF to R.string.lock_off,
            Store.LOCK_PICTURE to R.string.lock_picture,
            Store.LOCK_NUMBER to R.string.lock_number,
            Store.LOCK_PARENT to R.string.lock_parent,
        )
        val group = RadioGroup(this)
        for ((i, pair) in modes.withIndex()) {
            group.addView(RadioButton(this).apply {
                id = i + 1
                text = getString(pair.second)
                textSize = 16f
                isChecked = store.kidLockMode == pair.first
            })
        }
        group.setOnCheckedChangeListener { _, checkedId ->
            store.kidLockMode = modes[checkedId - 1].first
            render()
        }
        root.add(group, topMarginDp = 4)
    }

    /** One line per child: minutes played today and on which games (all family phones). */
    private fun todaySummary(): LinearLayout {
        val card = Ui.card(this)
        val kids = store.kids()
        if (kids.isEmpty()) card.add(Ui.text(this, getString(R.string.today_nothing), 15f, Ui.MUTED))
        for ((i, kid) in kids.withIndex()) {
            val used = store.usedMs(kid.id).coerceAtLeast(0)
            val games = store.gamesToday(kid.id).take(4).joinToString(" · ") { (pkg, ms) ->
                "${label(pkg)} ${(ms + 30_000) / 60_000}"
            }
            val line = Ui.row(this)
            line.addView(Ui.kidAvatar(this, kid, kid.color, 32))
            line.addView(
                Ui.text(
                    this,
                    getString(R.string.today_line, kid.name, Ui.formatMinutes(this, used)) +
                        if (games.isNotEmpty()) "\n$games" else "",
                    15f,
                ).apply { setPadding(dp(12), 0, 0, 0) },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
            )
            card.add(line, topMarginDp = if (i == 0) 0 else 10)
        }
        return card
    }

    private fun gamesTab(root: LinearLayout) {
        root.add(section(getString(R.string.section_games)), topMarginDp = 20)
        val detected = store.detectedGames(refresh = true)
        root.add(Switch(this).apply {
            text = getString(R.string.auto_games)
            textSize = 16f
            isChecked = store.autoGames
            setOnCheckedChangeListener { _, checked ->
                store.autoGames = checked
                render()
            }
        }, topMarginDp = 8)
        root.add(
            Ui.text(this, getString(R.string.auto_games_hint), 14f, Ui.MUTED),
            topMarginDp = 4,
        )
        val timed = store.sortByRecentUse(store.games().map { it to label(it) }, { it.first }, { it.second })
            .map { (pkg, name) -> (if (pkg in detected) "🤖 " else "🎮 ") + name }
        root.add(
            Ui.text(this, if (timed.isEmpty()) getString(R.string.no_games_timed) else timed.joinToString("\n"), 16f),
            topMarginDp = 8,
        )
        root.add(Ui.button(this, getString(R.string.choose_games)) { chooseGames() }, topMarginDp = 8)

        // Allowed hours: games are blocked outside them, even with time left.
        root.add(section(getString(R.string.section_hours)), topMarginDp = 24)
        root.add(Switch(this).apply {
            text = getString(R.string.hours_switch)
            textSize = 16f
            isChecked = store.hoursEnabled
            setOnCheckedChangeListener { _, checked ->
                store.hoursEnabled = checked
                render()
            }
        }, topMarginDp = 8)
        if (store.hoursEnabled) {
            val times = Ui.row(this)
            times.addView(Ui.button(this, getString(R.string.hours_from, Ui.formatTimeOfDay(this, store.hoursFrom)), Ui.MUTED, 15f) {
                pickTime(store.hoursFrom) { store.hoursFrom = it }
            }, weighted())
            times.addView(Ui.button(this, getString(R.string.hours_to, Ui.formatTimeOfDay(this, store.hoursTo)), Ui.MUTED, 15f) {
                pickTime(store.hoursTo) { store.hoursTo = it }
            }, weighted(leftMarginDp = 8))
            root.add(times, topMarginDp = 8)
        }
        root.add(Ui.text(this, getString(R.string.hours_hint), 14f, Ui.MUTED), topMarginDp = 4)
    }

    private fun settingsTab(root: LinearLayout, guardOn: Boolean) {
        root.add(section(getString(R.string.section_permissions)), topMarginDp = 20)
        val usage = GuardService.hasUsageAccess(this)
        val overlay = GuardService.canOverlay(this)
        val notif = Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val battery = getSystemService(PowerManager::class.java)!!.isIgnoringBatteryOptimizations(packageName)
        val allSet = usage && overlay && notif && battery

        if (allSet && !showPermissions) {
            // Everything is on: one line, tap to see the details.
            root.add(Ui.text(this, getString(R.string.all_set), 16f, bold = true).apply {
                setOnClickListener {
                    showPermissions = true
                    render()
                }
            }, topMarginDp = 8)
        } else {
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

        root.add(section(getString(R.string.section_sync)), topMarginDp = 24)
        addSyncSection(root)

        root.add(section(getString(R.string.section_security)), topMarginDp = 24)
        root.add(Switch(this).apply {
            text = getString(R.string.lock_settings)
            textSize = 16f
            isChecked = store.protectSettings
            setOnCheckedChangeListener { _, checked -> store.protectSettings = checked }
        }, topMarginDp = 8)
        root.add(Ui.button(this, getString(R.string.open_settings), Ui.MUTED) {
            openSystem(Intent(Settings.ACTION_SETTINGS))
        }, topMarginDp = 8)
        if (Build.VERSION.SDK_INT >= 33) {
            root.add(Ui.button(this, getString(R.string.language), Ui.MUTED) {
                openSystem(Intent(Settings.ACTION_APP_LOCALE_SETTINGS, Uri.parse("package:$packageName")))
            }, topMarginDp = 8)
        }
        root.add(Ui.button(this, getString(R.string.change_pin), Ui.MUTED) {
            keepOpen = true
            startActivity(Intent(this, PinActivity::class.java).putExtra(PinActivity.EXTRA_MODE, PinActivity.MODE_CHANGE))
        }, topMarginDp = 8)

        // Update: downloads the newest PlayTime.apk in the browser; tapping it installs over this version.
        root.add(section(getString(R.string.section_update)), topMarginDp = 24)
        val version = packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
        root.add(Ui.text(this, getString(R.string.version_label, version), 15f, Ui.MUTED), topMarginDp = 4)
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

    private fun kidCard(kid: Kid, playing: Boolean): LinearLayout {
        val card = Ui.card(this)
        val remaining = store.remainingMs(kid)
        val header = Ui.row(this)
        header.addView(Ui.kidAvatar(this, kid, kid.color, 48).apply { setOnClickListener { choosePhoto(kid) } })
        header.addView(
            Ui.text(this, kid.name + if (playing) "  " + getString(R.string.playing_badge) else "", 22f, kid.color, bold = true)
                .apply { setPadding(dp(12), 0, dp(12), 0) },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        header.addView(Ui.text(this, getString(R.string.photo_button), 15f, Ui.ACCENT, bold = true).apply {
            setOnClickListener { choosePhoto(kid) }
        })
        card.add(header)
        if (store.missingSecret(kid)) {
            card.add(Ui.text(this, getString(R.string.no_secret_set), 14f, Ui.DANGER), topMarginDp = 2)
        }
        card.add(
            Ui.text(
                this,
                getString(
                    R.string.kid_summary,
                    kid.dailyMinutes,
                    Ui.formatClock(store.usedMs(kid.id).coerceAtLeast(0)),
                    Ui.formatClock(remaining),
                ),
                15f,
                Ui.MUTED,
            ),
            topMarginDp = 4,
        )

        val row1 = Ui.row(this)
        row1.addView(Ui.button(this, getString(R.string.bonus_15), kid.color, 15f) {
            store.addBonus(kid.id, 15)
            render()
        }, weighted())
        row1.addView(Ui.button(this, getString(R.string.reset_today), Ui.MUTED, 15f) {
            store.resetToday(kid.id)
            render()
        }, weighted(leftMarginDp = 8))
        card.add(row1, topMarginDp = 12)

        val row2 = Ui.row(this)
        row2.addView(Ui.button(this, getString(R.string.edit), Ui.MUTED, 15f) { editKid(kid) }, weighted())
        if (playing) {
            row2.addView(Ui.button(this, getString(R.string.stop_now), Ui.DANGER, 15f) {
                store.pause()
                render()
            }, weighted(leftMarginDp = 8))
        } else {
            row2.addView(Ui.button(this, getString(R.string.remove), Ui.DANGER, 15f) { confirmRemove(kid) }, weighted(leftMarginDp = 8))
        }
        card.add(row2, topMarginDp = 8)
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
                it.background = Ui.rounded(if (it.text == picture) Ui.ACCENT else Ui.TRACK, 12, this)
            }
            for (animal in Store.SECRET_PICTURES) {
                val cell = Ui.text(this, animal, 26f, center = true).apply {
                    setOnClickListener {
                        picture = animal
                        paint()
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

    private fun label(pkg: String): String = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg.substringAfterLast('.')) // Not installed on this phone: short package name.

    /** Opens a system screen; the Settings lock is lifted for 5 minutes so the parent isn't blocked. */
    private fun openSystem(intent: Intent) {
        keepOpen = true
        store.unlockSettings()
        startActivity(intent)
    }

    companion object {
        private const val TAB_KIDS = "kids"
        private const val TAB_GAMES = "games"
        private const val TAB_SETTINGS = "settings"
        private const val REQUEST_PHOTO = 7

        /** The newest APK, published by CI to the public releases-only repository. */
        private const val UPDATE_URL =
            "https://github.com/mckogan2/play-time-releases/releases/latest/download/PlayTime.apk"
    }
}
