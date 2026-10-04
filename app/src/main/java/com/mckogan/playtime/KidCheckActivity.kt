package com.mckogan.playtime

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.GridLayout
import android.widget.TextView
import com.mckogan.playtime.Ui.add
import com.mckogan.playtime.Ui.dp

/**
 * "Is it really you?" Shown after a kid taps their name, so a sibling can't use their time.
 * Depending on the parent's choice: find your secret animal, type your secret number,
 * or a parent types the parent PIN. Finishes with RESULT_OK when the check passes.
 */
class KidCheckActivity : Activity() {

    private lateinit var store: Store
    private lateinit var kid: Kid
    private lateinit var mode: String
    private lateinit var messageView: TextView
    private lateinit var dotsView: TextView
    private val handler = Handler(Looper.getMainLooper())
    private var entered = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        Ui.applyTheme(this)
        super.onCreate(savedInstanceState)
        store = Store(this)
        val found = store.kid(intent.getStringExtra(EXTRA_KID))
        mode = store.kidLockMode
        if (found == null) {
            finish()
            return
        }
        kid = found
        // Nothing to check (protection off, or no secret set yet): let them play.
        if (mode == Store.LOCK_OFF || store.missingSecret(kid) || (mode == Store.LOCK_PARENT && !store.hasPin())) {
            pass()
            return
        }

        val root = Ui.column(this, 24).apply {
            setBackgroundColor(Ui.BG)
            gravity = Gravity.CENTER_HORIZONTAL
        }
        val title = when (mode) {
            Store.LOCK_PICTURE -> getString(R.string.check_picture_title, kid.name)
            Store.LOCK_NUMBER -> getString(R.string.check_number_title, kid.name)
            else -> getString(R.string.check_parent_title, kid.name)
        }
        root.add(Ui.text(this, title, 26f, kid.color, bold = true, center = true), topMarginDp = 32)
        dotsView = Ui.text(this, "", 40f, Ui.ACCENT, bold = true, center = true)
        messageView = Ui.text(this, "", 16f, Ui.MUTED, center = true)
        if (mode != Store.LOCK_PICTURE) root.add(dotsView, topMarginDp = 16)
        root.add(messageView, topMarginDp = 8)
        if (mode == Store.LOCK_PICTURE) {
            root.add(pictureGrid(), topMarginDp = 24, fill = false)
        } else {
            root.add(Ui.keypad(this) { press(it) }, topMarginDp = 16, fill = false)
        }
        setContentView(root)
        refresh()
        if (mode == Store.LOCK_PARENT) Fingerprint.ask(this, title) { pass() }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    /** The kid's animal and 5 others, shuffled every time so siblings can't learn a position. */
    private fun pictureGrid(): GridLayout {
        val secret = kid.secretPicture!!
        val choices = (Store.SECRET_PICTURES.filter { it != secret }.shuffled().take(5) + secret).shuffled()
        val grid = GridLayout(this).apply { columnCount = 3 }
        for (animal in choices) {
            val cell = Ui.text(this, animal, 48f, center = true).apply {
                background = Ui.rounded(Ui.CARD, 24, this@KidCheckActivity)
                setOnClickListener { answer(animal == secret) }
            }
            val lp = GridLayout.LayoutParams().apply {
                width = dp(96)
                height = dp(96)
                val m = dp(8)
                setMargins(m, m, m, m)
            }
            grid.addView(cell, lp)
        }
        return grid
    }

    private fun codeLength() = if (mode == Store.LOCK_NUMBER) Store.KID_NUMBER_LENGTH else PIN_LENGTH

    private fun press(key: String) {
        if (lockedForMs() > 0) return
        entered = if (key == "⌫") entered.dropLast(1) else (entered + key).take(codeLength())
        messageView.text = ""
        refresh()
        if (entered.length == codeLength()) {
            val code = entered
            handler.postDelayed({
                entered = ""
                answer(if (mode == Store.LOCK_NUMBER) store.checkSecretNumber(kid, code) else store.checkPin(code))
                refresh()
            }, 150)
        }
    }

    private fun answer(correct: Boolean) {
        if (lockedForMs() > 0) return
        if (correct) {
            store.kidPassed(kid.id)
            pass()
        } else {
            store.kidFailed(kid.id)
            messageView.text = getString(R.string.check_wrong)
            if (lockedForMs() > 0) tickLockout()
        }
    }

    private fun pass() {
        setResult(RESULT_OK, Intent().putExtra(EXTRA_KID, intent.getStringExtra(EXTRA_KID)))
        finish()
    }

    private fun lockedForMs() = store.kidLockedUntil(kid.id) - System.currentTimeMillis()

    private fun tickLockout() {
        val left = lockedForMs()
        if (left > 0) {
            messageView.text = getString(R.string.pin_wait, ((left + 999) / 1000).toInt())
            handler.postDelayed({ tickLockout() }, 500)
        } else {
            messageView.text = ""
        }
    }

    private fun refresh() {
        if (mode != Store.LOCK_PICTURE) {
            dotsView.text = "●".repeat(entered.length) + "○".repeat(codeLength() - entered.length)
        }
        if (lockedForMs() > 0) tickLockout()
    }

    companion object {
        const val EXTRA_KID = "kid"
        private const val PIN_LENGTH = 4
    }
}
