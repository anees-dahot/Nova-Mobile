package com.anees.novamobile

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.anees.novamobile.data.local.LocalStore

/** App-wide setup tuned for a 1.7GB, 32-bit TV box. */
class NovaApp : Application(), ImageLoaderFactory {

    override fun onCreate() {
        super.onCreate()
        LocalStore.init(this)
        // Latest source list from GitHub: dead sources get replaced without reinstalling the app
        Thread { com.anees.novamobile.ui.player.StreamExtractor.refreshRemote() }.start()
    }

    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        // Small memory cache + 16-bit bitmaps: posters look the same on a TV, at half the RAM
        .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.12).build() }
        .diskCache { DiskCache.Builder().directory(cacheDir.resolve("img")).maxSizeBytes(80L * 1024 * 1024).build() }
        .allowRgb565(true)
        .crossfade(true)
        .respectCacheHeaders(false)
        .build()

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // Player is opening / system is low: drop decoded posters, they reload from disk instantly
        if (level >= TRIM_MEMORY_UI_HIDDEN) coil.Coil.imageLoader(this).memoryCache?.clear()
    }
}
