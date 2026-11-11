package com.anees.novamobile.ui.sources

import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import com.anees.novamobile.R
import com.anees.novamobile.data.local.LocalStore
import com.anees.novamobile.data.model.LangTags
import com.anees.novamobile.data.model.MediaCard
import com.anees.novamobile.data.model.MediaType
import com.anees.novamobile.download.DownloadStarter
import com.anees.novamobile.ui.common.Ui
import com.anees.novamobile.ui.common.dp
import com.anees.novamobile.ui.downloads.DownloadUi
import com.anees.novamobile.ui.player.CutRules
import com.anees.novamobile.ui.player.StreamExtractor
import com.anees.novamobile.ui.player.StreamLength
import com.anees.novamobile.ui.player.StreamMeta
import com.anees.novamobile.ui.player.WebStreamSniffer
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Every stream of a movie / episode, one card per source: its running time and a cut tag (Official /
 * Extended +49m / Shorter / Different title? / CAM), release tags (WEB-DL, HEVC, HDR…), audio languages,
 * subtitles, and its qualities as chips with sizes. Tapping a chip plays or downloads exactly that file;
 * the pick sticks to the title, so later episodes come from the same place. Sources serving the very same
 * files (MovieBox and NetMirror share a backend) are shown once, the other named as "same files on".
 */
@OptIn(UnstableApi::class)
class SourcesPage(
    private val act: AppCompatActivity,
    private val card: MediaCard,
    private val season: Int,
    private val episode: Int,
    private val mode: Mode,
    /** Official runtime in minutes (movie runtime / episode runtime), to judge each stream's cut. */
    private val expectedMin: Int?,
    /** Stream playing now (player), marked in the list. */
    private val playingUrl: String? = null,
    /** How many episodes the download covers (title line). */
    private val count: Int = 1,
    /** Download mode: "More options…" (quality, episodes) opens the classic sheet. */
    private val moreOptions: (() -> Unit)? = null,
    private val onPick: (Pick?) -> Unit,
) {
    enum class Mode { STREAM, DOWNLOAD }

    /** [provider]: "Name" or "Name#dub/server"; [height] 0 = automatic (HLS picks by the quality settings). Null pick = Auto. */
    data class Pick(val provider: String, val height: Int, val stream: WebStreamSniffer.Sniffed?, val bytes: Long?)

    /** One quality of a source: an MP4 file (own size) or an HLS rendition (0 = automatic). */
    private data class Q(val height: Int, val bytes: Long?, val mp4: Boolean, val meta: StreamMeta?)

    private data class Entry(val pr: DownloadStarter.Probe, val qs: List<Q>, val meta: StreamMeta, val sec: Int,
                             val also: MutableList<String> = mutableListOf())

    private enum class Cut(val color: Int) {
        OFFICIAL(0xFF30D158.toInt()), LONGER(0xFFFFB340.toInt()), SHORTER(0xFFFFB340.toInt()),
        WRONG(0xFFFF6B6B.toInt()), CAM(0xFFFF6B6B.toInt()), UNKNOWN(0x99FFFFFF.toInt())
    }

    private val ctx = act
    private val dialog = Dialog(ctx, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
    private val list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(16.dp, 4.dp, 16.dp, 28.dp) }
    private val status = TextView(ctx).apply { textSize = 12.5f; setTextColor(Ui.TEXT3) }
    private val bar = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
        isIndeterminate = true; indeterminateTintList = android.content.res.ColorStateList.valueOf(Ui.ACCENT)
    }
    private val entries = mutableListOf<Entry>()
    private val main = Handler(Looper.getMainLooper())
    private var pending = false
    private var job: Job? = null
    private var found = 0
    private var done = false
    private val movie = card.type == MediaType.MOVIE
    private val key = "${card.type}:${card.id}"
    private val saved = LocalStore.sourcePick(key)
    private val wantLang = LangTags.norm(LocalStore.settings().audioLang)
    private val wantH = if (mode == Mode.DOWNLOAD) DownloadStarter.heightOf(LocalStore.settings().dlQuality) else LocalStore.settings().maxQuality

    fun show() {
        val subtitle = card.title + (if (!movie && episode > 0) "  ·  S$season E$episode" else "") + (if (count > 1) "  ·  $count episodes" else "")
        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; setPadding(6.dp, 6.dp, 16.dp, 6.dp)
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                addView(ImageView(ctx).apply {
                    setImageResource(R.drawable.ic_back); setPadding(12.dp, 12.dp, 12.dp, 12.dp)
                    setOnClickListener { dialog.dismiss() }
                }, LinearLayout.LayoutParams(48.dp, 48.dp))
                addView(LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(text(if (mode == Mode.STREAM) "Choose a version" else "Download", 20f, Ui.TEXT, bold = true))
                    addView(text(subtitle, 13f, Ui.TEXT2).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
                }, LinearLayout.LayoutParams(0, -2, 1f))
            })
            addView(status, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = 54.dp; topMargin = 4.dp })
        }
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(0xFF000000.toInt())
            addView(header)
            addView(bar, LinearLayout.LayoutParams(-1, 3.dp))
            addView(ScrollView(ctx).apply { isVerticalScrollBarEnabled = false; addView(list) }, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(root)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.BLACK)); setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
            // Cover the camera cutout strip too (landscape player behind), content kept clear of it
            if (android.os.Build.VERSION.SDK_INT >= 28) attributes = attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES }
        }
        root.setOnApplyWindowInsetsListener { v, ins ->
            val c = if (android.os.Build.VERSION.SDK_INT >= 28) ins.displayCutout else null
            v.setPadding(c?.safeInsetLeft ?: 0, ins.systemWindowInsetTop, c?.safeInsetRight ?: 0, ins.systemWindowInsetBottom)
            ins
        }
        dialog.setOnDismissListener { job?.cancel(); main.removeCallbacksAndMessages(null) }
        dialog.show()
        render()
        job = act.lifecycleScope.launch {
            DownloadStarter.probe(ctx, DownloadStarter.Item(card, season, episode), quickFirst = true).collect { pr ->
                if (!dialog.isShowing) return@collect
                found++
                add(pr)
                schedule()
            }
            done = true
            render()
        }
    }

    // ── Data ──────────────────────────────────────────────────────────────────

    private fun add(pr: DownloadStarter.Probe) {
        val base = pr.meta ?: StreamMeta()
        val sec = pr.durationSec.takeIf { it > 0 } ?: base.durationSec ?: -1
        val all = pr.qualities.filter { it.height > 0 }.sortedByDescending { it.height }.distinctBy { com.anees.novamobile.ui.player.ReleaseInfo.classOf(it.height) }
        val files = all.filter { it.mp4 }
        val qs = when {
            files.isNotEmpty() -> files.map { Q(it.height, it.bytes ?: it.meta?.bytes, true, it.meta) }
            // HLS: every rendition it has; streaming also offers Auto (adapts to your connection)
            all.isNotEmpty() -> (if (mode == Mode.STREAM) listOf(Q(0, null, false, null)) else emptyList()) + all.map { Q(it.height, it.bytes, false, it.meta) }
            else -> listOf(Q(0, null, false, null))
        }
        // A deeper read of a source already listed: replace it in place (keeps its "same files" names)
        val old = entries.indexOfFirst { it.pr.provider == pr.provider }
        val e = Entry(pr, qs, base, sec, entries.getOrNull(old)?.also ?: mutableListOf())
        if (old >= 0) { entries[old] = e; return }
        if (entries.any { name(pr.provider) in it.also }) return
        // Same files on another source (shared backend): one card, the other named
        val sig = signature(e)
        val twin = sig?.let { s -> entries.firstOrNull { signature(it) == s } }
        if (twin != null) { name(pr.provider).let { n -> if (n !in twin.also && twin.pr.provider != pr.provider) twin.also += n }; return }
        entries += e
    }

    /** Identical MP4 sizes per height = the same files. */
    private fun signature(e: Entry): String? = e.qs.takeIf { q -> q.isNotEmpty() && q.all { it.mp4 && it.bytes != null } }
        ?.joinToString(",") { "${it.height}:${it.bytes}" }

    private fun name(pick: String): String {
        val (n, v) = StreamExtractor.splitPick(pick)
        return listOfNotNull(n, v?.let { if (it.startsWith("@")) it.drop(1) else "${LangTags.name(it) ?: it} dub" }).joinToString(" · ")
    }

    private fun cutOf(e: Entry): Cut = when {
        e.meta.isCam -> Cut.CAM
        movie && CutRules.looksWrong(e.sec, expectedMin) -> Cut.WRONG
        com.anees.novamobile.ui.player.ReleaseInfo.mentions(e.meta.release, card.title) == false -> Cut.WRONG
        e.meta.edition == "Theatrical" -> Cut.OFFICIAL
        e.meta.edition in setOf("Extended", "Director's Cut", "Unrated") -> Cut.LONGER
        else -> when (StreamLength.kind(e.sec, expectedMin)) {
            StreamLength.Kind.MATCH -> Cut.OFFICIAL
            StreamLength.Kind.LONGER -> Cut.LONGER
            StreamLength.Kind.SHORTER -> Cut.SHORTER
            StreamLength.Kind.UNKNOWN -> Cut.UNKNOWN
        }
    }

    private fun langsOf(e: Entry) = e.pr.langs.ifEmpty { e.meta.langs.orEmpty() }

    /** Your pick first; then your language, your cut setting, the quality closest to yours, direct files; CAM / wrong titles last. */
    private fun rank(e: Entry): Int {
        if (saved?.first == e.pr.provider) return Int.MAX_VALUE
        val best = e.qs.map { it.height }.filter { it > 0 }.let { hs -> hs.filter { it <= wantH }.maxOrNull() ?: hs.minOrNull() }
            ?: e.pr.qualities.maxOfOrNull { it.height } ?: e.pr.height
        val fit = when { best <= 0 -> 500; best <= wantH -> 2000 - (wantH - best); else -> 1000 - (best - wantH) / 2 }
        val langs = langsOf(e)
        val pen = CutRules.penalty(e.sec, e.meta, expectedMin, movie, card.title)
        return fit + pen + (if (wantLang != null && wantLang in langs) 3000 else 0) + langs.size * 20 +
            (if (e.qs.any { it.mp4 }) 100 else 0) + (if (e.sec > 0) 50 else 0)
    }

    // ── Views ─────────────────────────────────────────────────────────────────

    private fun schedule() {
        if (pending) return
        pending = true
        main.postDelayed({ pending = false; render() }, 400)
    }

    private fun render() {
        if (!dialog.isShowing) return
        bar.visibility = if (done) View.INVISIBLE else View.VISIBLE
        status.text = when {
            !done && found == 0 -> "Checking every source…"
            !done -> "${entries.size} found · still checking…"
            entries.isEmpty() -> "No source answered. Auto keeps trying when you start."
            else -> "${entries.size} source${if (entries.size > 1) "s" else ""} found"
        }
        list.removeAllViews()
        list.addView(autoCard())
        if (entries.isEmpty() && !done) repeat(3) { list.addView(skeleton()) }
        entries.sortedByDescending { rank(it) }.forEach { list.addView(sourceCard(it)) }
        if (moreOptions != null) list.addView(text("More options (quality, episodes)…", 14f, Ui.ACCENT).apply {
            setPadding(4.dp, 16.dp, 4.dp, 8.dp)
            setOnClickListener { dialog.dismiss(); moreOptions.invoke() }
        })
    }

    private fun autoCard(): View = box(saved == null && playingUrl == null).apply {
        setOnClickListener { Ui.tick(it); LocalStore.setSourcePick(key, null, 0); dialog.dismiss(); onPick(null) }
        addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(text("✦  Auto · best match", 15f, Ui.TEXT, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
            addView(text(if (mode == Mode.STREAM) "PLAY" else "DOWNLOAD", 11.5f, Ui.ACCENT, bold = true).apply { letterSpacing = 0.08f })
        })
        val cut = when (LocalStore.settings().prefCut) { 1 -> "official cut"; 2 -> "extended cut"; else -> null }
        addView(text("Your language · ${wantH}p" + (cut?.let { " · $it" } ?: "") + " · never CAM", 12f, Ui.TEXT2)
            .apply { setPadding(0, 2.dp, 0, 0) })
        saved?.let { addView(text("Following your pick: ${name(it.first)}" + if (it.second > 0) " · ${it.second}p" else "", 12f, Ui.ACCENT).apply { setPadding(0, 4.dp, 0, 0) }) }
    }

    private fun sourceCard(e: Entry): View {
        val now = playingUrl != null && (e.pr.stream?.url == playingUrl || e.pr.stream?.options?.any { it.url == playingUrl } == true)
        val mine = saved?.first == e.pr.provider
        val cut = cutOf(e)
        return box(now || mine).apply {
            alpha = if (cut == Cut.CAM || cut == Cut.WRONG) 0.72f else 1f
            // 1 · name, mark, length
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                addView(text(name(e.pr.provider), 15f, Ui.TEXT, bold = true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END },
                    LinearLayout.LayoutParams(0, -2, 1f))
                (if (now) "PLAYING" else if (mine) "YOUR PICK" else null)?.let {
                    addView(text(it, 10.5f, Ui.ACCENT, bold = true).apply { letterSpacing = 0.08f; setPadding(8.dp, 0, 0, 0) })
                }
                addView(text(if (e.sec > 0) StreamLength.format(e.sec) else "—", 13f, Ui.TEXT2, bold = true).apply { setPadding(10.dp, 0, 0, 0) })
            })
            // 2 · cut + release tags, then audio / subtitles, one scrolling line
            val diff = if (e.sec > 0 && expectedMin != null && expectedMin > 0) (e.sec + 30) / 60 - expectedMin else 0
            val tags = mutableListOf(when (cut) {
                Cut.OFFICIAL -> (e.meta.edition ?: if (movie) "Official cut" else "Full episode")
                Cut.LONGER -> (e.meta.edition ?: if (movie) "Extended" else "Longer") + if (diff > 0) " +${diff}m" else ""
                Cut.SHORTER -> "Shorter ${diff}m"
                Cut.WRONG -> "Different title?"
                Cut.CAM -> "CAM recording"
                Cut.UNKNOWN -> "Length unknown"
            } to cut.color)
            e.meta.badges().filter { it !in StreamMeta.CAM_TYPES }.forEach { tags += it to Ui.TEXT }
            e.meta.service?.let { tags += it to Ui.TEXT2 }
            e.meta.flags?.filter { it == "Proper" || it == "Repack" || it == "Watermark" }?.forEach { tags += it to Ui.TEXT2 }
            val langs = langsOf(e).mapNotNull { LangTags.name(it) }
            val audio = when {
                langs.isNotEmpty() -> langs.joinToString(" · ")
                e.pr.audioTracks > 1 -> "${e.pr.audioTracks} audio tracks"
                LangTags.name(card.lang) != null -> "${LangTags.name(card.lang)}?"
                else -> "Audio ?"
            }
            val subs = e.pr.stream?.subtitles?.size ?: 0
            addView(chipRow(tags, "🔊 $audio" + if (subs > 0) "   💬 $subs" else ""), LinearLayout.LayoutParams(-1, -2).apply { topMargin = 6.dp })
            // 3 · every quality it has: tap one to play / download exactly that
            addView(HorizontalScrollView(ctx).apply {
                isHorizontalScrollBarEnabled = false
                addView(LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    e.qs.forEachIndexed { i, q -> addView(qualityChip(e, q, now), LinearLayout.LayoutParams(-2, -2).apply { if (i > 0) leftMargin = 6.dp }) }
                })
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 8.dp })
            val extra = listOfNotNull(e.also.takeIf { it.isNotEmpty() }?.let { "Same files on ${it.joinToString(", ")}" },
                e.meta.release?.takeIf { it.length > 12 && !it.equals(card.title, true) && informative(it) })
            if (extra.isNotEmpty()) addView(text(extra.joinToString("  ·  "), 11f, Ui.TEXT3).apply {
                maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE; setPadding(0, 6.dp, 0, 0) })
            setOnClickListener { Ui.tick(it); if (now) dialog.dismiss() else pick(e, bestQ(e)) }
        }
    }

    /** Release names worth showing: they carry details the tags don't (filenames, headings), not just the title. */
    private fun informative(s: String) = Regex("""(?i)\d{3,4}p|x26[45]|hevc|web|blu|rip|\d+(\.\d+)?\s?(gb|mb)""").containsMatchIn(s)

    /** The quality your settings would take: tallest within them (Auto for adaptive streams). */
    private fun bestQ(e: Entry): Q = e.qs.firstOrNull { it.height == 0 } ?: e.qs.filter { it.height in 1..wantH }.maxByOrNull { it.height } ?: e.qs.minBy { it.height }

    private fun qualityChip(e: Entry, q: Q, now: Boolean): View = TextView(ctx).apply {
        val top = q === bestQ(e)
        background = GradientDrawable().apply {
            cornerRadius = 8f.dp
            setColor(if (top) 0x330A84FF else 0x14FFFFFF)
            setStroke(1.dp, if (top) Ui.ACCENT else Ui.HAIRLINE)
        }
        setPadding(12.dp, 6.dp, 12.dp, 6.dp)
        val label = if (q.height > 0) "${com.anees.novamobile.ui.player.ReleaseInfo.classOf(q.height)}p" else "Auto"
        // Direct files: MKV releases say so in their name (".mkv", "x265… [DDP5.1]"); the rest are MP4
        val mkv = (q.meta?.release ?: e.meta.release)?.contains(".mkv", true) == true || e.pr.provider in setOf("4KHDHub", "HDHub4u", "MoviesDrive")
        val sub = listOfNotNull(if (q.mp4) (if (mkv) "MKV" else "MP4") else if (q.height > 0) null else "adaptive", q.bytes?.let { (if (q.mp4) "" else "~") + DownloadUi.size(ctx, it) }
            ?: q.meta?.bitrateKbps?.takeIf { !q.mp4 }?.let { if (it >= 1000) "%.1f Mb/s".format(it / 1000.0) else "$it kb/s" }).joinToString(" · ")
        text = android.text.SpannableStringBuilder(label).apply {
            setSpan(android.text.style.StyleSpan(Typeface.BOLD), 0, length, 0)
            if (sub.isNotEmpty()) { append("  "); val st = length; append(sub)
                setSpan(ForegroundColor(Ui.TEXT2), st, length, 0); setSpan(android.text.style.RelativeSizeSpan(0.85f), st, length, 0) }
        }
        textSize = 13.5f; setTextColor(Ui.TEXT)
        setOnClickListener { Ui.tick(it); if (now && q.height == 0) dialog.dismiss() else pick(e, q) }
    }

    private fun ForegroundColor(c: Int) = android.text.style.ForegroundColorSpan(c)

    private fun pick(e: Entry, q: Q) {
        LocalStore.setSourcePick(key, e.pr.provider, q.height)
        dialog.dismiss()
        onPick(Pick(e.pr.provider, q.height, e.pr.stream, q.bytes))
    }

    private fun chipRow(tags: List<Pair<String, Int>>, trailing: String? = null) = HorizontalScrollView(ctx).apply {
        isHorizontalScrollBarEnabled = false
        addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            trailing?.let { t -> post { addView(text(t, 12.5f, Ui.TEXT2), LinearLayout.LayoutParams(-2, -2).apply { leftMargin = 10.dp }) } }
            tags.forEachIndexed { i, (t, c) ->
                addView(text(t, 11f, c, bold = true).apply {
                    background = GradientDrawable().apply { cornerRadius = 6f.dp; setColor((c and 0x00FFFFFF) or 0x26000000) }
                    setPadding(7.dp, 2.dp, 7.dp, 2.dp)
                }, LinearLayout.LayoutParams(-2, -2).apply { if (i > 0) leftMargin = 5.dp })
            }
        })
    }

    private fun skeleton(): View = box(false).apply {
        alpha = 0.5f
        listOf(140, 200, 260).forEachIndexed { i, w ->
            addView(View(ctx).apply { background = GradientDrawable().apply { cornerRadius = 4f.dp; setColor(0x22FFFFFF) } },
                LinearLayout.LayoutParams(w.dp, if (i == 2) 40.dp else 12.dp).apply { topMargin = if (i == 0) 2.dp else 10.dp })
        }
    }

    private fun box(on: Boolean) = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply { cornerRadius = 14f.dp; setColor(0xFF1C1C1E.toInt()); setStroke(if (on) 2.dp else 1.dp, if (on) Ui.ACCENT else Ui.HAIRLINE) }
        setPadding(14.dp, 11.dp, 14.dp, 11.dp)
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 8.dp }
    }

    private fun text(s: String, size: Float, color: Int, bold: Boolean = false) = TextView(ctx).apply {
        text = s; textSize = size; setTextColor(color)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
}
