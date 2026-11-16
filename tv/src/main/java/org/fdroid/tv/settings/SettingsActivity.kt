package org.fdroid.tv.settings

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.format.DateFormat
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import org.fdroid.tv.BuildConfig
import org.fdroid.tv.Fdroid
import org.fdroid.tv.R
import java.util.Date

/**
 * Repository status, the update trigger and app info, laid out like the store
 * settings screen of the era: a vertically centred list of rows grouped under
 * section headers, each row carrying an icon, a title and a description, with
 * a chevron on the rows that open something.
 */
class SettingsActivity : FragmentActivity() {

    private lateinit var items: LinearLayout
    private val rows = mutableListOf<View>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        items = findViewById(R.id.settings_items)
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
        val previous = rows.indexOfFirst { it.hasFocus() }
        items.removeAllViews()
        rows.clear()

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
        addRow(R.drawable.ic_update, getString(R.string.settings_update_index), status) {
            Fdroid.sync(this)
            buildItems()
        }
        addRow(
            R.drawable.ic_repo,
            getString(R.string.settings_repo),
            repo?.address.orEmpty(),
            chevron = true,
        ) {
            repo?.address?.let { openUrl(it) }
        }

        addHeader(getString(R.string.settings_about))
        addRow(
            R.drawable.ic_info,
            getString(R.string.settings_build_version),
            getString(R.string.settings_build_version_summary, BuildConfig.VERSION_NAME),
        )
        addRow(
            R.drawable.ic_code,
            getString(R.string.settings_source),
            getString(R.string.settings_source_summary),
            chevron = true,
        ) {
            openUrl("https://gitlab.com/fdroid/fdroidclient")
        }

        val target = rows.getOrNull(if (previous >= 0) previous else 0) ?: return
        target.post {
            if (!isFinishing) target.requestFocus()
        }
    }

    private fun addHeader(title: String) {
        val header = layoutInflater.inflate(R.layout.settings_header, items, false) as TextView
        header.text = title
        items.addView(header)
    }

    private fun addRow(
        iconRes: Int,
        title: String,
        description: String,
        chevron: Boolean = false,
        onClick: (() -> Unit)? = null,
    ) {
        val view = layoutInflater.inflate(R.layout.settings_row, items, false)
        view.findViewById<ImageView>(R.id.settings_row_icon).setImageResource(iconRes)
        view.findViewById<TextView>(R.id.settings_row_title).text = title
        view.findViewById<TextView>(R.id.settings_row_description).text = description
        if (description.isEmpty()) {
            view.findViewById<TextView>(R.id.settings_row_description).visibility = View.GONE
        }
        if (chevron) {
            view.findViewById<ImageView>(R.id.settings_row_chevron).visibility = View.VISIBLE
        }
        if (onClick != null) {
            view.isFocusable = true
            view.isClickable = true
            view.setOnClickListener { onClick() }
            view.tag = rows.size
            rows.add(view)
        }
        items.addView(view)
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            // no browser on this device
        }
    }
}
