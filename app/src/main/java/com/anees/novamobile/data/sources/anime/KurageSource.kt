package com.anees.novamobile.data.sources.anime

import android.util.Log
import com.anees.novamobile.data.sources.vegamovies.VmHttp
import com.anees.novamobile.ui.player.StreamExtractor
import com.anees.novamobile.ui.player.StreamMeta
import com.anees.novamobile.ui.player.WebStreamSniffer
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Kurage (Nov 2026): tRPC API keyed by AniList id → sub and English dub as MP4 files (Range works, so they
 * stream and download). One request asks for both; the dub becomes a variant of the sub.
 */
object KurageSource {
    private const val TAG = "NovaTV"
    private const val BASE = "https://kurage.live"

    suspend fun resolve(t: StreamExtractor.Target): WebStreamSniffer.Sniffed? {
        val al = t.aniListId ?: return null
        val ep = t.aniEpisode ?: if (t.isMovie) 1 else t.episode
        val input = JSONObject().apply {
            put("0", JSONObject().put("json", JSONObject().put("animeId", al).put("episode", ep).put("language", "sub")))
            put("1", JSONObject().put("json", JSONObject().put("animeId", al).put("episode", ep).put("language", "dub")))
        }
        val url = "$BASE/api/trpc/episodes.source,episodes.source?batch=1&input=" + URLEncoder.encode(input.toString(), "UTF-8")
        val h = mapOf("User-Agent" to VmHttp.UA, "trpc-accept" to "application/json", "x-trpc-source" to "nextjs-react",
            "Referer" to "$BASE/", "Origin" to BASE, "Accept" to "application/json")
        val body = VmHttp.get(url, h, 12_000) ?: return null
        val arr = runCatching { JSONArray(body) }.getOrNull() ?: return null
        val found = listOf(0 to "ja", 1 to "en").mapNotNull { (i, lang) ->
            val d = arr.optJSONObject(i)?.optJSONObject("result")?.optJSONObject("data")?.optJSONObject("json") ?: return@mapNotNull null
            if (!d.optBoolean("available", true)) return@mapNotNull null
            val u = d.optString("url").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val full = if (u.startsWith("http")) u else BASE + u
            val mp4 = d.optString("sourceType") == "mp4"
            WebStreamSniffer.Sniffed(full, mapOf("Referer" to "$BASE/"), VmHttp.UA, emptyList(), audioLang = lang,
                meta = StreamMeta(server = d.optString("label").ifBlank { null }, langs = listOf(lang),
                    release = if (lang == "ja" && mp4) "Japanese · English subtitles burned in" else null))
        }
        if (found.isEmpty()) return null
        Log.i(TAG, "kurage: anilist $al ep $ep ${found.mapNotNull { it.audioLang }}")
        return found.first().copy(variants = found.drop(1).takeIf { it.isNotEmpty() })
    }
}
