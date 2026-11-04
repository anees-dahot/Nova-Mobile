package com.anees.novamobile.ui.common

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.anees.novamobile.R

/**
 * Bottom sheet menu (iOS action sheet / Material bottom sheet): dimmed screen, grabber, title,
 * grouped rows with a check on the selected one. Drag down or tap outside to dismiss.
 * Used for long-press actions, the player's menus and the resume prompt.
 */
object ActionSheet {

    data class Item(
        val label: String,
        val checked: Boolean = false,
        val header: Boolean = false,
        val action: () -> Unit = {}
    )

    fun show(ctx: Context, title: String?, items: List<Item>, subtitle: String? = null, onDismiss: () -> Unit = {}): Dialog {
        val metrics = ctx.resources.displayMetrics
        // Phones: full width; landscape / tablets: a centred 480dp column
        val sheetW = minOf(metrics.widthPixels, 480.dp)
        val maxH = (metrics.heightPixels * 0.85f).toInt()
        val dialog = Dialog(ctx, android.R.style.Theme_Translucent_NoTitleBar_Fullscreen)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val sheet = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Ui.SURFACE)
                cornerRadii = floatArrayOf(16f.dp, 16f.dp, 16f.dp, 16f.dp, 0f, 0f, 0f, 0f)
            }
            isClickable = true
            setPadding(0, 8.dp, 0, 12.dp)
        }
        // Grabber
        sheet.addView(View(ctx).apply {
            background = GradientDrawable().apply { setColor(0x4DFFFFFF); cornerRadius = 3f.dp }
        }, LinearLayout.LayoutParams(36.dp, 5.dp).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = 6.dp })
        title?.let {
            sheet.addView(TextView(ctx).apply {
                text = it
                textSize = 17f
                setTextColor(Ui.TEXT)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                gravity = Gravity.CENTER
                maxLines = 2
                setPadding(20.dp, 6.dp, 20.dp, if (subtitle == null) 10.dp else 2.dp)
            })
        }
        subtitle?.let {
            sheet.addView(TextView(ctx).apply {
                text = it
                textSize = 13f
                setTextColor(Ui.TEXT2)
                gravity = Gravity.CENTER
                setLineSpacing(0f, 1.15f)
                setPadding(24.dp, 0, 24.dp, 10.dp)
            })
        }

        val column = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        var checkedRow: View? = null
        items.forEachIndexed { i, item ->
            if (item.header) {
                column.addView(TextView(ctx).apply {
                    text = item.label.uppercase()
                    textSize = 12f
                    letterSpacing = 0.04f
                    setTextColor(Ui.TEXT3)
                    setPadding(20.dp, if (i == 0) 6.dp else 18.dp, 20.dp, 6.dp)
                })
                return@forEachIndexed
            }
            val destructive = item.label.startsWith("Delete") || item.label.startsWith("Remove") || item.label.startsWith("Cancel Download") || item.label == "Clear"
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = 50.dp
                setPadding(20.dp, 0, 16.dp, 0)
                foreground = android.util.TypedValue().let { tv ->
                    ctx.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true); ctx.getDrawable(tv.resourceId)
                }
                if (item.checked) setBackgroundColor(0x14FFFFFF)
                isFocusable = true
                setOnClickListener { Ui.tick(this); dialog.dismiss(); item.action() }
            }
            row.addView(TextView(ctx).apply {
                text = item.label
                textSize = 16f
                setTextColor(when { destructive -> Ui.DANGER; item.checked -> Ui.ACCENT; else -> Ui.TEXT })
                if (item.checked) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (item.checked) row.addView(ImageView(ctx).apply {
                setImageResource(R.drawable.ic_check)
                imageTintList = android.content.res.ColorStateList.valueOf(Ui.ACCENT)
            }, LinearLayout.LayoutParams(20.dp, 20.dp))
            column.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            if (item.checked && checkedRow == null) checkedRow = row
        }
        val scroll = ScrollView(ctx).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(column)
        }
        sheet.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val scrim = FrameLayout(ctx).apply {
            setBackgroundColor(0x99000000.toInt())
            setOnClickListener { dialog.dismiss() }
            addView(sheet, FrameLayout.LayoutParams(sheetW, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
        }
        // Cap the height: long menus scroll inside the sheet
        sheet.viewTreeObserver.addOnGlobalLayoutListener {
            if (sheet.height > maxH) {
                val over = sheet.height - maxH
                scroll.layoutParams = scroll.layoutParams.apply { height = (scroll.height - over).coerceAtLeast(120.dp) }
            }
        }
        // Drag the sheet down to dismiss
        var downY = 0f
        sheet.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downY = e.rawY; true }
                MotionEvent.ACTION_MOVE -> { v.translationY = (e.rawY - downY).coerceAtLeast(0f); true }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (v.translationY > v.height * 0.25f) dialog.dismiss() else v.animate().translationY(0f).setDuration(150).start()
                    true
                }
                else -> false
            }
        }

        dialog.setContentView(scrim)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
        }
        dialog.setOnDismissListener { onDismiss() }
        dialog.show()

        val motion = Ui.motion(ctx)
        scrim.alpha = 0f
        scrim.animate().alpha(1f).setDuration((160 * motion).toLong()).start()
        sheet.translationY = 400f.dp
        sheet.animate().translationY(0f).setDuration((240 * motion).toLong()).setInterpolator(android.view.animation.DecelerateInterpolator(2f)).start()
        checkedRow?.let { r -> scroll.post { scroll.scrollTo(0, (r.top - 80.dp).coerceAtLeast(0)) } }
        return dialog
    }
}
