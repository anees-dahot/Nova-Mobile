package com.anees.novamobile.ui.player

import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Outline
import android.graphics.Rect
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.util.Rational
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.*
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import coil.load
import com.anees.novamobile.R
import com.anees.novamobile.data.local.LocalStore
import com.anees.novamobile.data.local.Settings
import com.anees.novamobile.data.model.Episode
import com.anees.novamobile.data.model.HistoryEntry
import com.anees.novamobile.data.model.MediaCard
import com.anees.novamobile.data.model.MediaType
import com.anees.novamobile.data.repository.MediaRepository
import com.anees.novamobile.ui.common.ActionSheet
import com.anees.novamobile.ui.common.Ui
import com.anees.novamobile.ui.common.dp
import com.google.gson.Gson
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import okhttp3.Dns
import java.net.Inet6Address
import java.util.Date
import java.util.Locale

@UnstableApi
class PlayerActivity : AppCompatActivity(), PlayerGestures.Host {

    // ── Views ─────────────────────────────────────────────────────────────────
    private lateinit var root: FrameLayout
    private lateinit var playerView: PlayerView
    private lateinit var buffering: ProgressBar
    private lateinit var topTitle: TextView
    private lateinit var topSub: TextView
    private lateinit var timeRemaining: TextView
    private lateinit var speedBtn: TextView
    private lateinit var qualityBtn: TextView
    private lateinit var episodesBtn: TextView
    private lateinit var nextEpBtn: TextView
    private lateinit var hud: View
    private lateinit var hudIcon: ImageView
    private lateinit var hudText: TextView
    private lateinit var hudBar: ProgressBar
    private lateinit var seekLeft: TextView
    private lateinit var seekRight: TextView
    private lateinit var unlockBtn: ImageButton
    private lateinit var nextCard: View
    private lateinit var nextCountdown: ProgressBar
    private lateinit var drawer: View
    private lateinit var drawerScrim: View
    private lateinit var loadingLayer: View
    private lateinit var statusText: TextView
    private lateinit var loadingBar: ProgressBar
    private lateinit var retryBtn: Button
    private lateinit var pickSourceBtn: Button
    private lateinit var skipBtn: Button

    // ── State ─────────────────────────────────────────────────────────────────
    private lateinit var card: MediaCard
    private lateinit var settings: Settings
    private val repo = MediaRepository()
    private val sniffers = mutableListOf<WebStreamSniffer>()
    private var player: ExoPlayer? = null
    private var session: MediaSession? = null
    private var season = 0
    private var episode = 0
    private var target: StreamExtractor.Target? = null
    private var providers = StreamExtractor.ordered()
    private var providerIndex = 0
    /** Every stream found for this episode with its real audio languages, for the language menu. */
    private val langSources = mutableListOf<Triple<StreamExtractor.Provider, WebStreamSniffer.Sniffed, List<String>>>()
    private var langJob: kotlinx.coroutines.Job? = null
    private var usedCache = false
    /** Sources already searched for this episode, so later waves don't repeat them. */
    private val raced = mutableSetOf<StreamExtractor.Provider>()
    private var resumeMs = 0L
    private var awaitingResumeChoice = false
    private var introRange: LongRange? = null
    private var outroRange: LongRange? = null
    private var skipShownAt = 0L
    private var nextEpisode: Pair<Int, Int>? = null
    private var seasonEpisodes: List<Episode> = emptyList()
    private var nextDismissed = false
    private var nextDeadline = 0L          // elapsedRealtime when the up-next countdown plays the next episode
    private var speed = 1f
    private var originalModeId = 0
    private var loudness: android.media.audiofx.LoudnessEnhancer? = null
    private var boost = 0
    private var audioPicked = false
    private var nextPrefetched = false
    private var syncedWatched = false
    private var playPressedAt = 0L
    private var current: WebStreamSniffer.Sniffed? = null
    private var bypassedProxy = false
    private var proxyLastResort: WebStreamSniffer.Sniffed? = null
    private var qualityLastResort: WebStreamSniffer.Sniffed? = null
    private var offlineItem: MediaItem? = null
    private var sleepJob: Job? = null
    private var sleepAt = 0L              // elapsedRealtime when the timer fires, 0 = off
    private var sleepAtEnd = false        // stop when this episode ends
    private var bandwidthMeter: androidx.media3.exoplayer.upstream.DefaultBandwidthMeter? = null
    private var locked = false
    private var inPip = false
    private var hudHide: Runnable? = null
    private var filler: com.anees.novamobile.data.sources.anime.FillerInfo.Show? = null
    private var episodeLine = ""          // "S1 · E3  ·  Episode name", shown under the title with "ends 10:42 PM"
    private lateinit var gestures: PlayerGestures
    private lateinit var subOverlay: androidx.media3.ui.SubtitleView
    private lateinit var subSync: SubtitleSync
    /** Audio sync in ms (+ = sound later); kept for the whole session, applied to every new player. */
    private var audioDelayMs = 0L
    private var audioDelay: AudioDelay? = null
    /** Subtitle files of the saved download being played, registered with [subSync] once the player exists. */
    private var offlineSubFiles: List<Triple<String, String?, java.io.File>> = emptyList()

    private var streamJob: Job? = null
    private var tickJob: Job? = null
    private var readyTimeout: Job? = null

    companion object {
        private const val TAG = "NovaTV"
        private const val EXTRA_CARD = "card"
        private const val EXTRA_SEASON = "season"
        private const val EXTRA_EPISODE = "episode"
        private const val ACTION_PIP = "com.anees.novamobile.PIP"
        /** Sources searched per wave in race(), and the gap between their starts. */
        private const val WAVE = 3
        private const val WAVE_STAGGER_MS = 4_000L
        private const val COLLECT_MS = 5_000L
        /** Longest wait for a source with your language after the first answer. */
        private const val DUB_WAIT_MS = 14_000L
        private const val SETTLE_MS = 1_500L
        private const val UP_NEXT_MS = 10_000L
        private val SPEEDS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)

        fun launch(ctx: Context, card: MediaCard, season: Int = 0, episode: Int = 0) {
            ctx.startActivity(Intent(ctx, PlayerActivity::class.java).apply {
                putExtra(EXTRA_CARD, Gson().toJson(card))
                putExtra(EXTRA_SEASON, season)
                putExtra(EXTRA_EPISODE, episode)
                if (ctx !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }

        /** For notifications: opens the player straight on an episode. */
        fun intent(ctx: Context, card: MediaCard, season: Int, episode: Int): Intent =
            Intent(ctx, PlayerActivity::class.java)
                .putExtra(EXTRA_CARD, Gson().toJson(card)).putExtra(EXTRA_SEASON, season).putExtra(EXTRA_EPISODE, episode)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /** Picture-in-picture buttons: −10s, play/pause, +10s. */
    private val pipReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val p = player ?: return
            when (i.getIntExtra("a", 0)) {
                1 -> p.seekTo((p.currentPosition - 10_000).coerceAtLeast(0))
                2 -> p.playWhenReady = !p.playWhenReady
                3 -> p.seekTo(p.currentPosition + 10_000)
            }
            updatePip()
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
        topTitle = findViewById(R.id.top_title)
        topSub = findViewById(R.id.top_sub)
        timeRemaining = findViewById(R.id.time_remaining)
        speedBtn = findViewById(R.id.btn_speed)
        qualityBtn = findViewById(R.id.btn_quality)
        episodesBtn = findViewById(R.id.btn_episodes)
        nextEpBtn = findViewById(R.id.btn_next_ep)
        hud = findViewById(R.id.gesture_hud)
        hudIcon = findViewById(R.id.hud_icon)
        hudText = findViewById(R.id.hud_text)
        hudBar = findViewById(R.id.hud_bar)
        seekLeft = findViewById(R.id.seek_left)
        seekRight = findViewById(R.id.seek_right)
        unlockBtn = findViewById(R.id.btn_unlock)
        nextCard = findViewById(R.id.next_card)
        nextCountdown = findViewById(R.id.next_countdown)
        drawer = findViewById(R.id.episodes_drawer)
        drawerScrim = findViewById(R.id.episodes_scrim)
        loadingLayer = findViewById(R.id.loading_layer)
        statusText = findViewById(R.id.status_text)
        loadingBar = findViewById(R.id.loading)
        retryBtn = findViewById(R.id.btn_retry)
        pickSourceBtn = findViewById(R.id.btn_pick_source)
        skipBtn = findViewById(R.id.btn_skip)

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
        // Draws subtitles while a sync offset is set (see SubtitleSync); sits right above the video
        subOverlay = androidx.media3.ui.SubtitleView(this).apply { visibility = View.GONE; isClickable = false; isFocusable = false }
        root.addView(subOverlay, root.indexOfChild(playerView) + 1, FrameLayout.LayoutParams(-1, -1))
        subSync = SubtitleSync(subOverlay, { playerView.subtitleView }, lifecycleScope)
        styleSubtitles()
        // Zoom is now a picture scale on top of Fit; an old "zoom to fill" setting becomes Fit + fill zoom
        val wasZoom = settings.resizeMode == AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        playerView.resizeMode = if (wasZoom) AspectRatioFrameLayout.RESIZE_MODE_FIT else settings.resizeMode
        if (wasZoom) playerView.post { playerView.postDelayed({ gestures.setZoom(gestures.fillZoom(), showHud = false) }, 1500) }
        playerView.controllerShowTimeoutMs = 3500

        setupControls()
        gestures = PlayerGestures(this, playerView, this).also { it.attach() }
        immersive()
        ContextCompat.registerReceiver(this, pipReceiver, IntentFilter(ACTION_PIP), ContextCompat.RECEIVER_NOT_EXPORTED)

        startEpisode(season, episode)
    }

    /** Player is single-instance (picture-in-picture): a new title replaces the one playing. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val json = intent.getStringExtra(EXTRA_CARD) ?: return
        saveProgress()
        card = Gson().fromJson(json, MediaCard::class.java)
        target = null
        seasonEpisodes = emptyList()
        findViewById<ImageView>(R.id.loading_backdrop).load(card.backdropUrl ?: card.posterUrl)
        startEpisode(intent.getIntExtra(EXTRA_SEASON, 0), intent.getIntExtra(EXTRA_EPISODE, 0))
    }

    // ── Episode start / resume prompt ─────────────────────────────────────────

    private fun startEpisode(s: Int, e: Int) {
        playPressedAt = SystemClock.elapsedRealtime()
        season = s; episode = e
        providers = StreamExtractor.ordered()
        providerIndex = 0
        langJob?.cancel(); langSources.clear(); raced.clear()
        introRange = null
        outroRange = null
        skipShownAt = 0
        nextEpisode = null
        nextDismissed = false
        nextDeadline = 0
        nextPrefetched = false
        syncedWatched = false
        proxyLastResort = null
        qualityLastResort = null
        offlineSubFiles = emptyList()
        subSync.set(0)   // another episode, another subtitle file
        listOf(skipBtn, nextCard, retryBtn, pickSourceBtn, episodesBtn, nextEpBtn).forEach { it.visibility = View.GONE }
        closeDrawer()
        releasePlayer()
        topTitle.text = title()
        episodeLine = ""
        topSub.text = ""
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
                ActionSheet.Item("Resume from ${fmt(resumeMs)}") { onResumeChoice(resumeMs) },
                ActionSheet.Item("Start from Beginning") { onResumeChoice(0) }
            ), onDismiss = { if (awaitingResumeChoice) onResumeChoice(resumeMs) })
        }

        offlineItem = null
        streamJob?.cancel()
        // Downloaded? Play the saved copy: no search, works offline
        val dl = com.anees.novamobile.download.NovaDownloads.get(this, com.anees.novamobile.download.DownloadMeta.id(card, s, e))
        if (dl?.state == androidx.media3.exoplayer.offline.Download.STATE_COMPLETED) { playOffline(dl); return }
        // Still downloading (MovieBox-style "watch while downloading"): play the same file from what's saved,
        // fetching the rest as needed — starts instantly, no source search. A dead link falls back to streaming.
        if (dl != null && dl.state != androidx.media3.exoplayer.offline.Download.STATE_FAILED && dl.bytesDownloaded > 8L * 1024 * 1024 && online()) {
            Log.i(TAG, "watching while downloading (${dl.percentDownloaded.toInt()}%)"); playOffline(dl); return
        }
        streamJob = lifecycleScope.launch {
            showStatus("Finding ${title()}…")
            // Family-safe check runs alongside the stream search instead of before it
            launch {
                if (repo.isBlocked(card)) {
                    streamJob?.cancel(); releasePlayer()
                    showRetry("This title isn't available in Nova", retry = false)
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

    // ── Source search: health-ordered, cached, three at a time in staggered waves ──

    private fun sourceLabel(p: StreamExtractor.Provider) = "${p.name}  ·  source ${providers.indexOf(p) + 1} of ${providers.size}"

    private suspend fun findSource() {
        val t = target ?: return
        OpenSubs.prefetch(t, repo, subLangs())
        // An instant start gives the search a moment; a race takes longer than the search anyway
        if (providers.any { StreamExtractor.cached(it, t) != null }) OpenSubs.await(t, repo, subLangs(), 1_500)
        while (providerIndex < providers.size) {
            val p = providers[providerIndex]
            val cachedLink = StreamExtractor.cached(p, t)
            if (cachedLink != null) {
                if (proxyOnly(cachedLink)) continue
                if (!qualityGate(cachedLink)) continue
                if (!lacksWanted(cachedLink)) { usedCache = true; Log.i(TAG, "instant: cached link for ${p.name}"); play(cachedLink); return }
                Log.i(TAG, "cached ${p.name} link is single-language: racing multi-audio sources first")
            }
            // A background search for exactly this may already be running (title page / Continue Watching)
            val inFlight = Prefetcher.claim(StreamExtractor.embedUrl(p, t))
            if (inFlight != null) {
                showStatus("Connecting to ${sourceLabel(p)}")
                val r = runCatching { inFlight.await() }.getOrNull()
                if (r != null) {
                    StreamExtractor.remember(p, t, r)
                    if (proxyOnly(r)) continue
                    if (!qualityGate(r)) continue
                    if (!lacksWanted(r)) { Log.i(TAG, "instant: joined prefetch for ${p.name}"); usedCache = false; play(r); return }
                    Log.i(TAG, "prefetched ${p.name} link is single-language: racing multi-audio sources first")
                }
            }
            // Up to 3 sources per wave, 4s apart: the stagger keeps a low-RAM device from opening
            // every hidden page at once but still beats waiting out one full timeout per source
            // Race every multi-audio source at once (most are cheap HTTP resolvers) to find the richest stream
            // Movies, series and anime alike: every multi-audio source left joins, wherever it sits in the order
            val rest = providers.drop(providerIndex).filter { it == p || it !in raced }
            val wave = (rest.take(WAVE) + rest.filter { it.multiAudio }).distinct()
            raced += wave
            showStatus("Connecting to ${sourceLabel(p)}")
            val won = race(t, wave)
            if (won != null) {
                providerIndex = providers.indexOf(won.first)
                usedCache = false
                StreamExtractor.remember(won.first, t, won.second)
                if (proxyOnly(won.second)) continue
                if (!qualityGate(won.second)) continue
                play(won.second)
                return
            }
            providerIndex++
            while (providerIndex < providers.size && providers[providerIndex] in raced) providerIndex++
        }
        proxyLastResort?.let { s -> proxyLastResort = null; Log.i(TAG, "last resort: source's own proxy"); play(s, bypassProxy = false); return }
        qualityLastResort?.let { s -> qualityLastResort = null; Log.i(TAG, "last resort: only sub-720p sources answered"); play(s); return }
        showRetry("No source answered in time.\nRetry usually works, or pick a source yourself.")
    }

    /** Subtitle languages worth fetching: yours, then English. */
    private fun subLangs() = listOfNotNull(LocalStore.subtitleFor(key())?.takeIf { it != "off" }, "en")

    /** Audio language you picked in the menu for this title (no app-wide setting: the richest stream plays). */
    private fun wantedLang(): String? = LocalStore.audioFor(key())?.takeIf { it != "orig" }

    /**
     * First search only: a ready link with a single language waits for the multi-audio race
     * (the link stays cached), unless a recent search found no source with more.
     */
    private suspend fun lacksWanted(s: WebStreamSniffer.Sniffed): Boolean {
        if (raced.isNotEmpty() || providers.none { it.multiAudio && it !in raced }) return false
        if (LocalStore.noDub(key(), "multi")) return false   // searched lately and nobody had more
        val langs = AudioProbe.langs(s)
        val want = wantedLang()
        return langs.size < 2 || (want != null && want !in langs)
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

    /** Saved download: same player and track rules, data from the download store, subtitles from disk. */
    private fun online(): Boolean {
        val cm = getSystemService(android.net.ConnectivityManager::class.java) ?: return true
        return cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }

    /** Watching while it still downloads (label says so; the rest streams in). */
    private var offlinePartial = false

    private fun playOffline(d: androidx.media3.exoplayer.offline.Download) {
        offlinePartial = d.state != androidx.media3.exoplayer.offline.Download.STATE_COMPLETED
        val meta = com.anees.novamobile.download.DownloadMeta.of(d)
        val paths = meta?.subtitles ?: listOfNotNull(meta?.subtitle)
        val langs = meta?.subtitleLangs
        val subs = paths.mapIndexedNotNull { i, path ->
            val f = java.io.File(path)
            if (!f.exists()) return@mapIndexedNotNull null
            SubtitleCleaner.fixFile(f)
            val lang = langs?.getOrNull(i)?.takeIf { it.isNotBlank() } ?: if (i == 0) "en" else null
            MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(f)).setMimeType(MimeTypes.TEXT_VTT)
                .setId("nsub:$i")
                .setLanguage(lang)
                .setLabel(lang?.let { Locale(it).displayLanguage } ?: "Subtitles ${i + 1}").build()
        }
        offlineSubFiles = subs.mapIndexedNotNull { i, c -> c.id?.let { id -> Triple(id, c.label, java.io.File(c.uri.path ?: return@mapIndexedNotNull null)) } }
        offlineItem = d.request.toMediaItem().buildUpon()
            .setSubtitleConfigurations(subs)
            .setMediaMetadata(metadata())
            .build()
        Log.i(TAG, "offline: playing saved download")
        lifecycleScope.launch { runCatching { target = StreamExtractor.resolveTarget(card, season, episode, repo); loadExtras() } }
        play(WebStreamSniffer.Sniffed(d.request.uri.toString(), emptyMap(), "", emptyList()), bypassProxy = false)
    }

    /** "…/api/robot-proxy?url=https://origin/…" → "origin". */
    private fun originHost(url: String): String? =
        // Braflix's /dash-proxy signs CDN cookies: never bypass it
        if (url.contains("dash-proxy")) null else runCatching { Uri.parse(Uri.parse(url).getQueryParameter("url")).host }.getOrNull()

    /**
     * Searches every provider in [wave]: hidden pages start 0s/4s/8s apart, resolvers (plain HTTP)
     * at once. After the first answer the others get [COLLECT_MS] more; the stream with the most
     * real audio languages (yours first) wins.
     */
    private suspend fun race(t: StreamExtractor.Target, wave: List<StreamExtractor.Provider>):
            Pair<StreamExtractor.Provider, WebStreamSniffer.Sniffed>? = coroutineScope {
        val results = Channel<Pair<StreamExtractor.Provider, WebStreamSniffer.Sniffed?>>(wave.size)
        fun search(p: StreamExtractor.Provider, delayMs: Long) = launch {
            delay(delayMs)
            if (delayMs > 0) showStatus("Still looking…  also trying ${sourceLabel(p)}")
            val t0 = System.currentTimeMillis()
            val r = searchOne(t, p)
            if (r != null) LocalStore.setProviderSpeed(p.name, System.currentTimeMillis() - t0)
            results.send(p to r)
        }
        var pages = 0
        val jobs = wave.mapIndexed { i, p ->
            search(p, if (p.resolver != null) 0L else pages++ * WAVE_STAGGER_MS)
        }
        var pending = jobs.size
        val found = mutableListOf<Pair<StreamExtractor.Provider, WebStreamSniffer.Sniffed>>()
        var deadline = 0L
        while (pending > 0) {
            val next = if (found.isEmpty()) results.receive()
                else withTimeoutOrNull((deadline - SystemClock.elapsedRealtime()).coerceAtLeast(1)) { results.receive() } ?: break
            val (p, r) = next
            pending--
            if (r == null) { Log.i(TAG, "${p.name} gave nothing"); LocalStore.reportProvider(p.name, false); continue }
            found += p to r
            val now = SystemClock.elapsedRealtime()
            // Multi-audio pages (VidLux) often answer last: while every answer has one language, wait
            // longer for them; once a stream has several, give the rest a short moment to beat it
            if (deadline == 0L) { deadline = now + DUB_WAIT_MS; showStatus("Checking languages…") }
            val langs = AudioProbe.langs(r)
            val want = wantedLang()
            if (langs.size >= 2 && (want == null || want in langs)) deadline = minOf(deadline, now + COLLECT_MS)
        }
        jobs.forEach { it.cancel() }
        if (found.isEmpty()) return@coroutineScope null
        found.forEach { (p, r) -> StreamExtractor.remember(p, t, r) }
        // Language variants (separate per-language streams) compete as their own entries
        val probed = found.flatMap { (p, r) -> r.withVariants().map { p to it } }
            .map { (p, r) -> async { Triple(p, r, AudioProbe.langs(r)) } }.awaitAll()
        addLangSources(probed)
        // Most languages wins (your pick for this title first, if you made one)
        val best = probed.maxByOrNull { AudioProbe.score(it.third, wantedLang() ?: "orig") }!!
        // Nobody had more than one language: next plays of this title start from the saved link right away
        if (best.third.size < 2) LocalStore.setNoDub(key(), "multi")
        Log.i(TAG, "languages: ${probed.joinToString { "${it.first.name}=${it.third}" }} → ${best.first.name}")
        best.first to best.second
    }

    private fun addLangSources(list: List<Triple<StreamExtractor.Provider, WebStreamSniffer.Sniffed, List<String>>>) {
        list.forEach { n -> langSources.removeAll { it.second.url == n.second.url }; langSources += n }
    }

    /**
     * Played from a saved or prefetched link skipped the race: fill the language menu in the
     * background from the cheap multi-audio resolvers (no hidden pages), plus the stream playing now.
     */
    private fun gatherLanguages(now: WebStreamSniffer.Sniffed) {
        val t = target ?: return
        val cur = providers.getOrNull(providerIndex)
        langJob?.cancel()
        langJob = lifecycleScope.launch {
            val list = coroutineScope {
                providers.filter { it.multiAudio && (it.resolver != null || it == cur) }.map { p ->
                    async {
                        val r = if (p == cur) now else StreamExtractor.cached(p, t)
                            ?: withTimeoutOrNull(p.timeoutMs) { runCatching { p.resolver!!.invoke(t) }.getOrNull() }
                        r?.let { StreamExtractor.remember(p, t, it); it.withVariants().map { v -> Triple(p, v, AudioProbe.langs(v)) } }
                    }
                }.awaitAll().filterNotNull().flatten()
            }
            addLangSources(list)
            Log.i(TAG, "languages found: ${langSources.joinToString { "${it.first.name}=${it.third}" }}")
        }
    }

    /** Language menu picked a language only another found source has: play that stream from the same second. */
    private fun switchTo(p: StreamExtractor.Provider, s: WebStreamSniffer.Sniffed) {
        player?.currentPosition?.takeIf { it > 0 }?.let { resumeMs = it }
        releasePlayer()
        providerIndex = providers.indexOf(p).coerceAtLeast(0)
        usedCache = false   // a failure here counts against the source and moves past it
        loadingLayer.alpha = 1f
        loadingLayer.visibility = View.VISIBLE
        showStatus("Switching to ${sourceLabel(p)}")
        play(s)
    }

    /**
     * One attempt against a source: plain HTTP for resolver sources (search-based scrapers
     * like Vegamovies return the stream URL directly), a hidden WebView for embed pages.
     */
    private suspend fun searchOne(t: StreamExtractor.Target, p: StreamExtractor.Provider): WebStreamSniffer.Sniffed? {
        StreamExtractor.cached(p, t)?.let { return it }   // already found: answers the race at once
        val resolver = p.resolver
        if (resolver != null) return withTimeoutOrNull(p.timeoutMs) { runCatching { resolver.invoke(t) }.onFailure { if (it is CancellationException) throw it; Log.w(TAG, "${p.name} failed: ${it.message}") }.getOrNull() }
        val sn = WebStreamSniffer(this@PlayerActivity, root).also { sniffers += it }
        return try { StreamExtractor.sniffUrl(p, t)?.let { sn.sniff(it, p.timeoutMs) } } catch (e: CancellationException) { throw e } catch (e: Exception) { Log.w(TAG, "${p.name} page failed: ${e.message}"); null } finally { sniffers -= sn }
    }

    /**
     * Quality gate: a stream whose best variant is below 720p is skipped while other sources
     * remain, but remembered so a low-res stream still beats no stream at all.
     * Returns true when the link may play.
     */
    private suspend fun qualityGate(s: WebStreamSniffer.Sniffed): Boolean {
        if (offlineItem != null || !s.url.contains("m3u8", ignoreCase = true)) return true
        val max = QualityGate.maxHeightAsync(s)
        if (max < 0 || max >= QualityGate.MIN_HEIGHT) return true
        val p = providers.getOrNull(providerIndex)
        Log.i(TAG, "${p?.name}: best variant ${max}p is below ${QualityGate.MIN_HEIGHT}p, trying other sources")
        p?.let { LocalStore.reportProvider(it.name, false) }
        if (qualityLastResort == null) qualityLastResort = s
        providerIndex++
        return false
    }

    /** Player error / stall: re-sniff once if the link was cached, else move on. */
    private fun fallback(reason: String) {
        Log.w(TAG, "source failed: $reason")
        // Saved download broken or gone: stream it instead (once), never replay the broken file
        if (offlineItem != null) {
            offlineItem = null
            usedCache = false
            showStatus("Downloaded copy didn't play · streaming instead")
            switchSource(0)
            return
        }
        // Direct-to-origin refused: remember that server, keep this link as a last resort (its proxy
        // is slow), and move on to the next source first
        val failed = current
        if (bypassedProxy && failed != null && reason.contains("HTTP")) {
            originHost(failed.url)?.let { LocalStore.setDirectBad(it) }
            proxyLastResort = failed
        }
        val t = target
        val p = providers.getOrNull(providerIndex)
        if (t != null && p != null) {
            StreamExtractor.forget(p, t)
            if (!usedCache) { LocalStore.reportProvider(p.name, false); providerIndex++ }
            // Another stream found for this episode beats a new search
            langSources.removeAll { it.first == p }
            val want = wantedLang()
            langSources.maxByOrNull { AudioProbe.score(it.third, want ?: "orig") }?.let { (np, ns, ls) ->
                Log.i(TAG, "trying ${np.name}, already found for this episode")
                switchTo(np, ns)
                if (want != null && want !in ls)   // say why the language is gone instead of switching silently
                    showStatus("${com.anees.novamobile.data.model.LangTags.name(want) ?: want} source failed · trying ${sourceLabel(np)}")
                return
            }
        }
        usedCache = false
        switchSource(providerIndex)
    }

    /** Stop the current stream and search again from source [index], keeping the position. */
    private fun switchSource(index: Int) {
        player?.currentPosition?.takeIf { it > 0 }?.let { resumeMs = it }
        releasePlayer()
        providerIndex = index
        retryBtn.visibility = View.GONE; pickSourceBtn.visibility = View.GONE
        loadingLayer.alpha = 1f
        loadingLayer.visibility = View.VISIBLE
        streamJob?.cancel()
        streamJob = lifecycleScope.launch {
            if (target == null) target = StreamExtractor.resolveTarget(card, season, episode, repo)
            findSource()
        }
    }

    private suspend fun loadExtras() {
        val t = target ?: return
        if (card.type == MediaType.ANIME && t.malId != null) {
            val skips = StreamExtractor.animeSkips(t.malId, episode)
            introRange = skips["op"]
            outroRange = skips["ed"]
        } else if (card.type == MediaType.SERIES) {
            introRange = 30_000L..180_000L      // no intro data for series: a short-lived "Skip +85s" hint
        }
        nextEpisode = when (card.type) {
            MediaType.SERIES -> {
                val eps = repo.getSeasonEpisodes(card.id, season)
                seasonEpisodes = eps
                eps.firstOrNull { it.episodeNumber == episode }?.let { ep ->
                    if (ep.name.isNotBlank()) { episodeLine = "E$episode  ·  ${ep.name}"; updateTimes() }
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
        // Anime filler knowledge: this episode's real title + kind; "Skip Anime Filler" makes Next jump to the next story episode
        if (com.anees.novamobile.data.sources.anime.FillerInfo.applies(card) && !t.isMovie) {
            val f = com.anees.novamobile.data.sources.anime.FillerInfo.forShow(card, t.malId)
            filler = f
            f.at(season, episode)?.let { e ->
                episodeLine = listOfNotNull("E$episode", e.title, com.anees.novamobile.ui.common.FillerBadge.text(e.kind)?.lowercase()?.replaceFirstChar { it.uppercase() }).joinToString("  ·  ")
                updateTimes()
                if (e.kind.skippable) android.widget.Toast.makeText(this@PlayerActivity, "${e.kind.label} episode" +
                    (if (e.kind == com.anees.novamobile.data.sources.anime.FillerInfo.Kind.FILLER) " · not part of the manga story" else ""), android.widget.Toast.LENGTH_LONG).show()
            }
            val last = if (card.type == MediaType.ANIME) card.episodes ?: Int.MAX_VALUE else seasonEpisodes.size
            nextEpisode?.let { (ns, ne) ->
                var n = ne
                if (settings.skipFiller) while (n < last && f.at(ns, n)?.kind?.skippable == true) n++
                nextEpisode = ns to n
            }
        }
        val hasEpisodes = (card.type == MediaType.SERIES && seasonEpisodes.isNotEmpty()) || (card.type == MediaType.ANIME && !t.isMovie)
        episodesBtn.visibility = if (hasEpisodes) View.VISIBLE else View.GONE
        nextEpBtn.visibility = if (nextEpisode != null) View.VISIBLE else View.GONE
    }

    // ── Playback ──────────────────────────────────────────────────────────────

    private fun metadata(): MediaMetadata = MediaMetadata.Builder()
        .setTitle(title())
        .setArtist(if (card.type == MediaType.MOVIE) null else card.title)
        .setArtworkUri((card.backdropUrl ?: card.posterUrl)?.let { Uri.parse(it) })
        .build()

    /**
     * [bypassProxy]: fetch straight from the origin behind a source's proxy (much faster). Some
     * origins refuse direct access; then the same link is retried through the proxy (see fallback).
     */
    private fun play(s: WebStreamSniffer.Sniffed, bypassProxy: Boolean = true) {
        current = s
        if (offlineItem == null && langSources.isEmpty() && langJob?.isActive != true) gatherLanguages(s)
        val origin = originHost(s.url)
        bypassedProxy = bypassProxy && s.url.contains("proxy?") && origin != null && !LocalStore.directBad(origin)
        // Stream tokens are often locked to the IP family the WebView used (usually IPv6)
        val preferV6 = tokenPrefersV6(s.url)
        val client = com.anees.novamobile.data.api.RetrofitClient.base.newBuilder()   // shares warm connections
            .dns(object : Dns { override fun lookup(hostname: String) = Dns.SYSTEM.lookup(hostname).sortedBy { if ((it is Inet6Address) == preferV6) 0 else 1 } })
            .build()
        val http = OkHttpDataSource.Factory(client).setUserAgent(s.userAgent).setDefaultRequestProperties(s.headers)
        val dataSource = if (offlineItem != null) com.anees.novamobile.download.NovaDownloads.playbackFactory(this)
            else SubtitleCleaner.factory(this, http, client, s.headers + ("User-Agent" to s.userAgent), bypassedProxy)

        val own = s.subtitles.filterNot { SubtitleCleaner.isThumbnailTrack(it) }.take(8)
        val ownLangs = own.map { SubtitleCleaner.langOf(it) ?: "en" }
        // Source brought none (or not yours): OpenSubtitles fills in, found while sources raced
        val extra = if (offlineItem != null) emptyList() else target?.let { OpenSubs.ready(it) }.orEmpty()
            .filter { it.lang !in ownLangs }.take(3)
        val subs = own.mapIndexed { i, url ->
            MediaItem.SubtitleConfiguration.Builder(SubtitleCleaner.wrap(url))
                .setMimeType(MimeTypes.TEXT_VTT)   // the cleaner turns SubRip into WebVTT
                .setId("nsub:$i")
                .setLanguage(ownLangs[i])
                .setLabel(subLabel(url, i))
                .build()
        } + extra.mapIndexed { i, sub ->
            MediaItem.SubtitleConfiguration.Builder(SubtitleCleaner.wrap(sub.url))
                .setMimeType(MimeTypes.TEXT_VTT)
                .setId("nsub:x$i")
                .setLanguage(sub.lang)
                .setLabel("${Locale(sub.lang).displayLanguage} · OpenSubtitles")
                .build()
        }
        val item = MediaItem.Builder()
            .setUri(s.url)
            .apply {
                val u = s.url.lowercase()
                if (u.contains("m3u8")) setMimeType(MimeTypes.APPLICATION_M3U8)
                else if (u.contains(".mpd") || u.contains("dash-proxy")) setMimeType(MimeTypes.APPLICATION_MPD)
            }
            .setSubtitleConfigurations(subs)
            .setMediaMetadata(metadata())
            .build()
            .let { offlineItem ?: it }

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(15_000, 30_000, 1_000, 3_000)   // start after 1s of video
            .setTargetBufferBytes(24 * 1024 * 1024)
            .setPrioritizeTimeOverSizeThresholds(false)
            .build()
        // Low-CPU mode: tunneled playback hands A/V sync to the video chip
        val selector = androidx.media3.exoplayer.trackselection.DefaultTrackSelector(this).apply {
            setParameters(buildUponParameters().setTunnelingEnabled(settings.lowCpuMode))
        }
        // Start at the quality the connection really managed last time: no blurry first seconds, no stall
        val meter = androidx.media3.exoplayer.upstream.DefaultBandwidthMeter.Builder(this)
            .apply { LocalStore.lastBandwidth().takeIf { it > 0 }?.let { setInitialBitrateEstimate(it) } }
            .build()
        bandwidthMeter = meter
        // Audio sync: a processor in the audio sink shifts the sound against the picture
        val delay = AudioDelay().also { it.delayMs = audioDelayMs; audioDelay = it }
        val renderers = object : androidx.media3.exoplayer.DefaultRenderersFactory(this) {
            override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean) =
                androidx.media3.exoplayer.audio.DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .setAudioProcessors(arrayOf(delay))
                    .build()
        }.setEnableDecoderFallback(true)
        val exo = ExoPlayer.Builder(this, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSource))
            .setLoadControl(loadControl)
            .setTrackSelector(selector)
            .setBandwidthMeter(meter)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .setHandleAudioBecomingNoisy(true)       // headphones unplugged: pause
            .build()
        player = exo
        audioPicked = false
        playerView.player = exo
        subSync.attach(exo)
        if (offlineItem != null) offlineSubFiles.forEach { (id, label, f) -> subSync.register(id, label, { f }) }
        else {
            val subHeaders = s.headers + ("User-Agent" to s.userAgent)
            own.forEachIndexed { i, url -> subSync.register("nsub:$i", subLabel(url, i)) { SubtitleCleaner.cleaned(this, url, client, subHeaders) } }
            extra.forEachIndexed { i, sub -> subSync.register("nsub:x$i", "${Locale(sub.lang).displayLanguage} · OpenSubtitles") { SubtitleCleaner.cleaned(this, sub.url, client, emptyMap()) } }
        }
        // Headset buttons, Bluetooth, Android's media controls (also in picture-in-picture)
        session = runCatching { MediaSession.Builder(this, exo).setId("nova-${SystemClock.elapsedRealtimeNanos()}").build() }.getOrNull()
        applyTrackPrefs(exo)
        attachBoost(exo)

        exo.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_READY -> {
                        if (readyTimeout?.isActive == true) providers.getOrNull(providerIndex)?.let { LocalStore.reportProvider(it.name, true) }
                        readyTimeout?.cancel()
                        buffering.visibility = View.GONE
                        findViewById<View>(R.id.center_controls).visibility = View.VISIBLE
                        if (loadingLayer.visibility == View.VISIBLE) {
                            Log.i(TAG, "timing: first frame ${SystemClock.elapsedRealtime() - playPressedAt}ms after Play")
                            loadingLayer.animate().alpha(0f).setDuration(300).withEndAction { loadingLayer.visibility = View.GONE }.start()
                            if (!inPip) playerView.showController()
                            matchFrameRate(exo)
                        }
                    }
                    Player.STATE_BUFFERING -> if (loadingLayer.visibility != View.VISIBLE) {
                        buffering.visibility = View.VISIBLE
                        findViewById<View>(R.id.center_controls).visibility = View.INVISIBLE
                    }
                    Player.STATE_ENDED -> {
                        saveProgress()
                        when {
                            sleepAtEnd -> goToSleep()
                            nextEpisode != null && settings.autoplayNext -> playNext()
                            nextEpisode != null -> { nextDismissed = false; showNextCard(countdown = false) }
                            else -> finish()
                        }
                    }
                    else -> {}
                }
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) = updatePip()
            override fun onCues(cueGroup: androidx.media3.common.text.CueGroup) = subSync.onCues(cueGroup)
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                updatePip(); updateQualityLabel()
                // Keep the zoom after the picture's frame is re-laid out for the new size
                if (gestures.zoom > 1f) playerView.post { gestures.setZoom(gestures.zoom, showHud = false) }
            }
            override fun onPlayerError(error: PlaybackException) {
                (error.cause as? androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException)?.let {
                    Log.w(TAG, "HTTP ${it.responseCode} on ${it.dataSpec.uri.toString().take(200)}")
                }
                fallback(error.errorCodeName)
            }
            override fun onTracksChanged(tracks: Tracks) {
                updateQualityLabel()
                val audio = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
                fun langOf(f: Format) = com.anees.novamobile.data.model.LangTags.norm(f.language) ?: com.anees.novamobile.data.model.LangTags.code(f.label)
                // Remember real audio languages so cards can be tagged "Hindi · English"
                val langs = audio.flatMap { g -> (0 until g.length).mapNotNull { langOf(g.getTrackFormat(it)) } }.distinct()
                if (langs.isNotEmpty()) LocalStore.setAudioLangs(key(), langs)

                // Sources like VidLux name tracks ("Hindi") without a language code, so match by name once
                if (audioPicked || audio.isEmpty()) return
                audioPicked = true
                val want = wantedLang() ?: return   // nothing picked for this title: the stream's default track
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
                // No automatic source switching for a missing dub: other sources stay one tap away (⋯ → Source)
                Log.i(TAG, "${com.anees.novamobile.data.model.LangTags.name(want) ?: want} not in this stream; playing available audio")
            }
        })

        exo.setMediaItem(item, resumeMs)
        exo.setPlaybackSpeed(speed)
        exo.prepare()
        exo.playWhenReady = !awaitingResumeChoice

        readyTimeout?.cancel()
        readyTimeout = lifecycleScope.launch { delay(25_000); if (exo.playbackState != Player.STATE_READY) fallback("not ready in 25s") }
        startTicker()
        Log.i(TAG, if (offlineItem != null) "playing saved download" else "playing via ${providers.getOrNull(providerIndex)?.name}, subs=${subs.size}")
    }

    /** Audio language picked for this title (if any), subtitles, quality cap, low-CPU mode. */
    private fun applyTrackPrefs(exo: ExoPlayer) {
        val lang = wantedLang()
        val sub = LocalStore.subtitleFor(key())
        val subsOn = if (sub != null) sub != "off" else settings.subtitlesOn
        val cap = settings.maxQuality
        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
            .apply { if (lang != null) setPreferredAudioLanguages(lang) }
            .setPreferredTextLanguage(sub?.takeIf { it != "off" } ?: "en")
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !subsOn)
            .setMaxVideoSize(cap * 16 / 9, cap)   // a 4K stream would stutter a weak device unless allowed
            .build()
    }

    /** Quiet streams: a loudness enhancer (with built-in limiter, so loud scenes don't clip). */
    private fun attachBoost(exo: ExoPlayer) {
        loudness?.release()
        loudness = null
        val sessionId = androidx.media3.common.util.Util.generateAudioSessionIdV21(this)
        exo.setAudioSessionId(sessionId)
        loudness = runCatching { android.media.audiofx.LoudnessEnhancer(sessionId) }.getOrNull()
        applyBoost()
    }

    private fun applyBoost() {
        val mb = when (boost) { 1 -> 600; 2 -> 1000; 3 -> 1500; else -> 0 }
        loudness?.let { runCatching { it.setTargetGain(mb); it.enabled = mb > 0 } }
    }

    private fun styleSubtitles() {
        listOfNotNull(playerView.subtitleView, subOverlay).forEach { it.apply {
            setStyle(CaptionStyleCompat(Color.WHITE, Color.TRANSPARENT, Color.TRANSPARENT,
                CaptionStyleCompat.EDGE_TYPE_OUTLINE, Color.BLACK, null))
            setFractionalTextSize(when (settings.subtitleSize) { 0 -> 0.045f; 2 -> 0.072f; else -> 0.056f })
            setBottomPaddingFraction(0.08f)
        } }
    }

    /** Switch the display to a refresh rate that fits the film (e.g. 24Hz) to remove judder. */
    private fun matchFrameRate(exo: ExoPlayer) {
        if (!settings.matchFrameRate) return
        val fps = exo.videoFormat?.frameRate?.takeIf { it > 0 } ?: return
        @Suppress("DEPRECATION") val display = windowManager.defaultDisplay
        val cur = display.mode
        val best = display.supportedModes
            .filter { it.physicalWidth == cur.physicalWidth && it.physicalHeight == cur.physicalHeight }
            .filter { val r = it.refreshRate / fps; kotlin.math.abs(r - Math.round(r)) < 0.01f }
            .minByOrNull { it.refreshRate } ?: return
        if (best.modeId != cur.modeId) window.attributes = window.attributes.apply { preferredDisplayModeId = best.modeId }
    }

    // ── Controls ──────────────────────────────────────────────────────────────

    private fun setupControls() {
        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }
        findViewById<View>(R.id.loading_close).setOnClickListener { finish() }
        findViewById<View>(R.id.btn_options).setOnClickListener { moreSheet() }
        findViewById<View>(R.id.btn_tracks).setOnClickListener { tracksSheet() }
        findViewById<View>(R.id.btn_lock).setOnClickListener { setLocked(true) }
        findViewById<View>(R.id.btn_pip).apply {
            visibility = if (pipSupported()) View.VISIBLE else View.GONE
            setOnClickListener { enterPip() }
        }
        findViewById<View>(R.id.btn_rew10).setOnClickListener { player?.let { p -> p.seekBack(); seekFlash(false, 10) } }
        findViewById<View>(R.id.btn_fwd10).setOnClickListener { player?.let { p -> p.seekForward(); seekFlash(true, 10) } }
        // Quiet text buttons: 18dp icons
        listOf(R.id.btn_speed, R.id.btn_tracks, R.id.btn_quality, R.id.btn_episodes, R.id.btn_next_ep).forEach { id ->
            findViewById<TextView>(id).apply {
                val d = compoundDrawablesRelative[0]?.mutate()?.apply { setBounds(0, 0, 18.dp, 18.dp) }
                setCompoundDrawablesRelative(d, null, null, null)
            }
        }
        speedBtn.setOnClickListener { speedSheet() }
        qualityBtn.setOnClickListener { qualitySheet() }
        episodesBtn.setOnClickListener { openDrawer() }
        nextEpBtn.setOnClickListener { playNext() }
        unlockBtn.setOnClickListener { setLocked(false) }
        skipBtn.setOnClickListener { skip() }
        retryBtn.setOnClickListener { startEpisode(season, episode) }
        pickSourceBtn.setOnClickListener { sourceSheet() }
        findViewById<View>(R.id.next_play).setOnClickListener { playNext() }
        findViewById<View>(R.id.next_dismiss).setOnClickListener { nextDismissed = true; hideNextCard() }
        drawerScrim.setOnClickListener { closeDrawer() }
        findViewById<View>(R.id.next_still).apply {
            outlineProvider = object : ViewOutlineProvider() { override fun getOutline(v: View, o: Outline) = o.setRoundRect(0, 0, v.width, v.height, 8f.dp) }
            clipToOutline = true
        }
        speedBtn.text = speedLabel(speed)
        // Skip / up-next sit above the time bar while controls show, lower when they hide
        playerView.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { v ->
            val lift = if (v == View.VISIBLE) 0f else 56f.dp
            skipBtn.animate().translationY(lift).setDuration(200).start()
            nextCard.animate().translationY(lift).setDuration(200).start()
            // Subtitles move up out of the way of the time bar while controls show
            listOfNotNull(playerView.subtitleView, subOverlay).forEach { it.setBottomPaddingFraction(if (v == View.VISIBLE) 0.2f else 0.08f) }
            if (v == View.VISIBLE) updateTimes()
        })
    }

    private fun rotate() {
        requestedOrientation = if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE)
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    }

    private fun setLocked(on: Boolean) {
        locked = on
        unlockBtn.setImageResource(R.drawable.ic_lock)
        playerView.useController = !on
        if (on) {
            playerView.hideController()
            flashUnlock()
            hud(R.drawable.ic_lock, "Screen locked", null); scheduleHudHide(900)
        } else {
            unlockBtn.visibility = View.GONE
            playerView.showController()
        }
        updatePip()
    }

    private fun flashUnlock() {
        unlockBtn.animate().cancel()
        unlockBtn.visibility = View.VISIBLE
        unlockBtn.alpha = 1f
        unlockBtn.animate().alpha(0f).setStartDelay(2500).setDuration(300).withEndAction { if (locked) unlockBtn.visibility = View.GONE }.start()
    }

    private fun updateTimes() {
        val p = player ?: return
        val dur = p.duration.takeIf { it > 0 } ?: return
        val remaining = ((dur - p.currentPosition) / speed).toLong()
        val sleep = when {
            sleepAt > 0 -> "  ·  sleep in ${(sleepAt - SystemClock.elapsedRealtime()) / 60_000 + 1}m"
            sleepAtEnd -> "  ·  sleep at end"
            else -> ""
        }
        timeRemaining.text = "-${fmt(remaining)}"
        val ends = "ends ${android.text.format.DateFormat.getTimeFormat(this).format(Date(System.currentTimeMillis() + remaining))}$sleep"   // follows the phone's 12/24-hour setting
        topSub.text = if (episodeLine.isNotEmpty()) "$episodeLine  ·  $ends" else ends
    }

    private fun updateQualityLabel() {
        val p = player ?: return
        val h = p.videoSize.height.takeIf { it > 0 }
        val manual = p.trackSelectionParameters.overrides.keys.any { it.type == C.TRACK_TYPE_VIDEO }
        qualityBtn.text = when {
            offlineItem != null -> (if (offlinePartial) "Downloading" else "Downloaded") + (h?.let { " · ${it}p" } ?: "")
            h == null -> "Auto"
            manual -> "${h}p"
            else -> "Auto · ${h}p"
        }
    }

    private fun startTicker() {
        tickJob?.cancel()
        tickJob = lifecycleScope.launch {
            var n = 0
            while (isActive) {
                delay(250)
                val p = player ?: continue
                val pos = p.currentPosition
                val dur = p.duration.takeIf { it > 0 } ?: continue
                val remaining = dur - pos
                val next = nextEpisode

                // Up-next countdown fills the Play button, then plays the next episode
                if (nextCard.visibility == View.VISIBLE && nextDeadline > 0) {
                    val left = nextDeadline - SystemClock.elapsedRealtime()
                    nextCountdown.progress = (1000 - left * 1000 / UP_NEXT_MS).toInt().coerceIn(0, 1000)
                    if (left <= 0 && p.isPlaying) { playNext(); continue }
                }
                if (++n % 2 != 0) continue          // everything below: twice a second

                if (playerView.isControllerFullyVisible) updateTimes()

                // Skip intro (anime: AniSkip times; series: a hint shown 8s at most) / skip credits (anime)
                val intro = introRange
                val outro = outroRange
                val inIntro = intro != null && pos in intro && pos < intro.last - 3000
                val inOutro = outro != null && pos in outro && pos < outro.last - 3000 && (next == null || nextDismissed)
                val seriesHint = card.type == MediaType.SERIES
                if ((inIntro || inOutro) && !inPip && nextCard.visibility != View.VISIBLE) {
                    if (skipBtn.visibility != View.VISIBLE) {
                        skipBtn.text = when { inOutro -> "Skip Credits"; seriesHint -> "Skip  +85s"; else -> "Skip Intro" }
                        skipBtn.visibility = View.VISIBLE
                        skipBtn.alpha = 0f; skipBtn.animate().alpha(1f).setDuration(200).start()
                        skipShownAt = SystemClock.elapsedRealtime()
                    } else if (seriesHint && inIntro && SystemClock.elapsedRealtime() - skipShownAt > 8_000) {
                        skipBtn.visibility = View.GONE; introRange = null
                    }
                } else if (skipBtn.visibility == View.VISIBLE) skipBtn.visibility = View.GONE

                // Two minutes before the end, find the next episode's stream so "Next" starts at once
                if (next != null && !nextPrefetched && remaining in 1..120_000) {
                    nextPrefetched = true
                    Prefetcher.request(this@PlayerActivity, card, next.first, next.second, hostOverride = root)
                }
                // Up next: at the credits (when known) or 20s before the end
                val creditsAt = outro?.first ?: (dur - 20_000)
                if (next != null && !nextDismissed && pos >= creditsAt && remaining > 0 && nextCard.visibility != View.VISIBLE && !inPip)
                    showNextCard(countdown = settings.autoplayNext)

                if (n % 20 == 0) saveProgress()
            }
        }
    }

    private fun showNextCard(countdown: Boolean) {
        val next = nextEpisode ?: return
        val ep = if (next.first == season) seasonEpisodes.firstOrNull { it.episodeNumber == next.second } else null
        findViewById<TextView>(R.id.next_title).text = buildString {
            append(if (card.type == MediaType.ANIME) "Episode ${next.second}" else "S${next.first} · E${next.second}")
            val f = filler?.at(next.first, next.second)
            (f?.title ?: ep?.name)?.takeIf { it.isNotBlank() }?.let { append("  ·  $it") }
            com.anees.novamobile.ui.common.FillerBadge.text(f?.kind)?.let { append("  ·  $it") }
            if (next.first == season && next.second > episode + 1) append("  (skipped ${next.second - episode - 1} filler)")
        }
        findViewById<ImageView>(R.id.next_still).load(ep?.stillPath?.let { "https://image.tmdb.org/t/p/w300$it" } ?: card.backdropUrl)
        nextDeadline = if (countdown) SystemClock.elapsedRealtime() + UP_NEXT_MS else 0
        nextCountdown.progress = if (countdown) 0 else 1000
        findViewById<TextView>(R.id.next_play_label).text = if (countdown) "Next episode" else "Play next"
        skipBtn.visibility = View.GONE
        nextCard.visibility = View.VISIBLE
        nextCard.alpha = 0f; nextCard.translationX = 40f.dp
        nextCard.animate().alpha(1f).translationX(0f).setDuration(250).start()
    }

    private fun hideNextCard() {
        nextDeadline = 0
        nextCard.animate().alpha(0f).setDuration(150).withEndAction { nextCard.visibility = View.GONE }.start()
    }

    // ── Gesture host ──────────────────────────────────────────────────────────

    override val enabled get() = !locked && loadingLayer.visibility != View.VISIBLE && drawer.visibility != View.VISIBLE
    override val swipes get() = settings.gestures
    override fun position() = player?.currentPosition ?: 0L
    override fun duration() = player?.duration ?: 0L
    override fun seekTo(ms: Long) { player?.seekTo(ms) }
    override fun togglePlay(): Boolean { val p = player ?: return false; p.playWhenReady = !p.playWhenReady; return p.playWhenReady }
    override fun setTempSpeed(speed: Float?) { player?.setPlaybackSpeed(speed ?: this.speed) }
    override fun boost() = boost
    override fun setBoost(level: Int) { boost = level; applyBoost() }
    override fun onResize(mode: Int) { settings = settings.copy(resizeMode = mode); LocalStore.saveSettings(LocalStore.settings().copy(resizeMode = mode)) }
    override fun onLockedTap() = flashUnlock()

    override fun hud(icon: Int, text: String, fraction: Float?) {
        hudHide?.let { hud.removeCallbacks(it) }
        // Everything, brightness and volume included, shows in the pill at the top
        hud.animate().cancel()
        hudIcon.setImageResource(icon)
        hudText.text = text
        hudBar.visibility = if (fraction == null) View.GONE else View.VISIBLE
        fraction?.let { hudBar.progress = (it * 1000).toInt() }
        if (hud.visibility != View.VISIBLE || hud.alpha < 1f) { hud.visibility = View.VISIBLE; hud.animate().alpha(1f).setDuration(120).start() }
    }

    /** Phone volume keys: same pill on top instead of the system slider covering the picture. */
    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent): Boolean {
        if (keyCode != android.view.KeyEvent.KEYCODE_VOLUME_UP && keyCode != android.view.KeyEvent.KEYCODE_VOLUME_DOWN)
            return super.onKeyDown(keyCode, event)
        val audio = getSystemService(AUDIO_SERVICE) as android.media.AudioManager
        val stream = android.media.AudioManager.STREAM_MUSIC
        audio.adjustStreamVolume(stream,
            if (keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP) android.media.AudioManager.ADJUST_RAISE else android.media.AudioManager.ADJUST_LOWER, 0)
        val max = audio.getStreamMaxVolume(stream).coerceAtLeast(1)
        val cur = audio.getStreamVolume(stream)
        hud(R.drawable.ic_volume, if (cur == 0) "Muted" else "${cur * 100 / max}%", cur.toFloat() / max)
        scheduleHudHide(1200)
        return true
    }

    override fun hideHud() {
        hud.animate().alpha(0f).setDuration(200).withEndAction { hud.visibility = View.GONE }.start()
        listOf(R.id.side_left, R.id.side_right).map { findViewById<View>(it) }.filter { it.visibility == View.VISIBLE }
            .forEach { b -> b.animate().alpha(0f).setDuration(250).withEndAction { b.visibility = View.GONE }.start() }
    }

    private fun scheduleHudHide(ms: Long) {
        hudHide?.let { hud.removeCallbacks(it) }
        hudHide = Runnable { hideHud() }.also { hud.postDelayed(it, ms) }
    }

    override fun seekFlash(forward: Boolean, seconds: Int) {
        val v = if (forward) seekRight else seekLeft
        (if (forward) seekLeft else seekRight).visibility = View.GONE
        v.text = "$seconds seconds"
        v.animate().cancel()
        if (v.visibility != View.VISIBLE) { v.alpha = 0f; v.visibility = View.VISIBLE }
        v.animate().alpha(1f).setStartDelay(0).setDuration(100).withEndAction {
            v.animate().alpha(0f).setStartDelay(550).setDuration(250).withEndAction { v.visibility = View.GONE }.start()
        }.start()
    }

    // ── Picture-in-picture ────────────────────────────────────────────────────

    private fun pipSupported() = packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE)

    private fun pipParams(): PictureInPictureParams {
        val p = player
        val vs = p?.videoSize
        val ratio = if (vs != null && vs.width > 0 && vs.height > 0) {
            val r = vs.width.toFloat() * vs.pixelWidthHeightRatio / vs.height
            Rational((r.coerceIn(0.42f, 2.38f) * 1000).toInt(), 1000)
        } else Rational(16, 9)
        fun action(code: Int, icon: Int, label: String) = RemoteAction(Icon.createWithResource(this, icon), label, label,
            PendingIntent.getBroadcast(this, code, Intent(ACTION_PIP).setPackage(packageName).putExtra("a", code),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        val playing = p?.playWhenReady == true
        val b = PictureInPictureParams.Builder()
            .setAspectRatio(ratio)
            .setActions(listOf(
                action(1, R.drawable.ic_replay10, "Back 10 seconds"),
                action(2, if (playing) R.drawable.ic_pause else R.drawable.ic_play, if (playing) "Pause" else "Play"),
                action(3, R.drawable.ic_forward10, "Forward 10 seconds"),
            ))
        val r = Rect(); playerView.getGlobalVisibleRect(r); if (!r.isEmpty) b.setSourceRectHint(r)
        if (Build.VERSION.SDK_INT >= 31) {
            b.setAutoEnterEnabled(settings.autoPip && p?.isPlaying == true && !locked)
            b.setSeamlessResizeEnabled(true)
        }
        return b.build()
    }

    private fun updatePip() {
        if (!pipSupported() || player == null) return
        runCatching { setPictureInPictureParams(pipParams()) }
    }

    private fun enterPip() {
        if (!pipSupported() || player == null || loadingLayer.visibility == View.VISIBLE) return
        runCatching { enterPictureInPictureMode(pipParams()) }
    }

    /** Before Android 12 there's no auto-enter: going Home while playing opens PiP here. */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT < 31 && settings.autoPip && player?.isPlaying == true && !locked) enterPip()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip = isInPictureInPictureMode
        playerView.useController = !inPip && !locked
        if (inPip) {
            playerView.hideController()
            listOf(hud, skipBtn, nextCard, seekLeft, seekRight, unlockBtn).forEach { it.visibility = View.GONE }
            closeDrawer()
            listOfNotNull(playerView.subtitleView, subOverlay).forEach { it.setFractionalTextSize(0.08f) }
        } else {
            styleSubtitles()
            // PiP window closed with ✕: the activity is already stopped
            if (lifecycle.currentState == Lifecycle.State.CREATED) { saveProgress(); player?.pause(); finish() }
        }
    }

    // ── Episodes drawer ───────────────────────────────────────────────────────

    private fun openDrawer() {
        val list = findViewById<LinearLayout>(R.id.episodes_list)
        list.removeAllViews()
        val progress = LocalStore.episodeProgress(key())
        findViewById<TextView>(R.id.episodes_title).text = if (card.type == MediaType.ANIME) card.title else "${card.title} · Season $season"
        val rows: List<Triple<Int, String, String?>> = if (card.type == MediaType.SERIES)
            seasonEpisodes.map { Triple(it.episodeNumber, it.name, it.stillPath?.let { p -> "https://image.tmdb.org/t/p/w300$p" }) }
        else (1..(card.episodes ?: (episode + 1))).map { Triple(it, "", null) }
        var currentRow: View? = null
        rows.forEach { (num, name, still) ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(16.dp, 8.dp, 16.dp, 8.dp)
                if (num == episode) setBackgroundColor(0x1AFFFFFF)
                setOnClickListener { closeDrawer(); if (num != episode) { saveProgress(); startEpisode(season, num) } }
            }
            Ui.pressable(row, 0.98f)
            val thumb = FrameLayout(this).apply {
                setBackgroundColor(0x22FFFFFF)
                outlineProvider = object : ViewOutlineProvider() { override fun getOutline(v: View, o: Outline) = o.setRoundRect(0, 0, v.width, v.height, 6f.dp) }
                clipToOutline = true
            }
            if (still != null) thumb.addView(ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; load(still) }, FrameLayout.LayoutParams(-1, -1))
            else thumb.addView(TextView(this).apply { text = "$num"; gravity = Gravity.CENTER; setTextColor(Ui.TEXT); textSize = 15f }, FrameLayout.LayoutParams(-1, -1))
            val p = progress["$season:$num"] ?: 0f
            if (p > 0.02f) thumb.addView(ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 100; this.progress = (p * 100).toInt(); progressDrawable = ContextCompat.getDrawable(this@PlayerActivity, R.drawable.seek_track)
            }, FrameLayout.LayoutParams(-1, 3.dp, Gravity.BOTTOM))
            row.addView(thumb, LinearLayout.LayoutParams(if (still != null) 120.dp else 44.dp, if (still != null) 68.dp else 44.dp))
            row.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(12.dp, 0, 0, 0)
                addView(TextView(this@PlayerActivity).apply {
                    text = if (name.isNotBlank()) "$num. $name" else "Episode $num"
                    setTextColor(if (num == episode) Ui.ACCENT else Ui.TEXT); textSize = 14f; maxLines = 2
                })
                val status = when { num == episode -> "Playing"; p >= 0.9f -> "Watched"; p > 0.02f -> "${(p * 100).toInt()}% watched"; else -> "" }
                if (status.isNotEmpty()) addView(TextView(this@PlayerActivity).apply { text = status; setTextColor(Ui.TEXT2); textSize = 12f })
            }, LinearLayout.LayoutParams(0, -2, 1f))
            list.addView(row)
            if (num == episode) currentRow = row
        }
        playerView.hideController()
        drawerScrim.visibility = View.VISIBLE
        drawer.visibility = View.VISIBLE
        drawer.translationX = 380f.dp
        drawer.animate().translationX(0f).setDuration(220).start()
        val scroll = list.parent as ScrollView
        currentRow?.let { r -> scroll.post { scroll.smoothScrollTo(0, (r.top - 80.dp).coerceAtLeast(0)) } }
    }

    private fun closeDrawer() {
        if (drawer.visibility != View.VISIBLE) return
        drawerScrim.visibility = View.GONE
        drawer.animate().translationX(380f.dp).setDuration(180).withEndAction { drawer.visibility = View.GONE }.start()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            drawer.visibility == View.VISIBLE -> closeDrawer()
            locked -> flashUnlock()
            nextCard.visibility == View.VISIBLE -> { nextDismissed = true; hideNextCard() }
            else -> @Suppress("DEPRECATION") super.onBackPressed()
        }
    }

    private fun skip() {
        val p = player ?: return
        val pos = p.currentPosition
        val outro = outroRange
        val intro = introRange
        when {
            outro != null && pos in outro -> if (nextEpisode != null) playNext() else p.seekTo(outro.last)
            card.type == MediaType.ANIME && intro != null -> { p.seekTo(intro.last); introRange = null }
            else -> { p.seekTo(pos + 85_000); introRange = null }
        }
        skipBtn.visibility = View.GONE
    }

    private fun playNext() {
        val next = nextEpisode ?: return
        saveProgress()
        nextCard.visibility = View.GONE
        startEpisode(next.first, next.second)
    }

    /** Watched download: auto-delete / queue the next ones, only once the player stops reading the file. */
    private fun finishOfflineIfWatched() {
        val p = player ?: return
        if (offlineItem != null && p.duration > 0 && p.currentPosition > p.duration * 0.95)
            com.anees.novamobile.download.SmartDownloads.onWatched(this, card, season, episode)
    }

    private fun saveProgress() {
        val p = player ?: return
        if (p.duration <= 0) return
        LocalStore.saveProgress(HistoryEntry(card, season, episode, p.currentPosition, p.duration))
        LocalStore.setEpisodeProgress(key(), season, episode, p.currentPosition.toFloat() / p.duration)
        if (!syncedWatched && p.currentPosition > p.duration * 0.9) { syncedWatched = true; com.anees.novamobile.sync.Sync.watched(card, season, episode) }
    }

    // ── Sheets: Speed · Audio & Subtitles · Quality · More (playback keeps going) ──

    private fun speedLabel(s: Float) = if (s == 1f) "1×" else "${s.toString().removeSuffix(".0")}×"

    private fun speedSheet() {
        val exo = player ?: return
        ActionSheet.show(this, "Playback speed", SPEEDS.map { sp ->
            ActionSheet.Item(if (sp == 1f) "Normal" else speedLabel(sp), checked = speed == sp) {
                speed = sp; exo.setPlaybackSpeed(sp); speedBtn.text = speedLabel(sp)
            }
        })
    }

    /** Turning subtitles on or off here becomes the default for every title, not just this one. */
    private fun rememberSubsOn(on: Boolean) {
        if (settings.subtitlesOn == on) return
        settings = settings.copy(subtitlesOn = on)
        LocalStore.saveSettings(LocalStore.settings().copy(subtitlesOn = on))
    }

    private fun tracksSheet() {
        val exo = player ?: return
        val items = mutableListOf<ActionSheet.Item>()
        val audio = exo.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
        if (audio.sumOf { it.length } > 0) items += ActionSheet.Item("Audio", header = true)
        audio.forEach { g ->
            for (i in 0 until g.length) {
                val f = g.getTrackFormat(i)
                items += ActionSheet.Item(trackName(f, "Track ${i + 1}"), checked = g.isTrackSelected(i)) {
                    exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
                        .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, i)).build()
                    (com.anees.novamobile.data.model.LangTags.norm(f.language) ?: com.anees.novamobile.data.model.LangTags.code(f.label))
                        ?.let { LocalStore.setAudioFor(key(), it) }   // remembered for the next episode
                }
            }
        }
        // Languages only other found sources have: picking one switches source at the same second
        val have = audio.flatMap { g -> (0 until g.length).mapNotNull { i -> val f = g.getTrackFormat(i)
            com.anees.novamobile.data.model.LangTags.norm(f.language) ?: com.anees.novamobile.data.model.LangTags.code(f.label) } }.toSet()
        val extra = langSources.filter { it.second.url != current?.url }
            .flatMap { (p, s, ls) -> ls.map { Triple(it, p, s) } }
            .filter { it.first !in have }.distinctBy { it.first }
        if (extra.isNotEmpty() && audio.sumOf { it.length } == 0) items += ActionSheet.Item("Audio", header = true)
        extra.forEach { (lang, p, s) ->
            items += ActionSheet.Item("${com.anees.novamobile.data.model.LangTags.name(lang) ?: lang}  ·  ${p.name}") {
                LocalStore.setAudioFor(key(), lang)
                switchTo(p, s)
            }
        }

        val text = exo.currentTracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
        items += ActionSheet.Item("Subtitles", header = true)
        items += ActionSheet.Item("Off", checked = text.none { it.isSelected }) {
            exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
            LocalStore.setSubtitleFor(key(), "off")
            rememberSubsOn(false)
        }
        text.forEach { g ->
            for (i in 0 until g.length) {
                val f = g.getTrackFormat(i)
                items += ActionSheet.Item(trackName(f, "Subtitle ${i + 1}"), checked = g.isTrackSelected(i)) {
                    exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                        .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, i)).build()
                    LocalStore.setSubtitleFor(key(), com.anees.novamobile.data.model.LangTags.norm(f.language) ?: "en")
                    rememberSubsOn(true)
                }
            }
        }
        items += ActionSheet.Item("Sync", header = true)
        items += ActionSheet.Item("Subtitle timing  ·  ${syncLabel(subSync.offsetMs)}") { syncSheet(subtitles = true) }
        items += ActionSheet.Item("Audio timing  ·  ${syncLabel(audioDelayMs)}") { syncSheet(subtitles = false) }
        items += ActionSheet.Item("Subtitle size", header = true)
        listOf("Small", "Medium", "Large").forEachIndexed { i, label ->
            items += ActionSheet.Item(label, checked = settings.subtitleSize == i) {
                settings = settings.copy(subtitleSize = i)
                LocalStore.saveSettings(LocalStore.settings().copy(subtitleSize = i))
                styleSubtitles()
            }
        }
        ActionSheet.show(this, "Audio & Subtitles", items)
    }

    private fun syncLabel(ms: Long) = when {
        ms == 0L -> "in sync"
        ms > 0 -> "+%.1fs later".format(ms / 1000f)
        else -> "%.1fs earlier".format(ms / 1000f)
    }

    /**
     * Stepper sheet for subtitle or audio timing: each tap moves it and reopens the sheet,
     * so you can nudge until words match lips. Playback keeps going underneath.
     */
    private fun syncSheet(subtitles: Boolean) {
        val cur = if (subtitles) subSync.offsetMs else audioDelayMs
        fun apply(ms: Long) {
            val v = ms.coerceIn(-10_000, 10_000)
            if (subtitles) subSync.set(v) else { audioDelayMs = v; audioDelay?.delayMs = v }
            hud(if (subtitles) R.drawable.ic_subtitles else R.drawable.ic_volume,
                "${if (subtitles) "Subtitles" else "Audio"} ${syncLabel(v)}", null)
            scheduleHudHide(1200)
            syncSheet(subtitles)
        }
        val steps = listOf(-1000L to "−1s", -250L to "−0.25s", -50L to "−0.05s", 50L to "+0.05s", 250L to "+0.25s", 1000L to "+1s")
        val what = if (subtitles) "Subtitles" else "Sound"
        val items = mutableListOf<ActionSheet.Item>()
        items += ActionSheet.Item("Show earlier", header = true)
        steps.filter { it.first < 0 }.forEach { (d, l) -> items += ActionSheet.Item("$what $l") { apply(cur + d) } }
        items += ActionSheet.Item("Show later", header = true)
        steps.filter { it.first > 0 }.forEach { (d, l) -> items += ActionSheet.Item("$what $l") { apply(cur + d) } }
        if (cur != 0L) items += ActionSheet.Item("Reset to in sync") { apply(0) }
        val note = when {
            subtitles && subSync.laterOnly() -> "This subtitle is inside the stream: it can only move later."
            subtitles -> "Words appear before the speech? Tap Later. After it? Tap Earlier. Resets on the next episode."
            else -> "Voices before lips move? Tap Later. After? Tap Earlier. Kept for this session."
        }
        ActionSheet.show(this, "${if (subtitles) "Subtitle" else "Audio"} timing  ·  ${syncLabel(cur)}", items, subtitle = note)
    }

    private fun qualitySheet() {
        val exo = player ?: return
        val items = mutableListOf<ActionSheet.Item>()
        val manual = exo.trackSelectionParameters.overrides.keys.any { it.type == C.TRACK_TYPE_VIDEO }
        items += ActionSheet.Item("Auto (recommended)", checked = !manual) {
            exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_VIDEO).build()
        }
        exo.currentTracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }.forEach { g ->
            // Below-720p variants are hidden only when the stream really has better ones,
            // so a 480p-only last-resort stream still offers its only quality
            val maxH = (0 until g.length).map { g.getTrackFormat(it).height }.filter { it > 0 }.maxOrNull() ?: 0
            (0 until g.length).sortedByDescending { g.getTrackFormat(it).height }.forEach { i ->
                val f = g.getTrackFormat(i)
                val h = f.height
                if (h <= 0 || h > settings.maxQuality) return@forEach
                if (maxH >= QualityGate.MIN_HEIGHT && h < QualityGate.MIN_HEIGHT) return@forEach
                val mbps = f.bitrate.takeIf { it > 0 }?.let { "  ·  %.1f Mbps".format(it / 1_000_000f) } ?: ""
                items += ActionSheet.Item("${h}p$mbps", checked = manual && exo.trackSelectionParameters.overrides[g.mediaTrackGroup]?.trackIndices?.contains(i) == true) {
                    exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
                        .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, i)).build()
                }
            }
        }
        ActionSheet.show(this, "Quality", items, subtitle = when {
            offlineItem != null -> "Downloaded copy"
            items.size == 1 -> "This stream has one quality"
            else -> null
        })
    }

    private fun moreSheet() {
        val items = mutableListOf<ActionSheet.Item>()
        items += ActionSheet.Item("Sleep timer", header = true)
        val left = if (sleepAt > 0) (sleepAt - SystemClock.elapsedRealtime()) / 60_000 else 0
        items += ActionSheet.Item(if (sleepAt > 0) "On · ${left + 1} min left" else "Off", checked = sleepAt == 0L && !sleepAtEnd) { setSleep(0) }
        listOf(15, 30, 60).forEach { m -> items += ActionSheet.Item("$m minutes") { setSleep(m) } }
        items += ActionSheet.Item("End of this ${if (card.type == MediaType.MOVIE) "movie" else "episode"}", checked = sleepAtEnd) { setSleep(-1) }

        items += ActionSheet.Item("Picture", header = true)
        items += ActionSheet.Item(if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) "Rotate to portrait" else "Rotate to landscape") { rotate() }
        // Zoom presets (pinch for any value 100%–400%); subtitles stay in place at any zoom
        val fill = gestures.fillZoom()
        val stretched = playerView.resizeMode == AspectRatioFrameLayout.RESIZE_MODE_FILL
        val z = gestures.zoom
        listOf(1f to "Fit  ·  100%", fill to "Fill screen  ·  ${(fill * 100).toInt()}%", 1.25f to "125%", 1.5f to "150%", 2f to "200%").distinctBy { it.first }.forEach { (v, label) ->
            items += ActionSheet.Item(label, checked = !stretched && kotlin.math.abs(z - v) < 0.01f) { gestures.setZoom(v); scheduleHudHide(1000) }
        }
        items += ActionSheet.Item("Stretch", checked = stretched) {
            gestures.setZoom(1f, showHud = false)
            playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FILL; onResize(AspectRatioFrameLayout.RESIZE_MODE_FILL)
        }

        items += ActionSheet.Item("Volume boost", header = true)
        listOf("Off", "+6 dB", "+10 dB", "+15 dB").forEachIndexed { i, label ->
            items += ActionSheet.Item(label, checked = boost == i) {
                boost = i
                applyBoost()
                LocalStore.saveSettings(LocalStore.settings().copy(volumeBoost = i))   // quiet sources stay fixed next time
            }
        }
        if (offlineItem == null) {
            items += ActionSheet.Item("Source", header = true)
            items += ActionSheet.Item("Switch source…") { sourceSheet() }
        }
        ActionSheet.show(this, null, items)
    }

    private fun sourceSheet() {
        ActionSheet.show(this, "Source", providers.mapIndexed { i, p ->
            val ms = LocalStore.providerSpeed(p.name)
            ActionSheet.Item(p.name + (if (ms > 0) "  ·  ~${(ms + 500) / 1000}s" else "") + (if (p.multiAudio) "  ·  multi-audio" else ""),
                checked = i == providerIndex && player != null) {
                if (i != providerIndex || player == null) { usedCache = false; switchSource(i) }
            }
        }, subtitle = "If a stream stutters or lacks your language, try another")
    }

    /** minutes > 0: stop after that long; -1: at the end of this episode; 0: off. */
    private fun setSleep(minutes: Int) {
        sleepJob?.cancel()
        sleepAt = 0; sleepAtEnd = minutes < 0
        if (minutes > 0) {
            sleepAt = SystemClock.elapsedRealtime() + minutes * 60_000L
            sleepJob = lifecycleScope.launch { delay(minutes * 60_000L); goToSleep() }
        }
        hud(R.drawable.ic_pause, when {
            minutes > 0 -> "Sleep in $minutes minutes"
            minutes < 0 -> "Stops at the end"
            else -> "Sleep timer off"
        }, null)
        scheduleHudHide(1200)
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
        if (retry) {
            retryBtn.visibility = View.VISIBLE; retryBtn.requestFocus()
            pickSourceBtn.visibility = if (target != null) View.VISIBLE else View.GONE
        }
    }

    /** Reads `ip_cidr` from a JWT `token=` param if present; otherwise prefer IPv6 like Chromium does. */
    private fun tokenPrefersV6(url: String): Boolean = runCatching {
        val jwt = Uri.parse(url).getQueryParameter("token") ?: return true
        val payload = String(android.util.Base64.decode(jwt.split(".")[1], android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING))
        val cidr = Regex(""""ip_cidr"\s*:\s*"([^"]+)"""").find(payload)?.groupValues?.get(1) ?: return true
        cidr.contains(':')
    }.getOrDefault(true)

    private fun subLabel(url: String, i: Int): String =
        SubtitleCleaner.langOf(url)?.let { Locale(it).displayLanguage } ?: if (i == 0) "English" else "Subtitles ${i + 1}"

    private fun releasePlayer() {
        finishOfflineIfWatched()
        bandwidthMeter?.bitrateEstimate?.takeIf { it > 300_000 }?.let { LocalStore.setLastBandwidth(it) }
        session?.release()
        session = null
        loudness?.release()
        loudness = null
        readyTimeout?.cancel()
        tickJob?.cancel()
        player?.release()
        player = null
        playerView.player = null
        buffering.visibility = View.GONE
    }

    /** Hide status + navigation bars (swipe from the edge brings them back briefly). */
    private fun immersive() {
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        androidx.core.view.WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        if (Build.VERSION.SDK_INT >= 28) window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) immersive()
    }

    override fun onStop() {
        super.onStop()
        saveProgress()
        if (!inPip) player?.pause()      // in picture-in-picture it keeps playing
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { unregisterReceiver(pipReceiver) }
        subSync.set(0)
        streamJob?.cancel()
        sniffers.toList().forEach { it.release() }
        Prefetcher.cancel()
        releasePlayer()
        if (settings.matchFrameRate) window.attributes = window.attributes.apply { preferredDisplayModeId = originalModeId }
    }
}
