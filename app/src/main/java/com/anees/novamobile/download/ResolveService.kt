package com.anees.novamobile.download

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.media3.common.util.UnstableApi
import com.anees.novamobile.MainActivity
import com.anees.novamobile.R
import com.anees.novamobile.data.local.LocalStore
import com.anees.novamobile.notify.NotifyChannels
import kotlinx.coroutines.*

/**
 * Keeps the app alive while queued downloads are still being matched to a stream, so a season
 * queued just before leaving the app keeps going. Stops itself when nothing is left to resolve.
 */
@UnstableApi
class ResolveService : Service() {
    private val scope = MainScope()

    companion object {
        private const val ID = 1002

        /** Call while the app is still visible (Android won't allow starting it from the background). */
        fun startIfNeeded(ctx: Context) {
            LocalStore.init(ctx)
            if (LocalStore.pendingDownloads().isEmpty()) return
            runCatching { androidx.core.content.ContextCompat.startForegroundService(ctx, Intent(ctx, ResolveService::class.java)) }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        LocalStore.init(this)
        val n = LocalStore.pendingDownloads().size
        val open = PendingIntent.getActivity(this, 3, Intent(this, MainActivity::class.java).putExtra("open", "downloads"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, NotifyChannels.DL_PROGRESS)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle("Preparing downloads")
            .setContentText(if (n == 1) "Finding a source…" else "Finding sources for $n videos…")
            .setProgress(0, 0, true)
            .setOngoing(true).setSilent(true)
            .setContentIntent(open)
            .build()
        val ok = runCatching {
            ServiceCompat.startForeground(this, ID, notification,
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0)
        }.isSuccess
        if (!ok || n == 0) { stopSelf(); return START_NOT_STICKY }
        DownloadStarter.resume(this)
        scope.coroutineContext.cancelChildren()
        scope.launch {
            delay(3_000)
            // Done, or only items left that need the app's WebView: stop (they continue when the app opens)
            while (isActive && DownloadStarter.busy) delay(2_000)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
