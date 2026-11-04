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
    ) {
        /** This stream plus every language variant, each flattened (no nested variants). */
        fun withVariants(): List<Sniffed> = listOf(copy(variants = null)) + variants.orEmpty().map { it.copy(variants = null) }
    }

    private val main = Handler(Looper.getMainLooper())
    private var webView: WebView? = null

    suspend fun sniff(embedUrl: String, timeoutMs: Long = 15_000): Sniffed? = try {
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
                            // Wait at most 0.5s for subtitle files (was a fixed 1.5s); a subtitle arriving ends it sooner
                            main.postDelayed({ finish(hit!!.copy(subtitles = subs.toList())) }, 500)
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
