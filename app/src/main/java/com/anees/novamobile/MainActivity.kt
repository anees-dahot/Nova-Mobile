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
import kotlinx.coroutines.launch
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
        findViewById<View>(R.id.btn_calendar).visibility = View.GONE   // calendar paused for now
        // Rebuild new-episode reminders in the background
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) { kotlinx.coroutines.delay(10_000); runCatching { com.anees.novamobile.calendar.AiringCalendar.reschedule(this@MainActivity) } }
        handlePlayIntent(intent)
        // Downloads: start the manager (restores the queue) and finish resolving any pending items
        com.anees.novamobile.download.NovaDownloads.manager(this)
        handler.postDelayed({ com.anees.novamobile.download.DownloadStarter.resume(this) }, 3000)
        if (intent?.getStringExtra("open") == "downloads") handler.post { openDownloads() }
        openShowFromIntent(intent)
        intent?.data?.let { com.anees.novamobile.sync.Sync.handleRedirect(this, it) }
        handler.postDelayed({ com.anees.novamobile.sync.Sync.syncNow(this) }, 6000)

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
    fun topBarHeight(): Int = topBar.height

    fun setTopBarSolid(solid: Boolean) {
        topBar.setBackgroundResource(if (solid) R.color.nova_black else R.drawable.topbar_bg)
    }

    fun openVoiceSearch() {
        switchTab(SEARCH)
        supportFragmentManager.executePendingTransactions()
        (fragments[SEARCH] as? SearchFragment)?.startVoice()
    }

    // Leaving with downloads still being matched to a stream: keep going in the background
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        com.anees.novamobile.download.ResolveService.startIfNeeded(this)
    }

    override fun onPause() {
        super.onPause()
        if (isFinishing) com.anees.novamobile.download.ResolveService.startIfNeeded(this)
    }

    override fun onDestroy() {
        // The hidden-WebView host belongs to this window; downloads fall back to plain-HTTP sources without it
        if (com.anees.novamobile.ui.player.Prefetcher.host === findViewById<android.widget.FrameLayout>(R.id.sniff_host)) com.anees.novamobile.ui.player.Prefetcher.host = null
        super.onDestroy()
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        if (intent.getStringExtra("open") == "downloads") openDownloads()
        openShowFromIntent(intent)
        handlePlayIntent(intent)
        intent.data?.let { if (com.anees.novamobile.sync.Sync.handleRedirect(this, it)) android.widget.Toast.makeText(this, "Account connected", android.widget.Toast.LENGTH_SHORT).show() }
    }

    // ── Banner: "Downloading Frieren · S1 E1   View" (Apple-style toast that slides up) ──

    private var banner: View? = null
    private val hideBanner = Runnable { banner?.let { b -> b.animate().translationY(b.height * 1.5f).alpha(0f).setDuration(220).withEndAction { b.visibility = View.GONE }.start() } }

    fun showBanner(message: String, action: String? = null, onAction: (() -> Unit)? = null) {
        val dp = resources.displayMetrics.density
        val b = banner ?: LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = android.graphics.drawable.GradientDrawable().apply { setColor(0xF22C2C2E.toInt()); cornerRadius = 16 * dp; setStroke(dp.toInt(), 0x22FFFFFF) }
            elevation = 12 * dp
            setPadding((18 * dp).toInt(), (12 * dp).toInt(), (8 * dp).toInt(), (12 * dp).toInt())
            addView(ImageView(context).apply { id = 1; setImageResource(R.drawable.ic_download) }, LinearLayout.LayoutParams((20 * dp).toInt(), (20 * dp).toInt()))
            addView(TextView(context).apply { id = 2; textSize = 14f; setTextColor(0xFFFFFFFF.toInt()); setPadding((10 * dp).toInt(), 0, (8 * dp).toInt(), 0); maxLines = 2 },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(TextView(context).apply {
                id = 3; textSize = 14f; setTextColor(0xFF0A84FF.toInt()); typeface = android.graphics.Typeface.DEFAULT_BOLD
                setPadding((12 * dp).toInt(), (6 * dp).toInt(), (12 * dp).toInt(), (6 * dp).toInt())
            })
            this@MainActivity.findViewById<android.widget.FrameLayout>(android.R.id.content).addView(this,
                android.widget.FrameLayout.LayoutParams(android.widget.FrameLayout.LayoutParams.MATCH_PARENT, android.widget.FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply {
                    setMargins((12 * dp).toInt(), 0, (12 * dp).toInt(), (72 * dp).toInt())
                })
            banner = this
        }
        b.findViewById<TextView>(2).text = message
        b.findViewById<TextView>(3).apply {
            text = action ?: ""; visibility = if (action == null) View.GONE else View.VISIBLE
            setOnClickListener { handler.removeCallbacks(hideBanner); hideBanner.run(); onAction?.invoke() }
        }
        handler.removeCallbacks(hideBanner)
        b.visibility = View.VISIBLE
        b.post {
            // Slides up from below, critically damped (no bounce), like an iOS banner
            b.translationY = b.height * 1.5f; b.alpha = 0f
            b.animate().translationY(0f).alpha(1f).setDuration(280).setInterpolator(android.view.animation.DecelerateInterpolator(2f)).start()
        }
        handler.postDelayed(hideBanner, 4500)
    }

    /** "S2 E5 is out — tap to play" notification. */
    private fun handlePlayIntent(i: android.content.Intent?) {
        val json = i?.getStringExtra("play_card") ?: return
        i.removeExtra("play_card")
        val card = runCatching { com.google.gson.Gson().fromJson(json, MediaCard::class.java) }.getOrNull() ?: return
        com.anees.novamobile.ui.player.PlayerActivity.launch(this, card, i.getIntExtra("play_s", 1), i.getIntExtra("play_e", 1))
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun openDownloads() = openOverlay(com.anees.novamobile.ui.downloads.DownloadsFragment())

    /** "Downloaded" notification for a series: open that show's folder. */
    private fun openShowFromIntent(i: android.content.Intent?) {
        if (i?.getStringExtra("open") != "downloads_show") return
        val card = runCatching { com.google.gson.Gson().fromJson(i.getStringExtra("card"), MediaCard::class.java) }.getOrNull() ?: return
        handler.post { openDownloadsShow(card) }
    }

    /** One show's downloaded episodes (a "folder" on the Downloads page). */
    fun openDownloadsShow(card: MediaCard) = openOverlay(com.anees.novamobile.ui.downloads.DownloadsShowFragment.of(card))

    /** Android 13+: ask once, at the moment it matters (first download), for progress notifications. */
    fun ensureNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 7)
        // Android 8-9: saving downloads to Movies/Nova needs storage access
        if (android.os.Build.VERSION.SDK_INT < 29 &&
            checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE), 8)
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
