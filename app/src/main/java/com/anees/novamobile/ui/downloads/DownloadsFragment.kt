package com.anees.novamobile.ui.downloads

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.text.format.Formatter
import android.view.*
import android.widget.*
import androidx.fragment.app.Fragment
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadService
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.anees.novamobile.R
import com.anees.novamobile.data.local.LocalStore
import com.anees.novamobile.data.model.MediaType
import com.anees.novamobile.download.DownloadMeta
import com.anees.novamobile.download.DownloadStarter
import com.anees.novamobile.download.NovaDownloadService
import com.anees.novamobile.download.NovaDownloads
import com.anees.novamobile.ui.common.ActionSheet
import com.anees.novamobile.ui.player.PlayerActivity

/** Downloads, Apple-style: large title, storage line, grouped by show, progress rings, swipe left to delete. */
@UnstableApi
class DownloadsFragment : Fragment() {

    private sealed class Row {
        data class Header(val title: String) : Row()
        data class Dl(val d: Download, val meta: DownloadMeta?) : Row()
        data class Unresolved(val item: DownloadStarter.Item) : Row()
    }

    private val rows = mutableListOf<Row>()
    private lateinit var list: RecyclerView
    private lateinit var storage: TextView
    private lateinit var toggleAll: TextView
    private lateinit var empty: View
    private val tick = Handler(Looper.getMainLooper())
    private val refresher = object : Runnable { override fun run() { load(); tick.postDelayed(this, 1000) } }
    private var lastBytes = HashMap<String, Pair<Long, Long>>()   // id → (bytes, time) for speed

    override fun onCreateView(inflater: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        val ctx = requireContext()
        val dp = resources.displayMetrics.density
        val root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.BLACK); isClickable = true }
        // Header: back, large title (tight tracking), storage + pause/resume all
        root.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding((8 * dp).toInt(), (8 * dp).toInt(), (16 * dp).toInt(), 0)
            addView(ImageButton(ctx).apply {
                setImageResource(R.drawable.ic_back); background = null
                setOnClickListener { parentFragmentManager.popBackStack() }
            }, LinearLayout.LayoutParams((44 * dp).toInt(), (44 * dp).toInt()))
            addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f))
            toggleAll = TextView(ctx).apply {
                textSize = 14f; setTextColor(Color.WHITE)
                setBackgroundResource(R.drawable.btn_secondary)
                setPadding((14 * dp).toInt(), (6 * dp).toInt(), (14 * dp).toInt(), (6 * dp).toInt())
                setOnClickListener { togglePauseAll() }
            }
            addView(toggleAll)
        })
        root.addView(TextView(ctx).apply {
            text = "Downloads"; textSize = 32f; setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif", Typeface.BOLD); letterSpacing = -0.02f
            setPadding((16 * dp).toInt(), (4 * dp).toInt(), 0, 0)
        })
        storage = TextView(ctx).apply { textSize = 13f; setTextColor(0x99FFFFFF.toInt()); setPadding((16 * dp).toInt(), (2 * dp).toInt(), 0, (8 * dp).toInt()) }
        root.addView(storage)
        val frame = FrameLayout(ctx)
        list = RecyclerView(ctx).apply { layoutManager = LinearLayoutManager(ctx); adapter = Adapter(); itemAnimator = null }
        frame.addView(list)
        empty = TextView(ctx).apply {
            text = "⬇\n\nDownloads you add appear here.\nWatch them anywhere, even offline."
            gravity = Gravity.CENTER; textSize = 15f; setTextColor(0x80FFFFFF.toInt()); setLineSpacing(0f, 1.2f)
        }
        frame.addView(empty, FrameLayout.LayoutParams(-1, -1))
        root.addView(frame, LinearLayout.LayoutParams(-1, 0, 1f))
        attachSwipeToDelete()
        return root
    }

    override fun onResume() { super.onResume(); tick.post(refresher) }
    override fun onPause() { super.onPause(); tick.removeCallbacks(refresher) }

    private fun load() {
        val ctx = context ?: return
        val all = NovaDownloads.all(ctx).filter { it.state != Download.STATE_REMOVING }
        rows.clear()
        val active = all.filter { it.state != Download.STATE_COMPLETED }
        val unresolved = LocalStore.unresolved()
        val pending = LocalStore.pendingDownloads()
        if (active.isNotEmpty() || unresolved.isNotEmpty() || pending.isNotEmpty()) {
            rows += Row.Header("In Progress")
            active.forEach { rows += Row.Dl(it, DownloadMeta.of(it)) }
            pending.forEach { rows += Row.Unresolved(it.copy(skipProvider = "__pending__")) }
            unresolved.forEach { rows += Row.Unresolved(it) }
        }
        // Finished downloads, grouped by show
        all.filter { it.state == Download.STATE_COMPLETED }.mapNotNull { d -> DownloadMeta.of(d)?.let { d to it } }
            .groupBy { it.second.card.title }.toSortedMap().forEach { (title, items) ->
                rows += Row.Header(title)
                items.sortedWith(compareBy({ it.second.season }, { it.second.episode })).forEach { rows += Row.Dl(it.first, it.second) }
            }
        empty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
        val stat = StatFs(NovaDownloads.dir.absolutePath)
        storage.text = "${Formatter.formatShortFileSize(ctx, NovaDownloads.usedBytes(ctx))} used · ${Formatter.formatShortFileSize(ctx, stat.availableBytes)} free" +
            if (NovaDownloads.storageFull) "  ·  Storage full" else ""
        val anyRunning = active.any { it.state == Download.STATE_DOWNLOADING || it.state == Download.STATE_QUEUED }
        toggleAll.visibility = if (active.isEmpty()) View.GONE else View.VISIBLE
        toggleAll.text = if (anyRunning) "Pause All" else "Resume All"
        list.adapter?.notifyDataSetChanged()
    }

    private fun togglePauseAll() {
        val ctx = requireContext()
        val running = NovaDownloads.all(ctx).any { it.state == Download.STATE_DOWNLOADING || it.state == Download.STATE_QUEUED }
        if (running) DownloadService.sendPauseDownloads(ctx, NovaDownloadService::class.java, false)
        else { NovaDownloads.clearStorageFull(ctx); DownloadService.sendResumeDownloads(ctx, NovaDownloadService::class.java, false) }
        load()
    }

    private fun subtitle(d: Download, m: DownloadMeta?): String {
        val ctx = requireContext()
        val ep = m?.takeIf { it.card.type != MediaType.MOVIE }?.let { "S${it.season} · E${it.episode}  ·  " } ?: ""
        val size = Formatter.formatShortFileSize(ctx, d.bytesDownloaded)
        return ep + when (d.state) {
            Download.STATE_COMPLETED -> size
            Download.STATE_DOWNLOADING -> {
                val now = System.currentTimeMillis()
                val prev = lastBytes[d.request.id]
                lastBytes[d.request.id] = d.bytesDownloaded to now
                val speed = prev?.let { (b, t) -> if (now > t) (d.bytesDownloaded - b) * 1000 / (now - t) else 0 } ?: 0
                "${d.percentDownloaded.toInt().coerceAtLeast(0)}%  ·  " + (if (speed > 0) "${Formatter.formatShortFileSize(ctx, speed)}/s" else size)
            }
            Download.STATE_QUEUED -> if (d.stopReason != Download.STOP_REASON_NONE) "Paused" else "Waiting…"
            Download.STATE_STOPPED -> "Paused  ·  $size"
            Download.STATE_FAILED -> "Failed — tap to retry"
            Download.STATE_RESTARTING -> "Restarting…"
            else -> ""
        }
    }

    private inner class Adapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = rows.size
        override fun getItemViewType(p: Int) = if (rows[p] is Row.Header) 0 else 1

        override fun onCreateViewHolder(parent: ViewGroup, type: Int): RecyclerView.ViewHolder {
            val dp = parent.resources.displayMetrics.density
            if (type == 0) return object : RecyclerView.ViewHolder(TextView(parent.context).apply {
                textSize = 13f; setTextColor(0x99FFFFFF.toInt()); isAllCaps = true; letterSpacing = 0.06f
                setPadding((16 * dp).toInt(), (18 * dp).toInt(), (16 * dp).toInt(), (6 * dp).toInt())
            }) {}
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_download, parent, false)
            v.findViewById<View>(R.id.dl_frame).apply {
                outlineProvider = object : ViewOutlineProvider() { override fun getOutline(view: View, o: Outline) = o.setRoundRect(0, 0, view.width, view.height, 8 * dp) }
                clipToOutline = true
            }
            // Press-down feedback (Apple: respond on touch-down)
            v.setOnTouchListener { x, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> x.animate().scaleX(0.98f).scaleY(0.98f).setDuration(90).start()
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> x.animate().scaleX(1f).scaleY(1f).setDuration(140).start()
                }
                false
            }
            return object : RecyclerView.ViewHolder(v) {}
        }

        override fun onBindViewHolder(h: RecyclerView.ViewHolder, p: Int) {
            val ctx = requireContext()
            when (val row = rows[p]) {
                is Row.Header -> (h.itemView as TextView).text = row.title
                is Row.Dl -> {
                    val v = h.itemView
                    val m = row.meta
                    v.findViewById<ImageView>(R.id.dl_thumb).load(m?.card?.backdropUrl ?: m?.card?.posterUrl) { crossfade(false) }
                    v.findViewById<TextView>(R.id.dl_title).text = m?.card?.title ?: row.d.request.id
                    v.findViewById<TextView>(R.id.dl_sub).text = subtitle(row.d, m)
                    val ring = v.findViewById<ProgressRing>(R.id.dl_ring)
                    ring.visibility = if (row.d.state == Download.STATE_COMPLETED) View.GONE else View.VISIBLE
                    ring.progress = (row.d.percentDownloaded / 100f).coerceAtLeast(0f)
                    ring.glyph = when (row.d.state) {
                        Download.STATE_FAILED -> "↻"
                        Download.STATE_STOPPED -> "▶"
                        Download.STATE_QUEUED -> if (row.d.stopReason != Download.STOP_REASON_NONE) "▶" else null
                        else -> null
                    }
                    ring.setOnClickListener { ringAction(row.d) }
                    v.setOnClickListener {
                        if (row.d.state == Download.STATE_COMPLETED && m != null) PlayerActivity.launch(ctx, m.card, m.season, m.episode)
                        else ringAction(row.d)
                    }
                    v.setOnLongClickListener { menu(row.d, m); true }
                }
                is Row.Unresolved -> {
                    val v = h.itemView
                    val i = row.item
                    v.findViewById<ImageView>(R.id.dl_thumb).load(i.card.backdropUrl ?: i.card.posterUrl) { crossfade(false) }
                    v.findViewById<TextView>(R.id.dl_title).text = i.card.title
                    val pending = i.skipProvider == "__pending__"
                    v.findViewById<TextView>(R.id.dl_sub).text = (if (i.card.type != MediaType.MOVIE) "S${i.season} · E${i.episode}  ·  " else "") +
                        if (pending) "Finding a source…" else "Couldn't find a source — tap to retry"
                    val ring = v.findViewById<ProgressRing>(R.id.dl_ring)
                    ring.visibility = View.VISIBLE; ring.progress = 0f; ring.glyph = if (pending) "…" else "↻"
                    val retry = View.OnClickListener {
                        if (!pending) {
                            LocalStore.saveUnresolved(LocalStore.unresolved() - i)
                            DownloadStarter.enqueue(ctx, listOf(i))
                        }
                    }
                    ring.setOnClickListener(retry); v.setOnClickListener(retry)
                    // Hold: remove a queued / set-aside item
                    v.setOnLongClickListener {
                        ActionSheet.show(ctx, i.card.title, listOf(ActionSheet.Item("Remove from Downloads") {
                            val same = { x: DownloadStarter.Item -> x.card.id == i.card.id && x.season == i.season && x.episode == i.episode }
                            LocalStore.savePendingDownloads(LocalStore.pendingDownloads().filterNot(same))
                            LocalStore.saveUnresolved(LocalStore.unresolved().filterNot(same))
                            load()
                        }))
                        true
                    }
                }
            }
        }
    }

    private fun ringAction(d: Download) {
        val ctx = requireContext()
        when (d.state) {
            Download.STATE_FAILED -> NovaDownloads.retry(ctx, d)
            Download.STATE_STOPPED -> DownloadService.sendSetStopReason(ctx, NovaDownloadService::class.java, d.request.id, Download.STOP_REASON_NONE, false)
            Download.STATE_QUEUED -> if (d.stopReason != Download.STOP_REASON_NONE)
                DownloadService.sendSetStopReason(ctx, NovaDownloadService::class.java, d.request.id, Download.STOP_REASON_NONE, false)
            else -> DownloadService.sendSetStopReason(ctx, NovaDownloadService::class.java, d.request.id, 1, false)   // pause this one
        }
        load()
    }

    private fun menu(d: Download, m: DownloadMeta?) {
        val ctx = requireContext()
        val items = mutableListOf<ActionSheet.Item>()
        if (d.state == Download.STATE_COMPLETED && m != null) items += ActionSheet.Item("▶  Play") { PlayerActivity.launch(ctx, m.card, m.season, m.episode) }
        if (m != null && m.card.type != MediaType.MOVIE && d.state == Download.STATE_COMPLETED)
            items += ActionSheet.Item("Delete Season ${m.season}") {
                NovaDownloads.all(ctx).filter { DownloadMeta.of(it)?.let { x -> x.card.id == m.card.id && x.season == m.season } == true }
                    .forEach { remove(it) }
            }
        items += ActionSheet.Item(if (d.state == Download.STATE_COMPLETED) "Delete Download" else "Cancel Download") { remove(d) }
        ActionSheet.show(ctx, m?.card?.title, items)
    }

    private fun remove(d: Download) {
        DownloadService.sendRemoveDownload(requireContext(), NovaDownloadService::class.java, d.request.id, false)
        DownloadMeta.of(d)?.subtitle?.let { runCatching { java.io.File(it).delete() } }
        tick.postDelayed({ load() }, 300)
    }

    /** iOS-style swipe left to delete, with a red "Delete" behind the row. */
    private fun attachSwipeToDelete() {
        val red = Paint().apply { color = 0xFFFF3B30.toInt() }
        val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 15 * resources.displayMetrics.density; textAlign = Paint.Align.RIGHT }
        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT) {
            override fun getSwipeDirs(rv: RecyclerView, vh: RecyclerView.ViewHolder) =
                if (rows.getOrNull(vh.bindingAdapterPosition) is Row.Dl) ItemTouchHelper.LEFT else 0
            override fun onMove(rv: RecyclerView, a: RecyclerView.ViewHolder, b: RecyclerView.ViewHolder) = false
            override fun onSwiped(vh: RecyclerView.ViewHolder, dir: Int) {
                (rows.getOrNull(vh.bindingAdapterPosition) as? Row.Dl)?.let { remove(it.d) }
            }
            override fun onChildDraw(c: Canvas, rv: RecyclerView, vh: RecyclerView.ViewHolder, dX: Float, dY: Float, state: Int, active: Boolean) {
                val v = vh.itemView
                c.drawRect(v.right + dX, v.top.toFloat(), v.right.toFloat(), v.bottom.toFloat(), red)
                c.drawText("Delete", v.right - 20 * resources.displayMetrics.density, v.top + v.height / 2f + label.textSize / 3, label)
                super.onChildDraw(c, rv, vh, dX, dY, state, active)
            }
        }).attachToRecyclerView(list)
    }
}
