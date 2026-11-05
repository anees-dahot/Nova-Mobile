package com.anees.novamobile.transfer

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.RequiresApi
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import kotlin.coroutines.resume

/**
 * Fallback link when Wi-Fi Direct isn't available: the sender opens an app-only hotspot (no internet
 * sharing, Android picks the band), the receiver joins it from the QR.
 */
@SuppressLint("MissingPermission")
object BeamHotspot {
    private const val TAG = "Beam"

    // ── Sender ───────────────────────────────────────────────────────────────

    private var reservation: WifiManager.LocalOnlyHotspotReservation? = null

    /** Starts the hotspot; returns its name and password. */
    suspend fun host(ctx: Context): Pair<String, String> {
        stop()
        val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val r = withTimeoutOrNull(15_000) {
            suspendCancellableCoroutine<WifiManager.LocalOnlyHotspotReservation?> { k ->
                runCatching {
                    wm.startLocalOnlyHotspot(object : WifiManager.LocalOnlyHotspotCallback() {
                        override fun onStarted(res: WifiManager.LocalOnlyHotspotReservation) { if (k.isActive) k.resume(res) else res.close() }
                        override fun onFailed(reason: Int) { Log.w(TAG, "hotspot failed: $reason"); if (k.isActive) k.resume(null) }
                        override fun onStopped() { reservation = null }
                    }, Handler(Looper.getMainLooper()))
                }.onFailure { Log.w(TAG, "hotspot not allowed: ${it.message}"); if (k.isActive) k.resume(null) }
            }
        } ?: throw IOException("Couldn't open a hotspot — turn off your own hotspot and try again")
        reservation = r
        val (ssid, pass) = if (Build.VERSION.SDK_INT >= 30) {
            val c = r.softApConfiguration
            @Suppress("DEPRECATION")
            val name = if (Build.VERSION.SDK_INT >= 33) c.wifiSsid?.toString()?.trim('"') else c.ssid
            name.orEmpty() to c.passphrase.orEmpty()
        } else {
            @Suppress("DEPRECATION")
            val c = r.wifiConfiguration
            @Suppress("DEPRECATION")
            c?.SSID.orEmpty().trim('"') to c?.preSharedKey.orEmpty().trim('"')
        }
        if (ssid.isEmpty()) { stop(); throw IOException("The hotspot didn't report its name") }
        return ssid to pass
    }

    fun stop() { runCatching { reservation?.close() }; reservation = null }

    // ── Receiver ─────────────────────────────────────────────────────────────

    private var callback: ConnectivityManager.NetworkCallback? = null

    /** Joins the sender's hotspot (Android shows a one-tap "connect" prompt). Traffic must be bound to the returned network. */
    @RequiresApi(29)
    suspend fun join(ctx: Context, ssid: String, pass: String): Network {
        release(ctx)
        val cm = ctx.applicationContext.getSystemService(ConnectivityManager::class.java)
        val spec = WifiNetworkSpecifier.Builder().setSsid(ssid).apply { if (pass.isNotEmpty()) setWpa2Passphrase(pass) }.build()
        val req = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(spec)
            .build()
        return withTimeoutOrNull(60_000) {
            suspendCancellableCoroutine<Network?> { k ->
                val cb = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(n: Network) { if (k.isActive) k.resume(n) }
                    override fun onUnavailable() { if (k.isActive) k.resume(null) }
                }
                callback = cb
                runCatching { cm.requestNetwork(req, cb) }.onFailure { if (k.isActive) k.resume(null) }
            }
        } ?: run { release(ctx); throw IOException("Couldn't join the other phone's hotspot") }
    }

    /** Leaves the hotspot (drops the request, Android reconnects the usual Wi-Fi). */
    fun release(ctx: Context) {
        val cb = callback ?: return
        callback = null
        runCatching { ctx.applicationContext.getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(cb) }
    }
}
