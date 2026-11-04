package com.anees.novamobile.ui.common

import android.content.Context
import android.content.res.Resources
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View

/** Design tokens shared by every screen built in code (same values as res/values/colors.xml). */
object Ui {
    const val ACCENT = 0xFF0A84FF.toInt()
    const val DANGER = 0xFFFF453A.toInt()
    const val SUCCESS = 0xFF30D158.toInt()
    const val WARNING = 0xFFFFD60A.toInt()
    const val TEXT = 0xFFFFFFFF.toInt()
    const val TEXT2 = 0x99FFFFFF.toInt()      // secondary
    const val TEXT3 = 0x66FFFFFF.toInt()      // tertiary / hints
    const val SURFACE = 0xFF1C1C1E.toInt()
    const val SURFACE2 = 0xFF2C2C2E.toInt()
    const val HAIRLINE = 0x33FFFFFF
    const val SCRIM = 0xD9000000.toInt()

    /** Animations follow the system "animation scale" (0 = reduce motion). */
    fun motion(ctx: Context): Float =
        android.provider.Settings.Global.getFloat(ctx.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f)

    /** Apple-style press feedback: shrink on touch-down, spring back on release. Doesn't consume the touch. */
    fun pressable(v: View, scale: Float = 0.97f) {
        v.setOnTouchListener { x, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> x.animate().scaleX(scale).scaleY(scale).setDuration(90).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> x.animate().scaleX(1f).scaleY(1f).setDuration(140).start()
            }
            false
        }
    }

    /** "▶ Play" with a real icon inline (stays next to the text in wide, centred buttons). */
    fun iconText(ctx: Context, icon: Int, text: String, color: Int, sizeDp: Int = 18): CharSequence {
        val d = ctx.getDrawable(icon)?.mutate() ?: return text
        d.setTint(color)
        d.setBounds(0, 0, sizeDp.dp, sizeDp.dp)
        return android.text.SpannableString("  $text").apply {
            setSpan(android.text.style.ImageSpan(d, android.text.style.DynamicDrawableSpan.ALIGN_CENTER), 0, 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    fun tick(v: View) { v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) }
    fun confirm(v: View) { v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY) }
}

val Int.dp: Int get() = (this * Resources.getSystem().displayMetrics.density).toInt()
val Float.dp: Float get() = this * Resources.getSystem().displayMetrics.density
