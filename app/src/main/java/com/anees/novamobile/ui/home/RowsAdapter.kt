package com.anees.novamobile.ui.home

import android.graphics.Rect
import android.os.Parcelable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.anees.novamobile.R
import com.anees.novamobile.data.model.MediaCard

sealed class HomeItem {
    abstract val key: String
    object Hero : HomeItem() { override val key = "hero" }
    data class Skeleton(override val key: String, val wide: Boolean = false) : HomeItem()
    data class Row(
        override val key: String,
        val title: String,
        val cards: List<MediaCard>,
        val wide: Boolean = false,
        val progress: List<Float>? = null,
        val subtitles: List<String?>? = null,
        val ranked: Boolean = false
    ) : HomeItem()
}

interface RowCallbacks {
    fun bindHero(view: View)
    fun onCardFocused(card: MediaCard)
    fun onCardClick(row: HomeItem.Row, index: Int, card: MediaCard)
    fun onCardLongClick(row: HomeItem.Row, index: Int, card: MediaCard)
}

/**
 * Vertical list of rows. Only rows on screen exist; posters inside rows share one view pool,
 * so scrolling a 15-row page costs about the same as a 3-row page.
 */
class RowsAdapter(private val cb: RowCallbacks) : ListAdapter<HomeItem, RecyclerView.ViewHolder>(DIFF) {

    private val pool = RecyclerView.RecycledViewPool().apply { setMaxRecycledViews(0, 30); setMaxRecycledViews(1, 12) }
    private val scrollState = HashMap<String, Parcelable?>()

    override fun getItemViewType(position: Int) = when (getItem(position)) {
        is HomeItem.Hero -> 0
        is HomeItem.Row -> 1
        is HomeItem.Skeleton -> 2
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(parent.context)
        return when (viewType) {
            0 -> object : RecyclerView.ViewHolder(inf.inflate(R.layout.item_hero, parent, false).also {
                // Big, full-bleed hero like the Apple TV app on iPhone: ~3/4 of the screen
                val dm = parent.resources.displayMetrics
                it.layoutParams = RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, minOf((dm.widthPixels * 1.5f).toInt(), (dm.heightPixels * 0.76f).toInt()))
            }) {}
            1 -> RowVH(inf.inflate(R.layout.item_row, parent, false))
            else -> SkeletonVH(inf.inflate(R.layout.item_row, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is HomeItem.Hero -> cb.bindHero(holder.itemView)
            is HomeItem.Row -> (holder as RowVH).bind(item)
            is HomeItem.Skeleton -> (holder as SkeletonVH).bind(item)
        }
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        if (holder is RowVH) holder.saveState()
    }

    inner class RowVH(v: View) : RecyclerView.ViewHolder(v) {
        private val title: TextView = v.findViewById(R.id.row_title)
        private val list: RecyclerView = v.findViewById(R.id.row_list)
        private var key: String? = null

        init {
            list.layoutManager = LinearLayoutManager(v.context, LinearLayoutManager.HORIZONTAL, false).apply { initialPrefetchItemCount = 6 }
            list.setRecycledViewPool(pool)
            list.itemAnimator = null
        }

        fun bind(row: HomeItem.Row) {
            key = row.key
            title.text = row.title
            list.adapter = MediaCardAdapter(row.cards, row.progress, row.subtitles, row.wide, row.ranked,
                onFocus = cb::onCardFocused,
                onLongClick = { i, c -> cb.onCardLongClick(row, i, c) },
                onClick = { i, c -> cb.onCardClick(row, i, c) })
            scrollState[row.key]?.let { list.layoutManager?.onRestoreInstanceState(it) }
        }

        fun saveState() { key?.let { scrollState[it] = list.layoutManager?.onSaveInstanceState() } }
    }

    /** Grey card shapes while the first load runs (no cache yet). */
    class SkeletonVH(v: View) : RecyclerView.ViewHolder(v) {
        private val list: RecyclerView = v.findViewById(R.id.row_list)
        fun bind(item: HomeItem.Skeleton) {
            val dp = itemView.resources.displayMetrics.density
            itemView.findViewById<TextView>(R.id.row_title).text = ""
            list.layoutManager = LinearLayoutManager(itemView.context, LinearLayoutManager.HORIZONTAL, false)
            list.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
                override fun getItemCount() = 8
                override fun onBindViewHolder(h: RecyclerView.ViewHolder, p: Int) {}
                override fun onCreateViewHolder(p: ViewGroup, t: Int) = object : RecyclerView.ViewHolder(View(p.context).apply {
                    setBackgroundResource(R.drawable.skeleton)
                    layoutParams = ViewGroup.MarginLayoutParams(((if (item.wide) 220 else 112) * dp).toInt(), ((if (item.wide) 124 else 168) * dp).toInt())
                        .apply { marginEnd = (10 * dp).toInt() }
                }) {}
            }
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<HomeItem>() {
            override fun areItemsTheSame(a: HomeItem, b: HomeItem) = a.key == b.key
            override fun areContentsTheSame(a: HomeItem, b: HomeItem) = a == b
        }
    }
}

