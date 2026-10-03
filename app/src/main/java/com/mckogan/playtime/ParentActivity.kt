package com.mckogan.playtime

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
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
        root.add(Ui.text(this, "Parent controls", 28f, bold = true))

        root.add(section("Setup"), topMarginDp = 20)
        val guardOn = GuardService.isEnabled(this)
        root.add(
            Ui.text(
                this,
                if (guardOn) "✅ Guard is on" else "⚠️ Guard is off — games are not being timed",
                16f,
                if (guardOn) Ui.TEXT else Ui.DANGER,
            ),
            topMarginDp = 8,
        )
        if (!guardOn) {
            root.add(Ui.button(this, "Turn on guard") { openSystem(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }, topMarginDp = 8)
            root.add(
                Ui.text(
                    this,
                    "In the list, open \"Play Time\" and switch it on. If it is greyed out: App info → ⋮ → Allow restricted settings.",
                    14f,
                    Ui.MUTED,
                ),
                topMarginDp = 6,
            )
            root.add(Ui.button(this, "Open App info", Ui.MUTED) {
                openSystem(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            }, topMarginDp = 8)
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            root.add(Ui.button(this, "Allow notifications", Ui.MUTED) {
                keepOpen = true
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
            }, topMarginDp = 8)
        }

        root.add(section("Children"), topMarginDp = 24)
        val active = store.activeKid()
        for (kid in store.kids()) root.add(kidCard(kid, active?.id == kid.id), topMarginDp = 10)
        root.add(Ui.button(this, "+ Add child", Ui.MUTED) { editKid(null) }, topMarginDp = 10)

        root.add(section("Games"), topMarginDp = 24)
        val labels = store.games().map { label(it) }.sorted()
        root.add(
            Ui.text(this, if (labels.isEmpty()) "No games chosen yet." else labels.joinToString("\n") { "🎮 $it" }, 16f),
            topMarginDp = 8,
        )
        root.add(Ui.button(this, "Choose games") { chooseGames() }, topMarginDp = 8)

        root.add(section("Security"), topMarginDp = 24)
        root.add(Switch(this).apply {
            text = "Lock phone Settings with PIN (stops kids switching the guard off or uninstalling)"
            textSize = 16f
            isChecked = store.protectSettings
            setOnCheckedChangeListener { _, checked -> store.protectSettings = checked }
        }, topMarginDp = 8)
        root.add(Ui.button(this, "Open phone Settings (unlocked 5 min)", Ui.MUTED) {
            openSystem(Intent(Settings.ACTION_SETTINGS))
        }, topMarginDp = 8)
        root.add(Ui.button(this, "Change PIN", Ui.MUTED) {
            keepOpen = true
            startActivity(Intent(this, PinActivity::class.java).putExtra(PinActivity.EXTRA_MODE, PinActivity.MODE_CHANGE))
        }, topMarginDp = 8)

        root.add(Ui.button(this, "Done", Ui.TEXT) { finish() }, topMarginDp = 32)

        setContentView(ScrollView(this).apply {
            setBackgroundColor(Ui.BG)
            addView(root)
        })
    }

    private fun section(title: String) = Ui.text(this, title, 20f, Ui.ACCENT, bold = true)

    private fun kidCard(kid: Kid, playing: Boolean): LinearLayout {
        val card = Ui.card(this)
        val remaining = store.remainingMs(kid)
        card.add(Ui.text(this, kid.name + if (playing) "  ▶ playing" else "", 22f, kid.color, bold = true))
        card.add(
            Ui.text(
                this,
                "${kid.dailyMinutes} min a day · used ${Ui.formatClock(store.usedMs(kid.id).coerceAtLeast(0))} · " +
                    "left ${Ui.formatClock(remaining)}",
                15f,
                Ui.MUTED,
            ),
            topMarginDp = 4,
        )

        val row1 = Ui.row(this)
        row1.addView(Ui.button(this, "+15 min", kid.color, 15f) {
            store.addBonus(kid.id, 15)
            render()
        }, weighted())
        row1.addView(Ui.button(this, "Reset today", Ui.MUTED, 15f) {
            store.resetToday(kid.id)
            render()
        }, weighted(leftMarginDp = 8))
        card.add(row1, topMarginDp = 12)

        val row2 = Ui.row(this)
        row2.addView(Ui.button(this, "Edit", Ui.MUTED, 15f) { editKid(kid) }, weighted())
        if (playing) {
            row2.addView(Ui.button(this, "Stop now", Ui.DANGER, 15f) {
                store.pause()
                render()
            }, weighted(leftMarginDp = 8))
        } else {
            row2.addView(Ui.button(this, "Remove", Ui.DANGER, 15f) { confirmRemove(kid) }, weighted(leftMarginDp = 8))
        }
        card.add(row2, topMarginDp = 8)
        return card
    }

    private fun weighted(leftMarginDp: Int = 0) =
        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(leftMarginDp) }

    private fun editKid(kid: Kid?) {
        val name = EditText(this).apply {
            hint = "Name"
            setText(kid?.name ?: "")
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
        }
        val minutes = EditText(this).apply {
            hint = "Minutes per day"
            setText((kid?.dailyMinutes ?: Store.DEFAULT_MINUTES).toString())
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        val form = Ui.column(this, 20).apply {
            addView(name)
            addView(minutes)
        }
        AlertDialog.Builder(this)
            .setTitle(if (kid == null) "Add child" else "Edit ${kid.name}")
            .setView(form)
            .setPositiveButton("Save") { _, _ ->
                val n = name.text.toString().trim()
                val m = minutes.text.toString().toIntOrNull()?.coerceIn(0, 24 * 60)
                if (n.isNotEmpty() && m != null) {
                    if (kid == null) store.addKid(n, m) else store.updateKid(kid.id, n, m)
                }
                render()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmRemove(kid: Kid) {
        AlertDialog.Builder(this)
            .setTitle("Remove ${kid.name}?")
            .setPositiveButton("Remove") { _, _ ->
                store.removeKid(kid.id)
                render()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun chooseGames() {
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = packageManager.queryIntentActivities(launcher, 0)
            .map { it.activityInfo.packageName }
            .filter { it != packageName }
            .distinct()
            .map { it to label(it) }
            .sortedBy { it.second.lowercase() }
        val selected = store.games().toMutableSet()
        val checked = apps.map { it.first in selected }.toBooleanArray()

        AlertDialog.Builder(this)
            .setTitle("Which apps are games?")
            .setMultiChoiceItems(apps.map { it.second }.toTypedArray(), checked) { _, i, isChecked ->
                if (isChecked) selected += apps[i].first else selected -= apps[i].first
            }
            .setPositiveButton("Save") { _, _ ->
                store.setGames(selected)
                render()
            }
            .setNegativeButton("Cancel", null)
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
