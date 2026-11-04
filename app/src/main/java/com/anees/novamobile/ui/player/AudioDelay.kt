package com.anees.novamobile.ui.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import java.nio.ByteBuffer

/**
 * Audio sync: shifts the sound against the picture by [delayMs] (positive = sound later).
 *
 * The audio clock drives A/V sync, so inserting N ms of silence makes every video frame show N ms
 * before its sound, and dropping N ms of samples does the opposite. The shift is applied again
 * after each seek (flush), and a change while playing applies just the difference.
 */
@UnstableApi
class AudioDelay : BaseAudioProcessor() {
    @Volatile var delayMs = 0L
    private var appliedFrames = 0L      // frames inserted (+) or dropped (−) since the last flush

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (!Util.isEncodingLinearPcm(inputAudioFormat.encoding) || inputAudioFormat.encoding == C.ENCODING_INVALID)
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val fmt = inputAudioFormat
        val frameBytes = fmt.bytesPerFrame.coerceAtLeast(1)
        val target = delayMs * fmt.sampleRate / 1000
        val delta = target - appliedFrames
        var silenceBytes = 0
        if (delta > 0) {
            silenceBytes = (delta * frameBytes).coerceAtMost(Int.MAX_VALUE.toLong() / 2).toInt()
            appliedFrames = target
        } else if (delta < 0) {
            val dropFrames = minOf(-delta, (inputBuffer.remaining() / frameBytes).toLong())
            inputBuffer.position(inputBuffer.position() + (dropFrames * frameBytes).toInt())
            appliedFrames -= dropFrames
        }
        val remaining = inputBuffer.remaining()
        if (silenceBytes == 0 && remaining == 0) return
        val out = replaceOutputBuffer(silenceBytes + remaining)
        repeat(silenceBytes) { out.put(0) }
        out.put(inputBuffer)
        out.flip()
    }

    override fun onFlush() { appliedFrames = 0 }
    override fun onReset() { appliedFrames = 0 }
}
