package org.fdroid.tv.browse

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import androidx.leanback.app.RowsSupportFragment
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.HeaderItem
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.ListRowPresenter
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.fdroid.database.AppOverviewItem
import org.fdroid.tv.Fdroid
import org.fdroid.tv.R
import org.fdroid.tv.details.DetailsActivity
import org.fdroid.tv.search.SearchActivity
import org.fdroid.tv.settings.SettingsActivity

/**
 * The store home screen: a permanent guide rail on the left and rows of wide
 * cards on the right, exactly like the 2015 Play Store for Android TV.
 */
class BrowseActivity : FragmentActivity() {

    private enum class Screen { HOME, GAMES, MY_APPS, SETTINGS }

    private data class GuideEntry(val titleRes: Int, val iconRes: Int, val screen: Screen)

    private val guideEntries = listOf(
        GuideEntry(R.string.guide_home, R.drawable.ic_rail_home, Screen.HOME),
        GuideEntry(R.string.guide_games, R.drawable.ic_games, Screen.GAMES),
        GuideEntry(R.string.guide_my_apps, R.drawable.ic_rail_myapps, Screen.MY_APPS),
        GuideEntry(R.string.guide_settings, R.drawable.ic_rail_settings, Screen.SETTINGS),
    )

    private lateinit var rowsFragment: RowsSupportFragment
    private lateinit var rootAdapter: ArrayObjectAdapter
    private lateinit var statusContainer: View
    private lateinit var statusText: TextView
    private lateinit var repository: AppRepository
    private val cardPresenter by lazy { CardPresenter { card -> openDetails(card) } }
    private val guideViews = HashMap<Screen, View>()
    private var screen = Screen.HOME
    private var cards: List<AppCard> = emptyList()
    private var installedCards: List<AppCard> = emptyList()
    private var updates: Set<String> = emptySet()
    private var lastItems: List<AppOverviewItem> = emptyList()
    private var focusRequested = false
    private var resumedBefore = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_browse)
        repository = AppRepository(this)

        statusContainer = findViewById(R.id.status_container)
        statusText = findViewById(R.id.status_text)

        val rowPresenter = ListRowPresenter(1, false).apply {
            setHeaderPresenter(TvRowHeaderPresenter())
            setShadowEnabled(false)
        }
        rootAdapter = ArrayObjectAdapter(rowPresenter)

        rowsFragment = RowsSupportFragment()
        rowsFragment.setAdapter(rootAdapter)
        supportFragmentManager.beginTransaction()
            .replace(R.id.rows_fragment, rowsFragment)
            .commitAllowingStateLoss()

        setupGuide()
        findViewById<View>(R.id.search_orb).setOnClickListener {
            startActivity(Intent(this, SearchActivity::class.java))
        }
        // Leanback's focus search does not reliably walk from the cards into our own
        // rail, so send LEFT there explicitly (back to the current section's entry).
        findViewById<View>(R.id.root).setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT && event.action == KeyEvent.ACTION_DOWN &&
                !isGuideFocused()
            ) {
                guideViews[screen]?.requestFocus() ?: false
            } else {
                false
            }
        }

        repository.overview().observe(this) { items ->
            lastItems = items
            refreshCards(items)
        }
        Fdroid.syncState.observe(this) { state ->
            if (state is Fdroid.SyncState.Done) {
                repository.invalidateDetails()
                refreshInstalledState()
            }
            renderStatus()
        }
        Fdroid.db.getAppDao().getInstalledAppListItems(packageManager).observe(this) { items ->
            lifecycleScope.launch {
                installedCards = repository.installedCards(items, updates)
                if (screen == Screen.MY_APPS) render()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (resumedBefore && cards.isNotEmpty()) {
            refreshInstalledState()
        }
        resumedBefore = true
    }

    override fun onBackPressed() {
        if (screen != Screen.HOME) {
            selectScreen(Screen.HOME)
        } else {
            super.onBackPressed()
        }
    }

    private fun setupGuide() {
        val container = findViewById<LinearLayout>(R.id.guide_items)
        val items = ArrayList<View>()
        guideEntries.forEach { entry ->
            val view = layoutInflater.inflate(R.layout.guide_item, container, false)
            view.id = View.generateViewId()
            view.findViewById<TextView>(R.id.guide_label).setText(entry.titleRes)
            view.findViewById<ImageView>(R.id.guide_icon).setImageResource(entry.iconRes)
            view.setOnClickListener { onGuideEntry(entry.screen) }
            view.setOnFocusChangeListener { v, hasFocus ->
                v.isSelected = hasFocus || screen == entry.screen
            }
            container.addView(view)
            items.add(view)
            guideViews[entry.screen] = view
        }
        // The orb and the entries sit in the same rail, so wire the order by hand:
        // relying on spatial focus search made the orb unreachable with the D-pad.
        val orb = findViewById<View>(R.id.search_orb)
        orb.nextFocusDownId = items.first().id
        items.forEachIndexed { index, view ->
            view.nextFocusUpId = if (index == 0) orb.id else items[index - 1].id
            if (index < items.size - 1) view.nextFocusDownId = items[index + 1].id
        }
        updateGuideSelection()
    }

    private fun isGuideFocused(): Boolean {
        val guide = findViewById<View>(R.id.guide)
        return guide.findFocus() != null
    }

    private fun onGuideEntry(target: Screen) {
        if (target == Screen.SETTINGS) {
            startActivity(Intent(this, SettingsActivity::class.java))
        } else {
            selectScreen(target)
        }
    }

    private fun selectScreen(target: Screen) {
        if (screen == target) return
        screen = target
        updateGuideSelection()
        render()
        rowsFragment.view?.requestFocus()
    }

    private fun updateGuideSelection() {
        guideViews.forEach { (entryScreen, view) ->
            view.isSelected = entryScreen == screen
        }
    }

    private fun refreshCards(items: List<AppOverviewItem>) {
        lifecycleScope.launch {
            updates = Fdroid.updatableApps()
            val installed = repository.installedPackages()
            cards = repository.buildCards(items, updates, installed)
            render()
        }
    }

    private fun refreshInstalledState() {
        lifecycleScope.launch {
            updates = Fdroid.updatableApps()
            val installed = repository.installedPackages()
            cards = cards.map { card ->
                card.copy(
                    installed = installed.contains(card.packageName),
                    updateAvailable = updates.contains(card.packageName),
                )
            }
            installedCards = installedCards.map { card ->
                card.copy(updateAvailable = updates.contains(card.packageName))
            }
            render()
        }
    }

    private fun render() {
        val rows = when (screen) {
            Screen.HOME -> repository.homeRows(cards)
            Screen.GAMES -> repository.gamesRows(cards)
            Screen.MY_APPS -> repository.myAppsRows(installedCards)
            Screen.SETTINGS -> emptyList()
        }

        val focusedView = currentFocus
        rootAdapter.clear()
        rows.forEach { row ->
            val cardAdapter = ArrayObjectAdapter(cardPresenter)
            row.cards.forEach { cardAdapter.add(it) }
            rootAdapter.add(ListRow(HeaderItem(row.title), cardAdapter))
        }

        if (rows.isNotEmpty() && !focusRequested) {
            focusRequested = true
            // Posted, because the rows have not been laid out yet on the first render;
            // otherwise the window hands the focus to the last guide entry.
            rowsFragment.view?.post { rowsFragment.view?.requestFocus() }
        } else if (focusedView != null && rows.isEmpty()) {
            rowsFragment.view?.requestFocus()
        }
        renderStatus()
    }

    private fun renderStatus() {
        val state = Fdroid.syncState.value
        val hasRows = rootAdapter.size() > 0
        when {
            hasRows -> {
                statusContainer.visibility = View.GONE
            }
            state is Fdroid.SyncState.Running -> {
                statusContainer.visibility = View.VISIBLE
                statusText.text = getString(R.string.index_updating)
            }
            state is Fdroid.SyncState.Error -> {
                statusContainer.visibility = View.VISIBLE
                statusText.text = getString(R.string.index_error, state.message)
            }
            cards.isEmpty() -> {
                statusContainer.visibility = View.VISIBLE
                statusText.text = getString(R.string.index_empty)
            }
            screen == Screen.MY_APPS -> {
                statusContainer.visibility = View.VISIBLE
                statusText.text = getString(R.string.my_apps_empty)
            }
            else -> {
                statusContainer.visibility = View.VISIBLE
                statusText.text = getString(R.string.search_empty)
            }
        }
    }

    private fun openDetails(card: AppCard) {
        startActivity(DetailsActivity.intent(this, card.packageName))
    }
}
