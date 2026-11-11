package com.anees.novamobile.data.sources.anime

/*
 * ZokoAnime: the player HiAnime (hianime.at) embeds, found Nov 2026 by decoding its
 * episode/servers API. Keyed by MAL id + episode, so no title search:
 *   zokoanime.video/stream/mal/<malId>/<episode>/<sub|dub>
 * The page carries window.__P = base64(json XOR "otaku-embed-v1") with the HLS master
 * (360p/720p/1080p) and VTT subtitles. The CDN wants the player's Referer.
 */

import android.util.Base64
import android.util.Log
import com.anees.novamobile.data.local.LocalStore
import com.anees.novamobile.data.sources.vegamovies.VmHttp
import com.anees.novamobile.ui.player.StreamExtractor
import com.anees.novamobile.ui.player.WebStreamSniffer
import org.json.JSONObject
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll

object ZokoAnimeSource {

    private const val TAG = "NovaTV"
    private const val BASE = "https://zokoanime.video"
    private const val KEY = "otaku-embed-v1"
    private val CONFIG = Regex("""window\.__P\s*=\s*"([^"]+)"""")

    /** Original (Japanese, many subtitle languages) and the English dub as its variant, both checked at once. */
    suspend fun resolve(t: StreamExtractor.Target): WebStreamSniffer.Sniffed? = kotlinx.coroutines.coroutineScope {
        val mal = t.malId ?: return@coroutineScope null
        val ep = t.aniEpisode ?: return@coroutineScope null
        val referer = "$BASE/"
        val found = listOf("sub" to "ja", "dub" to "en").map { (kind, lang) ->
            async {
                val cfg = config(mal, ep, kind) ?: return@async null
                val src = cfg.optString("src").takeIf { it.startsWith("http") } ?: return@async null
                val playlist = VmHttp.get(src, VmHttp.commonHeaders + ("Referer" to referer), 6_000) ?: return@async null
                if (!playlist.trimStart().startsWith("#EXTM3U")) return@async null
                val subs = subtitles(cfg)
                WebStreamSniffer.Sniffed(src, mapOf("Referer" to referer), VmHttp.UA, subs.map { it.first }, audioLang = lang,
                    meta = com.anees.novamobile.ui.player.StreamMeta(langs = listOf(lang), subLangs = subs.map { it.second }.takeIf { l -> l.any { it.isNotEmpty() } }))
            }
        }.awaitAll().filterNotNull()
        if (found.isEmpty()) return@coroutineScope null
        Log.i(TAG, "zokoanime: mal $mal ep $ep ${found.mapNotNull { it.audioLang }}")
        found.first().copy(variants = found.drop(1).takeIf { it.isNotEmpty() })
    }

    private suspend fun config(mal: Int, ep: Int, kind: String): JSONObject? {
        val page = "$BASE/stream/mal/$mal/$ep/$kind"
        val html = VmHttp.get(page, VmHttp.commonHeaders + ("Referer" to "https://hianime.at/"), 10_000) ?: return null
        val blob = CONFIG.find(html)?.groupValues?.get(1) ?: return null
        return runCatching {
            val raw = Base64.decode(blob, Base64.DEFAULT)
            val plain = ByteArray(raw.size) { i -> (raw[i].toInt() xor KEY[i % KEY.length].code).toByte() }
            JSONObject(String(plain, Charsets.UTF_8))
        }.getOrNull()
    }

    /** Subtitles with their languages (the site's "lang" field is unreliable: the label names the language), English first. */
    private fun subtitles(cfg: JSONObject): List<Pair<String, String>> {
        val arr = cfg.optJSONArray("subtitles") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
            .mapNotNull { o -> o.optString("src").takeIf { it.startsWith("http") }?.let { it to com.anees.novamobile.ui.player.ReleaseInfo.langCode(o.optString("label").ifBlank { o.optString("lang") }) } }
            .sortedByDescending { it.second == "en" }
            .take(8)
    }
}
