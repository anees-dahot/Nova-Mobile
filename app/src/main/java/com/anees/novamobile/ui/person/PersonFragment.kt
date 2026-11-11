package com.anees.novamobile.ui.person

import android.graphics.Outline
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.widget.NestedScrollView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.anees.novamobile.MainActivity
import com.anees.novamobile.R
import com.anees.novamobile.data.repository.MediaRepository
import com.anees.novamobile.ui.common.QuickActions
import com.anees.novamobile.ui.common.Ui
import com.anees.novamobile.ui.common.dp
import com.anees.novamobile.ui.home.MediaCardAdapter
import com.anees.novamobile.ui.home.posterGrid
import kotlinx.coroutines.launch

/** An actor or director: photo, short bio, and every title of theirs Nova can open. */
class PersonFragment : Fragment() {

    private val repo = MediaRepository()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = requireContext()
        val id = requireArguments().getInt(ARG_ID)
        val root = FrameLayout(ctx).apply { setBackgroundColor(0xFF000000.toInt()); isClickable = true }
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 68.dp, 0, 32.dp) }

        val photo = ImageView(ctx).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundResource(R.drawable.skeleton)
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, o: Outline) = o.setOval(0, 0, v.width, v.height)
            }
            clipToOutline = true
            contentDescription = null
            requireArguments().getString(ARG_PHOTO)?.let { load(it) { crossfade(200) } }
        }
        val name = TextView(ctx).apply {
            text = requireArguments().getString(ARG_NAME); textSize = 24f; setTextColor(Ui.TEXT); typeface = Typeface.DEFAULT_BOLD
        }
        val facts = TextView(ctx).apply { textSize = 13f; setTextColor(Ui.TEXT2); setPadding(0, 4.dp, 0, 0) }
        col.addView(LinearLayout(ctx).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(16.dp, 0, 16.dp, 0)
            addView(photo, LinearLayout.LayoutParams(96.dp, 96.dp))
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL; setPadding(16.dp, 0, 0, 0)
                addView(name); addView(facts)
            }, LinearLayout.LayoutParams(0, -2, 1f))
        })

        val bio = TextView(ctx).apply {
            textSize = 14f; setTextColor(0xCCFFFFFF.toInt()); setLineSpacing(0f, 1.25f)
            maxLines = 5; ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(16.dp, 16.dp, 16.dp, 0); visibility = View.GONE
            setOnClickListener { maxLines = if (maxLines == 5) 200 else 5 }
        }
        col.addView(bio)

        val heading = TextView(ctx).apply {
            text = "Known For"; textSize = 16f; setTextColor(0xE6FFFFFF.toInt())
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(16.dp, 24.dp, 16.dp, 0); visibility = View.GONE
        }
        col.addView(heading)
        val grid = RecyclerView(ctx).apply {
            posterGrid()
            isNestedScrollingEnabled = false; itemAnimator = null; clipToPadding = false
            setPadding(12.dp, 8.dp, 2.dp, 8.dp)
        }
        col.addView(grid)
        val status = TextView(ctx).apply {
            text = "Loading…"; textSize = 14f; setTextColor(Ui.TEXT3); gravity = Gravity.CENTER; setPadding(24.dp, 48.dp, 24.dp, 0)
        }
        col.addView(status)

        root.addView(NestedScrollView(ctx).apply { addView(col) })
        root.addView(ImageButton(ctx).apply {
            setBackgroundResource(R.drawable.circle_glass); setImageResource(R.drawable.ic_back); contentDescription = "Back"
            setOnClickListener { parentFragmentManager.popBackStack() }
        }, FrameLayout.LayoutParams(40.dp, 40.dp).apply { setMargins(12.dp, 12.dp, 12.dp, 12.dp) })

        viewLifecycleOwner.lifecycleScope.launch {
            val (p, cards) = repo.person(id) ?: run {
                status.text = "Couldn't load this person. Check your connection and try again."
                return@launch
            }
            if (!isAdded) return@launch
            name.text = p.name
            if (photo.drawable == null) p.photoUrl?.let { photo.load(it) { crossfade(200) } }
            facts.text = listOfNotNull(
                p.department?.takeIf { it.isNotBlank() },
                p.birthday?.take(4)?.let { b -> p.deathday?.take(4)?.let { "$b – $it" } ?: "Born $b" },
                p.placeOfBirth?.substringAfterLast(',')?.trim()?.takeIf { it.isNotBlank() }
            ).joinToString("  ·  ")
            if (p.biography.isNotBlank()) { bio.text = p.biography.trim(); bio.visibility = View.VISIBLE }
            if (cards.isEmpty()) { status.text = "Nothing of theirs is in Nova yet."; return@launch }
            status.visibility = View.GONE
            heading.visibility = View.VISIBLE
            grid.adapter = MediaCardAdapter(cards,
                onLongClick = { _, c -> QuickActions.show(ctx, c) },
                onClick = { _, c -> (activity as? MainActivity)?.openDetail(c) })
        }
        return root
    }

    companion object {
        private const val ARG_ID = "id"
        private const val ARG_NAME = "name"
        private const val ARG_PHOTO = "photo"
        fun of(id: Int, name: String, photo: String?) = PersonFragment().apply {
            arguments = Bundle().apply { putInt(ARG_ID, id); putString(ARG_NAME, name); putString(ARG_PHOTO, photo) }
        }
    }
}
