package com.anees.novamobile.ui.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener

/**
 * Some sources (e.g. OllyEmbed) disguise MPEG-TS video chunks as images by putting a tiny PNG
 * in front. Browsers' hls.js tolerates it; ExoPlayer doesn't. If a chunk starts with a PNG
 * signature, skip ahead to the first real TS packet (0x47 sync byte repeating every 188 bytes).
 * Anything that isn't a PNG passes through untouched.
 */
@UnstableApi
class PngStripDataSource(private val upstream: DataSource) : DataSource {
    private var head: ByteArray? = null
    private var headPos = 0
    private var checked = false

    override fun open(dataSpec: DataSpec): Long {
        head = null; headPos = 0; checked = false
        upstream.open(dataSpec)
        return C.LENGTH_UNSET.toLong()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (!checked) fillHead()
        head?.let { h ->
            if (headPos < h.size) {
                val n = minOf(length, h.size - headPos)
                System.arraycopy(h, headPos, buffer, offset, n)
                headPos += n
                return n
            }
        }
        return upstream.read(buffer, offset, length)
    }

    private fun fillHead() {
        checked = true
        val buf = ByteArray(8192)
        var size = 0
        while (size < buf.size) {
            val r = upstream.read(buf, size, buf.size - size)
            if (r == C.RESULT_END_OF_INPUT) break
            size += r
        }
        var start = 0
        if (size > 8 && buf[0] == 0x89.toByte() && buf[1] == 'P'.code.toByte() && buf[2] == 'N'.code.toByte() && buf[3] == 'G'.code.toByte()) {
            // The PNG ends after its IEND chunk (type + 4-byte CRC); real data (TS video or AAC audio) follows
            val iend = (8 until size - 8).firstOrNull { k ->
                buf[k] == 'I'.code.toByte() && buf[k + 1] == 'E'.code.toByte() && buf[k + 2] == 'N'.code.toByte() && buf[k + 3] == 'D'.code.toByte()
            }
            start = iend?.let { it + 8 } ?: (0 until size - 376).firstOrNull { k ->
                buf[k] == 0x47.toByte() && buf[k + 188] == 0x47.toByte() && buf[k + 376] == 0x47.toByte()
            } ?: 0
        }
        head = buf.copyOfRange(start, size)
        headPos = 0
    }

    override fun addTransferListener(transferListener: TransferListener) = upstream.addTransferListener(transferListener)
    override fun getUri(): Uri? = upstream.uri
    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders
    override fun close() = upstream.close()
}
