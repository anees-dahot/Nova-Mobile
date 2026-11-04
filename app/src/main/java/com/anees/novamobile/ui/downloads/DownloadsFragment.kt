package com.anees.novamobile.ui.downloads

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.view.*
import android.widget.*
import androidx.fragment.app.Fragment
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadService
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.anees.novamobile.MainActivity
import com.anees.novamobile.R
import com.anees.novamobile.data.local.LocalStore
import com.anees.novamobile.data.model.MediaCard
import com.anees.novamobile.data.model.MediaType
import com.anees.novamobile.download.DlStats
import com.anees.novamobile.download.DownloadMeta
import com.anees.novamobile.download.DownloadStarter
import com.anees.novamobile.download.NovaDownloadService
import com.anees.novamobile.download.NovaDownloads
import com.anees.novamobile.ui.common.ActionSheet
import com.anees.novamobile.ui.common.Ui
import com.anees.novamobile.ui.common.dp
import com.anees.novamobile.ui.player.PlayerActivity

/**
 * Downloads, Netflix-style: a "Downloading" card pinned on top (collapsed to one line, tap to see
 * each item), then one row per show (a folder → [DownloadsShowFragment]) or movie. Finished downloads
 * are always on screen; nothing to scroll past.
 */
@UnstableApi
class DownloadsFragment : Fragment() {

    private sealed class Row(val key: String) {
        class Summary(val open: Boolean, val count: Int, val pct: Int, val detail: String, val attention: Int, val running: Boolean, val pausable: Boolean) : Row("summary")
        class Active(val d: Download, val meta: DownloadMeta?, val last: Boolean) : Row("a:${d.request.id}")
        class Pending(val item: DownloadStarter.Item, val searching: Boolean, val last: Boolean) : Row("p:${item.card.id}:${item.season}:${item.episode}")
        class Header(val title: String) : Row("h:$title")
        class Folder(val card: MediaCard, val done: List<Pair<Download, DownloadMeta>>, val active: Int, val pct: Int, val watched: Int, val selected: Boolean) : Row("f:${card.type}:${card.id}")
        class Movie(val d: Download, val meta: DownloadMeta, val watched: Float, val selected: Boolean) : Row("m:${d.request.id}")
    }

    private lateinit var list: RecyclerView
    private lateinit var storageBar: ProgressBar
    private lateinit var storage: TextView
    private lateinit var offline: TextView
    private lateinit var empty: View
    private lateinit var editBar: View
    private lateinit var saveSelected: TextView
    private lateinit var deleteSelected: TextView
    private lateinit var doneEditing: TextView
    private val adapter = Adapter()
    private val selected = mutableSetOf<String>()          // row keys (folders / movies)
    private val pendingRemoval = mutableSetOf<String>()    // download ids swiped away, waiting out Undo
    private var editing = false
    private val prefs by lazy { requireContext().getSharedPreferences("nova", 0) }
    private var cardOpen: Boolean
        get() = prefs.getBoolean("dl_card_open", false)
        set(v) { prefs.edit().putBoolean("dl_card_open", v).apply() }
    private val main = Handler(Looper.getMainLooper())
    /** Live progress each second while something downloads; idle lists rely on the listener and refresh slowly. */
    private var anyActive = true
    private val tick = object : Runnable { override fun run() { load(); main.postDelayed(this, if (anyActive) 1000 else 5000) } }
    private val listener = object : DownloadManager.Listener {
        override fun onDownloadChanged(m: DownloadManager, d: Download, e: Exception?) { main.post { load() } }
        override fun onDownloadRemoved(m: DownloadManager, d: Download) { main.post { load() } }
    }

    override fun onCreateView(inflater: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        val ctx = requireContext()
        val root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.BLACK); isClickable = true }
        root.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(4.dp, 8.dp, 4.dp, 0)
            addView(iconButton(ctx, R.drawable.ic_back, "Back") { parentFragmentManager.popBackStack() }.apply {
                if (arguments?.getBoolean("tab") == true) visibility = View.INVISIBLE   // bottom-bar tab: nothing to go back to
            })
            addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f))
            addView(iconButton(ctx, R.drawable.ic_more, "More") { moreMenu() })
        })
        root.addView(TextView(ctx).apply {
            text = "Downloads"; textSize = 32f; setTextColor(Ui.TEXT)
            typeface = Typeface.create("sans-serif", Typeface.BOLD); letterSpacing = -0.02f
            setPadding(16.dp, 0, 16.dp, 0)
        })
        offline = TextView(ctx).apply {
            text = "You're offline — your downloads still play"
            textSize = 13f; setTextColor(Ui.WARNING); setPadding(16.dp, 4.dp, 16.dp, 0); visibility = View.GONE
            setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_offline, 0, 0, 0); compoundDrawablePadding = 6.dp
            compoundDrawableTintList = android.content.res.ColorStateList.valueOf(Ui.WARNING)
        }
        root.addView(offline)
        storage = TextView(ctx).apply { textSize = 13f; setTextColor(Ui.TEXT2); setPadding(16.dp, 6.dp, 16.dp, 6.dp) }
        root.addView(storage)
        storageBar = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000; progressDrawable = ctx.getDrawable(R.drawable.accent_track)
        }
        root.addView(storageBar, LinearLayout.LayoutParams(-1, 4.dp).apply { setMargins(16.dp, 0, 16.dp, 10.dp) })

        val frame = FrameLayout(ctx)
        list = RecyclerView(ctx).apply {
            layoutManager = LinearLayoutManager(ctx); adapter = this@DownloadsFragment.adapter
            (itemAnimator as? androidx.recyclerview.widget.SimpleItemAnimator)?.supportsChangeAnimations = false
            clipToPadding = false; setPadding(0, 0, 0, 24.dp)
        }
        frame.addView(list)
        empty = emptyState(ctx)
        frame.addView(empty, FrameLayout.LayoutParams(-1, -1))
        root.addView(frame, LinearLayout.LayoutParams(-1, 0, 1f))

        editBar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; visibility = View.GONE
            setBackgroundColor(Ui.SURFACE); setPadding(16.dp, 12.dp, 16.dp, 12.dp)
            doneEditing = TextView(ctx).apply { text = "Done"; textSize = 15f; setTextColor(Ui.ACCENT); setOnClickListener { setEditing(false) } }
            addView(doneEditing)
            addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f))
            saveSelected = TextView(ctx).apply {
                textSize = 15f; setTextColor(Ui.ACCENT); typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setPadding(0, 0, 20.dp, 0)
                setOnClickListener { saveSelection() }
            }
            addView(saveSelected)
            deleteSelected = TextView(ctx).apply {
                textSize = 15f; setTextColor(Ui.DANGER); typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setOnClickListener { deleteSelection() }
            }
            addView(deleteSelected)
        }
        root.addView(editBar, LinearLayout.LayoutParams(-1, -2))
        attachSwipeToDelete()
        return root
    }

    private fun iconButton(ctx: android.content.Context, icon: Int, label: String, onClick: () -> Unit) = ImageButton(ctx).apply {
        setImageResource(icon); background = null; contentDescription = label
        val tv = android.util.TypedValue(); ctx.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true)
        setBackgroundResource(tv.resourceId)
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(48.dp, 48.dp)
    }

    private fun emptyState(ctx: android.content.Context) = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; visibility = View.GONE
        setPadding(32.dp, 0, 32.dp, 64.dp)
        addView(ImageView(ctx).apply { setImageResource(R.drawable.ic_download); imageTintList = android.content.res.ColorStateList.valueOf(Ui.TEXT3) },
            LinearLayout.LayoutParams(56.dp, 56.dp))
        addView(TextView(ctx).apply {
            text = "No downloads yet"; textSize = 20f; setTextColor(Ui.TEXT); typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER; setPadding(0, 16.dp, 0, 6.dp)
        })
        addView(TextView(ctx).apply {
            text = "Download movies and episodes to watch anywhere, even without internet."
            textSize = 14f; setTextColor(Ui.TEXT2); gravity = Gravity.CENTER; setLineSpacing(0f, 1.2f)
        })
        addView(TextView(ctx).apply {
            text = "Find something to download"; textSize = 15f; setTextColor(Color.BLACK); gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = 22f.dp }
            setPadding(22.dp, 0, 22.dp, 0)
            setOnClickListener { parentFragmentManager.popBackStack(); (activity as? MainActivity)?.switchTab(0) }
            Ui.pressable(this)
        }, LinearLayout.LayoutParams(-2, 44.dp).apply { topMargin = 24.dp })
    }

    override fun onResume() {
        super.onResume()
        NovaDownloads.manager(requireContext()).addListener(listener)
        main.post(tick)
    }

    override fun onPause() {
        super.onPause()
        NovaDownloads.manager(requireContext()).removeListener(listener)
        main.removeCallbacks(tick)
    }

    // ── Data ──────────────────────────────────────────────────────────────────

    private fun load() {
        val ctx = context ?: return
        val all = NovaDownloads.all(ctx).filter { it.state != Download.STATE_REMOVING && it.request.id !in pendingRemoval }
        val withMeta = all.mapNotNull { d -> DownloadMeta.of(d)?.let { d to it } }
        val active = withMeta.filter { it.first.state != Download.STATE_COMPLETED }
        val pending = LocalStore.pendingDownloads()
        val unresolved = LocalStore.unresolved()
        val rows = mutableListOf<Row>()

        // Pinned card: everything not finished yet
        val activeCount = active.size + pending.size + unresolved.size
        anyActive = active.isNotEmpty() || pending.isNotEmpty()
        if (activeCount > 0) {
            val attention = active.count { it.first.state == Download.STATE_FAILED } + unresolved.size
            val running = active.map { it.first }.filter { it.state == Download.STATE_DOWNLOADING && !DownloadUi.paused(it) }
            val speed = running.sumOf { DlStats.speed(it) }
            val total = active.sumOf { DlStats.total(it.first).coerceAtLeast(0) }
            val done = active.sumOf { it.first.bytesDownloaded }
            val pct = if (total > 0) (done * 100 / total).toInt() else 0
            val left = (total - done).coerceAtLeast(0)
            val anyRunning = active.any { !DownloadUi.paused(it.first) && it.first.state != Download.STATE_FAILED } || pending.isNotEmpty()
            val working = activeCount - attention
            val detail = when {
                working == 0 -> "Tap to retry or remove"
                NovaDownloads.storageFull -> "Storage full"
                !anyRunning && active.isNotEmpty() -> "Paused · $pct%"
                speed > 0 -> listOf("$pct%", "${DlStats.size(ctx, speed)}/s", if (left > 0) DlStats.eta(left / speed) else "").filter { it.isNotEmpty() }.joinToString(" · ")
                pending.isNotEmpty() && active.isEmpty() -> "Finding sources…"
                else -> "Waiting…"
            }
            val open = cardOpen
            rows += Row.Summary(open, working, pct, detail, attention, anyRunning,
                pausable = active.any { it.first.state != Download.STATE_FAILED })
            if (open) {
                val items: List<Row> = active.sortedBy { if (it.first.state == Download.STATE_FAILED) 0 else 1 }.map { Row.Active(it.first, it.second, false) } +
                    unresolved.map { Row.Pending(it, searching = false, last = false) } +
                    pending.map { Row.Pending(it, searching = true, last = false) }
                items.forEachIndexed { i, r ->
                    val last = i == items.lastIndex
                    rows += when (r) { is Row.Active -> Row.Active(r.d, r.meta, last); is Row.Pending -> Row.Pending(r.item, r.searching, last); else -> r }
                }
            }
        }

        // Folders (shows) and movies: anything with at least one finished download
        val finished = withMeta.filter { it.first.state == Download.STATE_COMPLETED }
        if (finished.isNotEmpty()) rows += Row.Header("Downloaded")
        withMeta.groupBy { "${it.second.card.type}:${it.second.card.id}" }.values
            .filter { g -> g.any { it.first.state == Download.STATE_COMPLETED } }
            .sortedByDescending { g -> g.maxOf { it.second.createdAt } }      // most recent first
            .forEach { g ->
                val meta = g.first().second
                val done = g.filter { it.first.state == Download.STATE_COMPLETED }
                if (meta.card.type == MediaType.MOVIE || meta.season <= 0 && g.size == 1) {
                    val (d, m) = done.first()
                    rows += Row.Movie(d, m, DownloadUi.watched(m), "m:${d.request.id}" in selected)
                } else {
                    val act = g.filter { it.first.state != Download.STATE_COMPLETED }
                    val total = act.sumOf { DlStats.total(it.first).coerceAtLeast(0) }
                    val pct = if (total > 0) (act.sumOf { it.first.bytesDownloaded } * 1000 / total).toInt() else 0
                    val key = "f:${meta.card.type}:${meta.card.id}"
                    rows += Row.Folder(meta.card, done.sortedWith(compareBy({ it.second.season }, { it.second.episode })), act.size, pct,
                        done.count { DownloadUi.watched(it.second) >= 0.9f }, key in selected)
                }
            }

        adapter.submitList(rows)
        empty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE

        val stat = StatFs(NovaDownloads.dir.absolutePath)
        val used = all.sumOf { it.bytesDownloaded }
        storageBar.progress = if (used + stat.availableBytes > 0) (used * 1000 / (used + stat.availableBytes)).toInt() else 0
        storage.text = "${DownloadUi.size(ctx, used)} used by Nova  ·  ${DownloadUi.size(ctx, stat.availableBytes)} free"
        storage.setTextColor(if (NovaDownloads.storageFull) Ui.DANGER else Ui.TEXT2)
        offline.visibility = if (isOnline()) View.GONE else View.VISIBLE
        deleteSelected.text = if (selected.isEmpty()) "Delete" else "Delete (${selected.size})"
        deleteSelected.alpha = if (selected.isEmpty()) 0.4f else 1f
        saveSelected.text = if (selected.isEmpty()) "Save" else "Save (${selected.size})"
        saveSelected.alpha = if (selected.isEmpty()) 0.4f else 1f
    }

    private fun isOnline(): Boolean {
        val cm = context?.getSystemService(ConnectivityManager::class.java) ?: return true
        return cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }

    // ── Actions ───────────────────────────────────────────────────────────────

    private fun moreMenu() {
        val ctx = requireContext()
        val s = LocalStore.settings()
        val anyActive = NovaDownloads.all(ctx).any { it.state != Download.STATE_COMPLETED }
        val running = NovaDownloads.all(ctx).any { it.state != Download.STATE_COMPLETED && it.state != Download.STATE_FAILED && !DownloadUi.paused(it) }
        val items = mutableListOf<ActionSheet.Item>()
        items += ActionSheet.Item("Select to delete") { setEditing(true) }
        items += ActionSheet.Item("Delete everything watched") { deleteWatched() }
        if (anyActive) items += ActionSheet.Item(if (running) "Pause all downloads" else "Resume all downloads") { togglePauseAll() }
        items += ActionSheet.Item("Smart Downloads", header = true)
        items += ActionSheet.Item(if (s.smartDownloads) "On  ·  keeps ${s.keepNext} next episode${if (s.keepNext > 1) "s" else ""} ready" else "Off", checked = s.smartDownloads) { smartDownloadsSheet() }
        ActionSheet.show(ctx, null, items)
    }

    private fun togglePauseAll() {
        val ctx = requireContext()
        val all = NovaDownloads.all(ctx)
        val running = all.any { it.state != Download.STATE_COMPLETED && it.state != Download.STATE_FAILED && !DownloadUi.paused(it) }
        if (running) DownloadService.sendPauseDownloads(ctx, NovaDownloadService::class.java, false)
        else {
            NovaDownloads.clearStorageFull(ctx)
            DownloadService.sendResumeDownloads(ctx, NovaDownloadService::class.java, false)
            all.filter { it.stopReason != Download.STOP_REASON_NONE }.forEach {
                DownloadService.sendSetStopReason(ctx, NovaDownloadService::class.java, it.request.id, Download.STOP_REASON_NONE, false)
            }
        }
        load()
    }

    private fun setEditing(on: Boolean) {
        editing = on
        selected.clear()
        editBar.visibility = if (on) View.VISIBLE else View.GONE
        load()
        adapter.notifyItemRangeChanged(0, adapter.itemCount)
    }

    /** Download ids behind a selected row key (a folder = all its episodes). */
    private fun idsFor(key: String): List<Download> {
        val ctx = requireContext()
        return NovaDownloads.all(ctx).filter { d ->
            val m = DownloadMeta.of(d) ?: return@filter false
            key == "m:${d.request.id}" || key == "f:${m.card.type}:${m.card.id}"
        }.sortedWith(compareBy({ DownloadMeta.of(it)?.season ?: 0 }, { DownloadMeta.of(it)?.episode ?: 0 }))
    }

    private fun deleteSelection() {
        if (selected.isEmpty()) return
        val ctx = requireContext()
        val targets = selected.flatMap { idsFor(it) }
        val bytes = DownloadUi.size(ctx, targets.sumOf { it.bytesDownloaded })
        ActionSheet.show(ctx, "Delete ${targets.size} download${if (targets.size > 1) "s" else ""}?", listOf(
            ActionSheet.Item("Delete and free $bytes") { DownloadUi.delete(ctx, targets) { setEditing(false) } },
            ActionSheet.Item("Keep")
        ))
    }

    /** Bulk save: every finished download behind the selected rows, in episode order. */
    private fun saveSelection() {
        if (selected.isEmpty()) return
        val targets = selected.flatMap { idsFor(it) }
            .filter { it.state == Download.STATE_COMPLETED }
            .distinctBy { it.request.id }
            .sortedWith(compareBy({ DownloadMeta.of(it)?.season ?: 0 }, { DownloadMeta.of(it)?.episode ?: 0 }))
        if (targets.isEmpty()) { (activity as? MainActivity)?.showBanner("Finish downloading first"); return }
        (activity as? MainActivity)?.let { SaveToPhone.show(it, targets) }
    }

    private fun deleteWatched() {
        val ctx = requireContext()
        val watched = NovaDownloads.all(ctx).filter { d ->
            d.state == Download.STATE_COMPLETED && DownloadMeta.of(d)?.let { DownloadUi.watched(it) >= 0.9f } == true
        }
        if (watched.isEmpty()) { (activity as? MainActivity)?.showBanner("Nothing watched to delete"); return }
        val bytes = DownloadUi.size(ctx, watched.sumOf { it.bytesDownloaded })
        ActionSheet.show(ctx, "Delete ${watched.size} watched?", listOf(
            ActionSheet.Item("Delete and free $bytes") { DownloadUi.delete(ctx, watched) { main.postDelayed({ load() }, 300) } },
            ActionSheet.Item("Keep")
        ))
    }

    private fun smartDownloadsSheet() {
        val ctx = requireContext()
        val s = LocalStore.settings()
        ActionSheet.show(ctx, "Smart Downloads", listOf(
            ActionSheet.Item(if (s.smartDownloads) "Turn off" else "Turn on") { LocalStore.saveSettings(s.copy(smartDownloads = !s.smartDownloads)); load() },
            ActionSheet.Item("Keep ready", header = true),
        ) + (1..3).map { n ->
            ActionSheet.Item("Next $n episode${if (n > 1) "s" else ""}", checked = s.keepNext == n) { LocalStore.saveSettings(LocalStore.settings().copy(keepNext = n)) }
        }, subtitle = "Finish a downloaded episode and Nova deletes it, then downloads the next one. New episodes of shows you follow download on their own.")
    }

    private fun movieMenu(d: Download, m: DownloadMeta) {
        val ctx = requireContext()
        ActionSheet.show(ctx, m.card.title, listOf(
            ActionSheet.Item("Play") { PlayerActivity.launch(ctx, m.card, m.season, m.episode) },
            ActionSheet.Item("Go to movie") { (activity as? MainActivity)?.openDetail(m.card) },
            ActionSheet.Item("Save to phone…") { (activity as? MainActivity)?.let { SaveToPhone.show(it, listOf(d)) } },
            ActionSheet.Item("Delete download") { DownloadUi.delete(ctx, listOf(d)) { main.postDelayed({ load() }, 300) } },
        ), subtitle = DownloadUi.details(m).ifBlank { null })
    }

    private fun folderMenu(f: Row.Folder) {
        val ctx = requireContext()
        ActionSheet.show(ctx, f.card.title, listOf(
            ActionSheet.Item("Open") { (activity as? MainActivity)?.openDownloadsShow(f.card) },
            ActionSheet.Item(if (f.done.size == 1) "Save to phone…" else "Save all ${f.done.size} to phone…") { (activity as? MainActivity)?.let { SaveToPhone.show(it, idsFor(f.key)) } },
            ActionSheet.Item("Download more episodes") { (activity as? MainActivity)?.openDetail(f.card) },
            ActionSheet.Item(if (f.done.size == 1) "Delete episode" else "Delete all ${f.done.size} episodes") { DownloadUi.delete(ctx, idsFor(f.key)) { main.postDelayed({ load() }, 300) } },
        ))
    }

    private fun activeMenu(d: Download, m: DownloadMeta?) {
        val ctx = requireContext()
        ActionSheet.show(ctx, m?.let { DownloadUi.name(it) }, listOfNotNull(
            if (d.state == Download.STATE_FAILED) ActionSheet.Item("Retry") { NovaDownloads.retry(ctx, d) }
            else ActionSheet.Item(if (DownloadUi.paused(d)) "Resume" else "Pause") { DownloadUi.toggle(ctx, d) },
            m?.let { ActionSheet.Item("Go to ${if (it.card.type == MediaType.MOVIE) "movie" else "show"}") { (activity as? MainActivity)?.openDetail(it.card) } },
            ActionSheet.Item("Cancel download") { DownloadUi.remove(ctx, d); main.postDelayed({ load() }, 300) },
        ))
    }

    /** Swipe: hide now, delete after 4s unless Undo is tapped. */
    private fun removeWithUndo(targets: List<Download>, label: String) {
        DownloadUi.confirmDelete(requireContext(), targets, onCancel = { adapter.notifyDataSetChanged() /* un-swipe the row */ }) { phone -> removeWithUndo(targets, label, phone) }
    }

    private fun removeWithUndo(targets: List<Download>, label: String, alsoPhone: Boolean) {
        val ids = targets.map { it.request.id }
        pendingRemoval += ids
        load()
        val ctx = requireContext().applicationContext
        val commit = Runnable { if (pendingRemoval.removeAll(ids.toSet())) targets.forEach { DownloadUi.remove(ctx, it, alsoPhone) } }
        main.postDelayed(commit, 4000)
        (activity as? MainActivity)?.showBanner("Deleted $label", "Undo") { main.removeCallbacks(commit); pendingRemoval -= ids.toSet(); load() }
    }

    private fun attachSwipeToDelete() {
        val red = Paint().apply { color = Ui.DANGER }
        val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 15f.dp; textAlign = Paint.Align.RIGHT }
        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT) {
            override fun getSwipeDirs(rv: RecyclerView, vh: RecyclerView.ViewHolder) = when (adapter.currentList.getOrNull(vh.bindingAdapterPosition)) {
                is Row.Folder, is Row.Movie -> if (editing) 0 else ItemTouchHelper.LEFT
                else -> 0
            }
            override fun onMove(rv: RecyclerView, a: RecyclerView.ViewHolder, b: RecyclerView.ViewHolder) = false
            override fun onSwiped(vh: RecyclerView.ViewHolder, dir: Int) {
                when (val r = adapter.currentList.getOrNull(vh.bindingAdapterPosition)) {
                    is Row.Movie -> removeWithUndo(listOf(r.d), r.meta.card.title)
                    is Row.Folder -> removeWithUndo(idsFor(r.key).filter { it.state == Download.STATE_COMPLETED }, "${r.card.title} (${r.done.size})")
                    else -> {}
                }
            }
            override fun onChildDraw(c: Canvas, rv: RecyclerView, vh: RecyclerView.ViewHolder, dX: Float, dY: Float, state: Int, active: Boolean) {
                val v = vh.itemView
                c.drawRect(v.right + dX, v.top.toFloat(), v.right.toFloat(), v.bottom.toFloat(), red)
                c.drawText("Delete", v.right - 20f.dp, v.top + v.height / 2f + label.textSize / 3, label)
                super.onChildDraw(c, rv, vh, dX, dY, state, active)
            }
        }).attachToRecyclerView(list)
    }

    // ── List ──────────────────────────────────────────────────────────────────

    private object Diff : DiffUtil.ItemCallback<Row>() {
        override fun areItemsTheSame(a: Row, b: Row) = a.key == b.key
        override fun areContentsTheSame(a: Row, b: Row): Boolean = when {
            a is Row.Summary && b is Row.Summary -> a.open == b.open && a.count == b.count && a.pct == b.pct && a.detail == b.detail && a.attention == b.attention && a.running == b.running && a.pausable == b.pausable
            a is Row.Active && b is Row.Active -> a.d.state == b.d.state && a.d.bytesDownloaded == b.d.bytesDownloaded && a.d.stopReason == b.d.stopReason && a.last == b.last
            a is Row.Pending && b is Row.Pending -> a.searching == b.searching && a.last == b.last
            a is Row.Folder && b is Row.Folder -> a.done.size == b.done.size && a.active == b.active && a.pct == b.pct && a.watched == b.watched && a.selected == b.selected
            a is Row.Movie && b is Row.Movie -> a.watched == b.watched && a.selected == b.selected
            a is Row.Header && b is Row.Header -> true
            else -> false
        }
    }

    private inner class Adapter : ListAdapter<Row, RecyclerView.ViewHolder>(Diff) {
        override fun getItemViewType(p: Int) = when (getItem(p)) {
            is Row.Summary -> 0; is Row.Active, is Row.Pending -> 1; is Row.Header -> 2; else -> 3
        }

        override fun onCreateViewHolder(parent: ViewGroup, type: Int): RecyclerView.ViewHolder {
            val ctx = parent.context
            val inflater = LayoutInflater.from(ctx)
            val v = when (type) {
                0 -> summaryView(ctx)
                1 -> inflater.inflate(R.layout.item_dl_active, parent, false).also { a ->
                    a.findViewById<View>(R.id.a_poster).apply { outlineProvider = rounded(6f.dp); clipToOutline = true }
                }
                2 -> TextView(ctx).apply {
                    textSize = 20f; setTextColor(Ui.TEXT); typeface = Typeface.create("sans-serif", Typeface.BOLD); letterSpacing = -0.01f
                    setPadding(16.dp, 20.dp, 16.dp, 4.dp)
                    layoutParams = RecyclerView.LayoutParams(-1, -2)
                }
                else -> inflater.inflate(R.layout.item_dl_folder, parent, false).also { f ->
                    f.findViewById<View>(R.id.f_frame).apply { outlineProvider = rounded(8f.dp); clipToOutline = true }
                    Ui.pressable(f, 0.98f)
                }
            }
            return object : RecyclerView.ViewHolder(v) {}
        }

        private fun rounded(r: Float) = object : ViewOutlineProvider() { override fun getOutline(v: View, o: Outline) = o.setRoundRect(0, 0, v.width, v.height, r) }

        private fun summaryView(ctx: android.content.Context) = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            layoutParams = RecyclerView.LayoutParams(-1, -2).apply { setMargins(12.dp, 4.dp, 12.dp, 0) }
            setPadding(14.dp, 12.dp, 6.dp, 12.dp)
            isClickable = true
            val tv = android.util.TypedValue(); ctx.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
            foreground = ctx.getDrawable(tv.resourceId)
            addView(ProgressRing(ctx).apply { id = R.id.a_ring; isClickable = false }, LinearLayout.LayoutParams(36.dp, 36.dp))
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL; setPadding(12.dp, 0, 0, 0)
                addView(TextView(ctx).apply { id = R.id.a_title; textSize = 15f; setTextColor(Ui.TEXT); typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) })
                addView(TextView(ctx).apply { id = R.id.a_sub; textSize = 13f; setTextColor(Ui.TEXT2); maxLines = 1; fontFeatureSettings = "tnum" })
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(ImageButton(ctx).apply {
                id = R.id.f_end; background = null; contentDescription = "Pause all"
                imageTintList = android.content.res.ColorStateList.valueOf(Ui.TEXT)
            }, LinearLayout.LayoutParams(44.dp, 44.dp))
            addView(ImageView(ctx).apply { id = R.id.f_check; setImageResource(R.drawable.ic_expand); imageTintList = android.content.res.ColorStateList.valueOf(Ui.TEXT3) },
                LinearLayout.LayoutParams(24.dp, 24.dp).apply { marginEnd = 6.dp })
        }

        override fun onBindViewHolder(h: RecyclerView.ViewHolder, p: Int) {
            val v = h.itemView
            when (val row = getItem(p)) {
                is Row.Summary -> {
                    val last = !row.open
                    v.background = GradientDrawable().apply {
                        setColor(Ui.SURFACE)
                        val r = 14f.dp
                        cornerRadii = if (last) floatArrayOf(r, r, r, r, r, r, r, r) else floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
                    }
                    val attention = if (row.attention > 0) "${row.attention} need${if (row.attention == 1) "s" else ""} attention" else null
                    v.findViewById<ProgressRing>(R.id.a_ring).apply {
                        state = when { row.count == 0 -> ProgressRing.State.FAILED; row.running -> ProgressRing.State.DOWNLOADING; else -> ProgressRing.State.PAUSED }
                        progress = row.pct / 100f
                    }
                    v.findViewById<TextView>(R.id.a_title).text =
                        listOfNotNull(if (row.count > 0) "${row.count} downloading" else null, attention).joinToString("  ·  ")
                    v.findViewById<TextView>(R.id.a_sub).apply { text = row.detail; setTextColor(if (row.count == 0) Ui.DANGER else Ui.TEXT2) }
                    v.findViewById<ImageButton>(R.id.f_end).visibility = if (row.pausable) View.VISIBLE else View.GONE
                    v.findViewById<ImageButton>(R.id.f_end).apply {
                        setImageResource(if (row.running) R.drawable.ic_pause else R.drawable.ic_play)
                        contentDescription = if (row.running) "Pause all" else "Resume all"
                        setOnClickListener { Ui.tick(it); togglePauseAll() }
                    }
                    v.findViewById<ImageView>(R.id.f_check).animate().rotation(if (row.open) 180f else 0f).setDuration(180).start()
                    v.setOnClickListener { cardOpen = !cardOpen; Ui.tick(v); load() }
                }
                is Row.Active -> bindActive(v, row)
                is Row.Pending -> bindPending(v, row)
                is Row.Header -> (v as TextView).text = row.title
                is Row.Folder -> bindFolder(v, row)
                is Row.Movie -> bindMovie(v, row)
            }
        }

        private fun cardBottom(v: View, last: Boolean) {
            v.background = if (last) GradientDrawable().apply {
                setColor(Ui.SURFACE); val r = 14f.dp; cornerRadii = floatArrayOf(0f, 0f, 0f, 0f, r, r, r, r)
            } else android.graphics.drawable.ColorDrawable(Ui.SURFACE)
            (v.layoutParams as? ViewGroup.MarginLayoutParams)?.bottomMargin = if (last) 4.dp else 0
        }

        private fun bindActive(v: View, row: Row.Active) {
            val ctx = requireContext()
            val m = row.meta
            cardBottom(v, row.last)
            v.findViewById<ImageView>(R.id.a_poster).load(m?.card?.posterUrl ?: m?.card?.backdropUrl) { crossfade(false) }
            v.findViewById<TextView>(R.id.a_title).text = m?.let { DownloadUi.name(it) } ?: "Video"
            v.findViewById<TextView>(R.id.a_sub).apply {
                text = DownloadUi.status(ctx, row.d)
                setTextColor(if (row.d.state == Download.STATE_FAILED) Ui.DANGER else Ui.TEXT2)
            }
            val ring = v.findViewById<ProgressRing>(R.id.a_ring)
            ring.state = DownloadUi.ring(row.d)
            ring.progress = (row.d.percentDownloaded / 100f).coerceAtLeast(0f)
            ring.setOnClickListener { Ui.tick(it); DownloadUi.toggle(ctx, row.d); load() }
            v.setOnClickListener { activeMenu(row.d, m) }
            v.setOnLongClickListener { activeMenu(row.d, m); true }
        }

        private fun bindPending(v: View, row: Row.Pending) {
            val ctx = requireContext()
            val i = row.item
            cardBottom(v, row.last)
            v.findViewById<ImageView>(R.id.a_poster).load(i.card.posterUrl ?: i.card.backdropUrl) { crossfade(false) }
            v.findViewById<TextView>(R.id.a_title).text = if (i.card.type == MediaType.MOVIE) i.card.title
                else "${i.card.title} · ${if (i.card.type == MediaType.ANIME) "Episode ${i.episode}" else "S${i.season} · E${i.episode}"}"
            v.findViewById<TextView>(R.id.a_sub).apply {
                text = if (row.searching) "Finding a source…" else "No source had it · tap to retry"
                setTextColor(if (row.searching) Ui.TEXT2 else Ui.DANGER)
            }
            val ring = v.findViewById<ProgressRing>(R.id.a_ring)
            ring.state = if (row.searching) ProgressRing.State.QUEUED else ProgressRing.State.FAILED
            ring.progress = 0f
            val same = { x: DownloadStarter.Item -> x.card.id == i.card.id && x.season == i.season && x.episode == i.episode }
            val retry = View.OnClickListener {
                if (!row.searching) { LocalStore.saveUnresolved(LocalStore.unresolved().filterNot(same)); DownloadStarter.enqueue(ctx, listOf(i)); load() }
            }
            ring.setOnClickListener(retry)
            v.setOnClickListener(retry)
            v.setOnLongClickListener {
                ActionSheet.show(ctx, i.card.title, listOf(ActionSheet.Item("Remove from Downloads") {
                    LocalStore.savePendingDownloads(LocalStore.pendingDownloads().filterNot(same))
                    LocalStore.saveUnresolved(LocalStore.unresolved().filterNot(same))
                    load()
                }))
                true
            }
        }

        private fun bindCommon(v: View, poster: String?, title: String, sub: String, key: String, isSelected: Boolean) {
            v.findViewById<ImageView>(R.id.f_poster).load(poster) { crossfade(false) }
            v.findViewById<TextView>(R.id.f_title).text = title
            v.findViewById<TextView>(R.id.f_sub).text = sub
            listOf(R.id.f_badge, R.id.f_progress, R.id.f_count).forEach { v.findViewById<View>(it).visibility = View.GONE }
            v.findViewById<CheckBox>(R.id.f_check).apply { visibility = if (editing) View.VISIBLE else View.GONE; isChecked = isSelected }
            v.findViewById<View>(R.id.f_end).visibility = if (editing) View.GONE else View.VISIBLE
            if (editing) {
                v.setOnClickListener { if (!selected.remove(key)) selected += key; Ui.tick(v); load() }
                v.setOnLongClickListener(null)
            }
        }

        private fun bindFolder(v: View, row: Row.Folder) {
            val ctx = requireContext()
            val bytes = row.done.sumOf { it.first.bytesDownloaded }
            val n = row.done.size
            val sub = buildList {
                add("$n episode${if (n > 1) "s" else ""}")
                add(DownloadUi.size(ctx, bytes))
                if (row.watched > 0) add("${row.watched} watched")
            }.joinToString("  ·  ")
            bindCommon(v, row.card.posterUrl ?: row.card.backdropUrl, row.card.title, sub, row.key, row.selected)
            if (n > 1) v.findViewById<TextView>(R.id.f_count).apply { text = "$n"; visibility = View.VISIBLE }
            if (row.active > 0) {
                v.findViewById<TextView>(R.id.f_badge).apply { text = "${row.active} more downloading"; visibility = View.VISIBLE }
                v.findViewById<ProgressBar>(R.id.f_progress).apply { progress = row.pct; visibility = View.VISIBLE }
            }
            v.findViewById<ImageView>(R.id.f_end).setImageResource(R.drawable.ic_chevron)
            if (!editing) {
                v.setOnClickListener { Ui.tick(v); (activity as? MainActivity)?.openDownloadsShow(row.card) }
                v.setOnLongClickListener { folderMenu(row); true }
            }
        }

        private fun bindMovie(v: View, row: Row.Movie) {
            val ctx = requireContext()
            val m = row.meta
            val sub = listOfNotNull(m.card.year.ifBlank { null }, m.card.runtime?.takeIf { it > 0 }?.let { "${it / 60}h ${it % 60}m" },
                DownloadUi.size(ctx, row.d.bytesDownloaded)).joinToString("  ·  ")
            bindCommon(v, m.card.posterUrl ?: m.card.backdropUrl, if (m.season > 0) DownloadUi.name(m) else m.card.title, sub, row.key, row.selected)
            DownloadUi.details(m).takeIf { it.isNotBlank() }?.let { d -> v.findViewById<TextView>(R.id.f_badge).apply { text = d; visibility = View.VISIBLE } }
            if (row.watched > 0.02f) v.findViewById<ProgressBar>(R.id.f_progress).apply { progress = (row.watched * 1000).toInt(); visibility = View.VISIBLE }
            v.findViewById<ImageView>(R.id.f_end).setImageResource(R.drawable.ic_play)
            if (!editing) {
                v.setOnClickListener { PlayerActivity.launch(ctx, m.card, m.season, m.episode) }
                v.setOnLongClickListener { movieMenu(row.d, m); true }
            }
        }
    }
}
