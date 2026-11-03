package com.anees.novamobile.ui.detail

import android.content.Intent
import android.graphics.Outline
import android.net.Uri
import android.os.Bundle
import android.view.*
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.*
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.anees.novamobile.R
import com.anees.novamobile.data.local.LocalStore
import com.anees.novamobile.data.model.Episode
import com.anees.novamobile.data.model.MediaCard
import com.anees.novamobile.data.model.MediaType
import com.anees.novamobile.data.repository.MediaRepository
import com.anees.novamobile.ui.player.PlayerActivity
import com.google.gson.Gson
import kotlinx.coroutines.launch

class DetailFragment : Fragment() {

    companion object {
        private const val ARG_CARD = "card_json"
        fun newInstance(card: MediaCard) = DetailFragment().apply {
            arguments = Bundle().apply { putString(ARG_CARD, Gson().toJson(card)) }
        }
    }

    private val repo = MediaRepository()
    private lateinit var card: MediaCard
    private lateinit var playBtn: Button
    private lateinit var listBtn: Button
    private var season = 1
    private var episode = 1

    override fun onCreateView(inflater: LayoutInflater, c: ViewGroup?, s: Bundle?): View =
        inflater.inflate(R.layout.fragment_detail, c, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        card = Gson().fromJson(arguments?.getString(ARG_CARD), MediaCard::class.java) ?: return
        playBtn = view.findViewById(R.id.detail_play)
        listBtn = view.findViewById(R.id.detail_list)
        val trailerBtn = view.findViewById<Button>(R.id.detail_trailer)
        val loading = view.findViewById<ProgressBar>(R.id.detail_loading)

        view.findViewById<ImageView>(R.id.detail_backdrop).apply {
            colorFilter = android.graphics.ColorMatrixColorFilter(android.graphics.ColorMatrix().apply { setScale(0.7f, 0.7f, 0.7f, 1f) })
            load(card.backdropUrl ?: card.posterUrl) { crossfade(300) }
        }
        view.findViewById<TextView>(R.id.detail_title).text = card.title
        view.findViewById<TextView>(R.id.detail_overview).text = card.overview
        renderMeta(view, card)

        // Resume from history if present
        LocalStore.historyFor(card)?.let { h -> season = h.season.coerceAtLeast(1); episode = h.episode.coerceAtLeast(1) }
        updatePlayLabel()
        updateListLabel()

        playBtn.setOnClickListener { PlayerActivity.launch(requireContext(), card, season, episode) }
        listBtn.setOnClickListener { LocalStore.toggleWatchlist(card); updateListLabel() }
        trailerBtn.setOnClickListener {
            loading.visibility = View.VISIBLE
            viewLifecycleOwner.lifecycleScope.launch {
                val key = repo.trailerKey(card)
                loading.visibility = View.GONE
                val ctx = context ?: return@launch
                if (key == null) { Toast.makeText(ctx, "No trailer found", Toast.LENGTH_SHORT).show(); return@launch }
                openTrailer(key)
            }
        }
        view.findViewById<View>(R.id.detail_back).setOnClickListener { parentFragmentManager.popBackStack() }
        // Tap the description to expand it
        view.findViewById<TextView>(R.id.detail_overview).let { o -> o.setOnClickListener { o.maxLines = if (o.maxLines == 4) 40 else 4 } }
        // Instant Play: find the stream for the episode Play would start, while the user reads
        view.postDelayed({
            context?.let { com.anees.novamobile.ui.player.Prefetcher.request(it, card, season, episode) }
        }, 600)

        // Enrich with full details (runtime, genres, seasons, episodes)
        viewLifecycleOwner.lifecycleScope.launch {
            launch { showRelated(view) }
            if (repo.isBlocked(card)) {
                view.findViewById<TextView>(R.id.detail_overview).text = "This title isn't available in Nova TV."
                listOf(playBtn, listBtn, trailerBtn).forEach { it.visibility = View.GONE }
                return@launch
            }
            when (card.type) {
                MediaType.MOVIE -> repo.getMovieDetail(card.id)?.let { d ->
                    card = card.copy(imdbId = d.imdbId, runtime = d.runtime, genres = d.genres.ifEmpty { card.genres })
                    view.let { renderMeta(it, card) }
                }
                MediaType.SERIES -> repo.getSeriesDetail(card.id)?.let { (c, raw) ->
                    card = card.copy(imdbId = c.imdbId, totalSeasons = raw.numberOfSeasons)
                    renderMeta(view, card)
                    val seasons = raw.seasons?.filter { it.seasonNumber > 0 && it.episodeCount > 0 }?.map { it.seasonNumber } ?: listOf(1)
                    setupSeasons(view, seasons)
                }
                MediaType.ANIME -> {
                    val full = repo.getAnimeDetail(card.id)
                    if (full != null) card = card.copy(episodes = full.episodes ?: card.episodes)
                    renderMeta(view, card)
                    val count = (card.episodes ?: 1).coerceIn(1, 2000)
                    view.findViewById<View>(R.id.episode_section).visibility = View.VISIBLE
                    showEpisodes(view, (1..count).map { Episode(it, "Episode $it", it, 1) })
                }
            }
        }
    }

    override fun onDestroyView() {
        com.anees.novamobile.ui.player.Prefetcher.cancel()
        super.onDestroyView()
    }

    override fun onResume() {
        super.onResume()
        if (!::playBtn.isInitialized) return
        LocalStore.historyFor(card)?.let { h -> season = h.season.coerceAtLeast(1); episode = h.episode.coerceAtLeast(1) }
        updatePlayLabel()
        view?.findViewById<RecyclerView>(R.id.episode_list)?.adapter?.notifyDataSetChanged()
    }

    private fun renderMeta(view: View, c: MediaCard) {
        view.findViewById<TextView>(R.id.detail_meta).text = listOfNotNull(
            c.year.ifEmpty { null },
            c.rating.takeIf { it > 0 }?.let { "★ ${"%.1f".format(it)}" },
            c.runtime?.takeIf { it > 0 }?.let { "${it / 60}h ${it % 60}m" },
            c.totalSeasons?.let { "$it Season${if (it > 1) "s" else ""}" },
            c.episodes?.takeIf { c.type == MediaType.ANIME }?.let { "$it Episodes" },
            c.genres.ifEmpty { null },
            com.anees.novamobile.data.model.LangTags.label(c, LocalStore.audioLangs("${c.type}:${c.id}"))?.let { "🔊 $it" }
        ).joinToString("  ·  ")
    }

    private fun updatePlayLabel() {
        val h = LocalStore.historyFor(card)
        val resume = h != null && h.positionMs > 30_000 && (card.type != MediaType.MOVIE || h.progress < 0.95f)
        val where = when (card.type) {
            MediaType.MOVIE -> ""
            MediaType.ANIME -> "  Ep $episode"
            MediaType.SERIES -> "  S$season · E$episode"
        }
        playBtn.text = (if (resume && h!!.season.coerceAtLeast(1) == season && h.episode.coerceAtLeast(1) == episode) "▶  Resume" else "▶  Play") + where
    }

    private fun updateListLabel() {
        listBtn.text = if (LocalStore.inWatchlist(card)) "✓  In My List" else "+  My List"
    }

    private fun openTrailer(key: String) {
        val uri = Uri.parse("https://www.youtube.com/watch?v=$key")
        val ctx = requireContext()
        val smartTube = Intent(Intent.ACTION_VIEW, uri).setPackage("org.smarttube.stable")
        runCatching { startActivity(smartTube) }.recoverCatching { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
            .onFailure { Toast.makeText(ctx, "Install SmartTube to watch trailers", Toast.LENGTH_SHORT).show() }
    }

    // ── Seasons / episodes ────────────────────────────────────────────────────

    private fun setupSeasons(view: View, seasons: List<Int>) {
        val ctx = context ?: return
        view.findViewById<View>(R.id.episode_section).visibility = View.VISIBLE
        val chips = view.findViewById<LinearLayout>(R.id.season_chips)
        val dp = resources.displayMetrics.density
        chips.removeAllViews()
        if (season !in seasons) { season = seasons.first(); episode = 1; updatePlayLabel() }

        seasons.forEach { s ->
            chips.addView(TextView(ctx).apply {
                text = "Season $s"
                textSize = 13f
                gravity = Gravity.CENTER
                setTextColor(ctx.getColorStateList(R.color.tab_text))
                setBackgroundResource(R.drawable.chip_bg)
                setPadding((16 * dp).toInt(), 0, (16 * dp).toInt(), 0)
                isFocusable = true
                isSelected = s == season
                setOnClickListener { loadSeason(view, s, chips) }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, (32 * dp).toInt()).apply { marginEnd = (8 * dp).toInt() })
        }
        loadSeason(view, season, chips, keepEpisode = true)
    }

    private fun loadSeason(view: View, s: Int, chips: LinearLayout, keepEpisode: Boolean = false) {
        for (i in 0 until chips.childCount) chips.getChildAt(i).isSelected = (chips.getChildAt(i) as TextView).text == "Season $s"
        viewLifecycleOwner.lifecycleScope.launch {
            val eps = repo.getSeasonEpisodes(card.id, s)
            if (!keepEpisode) { season = s; episode = 1; updatePlayLabel() }
            showEpisodes(view, eps)
        }
    }

    private fun showEpisodes(view: View, eps: List<Episode>) {
        val list = view.findViewById<RecyclerView>(R.id.episode_list)
        list.layoutManager = LinearLayoutManager(requireContext())   // phone: vertical episode list
        list.itemAnimator = null
        list.adapter = EpisodeAdapter(eps) { ep ->
            season = if (card.type == MediaType.ANIME) 1 else ep.seasonNumber
            episode = ep.episodeNumber
            updatePlayLabel()
            PlayerActivity.launch(requireContext(), card, season, episode)
        }

    }

    private suspend fun showRelated(view: View) {
        val cards = repo.related(card).take(15)
        if (cards.isEmpty() || !isAdded) return
        view.findViewById<View>(R.id.related_title).visibility = View.VISIBLE
        view.findViewById<RecyclerView>(R.id.related_list).apply {
            layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
            itemAnimator = null
            adapter = com.anees.novamobile.ui.home.MediaCardAdapter(cards,
                onLongClick = { _, c -> com.anees.novamobile.ui.common.QuickActions.show(requireContext(), c) },
                onClick = { _, c -> (activity as? com.anees.novamobile.MainActivity)?.openDetail(c) })
        }
    }

    private inner class EpisodeAdapter(val eps: List<Episode>, val onClick: (Episode) -> Unit) :
        RecyclerView.Adapter<EpisodeAdapter.VH>() {

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val frame: FrameLayout = v.findViewById(R.id.ep_frame)
            val still: ImageView = v.findViewById(R.id.ep_still)
            val number: TextView = v.findViewById(R.id.ep_number)
            val title: TextView = v.findViewById(R.id.ep_title)
            val desc: TextView = v.findViewById(R.id.ep_desc)
            val bar: ProgressBar = v.findViewById(R.id.ep_progress)

            init {
                val r = 10f * v.resources.displayMetrics.density
                frame.outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(view: View, o: Outline) = o.setRoundRect(0, 0, view.width, view.height, r)
                }
                frame.clipToOutline = true
                v.setOnClickListener { bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION }?.let { onClick(eps[it]) } }
            }
        }

        override fun onCreateViewHolder(p: ViewGroup, t: Int) = VH(LayoutInflater.from(p.context).inflate(R.layout.item_episode, p, false))
        override fun getItemCount() = eps.size
        override fun onBindViewHolder(h: VH, i: Int) {
            val ep = eps[i]
            h.title.text = if (card.type == MediaType.ANIME) "Episode ${ep.episodeNumber}" else "${ep.episodeNumber}. ${ep.name}"
            h.number.text = ep.episodeNumber.toString()
            h.desc.text = listOfNotNull(ep.runtime?.let { "${it}m" }, ep.overview.ifBlank { null }).joinToString("  ·  ")
            h.desc.visibility = if (h.desc.text.isNullOrEmpty()) View.GONE else View.VISIBLE
            if (ep.stillPath != null) {
                h.number.visibility = View.GONE
                h.still.load("https://image.tmdb.org/t/p/w300${ep.stillPath}") { crossfade(false) }
            } else {
                h.number.visibility = View.VISIBLE
                h.still.setImageDrawable(null)
            }
            val hist = LocalStore.historyFor(card)
            val current = hist != null && hist.episode == ep.episodeNumber &&
                (card.type == MediaType.ANIME || hist.season == ep.seasonNumber)
            h.bar.visibility = if (current) View.VISIBLE else View.GONE
            if (current) h.bar.progress = (hist!!.progress * 1000).toInt()
        }
    }
}
