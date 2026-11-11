package com.anees.novamobile.ui.player

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class UniversalExtractorTest {
    private val ua = "Mozilla/5.0 (Linux; Android 14) Chrome/130 Mobile Safari/537.36"
    private lateinit var server: MockWebServer
    private val pages = HashMap<String, MockResponse>()
    private fun url(path: String) = server.url(path).toString()
    private fun html(body: String) = MockResponse().setHeader("Content-Type", "text/html").setBody(body)
    private val m3u8 get() = MockResponse().setHeader("Content-Type", "application/vnd.apple.mpegurl").setBody("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\nlow.m3u8\n")

    @Before fun up() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = request.path!!.substringBefore('?').let { p -> pages[p] ?: pages.entries.firstOrNull { it.key.endsWith("*") && p.startsWith(it.key.dropLast(1)) }?.value } ?: MockResponse().setResponseCode(404)
        }
        server.start()
    }
    @After fun down() = server.shutdown()

    @Test fun unpacksPackerScript() {
        // Real P.A.C.K.E.R output of: var s="https://cdn.x/a.m3u8";
        val packed = "eval(function(p,a,c,k,e,d){while(c--)if(k[c])p=p.replace(new RegExp('\\\\b'+c.toString(a)+'\\\\b','g'),k[c]);return p}('0 1=\"2://3.4/5.6\";',7,7,'var|s|https|cdn|x|a|m3u8'.split('|'),0,{}))"
        assertEquals("var s=\"https://cdn.x/a.m3u8\";", UniversalExtractor.unpack(packed))
    }

    @Test fun scanFindsEscapedRelativeAndAtob() {
        val b64 = java.util.Base64.getEncoder().encodeToString("https://h.io/b64.mp4".toByteArray())
        val f = UniversalExtractor.scan("""
            <script>var a={"file":"https:\/\/h.io\/esc.m3u8"};var b=atob("$b64");</script>
            <video><source src="/rel/v.mp4"></video><track src="subs/en.vtt">
            <iframe data-src="//player.io/e/1"></iframe><script type="application/ld+json">{"embedUrl":"https://emb.io/x"}</script>
            <a href="https://doubleclick.net/ad.mp4">x</a>
        """.trimIndent(), "https://site.io/watch/1")
        assertTrue(f.streams.toString(), "https://h.io/esc.m3u8" in f.streams)
        assertTrue(f.streams.toString(), "https://h.io/b64.mp4" in f.streams)
        assertTrue(f.streams.toString(), "https://site.io/rel/v.mp4" in f.streams)
        assertFalse("ads filtered", f.streams.any { "doubleclick" in it })
        assertTrue(f.subs.toString(), "https://site.io/watch/subs/en.vtt" in f.subs)
        assertTrue(f.iframes.toString(), "https://player.io/e/1" in f.iframes)
        assertTrue(f.iframes.toString(), "https://emb.io/x" in f.iframes)
    }

    @Test fun streamtapeLink() {
        val html = """document.getElementById('robotlink').innerHTML = '//streamtape.com/get_video?id=abc&expires=1&ip=x&token=' + ('xyzTOKEN').substring(1).substring(2);"""
        assertEquals("https://streamtape.com/get_video?id=abc&expires=1&ip=x&token=TOKEN&stream=1", UniversalExtractor.hostSpecific("https://streamtape.com/e/abc", html, ua))
    }

    @Test fun followsIframeChainAndVerifies() = runBlocking {
        pages["/embed"] = html("""<iframe src="/player"></iframe>""")
        pages["/player"] = html("""<script>jwplayer().setup({sources:[{file:"/hls/master.m3u8"}]});</script>""")
        pages["/hls/master.m3u8"] = m3u8
        val r = UniversalExtractor.extract(url("/embed"), ua)
        assertNotNull(r); assertEquals(url("/hls/master.m3u8"), r!!.url)
        assertEquals(url("/player"), r.headers["Referer"])
    }

    @Test fun rejectsFakeAndTinyFiles() = runBlocking {
        pages["/p"] = html("""<script>var a="/fake.m3u8", b="/tiny.mp4", c="/real.mp4";</script>""")
        pages["/fake.m3u8"] = html("<html>404</html>")
        pages["/tiny.mp4"] = MockResponse().setHeader("Content-Type", "video/mp4").setResponseCode(206).setHeader("Content-Range", "bytes 0-9/50000").setBody("0123456789")
        pages["/real.mp4"] = MockResponse().setHeader("Content-Type", "video/mp4").setResponseCode(206).setHeader("Content-Range", "bytes 0-9/900000000").setBody("0123456789")
        assertEquals(url("/real.mp4"), UniversalExtractor.extract(url("/p"), ua)?.url)
    }

    @Test fun doodstreamFlow() = runBlocking {
        pages["/e/xyz"] = html("""<script>${'$'}.get('/pass_md5/111/abc', function(d){ x = d + makePlay() + "?token=t0k3n&expiry=" })</script>""")
        pages["/pass_md5/111/abc"] = MockResponse().setBody(url("/dl/"))
        pages["/dl/*"] = MockResponse().setHeader("Content-Type", "video/mp4").setResponseCode(206).setHeader("Content-Range", "bytes 0-9/90000000").setBody("0123456789")
        val r = UniversalExtractor.extract(url("/e/xyz"), ua)
        assertNotNull(r); assertTrue(r!!.url, r.url.startsWith(url("/dl/")) && "token=t0k3n" in r.url)
    }

    @Test fun pageNeedingJsReturnsNull() = runBlocking {
        pages["/spa"] = html("""<div id="root"></div><script src="/app.js"></script>""")
        assertNull(UniversalExtractor.extract(url("/spa"), ua))
    }

    /** Live: real embed pages. Sites change, so this reports instead of failing. */
    @Test fun liveRealSites() = runBlocking {
        val urls = listOf(
            "https://vidlux.xyz/embed/movie/27205", "https://vidly.pro/movie/27205", "https://vidsrc.to/embed/movie/27205",
            "https://vidlink.pro/movie/27205", "https://vidsrc.cc/v2/embed/movie/27205", "https://player.videasy.net/movie/27205",
            "https://vidfast.pro/movie/27205", "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8",
            "https://bitdash-a.akamaihd.net/content/sintel/hls/playlist.m3u8",
        )
        for (u in urls) {
            val t0 = System.currentTimeMillis()
            val r = runCatching { UniversalExtractor.extract(u, ua) }.getOrNull()
            println("LIVE ${if (r != null) "HIT " else "miss"} ${System.currentTimeMillis() - t0}ms $u -> ${r?.url?.take(100)}")
        }
    }
}
