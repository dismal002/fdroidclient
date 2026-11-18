package org.fdroid.wear.browse

import android.content.Context
import android.text.format.Formatter
import androidx.core.content.pm.PackageInfoCompat
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.room.RoomDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.fdroid.database.AppListItem
import org.fdroid.database.AppOverviewItem
import org.fdroid.index.v2.FileV2
import org.fdroid.wear.Fdroid
import org.fdroid.wear.R
import org.fdroid.wear.search.SearchText

/**
 * The data a single store card needs.
 * Deliberately detached from the database so presenters never touch it.
 */
data class AppCard(
    val repoId: Long,
    val packageName: String,
    val name: String,
    val summary: String?,
    val icon: FileV2?,
    val featureGraphic: FileV2?,
    val banner: FileV2?,
    val installed: Boolean,
    val updateAvailable: Boolean,
    val isCompatible: Boolean,
    val categories: List<String>,
    val added: Long,
    val lastUpdated: Long,
) {
    /** F-Droid has fine grained game categories (Action Game, Puzzle Game, ...). */
    val isGame: Boolean get() = categories.any { it.endsWith("Game") }
}

data class TvRow(val title: String, val cards: List<AppCard>)

class AppRepository(private val context: Context) {

    private companion object {
        /** The store treats apps added in the last two weeks as new. */
        const val NEW_DAYS_MILLIS = 14L * 24 * 60 * 60 * 1000

        /** Screenshot types in the order the phone page prefers them. */
        val SCREENSHOT_TYPES = listOf("phone", "sevenInch", "tenInch", "tv", "wear")
    }

    private data class AppDetail(
        val featureGraphic: FileV2? = null,
        val banner: FileV2? = null,
        val categories: List<String> = emptyList(),
    )

    private val packageManager = context.packageManager
    private val locales: LocaleListCompat = LocaleListCompat.getDefault()

    @Volatile
    private var details: Map<String, AppDetail>? = null

    @Volatile
    private var searchIndex: Map<String, String>? = null

    fun overview(): LiveData<List<AppOverviewItem>> =
        Fdroid.db.getAppDao().getAppOverviewItems(800)

    fun invalidateDetails() {
        details = null
        searchIndex = null
    }

    private fun newCutoff(): Long = System.currentTimeMillis() - NEW_DAYS_MILLIS

    /** Apps added in the last two weeks, newest first. */
    fun newApps(): LiveData<List<AppOverviewItem>> =
        Fdroid.db.getAppDao().getNewApps(newCutoff(), 100)

    /** Apps that were updated recently but are not new. */
    fun recentlyUpdatedApps(): LiveData<List<AppOverviewItem>> =
        Fdroid.db.getAppDao().getRecentlyUpdatedApps(newCutoff(), 200)

    /** The store's editorial "most downloaded" list, curated in the bundled asset. */
    fun curatedApps(): LiveData<List<AppOverviewItem>> {
        val packages = mostDownloadedPackages()
        if (packages.isEmpty()) {
            return MutableLiveData(emptyList())
        }
        return Fdroid.db.getAppDao().getApps(packages)
    }

    fun mostDownloadedPackages(): List<String> = try {
        val text = context.assets.open("most_downloaded_apps.json").bufferedReader().use { it.readText() }
        Regex("\"([^\"]+)\"").findAll(text).map { it.groupValues[1] }.toList()
    } catch (e: Exception) {
        emptyList()
    }

    /** Keeps the curated order of [packages], dropping apps this index does not have. */
    fun inCuratedOrder(cards: List<AppCard>, packages: List<String>): List<AppCard> {
        val byPackage = cards.associateBy { it.packageName }
        return packages.mapNotNull { byPackage[it] }
    }

    /**
     * Normalized searchable text for every app in the index, built once and
     * reused for each keystroke. It covers the name and summary in every locale
     * the index provides, plus the author and package name, which is what makes
     * search work across languages.
     */
    suspend fun searchIndex(): Map<String, String> = withContext(Dispatchers.IO) {
        searchIndex ?: buildSearchIndex().also { if (it.isNotEmpty()) searchIndex = it }
    }

    private fun buildSearchIndex(): Map<String, String> {
        val entries = try {
            Fdroid.db.getAppDao().getSearchEntries()
        } catch (e: Exception) {
            return emptyMap()
        }
        val result = HashMap<String, String>(entries.size * 2)
        entries.forEach { entry ->
            val text = buildString {
                append(SearchText.normalize(entry.packageName))
                entry.name?.values?.forEach { append(' ').append(SearchText.normalize(it)) }
                entry.summary?.values?.forEach { append(' ').append(SearchText.normalize(it)) }
                entry.authorName?.let { append(' ').append(SearchText.normalize(it)) }
            }
            result[entry.packageName] = text
        }
        return result
    }

    suspend fun installedPackages(): Set<String> = withContext(Dispatchers.IO) {
        try {
            packageManager.getInstalledPackages(0).map { it.packageName }.toSet()
        } catch (e: Exception) {
            emptySet()
        }
    }

    suspend fun buildCards(
        items: List<AppOverviewItem>,
        updates: Set<String>,
        installed: Set<String>,
    ): List<AppCard> = withContext(Dispatchers.IO) {
        val details = details ?: loadDetails().also { details = it }
        items.map { item ->
            val detail = details[item.packageName]
            AppCard(
                repoId = item.repoId,
                packageName = item.packageName,
                name = item.name ?: item.packageName,
                summary = item.summary,
                icon = item.getIcon(locales),
                featureGraphic = detail?.featureGraphic,
                banner = detail?.banner,
                installed = installed.contains(item.packageName),
                updateAvailable = updates.contains(item.packageName),
                isCompatible = item.isCompatible,
                categories = detail?.categories ?: emptyList(),
                added = item.added,
                lastUpdated = item.lastUpdated,
            )
        }
    }

    /**
     * Artwork and categories for every app come from a single pass over the two
     * tables involved. Asking the database per app would mean thousands of
     * queries just to fill the home screen.
     */
    private fun loadDetails(): Map<String, AppDetail> {
        val result = HashMap<String, AppDetail>(6000)
        val room = Fdroid.db as? RoomDatabase ?: return result
        val db = room.openHelper.readableDatabase
        db.query("SELECT packageName, categories FROM AppMetadata").use { cursor ->
            while (cursor.moveToNext()) {
                val packageName = cursor.getString(0) ?: continue
                val categories = cursor.getString(1)
                    ?.split(',')
                    ?.filter { it.isNotBlank() }
                    ?: emptyList()
                result[packageName] = AppDetail(categories = categories)
            }
        }
        db.query(
            "SELECT packageName, type, name, sha256, size FROM LocalizedFile " +
                "WHERE type IN ('featureGraphic', 'tvBanner')"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val packageName = cursor.getString(0) ?: continue
                val type = cursor.getString(1)
                val name = cursor.getString(2) ?: continue
                val file = FileV2(
                    name = name,
                    sha256 = cursor.getString(3),
                    size = if (cursor.isNull(4)) null else cursor.getLong(4),
                    ipfsCidV1 = null,
                )
                val current = result[packageName] ?: AppDetail()
                result[packageName] = if (type == "featureGraphic") {
                    current.copy(featureGraphic = file)
                } else {
                    current.copy(banner = file)
                }
            }
        }
        return result
    }

    /** Cards for the apps that are installed and present in the index. */
    /**
     * The screenshots of one app, in the best matching locale of the most
     * suitable screenshot type the index provides.
     *
     * The index stores screenshots per type and locale (phone, sevenInch,
     * tenInch, tv, wear) in a separate list table, not as single files, so
     * reading them from there is what the carousel needs.
     */
    suspend fun screenshots(repoId: Long, packageName: String): List<FileV2> =
        withContext(Dispatchers.IO) {
            val room = Fdroid.db as? RoomDatabase ?: return@withContext emptyList()
            val byType = LinkedHashMap<String, LinkedHashMap<String, MutableList<FileV2>>>()
            room.openHelper.readableDatabase.query(
                "SELECT type, locale, name, sha256, size FROM LocalizedFileList " +
                    "WHERE repoId = ? AND packageName = ? ORDER BY rowid",
                arrayOf(repoId, packageName),
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val type = cursor.getString(0) ?: continue
                    val locale = cursor.getString(1) ?: continue
                    val name = cursor.getString(2) ?: continue
                    val file = FileV2(
                        name = name,
                        sha256 = cursor.getString(3),
                        size = if (cursor.isNull(4)) null else cursor.getLong(4),
                        ipfsCidV1 = null,
                    )
                    byType.getOrPut(type) { LinkedHashMap() }
                        .getOrPut(locale) { ArrayList() }
                        .add(file)
                }
            }
            for (type in SCREENSHOT_TYPES) {
                val locales = byType[type] ?: continue
                val best = bestLocale(locales.keys) ?: continue
                val files = locales.getValue(best)
                if (files.isNotEmpty()) return@withContext files
            }
            emptyList()
        }

    /** Picks the device's locale (or its language) when the index has it. */
    private fun bestLocale(available: Set<String>): String? {
        val device = LocaleListCompat.getDefault()
        for (index in 0 until device.size()) {
            val locale = device.get(index) ?: continue
            val tag = locale.toLanguageTag()
            available.firstOrNull { it.equals(tag, ignoreCase = true) }?.let { return it }
            available.firstOrNull { it.substringBefore('-').equals(locale.language, ignoreCase = true) }
                ?.let { return it }
        }
        return available.firstOrNull()
    }

    suspend fun installedCards(
        items: List<AppListItem>,
        updates: Set<String>,
    ): List<AppCard> = withContext(Dispatchers.IO) {
        val details = details ?: loadDetails().also { details = it }
        items.map { item ->
            val detail = details[item.packageName]
            AppCard(
                repoId = item.repoId,
                packageName = item.packageName,
                name = item.name ?: item.packageName,
                summary = item.summary,
                icon = item.getIcon(locales),
                featureGraphic = detail?.featureGraphic,
                banner = detail?.banner,
                installed = true,
                updateAvailable = updates.contains(item.packageName),
                isCompatible = item.isCompatible,
                categories = detail?.categories ?: emptyList(),
                added = item.lastUpdated,
                lastUpdated = item.lastUpdated,
            )
        }
    }

    suspend fun cardsFromListItems(items: List<AppListItem>): List<AppCard> =
        withContext(Dispatchers.IO) {
            items.map { item ->
                AppCard(
                    repoId = item.repoId,
                    packageName = item.packageName,
                    name = item.name ?: item.packageName,
                    summary = item.summary,
                    icon = item.getIcon(locales),
                    featureGraphic = null,
                    banner = null,
                    installed = item.installedVersionCode != null,
                    updateAvailable = false,
                    isCompatible = item.isCompatible,
                    categories = emptyList(),
                    added = item.lastUpdated,
                    lastUpdated = item.lastUpdated,
                )
            }
        }

    /**
     * Cards for the vertical list rows. Those rows only show the icon, name and
     * summary, so the scan for artwork and categories (the expensive part of
     * [buildCards]) is skipped and a long category list opens immediately.
     */
    suspend fun listCards(
        items: List<AppOverviewItem>,
        updates: Set<String>,
        installed: Set<String>,
    ): List<AppCard> = withContext(Dispatchers.IO) {
        items.map { item ->
            AppCard(
                repoId = item.repoId,
                packageName = item.packageName,
                name = item.name ?: item.packageName,
                summary = item.summary,
                icon = item.getIcon(locales),
                featureGraphic = null,
                banner = null,
                installed = installed.contains(item.packageName),
                updateAvailable = updates.contains(item.packageName),
                isCompatible = item.isCompatible,
                categories = emptyList(),
                added = item.added,
                lastUpdated = item.lastUpdated,
            )
        }
    }

    fun isInstalled(packageName: String): Boolean = installedVersionCode(packageName) != null

    fun installedVersionCode(packageName: String): Long? = try {
        PackageInfoCompat.getLongVersionCode(packageManager.getPackageInfo(packageName, 0))
    } catch (e: Exception) {
        null
    }

    fun installedVersionName(packageName: String): String? = try {
        packageManager.getPackageInfo(packageName, 0).versionName
    } catch (e: Exception) {
        null
    }

    fun sizeText(bytes: Long?): String =
        if (bytes == null || bytes <= 0) "" else Formatter.formatFileSize(context, bytes)

    /**
     * The 2015 store groups the home screen by store curation. F-Droid has no
     * editorial data, so the rows are derived from the index in the closest
     * meaningful way: apps with TV art first, then the newest and recently updated,
     * then category rows.
     */
    fun homeRows(cards: List<AppCard>): List<TvRow> {
        val withArt = cards.filter { it.featureGraphic != null || it.banner != null }
        val featured = (withArt.sortedByDescending { it.lastUpdated } +
            cards.filter { it.featureGraphic == null && it.banner == null })
            .distinctBy { it.packageName }
            .take(20)
        val rows = ArrayList<TvRow>()
        rows.addIfNotEmpty(context.getString(R.string.row_featured), featured)
        rows.addIfNotEmpty(
            context.getString(R.string.row_recently_updated),
            cards.sortedByDescending { it.lastUpdated }.take(20),
        )
        rows.addIfNotEmpty(
            context.getString(R.string.row_new),
            cards.sortedByDescending { it.added }.take(20),
        )
        rows.addIfNotEmpty(
            context.getString(R.string.row_games),
            cards.filter { it.isGame }.take(20),
        )
        rows.addIfNotEmpty(
            context.getString(R.string.row_multimedia),
            cards.filter { it.categories.contains("Multimedia") }.take(20),
        )
        rows.addIfNotEmpty(
            context.getString(R.string.row_internet),
            cards.filter { it.categories.contains("Internet") }.take(20),
        )
        return rows
    }

    fun gamesRows(cards: List<AppCard>): List<TvRow> {
        val games = cards.filter { it.isGame }
        val rows = ArrayList<TvRow>()
        rows.addIfNotEmpty(context.getString(R.string.row_games), games.take(24))
        rows.addIfNotEmpty(
            context.getString(R.string.row_recently_updated),
            games.sortedByDescending { it.lastUpdated }.take(24),
        )
        rows.addIfNotEmpty(
            context.getString(R.string.row_new),
            games.sortedByDescending { it.added }.take(24),
        )
        return rows
    }

    fun myAppsRows(cards: List<AppCard>): List<TvRow> {
        val updates = cards.filter { it.installed && it.updateAvailable }
        val installed = cards.filter { it.installed }.sortedBy { it.name.lowercase() }
        val recentlyUpdated = installed
            .filter { card -> updates.none { it.packageName == card.packageName } }
            .sortedByDescending { it.lastUpdated }
        val rows = ArrayList<TvRow>()
        rows.addIfNotEmpty(context.getString(R.string.row_updates), updates)
        rows.addIfNotEmpty(context.getString(R.string.row_recently_updated), recentlyUpdated.take(24))
        rows.addIfNotEmpty(context.getString(R.string.row_installed), installed)
        return rows
    }

    private fun MutableList<TvRow>.addIfNotEmpty(title: String, cards: List<AppCard>) {
        if (cards.isNotEmpty()) add(TvRow(title, cards))
    }
}
