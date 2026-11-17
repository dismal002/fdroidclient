package org.fdroid.phone.home

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.format.DateUtils
import android.text.format.Formatter
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.os.LocaleListCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.bumptech.glide.Glide
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fdroid.database.AppListItem
import org.fdroid.database.AppOverviewItem
import org.fdroid.database.UpdatableApp
import org.fdroid.index.v2.FileV2
import org.fdroid.phone.Fdroid
import org.fdroid.phone.R
import org.fdroid.phone.details.DetailsActivity
import org.fdroid.phone.history.InstallHistory
import org.fdroid.phone.install.InstallRequest
import org.fdroid.phone.install.InstallService
import org.fdroid.phone.search.SearchActivity
import java.io.File

/**
 * "My apps", following the Play Store my-apps screen from the reference APK:
 * the Installed tab groups the pending updates under an "Updates" header with
 * an "UPDATE ALL" action, followed by an "Up to date" section with a count. The
 * All tab is the library: installed apps plus apps installed in the past.
 */
class MyAppsActivity : AppCompatActivity() {

    companion object {
        fun intent(context: Context): Intent = Intent(context, MyAppsActivity::class.java)
    }

    private lateinit var drawer: DrawerLayout
    private lateinit var drawerItems: LinearLayout
    private lateinit var rows: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var empty: TextView
    private lateinit var tabInstalled: TextView
    private lateinit var tabAll: TextView
    private lateinit var indicator: View

    private val locales: LocaleListCompat = LocaleListCompat.getDefault()

    private var installedItems: List<AppListItem> = emptyList()
    private var libraryItems: List<AppOverviewItem> = emptyList()
    private var updates: List<UpdatableApp> = emptyList()
    private var updatesLoaded = false
    private var tab = TAB_INSTALLED
    private var queue: List<InstallRequest> = emptyList()
    private var installingPackage: String? = null

    private val installReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val packageName = intent.getStringExtra(InstallService.EXTRA_PACKAGE) ?: return
            when (intent.getStringExtra(InstallService.EXTRA_STATE)) {
                InstallService.STATE_DOWNLOADING,
                InstallService.STATE_INSTALLING,
                -> {
                    installingPackage = packageName
                    render()
                }
                InstallService.STATE_DONE -> {
                    installingPackage = null
                    updatesLoaded = false
                    loadUpdates()
                    startNextInQueue()
                }
                InstallService.STATE_ERROR, InstallService.STATE_CANCELLED -> {
                    installingPackage = null
                    queue = emptyList()
                    render()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_phone_myapps)

        drawer = findViewById(R.id.myapps_drawer)
        drawerItems = findViewById(R.id.myapps_drawer_items)
        rows = findViewById(R.id.myapps_rows)
        scroll = findViewById(R.id.myapps_scroll)
        empty = findViewById(R.id.myapps_empty)
        tabInstalled = findViewById(R.id.myapps_tab_installed)
        tabAll = findViewById(R.id.myapps_tab_all)
        indicator = findViewById(R.id.myapps_indicator)

        findViewById<View>(R.id.myapps_menu).setOnClickListener {
            PhoneDrawer.build(this, drawer, drawerItems, PhoneDrawer.MY_APPS)
            drawer.openDrawer(Gravity.START)
        }
        findViewById<View>(R.id.myapps_search).setOnClickListener {
            startActivity(Intent(this, SearchActivity::class.java))
        }
        tabInstalled.setOnClickListener { selectTab(TAB_INSTALLED) }
        tabAll.setOnClickListener { selectTab(TAB_ALL) }

        Fdroid.db.getAppDao().getInstalledAppListItems(packageManager).observe(this) { items ->
            installedItems = items
            render()
        }
        loadLibrary()
        loadUpdates()
        render()
    }

    override fun onResume() {
        super.onResume()
        LocalBroadcastManager.getInstance(this).registerReceiver(
            installReceiver,
            IntentFilter(InstallService.ACTION_INSTALL_STATE),
        )
        // Installs made from the details screen change this list too.
        loadUpdates()
    }

    override fun onPause() {
        LocalBroadcastManager.getInstance(this).unregisterReceiver(installReceiver)
        super.onPause()
    }

    /** The All tab: everything that was ever installed, which the history knows about. */
    private fun loadLibrary() {
        val packages = InstallHistory.events(this).map { it.packageName }.distinct()
        if (packages.isEmpty()) return
        Fdroid.db.getAppDao().getApps(packages).observe(this) { items ->
            libraryItems = items
            render()
        }
    }

    private fun loadUpdates() {
        if (updatesLoaded) return
        updatesLoaded = true
        lifecycleScope.launch {
            val updatable = withContext(Dispatchers.IO) {
                try {
                    Fdroid.updateChecker.getUpdatableApps()
                } catch (e: Exception) {
                    emptyList()
                }
            }
            updates = updatable
            render()
        }
    }

    private fun selectTab(newTab: Int) {
        if (tab == newTab) return
        tab = newTab
        render()
    }

    private fun render() {
        rows.removeAllViews()
        val unselected = 0xb3ffffff.toInt()
        tabInstalled.setTextColor(if (tab == TAB_INSTALLED) -1 else unselected)
        tabAll.setTextColor(if (tab == TAB_ALL) -1 else unselected)
        tabInstalled.post {
            val params = indicator.layoutParams as LinearLayout.LayoutParams
            params.marginStart = if (tab == TAB_INSTALLED) {
                dp(16)
            } else {
                dp(16) + tabInstalled.width + dp(24)
            }
            params.width = if (tab == TAB_INSTALLED) tabInstalled.width else tabAll.width
            indicator.layoutParams = params
        }

        val installedMap = installedItems.associateBy { it.packageName }
        val updateMap = updates.associateBy { it.packageName }

        if (tab == TAB_INSTALLED) {
            val updateRows = updates.sortedBy { (it.name ?: it.packageName).lowercase() }
            if (updateRows.isNotEmpty()) {
                addSectionHeader(getString(R.string.myapps_updates_header), getString(R.string.myapps_update_all)) {
                    updateAll(updateRows)
                }
                updateRows.forEach { updatable ->
                    addUpdateRow(updatable, installedMap[updatable.packageName])
                }
            }
            val upToDate = installedItems
                .filter { !updateMap.containsKey(it.packageName) }
                .sortedBy { (it.name ?: it.packageName).lowercase() }
            if (upToDate.isNotEmpty()) {
                addCountHeader(getString(R.string.myapps_up_to_date), upToDate.size)
            }
            upToDate.forEach { addInstalledRow(it) }
            empty.text = getString(R.string.my_apps_empty)
            empty.visibility = if (rows.childCount == 0) View.VISIBLE else View.GONE
        } else {
            val all = ArrayList<LibraryEntry>()
            installedItems.forEach { item ->
                all.add(
                    LibraryEntry(
                        packageName = item.packageName,
                        name = item.name ?: item.packageName,
                        repoId = item.repoId,
                        icon = item.getIcon(locales),
                        summary = item.summary,
                        installed = true,
                    ),
                )
            }
            libraryItems
                .filter { !installedMap.containsKey(it.packageName) }
                .forEach { item ->
                    all.add(
                        LibraryEntry(
                            packageName = item.packageName,
                            name = item.name ?: item.packageName,
                            repoId = item.repoId,
                            icon = item.getIcon(locales),
                            summary = item.summary,
                            installed = false,
                        ),
                    )
                }
            all.sortedBy { it.name.lowercase() }.forEach { entry ->
                val updatable = updateMap[entry.packageName]
                addAppRow(
                    packageName = entry.packageName,
                    name = entry.name,
                    iconRepoId = entry.repoId,
                    icon = entry.icon,
                    meta = entry.summary.orEmpty(),
                    action = when {
                        updatable != null -> getString(R.string.action_update)
                        entry.installed -> getString(R.string.myapps_installed_label)
                        else -> getString(R.string.action_install)
                    },
                    actionEnabled = updatable != null || !entry.installed,
                    actionColor = if (updatable != null || !entry.installed) {
                        ContextCompat.getColor(this, R.color.play_green)
                    } else {
                        ContextCompat.getColor(this, R.color.play_text_secondary)
                    },
                ) {
                    if (updatable != null) update(updatable) else openDetails(entry.packageName)
                }
            }
            empty.text = getString(R.string.my_apps_empty)
            empty.visibility = if (all.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private data class LibraryEntry(
        val packageName: String,
        val name: String,
        val repoId: Long,
        val icon: FileV2?,
        val summary: String?,
        val installed: Boolean,
    )

    private fun addUpdateRow(updatable: UpdatableApp, item: AppListItem?) {
        addAppRow(
            packageName = updatable.packageName,
            name = updatable.name ?: item?.name ?: updatable.packageName,
            iconRepoId = updatable.repoId,
            icon = item?.getIcon(locales) ?: updatable.getIcon(locales),
            meta = metaText(updatable.update.file.size, item?.lastUpdated ?: 0L),
            action = getString(R.string.action_update),
            actionEnabled = true,
            actionColor = ContextCompat.getColor(this, R.color.play_green),
        ) { update(updatable) }
    }

    private fun addInstalledRow(item: AppListItem) {
        addAppRow(
            packageName = item.packageName,
            name = item.name ?: item.packageName,
            iconRepoId = item.repoId,
            icon = item.getIcon(locales),
            meta = metaText(installedSize(item.packageName), historyTime(item.packageName) ?: item.lastUpdated),
            action = getString(R.string.myapps_installed_label),
            actionEnabled = false,
            actionColor = ContextCompat.getColor(this, R.color.play_text_secondary),
        ) { openDetails(item.packageName) }
    }

    private fun addSectionHeader(title: String, action: String?, onAction: (() -> Unit)?) {
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(6))
        }
        header.addView(
            TextView(this).apply {
                text = title
                setTextColor(ContextCompat.getColor(this@MyAppsActivity, R.color.play_text_primary))
                textSize = 16f
                setTypeface(typeface, Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            },
        )
        if (action != null && onAction != null) {
            header.addView(
                TextView(this).apply {
                    text = action
                    isAllCaps = true
                    letterSpacing = 0.06f
                    gravity = Gravity.CENTER
                    setTextColor(-1)
                    setBackgroundResource(R.drawable.bg_phone_primary_button)
                    setPadding(dp(14), dp(7), dp(14), dp(7))
                    setOnClickListener { onAction() }
                },
            )
        }
        rows.addView(header)
    }

    /** "Up to date    29" — the count is grey and right aligned, without a button. */
    private fun addCountHeader(title: String, count: Int) {
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(6))
        }
        header.addView(
            TextView(this).apply {
                text = title
                setTextColor(ContextCompat.getColor(this@MyAppsActivity, R.color.play_text_primary))
                textSize = 16f
                setTypeface(typeface, Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            },
        )
        header.addView(
            TextView(this).apply {
                text = count.toString()
                setTextColor(ContextCompat.getColor(this@MyAppsActivity, R.color.play_text_secondary))
                textSize = 16f
            },
        )
        rows.addView(header)
    }

    private fun addAppRow(
        packageName: String,
        name: String,
        iconRepoId: Long,
        icon: FileV2?,
        meta: String,
        action: String,
        actionEnabled: Boolean,
        actionColor: Int,
        onAction: () -> Unit,
    ) {
        val view = LayoutInflater.from(this).inflate(R.layout.item_phone_myapps_row, rows, false)
        val iconView = view.findViewById<ImageView>(R.id.myapps_icon)
        val model = Fdroid.imageModel(iconRepoId, icon)
        if (model != null) {
            Glide.with(iconView).load(model).into(iconView)
        }
        view.findViewById<TextView>(R.id.myapps_name).text = name
        view.findViewById<TextView>(R.id.myapps_meta).text = meta

        val button = view.findViewById<TextView>(R.id.myapps_action)
        val busy = installingPackage == packageName
        button.text = if (busy) getString(R.string.action_installing) else action
        button.setTextColor(actionColor)
        button.isEnabled = !busy && actionEnabled
        button.alpha = if (busy) 0.6f else 1f
        button.setOnClickListener { if (actionEnabled) onAction() }
        view.setOnClickListener { openDetails(packageName) }
        rows.addView(view)
    }

    private fun metaText(sizeBytes: Long?, updatedAt: Long): String {
        val parts = ArrayList<String>()
        if (sizeBytes != null && sizeBytes > 0) {
            parts.add(Formatter.formatFileSize(this, sizeBytes))
        }
        if (updatedAt > 0) {
            parts.add(getString(R.string.myapps_meta_updated, relativeTime(updatedAt)))
        }
        return parts.joinToString(" · ")
    }

    private fun update(updatable: UpdatableApp) {
        if (!canInstallPackages()) {
            Toast.makeText(this, R.string.install_unknown_sources, Toast.LENGTH_LONG).show()
            openUnknownSourcesSettings()
            return
        }
        startInstall(requestFor(updatable))
    }

    private fun updateAll(updatable: List<UpdatableApp>) {
        if (updatable.isEmpty()) return
        if (!canInstallPackages()) {
            Toast.makeText(this, R.string.install_unknown_sources, Toast.LENGTH_LONG).show()
            openUnknownSourcesSettings()
            return
        }
        queue = updatable.map { requestFor(it) }
        startNextInQueue()
    }

    private fun startNextInQueue() {
        val next = queue.firstOrNull() ?: return
        queue = queue.drop(1)
        startInstall(next)
    }

    private fun startInstall(request: InstallRequest) {
        installingPackage = request.packageName
        InstallService.start(this, request)
        render()
    }

    private fun requestFor(updatable: UpdatableApp) = InstallRequest(
        packageName = updatable.packageName,
        label = updatable.name ?: updatable.packageName,
        repoId = updatable.update.repoId,
        fileName = updatable.update.file.name,
        sha256 = updatable.update.file.sha256,
        size = updatable.update.file.size ?: 0L,
        versionName = updatable.update.manifest.versionName ?: "",
    )

    private fun openDetails(packageName: String) {
        startActivity(DetailsActivity.intent(this, packageName))
    }

    /** The install/update time, so "Updated 3 hr. ago" matches what actually happened. */
    private fun historyTime(packageName: String): Long? =
        InstallHistory.events(this).firstOrNull {
            it.packageName == packageName && it.action != InstallHistory.ACTION_UNINSTALLED
        }?.timestamp

    private fun installedSize(packageName: String): Long? = try {
        File(packageManager.getApplicationInfo(packageName, 0).sourceDir).length()
    } catch (e: Exception) {
        null
    }

    private fun relativeTime(timestamp: Long): String {
        val now = System.currentTimeMillis()
        val week = 7 * DateUtils.DAY_IN_MILLIS
        return if (now - timestamp in 0..week) {
            DateUtils.getRelativeTimeSpanString(
                timestamp,
                now,
                DateUtils.MINUTE_IN_MILLIS,
                DateUtils.FORMAT_ABBREV_RELATIVE,
            ).toString()
        } else {
            java.text.SimpleDateFormat("MMM d", java.util.Locale.getDefault())
                .format(java.util.Date(timestamp))
        }
    }

    private fun canInstallPackages(): Boolean = if (android.os.Build.VERSION.SDK_INT >= 26) {
        packageManager.canRequestPackageInstalls()
    } else {
        true
    }

    private fun openUnknownSourcesSettings() {
        val intent = if (android.os.Build.VERSION.SDK_INT >= 26) {
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))
        } else {
            Intent(Settings.ACTION_SECURITY_SETTINGS)
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            // Nothing else to do; the install stays blocked.
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onBackPressed() {
        if (drawer.isDrawerOpen(Gravity.START)) {
            drawer.closeDrawers()
        } else {
            super.onBackPressed()
        }
    }
}

private const val TAB_INSTALLED = 0
private const val TAB_ALL = 1
