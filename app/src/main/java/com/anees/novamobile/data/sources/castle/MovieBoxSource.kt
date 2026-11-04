package com.anees.novamobile.data.sources.castle

import android.util.Log
import com.anees.novamobile.data.api.RetrofitClient
import com.anees.novamobile.data.model.LangTags
import com.anees.novamobile.ui.player.StreamExtractor
import com.anees.novamobile.ui.player.WebStreamSniffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * MovieBox (aoneroom H5 API, ported from Indflix/IndStream, GPL-3.0). Every dub is its own
 * catalogue entry ("Frieren [Hindi]", "Vinland Saga [Hindi] S1-S2"), so the original and each
 * dub become one stream each: the original (or first match) is the primary, dubs ride along as
 * language variants. Free streams are MP4, mostly 480p (Nov 2026), so the quality gate keeps
 * this as a fallback behind 720p+ sources.
 */
object MovieBoxSource {
    private const val TAG = "NovaSrc"
    private const val HOST = "h5-api.aoneroom.com"
    private const val BASE = "https://$HOST"
    private const val UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36"
    private const val PLAY_REFERER = "https://movieboxonline.net/"
    private const val TOKEN_TTL_MS = 6 * 60 * 60_000L
    private val JSON = "application/json".toMediaType()
    private val BRACKET = Regex("""[\[(]([^\])]+)[\])]""")
    private val SEASONS = Regex("""\s+S(\d+)(?:\s*-\s*S?(\d+))?$""", RegexOption.IGNORE_CASE)
    private val NON_ALNUM = Regex("[^a-z0-9]")

    private var token: String? = null
    private var tokenAt = 0L
    private val tokenLock = Mutex()

    private data class Subject(val id: String, val detailPath: String, val lang: String?, val seasonEnd: Int)

    suspend fun resolve(t: StreamExtractor.Target): WebStreamSniffer.Sniffed? {
        val title = t.title?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val bearer = bearer(false) ?: return null
        val items = search(bearer, title, if (t.isMovie) 1 else 2)
            ?: bearer(true)?.let { search(it, title, if (t.isMovie) 1 else 2) } ?: return null
        val want = norm(title)
        val subjects = (0 until items.length()).mapNotNull { items.optJSONObject(it) }.mapNotNull { o ->
            val raw = o.optString("title")
            val id = o.optString("subjectId").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val clean = raw.replace(SEASONS, "").replace(Regex("""\s*[\[(][^\])]*[\])]"""), "").replace(Regex("""\s*\d{4}"""), "").trim()
            val n = norm(clean)
            if (n != want && !(want.length >= 4 && n.startsWith(want) && n.length - want.length <= 3)) return@mapNotNull null
            val tag = BRACKET.findAll(raw).map { it.groupValues[1] }.firstOrNull { s -> s.any { it.isLetter() } && s.none { it.isDigit() } }
            val seasonEnd = SEASONS.find(raw)?.let { m -> m.groupValues[2].toIntOrNull() ?: m.groupValues[1].toIntOrNull() } ?: 0
            Subject(id, o.optString("detailPath"), tag?.let { LangTags.code(it) }, seasonEnd)
        }.filter { t.isMovie || it.seasonEnd == 0 || it.seasonEnd >= t.season }
            .distinctBy { it.lang }.take(5)
        if (subjects.isEmpty()) { Log.i(TAG, "moviebox: no match for \"$title\""); return null }

        val streams = coroutineScope {
            subjects.map { s -> async { play(bearer, s, t)?.copy(audioLang = s.lang) } }.awaitAll()
        }.filterNotNull()
        if (streams.isEmpty()) return null
        // Original (untagged) first
        val ordered = streams.sortedBy { if (it.audioLang == null) 0 else 1 }
        Log.i(TAG, "moviebox: \"$title\" ${ordered.size} stream(s), dubs ${ordered.mapNotNull { it.audioLang }}")
        return ordered.first().copy(variants = ordered.drop(1).takeIf { it.isNotEmpty() })
    }

    private fun norm(s: String) = s.lowercase().replace(NON_ALNUM, "")

    // ── API ───────────────────────────────────────────────────────────────────

    /** Anonymous bearer token from the `x-user` header of the app-version endpoint. */
    private suspend fun bearer(refresh: Boolean): String? = tokenLock.withLock {
        if (!refresh) token?.takeIf { System.currentTimeMillis() - tokenAt < TOKEN_TTL_MS }?.let { return@withLock it }
        val fresh = withContext(Dispatchers.IO) {
            runCatching {
                client.newCall(Request.Builder().url("$BASE/wefeed-h5api-bff/app/get-latest-app-pkgs?app_name=moviebox").build()).execute()
                    .use { r -> r.header("x-user")?.let { JSONObject(it).optString("token") } }
            }.getOrNull()?.takeIf { it.isNotBlank() }
        }
        if (fresh != null) { token = fresh; tokenAt = System.currentTimeMillis() }
        fresh
    }

    private fun apiHeaders(bearer: String) = mapOf(
        "X-Client-Info" to """{"timezone":"Africa/Nairobi"}""", "Accept" to "application/json",
        "Accept-Language" to "en-US,en;q=0.5", "Authorization" to "Bearer $bearer", "User-Agent" to UA, "Referer" to BASE,
    )

    private fun unwrap(o: JSONObject): JSONObject { val d = o.optJSONObject("data") ?: return o; return d.optJSONObject("data") ?: d }

    /** Search rows, or null when the token was refused (caller refreshes it once). */
    private suspend fun search(bearer: String, title: String, type: Int): JSONArray? = withContext(Dispatchers.IO) {
        runCatching {
            val body = JSONObject(mapOf("keyword" to title, "page" to 1, "perPage" to 24, "subjectType" to type)).toString()
            client.newCall(Request.Builder().url("$BASE/wefeed-h5api-bff/subject/search").post(body.toRequestBody(JSON))
                .apply { apiHeaders(bearer).forEach { (k, v) -> header(k, v) } }.build()).execute().use { r ->
                val o = JSONObject(r.body?.string() ?: return@use null)
                if (r.code == 401 || r.code == 403 || o.optString("code", "0") != "0") null else unwrap(o).optJSONArray("items") ?: JSONArray()
            }
        }.getOrNull()
    }

    /** Best free MP4 of one subject (episode for series) plus its subtitles. */
    private suspend fun play(bearer: String, s: Subject, t: StreamExtractor.Target): WebStreamSniffer.Sniffed? = withContext(Dispatchers.IO) {
        runCatching {
            val q = "subjectId=${s.id}" + (if (t.isMovie) "" else "&se=${t.season}&ep=${t.episode}") + "&detailPath=${s.detailPath}"
            val h = apiHeaders(bearer) + mapOf(
                "Referer" to "https://fmoviesunblocked.net/spa/videoPlayPage/movies/${s.detailPath}?id=${s.id}&type=/movie/detail",
                "Origin" to "https://fmoviesunblocked.net")
            fun call(path: String) = client.newCall(Request.Builder().url("$BASE/wefeed-h5api-bff/subject/$path?$q")
                .apply { h.forEach { (k, v) -> header(k, v) } }.build()).execute().use { r -> r.body?.string()?.let { unwrap(JSONObject(it)) } }
            val play = call("play")
            val dl = call("download")
            val all = listOfNotNull(play?.optJSONArray("streams"), dl?.optJSONArray("downloads")).flatMap { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }
                .filter { !it.optBoolean("vipLocked") && it.optString("url").startsWith("http") && !it.optString("url").contains("macdn.aoneroom.com/other/") }
            val best = all.maxByOrNull { it.optString("resolutions").toIntOrNull() ?: it.optInt("resolution", 0) } ?: return@runCatching null
            val subs = dl?.optJSONArray("captions")?.let { a ->
                (0 until a.length()).mapNotNull { a.optJSONObject(it) }.sortedByDescending { it.optString("lan") == "en" }
                    .mapNotNull { it.optString("url").takeIf { u -> u.startsWith("http") } }
            }.orEmpty().take(4)
            WebStreamSniffer.Sniffed(best.optString("url"), mapOf("Referer" to PLAY_REFERER), UA, subs)
        }.getOrNull()
    }

    private val client by lazy { RetrofitClient.base.newBuilder().callTimeout(10, TimeUnit.SECONDS).build() }
}
