package com.mckogan.playtime

import android.content.Context
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.GenerativeBackend
import com.google.firebase.ai.type.Schema
import com.google.firebase.ai.type.generationConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Short facts for the parents' 30-second wait. A random featured Wikipedia article (in the app's
 * language) is rewritten by Gemini (Firebase AI Logic) into one short, accurate fun fact.
 * A few facts are prepared in the background so one is ready the moment the wait starts.
 * Any failure (offline, quota, odd answer) is skipped quietly: the wait then shows only the ring.
 */
object Facts {

    data class Fact(val text: String, val emoji: String, val title: String, val url: String)

    private const val PREFS = "facts"
    private const val QUEUE_SIZE = 3
    private const val MAX_EXTRACT = 4000
    private const val MAX_ATTEMPTS = 6
    private const val SEEN_MAX = 200
    private const val TITLES_TTL_MS = 7L * 24 * 60 * 60 * 1000
    private const val USER_AGENT = "PlayTime/1.0 (family parental-control app)"

    /** Tried in order; the first that works is remembered. Free "Flash-Lite" class models. */
    private val MODELS = listOf("gemini-3.5-flash-lite", "gemini-3.1-flash-lite", "gemini-2.5-flash-lite")

    private val CATEGORY = mapOf(
        "en" to "Category:Featured articles",
        "he" to "קטגוריה:ערכים מומלצים",
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var refilling = false

    private fun lang(context: Context): String =
        if (context.resources.configuration.locales[0].language in setOf("iw", "he")) "he" else "en"

    /** A ready fact (or null), and starts preparing the next ones. */
    fun next(context: Context): Fact? {
        val app = context.applicationContext
        val lang = lang(app)
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val queue = JSONArray(prefs.getString("queue2_$lang", "[]"))
        val fact = if (queue.length() > 0) {
            val o = queue.getJSONObject(0)
            queue.remove(0)
            prefs.edit().putString("queue2_$lang", queue.toString()).apply()
            Fact(o.getString("text"), o.optString("emoji"), o.optString("title"), o.optString("url"))
        } else {
            null
        }
        refill(app)
        return fact
    }

    /** Prepares facts in the background until [QUEUE_SIZE] are ready. Safe to call often. */
    fun refill(context: Context) {
        val app = context.applicationContext
        if (refilling || !Sync.isConfigured(app)) return
        refilling = true
        scope.launch {
            try {
                val lang = lang(app)
                var attempts = 0
                while (queueSize(app, lang) < QUEUE_SIZE && attempts++ < MAX_ATTEMPTS) {
                    makeOne(app, lang)?.let { push(app, lang, it) }
                }
            } finally {
                refilling = false
            }
        }
    }

    private fun queueSize(app: Context, lang: String) =
        JSONArray(app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("queue2_$lang", "[]")).length()

    private fun push(app: Context, lang: String, fact: Fact) {
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val queue = JSONArray(prefs.getString("queue2_$lang", "[]"))
        queue.put(JSONObject().put("text", fact.text).put("emoji", fact.emoji).put("title", fact.title).put("url", fact.url))
        prefs.edit().putString("queue2_$lang", queue.toString()).apply()
    }

    private suspend fun makeOne(app: Context, lang: String): Fact? = runCatching {
        val title = randomTitle(app, lang) ?: return null
        // The article's whole opening section (plain text), so there's enough material for a
        // longer fact without the model adding anything of its own.
        val page = getJson(
            "https://$lang.wikipedia.org/w/api.php?action=query&format=json&formatversion=2" +
                "&prop=extracts%7Cinfo&exintro=1&explaintext=1&inprop=url&redirects=1&titles=" +
                URLEncoder.encode(title, "UTF-8")
        )?.optJSONObject("query")?.optJSONArray("pages")?.optJSONObject(0) ?: return null
        val extract = page.optString("extract").trim().take(MAX_EXTRACT)
        if (extract.length < 200) return null
        val display = page.optString("title", title)
        val url = page.optString("fullurl").replace("://$lang.wikipedia.org", "://$lang.m.wikipedia.org")
        val answer = askGemini(app, lang, display, extract) ?: return null
        val text = answer.optString("fact").trim()
        if (text.length < 120 || text.length > 900) return null
        Fact(text, answer.optString("emoji").trim().take(4), display, url)
    }.getOrNull()

    private suspend fun askGemini(app: Context, lang: String, title: String, extract: String): JSONObject? {
        val language = if (lang == "he") "Hebrew" else "English"
        val prompt = """
            Here is the opening of the Wikipedia article "$title":
            $extract

            Write ONE surprising, interesting fact based ONLY on the text above. Do not add anything that is not in the text.
            Give it a little context so it's worth reading: 3 to 4 sentences, about 60 to 90 words,
            friendly and clear for an adult reader. Write it in $language.
            Also give one emoji that fits the topic.
        """.trimIndent()
        val schema = Schema.obj(mapOf("fact" to Schema.string(), "emoji" to Schema.string()))
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val known = prefs.getString("model", null)
        for (name in listOfNotNull(known) + MODELS.filter { it != known }) {
            val model = Firebase.ai(backend = GenerativeBackend.googleAI()).generativeModel(
                modelName = name,
                generationConfig = generationConfig {
                    responseMimeType = "application/json"
                    responseSchema = schema
                },
            )
            val text = runCatching { model.generateContent(prompt).text }.getOrNull() ?: continue
            prefs.edit().putString("model", name).apply()
            return runCatching { JSONObject(text) }.getOrNull()
        }
        return null
    }

    /** A random featured article title not shown recently. The title list is cached for a week. */
    private fun randomTitle(app: Context, lang: String): String? {
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        var titles = JSONArray(prefs.getString("titles_$lang", "[]"))
        if (titles.length() == 0 || System.currentTimeMillis() - prefs.getLong("titles_at_$lang", 0L) > TITLES_TTL_MS) {
            fetchTitles(lang)?.takeIf { it.length() > 0 }?.let {
                titles = it
                prefs.edit().putString("titles_$lang", it.toString()).putLong("titles_at_$lang", System.currentTimeMillis()).apply()
            }
        }
        if (titles.length() == 0) return null
        val seen = JSONArray(prefs.getString("seen_$lang", "[]"))
        val seenSet = (0 until seen.length()).map { seen.getString(it) }.toSet()
        val candidates = (0 until titles.length()).map { titles.getString(it) }.filter { it !in seenSet }
        val pick = (candidates.ifEmpty { (0 until titles.length()).map { titles.getString(it) } }).random()
        seen.put(pick)
        while (seen.length() > SEEN_MAX) seen.remove(0)
        prefs.edit().putString("seen_$lang", seen.toString()).apply()
        return pick
    }

    /** Up to ~2,000 titles from the featured-articles category (articles only). */
    private fun fetchTitles(lang: String): JSONArray? {
        val category = CATEGORY[lang] ?: return null
        val out = JSONArray()
        var cont: String? = null
        repeat(4) {
            val url = "https://$lang.wikipedia.org/w/api.php?action=query&list=categorymembers&format=json" +
                "&cmnamespace=0&cmlimit=500&cmtitle=" + URLEncoder.encode(category, "UTF-8") +
                (cont?.let { "&cmcontinue=" + URLEncoder.encode(it, "UTF-8") } ?: "")
            val json = getJson(url) ?: return out.takeIf { it.length() > 0 }
            val members = json.optJSONObject("query")?.optJSONArray("categorymembers") ?: return out
            for (i in 0 until members.length()) out.put(members.getJSONObject(i).getString("title"))
            cont = json.optJSONObject("continue")?.optString("cmcontinue")?.takeIf { it.isNotEmpty() } ?: return out
        }
        return out
    }

    private fun getJson(url: String): JSONObject? {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.setRequestProperty("User-Agent", USER_AGENT)
        conn.setRequestProperty("Accept", "application/json")
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        return try {
            if (conn.responseCode != 200) null
            else JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        } catch (e: Exception) {
            null
        } finally {
            conn.disconnect()
        }
    }
}
