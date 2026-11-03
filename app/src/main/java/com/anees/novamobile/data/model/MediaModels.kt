package com.anees.novamobile.data.model

import com.google.gson.annotations.SerializedName

// ─── TMDB ─────────────────────────────────────────────────────────────────────

data class TmdbPage<T>(
    val page: Int = 1,
    val results: List<T> = emptyList(),
    @SerializedName("total_pages") val totalPages: Int = 1,
    @SerializedName("total_results") val totalResults: Int = 0
)

data class TmdbMovie(
    val id: Int,
    val title: String = "",
    val overview: String = "",
    @SerializedName("poster_path") val posterPath: String? = null,
    @SerializedName("backdrop_path") val backdropPath: String? = null,
    @SerializedName("release_date") val releaseDate: String = "",
    @SerializedName("vote_average") val voteAverage: Double = 0.0,
    @SerializedName("genre_ids") val genreIds: List<Int> = emptyList(),
    @SerializedName("original_language") val originalLanguage: String? = null,
    @SerializedName("imdb_id") val imdbId: String? = null,
    val runtime: Int? = null,
    val genres: List<Genre>? = null,
    val adult: Boolean = false,
    val status: String? = null,
    @SerializedName("production_companies") val companies: List<Network>? = null
)

data class TmdbSeries(
    val id: Int,
    val name: String = "",
    val overview: String = "",
    @SerializedName("poster_path") val posterPath: String? = null,
    @SerializedName("backdrop_path") val backdropPath: String? = null,
    @SerializedName("first_air_date") val firstAirDate: String = "",
    @SerializedName("vote_average") val voteAverage: Double = 0.0,
    @SerializedName("genre_ids") val genreIds: List<Int> = emptyList(),
    @SerializedName("original_language") val originalLanguage: String? = null,
    @SerializedName("number_of_seasons") val numberOfSeasons: Int? = null,
    @SerializedName("number_of_episodes") val numberOfEpisodes: Int? = null,
    val seasons: List<Season>? = null,
    val adult: Boolean = false,
    val networks: List<Network>? = null,
    @SerializedName("external_ids") val externalIds: ExternalIds? = null
)

data class Season(
    @SerializedName("season_number") val seasonNumber: Int,
    val name: String = "",
    @SerializedName("episode_count") val episodeCount: Int = 0,
    @SerializedName("poster_path") val posterPath: String? = null
)

data class Episode(
    val id: Int,
    val name: String = "",
    @SerializedName("episode_number") val episodeNumber: Int,
    @SerializedName("season_number") val seasonNumber: Int,
    val overview: String = "",
    @SerializedName("still_path") val stillPath: String? = null,
    val runtime: Int? = null
)

data class SeasonDetail(
    val episodes: List<Episode> = emptyList()
)

data class Genre(val id: Int, val name: String)
data class Network(val id: Int = 0, val name: String = "")

data class ExternalIds(
    @SerializedName("imdb_id") val imdbId: String? = null
)

// ─── ANILIST ──────────────────────────────────────────────────────────────────

data class AniListResponse(val data: AniListData?)
data class AniListData(val Page: AniListPage?, val Media: AniMedia?)
data class AniListPage(val media: List<AniMedia>? = emptyList())

data class AniMedia(
    val id: Int,
    val title: AniTitle?,
    val description: String? = null,
    val coverImage: AniCoverImage?,
    val bannerImage: String? = null,
    val episodes: Int? = null,
    val status: String? = null,
    val averageScore: Int? = null,
    val genres: List<String>? = null,
    val isAdult: Boolean? = null,
    @SerializedName("nextAiringEpisode") val nextAiring: NextAiring? = null
)

data class AniTitle(val english: String? = null, val romaji: String? = null, val native: String? = null)
data class AniCoverImage(val large: String? = null, val extraLarge: String? = null)
data class NextAiring(val episode: Int = 0, @SerializedName("airingAt") val airingAt: Long = 0)

// ─── UNIFIED CARD ─────────────────────────────────────────────────────────────

enum class MediaType { MOVIE, SERIES, ANIME }

data class MediaCard(
    val id: Int,
    val tmdbId: Int? = null,
    val imdbId: String? = null,
    val aniListId: Int? = null,
    val title: String,
    val overview: String,
    val posterUrl: String?,
    val backdropUrl: String?,
    val year: String,
    val rating: Double,
    val type: MediaType,
    val runtime: Int? = null,
    val genres: String = "",
    val totalSeasons: Int? = null,
    val episodes: Int? = null,
    val lang: String? = null,             // original language (ISO 639-1)
    val released: String? = null          // yyyy-MM-dd, for NEW badges
)

fun TmdbMovie.toCard() = MediaCard(
    id = id, tmdbId = id, imdbId = imdbId,
    title = title, overview = overview,
    posterUrl = posterPath?.let { "https://image.tmdb.org/t/p/w342$it" },
    backdropUrl = backdropPath?.let { "https://image.tmdb.org/t/p/w780$it" },
    year = releaseDate.take(4), rating = voteAverage,
    type = MediaType.MOVIE, runtime = runtime,
    genres = genres?.joinToString(" · ") { it.name } ?: GenreNames.of(genreIds),
    lang = originalLanguage, released = releaseDate.ifEmpty { null }
)

fun TmdbSeries.toCard() = MediaCard(
    id = id, tmdbId = id, imdbId = externalIds?.imdbId,
    title = name, overview = overview,
    posterUrl = posterPath?.let { "https://image.tmdb.org/t/p/w342$it" },
    backdropUrl = backdropPath?.let { "https://image.tmdb.org/t/p/w780$it" },
    year = firstAirDate.take(4), rating = voteAverage,
    type = MediaType.SERIES, totalSeasons = numberOfSeasons,
    genres = GenreNames.of(genreIds), lang = originalLanguage, released = firstAirDate.ifEmpty { null }
)

fun AniMedia.toCard() = MediaCard(
    id = id, aniListId = id,
    title = title?.english ?: title?.romaji ?: "Unknown",
    overview = description?.replace(Regex("<.*?>"), "") ?: "",
    posterUrl = coverImage?.extraLarge ?: coverImage?.large,
    backdropUrl = bannerImage,
    year = "", rating = (averageScore ?: 0) / 10.0,
    type = MediaType.ANIME, episodes = episodes ?: nextAiring?.episode?.minus(1), lang = "ja",
    genres = genres?.take(3)?.joinToString(" · ") ?: ""
)

// ─── ANI.ZIP mapping (AniList → TMDB/MAL) ───────────────────────────────────

data class AniZip(val mappings: AniZipMap? = null, val episodes: Map<String, AniZipEp>? = null)
data class AniZipMap(
    @SerializedName("themoviedb_id") val tmdbId: String? = null,
    @SerializedName("mal_id") val malId: Int? = null,
    val type: String? = null
)
data class AniZipEp(val seasonNumber: Int? = null, val episodeNumber: Int? = null)

// ─── TMDB videos / genres ────────────────────────────────────────────────────

data class TmdbVideos(val results: List<TmdbVideo> = emptyList())
data class TmdbVideo(val key: String = "", val site: String = "", val type: String = "", val official: Boolean = false)

// ─── Local history ───────────────────────────────────────────────────────────

data class HistoryEntry(
    val card: MediaCard,
    val season: Int = 0,
    val episode: Int = 0,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val updatedAt: Long = System.currentTimeMillis()
) {
    val progress: Float get() = if (durationMs > 0) positionMs.toFloat() / durationMs else 0f
    val key: String get() = "${card.type}:${card.id}"
}

/** TMDB genre ids → names, so list items show genres without an extra request. */
object GenreNames {
    private val MAP = mapOf(
        28 to "Action", 12 to "Adventure", 16 to "Animation", 35 to "Comedy", 80 to "Crime", 99 to "Documentary",
        18 to "Drama", 10751 to "Family", 14 to "Fantasy", 36 to "History", 27 to "Horror", 10402 to "Music",
        9648 to "Mystery", 10749 to "Romance", 878 to "Sci-Fi", 53 to "Thriller", 10752 to "War", 37 to "Western",
        10759 to "Action & Adventure", 10762 to "Kids", 10763 to "News", 10764 to "Reality", 10765 to "Sci-Fi & Fantasy",
        10766 to "Soap", 10767 to "Talk", 10768 to "War & Politics"
    )
    fun of(ids: List<Int>) = ids.mapNotNull { MAP[it] }.take(3).joinToString(" · ")
}

/** Language tag shown on cards and info: what the stream really carries if we've seen it, else the original language. */
object LangTags {
    private val NAMES = mapOf("hi" to "Hindi", "en" to "English", "ur" to "Urdu", "ja" to "Japanese", "ko" to "Korean",
        "ta" to "Tamil", "te" to "Telugu", "ml" to "Malayalam", "kn" to "Kannada", "bn" to "Bengali", "pa" to "Punjabi",
        "zh" to "Chinese", "es" to "Spanish", "fr" to "French", "de" to "German", "it" to "Italian", "tr" to "Turkish", "ar" to "Arabic")

    /** "Hindi" / "Hindi Dubbed" / "English, Japanese" → "hi" / "hi" / "en" (sources often name tracks without a code). */
    fun code(label: String?): String? {
        val l = label?.lowercase() ?: return null
        return NAMES.entries.firstOrNull { l.startsWith(it.value.lowercase()) || l.contains(it.value.lowercase()) }?.key
    }

    fun name(code: String?): String? = code?.let { NAMES[it.take(2).lowercase()] ?: java.util.Locale(it).displayLanguage.takeIf { n -> n.isNotBlank() && n != it } }

    fun label(card: MediaCard, learned: List<String>?): String? {
        val seen = learned.orEmpty().mapNotNull { name(it) }.distinct()
        return if (seen.isNotEmpty()) seen.take(3).joinToString(" · ") else name(card.lang)
    }
}
