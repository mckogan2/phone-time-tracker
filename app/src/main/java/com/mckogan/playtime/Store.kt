package com.mckogan.playtime

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.os.SystemClock
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

    private val appContext: Context = context.applicationContext
    private val prefs: SharedPreferences =
        appContext.getSharedPreferences("playtime", Context.MODE_PRIVATE)

    init {
        val kid1 = appContext.getString(R.string.default_kid_1)
        val kid2 = appContext.getString(R.string.default_kid_2)
        if (!prefs.contains(KEY_KIDS)) {
            saveKids(
                listOf(
                    Kid(newId(), kid1, DEFAULT_MINUTES, KID_COLORS[0]),
                    Kid(newId(), kid2, DEFAULT_MINUTES, KID_COLORS[1]),
                )
            )
        } else if (!prefs.getBoolean(KEY_NAMES_MIGRATED, false) && kid1 != "Or") {
            // One-time: the first version always used English names. Use the app's language once.
            saveKids(kids().map {
                when (it.name) {
                    "Or" -> it.copy(name = kid1)
                    "Shahar" -> it.copy(name = kid2)
                    else -> it
                }
            })
        }
        if (kid1 != "Or") prefs.edit().putBoolean(KEY_NAMES_MIGRATED, true).apply()
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
    // Timed games = (apps marked as games, if auto is on) + apps the parent added - apps the parent removed.

    fun games(): Set<String> {
        val auto = if (autoGames) detectedGames() else emptySet()
        return auto - excludedGames() + addedGames()
    }

    var autoGames: Boolean
        get() = prefs.getBoolean(KEY_AUTO_GAMES, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_GAMES, value).apply()

    private fun addedGames(): Set<String> = prefs.getStringSet(KEY_GAMES, emptySet())!!.toSet()

    private fun excludedGames(): Set<String> = prefs.getStringSet(KEY_EXCLUDED_GAMES, emptySet())!!.toSet()

    /** Records the parent's tick/untick for one app in the "Choose games" list. */
    fun setGameChoice(pkg: String, timed: Boolean) {
        val added = addedGames().toMutableSet()
        val excluded = excludedGames().toMutableSet()
        val autoTimed = autoGames && pkg in detectedGames()
        added -= pkg
        excluded -= pkg
        if (timed && !autoTimed) added += pkg
        if (!timed && autoTimed) excluded += pkg
        prefs.edit().putStringSet(KEY_GAMES, added).putStringSet(KEY_EXCLUDED_GAMES, excluded).apply()
    }

    /** True if the app's developer marked it as a game. */
    @Suppress("DEPRECATION") // FLAG_IS_GAME is the older way some games still use.
    fun isMarkedGame(pkg: String): Boolean {
        val info = runCatching { appContext.packageManager.getApplicationInfo(pkg, 0) }.getOrNull() ?: return false
        return info.category == ApplicationInfo.CATEGORY_GAME || info.flags and ApplicationInfo.FLAG_IS_GAME != 0
    }

    /** Installed apps marked as games. Cached for a minute because the guard asks every second. */
    fun detectedGames(refresh: Boolean = false): Set<String> {
        val now = SystemClock.elapsedRealtime()
        if (!refresh) detectedCache?.let { if (now - detectedAt < DETECT_CACHE_MS) return it }
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val found = appContext.packageManager.queryIntentActivities(launcher, 0)
            .map { it.activityInfo.packageName }
            .filter { it != appContext.packageName && isMarkedGame(it) }
            .toSet()
        detectedCache = found
        detectedAt = now
        return found
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
        private const val KEY_NAMES_MIGRATED = "names_migrated"
        private const val KEY_EXCLUDED_GAMES = "excluded_games"
        private const val KEY_AUTO_GAMES = "auto_games"
        private const val DETECT_CACHE_MS = 60_000L

        @Volatile private var detectedCache: Set<String>? = null
        @Volatile private var detectedAt = 0L
        private const val KEY_PIN_HASH = "pin_hash"
        private const val KEY_PIN_SALT = "pin_salt"
        private const val KEY_PIN_FAILURES = "pin_failures"
        private const val KEY_PIN_LOCKED_UNTIL = "pin_locked_until"
        private const val KEY_PROTECT_SETTINGS = "protect_settings"
        private const val KEY_SETTINGS_UNTIL = "settings_until"
    }
}
