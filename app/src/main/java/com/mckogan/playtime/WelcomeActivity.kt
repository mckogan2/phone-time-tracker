package com.mckogan.playtime

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.mckogan.playtime.Ui.add
import com.mckogan.playtime.Ui.dp

/**
 * First-run setup on a new phone: start a new family (parent PIN → children → optional code for
 * the other parent's phone) or join an existing family with a code. Ends in Parent controls so
 * the permissions can be turned on.
 */
class WelcomeActivity : Activity() {

    private lateinit var store: Store
    private var step = STEP_WELCOME
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        Ui.applyTheme(this)
        super.onCreate(savedInstanceState)
        store = Store(this)
        step = savedInstanceState?.getInt(KEY_STEP) ?: STEP_WELCOME
        render()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_STEP, step)
    }

    @Deprecated("Activity.onBackPressed is fine for a no-AndroidX app")
    override fun onBackPressed() {
        when (step) {
            STEP_WELCOME -> super.onBackPressed()
            STEP_OTHER_PARENT -> go(STEP_KIDS)
            else -> go(STEP_WELCOME)
        }
    }

    @Deprecated("Activity.onActivityResult is fine for a no-AndroidX app")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_PIN && resultCode == RESULT_OK) go(STEP_KIDS)
    }

    private fun go(next: Int) {
        step = next
        render()
    }

    private fun render() {
        val root = Ui.column(this, 24)
        when (step) {
            STEP_WELCOME -> welcome(root)
            STEP_KIDS -> kids(root)
            STEP_OTHER_PARENT -> otherParent(root)
            STEP_JOIN -> join(root)
        }
        setContentView(ScrollView(this).apply {
            setBackgroundColor(Ui.BG)
            addView(root)
        })
    }

    private fun welcome(root: LinearLayout) {
        root.add(Ui.text(this, getString(R.string.home_title), 34f, bold = true, center = true), topMarginDp = 32)
        root.add(Ui.text(this, getString(R.string.welcome_subtitle), 17f, Ui.MUTED, center = true), topMarginDp = 12)
        root.add(Ui.button(this, getString(R.string.welcome_new)) {
            // An existing PIN (e.g. after going back) is kept; otherwise create one first.
            if (store.hasPin()) {
                go(STEP_KIDS)
            } else {
                startActivityForResult(
                    Intent(this, PinActivity::class.java).putExtra(PinActivity.EXTRA_MODE, PinActivity.MODE_SETUP),
                    REQUEST_PIN,
                )
            }
        }, topMarginDp = 40)
        if (Sync.isConfigured(this)) {
            root.add(Ui.button(this, getString(R.string.welcome_join), Ui.MUTED) { go(STEP_JOIN) }, topMarginDp = 12)
            root.add(Ui.text(this, getString(R.string.welcome_join_hint), 14f, Ui.MUTED, center = true), topMarginDp = 6)
        }
    }

    private fun kids(root: LinearLayout) {
        root.add(Ui.text(this, getString(R.string.welcome_kids_title), 28f, bold = true), topMarginDp = 16)
        root.add(Ui.text(this, getString(R.string.welcome_kids_hint), 15f, Ui.MUTED), topMarginDp = 4)

        for (kid in store.kids()) {
            val row = Ui.row(this).apply {
                background = Ui.rounded(Ui.CARD, 16, this@WelcomeActivity)
                val p = dp(12)
                setPadding(p, p, p, p)
            }
            row.addView(
                Ui.text(this, kid.name, 20f, kid.color, bold = true),
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
            )
            row.addView(Ui.text(this, Ui.formatMinutes(this, kid.dailyMinutes * Store.MINUTE_MS), 16f, Ui.MUTED))
            row.addView(Ui.text(this, "  ✕", 20f, Ui.DANGER).apply {
                setOnClickListener {
                    store.removeKid(kid.id)
                    render()
                }
            })
            root.add(row, topMarginDp = 10)
        }

        val name = EditText(this).apply {
            hint = getString(R.string.name_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
        }
        val minutes = EditText(this).apply {
            hint = getString(R.string.minutes_hint)
            setText(Store.DEFAULT_MINUTES.toString())
            inputType = InputType.TYPE_CLASS_NUMBER
            filters = arrayOf(InputFilter.LengthFilter(4))
        }
        val form = Ui.card(this)
        form.add(name)
        form.add(minutes, topMarginDp = 4)
        form.add(Ui.button(this, getString(R.string.add_child), Ui.MUTED, 16f) {
            val n = name.text.toString().trim()
            val m = minutes.text.toString().toIntOrNull()?.coerceIn(0, 24 * 60)
            if (n.isNotEmpty() && m != null) {
                store.addKid(n, m)
                render()
            } else {
                name.error = getString(R.string.name_hint)
            }
        }, topMarginDp = 8)
        root.add(form, topMarginDp = 16)

        if (store.kids().isNotEmpty()) {
            root.add(Ui.button(this, getString(R.string.welcome_next)) {
                if (Sync.isConfigured(this)) go(STEP_OTHER_PARENT) else finishSetup()
            }, topMarginDp = 24)
        }
    }

    private fun otherParent(root: LinearLayout) {
        root.add(Ui.text(this, getString(R.string.welcome_other_title), 26f, bold = true), topMarginDp = 16)
        root.add(Ui.text(this, getString(R.string.welcome_other_hint), 15f, Ui.MUTED), topMarginDp = 6)
        val status = Ui.text(this, "", 15f, Ui.MUTED, center = true)
        root.add(Ui.button(this, getString(R.string.welcome_other_yes)) {
            if (busy) return@button
            busy = true
            status.text = getString(R.string.sync_working)
            Sync.createFamily(this, { code ->
                busy = false
                if (!isFinishing) Ui.showFamilyCode(this, code) { finishSetup() }
            }, {
                busy = false
                status.text = getString(R.string.welcome_offline)
            })
        }, topMarginDp = 24)
        root.add(Ui.button(this, getString(R.string.welcome_other_later), Ui.MUTED) { finishSetup() }, topMarginDp = 12)
        root.add(status, topMarginDp = 12)
    }

    private fun join(root: LinearLayout) {
        root.add(Ui.text(this, getString(R.string.sync_join), 28f, bold = true), topMarginDp = 16)
        root.add(Ui.text(this, getString(R.string.welcome_join_steps), 15f, Ui.MUTED), topMarginDp = 6)
        val input = EditText(this).apply {
            hint = getString(R.string.sync_code_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            filters = arrayOf(InputFilter.LengthFilter(Sync.CODE_LENGTH + 2))
            textSize = 28f
            gravity = Gravity.CENTER
            textDirection = View.TEXT_DIRECTION_LTR
        }
        root.add(input, topMarginDp = 16)
        val status: TextView = Ui.text(this, "", 15f, Ui.MUTED, center = true)
        root.add(Ui.button(this, getString(R.string.sync_join_button)) {
            if (busy) return@button
            busy = true
            status.text = getString(R.string.sync_working)
            Sync.joinFamily(this, input.text.toString().replace(" ", ""), {
                busy = false
                finishSetup()
            }, {
                busy = false
                status.text = getString(R.string.sync_bad_code)
            })
        }, topMarginDp = 16)
        root.add(status, topMarginDp = 12)
    }

    /** Setup done: kids' screen underneath, Parent controls on top for the permissions. */
    private fun finishSetup() {
        if (isFinishing) return
        store.onboarded = true
        startActivities(
            arrayOf(
                Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                Intent(this, ParentActivity::class.java),
            )
        )
        finish()
    }

    companion object {
        private const val STEP_WELCOME = 0
        private const val STEP_KIDS = 1
        private const val STEP_OTHER_PARENT = 2
        private const val STEP_JOIN = 3
        private const val REQUEST_PIN = 1
        private const val KEY_STEP = "step"
    }
}
