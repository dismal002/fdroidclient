package org.fdroid.phone.settings

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.text.format.DateFormat
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.fdroid.database.Repository
import org.fdroid.phone.BuildConfig
import org.fdroid.phone.Fdroid
import org.fdroid.phone.R
import java.util.Date

/**
 * Settings, laid out like the store's settings page of the era: a dark app bar
 * with the title and back arrow, teal section headings, full-width rows with a
 * title over a grey summary, chevrons on the rows that lead somewhere and the
 * build version on a tinted footer block.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var items: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        items = findViewById(R.id.settings_items)
        findViewById<View>(R.id.settings_back).setOnClickListener { finish() }
        buildItems()
        Fdroid.syncState.observe(this) { state ->
            if (state is Fdroid.SyncState.Done || state is Fdroid.SyncState.Error) buildItems()
        }
    }

    override fun onResume() {
        super.onResume()
        buildItems()
    }

    private fun buildItems() {
        items.removeAllViews()

        val repo = Fdroid.repositories.values.firstOrNull { it.enabled }
        val lastUpdated = repo?.lastUpdated
        val status = when {
            Fdroid.syncState.value is Fdroid.SyncState.Running -> getString(R.string.index_updating)
            lastUpdated != null && lastUpdated > 0 ->
                getString(
                    R.string.settings_last_updated,
                    DateFormat.getMediumDateFormat(this).format(Date(lastUpdated)),
                )
            else -> getString(R.string.settings_never_updated)
        }

        addHeader(getString(R.string.settings_general))
        addRow(getString(R.string.settings_update_index), status, chevron = false) {
            Fdroid.sync(this)
            buildItems()
        }
        addDivider()

        addHeader(getString(R.string.settings_repositories))
        Fdroid.repositories.values.sortedBy { it.repoId }.forEach { repository ->
            addRow(
                repositoryLabel(repository),
                repositorySummary(repository),
                chevron = false,
            ) {
                showRepositoryDialog(repository)
            }
            addDivider()
        }
        addRow(
            getString(R.string.settings_add_repository),
            getString(R.string.settings_add_repository_summary),
            chevron = true,
        ) {
            showAddRepositoryDialog()
        }

        addHeader(getString(R.string.settings_about))
        addRow(
            getString(R.string.settings_licenses),
            getString(R.string.settings_licenses_summary),
            chevron = true,
        ) {
            openUrl("https://www.gnu.org/licenses/gpl-3.0.html")
        }
        addDivider()

        addFooter(
            getString(R.string.settings_build_version),
            getString(R.string.settings_version_footer, BuildConfig.VERSION_NAME),
        )
    }

    private fun addHeader(title: String) {
        val header = layoutInflater.inflate(R.layout.settings_header, items, false) as TextView
        header.text = title
        items.addView(header)
    }

    private fun addDivider() {
        items.addView(layoutInflater.inflate(R.layout.item_settings_divider, items, false))
    }

    private fun addRow(
        title: String,
        description: String,
        chevron: Boolean,
        onClick: (() -> Unit)? = null,
    ) {
        val view = layoutInflater.inflate(R.layout.settings_row, items, false)
        view.findViewById<TextView>(R.id.settings_row_title).text = title
        val summary = view.findViewById<TextView>(R.id.settings_row_description)
        if (description.isEmpty()) {
            summary.visibility = View.GONE
        } else {
            summary.text = description
        }
        if (chevron) {
            view.findViewById<ImageView>(R.id.settings_row_state).apply {
                visibility = View.VISIBLE
                setImageResource(R.drawable.ic_chevron)
                setColorFilter(ContextCompatColor.secondary(this@SettingsActivity))
                scaleX = 1f
            }
        }
        if (onClick != null) {
            view.isClickable = true
            view.isFocusable = true
            view.setOnClickListener { onClick() }
        }
        items.addView(view)
    }

    private fun addFooter(title: String, summary: String) {
        val view = layoutInflater.inflate(R.layout.item_settings_footer, items, false)
        view.findViewById<TextView>(R.id.footer_title).text = title
        view.findViewById<TextView>(R.id.footer_summary).text = summary
        items.addView(view)
    }

    private fun repositoryLabel(repository: Repository): String =
        repository.getName(LocaleListCompat.getDefault())
            ?: repository.address.substringAfter("//").substringBefore("/")

    private fun repositorySummary(repository: Repository): String =
        if (repository.enabled) {
            repository.address
        } else {
            repository.address + " (" + getString(R.string.repo_disabled) + ")"
        }

    private fun showRepositoryDialog(repository: Repository) {
        AlertDialog.Builder(this)
            .setTitle(repositoryLabel(repository))
            .setMessage(repositorySummary(repository))
            .setPositiveButton(if (repository.enabled) R.string.repo_disable else R.string.repo_enable) { _, _ ->
                lifecycleScope.launch {
                    Fdroid.setRepositoryEnabled(repository.repoId, !repository.enabled)
                    buildItems()
                }
            }
            .setNeutralButton(R.string.repo_remove) { _, _ -> confirmRemove(repository) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmRemove(repository: Repository) {
        AlertDialog.Builder(this)
            .setTitle(R.string.repo_remove)
            .setMessage(getString(R.string.repo_remove_confirm, repositoryLabel(repository)))
            .setPositiveButton(R.string.repo_remove) { _, _ ->
                lifecycleScope.launch {
                    Fdroid.removeRepository(repository.repoId)
                    buildItems()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showAddRepositoryDialog() {
        val padding = (24 * resources.displayMetrics.density).toInt()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding / 2, padding, 0)
        }
        val url = EditText(this).apply {
            hint = getString(R.string.repo_url_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine()
        }
        val fingerprint = EditText(this).apply {
            hint = getString(R.string.repo_fingerprint_hint)
            inputType = InputType.TYPE_CLASS_TEXT
            textSize = 13f
        }
        container.addView(url)
        container.addView(fingerprint)

        AlertDialog.Builder(this)
            .setTitle(R.string.settings_add_repository)
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                addRepository(url.text.toString(), fingerprint.text.toString())
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun addRepository(rawUrl: String, rawFingerprint: String) {
        val address = rawUrl.trim()
        val certificate = rawFingerprint.replace(" ", "").replace(":", "").trim()
        if (address.isEmpty() || certificate.length != 64) {
            Toast.makeText(this, R.string.repo_invalid, Toast.LENGTH_LONG).show()
            return
        }
        val name = android.net.Uri.parse(address).host ?: address
        lifecycleScope.launch {
            try {
                Fdroid.addRepository(name, address, certificate)
                buildItems()
                Fdroid.sync(this@SettingsActivity)
            } catch (e: Exception) {
                Toast.makeText(this@SettingsActivity, R.string.repo_invalid, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            // no browser on this device
        }
    }

    /** Small helper so the chevron tint does not depend on API 23's Context.getColor. */
    private object ContextCompatColor {
        fun secondary(activity: SettingsActivity): Int =
            androidx.core.content.ContextCompat.getColor(activity, R.color.play_text_secondary)
    }
}
