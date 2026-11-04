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
import com.anees.novamobile.ui.common.ActionSheet
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
        setupDownloads(view)
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
        viewLifecycleOwner.lifecycleScope.launch(kotlinx.coroutines.CoroutineExceptionHandler { _, e -> android.util.Log.e("NovaTV", "detail load failed", e) }) {
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

    override fun onPause() {
        super.onPause()
        dlTick.removeCallbacks(dlRunnable)
    }

    override fun onResume() {
        super.onResume()
        dlTick.postDelayed(dlRunnable, 1000)
        if (!::playBtn.isInitialized) return
        LocalStore.historyFor(card)?.let { h -> season = h.season.coerceAtLeast(1); episode = h.episode.coerceAtLeast(1) }
        updatePlayLabel()
        view?.findViewById<RecyclerView>(R.id.episode_list)?.adapter?.notifyDataSetChanged()
    }

    // ── Downloads ─────────────────────────────────────────────────────────────

    private var currentEpisodes: List<Episode> = emptyList()

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun dlState(s: Int, e: Int): String? {
        val d = com.anees.novamobile.download.NovaDownloads.get(requireContext(), com.anees.novamobile.download.DownloadMeta.id(card, s, e)) ?: return null
        return when (d.state) {
            androidx.media3.exoplayer.offline.Download.STATE_COMPLETED -> "✓"
            androidx.media3.exoplayer.offline.Download.STATE_FAILED -> null
            else -> "${d.percentDownloaded.toInt().coerceAtLeast(0)}%"
        }
    }

    /** Every download starts in the download sheet: quality, then live progress with actions. */
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun download(items: List<com.anees.novamobile.download.DownloadStarter.Item>) {
        val act = activity as? androidx.appcompat.app.AppCompatActivity ?: return
        com.anees.novamobile.ui.downloads.DownloadSheet.show(act, items) { refreshDownloads() }
    }

    /** Download button + episode rings follow the downloads live while this page is open. */
    private val dlTick = android.os.Handler(android.os.Looper.getMainLooper())
    private val dlRunnable = object : Runnable {
        override fun run() { refreshDownloads(); dlTick.postDelayed(this, 1000) }
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun refreshDownloads() {
        val v = view ?: return
        if (!isAdded) return
        setupDownloads(v)
        v.findViewById<RecyclerView>(R.id.episode_list)?.adapter?.let { it.notifyItemRangeChanged(0, it.itemCount, "dl") }
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun episodeDownload(ep: Episode) {
        val ctx = requireContext()
        val s = if (card.type == MediaType.ANIME) 1 else ep.seasonNumber
        val item = com.anees.novamobile.download.DownloadStarter.Item(card, s, ep.episodeNumber)
        val d = com.anees.novamobile.download.NovaDownloads.get(ctx, com.anees.novamobile.download.DownloadMeta.id(card, s, ep.episodeNumber))
        when {
            d == null -> download(listOf(item))
            d.state == androidx.media3.exoplayer.offline.Download.STATE_COMPLETED -> ActionSheet.show(ctx, "Episode ${ep.episodeNumber}", listOf(
                ActionSheet.Item("Play downloaded") { PlayerActivity.launch(ctx, card, s, ep.episodeNumber) },
                ActionSheet.Item("Go to Downloads") { (activity as? com.anees.novamobile.MainActivity)?.openDownloadsShow(card) },
                ActionSheet.Item("Delete download") { com.anees.novamobile.ui.downloads.DownloadUi.remove(ctx, d); dlTick.postDelayed({ refreshDownloads() }, 300) },
            ), subtitle = com.anees.novamobile.download.DownloadMeta.of(d)?.let { com.anees.novamobile.ui.downloads.DownloadUi.details(it) }?.ifBlank { null })
            else -> { com.anees.novamobile.ui.downloads.DownloadUi.toggle(ctx, d); refreshDownloads() }
        }
    }

    private fun setupDownloads(view: View) {
        val btn = view.findViewById<Button>(R.id.detail_download)
        val seasonBtn = view.findViewById<TextView>(R.id.download_season)
        if (card.type == MediaType.MOVIE) {
            btn.text = when (val st = dlState(0, 0)) { null -> "Download"; "✓" -> "Downloaded"; else -> "Downloading  $st" }
            btn.setOnClickListener {
                if (dlState(0, 0) == null) { download(listOf(com.anees.novamobile.download.DownloadStarter.Item(card, 0, 0))); btn.text = "Queued" }
                else (activity as? com.anees.novamobile.MainActivity)?.openDownloads()
            }
        } else {
            btn.text = when (val st = dlState(season.coerceAtLeast(1), episode.coerceAtLeast(1))) { null -> "Download"; "✓" -> "Downloaded"; else -> st }
            btn.setOnClickListener { download(listOf(com.anees.novamobile.download.DownloadStarter.Item(card, season, episode))) }
        }
        seasonBtn.setOnClickListener {
            if (card.type == MediaType.ANIME) {
                val total = currentEpisodes.size
                val ranges = (1..total step 12).map { it to minOf(it + 11, total) }
                ActionSheet.show(requireContext(), "Download episodes", ranges.map { (a, b) ->
                    ActionSheet.Item("Episodes $a–$b") { download((a..b).map { com.anees.novamobile.download.DownloadStarter.Item(card, 1, it) }) }
                } + ActionSheet.Item("All $total episodes") { download((1..total).map { com.anees.novamobile.download.DownloadStarter.Item(card, 1, it) }) })
            } else download(currentEpisodes.map { com.anees.novamobile.download.DownloadStarter.Item(card, it.seasonNumber, it.episodeNumber) })
        }
    }

    private fun episodeMenu(ep: Episode) {
        val s = if (card.type == MediaType.ANIME) 1 else ep.seasonNumber
        val rest = currentEpisodes.filter { it.episodeNumber >= ep.episodeNumber }
        ActionSheet.show(requireContext(), "Episode ${ep.episodeNumber}", listOf(
            ActionSheet.Item("Play") { PlayerActivity.launch(requireContext(), card, s, ep.episodeNumber) },
            ActionSheet.Item("Download Episode") { download(listOf(com.anees.novamobile.download.DownloadStarter.Item(card, s, ep.episodeNumber))) },
            ActionSheet.Item("Download From Here (${rest.size})") { download(rest.map { com.anees.novamobile.download.DownloadStarter.Item(card, s, it.episodeNumber) }) }
        ))
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
        playBtn.text = (if (resume && h!!.season.coerceAtLeast(1) == season && h.episode.coerceAtLeast(1) == episode) "Resume" else "Play") + where
        playBtn.text = com.anees.novamobile.ui.common.Ui.iconText(requireContext(), R.drawable.ic_play, playBtn.text.toString(), playBtn.currentTextColor)
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
        currentEpisodes = eps
        val est = android.text.format.Formatter.formatShortFileSize(requireContext(),
            com.anees.novamobile.download.DownloadStarter.estimateBytes(card, LocalStore.settings().dlQuality) * eps.size)
        view.findViewById<TextView>(R.id.download_season).text =
            if (card.type == MediaType.ANIME) "Download Episodes…" else "Download Season ${eps.firstOrNull()?.seasonNumber ?: season}  ·  ~$est"
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
        private val progress get() = LocalStore.episodeProgress("${card.type}:${card.id}")

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val frame: FrameLayout = v.findViewById(R.id.ep_frame)
            val still: ImageView = v.findViewById(R.id.ep_still)
            val number: TextView = v.findViewById(R.id.ep_number)
            val title: TextView = v.findViewById(R.id.ep_title)
            val desc: TextView = v.findViewById(R.id.ep_desc)
            val status: TextView = v.findViewById(R.id.ep_status)
            val badge: TextView = v.findViewById(R.id.ep_badge)
            val dim: View = v.findViewById(R.id.ep_dim)
            val bar: ProgressBar = v.findViewById(R.id.ep_progress)
            val dl: com.anees.novamobile.ui.downloads.ProgressRing = v.findViewById(R.id.ep_dl)

            init {
                val r = 10f * v.resources.displayMetrics.density
                frame.outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(view: View, o: Outline) = o.setRoundRect(0, 0, view.width, view.height, r)
                }
                frame.clipToOutline = true
                v.setOnClickListener { bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION }?.let { onClick(eps[it]) } }
                v.setOnLongClickListener { bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION }?.let { episodeMenu(eps[it]) }; true }
                dl.setOnClickListener { com.anees.novamobile.ui.common.Ui.tick(it); bindingAdapterPosition.takeIf { p -> p != RecyclerView.NO_POSITION }?.let { p -> episodeDownload(eps[p]) } }
            }
        }

        override fun onCreateViewHolder(p: ViewGroup, t: Int) = VH(LayoutInflater.from(p.context).inflate(R.layout.item_episode, p, false))
        override fun getItemCount() = eps.size

        @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
        private fun bindRing(h: VH, ep: Episode) {
            val s = if (card.type == MediaType.ANIME) 1 else ep.seasonNumber
            val d = com.anees.novamobile.download.NovaDownloads.get(requireContext(), com.anees.novamobile.download.DownloadMeta.id(card, s, ep.episodeNumber))
            h.dl.state = com.anees.novamobile.ui.downloads.DownloadUi.ring(d)
            h.dl.progress = ((d?.percentDownloaded ?: 0f) / 100f).coerceAtLeast(0f)
        }

        override fun onBindViewHolder(h: VH, i: Int, payloads: MutableList<Any>) {
            if (payloads.contains("dl")) bindRing(h, eps[i]) else onBindViewHolder(h, i)
        }

        override fun onBindViewHolder(h: VH, i: Int) {
            bindRing(h, eps[i])
            val ep = eps[i]
            h.title.text = if (card.type == MediaType.ANIME) "Episode ${ep.episodeNumber}" else "${ep.episodeNumber}. ${ep.name}"
            h.number.text = ep.episodeNumber.toString()
            val sNum = if (card.type == MediaType.ANIME) 1 else ep.seasonNumber
            val prog = progress["$sNum:${ep.episodeNumber}"] ?: 0f
            val watched = prog >= 0.9f
            // Status line: Watched / time left (download state lives in the ring on the right)
            val parts = mutableListOf<String>()
            if (watched) parts += "Watched"
            else if (prog > 0.02f) parts += ep.runtime?.let { "${(it * (1 - prog)).toInt().coerceAtLeast(1)} min left" } ?: "${(prog * 100).toInt()}% watched"
            h.status.visibility = if (parts.isEmpty()) View.GONE else View.VISIBLE
            h.status.text = parts.joinToString("   ")
            h.status.setTextColor(if (watched) 0xFF30D158.toInt() else 0xCCFFFFFF.toInt())
            h.badge.visibility = View.GONE
            h.dim.visibility = if (watched) View.VISIBLE else View.GONE
            h.title.alpha = if (watched) 0.6f else 1f
            h.desc.text = listOfNotNull(ep.runtime?.let { "${it}m" }, ep.overview.ifBlank { null }).joinToString("  ·  ")
            h.desc.visibility = if (h.desc.text.isNullOrEmpty()) View.GONE else View.VISIBLE
            if (ep.stillPath != null) {
                h.number.visibility = View.GONE
                h.still.load("https://image.tmdb.org/t/p/w300${ep.stillPath}") { crossfade(false) }
            } else {
                h.number.visibility = View.VISIBLE
                h.still.setImageDrawable(null)
            }
            h.bar.visibility = if (prog > 0.02f && !watched) View.VISIBLE else View.GONE
            h.bar.progress = (prog * 1000).toInt()
        }
    }
}
