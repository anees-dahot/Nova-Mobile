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

    /**
     * Pages, in fragment-slot order. Home's categories (For You / Movies / Series / Anime) are chips
     * at the top of Home; the bottom bar is Home · Search · Calendar · Downloads · My List.
     */
    private val pages = listOf<() -> Fragment>(
        { HomeFragment.newInstance(0) }, { HomeFragment.newInstance(1) }, { HomeFragment.newInstance(2) }, { HomeFragment.newInstance(3) },
        { SearchFragment() },
        { com.anees.novamobile.ui.calendar.CalendarFragment().apply { arguments = Bundle().apply { putBoolean("tab", true) } } },
        { com.anees.novamobile.ui.downloads.DownloadsFragment().apply { arguments = Bundle().apply { putBoolean("tab", true) } } },
        { WatchlistFragment() },
    )
    private val HOME = 0
    private val SEARCH = 4
    private val CALENDAR = 5
    private val DOWNLOADS = 6
    private val MY_LIST = 7
    private val CATEGORIES = listOf("For You", "Movies", "Series", "Anime")
    /** Bottom bar: label, icon, page it opens (Home opens the last category used). */
    private val tabs = listOf(
        Tab("Home", R.drawable.ic_home) { Fragment() },
        Tab("Search", R.drawable.ic_search) { Fragment() },
        Tab("Calendar", R.drawable.ic_calendar) { Fragment() },
        Tab("Downloads", R.drawable.ic_download) { Fragment() },
        Tab("My List", R.drawable.ic_bookmark) { Fragment() },
    )
    private fun pageOfTab(i: Int) = when (i) { 0 -> homePage; 1 -> SEARCH; 2 -> CALENDAR; 3 -> DOWNLOADS; else -> MY_LIST }
    private fun tabOfPage(p: Int) = when (p) { in 0..3 -> 0; SEARCH -> 1; CALENDAR -> 2; DOWNLOADS -> 3; else -> 4 }
    private var homePage = 0

    private val fragments = arrayOfNulls<Fragment>(pages.size)
    private val navItems = mutableListOf<View>()
    private val chipViews = mutableListOf<TextView>()
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
        // Smooth width change when the active capsule moves
        nav.layoutTransition = android.animation.LayoutTransition().apply {
            enableTransitionType(android.animation.LayoutTransition.CHANGING)
            setDuration(220)
        }
        tabs.forEachIndexed { i, t ->
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                isClickable = true
                contentDescription = t.label
                layoutTransition = android.animation.LayoutTransition().apply { setDuration(180) }
                addView(ImageView(context).apply {
                    setImageResource(t.icon)
                }, LinearLayout.LayoutParams((22 * dp).toInt(), (22 * dp).toInt()))
                addView(TextView(context).apply {
                    text = t.label
                    textSize = 13f
                    maxLines = 1
                    typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
                    setPadding((7 * dp).toInt(), 0, 0, 0)
                    visibility = View.GONE
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
                setOnClickListener { com.anees.novamobile.ui.common.Ui.tick(it); switchTab(pageOfTab(i)) }
            }
            com.anees.novamobile.ui.common.Ui.pressable(item, 0.94f)
            nav.addView(item, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
            navItems += item
        }
        // Category chips (Home only): pill per category, the active one filled
        val chips = findViewById<LinearLayout>(R.id.chips)
        CATEGORIES.forEachIndexed { i, label ->
            val chip = TextView(this).apply {
                text = label; textSize = 14f; gravity = Gravity.CENTER
                typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
                setPadding((16 * dp).toInt(), 0, (16 * dp).toInt(), 0)
                isClickable = true
                setOnClickListener { com.anees.novamobile.ui.common.Ui.tick(it); switchTab(i) }
            }
            chips.addView(chip, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, (32 * dp).toInt()).apply { marginEnd = (8 * dp).toInt() })
            chipViews += chip
        }

        findViewById<View>(R.id.btn_settings).setOnClickListener { openOverlay(SettingsFragment()) }
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

        pages.indices.forEach { i -> fragments[i] = supportFragmentManager.findFragmentByTag("tab_$i") }
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
        val target = fragments[index] ?: pages[index]().also {
            fragments[index] = it
            tx.add(R.id.fragment_container, it, "tab_$index")
        }
        tx.show(target).commit()
        if (index in 0..3) homePage = index
        paintNav(tabOfPage(index))
        paintChips()
        current = index
        // Search, Calendar and Downloads have their own large headers; the brand bar shows on Home and My List
        topBar.visibility = if (index == SEARCH || index == CALENDAR || index == DOWNLOADS) View.GONE else View.VISIBLE
        findViewById<View>(R.id.chips_scroll).visibility = if (index in 0..3) View.VISIBLE else View.GONE
        if (index !in 0..3) setTopBarSolid(true)
    }

    /** Active tab: white capsule, black icon + label, twice as wide; others: icon only, dimmed. */
    private fun paintNav(active: Int) {
        val dp = resources.displayMetrics.density
        navItems.forEachIndexed { i, v ->
            val on = i == active
            v.isSelected = on
            (v.layoutParams as LinearLayout.LayoutParams).weight = if (on) 2.2f else 1f
            v.background = if (on) android.graphics.drawable.GradientDrawable().apply { cornerRadius = 26 * dp; setColor(0xFFFFFFFF.toInt()) } else null
            val tint = if (on) 0xFF000000.toInt() else 0x99FFFFFF.toInt()
            ((v as LinearLayout).getChildAt(0) as ImageView).imageTintList = android.content.res.ColorStateList.valueOf(tint)
            (v.getChildAt(1) as TextView).apply { setTextColor(tint); visibility = if (on) View.VISIBLE else View.GONE }
            v.requestLayout()
        }
    }

    private fun paintChips() {
        val dp = resources.displayMetrics.density
        chipViews.forEachIndexed { i, c ->
            val on = i == homePage
            c.background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 16 * dp
                if (on) setColor(0xFFFFFFFF.toInt()) else { setColor(0x1FFFFFFF); setStroke((1 * dp).toInt(), 0x33FFFFFF) }
            }
            c.setTextColor(if (on) 0xFF000000.toInt() else 0xE6FFFFFF.toInt())
        }
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
    /** Downloads is a bottom-bar tab: close any screen on top and show it. */
    fun openDownloads() = showTab(DOWNLOADS)

    fun openCalendar() = showTab(CALENDAR)

    private fun showTab(page: Int) {
        if (supportFragmentManager.backStackEntryCount > 0)
            supportFragmentManager.popBackStackImmediate(null, androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE)
        switchTab(page)
    }

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
            current != HOME -> switchTab(if (current in 1..3) HOME else homePage)
            else -> @Suppress("DEPRECATION") super.onBackPressed()
        }
    }
}
