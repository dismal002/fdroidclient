package org.fdroid.wear.details

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Html
import android.text.format.Formatter
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.lifecycleScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.bumptech.glide.Glide
import kotlinx.coroutines.launch
import org.fdroid.database.App
import org.fdroid.database.AppVersion
import org.fdroid.wear.Fdroid
import org.fdroid.wear.R
import org.fdroid.wear.browse.AppRepository
import org.fdroid.wear.install.InstallRequest
import org.fdroid.wear.install.InstallService

/**
 * Watch app page, following the Play-Wear details screen: big icon, title and
 * author, a two-cell stats strip, then the install action as a white circle with
 * a label. Downloads show a thin progress bar under the action.
 */
class DetailsActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_PACKAGE = "org.fdroid.wear.extra.PACKAGE"

        fun intent(context: Context, packageName: String): Intent =
            Intent(context, DetailsActivity::class.java)
                .putExtra(EXTRA_PACKAGE, packageName)
    }

    private lateinit var repository: AppRepository
    private lateinit var packageName: String
    private lateinit var icon: ImageView
    private lateinit var title: TextView
    private lateinit var author: TextView
    private lateinit var stats: LinearLayout
    private lateinit var action: View
    private lateinit var actionLabel: TextView
    private lateinit var progressBlock: View
    private lateinit var progressLabel: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var description: TextView
    private lateinit var screenshotsScroll: View
    private lateinit var screenshots: LinearLayout

    private val locales: LocaleListCompat = LocaleListCompat.getDefault()
    private var app: App? = null
    private var version: AppVersion? = null
    private var loadingVersion = false
    private var installing = false
    private var actionSignature = ""
    private var screenshotsLoaded = false

    private val installReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.getStringExtra(InstallService.EXTRA_PACKAGE) != packageName) return
            when (intent.getStringExtra(InstallService.EXTRA_STATE)) {
                InstallService.STATE_DOWNLOADING -> {
                    installing = true
                    showProgress(intent)
                }
                InstallService.STATE_INSTALLING -> {
                    progressBlock.visibility = View.VISIBLE
                    progressBar.isIndeterminate = true
                    progressLabel.text = getString(R.string.action_installing)
                }
                InstallService.STATE_DONE -> {
                    installing = false
                    // The installed version is now current, so re-read what is offered.
                    version = null
                    showProgressBlock(false)
                    progressBlock.visibility = View.GONE
                    Toast.makeText(
                        this@DetailsActivity,
                        getString(R.string.install_done, app?.name ?: packageName),
                        Toast.LENGTH_SHORT,
                    ).show()
                    actionSignature = ""
                    render()
                }
                InstallService.STATE_CANCELLED -> {
                    installing = false
                    showProgressBlock(false)
                    progressBlock.visibility = View.GONE
                    actionSignature = ""
                    render()
                }
                InstallService.STATE_ERROR -> {
                    installing = false
                    showProgressBlock(false)
                    progressBlock.visibility = View.GONE
                    Toast.makeText(
                        this@DetailsActivity,
                        getString(
                            R.string.install_failed,
                            intent.getStringExtra(InstallService.EXTRA_MESSAGE) ?: "",
                        ),
                        Toast.LENGTH_LONG,
                    ).show()
                    actionSignature = ""
                    render()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_wear_details)
        packageName = intent.getStringExtra(EXTRA_PACKAGE) ?: run {
            finish()
            return
        }
        repository = AppRepository(this)

        icon = findViewById(R.id.icon)
        title = findViewById(R.id.title)
        author = findViewById(R.id.author)
        stats = findViewById(R.id.stats)
        action = findViewById(R.id.action)
        actionLabel = findViewById(R.id.action_label)
        progressBlock = findViewById(R.id.progress_block)
        progressLabel = findViewById(R.id.progress_label)
        progressBar = findViewById(R.id.progress_bar)
        description = findViewById(R.id.description)
        screenshotsScroll = findViewById(R.id.screenshots_scroll)
        screenshots = findViewById(R.id.screenshots)

        Fdroid.db.getAppDao().getApp(packageName).observe(this) { loaded ->
            app = loaded
            if (loaded != null && version == null) loadVersion(loaded)
            render()
        }
    }

    override fun onStart() {
        super.onStart()
        LocalBroadcastManager.getInstance(this).registerReceiver(
            installReceiver,
            IntentFilter(InstallService.ACTION_INSTALL_STATE),
        )
    }

    override fun onStop() {
        super.onStop()
        LocalBroadcastManager.getInstance(this).unregisterReceiver(installReceiver)
    }

    override fun onResume() {
        super.onResume()
        actionSignature = ""
        // Retry the version lookup in case the first one ran before the database was ready.
        app?.let { if (version == null) loadVersion(it) }
        render()
    }

    /** Loads the version to install/update to; retried if the first lookup came back empty. */
    private fun loadVersion(app: App) {
        if (loadingVersion) return
        loadingVersion = true
        lifecycleScope.launch {
            var loaded = Fdroid.suggestedVersion(packageName, app.metadata.preferredSigner)
            if (loaded == null) loaded = Fdroid.suggestedVersion(packageName, null)
            version = loaded
            loadingVersion = false
            actionSignature = ""
            render()
        }
    }

    private fun render() {
        val app = app ?: return
        title.text = app.name ?: packageName
        author.text = app.metadata.authorName
            ?: Fdroid.repo(app.repoId)?.address?.removePrefix("https://")
                ?.removePrefix("http://")?.substringBefore("/")
            ?: getString(R.string.brand)

        Glide.with(icon).load(Fdroid.imageModel(app.repoId, app.getIcon(locales))).into(icon)

        val text = app.getDescription(locales)?.trim().orEmpty()
        description.text = if (text.isBlank()) "" else Html.fromHtml(text)
        description.visibility = if (text.isBlank()) View.GONE else View.VISIBLE

        renderStats()
        renderScreenshots(app)
        renderAction()
    }

    /** Two cells with a divider between them, like the Play-Wear rating/downloads row. */
    private fun renderStats() {
        stats.removeAllViews()
        val versionName = version?.manifest?.versionName
        val size = version?.file?.size
        val cells = ArrayList<Pair<String, String>>()
        if (!versionName.isNullOrBlank()) {
            cells.add(versionName to getString(R.string.stat_version))
        }
        if (size != null && size > 0) {
            cells.add(Formatter.formatFileSize(this, size) to getString(R.string.stat_size))
        }
        cells.forEachIndexed { index, (value, label) ->
            if (index > 0) {
                stats.addView(View(this).apply {
                    setBackgroundColor(ContextCompat.getColor(this@DetailsActivity, R.color.wear_divider))
                }, LinearLayout.LayoutParams(dp(1), dp(22)).apply { gravity = android.view.Gravity.CENTER_VERTICAL })
            }
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            cell.addView(TextView(this).apply {
                text = value
                setTextColor(ContextCompat.getColor(this@DetailsActivity, R.color.wear_text))
                textSize = 13f
                gravity = android.view.Gravity.CENTER
            })
            cell.addView(TextView(this).apply {
                text = label
                setTextColor(ContextCompat.getColor(this@DetailsActivity, R.color.wear_text_secondary))
                textSize = 9f
                gravity = android.view.Gravity.CENTER
            })
            stats.addView(cell)
        }
    }

    private fun renderScreenshots(app: App) {
        if (screenshotsLoaded) return
        screenshotsLoaded = true
        lifecycleScope.launch {
            val files = repository.screenshots(app.repoId, packageName).take(6)
            screenshots.removeAllViews()
            files.forEach { file ->
                val image = ImageView(this@DetailsActivity).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(120), dp(120)).apply { marginEnd = dp(8) }
                    scaleType = ImageView.ScaleType.CENTER_CROP
                }
                Glide.with(image).load(Fdroid.imageModel(app.repoId, file)).into(image)
                screenshots.addView(image)
            }
            screenshotsScroll.visibility = if (files.isEmpty()) View.GONE else View.VISIBLE
        }
    }

    private fun renderAction() {
        val installedCode = repository.installedVersionCode(packageName)
        val availableCode = version?.manifest?.versionCode
        val signature = listOf(
            packageName,
            installedCode?.toString() ?: "-",
            availableCode?.toString() ?: "-",
            installing.toString(),
        ).joinToString("|")
        if (signature == actionSignature) return
        actionSignature = signature

        action.isEnabled = !installing
        actionLabel.text = when {
            installing -> getString(R.string.action_installing)
            installedCode == null -> getString(R.string.action_install)
            availableCode != null && availableCode > installedCode -> getString(R.string.action_update)
            else -> getString(R.string.action_open)
        }
        action.setOnClickListener {
            when {
                installing -> Unit
                installedCode == null -> install()
                availableCode != null && availableCode > installedCode -> install()
                else -> openApp()
            }
        }
    }

    private fun showProgress(intent: Intent) {
        progressBlock.visibility = View.VISIBLE
        showProgressBlock(true)
        actionLabel.text = getString(R.string.action_installing)
        action.isEnabled = false
        val percent = intent.getIntExtra(InstallService.EXTRA_PROGRESS, -1)
        val bytes = intent.getLongExtra(InstallService.EXTRA_BYTES, -1)
        val total = intent.getLongExtra(InstallService.EXTRA_TOTAL, -1)
        progressBar.isIndeterminate = percent < 0
        if (percent >= 0) progressBar.progress = percent
        progressLabel.text = when {
            percent < 0 -> getString(R.string.download_pending)
            bytes >= 0 && total > 0 -> getString(
                R.string.download_percent,
                percent,
            ) + " · " + Formatter.formatFileSize(this, bytes) + " / " +
                Formatter.formatFileSize(this, total)
            else -> getString(R.string.download_percent, percent)
        }
    }

    /**
     * On a small round screen the progress bar would sit below the fold, so the icon and stats
     * step aside while a download is in flight and come back once it is done.
     */
    private fun showProgressBlock(visible: Boolean) {
        icon.visibility = if (visible) View.GONE else View.VISIBLE
        stats.visibility = if (visible) View.GONE else View.VISIBLE
    }

    private fun install() {
        val version = version
        if (version == null) {
            Toast.makeText(this, R.string.wear_no_compatible, Toast.LENGTH_LONG).show()
            return
        }
        if (!canInstallPackages()) {
            Toast.makeText(this, R.string.install_unknown_sources, Toast.LENGTH_LONG).show()
            openUnknownSourcesSettings()
            return
        }
        installing = true
        actionSignature = ""
        renderAction()
        InstallService.start(
            this,
            InstallRequest(
                packageName = packageName,
                label = app?.name ?: packageName,
                repoId = version.repoId,
                fileName = version.file.name,
                sha256 = version.file.sha256,
                size = version.file.size ?: 0L,
                versionName = version.manifest.versionName ?: "",
            ),
        )
    }

    private fun openApp() {
        val intent = packageManager.getLaunchIntentForPackage(packageName)
        if (intent == null) {
            Toast.makeText(this, R.string.wear_open, Toast.LENGTH_SHORT).show()
            return
        }
        startActivity(intent)
    }

    private fun canInstallPackages(): Boolean = if (Build.VERSION.SDK_INT >= 26) {
        packageManager.canRequestPackageInstalls()
    } else {
        true
    }

    private fun openUnknownSourcesSettings() {
        val intent = if (Build.VERSION.SDK_INT >= 26) {
            // Points at ourselves: this app is the one asking to install packages.
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName()))
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
}
