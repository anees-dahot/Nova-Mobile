package com.anees.novamobile.ui.downloads

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import com.anees.novamobile.MainActivity
import com.anees.novamobile.data.model.MediaType
import com.anees.novamobile.download.DownloadMeta
import com.anees.novamobile.download.PublicExport
import com.anees.novamobile.ui.common.ActionSheet

/**
 * "Save to phone": copies finished downloads out of Nova as MP4 files that play in any app and stay
 * after uninstall. Every audio language of the download is muxed into that one file (MP4 supports
 * several audio tracks), so there is no language to pick:
 *  1. Where: the gallery (Movies/Nova), the folder used last time, or any folder you choose
 *  2. The same request can carry one episode or a whole season/bulk selection.
 */
@UnstableApi
object SaveToPhone {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("public_export", Context.MODE_PRIVATE)

    fun show(act: MainActivity, downloads: List<Download>) {
        val done = downloads.filter { it.state == Download.STATE_COMPLETED }.distinctBy { it.request.id }
        if (done.isEmpty()) { act.showBanner("Finish downloading first"); return }
        val metas = done.mapNotNull { DownloadMeta.of(it) }
        val what = when {
            done.size == 1 -> metas.firstOrNull()?.let { com.anees.novamobile.download.DownloadNotifier.name(it) } ?: "Video"
            metas.all { it.card.type != MediaType.MOVIE && it.season > 0 } -> "${done.size} episodes"
            else -> "${done.size} videos"
        }
        where(act, done, what)
    }

    private fun where(act: MainActivity, done: List<Download>, what: String) {
        val last = prefs(act).getString("tree", null)?.let { Uri.parse(it) }
            ?.takeIf { u -> act.contentResolver.persistedUriPermissions.any { it.uri == u && it.isWritePermission } }
        val items = mutableListOf(ActionSheet.Item("Save to", header = true),
            ActionSheet.Item("Gallery  ·  Movies/Nova") { start(act, done, null) })
        if (last != null) items += ActionSheet.Item("Last folder  ·  ${folderName(last)}") { start(act, done, last) }
        items += ActionSheet.Item("Choose a folder…") {
            act.pickFolder { uri -> prefs(act).edit().putString("tree", uri.toString()).apply(); start(act, done, uri) }
        }
        val langs = done.flatMap { d -> DownloadMeta.of(d)?.audioLangs.orEmpty() }.filter { it.isNotBlank() }.distinct().size
        val audio = if (langs > 1) "  ·  all languages" else ""
        ActionSheet.show(act, "Save $what$audio", items,
            subtitle = "One MP4 with every audio language. Plays in any video app and stays after Nova is uninstalled.")
    }

    private fun start(act: MainActivity, done: List<Download>, tree: Uri?) {
        val dest = tree?.let { folderName(it) } ?: "Movies/Nova"
        if (done.size == 1) act.showBanner("Saving to $dest…")
        else act.showBanner("Saving 0 of ${done.size} to $dest…")
        var finished = 0; var failed = 0; var lastMsg = ""
        // Manual requests are queued FIFO, so episodes are saved in this order
        done.forEach { d ->
            PublicExport.save(act, d.request.id, tree) { ok, msg ->
                finished++
                if (!ok) { failed++; lastMsg = msg }
                when {
                    finished < done.size -> act.showBanner("Saving $finished of ${done.size} to $dest…")
                    failed == 0 -> act.showBanner(if (done.size == 1) "Saved to $dest" else "Saved ${done.size} to $dest")
                    failed == done.size -> act.showBanner(lastMsg)
                    else -> act.showBanner("Saved ${done.size - failed} of ${done.size} · $lastMsg")
                }
            }
        }
    }

    private fun folderName(tree: Uri) = runCatching { DocumentsContract.getTreeDocumentId(tree).substringAfter(':').ifBlank { "Phone storage" } }.getOrDefault("Folder")
}
