package com.anees.novamobile.ui.player

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.*
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import coil.load
import com.anees.novamobile.R
import com.anees.novamobile.data.local.LocalStore
import com.anees.novamobile.data.local.Settings
import com.anees.novamobile.data.model.HistoryEntry
import com.anees.novamobile.data.model.MediaCard
import com.anees.novamobile.data.model.MediaType
import com.anees.novamobile.data.repository.MediaRepository
import com.anees.novamobile.ui.common.ActionSheet
import com.google.gson.Gson
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import okhttp3.Dns
import okhttp3.OkHttpClient
import java.net.Inet6Address
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

@UnstableApi
class PlayerActivity : AppCompatActivity() {

    // ── Views ─────────────────────────────────────────────────────────────────
    private lateinit var root: FrameLayout
    private lateinit var playerView: PlayerView
    private lateinit var buffering: ProgressBar
    private lateinit var centerIcon: TextView
    private lateinit var overlay: View
    private lateinit var overlayTitle: TextView
    private lateinit var overlaySub: TextView
    private lateinit var overlayDesc: TextView
    private lateinit var seekBar: ProgressBar
    private lateinit var timeLeft: TextView
    private lateinit var timeRight: TextView
    private lateinit var loadingLayer: View
    private lateinit var statusText: TextView
    private lateinit var loadingBar: ProgressBar
    private lateinit var retryBtn: Button
    private lateinit var skipBtn: Button
    private lateinit var nextBtn: Button
    private lateinit var topBar: View

    // ── State ─────────────────────────────────────────────────────────────────
    private lateinit var card: MediaCard
    private lateinit var settings: Settings
    private val repo = MediaRepository()
    private val sniffers = mutableListOf<WebStreamSniffer>()
    private var player: ExoPlayer? = null
    private var season = 0
    private var episode = 0
    private var target: StreamExtractor.Target? = null
    private var providers = StreamExtractor.ordered()
    private var providerIndex = 0
    private var usedCache = false
    private var resumeMs = 0L
    private var awaitingResumeChoice = false
    private var introRange: LongRange? = null
    private var nextEpisode: Pair<Int, Int>? = null
    private var nextDismissed = false
    private var speed = 1f
    private var pendingSeek: Long? = null
    private var originalModeId = 0
    private var loudness: android.media.audiofx.LoudnessEnhancer? = null
    private var boost = 0
    private var audioPicked = false
    private var nextPrefetched = false
    private var playPressedAt = 0L
    private var current: WebStreamSniffer.Sniffed? = null
    private var bypassedProxy = false
    private var proxyLastResort: WebStreamSniffer.Sniffed? = null
    private var sleepJob: Job? = null
    private var sleepAt = 0L              // elapsedRealtime when the timer fires, 0 = off
    private var sleepAtEnd = false        // stop when this episode ends
    private var bandwidthMeter: androidx.media3.exoplayer.upstream.DefaultBandwidthMeter? = null
    private val dubTried = mutableSetOf<String>()

    private var streamJob: Job? = null
    private var tickJob: Job? = null
    private var readyTimeout: Job? = null
    private var hideOverlayJob: Job? = null
    private var seekCommitJob: Job? = null

    companion object {
        private const val TAG = "NovaTV"
        private const val EXTRA_CARD = "card"
        private const val EXTRA_SEASON = "season"
        private const val EXTRA_EPISODE = "episode"
        private val CLOCK = SimpleDateFormat("h:mm a", Locale.getDefault())

        fun launch(ctx: Context, card: MediaCard, season: Int = 0, episode: Int = 0) {
            ctx.startActivity(Intent(ctx, PlayerActivity::class.java).apply {
                putExtra(EXTRA_CARD, Gson().toJson(card))
                putExtra(EXTRA_SEASON, season)
                putExtra(EXTRA_EPISODE, episode)
            })
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_player)
        LocalStore.init(this)
        Prefetcher.cancelWaiting()
        // Posters from the browse screens aren't needed while watching; free them for the video buffers
        coil.Coil.imageLoader(this).memoryCache?.clear()

        root = findViewById(R.id.player_root)
        playerView = findViewById(R.id.player_view)
        buffering = findViewById(R.id.buffering)
        centerIcon = findViewById(R.id.center_icon)
        overlay = findViewById(R.id.overlay)
        overlayTitle = findViewById(R.id.top_title)
        overlaySub = findViewById(R.id.overlay_sub)
        overlayDesc = findViewById(R.id.overlay_desc)
        seekBar = findViewById(R.id.seek_bar)
        timeLeft = findViewById(R.id.time_left)
        timeRight = findViewById(R.id.time_right)
        loadingLayer = findViewById(R.id.loading_layer)
        statusText = findViewById(R.id.status_text)
        loadingBar = findViewById(R.id.loading)
        retryBtn = findViewById(R.id.btn_retry)
        skipBtn = findViewById(R.id.btn_skip)
        nextBtn = findViewById(R.id.btn_next)

        card = Gson().fromJson(intent.getStringExtra(EXTRA_CARD), MediaCard::class.java)
        settings = LocalStore.settings()
        boost = settings.volumeBoost
        season = intent.getIntExtra(EXTRA_SEASON, 0)
        episode = intent.getIntExtra(EXTRA_EPISODE, 0)
        originalModeId = window.attributes.preferredDisplayModeId

        findViewById<ImageView>(R.id.loading_backdrop).apply {
            colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setScale(0.6f, 0.6f, 0.6f, 1f) })
            load(card.backdropUrl ?: card.posterUrl) { crossfade(300) }
        }
        styleSubtitles()

        topBar = findViewById(R.id.player_top)
        setupTouch()
        immersive()
        skipBtn.setOnClickListener { skipIntro() }
        nextBtn.setOnClickListener { playNext() }
        retryBtn.setOnClickListener { startEpisode(season, episode) }

        startEpisode(season, episode)
    }

    // ── Episode start / resume prompt ─────────────────────────────────────────

    private fun startEpisode(s: Int, e: Int) {
        playPressedAt = android.os.SystemClock.elapsedRealtime()
        season = s; episode = e
        providers = StreamExtractor.ordered()
        providerIndex = 0
        dubTried.clear()
        introRange = null
        nextEpisode = null
        nextDismissed = false
        nextPrefetched = false
        proxyLastResort = null
        listOf(skipBtn, nextBtn, retryBtn, overlay).forEach { it.visibility = View.GONE }
        releasePlayer()
        findViewById<TextView>(R.id.loading_title).text = title()
        loadingLayer.visibility = View.VISIBLE
        loadingLayer.alpha = 1f

        val saved = LocalStore.historyFor(card)
        resumeMs = if (saved != null && saved.season == s && saved.episode == e && saved.positionMs > 30_000 && saved.progress < 0.95f)
            saved.positionMs else 0L

        // Ask Resume / Start Over while the source search runs in the background (no extra wait)
        if (resumeMs > 0) {
            awaitingResumeChoice = true
            ActionSheet.show(this, title(), listOf(
                ActionSheet.Item("▶  Resume from ${fmt(resumeMs)}") { onResumeChoice(resumeMs) },
                ActionSheet.Item("Start from Beginning") { onResumeChoice(0) }
            ), onDismiss = { if (awaitingResumeChoice) onResumeChoice(resumeMs) })
        }

        streamJob?.cancel()
        streamJob = lifecycleScope.launch {
            showStatus("Finding ${title()}…")
            // Family-safe check runs alongside the stream search instead of before it
            launch {
                if (repo.isBlocked(card)) {
                    streamJob?.cancel(); releasePlayer()
                    showRetry("This title isn't available in Nova TV", retry = false)
                }
            }
            target = StreamExtractor.resolveTarget(card, s, e, repo)
            if (target == null) { showRetry("Couldn't match this title to a stream source."); return@launch }
            launch { loadExtras() }
            findSource()
        }
    }

    private fun onResumeChoice(ms: Long) {
        if (!awaitingResumeChoice) return
        awaitingResumeChoice = false
        resumeMs = ms
        player?.let { it.seekTo(ms); it.playWhenReady = true }
    }

    // ── Source search: health-ordered, cached, two at a time on slow pages ───

    private suspend fun findSource() {
        val t = target ?: return
        while (providerIndex < providers.size) {
            val p = providers[providerIndex]
            val cachedLink = StreamExtractor.cached(p, t)
            if (cachedLink != null) {
                if (proxyOnly(cachedLink)) continue
                usedCache = true; Log.i(TAG, "instant: cached link for ${p.name}"); play(cachedLink); return
            }
            // A background search for exactly this may already be running (title page / Continue Watching)
            val inFlight = Prefetcher.claim(StreamExtractor.embedUrl(p, t))
            if (inFlight != null) {
                showStatus("Connecting to ${p.name}…")
                val r = runCatching { inFlight.await() }.getOrNull()
                if (r != null) {
                    StreamExtractor.remember(p, t, r)
                    if (proxyOnly(r)) continue
                    Log.i(TAG, "instant: joined prefetch for ${p.name}"); usedCache = false; play(r); return
                }
            }
            val partner = providers.getOrNull(providerIndex + 1)
            showStatus("Connecting to ${p.name}…")
            val won = race(t, p, partner)
            if (won != null) {
                providerIndex = providers.indexOf(won.first)
                usedCache = false
                StreamExtractor.remember(won.first, t, won.second)
                if (proxyOnly(won.second)) continue
                play(won.second)
                return
            }
            providerIndex += if (partner != null) 2 else 1
        }
        proxyLastResort?.let { s -> proxyLastResort = null; Log.i(TAG, "last resort: source's own proxy"); play(s, bypassProxy = false); return }
        showRetry("No source answered in time.\nThe box may be busy — Retry usually works.")
    }

    /**
     * A link whose origin refuses direct access only plays through the source's slow proxy.
     * While other sources remain, keep it as the last resort and move on.
     */
    private fun proxyOnly(s: WebStreamSniffer.Sniffed): Boolean {
        val origin = originHost(s.url) ?: return false
        if (!s.url.contains("proxy?") || !LocalStore.directBad(origin) || providerIndex >= providers.size - 1) return false
        if (proxyLastResort == null) proxyLastResort = s
        Log.i(TAG, "${providers.getOrNull(providerIndex)?.name}: proxy-only link, trying other sources first")
        providerIndex++
        return true
    }

    /** "…/api/robot-proxy?url=https://origin/…" → "origin". */
    private fun originHost(url: String): String? =
        runCatching { Uri.parse(Uri.parse(url).getQueryParameter("url")).host }.getOrNull()

    /**
     * Searches [a]; if it hasn't answered after 12s, also searches [b] in a second hidden page.
     * First stream found wins and the other search is cancelled.
     */
    private suspend fun race(t: StreamExtractor.Target, a: StreamExtractor.Provider, b: StreamExtractor.Provider?):
            Pair<StreamExtractor.Provider, WebStreamSniffer.Sniffed>? = coroutineScope {
        val results = Channel<Pair<StreamExtractor.Provider, WebStreamSniffer.Sniffed?>>(2)
        fun search(p: StreamExtractor.Provider, delayMs: Long) = launch {
            delay(delayMs)
            if (delayMs > 0) showStatus("Still looking… also trying ${p.name}")
            val sn = WebStreamSniffer(this@PlayerActivity, root).also { sniffers += it }
            val t0 = System.currentTimeMillis()
            val r = try { sn.sniff(StreamExtractor.embedUrl(p, t), p.timeoutMs) } finally { sniffers -= sn }
            if (r != null) LocalStore.setProviderSpeed(p.name, System.currentTimeMillis() - t0)
            results.send(p to r)
        }
        val jobs = listOfNotNull(search(a, 0), b?.let { search(it, 12_000) })
        var pending = jobs.size
        var winner: Pair<StreamExtractor.Provider, WebStreamSniffer.Sniffed>? = null
        while (pending > 0 && winner == null) {
            val (p, r) = results.receive()
            pending--
            if (r != null) winner = p to r
            else { Log.i(TAG, "${p.name} gave nothing"); LocalStore.reportProvider(p.name, false) }
        }
        jobs.forEach { it.cancel() }
        winner
    }

    /**
     * The stream plays but lacks the preferred dub (e.g. VidLux has no Hindi for Loki):
     * try the next multi-audio source once, from the same position. If none has it, remember that.
     */
    private fun huntDub(want: String) {
        val current = providers.getOrNull(providerIndex) ?: return
        dubTried += current.name
        if (LocalStore.noDub(key(), want)) return
        val next = providers.indices.firstOrNull { it != providerIndex && providers[it].multiAudio && providers[it].name !in dubTried }
        val langName = com.anees.novamobile.data.model.LangTags.name(want) ?: want
        if (next == null) {
            LocalStore.setNoDub(key(), want)
            Log.i(TAG, "no $langName dub on any source")
            return
        }
        dubTried += providers[next].name   // counted as tried even if it fails, so the search can't loop
        Log.i(TAG, "$langName not on ${current.name}, trying ${providers[next].name}")
        android.widget.Toast.makeText(this, "$langName not on ${current.name} — trying ${providers[next].name}…", android.widget.Toast.LENGTH_SHORT).show()
        resumeMs = player?.currentPosition?.takeIf { it > 0 } ?: resumeMs
        releasePlayer()
        loadingLayer.alpha = 1f
        loadingLayer.visibility = View.VISIBLE
        providerIndex = next
        streamJob?.cancel()
        streamJob = lifecycleScope.launch { findSource() }
    }

    /** Player error / stall: re-sniff once if the link was cached, else move on. */
    private fun fallback(reason: String) {
        Log.w(TAG, "source failed: $reason")
        // Direct-to-origin refused: remember that server, keep this link as a last resort (its proxy
        // is slow), and move on to the next source first
        val failed = current
        if (bypassedProxy && failed != null && reason.contains("HTTP")) {
            originHost(failed.url)?.let { LocalStore.setDirectBad(it) }
            proxyLastResort = failed
        }
        providers.getOrNull(providerIndex)?.let { dubTried += it.name }   // a failed source isn't a dub candidate
        val t = target
        val p = providers.getOrNull(providerIndex)
        if (t != null && p != null) {
            StreamExtractor.forget(p, t)
            if (!usedCache) { LocalStore.reportProvider(p.name, false); providerIndex++ }
        }
        usedCache = false
        resumeMs = player?.currentPosition?.takeIf { it > 0 } ?: resumeMs
        releasePlayer()
        loadingLayer.visibility = View.VISIBLE
        streamJob?.cancel()
        streamJob = lifecycleScope.launch { findSource() }
    }

    private suspend fun loadExtras() {
        val t = target ?: return
        introRange = when {
            card.type == MediaType.ANIME && t.malId != null -> StreamExtractor.animeIntro(t.malId, episode)
            card.type == MediaType.SERIES -> 20_000L..100_000L
            else -> null
        }
        nextEpisode = when (card.type) {
            MediaType.SERIES -> {
                val eps = repo.getSeasonEpisodes(card.id, season)
                eps.firstOrNull { it.episodeNumber == episode }?.let { ep ->
                    overlaySub.text = "S$season · E$episode  ${ep.name}"
                    overlayDesc.text = ep.overview
                }
                val seasons = card.totalSeasons ?: repo.getSeriesDetail(card.id)?.second?.numberOfSeasons ?: season
                when {
                    episode < eps.size -> season to episode + 1
                    season < seasons -> season + 1 to 1
                    else -> null
                }
            }
            MediaType.ANIME -> if (!t.isMovie && (card.episodes == null || episode < card.episodes!!)) 1 to episode + 1 else null
            else -> null
        }
    }

    // ── Playback ──────────────────────────────────────────────────────────────

    /**
     * [bypassProxy]: fetch straight from the origin behind a source's proxy (much faster). Some
     * origins refuse direct access; then the same link is retried through the proxy (see fallback).
     */
    private fun play(s: WebStreamSniffer.Sniffed, bypassProxy: Boolean = true) {
        current = s
        val origin = originHost(s.url)
        bypassedProxy = bypassProxy && s.url.contains("proxy?") && origin != null && !LocalStore.directBad(origin)
        // Stream tokens are often locked to the IP family the WebView used (usually IPv6)
        val preferV6 = tokenPrefersV6(s.url)
        val client = com.anees.novamobile.data.api.RetrofitClient.base.newBuilder()   // shares warm connections
            .dns(object : Dns { override fun lookup(hostname: String) = Dns.SYSTEM.lookup(hostname).sortedBy { if ((it is Inet6Address) == preferV6) 0 else 1 } })
            .build()
        val http = OkHttpDataSource.Factory(client).setUserAgent(s.userAgent).setDefaultRequestProperties(s.headers)
        val dataSource = SubtitleCleaner.factory(this, http, client, s.headers + ("User-Agent" to s.userAgent), bypassedProxy)

        val subs = s.subtitles.filterNot { SubtitleCleaner.isThumbnailTrack(it) }.take(8).mapIndexed { i, url ->
            MediaItem.SubtitleConfiguration.Builder(SubtitleCleaner.wrap(url))
                .setMimeType(if (url.lowercase().contains(".srt")) MimeTypes.APPLICATION_SUBRIP else MimeTypes.TEXT_VTT)
                .setLanguage(guessLang(url) ?: "en")
                .setLabel(subLabel(url, i))
                .build()
        }
        val item = MediaItem.Builder()
            .setUri(s.url)
            .apply { if (s.url.lowercase().contains("m3u8")) setMimeType(MimeTypes.APPLICATION_M3U8) }
            .setSubtitleConfigurations(subs)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(title()).build())
            .build()

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(15_000, 30_000, 1_000, 3_000)   // start after 1s of video
            .setTargetBufferBytes(24 * 1024 * 1024)
            .setPrioritizeTimeOverSizeThresholds(false)
            .build()
        // Low-CPU mode: tunneled playback hands A/V sync to the box's video chip
        val selector = androidx.media3.exoplayer.trackselection.DefaultTrackSelector(this).apply {
            setParameters(buildUponParameters().setTunnelingEnabled(settings.lowCpuMode))
        }
        // Start at the quality the connection really managed last time: no blurry first seconds, no stall
        val meter = androidx.media3.exoplayer.upstream.DefaultBandwidthMeter.Builder(this)
            .apply { LocalStore.lastBandwidth().takeIf { it > 0 }?.let { setInitialBitrateEstimate(it) } }
            .build()
        bandwidthMeter = meter
        val renderers = androidx.media3.exoplayer.DefaultRenderersFactory(this).setEnableDecoderFallback(true)
        val exo = ExoPlayer.Builder(this, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSource))
            .setLoadControl(loadControl)
            .setTrackSelector(selector)
            .setBandwidthMeter(meter)
            .build()
        player = exo
        audioPicked = false
        playerView.player = exo
        applyTrackPrefs(exo)
        attachBoost(exo)

        exo.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_READY -> {
                        if (readyTimeout?.isActive == true) providers.getOrNull(providerIndex)?.let { LocalStore.reportProvider(it.name, true) }
                        readyTimeout?.cancel()
                        buffering.visibility = View.GONE
                        if (loadingLayer.visibility == View.VISIBLE) {
                            Log.i(TAG, "timing: first frame ${android.os.SystemClock.elapsedRealtime() - playPressedAt}ms after Play")
                            loadingLayer.animate().alpha(0f).setDuration(300).withEndAction { loadingLayer.visibility = View.GONE }.start()
                            showOverlay(3000)
                            matchFrameRate(exo)
                        }
                    }
                    Player.STATE_BUFFERING -> if (loadingLayer.visibility != View.VISIBLE) buffering.visibility = View.VISIBLE
                    Player.STATE_ENDED -> {
                        saveProgress()
                        if (sleepAtEnd) goToSleep()
                        else if (nextEpisode != null && settings.autoplayNext) playNext()
                        else if (nextEpisode != null) { nextBtn.text = "▶  Next Episode"; nextBtn.visibility = View.VISIBLE; nextBtn.requestFocus() }
                        else finish()
                    }
                    else -> {}
                }
            }
            override fun onPlayerError(error: PlaybackException) = fallback(error.errorCodeName)
            override fun onTracksChanged(tracks: Tracks) {
                val audio = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
                fun langOf(f: Format) = f.language?.take(2)?.takeIf { it != "un" } ?: com.anees.novamobile.data.model.LangTags.code(f.label)
                // Remember real audio languages so cards can be tagged "Hindi · English"
                val langs = audio.flatMap { g -> (0 until g.length).mapNotNull { langOf(g.getTrackFormat(it)) } }.distinct()
                if (langs.isNotEmpty()) LocalStore.setAudioLangs(key(), langs)

                // Sources like VidLux name tracks ("Hindi") without a language code, so match by name once
                if (audioPicked || audio.isEmpty()) return
                audioPicked = true
                val want = LocalStore.audioFor(key()) ?: settings.audioLang
                if (want == "orig") return
                val selected = audio.firstOrNull { it.isSelected }?.let { g -> (0 until g.length).firstOrNull { g.isTrackSelected(it) }?.let { langOf(g.getTrackFormat(it)) } }
                if (selected == want) return
                for (g in audio) for (i in 0 until g.length) {
                    if (langOf(g.getTrackFormat(i)) == want) {
                        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
                            .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, i)).build()
                        Log.i(TAG, "audio → ${g.getTrackFormat(i).label ?: want}")
                        return
                    }
                }
                huntDub(want)
            }
        })

        exo.setMediaItem(item, resumeMs)
        exo.setPlaybackSpeed(speed)
        exo.prepare()
        exo.playWhenReady = !awaitingResumeChoice

        readyTimeout?.cancel()
        readyTimeout = lifecycleScope.launch { delay(25_000); if (exo.playbackState != Player.STATE_READY) fallback("not ready in 25s") }
        startTicker()
        Log.i(TAG, "playing via ${providers.getOrNull(providerIndex)?.name}, subs=${subs.size}")
    }

    /** Audio language from Settings (or what was picked for this title), subtitles, 1080p cap, low-CPU mode. */
    private fun applyTrackPrefs(exo: ExoPlayer) {
        val lang = LocalStore.audioFor(key()) ?: settings.audioLang
        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
            .apply { if (lang != "orig") setPreferredAudioLanguages(lang, "en") }
            .setPreferredTextLanguage("en")
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !settings.subtitlesOn)
            .setMaxVideoSize(1920, 1080)   // a 4K stream would stutter this box
            .build()
    }

    /** Quiet streams: a loudness enhancer (with built-in limiter, so loud scenes don't clip). */
    private fun attachBoost(exo: ExoPlayer) {
        loudness?.release()
        loudness = null
        val session = androidx.media3.common.util.Util.generateAudioSessionIdV21(this)
        exo.setAudioSessionId(session)
        loudness = runCatching { android.media.audiofx.LoudnessEnhancer(session) }.getOrNull()
        applyBoost()
    }

    private fun applyBoost() {
        val mb = when (boost) { 1 -> 600; 2 -> 1000; 3 -> 1500; else -> 0 }
        loudness?.let { runCatching { it.setTargetGain(mb); it.enabled = mb > 0 } }
    }

    private fun styleSubtitles() {
        playerView.subtitleView?.apply {
            setStyle(CaptionStyleCompat(Color.WHITE, Color.TRANSPARENT, Color.TRANSPARENT,
                CaptionStyleCompat.EDGE_TYPE_OUTLINE, Color.BLACK, null))
            setFractionalTextSize(when (settings.subtitleSize) { 0 -> 0.045f; 2 -> 0.072f; else -> 0.056f })
            setBottomPaddingFraction(0.08f)
        }
    }

    /** Switch the TV to a refresh rate that fits the film (e.g. 24Hz) to remove judder. */
    private fun matchFrameRate(exo: ExoPlayer) {
        if (!settings.matchFrameRate) return
        val fps = exo.videoFormat?.frameRate?.takeIf { it > 0 } ?: return
        val display = windowManager.defaultDisplay
        val cur = display.mode
        val best = display.supportedModes
            .filter { it.physicalWidth == cur.physicalWidth && it.physicalHeight == cur.physicalHeight }
            .filter { val r = it.refreshRate / fps; kotlin.math.abs(r - Math.round(r)) < 0.01f }
            .minByOrNull { it.refreshRate } ?: return
        if (best.modeId != cur.modeId) window.attributes = window.attributes.apply { preferredDisplayModeId = best.modeId }
    }

    // ── Overlay / ticker ──────────────────────────────────────────────────────

    /** Built-in Media3 controls on phones; title shown in the top bar. */
    private fun showOverlay(autoHideMs: Long = 4000, withInfo: Boolean = false) {
        overlayTitle.text = title()
        playerView.controllerShowTimeoutMs = autoHideMs.toInt()
        playerView.showController()
    }

    private fun hideOverlay() = playerView.hideController()

    private fun updateTimes() {
        val p = player ?: return
        val dur = p.duration.takeIf { it > 0 } ?: return
        val pos = pendingSeek ?: p.currentPosition
        seekBar.progress = (pos * 1000 / dur).toInt()
        seekBar.secondaryProgress = (p.bufferedPosition * 1000 / dur).toInt()
        timeLeft.text = fmt(pos)
        val remaining = ((dur - pos) / speed).toLong()
        val sleep = if (sleepAt > 0) "   ·   💤 ${(sleepAt - android.os.SystemClock.elapsedRealtime()) / 60_000 + 1}m" else if (sleepAtEnd) "   ·   💤 end" else ""
        timeRight.text = "-${fmt(remaining)}   ·   Ends at ${CLOCK.format(Date(System.currentTimeMillis() + remaining))}$sleep"
    }

    private fun startTicker() {
        tickJob?.cancel()
        tickJob = lifecycleScope.launch {
            var n = 0
            while (isActive) {
                delay(500)
                val p = player ?: continue
                if (overlay.visibility == View.VISIBLE) updateTimes()
                if (++n % 2 != 0) continue
                val pos = p.currentPosition
                val dur = p.duration.takeIf { it > 0 } ?: continue

                val intro = introRange
                val showSkip = intro != null && pos in intro && pos < intro.last - 3000
                if (showSkip && skipBtn.visibility != View.VISIBLE) {
                    skipBtn.text = if (card.type == MediaType.ANIME) "Skip Intro" else "Skip Intro  +85s"
                    // Only real intro data (anime) takes focus; for series it's a hint you reach with ▼.
                    // Non-focusable until then, or Android would hand it focus automatically.
                    skipBtn.visibility = View.VISIBLE
                } else if (!showSkip && skipBtn.visibility == View.VISIBLE) skipBtn.visibility = View.GONE

                val next = nextEpisode
                val remaining = dur - pos
                // Two minutes before the end, find the next episode's stream so "Next" starts at once
                if (next != null && !nextPrefetched && remaining in 1..120_000) {
                    nextPrefetched = true
                    Prefetcher.request(this@PlayerActivity, card, next.first, next.second, hostOverride = root)
                }
                if (next != null && !nextDismissed && remaining in 1..20_000) {
                    nextBtn.text = "▶  Next: S${next.first} · E${next.second}   ${remaining / 1000}s"
                    if (nextBtn.visibility != View.VISIBLE) nextBtn.visibility = View.VISIBLE
                } else if (nextBtn.visibility == View.VISIBLE && remaining > 20_000) nextBtn.visibility = View.GONE

                if (n % 20 == 0) saveProgress()
            }
        }
    }

    private fun flash(icon: String) {
        centerIcon.text = icon
        centerIcon.visibility = View.VISIBLE
        centerIcon.alpha = 1f; centerIcon.scaleX = 0.9f; centerIcon.scaleY = 0.9f
        centerIcon.animate().scaleX(1f).scaleY(1f).setDuration(150).withEndAction {
            centerIcon.animate().alpha(0f).setStartDelay(350).setDuration(250).withEndAction { centerIcon.visibility = View.GONE }.start()
        }.start()
    }

    // ── Touch: tap toggles controls, double-tap left/right skips 10s ─────────

    private fun setupTouch() {
        val detector = android.view.GestureDetector(this, object : android.view.GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: android.view.MotionEvent) = true
            override fun onSingleTapConfirmed(e: android.view.MotionEvent): Boolean {
                if (playerView.isControllerFullyVisible) playerView.hideController() else playerView.showController()
                return true
            }
            override fun onDoubleTap(e: android.view.MotionEvent): Boolean {
                val p = player ?: return true
                val third = playerView.width / 3f
                when {
                    e.x < third -> { p.seekTo((p.currentPosition - 10_000).coerceAtLeast(0)); flash("⟲ 10") }
                    e.x > third * 2 -> { p.seekTo(p.currentPosition + 10_000); flash("10 ⟳") }
                    else -> { p.playWhenReady = !p.playWhenReady; flash(if (p.playWhenReady) "▶" else "❚❚") }
                }
                return true
            }
        })
        playerView.setOnTouchListener { _, e -> detector.onTouchEvent(e) }
        playerView.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { v ->
            topBar.visibility = v
        })
        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }
        findViewById<View>(R.id.btn_options).setOnClickListener { showPicker() }
    }

    /** Hide status + navigation bars (swipe from the edge brings them back briefly). */
    private fun immersive() {
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        androidx.core.view.WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) immersive()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (nextBtn.visibility == View.VISIBLE) { nextDismissed = true; nextBtn.visibility = View.GONE; return }
        @Suppress("DEPRECATION") super.onBackPressed()
    }

    private fun skipIntro() {
        val p = player ?: return
        val intro = introRange
        p.seekTo(if (card.type == MediaType.ANIME && intro != null) intro.last else p.currentPosition + 85_000)
        skipBtn.visibility = View.GONE
        introRange = null
    }

    private fun playNext() {
        val next = nextEpisode ?: return
        saveProgress()
        overlaySub.text = ""
        overlayDesc.text = ""
        startEpisode(next.first, next.second)
    }

    private fun saveProgress() {
        val p = player ?: return
        if (p.duration <= 0) return
        LocalStore.saveProgress(HistoryEntry(card, season, episode, p.currentPosition, p.duration))
    }

    // ── Audio · Subtitles · Quality · Speed · Source ──────────────────────────

    private fun showPicker() {
        val exo = player ?: return
        val wasPlaying = exo.playWhenReady
        val items = mutableListOf<ActionSheet.Item>()

        val audio = exo.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
        if (audio.sumOf { it.length } > 0) items += ActionSheet.Item("Audio", header = true)
        audio.forEach { g ->
            for (i in 0 until g.length) {
                val f = g.getTrackFormat(i)
                items += ActionSheet.Item(trackName(f, "Track ${i + 1}"), checked = g.isTrackSelected(i)) {
                    exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
                        .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, i)).build()
                    (f.language?.take(2)?.takeIf { it != "un" } ?: com.anees.novamobile.data.model.LangTags.code(f.label))
                        ?.let { LocalStore.setAudioFor(key(), it) }   // remembered for the next episode
                }
            }
        }

        val text = exo.currentTracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
        items += ActionSheet.Item("Subtitles", header = true)
        items += ActionSheet.Item("Off", checked = text.none { it.isSelected }) {
            exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
        }
        text.forEach { g ->
            for (i in 0 until g.length) {
                items += ActionSheet.Item(trackName(g.getTrackFormat(i), "Subtitle ${i + 1}"), checked = g.isTrackSelected(i)) {
                    exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                        .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, i)).build()
                }
            }
        }

        val video = exo.currentTracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO && it.length > 1 }
        if (video.isNotEmpty()) {
            items += ActionSheet.Item("Quality", header = true)
            items += ActionSheet.Item("Auto", checked = exo.trackSelectionParameters.overrides.keys.none { it.type == C.TRACK_TYPE_VIDEO }) {
                exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_VIDEO).build()
            }
            video.forEach { g ->
                for (i in 0 until g.length) {
                    val h = g.getTrackFormat(i).height
                    if (h <= 0 || h > 1080) continue
                    items += ActionSheet.Item("${h}p", checked = exo.trackSelectionParameters.overrides[g.mediaTrackGroup]?.trackIndices?.contains(i) == true) {
                        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
                            .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, i)).build()
                    }
                }
            }
        }

        items += ActionSheet.Item("Sleep Timer", header = true)
        val left = if (sleepAt > 0) (sleepAt - android.os.SystemClock.elapsedRealtime()) / 60_000 else 0
        items += ActionSheet.Item(if (sleepAt > 0) "On · ${left + 1} min left" else "Off", checked = sleepAt == 0L && !sleepAtEnd) { setSleep(0) }
        listOf(30, 60, 90).forEach { m -> items += ActionSheet.Item("$m minutes") { setSleep(m) } }
        items += ActionSheet.Item("End of this ${if (card.type == MediaType.MOVIE) "movie" else "episode"}", checked = sleepAtEnd) { setSleep(-1) }

        items += ActionSheet.Item("Volume Boost", header = true)
        listOf("Off", "+6 dB", "+10 dB", "+15 dB").forEachIndexed { i, label ->
            items += ActionSheet.Item(label, checked = boost == i) {
                boost = i
                applyBoost()
                LocalStore.saveSettings(LocalStore.settings().copy(volumeBoost = i))   // quiet sources stay fixed next time
            }
        }

        items += ActionSheet.Item("Speed", header = true)
        listOf(1f, 1.25f, 1.5f).forEach { sp -> items += ActionSheet.Item("${sp}×", checked = speed == sp) { speed = sp; exo.setPlaybackSpeed(sp) } }

        items += ActionSheet.Item("Source", header = true)
        providers.forEachIndexed { i, p ->
            items += ActionSheet.Item(p.name, checked = i == providerIndex) {
                if (i != providerIndex) {
                    resumeMs = exo.currentPosition
                    releasePlayer()
                    providerIndex = i
                    loadingLayer.alpha = 1f
                    loadingLayer.visibility = View.VISIBLE
                    streamJob?.cancel()
                    streamJob = lifecycleScope.launch { findSource() }
                }
            }
        }

        exo.playWhenReady = false
        ActionSheet.show(this, null, items, onDismiss = { player?.playWhenReady = wasPlaying })
    }

    /** minutes > 0: stop after that long; -1: at the end of this episode; 0: off. */
    private fun setSleep(minutes: Int) {
        sleepJob?.cancel()
        sleepAt = 0; sleepAtEnd = minutes < 0
        if (minutes > 0) {
            sleepAt = android.os.SystemClock.elapsedRealtime() + minutes * 60_000L
            sleepJob = lifecycleScope.launch { delay(minutes * 60_000L); goToSleep() }
        }
        android.widget.Toast.makeText(this, when {
            minutes > 0 -> "Sleep timer: $minutes minutes"
            minutes < 0 -> "Will stop at the end"
            else -> "Sleep timer off"
        }, android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun goToSleep() {
        saveProgress()
        player?.pause()
        finish()
    }

    private fun trackName(f: Format, fallback: String): String {
        val lang = f.language?.let { Locale(it).displayLanguage }?.takeIf { it.isNotBlank() && it != "und" }
        val channels = when (f.channelCount) { 6 -> "5.1"; 8 -> "7.1"; else -> null }
        return listOfNotNull(f.label, lang, channels).distinct().joinToString(" · ").ifBlank { fallback }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun key() = "${card.type}:${card.id}"

    private fun title() = when {
        card.type == MediaType.MOVIE || target?.isMovie == true -> card.title
        card.type == MediaType.ANIME -> "${card.title} · Episode $episode"
        else -> "${card.title} · S$season E$episode"
    }

    private fun fmt(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }

    private fun showStatus(msg: String) {
        loadingBar.visibility = View.VISIBLE
        statusText.text = msg
    }

    private fun showRetry(msg: String, retry: Boolean = true) {
        loadingLayer.alpha = 1f
        loadingLayer.visibility = View.VISIBLE
        loadingBar.visibility = View.INVISIBLE
        statusText.text = msg
        if (retry) { retryBtn.visibility = View.VISIBLE; retryBtn.requestFocus() }
    }

    /** Reads `ip_cidr` from a JWT `token=` param if present; otherwise prefer IPv6 like Chromium does. */
    private fun tokenPrefersV6(url: String): Boolean = runCatching {
        val jwt = Uri.parse(url).getQueryParameter("token") ?: return true
        val payload = String(android.util.Base64.decode(jwt.split(".")[1], android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING))
        val cidr = Regex(""""ip_cidr"\s*:\s*"([^"]+)"""").find(payload)?.groupValues?.get(1) ?: return true
        cidr.contains(':')
    }.getOrDefault(true)

    private fun guessLang(url: String): String? =
        Regex("""[/_\-.](en|eng|english|ar|ara|arabic|fr|es|de|ur|hi)[/_\-.]""", RegexOption.IGNORE_CASE)
            .find(url)?.groupValues?.get(1)?.lowercase()?.take(2)

    private fun subLabel(url: String, i: Int): String =
        guessLang(url)?.let { Locale(it).displayLanguage } ?: if (i == 0) "English" else "Subtitles ${i + 1}"

    private fun releasePlayer() {
        bandwidthMeter?.bitrateEstimate?.takeIf { it > 300_000 }?.let { LocalStore.setLastBandwidth(it) }
        loudness?.release()
        loudness = null
        readyTimeout?.cancel()
        tickJob?.cancel()
        seekCommitJob?.cancel()
        pendingSeek = null
        player?.release()
        player = null
        playerView.player = null
        buffering.visibility = View.GONE
    }

    override fun onStop() {
        super.onStop()
        saveProgress()
        player?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        streamJob?.cancel()
        sniffers.toList().forEach { it.release() }
        Prefetcher.cancel()
        releasePlayer()
        if (settings.matchFrameRate) window.attributes = window.attributes.apply { preferredDisplayModeId = originalModeId }
    }
}
