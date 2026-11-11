package com.anees.novamobile.calendar

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.anees.novamobile.MainActivity
import com.anees.novamobile.R
import com.anees.novamobile.data.local.LocalStore
import com.anees.novamobile.notify.NotifyChannels
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** New-episode alerts, the daily rebuild of reminders, and re-scheduling after reboot / update / clock change. */
class AiringReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "NovaCalendar"
        const val ACTION_EPISODE = "com.anees.novamobile.AIRING_EPISODE"
        const val ACTION_DAILY = "com.anees.novamobile.AIRING_DAILY"
        private const val EXACT_CHANGED = "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED"

        /** Why alerts can't show right now, or null when everything is on. */
        fun blocker(ctx: Context): Blocker? {
            if (!LocalStore.settings().newEpisodeAlerts) return Blocker.SETTING_OFF
            if (android.os.Build.VERSION.SDK_INT >= 33 &&
                ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return Blocker.PERMISSION
            if (!NotificationManagerCompat.from(ctx).areNotificationsEnabled()) return Blocker.APP_OFF
            val ch = ctx.getSystemService(NotificationManager::class.java)?.getNotificationChannel(NotifyChannels.NEW_EPISODES)
            if (ch != null && ch.importance == NotificationManager.IMPORTANCE_NONE) return Blocker.CHANNEL_OFF
            return null
        }
    }

    enum class Blocker { SETTING_OFF, PERMISSION, APP_OFF, CHANNEL_OFF }

    override fun onReceive(ctx: Context, intent: Intent) {
        LocalStore.init(ctx)
        when (intent.action) {
            ACTION_EPISODE -> notify(ctx, intent)
            ACTION_DAILY, Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED, EXACT_CHANGED -> {
                val done = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try { AiringCalendar.reschedule(ctx.applicationContext) }
                    catch (t: Throwable) { Log.w(TAG, "reschedule failed", t) }
                    finally { done.finish() }
                }
            }
        }
    }

    /** "S2 · E5 is out" with the poster; Play and Download right from the notification. */
    private fun notify(ctx: Context, i: Intent) {
        val s = i.getIntExtra("season", 1); val e = i.getIntExtra("episode", 1)
        val cardJson = i.getStringExtra("card")
        val title = i.getStringExtra("title") ?: return
        val key = i.getStringExtra("key")
        val test = i.getBooleanExtra("test", false)
        if (!test && key != null && key in LocalStore.notifiedEpisodes()) return     // alarm + catch-up: show once
        val nid = (key ?: "$s:$e:$title").hashCode()
        val done = goAsync()
        CoroutineScope(Dispatchers.Main).launch {
            try {
                val card = cardJson?.let { runCatching { com.google.gson.Gson().fromJson(it, com.anees.novamobile.data.model.MediaCard::class.java) }.getOrNull() }
                val open = PendingIntent.getActivity(ctx, nid,
                    Intent(ctx, MainActivity::class.java).putExtra("play_card", cardJson).putExtra("play_s", s).putExtra("play_e", e)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                val name = i.getStringExtra("name")?.takeIf { it.isNotBlank() && !it.matches(Regex("(?i)episode \\d+")) }
                val b = NotificationCompat.Builder(ctx, NotifyChannels.NEW_EPISODES)
                    .setSmallIcon(R.drawable.ic_calendar)
                    .setContentTitle(title)
                    .setContentText(listOfNotNull(if (test) "Test alert — new episodes will look like this" else "S$s · E$e is out", name.takeIf { !test }).joinToString("  ·  "))
                    .setContentIntent(open).setAutoCancel(true)
                    .setCategory(NotificationCompat.CATEGORY_EVENT)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setGroup(NotifyChannels.GROUP_EPISODES)
                    .setColor(com.anees.novamobile.ui.common.Ui.ACCENT)
                    .addAction(R.drawable.ic_play, "Play", open)
                if (card != null && card.id != 0) {
                    // Poster: never let a slow network hold the broadcast (and the alert) hostage
                    withTimeoutOrNull(6_000) { runCatching { NotifyChannels.bitmap(ctx, card.posterUrl, 256) }.getOrNull() }?.let { b.setLargeIcon(it) }
                    val dl = PendingIntent.getBroadcast(ctx, nid + 1,
                        Intent(ctx, com.anees.novamobile.download.DownloadActionReceiver::class.java)
                            .setAction(com.anees.novamobile.download.DownloadActionReceiver.EPISODE)
                            .putExtra("card", cardJson).putExtra("season", s).putExtra("episode", e).putExtra("nid", nid),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                    b.addAction(R.drawable.ic_download, "Download", dl)
                    // Smart Downloads: new episodes of shows you follow download on their own
                    if (!test && LocalStore.settings().smartDownloads) com.anees.novamobile.download.DownloadActionReceiver.queueEpisode(ctx, card, s, e)
                }
                val why = blocker(ctx)?.takeIf { it != Blocker.SETTING_OFF || !test }
                if (why != null) Log.w(TAG, "alert for $title S$s E$e not shown: $why")
                else {
                    try { NotificationManagerCompat.from(ctx).notify(nid, b.build()) }
                    catch (se: SecurityException) { Log.w(TAG, "notify denied", se) }
                }
                if (!test && key != null) LocalStore.markNotified(key)
            } finally { done.finish() }
        }
    }
}
