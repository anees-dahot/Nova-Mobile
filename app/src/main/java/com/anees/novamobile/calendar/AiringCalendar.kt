package com.anees.novamobile.calendar

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import com.anees.novamobile.BuildConfig
import com.anees.novamobile.data.api.RetrofitClient
import com.anees.novamobile.data.local.LocalStore
import com.anees.novamobile.data.model.MediaCard
import com.anees.novamobile.data.model.MediaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.Calendar
import java.util.TimeZone

/**
 * One upcoming (or just aired) episode.
 * [exactTime]: true when the time is known (AniList); false = TMDB gives only a date, and [airAt] is our
 * estimate of when it's actually out (evening of that date in the show's home country).
 */
data class AiringEp(
    val card: MediaCard,
    val season: Int,
    val episode: Int,
    val name: String,
    val airAt: Long,
    val exactTime: Boolean
) {
    /** Stable id for this episode's reminder / notification. */
    val key: String get() = "${card.type}:${card.id}:$season:$episode"
}

/**
 * Airing calendar:
 *  - exact TMDB / AniList ids from My List + Continue Watching (no title-search guessing)
 *  - anime: AniList's exact air timestamps; series: every upcoming episode of the current and next season
 *  - a week view (yesterday → 14 days), not just "the next episode"
 *  - reminders: exact alarms, rebuilt daily / after reboot / time-zone change, missed ones caught up
 *  - a failed refresh never wipes the schedule: shows whose fetch failed keep their saved episodes
 */
object AiringCalendar {
    private const val TAG = "NovaCalendar"
    const val WINDOW_BACK = 36 * 3600_000L
    const val WINDOW_AHEAD = 14 * 24 * 3600_000L
    /** Anime: remind an hour after airing (sources need time to pick it up). */
    private const val ANIME_DELAY = 3600_000L
    /** Missed reminders (phone off, app killed by a battery saver) are still shown if at most this late. */
    private const val CATCH_UP = 12 * 3600_000L
    private const val MAX_ALARMS = 60

    /** Shows the user follows: My List + Continue Watching, series and anime only, no duplicates. */
    fun followed(): List<MediaCard> =
        (LocalStore.watchlist() + LocalStore.history().map { it.card })
            .filter { it.type != MediaType.MOVIE }
            .distinctBy { "${it.type}:${it.id}" }

    /** True when the last refresh had at least one show whose data couldn't be fetched. */
    @Volatile var lastLoadPartial = false
        private set

    /**
     * Fresh schedule. Throws only when *everything* failed (offline) — callers then keep the saved schedule.
     * When some shows fail, their saved episodes are kept instead of disappearing.
     */
    suspend fun load(): List<AiringEp> = withContext(Dispatchers.IO) {
        val shows = followed()
        val now = System.currentTimeMillis()
        val cached = LocalStore.airingCache().groupBy { "${it.card.type}:${it.card.id}" }
        val series = shows.filter { it.type == MediaType.SERIES }
        val anime = shows.filter { it.type == MediaType.ANIME }
        var failures = 0; var attempts = 0
        val result = coroutineScope {
            val s = series.map { c -> async { runCatching { tmdb(c, now) }.onFailure { Log.w(TAG, "tmdb ${c.id}: $it") }.getOrNull() } }
            val a = if (anime.isEmpty()) null else async { runCatching { anilist(anime, now) }.onFailure { Log.w(TAG, "anilist: $it") }.getOrNull() }
            val out = mutableListOf<AiringEp>()
            series.zip(s.awaitAll()).forEach { (c, eps) ->
                attempts++
                if (eps != null) out += eps else { failures++; out += cached["${c.type}:${c.id}"].orEmpty() }
            }
            if (a != null) {
                attempts++
                val eps = a.await()
                if (eps != null) out += eps else { failures++; anime.forEach { c -> out += cached["${c.type}:${c.id}"].orEmpty() } }
            }
            out
        }
        if (attempts > 0 && failures == attempts) throw IOException("schedule refresh failed")
        lastLoadPartial = failures > 0
        result.filter { it.airAt in (now - WINDOW_BACK)..(now + WINDOW_AHEAD) }
            .distinctBy { it.key }
            .sortedBy { it.airAt }
            .also { LocalStore.saveAiringCache(it) }
    }

    /** GET → JSON; 404 = "no such season" (null), anything else non-2xx is a failure. */
    /** Capped per request: the shared client only limits connect and read, so a server trickling bytes could hold a refresh (and the boot receiver waiting on it) without end. */
    private val http by lazy { RetrofitClient.base.newBuilder().callTimeout(12, java.util.concurrent.TimeUnit.SECONDS).build() }

    private fun get(url: String): JSONObject? = http.newCall(Request.Builder().url(url).build()).execute().use {
        when {
            it.isSuccessful -> JSONObject(it.body?.string() ?: "{}")
            it.code == 404 -> null
            else -> throw IOException("HTTP ${it.code}")
        }
    }

    /** Series: next_episode_to_air tells which season is current; read that season's full episode list. */
    private fun tmdb(card: MediaCard, now: Long): List<AiringEp> {
        val key = BuildConfig.TMDB_KEY
        val show = get("https://api.themoviedb.org/3/tv/${card.id}?api_key=$key") ?: return emptyList()
        val next = show.optJSONObject("next_episode_to_air")
        val last = show.optJSONObject("last_episode_to_air")
        val seasonNum = (next ?: last)?.optInt("season_number") ?: return emptyList()
        val country = show.optJSONArray("origin_country")?.optString(0).orEmpty()
        val out = mutableListOf<AiringEp>()
        for (s in setOf(seasonNum, seasonNum + 1)) {
            val season = get("https://api.themoviedb.org/3/tv/${card.id}/season/$s?api_key=$key") ?: continue
            val eps = season.optJSONArray("episodes") ?: continue
            for (i in 0 until eps.length()) {
                val e = eps.getJSONObject(i)
                val date = e.optString("air_date").takeIf { it.length == 10 } ?: continue
                val at = releaseEstimate(date, country) ?: continue
                if (at < now - WINDOW_BACK || at > now + WINDOW_AHEAD) continue
                out += AiringEp(card, s, e.optInt("episode_number"), e.optString("name"), at, exactTime = false)
            }
        }
        return out
    }

    /** Anime: AniList airing schedule with exact Unix times (all pages, not just the first 50). */
    private fun anilist(anime: List<MediaCard>, now: Long): List<AiringEp> {
        val ids = anime.mapNotNull { it.aniListId ?: it.id }.distinct()
        if (ids.isEmpty()) return emptyList()
        val byId = anime.associateBy { it.aniListId ?: it.id }
        val q = "query(\$ids:[Int],\$from:Int,\$to:Int,\$page:Int){Page(page:\$page,perPage:50){pageInfo{hasNextPage}airingSchedules(mediaId_in:\$ids,airingAt_greater:\$from,airingAt_lesser:\$to,sort:TIME){airingAt episode mediaId}}}"
        val out = mutableListOf<AiringEp>()
        var page = 1
        while (page <= 5) {
            val vars = JSONObject().put("ids", JSONArray(ids)).put("page", page)
                .put("from", ((now - WINDOW_BACK) / 1000).toInt()).put("to", ((now + WINDOW_AHEAD) / 1000).toInt())
            val body = JSONObject().put("query", q).put("variables", vars).toString().toRequestBody("application/json".toMediaType())
            val json = http.newCall(Request.Builder().url("https://graphql.anilist.co").post(body).build()).execute().use {
                if (!it.isSuccessful) throw IOException("AniList HTTP ${it.code}")
                JSONObject(it.body?.string() ?: "{}")
            }
            val p = json.optJSONObject("data")?.optJSONObject("Page") ?: throw IOException("AniList: no data")
            val arr = p.optJSONArray("airingSchedules") ?: break
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val card = byId[o.getInt("mediaId")] ?: continue
                out += AiringEp(card, 1, o.getInt("episode"), "", o.getLong("airingAt") * 1000, exactTime = true)
            }
            if (p.optJSONObject("pageInfo")?.optBoolean("hasNextPage") != true) break
            page++
        }
        return out
    }

    /** Home time zone for countries with lots of TV; everything else is treated like US prime time. */
    private val countryZone = mapOf(
        "US" to "America/New_York", "CA" to "America/Toronto", "GB" to "Europe/London", "IE" to "Europe/Dublin",
        "AU" to "Australia/Sydney", "NZ" to "Pacific/Auckland", "JP" to "Asia/Tokyo", "KR" to "Asia/Seoul",
        "CN" to "Asia/Shanghai", "TW" to "Asia/Taipei", "HK" to "Asia/Hong_Kong", "TH" to "Asia/Bangkok",
        "IN" to "Asia/Kolkata", "PK" to "Asia/Karachi", "TR" to "Europe/Istanbul", "ES" to "Europe/Madrid",
        "FR" to "Europe/Paris", "DE" to "Europe/Berlin", "IT" to "Europe/Rome", "SE" to "Europe/Stockholm",
        "DK" to "Europe/Copenhagen", "NO" to "Europe/Oslo", "BR" to "America/Sao_Paulo", "MX" to "America/Mexico_City",
    )

    /**
     * TMDB gives only the home-country air *date* ("2026-11-05"). Episodes come out in that country's
     * evening, so estimate 21:00 there — e.g. a US show dated Nov 5 is out early Nov 6 in Pakistan,
     * not the morning of Nov 5 (which is ~20 hours before it exists).
     */
    fun releaseEstimate(date: String, country: String): Long? {
        val parts = date.split("-").mapNotNull { it.toIntOrNull() }
        if (parts.size != 3) return null
        val zone = TimeZone.getTimeZone(countryZone[country] ?: "America/New_York")
        return Calendar.getInstance(zone).apply { clear(); set(parts[0], parts[1] - 1, parts[2], 21, 0) }.timeInMillis
    }

    /** When the reminder for [ep] should go off. */
    fun remindAt(ep: AiringEp): Long = if (ep.exactTime) ep.airAt + ANIME_DELAY else ep.airAt

    // ── Reminders ─────────────────────────────────────────────────────────────

    /** Exact alarms allowed? (USE_EXACT_ALARM on 13+, SCHEDULE_EXACT_ALARM on 12, always before 12.) */
    fun canExact(ctx: Context): Boolean {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return false
        return android.os.Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
    }

    private fun setAlarm(ctx: Context, am: AlarmManager, at: Long, pi: PendingIntent) {
        // Exact + allowed in Doze, so "Episode out" arrives on time, not whenever the phone next wakes
        if (canExact(ctx)) runCatching { am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi); return }
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
    }

    /**
     * Rebuilds every reminder (app start, calendar open, daily alarm, reboot, update, time-zone change).
     * Uses fresh data when online, the saved schedule otherwise — a failed refresh never cancels reminders.
     * Reminders whose time passed while the phone was off are posted now (if at most [CATCH_UP] late).
     */
    suspend fun reschedule(ctx: Context, fresh: List<AiringEp>? = null) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        val enabled = LocalStore.settings().newEpisodeAlerts
        val eps = fresh ?: runCatching { load() }.getOrElse { LocalStore.airingCache() }
        val now = System.currentTimeMillis()
        LocalStore.scheduledReminders().forEach { am.cancel(pending(ctx, it, null)) }      // drop stale ones
        val ids = mutableListOf<Int>()
        val notified = LocalStore.notifiedEpisodes()
        if (enabled) {
            eps.filter { remindAt(it) > now }.take(MAX_ALARMS).forEach { ep ->
                val id = ep.key.hashCode()
                setAlarm(ctx, am, remindAt(ep), pending(ctx, id, ep))
                ids += id
            }
            // Missed while the phone was off / the app was force-stopped: post them now
            eps.filter { remindAt(it) in (now - CATCH_UP)..now && it.key !in notified }
                .forEach { ctx.sendBroadcast(intentFor(ctx, it, AiringReceiver.ACTION_EPISODE)) }
        }
        LocalStore.saveScheduledReminders(ids)
        // Daily rebuild at ~4 AM so new episodes / date changes are picked up (inexact is fine here)
        val daily = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, 1); set(Calendar.HOUR_OF_DAY, 4); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0)
        }.timeInMillis
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, daily, pending(ctx, 0, null, AiringReceiver.ACTION_DAILY))
        Log.i(TAG, "rescheduled ${ids.size} reminders (enabled=$enabled, exact=${canExact(ctx)}, eps=${eps.size})")
    }

    /** Settings → "Test alert": goes through the real alarm → receiver → notification path, ~5 s from now. */
    fun scheduleTest(ctx: Context): String {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return "No alarm service"
        val card = followed().firstOrNull() ?: MediaCard(id = 0, title = "Nova test show", overview = "", posterUrl = null, backdropUrl = null, year = "", rating = 0.0, type = MediaType.SERIES)
        val ep = AiringEp(card, 1, 1, "Test alert", System.currentTimeMillis(), exactTime = false)
        val i = intentFor(ctx, ep, AiringReceiver.ACTION_EPISODE).putExtra("test", true)
        val pi = PendingIntent.getBroadcast(ctx, 0x7E57, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        setAlarm(ctx, am, System.currentTimeMillis() + 5_000, pi)
        return "Test alert in 5 seconds"
    }

    private fun intentFor(ctx: Context, ep: AiringEp, action: String): Intent =
        Intent(ctx, AiringReceiver::class.java).setAction(action)
            .putExtra("key", ep.key)
            .putExtra("title", ep.card.title).putExtra("season", ep.season).putExtra("episode", ep.episode)
            .putExtra("name", ep.name).putExtra("card", com.google.gson.Gson().toJson(ep.card))

    private fun pending(ctx: Context, id: Int, ep: AiringEp?, action: String = AiringReceiver.ACTION_EPISODE): PendingIntent {
        val i = if (ep != null) intentFor(ctx, ep, action) else Intent(ctx, AiringReceiver::class.java).setAction(action)
        return PendingIntent.getBroadcast(ctx, id, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
}
