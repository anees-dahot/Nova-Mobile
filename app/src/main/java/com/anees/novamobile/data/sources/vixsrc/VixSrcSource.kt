package com.anees.novamobile.data.sources.vixsrc

import android.util.Log
import com.anees.novamobile.data.sources.vegamovies.VmHttp
import com.anees.novamobile.ui.player.StreamExtractor
import com.anees.novamobile.ui.player.StreamMeta
import com.anees.novamobile.ui.player.WebStreamSniffer
import org.json.JSONObject

/**
 * VixSrc over plain HTTP (no hidden page): its API hands an embed path, the embed page carries
 * `window.masterPlaylist` (url + token + expires). The master playlist lists audio renditions with real
 * language tags (English, Italian…), many subtitles, up to 1080p (`h=1`).
 */
object VixSrcSource {
    private const val TAG = "NovaTV"
    private const val BASE = "https://vixsrc.to"
    private val UA = VmHttp.UA
    private val headers get() = mapOf("User-Agent" to UA, "Referer" to "$BASE/", "Accept" to "*/*")

    suspend fun resolve(t: StreamExtractor.Target): WebStreamSniffer.Sniffed? {
        val api = if (t.isMovie) "$BASE/api/movie/${t.tmdbId}" else "$BASE/api/tv/${t.tmdbId}/${t.season}/${t.episode}"
        val src = VmHttp.get(api, headers, 8_000)?.let { runCatching { JSONObject(it).optString("src") }.getOrNull() }
            ?.takeIf { it.startsWith("/") } ?: return null
        val page = VmHttp.get(BASE + src, headers, 10_000) ?: return null
        val block = page.substringAfter("window.masterPlaylist", "").takeIf { it.isNotEmpty() } ?: return null
        val url = Regex("""url:\s*['"]([^'"]+)['"]""").find(block)?.groupValues?.get(1) ?: return null
        val token = Regex("""['"]?token['"]?\s*:\s*['"]([^'"]+)['"]""").find(block)?.groupValues?.get(1) ?: return null
        val expires = Regex("""['"]?expires['"]?\s*:\s*['"]([^'"]+)['"]""").find(block)?.groupValues?.get(1) ?: return null
        val fhd = page.contains("canPlayFHD = true")
        val master = url + (if (url.contains("?")) "&" else "?") + "token=$token&expires=$expires" + (if (fhd) "&h=1" else "") + "&lang=en"
        Log.i(TAG, "vixsrc: ${master.take(80)}")
        return WebStreamSniffer.Sniffed(master, mapOf("Referer" to "$BASE/", "Origin" to BASE), UA, emptyList(),
            meta = StreamMeta(server = null))
    }
}
