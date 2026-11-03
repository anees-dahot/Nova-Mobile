package com.anees.novamobile.data.api

import com.anees.novamobile.data.model.AniListResponse
import retrofit2.http.Body
import retrofit2.http.Headers
import retrofit2.http.POST

interface AniListApi {
    @Headers("Content-Type: application/json")
    @POST(".")
    suspend fun query(@Body body: Map<String, String>): AniListResponse
}

object AniListQueries {
    fun trending(page: Int = 1) = mapOf("query" to """
        { Page(page: $page, perPage: 20) { media(type: ANIME, isAdult: false, genre_not_in: ["Hentai"], sort: TRENDING) {
            id title { english romaji } description coverImage { large extraLarge }
            bannerImage episodes status averageScore genres isAdult
        }}}""".trimIndent())

    fun popular(page: Int = 1) = mapOf("query" to """
        { Page(page: $page, perPage: 20) { media(type: ANIME, isAdult: false, genre_not_in: ["Hentai"], sort: POPULARITY_DESC) {
            id title { english romaji } description coverImage { large extraLarge }
            bannerImage episodes status averageScore genres isAdult
        }}}""".trimIndent())

    fun topRated(page: Int = 1) = mapOf("query" to """
        { Page(page: $page, perPage: 20) { media(type: ANIME, isAdult: false, genre_not_in: ["Hentai"], sort: SCORE_DESC) {
            id title { english romaji } description coverImage { large extraLarge }
            bannerImage episodes status averageScore genres isAdult
        }}}""".trimIndent())

    fun search(query: String) = mapOf("query" to """
        { Page(page: 1, perPage: 20) { media(type: ANIME, isAdult: false, genre_not_in: ["Hentai"], search: "$query") {
            id title { english romaji } description coverImage { large extraLarge }
            bannerImage episodes status averageScore genres isAdult
        }}}""".trimIndent())

    fun detail(id: Int) = mapOf("query" to """
        { Media(id: $id, type: ANIME, isAdult: false) {
            id title { english romaji native } description
            coverImage { large extraLarge } bannerImage
            episodes status averageScore genres
            nextAiringEpisode { episode airingAt }
        }}""".trimIndent())
}
