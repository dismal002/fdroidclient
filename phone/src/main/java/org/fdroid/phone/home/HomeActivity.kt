package org.fdroid.phone.home

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import org.fdroid.phone.Fdroid
import org.fdroid.phone.R
import org.fdroid.phone.browse.AppCard
import org.fdroid.phone.browse.AppRepository
import org.fdroid.phone.browse.TvRow
import org.fdroid.phone.details.DetailsActivity
import org.fdroid.phone.search.SearchActivity
import org.fdroid.phone.settings.SettingsActivity
import kotlinx.coroutines.launch

/**
 * The phone store front: a green bar with the search field, a tab strip, the
 * category chips and the rows of cover-art cards. Rows are derived from the
 * index (newest, recently updated, then categories) - there is no dedicated
 * games row.
 */
class HomeActivity : AppCompatActivity() {

    private companion object {
        const val STATE_TAB = "org.fdroid.phone.state.TAB"
        const val DRAWER_APPS = 0
        const val DRAWER_MY_APPS = 1
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
    private lateinit var chips: LinearLayout

    private var cards: List<AppCard> = emptyList()
    private var lastItemCount = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_phone_home)
        repository = AppRepository(this)
        rows = findViewById(R.id.rows)
        chips = findViewById(R.id.chips)
        drawer = findViewById(R.id.drawer)
        drawerItems = findViewById(R.id.drawer_items)
        buildDrawer()
        findViewById<View>(R.id.menu_button).setOnClickListener {
            // The home screen is the apps page, so that is the row showing as selected.
            selectedDrawer = DRAWER_APPS
            buildDrawer()
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
        indexStatus.setOnClickListener { Fdroid.sync(this) }
        Fdroid.syncState.observe(this) { renderIndexStatus() }

        tabApps.setOnClickListener { selectTab(tabApps, animate = true) }
        tabCategories.setOnClickListener { selectTab(tabCategories, animate = true) }

        // Restore the tab the user was last on, otherwise start on the apps rows.
        selectedTab = savedInstanceState?.getInt(STATE_TAB) ?: R.id.tab_apps
        tabsRow.post {
            selectTab(findViewById(selectedTab), animate = false)
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

    private fun renderIndexStatus() {
        if (cards.isNotEmpty()) {
            indexStatus.visibility = View.GONE
            return
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

    private fun renderChips() {
        chips.removeAllViews()
        val categories = cards.flatMap { it.categories }
            .filterNot { it.endsWith("Game") }
            .groupingBy { it }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
            .map { it.key }
            .take(8)
        categories.forEach { category ->
            val chip = TextView(this)
            chip.text = category
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
            chip.setOnClickListener { openCategory(category) }
            chips.addView(chip)
        }
    }

    private fun renderRows() {
        rows.removeAllViews()
        val newest = cards.sortedByDescending { it.lastUpdated }
        val newRow = newest.take(20)
        val takenPackages = newRow.map { it.packageName }.toSet()
        val withArt = cards.filter {
            (it.featureGraphic != null || it.banner != null) && it.packageName !in takenPackages
        }
        val recommended = (withArt + newest.filter { it.packageName !in takenPackages })
            .distinctBy { it.packageName }
            .take(20)

        val sections = listOf(
            TvRow(getString(R.string.phone_row_new), newRow),
            TvRow(getString(R.string.phone_row_recommended), recommended),
        ) + cards.flatMap { it.categories }
            .filterNot { it.endsWith("Game") }
            .distinct()
            .sorted()
            .take(4)
            .map { category -> TvRow(category, cards.filter { it.categories.contains(category) }) }

        sections.forEach { section ->
            if (section.cards.isEmpty()) return@forEach
            addSection(section)
        }
    }

    private fun addSection(section: TvRow) {
        val header = LayoutInflater.from(this).inflate(R.layout.item_phone_section_header, rows, false)
        header.findViewById<TextView>(R.id.section_title).text = section.title
        header.findViewById<View>(R.id.section_more).setOnClickListener {
            openSection(section.title, section.cards)
        }
        rows.addView(header)

        val scroller = HorizontalScrollView(this)
        scroller.isHorizontalScrollBarEnabled = false
        scroller.clipToPadding = false
        scroller.setPadding(dp(16), 0, dp(16), 0)
        val strip = LinearLayout(this)
        strip.orientation = LinearLayout.HORIZONTAL
        scroller.addView(strip)

        section.cards.take(20).forEach { card ->
            val view = LayoutInflater.from(this).inflate(R.layout.item_phone_card, strip, false)
            bindCard(view, card)
            val params = LinearLayout.LayoutParams(dp(132), LinearLayout.LayoutParams.WRAP_CONTENT)
            params.marginEnd = dp(12)
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

    private fun openCategory(category: String) {
        startActivity(SectionListActivity.categoryIntent(this, category))
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
    private data class DrawerRow(
        val label: Int,
        val icon: Int,
        val tint: Int,
        val highlight: Boolean = false,
        val dividerBefore: Boolean = false,
        val action: () -> Unit,
    )

    private var selectedDrawer = DRAWER_APPS

    private fun buildDrawer() {
        drawerItems.removeAllViews()
        val appsSelected = selectedDrawer == DRAWER_APPS
        val rows = listOf(
            DrawerRow(
                R.string.phone_drawer_apps,
                R.drawable.ic_drawer_apps,
                if (appsSelected) 0xff558b2f.toInt() else 0xff757575.toInt(),
                highlight = appsSelected,
            ) {
                drawer.closeDrawers()
            },
            DrawerRow(
                R.string.phone_drawer_my_apps,
                R.drawable.all_apps,
                if (appsSelected) 0xff757575.toInt() else 0xff00796b.toInt(),
            ) {
                selectedDrawer = DRAWER_MY_APPS
                buildDrawer()
                openMyApps()
            },
            DrawerRow(R.string.phone_drawer_settings, R.drawable.ic_settings, 0xff757575.toInt(), dividerBefore = true) {
                startActivity(Intent(this, SettingsActivity::class.java))
            },
        )
        rows.forEach { row ->
            if (row.dividerBefore) {
                drawerItems.addView(View(this).apply {
                    setBackgroundColor(0xffe0e0e0.toInt())
                }, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(1)
                ).apply {
                    topMargin = dp(8)
                    bottomMargin = dp(8)
                })
            }
            drawerItems.addView(drawerRow(row))
        }
    }

    private fun drawerRow(row: DrawerRow): View {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), 0, dp(16), 0)
            isClickable = true
            isFocusable = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(48)
            )
            if (row.highlight) setBackgroundColor(0xffeeeeee.toInt())
            setOnClickListener { row.action() }
        }
        container.addView(ImageView(this).apply {
            setImageResource(row.icon)
            setColorFilter(row.tint)
            layoutParams = LinearLayout.LayoutParams(dp(22), dp(22))
        })
        container.addView(TextView(this).apply {
            text = getString(row.label)
            textSize = 15f
            setTextColor(0xff212121.toInt())
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            ).apply { marginStart = dp(20) }
        })
        return container
    }

    private fun openMyApps() {
        drawer.closeDrawers()
        lifecycleScope.launch {
            val installed = repository.installedPackages()
            startActivity(
                SectionListActivity.intent(
                    this@HomeActivity,
                    getString(R.string.phone_drawer_my_apps),
                    installed.toTypedArray(),
                )
            )
        }
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
        val counts = cards.flatMap { it.categories }
            .filterNot { it.endsWith("Game") }
            .groupingBy { it }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
        var row: LinearLayout? = null
        counts.forEachIndexed { index, entry ->
            if (index % 2 == 0) {
                row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).apply { bottomMargin = dp(10) }
                }
                categoriesList.addView(row)
            }
            val tile = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(0xffffffff.toInt())
                setPadding(dp(14), dp(14), dp(14), dp(14))
                layoutParams = LinearLayout.LayoutParams(
                    0, dp(86), 1f
                ).apply { marginEnd = if (index % 2 == 0) dp(10) else 0 }
                setOnClickListener { openCategory(entry.key) }
            }
            tile.addView(TextView(this).apply {
                text = entry.key
                setTextColor(0xff212121.toInt())
                textSize = 15f
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    0,
                    1f,
                )
            })
            tile.addView(TextView(this).apply {
                text = getString(R.string.phone_apps_count, entry.value)
                setTextColor(0xff757575.toInt())
                textSize = 13f
            })
            row?.addView(tile)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_TAB, selectedTab)
    }
}
