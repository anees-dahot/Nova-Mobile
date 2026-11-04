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
    private val main = Handler(Looper.getMainLooper())
    private var poll: Runnable? = null
    private val first get() = items.first()
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
            addView(body, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 16.dp })
        }
        val width = minOf(ctx.resources.displayMetrics.widthPixels, 480.dp)
        val scrim = FrameLayout(ctx).apply {
            setBackgroundColor(0x99000000.toInt())
            setOnClickListener { dialog.dismiss() }
            addView(ScrollView(ctx).apply { isFillViewport = false; addView(sheet) },
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

    private fun choose() {
        body.removeAllViews()
        val s = LocalStore.settings()
        var quality = s.dlQuality
        var remember = false
        sources()
        val options = listOf(Triple(0, "480p", "Saves space"), Triple(1, "720p", "Standard"), Triple(2, "1080p", "Best picture"))
        body.addView(label("Quality"))
        val rows = mutableListOf<View>()
        fun paint() = rows.forEachIndexed { i, r ->
            (r.background as GradientDrawable).setStroke(if (i == quality) 2.dp else 1.dp, if (i == quality) Ui.ACCENT else Ui.HAIRLINE)
            r.findViewById<ImageView>(android.R.id.icon).visibility = if (i == quality) View.VISIBLE else View.INVISIBLE
        }
        options.forEach { (q, name, hint) ->
            val size = DownloadUi.size(ctx, DownloadStarter.estimateBytes(first.card, q) * items.size)
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                background = GradientDrawable().apply { cornerRadius = 12f.dp; setColor(Ui.SURFACE2) }
                setPadding(16.dp, 12.dp, 14.dp, 12.dp)
                addView(LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(TextView(ctx).apply { text = name; textSize = 16f; setTextColor(Ui.TEXT); typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) })
                    addView(TextView(ctx).apply { text = "$hint  ·  about $size"; textSize = 13f; setTextColor(Ui.TEXT2) })
                }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(ImageView(ctx).apply { id = android.R.id.icon; setImageResource(R.drawable.ic_check); imageTintList = android.content.res.ColorStateList.valueOf(Ui.ACCENT) },
                    LinearLayout.LayoutParams(22.dp, 22.dp))
                setOnClickListener { quality = q; Ui.tick(it); paint() }
            }
            rows += row
            body.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 8.dp })
        }
        paint()

        body.addView(switchRow("Remember my choice", "Don't ask next time (change in Settings)", false) { on, _ -> remember = on })

        body.addView(TextView(ctx).apply {
            text = "${DownloadUi.size(ctx, freeBytes())} free on this phone"; textSize = 12f; setTextColor(Ui.TEXT3); gravity = Gravity.CENTER
            setPadding(0, 14.dp, 0, 10.dp)
        })
        body.addView(primary("Download") {
            val cur = LocalStore.settings()
            LocalStore.saveSettings(cur.copy(dlQuality = if (remember) quality else cur.dlQuality, dlAskQuality = !remember))
            start(quality)
        })
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
        body.addView(label(if (items.size > 1) "Source  ·  for all ${items.size} episodes" else "Source"))
        val list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        body.addView(list, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 8.dp })
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
                addView(TextView(ctx).apply { text = title; textSize = 15f; setTextColor(Ui.TEXT); typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) })
                addView(TextView(ctx).apply { text = sub; textSize = 12.5f; setTextColor(if (highlight) Ui.ACCENT else Ui.TEXT2) })
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(ImageView(ctx).apply { id = android.R.id.icon; setImageResource(R.drawable.ic_check); imageTintList = android.content.res.ColorStateList.valueOf(Ui.ACCENT) },
                LinearLayout.LayoutParams(22.dp, 22.dp))
            setOnClickListener { provider = name; Ui.tick(it); paint() }
            alpha = 0f; animate().alpha(1f).setDuration(180).start()
        }
        val best = row(null, "Best", "The source with the most audio languages")
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
                val audio = if (names.isEmpty()) "Audio not labelled" else names.joinToString(" · ")
                val q = if (pr.height > 0) "  ·  ${pr.height}p" else ""
                val title = pr.provider.split('#').let { parts -> if (parts.size > 1) "${parts[0]}  ·  ${LangTags.name(parts[1]) ?: parts[1]} dub" else parts[0] }
                val r = row(pr.provider, title, audio + q, highlight = pr.langs.size >= 2)
                rows += pr.provider to r
                // Richest in languages floats to the top
                val at = 1 + counts.count { it >= pr.langs.size }
                counts += pr.langs.size
                list.addView(r, at.coerceAtMost(list.childCount - 1), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 8.dp })
                searching.text = "$found found · still checking…"
                paint()
            }
            searching.text = if (found == 0) "No source answered yet · Best keeps trying when you download" else "$found source${if (found > 1) "s" else ""} found"
        }
    }

    // ── Step 2: start + live status ───────────────────────────────────────────

    private fun start(quality: Int?) {
        val q = quality ?: LocalStore.settings().dlQuality
        val need = DownloadStarter.estimateBytes(first.card, q) * items.size
        if (need > freeBytes()) { problem("Not enough space", "About ${DownloadUi.size(ctx, need)} needed, ${DownloadUi.size(ctx, freeBytes())} free. Delete something you've watched, or pick a smaller quality."); return }
        (act as? MainActivity)?.ensureNotificationPermission()
        items = items.map { it.copy(quality = quality, provider = provider) }
        val added = DownloadStarter.enqueue(ctx, items)
        if (added == 0 && items.size == 1 && NovaDownloads.completed(ctx, ids.first())) {
            problem("Already downloaded", "It's in your Downloads and plays offline."); return
        }
        status()
        onChange()
    }

    private fun problem(title: String, msg: String) {
        body.removeAllViews()
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
        body.removeAllViews()
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
                val more = next.map { (s, e) -> DownloadStarter.Item(first.card, s, e, quality = first.quality) }
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
