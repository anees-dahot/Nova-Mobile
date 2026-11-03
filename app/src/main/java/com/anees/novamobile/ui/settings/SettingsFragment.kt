package com.anees.novamobile.ui.settings

import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.anees.novamobile.BuildConfig
import com.anees.novamobile.R
import com.anees.novamobile.data.local.LocalStore
import com.anees.novamobile.data.local.Settings
import com.anees.novamobile.ui.common.ActionSheet
import com.anees.novamobile.ui.common.TabPage

/** Apple TV Settings look: a centred list, label on the left, value on the right. */
class SettingsFragment : Fragment(), TabPage {

    private lateinit var list: LinearLayout

    override fun onCreateView(inflater: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        val ctx = requireContext()
        list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        return ScrollView(ctx).apply {
            setBackgroundColor(0xFF0B0B0D.toInt())
            isVerticalScrollBarEnabled = false
            val dp = resources.displayMetrics.density
            setPadding(0, (56 * dp).toInt(), 0, (40 * dp).toInt())
            clipToPadding = false
            addView(LinearLayout(ctx).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding((16 * dp).toInt(), 0, (16 * dp).toInt(), 0)
                addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            })
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) = render()

    private fun render() {
        list.removeAllViews()
        list.addView(TextView(requireContext()).apply {
            text = "‹  Settings"; textSize = 22f; setTextColor(0xFFFFFFFF.toInt())
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(0, 0, 0, (8 * resources.displayMetrics.density).toInt())
            setOnClickListener { parentFragmentManager.popBackStack() }
        })
        val s = LocalStore.settings()
        val langs = listOf("hi" to "Hindi", "en" to "English", "ur" to "Urdu", "orig" to "Original")
        header("Playback")
        row("Audio Language", langs.first { it.first == s.audioLang }.second) {
            pick("Audio Language", langs.map { it.second }, langs.indexOfFirst { it.first == s.audioLang }) { i -> save(s.copy(audioLang = langs[i].first)) }
        }
        row("Subtitles", if (s.subtitlesOn) "On" else "Off") { save(s.copy(subtitlesOn = !s.subtitlesOn)) }
        val sizes = listOf("Small", "Medium", "Large")
        row("Subtitle Size", sizes[s.subtitleSize]) { pick("Subtitle Size", sizes, s.subtitleSize) { save(s.copy(subtitleSize = it)) } }
        val boosts = listOf("Off", "+6 dB", "+10 dB", "+15 dB")
        row("Volume Boost", boosts[s.volumeBoost]) { pick("Volume Boost", boosts, s.volumeBoost) { save(s.copy(volumeBoost = it)) } }
        row("Autoplay Next Episode", if (s.autoplayNext) "On" else "Off") { save(s.copy(autoplayNext = !s.autoplayNext)) }
        header("Video")
        row("Low-CPU Playback", if (s.lowCpuMode) "On" else "Off") { save(s.copy(lowCpuMode = !s.lowCpuMode)) }
        note("Low-CPU Playback uses the phone's video chip directly (smoother, less battery) but some streams may not start — turn it off if that happens.")
        header("Accounts")
        com.anees.novamobile.sync.Sync.services.forEach { svc ->
            val state = when { !svc.configured -> "Not set up"; svc.loggedIn() -> "Connected"; else -> "Connect" }
            row(svc.name, state) { account(svc) }
        }
        if (com.anees.novamobile.sync.Sync.services.any { it.loggedIn() }) {
            val last = LocalStore.lastSync().takeIf { it > 0 }?.let { android.text.format.DateUtils.getRelativeTimeSpanString(it).toString() } ?: "never"
            row("Sync Now", "Last: $last") {
                android.widget.Toast.makeText(requireContext(), "Syncing…", android.widget.Toast.LENGTH_SHORT).show()
                com.anees.novamobile.sync.Sync.syncNow(requireContext()) { if (isAdded) render() }
            }
        }
        header("Downloads")
        row("Downloads", "") { (activity as? com.anees.novamobile.MainActivity)?.openDownloads() }
        val qualities = listOf("480p (smaller)", "720p", "1080p (best)")
        row("Download Quality", qualities[s.dlQuality].substringBefore(" ")) { pick("Download Quality", qualities, s.dlQuality) { saveDl(s.copy(dlQuality = it)) } }
        row("Wi-Fi Only", if (s.dlWifiOnly) "On" else "Off") { saveDl(s.copy(dlWifiOnly = !s.dlWifiOnly)) }
        row("Downloads at Once", s.dlParallel.toString()) { pick("Downloads at Once", listOf("1", "2", "3"), s.dlParallel - 1) { saveDl(s.copy(dlParallel = it + 1)) } }
        row("Delete After Watching", if (s.dlAutoDeleteWatched) "On" else "Off") { save(s.copy(dlAutoDeleteWatched = !s.dlAutoDeleteWatched)) }
        header("Streaming")
        row("Sources", "${com.anees.novamobile.ui.player.StreamExtractor.PROVIDERS.size} active") { showSources() }
        header("Library")
        row("Clear Watch History", "") {
            ActionSheet.show(requireContext(), "Clear watch history?", listOf(
                ActionSheet.Item("Clear") { LocalStore.clearHistory(); Toast.makeText(requireContext(), "History cleared", Toast.LENGTH_SHORT).show() },
                ActionSheet.Item("Cancel")
            ))
        }
        header("About")
        row("Version", BuildConfig.VERSION_NAME) {}
        row("Check for Updates", "") { com.anees.novamobile.update.Updater.check(requireActivity(), viewLifecycleOwner.lifecycleScope, manual = true) }
        row("Content", "Family-safe (18+ removed)") {}
    }

    private fun account(svc: com.anees.novamobile.sync.SyncService) {
        val ctx = requireContext()
        when {
            !svc.configured -> ActionSheet.show(ctx, svc.name, listOf(ActionSheet.Item("OK")),
                subtitle = "Add this service's client ID to local.properties and rebuild (see README)")
            svc.loggedIn() -> ActionSheet.show(ctx, svc.name, listOf(
                ActionSheet.Item("Disconnect") { svc.logout(); render() }, ActionSheet.Item("Cancel")))
            svc === com.anees.novamobile.sync.TraktSync -> {
                var sheet: android.app.Dialog? = null
                com.anees.novamobile.sync.TraktSync.startDeviceLogin(
                    show = { code, url ->
                        sheet = ActionSheet.show(ctx, "Connect Trakt", listOf(ActionSheet.Item("Open $url") {
                            startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)))
                        }), subtitle = "Go to $url and enter:   $code")
                    },
                    done = { ok ->
                        sheet?.dismiss()
                        android.widget.Toast.makeText(ctx, if (ok) "Trakt connected" else "Trakt login didn't finish", android.widget.Toast.LENGTH_SHORT).show()
                        if (ok) com.anees.novamobile.sync.Sync.syncNow(ctx) { if (isAdded) render() } else if (isAdded) render()
                    })
            }
            else -> svc.login(requireActivity())
        }
    }

    /** Each source: health score and last search time; plus a live test on a known film. */
    private fun showSources() {
        val ctx = requireContext()
        val items = com.anees.novamobile.ui.player.StreamExtractor.ordered().map { p ->
            val ms = LocalStore.providerSpeed(p.name)
            val score = LocalStore.providerScore(p.name)
            val health = when { score >= 10 -> "●"; score >= 0 -> "◐"; else -> "○" }
            ActionSheet.Item("$health  ${p.name}${if (p.multiAudio) "  · multi-audio" else ""}${if (ms > 0) "  · ${"%.1f".format(ms / 1000f)}s" else ""}")
        } + ActionSheet.Item("▶  Test all sources now") { testSources() }
        ActionSheet.show(ctx, "Sources", items, subtitle = "● working  ◐ unsure  ○ failing   ·   list updates from GitHub")
    }

    private fun testSources() {
        val host = com.anees.novamobile.ui.player.Prefetcher.host ?: return
        val ctx = requireContext()
        val target = com.anees.novamobile.ui.player.StreamExtractor.Target(27205, true, 0, 0)   // Inception
        android.widget.Toast.makeText(ctx, "Testing sources… (about a minute)", android.widget.Toast.LENGTH_SHORT).show()
        viewLifecycleOwner.lifecycleScope.launch {
            val results = mutableListOf<String>()
            for (p in com.anees.novamobile.ui.player.StreamExtractor.PROVIDERS) {
                val t0 = System.currentTimeMillis()
                val r = com.anees.novamobile.ui.player.WebStreamSniffer(ctx, host)
                    .sniff(com.anees.novamobile.ui.player.StreamExtractor.embedUrl(p, target), p.timeoutMs)
                val ms = System.currentTimeMillis() - t0
                LocalStore.reportProvider(p.name, r != null)
                if (r != null) LocalStore.setProviderSpeed(p.name, ms)
                results += (if (r != null) "✓  ${p.name}  ${"%.1f".format(ms / 1000f)}s" else "✗  ${p.name}")
            }
            if (isAdded) ActionSheet.show(requireContext(), "Test results", results.map { ActionSheet.Item(it) })
        }
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun saveDl(s: Settings) {
        save(s)
        com.anees.novamobile.download.NovaDownloads.applySettings(requireContext())
    }

    private fun save(s: Settings) {
        val focused = list.indexOfChild(list.focusedChild)
        LocalStore.saveSettings(s)
        render()
        list.getChildAt(focused)?.requestFocus()
    }

    private fun pick(title: String, options: List<String>, selected: Int, onPick: (Int) -> Unit) {
        val focused = list.indexOfChild(list.focusedChild)
        ActionSheet.show(requireContext(), title, options.mapIndexed { i, o -> ActionSheet.Item(o, checked = i == selected) { onPick(i) } }) {
            list.getChildAt(focused)?.requestFocus()
        }
    }

    private fun header(text: String) {
        val dp = resources.displayMetrics.density
        list.addView(TextView(requireContext()).apply {
            this.text = text.uppercase()
            textSize = 11f
            letterSpacing = 0.08f
            setTextColor(0x80FFFFFF.toInt())
            setPadding((16 * dp).toInt(), (22 * dp).toInt(), 0, (6 * dp).toInt())
        })
    }

    private fun note(text: String) {
        val dp = resources.displayMetrics.density
        list.addView(TextView(requireContext()).apply {
            this.text = text
            textSize = 12f
            setTextColor(0x66FFFFFF)
            setPadding((16 * dp).toInt(), (8 * dp).toInt(), (16 * dp).toInt(), 0)
        })
    }

    private fun row(label: String, value: String, onClick: () -> Unit) {
        val ctx = requireContext()
        val dp = resources.displayMetrics.density
        list.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((16 * dp).toInt(), 0, (16 * dp).toInt(), 0)
            setBackgroundResource(R.drawable.settings_row)
            isFocusable = true
            setOnClickListener { onClick() }
            foreground = ctx.getDrawable(android.R.drawable.list_selector_background)
            addView(TextView(ctx).apply {
                text = label; textSize = 15f; isDuplicateParentStateEnabled = true
                setTextColor(ctx.getColorStateList(R.color.btn_overlay_text))
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(TextView(ctx).apply {
                text = value; textSize = 14f; isDuplicateParentStateEnabled = true
                setTextColor(ctx.getColorStateList(R.color.settings_value))
                typeface = Typeface.DEFAULT
            })
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (48 * dp).toInt()).apply { topMargin = (4 * dp).toInt() })
    }
}
