package org.fdroid.phone.home

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewTreeObserver
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import kotlinx.coroutines.launch
import org.fdroid.phone.Fdroid
import org.fdroid.phone.R
import org.fdroid.phone.browse.AppCard
import org.fdroid.phone.browse.AppRepository
import org.fdroid.phone.details.DetailsActivity

/**
 * Vertical list of the apps in one section or category. Long categories can hold
 * hundreds of apps, so rows are inflated a page at a time as the user scrolls
 * instead of all at once.
 */
class SectionListActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_TITLE = "org.fdroid.phone.extra.TITLE"
        private const val EXTRA_PACKAGES = "org.fdroid.phone.extra.PACKAGES"
        private const val EXTRA_CATEGORY = "org.fdroid.phone.extra.CATEGORY"

        /** How many rows are added per page; this keeps the first paint quick. */
        private const val PAGE_SIZE = 30

        fun intent(context: Context, title: String, packages: Array<String>): Intent =
            Intent(context, SectionListActivity::class.java)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_PACKAGES, packages)

        fun categoryIntent(context: Context, categoryId: String, title: String): Intent =
            Intent(context, SectionListActivity::class.java)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_CATEGORY, categoryId)
    }

    private lateinit var repository: AppRepository
    private lateinit var rows: LinearLayout
    private lateinit var scroll: ScrollView

    private var allCards: List<AppCard> = emptyList()
    private var nextIndex = 0

    private val scrollListener = ViewTreeObserver.OnScrollChangedListener { maybeLoadMore() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_phone_list)
        repository = AppRepository(this)
        rows = findViewById(R.id.list_rows)
        scroll = findViewById(R.id.list_scroll)

        findViewById<TextView>(R.id.list_title).text = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        findViewById<View>(R.id.list_back).setOnClickListener { finish() }

        val packages = intent.getStringArrayExtra(EXTRA_PACKAGES)?.toSet().orEmpty()
        val category = intent.getStringExtra(EXTRA_CATEGORY)

        scroll.viewTreeObserver.addOnScrollChangedListener(scrollListener)

        if (category == null && packages.isNotEmpty()) {
            // A "MORE" row: show exactly the apps of that row, in its order.
            val order = packages.withIndex().associate { it.value to it.index }
            Fdroid.db.getAppDao().getApps(packages.toList()).observe(this) { items ->
                lifecycleScope.launch {
                    val cards = repository.listCards(
                        items,
                        Fdroid.updatableApps(),
                        repository.installedPackages(),
                    ).sortedBy { order[it.packageName] ?: Int.MAX_VALUE }
                    render(cards)
                }
            }
            return
        }

        if (category != null) {
            // Categories come from the repository's own category list, so the
            // index, not the capped overview, decides what belongs to them.
            Fdroid.db.getAppDao().getAppOverviewItems(category, 500).observe(this) { items ->
                lifecycleScope.launch {
                    val cards = repository.listCards(
                        items,
                        Fdroid.updatableApps(),
                        repository.installedPackages(),
                    ).sortedBy { it.name.lowercase() }
                    render(cards)
                }
            }
            return
        }

        repository.overview().observe(this) { items ->
            lifecycleScope.launch {
                val updates = Fdroid.updatableApps()
                val installed = repository.installedPackages()
                val cards = repository.listCards(items, updates, installed)
                    .sortedBy { it.name.lowercase() }
                render(cards)
            }
        }
    }

    private fun render(cards: List<AppCard>) {
        allCards = cards
        nextIndex = 0
        rows.removeAllViews()
        appendPage()
    }

    /** Adds the next [PAGE_SIZE] rows, if there are any left. */
    private fun appendPage() {
        if (nextIndex >= allCards.size) return
        val end = (nextIndex + PAGE_SIZE).coerceAtMost(allCards.size)
        for (index in nextIndex until end) {
            addRow(allCards[index])
        }
        nextIndex = end
        // A short list that does not fill the screen emits no scroll events, so
        // it would never ask for the rest of its pages.
        rows.post {
            // rows.height is 0 until the first layout pass; filling before that
            // would keep appending pages for a list that is not measured yet.
            if (rows.height > 0 && rows.height <= scroll.height && nextIndex < allCards.size) {
                appendPage()
            }
        }
    }

    /** Appends a page when the user is close to the end of what is rendered. */
    private fun maybeLoadMore() {
        if (nextIndex >= allCards.size) return
        val child = scroll.getChildAt(0) ?: return
        val remaining = child.bottom - (scroll.height + scroll.scrollY)
        if (remaining < dp(400)) appendPage()
    }

    private fun addRow(card: AppCard) {
        val view = LayoutInflater.from(this).inflate(R.layout.item_phone_list_row, rows, false)
        val icon = view.findViewById<ImageView>(R.id.row_icon)
        val model = Fdroid.imageModel(card.repoId, card.icon)
        if (model != null) {
            Glide.with(icon).load(model).into(icon)
        }
        view.findViewById<TextView>(R.id.row_name).text = card.name
        view.findViewById<TextView>(R.id.row_meta).text = card.summary.orEmpty()
        view.setOnClickListener {
            startActivity(DetailsActivity.intent(this, card.packageName))
        }
        rows.addView(view)
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        scroll.viewTreeObserver.removeOnScrollChangedListener(scrollListener)
        super.onDestroy()
    }
}
