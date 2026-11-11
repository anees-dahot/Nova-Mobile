package com.anees.novamobile.ui.player

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.ViewGroup
import android.webkit.*
import android.widget.FrameLayout
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayInputStream
import kotlin.coroutines.resume

/**
 * Loads an embed page in a hidden WebView and captures the first real video request
 * (.m3u8 / .mp4) plus any subtitle files. Ads, trackers, images and pop-ups are blocked.
 * The WebView is created per sniff and destroyed right after, so it costs no RAM during playback.
 */
class WebStreamSniffer(private val ctx: Context, private val host: FrameLayout) {

    data class Sniffed(
        val url: String,
        val headers: Map<String, String>,
        val userAgent: String,
        val subtitles: List<String>,
        /** Audio language of a single-language stream when the source says so ("hi"); null = read the manifest. */
        val audioLang: String? = null,
        /**
         * Same episode in other languages as separate streams (CastleTV, MovieBox dubs). They join the
         * language menu as their own entries; picking one switches stream at the same second.
         */
        val variants: List<Sniffed>? = null,
        /** Picture height the source states (CastleTV "HD 720P", MovieBox resolution); null = read the stream. */
        val height: Int? = null,
        /**
         * Every file the source offers for this stream, one per quality (MovieBox / NetMirror MP4s),
         * so the download sheet can list real qualities with sizes; null = only [url].
         */
        val options: List<Option>? = null,
        /** What the source says about this stream (release name, cut, CAM/WEB-DL, codec, server, subtitle languages…). */
        val meta: StreamMeta? = null,
    ) {
        /** One downloadable file: its own url, picture height, (if the source says) size, and its own release details. */
        data class Option(val url: String, val height: Int, val bytes: Long? = null, val meta: StreamMeta? = null)
        /** This stream plus every language variant, each flattened (no nested variants). */
        fun withVariants(): List<Sniffed> = listOf(copy(variants = null)) + variants.orEmpty().map { it.copy(variants = null) }
    }

    private val main = Handler(Looper.getMainLooper())
    private var webView: WebView? = null

    /**
     * Find the stream on [embedUrl]: the fast HTTP tier ([UniversalExtractor]) and the WebView race,
     * first real answer wins. The WebView waits [FAST_GRACE_MS] so pages HTTP can read never pay for it.
     */
    suspend fun sniff(embedUrl: String, timeoutMs: Long = 15_000): Sniffed? = withTimeoutOrNull(timeoutMs) {
        if (!UniversalExtractor.worthTrying(embedUrl)) return@withTimeoutOrNull sniffWeb(embedUrl, timeoutMs)
        val ua = runCatching { android.webkit.WebSettings.getDefaultUserAgent(ctx) }.getOrDefault("Mozilla/5.0 (Linux; Android 14) Chrome/130 Mobile Safari/537.36")
        kotlinx.coroutines.coroutineScope {
            val fast = async { runCatching { UniversalExtractor.extract(embedUrl, ua) }.getOrNull() }
            withTimeoutOrNull(FAST_GRACE_MS) { fast.await() }?.let { return@coroutineScope it }
            val web = async { sniffWeb(embedUrl, timeoutMs) }
            // First non-null wins; a null from one side just leaves the other running
            val winner = kotlinx.coroutines.CompletableDeferred<Sniffed?>()
            val left = java.util.concurrent.atomic.AtomicInteger(2)
            listOf(fast, web).forEach { d ->
                launch { val r = runCatching { d.await() }.getOrNull()
                    if (r != null) winner.complete(r) else if (left.decrementAndGet() == 0) winner.complete(null) }
            }
            winner.await().also { fast.cancel(); web.cancel(); coroutineContext.cancelChildren() }
        }
    }

    private suspend fun sniffWeb(embedUrl: String, timeoutMs: Long): Sniffed? = try {
        withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<Sniffed?> { cont ->
                val subs = linkedSetOf<String>()
                var requests = 0
                var hit: Sniffed? = null
                val embedHost = Uri.parse(embedUrl).host ?: ""

                fun finish(value: Sniffed?) {
                    if (cont.isActive) cont.resume(value)
                }

                val wv = createWebView()
                webView = wv
                val ua = wv.settings.userAgentString
                // Players often sit in an iframe from another site, out of reach of evaluateJavascript:
                // a script runs in every frame and reports what its player knows here
                val frameInfo = mutableListOf<String>()
                runCatching {
                    if (androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.WEB_MESSAGE_LISTENER) &&
                        androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.DOCUMENT_START_SCRIPT)) {
                        androidx.webkit.WebViewCompat.addWebMessageListener(wv, "novaInfo", setOf("*")) { _, msg, origin, _, _ ->
                            msg.data?.takeIf { it.length < 200_000 }?.let { synchronized(frameInfo) { frameInfo += it } }
                            Log.d(TAG, "frame info from $origin")
                        }
                        androidx.webkit.WebViewCompat.addDocumentStartJavaScript(wv, FRAME_JS, setOf("*"))
                    }
                }.onFailure { Log.w(TAG, "frame scripts unavailable: ${it.message}") }

                wv.webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView, req: WebResourceRequest): WebResourceResponse? {
                        val url = req.url.toString()
                        val lower = url.lowercase()
                        requests++
                        if (isBlocked(lower)) return EMPTY()

                        if (SUB_RE.containsMatchIn(lower)) {
                            subs.add(url)
                            // Stream already found and waiting only for subtitles: done now
                            hit?.let { h -> main.post { finish(h.copy(subtitles = subs.toList())) } }
                        }

                        if (hit == null && MEDIA_RE.containsMatchIn(lower) && !isBlocked(lower) && !AD_VIDEO_RE.containsMatchIn(lower)) {
                            val referer = req.requestHeaders["Referer"] ?: req.requestHeaders["referer"] ?: embedUrl
                            val origin = Uri.parse(referer).let { "${it.scheme}://${it.host}" }
                            hit = Sniffed(url, mapOf("Referer" to referer, "Origin" to origin), ua, emptyList())
                            Log.i(TAG, "captured from $embedHost: ${url.take(100)}")
                            // Wait at most 0.5s for subtitle files (was a fixed 1.5s); a subtitle arriving ends it sooner.
                            // Meanwhile ask the page's own player what it knows: source labels ("Hindi"), audio
                            // track names, subtitle names, title (streams themselves rarely carry languages)
                            main.postDelayed({
                                if (!cont.isActive) return@postDelayed
                                var done = false
                                val give = { info: String? -> if (!done) { done = true
                                    finish(enrich(hit!!.copy(subtitles = subs.toList()), listOfNotNull(info?.let { decodeJs(it) }) + synchronized(frameInfo) { frameInfo.toList() })) } }
                                runCatching { wv.evaluateJavascript(PLAYER_INFO_JS) { r -> give(r) } }.onFailure { give(null) }
                                main.postDelayed({ give(null) }, 700)
                            }, 500)
                        }
                        return null
                    }

                    override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
                        // Ads love to hijack the top frame; only allow the embed host there
                        val h = req.url.host ?: return true
                        // Server redirects (a source moving domains) are fine; script-driven hops to ad sites aren't
                        if (req.isRedirect) return false
                        return req.isForMainFrame && !h.endsWith(embedHost.removePrefix("www."))
                    }

                    override fun onPageFinished(view: WebView, url: String) {
                        Log.i(TAG, "page loaded ($requests requests so far): ${url.take(80)}")
                        view.evaluateJavascript(MUTE_JS, null)
                    }
                }

                wv.webChromeClient = object : WebChromeClient() {
                    override fun onCreateWindow(v: WebView, dialog: Boolean, user: Boolean, msg: Message) = false
                }

                // Many players wait for a click on their big play button: tap the centre every 2.5s
                val tapper = object : Runnable {
                    override fun run() {
                        if (!cont.isActive || hit != null) return
                        tap(wv)
                        main.postDelayed(this, 2500)
                    }
                }
                main.postDelayed(tapper, 1500)

                cont.invokeOnCancellation {
                    main.removeCallbacks(tapper)
                    Log.i(TAG, "timed out on $embedHost after $requests requests")
                }
                Log.i(TAG, "sniffing $embedUrl")
                wv.loadUrl(embedUrl)
            }
        }
    } finally {
        // Also runs when a faster source wins the race and this search is cancelled
        release()
    }

    /**
     * Folds what the page's player said into the stream: the label of the playing source as its audio
     * language, other labelled sources as language variants, audio track names, subtitle names.
     */
    /** evaluateJavascript hands back a JS string literal ("\"{...}\""). */
    private fun decodeJs(raw: String): String? = raw.takeIf { it.length > 4 && it != "null" }?.let { runCatching { org.json.JSONArray("[$it]").getString(0) }.getOrNull() }

    private fun enrich(s: Sniffed, infos: List<String>): Sniffed = runCatching {
        // Every frame's report merged: sources, tracks, audio names; the first title
        val o = org.json.JSONObject().apply { put("src", org.json.JSONArray()); put("tr", org.json.JSONArray()); put("at", org.json.JSONArray()) }
        infos.mapNotNull { runCatching { org.json.JSONObject(it) }.getOrNull() }.forEach { f ->
            listOf("src", "tr", "at").forEach { k -> f.optJSONArray(k)?.let { a -> for (i in 0 until a.length()) o.getJSONArray(k).put(a.get(i)) } }
            if (!o.has("t") && f.optString("t").isNotBlank()) o.put("t", f.optString("t"))
        }
        if (infos.isEmpty()) return s
        fun lang(label: String?) = ReleaseInfo.langCode(label).takeIf { it.length == 2 }
        fun abs(u: String) = runCatching { java.net.URI(s.headers["Referer"] ?: s.url).resolve(u).toString() }.getOrDefault(u)
        val sources = o.optJSONArray("src")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }.orEmpty()
            .mapNotNull { x -> x.optString("f").takeIf { it.isNotBlank() }?.let { abs(it) to x.optString("l") } }
        val playing = sources.firstOrNull { it.first == s.url } ?: sources.firstOrNull()
        val audioLang = s.audioLang ?: lang(playing?.second)
        val variants = sources.filter { it.first != s.url && lang(it.second) != null && lang(it.second) != audioLang && MEDIA_RE.containsMatchIn(it.first.lowercase()) }
            .distinctBy { lang(it.second) }.map { (u, l) -> s.copy(url = u, audioLang = lang(l), variants = null) }
        val audioNames = o.optJSONArray("at")?.let { a -> (0 until a.length()).mapNotNull { lang(a.optString(it)) } }.orEmpty().distinct()
        val tracks = o.optJSONArray("tr")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }.orEmpty()
            .filter { it.optString("k").let { k -> k.isBlank() || k == "captions" || k == "subtitles" } }
            .mapNotNull { t -> t.optString("f").takeIf { it.isNotBlank() && !it.contains("thumbnail", true) }?.let { abs(it) to (lang(t.optString("l")) ?: "") } }
        val subs = (s.subtitles.map { u -> u to (tracks.firstOrNull { it.first == u }?.second ?: "") } +
            tracks.filter { t -> s.subtitles.none { it == t.first } }).distinctBy { it.first }.take(12)
        val title = o.optString("t").takeIf { it.isNotBlank() }
        Log.i(TAG, "player info: sources=${sources.map { it.second }} audio=$audioNames subs=${subs.map { it.second }}")
        val meta = (s.meta ?: StreamMeta()).let { m ->
            m.copy(langs = audioNames.takeIf { it.isNotEmpty() } ?: m.langs, release = m.release ?: title,
                subLangs = subs.map { it.second }.takeIf { l -> l.any { it.isNotEmpty() } } ?: m.subLangs)
        }
        s.copy(audioLang = audioLang, subtitles = subs.map { it.first }, meta = meta,
            variants = (s.variants.orEmpty() + variants).takeIf { it.isNotEmpty() })
    }.getOrElse { Log.w(TAG, "player info unreadable: ${it.message}"); s }

    fun release() {
        main.removeCallbacksAndMessages(null)
        webView?.let {
            it.stopLoading()
            it.loadUrl("about:blank")
            host.removeView(it)
            it.destroy()
        }
        webView = null
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(): WebView = WebView(ctx).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.setSupportMultipleWindows(true)
        settings.javaScriptCanOpenWindowsAutomatically = false
        settings.loadsImagesAutomatically = false
        settings.blockNetworkImage = true
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
        // Real size (players lay out to it) but drawn behind the opaque PlayerView
        alpha = 0.01f
        host.addView(this, 0, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private fun tap(v: WebView) {
        val x = v.width / 2f
        val y = v.height / 2f
        val t = SystemClock.uptimeMillis()
        MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0).also { v.dispatchTouchEvent(it); it.recycle() }
        MotionEvent.obtain(t, t + 50, MotionEvent.ACTION_UP, x, y, 0).also { v.dispatchTouchEvent(it); it.recycle() }
    }

    private fun isBlocked(url: String) = BLOCK.any { url.contains(it) } || IMG_RE.containsMatchIn(url)

    companion object {
        private const val TAG = "NovaTV"
        private const val FAST_GRACE_MS = 1_200L
        /** What the page's player knows (JW Player, hls.js, video.js, plain <video>), as JSON. */
        private val PLAYER_INFO_JS = """(function(){try{var o={src:[],tr:[],at:[]};
var jw=window.jwplayer&&jwplayer();if(jw&&jw.getPlaylist){var pl=jw.getPlaylist()||[];var it=pl[jw.getPlaylistIndex?jw.getPlaylistIndex():0]||pl[0]||{};
o.t=it.title||'';(it.sources||[]).forEach(function(x){o.src.push({f:x.file,l:x.label||x.language||''})});
(it.tracks||[]).forEach(function(x){o.tr.push({f:x.file,l:x.label||x.language||'',k:x.kind||''})});
(jw.getAudioTracks?jw.getAudioTracks()||[]:[]).forEach(function(a){o.at.push(a.language||a.name||a.label||'')});}
var h=window.hls||window.Hls&&window.player&&window.player.hls;if(h&&h.audioTracks){h.audioTracks.forEach(function(a){o.at.push(a.lang||a.name||'')});
(h.subtitleTracks||[]).forEach(function(x){o.tr.push({f:x.url||'',l:x.lang||x.name||'',k:'subtitles'})});}
var vj=window.videojs&&videojs.getPlayers&&videojs.getPlayers();if(vj){for(var k in vj){var p=vj[k];if(!p)continue;var at=p.audioTracks&&p.audioTracks();
if(at)for(var i=0;i<at.length;i++)o.at.push(at[i].language||at[i].label||'');var tt=p.remoteTextTracks&&p.remoteTextTracks();
if(tt)for(var j=0;j<tt.length;j++)o.tr.push({f:tt[j].src||'',l:tt[j].language||tt[j].label||'',k:tt[j].kind||''});}}
document.querySelectorAll('video track').forEach(function(t){o.tr.push({f:t.src,l:t.srclang||t.label||'',k:t.kind||''})});
return JSON.stringify(o)}catch(e){return ''}})()"""
        /** Runs in every frame from the start: reports its player's info (when it has any) once a second for 40s. */
        private val FRAME_JS = "(function(){if(!window.novaInfo)return;var last='';var n=0;var t=setInterval(function(){n++;" +
            "try{var j=" + PLAYER_INFO_JS + ";if(j&&j!==last&&j.indexOf('\"f\"')>0||j&&j!==last&&j.indexOf('\"at\":[\"')>0){last=j;novaInfo.postMessage(j)}}catch(e){}" +
            "if(n>40)clearInterval(t)},1000)})()"
        private val MEDIA_RE = Regex("""\.m3u8|\.mp4(\?|$)""")
        private val SUB_RE = Regex("""\.(vtt|srt)(\?|$)""")
        /** Ad / preroll videos that some players load before the real stream. */
        private val AD_VIDEO_RE = Regex("""interstitial|preroll|/vast|vast\.|/ads?/|[_-]ad[_-]|demo-video|gambling""")
        private val IMG_RE = Regex("""\.(png|jpe?g|gif|webp|svg|ico|woff2?|ttf)(\?|$)""")
        private val BLOCK = listOf(
            "doubleclick", "googlesyndication", "adservice", "google-analytics", "googletagmanager",
            "histats", "popads", "propeller", "adsterra", "exoclick", "juicyads", "mgid.com", "taboola",
            "outbrain", "adcash", "hilltopads", "clickadu", "a-ads.com", "bidgear", "onclicka",
            "/__ads/", "/ads/", "adskeeper", "yandex", "disqus", "cloudflareinsights", "pushnotif", "push-"
        )
        private const val MUTE_JS =
            "setInterval(function(){document.querySelectorAll('video,audio').forEach(function(v){v.muted=true;})},300);"
        private fun EMPTY() = WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
    }
}
