package com.anees.novamobile.data.local

import android.content.Context
import android.content.SharedPreferences
import com.anees.novamobile.data.model.HistoryEntry
import com.anees.novamobile.data.model.MediaCard
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/** Tiny SharedPreferences-backed store: continue-watching history + watchlist. */
/** User preferences (Settings tab). */
data class Settings(
    val audioLang: String = "hi",          // hi, en, ur, or "orig"
    val subtitlesOn: Boolean = false,
    val subtitleSize: Int = 1,             // 0 small, 1 medium, 2 large
    val autoplayNext: Boolean = true,
    val lowCpuMode: Boolean = false,       // tunneled playback
    val matchFrameRate: Boolean = false,
    val volumeBoost: Int = 0,              // 0 off, 1 +6dB, 2 +10dB, 3 +15dB
    val maxQuality: Int = 1080,            // playback cap: 720, 1080, or 2160 (a 4K stream would stutter a low-RAM box)
    val dlQuality: Int = 1,                // 0 480p, 1 720p, 2 1080p
    val dlWifiOnly: Boolean = false,
    val dlParallel: Int = 2,
    val dlAutoDeleteWatched: Boolean = false,
    val newEpisodeAlerts: Boolean = true,
    val resizeMode: Int = 0,               // 0 fit, 4 zoom to fill (AspectRatioFrameLayout modes)
    val autoPip: Boolean = true,           // leaving the player while playing opens picture-in-picture
    val gestures: Boolean = true,          // swipe for brightness / volume / seek
    val smartDownloads: Boolean = false,   // finished an episode: delete it, download the next
    val keepNext: Int = 1,                 // smart downloads: how many upcoming episodes to keep ready
    val dlAskQuality: Boolean = true,      // ask quality + audio before each download
    val dlAudioAll: Boolean = true,        // keep every audio language (false: preferred + English only)
    val dlStorage: Int = 0,                // 0 internal, 1 SD card / second storage
    val notifyDownloadDone: Boolean = true,
    val notifyDownloadProblem: Boolean = true
)

object LocalStore {
    private const val MAX_HISTORY = 30
    private lateinit var prefs: SharedPreferences
    private val gson = Gson()

    fun init(ctx: Context) {
        if (!::prefs.isInitialized) prefs = ctx.applicationContext.getSharedPreferences("nova", Context.MODE_PRIVATE)
    }

    // ── History ───────────────────────────────────────────────────────────────

    fun history(): List<HistoryEntry> = read("history", object : TypeToken<List<HistoryEntry>>() {})

    fun historyFor(card: MediaCard): HistoryEntry? = history().firstOrNull { it.key == "${card.type}:${card.id}" }

    fun saveProgress(entry: HistoryEntry) {
        val list = history().filterNot { it.key == entry.key }.toMutableList()
        // Treat >95% watched movies as finished
        val finished = entry.durationMs > 0 && entry.progress > 0.95f && entry.card.type.name == "MOVIE"
        if (!finished) list.add(0, entry)
        write("history", list.take(MAX_HISTORY))
    }

    fun removeHistory(key: String) = write("history", history().filterNot { it.key == key })

    // ── Watchlist ─────────────────────────────────────────────────────────────

    fun watchlist(): List<MediaCard> = read("watchlist", object : TypeToken<List<MediaCard>>() {})

    fun inWatchlist(card: MediaCard) = watchlist().any { it.id == card.id && it.type == card.type }

    /** Toggles and returns the new state. [notify]: also update connected accounts (Trakt/AniList/MAL). */
    fun toggleWatchlist(card: MediaCard, notify: Boolean = true): Boolean = toggleWatchlistLocal(card).also {
        if (notify) com.anees.novamobile.sync.Sync.listed(card, it)
    }

    private fun toggleWatchlistLocal(card: MediaCard): Boolean {
        val list = watchlist().toMutableList()
        val existing = list.indexOfFirst { it.id == card.id && it.type == card.type }
        return if (existing >= 0) { list.removeAt(existing); write("watchlist", list); false }
        else { list.add(0, card); write("watchlist", list); true }
    }

    // ── Settings ──────────────────────────────────────────────────────────────

    fun settings(): Settings = prefs.getString("settings", null)
        ?.let { runCatching { gson.fromJson(it, Settings::class.java) }.getOrNull() } ?: Settings()

    fun saveSettings(s: Settings) = prefs.edit().putString("settings", gson.toJson(s)).apply()

    fun clearHistory() = prefs.edit().remove("history").apply()

    // ── Per-title audio language (picked in the player, reused next episode) ─

    fun subtitleFor(key: String): String? = prefs.getString("sub_$key", null)          // language code, "off", or null
    fun setSubtitleFor(key: String, lang: String) = prefs.edit().putString("sub_$key", lang).apply()

    /** Download retry counts survive the app being closed (no endless retry storm after restarts). */
    fun dlAttempts(id: String): Int = prefs.getInt("dlatt_$id", 0)
    fun setDlAttempts(id: String, n: Int) = prefs.edit().apply { if (n <= 0) remove("dlatt_$id") else putInt("dlatt_$id", n) }.apply()

    fun audioFor(key: String): String? = prefs.getString("audio_$key", null)
    fun setAudioFor(key: String, lang: String) = prefs.edit().putString("audio_$key", lang).apply()

    /** Audio languages actually found in a title's stream (learned when it plays). */
    fun audioLangs(key: String): List<String>? = prefs.getString("langs_$key", null)?.split(",")?.filter { it.isNotBlank() }
    fun setAudioLangs(key: String, langs: List<String>) = prefs.edit().putString("langs_$key", langs.joinToString(",")).apply()

    /** Last measured network speed (bits/s), used as the player's starting estimate. */
    fun lastBandwidth(): Long = prefs.getLong("bw", 0)
    fun setLastBandwidth(bps: Long) = prefs.edit().putLong("bw", bps).apply()

    /** Found stream links: embed URL → (time, JSON). Keeps the newest 40. */
    fun stream(key: String): Pair<Long, String>? {
        val all = streams()
        return all[key]?.let { it.first to it.second }
    }

    fun saveStream(key: String, json: String?) {
        val all = streams().toMutableMap()
        if (json == null) all.remove(key) else all[key] = System.currentTimeMillis() to json
        val newest = all.entries.sortedByDescending { it.value.first }.take(40).associate { it.key to it.value }
        prefs.edit().putString("streams", gson.toJson(newest.mapValues { listOf(it.value.first.toString(), it.value.second) })).apply()
    }

    private fun streams(): Map<String, Pair<Long, String>> = runCatching {
        val type = object : TypeToken<Map<String, List<String>>>() {}.type
        gson.fromJson<Map<String, List<String>>>(prefs.getString("streams", null) ?: return emptyMap(), type)
            .mapValues { it.value[0].toLong() to it.value[1] }
    }.getOrDefault(emptyMap())

    // ── Per-episode progress (watched / partly watched) ───────────────────────

    fun episodeProgress(showKey: String): Map<String, Float> = prefs.getString("epp_$showKey", null)
        ?.let { runCatching { gson.fromJson<Map<String, Float>>(it, object : TypeToken<Map<String, Float>>() {}.type) }.getOrNull() } ?: emptyMap()

    fun setEpisodeProgress(showKey: String, season: Int, episode: Int, progress: Float) {
        val m = episodeProgress(showKey).toMutableMap()
        val k = "$season:$episode"
        if (progress >= (m[k] ?: 0f) || progress >= 0.9f || (m[k] ?: 0f) < 0.9f) m[k] = progress   // once watched, stays watched
        prefs.edit().putString("epp_$showKey", gson.toJson(m)).apply()
    }

    // ── Airing calendar ───────────────────────────────────────────────────────

    fun airingCache(): List<com.anees.novamobile.calendar.AiringEp> =
        read("airing_cache", object : TypeToken<List<com.anees.novamobile.calendar.AiringEp>>() {})
    fun saveAiringCache(eps: List<com.anees.novamobile.calendar.AiringEp>) = write("airing_cache", eps)
    fun scheduledReminders(): List<Int> = read("airing_ids", object : TypeToken<List<Int>>() {})
    fun saveScheduledReminders(ids: List<Int>) = write("airing_ids", ids)

    // ── Account sync ──────────────────────────────────────────────────────────

    fun syncToken(service: String): String? = prefs.getString("sync_$service", null)
    fun setSyncToken(service: String, token: String?) = prefs.edit().putString("sync_$service", token).apply()
    fun syncQueue(): List<com.anees.novamobile.sync.Sync.Pending> =
        read("sync_queue", object : TypeToken<List<com.anees.novamobile.sync.Sync.Pending>>() {})
    fun saveSyncQueue(q: List<com.anees.novamobile.sync.Sync.Pending>) = write("sync_queue", q)
    fun lastSync(): Long = prefs.getLong("sync_last", 0)
    fun setLastSync(t: Long) = prefs.edit().putLong("sync_last", t).apply()

    // ── Downloads ─────────────────────────────────────────────────────────────

    fun pendingDownloads(): List<com.anees.novamobile.download.DownloadStarter.Item> =
        read("dl_pending", object : TypeToken<List<com.anees.novamobile.download.DownloadStarter.Item>>() {})
    fun savePendingDownloads(items: List<com.anees.novamobile.download.DownloadStarter.Item>) = write("dl_pending", items)

    fun unresolved(): List<com.anees.novamobile.download.DownloadStarter.Item> =
        read("dl_unresolved", object : TypeToken<List<com.anees.novamobile.download.DownloadStarter.Item>>() {})
    fun saveUnresolved(items: List<com.anees.novamobile.download.DownloadStarter.Item>) = write("dl_unresolved", items)

    fun hostHeaders(host: String): Map<String, String>? = prefs.getString("hh_$host", null)
        ?.let { runCatching { gson.fromJson<Map<String, String>>(it, object : TypeToken<Map<String, String>>() {}.type) }.getOrNull() }
    fun setHostHeaders(host: String, h: Map<String, String>) = prefs.edit().putString("hh_$host", gson.toJson(h)).apply()

    fun downloadUserAgent(): String = prefs.getString("dl_ua", null) ?: "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"
    fun setDownloadUserAgent(ua: String) = prefs.edit().putString("dl_ua", ua).apply()

    // ── Recent searches ───────────────────────────────────────────────────────

    fun recentSearches(): List<String> = read("recent_q", object : TypeToken<List<String>>() {})

    fun addRecentSearch(q: String) {
        val t = q.trim().takeIf { it.length >= 2 } ?: return
        write("recent_q", (listOf(t) + recentSearches().filterNot { it.equals(t, true) }).take(10))
    }

    fun skippedUpdate(): String? = prefs.getString("skip_update", null)
    fun setSkippedUpdate(v: String) = prefs.edit().putString("skip_update", v).apply()
    /** A title whose sources had no [lang] dub when last raced (kept 12h, then searched again). */
    fun noDub(key: String, lang: String): Boolean =
        System.currentTimeMillis() - prefs.getLong("nodub:$key:$lang", 0L) < 12 * 60 * 60 * 1000L
    fun setNoDub(key: String, lang: String) = prefs.edit().putLong("nodub:$key:$lang", System.currentTimeMillis()).apply()
    fun lastUpdateCheck(): Long = prefs.getLong("update_checked_at", 0L)
    fun setLastUpdateCheck(v: Long) = prefs.edit().putLong("update_checked_at", v).apply()

    /** Origin servers that refused direct access (must go through the source's proxy). */
    fun directBad(host: String) = prefs.getBoolean("direct_bad_$host", false)
    fun setDirectBad(host: String) = prefs.edit().putBoolean("direct_bad_$host", true).apply()

    // ── Remote sources.json ───────────────────────────────────────────────────

    fun remoteSources(): String? = prefs.getString("remote_sources", null)
    fun saveRemoteSources(json: String) = prefs.edit().putString("remote_sources", json).apply()

    // ── Provider health ───────────────────────────────────────────────────────

    /** +3 per success, -1 per failure, clamped; VidSrc starts ahead because it's the most reliable. */
    fun providerScore(name: String): Int = prefs.getInt("prov_$name", if (name == "VidSrc") 10 else 0)

    /** How long the last successful search took (ms), for Settings → Sources. */
    fun providerSpeed(name: String): Long = prefs.getLong("prov_ms_$name", 0)
    fun setProviderSpeed(name: String, ms: Long) = prefs.edit().putLong("prov_ms_$name", ms).apply()

    fun reportProvider(name: String, ok: Boolean) =
        prefs.edit().putInt("prov_$name", (providerScore(name) + if (ok) 3 else -1).coerceIn(-10, 20)).apply()

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun <T> read(key: String, type: TypeToken<List<T>>): List<T> =
        prefs.getString(key, null)?.let { runCatching { gson.fromJson<List<T>>(it, type.type) }.getOrNull() } ?: emptyList()

    private fun write(key: String, value: Any) = prefs.edit().putString(key, gson.toJson(value)).apply()
}
