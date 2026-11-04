package com.anees.novamobile.ui.home

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.anees.novamobile.MainActivity
import com.anees.novamobile.R
import com.anees.novamobile.data.local.LocalStore
import com.anees.novamobile.data.model.LangTags
import com.anees.novamobile.data.model.MediaCard
import com.anees.novamobile.data.model.MediaType
import com.anees.novamobile.data.repository.HomeData
import com.anees.novamobile.ui.common.QuickActions
import com.anees.novamobile.ui.common.TabPage
import com.anees.novamobile.ui.player.PlayerActivity
import com.anees.novamobile.ui.player.Prefetcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Home / Movies / Series / Anime: a hero shelf on top, then swipeable rows. */
class HomeFragment : Fragment(), RowCallbacks, TabPage {

    private val viewModel: HomeViewModel by activityViewModels()
    private var tabIndex = 0
    private lateinit var rows: RecyclerView
    private val adapter = RowsAdapter(this)
    private var heroView: View? = null
    private var featured: List<MediaCard> = emptyList()
    private var heroCard: MediaCard? = null
    private var rotateJob: Job? = null
    private var topPrefetched = false

    companion object {
        fun newInstance(tabIndex: Int) = HomeFragment().apply { arguments = Bundle().apply { putInt("tab", tabIndex) } }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tabIndex = arguments?.getInt("tab") ?: 0
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View =
        inflater.inflate(R.layout.fragment_home, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        rows = view.findViewById(R.id.rows)
        rows.layoutManager = LinearLayoutManager(requireContext())
        rows.adapter = adapter
        rows.itemAnimator = null
        rows.setItemViewCacheSize(4)
        // Brand bar turns solid once the hero has scrolled away
        rows.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                val past = rv.computeVerticalScrollOffset() > (heroView?.height ?: 600) - 160
                (activity as? MainActivity)?.setTopBarSolid(past)
            }
        })

        adapter.submitList(listOf(HomeItem.Hero) + (1..3).map { HomeItem.Skeleton("sk$it", wide = it == 1 && tabIndex == 0) })
        viewModel.homeData.observe(viewLifecycleOwner) { render() }
        viewModel.genreRows.observe(viewLifecycleOwner) { if (tabIndex == 1 || tabIndex == 2) render() }
        viewModel.trending.observe(viewLifecycleOwner) { if (tabIndex == 0) render() }
        viewModel.load()
        if (tabIndex == 1) viewModel.loadGenres(MediaType.MOVIE)
        if (tabIndex == 2) viewModel.loadGenres(MediaType.SERIES)
    }

    override fun onDestroyView() { heroView = null; super.onDestroyView() }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) { render(); startRotation(); (activity as? MainActivity)?.setTopBarSolid(rows.computeVerticalScrollOffset() > 400) }
        else rotateJob?.cancel()
    }

    override fun onResume() { super.onResume(); if (!isHidden) { render(); startRotation() } }
    override fun onPause() { super.onPause(); rotateJob?.cancel() }

    override fun scrollToTop(): Boolean { rows.smoothScrollToPosition(0); return true }

    // ── Rows ──────────────────────────────────────────────────────────────────

    private fun sections(d: HomeData): List<Pair<String, List<MediaCard>>> {
        val genres = viewModel.genreRows.value ?: emptyMap()
        return when (tabIndex) {
            1 -> listOf("Trending Movies" to d.trendingMovies, "Bollywood" to d.hindiMovies, "In Theaters" to d.nowPlaying, "Top Rated" to d.topRatedMovies) +
                    (genres[MediaType.MOVIE] ?: emptyList())
            2 -> listOf("Trending Series" to d.trendingSeries, "Popular Series" to d.popularSeries,
                        "Hindi Series" to d.hindiSeries, "Pakistani Dramas" to d.pakDramas, "Top Rated" to d.topRatedSeries) +
                    (genres[MediaType.SERIES] ?: emptyList())
            3 -> listOf("Trending Anime" to d.trendingAnime, "Most Popular" to d.popularAnime, "Top Rated" to d.topAnime)
            else -> listOf(
                "Trending Movies" to d.trendingMovies, "Popular Series" to d.popularSeries,
                "Bollywood" to d.hindiMovies, "Trending Series" to d.trendingSeries,
                "Trending Anime" to d.trendingAnime, "Hindi Series" to d.hindiSeries,
                "Top Rated Movies" to d.topRatedMovies, "Top Rated Series" to d.topRatedSeries, "Top Anime" to d.topAnime
            )
        }.filter { it.second.isNotEmpty() }
    }

    private fun render() {
        val data = viewModel.homeData.value ?: return
        if (view == null) return
        val content = sections(data)
        // Nothing from the network (offline, all sources down): Home still shows what's on the device
        if (content.isEmpty() && tabIndex != 0) return
        content.firstOrNull()?.let { featured = it.second.filter { c -> c.backdropUrl != null }.take(6) }

        val items = mutableListOf<HomeItem>()
        if (featured.isNotEmpty() || heroCard != null) items += HomeItem.Hero
        if (tabIndex == 0) {
            val history = LocalStore.history()
            if (history.isNotEmpty()) items += HomeItem.Row("cw", "Continue Watching", history.map { it.card }, wide = true,
                progress = history.map { it.progress },
                subtitles = history.map { h -> if (h.card.type == MediaType.MOVIE) "${((h.durationMs - h.positionMs) / 60_000).coerceAtLeast(1)} min left" else "S${h.season} · E${h.episode}" })
            val list = LocalStore.watchlist()
            if (list.isNotEmpty()) items += HomeItem.Row("mylist", "My List", list)
            downloadsRow()?.let { row ->
                // Offline: downloads are what can play, so they go first
                if (offline() || content.isEmpty()) items.add(if (items.firstOrNull() == HomeItem.Hero) 1 else 0, row) else items += row
            }
            viewModel.trending.value?.take(10)?.takeIf { it.size >= 5 }?.let { items += HomeItem.Row("top10", "Top 10 Today", it, ranked = true) }
        }
        content.forEach { (title, cards) -> items += HomeItem.Row(title, title, cards) }
        if (items.isEmpty()) return  // keep skeletons until something arrives
        // No banner (offline): first row would sit under the top bar
        val top = if (items.first() == HomeItem.Hero) 0 else (activity as? MainActivity)?.topBarHeight() ?: 0
        if (rows.paddingTop != top) { rows.clipToPadding = false; rows.setPadding(rows.paddingLeft, top, rows.paddingRight, rows.paddingBottom) }
        adapter.submitList(items)
        if (heroCard == null) featured.firstOrNull()?.let { setHero(it) }

        // Most likely next tap: resume the top Continue Watching item. Ready its stream after Home settles.
        if (tabIndex == 0 && !topPrefetched) LocalStore.history().firstOrNull()?.let { h ->
            topPrefetched = true
            Prefetcher.request(requireContext(), h.card, h.season, h.episode, delayMs = 5000)
        }
    }

    override fun onCardFocused(card: MediaCard) {}

    /** One card per downloaded show / movie. */
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun downloadsRow(): HomeItem.Row? {
        val done = com.anees.novamobile.download.NovaDownloads.all(requireContext())
            .filter { it.state == androidx.media3.exoplayer.offline.Download.STATE_COMPLETED }
            .mapNotNull { com.anees.novamobile.download.DownloadMeta.of(it) }
        if (done.isEmpty()) return null
        val groups = done.groupBy { "${it.card.type}:${it.card.id}" }.values.sortedByDescending { g -> g.maxOf { it.createdAt } }
        return HomeItem.Row("downloads", "Downloads", groups.map { it.first().card },
            subtitles = groups.map { g -> if (g.first().card.type == MediaType.MOVIE) "Downloaded" else "${g.size} episode${if (g.size > 1) "s" else ""}" })
    }

    private fun offline(): Boolean {
        val cm = context?.getSystemService(android.net.ConnectivityManager::class.java) ?: return false
        return cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) != true
    }

    override fun onCardClick(row: HomeItem.Row, index: Int, card: MediaCard) {
        if (row.key == "downloads") {
            if (card.type == MediaType.MOVIE) PlayerActivity.launch(requireContext(), card) else (activity as? MainActivity)?.openDownloadsShow(card)
            return
        }
        if (row.key == "cw") {
            val h = LocalStore.history().getOrNull(index) ?: return
            PlayerActivity.launch(requireContext(), h.card, h.season, h.episode)
        } else (activity as? MainActivity)?.openDetail(card)
    }

    override fun onCardLongClick(row: HomeItem.Row, index: Int, card: MediaCard) =
        QuickActions.show(requireContext(), card) { render() }

    // ── Hero ──────────────────────────────────────────────────────────────────

    override fun bindHero(view: View) {
        heroView = view
        view.findViewById<Button>(R.id.btn_play).setOnClickListener {
            heroCard?.let { c ->
                val h = LocalStore.historyFor(c)
                when {
                    h != null -> PlayerActivity.launch(requireContext(), c, h.season, h.episode)
                    c.type == MediaType.MOVIE -> PlayerActivity.launch(requireContext(), c, 0, 0)
                    else -> (activity as? MainActivity)?.openDetail(c)
                }
            }
        }
        view.findViewById<Button>(R.id.btn_info).setOnClickListener { heroCard?.let { (activity as? MainActivity)?.openDetail(it) } }
        view.findViewById<View>(R.id.hero_root).setOnClickListener { heroCard?.let { (activity as? MainActivity)?.openDetail(it) } }
        view.findViewById<Button>(R.id.btn_watchlist).setOnClickListener {
            heroCard?.let { LocalStore.toggleWatchlist(it); updateHero(it); render() }
        }
        heroCard?.let { updateHero(it) }
    }

    private fun setHero(card: MediaCard) {
        heroCard = card
        updateHero(card)
    }

    private fun updateHero(card: MediaCard) {
        val v = heroView ?: return
        v.findViewById<ImageView>(R.id.hero_image).load(card.posterUrl?.replace("/w342", "/w780") ?: card.backdropUrl) { crossfade(350) }
        v.findViewById<TextView>(R.id.hero_kind).visibility = View.GONE
        v.findViewById<TextView>(R.id.hero_title).text = card.title
        v.findViewById<TextView>(R.id.hero_meta).text = listOfNotNull(
            when (card.type) { MediaType.MOVIE -> "Movie"; MediaType.SERIES -> "Series"; MediaType.ANIME -> "Anime" },
            card.year.ifEmpty { null },
            card.rating.takeIf { it > 0 }?.let { "★ ${"%.1f".format(it)}" },
            card.genres.ifEmpty { null },
            LangTags.label(card, LocalStore.audioLangs("${card.type}:${card.id}"))
        ).joinToString("  ·  ")
        v.findViewById<Button>(R.id.btn_play).apply {
            text = com.anees.novamobile.ui.common.Ui.iconText(context, R.drawable.ic_play, if (LocalStore.historyFor(card) != null) "Resume" else "Play", currentTextColor)
        }
        v.findViewById<Button>(R.id.btn_watchlist).text = if (LocalStore.inWatchlist(card)) "✓  My List" else "+  My List"
    }

    /** Rotate the featured hero every 8s. */
    private fun startRotation() {
        rotateJob?.cancel()
        if (featured.size < 2) return
        rotateJob = viewLifecycleOwner.lifecycleScope.launch {
            var i = featured.indexOf(heroCard).coerceAtLeast(0)
            while (isActive) {
                delay(8000)
                if (rows.computeVerticalScrollOffset() > 200) continue   // hero off screen: don't waste decoding
                i = (i + 1) % featured.size
                setHero(featured[i])
            }
        }
    }
}
