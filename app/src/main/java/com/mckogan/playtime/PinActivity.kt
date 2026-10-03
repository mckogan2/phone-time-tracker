package com.mckogan.playtime

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.mckogan.playtime.Ui.add
import com.mckogan.playtime.Ui.dp

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
        super.onCreate(savedInstanceState)
        store = Store(this)
        mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_PARENT
        creating = mode == MODE_CHANGE || !store.hasPin()

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
        root.add(keypad(), topMarginDp = 24, fill = false)
        setContentView(root)
        refresh()
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

    private fun keypad(): GridLayout {
        val grid = GridLayout(this).apply { columnCount = 3 }
        val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "⌫", "0", "")
        for (key in keys) {
            val size = dp(80)
            val cell = Ui.text(this, key, 28f, bold = true, center = true).apply {
                if (key.isNotEmpty()) {
                    background = Ui.rounded(Ui.CARD, 40, this@PinActivity)
                    setOnClickListener { press(key) }
                }
            }
            val lp = GridLayout.LayoutParams().apply {
                width = size
                height = size
                val m = dp(8)
                setMargins(m, m, m, m)
            }
            grid.addView(cell, lp)
        }
        return grid
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
                    messageView.text = "The PINs didn't match. Try again."
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
                messageView.text = "Wrong PIN"
            }
        }
        refresh()
    }

    private fun onSuccess() {
        when (mode) {
            MODE_PARENT -> startActivity(Intent(this, ParentActivity::class.java))
            MODE_SETTINGS -> store.unlockSettings()
        }
        finish()
    }

    private fun lockedForMs() = store.pinLockedUntil - System.currentTimeMillis()

    private fun tickLockout() {
        val left = lockedForMs()
        if (left > 0) {
            messageView.text = "Too many tries. Wait ${(left + 999) / 1000} s."
            handler.postDelayed({ tickLockout() }, 500)
        } else {
            messageView.text = ""
        }
    }

    private fun refresh() {
        titleView.text = when {
            creating && firstEntry == null -> if (mode == MODE_CHANGE) "Choose a new parent PIN" else "Create a parent PIN"
            creating -> "Enter the PIN again"
            mode == MODE_SETTINGS -> "🔒 Parent PIN needed for Settings"
            else -> "🔒 Parent PIN"
        }
        dotsView.text = "●".repeat(entered.length) + "○".repeat(PIN_LENGTH - entered.length)
        if (lockedForMs() > 0) tickLockout()
    }

    companion object {
        const val EXTRA_MODE = "mode"
        const val MODE_PARENT = "parent"
        const val MODE_SETTINGS = "settings"
        const val MODE_CHANGE = "change"
        private const val PIN_LENGTH = 4
        private const val MAX_FAILURES = 5
        private const val LOCKOUT_MS = 30_000L
    }
}
