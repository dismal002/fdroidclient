package org.fdroid.phone.home

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import kotlinx.coroutines.launch
import org.fdroid.phone.Fdroid
import org.fdroid.phone.R
import org.fdroid.phone.browse.AppCard
import org.fdroid.phone.browse.AppRepository
import org.fdroid.phone.details.DetailsActivity

/** Vertical list of the apps in one section or category. */
class SectionListActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_TITLE = "org.fdroid.phone.extra.TITLE"
        private const val EXTRA_PACKAGES = "org.fdroid.phone.extra.PACKAGES"
        private const val EXTRA_CATEGORY = "org.fdroid.phone.extra.CATEGORY"

        fun intent(context: Context, title: String, packages: Array<String>): Intent =
            Intent(context, SectionListActivity::class.java)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_PACKAGES, packages)

        fun categoryIntent(context: Context, category: String): Intent =
            Intent(context, SectionListActivity::class.java)
                .putExtra(EXTRA_TITLE, category)
                .putExtra(EXTRA_CATEGORY, category)
    }

    private lateinit var repository: AppRepository
    private lateinit var rows: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_phone_list)
        repository = AppRepository(this)
        rows = findViewById(R.id.list_rows)

        findViewById<TextView>(R.id.list_title).text = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        findViewById<View>(R.id.list_back).setOnClickListener { finish() }

        val packages = intent.getStringArrayExtra(EXTRA_PACKAGES)?.toSet().orEmpty()
        val category = intent.getStringExtra(EXTRA_CATEGORY)

        // "My apps & games" is looked up straight from the installed packages:
        // the overview query is capped, so older apps would silently go missing.
        if (category == null && packages.isNotEmpty()) {
            Fdroid.db.getAppDao().getInstalledAppListItems(packageManager).observe(this) { items ->
                lifecycleScope.launch {
                    val cards = repository.installedCards(items, Fdroid.updatableApps())
                        .sortedBy { it.name.lowercase() }
                    render(cards)
                }
            }
            return
        }

        repository.overview().observe(this) { items ->
            lifecycleScope.launch {
                val updates = Fdroid.updatableApps()
                val installed = repository.installedPackages()
                val cards = repository.buildCards(items, updates, installed)
                    .filter { card -> category == null || card.categories.contains(category) }
                    .sortedBy { it.name.lowercase() }
                render(cards)
            }
        }
    }

    private fun render(cards: List<AppCard>) {
        rows.removeAllViews()
        cards.forEach { card ->
            val view = LayoutInflater.from(this).inflate(R.layout.item_phone_list_row, rows, false)
            val icon = view.findViewById<ImageView>(R.id.row_icon)
            val model = Fdroid.imageModel(card.repoId, card.icon)
            if (model != null) {
                Glide.with(icon).load(model).into(icon)
            }
            view.findViewById<TextView>(R.id.row_name).text = card.name
            view.findViewById<TextView>(R.id.row_meta).text = card.summary.orEmpty()
            view.setOnClickListener {
                startActivity(DetailsActivity.intent(this, card.packageName))
            }
            rows.addView(view)
        }
    }
}
