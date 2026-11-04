package com.anees.novamobile.sync

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.anees.novamobile.BuildConfig
import com.anees.novamobile.data.api.RetrofitClient
import com.anees.novamobile.data.local.LocalStore
import com.anees.novamobile.data.model.MediaCard
import com.anees.novamobile.data.model.MediaType
import com.anees.novamobile.data.repository.MediaRepository
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Account sync, modelled on CloudStream's sync layer: one interface, each service plugged in.
 * Movies & series → Trakt (exact TMDB ids). Anime → AniList / MyAnimeList (exact ids via ani.zip).
 * Pushes that fail (offline, server down) wait in a saved queue and are retried on the next sync.
 */
interface SyncService {
    val name: String
    val configured: Boolean                    // client id present in local.properties
    fun loggedIn(): Boolean
    fun login(activity: Activity)
    fun logout()
    suspend fun markWatched(card: MediaCard, season: Int, episode: Int): Boolean
    suspend fun setListed(card: MediaCard, listed: Boolean): Boolean
    suspend fun pull(ctx: Context) {}          // import remote watchlist / watched state
}

object Sync {
    private const val TAG = "NovaSync"
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val repo = MediaRepository()
    val services: List<SyncService> = listOf(TraktSync, AniListSync, MalSync)

    data class Pending(val kind: String, val card: MediaCard, val season: Int = 0, val episode: Int = 0, val listed: Boolean = true)

    /** A movie or episode passed 90%. */
    fun watched(card: MediaCard, season: Int, episode: Int) = push(Pending("watched", card, season, episode))

    /** Added to / removed from My List. */
    fun listed(card: MediaCard, listed: Boolean) = push(Pending("list", card, listed = listed))

    private fun push(p: Pending) {
        if (services.none { it.loggedIn() }) return
        scope.launch { if (!send(p)) LocalStore.saveSyncQueue((LocalStore.syncQueue() + p).takeLast(300)) }
    }

    private suspend fun send(p: Pending): Boolean = services.filter { it.loggedIn() && handles(it, p.card) }.all { s ->
        runCatching { if (p.kind == "watched") s.markWatched(p.card, p.season, p.episode) else s.setListed(p.card, p.listed) }
            .onFailure { Log.w(TAG, "${s.name}: ${it.message}") }.getOrDefault(false)
    }

    private fun handles(s: SyncService, c: MediaCard) = if (s === TraktSync) c.type != MediaType.ANIME else c.type == MediaType.ANIME

    /** App start / "Sync now": retry the queue, then import from every connected account. */
    fun syncNow(ctx: Context, done: (() -> Unit)? = null) = scope.launch {
        val queue = LocalStore.syncQueue()
        val left = queue.filterNot { send(it) }
        LocalStore.saveSyncQueue(left)
        services.filter { it.loggedIn() }.forEach { s -> runCatching { s.pull(ctx) }.onFailure { Log.w(TAG, "${s.name} pull: ${it.message}") } }
        LocalStore.setLastSync(System.currentTimeMillis())
        withContext(Dispatchers.Main) { done?.invoke() }
    }

    /** OAuth redirects come back as novamobile://<service>… */
    fun handleRedirect(ctx: Context, uri: Uri): Boolean = when (uri.host) {
        "anilist" -> AniListSync.onRedirect(uri).also { if (it) syncNow(ctx) }
        "mal" -> { scope.launch { if (MalSync.onRedirect(uri)) syncNow(ctx) }; true }
        else -> false
    }

    // ── small HTTP helpers ────────────────────────────────────────────────────
    internal fun http(req: Request): Pair<Int, String> =
        RetrofitClient.base.newCall(req).execute().use { it.code to (it.body?.string() ?: "") }

    internal val JSON = "application/json".toMediaType()
    internal fun json(o: Any) = o.toString().toRequestBody(JSON)
    internal fun open(activity: Activity, url: String) = activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))

    /**
     * Login CSRF guard: a redirect only counts while a login this app started is in flight (10 min),
     * and its `state` must match when the service echoes it.
     */
    internal fun newState(service: String): String {
        val st = java.util.UUID.randomUUID().toString().replace("-", "")
        LocalStore.setSyncToken("${service}_state", "$st:${System.currentTimeMillis()}")
        return st
    }

    internal fun checkState(service: String, got: String?): Boolean {
        val saved = LocalStore.syncToken("${service}_state") ?: return false
        LocalStore.setSyncToken("${service}_state", null)
        val (st, at) = saved.split(":").let { it[0] to (it.getOrNull(1)?.toLongOrNull() ?: 0L) }
        if (System.currentTimeMillis() - at > 10 * 60_000) return false
        return got == null || got == st
    }
}

// ── Trakt: device-code login (enter a code at trakt.tv/activate) ──────────────
object TraktSync : SyncService {
    override val name = "Trakt"
    private const val API = "https://api.trakt.tv"
    override val configured get() = BuildConfig.TRAKT_ID.isNotEmpty() && BuildConfig.TRAKT_SECRET.isNotEmpty()
    override fun loggedIn() = LocalStore.syncToken("trakt") != null
    override fun logout() = LocalStore.setSyncToken("trakt", null)

    private fun req(path: String) = Request.Builder().url(API + path)
        .header("Content-Type", "application/json").header("trakt-api-version", "2").header("trakt-api-key", BuildConfig.TRAKT_ID)
        .apply { LocalStore.syncToken("trakt")?.let { header("Authorization", "Bearer $it") } }

    /** Starts device login; [show] receives (code, url) to display; polls until approved. */
    fun startDeviceLogin(show: (String, String) -> Unit, done: (Boolean) -> Unit) = Sync.scope.launch {
        val (code, body) = Sync.http(req("/oauth/device/code").post(Sync.json(JSONObject().put("client_id", BuildConfig.TRAKT_ID))).build())
        if (code != 200) { withContext(Dispatchers.Main) { done(false) }; return@launch }
        val o = JSONObject(body)
        withContext(Dispatchers.Main) { show(o.getString("user_code"), o.getString("verification_url")) }
        val deadline = System.currentTimeMillis() + o.getLong("expires_in") * 1000
        while (isActive && System.currentTimeMillis() < deadline) {
            delay(o.optLong("interval", 5) * 1000)
            val (c, b) = Sync.http(req("/oauth/device/token").post(Sync.json(JSONObject()
                .put("code", o.getString("device_code")).put("client_id", BuildConfig.TRAKT_ID).put("client_secret", BuildConfig.TRAKT_SECRET))).build())
            if (c == 200) { LocalStore.setSyncToken("trakt", JSONObject(b).getString("access_token")); withContext(Dispatchers.Main) { done(true) }; return@launch }
            if (c == 404 || c == 409 || c == 410 || c == 418) break
        }
        withContext(Dispatchers.Main) { done(false) }
    }

    override fun login(activity: Activity) {}   // device flow is started from Settings (shows the code)

    private fun item(card: MediaCard, season: Int, episode: Int): JSONObject {
        val ids = JSONObject().put("ids", JSONObject().put("tmdb", card.tmdbId ?: card.id))
        return if (card.type == MediaType.MOVIE) JSONObject().put("movies", JSONArray().put(ids))
        else JSONObject().put("shows", JSONArray().put(ids.put("seasons", JSONArray().put(JSONObject().put("number", season)
            .put("episodes", JSONArray().put(JSONObject().put("number", episode)))))))
    }

    override suspend fun markWatched(card: MediaCard, season: Int, episode: Int) =
        Sync.http(req("/sync/history").post(Sync.json(item(card, season, episode))).build()).first in 200..299

    override suspend fun setListed(card: MediaCard, listed: Boolean): Boolean {
        val ids = JSONObject().put("ids", JSONObject().put("tmdb", card.tmdbId ?: card.id))
        val body = JSONObject().put(if (card.type == MediaType.MOVIE) "movies" else "shows", JSONArray().put(ids))
        return Sync.http(req(if (listed) "/sync/watchlist" else "/sync/watchlist/remove").post(Sync.json(body)).build()).first in 200..299
    }

    /** Watchlist → My List; watched episodes → episode progress (✓ Watched). */
    override suspend fun pull(ctx: Context) {
        val (c, b) = Sync.http(req("/sync/watchlist").get().build())
        if (c == 200) JSONArray(b).let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i); val type = o.optString("type")
                val tmdb = o.optJSONObject(type)?.optJSONObject("ids")?.optInt("tmdb") ?: continue
                val card = if (type == "movie") Sync.repo.getMovieDetail(tmdb) else Sync.repo.getSeriesDetail(tmdb)?.first
                if (card != null && !LocalStore.inWatchlist(card)) LocalStore.toggleWatchlist(card, notify = false)
            }
        }
        val (c2, b2) = Sync.http(req("/sync/watched/shows").get().build())
        if (c2 == 200) JSONArray(b2).let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val tmdb = o.optJSONObject("show")?.optJSONObject("ids")?.optInt("tmdb") ?: continue
                val seasons = o.optJSONArray("seasons") ?: continue
                for (s in 0 until seasons.length()) {
                    val so = seasons.getJSONObject(s); val eps = so.getJSONArray("episodes")
                    for (e in 0 until eps.length())
                        LocalStore.setEpisodeProgress("SERIES:$tmdb", so.getInt("number"), eps.getJSONObject(e).getInt("number"), 1f)
                }
            }
        }
    }
}

// ── AniList: implicit grant (token comes back in the redirect) ────────────────
object AniListSync : SyncService {
    override val name = "AniList"
    override val configured get() = BuildConfig.ANILIST_ID.isNotEmpty()
    override fun loggedIn() = LocalStore.syncToken("anilist") != null
    override fun logout() = LocalStore.setSyncToken("anilist", null)
    override fun login(activity: Activity) =
        Sync.open(activity, "https://anilist.co/api/v2/oauth/authorize?client_id=${BuildConfig.ANILIST_ID}&response_type=token&state=${Sync.newState("anilist")}")

    fun onRedirect(uri: Uri): Boolean {
        val frag = Uri.parse("x://x?" + (uri.fragment ?: ""))
        val token = frag.getQueryParameter("access_token") ?: return false
        if (!Sync.checkState("anilist", frag.getQueryParameter("state") ?: uri.getQueryParameter("state"))) return false
        LocalStore.setSyncToken("anilist", token); return true
    }

    private fun gql(query: String, vars: JSONObject): Pair<Int, String> = Sync.http(Request.Builder().url("https://graphql.anilist.co")
        .header("Authorization", "Bearer ${LocalStore.syncToken("anilist")}").header("Accept", "application/json")
        .post(Sync.json(JSONObject().put("query", query).put("variables", vars))).build())

    private const val SAVE = "mutation(\$id:Int,\$p:Int,\$s:MediaListStatus){SaveMediaListEntry(mediaId:\$id,progress:\$p,status:\$s){id}}"

    override suspend fun markWatched(card: MediaCard, season: Int, episode: Int): Boolean {
        val done = card.episodes != null && episode >= card.episodes!!
        return gql(SAVE, JSONObject().put("id", card.aniListId ?: card.id).put("p", episode).put("s", if (done) "COMPLETED" else "CURRENT")).first == 200
    }

    override suspend fun setListed(card: MediaCard, listed: Boolean): Boolean =
        if (!listed) true else gql(SAVE, JSONObject().put("id", card.aniListId ?: card.id).put("s", "PLANNING")).first == 200

    /** Planning list → My List; watching progress → episode progress. */
    override suspend fun pull(ctx: Context) {
        val (vc, vb) = gql("{Viewer{id}}", JSONObject())
        if (vc != 200) return
        val uid = JSONObject(vb).getJSONObject("data").getJSONObject("Viewer").getInt("id")
        val (c, b) = gql("query(\$u:Int){MediaListCollection(userId:\$u,type:ANIME,status_in:[PLANNING,CURRENT]){lists{entries{status progress media{id}}}}}", JSONObject().put("u", uid))
        if (c != 200) return
        val lists = JSONObject(b).getJSONObject("data").getJSONObject("MediaListCollection").getJSONArray("lists")
        for (l in 0 until lists.length()) {
            val entries = lists.getJSONObject(l).getJSONArray("entries")
            for (e in 0 until entries.length()) {
                val en = entries.getJSONObject(e); val id = en.getJSONObject("media").getInt("id")
                if (en.getString("status") == "PLANNING") Sync.repo.getAnimeDetail(id)?.let { if (!LocalStore.inWatchlist(it)) LocalStore.toggleWatchlist(it, notify = false) }
                else for (ep in 1..en.optInt("progress")) LocalStore.setEpisodeProgress("ANIME:$id", 1, ep, 1f)
            }
        }
    }
}

// ── MyAnimeList: PKCE (plain) code flow ───────────────────────────────────────
object MalSync : SyncService {
    override val name = "MyAnimeList"
    override val configured get() = BuildConfig.MAL_ID.isNotEmpty()
    override fun loggedIn() = LocalStore.syncToken("mal") != null
    override fun logout() = LocalStore.setSyncToken("mal", null)

    override fun login(activity: Activity) {
        val verifier = (1..96).map { "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-._~".random() }.joinToString("")
        LocalStore.setSyncToken("mal_verifier", verifier)
        Sync.open(activity, "https://myanimelist.net/v1/oauth2/authorize?response_type=code&client_id=${BuildConfig.MAL_ID}" +
            "&code_challenge=$verifier&code_challenge_method=plain&redirect_uri=novamobile://mal&state=${Sync.newState("mal")}")
    }

    suspend fun onRedirect(uri: Uri): Boolean {
        val code = uri.getQueryParameter("code") ?: return false
        if (!Sync.checkState("mal", uri.getQueryParameter("state"))) return false
        val body = okhttp3.FormBody.Builder().add("client_id", BuildConfig.MAL_ID).add("grant_type", "authorization_code")
            .add("code", code).add("code_verifier", LocalStore.syncToken("mal_verifier") ?: "").add("redirect_uri", "novamobile://mal").build()
        val (c, b) = Sync.http(Request.Builder().url("https://myanimelist.net/v1/oauth2/token").post(body).build())
        if (c != 200) return false
        LocalStore.setSyncToken("mal", JSONObject(b).getString("access_token")); return true
    }

    private suspend fun malId(card: MediaCard): Int? = Sync.repo.animeMapping(card.aniListId ?: card.id)?.mappings?.malId

    private fun put(id: Int, form: okhttp3.FormBody) = Sync.http(Request.Builder().url("https://api.myanimelist.net/v2/anime/$id/my_list_status")
        .header("Authorization", "Bearer ${LocalStore.syncToken("mal")}").put(form).build()).first == 200

    override suspend fun markWatched(card: MediaCard, season: Int, episode: Int): Boolean {
        val id = malId(card) ?: return true
        val done = card.episodes != null && episode >= card.episodes!!
        return put(id, okhttp3.FormBody.Builder().add("status", if (done) "completed" else "watching").add("num_watched_episodes", episode.toString()).build())
    }

    override suspend fun setListed(card: MediaCard, listed: Boolean): Boolean {
        if (!listed) return true
        val id = malId(card) ?: return true
        return put(id, okhttp3.FormBody.Builder().add("status", "plan_to_watch").build())
    }
}
