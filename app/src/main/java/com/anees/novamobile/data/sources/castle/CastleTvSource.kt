package com.anees.novamobile.data.sources.castle

import android.util.Base64
import android.util.Log
import com.anees.novamobile.data.api.RetrofitClient
import com.anees.novamobile.data.model.LangTags
import com.anees.novamobile.data.sources.vegamovies.matchScore
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
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * CastleTV (the Castle app's API, ported from Indflix/IndStream, GPL-3.0): title search →
 * episode → one HLS per audio language (Nov 2026, Frieren S1E1: Hindi, English, Tamil, Telugu,
 * Japanese, 720p). Responses are AES-CBC encrypted with a key handed out by the API.
 *
 * The default stream is returned as the primary; every language comes along as a variant, so
 * the player's language menu lists them and the download sheet shows one row per dub.
 */
object CastleTvSource {
    private const val TAG = "NovaSrc"
    private const val BASE = "https://api.hlowb.com"
    private const val QUERY = "channel=IndiaA&clientType=1&lang=en-US"
    private const val PKG = "com.external.castle"
    private const val SIGN = "ED0955EB04E67A1D9F3305B95454FED485261475"
    private const val KEY_TTL_MS = 60 * 60_000L
    private val HEADERS = mapOf("User-Agent" to "okhttp/4.9.3", "Accept" to "application/json", "Referer" to BASE)
    private val JSON = "application/json".toMediaType()
    private const val MIN_MATCH = 0.6
    private val HEIGHT = Regex("(\\d{3,4})[pP]")

    private var key: String? = null
    private var keyAt = 0L
    private val keyLock = Mutex()

    suspend fun resolve(t: StreamExtractor.Target): WebStreamSniffer.Sniffed? {
        val title = t.title?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val sec = securityKey() ?: return null
        val rows = search(sec, title)
        // Same title, different year = different show (anime vs live-action remake): skip when the API says so
        val best = rows.filter { r -> val y = synchronized(rowYears) { rowYears[r.first] }; t.year == null || y == null || kotlin.math.abs(y - t.year) <= 1 }
            .map { it to matchScore(title, it.second, null, null) }.maxByOrNull { it.second }
        if (best == null || best.second < MIN_MATCH) { Log.i(TAG, "castletv: no match for \"$title\""); return null }
        var movieId = best.first.first
        var det = details(sec, movieId) ?: return null
        if (!t.isMovie) {
            // Each season is its own movie id
            val seasons = det.optJSONArray("seasons")
            val sid = (0 until (seasons?.length() ?: 0)).mapNotNull { seasons!!.optJSONObject(it) }
                .firstOrNull { it.optInt("number", -1) == t.season }
                ?.opt("movieId")?.takeIf { it != JSONObject.NULL }?.toString()
            if (sid != null && sid != movieId) { det = details(sec, sid) ?: return null; movieId = sid }
            else if (sid == null && t.season > 1) { Log.i(TAG, "castletv: no season ${t.season}"); return null }
        }
        val eps = det.optJSONArray("episodes") ?: return null
        val ep = if (t.isMovie) eps.optJSONObject(0)
            else (0 until eps.length()).mapNotNull { eps.optJSONObject(it) }.firstOrNull { it.optInt("number", -1) == t.episode }
        val epId = ep?.opt("id")?.takeIf { it != JSONObject.NULL }?.toString() ?: return null
        val tracks = ep.optJSONArray("tracks")?.let { arr ->
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
                .mapNotNull { o -> o.optString("languageId").takeIf { it.isNotBlank() }?.let { it to LangTags.code(o.optString("languageName")) } }
        }.orEmpty()

        val found = coroutineScope {
            val default = async { video(sec, movieId, epId, null) }
            val dubs = tracks.map { (id, lang) -> async { video(sec, movieId, epId, id)?.let { it.copy(audioLang = lang) } } }
            listOf(default.await()) + dubs.awaitAll()
        }.filterNotNull().distinctBy { it.url }
        if (found.isEmpty()) return null
        Log.i(TAG, "castletv: \"${best.first.second}\" ${found.size} stream(s), languages ${found.mapNotNull { it.audioLang }}")
        return found.first().copy(variants = found.drop(1).takeIf { it.isNotEmpty() })
    }

    // ── API ───────────────────────────────────────────────────────────────────

    private suspend fun securityKey(): String? = keyLock.withLock {
        key?.takeIf { System.currentTimeMillis() - keyAt < KEY_TTL_MS }?.let { return@withLock it }
        val fresh = get("$BASE/v0.1/system/getSecurityKey/1?$QUERY")
            ?.let { runCatching { JSONObject(it).optString("data") }.getOrNull() }?.takeIf { it.isNotBlank() }
        if (fresh != null) { key = fresh; keyAt = System.currentTimeMillis() }
        fresh
    }

    /** Release year of a search row when the API states one (field names vary). */
    private val rowYears = HashMap<String, Int>()

    private suspend fun search(sec: String, keyword: String): List<Pair<String, String>> {
        val q = URLEncoder.encode(keyword, "UTF-8")
        val text = get("$BASE/film-api/v1.1.0/movie/searchByKeyword?$QUERY&keyword=$q&mode=1&packageName=$PKG&page=1&size=30") ?: return emptyList()
        val rows = decrypt(text, sec)?.let { (it.optJSONObject("data") ?: it).optJSONArray("rows") } ?: return emptyList()
        return (0 until rows.length()).mapNotNull { rows.optJSONObject(it) }.mapNotNull { r ->
            val id = r.opt("id")?.toString()?.takeIf { it.isNotBlank() && it != "null" } ?: return@mapNotNull null
            val name = r.optString("title").ifBlank { r.optString("name") }.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            listOf("year", "releaseYear", "publishTime", "releaseTime", "releaseDate", "pubDate").firstNotNullOfOrNull { k ->
                r.optString(k).takeIf { it.length >= 4 }?.let { v -> Regex("""(19|20)\d{2}""").find(v)?.value?.toIntOrNull() }
            }?.let { synchronized(rowYears) { rowYears[id] = it } }
            id to name
        }
    }

    private suspend fun details(sec: String, movieId: String): JSONObject? =
        get("$BASE/film-api/v1.9.9/movie?$QUERY&movieId=$movieId&packageName=$PKG")
            ?.let { decrypt(it, sec) }?.let { it.optJSONObject("data") ?: it }

    /** One stream (720p/1080p where offered) for a language, or the default audio when [languageId] is null. */
    private suspend fun video(sec: String, movieId: String, episodeId: String, languageId: String?): WebStreamSniffer.Sniffed? {
        val body = JSONObject(mapOf(
            "mode" to "1", "appMarket" to "GuanWang", "clientType" to "1", "woolUser" to "false",
            "apkSignKey" to SIGN, "androidVersion" to "13", "movieId" to movieId, "episodeId" to episodeId,
            "isNewUser" to "true", "resolution" to "3", "packageName" to PKG,
        )).apply { if (languageId != null) put("languageId", languageId) }
        val text = post("$BASE/film-api/v2.0.1/movie/getVideo2?clientType=1&packageName=$PKG&channel=IndiaA&lang=en-US", body.toString()) ?: return null
        val data = decrypt(text, sec)?.let { it.optJSONObject("data") ?: it } ?: return null
        val url = data.optString("videoUrl").takeIf { it.startsWith("http") } ?: return null
        val subs = data.optJSONArray("subtitles")?.let { arr ->
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("url")?.takeIf { u -> u.startsWith("http") } }
        }.orEmpty().take(4)
        // "HD 720P" / "SD 480P": the best listed is what resolution=3 hands out
        val height = data.optJSONArray("videos")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optString("resolutionDescription") } }
            .orEmpty().plus(data.optString("resolutionDescription")).mapNotNull { HEIGHT.find(it)?.groupValues?.get(1)?.toIntOrNull() }.maxOrNull()
        return WebStreamSniffer.Sniffed(url, emptyMap(), "okhttp/4.9.3", subs, height = height)
    }

    /** Envelope: `{"data":"<cipher>"}` or a bare cipher; AES-CBC, key = IV = first 16 bytes of the security key. */
    private fun decrypt(text: String, sec: String): JSONObject? {
        val cipher = runCatching { JSONObject(text.trim()).let { r -> r.optJSONObject("data")?.let { return r }; r.optString("data") } }
            .getOrNull()?.takeIf { it.isNotBlank() } ?: text.trim()
        runCatching { return JSONObject(cipher) }
        return runCatching {
            val raw = Base64.decode(sec, Base64.DEFAULT)
            val k = ByteArray(16).also { out ->
                val n = minOf(raw.size, 16); System.arraycopy(raw, 0, out, 0, n)
                if (n < 16) "T!BgJB".toByteArray().copyInto(out, n, 0, minOf(6, 16 - n))
            }
            val c = Cipher.getInstance("AES/CBC/PKCS5Padding").apply { init(Cipher.DECRYPT_MODE, SecretKeySpec(k, "AES"), IvParameterSpec(k)) }
            val ct = Base64.decode(cipher.replace("-_.", "/").replace("@", "+"), Base64.DEFAULT)
            JSONObject(String(c.doFinal(ct), Charsets.UTF_8))
        }.getOrNull()
    }

    private val client by lazy { RetrofitClient.base.newBuilder().callTimeout(10, TimeUnit.SECONDS).build() }

    private suspend fun get(url: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            client.newCall(Request.Builder().url(url).apply { HEADERS.forEach { (k, v) -> header(k, v) } }.build()).execute()
                .use { r -> if (r.isSuccessful) r.body?.string() else null }
        }.getOrNull()
    }

    private suspend fun post(url: String, json: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            client.newCall(Request.Builder().url(url).post(json.toRequestBody(JSON)).apply { HEADERS.forEach { (k, v) -> header(k, v) } }.build()).execute()
                .use { r -> if (r.isSuccessful) r.body?.string() else null }
        }.getOrNull()
    }
}
