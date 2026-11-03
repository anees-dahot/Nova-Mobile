package com.anees.novamobile.ui.watchlist

import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.format.DateUtils
import android.view.*
import android.widget.*
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.anees.novamobile.MainActivity
import com.anees.novamobile.R
import com.anees.novamobile.data.local.LocalStore
import com.anees.novamobile.data.model.HistoryEntry
import com.anees.novamobile.data.model.MediaType
import com.anees.novamobile.ui.common.ActionSheet
import com.anees.novamobile.ui.home.MediaCardAdapter
import com.anees.novamobile.ui.home.posterColumns
import com.anees.novamobile.ui.player.PlayerActivity

/** "My List": large title, Saved / History segments, poster grid and a resume list. */
class WatchlistFragment : Fragment(), com.anees.novamobile.ui.common.TabPage {

    private var tab = 0            // 0 = Saved, 1 = History
    private var filter: MediaType? = null
    private val dp get() = resources.displayMetrics.density
    private fun px(v: Int) = (v * dp).toInt()

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View =
        i.inflate(R.layout.fragment_watchlist, c, false)

    override fun onResume() { super.onResume(); if (!isHidden) render() }
    override fun onHiddenChanged(hidden: Boolean) { super.onHiddenChanged(hidden); if (!hidden) render() }

    private fun render() {
        val root = view ?: return
        val rows = root.findViewById<LinearLayout>(R.id.list_rows)
        root.findViewById<View>(R.id.list_empty).visibility = View.GONE
        rows.removeAllViews()
        val ctx = requireContext()
        val saved = LocalStore.watchlist()
        val history = LocalStore.history().sortedByDescending { it.updatedAt }

        rows.addView(TextView(ctx).apply {
            text = "My List"; textSize = 32f; setTextColor(0xFFFFFFFF.toInt())
            typeface = Typeface.DEFAULT_BOLD; letterSpacing = -0.02f
            setPadding(px(16), 0, px(16), px(12))
        })
        rows.addView(segmented(listOf("Saved", "History"), tab) { tab = it; render() },
            LinearLayout.LayoutParams(-1, px(34)).apply { setMargins(px(16), 0, px(16), px(14)) })

        if (tab == 0) savedTab(rows, saved) else historyTab(rows, history)
    }

    // ── Saved ──

    private fun savedTab(rows: LinearLayout, all: List<com.anees.novamobile.data.model.MediaCard>) {
        val ctx = requireContext()
        if (all.isEmpty()) return empty(rows, R.drawable.ic_bookmark, "Nothing saved yet",
            "Tap “My List” on any movie, series or anime to keep it here.")
        val types = all.map { it.type }.distinct()
        if (types.size > 1) rows.addView(HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false; clipToPadding = false; setPadding(px(16), 0, px(16), 0)
            addView(LinearLayout(ctx).apply {
                (listOf<MediaType?>(null) + types).forEach { t ->
                    val count = if (t == null) all.size else all.count { it.type == t }
                    addView(chip("${label(t)}  $count", filter == t) { filter = t; render() },
                        LinearLayout.LayoutParams(-2, px(32)).apply { marginEnd = px(8) })
                }
            })
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = px(6) })
        val cards = all.filter { filter == null || it.type == filter }.reversed()   // newest first
        rows.addView(RecyclerView(ctx).apply {
            layoutManager = GridLayoutManager(ctx, ctx.posterColumns())
            isNestedScrollingEnabled = false; itemAnimator = null; clipToPadding = false
            setPadding(px(10), px(4), px(10), px(8))
            adapter = MediaCardAdapter(cards, null, null,
                onLongClick = { _, c -> com.anees.novamobile.ui.common.QuickActions.show(requireContext(), c) { render() } },
                onClick = { _, c -> (activity as? MainActivity)?.openDetail(c) })
        })
    }

    private fun label(t: MediaType?) = when (t) { null -> "All"; MediaType.MOVIE -> "Movies"; MediaType.SERIES -> "Series"; MediaType.ANIME -> "Anime" }

    // ── History ──

    private fun historyTab(rows: LinearLayout, history: List<HistoryEntry>) {
        val ctx = requireContext()
        if (history.isEmpty()) return empty(rows, R.drawable.ic_tv, "No watch history",
            "Movies and episodes you play show up here, so you can pick up where you left off.")
        rows.addView(LinearLayout(ctx).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(px(16), 0, px(8), px(4))
            addView(TextView(ctx).apply { text = "${history.size} titles"; textSize = 13f; setTextColor(0x99FFFFFF.toInt()) },
                LinearLayout.LayoutParams(0, -2, 1f))
            addView(TextView(ctx).apply {
                text = "Clear"; textSize = 15f; setTextColor(0xFF0A84FF.toInt()); setPadding(px(10), px(6), px(10), px(6))
                pressFeedback(this)
                setOnClickListener {
                    ActionSheet.show(ctx, "Clear watch history?", listOf(
                        ActionSheet.Item("Clear history") { LocalStore.clearHistory(); render() }),
                        subtitle = "Your saved titles and downloads stay.")
                }
            })
        })
        history.forEach { rows.addView(historyRow(it)) }
    }

    private fun historyRow(h: HistoryEntry): View {
        val ctx = requireContext()
        val row = LinearLayout(ctx).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(px(16), px(8), px(16), px(8))
            background = android.util.TypedValue().let { tv ->
                ctx.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true); ctx.getDrawable(tv.resourceId) }
        }
        val thumb = FrameLayout(ctx).apply {
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, o: Outline) { o.setRoundRect(0, 0, v.width, v.height, 8 * dp) }
            }
            clipToOutline = true; setBackgroundColor(0xFF1C1C1E.toInt())
            addView(ImageView(ctx).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                load(h.card.backdropUrl ?: h.card.posterUrl) { crossfade(false) }
            }, FrameLayout.LayoutParams(-1, -1))
            if (h.progress > 0.01f) {
                addView(View(ctx).apply { setBackgroundColor(0x55FFFFFF) }, FrameLayout.LayoutParams(-1, px(3), Gravity.BOTTOM))
                addView(View(ctx).apply { setBackgroundColor(0xFF0A84FF.toInt()); pivotX = 0f; scaleX = h.progress.coerceIn(0f, 1f) },
                    FrameLayout.LayoutParams(-1, px(3), Gravity.BOTTOM))
            }
        }
        row.addView(thumb, LinearLayout.LayoutParams(px(128), px(72)))
        val left = ((h.durationMs - h.positionMs) / 60000).coerceAtLeast(0)
        val status = when {
            h.progress >= 0.9f -> "Watched"
            h.durationMs > 0 -> "$left min left"
            else -> null
        }
        row.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; setPadding(px(12), 0, px(4), 0)
            addView(TextView(ctx).apply {
                text = h.card.title; textSize = 15f; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(0xFFFFFFFF.toInt()); typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            })
            addView(TextView(ctx).apply {
                text = listOfNotNull(if (h.card.type == MediaType.MOVIE) "Movie" else "S${h.season} · E${h.episode}", status).joinToString("  ·  ")
                textSize = 13f; setTextColor(0xB3FFFFFF.toInt()); setPadding(0, px(2), 0, 0)
            })
            addView(TextView(ctx).apply {
                text = DateUtils.getRelativeTimeSpanString(h.updatedAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
                textSize = 12f; setTextColor(0x66FFFFFF); setPadding(0, px(2), 0, 0)
            })
        }, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(ImageView(ctx).apply {
            setImageResource(android.R.drawable.ic_media_play); alpha = 0.8f
            setPadding(px(8), px(8), px(8), px(8))
        }, LinearLayout.LayoutParams(px(40), px(40)))
        pressFeedback(row)
        row.setOnClickListener { PlayerActivity.launch(ctx, h.card, h.season, h.episode) }
        row.setOnLongClickListener {
            ActionSheet.show(ctx, h.card.title, listOf(
                ActionSheet.Item(if (h.progress > 0.01f && h.progress < 0.9f) "Resume" else "Play") { PlayerActivity.launch(ctx, h.card, h.season, h.episode) },
                ActionSheet.Item("Go to details") { (activity as? MainActivity)?.openDetail(h.card) },
                ActionSheet.Item("Remove from history") { LocalStore.removeHistory(h.key); render() }
            )); true
        }
        return row
    }

    // ── Pieces ──

    /** iOS segmented control: grey track, raised selected segment. */
    private fun segmented(labels: List<String>, selected: Int, onPick: (Int) -> Unit): View {
        val ctx = requireContext()
        return LinearLayout(ctx).apply {
            background = round(0xFF1C1C1E.toInt(), 9f); setPadding(px(2), px(2), px(2), px(2))
            labels.forEachIndexed { i, l ->
                addView(TextView(ctx).apply {
                    text = l; gravity = Gravity.CENTER; textSize = 14f
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    setTextColor(if (i == selected) 0xFFFFFFFF.toInt() else 0x99FFFFFF.toInt())
                    background = if (i == selected) round(0xFF3A3A3C.toInt(), 7f) else null
                    elevation = if (i == selected) 2 * dp else 0f
                    setOnClickListener { if (i != selected) onPick(i) }
                }, LinearLayout.LayoutParams(0, -1, 1f))
            }
        }
    }

    private fun chip(text: String, on: Boolean, click: () -> Unit) = TextView(requireContext()).apply {
        this.text = text; gravity = Gravity.CENTER; textSize = 14f; setPadding(px(14), 0, px(14), 0)
        setTextColor(if (on) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
        background = round(if (on) 0xFFFFFFFF.toInt() else 0xFF2C2C2E.toInt(), 16f)
        pressFeedback(this); setOnClickListener { click() }
    }

    private fun empty(rows: LinearLayout, icon: Int, title: String, body: String) {
        val ctx = requireContext()
        rows.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            setPadding(px(40), px(80), px(40), 0)
            addView(ImageView(ctx).apply { setImageResource(icon); alpha = 0.35f }, LinearLayout.LayoutParams(px(52), px(52)))
            addView(TextView(ctx).apply {
                text = title; textSize = 20f; setTextColor(0xFFFFFFFF.toInt()); typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER; setPadding(0, px(14), 0, px(6))
            })
            addView(TextView(ctx).apply { text = body; textSize = 15f; setTextColor(0x99FFFFFF.toInt()); gravity = Gravity.CENTER })
            addView(TextView(ctx).apply {
                text = "Browse"; textSize = 15f; setTextColor(0xFF000000.toInt()); typeface = Typeface.DEFAULT_BOLD
                background = round(0xFFFFFFFF.toInt(), 20f); setPadding(px(24), px(10), px(24), px(10))
                pressFeedback(this); setOnClickListener { (activity as? MainActivity)?.switchTab(0) }
            }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = px(20) })
        })
    }

    private fun round(color: Int, r: Float) = GradientDrawable().apply { setColor(color); cornerRadius = r * dp }

    /** Shrinks on touch-down, springs back on release (Apple press feedback). */
    private fun pressFeedback(v: View) = v.setOnTouchListener { x, e ->
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> x.animate().scaleX(0.97f).scaleY(0.97f).setDuration(100).start()
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> x.animate().scaleX(1f).scaleY(1f).setDuration(180).start()
        }
        false
    }
}
