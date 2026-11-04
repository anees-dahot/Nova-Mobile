package com.anees.novamobile.download

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*

/**
 * Foreground service while "Save to phone" works, so Android doesn't freeze or kill the app when it's
 * left mid-save (a plain thread in a backgrounded app stops within seconds). Stops when the queue is empty.
 */
class ExportService : Service() {
    private val scope = MainScope()

    companion object {
        fun start(ctx: Context) {
            runCatching { ContextCompat.startForegroundService(ctx, Intent(ctx, ExportService::class.java)) }
                .onFailure { Log.w("PublicExport", "save service not started (app in background): ${it.message}") }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val ok = runCatching {
            ServiceCompat.startForeground(this, SaveProgress.ID, SaveProgress.build(this, "Starting…", -1),
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0)
        }.isSuccess
        if (!ok) { stopSelf(); return START_NOT_STICKY }
        scope.coroutineContext.cancelChildren()
        scope.launch {
            delay(1_000)
            while (isActive && PublicExport.busy) delay(1_000)
            ServiceCompat.stopForeground(this@ExportService, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
