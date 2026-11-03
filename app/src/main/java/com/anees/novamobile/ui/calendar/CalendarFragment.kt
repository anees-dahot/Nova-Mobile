package com.anees.novamobile.ui.calendar

import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.os.Bundle
import android.text.format.DateUtils
import android.view.*
import android.widget.*
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.anees.novamobile.MainActivity
import com.anees.novamobile.R
import com.anees.novamobile.calendar.AiringCalendar
import com.anees.novamobile.calendar.AiringEp
import com.anees.novamobile.data.local.LocalStore
import com.anees.novamobile.ui.player.PlayerActivity
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Airing calendar, Apple-style: large title, day sections (Yesterday, Today, Tomorrow, weekdays), aired = "Out now". */
class CalendarFragment : Fragment() {

    private sealed class Row { data class Day(val label: String) : Row(); data class Ep(val e: AiringEp) : Row() }
    private val rows = mutableListOf<Row>()
    private lateinit var status: TextView
    private lateinit var list: RecyclerView
    private val time = SimpleDateFormat("h:mm a", Locale.getDefault())
    private val dayFmt = SimpleDateFormat("EEEE, MMM d", Locale.getDefault())

    override fun onCreateView(inflater: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        val ctx = requireContext(); val dp = resources.displayMetrics.density
        val root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.BLACK); isClickable = true }
        root.addView(ImageButton(ctx).apply {
            setImageResource(R.drawable.ic_back); background = null
            setOnClickListener { parentFragmentManager.popBackStack() }
        }, LinearLayout.LayoutParams((52 * dp).toInt(), (52 * dp).toInt()).apply { topMargin = (8 * dp).toInt() })
        root.addView(TextView(ctx).apply {
            text = "Calendar"; textSize = 32f; setTextColor(Color.WHITE); letterSpacing = -0.02f
            typeface = Typeface.create("sans-serif", Typeface.BOLD); setPadding((16 * dp).toInt(), 0, 0, 0)
        })
        status = TextView(ctx).apply { textSize = 13f; setTextColor(0x99FFFFFF.toInt()); setPadding((16 * dp).toInt(), (2 * dp).toInt(), (16 * dp).toInt(), (8 * dp).toInt()) }
        root.addView(status)
        list = RecyclerView(ctx).apply { layoutManager = LinearLayoutManager(ctx); adapter = Adapter(); itemAnimator = null; clipToPadding = false; setPadding(0, 0, 0, (24 * dp).toInt()) }
        root.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        // Show the cached week instantly, then refresh
        show(LocalStore.airingCache(), loading = true)
        viewLifecycleOwner.lifecycleScope.launch {
            val eps = runCatching { AiringCalendar.load() }.getOrNull()
            if (eps != null) show(eps, loading = false) else status.text = "Couldn't refresh — showing saved schedule"
            context?.let { AiringCalendar.reschedule(it) }
        }
    }

    private fun show(eps: List<AiringEp>, loading: Boolean) {
        val followed = AiringCalendar.followed().size
        status.text = when {
            followed == 0 -> "Add series or anime to My List to see their new episodes here."
            loading && eps.isEmpty() -> "Loading schedule…"
            eps.isEmpty() -> "No new episodes in the next two weeks for your $followed shows."
            else -> "${eps.size} episodes · from your $followed shows"
        }
        rows.clear()
        eps.groupBy { dayLabel(it.airAt) }.forEach { (label, items) -> rows += Row.Day(label); items.forEach { rows += Row.Ep(it) } }
        list.adapter?.notifyDataSetChanged()
    }

    private fun dayLabel(at: Long): String = when {
        DateUtils.isToday(at) -> "Today"
        DateUtils.isToday(at - DateUtils.DAY_IN_MILLIS) -> "Yesterday"
        DateUtils.isToday(at + DateUtils.DAY_IN_MILLIS) -> "Tomorrow"
        else -> dayFmt.format(Date(at))
    }

    private inner class Adapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = rows.size
        override fun getItemViewType(p: Int) = if (rows[p] is Row.Day) 0 else 1

        override fun onCreateViewHolder(parent: ViewGroup, type: Int): RecyclerView.ViewHolder {
            val ctx = parent.context; val dp = ctx.resources.displayMetrics.density
            if (type == 0) return object : RecyclerView.ViewHolder(TextView(ctx).apply {
                textSize = 20f; setTextColor(Color.WHITE); typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                letterSpacing = -0.01f; setPadding((16 * dp).toInt(), (20 * dp).toInt(), (16 * dp).toInt(), (6 * dp).toInt())
            }) {}
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding((16 * dp).toInt(), (6 * dp).toInt(), (16 * dp).toInt(), (6 * dp).toInt())
                isClickable = true; foreground = ctx.getDrawable(android.R.drawable.list_selector_background)
                layoutParams = RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT)
            }
            row.addView(ImageView(ctx).apply {
                id = 1; scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundResource(R.drawable.skeleton)
                outlineProvider = object : ViewOutlineProvider() { override fun getOutline(v: View, o: Outline) = o.setRoundRect(0, 0, v.width, v.height, 6 * dp) }
                clipToOutline = true
            }, LinearLayout.LayoutParams((48 * dp).toInt(), (72 * dp).toInt()))
            row.addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL; setPadding((12 * dp).toInt(), 0, (8 * dp).toInt(), 0)
                addView(TextView(ctx).apply { id = 2; textSize = 15f; setTextColor(Color.WHITE); typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
                addView(TextView(ctx).apply { id = 3; textSize = 13f; setTextColor(0x99FFFFFF.toInt()); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
            }, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(TextView(ctx).apply { id = 4; textSize = 12f; gravity = Gravity.CENTER; setPadding((10 * dp).toInt(), (4 * dp).toInt(), (10 * dp).toInt(), (4 * dp).toInt()) })
            // Press-down feedback
            row.setOnTouchListener { v, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> v.animate().scaleX(0.98f).scaleY(0.98f).setDuration(90).start()
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.animate().scaleX(1f).scaleY(1f).setDuration(140).start()
                }
                false
            }
            return object : RecyclerView.ViewHolder(row) {}
        }

        override fun onBindViewHolder(h: RecyclerView.ViewHolder, p: Int) {
            when (val r = rows[p]) {
                is Row.Day -> (h.itemView as TextView).text = r.label
                is Row.Ep -> {
                    val v = h.itemView; val e = r.e
                    val aired = e.airAt <= System.currentTimeMillis()
                    v.findViewById<ImageView>(1).load(e.card.posterUrl) { crossfade(false) }
                    v.findViewById<TextView>(2).text = e.card.title
                    v.findViewById<TextView>(3).text = "S${e.season} · E${e.episode}" + (e.name.takeIf { it.isNotBlank() && !it.startsWith("Episode") }?.let { "  ·  $it" } ?: "")
                    v.findViewById<TextView>(4).apply {
                        text = if (aired) "Out now" else if (e.exactTime) time.format(Date(e.airAt)) else "Airs"
                        setTextColor(if (aired) Color.BLACK else Color.WHITE)
                        setBackgroundResource(if (aired) R.drawable.btn_play_hero else R.drawable.btn_secondary)
                    }
                    v.setOnClickListener {
                        if (aired) PlayerActivity.launch(requireContext(), e.card, e.season, e.episode)
                        else (activity as? MainActivity)?.openDetail(e.card)
                    }
                }
            }
        }
    }
}
