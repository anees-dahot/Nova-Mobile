package com.anees.novamobile.ui.downloads

import android.app.Dialog
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.Window
import android.view.WindowManager
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import coil.load
import com.anees.novamobile.MainActivity
import com.anees.novamobile.R
import com.anees.novamobile.data.local.LocalStore
import com.anees.novamobile.data.model.LangTags
import com.anees.novamobile.data.model.MediaType
import com.anees.novamobile.download.DlStats
import com.anees.novamobile.download.DownloadMeta
import com.anees.novamobile.download.DownloadStarter
import com.anees.novamobile.download.NovaDownloads
import com.anees.novamobile.download.SmartDownloads
import com.anees.novamobile.ui.common.Ui
import com.anees.novamobile.ui.common.dp
import kotlinx.coroutines.launch

/**
 * The sheet that opens when you tap Download:
 *  1. choose quality (with size), audio languages, "remember" — skipped if Settings says don't ask
 *  2. live status in the same sheet: finding a source → progress, speed, time left — with
 *     Download next episodes · Pause · Cancel · Go to Downloads · Done
 */
@UnstableApi
class DownloadSheet private constructor(private val act: AppCompatActivity, private var items: List<DownloadStarter.Item>, private val onChange: () -> Unit) {

    companion object {
        fun show(act: AppCompatActivity, items: List<DownloadStarter.Item>, onChange: () -> Unit = {}) {
            if (items.isEmpty()) return
            DownloadSheet(act, items, onChange).open()
        }
    }

    private val ctx = act
    private val dialog = Dialog(ctx, android.R.style.Theme_Translucent_NoTitleBar_Fullscreen)
    private val body = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    private val footer = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    private val main = Handler(Looper.getMainLooper())
    private var poll: Runnable? = null
    private val first get() = items.firstOrNull() ?: allItems.first()
    private val ids get() = items.map { DownloadMeta.id(it.card, it.season, it.episode) }

    private fun open() {
        val sheet = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { setColor(Ui.SURFACE); val r = 18f.dp; cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f) }
            isClickable = true
            setPadding(20.dp, 8.dp, 20.dp, 20.dp)
            addView(View(ctx).apply { background = GradientDrawable().apply { setColor(0x4DFFFFFF); cornerRadius = 3f.dp } },
                LinearLayout.LayoutParams(36.dp, 5.dp).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = 14.dp })
            addView(header())
            // Body scrolls; the Download button stays pinned at the bottom (footer)
            addView(object : ScrollView(ctx) {
                override fun onMeasure(w: Int, h: Int) = super.onMeasure(w, View.MeasureSpec.makeMeasureSpec((ctx.resources.displayMetrics.heightPixels * 0.62f).toInt(), View.MeasureSpec.AT_MOST))
            }.apply { isVerticalScrollBarEnabled = false; addView(body) }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 16.dp })
            addView(footer, LinearLayout.LayoutParams(-1, -2))
        }
        val width = minOf(ctx.resources.displayMetrics.widthPixels, 480.dp)
        val scrim = FrameLayout(ctx).apply {
            setBackgroundColor(0x99000000.toInt())
            setOnClickListener { dialog.dismiss() }
            addView(sheet,
                FrameLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
        }
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(scrim)
        dialog.window?.apply { setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT)); setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT) }
        dialog.setOnDismissListener { poll?.let { main.removeCallbacks(it) }; probeJob?.cancel(); onChange() }
        when {
            items.size == 1 && NovaDownloads.completed(ctx, ids.first()) -> problem("Already downloaded", "It's in your Downloads and plays offline.")
            else -> choose()   // always: the source list is worth a look (quality pre-picked if remembered)
        }
        dialog.show()
        sheet.translationY = 400f.dp
        sheet.animate().translationY(0f).setDuration((240 * Ui.motion(ctx)).toLong()).setInterpolator(android.view.animation.DecelerateInterpolator(2f)).start()
    }

    private fun header() = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        addView(ImageView(ctx).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP; load(first.card.posterUrl ?: first.card.backdropUrl)
            outlineProvider = object : ViewOutlineProvider() { override fun getOutline(v: View, o: Outline) = o.setRoundRect(0, 0, v.width, v.height, 6f.dp) }
            clipToOutline = true
        }, LinearLayout.LayoutParams(48.dp, 72.dp))
        addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; setPadding(14.dp, 0, 0, 0)
            addView(TextView(ctx).apply {
                text = first.card.title; textSize = 18f; setTextColor(Ui.TEXT); maxLines = 2
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            })
            addView(TextView(ctx).apply { text = what(); textSize = 13f; setTextColor(Ui.TEXT2); setPadding(0, 2.dp, 0, 0) })
        }, LinearLayout.LayoutParams(0, -2, 1f))
    }

    private fun what(): String = when {
        items.size > 1 -> "${items.size} episodes"
        first.card.type == MediaType.MOVIE -> "Movie"
        first.card.type == MediaType.ANIME -> "Episode ${first.episode}"
        else -> "Season ${first.season} · Episode ${first.episode}"
    }

    private fun freeBytes() = StatFs((ctx.getExternalFilesDir(null) ?: ctx.filesDir).absolutePath).availableBytes

    // ── Step 1: choose ────────────────────────────────────────────────────────

    /** Bulk downloads go in two steps: 1 pick episodes, 2 source + quality. A single item goes straight to 2. */
    private var step = 0

    private fun choose() = if (allItems.size > 1 && step != 2) episodeStep() else sourceStep()

    private fun stepLabel(t: String) = body.addView(TextView(ctx).apply {
        text = t; textSize = 12f; setTextColor(Ui.ACCENT); letterSpacing = 0.06f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); setPadding(4.dp, 0, 0, 10.dp)
    })

    private fun episodeStep() {
        step = 1
        body.removeAllViews(); footer.removeAllViews()
        stepLabel("STEP 1 OF 2  ·  PICK EPISODES")
        episodes()
        footer.addView(TextView(ctx).apply {
            text = "${DownloadUi.size(ctx, freeBytes())} free on this phone"; textSize = 12f; setTextColor(Ui.TEXT3); gravity = Gravity.CENTER
            setPadding(0, 10.dp, 0, 8.dp)
        })
        footer.addView(primary("Next") {}.also { dlButton = it }.apply {
            setOnClickListener { v ->
                if (items.isEmpty()) { Toast.makeText(ctx, "Pick at least one episode", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
                Ui.confirm(v); sourceStep()
            }
        })
        paintButton()
    }

    private fun sourceStep() {
        step = 2
        body.removeAllViews(); footer.removeAllViews()
        // Fresh source check for the first picked episode
        probeJob?.cancel(); probes.clear(); sourceQualities.clear(); titleViews.clear(); provider = null; bestBytes = null
        val s = LocalStore.settings()
        var remember = false
        if (allItems.size > 1) {
            stepLabel("STEP 2 OF 2  ·  SOURCE & QUALITY")
            body.addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                background = GradientDrawable().apply { cornerRadius = 12f.dp; setColor(Ui.SURFACE2) }
                setPadding(14.dp, 12.dp, 14.dp, 12.dp)
                addView(TextView(ctx).apply {
                    val eps = items.map { it.episode }
                    text = "${items.size} episode${if (items.size > 1) "s" else ""}  ·  " +
                        (if (eps.size <= 4) eps.joinToString(", ") { "E$it" } else "E${eps.first()} – E${eps.last()}")
                    textSize = 14f; setTextColor(Ui.TEXT); typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(TextView(ctx).apply { text = "Change"; textSize = 14f; setTextColor(Ui.ACCENT) })
                setOnClickListener { Ui.tick(it); probeJob?.cancel(); episodeStep() }
            }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 14.dp })
        }
        sources()
        body.addView(qualityBox, LinearLayout.LayoutParams(-1, -2))
        selH = DownloadStarter.heightOf(s.dlQuality)
        paintQualities()

        body.addView(switchRow("Remember my choice", "Don't ask next time (change in Settings)", false) { on, _ -> remember = on })

        footer.addView(TextView(ctx).apply {
            text = "${DownloadUi.size(ctx, freeBytes())} free on this phone"; textSize = 12f; setTextColor(Ui.TEXT3); gravity = Gravity.CENTER
            setPadding(0, 10.dp, 0, 8.dp)
        })
        footer.addView(primary("Download") {}.also { dlButton = it }.apply { setOnClickListener { v -> Ui.confirm(v); onDownload(remember) } })
        paintButton()
    }

    private var dlButton: TextView? = null
    private var bestBytes: Long? = null

    /** "Download  ·  264 MB" / "Download 3 episodes  ·  ~800 MB": you see what it costs before tapping. */
    private fun paintButton() {
        val n = items.size
        val per = provider?.let { sourceQualities[it] }?.firstOrNull { it.height == selH }?.bytes
            ?: if (provider == null) bestBytes else null
        val size = per?.let { DownloadUi.size(ctx, it * n) } ?: "~" + DownloadUi.size(ctx, estimate(items.ifEmpty { listOf(first) }, qualityIndex(selH)))
        dlButton?.text = when {
            n == 0 -> "Pick episodes"
            step == 1 -> "Next  ·  $n episode${if (n > 1) "s" else ""}  ·  ~" + DownloadUi.size(ctx, estimate(items.ifEmpty { listOf(first) }, qualityIndex(selH)))
            n == 1 -> "Download  ·  $size"
            else -> "Download $n episodes  ·  $size"
        }
        dlButton?.alpha = if (n == 0) 0.5f else 1f
    }

    private fun onDownload(remember: Boolean) {
        if (items.isEmpty()) { Toast.makeText(ctx, "Pick at least one episode", Toast.LENGTH_SHORT).show(); return }
        val q = qualityIndex(selH)
        val cur = LocalStore.settings()
        LocalStore.saveSettings(cur.copy(dlQuality = if (remember) q else cur.dlQuality, dlAskQuality = !remember))
        start(q)
    }

    // ── Episodes (bulk): pick, unpick, skip watched ───────────────────────────

    /** Every episode the sheet was opened with; [items] is the checked subset. */
    private val allItems = items
    /** Episodes ticked in step 1 (kept when you come back from step 2). */
    private var pickedKeys: Set<String>? = null
    private fun key(i: DownloadStarter.Item) = "${i.season}:${i.episode}"

    private fun episodes() {
        val watched = LocalStore.episodeProgress("${first.card.type}:${first.card.id}").filterValues { it >= 0.9f }.keys
        val have = allItems.filter { NovaDownloads.get(ctx, DownloadMeta.id(it.card, it.season, it.episode))?.let { d -> d.state != Download.STATE_FAILED } == true }.map { key(it) }.toSet()
        val checked = (pickedKeys ?: allItems.map { key(it) }.filter { it !in have }.toSet()).toMutableSet()
        // Anime: which episodes are filler / recap (cached by the episode list)
        val filler = com.anees.novamobile.data.sources.anime.FillerInfo.cached(first.card)
        val skippable = allItems.filter { filler?.at(it.season, it.episode)?.kind?.skippable == true }.map { key(it) }.toSet()
        val title = label("")
        body.addView(title)
        val chips = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        body.addView(HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; addView(chips) }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 8.dp })
        val list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        body.addView(list, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 12.dp })   // the sheet itself scrolls
        val boxes = mutableMapOf<String, CheckBox>()
        var syncing = false   // setting a box from code must not re-enter sync (61 boxes → stack overflow)
        fun sync() {
            items = allItems.filter { key(it) in checked }
            pickedKeys = checked.toSet()
            syncing = true
            try { boxes.forEach { (k, b) -> if (b.isChecked != (k in checked)) b.isChecked = k in checked } } finally { syncing = false }
            title.text = "Episodes  ·  ${items.size} of ${allItems.size} selected"
            paintQualities()
        }
        fun chip(text: String, onTap: () -> Unit) = chips.addView(TextView(ctx).apply {
            this.text = text; textSize = 13f; setTextColor(Ui.TEXT); setPadding(14.dp, 7.dp, 14.dp, 7.dp)
            background = GradientDrawable().apply { cornerRadius = 16f.dp; setColor(Ui.SURFACE2); setStroke(1.dp, Ui.HAIRLINE) }
            setOnClickListener { Ui.tick(it); onTap(); sync() }
        }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = 8.dp })
        chip("All") { checked.clear(); checked += allItems.map { key(it) }.filter { it !in have } }
        chip("Unwatched") { checked.clear(); checked += allItems.map { key(it) }.filter { it !in have && it !in watched } }
        chip("Hide watched") { checked.removeAll(watched) }
        if (skippable.isNotEmpty()) chip("Skip filler (${skippable.size})") { checked.removeAll(skippable) }
        chip("None") { checked.clear() }
        for (i in allItems) {
            val k = key(i)
            val fk = filler?.at(i.season, i.episode)?.kind
            val tags = listOfNotNull(com.anees.novamobile.ui.common.FillerBadge.text(fk)?.lowercase(),
                if (k in watched) "watched" else null, if (k in have) "downloaded" else null).joinToString(" · ")
            val box = CheckBox(ctx).apply {
                val fTitle = filler?.at(i.season, i.episode)?.title
                text = (if (i.card.type == MediaType.ANIME) "E${i.episode}" else "S${i.season} · E${i.episode}") +
                    ((i.title ?: fTitle)?.let { "  ·  $it" } ?: "") + if (tags.isNotEmpty()) "   ($tags)" else ""
                maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(if (k in have) Ui.TEXT3 else Ui.TEXT); textSize = 14f
                buttonTintList = android.content.res.ColorStateList.valueOf(Ui.ACCENT)
                isChecked = k in checked; isEnabled = k !in have
                setOnCheckedChangeListener { _, on -> if (syncing) return@setOnCheckedChangeListener; if (on) checked += k else checked -= k; sync() }
            }
            boxes[k] = box
            list.addView(box, LinearLayout.LayoutParams(-1, 44.dp))
        }
        sync()
    }

    // ── Quality: the picked source's real files, or a target for Best ─────────

    private val qualityBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    /** Picture height picked. */
    private var selH = 720
    /** Real qualities per answering source (from the probe). */
    private val sourceQualities = mutableMapOf<String, List<DownloadStarter.Quality>>()

    private var bestSub: TextView? = null
    /** Source row titles, so the likely pick gets a "Best pick" mark. */
    private val titleViews = mutableMapOf<String, Pair<TextView, String>>()
    private val probes = mutableListOf<DownloadStarter.Probe>()

    /** What "Best" will most likely use, with the same rules as the download (direct file, your language, closest quality). */
    private fun updateBest() {
        val wanted = LangTags.norm(LocalStore.settings().audioLang)
        val pick = probes.maxByOrNull { p ->
            val q = p.qualities.filter { it.height in 1..selH }.maxByOrNull { it.height } ?: p.qualities.minByOrNull { it.height }
            (if (q?.mp4 == true) 1000 else 0) + (if (wanted != null && wanted in p.langs) 500 else 0) + p.langs.size * 10 + (q?.height ?: 0) / 100 +
                (if ((q?.height ?: 0) in 1..selH) 150 else 0)
        } ?: return
        val q = pick.qualities.filter { it.height in 1..selH }.maxByOrNull { it.height } ?: pick.qualities.minByOrNull { it.height }
        bestSub?.text = "Likely: " + listOfNotNull(pick.provider.substringBefore('#'), q?.let { if (it.mp4) "MP4" else "HLS" }, q?.height?.let { "${it}p" },
            q?.bytes?.let { DownloadUi.size(ctx, it * items.size.coerceAtLeast(1)) }).joinToString("  ·  ")
        bestSub?.setTextColor(Ui.ACCENT)
        bestBytes = q?.bytes
        titleViews.forEach { (n, tv) ->
            tv.first.text = if (n == pick.provider) android.text.SpannableString("${tv.second}   BEST PICK").apply {
                setSpan(android.text.style.ForegroundColorSpan(Ui.ACCENT), tv.second.length, length, 0)
                setSpan(android.text.style.RelativeSizeSpan(0.72f), tv.second.length, length, 0)
            } else tv.second
        }
        paintButton()
    }

    /** Estimated size of [list] at a quality: each episode's real length when known. */
    private fun estimate(list: List<DownloadStarter.Item>, q: Int): Long = list.sumOf { i ->
        i.minutes?.takeIf { it > 0 }?.let { DownloadStarter.estimateMinutes(it, q) } ?: DownloadStarter.estimateBytes(i.card, q) }

    private fun qualityIndex(h: Int) = when { h <= 480 -> 0; h <= 720 -> 1; else -> 2 }

    private fun paintQualities() {
        qualityBox.removeAllViews()
        updateBest()
        val real = provider?.let { sourceQualities[it] }?.filter { it.height > 0 }?.sortedBy { it.height }?.takeIf { it.isNotEmpty() }
        val n = items.size.coerceAtLeast(1)
        val opts: List<Triple<Int, String, String>> = if (real != null) {
            if (real.none { it.height == selH }) selH = (real.filter { it.height <= selH }.maxByOrNull { it.height } ?: real.first()).height
            real.map { q ->
                val size = q.bytes?.let { "${DownloadUi.size(ctx, it * n)}" } ?: "about ${DownloadUi.size(ctx, estimate(items.ifEmpty { listOf(first) }, qualityIndex(q.height)))}"
                Triple(q.height, "${q.height}p", "${if (q.mp4) "MP4" else "HLS"}  ·  $size")
            }
        } else listOf(480 to "Saves space", 720 to "Standard", 1080 to "Best picture").map { (h, hint) ->
            Triple(h, "${h}p", "$hint  ·  about ${DownloadUi.size(ctx, estimate(items.ifEmpty { listOf(first) }, qualityIndex(h)))}")
        }.also { if (selH !in listOf(480, 720, 1080)) selH = DownloadStarter.heightOf(qualityIndex(selH)) }
        qualityBox.addView(label(if (real != null) "Quality  ·  what ${provider!!.substringBefore('#')} has" else "Quality  ·  nearest each source has"))
        opts.forEach { (h, name, hint) ->
            val on = h == selH
            qualityBox.addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                background = GradientDrawable().apply { cornerRadius = 12f.dp; setColor(Ui.SURFACE2); setStroke(if (on) 2.dp else 1.dp, if (on) Ui.ACCENT else Ui.HAIRLINE) }
                setPadding(16.dp, 12.dp, 14.dp, 12.dp)
                addView(LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(TextView(ctx).apply { text = name; textSize = 16f; setTextColor(Ui.TEXT); typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) })
                    addView(TextView(ctx).apply { text = hint; textSize = 13f; setTextColor(Ui.TEXT2) })
                }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(ImageView(ctx).apply { setImageResource(R.drawable.ic_check); imageTintList = android.content.res.ColorStateList.valueOf(Ui.ACCENT); visibility = if (on) View.VISIBLE else View.INVISIBLE },
                    LinearLayout.LayoutParams(22.dp, 22.dp))
                setOnClickListener { selH = h; Ui.tick(it); paintQualities() }
            }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 8.dp })
        }
        paintButton()
    }

    /** Source the download uses; null = Best (the stream with the most languages, yours first). */
    private var provider: String? = null
    /** Source checks stop when the sheet closes (hidden pages are heavy). */
    private var probeJob: kotlinx.coroutines.Job? = null

    /**
     * Every source that has it, filled in live as they answer: languages and picture quality,
     * so you can choose where the download comes from. A season uses the pick for every episode.
     */
    private fun sources() {
        body.addView(label(if (allItems.size > 1) "Source  ·  used for every selected episode" else "Source"))
        val list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        body.addView(list, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 8.dp })
        // Sources that can't say their quality/size sit behind "More sources" (still pickable)
        val moreBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        var moreCount = 0
        val moreToggle = TextView(ctx).apply {
            textSize = 13f; setTextColor(Ui.ACCENT); setPadding(4.dp, 2.dp, 4.dp, 10.dp); visibility = View.GONE
            setOnClickListener { Ui.tick(it); moreBox.visibility = if (moreBox.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                text = if (moreBox.visibility == View.VISIBLE) "Hide other sources" else "Show $moreCount more source${if (moreCount > 1) "s" else ""} (quality unknown)" }
        }
        body.addView(moreToggle)
        body.addView(moreBox, LinearLayout.LayoutParams(-1, -2))
        val rows = mutableListOf<Pair<String?, View>>()
        val counts = mutableListOf<Int>()   // languages per listed source, richest first
        fun paint() = rows.forEach { (name, r) ->
            val on = name == provider
            (r.background as GradientDrawable).setStroke(if (on) 2.dp else 1.dp, if (on) Ui.ACCENT else Ui.HAIRLINE)
            r.findViewById<ImageView>(android.R.id.icon).visibility = if (on) View.VISIBLE else View.INVISIBLE
        }
        fun row(name: String?, title: String, sub: String, highlight: Boolean = false): View = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply { cornerRadius = 12f.dp; setColor(Ui.SURFACE2) }
            setPadding(16.dp, 10.dp, 14.dp, 10.dp)
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(ctx).apply { text = title; textSize = 15f; setTextColor(Ui.TEXT); typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    if (name != null) titleViews[name] = this to title })
                addView(TextView(ctx).apply { text = sub; textSize = 12.5f; setTextColor(if (highlight) Ui.ACCENT else Ui.TEXT2) })
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(ImageView(ctx).apply { id = android.R.id.icon; setImageResource(R.drawable.ic_check); imageTintList = android.content.res.ColorStateList.valueOf(Ui.ACCENT) },
                LinearLayout.LayoutParams(22.dp, 22.dp))
            setOnClickListener { provider = name; Ui.tick(it); paint(); paintQualities() }
            alpha = 0f; animate().alpha(1f).setDuration(180).start()
        }
        val best = row(null, "Best", "Direct MP4 first, then most audio languages")
        bestSub = ((best as LinearLayout).getChildAt(0) as LinearLayout).getChildAt(1) as TextView
        rows += null to best
        list.addView(best, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 8.dp })
        val searching = TextView(ctx).apply { text = "Checking sources…"; textSize = 12.5f; setTextColor(Ui.TEXT3); setPadding(4.dp, 0, 0, 6.dp) }
        list.addView(searching)
        paint()
        var found = 0
        probeJob = act.lifecycleScope.launch {
            DownloadStarter.probe(ctx, first).collect { pr ->
                if (!dialog.isShowing) return@collect
                found++
                val names = pr.langs.mapNotNull { LangTags.name(it) }
                val unnamed = (pr.audioTracks - names.size).coerceAtLeast(0)
                val audio = when {
                    names.isNotEmpty() && unnamed > 0 -> names.joinToString(" · ") + " +$unnamed more"
                    names.isNotEmpty() -> names.joinToString(" · ")
                    pr.audioTracks > 1 -> "${pr.audioTracks} audio tracks (unlabelled)"
                    pr.audioTracks == 1 -> "1 audio track (language not stated)"
                    pr.qualities.any { it.mp4 } -> "Original audio"
                    else -> "Audio language not stated"
                }
                sourceQualities[pr.provider] = pr.qualities
                val kind = when { pr.qualities.isEmpty() -> ""; pr.qualities.any { it.mp4 } -> "MP4  ·  "; else -> "HLS  ·  " }
                probes += pr
                val qs = pr.qualities.filter { it.height > 0 }.distinctBy { it.height }.sortedBy { it.height }
                val q = "\n" + kind + (if (qs.isNotEmpty()) qs.joinToString(" · ") { x -> "${x.height}p" + (x.bytes?.let { " ${DownloadUi.size(ctx, it * items.size.coerceAtLeast(1))}" } ?: "") }
                    else if (pr.height > 0) "${pr.height}p" else "quality unknown")
                updateBest()
                val title = pr.provider.split('#').let { parts -> if (parts.size > 1) "${parts[0]}  ·  ${LangTags.name(parts[1]) ?: parts[1]} dub" else parts[0] }
                val r = row(pr.provider, title, audio + q, highlight = pr.langs.size >= 2)
                rows += pr.provider to r
                // Richest in languages floats to the top
                // Added in arrival order just above "checking…": rows never jump under your finger
                if (pr.qualities.isEmpty() && pr.height <= 0) {
                    moreCount++
                    moreBox.addView(r, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 8.dp })
                    moreToggle.visibility = View.VISIBLE
                    if (moreBox.visibility != View.VISIBLE) moreToggle.text = "Show $moreCount more source${if (moreCount > 1) "s" else ""} (quality unknown)"
                } else list.addView(r, (list.childCount - 1).coerceAtLeast(0), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 8.dp })
                searching.text = "$found found · still checking…"
                paint()
            }
            searching.text = if (found == 0) "No source answered yet · Best keeps trying when you download" else "$found source${if (found > 1) "s" else ""} found"
        }
    }

    // ── Step 2: start + live status ───────────────────────────────────────────

    private fun start(quality: Int?) {
        val q = quality ?: LocalStore.settings().dlQuality
        val known = provider?.let { sourceQualities[it] }?.firstOrNull { it.height == selH }?.bytes
        val need = known?.let { it * items.size } ?: (if (provider == null) bestBytes?.let { it * items.size } else null) ?: estimate(items.ifEmpty { listOf(first) }, q)
        if (need > freeBytes()) { problem("Not enough space", "About ${DownloadUi.size(ctx, need)} needed, ${DownloadUi.size(ctx, freeBytes())} free. Delete something you've watched, or pick a smaller quality."); return }
        (act as? MainActivity)?.ensureNotificationPermission()
        items = items.map { it.copy(quality = quality, provider = provider, height = selH) }
        val added = DownloadStarter.enqueue(ctx, items)
        if (added == 0 && items.size == 1 && NovaDownloads.completed(ctx, ids.first())) {
            problem("Already downloaded", "It's in your Downloads and plays offline."); return
        }
        status()
        onChange()
    }

    private fun problem(title: String, msg: String) {
        body.removeAllViews(); footer.removeAllViews()
        body.addView(TextView(ctx).apply { text = title; textSize = 16f; setTextColor(Ui.TEXT); typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) })
        body.addView(TextView(ctx).apply { text = msg; textSize = 14f; setTextColor(Ui.TEXT2); setPadding(0, 4.dp, 0, 16.dp); setLineSpacing(0f, 1.15f) })
        if (items.size == 1 && NovaDownloads.completed(ctx, ids.first())) {
            body.addView(primary("Play") { dialog.dismiss(); com.anees.novamobile.ui.player.PlayerActivity.launch(ctx, first.card, first.season, first.episode) })
            body.addView(secondary("Go to Downloads") { dialog.dismiss(); (act as? MainActivity)?.openDownloads() }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 10.dp })
        } else {
            body.addView(primary("Go to Downloads") { dialog.dismiss(); (act as? MainActivity)?.openDownloads() })
            body.addView(secondary("Close") { dialog.dismiss() }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 10.dp })
        }
    }

    private fun status() {
        body.removeAllViews(); footer.removeAllViews()
        val title = TextView(ctx).apply { textSize = 16f; setTextColor(Ui.TEXT); typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) }
        val line = TextView(ctx).apply { textSize = 13f; setTextColor(Ui.TEXT2); setPadding(0, 2.dp, 0, 10.dp); fontFeatureSettings = "tnum" }
        val bar = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000; progressDrawable = ctx.getDrawable(R.drawable.accent_track) }
        body.addView(title); body.addView(line)
        body.addView(bar, LinearLayout.LayoutParams(-1, 6.dp).apply { bottomMargin = 18.dp })

        val actions = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val pause = action(R.drawable.ic_pause, "Pause") {
            ids.mapNotNull { NovaDownloads.get(ctx, it) }.forEach { if (it.state != Download.STATE_COMPLETED) DownloadUi.toggle(ctx, it) }
        }
        val cancel = action(R.drawable.ic_delete, "Cancel") {
            ids.mapNotNull { NovaDownloads.get(ctx, it) }.forEach { DownloadUi.remove(ctx, it) }
            val drop = { x: DownloadStarter.Item -> items.any { it.card.id == x.card.id && it.season == x.season && it.episode == x.episode } }
            LocalStore.savePendingDownloads(LocalStore.pendingDownloads().filterNot(drop))
            LocalStore.saveUnresolved(LocalStore.unresolved().filterNot(drop))
            onChange(); dialog.dismiss()
        }
        val view = action(R.drawable.ic_download, "Downloads") { dialog.dismiss(); (act as? MainActivity)?.openDownloads() }
        listOf(pause, cancel, view).forEach { actions.addView(it, LinearLayout.LayoutParams(0, -2, 1f)) }
        body.addView(actions)

        // One episode of a show: offer the next ones right here
        val nextBtn = secondary("Download next 3 episodes") {}
        nextBtn.visibility = View.GONE
        body.addView(nextBtn, LinearLayout.LayoutParams(-1, 48.dp).apply { topMargin = 14.dp })
        if (items.size == 1 && first.card.type != MediaType.MOVIE) act.lifecycleScope.launch {
            val next = SmartDownloads.upcoming(first.card, first.season, first.episode, 3)
                .filter { (s, e) -> NovaDownloads.get(ctx, DownloadMeta.id(first.card, s, e)) == null }
            if (next.isEmpty()) return@launch
            (nextBtn as TextView).text = "Download next ${next.size} episode${if (next.size > 1) "s" else ""}"
            nextBtn.visibility = View.VISIBLE
            nextBtn.setOnClickListener {
                val more = next.map { (s, e) -> DownloadStarter.Item(first.card, s, e, quality = first.quality, provider = first.provider, height = first.height) }
                DownloadStarter.enqueue(ctx, more)
                items = items + more
                nextBtn.visibility = View.GONE
                Ui.confirm(it); onChange()
            }
        }
        body.addView(primary("Done") { dialog.dismiss() }, LinearLayout.LayoutParams(-1, 48.dp).apply { topMargin = 10.dp })

        val r = object : Runnable {
            override fun run() {
                val ds = ids.map { NovaDownloads.get(ctx, it) }
                val pending = LocalStore.pendingDownloads()
                val unresolved = LocalStore.unresolved()
                fun inList(l: List<DownloadStarter.Item>, i: DownloadStarter.Item) = l.any { it.card.id == i.card.id && it.season == i.season && it.episode == i.episode }
                val finding = items.count { i -> inList(pending, i) }
                val failed = items.count { i -> inList(unresolved, i) } + ds.count { it?.state == Download.STATE_FAILED }
                val started = ds.filterNotNull()
                val done = started.count { it.state == Download.STATE_COMPLETED }
                val total = started.sumOf { DlStats.total(it).coerceAtLeast(0) }
                val got = started.sumOf { it.bytesDownloaded }
                val speed = started.filter { it.state == Download.STATE_DOWNLOADING }.sumOf { DlStats.speed(it) }
                val allPaused = started.isNotEmpty() && started.filter { it.state != Download.STATE_COMPLETED }.let { a -> a.isNotEmpty() && a.all { DownloadUi.paused(it) } }
                when {
                    items.size == 1 && done == 1 -> { title.text = "Downloaded"; line.text = "Ready to watch offline"; bar.isIndeterminate = false; bar.progress = 1000 }
                    items.size == 1 && failed == 1 -> { title.text = "Couldn't find a source"; line.text = "Retry from Downloads in a bit"; bar.isIndeterminate = false; bar.progress = 0 }
                    started.isEmpty() -> { title.text = "Finding a source…"; line.text = "Usually takes a few seconds"; bar.isIndeterminate = true }
                    else -> {
                        bar.isIndeterminate = false
                        bar.progress = if (total > 0) (got * 1000 / total).toInt() else 0
                        title.text = when {
                            allPaused -> "Paused"
                            items.size == 1 -> "Downloading"
                            else -> "Downloading ${items.size} episodes"
                        }
                        line.text = listOfNotNull(
                            if (total > 0) "${(got * 100 / total)}%" else null,
                            if (speed > 0) "${DlStats.size(ctx, speed)}/s" else null,
                            if (speed > 0 && total > got) DlStats.eta((total - got) / speed) else null,
                            if (items.size > 1) "$done of ${items.size} done" else null,
                            if (finding > 0 && items.size > 1) "$finding finding sources" else null,
                            if (failed > 0 && items.size > 1) "$failed failed" else null,
                        ).joinToString("  ·  ")
                    }
                }
                (pause.getChildAt(0) as ImageView).setImageResource(if (allPaused) R.drawable.ic_play else R.drawable.ic_pause)
                (pause.getChildAt(1) as TextView).text = if (allPaused) "Resume" else "Pause"
                pause.alpha = if (started.any { it.state != Download.STATE_COMPLETED }) 1f else 0.35f
                main.postDelayed(this, 1000)
            }
        }
        poll = r
        main.post(r)
    }

    // ── Pieces ────────────────────────────────────────────────────────────────

    private fun label(t: String) = TextView(ctx).apply {
        text = t.uppercase(); textSize = 12f; letterSpacing = 0.04f; setTextColor(Ui.TEXT3); setPadding(2.dp, 6.dp, 0, 8.dp)
    }

    private fun switchRow(title: String, sub: String, on: Boolean, onToggle: (Boolean, TextView) -> Unit) = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(2.dp, 4.dp, 0, 4.dp)
        val subView = TextView(ctx).apply { text = sub; textSize = 13f; setTextColor(Ui.TEXT2) }
        addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(ctx).apply { text = title; textSize = 15f; setTextColor(Ui.TEXT) })
            addView(subView)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        val sw = Switch(ctx).apply {
            isChecked = on
            thumbTintList = android.content.res.ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(Color.WHITE, 0xFFBBBBBB.toInt()))
            trackTintList = android.content.res.ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(Ui.ACCENT, 0x55FFFFFF))
            setOnCheckedChangeListener { v, c -> Ui.tick(v); onToggle(c, subView) }
        }
        addView(sw)
        setOnClickListener { sw.toggle() }
    }

    private fun primary(t: String, onClick: () -> Unit) = TextView(ctx).apply {
        text = t; textSize = 16f; setTextColor(Color.BLACK); gravity = Gravity.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = 24f.dp }
        minHeight = 48.dp
        setOnClickListener { Ui.confirm(it); onClick() }
        Ui.pressable(this)
    }

    private fun secondary(t: String, onClick: () -> Unit) = TextView(ctx).apply {
        text = t; textSize = 15f; setTextColor(Ui.TEXT); gravity = Gravity.CENTER
        background = GradientDrawable().apply { setColor(Ui.SURFACE2); cornerRadius = 24f.dp }
        minHeight = 48.dp
        setOnClickListener { onClick() }
        Ui.pressable(this)
    }

    private fun action(icon: Int, t: String, onClick: () -> Unit) = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
        setPadding(0, 6.dp, 0, 6.dp)
        val tv = android.util.TypedValue(); ctx.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true)
        setBackgroundResource(tv.resourceId)
        addView(ImageView(ctx).apply {
            setImageResource(icon); imageTintList = android.content.res.ColorStateList.valueOf(Ui.TEXT)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Ui.SURFACE2) }
            setPadding(11.dp, 11.dp, 11.dp, 11.dp)
        }, LinearLayout.LayoutParams(44.dp, 44.dp))
        addView(TextView(ctx).apply { text = t; textSize = 12f; setTextColor(Ui.TEXT2); setPadding(0, 4.dp, 0, 0) })
        setOnClickListener { Ui.tick(it); onClick() }
    }
}
