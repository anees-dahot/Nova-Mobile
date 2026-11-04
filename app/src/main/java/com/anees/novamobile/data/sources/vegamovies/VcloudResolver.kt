package com.anees.novamobile.data.sources.vegamovies

import android.util.Base64
import java.util.concurrent.ConcurrentHashMap

/*
 * Ported from Indflix (https://github.com/dipender98/Indflix), GPL-3.0.
 * Original: Vegamovies/src/main/kotlin/com/vegamovies/VcloudResolver.kt
 * Adapted: `app.get` → VmHttp.get; kotlin.io.encoding.Base64 → android.util.Base64.
 */

internal data class VcloudStream(val url: String, val tag: String)

internal object VcloudResolver {

    val ATOB_ATOB = Regex(
        """var\s+url\s*=\s*atob\s*\(\s*atob\s*\(\s*['"]([A-Za-z0-9+/=]{16,})['"]\s*\)\s*\)""",
    )

    val BTN = Regex(
        """<a\b[^>]*href="([^"]+)"[^>]*>\s*(?:<[^>]+>\s*)*([^<]*(?:Download|Server|File)[^<]*)(?:<[^>]+>\s*)*</a>""",
        RegexOption.IGNORE_CASE,
    )

    val STREAMABLE_HOST = Regex("""(r2\.cloudflarestorage\.com|r2\.dev)""", RegexOption.IGNORE_CASE)

    private val directCache = ConcurrentHashMap<String, List<VcloudStream>>()

    /** vcloud page → its double-atob token page → the r2 streamable file URLs. */
    suspend fun resolve(vcloudUrl: String, referer: String, headers: Map<String, String>): List<VcloudStream> {
        directCache[vcloudUrl]?.let { return it }
        val page = VmHttp.get(vcloudUrl, headers + mapOf("Referer" to referer), timeoutMs = 15_000)
            ?: return rememberEmpty(vcloudUrl)
        val tokenUrl = ATOB_ATOB.find(page)?.groupValues?.get(1)?.let { decodeDoubleAtob(it) }
            ?.takeIf { it.startsWith("http") } ?: return rememberEmpty(vcloudUrl)
        val token = VmHttp.get(tokenUrl, headers + mapOf("Referer" to vcloudUrl), timeoutMs = 15_000)
            ?: return rememberEmpty(vcloudUrl)
        val seen = ArrayList<VcloudStream>(4)
        val urls = LinkedHashSet<String>()
        BTN.findAll(token).forEach { m ->
            val raw = m.groupValues[1].trim()
            val label = m.groupValues[2].trim().let {
                Regex("""\[([^\]]+)\]""").find(it)?.groupValues?.get(1)?.trim() ?: it
            }
            val abs = if (raw.startsWith("http")) raw else {
                val origin = VmHttp.originOf(tokenUrl) ?: return@forEach
                "$origin${if (raw.startsWith('/')) raw else "/$raw"}"
            }
            if (STREAMABLE_HOST.containsMatchIn(abs) && urls.add(abs)) {
                seen += VcloudStream(abs, label.take(20).ifBlank { "FSL" })
            }
        }
        if (seen.isNotEmpty() && directCache.size < 64) directCache[vcloudUrl] = seen
        return seen
    }

    private fun rememberEmpty(vcloudUrl: String): List<VcloudStream> {
        if (directCache.size < 64) directCache[vcloudUrl] = emptyList()
        return emptyList()
    }

    internal fun decodeDoubleAtob(blob: String): String? = try {
        val inner = String(decodeB64(blob.trim()))
        String(decodeB64(inner.trim()))
    } catch (e: Exception) {
        null
    }

    private fun decodeB64(s: String): ByteArray {
        val normalized = buildString {
            for (c in s) when (c) {
                '-' -> append('+'); '_' -> append('/'); else -> append(c)
            }
        }
        val padded = normalized + "=".repeat((4 - normalized.length % 4) % 4)
        return Base64.decode(padded, Base64.DEFAULT)
    }
}
