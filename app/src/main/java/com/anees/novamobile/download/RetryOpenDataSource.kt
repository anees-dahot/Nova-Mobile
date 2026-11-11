package com.anees.novamobile.download

import android.net.Uri
import android.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import java.io.IOException
import java.io.InterruptedIOException

/**
 * Retries a request that fails before any byte arrives (timeout, reset, DNS hiccup, 5xx) a couple of
 * times on the spot. Without it, one HLS segment's timeout fails the whole download and Media3 starts
 * over (playlist again, every saved segment re-checked); on a flaky link that happened every few minutes.
 * Expired links (403/404/410) and rate limits (429) are left to [NovaDownloads]' policy.
 *
 * Also: a connection that drops mid-file (or is cut by the slow-stream guard) is reopened at the byte it
 * reached and reading continues, instead of the whole piece / segment failing. Sits under
 * PngStripDataSource, so offsets are the server's real bytes.
 */
@UnstableApi
class RetryOpenDataSource(private val inner: DataSource, private val tries: Int = 3) : DataSource {
    override fun addTransferListener(transferListener: TransferListener) = inner.addTransferListener(transferListener)

    private var spec: DataSpec? = null
    private var read = 0L
    private var resumes = 0

    override fun open(dataSpec: DataSpec): Long {
        spec = dataSpec; read = 0; resumes = 0
        return openWithRetry(dataSpec)
    }

    private fun openWithRetry(dataSpec: DataSpec): Long {
        var n = 0
        while (true) {
            try { return inner.open(dataSpec) }
            catch (e: IOException) {
                if (++n >= tries || !retryable(e) || Thread.currentThread().isInterrupted) throw e
                Log.w("NovaDL", "open retry $n ${dataSpec.uri.host}: ${e.message}")
                runCatching { inner.close() }
                pause(700L * n)
            }
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        while (true) {
            try {
                val r = inner.read(buffer, offset, length)
                if (r > 0) { read += r; resumes = 0 }
                return r
            } catch (e: IOException) {
                val s = spec
                val http = s?.uri?.scheme?.startsWith("http") == true
                if (s == null || !http || read == 0L || ++resumes > tries || !retryable(e) || Thread.currentThread().isInterrupted) throw e
                val rest = s.subrange(read)
                Log.w("NovaDL", "read resume $resumes ${s.uri.host} @${rest.position}: ${e.message}")
                runCatching { inner.close() }
                pause(500L * resumes)
                // Server ignoring Range answers 200 from byte 0; OkHttpDataSource skips ahead to the position itself
                openWithRetry(rest)
            }
        }
    }

    private fun pause(ms: Long) {
        try { Thread.sleep(ms) } catch (_: InterruptedException) { Thread.currentThread().interrupt(); throw InterruptedIOException() }
    }

    private fun retryable(e: IOException): Boolean {
        val code = generateSequence(e as Throwable) { it.cause }.filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()?.responseCode
        if (code != null) return code == 408 || code in 500..599 && code != 501
        // Cancelled by Media3 (interrupt) — not a network problem
        if (generateSequence(e as Throwable) { it.cause }.any { it is InterruptedIOException && it !is java.net.SocketTimeoutException }) return false
        return true
    }

    override fun getUri(): Uri? = inner.uri
    override fun getResponseHeaders(): Map<String, List<String>> = inner.responseHeaders
    override fun close() { spec = null; inner.close() }
}
