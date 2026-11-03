package com.anees.novamobile.data.api

import com.google.gson.GsonBuilder
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object RetrofitClient {

    /**
     * One connection pool for everything (metadata, sniffing, video): reused warm connections
     * skip the TLS handshake on every play. No interceptors here, so stream requests keep the
     * WebView's own User-Agent.
     */
    val base: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val httpClient = base.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            // Family-safe: TMDB never returns adult titles for any endpoint
            val req = chain.request()
            val url = if (req.url.host == "api.themoviedb.org")
                req.url.newBuilder().setQueryParameter("include_adult", "false").build() else req.url
            chain.proceed(req.newBuilder().url(url)
                .header("User-Agent", "NovaTV/1.0 Android")
                .build())
        }
        .build()

    private val gson = GsonBuilder().setLenient().create()

    val tmdb: TmdbApi by lazy {
        Retrofit.Builder()
            .baseUrl("https://api.themoviedb.org/3/")
            .client(httpClient)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
            .create(TmdbApi::class.java)
    }

    val aniList: AniListApi by lazy {
        Retrofit.Builder()
            .baseUrl("https://graphql.anilist.co/")
            .client(httpClient)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
            .create(AniListApi::class.java)
    }

    /** Plain client for small JSON fetches (ani.zip, AniSkip). */
    val http: OkHttpClient get() = httpClient
}
