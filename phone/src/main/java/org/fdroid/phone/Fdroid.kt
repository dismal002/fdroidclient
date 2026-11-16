package org.fdroid.phone

import android.content.Context
import android.net.Uri
import androidx.lifecycle.MutableLiveData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.fdroid.CompatibilityChecker
import org.fdroid.CompatibilityCheckerImpl
import org.fdroid.IndexFile
import org.fdroid.database.AppVersion
import org.fdroid.database.DbUpdateChecker
import org.fdroid.database.FDroidDatabase
import org.fdroid.database.FDroidDatabaseHolder
import org.fdroid.database.InitialRepository
import org.fdroid.database.Repository
import org.fdroid.download.DownloadRequest
import org.fdroid.download.Downloader
import org.fdroid.download.DownloaderFactory
import org.fdroid.download.HttpDownloaderV2
import org.fdroid.download.HttpManager
import org.fdroid.index.IndexUpdateListener
import org.fdroid.index.IndexUpdateResult
import org.fdroid.index.RepoManager
import org.fdroid.index.RepoUpdater
import org.fdroid.index.RepoUriBuilder
import java.io.File
import java.io.IOException

/**
 * Everything the TV client needs from the regular F-Droid stack:
 * the Room database with the repository index, the repository manager,
 * the downloader and the update checker.
 */
object Fdroid {

    sealed class SyncState {
        object Idle : SyncState()
        data class Running(val message: String = "") : SyncState()
        data class Done(val appCount: Int) : SyncState()
        data class Error(val message: String) : SyncState()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    lateinit var db: FDroidDatabase
        private set
    lateinit var repoManager: RepoManager
        private set
    lateinit var downloaderFactory: DownloaderFactory
        private set
    lateinit var httpManager: HttpManager
        private set
    lateinit var compatibilityChecker: CompatibilityChecker
        private set
    lateinit var updateChecker: DbUpdateChecker
        private set

    @Volatile
    var repositories: Map<Long, Repository> = emptyMap()
        private set

    val syncState = MutableLiveData<SyncState>(SyncState.Idle)

    /**
     * True when the index is missing or older than a day, so the app only
     * downloads the full index when it actually needs to, instead of on every
     * start (a full update briefly empties the app tables).
     */
    @Volatile
    var shouldSyncOnStart = false
        private set

    @Volatile
    private var syncing = false

    @Volatile
    private var initialized = false

    val repoUriBuilder = RepoUriBuilder { repo, pathElements ->
        val builder = Uri.parse(repo.address).buildUpon()
        pathElements.forEach { builder.appendEncodedPath(it) }
        builder.build()
    }

    fun init(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            val app = context.applicationContext
            db = FDroidDatabaseHolder.getDb(app, "fdroid_db")
            httpManager = HttpManager("F-Droid", null, null, null)
            downloaderFactory = TvDownloaderFactory(httpManager)
            runBlocking(Dispatchers.IO) { ensureRepository(app) }
            repoManager = RepoManager(app, db, downloaderFactory, httpManager, repoUriBuilder)
            compatibilityChecker = CompatibilityCheckerImpl(app.packageManager)
            updateChecker = DbUpdateChecker(db, app.packageManager, compatibilityChecker)
            initialized = true
        }
    }

    private suspend fun ensureRepository(context: Context) = withContext(Dispatchers.IO) {
        val dao = db.getRepositoryDao()
        if (dao.getRepositories().isEmpty()) {
            initialRepositories(context).forEach { repo -> dao.insert(repo) }
        }
        refreshRepositories()
        val repo = repositories.values.firstOrNull { it.enabled }
        val appCount = repo?.let { db.getAppDao().getNumberOfAppsInRepository(it.repoId) } ?: 0
        val lastUpdated = repo?.lastUpdated ?: 0L
        val dayMillis = 24 * 60 * 60 * 1000L
        shouldSyncOnStart = appCount == 0 || (System.currentTimeMillis() - lastUpdated) > dayMillis
    }

    /**
     * Mirrors the prepopulation the phone app does from {@code default_repos.xml},
     * so the official repository (and its mirrors) is always set up correctly.
     */
    private fun initialRepositories(context: Context): List<InitialRepository> {
        val items = context.resources.getStringArray(R.array.default_repos)
        val repositories = ArrayList<InitialRepository>()
        var i = 0
        while (i + 6 < items.size) {
            val name = items[i].trim()
            val addresses = items[i + 1].trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            val description = items[i + 2].trim()
            val version = items[i + 3].trim().toLongOrNull() ?: 0L
            val enabled = items[i + 4].trim() == "1"
            val certificate = items[i + 6].trim().replace("\n", "").replace(" ", "")
            if (addresses.isNotEmpty()) {
                try {
                    repositories.add(
                        InitialRepository(
                            name = name,
                            address = addresses.first(),
                            mirrors = addresses.drop(1),
                            description = description,
                            certificate = certificate,
                            version = version,
                            enabled = enabled,
                        )
                    )
                } catch (e: IllegalArgumentException) {
                    // ignore invalid repo entries, like the phone app does
                }
            }
            i += 7
        }
        return repositories
    }

    private suspend fun refreshRepositories() = withContext(Dispatchers.IO) {
        repositories = db.getRepositoryDao().getRepositories().associateBy { it.repoId }
    }

    fun repo(repoId: Long): Repository? = repositories[repoId]

    /**
     * A Glide model for a file in a repository, so icons and graphics
     * are loaded through the same mirror/hash handling as the index.
     */
    fun imageModel(repoId: Long, file: IndexFile?): Any? {
        if (file == null) return null
        val repository = repositories[repoId] ?: return null
        return DownloadRequest(
            file,
            repository.getMirrors(),
            null,
            repository.username,
            repository.password,
            null,
        )
    }

    /**
     * Downloads and processes the index of every enabled repository.
     * Progress ends up in [syncState] so the UI can show it.
     */
    fun sync(context: Context) {
        if (syncing) return
        syncing = true
        val app = context.applicationContext
        scope.launch {
            syncState.postValue(SyncState.Running())
            try {
                val repos = repoManager.getRepositories().filter { it.enabled }
                var appCount = 0
                repos.forEach { repository ->
                    val updater = RepoUpdater(
                        tempDir = app.cacheDir,
                        db = db,
                        downloaderFactory = downloaderFactory,
                        repoUriBuilder = repoUriBuilder,
                        compatibilityChecker = compatibilityChecker,
                        listener = object : IndexUpdateListener {
                            override fun onDownloadProgress(
                                repo: Repository,
                                bytesRead: Long,
                                totalBytes: Long,
                            ) = Unit

                            override fun onUpdateProgress(
                                repo: Repository,
                                appsProcessed: Int,
                                totalApps: Int,
                            ) {
                                syncState.postValue(SyncState.Running("$appsProcessed / $totalApps"))
                            }
                        },
                    )
                    when (val result = updater.update(repository)) {
                        is IndexUpdateResult.Error -> throw result.e
                        else -> Unit
                    }
                    refreshRepositories()
                    appCount += db.getAppDao().getNumberOfAppsInRepository(repository.repoId)
                }
                syncState.postValue(SyncState.Done(appCount))
            } catch (e: Exception) {
                syncState.postValue(SyncState.Error(e.message ?: e.javaClass.simpleName))
            } finally {
                syncing = false
            }
        }
    }

    /** Adds a repository from a URL and its signing certificate fingerprint. */
    suspend fun addRepository(
        name: String,
        address: String,
        certificate: String,
    ): Long = withContext(Dispatchers.IO) {
        val repoId = db.getRepositoryDao().insert(
            InitialRepository(
                name = name,
                address = address,
                description = "",
                certificate = certificate.replace(" ", "").replace(":", "").lowercase(),
                version = 0,
                enabled = true,
            ),
        )
        refreshRepositories()
        repoId
    }

    suspend fun setRepositoryEnabled(repoId: Long, enabled: Boolean) = withContext(Dispatchers.IO) {
        db.getRepositoryDao().setRepositoryEnabled(repoId, enabled)
        refreshRepositories()
    }

    suspend fun removeRepository(repoId: Long) = withContext(Dispatchers.IO) {
        db.getRepositoryDao().deleteRepository(repoId)
        refreshRepositories()
    }

    /** The best version to install for the given package, if any. */
    suspend fun suggestedVersion(packageName: String, preferredSigner: String?): AppVersion? =
        withContext(Dispatchers.IO) {
            updateChecker.getSuggestedVersion(packageName, preferredSigner)
        }

    suspend fun updatableApps(): Set<String> = withContext(Dispatchers.IO) {
        try {
            updateChecker.getUpdatableApps().map { it.packageName }.toSet()
        } catch (e: Exception) {
            emptySet()
        }
    }

}

/**
 * Plain HTTP(S) downloads for index files, APKs and images.
 * No Tor or Bluetooth support, which the TV client does not need.
 */
private class TvDownloaderFactory(
    private val httpManager: HttpManager,
) : DownloaderFactory() {

    override fun create(
        repo: Repository,
        uri: Uri,
        indexFile: IndexFile,
        destFile: File,
    ): Downloader = create(repo, repo.getMirrors(), uri, indexFile, destFile, null)

    override fun create(
        repo: Repository,
        mirrors: List<org.fdroid.download.Mirror>,
        uri: Uri,
        indexFile: IndexFile,
        destFile: File,
        tryFirst: org.fdroid.download.Mirror?,
    ): Downloader {
        if (uri.scheme == "file") {
            throw IOException("Local repositories are not supported by F-Droid TV.")
        }
        val request = DownloadRequest(
            indexFile,
            mirrors,
            null,
            repo.username,
            repo.password,
            tryFirst,
        )
        return HttpDownloaderV2(httpManager, request, destFile)
    }
}
