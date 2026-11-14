package com.anees.novamobile.ui.player

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Fast tier of stream finding: plain HTTP, no WebView. Works the way yt-dlp's generic extractor and
 * Cloudstream/Aniyomi host extractors do:
 *  1. fetch the page, scan it for .m3u8/.mp4 links (absolute, relative, JSON-escaped)
 *  2. read player setups (`file:`/`src:`/`sources:`, `<source>`, og:video)
 *  3. unpack Dean Edwards `eval(function(p,a,c,k,e,d)…)` scripts and `atob("…")` blobs, scan again
 *  4. dive into iframes (in parallel, a few levels deep)
 *  5. check each candidate really is a stream (playlist header / big video file) before trusting it
 * Pages that build the link at runtime (heavy JS, Cloudflare) return null here and the WebView
 * sniffer takes over, so this tier only ever makes finding faster, never worse.
 */
object UniversalExtractor {
    private const val TAG = "NovaTV"
    private const val MAX_DEPTH = 2
    private const val MAX_IFRAMES = 4
    private const val MIN_MP4_BYTES = 5L * 1024 * 1024   // smaller = ad clip / preview

    internal var http: okhttp3.OkHttpClient = run {
        com.anees.novamobile.data.api.RetrofitClient.base.newBuilder()
            .connectTimeout(4, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS).callTimeout(7, TimeUnit.SECONDS)
            .followRedirects(true).build()
    }

    /** Embed hosts where the fast tier keeps missing: after a few misses the WebView starts at once. */
    private val misses = ConcurrentHashMap<String, Pair<Int, Long>>()   // host → (misses in a row, last miss)
    private const val MISS_TTL_MS = 30 * 60_000L
    /** Misses age out: a host that was down (or changed its page) gets the fast tier back, not "never again this session". */
    fun worthTrying(url: String): Boolean =
        misses[host(url)]?.let { (n, at) -> n < 3 || System.currentTimeMillis() - at > MISS_TTL_MS } ?: true
    private fun noteResult(url: String, ok: Boolean) {
        val h = host(url)
        val now = System.currentTimeMillis()
        if (ok) misses.remove(h)
        else misses.merge(h, 1 to now) { old, _ -> (if (now - old.second > MISS_TTL_MS) 1 else old.first + 1) to now }
    }

    suspend fun extract(pageUrl: String, userAgent: String): WebStreamSniffer.Sniffed? = withContext(Dispatchers.IO) {
        val t0 = System.currentTimeMillis()
        val r = runCatching { crawl(pageUrl, pageUrl, userAgent, 0, HashSet()) }
            .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }.getOrNull()
        // Lost the race (cancelled): says nothing about the host, so record no miss
        ensureActive()
        noteResult(pageUrl, r != null)
        log("fast extract ${if (r != null) "HIT" else "miss"} ${host(pageUrl)} in ${System.currentTimeMillis() - t0}ms: ${r?.url?.take(90)}")
        r
    }

    internal data class Found(val streams: List<String>, val subs: List<String>, val iframes: List<String>)

    private suspend fun crawl(url: String, referer: String, ua: String, depth: Int, seen: MutableSet<String>): WebStreamSniffer.Sniffed? {
        if (!synchronized(seen) { seen.add(url) } || isAd(url)) return null
        val html = fetch(url, referer, ua) ?: return null
        // The "page" is already a playlist (direct links, frames pointing straight at the stream)
        if (html.trimStart().startsWith("#EXTM3U")) return WebStreamSniffer.Sniffed(url, mapOf("Referer" to referer), ua, emptyList())
        val found = scan(html, url)
        val origin = URI(url).resolve("/").toString().removeSuffix("/")
        val headers = mapOf("Referer" to url, "Origin" to origin)
        // Known hosts whose link is assembled by script (Streamtape, DoodStream)
        hostSpecific(url, html, ua)?.let { s -> if (verify(s, headers, ua)) return WebStreamSniffer.Sniffed(s, headers, ua, found.subs) }
        // Check candidates in parallel; m3u8 first (adaptive, all qualities), then mp4
        val cands = found.streams.sortedBy { if (it.contains(".m3u8")) 0 else 1 }.take(8)
        if (cands.isNotEmpty()) {
            val ok = coroutineScope { cands.map { c -> async { verify(c, headers, ua) } }.awaitAll() }
            cands.zip(ok).firstOrNull { it.second }?.let { return WebStreamSniffer.Sniffed(it.first, headers, ua, found.subs) }
        }
        if (depth >= MAX_DEPTH) return null
        val frames = found.iframes.filterNot { isAd(it) }.take(MAX_IFRAMES)
        if (frames.isEmpty()) return null
        return coroutineScope {
            frames.map { f -> async { runCatching { crawl(f, url, ua, depth + 1, seen) }.getOrNull() } }.awaitAll().firstOrNull { it != null }
        }?.let { r -> if (found.subs.isEmpty()) r else r.copy(subtitles = (r.subtitles + found.subs).distinct()) }
    }

    /**
     * Hosts that build the link in script, ported from how Cloudstream/Aniyomi extractors read them.
     * Streamtape: robotlink = '//host/get_video?id=…' + ('xyz…').substring(n).
     * DoodStream: GET /pass_md5/… gives a base; link = base + 10 random chars + ?token=…&expiry=now.
     */
    internal fun hostSpecific(url: String, html: String, ua: String): String? = runCatching {
        STREAMTAPE_RE.find(html)?.let { m ->
            val tail = m.groupValues[2].drop(m.groupValues[3].toInt()).drop(m.groupValues[4].toIntOrNull() ?: 0)
            return "https:" + m.groupValues[1] + tail + "&stream=1"
        }
        val pass = DOOD_PASS_RE.find(html)?.groupValues?.get(1) ?: return null
        val token = DOOD_TOKEN_RE.find(html)?.groupValues?.get(1) ?: return null
        val base = fetch(URI(url).resolve(pass).toString(), url, ua)?.trim()?.takeIf { it.startsWith("http") } ?: return null
        val rnd = (1..10).map { ALPHA.random() }.joinToString("")
        "$base$rnd?token=$token&expiry=${System.currentTimeMillis()}"
    }.getOrNull()

    private fun fetch(url: String, referer: String, ua: String): String? = runCatching {
        val req = Request.Builder().url(url).header("User-Agent", ua).header("Referer", referer)
            .header("Accept", "text/html,application/xhtml+xml,*/*;q=0.8").build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) { if (r.header("cf-mitigated") != null || r.code == 403 || r.code == 503) log("fast tier blocked (${r.code}) on ${host(url)}"); return null }
            val type = r.header("Content-Type").orEmpty()
            if (type.startsWith("video") || type.startsWith("image")) return null
            r.body?.source()?.let { src -> src.request(2_000_000); src.buffer.readUtf8(minOf(src.buffer.size, 2_000_000)) }
        }
    }.getOrNull()

    /** Everything in [raw] that looks like a stream, subtitle or embedded player. */
    internal fun scan(raw: String, base: String): Found {
        val texts = mutableListOf(clean(raw))
        PACKED_RE.findAll(raw).take(8).forEach { m -> unpack(m.value)?.let { texts += clean(it) } }
        ATOB_RE.findAll(raw).take(30).forEach { m -> b64(m.groupValues[1])?.let { texts += clean(it) } }
        val streams = linkedSetOf<String>(); val subs = linkedSetOf<String>(); val frames = linkedSetOf<String>()
        for (t in texts) {
            ABS_MEDIA_RE.findAll(t).forEach { streams += it.value }
            QUOTED_MEDIA_RE.findAll(t).forEach { m -> abs(base, m.groupValues[1])?.let { streams += it } }
            PLAYER_KEY_RE.findAll(t).forEach { m -> abs(base, m.groupValues[1])?.takeIf { MEDIA_HINT.containsMatchIn(it) }?.let { streams += it } }
            SUB_RE.findAll(t).forEach { m -> abs(base, m.groupValues[1])?.let { subs += it } }
            EMBED_KEY_RE.findAll(t).forEach { m -> abs(base, m.groupValues[1])?.takeIf { it.startsWith("http") }?.let { frames += it } }
            IFRAME_RE.findAll(t).forEach { m -> abs(base, m.groupValues[1])?.takeIf { it.startsWith("http") }?.let { frames += it } }
        }
        return Found(streams.filterNot { isAd(it) }, subs.toList(), frames.toList())
    }

    /** Is [url] a real stream: a playlist that starts with #EXTM3U, or a video file bigger than an ad. */
    private fun verify(url: String, headers: Map<String, String>, ua: String): Boolean = runCatching {
        val b = Request.Builder().url(url).header("User-Agent", ua).header("Range", "bytes=0-2047")
        headers.forEach { (k, v) -> b.header(k, v) }
        http.newCall(b.build()).execute().use { r ->
            if (r.code != 200 && r.code != 206) return false
            val head = r.body?.source()?.let { s -> s.request(2048); s.buffer.readUtf8(minOf(s.buffer.size, 2048)) }.orEmpty()
            if (head.trimStart().startsWith("#EXTM3U")) return true
            val type = r.header("Content-Type").orEmpty()
            if (type.contains("html") || type.contains("json") || type.contains("text")) return false
            val total = r.header("Content-Range")?.substringAfter('/')?.toLongOrNull() ?: r.body?.contentLength() ?: -1
            total < 0 || total >= MIN_MP4_BYTES
        }
    }.getOrDefault(false)

    // ---- helpers ----

    private fun clean(s: String) = s.replace("\\/", "/").replace("\\u0026", "&").replace("\\u002F", "/").replace("&amp;", "&")

    private fun abs(base: String, u: String): String? = runCatching {
        val t = u.trim()
        if (t.isEmpty() || t.startsWith("data:") || t.startsWith("javascript:") || t.startsWith("blob:")) null
        else URI(base).resolve(t.replace(" ", "%20")).toString()
    }.getOrNull()

    private fun host(u: String) = runCatching { URI(u).host.orEmpty().removePrefix("www.") }.getOrDefault("")

    private fun isAd(u: String): Boolean {
        val l = u.lowercase()
        return AD_HOSTS.any { l.contains(it) } || AD_PATH_RE.containsMatchIn(l)
    }

    private fun log(msg: String) { runCatching { Log.i(TAG, msg) } }

    private fun b64(s: String): String? = runCatching {
        String(java.util.Base64.getMimeDecoder().decode(s), Charsets.UTF_8).takeIf { it.contains("http") || it.contains(".m3u8") || it.contains("<iframe") }
    }.getOrNull()

    /** Dean Edwards P.A.C.K.E.R. (`eval(function(p,a,c,k,e,d){…}('…',62,123,'a|b|c'.split('|')…))`). */
    internal fun unpack(packed: String): String? = runCatching {
        val m = PACKED_ARGS_RE.find(packed) ?: return null
        val payload = m.groupValues[1].replace("\\'", "'").replace("\\\\", "\\")
        val radix = m.groupValues[2].toInt()
        val count = m.groupValues[3].toInt()
        val words = m.groupValues[4].split('|')
        if (radix !in 2..62 || words.size < count) return null
        WORD_RE.replace(payload) { w ->
            val i = decodeBase(w.value, radix)
            if (i in words.indices && words[i].isNotEmpty()) words[i] else w.value
        }
    }.getOrNull()

    private const val ALPHA = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
    private fun decodeBase(s: String, radix: Int): Int {
        var n = 0
        for (c in s) { val d = ALPHA.indexOf(c); if (d < 0 || d >= radix) return -1; n = n * radix + d; if (n < 0) return -1 }
        return n
    }

    private val PACKED_RE = Regex("""eval\(function\(p,a,c,k,e,[dr]\).*?\.split\('\|'\)[^)]*\)\)""", RegexOption.DOT_MATCHES_ALL)
    private val PACKED_ARGS_RE = Regex("""\}\s*\('(.*)',\s*(\d+),\s*(\d+),\s*'(.*?)'\.split\('\|'\)""", RegexOption.DOT_MATCHES_ALL)
    private val WORD_RE = Regex("""\b\w+\b""")
    private val ATOB_RE = Regex("""atob\(\s*["']([A-Za-z0-9+/=]{16,4000})["']\s*\)""")
    private val ABS_MEDIA_RE = Regex("""https?://[^\s"'<>\\`]+?\.(?:m3u8|mp4)(?:\?[^\s"'<>\\`]*)?""", RegexOption.IGNORE_CASE)
    private val QUOTED_MEDIA_RE = Regex("""["'`]([^"'`\s<>]+?\.(?:m3u8|mp4)(?:\?[^"'`\s<>]*)?)["'`]""", RegexOption.IGNORE_CASE)
    private val PLAYER_KEY_RE = Regex("""(?:file|src|source|hls|video_url|videoUrl|stream|contentUrl|og:video(?::url)?["']\s+content)\s*[:=]\s*["']([^"']{8,})["']""", RegexOption.IGNORE_CASE)
    private val MEDIA_HINT = Regex("""\.m3u8|\.mp4|/hls/|/manifest|playlist""", RegexOption.IGNORE_CASE)
    private val SUB_RE = Regex("""["']([^"'\s<>]+?\.(?:vtt|srt)(?:\?[^"'\s<>]*)?)["']""", RegexOption.IGNORE_CASE)
    private val EMBED_KEY_RE = Regex("""["']?embedUrl["']?\s*:\s*["']([^"']+)["']""")
    private val STREAMTAPE_RE = Regex("""getElementById\('(?:robot|norobot)link'\)\.innerHTML\s*=\s*['"]([^'"]+)['"]\s*\+\s*\(?['"]([^'"]+)['"]\)?\.substring\((\d+)\)(?:\.substring\((\d+)\))?""")
    private val DOOD_PASS_RE = Regex("""['"](/pass_md5/[^'"]+)['"]""")
    private val DOOD_TOKEN_RE = Regex("""[?&]token=([a-z0-9]+)""")
    private val IFRAME_RE = Regex("""<iframe[^>]+?(?:data-)?src\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    private val AD_PATH_RE = Regex("""interstitial|preroll|/vast|vast\.|/ads?/|[_-]ad[_-]|demo-video|gambling|trailer""")
    private val AD_HOSTS = listOf(
        "doubleclick", "googlesyndication", "adservice", "google-analytics", "googletagmanager", "histats",
        "popads", "propeller", "adsterra", "exoclick", "juicyads", "mgid.com", "taboola", "outbrain", "adcash",
        "hilltopads", "clickadu", "a-ads.com", "bidgear", "onclicka", "adskeeper", "youtube.com", "youtu.be",
        "facebook.com", "twitter.com", "disqus",
    )
}
