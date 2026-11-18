package com.anees.novamobile.data.sources

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Test

class LinkProbeTest {
    private fun probe(vararg responses: MockResponse): List<Boolean> = MockWebServer().use { s ->
        responses.forEach { s.enqueue(it) }
        s.start()
        responses.indices.map { runBlocking { LinkProbe.alive(s.url("/f$it").toString()) } }
    }

    private fun bytes(code: Int, type: String) =
        MockResponse().setResponseCode(code).setHeader("Content-Type", type).setBody(Buffer().write(ByteArray(4096) { 1 }))

    @Test fun tellsLiveFromDead() {
        val got = probe(
            bytes(206, "video/x-matroska"),                                    // live, honours Range
            bytes(200, "application/octet-stream"),                            // live, ignores Range
            MockResponse().setResponseCode(404).setBody("{\"success\":false}"), // deleted PixelDrain file
            MockResponse().setResponseCode(500),                                // dead 10Gbps worker
            MockResponse().setResponseCode(200).setHeader("Content-Type", "text/html").setBody("<html>blocked</html>"),
            MockResponse().setResponseCode(200).setHeader("Content-Type", "application/octet-stream").setBody("<!DOCTYPE html><p>nope"),
            MockResponse().setResponseCode(200).setHeader("Content-Type", "application/vnd.apple.mpegurl").setBody("#EXTM3U\n#EXTINF:4,\na.ts\n"),
        )
        assertEquals(listOf(true, true, false, false, false, false, true), got)
    }
}
