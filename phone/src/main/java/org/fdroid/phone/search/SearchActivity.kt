package org.fdroid.phone.search

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.lifecycle.LiveData
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import kotlinx.coroutines.launch
import org.fdroid.database.AppListItem
import org.fdroid.database.AppListSortOrder
import org.fdroid.phone.Fdroid
import org.fdroid.phone.R
import org.fdroid.phone.browse.AppCard
import org.fdroid.phone.browse.AppRepository
import org.fdroid.phone.details.DetailsActivity
import org.fdroid.phone.install.InstallRequest
import org.fdroid.phone.install.InstallService
import java.util.concurrent.atomic.AtomicInteger

/**
 * Search over the whole index. The field sits in the green bar and the results
 * are the store's rows: icon, name, summary and a green install button.
 */
class SearchActivity : AppCompatActivity() {

    private lateinit var repository: AppRepository
    private lateinit var results: LinearLayout
    private lateinit var empty: TextView
    private lateinit var field: EditText
    private lateinit var clear: View

    private var items: List<AppListItem> = emptyList()
    private var itemsLive: LiveData<List<AppListItem>>? = null

    private val handler = Handler(Looper.getMainLooper())
    private val generation = AtomicInteger()
    private var pending: Runnable? = null

    private val installReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val state = intent.getStringExtra(InstallService.EXTRA_STATE) ?: return
            if (state != InstallService.STATE_DONE && state != InstallService.STATE_ERROR) return
            // The row still says INSTALLING; re-read the installed state so it turns into OPEN.
            val query = field.text?.toString().orEmpty()
            observeItems()
            if (query.isNotBlank()) search(query)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_phone_search)
        repository = AppRepository(this)
        results = findViewById(R.id.search_results)
        empty = findViewById(R.id.search_empty)
        field = findViewById(R.id.search_query)
        clear = findViewById(R.id.search_clear)

        findViewById<View>(R.id.search_back).setOnClickListener { finish() }
        clear.setOnClickListener {
            field.setText("")
            results.removeAllViews()
            empty.visibility = View.GONE
            clear.visibility = View.GONE
        }
        field.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                clear.visibility = if (s.isNullOrBlank()) View.GONE else View.VISIBLE
                scheduleSearch(s?.toString().orEmpty())
            }
        })
        field.requestFocus()
        observeItems()
    }

    /**
     * Keeps every app of the index in memory, so a keystroke only has to filter
     * this list instead of querying the database. Re-registering the observer
     * re-reads the installed state from the package manager after an install.
     */
    private fun observeItems() {
        val live = Fdroid.db.getAppDao()
            .getAppListItems(packageManager, null, AppListSortOrder.NAME)
        live.observe(this) { all ->
            items = all
            val query = field.text?.toString().orEmpty()
            if (query.isNotBlank()) search(query)
        }
        itemsLive?.removeObservers(this)
        itemsLive = live
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

    private fun scheduleSearch(query: String) {
        pending?.let { handler.removeCallbacks(it) }
        if (query.isBlank()) {
            generation.incrementAndGet()
            results.removeAllViews()
            empty.visibility = View.GONE
            return
        }
        val runnable = Runnable { search(query) }
        pending = runnable
        handler.postDelayed(runnable, 250)
    }

    /**
     * Matches the query against every locale the index has for each app, so an
     * app is found by its name in any language, its author or package name.
     */
    private fun search(query: String) {
        val current = generation.incrementAndGet()
        val trimmed = query.trim()
        if (!SearchText.isSearchable(trimmed)) {
            results.removeAllViews()
            empty.visibility = View.GONE
            return
        }
        val words = SearchText.words(trimmed)
        lifecycleScope.launch {
            val index = repository.searchIndex()
            if (current != generation.get()) return@launch
            val matches = items.filter { item ->
                index[item.packageName]?.let { SearchText.matches(it, words) } == true
            }
            if (current != generation.get()) return@launch
            showResults(repository.cardsFromListItems(matches).take(60))
        }
    }

    private fun showResults(cards: List<AppCard>) {
        results.removeAllViews()
        empty.visibility = if (cards.isEmpty()) View.VISIBLE else View.GONE
        cards.take(60).forEach { card -> results.addView(buildRow(card)) }
    }

    private fun buildRow(card: AppCard): View {
        val row = LayoutInflater.from(this).inflate(R.layout.item_phone_search_row, results, false)
        val icon = row.findViewById<ImageView>(R.id.result_icon)
        Fdroid.imageModel(card.repoId, card.icon)?.let { model ->
            Glide.with(icon).load(model).into(icon)
        }
        row.findViewById<TextView>(R.id.result_name).text = card.name
        row.findViewById<TextView>(R.id.result_meta).text = card.summary.orEmpty()
        row.setOnClickListener { startActivity(DetailsActivity.intent(this, card.packageName)) }

        val action = row.findViewById<TextView>(R.id.result_action)
        when {
            card.installed && !card.updateAvailable -> {
                action.text = getString(R.string.action_open)
                action.setOnClickListener { openApp(card.packageName) }
            }
            card.updateAvailable -> {
                action.text = getString(R.string.action_update)
                action.setOnClickListener { install(card, action) }
            }
            else -> {
                action.text = getString(R.string.action_install)
                action.setOnClickListener { install(card, action) }
            }
        }
        return row
    }

    private fun install(card: AppCard, action: TextView) {
        if (!canInstallPackages()) {
            Toast.makeText(this, R.string.install_unknown_sources, Toast.LENGTH_LONG).show()
            openUnknownSourcesSettings()
            return
        }
        action.text = getString(R.string.action_installing)
        lifecycleScope.launch {
            val version = Fdroid.suggestedVersion(card.packageName, null)
            if (version == null) {
                action.text = getString(R.string.action_install)
                Toast.makeText(this@SearchActivity, R.string.install_no_compatible_version, Toast.LENGTH_LONG).show()
                return@launch
            }
            action.text = getString(R.string.action_installing)
            InstallService.start(
                this@SearchActivity,
                InstallRequest(
                    packageName = card.packageName,
                    label = card.name,
                    repoId = version.repoId,
                    fileName = version.file.name,
                    sha256 = version.file.sha256,
                    size = version.file.size ?: 0L,
                    versionName = version.manifest.versionName ?: "",
                ),
            )
        }
    }

    private fun openApp(packageName: String) {
        val intent = packageManager.getLaunchIntentForPackage(packageName)
        if (intent == null) {
            Toast.makeText(this, R.string.action_open, Toast.LENGTH_SHORT).show()
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
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))
        } else {
            Intent(Settings.ACTION_SECURITY_SETTINGS)
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            // not every build exposes this page
        }
    }

    override fun onDestroy() {
        pending?.let { handler.removeCallbacks(it) }
        super.onDestroy()
    }
}
