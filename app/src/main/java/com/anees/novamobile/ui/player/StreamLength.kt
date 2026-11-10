package com.anees.novamobile.ui.player

import kotlin.math.abs

/**
 * Running time of a stream, compared with the official (TMDB) runtime, so different cuts can be told
 * apart: one site has the theatrical LOTR, another the extended one, a third a wrong title.
 */
object StreamLength {
    enum class Kind { MATCH, LONGER, SHORTER, UNKNOWN }

    /** "2h 58m" / "44m". */
    fun format(sec: Int): String {
        val m = (sec + 30) / 60
        return if (m >= 60) "${m / 60}h ${m % 60}m" else "${m}m"
    }

    /** How [sec] compares with [expectedMin]: within 6 minutes (or 12% for short episodes) counts as the same cut. */
    fun kind(sec: Int, expectedMin: Int?): Kind {
        if (sec <= 0 || expectedMin == null || expectedMin <= 0) return Kind.UNKNOWN
        val diff = sec / 60.0 - expectedMin
        val slack = maxOf(6.0, expectedMin * 0.12)
        return when { abs(diff) <= slack -> Kind.MATCH; diff > 0 -> Kind.LONGER; else -> Kind.SHORTER }
    }

    /** "2h 58m · Extended cut (+40m)", "2h 1m · matches", "1h 10m · Shorter (−50m), maybe a different title"; null = unknown length. */
    fun label(sec: Int, expectedMin: Int?, movie: Boolean): String? {
        if (sec <= 0) return null
        val len = format(sec)
        val diff = expectedMin?.let { (sec + 30) / 60 - it } ?: 0
        return when (kind(sec, expectedMin)) {
            Kind.MATCH -> "$len · official length"
            Kind.LONGER -> "$len · " + (if (movie) "Extended cut" else "Longer") + " (+${diff}m)"
            Kind.SHORTER -> "$len · Shorter (−${-diff}m)" + if (movie && -diff > 30) ", maybe a different title" else ""
            Kind.UNKNOWN -> len
        }
    }
}
