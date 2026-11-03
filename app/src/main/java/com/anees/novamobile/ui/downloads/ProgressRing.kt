package com.anees.novamobile.ui.downloads

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/** iOS-style download ring: track + arc, with a stop square (downloading) or arrow states drawn as text. */
class ProgressRing @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {
    private val dp = resources.displayMetrics.density
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2.5f * dp; color = 0x40FFFFFF }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2.5f * dp; color = 0xFFFFFFFF.toInt(); strokeCap = Paint.Cap.ROUND }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); textAlign = Paint.Align.CENTER; textSize = 13 * dp }
    private val box = RectF()
    private var shown = 0f
    var progress = 0f          // 0..1
        set(v) { field = v.coerceIn(0f, 1f); animateTo() }
    var glyph: String? = null  // null = stop square; "▶" resume; "↻" retry; "✓" done
        set(v) { field = v; invalidate() }

    private fun animateTo() {
        // Ease the arc toward the new value (no jumps between 1s progress updates)
        val from = shown
        animate().cancel()
        android.animation.ValueAnimator.ofFloat(from, progress).apply {
            duration = 450
            interpolator = android.view.animation.DecelerateInterpolator()
            addUpdateListener { shown = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onDraw(c: Canvas) {
        val r = minOf(width, height) / 2f - 2 * dp
        box.set(width / 2f - r, height / 2f - r, width / 2f + r, height / 2f + r)
        c.drawOval(box, track)
        if (shown > 0f) c.drawArc(box, -90f, 360f * shown, false, arc)
        val g = glyph
        if (g == null) {
            val s = r * 0.42f
            c.drawRoundRect(width / 2f - s, height / 2f - s, width / 2f + s, height / 2f + s, 1.5f * dp, 1.5f * dp, fill)
        } else c.drawText(g, width / 2f, height / 2f - (text.descent() + text.ascent()) / 2, text)
    }
}
