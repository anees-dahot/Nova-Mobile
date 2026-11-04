package com.anees.novamobile.ui.downloads

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat
import com.anees.novamobile.R
import com.anees.novamobile.ui.common.Ui

/**
 * iOS-style download button: track + progress arc with an icon in the middle.
 * IDLE ↓ · QUEUED spinning arc · DOWNLOADING pause bars · PAUSED ▶ · FAILED ↻ (red) · DONE ✓ (filled).
 */
class ProgressRing @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {
    enum class State { IDLE, QUEUED, DOWNLOADING, PAUSED, FAILED, DONE }

    private val dp = resources.displayMetrics.density
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2.5f * dp; color = 0x33FFFFFF }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2.5f * dp; strokeCap = Paint.Cap.ROUND }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val box = RectF()
    private var shown = 0f
    private var spin = 0f
    private var spinner: ValueAnimator? = null
    private var progressAnim: ValueAnimator? = null
    private val icons = HashMap<Int, Drawable>()

    var progress = 0f          // 0..1
        set(v) {
            val nv = v.coerceIn(0f, 1f)
            if (nv == field) return
            field = nv; animateTo()
        }

    var state = State.IDLE
        set(v) {
            if (v == field) return
            field = v
            if (v == State.QUEUED) startSpin() else stopSpin()
            contentDescription = when (v) {
                State.IDLE -> "Download"; State.QUEUED -> "Waiting"; State.DOWNLOADING -> "Pause"
                State.PAUSED -> "Resume"; State.FAILED -> "Retry"; State.DONE -> "Downloaded"
            }
            invalidate()
        }

    private fun animateTo() {
        // Ease the arc toward the new value (no jumps between 1s progress updates)
        progressAnim?.cancel()
        progressAnim = ValueAnimator.ofFloat(shown, progress).apply {
            duration = 450
            interpolator = DecelerateInterpolator()
            addUpdateListener { shown = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    private fun startSpin() {
        if (spinner != null) return
        spinner = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 1000; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
            addUpdateListener { spin = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    private fun stopSpin() { spinner?.cancel(); spinner = null }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); if (state == State.QUEUED) startSpin() }
    override fun onDetachedFromWindow() { stopSpin(); progressAnim?.cancel(); super.onDetachedFromWindow() }

    private fun icon(res: Int) = icons.getOrPut(res) { ContextCompat.getDrawable(context, res)!!.mutate() }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = minOf(width - paddingLeft - paddingRight, height - paddingTop - paddingBottom) / 2f - 1.5f * dp
        box.set(cx - r, cy - r, cx + r, cy + r)
        arc.color = if (state == State.FAILED) Ui.DANGER else Ui.ACCENT
        when (state) {
            State.DONE -> { fill.color = Ui.ACCENT; c.drawCircle(cx, cy, r + 1.5f * dp, fill) }
            State.IDLE -> c.drawOval(box, track)
            State.QUEUED -> { c.drawOval(box, track); c.drawArc(box, spin - 90f, 80f, false, arc) }
            else -> { c.drawOval(box, track); if (shown > 0f) c.drawArc(box, -90f, 360f * shown, false, arc) }
        }
        val s = r * 0.55f
        when (state) {
            State.DOWNLOADING -> {   // pause bars
                fill.color = Ui.TEXT
                val w = s * 0.32f
                c.drawRoundRect(cx - s * 0.55f, cy - s * 0.6f, cx - s * 0.55f + w, cy + s * 0.6f, dp, dp, fill)
                c.drawRoundRect(cx + s * 0.55f - w, cy - s * 0.6f, cx + s * 0.55f, cy + s * 0.6f, dp, dp, fill)
            }
            State.QUEUED -> {}
            else -> {
                val res = when (state) {
                    State.IDLE -> R.drawable.ic_download
                    State.PAUSED -> R.drawable.ic_play
                    State.FAILED -> R.drawable.ic_retry
                    else -> R.drawable.ic_check
                }
                val d = icon(res)
                d.setTint(when (state) { State.FAILED -> Ui.DANGER; State.DONE -> 0xFFFFFFFF.toInt(); else -> Ui.TEXT })
                val half = (if (state == State.IDLE) s * 1.05f else s).toInt()
                d.setBounds((cx - half).toInt(), (cy - half).toInt(), (cx + half).toInt(), (cy + half).toInt())
                d.draw(c)
            }
        }
    }
}
