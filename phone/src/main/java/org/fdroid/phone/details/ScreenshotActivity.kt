package org.fdroid.phone.details

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.bumptech.glide.Glide
import org.fdroid.index.v2.FileV2
import org.fdroid.phone.Fdroid
import org.fdroid.phone.R

/**
 * Full-screen viewer for the details page carousel: swipe left and right
 * between the screenshots, tap or press back to return to the app page.
 */
class ScreenshotActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_REPO_ID = "org.fdroid.phone.extra.REPO_ID"
        private const val EXTRA_NAMES = "org.fdroid.phone.extra.NAMES"
        private const val EXTRA_SHAS = "org.fdroid.phone.extra.SHAS"
        private const val EXTRA_INDEX = "org.fdroid.phone.extra.INDEX"

        fun intent(context: Context, repoId: Long, files: List<FileV2>, index: Int): Intent =
            Intent(context, ScreenshotActivity::class.java)
                .putExtra(EXTRA_REPO_ID, repoId)
                .putExtra(EXTRA_NAMES, files.map { it.name }.toTypedArray())
                .putExtra(EXTRA_SHAS, files.map { it.sha256 }.toTypedArray())
                .putExtra(EXTRA_INDEX, index)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_phone_screenshot)

        val repoId = intent.getLongExtra(EXTRA_REPO_ID, 0L)
        val names = intent.getStringArrayExtra(EXTRA_NAMES) ?: run {
            finish()
            return
        }
        val shas = intent.getStringArrayExtra(EXTRA_SHAS) ?: arrayOfNulls<String>(names.size)
        val files = names.mapIndexed { i, name ->
            FileV2(name = name, sha256 = shas.getOrNull(i), ipfsCidV1 = null, size = null)
        }
        if (files.isEmpty()) {
            finish()
            return
        }

        val pager = findViewById<ViewPager2>(R.id.pager)
        val counter = findViewById<TextView>(R.id.viewer_counter)
        pager.adapter = Adapter(files, repoId)
        findViewById<android.view.View>(R.id.viewer_close).setOnClickListener { finish() }

        val showCounter = {
            counter.text = getString(R.string.phone_screen_of, pager.currentItem + 1, files.size)
        }
        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) = showCounter()
        })
        pager.setCurrentItem(intent.getIntExtra(EXTRA_INDEX, 0).coerceIn(0, files.size - 1), false)
        showCounter()
    }

    private inner class Adapter(
        private val files: List<FileV2>,
        private val repoId: Long,
    ) : RecyclerView.Adapter<Adapter.PageHolder>() {

        inner class PageHolder(val image: ImageView) : RecyclerView.ViewHolder(image)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageHolder {
            val image = ImageView(this@ScreenshotActivity).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                setBackgroundColor(0xff000000.toInt())
                scaleType = ImageView.ScaleType.FIT_CENTER
                setOnClickListener { finish() }
            }
            return PageHolder(image)
        }

        override fun onBindViewHolder(holder: PageHolder, position: Int) {
            Fdroid.imageModel(repoId, files[position])?.let { model ->
                Glide.with(holder.image).load(model).fitCenter().into(holder.image)
            }
        }

        override fun getItemCount(): Int = files.size
    }
}
