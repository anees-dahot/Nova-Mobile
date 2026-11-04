package com.anees.novamobile.data.sources.multimovies

/*
 * Ported from Indflix (https://github.com/dipender98/Indflix), GPL-3.0.
 * Original: Multimovies/src/main/kotlin/com/multimovies/MultimoviesPlugin.kt
 *   (parseV2SearchApi, parseV2WatchConfig/parseV2Servers, extractTmdbIdFromServerUrl,
 *   MultimoviesDomainResolver, MultiSourcePuller.unwrapEmbed + stream regexes)
 *   and ExternalSources.kt (ModiplayExtractor / parseModiplayStreams).
 * Adapted: no CloudStream load/search pipeline: one title → its page (or episode page) →
 * the page's iframe servers, unwrapped over plain HTTP in parallel; every candidate playlist
 * is fetched once before it is returned, so a token-locked link never reaches the player.
 * GDMirror (iqsmartgames) is not ported: its host did not answer during the Nov 2026 port.
 */

import android.util.Log
import com.anees.novamobile.data.sources.vegamovies.VmHttp
import com.anees.novamobile.data.sources.vegamovies.matchScore
import com.anees.novamobile.ui.player.StreamExtractor
import com.anees.novamobile.ui.player.WebStreamSniffer
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URLDecoder
import java.net.URLEncoder

object MultimoviesSource {

    private const val TAG = "NovaTV"
    private const val LANDING_URL = "https://multimovies.wtf/"
    private const val SEED_DOMAIN = "https://m1.multimovies.courses"
    private val LIVE_DOMAIN = Regex("""^https://(?:[a-z0-9-]+\.)?multimovies\.[a-z]{2,10}$""")
    private const val MIN_MATCH = 0.45
    private const val EXTRACT_BUDGET_MS = 14_000L
    private const val MAX_UNWRAP_LEVELS = 3

    @Volatile private var domain = SEED_DOMAIN
    @Volatile private var healedAt = 0L

    private val headers get() = VmHttp.commonHeaders

    private data class Hit(val title: String, val url: String, val year: Int?, val isSeries: Boolean)
    private data class Server(val name: String, val url: String)
    private data class Found(val server: String, val url: String, val referer: String)

    // ── Entry point ───────────────────────────────────────────────────────────

    suspend fun resolve(t: StreamExtractor.Target): WebStreamSniffer.Sniffed? {
        val title = t.title?.trim()?.takeIf { it.isNotBlank() } ?: return null
        var hits = search(title)
        if (hits.isEmpty() && heal()) hits = search(title)
        val ranked = hits.filter { it.isSeries == !t.isMovie }
            .map { it to matchScore(title, it.title, it.year, t.year) }
            .filter { it.second >= MIN_MATCH }
            .sortedByDescending { it.second }
            .take(3)
        for ((hit, _) in ranked) {
            val page = absolute(hit.url).trimEnd('/') + if (t.isMovie) "" else "/season/${t.season}/episode/${t.episode}"
            val html = VmHttp.get(page, headers, 10_000) ?: continue
            val servers = servers(html)
            if (servers.isEmpty()) continue
            // Servers embed the TMDB id: a page for some other title with the same name is skipped
            val ids = servers.mapNotNull { tmdbIdOf(it.url) }.toSet()
            if (ids.isNotEmpty() && t.tmdbId.toString() !in ids) { Log.i(TAG, "multimovies: \"${hit.title}\" is another title"); continue }
            val found = extract(servers, page) ?: continue
            Log.i(TAG, "multimovies: \"${hit.title}\" via ${found.server}")
            return WebStreamSniffer.Sniffed(found.url, mapOf("Referer" to found.referer), VmHttp.UA, emptyList())
        }
        return null
    }

    // ── Search + domain ───────────────────────────────────────────────────────

    private suspend fun search(query: String): List<Hit> {
        val q = URLEncoder.encode(query, "UTF-8")
        val root = VmHttp.get("$domain/api/search?q=$q&limit=12", headers, 8_000)
            ?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return emptyList()
        val out = ArrayList<Hit>()
        fun push(o: JSONObject?) {
            o ?: return
            val kind = o.optString("type")
            if (kind != "movie" && kind != "tv") return
            val title = o.optString("title").ifBlank { o.optString("name") }.trim()
            val url = o.optString("url").trim()
            if (title.isEmpty() || url.isEmpty()) return
            val year = o.optInt("year", -1).takeIf { it > 0 }
            out += Hit(title, url, year, kind == "tv")
        }
        val results = root.optJSONArray("results")
        if (results != null) for (i in 0 until results.length()) push(results.optJSONObject(i))
        else listOf("movies", "tv_shows", "tv").forEach { k ->
            root.optJSONArray(k)?.let { a -> for (i in 0 until a.length()) push(a.optJSONObject(i)) }
        }
        return out.distinctBy { it.url }
    }

    /** Domains rotate: the landing page links to the live one most often. At most once a minute. */
    private suspend fun heal(): Boolean {
        if (System.currentTimeMillis() - healedAt < 60_000) return false
        healedAt = System.currentTimeMillis()
        val html = VmHttp.get(LANDING_URL, headers, 8_000) ?: return false
        val live = Jsoup.parse(html).select("a[href]")
            .map { it.attr("href").trim().trimEnd('/').replace("https://www.", "https://") }
            .filter { LIVE_DOMAIN.matches(it) && it != LANDING_URL.trimEnd('/') }
            .groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: return false
        if (live == domain) return false
        Log.i(TAG, "multimovies: domain $domain → $live")
        domain = live
        return true
    }

    private fun absolute(url: String) = if (url.startsWith("http")) url else domain + (if (url.startsWith("/")) url else "/$url")

    // ── Page servers (watchConfig JSON + server chips) ────────────────────────

    private fun servers(html: String): List<Server> {
        val out = ArrayList<Server>()
        Regex("""watchConfig\s*=\s*(\{.*?\})\s*;""", RegexOption.DOT_MATCHES_ALL).find(html)?.let { m ->
            runCatching {
                val arr = JSONObject(m.groupValues[1]).optJSONArray("initialServers")
                for (i in 0 until (arr?.length() ?: 0)) {
                    val o = arr!!.optJSONObject(i) ?: continue
                    val url = o.optString("url").replace("\\/", "/").replace("&amp;", "&").trim()
                    if (url.startsWith("http")) out += Server(o.optString("name").trim().ifEmpty { "Server" }, url)
                }
            }
        }
        Jsoup.parse(html).select("button.server-chip[data-url]").forEach { b ->
            val url = b.attr("data-url").replace("&amp;", "&").trim()
            if (url.startsWith("http")) out += Server(b.selectFirst(".server-name")?.text()?.trim() ?: "Server", url)
        }
        return out.distinctBy { it.url }.filterNot { isYouTube(it.url) }
    }

    private fun tmdbIdOf(url: String): String? =
        Regex("""[?&]id=(\d{2,10})""").find(url)?.groupValues?.get(1)
            ?: Regex("""/(?:movie|tv)/(\d{2,10})""").find(url)?.groupValues?.get(1)
            ?: Regex("""[?&]tmdb=(\d{2,10})""").find(url)?.groupValues?.get(1)

    private fun isYouTube(url: String) = hostOf(url).let { it.contains("youtube") || it.contains("youtu.be") }

    private fun hostOf(url: String) = url.substringAfter("://").substringBefore("/").lowercase()

    // ── Extraction ────────────────────────────────────────────────────────────

    /**
     * Unwraps every server at once and keeps the best that answered in time: Cineverse
     * (Hindi-first multi-audio) and Filmu (1080p, English + Hindi) ahead of unknown embeds.
     */
    private suspend fun extract(servers: List<Server>, pageUrl: String): Found? = coroutineScope {
        val jobs = servers.take(10).map { s ->
            async { withTimeoutOrNull(EXTRACT_BUDGET_MS) { runCatching { extractOne(s, pageUrl) }.getOrNull() } }
        }
        jobs.awaitAll().filterNotNull().minByOrNull { priority(it) }
    }

    private fun priority(f: Found): Int = when {
        hostOf(f.referer).contains("modiplay") -> 0
        hostOf(f.referer).contains("filmu") -> 1
        else -> 2
    }

    private suspend fun extractOne(s: Server, pageUrl: String): Found? {
        if (hostOf(s.url).contains("modiplay")) return modiplay(s)
        var current = s.url
        var referer = pageUrl
        repeat(MAX_UNWRAP_LEVELS) {
            val html = VmHttp.get(current, headers + ("Referer" to referer), 8_000) ?: return null
            streamUrlIn(html, current)?.let { url ->
                val ref = origin(current) + "/"
                return if (playable(url, ref)) Found(s.name, url, ref) else null
            }
            val frame = Jsoup.parse(html).select("iframe[src]").map { it.attr("src").trim() }
                .firstOrNull { it.isNotBlank() && !it.startsWith("about:") && !it.startsWith("javascript:") } ?: return null
            val next = resolveRelative(current, frame.replace("&amp;", "&"))
            if (next == current) return null
            referer = current
            current = next
        }
        return null
    }

    /** Cineverse: embed → proxy.php player → its own /stream_proxy.php playlist (the CDN link is token-locked). */
    private suspend fun modiplay(s: Server): Found? {
        val embed = VmHttp.get(s.url, headers + ("Referer" to "$domain/"), 8_000) ?: return null
        val frame = Regex("""<iframe[^>]+src=["']([^"']*proxy\.php[^"']*)["']""", RegexOption.IGNORE_CASE)
            .find(embed)?.groupValues?.get(1)?.replace("&amp;", "&") ?: return null
        val player = VmHttp.get(resolveRelative(s.url, frame), headers + ("Referer" to s.url), 8_000) ?: return null
        val clean = player.replace("\\/", "/").replace("&amp;", "&")
        val base = origin(s.url)
        val candidates = Regex("""/stream_proxy\.php\?url=[^"'\s<>]*?\.m3u8[^"'\s<>]*""").findAll(clean)
            .map { base + it.value }.toList()
        for (url in candidates.distinct().take(3)) {
            if (playable(url, "$base/")) return Found(s.name, url, "$base/")
        }
        return null
    }

    /** First stream link a page hands out: m3u8/mp4 literals, JS player configs, <video src>, encoded master urls. */
    private fun streamUrlIn(html: String, baseUrl: String): String? {
        val text = html.replace("\\/", "/").replace("\\\"", "\"")
        Regex("""(?:https?:)?//[^"'\s<>]*proxy\.php\?[^"'\s<>]*serve_m3u8=1[^"'\s<>]*""").find(text)
            ?.let { return resolveRelative(baseUrl, it.value) }
        STREAM_URL.firstNotNullOfOrNull { r -> r.find(text)?.value?.trim('"', '\'', '\\') }?.let { return it }
        CONFIG_URL.firstNotNullOfOrNull { r -> r.find(text)?.groupValues?.get(1) }
            ?.let { return resolveRelative(baseUrl, it) }
        Jsoup.parse(html).selectFirst("video[src], video source[src], source[src]")?.attr("src")?.trim()
            ?.takeIf { it.isNotBlank() }?.let { return resolveRelative(baseUrl, it) }
        Regex("""url=([^"'&\s]+?%2F[^"'&\s]*master\.m3u8[^"'&\s]*)""", RegexOption.IGNORE_CASE).find(text)
            ?.let { m -> runCatching { URLDecoder.decode(m.groupValues[1], "UTF-8") }.getOrNull() }
            ?.takeIf { it.startsWith("http") }?.let { return it }
        return null
    }

    private val STREAM_URL = listOf(
        Regex("""https?://[^\s"'<>\\]+\.m3u8[^\s"'<>\\]*"""),
        Regex("""https?://[^\s"'<>\\]+\.mp4[^\s"'<>\\]*"""),
    )

    private val CONFIG_URL = listOf(
        Regex("""["']?(?:file|url|src|hlsUrl|hls_source|streamUrl|stream_url|playUrl)["']?\s*[:=]\s*["']([^"']+\.(?:m3u8|mp4)[^"']*)["']""", RegexOption.IGNORE_CASE),
        Regex("""sources\s*[:=]\s*\[\s*\{\s*["']?file["']?\s*[:=]\s*["']([^"']+\.(?:m3u8|mp4)[^"']*)["']""", RegexOption.IGNORE_CASE),
    )

    /** One fetch of the playlist: token-locked or dead links (403, HTML error page) are dropped here. */
    private suspend fun playable(url: String, referer: String): Boolean {
        if (!url.contains("m3u8", ignoreCase = true)) return true    // mp4: the player's own retry covers it
        val body = VmHttp.get(url, headers + ("Referer" to referer), 6_000) ?: return false
        return body.trimStart().startsWith("#EXTM3U")
    }

    private fun origin(url: String) = Regex("""^https?://[^/]+""").find(url)?.value ?: url

    private fun resolveRelative(base: String, path: String): String = when {
        path.startsWith("//") -> "https:$path"
        path.startsWith("http", ignoreCase = true) -> path
        path.startsWith("/") -> origin(base) + path
        else -> origin(base) + "/" + path
    }
}
