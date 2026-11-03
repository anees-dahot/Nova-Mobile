package com.anees.novamobile.ui.common

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.anees.novamobile.R

/**
 * Apple TV style centred menu: dimmed screen, title, stacked pill buttons.
 * Used for long-press actions, the player's audio/subtitle menu and the resume prompt.
 */
object ActionSheet {

    data class Item(
        val label: String,
        val checked: Boolean = false,
        val header: Boolean = false,
        val action: () -> Unit = {}
    )

    fun show(ctx: Context, title: String?, items: List<Item>, subtitle: String? = null, onDismiss: () -> Unit = {}): Dialog {
        val dp = ctx.resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()
        // Phone-safe width: 380dp max, 32dp margins on narrow screens
        val sheetW = minOf(px(380), ctx.resources.displayMetrics.widthPixels - px(32))
        val dialog = Dialog(ctx, android.R.style.Theme_Translucent_NoTitleBar_Fullscreen)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val column = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        title?.let {
            column.addView(TextView(ctx).apply {
                text = it
                textSize = 22f
                setTextColor(Color.WHITE)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, px(if (subtitle == null) 18 else 4))
            })
        }
        subtitle?.let {
            column.addView(TextView(ctx).apply {
                text = it
                textSize = 13f
                setTextColor(0x99FFFFFF.toInt())
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, px(18))
            })
        }

        var firstFocus: View? = null
        var checkedFocus: View? = null
        items.forEach { item ->
            if (item.header) {
                column.addView(TextView(ctx).apply {
                    text = item.label
                    textSize = 11f
                    textSize = 13f
                    setTextColor(0x80FFFFFF.toInt())
                    setPadding(px(6), px(14), 0, px(6))
                }, LinearLayout.LayoutParams(sheetW, ViewGroup.LayoutParams.WRAP_CONTENT))
                return@forEach
            }
            val btn = TextView(ctx).apply {
                text = (if (item.checked) "✓   " else "") + item.label
                textSize = 15f
                gravity = Gravity.CENTER_VERTICAL
                setPadding(px(22), 0, px(22), 0)
                setTextColor(ctx.getColorStateList(R.color.btn_overlay_text))
                setBackgroundResource(R.drawable.btn_overlay)
                isFocusable = true
                setOnFocusChangeListener { v, has -> v.animate().scaleX(if (has) 1.04f else 1f).scaleY(if (has) 1.04f else 1f).setDuration(120).start() }
                setOnClickListener { dialog.dismiss(); item.action() }
            }
            column.addView(btn, LinearLayout.LayoutParams(sheetW, px(46)).apply { topMargin = px(8) })
            if (firstFocus == null) firstFocus = btn
            if (item.checked && checkedFocus == null) checkedFocus = btn
        }

        val scroll = ScrollView(ctx).apply {
            isVerticalScrollBarEnabled = false
            clipToPadding = false
            setPadding(0, px(40), 0, px(40))
            addView(column)
        }
        val root = FrameLayout(ctx).apply {
            setBackgroundColor(0xD9000000.toInt())
            addView(scroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        }
        dialog.setContentView(root)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.setOnDismissListener { onDismiss() }
        dialog.show()

        column.alpha = 0f
        column.scaleX = 0.96f; column.scaleY = 0.96f
        column.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(180).start()
        // Focus once the dialog is laid out, otherwise the highlight doesn't show
        (checkedFocus ?: firstFocus)?.let { v -> v.post { v.requestFocus() } }
        return dialog
    }
}
