package com.anees.novamobile.ui.player

import com.anees.novamobile.data.api.FastDns
import com.anees.novamobile.data.sources.vegamovies.VmHttp
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import okhttp3.Dns
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicInteger

class ScraperInfoTest {

    // ── Release names ─────────────────────────────────────────────────────────

    @Test fun webDlFull() {
        val m = ReleaseInfo.parse("Oppenheimer.2023.IMAX.2160p.AMZN.WEB-DL.DDP5.1.Atmos.HDR10+.H.265-FLUX.mkv")!!
        assertEquals("IMAX", m.edition)
        assertEquals("WEB-DL", m.sourceType)
        assertEquals("HEVC", m.codec)
        assertEquals("HDR10+", m.hdr)
        assertEquals("DD+ Atmos", m.audio)
        assertEquals("5.1", m.channels)
        assertEquals("Prime Video", m.service)
        assertEquals("FLUX", m.group)
        assertEquals(2160, m.height)
        assertTrue(m.badges().contains("DD+ 5.1 Atmos"))
    }

    @Test fun indianDualAudio() {
        val m = ReleaseInfo.parse("Jawan (2023) Hindi-English Dual Audio 1080p WEB-DL x264 ESub [2.4GB]")!!
        assertEquals(listOf("hi", "en"), m.langs)
        assertEquals("Dual Audio", m.audioMode)
        assertEquals("ESub", m.subs)
        assertEquals("x264", m.codec)
        assertEquals((2.4 * 1024 * 1024 * 1024).toLong(), m.bytes)
        assertNull(m.group)
    }

    @Test fun remuxTrueHd() {
        val m = ReleaseInfo.parse("Dune.Part.Two.2024.1080p.BluRay.REMUX.AVC.TrueHD.7.1.Atmos-FraMeSToR")!!
        assertEquals(true, m.remux)
        assertEquals("TrueHD Atmos", m.audio)
        assertEquals("7.1", m.channels)
        assertEquals("FraMeSToR", m.group)
        assertEquals("REMUX", m.badges().first())
    }

    @Test fun episodes() {
        ReleaseInfo.parse("The.Boys.S04E03.1080p.WEB.h264-ETHEL")!!.let { assertEquals(4, it.season); assertEquals(3, it.episode) }
        ReleaseInfo.parse("[SubsPlease] Frieren - 12 (1080p) [ABCD1234].mkv")!!.let { assertEquals(12, it.episode); assertEquals("SubsPlease", it.group) }
        ReleaseInfo.parse("Show 2x05 720p HDTV")!!.let { assertEquals(2, it.season); assertEquals(5, it.episode) }
        // a year after " - " is not an episode
        assertNull(ReleaseInfo.parse("Inception - 2010 [1080p]")!!.episode)
    }

    @Test fun moreFields() {
        val m = ReleaseInfo.parse("Movie 2022 720p 60fps HDTC Multi Audio [Hindi+Tamil+Telugu] AAC2.0 HC Proper")!!
        assertEquals(60, m.fps)
        assertEquals("TC", m.sourceType)
        assertTrue(m.isCam)
        assertEquals("Multi Audio", m.audioMode)
        assertEquals("AAC", m.audio)
        assertEquals("2.0", m.channels)
        assertEquals("Hardsub", m.subs)
        assertEquals(listOf("Proper"), m.flags)
        assertEquals(listOf("hi", "ta", "te"), m.langs)
    }

    @Test fun orElseKeepsAllFields() {
        val a = StreamMeta(codec = "HEVC", flags = listOf("Proper"))
        val b = StreamMeta(codec = "x264", audio = "DD+", channels = "5.1", fps = 24, flags = listOf("Repack"))
        val m = a.orElse(b)
        assertEquals("HEVC", m.codec); assertEquals("DD+", m.audio); assertEquals("5.1", m.channels); assertEquals(24, m.fps)
        assertEquals(listOf("Proper", "Repack"), m.flags)
    }

    // ── HLS master ────────────────────────────────────────────────────────────

    private val master = """
        #EXTM3U
        #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud",LANGUAGE="en",NAME="English",CHANNELS="16/JOC",URI="a_en.m3u8"
        #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud",LANGUAGE="hi",NAME="Hindi",CHANNELS="2",URI="a_hi.m3u8"
        #EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="subs",LANGUAGE="en",NAME="English",URI="s_en.m3u8"
        #EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="subs",LANGUAGE="es",NAME="Spanish",URI="s_es.m3u8"
        #EXT-X-STREAM-INF:BANDWIDTH=2500000,AVERAGE-BANDWIDTH=2000000,RESOLUTION=1280x720,CODECS="avc1.64001f,ec-3",FRAME-RATE=23.976,AUDIO="aud"
        v720.m3u8
        #EXT-X-STREAM-INF:BANDWIDTH=9000000,RESOLUTION=3840x2160,CODECS="dvh1.05.06,ec-3",FRAME-RATE=24.000,VIDEO-RANGE=PQ,AUDIO="aud"
        v2160.m3u8
    """.trimIndent()

    @Test fun hlsMaster() {
        val r = HlsInfo.parse(master)!!
        assertEquals(2160, r.meta.height)
        assertEquals("HEVC", r.meta.codec)
        assertEquals("Dolby Vision", r.meta.hdr)
        assertEquals("DD+ Atmos", r.meta.audio)
        assertEquals("7.1", r.meta.channels)
        assertEquals(9000, r.meta.bitrateKbps)
        assertEquals(24, r.meta.fps)
        assertEquals("2 subs", r.meta.subs)
        assertEquals("Dual Audio", r.meta.audioMode)
        assertEquals(setOf(720, 2160), r.variants.keys)
        assertEquals("x264", r.variants[720]!!.codec)
        assertEquals(2000, r.variants[720]!!.bitrateKbps)   // AVERAGE-BANDWIDTH preferred
    }

    @Test fun hlsAttrsKeepQuotedCommas() {
        val a = HlsInfo.attrs("""#EXT-X-STREAM-INF:BANDWIDTH=1,CODECS="hvc1.2.4.L120,mp4a.40.2",RESOLUTION=1920x1080""")
        assertEquals("hvc1.2.4.L120,mp4a.40.2", a["CODECS"]); assertEquals("1920x1080", a["RESOLUTION"])
        val r = HlsInfo.parse("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1,CODECS=\"hvc1.2.4.L120,mp4a.40.2\",RESOLUTION=1920x1080\nx.m3u8")!!
        assertEquals("10-bit", r.meta.hdr); assertEquals("AAC", r.meta.audio)
        assertNull(HlsInfo.parse("#EXTM3U\n#EXTINF:4,\nseg.ts"))
    }

    // ── API JSON ──────────────────────────────────────────────────────────────

    @Test fun jsonMeta() {
        val m = JsonMeta.from(JSONObject("""{"file_name":"Film.2021.1080p.NF.WEB-DL.DDP5.1.H.264-GRP.mkv","bitrate":4500000,"duration":"7200","size":"3221225472"}"""))!!
        assertEquals("Netflix", m.service); assertEquals("DD+", m.audio); assertEquals("5.1", m.channels)
        assertEquals(4500, m.bitrateKbps); assertEquals(7200, m.durationSec); assertEquals(3221225472L, m.bytes); assertEquals(1080, m.height)
        assertNull(JsonMeta.from(null))
    }

    // ── DNS ───────────────────────────────────────────────────────────────────

    @Test fun dnsCachesPrefersV4AndBeatsIspBlock() {
        val calls = AtomicInteger()
        val v6 = InetAddress.getByName("2606:4700::1111"); val v4 = InetAddress.getByName("104.16.1.1")
        FastDns.system = object : Dns { override fun lookup(hostname: String): List<InetAddress> { val h = hostname; calls.incrementAndGet(); return when (h) { "blocked.test" -> listOf(InetAddress.getByName("127.0.0.1")); "fail.test" -> throw UnknownHostException(h); else -> listOf(v6, v4) } } }
        FastDns.dohLookup = { h -> if (h == "blocked.test") listOf(InetAddress.getByName("172.67.1.1").let { InetAddress.getByAddress(h, it.address) }) else emptyList() }
        assertEquals(v4, FastDns.lookup("site.test").first())         // IPv4 first
        FastDns.lookup("site.test")
        assertEquals(1, calls.get())                                    // cached
        assertEquals("172.67.1.1", FastDns.lookup("blocked.test").first().hostAddress)   // sinkhole → DoH
        assertThrows(UnknownHostException::class.java) { FastDns.lookup("fail.test") }
        FastDns.system = Dns.SYSTEM
    }

    @Test fun carrierBlockPageSwitchesHostToDoh() {
        val forged = InetAddress.getByAddress("forged.test", byteArrayOf(203.toByte(), 0, 113, 9))   // public block page
        val real = InetAddress.getByAddress("forged.test", byteArrayOf(104, 16, 2, 2))
        FastDns.system = object : Dns { override fun lookup(hostname: String) = listOf(forged) }
        FastDns.dohLookup = { listOf(real) }
        assertEquals(forged, FastDns.lookup("forged.test").first())     // looks fine to the resolver
        assertTrue(FastDns.suspectBlock("forged.test"))                 // TLS failed → retry once
        assertEquals(real, FastDns.lookup("forged.test").first())       // now via DoH
        assertFalse(FastDns.suspectBlock("forged.test"))                // no endless retries
        assertFalse(FastDns.suspectBlock("10.0.0.1"))                   // raw IPs never
        FastDns.system = Dns.SYSTEM
    }

    // ── Page cache / in-flight dedupe ─────────────────────────────────────────

    @Test fun sameGetIsFetchedOnce() = runBlocking {
        val hits = AtomicInteger()
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse { hits.incrementAndGet(); Thread.sleep(150); return MockResponse().setBody("<html>ok</html>") }
        }
        server.start()
        try {
            VmHttp.PageCache.clear()
            val url = server.url("/page").toString()
            val bodies = (1..6).map { async(kotlinx.coroutines.Dispatchers.IO) { VmHttp.get(url) } }.awaitAll()
            assertTrue(bodies.all { it == "<html>ok</html>" })
            assertEquals(VmHttp.get(url), "<html>ok</html>")
            assertEquals(1, hits.get())
        } finally { server.shutdown() }
    }
}
