package com.anees.novamobile.data.sources

import android.util.Log
import com.anees.novamobile.data.api.RetrofitClient
import com.anees.novamobile.data.sources.vegamovies.VmHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * One cheap request that tells a live file link from a dead one before it is handed to the player or the
 * downloader. File hosts behind HubCloud & co. often list links that 404 (deleted PixelDrain files, one
 * shared decoy id for every quality), 500 (dead 10Gbps workers) or answer an HTML page: played as is, those
 * sat at 0 B / 0 % while the downloader kept fetching "fresh" copies of the same dead link.
 */
object LinkProbe {
    private val client by lazy {
        RetrofitClient.base.newBuilder()
            .connectTimeout(5, TimeUnit.SECONDS).readTimeout(6, TimeUnit.SECONDS).callTimeout(8, TimeUnit.SECONDS)
            .build()
    }

    /** True when [url] answers 2xx with something that isn't an HTML/JSON error page, and its first bytes arrive in time. */
    suspend fun alive(url: String, headers: Map<String, String> = emptyMap()): Boolean = withContext(Dispatchers.IO) {
        val ok = runCatching {
            val rb = Request.Builder().url(url).header("User-Agent", VmHttp.UA).header("Range", "bytes=0-65535")
            headers.forEach { (k, v) -> rb.header(k, v) }
            client.newCall(rb.build()).execute().use { r ->
                if (!r.isSuccessful) return@use false
                val type = r.header("Content-Type").orEmpty().lowercase()
                if (type.startsWith("text/html") || type.contains("json")) return@use false
                val buf = ByteArray(1024)
                val n = r.body?.byteStream()?.read(buf) ?: -1
                n > 0 && !String(buf, 0, n, Charsets.ISO_8859_1).trimStart().let { it.startsWith("<", ignoreCase = true) || it.startsWith("{") }
            }
        }.getOrDefault(false)
        if (!ok) Log.i("LinkProbe", "dead link on ${url.substringAfter("://").substringBefore("/")}")
        ok
    }
}
