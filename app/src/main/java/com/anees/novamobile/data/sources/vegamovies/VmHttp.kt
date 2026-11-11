package com.anees.novamobile.data.sources.vegamovies

import com.anees.novamobile.data.api.RetrofitClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume

/*
 * Ported from Indflix (https://github.com/dipender98/Indflix), GPL-3.0.
 * Original: Vegamovies/src/main/kotlin/com/vegamovies/VegamoviesPlugin.kt
 * Adapted: CloudStream `app.get`/CloudflareKiller → OkHttp with coroutine cancellation;
 * the Cloudflare cookie-solver interceptor is not ported (a challenged host simply fails
 * its health score until the domain healer finds a live mirror).
 */
object VmHttp {

    const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36"

    const val TAG = "NovaTV"

    const val SEED_VEGA = "https://new1.vegamovies.lease"
    const val SEED_ROG = "https://rogmovies.one"
    const val BOOTSTRAP_VEGA = "https://new2.vegamovies.futbol"
    const val BOOTSTRAP_ROG = "https://new2.rogmovies.click"
    const val LANDING_PROBE = "/?re=vg&t=2"
    const val HEAL_COOLDOWN_MS = 60_000L

    val VEGA_SEEDS = listOf(SEED_VEGA, BOOTSTRAP_VEGA)
    val ROG_SEEDS = listOf(SEED_ROG, BOOTSTRAP_ROG)

    val commonHeaders = mapOf("User-Agent" to UA, "Accept-Language" to "en-US,en;q=0.9")

    @Volatile var mainUrl = SEED_VEGA
    @Volatile var bollywoodUrl = SEED_ROG

    private val lastHeal = AtomicLong(0L)

    /** Cancellable GET: cancelling the coroutine (source-search timeout) aborts the request in flight. */
    suspend fun get(url: String, headers: Map<String, String> = commonHeaders, timeoutMs: Long = 15_000): String? =
        getFull(url, headers, timeoutMs)?.first

    /** As [get], plus the final URL after redirects (seeds often redirect to the live mirror). */
    /** Cancellable form POST (FirePlayer-style player APIs). */
    suspend fun post(url: String, form: Map<String, String>, headers: Map<String, String> = commonHeaders, timeoutMs: Long = 15_000): String? =
        getFull(url, headers, timeoutMs, form)?.first

    suspend fun getFull(url: String, headers: Map<String, String> = commonHeaders, timeoutMs: Long = 15_000,
                        form: Map<String, String>? = null): Pair<String, String>? {
        if (form != null) return fetch(url, headers, timeoutMs, form)
        // Same page asked again (another source / query / mirror check): answer from memory, or join the request in flight
        val key = url + "\u0000" + headers.entries.sortedBy { it.key }.joinToString("\u0000") { "${it.key}=${it.value}" }
        PageCache.get(key)?.let { return it }
        val d = inflight.computeIfAbsent(key) {
            scope.async(start = kotlinx.coroutines.CoroutineStart.LAZY) { fetch(url, headers, timeoutMs, null)?.also { PageCache.put(key, it) } }
                .also { job -> job.invokeOnCompletion { inflight.remove(key, job) } }
        }
        d.start()
        return d.await()
    }

    /** Recent page bodies, so one search doesn't download the same page twice. */
    internal object PageCache {
        private const val TTL_MS = 120_000L
        private const val MAX_BODY = 600_000
        private const val MAX_ENTRIES = 48
        private val map = object : LinkedHashMap<String, Pair<Long, Pair<String, String>>>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Long, Pair<String, String>>>?) = size > MAX_ENTRIES
        }
        @Synchronized fun get(k: String): Pair<String, String>? = map[k]?.let { if (System.currentTimeMillis() < it.first) it.second else { map.remove(k); null } }
        @Synchronized fun put(k: String, v: Pair<String, String>) { if (v.first.length <= MAX_BODY) map[k] = (System.currentTimeMillis() + TTL_MS) to v }
        @Synchronized fun clear() = map.clear()
    }
    private val inflight = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.Deferred<Pair<String, String>?>>()
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)
    /** Clients by timeout: building one per request cost an allocation storm during big searches. */
    private val clients = java.util.concurrent.ConcurrentHashMap<Long, okhttp3.OkHttpClient>()

    private suspend fun fetch(url: String, headers: Map<String, String>, timeoutMs: Long,
                              form: Map<String, String>?): Pair<String, String>? {
        val req = runCatching {
            val hb = Headers.Builder()
            headers.forEach { (k, v) -> hb.add(k, v) }
            val rb = Request.Builder().url(url).headers(hb.build())
            if (form != null) rb.post(okhttp3.FormBody.Builder().apply { form.forEach { (k, v) -> add(k, v) } }.build())
            rb.build()
        }.getOrNull() ?: return null
        val client = clients.getOrPut(timeoutMs) { RetrofitClient.base.newBuilder().callTimeout(timeoutMs, TimeUnit.MILLISECONDS).build() }
        return suspendCancellableCoroutine { cont ->
            val call = client.newCall(req)
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { if (cont.isActive) cont.resume(null) }
                override fun onResponse(call: Call, response: Response) {
                    val body = runCatching { response.body?.string() }.getOrNull()
                    if (cont.isActive) cont.resume(
                        if (response.isSuccessful && body != null) body to response.request.url.toString() else null
                    )
                }
            })
            cont.invokeOnCancellation { call.cancel() }
        }
    }

    private fun isChallengeBody(text: String): Boolean {
        val head = text.take(2500).lowercase()
        return head.contains("just a moment") || head.contains("checking your browser") ||
            head.contains("cf-chl-") || head.contains("challenge-platform")
    }

    fun isChallenge(doc: Document): Boolean {
        val t = doc.title()
        return t.contains("just a moment", true) || t.contains("checking your browser", true)
    }

    /** Page fetch: HTML body → Jsoup document, null on error / landing page / challenge. */
    suspend fun fetchDoc(url: String, timeoutMs: Long = 12_000, headers: Map<String, String> = commonHeaders): Document? {
        val (text, finalUrl) = getFull(url, headers, timeoutMs) ?: return null
        if (isChallengeBody(text)) return null
        return runCatching { org.jsoup.Jsoup.parse(text, finalUrl) }.getOrNull()?.takeUnless { isChallenge(it) || isLanding(it) }
    }

    fun isLanding(doc: Document): Boolean =
        doc.selectFirst("#stx") != null || doc.body().html().contains("qikly.pro/host")

    /** The site's JSON search index: returns the raw body only when it really is JSON. */
    suspend fun searchIndexJson(base: String, query: String): String? {
        val url = "$base/ts-search.php?q=${URLEncoder.encode(query.trim(), "UTF-8")}&page=1"
        val direct = get(url, commonHeaders + mapOf("Referer" to "$base/"), 12_000)
        if (direct != null && direct.trimStart().startsWith("{")) return direct
        return null
    }

    private suspend fun liveDoc(seed: String): Pair<Document, String>? {
        val first = fetchDoc(seed, 10_000) ?: return null
        if (!isLanding(first)) return first to first.baseUri()
        val origin = originOf(first.baseUri()) ?: return null
        return fetchDoc(origin + LANDING_PROBE, 12_000)?.takeUnless { isLanding(it) }?.let { it to it.baseUri() }
    }

    private suspend fun verifiedBase(family: String, seeds: List<String>): String? {
        for (seed in seeds) {
            val live = liveDoc(seed) ?: continue
            val origin = originOf(live.second) ?: continue
            for (candidate in listOf(origin) + candidates(live.first, family)) {
                if (searchIndexJson(candidate, "film") != null) return candidate
            }
        }
        return null
    }

    /** Domains move often: re-derive them from the seed hosts, keeping one that serves the search index. */
    suspend fun refreshDomains(): Unit = coroutineScope {
        val started = System.currentTimeMillis()
        val last = lastHeal.get()
        // Claim the heal up front, so two searches starting together don't both walk the seeds
        if (started - last < HEAL_COOLDOWN_MS || !lastHeal.compareAndSet(last, started)) return@coroutineScope
        val a = async { verifiedBase("vegamovies", VEGA_SEEDS) }
        val b = async { verifiedBase("rogmovies", ROG_SEEDS) }
        val vega = a.await()?.also { mainUrl = it }
        val rog = b.await()?.also { bollywoodUrl = it }
        // Nothing verified (offline, seeds down for a moment): try again soon instead of a full cooldown later
        lastHeal.set(if (vega == null && rog == null) System.currentTimeMillis() - HEAL_COOLDOWN_MS + HEAL_RETRY_MS else System.currentTimeMillis())
    }
    private const val HEAL_RETRY_MS = 10_000L

    fun needsHeal(): Boolean = System.currentTimeMillis() - lastHeal.get() > HEAL_COOLDOWN_MS

    /** Mirror links of the given family found on a page (vegamovies.* / rogmovies.*). */
    fun candidates(doc: Document, family: String): List<String> =
        doc.select("a[href]")
            .mapNotNull {
                Regex("""https?://[a-z0-9.\-]*$family\.[a-z]{2,10}""", RegexOption.IGNORE_CASE)
                    .find(it.attr("abs:href"))?.value?.lowercase()
            }
            .filterNot { it.contains("apk") || it.endsWith(".cfd") }
            .groupingBy { it }.eachCount()
            .entries.sortedByDescending { it.value }
            .map { it.key }

    fun originOf(url: String): String? = Regex("""^https?://[^/]+""").find(url)?.value

    fun candidateBases(family: String): List<String> {
        val primary = if (family == "vegamovies") mainUrl else bollywoodUrl
        val seeds = if (family == "vegamovies") VEGA_SEEDS else ROG_SEEDS
        return (listOf(primary) + seeds).distinct()
    }

    suspend fun <T> withSem(sem: kotlinx.coroutines.sync.Semaphore, block: suspend () -> T): T {
        sem.acquire()
        try { return block() } finally { sem.release() }
    }

    suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }
}
