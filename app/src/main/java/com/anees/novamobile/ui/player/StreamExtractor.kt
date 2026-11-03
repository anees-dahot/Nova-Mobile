package com.anees.novamobile.ui.player

import com.anees.novamobile.data.api.RetrofitClient
import com.anees.novamobile.data.model.MediaCard
import com.anees.novamobile.data.model.MediaType
import com.anees.novamobile.data.repository.MediaRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject

/**
 * Builds the ordered list of embed pages for a title. Order comes from a headless test
 * (Nov 2026): vidsrc.to and vidlink returned real streams; the rest are fallbacks.
 */
object StreamExtractor {

    /** [timeoutMs]: slow TV boxes need ~20-25s for vidsrc's page chain on a cold WebView. */
    data class Provider(val name: String, val movie: (Int) -> String, val tv: (Int, Int, Int) -> String,
                        val timeoutMs: Long = 15_000, val multiAudio: Boolean = false)

    /** What to ask providers for: always a TMDB id, movie or tv episode. */
    data class Target(val tmdbId: Int, val isMovie: Boolean, val season: Int, val episode: Int, val malId: Int? = null)

    /** Built-in list, used until (or if) the remote sources.json from GitHub loads. */
    val BUILT_IN = listOf(
        // Nov 2026 test: one HLS stream with many dubs (e.g. Inception: English, Hindi, Telugu, Arabic…), found in ~3s
        Provider("VidLux", { "https://vidlux.xyz/embed/movie/$it" }, { id, s, e -> "https://vidlux.xyz/embed/tv/$id/$s/$e" }, 20_000, multiAudio = true),
        // Backup with real dubs (Nov 2026): Vidly had Hindi + 6 more on Loki where VidLux had no Hindi
        Provider("Vidly", { "https://vidly.pro/movie/$it" }, { id, s, e -> "https://vidly.pro/tv/$id/$s/$e" }, 20_000, multiAudio = true),
        // OllyEmbed (?server=2, Hindi + English) is found by the sniffer but doesn't start in ExoPlayer yet
        // (PNG-wrapped chunks behind a worker proxy, see PngStripDataSource). Left out until that's solved:
        // Provider("OllyEmbed", { "https://ollyembed.pages.dev/movie/$it?server=2" }, { id, s, e -> "https://ollyembed.pages.dev/tv/$id/$s/$e?server=2" }, 20_000, multiAudio = true),
        Provider("VidSrc", { "https://vidsrc.to/embed/movie/$it" }, { id, s, e -> "https://vidsrc.to/embed/tv/$id/$s/$e" }, 35_000),
        Provider("VidLink", { "https://vidlink.pro/movie/$it?autoplay=true" }, { id, s, e -> "https://vidlink.pro/tv/$id/$s/$e?autoplay=true" }),
        Provider("VidSrc CC", { "https://vidsrc.cc/v2/embed/movie/$it?autoPlay=true" }, { id, s, e -> "https://vidsrc.cc/v2/embed/tv/$id/$s/$e?autoPlay=true" }),
        Provider("Videasy", { "https://player.videasy.net/movie/$it" }, { id, s, e -> "https://player.videasy.net/tv/$id/$s/$e" }),
        Provider("VidFast", { "https://vidfast.pro/movie/$it?autoPlay=true" }, { id, s, e -> "https://vidfast.pro/tv/$id/$s/$e?autoPlay=true" }),
    )

    /** Remote list (sources.json in the GitHub repo) if available, else the built-in one. */
    val PROVIDERS: List<Provider> get() = remote() ?: BUILT_IN

    private var remoteCache: Pair<String, List<Provider>>? = null

    private fun remote(): List<Provider>? {
        val json = com.anees.novamobile.data.local.LocalStore.remoteSources() ?: return null
        remoteCache?.let { if (it.first == json) return it.second }
        return runCatching {
            val arr = org.json.JSONObject(json).getJSONArray("sources")
            (0 until arr.length()).map { arr.getJSONObject(it) }.filter { it.optBoolean("enabled", true) }.map { o ->
                val movie = o.getString("movie"); val tv = o.getString("tv")
                Provider(o.getString("name"),
                    { id -> movie.replace("{id}", id.toString()) },
                    { id, s, e -> tv.replace("{id}", id.toString()).replace("{s}", s.toString()).replace("{e}", e.toString()) },
                    o.optLong("timeoutMs", 15_000), o.optBoolean("multiAudio", false))
            }.takeIf { it.isNotEmpty() }
        }.getOrNull()?.also { remoteCache = json to it }
    }

    /** Fetch the latest sources.json (called at app start, off the main thread). */
    fun refreshRemote() {
        runCatching {
            val req = okhttp3.Request.Builder().url(REMOTE_URL).header("Cache-Control", "no-cache").build()
            com.anees.novamobile.data.api.RetrofitClient.base.newCall(req).execute().use { r ->
                val body = r.body?.string()
                if (r.isSuccessful && body != null && org.json.JSONObject(body).has("sources"))
                    com.anees.novamobile.data.local.LocalStore.saveRemoteSources(body)
            }
        }
    }
    private const val REMOTE_URL = "https://raw.githubusercontent.com/anees-dahot/Nova-Movies-TV-App/main/sources.json"

    /** Providers that worked recently go first; ones that keep failing sink to the end. */
    fun ordered(): List<Provider> {
        // Wanting a dub (not "Original") puts multi-audio sources first, whatever their score
        val wantsDub = com.anees.novamobile.data.local.LocalStore.settings().audioLang != "orig"
        return PROVIDERS.sortedWith(
            compareByDescending<Provider> { wantsDub && it.multiAudio }
                .thenByDescending { com.anees.novamobile.data.local.LocalStore.providerScore(it.name) }
        )
    }

    /**
     * Found links stay valid for hours: keep them in memory and on disk (2h), so retries, source
     * switches, prefetches and Resume after an app restart skip the search entirely.
     */
    private const val TTL = 2 * 60 * 60_000L
    private val cache = HashMap<String, Pair<Long, WebStreamSniffer.Sniffed>>()
    private val gson = com.google.gson.Gson()

    fun cached(p: Provider, t: Target): WebStreamSniffer.Sniffed? {
        val key = embedUrl(p, t)
        cache[key]?.let { (at, s) -> if (System.currentTimeMillis() - at < TTL) return s }
        return com.anees.novamobile.data.local.LocalStore.stream(key)?.let { (at, json) ->
            if (System.currentTimeMillis() - at >= TTL) null
            else runCatching { gson.fromJson(json, WebStreamSniffer.Sniffed::class.java) }.getOrNull()?.also { cache[key] = at to it }
        }
    }

    fun remember(p: Provider, t: Target, s: WebStreamSniffer.Sniffed) {
        val key = embedUrl(p, t)
        cache[key] = System.currentTimeMillis() to s
        com.anees.novamobile.data.local.LocalStore.saveStream(key, gson.toJson(s))
    }

    fun forget(p: Provider, t: Target) {
        val key = embedUrl(p, t)
        cache.remove(key)
        com.anees.novamobile.data.local.LocalStore.saveStream(key, null)
    }

    /**
     * Some providers wrap every playlist/segment in a slow proxy (`…/api/robot-proxy?url=<real>`).
     * VidLux's proxy managed ~28 KB/s vs ~490 KB/s from the origin (Nov 2026), so go direct.
     */
    fun unwrapProxy(uri: android.net.Uri): android.net.Uri {
        if (uri.path?.contains("robot-proxy") != true) return uri
        return uri.getQueryParameter("url")?.let { android.net.Uri.parse(it) } ?: uri
    }

    fun embedUrl(p: Provider, t: Target) = if (t.isMovie) p.movie(t.tmdbId) else p.tv(t.tmdbId, t.season, t.episode)

    /** Movies/series map 1:1; anime goes AniList → TMDB via ani.zip (season/episode from TVDB numbering). */
    suspend fun resolveTarget(card: MediaCard, season: Int, episode: Int, repo: MediaRepository): Target? = when (card.type) {
        MediaType.MOVIE -> Target(card.tmdbId ?: card.id, true, 0, 0)
        MediaType.SERIES -> Target(card.tmdbId ?: card.id, false, season.coerceAtLeast(1), episode.coerceAtLeast(1))
        MediaType.ANIME -> {
            val zip = repo.animeMapping(card.aniListId ?: card.id)
            val tmdb = zip?.mappings?.tmdbId?.toIntOrNull()
            if (tmdb == null) null
            else if (zip.mappings.type == "MOVIE") Target(tmdb, true, 0, 0, zip.mappings.malId)
            else {
                val ep = zip.episodes?.get(episode.toString())
                Target(tmdb, false, ep?.seasonNumber ?: 1, ep?.episodeNumber ?: episode, zip.mappings.malId)
            }
        }
    }

    /** AniSkip opening interval in ms for an anime episode, or null. */
    suspend fun animeIntro(malId: Int, episode: Int): LongRange? = withContext(Dispatchers.IO) {
        runCatching {
            val url = "https://api.aniskip.com/v2/skip-times/$malId/$episode?types[]=op&episodeLength=0"
            RetrofitClient.http.newCall(Request.Builder().url(url).build()).execute().use { r ->
                val results = JSONObject(r.body?.string() ?: return@use null).optJSONArray("results") ?: return@use null
                if (results.length() == 0) return@use null
                val iv = results.getJSONObject(0).getJSONObject("interval")
                (iv.getDouble("startTime") * 1000).toLong()..(iv.getDouble("endTime") * 1000).toLong()
            }
        }.getOrNull()
    }
}
