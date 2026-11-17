package org.fdroid.phone.home

import android.app.Dialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import org.fdroid.database.Category
import org.fdroid.phone.Fdroid
import org.fdroid.phone.R
import org.fdroid.phone.browse.AppCard
import org.fdroid.phone.browse.AppRepository
import org.fdroid.phone.browse.TvRow
import org.fdroid.phone.details.DetailsActivity
import org.fdroid.phone.search.SearchActivity
import org.fdroid.phone.settings.SettingsActivity
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The phone store front: a green bar with the search field, a tab strip, the
 * category chips and the rows of cover-art cards. Rows are derived from the
 * index (newest, recently updated, then categories) - there is no dedicated
 * games row.
 */
class HomeActivity : AppCompatActivity() {

    private companion object {
        const val STATE_TAB = "org.fdroid.phone.state.TAB"
    }

    private lateinit var repository: AppRepository
    private lateinit var rows: LinearLayout
    private lateinit var drawer: DrawerLayout
    private lateinit var drawerItems: LinearLayout
    private lateinit var tabApps: TextView
    private lateinit var tabCategories: TextView
    private lateinit var tabsRow: LinearLayout
    private lateinit var indicator: View
    private lateinit var appsContent: View
    private lateinit var categoriesScroll: View
    private lateinit var categoriesList: LinearLayout
    private var selectedTab = R.id.tab_apps
    private lateinit var indexStatus: TextView
    private var syncRequested = false
    private var syncDialog: Dialog? = null
    private lateinit var chips: LinearLayout

    private var cards: List<AppCard> = emptyList()
    private var newCards: List<AppCard> = emptyList()
    private var recentCards: List<AppCard> = emptyList()
    private var curatedCards: List<AppCard> = emptyList()
    private var categories: List<Category> = emptyList()
    private var lastItemCount = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_phone_home)
        repository = AppRepository(this)
        rows = findViewById(R.id.rows)
        chips = findViewById(R.id.chips)
        drawer = findViewById(R.id.drawer)
        drawerItems = findViewById(R.id.drawer_items)
        findViewById<View>(R.id.menu_button).setOnClickListener {
            // The home screen is the apps page, so that is the row showing as selected.
            PhoneDrawer.build(this, drawer, drawerItems, PhoneDrawer.APPS)
            drawer.openDrawer(Gravity.START)
        }

        findViewById<View>(R.id.search_field).setOnClickListener {
            startActivity(Intent(this, SearchActivity::class.java))
        }
        tabApps = findViewById(R.id.tab_apps)
        tabCategories = findViewById(R.id.tab_categories)
        tabsRow = findViewById(R.id.tabs_row)
        indicator = findViewById(R.id.tab_indicator)
        appsContent = findViewById(R.id.apps_content)
        categoriesScroll = findViewById(R.id.categories_scroll)
        categoriesList = findViewById(R.id.categories_list)
        indexStatus = findViewById(R.id.index_status)
        applyTabletChrome()
        indexStatus.setOnClickListener { Fdroid.sync(this) }
        Fdroid.syncState.observe(this) { renderIndexStatus() }

        tabApps.setOnClickListener { selectTab(tabApps, animate = true) }
        tabCategories.setOnClickListener { selectTab(tabCategories, animate = true) }

        // Restore the tab the user was last on, otherwise start on the apps rows.
        selectedTab = savedInstanceState?.getInt(STATE_TAB) ?: R.id.tab_apps
        tabsRow.post {
            selectTab(findViewById(selectedTab), animate = false)
        }

        Fdroid.db.getRepositoryDao().getLiveCategories().observe(this) { list ->
            // Several repositories may declare the same category id.
            categories = list.distinctBy { it.id }
            renderChips()
            if (selectedTab == R.id.tab_categories) renderCategories()
        }
        repository.newApps().observe(this) { items ->
            lifecycleScope.launch { newCards = buildAsync(items); renderRows() }
        }
        repository.recentlyUpdatedApps().observe(this) { items ->
            lifecycleScope.launch { recentCards = buildAsync(items); renderRows() }
        }
        repository.curatedApps().observe(this) { items ->
            lifecycleScope.launch {
                curatedCards = repository.inCuratedOrder(
                    buildAsync(items),
                    repository.mostDownloadedPackages(),
                )
                renderRows()
            }
        }

        repository.overview().observe(this) { items ->
            lifecycleScope.launch {
                // The first emission can arrive while the index is still empty; the
                // cached categories and artwork would then stay empty after the sync.
                if (items.size != lastItemCount) {
                    lastItemCount = items.size
                    repository.invalidateDetails()
                }
                val updates = Fdroid.updatableApps()
                val installed = repository.installedPackages()
                cards = repository.buildCards(items, updates, installed)
                renderChips()
                renderRows()
                if (selectedTab == R.id.tab_categories) renderCategories()
                renderIndexStatus()
                // A fresh install has no index yet: fetch it once so the home
                // screen does not stay empty until the user finds Settings.
                if (cards.isEmpty() && !syncRequested &&
                    Fdroid.syncState.value !is Fdroid.SyncState.Running
                ) {
                    syncRequested = true
                    Fdroid.sync(this@HomeActivity)
                }
            }
        }
    }

    private suspend fun buildAsync(items: List<org.fdroid.database.AppOverviewItem>): List<AppCard> =
        repository.buildCards(items, Fdroid.updatableApps(), repository.installedPackages())

    private fun renderIndexStatus() {
        if (cards.isNotEmpty()) {
            indexStatus.visibility = View.GONE
            dismissSyncDialog()
            return
        }
        if (Fdroid.syncState.value is Fdroid.SyncState.Running) {
            showSyncDialog()
        } else {
            dismissSyncDialog()
        }
        indexStatus.visibility = View.VISIBLE
        when (val state = Fdroid.syncState.value) {
            is Fdroid.SyncState.Running -> {
                indexStatus.isClickable = false
                indexStatus.text = getString(R.string.index_updating)
            }
            is Fdroid.SyncState.Error -> {
                indexStatus.isClickable = true
                indexStatus.text = getString(R.string.index_error, state.message)
            }
            else -> {
                indexStatus.isClickable = true
                indexStatus.text = getString(R.string.index_empty)
            }
        }
    }

    /** The store's "Please wait" card, shown only while the first index download runs. */
    private fun showSyncDialog() {
        if (syncDialog?.isShowing == true || isFinishing) return
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_sync_progress)
        dialog.setCancelable(false)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.55f)
        }
        dialog.show()
        val width = minOf(dp(360), (resources.displayMetrics.widthPixels * 0.9f).toInt())
        dialog.window?.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
        syncDialog = dialog
    }

    private fun dismissSyncDialog() {
        syncDialog?.dismiss()
        syncDialog = null
    }

    /**
     * Tablets get the store's large-screen chrome: a narrow, centred search box
     * with the tabs and category chips centred underneath it.
     */
    private fun applyTabletChrome() {
        if (!isTablet) return
        val field = findViewById<View>(R.id.search_field)
        val params = field.layoutParams as LinearLayout.LayoutParams
        params.width = 0
        params.weight = 1f
        params.height = dp(56)
        params.gravity = Gravity.CENTER_VERTICAL
        params.topMargin = 0
        params.bottomMargin = 0
        params.marginStart = dp(6)
        params.marginEnd = dp(24)
        field.layoutParams = params

        tabsRow.gravity = Gravity.CENTER_HORIZONTAL
        (tabApps.layoutParams as LinearLayout.LayoutParams).marginStart = 0
        tabApps.textSize = 15f
        tabCategories.textSize = 15f

        findViewById<HorizontalScrollView>(R.id.chips_scroll).isFillViewport = true
        chips.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
        )
        chips.gravity = Gravity.CENTER_HORIZONTAL
    }

    private fun sidePadding(): Int = if (isTablet) dp(48) else dp(16)

    private fun cardWidth(): Int {
        if (!isTablet) return dp(132)
        val available = resources.displayMetrics.widthPixels - sidePadding() * 2
        val gap = dp(16)
        return ((available - gap * 4) / 5).coerceAtLeast(dp(160))
    }

    /**
     * The index lists a category for every app category, including the fine
     * grained game ones. Chips, rows and the grid show the broad categories in
     * order of how many apps they have, which is what the store surfaces.
     */
    private fun visibleCategories(): List<Category> {
        val counts = cards.flatMap { it.categories }.groupingBy { it }.eachCount()
        return categories
            .filterNot { it.id.endsWith("Game") }
            .sortedByDescending { counts[it.id] ?: 0 }
    }

    private fun renderChips() {
        chips.removeAllViews()
        chips.setPadding(sidePadding() - dp(4), 0, sidePadding() - dp(4), 0)
        visibleCategories().take(8).forEach { category ->
            val chip = TextView(this)
            chip.text = category.getName(locales) ?: category.id
            chip.setTextColor(0xffffffff.toInt())
            chip.textSize = 12f
            chip.letterSpacing = 0.04f
            chip.gravity = android.view.Gravity.CENTER
            chip.setBackgroundResource(R.drawable.bg_phone_chip)
            chip.setPadding(dp(14), dp(6), dp(14), dp(6))
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            params.marginEnd = dp(8)
            chip.layoutParams = params
            chip.setOnClickListener { openCategory(category.id, category.getName(locales) ?: category.id) }
            chips.addView(chip)
        }
    }

    /**
     * The discover screen mirrors the F-Droid client's sections: apps that are
     * new to the index, apps that were updated recently, the curated most
     * downloaded list, and a few categories from the repository.
     */
    private fun renderRows() {
        rows.removeAllViews()
        val newRow = if (newCards.isNotEmpty()) {
            newCards
        } else {
            cards.sortedByDescending { it.added }
        }
        val newPackages = newRow.map { it.packageName }.toSet()
        val recentRow = (if (recentCards.isNotEmpty()) recentCards else cards.sortedByDescending { it.lastUpdated })
            .filterNot { it.packageName in newPackages }
            .distinctBy { it.packageName }

        val sections = ArrayList<TvRow>()
        sections.addIfNotEmpty(getString(R.string.row_new), newRow)
        sections.addIfNotEmpty(getString(R.string.row_recently_updated), recentRow)
        sections.addIfNotEmpty(getString(R.string.phone_row_most_downloaded), curatedCards)
        visibleCategories().take(4).forEach { category ->
            sections.addIfNotEmpty(
                category.getName(locales) ?: category.id,
                cards.filter { card -> card.categories.contains(category.id) },
            )
        }

        sections.forEach { section -> addSection(section) }
    }

    private fun MutableList<TvRow>.addIfNotEmpty(title: String, sectionCards: List<AppCard>) {
        if (sectionCards.isNotEmpty()) add(TvRow(title, sectionCards.take(20)))
    }

    private fun addSection(section: TvRow) {
        val header = LayoutInflater.from(this).inflate(R.layout.item_phone_section_header, rows, false)
        header.findViewById<TextView>(R.id.section_title).text = section.title
        header.findViewById<View>(R.id.section_more).setOnClickListener {
            openSection(section.title, section.cards)
        }
        val side = sidePadding()
        header.setPadding(side, header.paddingTop, side, header.paddingBottom)
        rows.addView(header)

        val scroller = HorizontalScrollView(this)
        scroller.isHorizontalScrollBarEnabled = false
        scroller.clipToPadding = false
        scroller.setPadding(side, 0, side, 0)
        val strip = LinearLayout(this)
        strip.orientation = LinearLayout.HORIZONTAL
        scroller.addView(strip)

        section.cards.take(20).forEach { card ->
            val view = LayoutInflater.from(this).inflate(R.layout.item_phone_card, strip, false)
            bindCard(view, card)
            val params = LinearLayout.LayoutParams(cardWidth(), LinearLayout.LayoutParams.WRAP_CONTENT)
            params.marginEnd = if (isTablet) dp(16) else dp(12)
            view.layoutParams = params
            view.setOnClickListener { openDetails(card) }
            strip.addView(view)
        }
        rows.addView(scroller)
    }

    private fun bindCard(view: View, card: AppCard) {
        val art = view.findViewById<ImageView>(R.id.card_art)
        val wide = card.featureGraphic ?: card.banner
        val model = Fdroid.imageModel(card.repoId, wide ?: card.icon)
        if (model != null) {
            if (wide != null) {
                // Store artwork is 16:9-ish, so it fills the card when cropped.
                art.setPadding(0, 0, 0, 0)
                art.scaleType = ImageView.ScaleType.CENTER_CROP
                Glide.with(art).load(model).centerCrop().into(art)
            } else {
                // Icons are square; letterbox them instead of cropping them.
                val padding = dp(18)
                art.setPadding(padding, padding, padding, padding)
                art.scaleType = ImageView.ScaleType.FIT_CENTER
                Glide.with(art).load(model).fitCenter().into(art)
            }
        } else {
            art.setImageDrawable(null)
        }
        view.findViewById<TextView>(R.id.card_name).text = card.name
        view.findViewById<TextView>(R.id.card_meta).text = when {
            card.updateAvailable -> getString(R.string.phone_update_available)
            card.installed -> getString(R.string.phone_installed)
            else -> card.categories.firstOrNull().orEmpty()
        }
    }

    private fun openDetails(card: AppCard) {
        startActivity(DetailsActivity.intent(this, card.packageName))
    }

    private fun openSection(title: String, sectionCards: List<AppCard>) {
        startActivity(
            SectionListActivity.intent(
                this,
                title,
                sectionCards.map { it.packageName }.toTypedArray(),
            ),
        )
    }

    private fun openCategory(categoryId: String, title: String) {
        startActivity(SectionListActivity.categoryIntent(this, categoryId, title))
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
    private val isTablet: Boolean
        get() = resources.configuration.smallestScreenWidthDp >= 600

    private val locales = androidx.core.os.LocaleListCompat.getDefault()

    private fun openMyApps() {
        drawer.closeDrawers()
        startActivity(MyAppsActivity.intent(this))
    }

    override fun onDestroy() {
        dismissSyncDialog()
        super.onDestroy()
    }

    override fun onBackPressed() {
        if (drawer.isDrawerOpen(Gravity.START)) {
            drawer.closeDrawers()
        } else {
            super.onBackPressed()
        }
    }
    /** Switches between the apps rows and the category grid, sliding the tab underline. */
    private fun selectTab(tab: TextView, animate: Boolean) {
        selectedTab = tab.id
        val showCategories = tab.id == R.id.tab_categories
        tabApps.setTextColor(if (showCategories) 0xb3ffffff.toInt() else 0xffffffff.toInt())
        tabCategories.setTextColor(if (showCategories) 0xffffffff.toInt() else 0xb3ffffff.toInt())
        appsContent.visibility = if (showCategories) View.GONE else View.VISIBLE
        categoriesScroll.visibility = if (showCategories) View.VISIBLE else View.GONE
        if (showCategories) renderCategories()

        tab.post {
            val params = indicator.layoutParams
            if (params.width != tab.width) {
                params.width = tab.width
                indicator.layoutParams = params
            }
            val target = tabsRow.x + tab.x - indicator.left
            if (animate) {
                indicator.animate().translationX(target).setDuration(180).start()
            } else {
                indicator.translationX = target
            }
        }
    }

    private fun renderCategories() {
        categoriesList.removeAllViews()
        val side = sidePadding()
        categoriesList.setPadding(side, dp(12), side, dp(12))
        val columns = if (isTablet) 4 else 2
        val items = visibleCategories()
        if (items.isEmpty()) return

        // The tiles are laid out straight away; the app counts are one query per
        // category, so they arrive afterwards instead of holding the tab blank.
        val countViews = HashMap<String, TextView>()
        var row: LinearLayout? = null
        items.forEachIndexed { index, category ->
            if (index % columns == 0) {
                row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).apply { bottomMargin = dp(10) }
                }
                categoriesList.addView(row)
            }
            val name = category.getName(locales) ?: category.id
            val tile = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(0xffffffff.toInt())
                setPadding(dp(14), dp(14), dp(14), dp(12))
                layoutParams = LinearLayout.LayoutParams(
                    0, dp(96), 1f
                ).apply { marginEnd = if (index % columns == columns - 1) 0 else dp(10) }
                setOnClickListener { openCategory(category.id, name) }
            }
            tile.addView(TextView(this).apply {
                text = name
                setTextColor(0xff212121.toInt())
                textSize = 15f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            tile.addView(TextView(this).apply {
                text = category.getDescription(locales).orEmpty()
                setTextColor(0xff757575.toInt())
                textSize = 12f
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    0,
                    1f,
                ).apply { topMargin = dp(4) }
            })
            val countView = TextView(this).apply {
                setTextColor(0xff00796b.toInt())
                textSize = 12f
            }
            countViews[category.id] = countView
            tile.addView(countView)
            row?.addView(tile)
        }

        lifecycleScope.launch {
            items.forEach { category ->
                val count = withContext(kotlinx.coroutines.Dispatchers.IO) {
                    Fdroid.db.getAppDao().getNumberOfAppsInCategory(category.id)
                }
                countViews[category.id]?.text = getString(R.string.phone_apps_count, count)
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_TAB, selectedTab)
    }
}
