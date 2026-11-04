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
                        val timeoutMs: Long = 15_000, val multiAudio: Boolean = false,
                        /**
                         * Search-based source (Vegamovies-style): finds the stream over plain HTTP from
                         * title + season/episode, so [movie]/[tv] are only its cache key, not a real page.
                         */
                        val resolver: (suspend (Target) -> WebStreamSniffer.Sniffed?)? = null,
                        /** Site that only lists player pages (Toonstream): finds the page, the WebView sniffs it. */
                        val page: (suspend (Target) -> String?)? = null) {
        /** A fixed embed page (prefetchable); page and resolver sources aren't. */
        val isEmbed get() = resolver == null && page == null
    }

    /** What to ask providers for: a TMDB id (movie or tv episode) plus the title, so
     *  search-based sources (Vegamovies) can find the page even though they don't take ids. */
    data class Target(val tmdbId: Int, val isMovie: Boolean, val season: Int, val episode: Int,
                      val malId: Int? = null, val title: String? = null, val year: Int? = null,
                      val imdbId: String? = null,
                      /** Anime: episode number within the AniList/MAL entry (season/episode are TVDB's). */
                      val aniEpisode: Int? = null)

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

    /**
     * Sources ported from Indflix (GPL-3.0): always on, independent of sources.json.
     * They resolve over plain HTTP, so they don't need an embed page (their movie/tv lambdas
     * are only stable cache keys).
     */
    val RESOLVERS = listOf(
        Provider("Vegamovies",
            { id -> "vegamovies://movie/$id" },
            { id, s, e -> "vegamovies://tv/$id/$s/$e" },
            timeoutMs = 30_000,
            resolver = { t -> com.anees.novamobile.data.sources.vegamovies.VegamoviesSource.resolve(t) }),
        // IndStream JSON APIs (Nov 2026: all three answered Inception + GoT S1E1 with playable links)
        Provider("NetMirror", { "netmirror://movie/$it" }, { id, s, e -> "netmirror://tv/$id/$s/$e" },
            timeoutMs = 20_000, multiAudio = true,
            resolver = { t -> com.anees.novamobile.data.sources.indstream.IndStreamSources.netmirror(t) }),
        Provider("VaPlayer", { "vaplayer://movie/$it" }, { id, s, e -> "vaplayer://tv/$id/$s/$e" },
            timeoutMs = 20_000,
            resolver = { t -> com.anees.novamobile.data.sources.indstream.IndStreamSources.vaplayer(t) }),
        Provider("Braflix", { "braflix://movie/$it" }, { id, s, e -> "braflix://tv/$id/$s/$e" },
            timeoutMs = 20_000,
            resolver = { t -> com.anees.novamobile.data.sources.indstream.IndStreamSources.braflix(t) }),
        // Hub of iframe servers (Cineverse Hindi-first, Filmu 1080p multi-audio, …), unwrapped over HTTP
        Provider("Multimovies", { "multimovies://movie/$it" }, { id, s, e -> "multimovies://tv/$id/$s/$e" },
            timeoutMs = 30_000, multiAudio = true,
            resolver = { t -> com.anees.novamobile.data.sources.multimovies.MultimoviesSource.resolve(t) }),
        // Anime with every Indian dub in one HLS (FirePlayer); skip non-anime titles instantly
        Provider("AnimeSalt", { "animesalt://movie/$it" }, { id, s, e -> "animesalt://tv/$id/$s/$e" },
            timeoutMs = 25_000, multiAudio = true,
            resolver = { t -> com.anees.novamobile.data.sources.anime.ToroAnimeSource.animeSalt.resolve(t) }),
        Provider("AnimeWorld", { "animeworld://movie/$it" }, { id, s, e -> "animeworld://tv/$id/$s/$e" },
            timeoutMs = 25_000, multiAudio = true,
            resolver = { t -> com.anees.novamobile.data.sources.anime.ToroAnimeSource.animeWorld.resolve(t) }),
        // One HLS with many dubs (Frieren: Hindi, Tamil, English, Japanese, German, Spanish, French)
        Provider("AniZone", { "anizone://movie/$it" }, { id, s, e -> "anizone://tv/$id/$s/$e" },
            timeoutMs = 20_000, multiAudio = true,
            resolver = { t -> com.anees.novamobile.data.sources.anime.AniZoneSource.resolve(t) }),
        // Castle app API: one HLS per dub (Hindi, Tamil, Telugu, English, Japanese…), 720p+
        Provider("CastleTV", { "castletv://movie/$it" }, { id, s, e -> "castletv://tv/$id/$s/$e" },
            timeoutMs = 25_000, multiAudio = true,
            resolver = { t -> com.anees.novamobile.data.sources.castle.CastleTvSource.resolve(t) }),
        // Anime/cartoons with every Indian dub in one stream; player pages sniffed by the WebView
        Provider("Toonstream", { "toonstream://movie/$it" }, { id, s, e -> "toonstream://tv/$id/$s/$e" },
            timeoutMs = 30_000, multiAudio = true,
            page = { t -> com.anees.novamobile.data.sources.toon.ToonstreamSource.page(t) }),
        // MovieBox: original + separate dub entries (lots of Hindi), MP4 mostly 480p: a fallback
        Provider("MovieBox", { "moviebox://movie/$it" }, { id, s, e -> "moviebox://tv/$id/$s/$e" },
            timeoutMs = 25_000, multiAudio = true,
            resolver = { t -> com.anees.novamobile.data.sources.castle.MovieBoxSource.resolve(t) }),
        // HiAnime's player: MAL id + episode → 1080p sub/dub HLS, new episodes fast
        Provider("ZokoAnime", { "zokoanime://movie/$it" }, { id, s, e -> "zokoanime://tv/$id/$s/$e" },
            timeoutMs = 15_000,
            resolver = { t -> com.anees.novamobile.data.sources.anime.ZokoAnimeSource.resolve(t) }),
    )

    /**
     * Embed pages added Nov 2026 (from the Nuvio provider lists), always on: unlike [BUILT_IN] they
     * aren't replaced by sources.json (a remote entry with the same name wins).
     */
    val EXTRA_EMBEDS = listOf(
        // JW Player page by TMDB id; English + Hindi
        Provider("XPass", { "https://play.xpass.top/e/movie/$it" }, { id, s, e -> "https://play.xpass.top/e/tv/$id/$s/$e" }, 20_000, multiAudio = true),
        // Token-signed HLS, often several audio languages
        Provider("VixSrc", { "https://vixsrc.to/movie/$it" }, { id, s, e -> "https://vixsrc.to/tv/$id/$s/$e" }, 20_000, multiAudio = true),
        Provider("VidRock", { "https://vidrock.net/movie/$it" }, { id, s, e -> "https://vidrock.net/tv/$id/$s/$e" }, 20_000),
    )

    /** Remote list (sources.json in the GitHub repo) if available, else the built-in one; then extra embeds + resolvers. */
    val PROVIDERS: List<Provider> get() {
        val base = remote() ?: BUILT_IN
        return base + EXTRA_EMBEDS.filter { e -> base.none { it.name == e.name } } + RESOLVERS
    }

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
        // Multi-audio sources first, whatever their score: the stream with the most languages wins
        return PROVIDERS.sortedWith(
            compareByDescending<Provider> { it.multiAudio }
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

    fun remember(p: Provider, t: Target, s0: WebStreamSniffer.Sniffed) {
        val key = embedUrl(p, t)
        // Playing one language variant: keep the others with it (it becomes the primary)
        val prev = cache[key]?.second
        val s = if (s0.variants == null && prev?.variants != null && prev.withVariants().any { it.url == s0.url })
            s0.copy(variants = prev.withVariants().filter { it.url != s0.url }) else s0
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

    /** Download sheet picks like "CastleTV#hi": the provider name and the language variant wanted. */
    fun splitPick(pick: String): Pair<String, String?> = pick.substringBefore('#') to pick.substringAfter('#', "").ifBlank { null }

    /** The stream in [lang] among [s] and its variants. */
    fun variant(s: WebStreamSniffer.Sniffed, lang: String?): WebStreamSniffer.Sniffed? =
        if (lang == null) s else s.withVariants().firstOrNull { it.audioLang == lang }

    /** The page the WebView sniffs: found by the source's [Provider.page] search, else its fixed embed URL. */
    suspend fun sniffUrl(p: Provider, t: Target): String? = when {
        p.resolver != null -> null   // its movie/tv URLs are cache keys, not pages: never load them in the WebView
        p.page != null -> runCatching { p.page.invoke(t) }.getOrNull()
        else -> embedUrl(p, t)
    }

    fun embedUrl(p: Provider, t: Target) = if (t.isMovie) p.movie(t.tmdbId) else p.tv(t.tmdbId, t.season, t.episode)

    /** Movies/series map 1:1; anime goes AniList → TMDB via ani.zip (season/episode from TVDB numbering). */
    suspend fun resolveTarget(card: MediaCard, season: Int, episode: Int, repo: MediaRepository): Target? = when (card.type) {
        MediaType.MOVIE -> Target(card.tmdbId ?: card.id, true, 0, 0, title = card.title, year = card.year.toIntOrNull(), imdbId = card.imdbId)
        MediaType.SERIES -> Target(card.tmdbId ?: card.id, false, season.coerceAtLeast(1), episode.coerceAtLeast(1),
            title = card.title, year = card.year.toIntOrNull(), imdbId = card.imdbId)
        MediaType.ANIME -> {
            val zip = repo.animeMapping(card.aniListId ?: card.id)
            val tmdb = zip?.mappings?.tmdbId?.toIntOrNull()
            if (tmdb == null) null
            else if (zip.mappings.type == "MOVIE") Target(tmdb, true, 0, 0, zip.mappings.malId, card.title, card.year.toIntOrNull(), aniEpisode = 1)
            else {
                val ep = zip.episodes?.get(episode.toString())
                Target(tmdb, false, ep?.seasonNumber ?: 1, ep?.episodeNumber ?: episode, zip.mappings.malId,
                    card.title, card.year.toIntOrNull(), aniEpisode = episode)
            }
        }
    }

    /** AniSkip opening interval in ms for an anime episode, or null. */
    /** AniSkip opening ("op") and ending ("ed") times for an anime episode. */
    suspend fun animeSkips(malId: Int, episode: Int): Map<String, LongRange> = withContext(Dispatchers.IO) {
        runCatching {
            val url = "https://api.aniskip.com/v2/skip-times/$malId/$episode?types[]=op&types[]=ed&episodeLength=0"
            RetrofitClient.http.newCall(Request.Builder().url(url).build()).execute().use { r ->
                val results = JSONObject(r.body?.string() ?: return@use emptyMap()).optJSONArray("results") ?: return@use emptyMap()
                (0 until results.length()).associate { i ->
                    val o = results.getJSONObject(i)
                    val iv = o.getJSONObject("interval")
                    o.optString("skipType") to (iv.getDouble("startTime") * 1000).toLong()..(iv.getDouble("endTime") * 1000).toLong()
                }
            }
        }.getOrDefault(emptyMap())
    }
}
