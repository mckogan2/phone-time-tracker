package com.mckogan.playtime

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.LocalDate
import java.util.UUID

data class Kid(
    val id: String,
    val name: String,
    val dailyMinutes: Int,
    val color: Int,
)

/** All app state, kept in SharedPreferences so it survives restarts. */
class Store(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("playtime", Context.MODE_PRIVATE)

    init {
        if (!prefs.contains(KEY_KIDS)) {
            saveKids(
                listOf(
                    Kid(newId(), "Or", DEFAULT_MINUTES, KID_COLORS[0]),
                    Kid(newId(), "Shahar", DEFAULT_MINUTES, KID_COLORS[1]),
                )
            )
        }
    }

    // ---- Kids ----

    fun kids(): List<Kid> {
        val arr = JSONArray(prefs.getString(KEY_KIDS, "[]"))
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Kid(o.getString("id"), o.getString("name"), o.getInt("minutes"), o.getInt("color"))
        }
    }

    fun kid(id: String?): Kid? = id?.let { kidId -> kids().firstOrNull { it.id == kidId } }

    private fun saveKids(kids: List<Kid>) {
        val arr = JSONArray()
        kids.forEach {
            arr.put(
                JSONObject()
                    .put("id", it.id)
                    .put("name", it.name)
                    .put("minutes", it.dailyMinutes)
                    .put("color", it.color)
            )
        }
        prefs.edit().putString(KEY_KIDS, arr.toString()).apply()
    }

    fun addKid(name: String, minutes: Int) {
        val kids = kids()
        saveKids(kids + Kid(newId(), name, minutes, KID_COLORS[kids.size % KID_COLORS.size]))
    }

    fun updateKid(id: String, name: String, minutes: Int) {
        saveKids(kids().map { if (it.id == id) it.copy(name = name, dailyMinutes = minutes) else it })
    }

    fun removeKid(id: String) {
        if (activeKidId() == id) pause()
        saveKids(kids().filterNot { it.id == id })
        prefs.edit().remove(usedKey(id)).remove(dayKey(id)).apply()
    }

    // ---- Daily time ----

    /** Milliseconds used today. Usage from an earlier day counts as zero (midnight reset). */
    fun usedMs(kidId: String): Long =
        if (prefs.getString(dayKey(kidId), null) == today()) prefs.getLong(usedKey(kidId), 0L) else 0L

    fun remainingMs(kid: Kid): Long = kid.dailyMinutes * MINUTE_MS - usedMs(kid.id)

    fun addUsed(kidId: String, ms: Long) {
        prefs.edit()
            .putLong(usedKey(kidId), usedMs(kidId) + ms)
            .putString(dayKey(kidId), today())
            .apply()
    }

    /** Extra time for today only: stored as negative usage. */
    fun addBonus(kidId: String, minutes: Int) = addUsed(kidId, -minutes * MINUTE_MS)

    fun resetToday(kidId: String) {
        prefs.edit().putLong(usedKey(kidId), 0L).putString(dayKey(kidId), today()).apply()
    }

    // ---- Who is playing ----

    fun activeKidId(): String? = prefs.getString(KEY_ACTIVE, null)

    fun activeKid(): Kid? = kid(activeKidId())

    /** Wall-clock time when the current kid was selected. */
    fun activeSince(): Long = prefs.getLong(KEY_ACTIVE_SINCE, 0L)

    fun setActive(kidId: String) {
        prefs.edit()
            .putString(KEY_ACTIVE, kidId)
            .putLong(KEY_ACTIVE_SINCE, System.currentTimeMillis())
            .apply()
    }

    fun pause() {
        prefs.edit().remove(KEY_ACTIVE).remove(KEY_ACTIVE_SINCE).apply()
    }

    // ---- Games ----

    fun games(): Set<String> = prefs.getStringSet(KEY_GAMES, emptySet())!!.toSet()

    fun setGames(packages: Set<String>) {
        prefs.edit().putStringSet(KEY_GAMES, packages.toSet()).apply()
    }

    // ---- Parent PIN ----

    fun hasPin(): Boolean = prefs.contains(KEY_PIN_HASH)

    fun setPin(pin: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }.toHex()
        prefs.edit().putString(KEY_PIN_SALT, salt).putString(KEY_PIN_HASH, hash(salt, pin)).apply()
    }

    fun checkPin(pin: String): Boolean {
        val salt = prefs.getString(KEY_PIN_SALT, null) ?: return false
        return hash(salt, pin) == prefs.getString(KEY_PIN_HASH, null)
    }

    var pinLockedUntil: Long
        get() = prefs.getLong(KEY_PIN_LOCKED_UNTIL, 0L)
        set(value) = prefs.edit().putLong(KEY_PIN_LOCKED_UNTIL, value).apply()

    var pinFailures: Int
        get() = prefs.getInt(KEY_PIN_FAILURES, 0)
        set(value) = prefs.edit().putInt(KEY_PIN_FAILURES, value).apply()

    // ---- Settings lock ----

    var protectSettings: Boolean
        get() = prefs.getBoolean(KEY_PROTECT_SETTINGS, false)
        set(value) = prefs.edit().putBoolean(KEY_PROTECT_SETTINGS, value).apply()

    fun settingsUnlocked(): Boolean = System.currentTimeMillis() < prefs.getLong(KEY_SETTINGS_UNTIL, 0L)

    fun unlockSettings() {
        prefs.edit()
            .putLong(KEY_SETTINGS_UNTIL, System.currentTimeMillis() + SETTINGS_UNLOCK_MS)
            .apply()
    }

    private fun usedKey(id: String) = "used_$id"
    private fun dayKey(id: String) = "day_$id"
    private fun today() = LocalDate.now().toString()
    private fun newId() = UUID.randomUUID().toString()

    private fun hash(salt: String, pin: String): String =
        MessageDigest.getInstance("SHA-256").digest((salt + pin).toByteArray()).toHex()

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }

    companion object {
        const val MINUTE_MS = 60_000L
        const val DEFAULT_MINUTES = 45
        const val SETTINGS_UNLOCK_MS = 5 * MINUTE_MS

        val KID_COLORS = intArrayOf(
            0xFF4F7CFF.toInt(), // blue
            0xFF2EB872.toInt(), // green
            0xFFB45CE6.toInt(), // purple
            0xFFFF8A3D.toInt(), // orange
        )

        private const val KEY_KIDS = "kids"
        private const val KEY_ACTIVE = "active_kid"
        private const val KEY_ACTIVE_SINCE = "active_since"
        private const val KEY_GAMES = "games"
        private const val KEY_PIN_HASH = "pin_hash"
        private const val KEY_PIN_SALT = "pin_salt"
        private const val KEY_PIN_FAILURES = "pin_failures"
        private const val KEY_PIN_LOCKED_UNTIL = "pin_locked_until"
        private const val KEY_PROTECT_SETTINGS = "protect_settings"
        private const val KEY_SETTINGS_UNTIL = "settings_until"
    }
}
