package com.mckogan.playtime

import android.app.usage.UsageStatsManager
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
    /** Secret animal for the "secret picture" protection, or null. */
    val secretPicture: String? = null,
    /** "salt:hash" of the kid's secret number, or null. */
    val secretNumber: String? = null,
    /** Small square JPEG of the kid's face, Base64, or null (shows the first letter instead). */
    val photo: String? = null,
)

/** All app state, kept in SharedPreferences so it survives restarts. */
class Store(context: Context) {

    private val appContext: Context = context.applicationContext
    private val prefs: SharedPreferences =
        appContext.getSharedPreferences("playtime", Context.MODE_PRIVATE)

    init {
        // Decided once: phones that were set up before the Welcome screen existed skip it.
        if (!prefs.contains(KEY_ONBOARDED)) {
            prefs.edit().putBoolean(KEY_ONBOARDED, prefs.contains(KEY_KIDS) || prefs.contains(KEY_PIN_HASH)).apply()
        }
    }

    /** False until the first-run setup (new family or join) is finished. */
    var onboarded: Boolean
        get() = prefs.getBoolean(KEY_ONBOARDED, false)
        set(value) = prefs.edit().putBoolean(KEY_ONBOARDED, value).apply()

    // ---- Kids ----

    fun kids(): List<Kid> {
        val arr = JSONArray(prefs.getString(KEY_KIDS, "[]"))
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Kid(
                o.getString("id"),
                o.getString("name"),
                o.getInt("minutes"),
                o.getInt("color"),
                o.optString("picture").ifEmpty { null },
                o.optString("number").ifEmpty { null },
                o.optString("photo").ifEmpty { null },
            )
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
                    .put("picture", it.secretPicture ?: "")
                    .put("number", it.secretNumber ?: "")
                    .put("photo", it.photo ?: "")
            )
        }
        prefs.edit().putString(KEY_KIDS, arr.toString()).apply()
        sharedChanged()
    }

    /** Adds a child and returns their new ID. */
    fun addKid(name: String, minutes: Int): String {
        val kids = kids()
        val id = newId()
        saveKids(kids + Kid(id, name, minutes, KID_COLORS[kids.size % KID_COLORS.size]))
        return id
    }

    fun updateKid(id: String, name: String, minutes: Int) {
        saveKids(kids().map { if (it.id == id) it.copy(name = name, dailyMinutes = minutes) else it })
    }

    fun removeKid(id: String) {
        if (activeKidId() == id) pause()
        saveKids(kids().filterNot { it.id == id })
        prefs.edit().remove(usedKey(id)).remove(dayKey(id)).remove(sharedKey(id)).apply()
    }

    fun setPhoto(kidId: String, photo: String?) {
        saveKids(kids().map { if (it.id == kidId) it.copy(photo = photo) else it })
    }

    // ---- Allowed game hours (shared by the family) ----

    var hoursEnabled: Boolean
        get() = prefs.getBoolean(KEY_HOURS_ON, false)
        set(value) {
            prefs.edit().putBoolean(KEY_HOURS_ON, value).apply()
            sharedChanged()
        }

    /** Minutes after midnight when games open, e.g. 600 = 10:00. */
    var hoursFrom: Int
        get() = prefs.getInt(KEY_HOURS_FROM, DEFAULT_HOURS_FROM)
        set(value) {
            prefs.edit().putInt(KEY_HOURS_FROM, value).apply()
            sharedChanged()
        }

    /** Minutes after midnight when games close, e.g. 1170 = 19:30. */
    var hoursTo: Int
        get() = prefs.getInt(KEY_HOURS_TO, DEFAULT_HOURS_TO)
        set(value) {
            prefs.edit().putInt(KEY_HOURS_TO, value).apply()
            sharedChanged()
        }

    /** False outside the allowed hours (if the parent turned them on). */
    fun gamesAllowedNow(): Boolean {
        if (!hoursEnabled) return true
        val now = java.time.LocalTime.now().let { it.hour * 60 + it.minute }
        val from = hoursFrom
        val to = hoursTo
        return if (from <= to) now in from until to else now >= from || now < to
    }

    /** True if it's before today's opening time (otherwise games are closed for the night). */
    fun beforeOpening(): Boolean {
        val now = java.time.LocalTime.now().let { it.hour * 60 + it.minute }
        return hoursFrom <= hoursTo && now < hoursFrom
    }

    // ---- "Who's playing" protection ----

    var kidLockMode: String
        get() = prefs.getString(KEY_KID_LOCK, LOCK_OFF) ?: LOCK_OFF
        set(value) {
            prefs.edit().putString(KEY_KID_LOCK, value).apply()
            sharedChanged()
        }

    fun setSecretPicture(kidId: String, picture: String?) {
        saveKids(kids().map { if (it.id == kidId) it.copy(secretPicture = picture) else it })
    }

    fun setSecretNumber(kidId: String, number: String?) {
        val stored = number?.let {
            val salt = ByteArray(8).also { b -> SecureRandom().nextBytes(b) }.toHex()
            "$salt:${hash(salt, it)}"
        }
        saveKids(kids().map { if (it.id == kidId) it.copy(secretNumber = stored) else it })
    }

    fun checkSecretNumber(kid: Kid, number: String): Boolean {
        val (salt, h) = kid.secretNumber?.split(':', limit = 2)?.takeIf { it.size == 2 } ?: return false
        return hash(salt, number) == h
    }

    /** True if the current protection mode needs a secret this kid doesn't have yet. */
    fun missingSecret(kid: Kid): Boolean = when (kidLockMode) {
        LOCK_PICTURE -> kid.secretPicture == null
        LOCK_NUMBER -> kid.secretNumber == null
        else -> false
    }

    fun kidLockedUntil(kidId: String): Long = prefs.getLong("kidlock_$kidId", 0L)

    /** Counts a wrong answer; after [KID_MAX_FAILURES] the kid waits [KID_LOCKOUT_MS]. */
    fun kidFailed(kidId: String) {
        val failures = prefs.getInt("kidfail_$kidId", 0) + 1
        if (failures >= KID_MAX_FAILURES) {
            prefs.edit().putInt("kidfail_$kidId", 0)
                .putLong("kidlock_$kidId", System.currentTimeMillis() + KID_LOCKOUT_MS).apply()
        } else {
            prefs.edit().putInt("kidfail_$kidId", failures).apply()
        }
    }

    fun kidPassed(kidId: String) {
        prefs.edit().putInt("kidfail_$kidId", 0).apply()
    }

    // ---- Daily time ----
    // Used today = this phone's own counter + other family phones' counters (from sync)
    //            + adjustments (+15 min bonuses, resets). Everything from an earlier day counts as zero.

    /** Milliseconds used today, on all family phones. */
    fun usedMs(kidId: String): Long = myUsedMs(kidId) + othersUsedMs(kidId) + adjustMs(kidId)

    /** Milliseconds counted on this phone today. */
    fun myUsedMs(kidId: String): Long =
        if (prefs.getString(dayKey(kidId), null) == today()) prefs.getLong(usedKey(kidId), 0L) else 0L

    private fun shared(kidId: String): JSONObject? =
        prefs.getString(sharedKey(kidId), null)?.let { JSONObject(it) }?.takeIf { it.optString("day") == today() }

    private fun othersUsedMs(kidId: String): Long = shared(kidId)?.optLong("others") ?: 0L

    private fun adjustMs(kidId: String): Long = shared(kidId)?.optLong("adjust") ?: 0L

    private fun saveShared(kidId: String, others: Long, adjust: Long) {
        val o = JSONObject().put("day", today()).put("others", others).put("adjust", adjust)
        prefs.edit().putString(sharedKey(kidId), o.toString()).apply()
    }

    fun remainingMs(kid: Kid): Long = kid.dailyMinutes * MINUTE_MS - usedMs(kid.id)

    fun addUsed(kidId: String, ms: Long, pkg: String? = null) {
        val e = prefs.edit()
            .putLong(usedKey(kidId), myUsedMs(kidId) + ms)
            .putString(dayKey(kidId), today())
        if (pkg != null) {
            val mine = myGames()
            val kid = mine.optJSONObject(kidId) ?: JSONObject().also { mine.put(kidId, it) }
            kid.put(pkg, kid.optLong(pkg) + ms)
            e.putString(KEY_MY_GAMES, JSONObject().put("day", today()).put("kids", mine).toString())
        }
        e.apply()
    }

    // ---- Per-game time today (for the daily summary) ----

    /** {kidId: {pkg: ms}} counted on this phone today. */
    fun myGames(): JSONObject = dayJson(KEY_MY_GAMES)

    private fun dayJson(key: String): JSONObject {
        val o = prefs.getString(key, null)?.let { JSONObject(it) } ?: return JSONObject()
        return if (o.optString("day") == today()) o.optJSONObject("kids") ?: JSONObject() else JSONObject()
    }

    /** Sync: other phones' per-game time today, {kidId: {pkg: ms}}. */
    fun applyRemoteGames(day: String, games: JSONObject) {
        if (day == today()) prefs.edit().putString(KEY_OTHER_GAMES, JSONObject().put("day", day).put("kids", games).toString()).apply()
    }

    /** Minutes per game for [kidId] today on all family phones, most played first. */
    fun gamesToday(kidId: String): List<Pair<String, Long>> {
        val total = mutableMapOf<String, Long>()
        for (source in listOf(myGames(), dayJson(KEY_OTHER_GAMES))) {
            val kid = source.optJSONObject(kidId) ?: continue
            for (pkg in kid.keys()) total[pkg] = (total[pkg] ?: 0L) + kid.optLong(pkg)
        }
        return total.entries.filter { it.value > 0 }.sortedByDescending { it.value }.map { it.key to it.value }
    }

    // ---- Fingerprint (this phone only) ----

    var useFingerprint: Boolean
        get() = prefs.getBoolean(KEY_FINGERPRINT, true)
        set(value) = prefs.edit().putBoolean(KEY_FINGERPRINT, value).apply()

    // ---- My apps: the parent's own apps that ask "how long?" before opening (this phone only) ----

    fun myApps(): Set<String> = prefs.getStringSet(KEY_MY_APPS, emptySet())!!.toSet()

    fun setMyApps(packages: Set<String>) {
        prefs.edit().putStringSet(KEY_MY_APPS, packages.toSet()).apply()
    }

    /**
     * When the current session for [pkg] ends (wall clock, ms). The clock runs whether or not
     * the app is on screen: "15 minutes" means 15 real minutes from when they were chosen.
     */
    fun myAppUntil(pkg: String): Long = prefs.getLong("myapp_until_$pkg", 0L)

    fun startMyApp(pkg: String, minutes: Int) {
        prefs.edit().putLong("myapp_until_$pkg", System.currentTimeMillis() + minutes * MINUTE_MS).apply()
    }

    fun myAppActive(pkg: String): Boolean = System.currentTimeMillis() < myAppUntil(pkg)

    /** Ends the session now ("Close" or "End now"). The next open asks "who's using it?". */
    fun endMyApp(pkg: String) {
        prefs.edit().remove("myapp_until_$pkg").apply()
    }

    // ---- Parent playing (this phone only) ----

    var parentPlayingUntil: Long
        get() = prefs.getLong(KEY_PARENT_UNTIL, 0L)
        set(value) = prefs.edit().putLong(KEY_PARENT_UNTIL, value).apply()

    fun parentPlaying(): Boolean = System.currentTimeMillis() < parentPlayingUntil

    // ---- Floating bubble position (this phone only) ----

    var bubbleX: Int
        get() = prefs.getInt(KEY_BUBBLE_X, -1)
        set(value) = prefs.edit().putInt(KEY_BUBBLE_X, value).apply()

    var bubbleY: Int
        get() = prefs.getInt(KEY_BUBBLE_Y, -1)
        set(value) = prefs.edit().putInt(KEY_BUBBLE_Y, value).apply()

    private fun adjust(kidId: String, deltaMs: Long) {
        saveShared(kidId, othersUsedMs(kidId), adjustMs(kidId) + deltaMs)
        onAdjust?.invoke(kidId, today(), deltaMs)
    }

    /** Extra time for today only. */
    fun addBonus(kidId: String, minutes: Int) = adjust(kidId, -minutes * MINUTE_MS)

    fun resetToday(kidId: String) = adjust(kidId, -usedMs(kidId))

    /** Sync: the other phones' counters and the shared adjustment for [day]. */
    fun applyRemoteUsage(kidId: String, day: String, othersMs: Long, adjustMs: Long) {
        if (day == today()) saveShared(kidId, othersMs, adjustMs)
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
        set(value) {
            prefs.edit().putBoolean(KEY_AUTO_GAMES, value).apply()
            sharedChanged()
        }

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
        sharedChanged()
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

    /**
     * Apps used in the last [RECENT_DAYS] days first (most recent on top), then the rest A–Z.
     * Uses Android's own "last used" times (Usage access); without it the list is just A–Z.
     */
    fun <T> sortByRecentUse(items: List<T>, pkg: (T) -> String, label: (T) -> String): List<T> {
        val lastUsed = lastUsedTimes()
        return items.sortedWith(compareByDescending<T> { lastUsed[pkg(it)] ?: 0L }.thenBy { label(it).lowercase() })
    }

    private fun lastUsedTimes(): Map<String, Long> {
        val usage = appContext.getSystemService(UsageStatsManager::class.java) ?: return emptyMap()
        val now = System.currentTimeMillis()
        val since = now - RECENT_DAYS * 24 * 60 * MINUTE_MS
        val stats = runCatching { usage.queryAndAggregateUsageStats(since, now) }.getOrNull() ?: return emptyMap()
        return stats.mapValues { it.value.lastTimeUsed }.filterValues { it >= since }
    }

    // ---- Parent PIN ----

    fun hasPin(): Boolean = prefs.contains(KEY_PIN_HASH)

    fun setPin(pin: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }.toHex()
        prefs.edit().putString(KEY_PIN_SALT, salt).putString(KEY_PIN_HASH, hash(salt, pin)).apply()
        sharedChanged()
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
        set(value) {
            prefs.edit().putBoolean(KEY_PROTECT_SETTINGS, value).apply()
            sharedChanged()
        }

    fun settingsUnlocked(): Boolean = System.currentTimeMillis() < prefs.getLong(KEY_SETTINGS_UNTIL, 0L)

    fun unlockSettings() {
        prefs.edit()
            .putLong(KEY_SETTINGS_UNTIL, System.currentTimeMillis() + SETTINGS_UNLOCK_MS)
            .apply()
    }

    // ---- Family sync ----

    /** This phone's random ID inside a family. */
    val phoneId: String
        get() = prefs.getString(KEY_PHONE_ID, null) ?: newId().also { prefs.edit().putString(KEY_PHONE_ID, it).apply() }

    var familyId: String?
        get() = prefs.getString(KEY_FAMILY_ID, null)
        set(value) = prefs.edit().putString(KEY_FAMILY_ID, value).apply()

    /** When the shared settings last changed on this phone (or were taken from another phone). */
    val settingsUpdatedAt: Long
        get() = prefs.getLong(KEY_SETTINGS_UPDATED, 0L)

    /** Everything family phones share, as plain values for the online copy. */
    fun sharedSettings(): Map<String, Any?> = mapOf(
        "kids" to prefs.getString(KEY_KIDS, "[]"),
        "autoGames" to autoGames,
        "addedGames" to addedGames().toList(),
        "excludedGames" to excludedGames().toList(),
        "kidLockMode" to kidLockMode,
        "pinHash" to prefs.getString(KEY_PIN_HASH, null),
        "pinSalt" to prefs.getString(KEY_PIN_SALT, null),
        "protectSettings" to protectSettings,
        "hoursEnabled" to hoursEnabled,
        "hoursFrom" to hoursFrom,
        "hoursTo" to hoursTo,
        "updatedAt" to settingsUpdatedAt,
    )

    /** Takes the settings saved by another family phone. */
    fun applySharedSettings(data: Map<String, Any?>) {
        val e = prefs.edit()
        (data["kids"] as? String)?.let { e.putString(KEY_KIDS, it) }
        (data["autoGames"] as? Boolean)?.let { e.putBoolean(KEY_AUTO_GAMES, it) }
        (data["addedGames"] as? List<*>)?.let { e.putStringSet(KEY_GAMES, it.filterIsInstance<String>().toSet()) }
        (data["excludedGames"] as? List<*>)?.let { e.putStringSet(KEY_EXCLUDED_GAMES, it.filterIsInstance<String>().toSet()) }
        (data["kidLockMode"] as? String)?.let { e.putString(KEY_KID_LOCK, it) }
        val pinHash = data["pinHash"] as? String
        val pinSalt = data["pinSalt"] as? String
        if (pinHash != null && pinSalt != null) e.putString(KEY_PIN_HASH, pinHash).putString(KEY_PIN_SALT, pinSalt)
        (data["protectSettings"] as? Boolean)?.let { e.putBoolean(KEY_PROTECT_SETTINGS, it) }
        (data["hoursEnabled"] as? Boolean)?.let { e.putBoolean(KEY_HOURS_ON, it) }
        (data["hoursFrom"] as? Number)?.let { e.putInt(KEY_HOURS_FROM, it.toInt()) }
        (data["hoursTo"] as? Number)?.let { e.putInt(KEY_HOURS_TO, it.toInt()) }
        (data["updatedAt"] as? Number)?.let { e.putLong(KEY_SETTINGS_UPDATED, it.toLong()) }
        e.apply()
    }

    private fun sharedChanged() {
        prefs.edit().putLong(KEY_SETTINGS_UPDATED, System.currentTimeMillis()).apply()
        onSharedChange?.invoke()
    }

    private fun sharedKey(id: String) = "shared_$id"
    private fun usedKey(id: String) = "used_$id"
    private fun dayKey(id: String) = "day_$id"
    fun today(): String = LocalDate.now().toString()
    private fun newId() = UUID.randomUUID().toString()

    private fun hash(salt: String, pin: String): String =
        MessageDigest.getInstance("SHA-256").digest((salt + pin).toByteArray()).toHex()

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }

    companion object {
        const val MINUTE_MS = 60_000L
        const val DEFAULT_MINUTES = 45
        const val SETTINGS_UNLOCK_MS = 5 * MINUTE_MS
        private const val RECENT_DAYS = 14L

        const val LOCK_OFF = "off"
        const val LOCK_PICTURE = "picture"
        const val LOCK_NUMBER = "number"
        const val LOCK_PARENT = "parent"
        const val KID_NUMBER_LENGTH = 3
        private const val KID_MAX_FAILURES = 3
        private const val KID_LOCKOUT_MS = 30_000L

        /** Animals a kid can pick as their secret picture. */
        val SECRET_PICTURES = listOf("🦁", "🐸", "🐵", "🐼", "🐯", "🐶", "🐱", "🐰", "🦄", "🐢", "🐙", "🦋")

        /** Called after a shared setting changes on this phone (set by family sync). */
        @Volatile var onSharedChange: (() -> Unit)? = null

        /** Called after +15 min / reset on this phone: (kidId, day, deltaMs). Set by family sync. */
        @Volatile var onAdjust: ((String, String, Long) -> Unit)? = null

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
        private const val KEY_ONBOARDED = "onboarded"
        private const val KEY_FINGERPRINT = "use_fingerprint"
        private const val KEY_MY_APPS = "my_apps"
        private const val KEY_HOURS_ON = "hours_on"
        private const val KEY_HOURS_FROM = "hours_from"
        private const val KEY_HOURS_TO = "hours_to"
        const val DEFAULT_HOURS_FROM = 10 * 60
        const val DEFAULT_HOURS_TO = 19 * 60 + 30
        private const val KEY_MY_GAMES = "my_games"
        private const val KEY_OTHER_GAMES = "other_games"
        private const val KEY_PARENT_UNTIL = "parent_until"
        private const val KEY_BUBBLE_X = "bubble_x"
        private const val KEY_BUBBLE_Y = "bubble_y"
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
        private const val KEY_KID_LOCK = "kid_lock_mode"
        private const val KEY_PHONE_ID = "phone_id"
        private const val KEY_FAMILY_ID = "family_id"
        private const val KEY_SETTINGS_UPDATED = "settings_updated"
    }
}
