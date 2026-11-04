package com.anees.novamobile.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.provider.Settings
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.anees.novamobile.R

/** Every notification channel in one place, created once at app start. */
object NotifyChannels {
    const val DL_PROGRESS = "nova_downloads"
    const val DL_DONE = "nova_downloads_done"
    const val DL_PROBLEM = "nova_downloads_problem"
    const val NEW_EPISODES = "nova_new_episodes"

    const val GROUP_DONE = "nova.dl.done"
    const val GROUP_EPISODES = "nova.episodes"

    fun create(ctx: Context) {
        if (android.os.Build.VERSION.SDK_INT < 26) return
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannels(listOf(
            NotificationChannel(DL_PROGRESS, ctx.getString(R.string.downloads_channel), NotificationManager.IMPORTANCE_LOW).apply {
                description = "Progress while videos download"; setShowBadge(false)
            },
            NotificationChannel(DL_DONE, "Finished downloads", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "When a download is ready to watch offline"
            },
            NotificationChannel(DL_PROBLEM, "Download problems", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "A download failed or storage is full"
            },
            NotificationChannel(NEW_EPISODES, "New episodes", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "When a new episode of a show you follow comes out"
            },
        ))
    }

    /** System screen for one channel (sound, importance, on/off). */
    fun settingsIntent(ctx: Context, channel: String): Intent =
        Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
            .putExtra(Settings.EXTRA_CHANNEL_ID, channel)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Poster / backdrop for a notification, from Coil's disk cache when possible. */
    suspend fun bitmap(ctx: Context, url: String?, width: Int = 512): Bitmap? {
        if (url.isNullOrBlank()) return null
        val r = ctx.imageLoader.execute(ImageRequest.Builder(ctx).data(url).size(width).allowHardware(false).build())
        return ((r as? SuccessResult)?.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
    }
}
