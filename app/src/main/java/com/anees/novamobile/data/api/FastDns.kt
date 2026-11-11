package com.anees.novamobile.data.api

import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.Inet4Address
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * DNS for every scraper request:
 *  - answers cached 5 min (sources hit the same few hosts dozens of times per search)
 *  - IPv4 first: OkHttp 4 tries addresses one after another, so a dead IPv6 route cost a full
 *    connect timeout before the working IPv4 address was tried
 *  - ISP blocks (Jio / Airtel answer blocked sites with a sinkhole or nothing): asks DNS-over-HTTPS
 *    (Cloudflare, Google by IP) instead, so the source still resolves
 *  - a stale answer beats no answer when the lookup itself fails (flaky network)
 */
object FastDns : Dns {
    private const val TTL_MS = 5 * 60_000L
    private const val FAIL_TTL_MS = 30_000L
    private class Entry(val until: Long, val ips: List<InetAddress>)
    private val cache = ConcurrentHashMap<String, Entry>()

    /** Plain client (system DNS) for the DoH calls themselves; endpoints are IPs, so no lookup recursion. */
    private val doh by lazy {
        OkHttpClient.Builder().connectTimeout(3, TimeUnit.SECONDS).readTimeout(3, TimeUnit.SECONDS).callTimeout(4, TimeUnit.SECONDS).build()
    }
    private val DOH = listOf("https://1.1.1.1/dns-query", "https://8.8.8.8/resolve")

    /** Test hook: replace the system resolver. */
    internal var system: Dns = Dns.SYSTEM
    internal var dohLookup: (String) -> List<InetAddress> = ::dohResolve
    internal var now: () -> Long = System::currentTimeMillis

    override fun lookup(hostname: String): List<InetAddress> {
        val t = now()
        cache[hostname]?.let { if (t < it.until) return it.ips.ifEmpty { throw UnknownHostException(hostname) } }
        val sys = runCatching { system.lookup(hostname) }.getOrDefault(emptyList())
        val ips = sys.filterNot { blocked(it) }.ifEmpty {
            // Nothing usable from the ISP resolver: try DoH (skip for local names / raw IPs)
            if (hostname.contains('.') && !hostname.all { it.isDigit() || it == '.' || it == ':' }) runCatching { dohLookup(hostname) }.getOrDefault(emptyList()) else sys
        }
        if (ips.isEmpty()) {
            cache[hostname]?.ips?.takeIf { it.isNotEmpty() }?.let { return it }   // stale beats nothing
            cache[hostname] = Entry(t + FAIL_TTL_MS, emptyList())
            throw UnknownHostException(hostname)
        }
        val sorted = ips.sortedBy { if (it is Inet4Address) 0 else 1 }
        cache[hostname] = Entry(t + TTL_MS, sorted)
        return sorted
    }

    /** Forget a host (its address stopped answering). */
    fun forget(hostname: String) { cache.remove(hostname) }

    /** Sinkhole answers ISPs give for blocked sites: loopback, 0.0.0.0, private ranges. */
    internal fun blocked(a: InetAddress): Boolean =
        a.isAnyLocalAddress || a.isLoopbackAddress || a.isSiteLocalAddress || a.isLinkLocalAddress

    private fun dohResolve(host: String): List<InetAddress> {
        for (base in DOH) {
            val ips = runCatching {
                doh.newCall(Request.Builder().url("$base?name=$host&type=A").header("Accept", "application/dns-json").build()).execute().use { r ->
                    if (!r.isSuccessful) return@use emptyList()
                    val answers = JSONObject(r.body?.string() ?: "").optJSONArray("Answer") ?: return@use emptyList()
                    (0 until answers.length()).mapNotNull { answers.optJSONObject(it) }
                        .filter { it.optInt("type") == 1 }
                        .mapNotNull { a -> runCatching { InetAddress.getByAddress(host, parseV4(a.optString("data"))) }.getOrNull() }
                }
            }.getOrDefault(emptyList()).filterNot { blocked(it) }
            if (ips.isNotEmpty()) return ips
        }
        return emptyList()
    }

    private fun parseV4(s: String): ByteArray {
        val p = s.split('.').map { it.toInt() }
        require(p.size == 4 && p.all { it in 0..255 })
        return ByteArray(4) { p[it].toByte() }
    }
}
