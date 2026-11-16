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
import com.anees.novamobile.calendar.AiringReceiver
import com.anees.novamobile.notify.NotifyChannels
import com.anees.novamobile.data.local.LocalStore
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
    private lateinit var alerts: TextView
    private lateinit var list: RecyclerView
    private val time = SimpleDateFormat("h:mm a", Locale.getDefault())
    private val dayFmt = SimpleDateFormat("EEEE, MMM d", Locale.getDefault())

    override fun onCreateView(inflater: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        val ctx = requireContext(); val dp = resources.displayMetrics.density
        val root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.BLACK); isClickable = true }
        root.addView(ImageButton(ctx).apply {
            setImageResource(R.drawable.ic_back); background = null
            setOnClickListener { parentFragmentManager.popBackStack() }
            // As a bottom-bar tab there's nothing to go back to
            if (arguments?.getBoolean("tab") == true) visibility = View.INVISIBLE
        }, LinearLayout.LayoutParams((52 * dp).toInt(), (52 * dp).toInt()).apply { topMargin = (8 * dp).toInt() })
        root.addView(TextView(ctx).apply {
            text = "Calendar"; textSize = 32f; setTextColor(Color.WHITE); letterSpacing = -0.02f
            typeface = Typeface.create("sans-serif", Typeface.BOLD); setPadding((16 * dp).toInt(), 0, 0, 0)
        })
        status = TextView(ctx).apply { textSize = 13f; setTextColor(0x99FFFFFF.toInt()); setPadding((16 * dp).toInt(), (2 * dp).toInt(), (16 * dp).toInt(), (8 * dp).toInt()) }
        root.addView(status)
        // Why alerts won't arrive (permission / notifications off) + one-tap fix
        alerts = TextView(ctx).apply {
            textSize = 13f; setTextColor(Color.WHITE); visibility = View.GONE
            setBackgroundColor(0x33FF9F0A); setPadding((16 * dp).toInt(), (10 * dp).toInt(), (16 * dp).toInt(), (10 * dp).toInt())
        }
        root.addView(alerts, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = (8 * dp).toInt() })
        list = RecyclerView(ctx).apply { layoutManager = LinearLayoutManager(ctx); adapter = Adapter(); itemAnimator = null; clipToPadding = false; setPadding(0, 0, 0, (24 * dp).toInt()) }
        root.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        // Show the cached week instantly, then refresh
        show(LocalStore.airingCache(), loading = true)
        viewLifecycleOwner.lifecycleScope.launch {
            val eps = runCatching { AiringCalendar.load() }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }.getOrNull()
            if (eps != null) {
                show(eps, loading = false)
                if (AiringCalendar.lastLoadPartial) status.append("  ·  some shows couldn't refresh")
            } else {
                val saved = LocalStore.airingCache()
                show(saved, loading = false)
                status.text = if (saved.isEmpty()) "Couldn't load the schedule — check your connection" else "Offline — showing saved schedule"
            }
            context?.applicationContext?.let { app -> kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { AiringCalendar.reschedule(app, eps) } } }
        }
        // First visit with shows to follow: ask for notifications right when it matters
        val prefs = requireContext().getSharedPreferences("calendar_ui", 0)
        if (!prefs.getBoolean("asked_notif", false) && AiringCalendar.followed().isNotEmpty() &&
            AiringReceiver.blocker(requireContext()) == AiringReceiver.Blocker.PERMISSION) {
            prefs.edit().putBoolean("asked_notif", true).apply()
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 41)
        }
    }

    override fun onResume() {
        super.onResume()
        updateAlerts()
        list.adapter?.notifyDataSetChanged()      // "Out now" badges flip while you were away
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        updateAlerts()
    }

    private fun updateAlerts() {
        val ctx = context ?: return
        val why = if (AiringCalendar.followed().isEmpty()) null else AiringReceiver.blocker(ctx)
        alerts.visibility = if (why == null) View.GONE else View.VISIBLE
        alerts.text = when (why) {
            AiringReceiver.Blocker.SETTING_OFF -> "🔕  New-episode alerts are off.  Tap to turn on"
            AiringReceiver.Blocker.PERMISSION -> "🔕  Nova can't notify you about new episodes.  Tap to allow"
            AiringReceiver.Blocker.APP_OFF -> "🔕  Notifications are off for Nova.  Tap to open settings"
            AiringReceiver.Blocker.CHANNEL_OFF -> "🔕  \"New episodes\" notifications are off.  Tap to open settings"
            null -> ""
        }
        alerts.setOnClickListener {
            when (why) {
                AiringReceiver.Blocker.SETTING_OFF -> {
                    LocalStore.saveSettings(LocalStore.settings().copy(newEpisodeAlerts = true)); updateAlerts()
                    viewLifecycleOwner.lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) { runCatching { AiringCalendar.reschedule(ctx.applicationContext) } }
                }
                AiringReceiver.Blocker.PERMISSION ->
                    // Denied twice → Android won't show the dialog again; go to the app's notification settings
                    if (shouldShowRequestPermissionRationale(android.Manifest.permission.POST_NOTIFICATIONS) ||
                        !ctx.getSharedPreferences("calendar_ui", 0).getBoolean("asked_notif", false)) {
                        ctx.getSharedPreferences("calendar_ui", 0).edit().putBoolean("asked_notif", true).apply()
                        requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 41)
                    } else openAppNotificationSettings(ctx)
                AiringReceiver.Blocker.APP_OFF -> openAppNotificationSettings(ctx)
                AiringReceiver.Blocker.CHANNEL_OFF -> runCatching { startActivity(NotifyChannels.settingsIntent(ctx, NotifyChannels.NEW_EPISODES)) }
                null -> {}
            }
        }
    }

    private fun openAppNotificationSettings(ctx: android.content.Context) = runCatching {
        startActivity(android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, ctx.packageName))
    }

    private fun show(eps: List<AiringEp>, loading: Boolean) {
        val followed = AiringCalendar.followed().size
        status.text = when {
            followed == 0 -> "Add series or anime to My List to see their new episodes here."
            loading && eps.isEmpty() -> "Loading schedule…"
            eps.isEmpty() -> "No new episodes in the next two weeks for your $followed shows."
            else -> "${eps.size} episode${if (eps.size == 1) "" else "s"} · from your $followed shows"
        }
        rows.clear()
        eps.groupBy { dayLabel(it.airAt) }.forEach { (label, items) -> rows += Row.Day(label); items.forEach { rows += Row.Ep(it) } }
        list.adapter?.notifyDataSetChanged()
    }

    private fun dayLabel(at: Long): String = when {
        DateUtils.isToday(at) -> "Today"
        DateUtils.isToday(at + DateUtils.DAY_IN_MILLIS) -> "Yesterday"
        DateUtils.isToday(at - DateUtils.DAY_IN_MILLIS) -> "Tomorrow"
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
                    v.findViewById<TextView>(3).text = "S${e.season} · E${e.episode}" + (e.name.takeIf { it.isNotBlank() && !it.matches(Regex("(?i)episode \\d+")) }?.let { "  ·  $it" } ?: "")
                    v.findViewById<TextView>(4).apply {
                        text = if (aired) "Out now" else if (e.exactTime) time.format(Date(e.airAt)) else "~" + time.format(Date(e.airAt))
                        setTextColor(if (aired) Color.BLACK else Color.WHITE)
                        setBackgroundResource(if (aired) R.drawable.btn_play_hero else R.drawable.btn_secondary)
                    }
                    // Show page first (on that episode): play, download or read about it from there
                    v.setOnClickListener { (activity as? MainActivity)?.openDetail(e.card, e.season, e.episode) }
                }
            }
        }
    }
}
