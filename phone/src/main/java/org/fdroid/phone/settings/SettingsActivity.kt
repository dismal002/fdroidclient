package org.fdroid.phone.settings

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.format.DateFormat
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
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
        addRow(getString(R.string.settings_repo), repo?.address.orEmpty(), chevron = true) {
            repo?.address?.let { openUrl(it) }
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
