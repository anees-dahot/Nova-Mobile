package com.anees.novamobile.download

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadService
import com.anees.novamobile.MainActivity
import com.anees.novamobile.R
import com.anees.novamobile.data.local.LocalStore
import com.anees.novamobile.data.model.MediaCard
import com.anees.novamobile.data.model.MediaType
import com.anees.novamobile.notify.NotifyChannels
import com.anees.novamobile.ui.player.PlayerActivity
import com.google.gson.Gson
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/** Download notifications: ready to watch (grouped, Play / Delete), problems (Retry), storage full. */
@UnstableApi
object DownloadNotifier {
    private val scope = MainScope()
    private const val SUMMARY_ID = 4000
    /** Posters for the progress notification, loaded once per title (it's built synchronously). */
    val posters = ConcurrentHashMap<String, Bitmap>()

    fun name(m: DownloadMeta) = if (m.card.type == MediaType.MOVIE || m.season <= 0) m.card.title else "${m.card.title} · S${m.season} E${m.episode}"

    fun poster(ctx: Context, card: MediaCard): Bitmap? {
        val url = card.posterUrl ?: card.backdropUrl ?: return null
        posters[url]?.let { return it }
        scope.launch { NotifyChannels.bitmap(ctx, url, 256)?.let { posters[url] = it } }
        return null
    }

    private fun openDownloads(ctx: Context, code: Int) = PendingIntent.getActivity(ctx, code,
        Intent(ctx, MainActivity::class.java).putExtra("open", "downloads").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun action(ctx: Context, what: String, id: String, code: Int) = PendingIntent.getBroadcast(ctx, code,
        Intent(ctx, DownloadActionReceiver::class.java).setAction(what).putExtra("id", id),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    fun done(ctx: Context, d: Download) {
        if (!LocalStore.settings().notifyDownloadDone) return
        val meta = DownloadMeta.of(d) ?: return
        val id = d.request.id
        val nid = id.hashCode()
        scope.launch {
            val backdrop = NotifyChannels.bitmap(ctx, meta.card.backdropUrl, 780)
            val poster = NotifyChannels.bitmap(ctx, meta.card.posterUrl, 256)
            val play = PendingIntent.getActivity(ctx, nid, PlayerActivity.intent(ctx, meta.card, meta.season, meta.episode),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val detail = listOfNotNull(meta.quality?.let { "${it}p" }, DlStats.size(ctx, d.bytesDownloaded)).joinToString("  ·  ")
            val b = NotificationCompat.Builder(ctx, NotifyChannels.DL_DONE)
                .setSmallIcon(R.drawable.ic_download)
                .setContentTitle(name(meta))
                .setContentText("Ready to watch offline  ·  $detail")
                .setLargeIcon(poster)
                .setContentIntent(if (meta.card.type == MediaType.MOVIE) play else PendingIntent.getActivity(ctx, nid + 2,
                    Intent(ctx, MainActivity::class.java).putExtra("open", "downloads_show").putExtra("card", Gson().toJson(meta.card))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
                .setAutoCancel(true)
                .setGroup(NotifyChannels.GROUP_DONE)
                .setColor(0xFF0A84FF.toInt())
                .addAction(R.drawable.ic_play, "Play", play)
                .addAction(R.drawable.ic_delete, "Delete", action(ctx, DownloadActionReceiver.DELETE, id, nid + 1))
            if (backdrop != null) b.setStyle(NotificationCompat.BigPictureStyle().bigPicture(backdrop).bigLargeIcon(null as Bitmap?))
            val nm = NotificationManagerCompat.from(ctx)
            runCatching { nm.notify(nid, b.build()) }
            // Several finished: Android stacks them under one "N downloads ready"
            runCatching {
                nm.notify(SUMMARY_ID, NotificationCompat.Builder(ctx, NotifyChannels.DL_DONE)
                    .setSmallIcon(R.drawable.ic_download)
                    .setContentTitle("Downloads ready")
                    .setGroup(NotifyChannels.GROUP_DONE).setGroupSummary(true)
                    .setContentIntent(openDownloads(ctx, SUMMARY_ID))
                    .setAutoCancel(true).build())
            }
        }
    }

    /** A download gave up (or no source had it): tap Retry right from the notification. */
    fun failed(ctx: Context, id: String, title: String, reason: String) {
        if (!LocalStore.settings().notifyDownloadProblem) return
        val nid = ("fail:$id").hashCode()
        val n = NotificationCompat.Builder(ctx, NotifyChannels.DL_PROBLEM)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle("Couldn't download $title")
            .setContentText(reason)
            .setContentIntent(openDownloads(ctx, nid))
            .setAutoCancel(true)
            .setColor(0xFFFF453A.toInt())
            .addAction(R.drawable.ic_retry, "Retry", action(ctx, DownloadActionReceiver.RETRY, id, nid + 1))
            .build()
        runCatching { NotificationManagerCompat.from(ctx).notify(nid, n) }
    }

    fun storageFull(ctx: Context) {
        if (!LocalStore.settings().notifyDownloadProblem) return
        val n = NotificationCompat.Builder(ctx, NotifyChannels.DL_PROBLEM)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle("Storage full — downloads paused")
            .setContentText("Delete something you've watched, then resume")
            .setContentIntent(openDownloads(ctx, 4001))
            .setAutoCancel(true)
            .addAction(R.drawable.ic_download, "Manage downloads", openDownloads(ctx, 4002))
            .build()
        runCatching { NotificationManagerCompat.from(ctx).notify(4001, n) }
    }

    fun cancel(ctx: Context, nid: Int) = NotificationManagerCompat.from(ctx).cancel(nid)
}

/** Notification buttons: Retry a failed / unresolved download, Delete a finished one. */
@UnstableApi
class DownloadActionReceiver : BroadcastReceiver() {
    companion object {
        const val RETRY = "com.anees.novamobile.DL_RETRY"
        const val DELETE = "com.anees.novamobile.DL_DELETE"
        const val EPISODE = "com.anees.novamobile.DL_EPISODE"

        @androidx.annotation.OptIn(UnstableApi::class)
        fun queueEpisode(ctx: Context, card: MediaCard, season: Int, episode: Int) {
            DownloadStarter.enqueue(ctx, listOf(DownloadStarter.Item(card, season, episode)))
        }
    }

    override fun onReceive(ctx: Context, i: Intent) {
        LocalStore.init(ctx)
        val id = i.getStringExtra("id") ?: ""
        when (i.action) {
            RETRY -> {
                DownloadNotifier.cancel(ctx, ("fail:$id").hashCode())
                val d = NovaDownloads.get(ctx, id)
                // From a notification the app may be in the background: start the service in the foreground
                if (d != null) { LocalStore.setDlAttempts(id, 0); runCatching { DownloadService.sendAddDownload(ctx, NovaDownloadService::class.java, d.request, true) } }
                else LocalStore.unresolved().firstOrNull { DownloadMeta.id(it.card, it.season, it.episode) == id }?.let { item ->
                    LocalStore.saveUnresolved(LocalStore.unresolved() - item)
                    DownloadStarter.enqueue(ctx, listOf(item))
                }
            }
            EPISODE -> {
                val card = cardFromJson(i.getStringExtra("card")) ?: return
                DownloadNotifier.cancel(ctx, i.getIntExtra("nid", 0))
                queueEpisode(ctx, card, i.getIntExtra("season", 1), i.getIntExtra("episode", 1))
                android.widget.Toast.makeText(ctx, "Downloading ${card.title} · S${i.getIntExtra("season", 1)} E${i.getIntExtra("episode", 1)}", android.widget.Toast.LENGTH_SHORT).show()
            }
            DELETE -> {
                DownloadNotifier.cancel(ctx, id.hashCode())
                NovaDownloads.get(ctx, id)?.let { NovaDownloads.deleteSubtitles(it) }
                NovaDownloads.manager(ctx).removeDownload(id)
            }
        }
    }
}

/** Gson for the card extra the new-episode notification carries. */
internal fun cardFromJson(json: String?): MediaCard? = json?.let { runCatching { Gson().fromJson(it, MediaCard::class.java) }.getOrNull() }
