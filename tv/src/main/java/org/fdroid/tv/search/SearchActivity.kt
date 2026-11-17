package org.fdroid.tv.search

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.widget.EditText
import androidx.fragment.app.FragmentActivity
import androidx.leanback.app.RowsSupportFragment
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.HeaderItem
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.ListRowPresenter
import androidx.lifecycle.LiveData
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.fdroid.database.AppListItem
import org.fdroid.database.AppListSortOrder
import org.fdroid.tv.Fdroid
import org.fdroid.tv.R
import org.fdroid.tv.browse.AppCard
import org.fdroid.tv.browse.AppRepository
import org.fdroid.tv.browse.CardPresenter
import org.fdroid.tv.browse.TvRowHeaderPresenter
import org.fdroid.tv.details.DetailsActivity
import java.util.concurrent.atomic.AtomicInteger

/**
 * Search across the whole index. The field keeps focus so the remote's d-pad
 * centre opens the on-screen keyboard, and the results reuse the store cards.
 */
class SearchActivity : FragmentActivity() {

    private lateinit var repository: AppRepository
    private lateinit var rowsAdapter: ArrayObjectAdapter
    private lateinit var rowsFragment: RowsSupportFragment
    private var items: List<AppListItem> = emptyList()
    private var itemsLive: LiveData<List<AppListItem>>? = null

    private val handler = Handler(Looper.getMainLooper())
    private val generation = AtomicInteger()
    private var pending: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_search)
        repository = AppRepository(this)

        val presenter = ListRowPresenter(1, false).apply {
            setHeaderPresenter(TvRowHeaderPresenter())
            setShadowEnabled(false)
        }
        rowsAdapter = ArrayObjectAdapter(presenter)
        rowsFragment = RowsSupportFragment()
        rowsFragment.setAdapter(rowsAdapter)
        rowsFragment.setOnItemViewClickedListener { _, item, _, _ ->
            val card = item as? AppCard ?: return@setOnItemViewClickedListener
            startActivity(DetailsActivity.intent(this, card.packageName))
        }
        supportFragmentManager.beginTransaction()
            .replace(R.id.search_rows, rowsFragment)
            .commitAllowingStateLoss()

        val field = findViewById<EditText>(R.id.search_query)
        field.requestFocus()
        field.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                scheduleSearch(s?.toString().orEmpty())
            }
        })
        observeItems()
    }

    /**
     * Keeps the whole index in memory for filtering; the query itself matches
     * the app text of every locale, not just the one the device is set to.
     */
    private fun observeItems() {
        val live = Fdroid.db.getAppDao()
            .getAppListItems(packageManager, null, AppListSortOrder.NAME)
        live.observe(this) { all ->
            items = all
            val query = (findViewById<EditText>(R.id.search_query).text?.toString()).orEmpty()
            if (query.isNotBlank()) scheduleSearch(query)
        }
        itemsLive?.removeObservers(this)
        itemsLive = live
    }

    private fun scheduleSearch(query: String) {
        pending?.let { handler.removeCallbacks(it) }
        if (query.isBlank()) {
            generation.incrementAndGet()
            rowsAdapter.clear()
            return
        }
        val runnable = Runnable { search(query) }
        pending = runnable
        handler.postDelayed(runnable, 250)
    }

    private fun search(query: String) {
        val current = generation.incrementAndGet()
        val trimmed = query.trim()
        if (!SearchText.isSearchable(trimmed)) {
            showResults(emptyList())
            return
        }
        val words = SearchText.words(trimmed)
        lifecycleScope.launch {
            val index = repository.searchIndex()
            if (current != generation.get()) return@launch
            val matches = items.filter { item ->
                index[item.packageName]?.let { SearchText.matches(it, words) } == true
            }
            if (current != generation.get()) return@launch
            showResults(repository.cardsFromListItems(matches))
        }
    }

    private fun showResults(cards: List<AppCard>) {
        rowsAdapter.clear()
        if (cards.isEmpty()) return
        val adapter = ArrayObjectAdapter(CardPresenter { card ->
            startActivity(DetailsActivity.intent(this, card.packageName))
        })
        cards.take(60).forEach { adapter.add(it) }
        rowsAdapter.add(ListRow(HeaderItem(getString(R.string.row_results)), adapter))
    }

    override fun onDestroy() {
        pending?.let { handler.removeCallbacks(it) }
        super.onDestroy()
    }
}
