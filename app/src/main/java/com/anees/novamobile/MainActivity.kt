package com.anees.novamobile

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.anees.novamobile.data.model.MediaCard
import com.anees.novamobile.ui.common.TabPage
import com.anees.novamobile.ui.detail.DetailFragment
import com.anees.novamobile.ui.home.HomeFragment
import com.anees.novamobile.ui.search.SearchFragment
import com.anees.novamobile.ui.settings.SettingsFragment
import com.anees.novamobile.ui.watchlist.WatchlistFragment

class MainActivity : AppCompatActivity() {

    private data class Tab(val label: String, val icon: Int, val create: () -> Fragment)

    private val tabs = listOf(
        Tab("Home", R.drawable.ic_home) { HomeFragment.newInstance(0) },
        Tab("Movies", R.drawable.ic_movie) { HomeFragment.newInstance(1) },
        Tab("Series", R.drawable.ic_tv) { HomeFragment.newInstance(2) },
        Tab("Anime", R.drawable.ic_anime) { HomeFragment.newInstance(3) },
        Tab("My List", R.drawable.ic_bookmark) { WatchlistFragment() },
    )
    private val SEARCH = tabs.size          // search lives behind the top-bar 🔍, not the bottom bar
    private val HOME = 0

    private val fragments = arrayOfNulls<Fragment>(tabs.size + 1)
    private val navItems = mutableListOf<View>()
    private var current = -1
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var topBar: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        topBar = findViewById(R.id.top_bar)
        com.anees.novamobile.ui.player.Prefetcher.host = findViewById(R.id.sniff_host)
        handler.postDelayed({ runCatching { android.webkit.WebSettings.getDefaultUserAgent(this) } }, 4000)
        if (savedInstanceState == null) handler.postDelayed({ com.anees.novamobile.update.Updater.check(this, lifecycleScope) }, 8000)

        val nav = findViewById<LinearLayout>(R.id.bottom_nav)
        val dp = resources.displayMetrics.density
        tabs.forEachIndexed { i, t ->
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                isClickable = true
                addView(ImageView(context).apply {
                    setImageResource(t.icon)
                    imageTintList = getColorStateList(R.color.nav_item)
                    isDuplicateParentStateEnabled = true
                }, LinearLayout.LayoutParams((24 * dp).toInt(), (24 * dp).toInt()))
                addView(TextView(context).apply {
                    text = t.label
                    gravity = Gravity.CENTER
                    textSize = 11f
                    setTextColor(getColorStateList(R.color.nav_item))
                    isDuplicateParentStateEnabled = true
                    setPadding(0, (3 * dp).toInt(), 0, 0)
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
                setOnClickListener { switchTab(i) }
            }
            nav.addView(item, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
            navItems += item
        }

        findViewById<View>(R.id.btn_search).setOnClickListener { switchTab(SEARCH) }
        findViewById<View>(R.id.btn_settings).setOnClickListener { openOverlay(SettingsFragment()) }
        findViewById<View>(R.id.btn_downloads).setOnClickListener { openDownloads() }
        // Downloads: start the manager (restores the queue) and finish resolving any pending items
        com.anees.novamobile.download.NovaDownloads.manager(this)
        handler.postDelayed({ com.anees.novamobile.download.DownloadStarter.resume(this) }, 3000)
        if (intent?.getStringExtra("open") == "downloads") handler.post { openDownloads() }

        (0..tabs.size).forEach { i -> fragments[i] = supportFragmentManager.findFragmentByTag("tab_$i") }
        val visible = fragments.indexOfFirst { it != null && !it.isHidden }
        switchTab(if (visible >= 0) visible else HOME)
    }

    fun switchTab(index: Int) {
        if (index == current) {
            (fragments[index] as? TabPage)?.scrollToTop()   // tap the active tab again = back to top
            return
        }
        val tx = supportFragmentManager.beginTransaction().setCustomAnimations(android.R.anim.fade_in, android.R.anim.fade_out)
        fragments.getOrNull(current)?.let { tx.hide(it) }
        val target = fragments[index] ?: (if (index == SEARCH) SearchFragment() else tabs[index].create()).also {
            fragments[index] = it
            tx.add(R.id.fragment_container, it, "tab_$index")
        }
        tx.show(target).commit()
        navItems.forEachIndexed { i, v -> v.isSelected = i == index }
        current = index
        // Search has its own header; the brand bar shows everywhere else
        topBar.visibility = if (index == SEARCH) View.GONE else View.VISIBLE
    }

    /** Brand bar fades over content as it scrolls (set by Home). */
    fun setTopBarSolid(solid: Boolean) {
        topBar.setBackgroundResource(if (solid) R.color.nova_black else R.drawable.topbar_bg)
    }

    fun openVoiceSearch() {
        switchTab(SEARCH)
        supportFragmentManager.executePendingTransactions()
        (fragments[SEARCH] as? SearchFragment)?.startVoice()
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        if (intent.getStringExtra("open") == "downloads") openDownloads()
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun openDownloads() = openOverlay(com.anees.novamobile.ui.downloads.DownloadsFragment())

    /** Android 13+: ask once, at the moment it matters (first download), for progress notifications. */
    fun ensureNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 7)
    }

    fun openDetail(card: MediaCard) {
        com.anees.novamobile.ui.player.Prefetcher.cancel()
        openOverlay(DetailFragment.newInstance(card))
    }

    private fun openOverlay(f: Fragment) {
        supportFragmentManager.beginTransaction()
            .setCustomAnimations(R.anim.detail_in, android.R.anim.fade_out, android.R.anim.fade_in, R.anim.detail_out)
            .add(R.id.detail_container, f)
            .addToBackStack(null)
            .commit()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            supportFragmentManager.backStackEntryCount > 0 -> supportFragmentManager.popBackStack()
            current != HOME -> switchTab(HOME)
            else -> @Suppress("DEPRECATION") super.onBackPressed()
        }
    }
}
