package org.fdroid.tv.details

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
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.os.LocaleListCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.core.content.ContextCompat
import com.bumptech.glide.Glide
import org.fdroid.database.App
import org.fdroid.database.AppVersion
import org.fdroid.tv.Fdroid
import org.fdroid.tv.R
import org.fdroid.tv.browse.AppRepository
import org.fdroid.tv.install.InstallRequest
import org.fdroid.tv.install.InstallService
import android.text.format.DateFormat
import java.util.Date

/**
 * The app details page. Its hero area follows what the decompiled store actually
 * does (DetailsBackgroundTopRowPresenter): apps with a video show the still of
 * that video plus a play button which opens the video on the TV, everything else
 * just shows the hero graphic. There is no auto-playing trailer for apps that
 * have no video.
 */
class DetailsActivity : FragmentActivity() {

    companion object {
        private const val EXTRA_PACKAGE = "org.fdroid.tv.extra.PACKAGE"

        fun intent(context: Context, packageName: String): Intent =
            Intent(context, DetailsActivity::class.java).putExtra(EXTRA_PACKAGE, packageName)
    }

    private lateinit var repository: AppRepository
    private lateinit var packageName: String
    private lateinit var heroImage: ImageView
    private lateinit var heroPlay: View
    private lateinit var poster: ImageView
    private lateinit var title: TextView
    private lateinit var author: TextView
    private lateinit var badges: LinearLayout
    private lateinit var description: TextView
    private lateinit var footer: TextView
    private lateinit var actions: LinearLayout
    private lateinit var permissionsHeader: TextView
    private lateinit var permissionsList: LinearLayout
    private lateinit var antiFeaturesHeader: TextView
    private lateinit var antiFeaturesList: LinearLayout
    private lateinit var loading: View

    private var app: App? = null
    private var version: AppVersion? = null
    private var expanded = false
    private var installing = false
    private var downloadBytes: TextView? = null
    private var downloadPercent: TextView? = null
    private var downloadBar: android.widget.ProgressBar? = null
    private var actionSignature = ""
    private val locales: LocaleListCompat = LocaleListCompat.getDefault()

    private val installReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.getStringExtra(InstallService.EXTRA_PACKAGE) != packageName) return
            when (intent.getStringExtra(InstallService.EXTRA_STATE)) {
                InstallService.STATE_DOWNLOADING -> {
                    if (!installing) {
                        installing = true
                        actionSignature = ""
                        render()
                    }
                    val progress = intent.getIntExtra(InstallService.EXTRA_PROGRESS, -1)
                    val bytesRead = intent.getLongExtra(InstallService.EXTRA_BYTES, -1)
                    val totalBytes = intent.getLongExtra(InstallService.EXTRA_TOTAL, -1)
                    downloadBar?.isIndeterminate = progress < 0
                    if (progress >= 0) downloadBar?.progress = progress
                    downloadPercent?.text = if (progress >= 0) {
                        getString(R.string.download_percent, progress)
                    } else {
                        ""
                    }
                    downloadBytes?.text = if (bytesRead >= 0 && totalBytes > 0) {
                        getString(
                            R.string.download_bytes,
                            android.text.format.Formatter.formatFileSize(this@DetailsActivity, bytesRead),
                            android.text.format.Formatter.formatFileSize(this@DetailsActivity, totalBytes),
                        )
                    } else {
                        ""
                    }
                }
                InstallService.STATE_INSTALLING -> {
                    downloadBar?.isIndeterminate = true
                    downloadPercent?.text = getString(R.string.action_installing)
                }
                InstallService.STATE_DONE -> {
                    installing = false
                    Toast.makeText(
                        this@DetailsActivity,
                        getString(R.string.install_done, app?.name ?: packageName),
                        Toast.LENGTH_SHORT,
                    ).show()
                    actionSignature = ""
                    render()
                }
                InstallService.STATE_ERROR -> {
                    installing = false
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
        setContentView(R.layout.activity_details)
        packageName = intent.getStringExtra(EXTRA_PACKAGE) ?: run {
            finish()
            return
        }
        repository = AppRepository(this)

        heroImage = findViewById(R.id.hero_image)
        heroPlay = findViewById(R.id.hero_play)
        poster = findViewById(R.id.poster)
        title = findViewById(R.id.title)
        author = findViewById(R.id.author)
        badges = findViewById(R.id.badges)
        description = findViewById(R.id.description)
        footer = findViewById(R.id.footer)
        actions = findViewById(R.id.actions)
        permissionsHeader = findViewById(R.id.permissions_header)
        permissionsList = findViewById(R.id.permissions)
        antiFeaturesHeader = findViewById(R.id.antifeatures_header)
        antiFeaturesList = findViewById(R.id.antifeatures)
        loading = findViewById(R.id.loading)

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
        render()
    }

    private fun loadVersion(app: App) {
        lifecycleScope.launchWhenStarted {
            version = Fdroid.suggestedVersion(packageName, app.metadata.preferredSigner)
                ?: Fdroid.suggestedVersion(packageName, null)
            render()
        }
    }

    private fun render() {
        val app = app ?: return
        loading.visibility = View.GONE

        title.text = app.name ?: packageName
        author.text = app.metadata.authorName
            ?: Fdroid.repo(app.repoId)?.let { repo -> repoName(repo.address) }
            ?: getString(R.string.brand)

        val art = app.getFeatureGraphic(locales) ?: app.getTvBanner(locales)
        val artModel = Fdroid.imageModel(app.repoId, art)
        if (artModel != null) {
            Glide.with(heroImage).load(artModel).centerCrop().into(heroImage)
        } else {
            heroImage.setImageDrawable(null)
        }

        val iconModel = Fdroid.imageModel(app.repoId, app.getIcon(locales))
        poster.setImageDrawable(null)
        if (iconModel != null) {
            val padding = dp(10f)
            poster.setPadding(padding, padding, padding, padding)
            Glide.with(poster).load(iconModel).into(poster)
        }

        val video = app.getVideo(locales)
        heroPlay.visibility = if (video.isNullOrBlank()) View.GONE else View.VISIBLE
        if (!video.isNullOrBlank()) {
            heroPlay.setOnClickListener { openVideo(video) }
        }

        description.text = html(app.getDescription(locales)?.trim())
        description.maxLines = if (expanded) Int.MAX_VALUE else 8

        renderBadges(app)
        renderFooter(app)
        renderAntiFeatures()
        renderPermissions()
        renderActions(app)
    }

    private fun renderAntiFeatures() {
        val version = version
        val keys = version?.antiFeatureKeys.orEmpty()
        antiFeaturesList.removeAllViews()
        antiFeaturesHeader.visibility = if (keys.isEmpty()) View.GONE else View.VISIBLE
        keys.forEach { key ->
            addInfoRow(
                container = antiFeaturesList,
                iconRes = R.drawable.ic_antifeature,
                title = antiFeatureLabel(key),
                description = version?.getAntiFeatureReason(key, locales)?.takeIf { it.isNotBlank() }
                    ?: antiFeatureDescription(key),
            )
        }
    }

    /**
     * Lists the app's permissions grouped the way the store does it: one row per
     * bucket, with that bucket's icon, title and description.
     */
    private fun renderPermissions() {
        val version = version
        val permissions = (version?.usesPermission.orEmpty() + version?.usesPermissionSdk23.orEmpty())
            .distinctBy { it.name }
        val groups = Permissions.groupsFor(permissions)
        permissionsList.removeAllViews()
        permissionsHeader.visibility = if (groups.isEmpty()) View.GONE else View.VISIBLE
        groups.forEach { group ->
            val description = if (group.bucket.id == Permissions.OTHER_ID) {
                group.permissions.joinToString("  ·  ") { Permissions.humanized(it.name) }
            } else {
                getString(group.bucket.descriptionRes)
            }
            addInfoRow(
                container = permissionsList,
                iconRes = group.bucket.iconRes,
                title = getString(group.bucket.titleRes),
                description = description,
            )
        }
    }

    private fun addInfoRow(
        container: LinearLayout,
        iconRes: Int,
        title: String,
        description: String,
    ) {
        val row = layoutInflater.inflate(R.layout.details_info_row, container, false)
        row.findViewById<ImageView>(R.id.info_row_icon).setImageResource(iconRes)
        row.findViewById<TextView>(R.id.info_row_title).text = title
        val descriptionView = row.findViewById<TextView>(R.id.info_row_description)
        if (description.isEmpty()) {
            descriptionView.visibility = View.GONE
        } else {
            descriptionView.text = description
        }
        container.addView(row)
    }

    /**
     * The index stores descriptions as HTML, so the markup has to be rendered
     * rather than shown verbatim.
     */
    private fun html(text: String?): CharSequence {
        if (text.isNullOrBlank()) return ""
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            Html.fromHtml(text, Html.FROM_HTML_MODE_LEGACY)
        } else {
            @Suppress("DEPRECATION")
            Html.fromHtml(text)
        }
    }

    private fun renderBadges(app: App) {
        badges.removeAllViews()
        val manifest = version?.manifest
        val features = manifest?.featureNames ?: emptyList()
        if (features.contains("android.software.leanback") || features.contains("android.hardware.gamepad")) {
            addBadge(getString(R.string.app_name), green = true)
        }
        version?.antiFeatureKeys?.forEach { key ->
            addBadge(antiFeatureLabel(key))
        }
        app.metadata.license?.let { addBadge(it) }
    }

    private fun addBadge(text: String, green: Boolean = false) {
        val badge = TextView(this)
        badge.setText(text)
        badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        badge.setTextColor(ContextCompat.getColor(this, if (green) R.color.accent_green else R.color.text_secondary))
        badge.setPadding(dp(10f), dp(3f), dp(10f), dp(3f))
        badge.setBackgroundResource(R.drawable.badge_bg)
        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        params.marginEnd = dp(8f)
        badge.layoutParams = params
        badges.addView(badge)
    }

    private fun renderFooter(app: App) {
        val parts = ArrayList<String>()
        val versionName = version?.manifest?.versionName
        if (!versionName.isNullOrBlank()) parts.add(getString(R.string.details_version, versionName))
        val size = version?.file?.size
        if (size != null && size > 0) parts.add(Formatter.formatFileSize(this, size))
        app.metadata.license?.let { parts.add(it) }
        val updated = app.metadata.lastUpdated
        if (updated > 0) {
            parts.add(DateFormat.getMediumDateFormat(this).format(Date(updated)))
        }
        footer.text = parts.joinToString("  ·  ")
    }

    private fun renderActions(app: App) {
        val installedCode = repository.installedVersionCode(packageName)
        val availableCode = version?.manifest?.versionCode
        val signature = listOf(
            packageName,
            installedCode?.toString() ?: "-",
            availableCode?.toString() ?: "-",
            expanded.toString(),
            app.metadata.webSite ?: "-",
            app.metadata.sourceCode ?: "-",
            installing.toString(),
        ).joinToString("|")
        if (signature == actionSignature) return
        actionSignature = signature

        actions.removeAllViews()
        when {
            // Just like leanback's details action, the download replaces the button.
            installing -> addProgress()
            installedCode == null -> addAction(getString(R.string.action_install), primary = true) { install() }
            availableCode != null && availableCode > installedCode ->
                addAction(getString(R.string.action_update), primary = true) { install() }
            else -> addAction(getString(R.string.action_open), primary = true) { openApp() }
        }
        if (installedCode != null) {
            addAction(getString(R.string.action_uninstall)) { uninstall() }
        }
        addAction(getString(if (expanded) R.string.action_less else R.string.action_full_description)) {
            expanded = !expanded
            render()
        }
        app.metadata.webSite?.let { url ->
            addAction(getString(R.string.action_website)) { openUrl(url) }
        }
        app.metadata.sourceCode?.let { url ->
            addAction(getString(R.string.action_source_code)) { openUrl(url) }
        }

        val firstAction = actions.getChildAt(0)
        if (firstAction != null) {
            firstAction.post {
                // The scrolling description likes to grab the focus first, which leaves
                // the d-pad stuck with no visible target. Default to the primary action.
                if (actions.findFocus() == null) firstAction.requestFocus()
            }
        }
    }

    private fun addProgress() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        downloadBytes = TextView(this).apply {
            setTextColor(ContextCompat.getColor(this@DetailsActivity, R.color.text_secondary))
            textSize = 12f
            isAllCaps = true
        }
        downloadPercent = TextView(this).apply {
            setTextColor(ContextCompat.getColor(this@DetailsActivity, R.color.text_secondary))
            textSize = 12f
            isAllCaps = true
        }
        top.addView(downloadBytes, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(downloadPercent)
        box.addView(top, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        downloadBar = android.widget.ProgressBar(
            this,
            null,
            android.R.attr.progressBarStyleHorizontal,
        ).apply {
            max = 100
            progressDrawable = ContextCompat.getDrawable(this@DetailsActivity, R.drawable.progress_tv)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(4f),
            ).apply { topMargin = dp(6f) }
        }
        box.addView(downloadBar)
        actions.addView(
            box,
            LinearLayout.LayoutParams(dp(320f), LinearLayout.LayoutParams.WRAP_CONTENT),
        )
    }

    private fun addAction(label: String, primary: Boolean = false, onClick: () -> Unit) {
        val button = TextView(this, null, 0, R.style.TvActionButton)
        button.text = label
        button.tag = if (primary) "primary" else "secondary"
        button.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
        button.setOnClickListener { onClick() }
        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        params.marginEnd = dp(4f)
        actions.addView(button, params)
    }

    private fun install() {
        val version = version
        if (version == null) {
            Toast.makeText(this, R.string.install_no_compatible_version, Toast.LENGTH_LONG).show()
            return
        }
        if (!canInstallPackages()) {
            Toast.makeText(this, R.string.install_unknown_sources, Toast.LENGTH_LONG).show()
            openUnknownSourcesSettings()
            return
        }
        installing = true
        actionSignature = ""
        render()
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

    private fun uninstall() {
        try {
            startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:$packageName")))
        } catch (e: Exception) {
            Toast.makeText(this, e.message, Toast.LENGTH_LONG).show()
        }
    }

    private fun openApp() {
        val intent = if (Build.VERSION.SDK_INT >= 21) {
            packageManager.getLeanbackLaunchIntentForPackage(packageName)
                ?: packageManager.getLaunchIntentForPackage(packageName)
        } else {
            packageManager.getLaunchIntentForPackage(packageName)
        }
        if (intent == null) {
            Toast.makeText(this, R.string.action_open, Toast.LENGTH_SHORT).show()
            return
        }
        startActivity(intent)
    }

    private fun openVideo(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            Toast.makeText(this, R.string.action_trailer, Toast.LENGTH_SHORT).show()
        }
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            Toast.makeText(this, url, Toast.LENGTH_SHORT).show()
        }
    }

    private fun canInstallPackages(): Boolean = if (Build.VERSION.SDK_INT >= 26) {
        packageManager.canRequestPackageInstalls()
    } else {
        true
    }

    private fun openUnknownSourcesSettings() {
        val intent = if (Build.VERSION.SDK_INT >= 26) {
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))
        } else {
            Intent(Settings.ACTION_SECURITY_SETTINGS)
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            // TV builds do not always expose this settings page
        }
    }

    /** What an anti-feature means, used when the index carries no reason of its own. */
    private fun antiFeatureDescription(key: String): String = getString(
        when (key) {
            "Ads" -> R.string.antifeature_ads_desc
            "Tracking" -> R.string.antifeature_tracking_desc
            "NonFreeNet" -> R.string.antifeature_nonfree_net_desc
            "NonFreeAdd" -> R.string.antifeature_nonfree_add_desc
            "NonFreeDep" -> R.string.antifeature_nonfree_dep_desc
            "NonFreeAssets" -> R.string.antifeature_nonfree_assets_desc
            "UpstreamNonFree" -> R.string.antifeature_upstream_nonfree_desc
            "NoSourceSince" -> R.string.antifeature_no_source_desc
            "KnownVuln" -> R.string.antifeature_known_vuln_desc
            "DisabledAlgorithm" -> R.string.antifeature_disabled_algorithm_desc
            "WeakSignature" -> R.string.antifeature_weak_signature_desc
            else -> R.string.antifeature_generic_desc
        },
    )

    private fun antiFeatureLabel(key: String): String = when (key) {
        "Ads" -> "Contains ads"
        "Tracking" -> "Tracks you"
        "NonFreeNet" -> "Non-free network"
        "NonFreeAdd" -> "Non-free addons"
        "NonFreeDep" -> "Non-free dependencies"
        "NonFreeAssets" -> "Non-free assets"
        "UpstreamNonFree" -> "Upstream non-free"
        "NoSourceSince" -> "No source since"
        "KnownVuln" -> "Known vulnerabilities"
        "DisabledAlgorithm" -> "Disabled algorithm"
        "WeakSignature" -> "Weak signature"
        else -> key
    }

    private fun repoName(address: String): String =
        address.removePrefix("https://").removePrefix("http://").substringBefore("/")

    private fun dp(value: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics).toInt()
}
