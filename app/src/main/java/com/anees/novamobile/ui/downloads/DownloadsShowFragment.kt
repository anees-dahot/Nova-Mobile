package com.anees.novamobile.ui.downloads

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.*
import android.widget.*
import androidx.fragment.app.Fragment
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.anees.novamobile.MainActivity
import com.anees.novamobile.R
import com.anees.novamobile.data.model.MediaCard
import com.anees.novamobile.data.model.MediaType
import com.anees.novamobile.download.DownloadMeta
import com.anees.novamobile.download.NovaDownloads
import com.anees.novamobile.ui.common.ActionSheet
import com.anees.novamobile.ui.common.Ui
import com.anees.novamobile.ui.common.dp
import com.anees.novamobile.ui.player.PlayerActivity
import com.google.gson.Gson
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/** One show's downloads ("folder"): backdrop header, season chips, episodes that play offline. */
@UnstableApi
class DownloadsShowFragment : Fragment() {

    companion object {
        fun of(card: MediaCard) = DownloadsShowFragment().apply { arguments = Bundle().apply { putString("card", Gson().toJson(card)) } }
    }

    private class Ep(val d: Download, val m: DownloadMeta, val watched: Float) { val key get() = d.request.id }

    private lateinit var card: MediaCard
    private lateinit var list: RecyclerView
    private lateinit var summary: TextView
    private lateinit var chips: LinearLayout
    private var season = -1
    private val adapter = Adapter()
    /** "season:episode" → TMDB episode (still image + name), filled in after the screen opens. */
    private val info = HashMap<String, com.anees.novamobile.data.model.Episode>()
    private val pendingRemoval = mutableSetOf<String>()
    private val main = Handler(Looper.getMainLooper())
    private val tick = object : Runnable { override fun run() { load(); main.postDelayed(this, 1000) } }
    private val listener = object : DownloadManager.Listener {
        override fun onDownloadChanged(m: DownloadManager, d: Download, e: Exception?) { main.post { load() } }
        override fun onDownloadRemoved(m: DownloadManager, d: Download) { main.post { load() } }
    }

    override fun onCreateView(inflater: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        val ctx = requireContext()
        card = Gson().fromJson(requireArguments().getString("card"), MediaCard::class.java)
        val root = FrameLayout(ctx).apply { setBackgroundColor(Color.BLACK); isClickable = true }
        val column = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

        // Header: dimmed backdrop, title, summary
        val header = FrameLayout(ctx)
        header.addView(ImageView(ctx).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setScale(0.55f, 0.55f, 0.55f, 1f) })
            load(card.backdropUrl ?: card.posterUrl)
        }, FrameLayout.LayoutParams(-1, 200.dp))
        header.addView(View(ctx).apply { setBackgroundResource(R.drawable.gradient_bottom) }, FrameLayout.LayoutParams(-1, 120.dp, Gravity.BOTTOM))
        header.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; setPadding(16.dp, 0, 16.dp, 12.dp)
            addView(TextView(ctx).apply {
                text = card.title; textSize = 28f; setTextColor(Ui.TEXT); maxLines = 2
                typeface = Typeface.create("sans-serif", Typeface.BOLD); letterSpacing = -0.02f
            })
            summary = TextView(ctx).apply { textSize = 13f; setTextColor(Ui.TEXT2); setPadding(0, 2.dp, 0, 0) }
            addView(summary)
        }, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        column.addView(header, LinearLayout.LayoutParams(-1, 200.dp))

        chips = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; setPadding(12.dp, 10.dp, 12.dp, 4.dp) }
        column.addView(HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; addView(chips) })

        list = RecyclerView(ctx).apply {
            layoutManager = LinearLayoutManager(ctx); adapter = this@DownloadsShowFragment.adapter
            (itemAnimator as? androidx.recyclerview.widget.SimpleItemAnimator)?.supportsChangeAnimations = false
            clipToPadding = false; setPadding(0, 4.dp, 0, 24.dp)
        }
        column.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))

        // Footer: more episodes · delete all
        column.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Ui.SURFACE); setPadding(16.dp, 12.dp, 16.dp, 12.dp)
            addView(TextView(ctx).apply {
                text = "Download more episodes"; textSize = 15f; setTextColor(Ui.ACCENT)
                setOnClickListener { (activity as? MainActivity)?.openDetail(card) }
            })
            addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f))
            addView(TextView(ctx).apply {
                text = "Delete all"; textSize = 15f; setTextColor(Ui.DANGER)
                setOnClickListener { deleteAll() }
            })
        })
        root.addView(column)
        root.addView(ImageButton(ctx).apply {
            setImageResource(R.drawable.ic_back); contentDescription = "Back"
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x66000000) }
            setOnClickListener { parentFragmentManager.popBackStack() }
        }, FrameLayout.LayoutParams(40.dp, 40.dp).apply { setMargins(12.dp, 12.dp, 0, 0) })
        attachSwipeToDelete()
        if (card.type == MediaType.SERIES) {
            viewLifecycleOwner.lifecycleScope.launch {
                val repo = com.anees.novamobile.data.repository.MediaRepository()
                episodes().map { it.m.season }.distinct().forEach { s ->
                    runCatching { repo.getSeasonEpisodes(card.id, s) }.getOrDefault(emptyList()).forEach { info["$s:${it.episodeNumber}"] = it }
                }
                adapter.notifyItemRangeChanged(0, adapter.itemCount)
            }
        }
        return root
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

    private fun episodes(): List<Ep> = NovaDownloads.all(requireContext())
        .filter { it.state != Download.STATE_REMOVING && it.request.id !in pendingRemoval }
        .mapNotNull { d -> DownloadMeta.of(d)?.takeIf { it.card.id == card.id && it.card.type == card.type }?.let { Ep(d, it, DownloadUi.watched(it)) } }
        .sortedWith(compareBy({ it.m.season }, { it.m.episode }))

    private fun load() {
        val ctx = context ?: return
        val eps = episodes()
        if (eps.isEmpty()) { parentFragmentManager.popBackStack(); return }
        val seasons = eps.map { it.m.season }.distinct()
        if (season !in seasons) season = seasons.first()
        // Season chips only when there's more than one
        chips.visibility = if (seasons.size > 1) View.VISIBLE else View.GONE
        if (chips.childCount != seasons.size || chips.tag != season) {
            chips.removeAllViews(); chips.tag = season
            seasons.forEach { s ->
                chips.addView(TextView(ctx).apply {
                    text = "Season $s"; textSize = 14f
                    setTextColor(if (s == season) Color.BLACK else Ui.TEXT)
                    background = GradientDrawable().apply { cornerRadius = 16f.dp; setColor(if (s == season) Color.WHITE else Ui.SURFACE2) }
                    setPadding(14.dp, 6.dp, 14.dp, 6.dp)
                    setOnClickListener { season = s; Ui.tick(it); load() }
                }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = 8.dp })
            }
        }
        val done = eps.filter { it.d.state == Download.STATE_COMPLETED }
        val active = eps.size - done.size
        summary.text = listOfNotNull(
            "${done.size} episode${if (done.size == 1) "" else "s"}",
            DownloadUi.size(ctx, done.sumOf { it.d.bytesDownloaded }),
            active.takeIf { it > 0 }?.let { "$it downloading" },
        ).joinToString("  ·  ")
        adapter.submitList(eps.filter { it.m.season == season })
    }

    private fun play(e: Ep) {
        if (e.d.state == Download.STATE_COMPLETED) PlayerActivity.launch(requireContext(), e.m.card, e.m.season, e.m.episode)
        else DownloadUi.toggle(requireContext(), e.d)
    }

    private fun menu(e: Ep) {
        val ctx = requireContext()
        val items = mutableListOf<ActionSheet.Item>()
        if (e.d.state == Download.STATE_COMPLETED) items += ActionSheet.Item(if (e.watched in 0.02f..0.9f) "Resume" else "Play") { play(e) }
        else items += ActionSheet.Item(if (DownloadUi.paused(e.d)) "Resume download" else "Pause download") { DownloadUi.toggle(ctx, e.d) }
        items += ActionSheet.Item("Go to show") { (activity as? MainActivity)?.openDetail(card) }
        items += ActionSheet.Item(if (e.d.state == Download.STATE_COMPLETED) "Delete download" else "Cancel download") { DownloadUi.delete(ctx, listOf(e.d)) { main.postDelayed({ load() }, 300) } }
        ActionSheet.show(ctx, DownloadUi.name(e.m), items, subtitle = DownloadUi.details(e.m).ifBlank { null })
    }

    private fun deleteAll() {
        val ctx = requireContext()
        val eps = episodes()
        ActionSheet.show(ctx, "Delete all ${eps.size} episodes of ${card.title}?", listOf(
            ActionSheet.Item("Delete and free ${DownloadUi.size(ctx, eps.sumOf { it.d.bytesDownloaded })}") {
                DownloadUi.delete(ctx, eps.map { it.d }) { if (isAdded) parentFragmentManager.popBackStack() }
            },
            ActionSheet.Item("Keep"),
        ))
    }

    private fun attachSwipeToDelete() {
        val red = Paint().apply { color = Ui.DANGER }
        val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 15f.dp; textAlign = Paint.Align.RIGHT }
        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT) {
            override fun onMove(rv: RecyclerView, a: RecyclerView.ViewHolder, b: RecyclerView.ViewHolder) = false
            override fun onSwiped(vh: RecyclerView.ViewHolder, dir: Int) {
                val e = adapter.currentList.getOrNull(vh.bindingAdapterPosition) ?: return
                DownloadUi.confirmDelete(requireContext(), listOf(e.d), onCancel = { adapter.notifyItemChanged(vh.bindingAdapterPosition) }) { phone -> swipeRemove(e, phone) }
            }
            private fun swipeRemove(e: Ep, phone: Boolean) {
                pendingRemoval += e.key
                load()
                val app = requireContext().applicationContext
                val commit = Runnable { if (pendingRemoval.remove(e.key)) DownloadUi.remove(app, e.d, phone) }
                main.postDelayed(commit, 4000)
                (activity as? MainActivity)?.showBanner("Deleted ${DownloadUi.episode(e.m)}", "Undo") { main.removeCallbacks(commit); pendingRemoval -= e.key; load() }
            }
            override fun onChildDraw(c: Canvas, rv: RecyclerView, vh: RecyclerView.ViewHolder, dX: Float, dY: Float, state: Int, active: Boolean) {
                val v = vh.itemView
                c.drawRect(v.right + dX, v.top.toFloat(), v.right.toFloat(), v.bottom.toFloat(), red)
                c.drawText("Delete", v.right - 20f.dp, v.top + v.height / 2f + label.textSize / 3, label)
                super.onChildDraw(c, rv, vh, dX, dY, state, active)
            }
        }).attachToRecyclerView(list)
    }

    private object Diff : DiffUtil.ItemCallback<Ep>() {
        override fun areItemsTheSame(a: Ep, b: Ep) = a.key == b.key
        override fun areContentsTheSame(a: Ep, b: Ep) = a.d.state == b.d.state && a.d.bytesDownloaded == b.d.bytesDownloaded &&
            a.d.stopReason == b.d.stopReason && a.watched == b.watched
    }

    private inner class Adapter : ListAdapter<Ep, RecyclerView.ViewHolder>(Diff) {
        override fun onCreateViewHolder(parent: ViewGroup, type: Int): RecyclerView.ViewHolder {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_download, parent, false)
            v.findViewById<View>(R.id.dl_frame).apply {
                outlineProvider = object : ViewOutlineProvider() { override fun getOutline(view: View, o: Outline) = o.setRoundRect(0, 0, view.width, view.height, 8f.dp) }
                clipToOutline = true
            }
            Ui.pressable(v, 0.98f)
            return object : RecyclerView.ViewHolder(v) {}
        }

        override fun onBindViewHolder(h: RecyclerView.ViewHolder, p: Int) {
            val ctx = requireContext()
            val e = getItem(p)
            val v = h.itemView
            val done = e.d.state == Download.STATE_COMPLETED
            val ep = info["${e.m.season}:${e.m.episode}"]
            v.findViewById<ImageView>(R.id.dl_thumb).load(ep?.stillPath?.let { "https://image.tmdb.org/t/p/w300$it" } ?: e.m.card.backdropUrl ?: e.m.card.posterUrl) { crossfade(false) }
            v.findViewById<TextView>(R.id.dl_title).text = ep?.name?.takeIf { it.isNotBlank() }?.let { "${e.m.episode}. $it" } ?: "Episode ${e.m.episode}"
            v.findViewById<TextView>(R.id.dl_sub).apply {
                text = if (done) listOfNotNull(DownloadUi.size(ctx, e.d.bytesDownloaded), e.m.quality?.let { "${it}p" },
                    when { e.watched >= 0.9f -> "Watched"; e.watched > 0.02f -> "${(e.watched * 100).toInt()}% watched"; else -> null }).joinToString("  ·  ")
                    else DownloadUi.status(ctx, e.d)
                setTextColor(if (e.d.state == Download.STATE_FAILED) Ui.DANGER else Ui.TEXT2)
            }
            v.findViewById<View>(R.id.dl_check).visibility = View.GONE
            v.findViewById<View>(R.id.dl_badge).visibility = View.GONE
            v.findViewById<ProgressBar>(R.id.dl_watched).apply {
                visibility = if (done && e.watched > 0.02f) View.VISIBLE else View.GONE
                progress = (e.watched * 100).toInt()
            }
            v.findViewById<View>(R.id.dl_done).visibility = if (done && e.watched >= 0.9f) View.VISIBLE else View.GONE
            val ring = v.findViewById<ProgressRing>(R.id.dl_ring)
            if (done) {
                // Finished: a play button instead of the ring
                ring.state = ProgressRing.State.PAUSED; ring.progress = 0f
                ring.setOnClickListener { play(e) }
            } else {
                ring.state = DownloadUi.ring(e.d)
                ring.progress = (e.d.percentDownloaded / 100f).coerceAtLeast(0f)
                ring.setOnClickListener { Ui.tick(it); DownloadUi.toggle(ctx, e.d); load() }
            }
            v.setOnClickListener { play(e) }
            v.setOnLongClickListener { menu(e); true }
        }
    }
}
