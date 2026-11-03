package com.anees.novamobile.calendar

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.anees.novamobile.MainActivity
import com.anees.novamobile.R
import com.anees.novamobile.data.local.LocalStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** New-episode alerts, the daily rebuild of reminders, and re-scheduling after a reboot. */
class AiringReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_EPISODE = "com.anees.novamobile.AIRING_EPISODE"
        const val ACTION_DAILY = "com.anees.novamobile.AIRING_DAILY"
        private const val CHANNEL = "nova_new_episodes"
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        LocalStore.init(ctx)
        when (intent.action) {
            ACTION_EPISODE -> notify(ctx, intent)
            ACTION_DAILY, Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                val done = goAsync()
                CoroutineScope(Dispatchers.IO).launch { runCatching { AiringCalendar.reschedule(ctx) }; done.finish() }
            }
        }
    }

    private fun notify(ctx: Context, i: Intent) {
        val nm = NotificationManagerCompat.from(ctx)
        if (android.os.Build.VERSION.SDK_INT >= 26)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "New episodes", NotificationManager.IMPORTANCE_DEFAULT))
        val s = i.getIntExtra("season", 1); val e = i.getIntExtra("episode", 1)
        val open = PendingIntent.getActivity(ctx, (i.getStringExtra("card") ?: "").hashCode(),
            Intent(ctx, MainActivity::class.java).putExtra("play_card", i.getStringExtra("card")).putExtra("play_s", s).putExtra("play_e", e)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_calendar)
            .setContentTitle(i.getStringExtra("title"))
            .setContentText("S$s · E$e is out — tap to play")
            .setContentIntent(open).setAutoCancel(true).build()
        runCatching { nm.notify("$s:$e:${i.getStringExtra("title")}".hashCode(), n) }
    }
}
