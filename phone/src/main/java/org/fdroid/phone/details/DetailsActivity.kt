package org.fdroid.phone.details

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Html
import android.text.format.DateFormat
import android.text.format.Formatter
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.lifecycleScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.core.content.ContextCompat
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import kotlinx.coroutines.launch
import org.fdroid.database.App
import org.fdroid.database.AppVersion
import org.fdroid.index.v2.FileV2
import org.fdroid.phone.Fdroid
import org.fdroid.phone.R
import org.fdroid.phone.browse.AppRepository
import org.fdroid.phone.install.InstallRequest
import org.fdroid.phone.install.InstallService
import java.util.Date

/**
 * The app page: promo artwork with the back button, the icon/title block, the
 * install pair, the circular stat strip, the description, the tinted what's-new
 * block, and the screenshots strip.
 */
class DetailsActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_PACKAGE = "org.fdroid.phone.extra.PACKAGE"

        fun intent(context: Context, packageName: String): Intent =
            Intent(context, DetailsActivity::class.java).putExtra(EXTRA_PACKAGE, packageName)
    }

    private lateinit var repository: AppRepository
    private lateinit var packageName: String
    private lateinit var heroImage: ImageView
    private lateinit var heroPlay: View
    private lateinit var icon: ImageView
    private lateinit var title: TextView
    private lateinit var author: TextView
    private lateinit var actions: LinearLayout
    private lateinit var stats: LinearLayout
    private lateinit var description: TextView
    private lateinit var readMore: TextView
    private lateinit var whatsNewBlock: View
    private lateinit var whatsNewText: TextView
    private lateinit var screenshotsBlock: View
    private lateinit var screenshots: LinearLayout
    private lateinit var links: TextView
    private lateinit var linkRows: LinearLayout
    private lateinit var antiFeaturesBlock: View
    private lateinit var antiFeatures: LinearLayout
    private lateinit var permissionsBlock: View
    private lateinit var permissionsList: LinearLayout

    private val isTablet: Boolean
        get() = resources.configuration.smallestScreenWidthDp >= 600
    private lateinit var loading: View
    private lateinit var progressBlock: View
    private lateinit var downloadBar: android.widget.ProgressBar
    private lateinit var downloadBytes: TextView
    private lateinit var downloadPercent: TextView

    private var app: App? = null
    private var version: AppVersion? = null
    private var expanded = false
    private var installing = false
    private var actionSignature = ""
    private var screenshotsLoaded = false
    private val locales: LocaleListCompat = LocaleListCompat.getDefault()

    private val installReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.getStringExtra(InstallService.EXTRA_PACKAGE) != packageName) return
            when (intent.getStringExtra(InstallService.EXTRA_STATE)) {
                InstallService.STATE_DOWNLOADING -> {
                    installing = true
                    progressBlock.visibility = View.VISIBLE
                    showDownloadProgress(intent)
                    actionSignature = ""
                    render()
                }
                InstallService.STATE_INSTALLING -> {
                    progressBlock.visibility = View.VISIBLE
                    downloadPercent.text = getString(R.string.action_installing)
                }
                InstallService.STATE_DONE -> {
                    installing = false
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
                    progressBlock.visibility = View.GONE
                    actionSignature = ""
                    render()
                }
                InstallService.STATE_ERROR -> {
                    installing = false
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
        setContentView(R.layout.activity_phone_details)
        packageName = intent.getStringExtra(EXTRA_PACKAGE) ?: run {
            finish()
            return
        }
        repository = AppRepository(this)

        heroImage = findViewById(R.id.hero_image)
        heroPlay = findViewById(R.id.hero_play)
        icon = findViewById(R.id.app_icon)
        title = findViewById(R.id.title)
        author = findViewById(R.id.author)
        actions = findViewById(R.id.actions)
        progressBlock = findViewById(R.id.download_progress)
        downloadBar = findViewById(R.id.download_bar)
        downloadBytes = findViewById(R.id.download_bytes)
        downloadPercent = findViewById(R.id.download_percent)
        findViewById<View>(R.id.download_cancel).setOnClickListener {
            InstallService.cancel(this, packageName)
        }
        stats = findViewById(R.id.stats)
        description = findViewById(R.id.description)
        readMore = findViewById(R.id.read_more)
        whatsNewBlock = findViewById(R.id.whats_new_block)
        whatsNewText = findViewById(R.id.whats_new_text)
        screenshotsBlock = findViewById(R.id.screenshots_block)
        screenshots = findViewById(R.id.screenshots)
        links = findViewById(R.id.details_links)
        linkRows = findViewById(R.id.details_link_rows)
        antiFeaturesBlock = findViewById(R.id.antifeatures_block)
        antiFeatures = findViewById(R.id.antifeatures)
        permissionsBlock = findViewById(R.id.permissions_block)
        permissionsList = findViewById(R.id.permissions)
        loading = findViewById(R.id.loading)

        findViewById<View>(R.id.back).setOnClickListener { finish() }
        readMore.setOnClickListener {
            expanded = !expanded
            render()
        }

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
        lifecycleScope.launch {
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
            ?: Fdroid.repo(app.repoId)?.let { repo ->
                repo.address.removePrefix("https://").removePrefix("http://").substringBefore("/")
            }
            ?: getString(R.string.brand)

        val wide = app.getFeatureGraphic(locales) ?: app.getTvBanner(locales)
        val wideModel = Fdroid.imageModel(app.repoId, wide)
        if (wideModel != null) {
            Glide.with(heroImage).load(wideModel).centerCrop().into(heroImage)
        } else {
            // Without promo art the hero shows the app icon on the page colour.
            heroImage.setImageDrawable(null)
            Glide.with(icon).load(Fdroid.imageModel(app.repoId, app.getIcon(locales))).into(icon)
        }

        val iconModel = Fdroid.imageModel(app.repoId, app.getIcon(locales))
        if (iconModel != null) {
            Glide.with(icon).load(iconModel).into(icon)
        }

        val video = app.getVideo(locales)
        heroPlay.visibility = if (video.isNullOrBlank()) View.GONE else View.VISIBLE
        if (!video.isNullOrBlank()) {
            heroPlay.setOnClickListener { openVideo(video) }
        }

        description.text = html(app.getDescription(locales)?.trim())
        description.maxLines = if (expanded) Int.MAX_VALUE else 6
        readMore.visibility = if ((description.text?.length ?: 0) > 0) View.VISIBLE else View.GONE
        readMore.text = getString(if (expanded) R.string.phone_less else R.string.phone_read_more)

        val whatsNew = version?.getWhatsNew(locales)
        if (whatsNew.isNullOrBlank()) {
            whatsNewBlock.visibility = View.GONE
        } else {
            whatsNewBlock.visibility = View.VISIBLE
            whatsNewText.text = html(whatsNew)
        }

        renderStats(app)
        renderAntiFeatures()
        renderPermissions()
        renderLinks(app)
        renderScreenshots(app)
        renderActions(app)
    }

    private fun renderStats(app: App) {
        stats.removeAllViews()
        val badges = ArrayList<Pair<String, String>>()
        version?.manifest?.versionName?.takeIf { it.isNotBlank() }?.let {
            badges.add(it to getString(R.string.stat_version))
        }
        version?.file?.size?.takeIf { it > 0 }?.let {
            badges.add(Formatter.formatFileSize(this, it) to getString(R.string.stat_size))
        }
        app.metadata.license?.let { badges.add(it to getString(R.string.stat_licence)) }
        version?.manifest?.minSdkVersion?.let {
            badges.add("API $it" to getString(R.string.stat_requires))
        }

        badges.forEach { (value, label) ->
            val badge = layoutInflater.inflate(R.layout.item_stat_badge, stats, false)
            val valueView = badge.findViewById<TextView>(R.id.stat_value)
            valueView.text = value
            // Version codes such as 2026110800 are wider than the circle.
            valueView.textSize = when {
                value.length > 8 -> 9f
                value.length > 6 -> 11f
                else -> 13f
            }
            badge.findViewById<TextView>(R.id.stat_label).text = label
            // Share the width evenly so four circles never run off the screen.
            badge.layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f,
            )
            stats.addView(badge)
        }
    }

    private fun renderLinks(app: App) {
        val parts = ArrayList<String>()
        val updated = app.metadata.lastUpdated
        if (updated > 0) {
            parts.add(getString(R.string.details_updated, DateFormat.getMediumDateFormat(this).format(Date(updated))))
        }
        links.text = parts.joinToString("\n")
        links.visibility = if (parts.isEmpty()) View.GONE else View.VISIBLE

        linkRows.removeAllViews()
        app.metadata.webSite?.takeIf { it.isNotBlank() }?.let { url ->
            addLinkRow(R.drawable.ic_web, getString(R.string.action_website), url)
        }
        app.metadata.sourceCode?.takeIf { it.isNotBlank() }?.let { url ->
            addLinkRow(R.drawable.ic_code, getString(R.string.action_source_code), url)
        }
        app.metadata.donate?.filter { it.isNotBlank() }?.forEach { url ->
            addLinkRow(R.drawable.ic_gift, getString(R.string.action_donate), url)
        }
    }

    private fun addLinkRow(iconRes: Int, label: String, url: String) {
        val row = layoutInflater.inflate(R.layout.phone_link_row, linkRows, false)
        row.findViewById<ImageView>(R.id.link_row_icon).setImageResource(iconRes)
        row.findViewById<TextView>(R.id.link_row_text).text = url
        row.contentDescription = label
        row.setOnClickListener { openLink(url) }
        linkRows.addView(row)
    }

    private fun openLink(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            Toast.makeText(this, url, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * The index flags apps for anti-features (ads, tracking, non-free
     * dependencies, ...); each flagged item gets a row with the reason the
     * repository gives, matching what the store's app page shows.
     */
    private fun renderAntiFeatures() {
        val version = version
        val keys = version?.antiFeatureKeys.orEmpty()
        antiFeatures.removeAllViews()
        antiFeaturesBlock.visibility = if (keys.isEmpty()) View.GONE else View.VISIBLE
        keys.forEach { key ->
            val reason = version?.getAntiFeatureReason(key, locales)?.takeIf { it.isNotBlank() }
            addInfoRow(
                container = antiFeatures,
                iconRes = R.drawable.ic_antifeature,
                title = antiFeatureLabel(key),
                description = reason ?: antiFeatureDescription(key),
            )
        }
    }

    /**
     * Lists the app's permissions grouped the way the store does it: one row per
     * bucket, with that bucket's icon, title and description.
     */
    private fun renderPermissions() {
        val permissions = (version?.usesPermission.orEmpty() + version?.usesPermissionSdk23.orEmpty())
            .distinctBy { it.name }
        val groups = Permissions.groupsFor(permissions)
        permissionsList.removeAllViews()
        permissionsBlock.visibility = if (groups.isEmpty()) View.GONE else View.VISIBLE
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

    private fun addInfoRow(container: LinearLayout, iconRes: Int, title: String, description: String) {
        val row = layoutInflater.inflate(R.layout.phone_info_row, container, false)
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

    private fun renderScreenshots(app: App) {
        if (screenshotsLoaded) return
        screenshotsLoaded = true
        lifecycleScope.launch {
            val repoId = version?.repoId ?: app.repoId
            var files = repository.screenshots(repoId, packageName)
            if (files.isEmpty()) {
                // This index carries no screenshot entries at all, so the carousel
                // falls back to the wide store artwork the app does have.
                files = listOfNotNull(app.getFeatureGraphic(locales), app.getTvBanner(locales))
            }
            if (files.isEmpty()) {
                screenshotsBlock.visibility = View.GONE
                return@launch
            }
            screenshotsBlock.visibility = View.VISIBLE
            if (isTablet) {
                findViewById<HorizontalScrollView>(R.id.screenshots_scroll).isFillViewport = true
                screenshots.layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                )
                screenshots.gravity = Gravity.CENTER_HORIZONTAL
            }
            val shots = files.toList()
            shots.forEachIndexed { index, file ->
                addScreenshot(repoId, file, shots, index)
            }
        }
    }

    /**
     * Screenshots are mostly portrait, so a fixed wide frame cropped them to a
     * pointless middle slice. The thumbnail keeps a fixed height and takes its
     * width from the screenshot's own aspect ratio once it is loaded, which is
     * how the store shows them without cutting anything off.
     */
    private fun addScreenshot(repoId: Long, file: FileV2, shots: List<FileV2>, index: Int) {
        val frameHeight = dp(if (isTablet) 300 else 200)
        val maxWidth = dp(if (isTablet) 480 else 400)
        val image = ImageView(this).apply {
            // The frame follows the screenshot's ratio, so this does not crop;
            // only artwork wider than the frame limit gets trimmed at the sides.
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundResource(R.color.card_image_background)
            layoutParams = LinearLayout.LayoutParams(frameHeight, frameHeight).apply {
                marginEnd = dp(10)
            }
            setOnClickListener {
                startActivity(ScreenshotActivity.intent(this@DetailsActivity, repoId, shots, index))
            }
        }
        screenshots.addView(image)
        val model = Fdroid.imageModel(repoId, file) ?: return
        Glide.with(this).load(model).into(
            object : CustomTarget<Drawable>(maxWidth, frameHeight) {
                override fun onResourceReady(resource: Drawable, transition: Transition<in Drawable>?) {
                    image.setImageDrawable(resource)
                    val width = resource.intrinsicWidth
                    val height = resource.intrinsicHeight
                    if (width <= 0 || height <= 0) return
                    val scaled = (frameHeight.toFloat() * width / height).toInt()
                        .coerceIn(dp(90), maxWidth)
                    image.layoutParams = LinearLayout.LayoutParams(scaled, frameHeight).apply {
                        marginEnd = dp(10)
                    }
                }

                override fun onLoadCleared(placeholder: Drawable?) {
                    image.setImageDrawable(placeholder)
                }
            },
        )
    }

    private fun showDownloadProgress(intent: Intent) {
        val percent = intent.getIntExtra(InstallService.EXTRA_PROGRESS, -1)
        val bytes = intent.getLongExtra(InstallService.EXTRA_BYTES, -1)
        val total = intent.getLongExtra(InstallService.EXTRA_TOTAL, -1)
        downloadBar.isIndeterminate = percent < 0
        if (percent >= 0) downloadBar.progress = percent
        downloadPercent.text = if (percent >= 0) {
            getString(R.string.download_percent, percent)
        } else {
            getString(R.string.download_pending)
        }
        downloadBytes.text = if (bytes >= 0 && total > 0) {
            getString(
                R.string.download_bytes,
                android.text.format.Formatter.formatFileSize(this, bytes),
                android.text.format.Formatter.formatFileSize(this, total),
            )
        } else {
            ""
        }
    }

    private fun renderActions(app: App) {
        val installedCode = repository.installedVersionCode(packageName)
        val availableCode = version?.manifest?.versionCode
        val signature = listOf(
            packageName,
            installedCode?.toString() ?: "-",
            availableCode?.toString() ?: "-",
            expanded.toString(),
            installing.toString(),
        ).joinToString("|")
        if (signature == actionSignature) return
        actionSignature = signature

        actions.removeAllViews()
        when {
            // While downloading the progress block above takes the place of the button.
            installing -> {}
            installedCode == null ->
                addAction(getString(R.string.action_install), primary = true) { install() }
            availableCode != null && availableCode > installedCode ->
                addAction(getString(R.string.action_update), primary = true) { install() }
            else -> addAction(getString(R.string.action_open), primary = true) { openApp() }
        }
        if (installedCode != null && !installing) {
            addAction(getString(R.string.action_uninstall), primary = false) { uninstall() }
        }
    }

    private fun addAction(label: String, primary: Boolean, onClick: () -> Unit) {
        val button = TextView(this)
        button.text = label
        button.tag = if (primary) "primary" else "secondary"
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        button.letterSpacing = 0.05f
        button.gravity = Gravity.CENTER
        button.isAllCaps = true
        button.gravity = Gravity.CENTER
        button.setPadding(0, dp(10), 0, dp(10))
        if (primary) {
            button.setTextColor(0xffffffff.toInt())
            button.setBackgroundResource(R.drawable.bg_phone_primary_button)
        } else {
            button.setTextColor(ContextCompat.getColor(this, R.color.play_green))
            button.setBackgroundResource(R.drawable.bg_phone_secondary_button)
        }
        button.setOnClickListener { onClick() }
        val params = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        params.marginEnd = dp(6)
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
            org.fdroid.phone.history.InstallHistory.record(
                this,
                org.fdroid.phone.history.InstallHistory.Event(
                    packageName = packageName,
                    name = app?.name ?: packageName,
                    versionName = repository.installedVersionName(packageName),
                    action = org.fdroid.phone.history.InstallHistory.ACTION_UNINSTALLED,
                    timestamp = System.currentTimeMillis(),
                ),
            )
            startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:$packageName")))
        } catch (e: Exception) {
            Toast.makeText(this, e.message, Toast.LENGTH_LONG).show()
        }
    }

    private fun openApp() {
        val intent = packageManager.getLeanbackLaunchIntentForPackage(packageName)
            ?: packageManager.getLaunchIntentForPackage(packageName)
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
            // some builds do not expose this settings page
        }
    }

    private fun html(text: String?): CharSequence {
        if (text.isNullOrBlank()) return ""
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            Html.fromHtml(text, Html.FROM_HTML_MODE_LEGACY)
        } else {
            @Suppress("DEPRECATION")
            Html.fromHtml(text)
        }
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()
}
