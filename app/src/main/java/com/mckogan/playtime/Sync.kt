package com.mckogan.playtime

import android.content.Context
import android.os.SystemClock
import com.google.firebase.FirebaseApp
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import java.security.SecureRandom
import java.util.Date

/**
 * Family sync: phones that joined the same family share kids, settings and today's time.
 *
 * Online layout (Firestore), one document per family plus one per day:
 *   families/{familyId}                 shared settings (see Store.sharedSettings) + members (phone uids)
 *   families/{familyId}/days/{date}     kids.{kidId}.devices.{phoneId} = ms counted on that phone
 *                                       kids.{kidId}.adjustMs          = +15 min bonuses and resets
 *   joinCodes/{code}                    { familyId, expiresAt }: lets a new phone join for 24 hours
 *
 * Phones sign in anonymously (no account). Without a family, nothing here runs and the phone
 * works fully on its own. If the app was built without a Firebase project, sync is unavailable.
 */
object Sync {

    private const val USAGE_PUSH_MS = 30_000L
    private const val CODE_TTL_MS = 24 * 60 * 60 * 1000L
    private const val CODE_LETTERS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    const val CODE_LENGTH = 6

    private var started = false
    private var settingsListener: ListenerRegistration? = null
    private var dayListener: ListenerRegistration? = null
    private var listenedDay: String? = null
    private var lastUsagePush = 0L
    private val lastPushed = mutableMapOf<String, Long>()

    /** How many phones are in the family (from the last update). */
    @Volatile var phoneCount = 0
        private set

    /** True if this build includes a Firebase project (google-services.json). */
    fun isConfigured(context: Context): Boolean = runCatching {
        FirebaseApp.getApps(context).isNotEmpty() || FirebaseApp.initializeApp(context) != null
    }.getOrDefault(false)

    fun isInFamily(context: Context): Boolean = Store(context).familyId != null

    private fun db() = FirebaseFirestore.getInstance()

    private fun family(id: String): DocumentReference = db().collection("families").document(id)

    private fun day(familyId: String, day: String) = family(familyId).collection("days").document(day)

    private fun signIn(onReady: (String) -> Unit, onError: (Exception) -> Unit) {
        val auth = FirebaseAuth.getInstance()
        auth.currentUser?.let {
            onReady(it.uid)
            return
        }
        auth.signInAnonymously()
            .addOnSuccessListener { result -> result.user?.let { onReady(it.uid) } ?: onError(IllegalStateException()) }
            .addOnFailureListener { onError(it) }
    }

    /** Starts listening for the family's changes. Safe to call often. */
    fun start(context: Context) {
        val app = context.applicationContext
        if (started || !isConfigured(app)) return
        val familyId = Store(app).familyId ?: return
        started = true
        Store.onSharedChange = { pushSettings(app) }
        Store.onAdjust = { kidId, date, delta -> pushAdjust(app, kidId, date, delta) }
        signIn({ listen(app, familyId) }, { started = false })
    }

    private fun stop() {
        settingsListener?.remove()
        dayListener?.remove()
        settingsListener = null
        dayListener = null
        listenedDay = null
        started = false
        Store.onSharedChange = null
        Store.onAdjust = null
    }

    private fun listen(app: Context, familyId: String) {
        settingsListener?.remove()
        settingsListener = family(familyId).addSnapshotListener { snap, error ->
            if (error != null || snap == null || !snap.exists()) return@addSnapshotListener
            val data = snap.data ?: return@addSnapshotListener
            phoneCount = (data["members"] as? List<*>)?.size ?: 0
            val store = Store(app)
            val remoteAt = (data["updatedAt"] as? Number)?.toLong() ?: 0L
            when {
                snap.metadata.hasPendingWrites() -> Unit
                remoteAt > store.settingsUpdatedAt -> store.applySharedSettings(data)
                // Changed here while offline: send our newer copy.
                remoteAt < store.settingsUpdatedAt -> pushSettings(app)
            }
        }
        listenDay(app, familyId)
    }

    private fun listenDay(app: Context, familyId: String) {
        val store = Store(app)
        val date = store.today()
        listenedDay = date
        dayListener?.remove()
        dayListener = day(familyId, date).addSnapshotListener { snap, error ->
            if (error != null || snap == null) return@addSnapshotListener
            val kids = snap.get("kids") as? Map<*, *> ?: return@addSnapshotListener
            val me = store.phoneId
            for ((kidId, value) in kids) {
                val entry = value as? Map<*, *> ?: continue
                val devices = entry["devices"] as? Map<*, *> ?: emptyMap<Any, Any>()
                val others = devices.filterKeys { it != me }.values.sumOf { (it as? Number)?.toLong() ?: 0L }
                val adjust = (entry["adjustMs"] as? Number)?.toLong() ?: 0L
                store.applyRemoteUsage(kidId.toString(), date, others, adjust)
            }
        }
    }

    /** Called every second by the guard: sends this phone's counters every 30 s. */
    fun tick(context: Context) {
        val app = context.applicationContext
        if (!started) {
            start(app)
            return
        }
        val store = Store(app)
        val familyId = store.familyId ?: return stop()
        if (store.today() != listenedDay) listenDay(app, familyId)
        val now = SystemClock.elapsedRealtime()
        if (now - lastUsagePush < USAGE_PUSH_MS) return
        lastUsagePush = now
        pushUsage(store, familyId)
    }

    /** Sends this phone's counters now (on pause, time's up, opening the app). */
    fun flush(context: Context) {
        if (!started) return
        val store = Store(context)
        store.familyId?.let { pushUsage(store, it) }
    }

    private fun pushUsage(store: Store, familyId: String) {
        val date = store.today()
        val me = store.phoneId
        val kids = mutableMapOf<String, Any>()
        for (kid in store.kids()) {
            val ms = store.myUsedMs(kid.id)
            val key = "$date/${kid.id}"
            if (lastPushed[key] == ms) continue
            lastPushed[key] = ms
            kids[kid.id] = mapOf("devices" to mapOf(me to ms))
        }
        if (kids.isNotEmpty()) day(familyId, date).set(mapOf("kids" to kids), SetOptions.merge())
    }

    private fun pushAdjust(app: Context, kidId: String, date: String, deltaMs: Long) {
        val familyId = Store(app).familyId ?: return
        day(familyId, date).set(
            mapOf("kids" to mapOf(kidId to mapOf("adjustMs" to FieldValue.increment(deltaMs)))),
            SetOptions.merge(),
        )
    }

    private fun pushSettings(app: Context) {
        val store = Store(app)
        val familyId = store.familyId ?: return
        family(familyId).set(store.sharedSettings(), SetOptions.merge())
    }

    // ---- Create / join / leave ----

    /** Makes a new family from this phone's kids and settings. Returns a join code for the other phone. */
    fun createFamily(context: Context, onDone: (String) -> Unit, onError: (Exception) -> Unit) {
        val app = context.applicationContext
        signIn({ uid ->
            val store = Store(app)
            val familyId = randomString("abcdefghijklmnopqrstuvwxyz0123456789", 24)
            val code = randomString(CODE_LETTERS, CODE_LENGTH)
            val batch = db().batch()
            batch.set(family(familyId), store.sharedSettings() + mapOf("members" to listOf(uid)))
            batch.set(db().collection("joinCodes").document(code), joinCode(familyId))
            batch.commit()
                .addOnSuccessListener {
                    store.familyId = familyId
                    // Bring today's time (including bonuses already given) online.
                    val date = store.today()
                    val kids = store.kids().associate { kid ->
                        kid.id to mapOf(
                            "devices" to mapOf(store.phoneId to store.myUsedMs(kid.id)),
                            "adjustMs" to store.usedMs(kid.id) - store.myUsedMs(kid.id),
                        )
                    }
                    day(familyId, date).set(mapOf("kids" to kids), SetOptions.merge())
                    stop()
                    start(app)
                    onDone(code)
                }
                .addOnFailureListener { onError(it) }
        }, onError)
    }

    /** A fresh join code for adding another phone to this phone's family. */
    fun newJoinCode(context: Context, onDone: (String) -> Unit, onError: (Exception) -> Unit) {
        val familyId = Store(context).familyId ?: return
        signIn({
            val code = randomString(CODE_LETTERS, CODE_LENGTH)
            db().collection("joinCodes").document(code).set(joinCode(familyId))
                .addOnSuccessListener { onDone(code) }
                .addOnFailureListener { onError(it) }
        }, onError)
    }

    /** Joins the family behind [code]. This phone's kids and settings are replaced by the family's. */
    fun joinFamily(context: Context, code: String, onDone: () -> Unit, onError: (Exception) -> Unit) {
        val app = context.applicationContext
        val cleanCode = code.trim().uppercase()
        signIn({ uid ->
            db().collection("joinCodes").document(cleanCode).get()
                .addOnSuccessListener { joinDoc ->
                    val familyId = joinDoc.getString("familyId")
                        ?: return@addOnSuccessListener onError(IllegalArgumentException("bad code"))
                    family(familyId)
                        .update(mapOf("members" to FieldValue.arrayUnion(uid), "joinCode" to cleanCode))
                        .addOnSuccessListener {
                            family(familyId).get()
                                .addOnSuccessListener { snap ->
                                    val store = Store(app)
                                    store.pause()
                                    store.applySharedSettings(snap.data ?: emptyMap())
                                    store.familyId = familyId
                                    stop()
                                    start(app)
                                    onDone()
                                }
                                .addOnFailureListener { onError(it) }
                        }
                        .addOnFailureListener { onError(it) }
                }
                .addOnFailureListener { onError(it) }
        }, onError)
    }

    /** Leaves the family. This phone keeps a copy of the kids and goes back to working on its own. */
    fun leaveFamily(context: Context) {
        val store = Store(context)
        val familyId = store.familyId ?: return
        FirebaseAuth.getInstance().currentUser?.uid?.let {
            family(familyId).update("members", FieldValue.arrayRemove(it))
        }
        store.familyId = null
        phoneCount = 0
        stop()
    }

    private fun joinCode(familyId: String) =
        mapOf("familyId" to familyId, "expiresAt" to Timestamp(Date(System.currentTimeMillis() + CODE_TTL_MS)))

    private fun randomString(letters: String, length: Int): String {
        val random = SecureRandom()
        return (1..length).map { letters[random.nextInt(letters.length)] }.joinToString("")
    }
}
