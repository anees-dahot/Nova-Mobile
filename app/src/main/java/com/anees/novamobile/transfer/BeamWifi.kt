package com.anees.novamobile.transfer

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Looper
import android.os.PowerManager
import androidx.annotation.RequiresApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.security.SecureRandom
import kotlin.coroutines.resume

/**
 * Wi-Fi Direct link between the two phones. The receiver hosts a private group (5 GHz when the chip
 * allows) with a random name + password; the sender joins it straight from the QR, so neither phone
 * shows an "accept invitation" dialog and no router or internet is involved.
 */
@SuppressLint("MissingPermission")
@RequiresApi(29)
object BeamWifi {
    /** Group owner address is fixed by Android's Wi-Fi Direct stack. */
    const val HOST_IP = "192.168.49.1"

    private var mgr: WifiP2pManager? = null
    private var ch: WifiP2pManager.Channel? = null

    private fun channel(ctx: Context): Pair<WifiP2pManager, WifiP2pManager.Channel> {
        val m = mgr ?: (ctx.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
            ?: throw IOException("This phone doesn't support Wi-Fi Direct")).also { mgr = it }
        val c = ch ?: (m.initialize(ctx.applicationContext, Looper.getMainLooper()) { ch = null }
            ?: throw IOException("Wi-Fi Direct is unavailable — turn Wi-Fi on")).also { ch = it }
        return m to c
    }

    /** Starts the group and returns the invite to show as a QR, plus the band it landed on. */
    suspend fun host(ctx: Context): Pair<Beam.Invite, String> {
        val (m, c) = channel(ctx)
        action { m.removeGroup(c, it) }
        val ssid = "DIRECT-Nv-" + code(6)
        val pass = code(8)
        for (band in intArrayOf(WifiP2pConfig.GROUP_OWNER_BAND_5GHZ, WifiP2pConfig.GROUP_OWNER_BAND_AUTO)) {
            val cfg = WifiP2pConfig.Builder().setNetworkName(ssid).setPassphrase(pass)
                .setGroupOperatingBand(band).enablePersistentMode(false).build()
            var err: Int? = null
            repeat(3) { attempt ->
                err = action { m.createGroup(c, cfg, it) }
                if (err != WifiP2pManager.BUSY) return@repeat
                delay(800L * (attempt + 1))
            }
            if (err != null) continue
            val g = withTimeoutOrNull(10_000) { while (true) { group(m, c)?.let { return@withTimeoutOrNull it }; delay(300) }; null } ?: continue
            return Beam.Invite(g.networkName ?: ssid, g.passphrase ?: pass, Beam.PORT) to band(g)
        }
        throw IOException("Couldn't start Wi-Fi Direct — make sure Wi-Fi is on and hotspot is off")
    }

    /** Joins the receiver's group; returns once the link is up. */
    suspend fun join(ctx: Context, inv: Beam.Invite) {
        val (m, c) = channel(ctx)
        action { m.cancelConnect(c, it) }
        action { m.removeGroup(c, it) }
        val cfg = WifiP2pConfig.Builder().setNetworkName(inv.ssid).setPassphrase(inv.pass)
            .enablePersistentMode(false).build()
        var err: Int? = null
        for (attempt in 0 until 4) {
            err = action { m.connect(c, cfg, it) }
            if (err != WifiP2pManager.BUSY) break
            delay(1_000L * (attempt + 1))
        }
        if (err != null) throw IOException("Couldn't reach the other phone (code $err) — keep both phones close with Wi-Fi on")
        val ok = withTimeoutOrNull(40_000) {
            while (true) { if (info(m, c)?.groupFormed == true) return@withTimeoutOrNull true; delay(400) }
            @Suppress("UNREACHABLE_CODE") false
        }
        if (ok != true) throw IOException("The other phone didn't answer — check its QR is still showing")
    }

    fun teardown() {
        val m = mgr ?: return
        val c = ch ?: return
        runCatching { m.cancelConnect(c, null) }
        runCatching { m.removeGroup(c, null) }
    }

    private fun band(g: WifiP2pGroup) = if (g.frequency >= 4900) "5 GHz" else if (g.frequency > 0) "2.4 GHz" else ""

    private suspend fun action(block: (WifiP2pManager.ActionListener) -> Unit): Int? = suspendCancellableCoroutine { k ->
        runCatching {
            block(object : WifiP2pManager.ActionListener {
                override fun onSuccess() { if (k.isActive) k.resume(null) }
                override fun onFailure(reason: Int) { if (k.isActive) k.resume(reason) }
            })
        }.onFailure { if (k.isActive) k.resume(WifiP2pManager.ERROR) }
    }

    private suspend fun group(m: WifiP2pManager, c: WifiP2pManager.Channel): WifiP2pGroup? =
        suspendCancellableCoroutine { k -> m.requestGroupInfo(c) { if (k.isActive) k.resume(it) } }

    private suspend fun info(m: WifiP2pManager, c: WifiP2pManager.Channel): WifiP2pInfo? =
        suspendCancellableCoroutine { k -> m.requestConnectionInfo(c) { if (k.isActive) k.resume(it) } }

    private val rnd = SecureRandom()
    private const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"   // no 0/O/1/I, easy to type
    fun code(n: Int) = String(CharArray(n) { ALPHABET[rnd.nextInt(ALPHABET.length)] })

    /** Keeps Wi-Fi at full speed and the CPU awake while bytes move (screen may be off). */
    class Locks(ctx: Context) {
        private val wifi = (ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "nova:beam").apply { setReferenceCounted(false) }
        private val cpu = (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "nova:beam").apply { setReferenceCounted(false) }
        fun hold() = apply { runCatching { wifi.acquire() }; runCatching { cpu.acquire(6 * 60 * 60 * 1000L) } }
        fun release() { runCatching { if (wifi.isHeld) wifi.release() }; runCatching { if (cpu.isHeld) cpu.release() } }
    }

    fun supported() = Build.VERSION.SDK_INT >= 29
}
