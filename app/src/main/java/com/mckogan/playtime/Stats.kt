package com.mckogan.playtime

import android.content.Context
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Parent stats for the last [DAYS] days: time per kid per day and each kid's top games, plus this
 * phone's parent-side counts (my apps, the "3 more minutes?" answers, "Want more?" for my apps).
 * Kids' time is the whole family's when the phones are synced, otherwise this phone's only.
 */
object Stats {

    const val DAYS = 7
    private const val TOP_GAMES = 5
    private val WEEKDAY = DateTimeFormatter.ofPattern("EEE")

    class Week(
        val days: List<String>,
        /** kidId -> ms per day, in [days] order. */
        val daily: Map<String, List<Long>>,
        /** kidId -> (pkg -> ms over the week), most played first. */
        val games: Map<String, List<Pair<String, Long>>>,
        /** (pkg -> minutes chosen for my apps), most first. This phone only. */
        val myApps: List<Pair<String, Int>>,
        val extraYes: Int,
        val extraNo: Int,
        val moreAsked: Int,
        /** True when kids' time covers the whole family, false when it is this phone only. */
        val family: Boolean,
    )

    fun days(): List<String> = (DAYS - 1 downTo 0).map { LocalDate.now().minusDays(it.toLong()).toString() }

    /** "Mon", "Tue"… for a yyyy-mm-dd day. */
    fun weekday(day: String): String = LocalDate.parse(day).format(WEEKDAY)

    /** Loads the week; [onResult] runs on the main thread. */
    fun load(context: Context, onResult: (Week) -> Unit) {
        val app = context.applicationContext
        val days = days()
        Sync.fetchDays(app, days) { cloud -> onResult(build(Store(app), days, cloud)) }
    }

    private fun build(store: Store, days: List<String>, cloud: Map<String, Map<*, *>>?): Week {
        val kidIds = store.kids().map { it.id }
        val daily = kidIds.associateWith { MutableList(days.size) { 0L } }
        val games = kidIds.associateWith { mutableMapOf<String, Long>() }
        val myApps = mutableMapOf<String, Int>()
        var yes = 0
        var no = 0
        var more = 0

        for ((i, day) in days.withIndex()) {
            val h = store.historyDay(day)
            if (cloud == null) {
                h.optJSONObject("kids")?.let { kids ->
                    for (kidId in kids.keys()) daily[kidId]?.set(i, kids.optLong(kidId))
                }
                h.optJSONObject("games")?.let { all ->
                    for (kidId in all.keys()) {
                        val perGame = all.optJSONObject(kidId) ?: continue
                        val total = games[kidId] ?: continue
                        for (pkg in perGame.keys()) total[pkg] = (total[pkg] ?: 0L) + perGame.optLong(pkg)
                    }
                }
            } else {
                for ((kidId, entry) in cloud[day].orEmpty()) {
                    val id = kidId.toString()
                    val series = daily[id] ?: continue
                    val phones = entry as? Map<*, *> ?: continue
                    // Every phone's share of this kid's time that day.
                    val devices = phones["devices"] as? Map<*, *>
                    series[i] = devices?.values?.sumOf { (it as? Number)?.toLong() ?: 0L } ?: 0L
                    val total = games[id] ?: continue
                    (phones["games"] as? Map<*, *>)?.values?.forEach { perPhone ->
                        (perPhone as? Map<*, *>)?.forEach { (pkg, ms) ->
                            total[pkg.toString()] = (total[pkg.toString()] ?: 0L) + ((ms as? Number)?.toLong() ?: 0L)
                        }
                    }
                }
            }
            h.optJSONObject("myApps")?.let { apps ->
                for (pkg in apps.keys()) myApps[pkg] = (myApps[pkg] ?: 0) + apps.optInt(pkg)
            }
            yes += h.optInt("extraYes")
            no += h.optInt("extraNo")
            more += h.optInt("moreAsked")
        }

        return Week(
            days = days,
            daily = daily,
            games = games.mapValues { (_, totals) ->
                totals.entries.filter { it.value > 0 }.sortedByDescending { it.value }.map { it.key to it.value }
            },
            myApps = myApps.entries.filter { it.value > 0 }.sortedByDescending { it.value }.map { it.key to it.value },
            extraYes = yes,
            extraNo = no,
            moreAsked = more,
            family = cloud != null,
        )
    }

    /** The games that are shown as bars: the top few, and the rest together as "Other" (pkg ""). */
    fun topGames(games: List<Pair<String, Long>>): List<Pair<String, Long>> {
        val rest = games.drop(TOP_GAMES).sumOf { it.second }
        return games.take(TOP_GAMES) + (if (rest > 0) listOf("" to rest) else emptyList())
    }

    /** How many days in the week have any kids' time, to tell "no data yet" from a quiet week. */
    fun hasAnything(week: Week): Boolean =
        week.daily.values.any { days -> days.any { it > 0 } } || week.myApps.isNotEmpty() ||
            week.extraYes + week.extraNo + week.moreAsked > 0
}
