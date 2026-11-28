package com.anees.novamobile.ui.player

import android.util.Log
import com.anees.novamobile.data.api.RetrofitClient
import com.anees.novamobile.data.repository.MediaRepository
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request
import org.json.JSONArray

/**
 * Subtitles for streams that bring none (most HTTP resolvers, many embeds): OpenSubtitles'
 * key-less REST search by IMDb id. Links are .gz; [SubtitleCleaner] unpacks them.
 */
object OpenSubs {
    private const val TAG = "NovaTV"
    private const val API = "https://rest.opensubtitles.org/search"
    private val ISO3 = mapOf("en" to "eng", "hi" to "hin", "ar" to "ara", "fr" to "fre", "es" to "spa", "de" to "ger",
        "ur" to "urd", "ta" to "tam", "te" to "tel", "ja" to "jpn", "ko" to "kor", "pt" to "por", "it" to "ita")

    /** A found subtitle: its .gz link and two-letter language. */
    data class Sub(val url: String, val lang: String)

    private val jobs = HashMap<String, Deferred<List<Sub>>>()

    private fun key(t: StreamExtractor.Target) = "${t.tmdbId}:${t.isMovie}:${t.season}:${t.episode}"

    /** Start (or join) the search for [t]; safe to call early, e.g. while sources are still racing. */
    @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
    fun prefetch(t: StreamExtractor.Target, repo: MediaRepository, langs: List<String>): Deferred<List<Sub>> = synchronized(jobs) {
        val k = key(t)
        jobs.getOrPut(k) {
            GlobalScope.async(Dispatchers.IO) {
                runCatching { search(t, repo, langs) }.getOrElse {
                    // A failed search (network, rate limit) is forgotten, so the next play asks again;
                    // only a real answer, even an empty one, is kept
                    Log.w(TAG, "opensubtitles failed: ${it.message}")
                    synchronized(jobs) { jobs.remove(k) }
                    emptyList()
                }
            }
        }
    }

    /** Whatever is found already, without waiting. */
    fun ready(t: StreamExtractor.Target): List<Sub> = synchronized(jobs) { jobs[key(t)] }
        ?.takeIf { it.isCompleted }?.let { runCatching { it.getCompleted() }.getOrNull() } ?: emptyList()

    suspend fun await(t: StreamExtractor.Target, repo: MediaRepository, langs: List<String>, timeoutMs: Long): List<Sub> =
        withTimeoutOrNull(timeoutMs) { prefetch(t, repo, langs).await() } ?: ready(t)

    /** One subtitle file on OpenSubtitles, with what it was made for. */
    data class Version(val url: String, val lang: String, val release: String, val downloads: Long, val hearingImpaired: Boolean)

    private val versionCache = HashMap<String, List<Version>>()

    /**
     * Every version for [t] in [langs], most downloaded first (hearing-impaired after plain). Different
     * releases are timed differently; [com.anees.novamobile.download.SubAligner] picks the one that fits.
     */
    suspend fun versions(t: StreamExtractor.Target, repo: MediaRepository, langs: List<String>): List<Version> {
        val k = key(t) + langs.joinToString(",")
        synchronized(versionCache) { versionCache[k] }?.let { return it }
        val arr = query(t, repo, langs) ?: return emptyList()
        val list = (0 until arr.length()).map { arr.getJSONObject(it) }
            .filter { it.optString("SubFormat") == "srt" && it.optString("SubDownloadLink").isNotBlank() }
            .map { Version(it.optString("SubDownloadLink"), it.optString("ISO639"),
                it.optString("MovieReleaseName").ifBlank { it.optString("SubFileName") }.trim(),
                it.optString("SubDownloadsCnt").toLongOrNull() ?: 0, it.optString("SubHearingImpaired") == "1") }
            .distinctBy { it.url }
            .sortedWith(compareBy<Version> { langs.indexOf(it.lang).let { i -> if (i < 0) 99 else i } }
                .thenBy { it.hearingImpaired }.thenByDescending { it.downloads })
        synchronized(versionCache) { versionCache[k] = list }
        return list
    }

    private suspend fun query(t: StreamExtractor.Target, repo: MediaRepository, langs: List<String>): JSONArray? {
        val imdb = imdbOf(t, repo)?.removePrefix("tt")?.trimStart('0') ?: return null
        val codes = langs.distinct().mapNotNull { ISO3[it] }.ifEmpty { listOf("eng") }
        val path = buildString {
            if (!t.isMovie) append("/episode-${t.episode}")
            append("/imdbid-$imdb")
            if (!t.isMovie) append("/season-${t.season}")
            append("/sublanguageid-${codes.joinToString(",")}")
        }
        val body = withContext(Dispatchers.IO) {
            val req = Request.Builder().url(API + path).header("X-User-Agent", "TemporaryUserAgent").build()
            RetrofitClient.base.newCall(req).execute().use {
                if (!it.isSuccessful) throw java.io.IOException("HTTP ${it.code}")
                it.body?.string()
            }
        } ?: return null
        return JSONArray(body)
    }

    private suspend fun imdbOf(t: StreamExtractor.Target, repo: MediaRepository): String? =
        t.imdbId ?: if (t.isMovie) repo.getMovieDetail(t.tmdbId)?.imdbId
        else repo.getSeriesDetail(t.tmdbId)?.second?.externalIds?.imdbId

    private suspend fun search(t: StreamExtractor.Target, repo: MediaRepository, langs: List<String>): List<Sub> {
        val arr = query(t, repo, langs) ?: return emptyList()
        val all = (0 until arr.length()).map { arr.getJSONObject(it) }
            .filter { it.optString("SubFormat") == "srt" && it.optString("SubDownloadLink").isNotBlank() }
        // Per language: the most downloaded file, hearing-impaired versions last
        val subs = all.groupBy { it.optString("ISO639") }.mapNotNull { (lang, l) ->
            l.maxByOrNull { (if (it.optString("SubHearingImpaired") == "1") 0 else 1_000_000_000L) + it.optString("SubDownloadsCnt").toLongOrNull().let { n -> n ?: 0 } }
                ?.let { Sub(it.optString("SubDownloadLink"), lang) }
        }.sortedBy { langs.indexOf(it.lang).let { i -> if (i < 0) 99 else i } }
        Log.i(TAG, "opensubtitles: ${subs.joinToString { it.lang }} for ${t.title}")
        return subs
    }
}
