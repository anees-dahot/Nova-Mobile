package com.anees.novamobile.ui.player

import android.content.Context
import android.util.Log
import android.widget.FrameLayout
import com.anees.novamobile.data.model.MediaCard
import com.anees.novamobile.data.repository.MediaRepository
import kotlinx.coroutines.*

/**
 * Instant Play: finds the stream link *before* Play is pressed (title page open, Continue
 * Watching card focused, last episode minutes). The player then only has to start the video.
 *
 * One search at a time. A search the player is waiting for ("claimed") is never cancelled.
 */
object Prefetcher {
    private const val TAG = "NovaTV"
    private val scope = MainScope()
    private val repo = MediaRepository()
    private var job: Job? = null
    private var pendingKey: String? = null
    private var pending: Deferred<WebStreamSniffer.Sniffed?>? = null
    private var claimed = false
    private var lastRequest: String? = null

    /** Host view (behind opaque UI) for the hidden WebView; set by MainActivity / PlayerActivity. */
    var host: FrameLayout? = null

    fun request(ctx: Context, card: MediaCard, season: Int, episode: Int, delayMs: Long = 0, hostOverride: FrameLayout? = null) {
        val req = "${card.type}:${card.id}:$season:$episode"
        if (req == lastRequest && (job?.isActive == true || pending?.isActive == true)) return
        cancel()
        lastRequest = req
        val appCtx = ctx
        job = scope.launch {
            delay(delayMs)
            val target = StreamExtractor.resolveTarget(card, season, episode, repo) ?: return@launch
            // Embed pages only: a resolver source (HTTP search) isn't something a WebView can prefetch
            val p = StreamExtractor.ordered().firstOrNull { it.isEmbed } ?: return@launch
            if (StreamExtractor.cached(p, target) != null) return@launch
            val h = hostOverride ?: host ?: return@launch
            val key = StreamExtractor.embedUrl(p, target)
            val t0 = System.currentTimeMillis()
            Log.i(TAG, "prefetch ${card.title} via ${p.name}")
            val d = async { WebStreamSniffer(appCtx, h).sniff(key, p.timeoutMs) }
            pendingKey = key; pending = d; claimed = false
            val r = runCatching { d.await() }.getOrNull()
            if (r != null) {
                StreamExtractor.remember(p, target, r)
                com.anees.novamobile.data.local.LocalStore.setProviderSpeed(p.name, System.currentTimeMillis() - t0)
                Log.i(TAG, "prefetch ready in ${System.currentTimeMillis() - t0}ms")
            }
            if (pendingKey == key) { pendingKey = null; pending = null; claimed = false }
        }
    }

    /** The player takes over a search that's already running for exactly this source + episode. */
    fun claim(embedUrl: String): Deferred<WebStreamSniffer.Sniffed?>? =
        if (pendingKey == embedUrl && pending?.isActive == true) { claimed = true; pending } else null

    /** Player opened: drop a prefetch that hasn't started searching yet (a running one may be claimed). */
    fun cancelWaiting() {
        if (pending == null) { job?.cancel(); job = null; lastRequest = null }
    }

    fun cancel() {
        if (claimed) return
        job?.cancel(); pending?.cancel()
        job = null; pending = null; pendingKey = null; lastRequest = null
    }
}
