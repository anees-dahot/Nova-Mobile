package com.anees.novamobile.transfer

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.util.UnstableApi
import com.anees.novamobile.MainActivity
import com.anees.novamobile.R
import com.anees.novamobile.download.NovaDownloads
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Sending phone: runs the [BeamServer] (and the Wi-Fi Direct group, when the phone has it) as a
 * foreground service, so the transfer keeps going with the screen off or Nova in the background.
 */
@UnstableApi
class BeamService : Service() {

    /** What the send screen shows. */
    data class State(val invite: Beam.Invite? = null, val band: String = "", val error: String? = null, val sent: Long = 0, val speed: Long = 0, val connected: Boolean = false)

    companion object {
        private const val TAG = "Beam"
        private const val NOTE_ID = 7400
        @Volatile var state = State(); private set
        @Volatile var running = false; private set
        private var server: BeamServer? = null

        private const val RECEIVE = "receive"
        @Volatile var receivingNow = false; private set

        fun start(ctx: Context) { ContextCompat.startForegroundService(ctx, Intent(ctx, BeamService::class.java)) }
        fun stop(ctx: Context) { ctx.stopService(Intent(ctx, BeamService::class.java)) }
        /** Receiving phone: keeps the copy going with the screen off. */
        fun receiving(ctx: Context) { receivingNow = true; ContextCompat.startForegroundService(ctx, Intent(ctx, BeamService::class.java).setAction(RECEIVE)) }
        fun stopReceiving(ctx: Context) { if (receivingNow) { receivingNow = false; ctx.stopService(Intent(ctx, BeamService::class.java)) } }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var locks: BeamWifi.Locks? = null
    private var direct = false
    private var hotspot = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == RECEIVE) {
            ServiceCompat.startForeground(this, NOTE_ID, note("Connecting…", "Receiving from another phone"),
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0)
            if (locks == null) locks = BeamWifi.Locks(this).hold()
            scope.launch {
                var last = 0L; var lastAt = System.currentTimeMillis()
                while (receivingNow) {
                    kotlinx.coroutines.delay(1000)
                    val p = BeamLink.progress
                    val now = System.currentTimeMillis()
                    val sp = (p.done.coerceAtLeast(BeamLink.peer?.bytes?.get() ?: 0) - last) * 1000 / (now - lastAt).coerceAtLeast(1)
                    last = BeamLink.peer?.bytes?.get() ?: last; lastAt = now
                    notify(note("${p.index} of ${p.count} · ${android.text.format.Formatter.formatShortFileSize(this@BeamService, sp.coerceAtLeast(0))}/s", "Receiving from another phone"))
                }
                stopSelf()
            }
            return START_NOT_STICKY
        }
        ServiceCompat.startForeground(this, NOTE_ID, note("Waiting for the other phone…"),
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0)
        if (running) return START_NOT_STICKY
        running = true
        state = State()
        locks = BeamWifi.Locks(this).hold()
        scope.launch {
            try {
                NovaDownloads.manager(this@BeamService)
                val token = BeamWifi.code(12)
                val srv = BeamServer(applicationContext, token)
                val port = kotlinx.coroutines.withContext(Dispatchers.IO) { srv.start() }
                server = srv
                var ssid = ""; var pass = ""; var band = ""
                if (Build.VERSION.SDK_INT >= 29 && BeamPermissions.granted(this@BeamService)) {
                    runCatching { BeamWifi.host(this@BeamService) }
                        .onSuccess { (inv, b) -> ssid = inv.ssid; pass = inv.pass; band = b; direct = true }
                        .onFailure { Log.w(TAG, "wifi direct unavailable: ${it.message}") }
                }
                // No Wi-Fi Direct on this phone: open an app hotspot the other phone can join instead
                var hs = ""; var hp = ""
                if (!direct && BeamPermissions.granted(this@BeamService)) {
                    runCatching { BeamHotspot.host(this@BeamService) }
                        .onSuccess { (n, pw) -> hs = n; hp = pw; hotspot = true; band = "hotspot"; kotlinx.coroutines.delay(800) }
                        .onFailure { Log.w(TAG, "hotspot unavailable: ${it.message}") }
                }
                val ips = Beam.localIps()
                if (!direct && !hotspot && ips.isEmpty()) throw java.io.IOException("Turn on Wi-Fi (or connect both phones to the same Wi-Fi) and try again")
                state = State(Beam.Invite(ssid, pass, port, token, ips, hs, hp), band)
                Log.i(TAG, "sending: direct=$direct band=$band ips=$ips port=$port")
                if (com.anees.novamobile.BuildConfig.DEBUG) Log.d(TAG, "invite: ${state.invite!!.encode()}")
                tick(srv)
            } catch (e: Exception) {
                Log.w(TAG, "can't start sending", e)
                state = state.copy(error = e.message ?: "Couldn't start")
            }
        }
        return START_NOT_STICKY
    }

    /** Live speed for the screen and the notification. */
    private fun tick(srv: BeamServer) {
        scope.launch {
            var last = 0L
            var lastAt = System.currentTimeMillis()
            var smooth = 0.0
            while (running) {
                kotlinx.coroutines.delay(500)
                val now = System.currentTimeMillis()
                val sent = srv.bytesSent.get()
                val inst = (sent - last) * 1000.0 / (now - lastAt).coerceAtLeast(1)
                smooth = if (smooth == 0.0) inst else smooth * 0.6 + inst * 0.4
                last = sent; lastAt = now
                val active = now - srv.lastRequestAt < 3000
                state = state.copy(sent = sent, speed = if (active) smooth.toLong() else 0, connected = state.connected || srv.lastRequestAt > 0)
                if (active) notify(note("Sending · ${android.text.format.Formatter.formatShortFileSize(this@BeamService, smooth.toLong())}/s"))
            }
        }
    }

    private fun notify(n: android.app.Notification) {
        runCatching { androidx.core.app.NotificationManagerCompat.from(this).notify(NOTE_ID, n) }
    }

    private fun note(text: String, title: String = "Sending to another phone") = NotificationCompat.Builder(this, NovaDownloads.CHANNEL)
        .setSmallIcon(R.drawable.ic_download)
        .setContentTitle(title)
        .setContentText(text)
        .setOngoing(true).setOnlyAlertOnce(true).setSilent(true)
        .setContentIntent(PendingIntent.getActivity(this, 7401,
            Intent(this, MainActivity::class.java).putExtra("open", "beam_send").addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        .build()

    override fun onDestroy() {
        running = false
        server?.stop(); server = null
        if (direct && Build.VERSION.SDK_INT >= 29) BeamWifi.teardown()
        if (hotspot) BeamHotspot.stop()
        locks?.release()
        scope.cancel()
        state = State()
        super.onDestroy()
    }
}
