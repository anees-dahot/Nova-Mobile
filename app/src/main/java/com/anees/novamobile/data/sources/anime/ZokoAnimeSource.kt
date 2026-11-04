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

object ZokoAnimeSource {

    private const val TAG = "NovaTV"
    private const val BASE = "https://zokoanime.video"
    private const val KEY = "otaku-embed-v1"
    private val CONFIG = Regex("""window\.__P\s*=\s*"([^"]+)"""")

    suspend fun resolve(t: StreamExtractor.Target): WebStreamSniffer.Sniffed? {
        val mal = t.malId ?: return null
        val ep = t.aniEpisode ?: return null
        // Original with subs first, the English dub as the fallback (no language setting any more)
        val order = listOf("sub", "dub")
        for (kind in order) {
            val cfg = config(mal, ep, kind) ?: continue
            val src = cfg.optString("src").takeIf { it.startsWith("http") } ?: continue
            val referer = "$BASE/"
            val playlist = VmHttp.get(src, VmHttp.commonHeaders + ("Referer" to referer), 6_000) ?: continue
            if (!playlist.trimStart().startsWith("#EXTM3U")) continue
            Log.i(TAG, "zokoanime: mal $mal ep $ep ($kind)")
            return WebStreamSniffer.Sniffed(src, mapOf("Referer" to referer), VmHttp.UA, subtitles(cfg))
        }
        return null
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

    /** English first, then the rest; the player shows a handful at most. */
    private fun subtitles(cfg: JSONObject): List<String> {
        val arr = cfg.optJSONArray("subtitles") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
            .sortedByDescending { it.optString("lang") == "en" }
            .mapNotNull { o -> o.optString("src").takeIf { it.startsWith("http") } }
            .take(4)
    }
}
