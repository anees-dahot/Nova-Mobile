package com.anees.novamobile.transfer

import android.net.Uri
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Phone-to-phone transfer ("Beam"). The sending phone serves its finished downloads over a local link;
 * the receiving phone adds them to its own download queue, with the bytes coming from the sender
 * instead of the internet (see [BeamLink]). Pieces the sender doesn't have come from the internet.
 *
 * Link: the same Wi-Fi network when both phones are on one (found from the QR's addresses), else a
 * private Wi-Fi Direct group the sender hosts ([BeamWifi]).
 */
object Beam {
    const val PORT = 47700
    private const val SCHEME = "novabeam"

    /**
     * What the QR carries. [ssid]/[pass] are empty when Wi-Fi Direct isn't available on the sender;
     * [ips] are the sender's addresses on its current networks.
     */
    data class Invite(val ssid: String, val pass: String, val port: Int, val token: String = "", val ips: List<String> = emptyList(),
                      val hsSsid: String = "", val hsPass: String = "") {
        fun encode(): String = Uri.Builder().scheme(SCHEME).authority("v1")
            .appendQueryParameter("s", ssid).appendQueryParameter("p", pass)
            .appendQueryParameter("port", port.toString()).appendQueryParameter("t", token)
            .appendQueryParameter("ip", ips.joinToString(","))
            .apply { if (hsSsid.isNotEmpty()) appendQueryParameter("hs", hsSsid).appendQueryParameter("hp", hsPass) }
            .build().toString()

        val hasDirect get() = ssid.isNotEmpty() && pass.isNotEmpty()
        val hasHotspot get() = hsSsid.isNotEmpty()

        companion object {
            fun decode(text: String): Invite? = runCatching {
                val u = Uri.parse(text.trim())
                if (u.scheme != SCHEME) return null
                Invite(
                    u.getQueryParameter("s").orEmpty(), u.getQueryParameter("p").orEmpty(),
                    u.getQueryParameter("port")?.toIntOrNull() ?: PORT, u.getQueryParameter("t").orEmpty(),
                    u.getQueryParameter("ip").orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() },
                    u.getQueryParameter("hs").orEmpty(), u.getQueryParameter("hp").orEmpty(),
                ).takeIf { it.token.isNotEmpty() }
            }.getOrNull()
        }
    }

    /** IPv4 addresses on active, non-loopback interfaces (Wi-Fi, hotspot, Wi-Fi Direct). */
    fun localIps(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { ni -> ni.inetAddresses.toList().filterIsInstance<Inet4Address>().map { it.hostAddress.orEmpty() } }
            .filter { it.isNotEmpty() && it != BeamWifi.HOST_IP }
            .distinct()
    }.getOrDefault(emptyList())

    /** One finished download the sender offers. */
    data class Offer(
        val id: String,
        val uri: String,
        val mimeType: String?,
        val streamKeys: List<IntArray>,     // period, group, stream
        val customCacheKey: String?,
        val data: String,                   // DownloadMeta json
        val bytes: Long,
        val subtitles: Int,                 // number of sidecar subtitle files
    )

    /** A cached item of a download: its cache key, total length, real address, and cached spans (position, length). */
    data class Piece(val key: String, val length: Long, val redirect: String?, val spans: List<LongArray>)
}
