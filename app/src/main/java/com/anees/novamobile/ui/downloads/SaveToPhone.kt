package com.anees.novamobile.ui.downloads

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import com.anees.novamobile.MainActivity
import com.anees.novamobile.data.model.LangTags
import com.anees.novamobile.download.DownloadMeta
import com.anees.novamobile.download.PublicExport
import com.anees.novamobile.ui.common.ActionSheet

/**
 * "Save to phone": copies finished downloads out of Nova as MP4 files that play in any app and stay
 * after uninstall. Two quick steps in the same sheet style as the rest of the app:
 *  1. Audio: an MP4 copy holds one audio track, so pick the language (Nova itself keeps them all)
 *  2. Where: the gallery (Movies/Nova), the folder used last time, or any folder you choose
 */
@UnstableApi
object SaveToPhone {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("public_export", Context.MODE_PRIVATE)

    fun show(act: MainActivity, downloads: List<Download>) {
        val done = downloads.filter { it.state == Download.STATE_COMPLETED }
        if (done.isEmpty()) { act.showBanner("Finish downloading first"); return }
        val langs = done.flatMap { DownloadMeta.of(it)?.audioLangs.orEmpty() }.distinct()
        val what = if (done.size == 1) DownloadMeta.of(done[0])?.let { com.anees.novamobile.download.DownloadNotifier.name(it) } ?: "Video" else "${done.size} episodes"
        if (langs.size <= 1) { where(act, done, langs.firstOrNull(), what); return }
        ActionSheet.show(act, "Save $what", listOf(ActionSheet.Item("Audio language", header = true)) +
            langs.map { l -> ActionSheet.Item(LangTags.name(l) ?: l) { where(act, done, l, what) } },
            subtitle = "A saved MP4 carries one audio language. Every language stays available in Nova.")
    }

    private fun where(act: MainActivity, done: List<Download>, lang: String?, what: String) {
        val last = prefs(act).getString("tree", null)?.let { Uri.parse(it) }
            ?.takeIf { u -> act.contentResolver.persistedUriPermissions.any { it.uri == u && it.isWritePermission } }
        val items = mutableListOf(ActionSheet.Item("Save to", header = true),
            ActionSheet.Item("Gallery  ·  Movies/Nova") { start(act, done, lang, null) })
        if (last != null) items += ActionSheet.Item("Last folder  ·  ${folderName(last)}") { start(act, done, lang, last) }
        items += ActionSheet.Item("Choose a folder…") {
            act.pickFolder { uri -> prefs(act).edit().putString("tree", uri.toString()).apply(); start(act, done, lang, uri) }
        }
        val audio = lang?.let { LangTags.name(it) ?: it }?.let { "  ·  $it audio" } ?: ""
        ActionSheet.show(act, "Save $what$audio", items, subtitle = "Plays in any video app and stays after Nova is uninstalled.")
    }

    private fun start(act: MainActivity, done: List<Download>, lang: String?, tree: Uri?) {
        val dest = tree?.let { folderName(it) } ?: "Movies/Nova"
        act.showBanner(if (done.size == 1) "Saving to $dest…" else "Saving ${done.size} episodes to $dest…")
        var left = done.size; var failed = 0; var lastMsg = ""
        done.forEach { d ->
            PublicExport.save(act, d.request.id, lang, tree) { ok, msg ->
                left--; if (!ok) { failed++; lastMsg = msg }
                if (left == 0) act.showBanner(when {
                    failed == 0 -> if (done.size == 1) "Saved to $dest" else "Saved ${done.size} episodes to $dest"
                    failed == done.size -> lastMsg
                    else -> "Saved ${done.size - failed} of ${done.size} · $lastMsg"
                })
            }
        }
    }

    private fun folderName(tree: Uri) = runCatching { DocumentsContract.getTreeDocumentId(tree).substringAfter(':').ifBlank { "Phone storage" } }.getOrDefault("Folder")
}
