package com.anees.novamobile.calendar

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
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
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/** One upcoming (or just aired) episode. [exactTime]: true when the time is known (AniList), false = date only. */
data class AiringEp(
    val card: MediaCard,
    val season: Int,
    val episode: Int,
    val name: String,
    val airAt: Long,
    val exactTime: Boolean
)

/**
 * Airing calendar, built to avoid the problems of Vega's version:
 *  - exact TMDB / AniList ids from My List + Continue Watching (no title-search guessing)
 *  - anime: AniList's exact air timestamps; series: every upcoming episode of the current and next season
 *  - a week view (yesterday → 14 days), not just "the next episode"
 *  - reminders rebuilt daily and after reboot (see [AiringReceiver])
 */
object AiringCalendar {
    private val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
    private const val WINDOW_BACK = 24 * 3600_000L
    private const val WINDOW_AHEAD = 14 * 24 * 3600_000L

    /** Shows the user follows: My List + Continue Watching, series and anime only, no duplicates. */
    fun followed(): List<MediaCard> =
        (LocalStore.watchlist() + LocalStore.history().map { it.card })
            .filter { it.type != MediaType.MOVIE }
            .distinctBy { "${it.type}:${it.id}" }

    suspend fun load(): List<AiringEp> = withContext(Dispatchers.IO) {
        val shows = followed()
        val now = System.currentTimeMillis()
        val result = coroutineScope {
            val series = shows.filter { it.type == MediaType.SERIES }.map { async { runCatching { tmdb(it, now) }.getOrDefault(emptyList()) } }
            val anime = async { runCatching { anilist(shows.filter { it.type == MediaType.ANIME }, now) }.getOrDefault(emptyList()) }
            series.awaitAll().flatten() + anime.await()
        }
        result.filter { it.airAt in (now - WINDOW_BACK)..(now + WINDOW_AHEAD) }.sortedBy { it.airAt }
            .also { LocalStore.saveAiringCache(it) }
    }

    private fun get(url: String): JSONObject? = RetrofitClient.base.newCall(Request.Builder().url(url).build()).execute()
        .use { if (it.isSuccessful) JSONObject(it.body?.string() ?: "{}") else null }

    /** Series: next_episode_to_air tells which season is current; read that season's full episode list. */
    private fun tmdb(card: MediaCard, now: Long): List<AiringEp> {
        val key = BuildConfig.TMDB_KEY
        val show = get("https://api.themoviedb.org/3/tv/${card.id}?api_key=$key") ?: return emptyList()
        val next = show.optJSONObject("next_episode_to_air")
        val last = show.optJSONObject("last_episode_to_air")
        val seasonNum = (next ?: last)?.optInt("season_number") ?: return emptyList()
        val out = mutableListOf<AiringEp>()
        for (s in setOf(seasonNum, seasonNum + 1)) {
            val season = get("https://api.themoviedb.org/3/tv/${card.id}/season/$s?api_key=$key") ?: continue
            val eps = season.optJSONArray("episodes") ?: continue
            for (i in 0 until eps.length()) {
                val e = eps.getJSONObject(i)
                val date = e.optString("air_date").takeIf { it.length == 10 } ?: continue
                val at = localMorning(date)   // date only from TMDB: shown as the local day
                if (at < now - WINDOW_BACK || at > now + WINDOW_AHEAD) continue
                out += AiringEp(card, s, e.optInt("episode_number"), e.optString("name"), at, exactTime = false)
            }
        }
        return out
    }

    /** Anime: AniList airing schedule with exact Unix times. */
    private fun anilist(anime: List<MediaCard>, now: Long): List<AiringEp> {
        val ids = anime.mapNotNull { it.aniListId ?: it.id }.distinct()
        if (ids.isEmpty()) return emptyList()
        val q = "query(\$ids:[Int],\$from:Int,\$to:Int){Page(perPage:50){airingSchedules(mediaId_in:\$ids,airingAt_greater:\$from,airingAt_lesser:\$to,sort:TIME){airingAt episode mediaId}}}"
        val vars = JSONObject().put("ids", JSONArray(ids)).put("from", ((now - WINDOW_BACK) / 1000).toInt()).put("to", ((now + WINDOW_AHEAD) / 1000).toInt())
        val body = JSONObject().put("query", q).put("variables", vars).toString().toRequestBody("application/json".toMediaType())
        val json = RetrofitClient.base.newCall(Request.Builder().url("https://graphql.anilist.co").post(body).build()).execute()
            .use { JSONObject(it.body?.string() ?: "{}") }
        val arr = json.optJSONObject("data")?.optJSONObject("Page")?.optJSONArray("airingSchedules") ?: return emptyList()
        val byId = anime.associateBy { it.aniListId ?: it.id }
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.getJSONObject(i)
            val card = byId[o.getInt("mediaId")] ?: return@mapNotNull null
            AiringEp(card, 1, o.getInt("episode"), "Episode ${o.getInt("episode")}", o.getLong("airingAt") * 1000, exactTime = true)
        }
    }

    /** "2026-11-05" → that day at 09:00 local (when we remind for date-only episodes). */
    private fun localMorning(date: String): Long {
        val utc = day.parse(date)!!
        return Calendar.getInstance().apply {
            timeInMillis = utc.time
            val y = get(Calendar.YEAR); val m = get(Calendar.MONTH); val d = get(Calendar.DAY_OF_MONTH)
            timeZone = TimeZone.getDefault(); clear(); set(y, m, d, 9, 0)
        }.timeInMillis
    }

    // ── Reminders ─────────────────────────────────────────────────────────────

    /** Rebuilds every reminder from the latest data (app start, daily alarm, after reboot). */
    suspend fun reschedule(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        val enabled = LocalStore.settings().newEpisodeAlerts
        val eps = runCatching { load() }.getOrElse { LocalStore.airingCache() }
        val now = System.currentTimeMillis()
        LocalStore.scheduledReminders().forEach { am.cancel(pending(ctx, it, null)) }      // drop stale ones
        val ids = mutableListOf<Int>()
        if (enabled) eps.filter { it.airAt > now }.take(40).forEach { ep ->
            // Anime: an hour after airing (sources need time); series (date only): 9:00 local
            val at = if (ep.exactTime) ep.airAt + 3600_000L else ep.airAt
            val id = "${ep.card.type}:${ep.card.id}:${ep.season}:${ep.episode}".hashCode()
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending(ctx, id, ep))
            ids += id
        }
        LocalStore.saveScheduledReminders(ids)
        // Daily rebuild at ~4 AM so new episodes / date changes are picked up
        val daily = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, 1); set(Calendar.HOUR_OF_DAY, 4); set(Calendar.MINUTE, 0) }.timeInMillis
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, daily, pending(ctx, 0, null, AiringReceiver.ACTION_DAILY))
    }

    private fun pending(ctx: Context, id: Int, ep: AiringEp?, action: String = AiringReceiver.ACTION_EPISODE): PendingIntent {
        val i = Intent(ctx, AiringReceiver::class.java).setAction(action)
        ep?.let {
            i.putExtra("title", it.card.title).putExtra("season", it.season).putExtra("episode", it.episode)
                .putExtra("name", it.name).putExtra("card", com.google.gson.Gson().toJson(it.card))
        }
        return PendingIntent.getBroadcast(ctx, id, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
}
