package com.anees.novamobile.ui.common

import android.content.Context
import com.anees.novamobile.MainActivity
import com.anees.novamobile.data.local.LocalStore
import com.anees.novamobile.data.model.MediaCard
import com.anees.novamobile.data.model.MediaType
import com.anees.novamobile.ui.player.PlayerActivity

/** Long-press (hold OK) menu for any card. [onChanged] lets the caller refresh its rows. */
object QuickActions {
    fun show(ctx: Context, card: MediaCard, onChanged: () -> Unit = {}) {
        val inList = LocalStore.inWatchlist(card)
        val hist = LocalStore.historyFor(card)
        val items = mutableListOf<ActionSheet.Item>()
        if (hist != null) {
            val where = if (card.type == MediaType.MOVIE) "" else "  S${hist.season} · E${hist.episode}"
            items += ActionSheet.Item("▶  Resume$where") { PlayerActivity.launch(ctx, card, hist.season, hist.episode) }
        }
        items += ActionSheet.Item("Details") { (ctx as? MainActivity)?.openDetail(card) }
        items += ActionSheet.Item(if (inList) "Remove from My List" else "Add to My List") { LocalStore.toggleWatchlist(card); onChanged() }
        if (hist != null) items += ActionSheet.Item("Remove from Continue Watching") { LocalStore.removeHistory(hist.key); onChanged() }
        ActionSheet.show(ctx, card.title, items)
    }
}
