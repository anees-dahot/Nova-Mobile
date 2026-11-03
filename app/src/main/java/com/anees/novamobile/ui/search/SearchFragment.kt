package com.anees.novamobile.ui.search

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.view.*
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.anees.novamobile.MainActivity
import com.anees.novamobile.R
import com.anees.novamobile.data.local.LocalStore
import com.anees.novamobile.data.model.MediaCard
import com.anees.novamobile.data.repository.MediaRepository
import com.anees.novamobile.ui.common.QuickActions
import com.anees.novamobile.ui.common.TabPage
import com.anees.novamobile.ui.home.HomeViewModel
import com.anees.novamobile.ui.home.MediaCardAdapter
import com.anees.novamobile.ui.home.posterColumns
import kotlinx.coroutines.*

/** Search: system keyboard + mic, results update as you type, Trending and recents when empty. */
class SearchFragment : Fragment(), TabPage {

    private val repo = MediaRepository()
    private val home: HomeViewModel by activityViewModels()
    private var searchJob: Job? = null
    private lateinit var input: EditText
    private lateinit var results: RecyclerView
    private lateinit var title: TextView
    private lateinit var recent: LinearLayout

    private val voice = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val text = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (r.resultCode == Activity.RESULT_OK && !text.isNullOrBlank()) { input.setText(text); input.setSelection(text.length) }
    }

    override fun onCreateView(inflater: LayoutInflater, c: ViewGroup?, s: Bundle?): View =
        inflater.inflate(R.layout.fragment_search, c, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        input = view.findViewById(R.id.search_input)
        results = view.findViewById(R.id.search_results)
        title = view.findViewById(R.id.results_title)
        recent = view.findViewById(R.id.recent)
        val clear = view.findViewById<View>(R.id.btn_clear)
        results.layoutManager = GridLayoutManager(requireContext(), requireContext().posterColumns())
        results.itemAnimator = null
        // Scrolling the results hides the keyboard
        results.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(rv: RecyclerView, state: Int) { if (state == RecyclerView.SCROLL_STATE_DRAGGING) hideKeyboard() }
        })

        input.doAfterTextChanged { e ->
            clear.visibility = if (e.isNullOrEmpty()) View.GONE else View.VISIBLE
            runSearch(e?.toString()?.trim() ?: "", 250)
        }
        input.setOnEditorActionListener { v, action, _ ->
            if (action == EditorInfo.IME_ACTION_SEARCH) { runSearch(v.text.toString().trim(), 0); hideKeyboard(); true } else false
        }
        clear.setOnClickListener { input.setText("") }
        view.findViewById<View>(R.id.btn_voice).setOnClickListener { startVoice() }

        home.trending.observe(viewLifecycleOwner) { if (input.text.isNullOrBlank()) showCards("Trending Now", it) }
        renderRecent()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) { renderRecent(); if (input.text.isNullOrEmpty()) showKeyboard() } else hideKeyboard()
    }

    override fun scrollToTop(): Boolean { results.smoothScrollToPosition(0); input.requestFocus(); showKeyboard(); return true }

    fun startVoice() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Say a movie, series or anime")
        runCatching { voice.launch(intent) }
            .onFailure { Toast.makeText(requireContext(), "Voice search isn't available", Toast.LENGTH_SHORT).show() }
    }

    private fun runSearch(q: String, debounce: Long) {
        searchJob?.cancel()
        if (q.length < 2) { showCards("Trending Now", home.trending.value ?: emptyList()); return }
        cache[q.lowercase()]?.let { showCards("Results", it); return }
        searchJob = viewLifecycleOwner.lifecycleScope.launch {
            delay(debounce)
            title.text = "Searching…"
            val cards = repo.search(q)
            cache[q.lowercase()] = cards
            showCards(if (cards.isEmpty()) "No results for “$q”" else "Results", cards)
        }
    }

    private fun showCards(header: String, cards: List<MediaCard>) {
        title.text = header
        results.adapter = MediaCardAdapter(cards,
            onLongClick = { _, c -> QuickActions.show(requireContext(), c) },
            onClick = { _, c ->
                input.text?.toString()?.takeIf { it.isNotBlank() }?.let { LocalStore.addRecentSearch(it); renderRecent() }
                hideKeyboard()
                (activity as? MainActivity)?.openDetail(c)
            })
    }

    private fun renderRecent() {
        if (!::recent.isInitialized) return
        recent.removeAllViews()
        val dp = resources.displayMetrics.density
        LocalStore.recentSearches().forEach { q ->
            recent.addView(TextView(requireContext()).apply {
                text = q
                textSize = 13f
                gravity = Gravity.CENTER
                setTextColor(0xE6FFFFFF.toInt())
                setBackgroundResource(R.drawable.chip_bg)
                setPadding((14 * dp).toInt(), 0, (14 * dp).toInt(), 0)
                setOnClickListener { input.setText(q); input.setSelection(q.length) }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, (32 * dp).toInt()).apply { marginStart = (8 * dp).toInt() })
        }
    }

    private fun imm() = requireContext().getSystemService(InputMethodManager::class.java)
    private fun showKeyboard() { input.requestFocus(); input.post { imm()?.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT) } }
    private fun hideKeyboard() { imm()?.hideSoftInputFromWindow(input.windowToken, 0) }

    companion object {
        private val cache = object : LinkedHashMap<String, List<MediaCard>>(32, 0.75f, true) {
            override fun removeEldestEntry(e: MutableMap.MutableEntry<String, List<MediaCard>>?) = size > 40
        }
    }
}
