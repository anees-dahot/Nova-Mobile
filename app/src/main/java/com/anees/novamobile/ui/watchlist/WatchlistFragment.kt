package com.anees.novamobile.ui.watchlist

import android.graphics.Typeface
import android.os.Bundle
import android.view.*
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.anees.novamobile.MainActivity
import com.anees.novamobile.R
import com.anees.novamobile.data.local.LocalStore
import com.anees.novamobile.data.model.MediaCard
import com.anees.novamobile.data.model.MediaType
import com.anees.novamobile.ui.home.MediaCardAdapter
import com.anees.novamobile.ui.home.posterColumns
import com.anees.novamobile.ui.player.PlayerActivity

/** "My List": watchlist grid + full watch history. */
class WatchlistFragment : Fragment(), com.anees.novamobile.ui.common.TabPage {

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View =
        i.inflate(R.layout.fragment_watchlist, c, false)

    override fun onResume() { super.onResume(); if (!isHidden) render() }
    override fun onHiddenChanged(hidden: Boolean) { super.onHiddenChanged(hidden); if (!hidden) render() }

    private fun render() {
        val root = view ?: return
        val rows = root.findViewById<LinearLayout>(R.id.list_rows)
        rows.removeAllViews()
        val list = LocalStore.watchlist()
        val history = LocalStore.history()
        root.findViewById<View>(R.id.list_empty).visibility = if (list.isEmpty() && history.isEmpty()) View.VISIBLE else View.GONE

        if (list.isNotEmpty()) addSection(rows, "My List", list) { _, c -> (activity as? MainActivity)?.openDetail(c) }
        if (history.isNotEmpty()) addSection(rows, "Recently Watched", history.map { it.card },
            progress = history.map { it.progress },
            subtitles = history.map { h -> if (h.card.type == MediaType.MOVIE) null else "S${h.season} · E${h.episode}" }
        ) { i, _ -> PlayerActivity.launch(requireContext(), history[i].card, history[i].season, history[i].episode) }
    }

    private fun addSection(
        rows: LinearLayout, title: String, cards: List<MediaCard>,
        progress: List<Float>? = null, subtitles: List<String?>? = null,
        onClick: (Int, MediaCard) -> Unit
    ) {
        val ctx = requireContext()
        val dp = resources.displayMetrics.density
        rows.addView(TextView(ctx).apply {
            text = title
            textSize = 17f
            setTextColor(0xE6FFFFFF.toInt())
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding((48 * dp).toInt(), (16 * dp).toInt(), 0, (6 * dp).toInt())
        })
        rows.addView(RecyclerView(ctx).apply {
            layoutManager = GridLayoutManager(ctx, ctx.posterColumns())
            isNestedScrollingEnabled = false
            itemAnimator = null
            clipToPadding = false
            clipChildren = false
            setPadding((48 * dp).toInt(), (8 * dp).toInt(), (48 * dp).toInt(), (8 * dp).toInt())
            adapter = MediaCardAdapter(cards, progress, subtitles,
                onLongClick = { _, c -> com.anees.novamobile.ui.common.QuickActions.show(requireContext(), c) { render() } },
                onClick = onClick)
        })
    }
}
