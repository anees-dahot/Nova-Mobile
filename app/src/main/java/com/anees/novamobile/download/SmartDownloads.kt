package com.anees.novamobile.download

import android.content.Context
import android.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadService
import com.anees.novamobile.data.local.LocalStore
import com.anees.novamobile.data.model.MediaCard
import com.anees.novamobile.data.model.MediaType
import com.anees.novamobile.data.repository.MediaRepository
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

/**
 * Netflix-style smart downloads: finishing a downloaded episode deletes it ("Delete After Watching"
 * or Smart Downloads) and, with Smart Downloads on, queues the next episodes so [Settings.keepNext]
 * are always ready offline.
 */
@UnstableApi
object SmartDownloads {
    private const val TAG = "NovaDL"
    private val scope = MainScope()
    private val repo = MediaRepository()
    private val handled = mutableSetOf<String>()

    fun onWatched(ctx: Context, card: MediaCard, season: Int, episode: Int) {
        val id = DownloadMeta.id(card, season, episode)
        if (!handled.add(id)) return
        val s = LocalStore.settings()
        val watchedMeta = NovaDownloads.get(ctx, id)?.let { DownloadMeta.of(it) }
        if (s.dlAutoDeleteWatched || s.smartDownloads) {
            DownloadService.sendRemoveDownload(ctx, NovaDownloadService::class.java, id, false)
            NovaDownloads.get(ctx, id)?.let { NovaDownloads.deleteSubtitles(it) }
        }
        if (!s.smartDownloads || card.type == MediaType.MOVIE) return
        val app = ctx.applicationContext
        scope.launch {
            val keep = s.keepNext.coerceIn(1, 3)
            // With "Skip Anime Filler" on, filler/recap episodes don't take a slot
            val filler = if (s.skipFiller) com.anees.novamobile.data.sources.anime.FillerInfo.forShow(card) else null
            val next = upcoming(card, season, episode, if (filler != null) keep + 12 else keep)
                .filter { (ns, ne) -> filler?.at(ns, ne)?.kind?.skippable != true }
                .take(keep)
                .filter { (ns, ne) -> NovaDownloads.get(app, DownloadMeta.id(card, ns, ne))?.state.let { it == null || it == Download.STATE_FAILED } }
            if (next.isEmpty()) return@launch
            Log.i(TAG, "smart downloads: queueing ${next.size} after S${season}E$episode")
            // Same source and quality as the episode you just watched
            DownloadStarter.enqueue(app, next.map { DownloadStarter.Item(card, it.first, it.second, provider = watchedMeta?.provider, height = watchedMeta?.quality) })
        }
    }

    /** The next [n] episodes that exist (crosses into the next season for series). */
    suspend fun upcoming(card: MediaCard, season: Int, episode: Int, n: Int): List<Pair<Int, Int>> {
        if (card.type == MediaType.ANIME) {
            val total = card.episodes ?: Int.MAX_VALUE
            return (episode + 1..episode + n).filter { it <= total }.map { season to it }
        }
        val out = mutableListOf<Pair<Int, Int>>()
        var s = season
        var e = episode
        var eps = runCatching { repo.getSeasonEpisodes(card.id, s) }.getOrDefault(emptyList()).map { it.episodeNumber }
        while (out.size < n) {
            val nextE = eps.filter { it > e }.minOrNull()
            if (nextE != null) { out += s to nextE; e = nextE; continue }
            s++; e = 0
            eps = runCatching { repo.getSeasonEpisodes(card.id, s) }.getOrDefault(emptyList()).map { it.episodeNumber }
            if (eps.isEmpty()) break
        }
        return out
    }
}
