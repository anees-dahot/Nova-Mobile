package com.anees.novamobile.data.sources.vegamovies

import com.anees.novamobile.data.local.LocalStore
import com.anees.novamobile.ui.player.StreamExtractor
import com.anees.novamobile.ui.player.WebStreamSniffer
import android.util.Log
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.Jsoup

/*
 * Ported from Indflix (https://github.com/dipender98/Indflix), GPL-3.0.
 * Original: Vegamovies/src/main/kotlin/com/vegamovies/VegamoviesPlugin.kt (+ LinkNaming)
 *
 * Adapted for Nova: CloudStream's search/load/loadLinks UI pipeline (SearchResponse,
 * LoadResponse, ExtractorLink, main page, posters, subtitles) is gone — Nova browses TMDB
 * and only needs one thing: "does this site have <title>, and what is a direct stream URL
 * for S/E n?". So the chain is: JSON index search → best page → parse download groups →
 * expand gateways → resolve V-Cloud / G-Drive to a direct file.
 *
 * Everything resolves over plain HTTP (cancellable); no WebView is involved.
 */
object VegamoviesSource {

    private const val TAG = "NovaTV"
    private const val SEARCH_MAX_RESULTS = 10
    private const val MIN_MATCH = 0.45
    /** Cap on stream-resolution attempts per search: the provider timeout bounds the total. */
    private const val MAX_ATTEMPTS = 8

    private val GENXFM_REGEX = Regex(
        """https?://[a-z0-9.\-]*nexdrive\.[a-z]{2,10}/genxfm[^\s"'<>\\]+""",
        RegexOption.IGNORE_CASE,
    )

    private val DOWNLOAD_HEADING = Regex(
        """(?i)(\d{3,4}p\b|4K|WEB[\s-]?DL|WEBRip|Blu\s?Ray|BD-?Rip|HD-?Rip|DVDRip|x26[45]|HEVC|AVC\b|\d+(?:\.\d+)?\s?(?:GB|MB)(?:/|\b)|/ZiP|/ZIP|\bZIP\b|Batch|Season\s*\d|\bPack\b|Complete|DUAL\s*[- ]?AUDIO|HINDI|TAMIL|TELUGU|\bEP(?:\.|ISODE)?\s*\d|S\d{1,2}[\s._-]?E\d{1,3})""",
    )

    private data class Hit(val url: String, val title: String, val isSeries: Boolean, val year: Int?)
    private data class DlLink(val gatewayUrl: String, val chip: String, val heading: String)
    private data class Scraped(val imdbId: String?, val groups: List<Group>)
    private data class Group(
        val heading: String,
        val season: Int?,
        val episode: Int?,
        val links: List<DlLink>,
        val pack: Boolean,
    )

    /** One concrete file candidate: server kind, URL, and the heading it was listed under (quality). */
    private data class Cand(val kind: String, val url: String, val heading: String, val idx: Int = 0)

    // ── Entry point ───────────────────────────────────────────────────────────

    /** Resolve one movie or episode to a direct stream URL. Null = "this source doesn't have it". */
    suspend fun resolve(t: StreamExtractor.Target): WebStreamSniffer.Sniffed? {
        val query = t.title?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val cap = LocalStore.settings().maxQuality
        val hit = searchBest(query, t.year, wantSeries = !t.isMovie) ?: return null
        val doc = VmHttp.fetchDoc(hit.url) ?: run {
            VmHttp.refreshDomains()
            VmHttp.fetchDoc(rebase(hit.url)) ?: return null
        }
        val scraped = parseDetail(doc)
        if (scraped.groups.isEmpty()) return null
        val cands = order(
            if (t.isMovie) movieCandidates(scraped, hit.url)
            else episodeCandidates(scraped, hit.url, t.season, t.episode),
            cap,
        )
        if (cands.isEmpty()) return null
        Log.i(TAG, "vegamovies: \"${hit.title}\" → ${cands.size} candidate(s)")
        return emit(cands, hit.url)
    }

    /** Re-point an old page URL at the current mirror (domains rotate). */
    private fun rebase(url: String): String {
        val path = Regex("""^https?://[^/]+(/.*)?$""").find(url)?.groupValues?.get(1) ?: return url
        return VmHttp.mainUrl.trimEnd('/') + path
    }

    // ── Search ────────────────────────────────────────────────────────────────

    private suspend fun searchBest(query: String, wantYear: Int?, wantSeries: Boolean): Hit? {
        if (VmHttp.needsHeal()) VmHttp.refreshDomains()
        var hits = collectHits(query)
        if (hits.isEmpty()) {                     // domains rotted mid-session: heal and retry once
            VmHttp.refreshDomains()
            hits = collectHits(query)
        }
        if (hits.isEmpty()) return null
        return hits.asSequence()
            .filter { it.isSeries == wantSeries }
            .map { it to matchScore(query, it.title, it.year, wantYear) }
            .filter { it.second >= MIN_MATCH }
            .sortedWith(compareByDescending<Pair<Hit, Double>> { it.second }
                .thenBy { yearDiffSortKey(wantYear, it.first.year) })
            .map { it.first }
            .take(SEARCH_MAX_RESULTS)
            .firstOrNull()
    }

    private suspend fun collectHits(query: String): List<Hit> = coroutineScope {
        val queries = (listOf(query) + queryVariants(query, max = 1)).distinct()
        listOf("vegamovies", "rogmovies").map { family ->
            async { familyHits(family, queries) }
        }.awaitAll().flatten().distinctBy { it.url }.take(SEARCH_MAX_RESULTS)
    }

    private suspend fun familyHits(family: String, queries: List<String>): List<Hit> {
        val out = LinkedHashMap<String, Hit>()
        for (base in VmHttp.candidateBases(family)) {
            for (q in queries) {
                val json = VmHttp.searchIndexJson(base, q) ?: continue
                parseSearchHits(json, base).forEach { out.putIfAbsent(it.url, it) }
            }
            if (out.isNotEmpty()) return out.values.toList()
        }
        return out.values.toList()
    }

    private fun parseSearchHits(json: String, baseUrl: String): List<Hit> {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return emptyList()
        val hits = root.optJSONArray("hits") ?: return emptyList()
        return (0 until hits.length()).mapNotNull { i ->
            val doc = hits.optJSONObject(i)?.optJSONObject("document") ?: return@mapNotNull null
            val permalink = doc.optString("permalink").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val rawTitle = doc.optString("post_title").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val title = cleanSearchTitle(rawTitle)
            if (title.isBlank()) return@mapNotNull null
            val absolute = if (permalink.startsWith("http")) permalink else baseUrl.trimEnd('/') + permalink
            val categories = doc.optJSONArray("category")?.let { c ->
                (0 until c.length()).mapNotNull { ci -> c.optString(ci).takeIf { it.isNotBlank() } }
            } ?: emptyList()
            val year = Regex("""\((\d{4})""").find(rawTitle)?.groupValues?.get(1)?.toIntOrNull()
            Hit(absolute, title, isSeries(permalink, categories, rawTitle), year)
        }
    }

    internal fun cleanSearchTitle(raw: String): String {
        var t = raw.trim().removePrefix("Download").trim()
        val until = Regex("""(.*?\(\d{4}[^)]*\))""").find(t)
        if (until != null) return until.groupValues[1].trim().trimEnd(' ', '-', '|', ':', ',')
        t = t.replace(
            Regex("""(?i)\)\s*(\d{3,4}p[\s\S]*|WEB[\s-]?DL.*|BluRay.*|HDTS.*|HDTV.*|4K.*|Dual Audio.*|Hindi.*|S\d{2}E\d{2,}.*)$"""),
            "",
        ).trim()
        return t.trim(' ', '-', '|', ':', ',').ifBlank { raw.trim().take(80) }
    }

    internal fun isSeries(permalink: String, categories: List<String>, rawTitle: String): Boolean {
        val hay = (permalink + " " + categories.joinToString(" ") + " " + rawTitle).lowercase()
        return Regex(
            """season|series|-series|drama|anime|s\d{1,2}e\d{1,3}|web-?show|tv-show|\beps\b|multimovies\.|complete""",
        ).containsMatchIn(hay) && !Regex("""full movie|-movie\b""").containsMatchIn(hay)
    }

    // ── Detail page ───────────────────────────────────────────────────────────

    /** Walks headings + gateway anchors, bucketing links under the quality/season heading they follow. */
    private fun parseDetail(doc: Document): Scraped {
        val imdbId = Regex("""imdb\.com/title/(tt\d+)""", RegexOption.IGNORE_CASE)
            .find(doc.body().html())?.groupValues?.get(1)
            ?: Regex("""\[imdb[^]]*](tt\d+)\[/imdb]""", RegexOption.IGNORE_CASE)
                .find(doc.body().html())?.groupValues?.get(1)
        val groups = ArrayList<Group>()
        var heading = ""
        var anchors = ArrayList<DlLink>()

        fun closeGroup() {
            if (anchors.isNotEmpty() && heading.isNotBlank()) {
                val (season, episode) = LinkNaming.seasonEpisodeFrom(heading)
                val pack = LinkNaming.isPack(heading) && episode == null
                groups += Group(heading, season, episode, anchors.toList(), pack)
            }
            anchors = ArrayList()
        }

        val root = doc.selectFirst("div.entry-content, article, main") ?: doc.body()
        for (el in root.getAllElements()) {
            when {
                el.tagName().matches(Regex("h[1-6]")) -> {
                    val t = el.text().trim()
                    if (t.isNotBlank() && t.length < 220) {
                        if (anchors.isNotEmpty()) closeGroup()
                        heading = if (DOWNLOAD_HEADING.containsMatchIn(t)) t else ""
                    }
                }
                el.tagName() == "a" -> {
                    val href = el.attr("abs:href")
                    if (GENXFM_REGEX.containsMatchIn(href)) {
                        anchors += DlLink(href.trimEnd('/') + "/", LinkNaming.kindFromChip(el.text()), heading)
                    }
                }
            }
        }
        closeGroup()
        val merged = groups.groupBy { it.heading to (it.season ?: 1) to it.episode }
            .map { (_, gs) -> gs.first().copy(links = gs.flatMap { it.links }.distinctBy { it.gatewayUrl }) }
            .filter { it.links.isNotEmpty() }
        return Scraped(imdbId, merged)
    }

    // ── Candidate selection ───────────────────────────────────────────────────

    /** A movie: every quality heading is a candidate; ordering picks the best that fits the cap. */
    private suspend fun movieCandidates(scraped: Scraped, referer: String): List<Cand> =
        expandGroups(scraped.groups, referer)

    /**
     * An episode: explicit "S01E05"-style headings match directly; season-wide groups fall back
     * to positional mapping (the Nth link of each server family = episode N), as in the plugin.
     */
    private suspend fun episodeCandidates(scraped: Scraped, referer: String, season: Int, episode: Int): List<Cand> {
        val sg = scraped.groups.filter { (it.season ?: 1) == season }
        if (sg.isEmpty()) return emptyList()
        val hasExplicit = sg.any { it.episode != null }
        val chosen = if (hasExplicit) sg.filter { it.episode == episode } else sg.filter { !it.pack }
        if (chosen.isEmpty()) return emptyList()
        val expanded = expandGroups(chosen, referer)
        if (hasExplicit) return expanded.filter { it.kind != Servers.ZIP }

        val pool = expanded.filter { it.kind != Servers.ZIP }
        val maxEps = pool.groupBy { it.kind }
            .maxOfOrNull { (_, same) -> same.maxOf { it.idx } + 1 } ?: 0
        return when {
            maxEps > 1 && episode in 1..maxEps -> pool.filter { it.idx == episode - 1 }
            maxEps <= 1 -> pool          // one file for the whole season: the only thing we can offer
            else -> emptyList()
        }
    }

    /** Expand the gateway links of each group concurrently; groups with nothing usable are dropped. */
    private suspend fun expandGroups(groups: List<Group>, referer: String): List<Cand> = coroutineScope {
        val sem = Semaphore(4)
        groups.map { g ->
            async { VmHttp.withSem(sem) { expandGroup(g, referer) } }
        }.awaitAll().flatten().filter { it.kind != Servers.ZIP }
    }

    /** Gateway page → concrete V-Cloud / G-Drive files, keeping each link's index for positional
     *  episode mapping. Gateways that don't expand are dropped: their targets are download-only
     *  pages a plain HTTP client can't turn into a stream. */
    private suspend fun expandGroup(g: Group, referer: String): List<Cand> =
        g.links.flatMap { dl ->
            val chipKind = LinkNaming.kindFromChip(dl.chip)
            val exp = NexdriveResolver.expand(dl.gatewayUrl, referer, VmHttp.commonHeaders)
            exp.links.map { c ->
                Cand(
                    kind = if (chipKind == Servers.ZIP) Servers.ZIP else c.kind,
                    url = c.url,
                    heading = g.heading,
                    idx = c.idx,
                )
            }.distinctBy { it.url }
        }

    // ── Ordering / emission ───────────────────────────────────────────────────

    /** V-Cloud first (streamable r2 files), then G-Drive; within a wave prefer the best quality
     *  that fits the Settings cap, then unknown, then sub-720p, and over-cap 4K last. */
    private fun order(cands: List<Cand>, cap: Int): List<Cand> {
        fun wave(kind: String) = when (kind) {
            Servers.VCLOUD -> 0
            Servers.GDRIVE -> 1
            else -> 2
        }
        fun rank(q: Int) = when {
            q == 0 -> 2000
            q in 720..cap -> 4000 + q
            q < 720 -> 1000 + q
            else -> 500 + q / 100
        }
        return cands.distinctBy { it.kind to it.url }
            .sortedWith(compareBy<Cand> { wave(it.kind) }
                .thenByDescending { rank(LinkNaming.qualityInt(it.heading)) })
    }

    private fun looksPlayable(url: String): Boolean {
        val u = url.lowercase()
        return u.contains(".m3u8") || u.contains(".mp4") ||
            u.contains("video-downloads.googleusercontent.com") ||
            u.contains("r2.cloudflarestorage.com") || u.contains("r2.dev") ||
            u.contains("googlevideo.com")
    }

    /** Walk candidates in wave order; first one that resolves to a real file wins. */
    private suspend fun emit(cands: List<Cand>, pageUrl: String): WebStreamSniffer.Sniffed? {
        val origin = VmHttp.originOf(pageUrl) ?: VmHttp.mainUrl
        val playbackHeaders = VmHttp.commonHeaders + mapOf("Referer" to pageUrl, "Origin" to origin)
        for (c in cands.take(MAX_ATTEMPTS)) {
            val url = when {
                c.kind == Servers.VCLOUD || c.url.contains("vcloud") ->
                    VcloudResolver.resolve(c.url, pageUrl, VmHttp.commonHeaders).firstOrNull()?.url
                c.url.contains("fastdl") ->
                    NexdriveResolver.resolveEmbed(c.url, pageUrl, VmHttp.commonHeaders)
                looksPlayable(c.url) -> c.url
                else -> null
            } ?: continue
            if (!looksPlayable(url)) continue
            return WebStreamSniffer.Sniffed(url, playbackHeaders, VmHttp.UA, emptyList())
        }
        return null
    }
}
