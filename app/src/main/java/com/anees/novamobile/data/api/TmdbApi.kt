package com.anees.novamobile.data.api

import com.anees.novamobile.data.model.*
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

interface TmdbApi {

    @GET("trending/movie/week")
    suspend fun trendingMovies(@Query("api_key") key: String): TmdbPage<TmdbMovie>

    @GET("movie/popular")
    suspend fun popularMovies(@Query("api_key") key: String, @Query("page") page: Int = 1): TmdbPage<TmdbMovie>

    @GET("movie/top_rated")
    suspend fun topRatedMovies(@Query("api_key") key: String, @Query("page") page: Int = 1): TmdbPage<TmdbMovie>

    @GET("movie/now_playing")
    suspend fun nowPlaying(@Query("api_key") key: String): TmdbPage<TmdbMovie>

    @GET("movie/{id}")
    suspend fun movieDetail(@Path("id") id: Int, @Query("api_key") key: String,
                            @Query("append_to_response") append: String = "external_ids"): TmdbMovie

    @GET("trending/tv/week")
    suspend fun trendingSeries(@Query("api_key") key: String): TmdbPage<TmdbSeries>

    @GET("tv/popular")
    suspend fun popularSeries(@Query("api_key") key: String, @Query("page") page: Int = 1): TmdbPage<TmdbSeries>

    @GET("tv/top_rated")
    suspend fun topRatedSeries(@Query("api_key") key: String, @Query("page") page: Int = 1): TmdbPage<TmdbSeries>

    @GET("tv/{id}")
    suspend fun seriesDetail(@Path("id") id: Int, @Query("api_key") key: String,
                             @Query("append_to_response") append: String = "external_ids"): TmdbSeries

    @GET("tv/{id}/season/{season}")
    suspend fun seasonDetail(@Path("id") id: Int, @Path("season") season: Int,
                             @Query("api_key") key: String): SeasonDetail

    @GET("discover/movie")
    suspend fun discoverMovies(@Query("api_key") key: String, @Query("with_genres") genre: Int? = null,
                               @Query("with_original_language") lang: String? = null,
                               @Query("sort_by") sort: String = "popularity.desc",
                               @Query("vote_count.gte") minVotes: Int? = null,
                               @Query("primary_release_year") year: Int? = null,
                               @Query("vote_average.gte") minRating: Float? = null,
                               @Query("page") page: Int = 1): TmdbPage<TmdbMovie>

    @GET("discover/tv")
    suspend fun discoverSeries(@Query("api_key") key: String, @Query("with_genres") genre: Int? = null,
                               @Query("with_original_language") lang: String? = null,
                               @Query("sort_by") sort: String = "popularity.desc",
                               @Query("vote_count.gte") minVotes: Int? = null,
                               @Query("first_air_date_year") year: Int? = null,
                               @Query("vote_average.gte") minRating: Float? = null,
                               @Query("page") page: Int = 1): TmdbPage<TmdbSeries>

    @GET("{type}/{id}/videos")
    suspend fun videos(@Path("type") type: String, @Path("id") id: Int, @Query("api_key") key: String): TmdbVideos

    @GET("movie/{id}/recommendations")
    suspend fun movieRecs(@Path("id") id: Int, @Query("api_key") key: String): TmdbPage<TmdbMovie>

    @GET("tv/{id}/recommendations")
    suspend fun seriesRecs(@Path("id") id: Int, @Query("api_key") key: String): TmdbPage<TmdbSeries>

    @GET("trending/all/day")
    suspend fun trendingAllDay(@Query("api_key") key: String): TmdbPage<TmdbSearchResult>

    @GET("search/multi")
    suspend fun search(@Query("api_key") key: String, @Query("query") query: String,
                       @Query("page") page: Int = 1): TmdbPage<TmdbSearchResult>

    @GET("{type}/{id}/credits")
    suspend fun credits(@Path("type") type: String, @Path("id") id: Int, @Query("api_key") key: String): TmdbCredits

    /** Whole-show cast (every season), ordered by episode count. */
    @GET("tv/{id}/aggregate_credits")
    suspend fun seriesCredits(@Path("id") id: Int, @Query("api_key") key: String): TmdbCredits

    @GET("person/{id}")
    suspend fun person(@Path("id") id: Int, @Query("api_key") key: String,
                       @Query("append_to_response") append: String = "combined_credits"): TmdbPerson
}

data class TmdbCredits(val cast: List<CastMember> = emptyList(), val crew: List<CastMember> = emptyList())

data class CastMember(
    val id: Int = 0,
    val name: String = "",
    val character: String? = null,
    val job: String? = null,
    val roles: List<CastRole>? = null,      // aggregate_credits (series)
    @com.google.gson.annotations.SerializedName("profile_path") val profilePath: String? = null
) {
    val role: String get() = character?.takeIf { it.isNotBlank() } ?: roles?.firstOrNull()?.character?.takeIf { it.isNotBlank() } ?: job.orEmpty()
    val photoUrl: String? get() = profilePath?.let { "https://image.tmdb.org/t/p/w185$it" }
}

data class CastRole(val character: String? = null)

data class TmdbPerson(
    val id: Int = 0,
    val name: String = "",
    val biography: String = "",
    val birthday: String? = null,
    val deathday: String? = null,
    @com.google.gson.annotations.SerializedName("place_of_birth") val placeOfBirth: String? = null,
    @com.google.gson.annotations.SerializedName("known_for_department") val department: String? = null,
    @com.google.gson.annotations.SerializedName("profile_path") val profilePath: String? = null,
    @com.google.gson.annotations.SerializedName("combined_credits") val credits: PersonCredits? = null
) {
    val photoUrl: String? get() = profilePath?.let { "https://image.tmdb.org/t/p/w342$it" }
}

data class PersonCredits(val cast: List<TmdbSearchResult> = emptyList(), val crew: List<TmdbSearchResult> = emptyList())

data class TmdbSearchResult(
    val id: Int,
    val title: String? = null,
    val name: String? = null,
    val overview: String? = null,
    @com.google.gson.annotations.SerializedName("poster_path") val posterPath: String? = null,
    @com.google.gson.annotations.SerializedName("backdrop_path") val backdropPath: String? = null,
    @com.google.gson.annotations.SerializedName("release_date") val releaseDate: String? = null,
    @com.google.gson.annotations.SerializedName("first_air_date") val firstAirDate: String? = null,
    @com.google.gson.annotations.SerializedName("vote_average") val voteAverage: Double = 0.0,
    @com.google.gson.annotations.SerializedName("media_type") val mediaType: String = "",
    val adult: Boolean = false,
    @com.google.gson.annotations.SerializedName("vote_count") val voteCount: Int = 0,
    @com.google.gson.annotations.SerializedName("original_language") val originalLanguage: String? = null
)
