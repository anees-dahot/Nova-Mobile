package com.anees.novamobile.transfer

import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.format.Formatter
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import com.anees.novamobile.MainActivity
import com.anees.novamobile.download.DownloadMeta
import com.anees.novamobile.download.NovaDownloads
import com.anees.novamobile.ui.common.Ui
import com.anees.novamobile.ui.common.dp
import com.anees.novamobile.ui.downloads.DownloadUi
import com.google.gson.Gson
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.coroutines.launch

/** Screens for phone-to-phone transfer: Send (shows a QR) and Receive (scans it, then pick what to copy). */
@UnstableApi
object BeamUi {

    // ── Send ─────────────────────────────────────────────────────────────────

    fun send(a: AppCompatActivity) {
        val done = NovaDownloads.all(a).count { it.state == androidx.media3.exoplayer.offline.Download.STATE_COMPLETED }
        if (done == 0) { (a as? MainActivity)?.showBanner("Nothing downloaded to send yet"); return }
        if (BeamService.running) { sendDialog(a); return }
        BeamPermissions.ask(a) { _ ->
            if (BeamLink.peer != null) { (a as? MainActivity)?.showBanner("Finish receiving first"); return@ask }
            BeamService.start(a)
            sendDialog(a)
        }
    }

    private fun sendDialog(a: AppCompatActivity) {
        val ctx: Context = a
        val qr = ImageView(ctx).apply { background = rounded(Color.WHITE, 18f); setPadding(14.dp, 14.dp, 14.dp, 14.dp) }
        val spinner = ProgressBar(ctx)
        val status = text(ctx, "Getting ready…", 15f, Ui.TEXT, bold = true).apply { gravity = Gravity.CENTER }
        val detail = text(ctx, "", 13f, Ui.TEXT2).apply { gravity = Gravity.CENTER }
        val stop = button(ctx, "Stop sending", Ui.DANGER)
        val body = column(ctx).apply {
            addView(text(ctx, "Send to another phone", 22f, Ui.TEXT, bold = true).apply { gravity = Gravity.CENTER })
            addView(text(ctx, "On the other phone open Nova › Downloads › ⋯ › Receive from another phone, and scan this code. Keep both phones close.", 14f, Ui.TEXT2).apply {
                gravity = Gravity.CENTER; setPadding(0, 6.dp, 0, 18.dp); setLineSpacing(0f, 1.15f)
            })
            addView(android.widget.FrameLayout(ctx).apply {
                addView(spinner, android.widget.FrameLayout.LayoutParams(48.dp, 48.dp, Gravity.CENTER))
                addView(qr, android.widget.FrameLayout.LayoutParams(-1, -1))
            }, LinearLayout.LayoutParams(260.dp, 260.dp).apply { gravity = Gravity.CENTER_HORIZONTAL })
            addView(status, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 18.dp })
            addView(detail, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 4.dp })
            addView(stop, LinearLayout.LayoutParams(-1, 50.dp).apply { topMargin = 20.dp })
        }
        val d = sheet(a, body)
        qr.visibility = View.INVISIBLE
        val h = Handler(Looper.getMainLooper())
        var shown: String? = null
        val poll = object : Runnable {
            override fun run() {
                val s = BeamService.state
                when {
                    s.error != null -> { spinner.visibility = View.GONE; status.text = "Couldn't start"; status.setTextColor(Ui.DANGER); detail.text = s.error }
                    s.invite == null -> {}
                    else -> {
                        val code = s.invite.encode()
                        if (shown != code) { shown = code; qr.setImageBitmap(qrBitmap(code, 720)); qr.visibility = View.VISIBLE; spinner.visibility = View.GONE }
                        val how = when {
                            s.invite.hasDirect && s.band.isNotEmpty() -> "Direct link · ${s.band}"
                            s.invite.hasDirect -> "Direct link"
                            s.invite.hasHotspot -> "Over this phone's hotspot"
                            else -> "Same Wi-Fi or hotspot only"
                        }
                        if (!s.connected) { status.text = "Waiting for the other phone…"; status.setTextColor(Ui.TEXT); detail.text = how }
                        else {
                            status.setTextColor(Ui.SUCCESS)
                            status.text = if (s.speed > 0) "Sending · ${Formatter.formatShortFileSize(ctx, s.speed)}/s" else "Connected"
                            detail.text = "${Formatter.formatShortFileSize(ctx, s.sent)} sent · $how"
                        }
                    }
                }
                if (d.isShowing) h.postDelayed(this, 500)
            }
        }
        h.post(poll)
        stop.setOnClickListener { BeamService.stop(a); d.dismiss() }
        d.setOnCancelListener {
            // Leaving mid-transfer keeps it going in the background (notification); otherwise stop
            if (BeamService.state.connected && BeamService.state.speed > 0) (a as? MainActivity)?.showBanner("Still sending in the background", "Stop") { BeamService.stop(a) }
            else BeamService.stop(a)
        }
        d.show()
    }

    // ── Receive ──────────────────────────────────────────────────────────────

    fun receive(a: AppCompatActivity) {
        if (BeamLink.peer != null) { (a as? MainActivity)?.showBanner("Already receiving"); return }
        if (BeamService.running) { (a as? MainActivity)?.showBanner("Stop sending first"); return }
        BeamPermissions.ask(a) { _ ->
            val opts = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
            GmsBarcodeScanning.getClient(a, opts).startScan()
                .addOnSuccessListener { code -> code.rawValue?.let { receiveText(a, it) } }
                .addOnFailureListener { e -> (a as? MainActivity)?.showBanner("Couldn't open the scanner: ${e.message ?: "Google Play services needed"}") }
        }
    }

    /** From a scanned (or, in debug builds, pasted) invite. */
    fun receiveText(a: AppCompatActivity, text: String) {
        val inv = Beam.Invite.decode(text) ?: run { (a as? MainActivity)?.showBanner("That's not a Nova transfer code"); return }
        val ctx: Context = a
        val status = text(ctx, "Looking for the other phone…", 15f, Ui.TEXT, bold = true).apply { gravity = Gravity.CENTER }
        val body = column(ctx).apply {
            addView(ProgressBar(ctx), LinearLayout.LayoutParams(44.dp, 44.dp).apply { gravity = Gravity.CENTER_HORIZONTAL })
            addView(status, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 14.dp })
        }
        val wait = sheet(a, body)
        var cancelled = false
        wait.setOnCancelListener { cancelled = true }
        wait.show()
        a.lifecycleScope.launch {
            try {
                val p = BeamLink.connect(a.applicationContext, inv) { s -> a.runOnUiThread { status.text = s } }
                status.text = "Getting the list…"
                val offers = BeamLink.list(p)
                if (cancelled) { if (p.direct) BeamWifi.teardown(); return@launch }
                wait.dismiss()
                pick(a, p, offers)
            } catch (e: Exception) {
                if (!cancelled) { status.text = e.message ?: "Couldn't connect"; status.setTextColor(Ui.DANGER) }
            }
        }
    }

    private fun pick(a: AppCompatActivity, p: BeamLink.Peer, offers: List<Beam.Offer>) {
        val ctx: Context = a
        val gson = Gson()
        val have = NovaDownloads.all(a).filter { it.state == androidx.media3.exoplayer.offline.Download.STATE_COMPLETED }.map { it.request.id }.toSet()
        val rows = offers.sortedBy { runCatching { gson.fromJson(it.data, DownloadMeta::class.java) }.getOrNull()?.let { m -> "${m.card.title} ${"%03d".format(m.season)} ${"%04d".format(m.episode)}" } ?: it.id }
        val chosen = rows.filter { it.id !in have }.map { it.id }.toMutableSet()
        val go = button(ctx, "", Ui.ACCENT)
        fun refresh() {
            val bytes = rows.filter { it.id in chosen }.sumOf { it.bytes }
            go.text = if (chosen.isEmpty()) "Pick something to receive" else "Receive ${chosen.size} · ${Formatter.formatShortFileSize(ctx, bytes)}"
            go.isEnabled = chosen.isNotEmpty(); go.alpha = if (chosen.isEmpty()) 0.4f else 1f
        }
        val list = column(ctx).apply { setPadding(0, 0, 0, 0) }
        rows.forEach { o ->
            val m = runCatching { gson.fromJson(o.data, DownloadMeta::class.java) }.getOrNull()
            val owned = o.id in have
            val box = CheckBox(ctx).apply { isChecked = o.id in chosen; isEnabled = !owned; buttonTintList = android.content.res.ColorStateList.valueOf(Ui.ACCENT); isClickable = false }
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, 8.dp, 0, 8.dp)
                addView(box)
                addView(column(ctx).apply {
                    setPadding(8.dp, 0, 0, 0)
                    addView(text(ctx, m?.let { DownloadUi.name(it) } ?: o.id, 15f, Ui.TEXT).apply { maxLines = 2 })
                    addView(text(ctx, if (owned) "Already on this phone" else Formatter.formatShortFileSize(ctx, o.bytes), 13f, Ui.TEXT2))
                }, LinearLayout.LayoutParams(0, -2, 1f))
                alpha = if (owned) 0.5f else 1f
                if (!owned) setOnClickListener { if (!chosen.remove(o.id)) chosen += o.id; box.isChecked = o.id in chosen; Ui.tick(this); refresh() }
            }
            list.addView(row)
        }
        val body = column(ctx).apply {
            addView(text(ctx, "Receive from the other phone", 20f, Ui.TEXT, bold = true))
            addView(text(ctx, "${rows.size} download${if (rows.size == 1) "" else "s"} available · ${if (p.direct) "direct link" else "same Wi-Fi"}", 13f, Ui.TEXT2).apply { setPadding(0, 4.dp, 0, 10.dp) })
            addView(ScrollView(ctx).apply { addView(list) }, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(go, LinearLayout.LayoutParams(-1, 50.dp).apply { topMargin = 12.dp })
        }
        val d = sheet(a, body, tall = true)
        var started = false
        d.setOnDismissListener { if (!started) BeamLink.abandon(a.applicationContext, p) }
        refresh()
        go.setOnClickListener {
            val todo = rows.filter { it.id in chosen }
            started = true
            d.dismiss()
            val n = runCatching { BeamLink.receive(a.applicationContext, p, todo) }.getOrElse { e ->
                (a as? MainActivity)?.showBanner("Couldn't start: ${e.message}"); BeamLink.finish(a.applicationContext); -1
            }
            when {
                n > 0 -> { a.getSharedPreferences("nova", 0).edit().putBoolean("dl_card_open", true).apply(); progressDialog(a) }
                n == 0 -> { BeamLink.abandon(a.applicationContext, p); (a as? MainActivity)?.showBanner("Nothing new to receive") }
            }
        }
        d.show()
    }

    /** Live copy progress: speed, how much is left, which item. Closing it leaves the copy running. */
    private fun progressDialog(a: AppCompatActivity) {
        val ctx: Context = a
        val speed = text(ctx, "Starting…", 30f, Ui.TEXT, bold = true).apply { gravity = Gravity.CENTER }
        val bar = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000; progressDrawable = ctx.getDrawable(com.anees.novamobile.R.drawable.accent_track) }
        val amount = text(ctx, "", 14f, Ui.TEXT2).apply { gravity = Gravity.CENTER }
        val item = text(ctx, "", 14f, Ui.TEXT).apply { gravity = Gravity.CENTER; maxLines = 2 }
        val close = button(ctx, "Hide", Ui.SURFACE2)
        val cancel = button(ctx, "Cancel", Ui.DANGER)
        val body = column(ctx).apply {
            addView(text(ctx, "Receiving", 20f, Ui.TEXT, bold = true).apply { gravity = Gravity.CENTER })
            addView(speed, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 14.dp })
            addView(bar, LinearLayout.LayoutParams(-1, 6.dp).apply { topMargin = 14.dp })
            addView(amount, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 8.dp })
            addView(item, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 10.dp })
            addView(LinearLayout(ctx).apply {
                addView(cancel, LinearLayout.LayoutParams(0, 48.dp, 1f).apply { marginEnd = 10.dp })
                addView(close, LinearLayout.LayoutParams(0, 48.dp, 1f))
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 20.dp })
        }
        val d = sheet(a, body)
        val h = Handler(Looper.getMainLooper())
        var last = 0L; var lastAt = System.currentTimeMillis(); var smooth = 0.0
        val poll = object : Runnable {
            override fun run() {
                val p = BeamLink.progress
                val got = BeamLink.peer?.bytes?.get() ?: p.done
                val now = System.currentTimeMillis()
                val inst = (got - last) * 1000.0 / (now - lastAt).coerceAtLeast(1)
                smooth = if (smooth == 0.0) inst else smooth * 0.5 + inst * 0.5
                last = got; lastAt = now
                if (p.finished) {
                    speed.text = if (p.failed == 0) "Done" else "Done · ${p.failed} not copied"
                    speed.setTextColor(if (p.failed == 0) Ui.SUCCESS else Ui.WARNING)
                    bar.progress = 1000
                    amount.text = "${Formatter.formatShortFileSize(ctx, got)} received"
                    item.text = "${p.ok} download${if (p.ok == 1) "" else "s"} ready to watch"
                    cancel.visibility = View.GONE; close.text = "Close"
                    return
                }
                speed.text = if (smooth > 0) "${Formatter.formatShortFileSize(ctx, smooth.toLong())}/s" else "Starting…"
                bar.progress = if (p.total > 0) (got * 1000 / p.total).toInt().coerceIn(0, 1000) else 0
                val left = (p.total - got).coerceAtLeast(0)
                val eta = if (smooth > 1) " · ${(left / smooth).toLong().let { if (it >= 60) "${it / 60} min" else "$it s" }} left" else ""
                amount.text = "${Formatter.formatShortFileSize(ctx, got)} of ${Formatter.formatShortFileSize(ctx, p.total)}$eta · ${p.how}"
                item.text = if (p.count > 1) "${p.index} of ${p.count} · ${p.title}" else p.title
                if (d.isShowing) h.postDelayed(this, 500)
            }
        }
        close.setOnClickListener { d.dismiss() }
        cancel.setOnClickListener {
            BeamLink.cancel(a.applicationContext)
            d.dismiss()
            (a as? MainActivity)?.showBanner("Transfer cancelled")
        }
        d.setOnShowListener { h.post(poll) }
        d.show()
    }

    // ── Bits ─────────────────────────────────────────────────────────────────

    private fun qrBitmap(text: String, size: Int): Bitmap {
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size,
            mapOf(EncodeHintType.MARGIN to 0, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M))
        val px = IntArray(size * size) { i -> if (m.get(i % size, i / size)) Color.BLACK else Color.WHITE }
        return Bitmap.createBitmap(px, size, size, Bitmap.Config.RGB_565)
    }

    private fun sheet(a: AppCompatActivity, body: View, tall: Boolean = false): Dialog = Dialog(a).apply {
        val card = android.widget.FrameLayout(a).apply {
            background = rounded(Ui.SURFACE, 22f)
            setPadding(22.dp, 24.dp, 22.dp, 22.dp)
            addView(body)
        }
        setContentView(card)
        window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window?.setLayout((a.resources.displayMetrics.widthPixels * 0.92f).toInt(), if (tall) (a.resources.displayMetrics.heightPixels * 0.75f).toInt() else ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun column(ctx: Context) = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

    private fun text(ctx: Context, s: String, size: Float, color: Int, bold: Boolean = false) = TextView(ctx).apply {
        text = s; textSize = size; setTextColor(color)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    private fun button(ctx: Context, s: String, color: Int) = TextView(ctx).apply {
        text = s; textSize = 15f; gravity = Gravity.CENTER; setTextColor(if (color == Ui.DANGER) Ui.DANGER else Color.WHITE)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        background = rounded(if (color == Ui.DANGER) 0x33FF453A else color, 14f)
        Ui.pressable(this)
    }

    private fun rounded(color: Int, r: Float) = GradientDrawable().apply { setColor(color); cornerRadius = r.dp }
}
