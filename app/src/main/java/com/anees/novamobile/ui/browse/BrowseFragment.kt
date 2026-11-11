package com.anees.novamobile.ui.browse

import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.anees.novamobile.MainActivity
import com.anees.novamobile.R
import com.anees.novamobile.data.model.MediaCard
import com.anees.novamobile.data.model.MediaType
import com.anees.novamobile.data.repository.MediaRepository
import com.anees.novamobile.data.repository.MediaRepository.BrowseFilter
import com.anees.novamobile.ui.common.ActionSheet
import com.anees.novamobile.ui.common.QuickActions
import com.anees.novamobile.ui.common.Ui
import com.anees.novamobile.ui.common.dp
import com.anees.novamobile.ui.home.MediaCardAdapter
import com.anees.novamobile.ui.home.posterGrid
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Browse the whole catalogue by genre, year, rating, language and order; more loads as you scroll. */
class BrowseFragment : Fragment() {

    private val repo = MediaRepository()
    private var filter = BrowseFilter()
    private val cards = mutableListOf<MediaCard>()
    private var page = 0
    private var loading: Job? = null
    private var ended = false
    private lateinit var chips: LinearLayout
    private lateinit var grid: RecyclerView
    private lateinit var status: TextView

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = requireContext()
        arguments?.let { a ->
            filter = filter.copy(
                type = if (a.getBoolean(ARG_SERIES)) MediaType.SERIES else MediaType.MOVIE,
                genre = a.getInt(ARG_GENRE, 0).takeIf { it > 0 })
        }
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(0xFF000000.toInt()); isClickable = true
        }
        root.addView(LinearLayout(ctx).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(12.dp, 12.dp, 16.dp, 4.dp)
            addView(ImageButton(ctx).apply {
                setBackgroundResource(R.drawable.circle_glass); setImageResource(R.drawable.ic_back); contentDescription = "Back"
                setOnClickListener { parentFragmentManager.popBackStack() }
            }, LinearLayout.LayoutParams(40.dp, 40.dp))
            addView(TextView(ctx).apply {
                text = "Browse"; textSize = 22f; setTextColor(Ui.TEXT); typeface = Typeface.DEFAULT_BOLD; setPadding(14.dp, 0, 0, 0)
            })
        })
        chips = LinearLayout(ctx).apply { setPadding(8.dp, 0, 16.dp, 0) }
        root.addView(HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false; clipToPadding = false; addView(chips)
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 8.dp })

        status = TextView(ctx).apply {
            textSize = 14f; setTextColor(Ui.TEXT3); gravity = Gravity.CENTER; setPadding(32.dp, 56.dp, 32.dp, 0); visibility = View.GONE
        }
        root.addView(status)

        var columns = 3
        grid = RecyclerView(ctx).apply {
            columns = posterGrid()
            itemAnimator = null; clipToPadding = false
            setPadding(12.dp, 12.dp, 2.dp, 24.dp)
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                    val last = (rv.layoutManager as GridLayoutManager).findLastVisibleItemPosition()
                    if (dy > 0 && last >= cards.size - columns * 3) loadMore()
                }
            })
        }
        root.addView(grid, LinearLayout.LayoutParams(-1, 0, 1f))
        renderChips()
        reload()
        return root
    }

    // ── Filters ───────────────────────────────────────────────────────────────

    private fun renderChips() {
        val ctx = requireContext()
        chips.removeAllViews()
        fun chip(label: String, active: Boolean, onClick: () -> Unit) {
            chips.addView(TextView(ctx).apply {
                text = label; textSize = 13f; gravity = Gravity.CENTER
                setTextColor(if (active) Ui.TEXT else 0xCCFFFFFF.toInt())
                setBackgroundResource(R.drawable.chip_bg); isSelected = active
                setPadding(14.dp, 0, 14.dp, 0)
                Ui.pressable(this)
                setOnClickListener { Ui.tick(it); onClick() }
            }, LinearLayout.LayoutParams(-2, 34.dp).apply { marginStart = 8.dp })
        }
        val movies = filter.type == MediaType.MOVIE
        chip("Movies", movies) { if (!movies) apply(BrowseFilter(MediaType.MOVIE, null, filter.year, filter.minRating, filter.lang, filter.sort)) }
        chip("Series", !movies) { if (movies) apply(BrowseFilter(MediaType.SERIES, null, filter.year, filter.minRating, filter.lang, filter.sort)) }

        val genres = if (movies) MOVIE_GENRES else TV_GENRES
        chip((genres.firstOrNull { it.second == filter.genre }?.first ?: "Genre") + "  ▾", filter.genre != null) {
            pick("Genre", listOf("Any genre" to null) + genres, filter.genre) { apply(filter.copy(genre = it)) }
        }
        chip((filter.year?.toString() ?: "Year") + "  ▾", filter.year != null) {
            val now = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
            pick("Year", listOf("Any year" to null) + (now downTo 1980).map { it.toString() to it }, filter.year) { apply(filter.copy(year = it)) }
        }
        chip((filter.minRating?.let { "★ ${it.toInt()}+" } ?: "Rating") + "  ▾", filter.minRating != null) {
            pick("Rating", listOf("Any rating" to null, "★ 6 and up" to 6f, "★ 7 and up" to 7f, "★ 8 and up" to 8f), filter.minRating) {
                apply(filter.copy(minRating = it))
            }
        }
        chip((LANGS.firstOrNull { it.second == filter.lang }?.first ?: "Language") + "  ▾", filter.lang != null) {
            pick("Original language", listOf("Any language" to null) + LANGS, filter.lang) { apply(filter.copy(lang = it)) }
        }
        chip(SORTS.first { it.second == filter.sort }.first + "  ▾", filter.sort != SORTS[0].second) {
            pick("Sort by", SORTS, filter.sort) { apply(filter.copy(sort = it ?: "popularity.desc")) }
        }
        if (filter != BrowseFilter(filter.type)) chip("Reset", false) { apply(BrowseFilter(filter.type)) }
    }

    private fun <T> pick(title: String, options: List<Pair<String, T?>>, current: T?, onPick: (T?) -> Unit) {
        ActionSheet.show(requireContext(), title, options.map { (label, value) ->
            ActionSheet.Item(label, checked = value == current) { onPick(value) }
        })
    }

    private fun apply(f: BrowseFilter) {
        if (f == filter) return
        filter = f
        renderChips()
        reload()
    }

    // ── Results ───────────────────────────────────────────────────────────────

    private fun reload() {
        loading?.cancel(); loading = null
        cards.clear(); page = 0; ended = false
        grid.adapter = MediaCardAdapter(cards,
            onLongClick = { _, c -> QuickActions.show(requireContext(), c) },
            onClick = { _, c -> (activity as? MainActivity)?.openDetail(c) })
        status.text = "Loading…"; status.visibility = View.VISIBLE
        loadMore()
    }

    private fun loadMore() {
        if (loading?.isActive == true || ended) return
        val asked = filter
        loading = viewLifecycleOwner.lifecycleScope.launch {
            val next = repo.browse(asked, page + 1)
            if (asked != filter) return@launch
            page++
            // TMDB caps discover at 500 pages; an empty page is the end (or a failed request)
            if (next.isEmpty() || page >= 500) ended = true
            val known = cards.mapTo(HashSet()) { it.id }
            val fresh = next.filter { known.add(it.id) }
            val from = cards.size
            cards.addAll(fresh)
            grid.adapter?.notifyItemRangeInserted(from, fresh.size)
            status.visibility = if (cards.isEmpty()) View.VISIBLE else View.GONE
            if (cards.isEmpty()) status.text = if (filter == BrowseFilter(filter.type))
                "Couldn't load titles. Check your connection and try again."
            else "Nothing matches these filters. Try removing one."
        }
    }

    companion object {
        private const val ARG_SERIES = "series"
        private const val ARG_GENRE = "genre"
        fun of(type: MediaType = MediaType.MOVIE, genre: Int? = null) = BrowseFragment().apply {
            arguments = Bundle().apply { putBoolean(ARG_SERIES, type == MediaType.SERIES); putInt(ARG_GENRE, genre ?: 0) }
        }

        private val MOVIE_GENRES: List<Pair<String, Int?>> = listOf("Action" to 28, "Adventure" to 12, "Animation" to 16, "Comedy" to 35,
            "Crime" to 80, "Documentary" to 99, "Drama" to 18, "Family" to 10751, "Fantasy" to 14, "History" to 36, "Horror" to 27,
            "Music" to 10402, "Mystery" to 9648, "Romance" to 10749, "Sci-Fi" to 878, "Thriller" to 53, "War" to 10752, "Western" to 37)
        private val TV_GENRES: List<Pair<String, Int?>> = listOf("Action & Adventure" to 10759, "Animation" to 16, "Comedy" to 35,
            "Crime" to 80, "Documentary" to 99, "Drama" to 18, "Family" to 10751, "Kids" to 10762, "Mystery" to 9648,
            "Reality" to 10764, "Sci-Fi & Fantasy" to 10765, "War & Politics" to 10768, "Western" to 37)
        private val LANGS: List<Pair<String, String?>> = listOf("English" to "en", "Hindi" to "hi", "Urdu" to "ur", "Japanese" to "ja",
            "Korean" to "ko", "Spanish" to "es", "French" to "fr", "Turkish" to "tr", "Tamil" to "ta", "Telugu" to "te",
            "Punjabi" to "pa", "Chinese" to "zh")
        private val SORTS: List<Pair<String, String?>> = listOf("Most Popular" to "popularity.desc", "Top Rated" to "vote_average.desc",
            "Most Voted" to "vote_count.desc")
    }
}
