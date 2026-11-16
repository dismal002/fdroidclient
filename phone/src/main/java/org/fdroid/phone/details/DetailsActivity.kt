package org.fdroid.phone.details

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import kotlinx.coroutines.launch
import org.fdroid.database.App
import org.fdroid.database.AppVersion
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
    private lateinit var loading: View

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
                    val progress = intent.getIntExtra(InstallService.EXTRA_PROGRESS, -1)
                    val text = if (progress >= 0) {
                        "${getString(R.string.action_install)} $progress%"
                    } else {
                        getString(R.string.action_install)
                    }
                    actions.findViewWithTag<TextView>("primary")?.text = text
                }
                InstallService.STATE_INSTALLING ->
                    actions.findViewWithTag<TextView>("primary")?.text =
                        getString(R.string.action_installing)
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
        stats = findViewById(R.id.stats)
        description = findViewById(R.id.description)
        readMore = findViewById(R.id.read_more)
        whatsNewBlock = findViewById(R.id.whats_new_block)
        whatsNewText = findViewById(R.id.whats_new_text)
        screenshotsBlock = findViewById(R.id.screenshots_block)
        screenshots = findViewById(R.id.screenshots)
        links = findViewById(R.id.details_links)
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
        app.metadata.webSite?.let { parts.add(it) }
        app.metadata.sourceCode?.let { parts.add(it) }
        links.text = parts.joinToString("\n")
    }

    private fun renderScreenshots(app: App) {
        if (screenshotsLoaded) return
        screenshotsLoaded = true
        lifecycleScope.launch {
            val repoId = version?.repoId ?: app.repoId
            var files = repository.artwork(repoId, packageName)
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
            val shots = files.toList()
            shots.forEachIndexed { index, file ->
                val image = ImageView(this@DetailsActivity)
                image.scaleType = ImageView.ScaleType.CENTER_CROP
                image.setBackgroundResource(R.color.card_image_background)
                val params = LinearLayout.LayoutParams(dp(220), dp(124))
                params.marginEnd = dp(10)
                image.layoutParams = params
                Fdroid.imageModel(repoId, file)?.let { model ->
                    Glide.with(this@DetailsActivity).load(model).into(image)
                }
                image.setOnClickListener {
                    startActivity(ScreenshotActivity.intent(this@DetailsActivity, repoId, shots, index))
                }
                screenshots.addView(image)
            }
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
            installing -> addAction(getString(R.string.action_installing), primary = true) {}
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
