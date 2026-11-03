package com.anees.novamobile.ui.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.anees.novamobile.data.model.MediaCard
import com.anees.novamobile.data.model.MediaType
import com.anees.novamobile.data.repository.HomeData
import com.anees.novamobile.data.repository.MediaRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Shared by all tabs. Shows last session's rows instantly from disk, then refreshes quietly. */
class HomeViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = MediaRepository()
    private val dir = app.cacheDir
    val homeData = MutableLiveData<HomeData>()
    val genreRows = MutableLiveData<Map<MediaType, List<Pair<String, List<MediaCard>>>>>(emptyMap())
    val trending = MutableLiveData<List<MediaCard>>(emptyList())
    private var started = false
    private val genreLoading = mutableSetOf<MediaType>()

    fun load() {
        if (started) return
        started = true
        viewModelScope.launch {
            val cached: HomeData? = withContext(Dispatchers.IO) { repo.cachedHome(dir) }
            if (cached != null && homeData.value == null) homeData.value = cached
            val fresh = runCatching { repo.getHomePage() }.getOrNull()
            if (fresh != null && fresh.trendingMovies.isNotEmpty()) {
                homeData.value = fresh
                withContext(Dispatchers.IO) { repo.saveHome(dir, fresh) }
            } else if (homeData.value == null) homeData.value = HomeData()
        }
        // Search's empty state, ready before the user opens it
        viewModelScope.launch { trending.value = repo.trendingToday() }
    }

    fun loadGenres(type: MediaType) {
        if (genreRows.value?.containsKey(type) == true || !genreLoading.add(type)) return
        viewModelScope.launch {
            val rows = repo.genreRows(type)
            genreRows.value = (genreRows.value ?: emptyMap()) + (type to rows)
        }
    }
}
