package org.fdroid.tv.settings

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.text.format.DateFormat
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.core.os.LocaleListCompat
import androidx.leanback.preference.LeanbackPreferenceFragment
import androidx.leanback.preference.LeanbackSettingsFragment
import androidx.lifecycle.Observer
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragment
import androidx.preference.PreferenceScreen
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import org.fdroid.database.Repository
import org.fdroid.tv.BuildConfig
import org.fdroid.tv.Fdroid
import org.fdroid.tv.R
import java.util.Date

/**
 * The leanback settings host: it presents the preference panel over the dimmed
 * page, exactly as the platform settings screens of the era did.
 */
class TvSettingsFragment : LeanbackSettingsFragment() {

    override fun onPreferenceStartInitialScreen() {
        startPreferenceFragment(TvPreferenceFragment())
    }

    override fun onPreferenceStartFragment(fragment: PreferenceFragment, preference: Preference): Boolean =
        false

    override fun onPreferenceStartScreen(fragment: PreferenceFragment, screen: PreferenceScreen): Boolean =
        false
}

/** Repository status, the update trigger and app info, as native preferences. */
class TvPreferenceFragment : LeanbackPreferenceFragment() {

    private var updatePreference: Preference? = null

    private val syncObserver = Observer<Fdroid.SyncState> {
        updatePreference?.summary = updateStatus()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setTitle(getString(R.string.settings_title))
        Fdroid.syncState.observeForever(syncObserver)
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        val context = preferenceManager.context
        val screen = preferenceManager.createPreferenceScreen(context)

        val general = PreferenceCategory(context).apply {
            title = context.getString(R.string.settings_general)
        }
        screen.addPreference(general)
        updatePreference = Preference(context).apply {
            title = context.getString(R.string.settings_update_index)
            summary = updateStatus()
            setOnPreferenceClickListener {
                activity?.let { activity -> Fdroid.sync(activity) }
                true
            }
        }
        general.addPreference(updatePreference)

        val repositories = PreferenceCategory(context).apply {
            title = context.getString(R.string.settings_repositories)
        }
        screen.addPreference(repositories)
        Fdroid.repositories.values.sortedBy { it.repoId }.forEach { repository ->
            repositories.addPreference(
                Preference(context).apply {
                    title = repositoryLabel(context, repository)
                    summary = repositorySummary(context, repository)
                    setOnPreferenceClickListener {
                        showRepositoryDialog(context, repository)
                        true
                    }
                },
            )
        }
        repositories.addPreference(
            Preference(context).apply {
                title = context.getString(R.string.settings_add_repository)
                summary = context.getString(R.string.settings_add_repository_summary)
                setOnPreferenceClickListener {
                    showAddRepositoryDialog(context)
                    true
                }
            },
        )

        val about = PreferenceCategory(context).apply {
            title = context.getString(R.string.settings_about)
        }
        screen.addPreference(about)
        about.addPreference(
            Preference(context).apply {
                title = context.getString(R.string.settings_build_version)
                summary = context.getString(
                    R.string.settings_build_version_summary,
                    BuildConfig.VERSION_NAME,
                )
            },
        )
        about.addPreference(
            Preference(context).apply {
                title = context.getString(R.string.settings_source)
                summary = context.getString(R.string.settings_source_summary)
                setOnPreferenceClickListener {
                    openUrl(context, "https://gitlab.com/fdroid/fdroidclient")
                    true
                }
            },
        )

        setPreferenceScreen(screen)
    }

    override fun onDestroyView() {
        Fdroid.syncState.removeObserver(syncObserver)
        super.onDestroyView()
    }

    private fun updateStatus(): String {
        val context = activity ?: return ""
        val repository = Fdroid.repositories.values.firstOrNull { it.enabled }
        val lastUpdated = repository?.lastUpdated
        return when {
            Fdroid.syncState.value is Fdroid.SyncState.Running ->
                context.getString(R.string.index_updating)
            lastUpdated != null && lastUpdated > 0 ->
                context.getString(
                    R.string.settings_last_updated,
                    DateFormat.getMediumDateFormat(context).format(Date(lastUpdated)),
                )
            else -> context.getString(R.string.settings_never_updated)
        }
    }

    private fun repositoryLabel(context: Context, repository: Repository): String =
        repository.getName(LocaleListCompat.getDefault())
            ?: repository.address.substringAfter("//").substringBefore("/")

    private fun repositorySummary(context: Context, repository: Repository): String =
        if (repository.enabled) {
            repository.address
        } else {
            repository.address + " (" + context.getString(R.string.repo_disabled) + ")"
        }

    private fun showRepositoryDialog(context: Context, repository: Repository) {
        AlertDialog.Builder(context)
            .setTitle(repositoryLabel(context, repository))
            .setMessage(repositorySummary(context, repository))
            .setPositiveButton(if (repository.enabled) R.string.repo_disable else R.string.repo_enable) { _, _ ->
                toggleRepository(repository)
            }
            .setNeutralButton(R.string.repo_remove) { _, _ -> confirmRemove(context, repository) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun toggleRepository(repository: Repository) {
        val activity = activity ?: return
        MainScope().launch {
            Fdroid.setRepositoryEnabled(repository.repoId, !repository.enabled)
            activity.recreate()
        }
    }

    private fun confirmRemove(context: Context, repository: Repository) {
        AlertDialog.Builder(context)
            .setTitle(R.string.repo_remove)
            .setMessage(
                context.getString(R.string.repo_remove_confirm, repositoryLabel(context, repository)),
            )
            .setPositiveButton(R.string.repo_remove) { _, _ -> removeRepository(repository) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun removeRepository(repository: Repository) {
        val activity = activity ?: return
        MainScope().launch {
            Fdroid.removeRepository(repository.repoId)
            activity.recreate()
        }
    }

    private fun showAddRepositoryDialog(context: Context) {
        val padding = (24 * resources.displayMetrics.density).toInt()
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding / 2, padding, 0)
        }
        val url = EditText(context).apply {
            hint = context.getString(R.string.repo_url_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine()
        }
        val fingerprint = EditText(context).apply {
            hint = context.getString(R.string.repo_fingerprint_hint)
            inputType = InputType.TYPE_CLASS_TEXT
            textSize = 13f
        }
        container.addView(url)
        container.addView(fingerprint)

        AlertDialog.Builder(context)
            .setTitle(R.string.settings_add_repository)
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                addRepository(context, url.text.toString(), fingerprint.text.toString())
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun addRepository(context: Context, rawUrl: String, rawFingerprint: String) {
        val address = rawUrl.trim()
        val certificate = rawFingerprint.replace(" ", "").replace(":", "").trim()
        if (address.isEmpty() || certificate.length != 64) {
            Toast.makeText(context, R.string.repo_invalid, Toast.LENGTH_LONG).show()
            return
        }
        val name = Uri.parse(address).host ?: address
        val activity = activity ?: return
        MainScope().launch {
            try {
                Fdroid.addRepository(name, address, certificate)
                Fdroid.sync(activity)
                activity.recreate()
            } catch (e: Exception) {
                Toast.makeText(context, R.string.repo_invalid, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun openUrl(context: Context, url: String) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            // no browser on this device
        }
    }
}
