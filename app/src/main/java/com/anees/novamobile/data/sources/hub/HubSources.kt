package com.anees.novamobile.data.sources.hub

import android.util.Base64
import android.util.Log
import com.anees.novamobile.data.sources.LinkProbe
import com.anees.novamobile.data.sources.vegamovies.VmHttp
import com.anees.novamobile.data.sources.vegamovies.matchScore
import com.anees.novamobile.ui.player.ReleaseInfo
import com.anees.novamobile.ui.player.StreamExtractor
import com.anees.novamobile.ui.player.StreamMeta
import com.anees.novamobile.ui.player.WebStreamSniffer
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.jsoup.Jsoup

/**
 * Big direct-file release sites (Nov 2026), resolved over plain HTTP:
 *  - 4KHDHub: real releases with full names and sizes — WEB-DL / BluRay / REMUX, x265 10-bit, DV/HDR, IMAX,
 *    Hindi + English — each a direct MKV on R2 storage (Range works: streams and downloads).
 * Chain: search → title page (one block per release) → encoded redirect → HubCloud → final file links.
 * One file per picture height (the smallest of each), so the Sources page shows every quality with its release.
 */
object HubSources {
    private const val TAG = "NovaTV"
    private val UA = VmHttp.UA
    private const val DOMAINS = "https://raw.githubusercontent.com/phisher98/TVVVV/refs/heads/main/domains.json"
    @Volatile private var domains: JSONObject? = null
    @Volatile private var domainsAt = 0L
    @Volatile private var domainsTriedAt = 0L

    /** Live domain of a site from the community domains list (6h), else [fallback]. */
    private suspend fun domain(key: String, fallback: String): String {
        val now = System.currentTimeMillis()
        // List unreachable (GitHub raw is blocked on some networks): ask again in 5 minutes, not on every
        // search, where each of the three hub sources waited out the timeout first
        if ((domains == null || now - domainsAt > 6 * 3600_000L) && now - domainsTriedAt > 5 * 60_000L) {
            domainsTriedAt = now
            VmHttp.get(DOMAINS, mapOf("User-Agent" to UA), 6_000)?.let { runCatching { JSONObject(it) }.getOrNull() }?.let { domains = it; domainsAt = System.currentTimeMillis() }
        }
        return domains?.optString(key)?.takeIf { it.startsWith("http") }?.trimEnd('/') ?: fallback
    }

    private val headers get() = mapOf("User-Agent" to UA, "Accept" to "text/html,*/*")

    /** Search results and title / season pages for 10 minutes: a season downloaded in bulk fetches them once, not per episode. */
    private val pages = object : LinkedHashMap<String, Pair<Long, String>>(32, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, Pair<Long, String>>) = size > 40
    }
    private suspend fun page(url: String, h: Map<String, String>, timeout: Long): String? {
        synchronized(pages) { pages[url]?.takeIf { System.currentTimeMillis() - it.first < 10 * 60_000L }?.let { return it.second } }
        return VmHttp.get(url, h, timeout)?.also { body -> synchronized(pages) { pages[url] = System.currentTimeMillis() to body } }
    }

    // ── 4KHDHub ───────────────────────────────────────────────────────────────

    suspend fun fourKHdHub(t: StreamExtractor.Target): WebStreamSniffer.Sniffed? {
        val title = t.title?.takeIf { it.isNotBlank() } ?: return null
        val base = domain("4khdhub", "https://4khdhub.one")
        val search = page("$base/?s=" + java.net.URLEncoder.encode("$title ${t.year ?: ""}".trim(), "UTF-8"), headers, 12_000) ?: return null
        val want = if (t.isMovie) "Movies" else "Series"
        val pageUrl = Jsoup.parse(search, base).select(".movie-card").mapNotNull { c ->
            if (!c.select(".movie-card-format").text().contains(want)) return@mapNotNull null
            val y = c.select(".movie-card-meta").text().trim().take(4).toIntOrNull()
            if (t.year != null && y != null && kotlin.math.abs(y - t.year) > 1) return@mapNotNull null
            val name = c.select(".movie-card-title").text().replace(Regex("""\[.*?]"""), "").trim()
            val score = matchScore(title, name, y, t.year)
            if (score < 0.6) null else c.absUrl("href").ifBlank { null }?.let { it to score }
        }.maxByOrNull { it.second }?.first ?: return null
        val html = page(pageUrl, headers, 12_000) ?: return null
        val doc = Jsoup.parse(html, pageUrl)
        val items = if (t.isMovie) doc.select(".download-item") else {
            val s = "S" + t.season.toString().padStart(2, '0')
            val e = "Episode-" + t.episode.toString().padStart(2, '0')
            doc.select(".episode-item").toList().filter { it.select(".episode-title").text().contains(s) }
                .flatMap { it.select(".episode-download-item").toList() }.filter { it.text().contains(e) }
        }
        data class Rel(val name: String, val bytes: Long?, val height: Int, val link: String)
        val rels = items.mapNotNull { el ->
            val name = el.select(".file-title, .episode-file-title").text().trim().ifBlank { el.text().take(160) }
            val link = el.select("a").map { it.absUrl("href") }.firstOrNull { it.contains("hubcloud") || it.contains("?id=") } ?: return@mapNotNull null
            val meta = ReleaseInfo.parse(name)
            Rel(name, meta?.bytes ?: Regex("""([\d.]+)\s?(GB|MB)""").find(el.text())?.let { sizeOf(it.value) }, ReleaseInfo.height(name) ?: 0, link)
        }
        if (rels.isEmpty()) return null
        // One release per height: the smallest (streams best); every height offered
        val picks = rels.filter { it.height > 0 }.groupBy { it.height }.map { (_, l) -> l.minBy { it.bytes ?: Long.MAX_VALUE } }
            .ifEmpty { listOf(rels.minBy { it.bytes ?: Long.MAX_VALUE }) }.sortedBy { it.height }.take(4)
        val files = coroutineScope {
            picks.map { r -> async { withTimeoutOrNull(20_000) { runCatching { hubCloud(resolveRedirect(r.link)) }.getOrNull() }?.let { r to it } } }.awaitAll().filterNotNull()
        }
        if (files.isEmpty()) return null
        val options = files.map { (r, f) ->
            WebStreamSniffer.Sniffed.Option(f.url, r.height.takeIf { it > 0 } ?: 1080, f.bytes ?: r.bytes,
                (ReleaseInfo.parse(f.name) ?: StreamMeta()).orElse(ReleaseInfo.parse(r.name)).copy(server = "HubCloud", bytes = f.bytes ?: r.bytes))
        }.distinctBy { it.height }.sortedBy { it.height }
        Log.i(TAG, "4khdhub: ${options.joinToString { "${it.height}p ${it.bytes}" }}")
        val top = options.lastOrNull { it.height <= 1080 } ?: options.first()
        return WebStreamSniffer.Sniffed(top.url, emptyMap(), UA, emptyList(), height = top.height,
            options = options.takeIf { it.size > 1 }, meta = top.meta)
    }

    // ── HDHub4u ───────────────────────────────────────────────────────────────

    /**
     * HDHub4u: huge Indian + Hollywood catalogue (Hindi dubs, 480p→4K, x264 / 10-bit HEVC with sizes).
     * Its search moved to a Typesense host that also returns the IMDb id, so matches are exact.
     */
    suspend fun hdHub4u(t: StreamExtractor.Target): WebStreamSniffer.Sniffed? {
        val title = t.title?.takeIf { it.isNotBlank() } ?: return null
        val base = domain("HDHUB4u", "https://new12.hdhub4u.cl")
        val h = headers + mapOf("Cookie" to "xla=s4t", "Referer" to "$base/", "Origin" to base)
        val q = java.net.URLEncoder.encode(if (t.isMovie) title else "$title Season ${t.season}", "UTF-8")
        val json = page("https://search.pingora.fyi/collections/post/documents/search?q=$q&query_by=post_title,category" +
            "&query_by_weights=4,2&sort_by=sort_by_date:desc&limit=15&highlight_fields=none&use_cache=true&page=1", h, 10_000) ?: return null
        val hits = runCatching { JSONObject(json).getJSONArray("hits") }.getOrNull() ?: return null
        data class Hit(val title: String, val url: String, val imdb: String?, val score: Double)
        val best = (0 until hits.length()).mapNotNull { hits.optJSONObject(it)?.optJSONObject("document") }.mapNotNull { d ->
            val name = d.optString("post_title"); val url = d.optString("permalink").ifBlank { return@mapNotNull null }
            val imdb = d.optString("imdb_id").takeIf { it.startsWith("tt") }
            if (!t.isMovie && !Regex("""(?i)season\s*0?${t.season}\b|\bS0?${t.season}\b""").containsMatchIn(name)) return@mapNotNull null
            val y = Regex("""\((\d{4})\)""").find(name)?.groupValues?.get(1)?.toIntOrNull()
            val sc = if (imdb != null && imdb == t.imdbId) 10.0 else matchScore(title, name.substringBefore("(").trim(), y, t.year)
            Hit(name, url, imdb, sc)
        }.filter { it.score >= 0.6 }.maxByOrNull { it.score } ?: return null
        val pageUrl = best.url.replace(Regex("""^https?://[^/]+"""), base)
        val doc = Jsoup.parse(page(pageUrl, h, 12_000) ?: return null, pageUrl)
        data class Rel(val label: String, val link: String, val height: Int, val bytes: Long?)
        val anchors: List<Pair<String, String>> = if (t.isMovie) {
            doc.select("h3 a, h4 a").map { it.text().trim() to it.absUrl("href") }
        } else {
            // "EPiSODE 3" heading: its links and those after it until the next rule
            val ep = Regex("""(?i)(?:EPiSODE\s*0*(\d+)|\bE0*(\d+)\b)""")
            doc.select("h3, h4").toList().filter { el -> ep.find(el.text())?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() }?.toIntOrNull() == t.episode }
                .flatMap { el ->
                    val out = el.select("a[href]").map { a -> "${el.text()} ${a.text()}" to a.absUrl("href") }.toMutableList()
                    var n = el.nextElementSibling()
                    while (n != null && n.tagName() != "hr" && !ep.containsMatchIn(n.text().take(40))) { out += n.select("a[href]").map { a -> "${el.text()} ${a.text()}" to a.absUrl("href") }; n = n.nextElementSibling() }
                    out
                }
        }
        // Movies: one link per quality heading. Episodes: "E01 | Watch" rows carry no quality — read it from the file
        val rels = if (t.isMovie) anchors.filter { Regex("""(?i)480|720|1080|2160|4K""").containsMatchIn(it.first) && !it.first.contains("zip", true) }
            .map { (l, u) -> Rel(l, u, ReleaseInfo.height(l) ?: 0, sizeOf(l)) }.filter { it.height > 0 }
            else anchors.filter { !it.second.contains("hubstream") && !it.second.contains("hdstream4u") && !it.first.contains("zip", true) }.distinctBy { it.second }
                .map { (l, u) -> Rel(l, u, ReleaseInfo.height(l) ?: 0, sizeOf(l)) }
        // "WATCH" player (hdstream4u): a multi-quality HLS of the same release, used when the files are dead and kept as a spare
        val watchUrl = anchors.map { it.second }.firstOrNull { it.contains("hdstream4u") }
        val picks = if (t.isMovie) rels.groupBy { it.height }.map { (_, l) -> l.minBy { it.bytes ?: Long.MAX_VALUE } }.sortedBy { it.height }.take(4) else rels.take(3)
        val pageMeta = ReleaseInfo.parse(best.title)
        val (files, watch) = coroutineScope {
            val w = async { watchUrl?.let { hdStream(it, pageMeta) } }
            picks.map { r -> async { withTimeoutOrNull(20_000) { runCatching { hubLink(r.link, h) }.getOrNull() }?.let { r to it } } }.awaitAll().filterNotNull() to w.await()
        }
        if (files.isEmpty()) return watch?.also { Log.i(TAG, "hdhub4u: files dead, WATCH stream for \"${best.title.take(60)}\"") }
        val options = files.map { (r, f) ->
            val m = (ReleaseInfo.parse("${f.name ?: ""} ${r.label}") ?: StreamMeta()).orElse(pageMeta).copy(server = "HubCloud", bytes = f.bytes ?: r.bytes)
            val height = r.height.takeIf { it > 0 } ?: ReleaseInfo.height(f.name) ?: ReleaseInfo.height(best.title)?.coerceAtMost(1080) ?: 720
            WebStreamSniffer.Sniffed.Option(f.url, height, f.bytes ?: r.bytes, m)
        }.distinctBy { it.height }.sortedBy { it.height }
        Log.i(TAG, "hdhub4u: \"${best.title.take(60)}\" ${options.joinToString { "${it.height}p" }}${if (watch != null) " + WATCH" else ""}")
        val top = options.lastOrNull { it.height <= 1080 } ?: options.first()
        return WebStreamSniffer.Sniffed(top.url, emptyMap(), UA, emptyList(), height = top.height,
            options = options.takeIf { it.size > 1 }, meta = top.meta, variants = watch?.let { listOf(it) })
    }

    /** hdstream4u embed (packed JS → jwplayer HLS), only if its playlist answers. */
    private suspend fun hdStream(url: String, pageMeta: StreamMeta?): WebStreamSniffer.Sniffed? = withTimeoutOrNull(12_000) {
        val s = runCatching { com.anees.novamobile.ui.player.UniversalExtractor.extract(url, UA) }.getOrNull() ?: return@withTimeoutOrNull null
        if (!LinkProbe.alive(s.url, s.headers)) return@withTimeoutOrNull null
        s.copy(meta = (pageMeta ?: StreamMeta()).copy(server = "HDStream"))
    }

    // ── MoviesDrive ──────────────────────────────────────────────────────────

    /** MoviesDrive: IMDb-exact search, every quality (480p→4K, x264 / 10-bit HEVC) with size, via mdrive → HubCloud. */
    suspend fun moviesDrive(t: StreamExtractor.Target): WebStreamSniffer.Sniffed? {
        val base = domain("moviesdrive", "https://new2.moviesdrive.cyou")
        val h = headers + ("Referer" to "$base/")
        val q = t.imdbId?.takeIf { it.startsWith("tt") } ?: t.title?.takeIf { it.isNotBlank() } ?: return null
        val json = page("$base/search.php?q=" + java.net.URLEncoder.encode(q, "UTF-8"), h, 10_000) ?: return null
        val docs = runCatching { JSONObject(json).getJSONArray("hits") }.getOrNull()?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optJSONObject("document") } }.orEmpty()
        val hit = docs.firstOrNull { t.imdbId != null && it.optString("imdb_id") == t.imdbId }
            ?: docs.firstOrNull { d -> t.title != null && matchScore(t.title, d.optString("post_title").substringBefore("(").trim(),
                Regex("""\((\d{4})\)""").find(d.optString("post_title"))?.groupValues?.get(1)?.toIntOrNull(), t.year) >= 0.7 } ?: return null
        val pageUrl = hit.optString("permalink").let { if (it.startsWith("http")) it else base + it }
        val doc = Jsoup.parse(page(pageUrl, h, 12_000) ?: return null, pageUrl)
        val anchors: List<Pair<String, String>> = if (t.isMovie) {
            doc.select("h5 > a").map { it.text().replace('\u00a0', ' ').trim() to it.absUrl("href") }
        } else {
            // "Season 2" heading → its episode page → "Ep03" heading → the links after it
            val sep = Regex("""(?i)\bEp0*${t.episode}\b""")
            doc.select("h5").toList().filter { Regex("""(?i)Season\s*0*${t.season}\b""").containsMatchIn(it.text()) }.take(4).flatMap { e ->
                val label = e.text()
                val next = e.nextElementSibling()?.selectFirst("a")?.absUrl("href") ?: return@flatMap emptyList()
                val ep = Jsoup.parse(page(next, h, 10_000) ?: return@flatMap emptyList(), next)
                ep.select("h5").toList().filter { sep.containsMatchIn(it.text()) }.flatMap { x ->
                    listOfNotNull(x.nextElementSibling(), x.nextElementSibling()?.nextElementSibling()).mapNotNull { it.selectFirst("a")?.absUrl("href") }
                        .filter { it.contains("hubcloud") || it.contains("mdrive") }.map { label to it }
                }
            }
        }
        data class Rel(val label: String, val link: String, val height: Int, val bytes: Long?)
        val rels = anchors.map { (l, u) -> Rel(l, u, ReleaseInfo.height(l) ?: 0, sizeOf(l)) }.filter { it.height > 0 && !it.label.contains("zip", true) }
        if (rels.isEmpty()) return null
        val picks = rels.groupBy { it.height }.map { (_, l) -> l.minBy { it.bytes ?: Long.MAX_VALUE } }.sortedBy { it.height }.take(4)
        val pageMeta = ReleaseInfo.parse(hit.optString("post_title"))
        val files = coroutineScope {
            picks.map { r -> async { withTimeoutOrNull(20_000) { runCatching {
                if (r.link.contains("hubcloud")) hubCloud(r.link)
                else VmHttp.get(r.link, h, 10_000)?.let { Regex("""https?://[^"'\s]*hubcloud[^"'\s]*/drive/[^"'\s]+""").find(it)?.value }?.let { hubCloud(it) }
            }.getOrNull() }?.let { r to it } } }.awaitAll().filterNotNull()
        }
        if (files.isEmpty()) return null
        val options = files.map { (r, f) ->
            val m = (ReleaseInfo.parse("${f.name ?: ""} ${r.label}") ?: StreamMeta()).orElse(pageMeta).copy(server = "HubCloud", bytes = f.bytes ?: r.bytes)
            WebStreamSniffer.Sniffed.Option(f.url, r.height, f.bytes ?: r.bytes, m)
        }.distinctBy { it.height }.sortedBy { it.height }
        Log.i(TAG, "moviesdrive: ${options.joinToString { "${it.height}p" }}")
        val top = options.lastOrNull { it.height <= 1080 } ?: options.first()
        return WebStreamSniffer.Sniffed(top.url, emptyMap(), UA, emptyList(), height = top.height,
            options = options.takeIf { it.size > 1 }, meta = top.meta)
    }

    /** Any HDHub-family link → a direct file: hubcdn (base64 Google link), gateways, hblinks / hubdrive pages, HubCloud. */
    private suspend fun hubLink(url: String, h: Map<String, String>): HubFile? {
        if (url.contains("hubcdn")) {
            val html = VmHttp.get(url, h, 10_000) ?: return null
            val enc = Regex("""[?&]r=([A-Za-z0-9+/=]{20,})""").find(html)?.groupValues?.get(1) ?: return null
            // Not base64 after all, or not a link: "no file" for this pick, never an exception that ends the whole source
            val inner = runCatching { b64(enc) }.getOrNull() ?: return null
            val direct = Regex("""link=(https?://\S+)""").find(inner)?.groupValues?.get(1) ?: inner
            val file = runCatching { java.net.URLDecoder.decode(direct, "UTF-8") }.getOrNull()?.takeIf { it.startsWith("http") } ?: return null
            return if (LinkProbe.alive(file)) HubFile(file, null, null) else null
        }
        val u = resolveRedirect(url)
        if (u.contains("hubcloud")) return hubCloud(u)
        // hblinks / hubdrive pages: a HubCloud link, else a HubDrive file (→ HubCloud), else a hubcdn file
        val html = VmHttp.get(u, h, 10_000) ?: return null
        Regex("""https?://[^"'\s]*hubcloud[^"'\s]*/drive/[^"'\s<]+""").find(html)?.value?.let { return hubCloud(it) }
        if (!u.contains("hubdrive")) Regex("""https?://[^"'\s]*hubdrive[^"'\s]*/file/[^"'\s<]+""").find(html)?.value?.let { d ->
            VmHttp.get(d, h, 10_000)?.let { Regex("""https?://[^"'\s]*hubcloud[^"'\s]*/drive/[^"'\s<]+""").find(it)?.value }?.let { c -> hubCloud(c)?.let { return it } }
        }
        Regex("""https?://[^"'\s]*hubcdn[^"'\s]*/file/[^"'\s<]+""").find(html)?.value?.let { return hubLink(it, h) }
        return null
    }

    // ── Shared: redirects + HubCloud ─────────────────────────────────────────

    private fun b64(s: String) = String(Base64.decode(s.trim() + "=".repeat((4 - s.trim().length % 4) % 4), Base64.DEFAULT), Charsets.ISO_8859_1)
    private fun rot13(s: String) = s.map { c -> when (c) { in 'a'..'z' -> 'a' + (c - 'a' + 13) % 26; in 'A'..'Z' -> 'A' + (c - 'A' + 13) % 26; else -> c } }.joinToString("")

    /** "?id=…" gateway pages hide the real link as base64 → base64 → rot13 → base64 JSON {"o": base64(url)}. */
    private suspend fun resolveRedirect(url: String): String {
        if (url.contains("hubcloud.") || url.contains("hubdrive.")) return url
        val html = VmHttp.get(url, headers, 10_000) ?: return url
        val enc = Regex("""'o','(.*?)'""").find(html)?.groupValues?.get(1) ?: return url
        return runCatching { b64(JSONObject(b64(rot13(b64(b64(enc))))).getString("o")) }.getOrDefault(url)
    }

    data class HubFile(val url: String, val bytes: Long?, val name: String?)

    /**
     * HubCloud drive page → its links page → the first direct file that actually answers: R2 "FSL", then PixelDrain,
     * then 10Gbps. Listed links are often dead (PixelDrain decoys shared by every quality, 10Gbps workers answering
     * 500), so each is probed; a page with no live link is "no file", and the next quality or source is used.
     */
    suspend fun hubCloud(url: String): HubFile? {
        if (!url.contains("hubcloud")) return null
        val first = VmHttp.get(url, headers + ("Referer" to url), 10_000) ?: return null
        val next = Regex("""var url ?= ?'(.*?)'""").find(first)?.groupValues?.get(1) ?: return null
        val html = VmHttp.get(next, headers + ("Referer" to url), 10_000) ?: return null
        val doc = Jsoup.parse(html, next)
        val bytes = sizeOf(doc.select("#size").text())
        val name = doc.title().trim().ifBlank { null }
        val links = doc.select("a[href]").map { it.text().trim() to it.absUrl("href") }
        val fsl = links.filter { it.first.contains("FSL") || it.second.contains("r2.cloudflarestorage.com") || it.second.contains("r2.dev") }.map { it.second }
        val pixel = links.filter { it.second.contains("pixeldrain") }
            .mapNotNull { (_, u) -> Regex("""/u/([A-Za-z0-9]+)""").find(u)?.groupValues?.get(1)?.let { "https://pixeldrain.dev/api/file/$it" } }
        val quick = (fsl + pixel).distinct().take(4)
        // Probed together; the first live one in preference order wins
        val live = coroutineScope { quick.map { u -> async { u.takeIf { LinkProbe.alive(it) } } }.awaitAll() }.firstOrNull { it != null }
        if (live != null) return HubFile(live, bytes, name)
        // 10Gbps: a redirect chain ending at ".../dl.php?link=<direct Google file>" (only walked when the rest are dead)
        val fast = links.firstOrNull { it.first.contains("10Gbps", true) }?.second?.let { f ->
            val final = redirectTarget(f, next) ?: return@let null
            final.substringAfter("link=", "").takeIf { it.startsWith("http") }?.let { java.net.URLDecoder.decode(it, "UTF-8") } ?: final.takeIf { !it.contains("hubcloud") }
        }?.takeIf { LinkProbe.alive(it) } ?: return null
        return HubFile(fast, bytes, name)
    }

    /** Walks redirects (bodies never read) until one carries "link=" or the chain ends; the last URL. */
    private suspend fun redirectTarget(start: String, referer: String): String? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val client = com.anees.novamobile.data.api.RetrofitClient.base.newBuilder().followRedirects(false).followSslRedirects(false)
            .callTimeout(8, java.util.concurrent.TimeUnit.SECONDS).build()
        var url = start
        repeat(6) {
            if (url.contains("link=")) return@withContext url
            val loc = runCatching {
                client.newCall(okhttp3.Request.Builder().url(url).header("User-Agent", UA).header("Referer", referer).build()).execute()
                    .use { r -> if (r.isRedirect) r.header("Location") else null }
            }.getOrNull() ?: return@withContext url
            url = runCatching { java.net.URI(url).resolve(loc).toString() }.getOrNull() ?: return@withContext url
        }
        url
    }

    private fun sizeOf(text: String?): Long? = text?.let { Regex("""([\d.]+)\s?(GB|MB)""", RegexOption.IGNORE_CASE).find(it) }?.let { m ->
        val v = m.groupValues[1].toDoubleOrNull() ?: return null
        (v * if (m.groupValues[2].equals("GB", true)) 1024.0 * 1024 * 1024 else 1024.0 * 1024).toLong()
    }
}
