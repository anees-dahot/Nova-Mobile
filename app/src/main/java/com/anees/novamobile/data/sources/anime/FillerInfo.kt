package com.anees.novamobile.data.sources.anime

import android.content.Context
import android.util.Log
import com.anees.novamobile.data.api.RetrofitClient
import com.anees.novamobile.data.model.MediaCard
import com.anees.novamobile.data.model.MediaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Which anime episodes are filler, from animefillerlist.com (canon / mixed / filler per episode, plus
 * real episode titles), with Jikan (MyAnimeList `filler`/`recap` flags) as a backup. Kept on disk for
 * 3 days, so episode lists, the player and the download sheet read it instantly.
 */
object FillerInfo {
    private const val TAG = "NovaFiller"
    private const val AFL = "https://www.animefillerlist.com"
    private const val TTL = 3L * 24 * 3600_000
    private const val UA = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0 Mobile Safari/537.36"

    enum class Kind(val label: String) { CANON("Canon"), ANIME_CANON("Anime canon"), MIXED("Mixed"), FILLER("Filler"), RECAP("Recap");
        /** Worth skipping if you only want the story. */
        val skippable get() = this == FILLER || this == RECAP
    }
    data class Ep(val kind: Kind, val title: String?)

    /**
     * One show's list. Anime entries number episodes 1..N; TMDB series restart each season, so
     * [offsets] (episodes before each season) turn S3 E5 into the absolute number the list uses.
     */
    class Show(val eps: Map<Int, Ep>, private val offsets: Map<Int, Int>, private val counts: Map<Int, Int>) {
        fun absolute(season: Int, ep: Int): Int {
            val off = offsets[season] ?: return ep
            // Some TMDB seasons already use absolute numbers (S2 starts at E33): leave those alone
            return if (season > 1 && ep > (counts[season] ?: Int.MAX_VALUE)) ep else off + ep
        }
        fun at(season: Int, ep: Int): Ep? = eps[absolute(season, ep)]
        val skippableCount get() = eps.count { it.value.kind.skippable }
    }

    /** Anime from AniList, or a Japanese TMDB series (how most anime show up in search). */
    fun applies(card: MediaCard) = card.type == MediaType.ANIME || (card.type == MediaType.SERIES && card.lang == "ja")

    private lateinit var prefs: android.content.SharedPreferences
    private val mem = HashMap<String, Show>()
    private val lock = Mutex()
    private val http by lazy { RetrofitClient.base.newBuilder().callTimeout(15, TimeUnit.SECONDS).build() }

    fun init(ctx: Context) { if (!::prefs.isInitialized) prefs = ctx.applicationContext.getSharedPreferences("filler", Context.MODE_PRIVATE) }

    private fun key(card: MediaCard) = "${card.type}:${card.id}"

    /** Already-known data without any network (null = not loaded yet or not an anime). */
    fun cached(card: MediaCard): Show? {
        if (!applies(card) || !::prefs.isInitialized) return null
        mem[key(card)]?.let { return it }
        val raw = prefs.getString(key(card), null) ?: return null
        return runCatching { show(decode(JSONObject(raw)), prefs.getString("${key(card)}#s", null)) }.getOrNull()?.also { mem[key(card)] = it }
    }

    /** Season episode counts, stored as "1:32,2:21,…". */
    private fun show(eps: Map<Int, Ep>, seasons: String?): Show {
        val counts = seasons.orEmpty().split(',').mapNotNull { p -> p.split(':').takeIf { it.size == 2 }?.let { (a, b) -> a.toIntOrNull()?.let { x -> b.toIntOrNull()?.let { x to it } } } }.toMap()
        var run = 0
        val offsets = counts.keys.filter { it > 0 }.sorted().associateWith { s -> run.also { run += counts[s] ?: 0 } }
        return Show(eps, offsets, counts)
    }

    private suspend fun seasonCounts(card: MediaCard): String? {
        if (card.type != MediaType.SERIES) return null
        val d = com.anees.novamobile.data.repository.MediaRepository().getSeriesDetail(card.id)?.second ?: return null
        return d.seasons.orEmpty().filter { it.seasonNumber > 0 }.joinToString(",") { "${it.seasonNumber}:${it.episodeCount}" }
    }

    /** Episode number → kind/title for an anime; empty when no list has it. */
    suspend fun forShow(card: MediaCard, malId: Int? = null): Show {
        val none = Show(emptyMap(), emptyMap(), emptyMap())
        if (!applies(card) || !::prefs.isInitialized) return none
        val k = key(card)
        val fresh = prefs.getLong("$k#t", 0) > System.currentTimeMillis() - TTL
        if (fresh) cached(card)?.let { return it }
        return lock.withLock {
            if (prefs.getLong("$k#t", 0) > System.currentTimeMillis() - TTL) cached(card)?.let { return@withLock it }
            val seasons = runCatching { seasonCounts(card) }.getOrNull()
            val got = withContext(Dispatchers.IO) {
                runCatching { fromAfl(card) }.onFailure { Log.w(TAG, "afl: ${it.message}") }.getOrNull()?.takeIf { it.isNotEmpty() }
                    ?: malId?.let { id -> runCatching { fromJikan(id) }.onFailure { Log.w(TAG, "jikan: ${it.message}") }.getOrNull() }
                    ?: emptyMap()
            }
            // Keep old data if the network failed this time
            if (got.isEmpty()) return@withLock cached(card) ?: none
            val sh = show(got, seasons)
            mem[k] = sh
            prefs.edit().putString(k, encode(got).toString()).putString("$k#s", seasons).putLong("$k#t", System.currentTimeMillis()).apply()
            Log.i(TAG, "${card.title}: ${got.size} eps, ${sh.skippableCount} filler/recap")
            sh
        }
    }

    // ── animefillerlist.com ───────────────────────────────────────────────────

    private fun get(url: String): String? = http.newCall(Request.Builder().url(url).header("User-Agent", UA).build()).execute().use { r ->
        if (r.isSuccessful) r.body?.string() else null
    }

    /** "Naruto Shippūden" → "narutoshippuden": accents dropped, then letters/digits only. */
    private fun norm(s: String) = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "").lowercase().replace(Regex("[^a-z0-9]"), "")

    /** Show list (name → slug), cached a week: names like "Attack on Titan (Shingeki no Kyojin)" match either title. */
    private fun shows(): Map<String, String> {
        val cachedAt = prefs.getLong("#shows#t", 0)
        val raw = prefs.getString("#shows", null)?.takeIf { cachedAt > System.currentTimeMillis() - 7 * 24 * 3600_000L }
            ?: get("$AFL/shows")?.also { prefs.edit().putString("#shows", it).putLong("#shows#t", System.currentTimeMillis()).apply() }
            ?: prefs.getString("#shows", null) ?: return emptyMap()
        val out = LinkedHashMap<String, String>()
        Regex("""<a href="/shows/([^"]+)">([^<]+)</a>""").findAll(raw).forEach { m ->
            val slug = m.groupValues[1]; val name = android.text.Html.fromHtml(m.groupValues[2], 0).toString()
            val main = name.substringBefore(" (").trim()
            val alias = name.substringAfter(" (", "").substringBeforeLast(")").trim()
            listOf(name, main, alias).filter { it.isNotBlank() }.forEach { n -> out.putIfAbsent(norm(n), slug) }
        }
        return out
    }

    private val STOP = setOf("a", "an", "as", "at", "but", "by", "for", "from", "is", "in", "into", "of", "off", "on", "onto", "the", "this", "that", "to", "with")

    private fun fromAfl(card: MediaCard): Map<Int, Ep> {
        val t = norm(card.title)
        val list = shows()
        val slug = list[t]
            ?: java.text.Normalizer.normalize(card.title, java.text.Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").lowercase().replace(Regex("[^a-z0-9 ]"), " ").split(' ').filter { it.isNotBlank() && it !in STOP }.joinToString("-")
        val html = get("$AFL/shows/$slug") ?: return emptyMap()
        val row = Regex("""<tr class="([a-z_/]+) (?:odd|even)" id="eps-(\d+)">.*?<td class="Title">(?:<a [^>]*>)?([^<]*)""")
        return row.findAll(html).mapNotNull { m ->
            val kind = when (m.groupValues[1]) {
                "manga_canon" -> Kind.CANON
                "anime_canon" -> Kind.ANIME_CANON
                "mixed_canon/filler" -> Kind.MIXED
                "filler" -> Kind.FILLER
                else -> return@mapNotNull null
            }
            val n = m.groupValues[2].toIntOrNull() ?: return@mapNotNull null
            n to Ep(kind, android.text.Html.fromHtml(m.groupValues[3], 0).toString().trim().ifBlank { null })
        }.toMap()
    }

    // ── Jikan (MyAnimeList) backup ────────────────────────────────────────────

    private suspend fun fromJikan(malId: Int): Map<Int, Ep> {
        val out = HashMap<Int, Ep>()
        for (page in 1..25) {
            val body = withTimeoutOrNull(12_000) { get("https://api.jikan.moe/v4/anime/$malId/episodes?page=$page") } ?: break
            val o = JSONObject(body)
            val arr = o.optJSONArray("data") ?: break
            for (i in 0 until arr.length()) {
                val e = arr.getJSONObject(i)
                val kind = when { e.optBoolean("recap") -> Kind.RECAP; e.optBoolean("filler") -> Kind.FILLER; else -> Kind.CANON }
                out[e.optInt("mal_id")] = Ep(kind, e.optString("title").takeIf { it.isNotBlank() && it != "null" })
            }
            if (o.optJSONObject("pagination")?.optBoolean("has_next_page") != true) break
            kotlinx.coroutines.delay(400)   // Jikan allows ~3 requests a second
        }
        return out
    }

    // ── Disk format ───────────────────────────────────────────────────────────

    private fun encode(m: Map<Int, Ep>) = JSONObject().apply {
        m.forEach { (n, e) -> put(n.toString(), JSONObject().put("k", e.kind.name).put("t", e.title ?: "")) }
    }

    private fun decode(o: JSONObject): Map<Int, Ep> = o.keys().asSequence().mapNotNull { k ->
        val e = o.getJSONObject(k)
        val kind = runCatching { Kind.valueOf(e.getString("k")) }.getOrNull() ?: return@mapNotNull null
        k.toIntOrNull()?.let { it to Ep(kind, e.optString("t").ifBlank { null }) }
    }.toMap()
}
