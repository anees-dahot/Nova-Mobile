package com.anees.novamobile.ui.common

import android.content.res.ColorStateList
import android.view.View
import android.widget.TextView
import com.anees.novamobile.data.sources.anime.FillerInfo

/** Small pill for anime episodes: FILLER (orange), MIXED (amber), RECAP (grey); canon shows nothing. */
object FillerBadge {
    fun color(k: FillerInfo.Kind?): Int? = when (k) {
        FillerInfo.Kind.FILLER -> 0xFFFF7A1A.toInt()
        FillerInfo.Kind.MIXED -> 0xFFE0A100.toInt()
        FillerInfo.Kind.RECAP -> 0xFF8E8E93.toInt()
        else -> null
    }

    fun text(k: FillerInfo.Kind?): String? = when (k) {
        FillerInfo.Kind.FILLER -> "FILLER"
        FillerInfo.Kind.MIXED -> "MIXED"
        FillerInfo.Kind.RECAP -> "RECAP"
        else -> null
    }

    fun apply(v: TextView, k: FillerInfo.Kind?) {
        val c = color(k)
        if (c == null) { v.visibility = View.GONE; return }
        v.visibility = View.VISIBLE
        v.text = text(k)
        v.backgroundTintList = ColorStateList.valueOf(c)
    }
}
