package org.fdroid.wear.search

import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.asFlow
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.fdroid.wear.Fdroid
import org.fdroid.wear.R
import org.fdroid.wear.browse.AppCard
import org.fdroid.wear.browse.AppRepository
import org.fdroid.wear.details.DetailsActivity

/** Text search on the watch, using the same multi-locale matching as the other clients. */
class SearchActivity : AppCompatActivity() {

    private lateinit var repository: AppRepository
    private lateinit var results: LinearLayout
    private lateinit var empty: TextView
    private var pending: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_wear_search)
        repository = AppRepository(this)
        results = findViewById(R.id.results)
        empty = findViewById(R.id.empty)

        val query = findViewById<EditText>(R.id.query)
        query.requestFocus()
        query.post {
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .showSoftInput(query, InputMethodManager.SHOW_IMPLICIT)
        }
        query.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                search(s?.toString().orEmpty())
            }

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        })
    }

    private fun search(query: String) {
        pending?.cancel()
        val trimmed = query.trim()
        if (!SearchText.isSearchable(trimmed)) {
            results.removeAllViews()
            empty.visibility = View.GONE
            return
        }
        val words = SearchText.words(trimmed)
        pending = lifecycleScope.launch {
            delay(200)
            val index = repository.searchIndex()
            // The whole index is searched, not just the home screen's first page.
            val names = index.filterValues { text -> SearchText.matches(text, words) }
                .keys.take(40).toList()
            if (names.isEmpty()) {
                showResults(emptyList())
                return@launch
            }
            val matches = Fdroid.db.getAppDao().getApps(names).asFlow().first()
            val cards = repository.listCards(
                matches,
                Fdroid.updatableApps(),
                repository.installedPackages(),
            ).take(30)
            showResults(cards)
        }
    }

    private fun showResults(cards: List<AppCard>) {
        results.removeAllViews()
        empty.visibility = if (cards.isEmpty()) View.VISIBLE else View.GONE
        cards.forEach { card ->
            val row = LayoutInflater.from(this).inflate(R.layout.item_wear_app, results, false)
            val icon = row.findViewById<ImageView>(R.id.app_icon)
            Fdroid.imageModel(card.repoId, card.icon)?.let { model ->
                Glide.with(icon).load(model).into(icon)
            }
            row.findViewById<TextView>(R.id.app_name).text = card.name
            row.findViewById<TextView>(R.id.app_meta).text = card.summary.orEmpty()
            row.setOnClickListener {
                startActivity(DetailsActivity.intent(this, card.packageName))
            }
            results.addView(row)
        }
    }
}
