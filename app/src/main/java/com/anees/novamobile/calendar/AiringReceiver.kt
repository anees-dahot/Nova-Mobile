package com.anees.novamobile.calendar

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.anees.novamobile.MainActivity
import com.anees.novamobile.R
import com.anees.novamobile.data.local.LocalStore
import com.anees.novamobile.notify.NotifyChannels
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** New-episode alerts, the daily rebuild of reminders, and re-scheduling after a reboot. */
class AiringReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_EPISODE = "com.anees.novamobile.AIRING_EPISODE"
        const val ACTION_DAILY = "com.anees.novamobile.AIRING_DAILY"
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

    /** "S2 · E5 is out" with the poster; Play and Download right from the notification. */
    private fun notify(ctx: Context, i: Intent) {
        val s = i.getIntExtra("season", 1); val e = i.getIntExtra("episode", 1)
        val cardJson = i.getStringExtra("card")
        val title = i.getStringExtra("title") ?: return
        val nid = "$s:$e:$title".hashCode()
        val done = goAsync()
        CoroutineScope(Dispatchers.Main).launch {
            try {
                val card = cardJson?.let { runCatching { com.google.gson.Gson().fromJson(it, com.anees.novamobile.data.model.MediaCard::class.java) }.getOrNull() }
                val open = PendingIntent.getActivity(ctx, nid,
                    Intent(ctx, MainActivity::class.java).putExtra("play_card", cardJson).putExtra("play_s", s).putExtra("play_e", e)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                val b = NotificationCompat.Builder(ctx, NotifyChannels.NEW_EPISODES)
                    .setSmallIcon(R.drawable.ic_calendar)
                    .setContentTitle(title)
                    .setContentText(listOfNotNull("S$s · E$e is out", i.getStringExtra("name")?.takeIf { it.isNotBlank() }).joinToString("  ·  "))
                    .setContentIntent(open).setAutoCancel(true)
                    .setGroup(NotifyChannels.GROUP_EPISODES)
                    .setColor(com.anees.novamobile.ui.common.Ui.ACCENT)
                    .addAction(R.drawable.ic_play, "Play", open)
                if (card != null) {
                    NotifyChannels.bitmap(ctx, card.posterUrl, 256)?.let { b.setLargeIcon(it) }
                    val dl = PendingIntent.getBroadcast(ctx, nid + 1,
                        Intent(ctx, com.anees.novamobile.download.DownloadActionReceiver::class.java)
                            .setAction(com.anees.novamobile.download.DownloadActionReceiver.EPISODE)
                            .putExtra("card", cardJson).putExtra("season", s).putExtra("episode", e).putExtra("nid", nid),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                    b.addAction(R.drawable.ic_download, "Download", dl)
                    // Smart Downloads: new episodes of shows you follow download on their own
                    if (LocalStore.settings().smartDownloads) com.anees.novamobile.download.DownloadActionReceiver.queueEpisode(ctx, card, s, e)
                }
                runCatching { NotificationManagerCompat.from(ctx).notify(nid, b.build()) }
            } finally { done.finish() }
        }
    }
}
