package com.mckogan.playtime

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import com.mckogan.playtime.Ui.add

/** Parent PIN pad. Creates the PIN the first time, then guards the parent screen and Settings. */
class PinActivity : Activity() {

    private lateinit var store: Store
    private lateinit var mode: String
    private lateinit var titleView: TextView
    private lateinit var dotsView: TextView
    private lateinit var messageView: TextView
    private val handler = Handler(Looper.getMainLooper())

    private var entered = ""
    private var creating = false
    private var firstEntry: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        Ui.applyTheme(this)
        super.onCreate(savedInstanceState)
        store = Store(this)
        mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_PARENT
        creating = mode == MODE_CHANGE || mode == MODE_SETUP || !store.hasPin()

        if (mode == MODE_SETTINGS && !store.hasPin()) {
            finish()
            return
        }

        val root = Ui.column(this, 24).apply {
            setBackgroundColor(Ui.BG)
            gravity = Gravity.CENTER_HORIZONTAL
        }
        titleView = Ui.text(this, "", 24f, bold = true, center = true)
        dotsView = Ui.text(this, "", 40f, Ui.ACCENT, bold = true, center = true)
        messageView = Ui.text(this, "", 15f, Ui.MUTED, center = true)
        root.add(titleView, topMarginDp = 32)
        root.add(dotsView, topMarginDp = 24)
        root.add(messageView, topMarginDp = 8)
        root.add(Ui.keypad(this) { press(it) }, topMarginDp = 24, fill = false)
        setContentView(root)
        refresh()
        // Fingerprint first; the PIN pad stays underneath as the backup.
        if (!creating) {
            Fingerprint.ask(this, titleView.text.toString()) {
                store.pinFailures = 0
                onSuccess()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        GuardService.instance?.hideCover()
    }

    @Deprecated("Activity.onBackPressed is fine for a no-AndroidX app")
    override fun onBackPressed() {
        if (mode == MODE_SETTINGS) {
            // Leaving the PIN pad must not reveal the Settings app underneath.
            startActivity(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
        finish()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun press(key: String) {
        if (lockedForMs() > 0) return
        entered = if (key == "⌫") entered.dropLast(1) else (entered + key).take(PIN_LENGTH)
        messageView.text = ""
        refresh()
        if (entered.length == PIN_LENGTH) handler.postDelayed({ submit() }, 150)
    }

    private fun submit() {
        val pin = entered
        entered = ""
        if (creating) {
            val first = firstEntry
            when {
                first == null -> firstEntry = pin
                first == pin -> {
                    store.setPin(pin)
                    creating = false
                    onSuccess()
                    return
                }
                else -> {
                    firstEntry = null
                    messageView.text = getString(R.string.pin_mismatch)
                }
            }
        } else if (store.checkPin(pin)) {
            store.pinFailures = 0
            onSuccess()
            return
        } else {
            val failures = store.pinFailures + 1
            if (failures >= MAX_FAILURES) {
                store.pinFailures = 0
                store.pinLockedUntil = System.currentTimeMillis() + LOCKOUT_MS
                tickLockout()
            } else {
                store.pinFailures = failures
                messageView.text = getString(R.string.pin_wrong)
            }
        }
        refresh()
    }

    private fun onSuccess() {
        when (mode) {
            MODE_PARENT -> startActivity(Intent(this, ParentActivity::class.java))
            MODE_SETTINGS -> store.unlockSettings()
            MODE_SETUP, MODE_VERIFY -> setResult(RESULT_OK)
        }
        finish()
    }

    private fun lockedForMs() = store.pinLockedUntil - System.currentTimeMillis()

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
        titleView.text = when {
            creating && firstEntry == null -> getString(if (mode == MODE_CHANGE) R.string.pin_new else R.string.pin_create)
            creating -> getString(R.string.pin_again)
            mode == MODE_SETTINGS -> getString(R.string.pin_settings)
            else -> getString(R.string.pin_title)
        }
        dotsView.text = "●".repeat(entered.length) + "○".repeat(PIN_LENGTH - entered.length)
        if (lockedForMs() > 0) tickLockout()
    }

    companion object {
        const val EXTRA_MODE = "mode"
        const val MODE_PARENT = "parent"
        const val MODE_SETTINGS = "settings"
        const val MODE_CHANGE = "change"
        const val MODE_SETUP = "setup"

        /** Just checks it's a parent (fingerprint or PIN) and returns RESULT_OK. */
        const val MODE_VERIFY = "verify"
        private const val PIN_LENGTH = 4
        private const val MAX_FAILURES = 5
        private const val LOCKOUT_MS = 30_000L
    }
}
