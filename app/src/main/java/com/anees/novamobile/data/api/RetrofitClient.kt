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
        // Source searches fire dozens of requests at a few hosts at once: OkHttp's default 5 per host queued them
        .dispatcher(okhttp3.Dispatcher().apply { maxRequests = 128; maxRequestsPerHost = 16 })
        .connectionPool(okhttp3.ConnectionPool(24, 5, TimeUnit.MINUTES))
        .dns(FastDns)
        .addInterceptor(BlockRetry)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    /**
     * Mobile data often fails where Wi-Fi works: the carrier's DNS points blocked hosts at a block page
     * on a public IP (wrong certificate) or a dead address (refused / reset). Retry such a failure once
     * with the host resolved over DNS-over-HTTPS.
     */
    private object BlockRetry : okhttp3.Interceptor {
        override fun intercept(chain: okhttp3.Interceptor.Chain): okhttp3.Response {
            val req = chain.request()
            return try { chain.proceed(req) } catch (e: java.io.IOException) {
                val blockLike = e is javax.net.ssl.SSLException || e is java.net.ConnectException ||
                    e is java.net.NoRouteToHostException ||
                    (e is java.net.SocketException && e.message?.contains("reset", true) == true)
                if (!blockLike || req.body?.isOneShot() == true || chain.call().isCanceled() || !FastDns.suspectBlock(req.url.host)) throw e
                chain.proceed(req)
            }
        }
    }

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
