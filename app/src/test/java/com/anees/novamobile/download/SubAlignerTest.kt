package com.anees.novamobile.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class SubAlignerTest {
    private val rnd = Random(7)

    /** 40 minutes of dialogue: lines of 1–4 s with 0.5–6 s gaps. */
    private fun cues(): List<SubText.Cue> {
        val out = mutableListOf<SubText.Cue>()
        var t = 5_000L
        while (t < 40 * 60_000L) {
            val len = 1_000L + rnd.nextLong(3_000); out += SubText.Cue(t, t + len, "line")
            t += len + 500 + rnd.nextLong(5_500)
        }
        return out
    }

    /** Speech map for audio where the real lines sit at scale·t + offset, with 15% of frames flipped. */
    private fun speechFor(cues: List<SubText.Cue>, scale: Double, offsetMs: Long): FloatArray {
        val n = 45 * 60_000 / 50
        val a = FloatArray(n) { -1f }
        for (c in cues) {
            val f = ((c.startMs * scale + offsetMs) / 50).toInt().coerceAtLeast(0)
            val e = ((c.endMs * scale + offsetMs) / 50).toInt().coerceAtMost(n)
            for (i in f until e) a[i] = 1f
        }
        for (i in a.indices) if (rnd.nextDouble() < 0.15) a[i] = -a[i]
        return a
    }

    @Test fun findsShift() {
        val c = cues()
        val fit = SubAligner.fit(speechFor(c, 1.0, 2_350), c)
        assertEquals(1.0, fit.scale, 1e-9)
        assertEquals(2_350.0, fit.offsetMs.toDouble(), 100.0)
    }

    @Test fun findsFrameRateStretch() {
        val c = cues()
        val fit = SubAligner.fit(speechFor(c, 25 / 23.976, -1_200), c)
        assertEquals(25 / 23.976, fit.scale, 1e-6)
        assertEquals(-1_200.0, fit.offsetMs.toDouble(), 150.0)
    }

    @Test fun leavesSyncedAlone() {
        val c = cues()
        val fit = SubAligner.fit(speechFor(c, 1.0, 0), c)
        assertFalse(fit.moved)
    }

    @Test fun ranksMatchingVersionFirst() {
        val good = cues(); val other = cues()   // a different film's timing
        val speech = speechFor(good, 1.0, 0)
        assertTrue(SubAligner.fit(speech, good).score > SubAligner.fit(speech, other).score + 0.1)
    }

    @Test fun unrelatedAudioLeavesSubtitleAlone() {
        val c = cues()
        val noise = FloatArray(45 * 60_000 / 50) { if (rnd.nextDouble() < 0.5) 1f else -1f }
        val fit = SubAligner.fit(noise, c)
        assertFalse(fit.moved)
        assertFalse(fit.confident)
    }
}
