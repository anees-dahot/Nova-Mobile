package com.anees.novamobile.data.repository

import com.anees.novamobile.BuildConfig
import com.anees.novamobile.data.api.AniListQueries
import com.anees.novamobile.data.api.RetrofitClient
import com.anees.novamobile.data.SafeFilter
import com.anees.novamobile.data.api.TmdbSearchResult
import com.anees.novamobile.data.model.*
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.Request

class MediaRepository {

    private val tmdb = RetrofitClient.tmdb
    private val ani = RetrofitClient.aniList
    private val KEY = BuildConfig.TMDB_KEY

    // ── Home rows ─────────────────────────────────────────────────────────────

    suspend fun getHomePage(): HomeData = coroutineScope {
        val trendingMovies = async { safe { tmdb.trendingMovies(KEY).results.movies() } }
        val trendingSeries = async { safe { tmdb.trendingSeries(KEY).results.series() } }
        val popularSeries  = async { safe { tmdb.popularSeries(KEY).results.series() } }
        val topRatedMovies = async { safe { tmdb.topRatedMovies(KEY).results.movies() } }
        val topRatedSeries = async { safe { tmdb.topRatedSeries(KEY).results.series() } }
        val nowPlaying     = async { safe { tmdb.nowPlaying(KEY).results.movies() } }
        val hindiMovies    = async { safe { tmdb.discoverMovies(KEY, lang = "hi", minVotes = 25).results.movies() } }
        val hindiSeries    = async { safe { tmdb.discoverSeries(KEY, lang = "hi", minVotes = 25).results.series() } }
        val pakDramas      = async { safe { tmdb.discoverSeries(KEY, lang = "ur", minVotes = 5).results.series() } }
        val trendingAnime  = async { safe { ani.query(AniListQueries.trending()).data?.Page?.media.anime() } }
        val popularAnime   = async { safe { ani.query(AniListQueries.popular()).data?.Page?.media.anime() } }
        val topAnime       = async { safe { ani.query(AniListQueries.topRated()).data?.Page?.media.anime() } }

        HomeData(
            trendingMovies = trendingMovies.await(),
            trendingSeries = trendingSeries.await(),
            popularSeries = popularSeries.await(),
            hindiMovies = hindiMovies.await(),
            hindiSeries = hindiSeries.await(),
            pakDramas = pakDramas.await(),
            topRatedMovies = topRatedMovies.await(),
            topRatedSeries = topRatedSeries.await(),
            nowPlaying = nowPlaying.await(),
            trendingAnime = trendingAnime.await(),
            popularAnime = popularAnime.await(),
            topAnime = topAnime.await()
        )
    }

    // ── Home cache: show last session's rows instantly, refresh in background ──

    fun cachedHome(dir: java.io.File): HomeData? = runCatching {
        Gson().fromJson(java.io.File(dir, "home.json").readText(), HomeData::class.java)
    }.getOrNull()

    fun saveHome(dir: java.io.File, data: HomeData) {
        runCatching { java.io.File(dir, "home.json").writeText(Gson().toJson(data)) }
    }

    suspend fun related(card: MediaCard): List<MediaCard> = when (card.type) {
        MediaType.MOVIE -> safe { tmdb.movieRecs(card.id, KEY).results.movies() }
        MediaType.SERIES -> safe { tmdb.seriesRecs(card.id, KEY).results.series() }
        MediaType.ANIME -> emptyList()
    }

    /** Trending titles across movies + TV for the empty Search screen. */
    suspend fun trendingToday(): List<MediaCard> = safe {
        tmdb.trendingAllDay(KEY).results
            .filter { (it.mediaType == "movie" || it.mediaType == "tv") && !it.adult && SafeFilter.titleOk(it.title ?: it.name) }
            .map { it.toCard() }
    }

    /** Genre rows for the Movies / Series tabs, loaded lazily the first time a tab is shown. */
    suspend fun genreRows(type: MediaType): List<Pair<String, List<MediaCard>>> = coroutineScope {
        val genres = if (type == MediaType.MOVIE) MOVIE_GENRES else TV_GENRES
        genres.map { (name, id) ->
            async {
                name to safe {
                    if (type == MediaType.MOVIE) tmdb.discoverMovies(KEY, genre = id).results.movies()
                    else tmdb.discoverSeries(KEY, genre = id).results.series()
                }
            }
        }.map { it.await() }.filter { it.second.isNotEmpty() }
    }

    // ── Detail ────────────────────────────────────────────────────────────────

    /** Returns null for anything that fails the family-safe filter. */
    suspend fun getMovieDetail(id: Int): MediaCard? = runCatching {
        tmdb.movieDetail(id, KEY).takeIf { d ->
            !d.adult && SafeFilter.titleOk(d.title) && SafeFilter.networksOk(d.companies.orEmpty().map { it.name })
        }?.toCard()
    }.getOrNull()

    suspend fun isBlocked(card: MediaCard): Boolean = when (card.type) {
        MediaType.MOVIE -> runCatching { tmdb.movieDetail(card.id, KEY) }.getOrNull()
            ?.let { it.adult || !SafeFilter.titleOk(it.title) || !SafeFilter.networksOk(it.companies.orEmpty().map { c -> c.name }) } ?: false
        MediaType.SERIES -> runCatching { tmdb.seriesDetail(card.id, KEY) }.getOrNull()
            ?.let { it.adult || !SafeFilter.titleOk(it.name) || !SafeFilter.networksOk(it.networks.orEmpty().map { n -> n.name }) } ?: false
        MediaType.ANIME -> !SafeFilter.titleOk(card.title)
    }

    suspend fun getSeriesDetail(id: Int): Pair<MediaCard, TmdbSeries>? = runCatching {
        val detail = tmdb.seriesDetail(id, KEY)
        detail.toCard() to detail
    }.onFailure { android.util.Log.w("NovaTV", "series $id detail failed: $it") }.getOrNull()

    suspend fun getSeasonEpisodes(seriesId: Int, season: Int): List<Episode> = runCatching {
        tmdb.seasonDetail(seriesId, season, KEY).episodes
    }.getOrDefault(emptyList())

    suspend fun getAnimeDetail(id: Int): MediaCard? = runCatching {
        ani.query(AniListQueries.detail(id)).data?.Media?.takeIf { it.isAdult != true }?.toCard()
    }.getOrNull()

    /** Runtime ("2h 10m") or season count for the focused card's info line; cached per title. */
    suspend fun extraInfo(card: MediaCard): String? {
        val key = "${card.type}:${card.id}"
        infoCache[key]?.let { return it }
        val info = when (card.type) {
            MediaType.MOVIE -> getMovieDetail(card.id)?.runtime?.takeIf { it > 0 }?.let { "${it / 60}h ${it % 60}m" }
            MediaType.SERIES -> getSeriesDetail(card.id)?.second?.numberOfSeasons?.let { "$it Season${if (it > 1) "s" else ""}" }
            MediaType.ANIME -> card.episodes?.let { "$it Episodes" }
        }
        if (info != null) infoCache[key] = info
        return info
    }

    /** YouTube key of the best trailer, or null. */
    suspend fun trailerKey(card: MediaCard): String? = runCatching {
        val (type, id) = when (card.type) {
            MediaType.MOVIE -> "movie" to card.id
            MediaType.SERIES -> "tv" to card.id
            MediaType.ANIME -> {
                val m = animeMapping(card.id)?.mappings ?: return null
                (if (m.type == "MOVIE") "movie" else "tv") to (m.tmdbId?.toIntOrNull() ?: return null)
            }
        }
        tmdb.videos(type, id, KEY).results
            .filter { it.site == "YouTube" }
            .sortedWith(compareByDescending<TmdbVideo> { it.type == "Trailer" }.thenByDescending { it.official })
            .firstOrNull()?.key
    }.getOrNull()

    // ── Anime → TMDB mapping (ani.zip) ────────────────────────────────────────

    suspend fun animeMapping(aniListId: Int): AniZip? = withContext(Dispatchers.IO) {
        mappingCache[aniListId]?.let { return@withContext it }
        runCatching {
            val req = Request.Builder().url("https://api.ani.zip/mappings?anilist_id=$aniListId").build()
            RetrofitClient.http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) null else Gson().fromJson(r.body?.string(), AniZip::class.java)
            }
        }.getOrNull()?.also { mappingCache[aniListId] = it }
    }

    // ── Search ────────────────────────────────────────────────────────────────

    suspend fun search(query: String): List<MediaCard> = coroutineScope {
        val tmdbResults = async { safe { tmdb.search(KEY, query).results } }
        val anime = async { safe { ani.query(AniListQueries.search(query)).data?.Page?.media.anime() } }

        val fromTmdb = tmdbResults.await()
            .filter { (it.mediaType == "movie" || it.mediaType == "tv") && !it.posterPath.isNullOrEmpty() &&
                SafeFilter.searchOk(it.title ?: it.name, it.originalLanguage, it.voteCount, it.adult) }
            .map { it.toCard() }
        // Keep TMDB relevance order, then anime
        fromTmdb + anime.await()
    }

    private fun TmdbSearchResult.toCard() = MediaCard(
        id = id, tmdbId = id,
        title = title ?: name ?: "",
        overview = overview ?: "",
        posterUrl = posterPath?.let { "https://image.tmdb.org/t/p/w342$it" },
        backdropUrl = backdropPath?.let { "https://image.tmdb.org/t/p/w780$it" },
        year = (releaseDate ?: firstAirDate ?: "").take(4),
        rating = voteAverage,
        type = if (mediaType == "tv") MediaType.SERIES else MediaType.MOVIE,
        lang = originalLanguage, released = (releaseDate ?: firstAirDate)?.ifEmpty { null }
    )

    // 18+ safety net on top of include_adult=false / isAdult:false at the API level
    private fun List<TmdbMovie>.movies() = filter { !it.adult && SafeFilter.titleOk(it.title) }.map { it.toCard() }
    private fun List<TmdbSeries>.series() = filter { !it.adult && SafeFilter.titleOk(it.name) }.map { it.toCard() }
    private fun List<AniMedia>?.anime() = orEmpty()
        .filterNot { it.isAdult == true || it.genres?.any { g -> g.equals("Hentai", true) } == true || !SafeFilter.titleOk(it.title?.english ?: it.title?.romaji) }
        .map { it.toCard() }

    private inline fun <T> safe(block: () -> List<T>): List<T> = runCatching(block).getOrDefault(emptyList())

    companion object {
        private val mappingCache = HashMap<Int, AniZip>()
        private val infoCache = HashMap<String, String>()
        val MOVIE_GENRES = listOf("Action" to 28, "Comedy" to 35, "Horror" to 27, "Sci-Fi" to 878, "Thriller" to 53, "Animation" to 16, "Romance" to 10749)
        val TV_GENRES = listOf("Drama" to 18, "Crime" to 80, "Comedy" to 35, "Sci-Fi & Fantasy" to 10765, "Mystery" to 9648, "Action & Adventure" to 10759)
    }
}

data class HomeData(
    val trendingMovies: List<MediaCard> = emptyList(),
    val trendingSeries: List<MediaCard> = emptyList(),
    val popularSeries: List<MediaCard> = emptyList(),
    val hindiMovies: List<MediaCard> = emptyList(),
    val hindiSeries: List<MediaCard> = emptyList(),
    val pakDramas: List<MediaCard> = emptyList(),
    val topRatedMovies: List<MediaCard> = emptyList(),
    val topRatedSeries: List<MediaCard> = emptyList(),
    val nowPlaying: List<MediaCard> = emptyList(),
    val trendingAnime: List<MediaCard> = emptyList(),
    val popularAnime: List<MediaCard> = emptyList(),
    val topAnime: List<MediaCard> = emptyList()
)
