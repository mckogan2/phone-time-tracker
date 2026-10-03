package com.mckogan.playtime

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import com.mckogan.playtime.Ui.add
import com.mckogan.playtime.Ui.dp

/** Parent controls. Only reachable through the PIN pad, and closes as soon as it leaves the screen. */
class ParentActivity : Activity() {

    private lateinit var store: Store

    /** True while we sent the parent to a system screen on purpose, so we don't close ourselves. */
    private var keepOpen = false
    private var stoppedAt = 0L

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

        root.add(section(getString(R.string.section_permissions)), topMarginDp = 20)
        val guardOn = GuardService.isReady(this)
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
            permissionRow(
                getString(R.string.perm_usage),
                getString(R.string.perm_usage_hint),
                GuardService.hasUsageAccess(this),
            ) { openSystem(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) },
            topMarginDp = 10,
        )
        root.add(
            permissionRow(
                getString(R.string.perm_overlay),
                getString(R.string.perm_overlay_hint),
                GuardService.canOverlay(this),
            ) {
                openSystem(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            },
            topMarginDp = 10,
        )
        root.add(
            permissionRow(
                getString(R.string.perm_notif),
                getString(R.string.perm_notif_hint),
                Build.VERSION.SDK_INT < 33 ||
                    checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED,
            ) {
                openSystem(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                )
            },
            topMarginDp = 10,
        )
        root.add(
            permissionRow(
                getString(R.string.perm_battery),
                getString(R.string.perm_battery_hint),
                getSystemService(PowerManager::class.java)!!.isIgnoringBatteryOptimizations(packageName),
            ) {
                openSystem(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
                )
            },
            topMarginDp = 10,
        )

        root.add(section(getString(R.string.section_children)), topMarginDp = 24)
        val active = store.activeKid()
        for (kid in store.kids()) root.add(kidCard(kid, active?.id == kid.id), topMarginDp = 10)
        root.add(Ui.button(this, getString(R.string.add_child), Ui.MUTED) { editKid(null) }, topMarginDp = 10)

        root.add(section(getString(R.string.section_games)), topMarginDp = 24)
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
        val timed = store.games().map { (if (it in detected) "🤖 " else "🎮 ") + label(it) }.sortedBy { it.drop(3).lowercase() }
        root.add(
            Ui.text(this, if (timed.isEmpty()) getString(R.string.no_games_timed) else timed.joinToString("\n"), 16f),
            topMarginDp = 8,
        )
        root.add(Ui.button(this, getString(R.string.choose_games)) { chooseGames() }, topMarginDp = 8)

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

        root.add(Ui.button(this, getString(R.string.done), Ui.TEXT) { finish() }, topMarginDp = 32)

        setContentView(ScrollView(this).apply {
            setBackgroundColor(Ui.BG)
            addView(root)
        })
    }

    private fun permissionRow(title: String, hint: String, granted: Boolean, turnOn: () -> Unit): LinearLayout {
        val card = Ui.card(this)
        card.add(Ui.text(this, (if (granted) "✅ " else "⬜ ") + title, 18f, bold = true))
        card.add(Ui.text(this, hint, 14f, Ui.MUTED), topMarginDp = 2)
        if (!granted) card.add(Ui.button(this, getString(R.string.turn_on), sizeSp = 16f, onClick = turnOn), topMarginDp = 10)
        return card
    }

    private fun section(title: String) = Ui.text(this, title, 20f, Ui.ACCENT, bold = true)

    private fun kidCard(kid: Kid, playing: Boolean): LinearLayout {
        val card = Ui.card(this)
        val remaining = store.remainingMs(kid)
        card.add(Ui.text(this, kid.name + if (playing) "  " + getString(R.string.playing_badge) else "", 22f, kid.color, bold = true))
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
        AlertDialog.Builder(this)
            .setTitle(if (kid == null) getString(R.string.add_child_title) else getString(R.string.edit_title, kid.name))
            .setView(form)
            .setPositiveButton(R.string.save) { _, _ ->
                val n = name.text.toString().trim()
                val m = minutes.text.toString().toIntOrNull()?.coerceIn(0, 24 * 60)
                if (n.isNotEmpty() && m != null) {
                    if (kid == null) store.addKid(n, m) else store.updateKid(kid.id, n, m)
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
        // Games first, then everything else, each alphabetically.
        val sorted = apps.sortedWith(compareBy({ it.first !in detected }, { it.second.lowercase() }))
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
    }.getOrDefault(pkg)

    /** Opens a system screen; the Settings lock is lifted for 5 minutes so the parent isn't blocked. */
    private fun openSystem(intent: Intent) {
        keepOpen = true
        store.unlockSettings()
        startActivity(intent)
    }
}
