package com.anees.novamobile.ui.player

import android.util.Log
import android.view.View
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.text.CuesWithTiming
import androidx.media3.extractor.text.SubtitleParser
import androidx.media3.extractor.text.webvtt.WebvttParser
import androidx.media3.ui.SubtitleView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Subtitle sync: shows the selected subtitle [offsetMs] later (+) or earlier (−).
 *
 * The player can't shift cue times, so while an offset is set the player's own subtitle view is
 * hidden and [overlay] draws the cues instead: read straight from the subtitle's WebVTT file for
 * sideloaded tracks (both directions), or the player's own cues replayed later for subtitles
 * inside the stream (later only: future cues aren't known yet).
 */
@UnstableApi
class SubtitleSync(private val overlay: SubtitleView, private val playerSubs: () -> SubtitleView?, private val scope: CoroutineScope) {
    var offsetMs = 0L; private set
    private var player: Player? = null
    /** Format id (or label) of a sideloaded track → its WebVTT file. */
    private val files = HashMap<String, () -> File?>()
    private var loadedKey: String? = null
    private var fileCues: List<CuesWithTiming>? = null
    private var loadJob: Job? = null
    private var warned: String? = null
    /** In-stream cues as they arrived, by media time, for the "later" replay. */
    private val history = ArrayDeque<Pair<Long, List<Cue>>>()

    private val tick = object : Runnable {
        override fun run() { render(); if (offsetMs != 0L) overlay.postDelayed(this, 80) }
    }

    /** New player/stream: forget the old tracks and files. */
    fun attach(p: Player?) {
        player = p
        files.clear(); history.clear()
        loadJob?.cancel(); loadedKey = null; fileCues = null
        set(offsetMs)   // same episode on another source keeps its timing; startEpisode resets it
    }

    fun register(id: String, label: String?, file: () -> File?) {
        files[id] = file
        if (!label.isNullOrBlank()) files.putIfAbsent("label:$label", file)
    }

    fun onCues(group: CueGroup) {
        history.addLast(group.presentationTimeUs / 1000 to group.cues)
        while (history.size > 400) history.removeFirst()
    }

    fun set(ms: Long) {
        offsetMs = ms
        overlay.removeCallbacks(tick)
        if (ms == 0L) { overlay.setCues(emptyList()); overlay.visibility = View.GONE; playerSubs()?.visibility = View.VISIBLE }
        else overlay.post(tick)
    }

    /** True when the selected subtitle can only move later (it's inside the stream). */
    fun laterOnly(): Boolean = selectedFile() == null && selectedFormat() != null

    private fun selectedFormat(): androidx.media3.common.Format? {
        val p = player ?: return null
        if (p.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)) return null
        for (g in p.currentTracks.groups) if (g.type == C.TRACK_TYPE_TEXT) for (i in 0 until g.length) if (g.isTrackSelected(i)) return g.getTrackFormat(i)
        return null
    }

    private fun selectedFile(): Pair<String, () -> File?>? {
        val f = selectedFormat() ?: return null
        f.id?.let { id -> files[id]?.let { return id to it }; files.entries.firstOrNull { id.endsWith(it.key) }?.let { return it.key to it.value } }
        f.label?.let { l -> files["label:$l"]?.let { return "label:$l" to it } }
        if (warned != f.id) { warned = f.id; Log.i("NovaTV", "subtitle sync: track ${f.id} / ${f.label} is in the stream (known: ${files.keys})") }
        return null
    }

    private fun render() {
        val p = player ?: return
        val subs = playerSubs()
        val pos = p.currentPosition
        val sel = selectedFile()
        if (sel != null) {
            if (sel.first != loadedKey) load(sel.first, sel.second)
            val list = fileCues
            if (list == null) { subs?.visibility = View.VISIBLE; overlay.visibility = View.GONE; return }   // still reading the file
            subs?.visibility = View.INVISIBLE; overlay.visibility = View.VISIBLE
            val t = (pos - offsetMs) * 1000
            overlay.setCues(list.filter { t >= it.startTimeUs && t < it.endTimeUs }.flatMap { it.cues })
            return
        }
        if (selectedFormat() == null || offsetMs < 0) {   // subtitles off, or an in-stream one asked to come earlier
            subs?.visibility = View.VISIBLE; overlay.visibility = View.GONE; return
        }
        subs?.visibility = View.INVISIBLE; overlay.visibility = View.VISIBLE
        val at = pos - offsetMs
        while (history.size > 1 && history[1].first <= at - 60_000) history.removeFirst()
        overlay.setCues(history.lastOrNull { it.first <= at }?.second.orEmpty())
    }

    private fun load(key: String, file: () -> File?) {
        loadedKey = key; fileCues = null
        loadJob?.cancel()
        loadJob = scope.launch {
            val parsed = withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = file()?.takeIf { it.exists() }?.readBytes() ?: return@runCatching null
                    val out = mutableListOf<CuesWithTiming>()
                    WebvttParser().parse(bytes, 0, bytes.size, SubtitleParser.OutputOptions.allCues()) { out += it }
                    out.sortedBy { it.startTimeUs }
                }.onFailure { Log.w("NovaTV", "subtitle sync: couldn't read ${key}: ${it.message}") }.getOrNull()
            }
            if (loadedKey == key) fileCues = parsed ?: emptyList()
        }
    }
}
