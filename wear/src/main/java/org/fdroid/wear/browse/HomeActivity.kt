package org.fdroid.wear.browse

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import kotlinx.coroutines.launch
import org.fdroid.wear.Fdroid
import org.fdroid.wear.R
import org.fdroid.wear.details.DetailsActivity
import org.fdroid.wear.search.SearchActivity

/**
 * The watch store home, modelled on Play-Wear: a search orb at the top and
 * clusters of apps, each with a translucent band header. Everything sits inside
 * the round display with generous side padding.
 */
class HomeActivity : AppCompatActivity() {

    private lateinit var repository: AppRepository
    private lateinit var clusters: LinearLayout
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_wear_home)
        repository = AppRepository(this)
        clusters = findViewById(R.id.clusters)
        status = findViewById(R.id.status)

        findViewById<View>(R.id.search_button).setOnClickListener {
            startActivity(Intent(this, SearchActivity::class.java))
        }

        repository.overview().observe(this) { items ->
            lifecycleScope.launch {
                val cards = repository.listCards(
                    items,
                    Fdroid.updatableApps(),
                    repository.installedPackages(),
                )
                render(cards)
            }
        }
    }

    private fun render(cards: List<AppCard>) {
        clusters.removeAllViews()
        status.visibility = if (cards.isEmpty()) View.VISIBLE else View.GONE

        val updates = cards.filter { it.updateAvailable }.take(6)
        if (updates.isNotEmpty()) {
            addCluster(getString(R.string.wear_updates), updates)
        }

        val newest = cards.sortedByDescending { it.added }.take(8)
        if (newest.isNotEmpty()) {
            addCluster(getString(R.string.wear_new_apps), newest)
        }

        val mostDownloaded = repository.inCuratedOrder(cards, repository.mostDownloadedPackages()).take(8)
        if (mostDownloaded.isNotEmpty()) {
            addCluster(getString(R.string.wear_most_downloaded), mostDownloaded)
        }
    }

    private fun addCluster(title: String, cards: List<AppCard>) {
        clusters.addView(
            TextView(this).apply {
                text = title
                gravity = Gravity.CENTER
                setTextColor(ContextCompat.getColor(this@HomeActivity, R.color.wear_text))
                setBackgroundColor(ContextCompat.getColor(this@HomeActivity, R.color.wear_band))
                textSize = 13f
                setPadding(0, dp(6), 0, dp(6))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(10) }
            },
        )
        cards.forEach { card -> clusters.addView(buildRow(card)) }
    }

    private fun buildRow(card: AppCard): View {
        val row = LayoutInflater.from(this).inflate(R.layout.item_wear_app, clusters, false)
        val icon = row.findViewById<ImageView>(R.id.app_icon)
        Fdroid.imageModel(card.repoId, card.icon)?.let { model ->
            Glide.with(icon).load(model).into(icon)
        }
        row.findViewById<TextView>(R.id.app_name).text = card.name
        val meta = ArrayList<String>()
        if (card.updateAvailable) meta.add(getString(R.string.action_update))
        card.summary?.takeIf { it.isNotBlank() }?.let { meta.add(it) }
        row.findViewById<TextView>(R.id.app_meta).text = meta.joinToString(" · ")
        row.setOnClickListener {
            startActivity(DetailsActivity.intent(this, card.packageName))
        }
        return row
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
