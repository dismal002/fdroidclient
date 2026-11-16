package org.fdroid.tv.browse

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.drawable.toDrawable
import androidx.leanback.widget.Presenter
import androidx.palette.graphics.Palette
import androidx.core.content.ContextCompat
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import org.fdroid.tv.Fdroid
import org.fdroid.tv.R
import kotlin.math.absoluteValue

/**
 * The wide app card of the 2015 store: a 268x134dp image with a coloured info bar.
 * The info bar takes its colour from the artwork, which is what made the store
 * cards look like they belonged to each app.
 */
class CardPresenter(
    private val onClick: (AppCard) -> Unit,
) : Presenter() {

    private class CardViewHolder(view: View) : ViewHolder(view) {
        var card: AppCard? = null
        val image: ImageView = view.findViewById(R.id.card_image)
        val imageFrame: View = view.findViewById(R.id.card_image_frame)
        val focusRing: View = view.findViewById(R.id.card_focus_ring)
        val info: View = view.findViewById(R.id.card_info)
        val icon: ImageView = view.findViewById(R.id.card_icon)
        val title: TextView = view.findViewById(R.id.card_title)
        val subtitle: TextView = view.findViewById(R.id.card_subtitle)
        val status: TextView = view.findViewById(R.id.card_status)
    }

    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.tv_card, parent, false)
        val holder = CardViewHolder(view)
        view.isFocusable = true
        view.isFocusableInTouchMode = true
        view.setOnClickListener {
            holder.card?.let(onClick)
        }
        view.onFocusChangeListener = View.OnFocusChangeListener { v, hasFocus ->
            val ring = (v as ViewGroup).findViewById<View>(R.id.card_focus_ring)
            ring.visibility = if (hasFocus) View.VISIBLE else View.INVISIBLE
            v.animate()
                .scaleX(if (hasFocus) 1.03f else 1f)
                .scaleY(if (hasFocus) 1.03f else 1f)
                .setDuration(120)
                .start()
        }
        return holder
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any) {
        val card = item as AppCard
        val holder = viewHolder as CardViewHolder
        val context = holder.view.context
        holder.card = card

        holder.title.text = card.name
        holder.subtitle.text = card.summary?.lineSequence()?.firstOrNull()
            ?: card.categories.firstOrNull()
            ?: context.getString(R.string.brand)
        holder.status.setText(
            when {
                card.updateAvailable -> R.string.status_update
                card.installed -> R.string.status_installed
                else -> R.string.status_get
            }
        )

        // reset recycled state
        holder.image.setImageDrawable(null)
        holder.image.setPadding(0, 0, 0, 0)
        holder.image.scaleType = ImageView.ScaleType.CENTER_CROP
        holder.imageFrame.setBackgroundColor(ContextCompat.getColor(context, R.color.card_image_background))
        holder.info.setBackgroundColor(ContextCompat.getColor(context, R.color.card_info))
        holder.icon.setImageDrawable(null)
        holder.view.scaleX = 1f
        holder.view.scaleY = 1f
        holder.focusRing.visibility = View.INVISIBLE

        val repoId = card.repoId
        Glide.with(holder.icon)
            .load(Fdroid.imageModel(repoId, card.icon))
            .into(holder.icon)

        val art = card.featureGraphic ?: card.banner
        if (art != null) {
            Glide.with(holder.image)
                .asBitmap()
                .load(Fdroid.imageModel(repoId, art))
                .centerCrop()
                .addListener(object : RequestListener<Bitmap> {
                    override fun onLoadFailed(
                        e: GlideException?,
                        model: Any?,
                        target: Target<Bitmap>,
                        isFirstResource: Boolean,
                    ): Boolean = false

                    override fun onResourceReady(
                        resource: Bitmap,
                        model: Any,
                        target: Target<Bitmap>,
                        dataSource: DataSource,
                        isFirstResource: Boolean,
                    ): Boolean {
                        if (holder.card === card) {
                            holder.info.setBackgroundColor(infoColorFor(resource))
                        }
                        return false
                    }
                })
                .into(holder.image)
        } else {
            // Apps without TV art get the store's fallback: the icon on a muted tile.
            val tile = tileColorFor(card.packageName)
            holder.imageFrame.setBackgroundColor(tile)
            holder.info.setBackgroundColor(tile)
            val padding = dp(context, 26f)
            holder.image.setPadding(padding, padding, padding, padding)
            holder.image.scaleType = ImageView.ScaleType.FIT_CENTER
            Glide.with(holder.image)
                .load(Fdroid.imageModel(repoId, card.icon))
                .into(holder.image)
        }
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {
        val holder = viewHolder as CardViewHolder
        holder.card = null
        holder.image.setImageDrawable(null)
        holder.icon.setImageDrawable(null)
        Glide.with(holder.image).clear(holder.image)
        Glide.with(holder.icon).clear(holder.icon)
    }

    private fun infoColorFor(bitmap: Bitmap): Int {
        val palette = Palette.from(bitmap).maximumColorCount(12).generate()
        val swatch = palette.darkMutedSwatch
            ?: palette.mutedSwatch
            ?: palette.darkVibrantSwatch
            ?: palette.dominantSwatch
            ?: return Color.parseColor("#263238")
        return readableBarColor(swatch.rgb)
    }

    /** Keep the info bar dark enough for the white text to stay readable. */
    private fun readableBarColor(color: Int): Int {
        var candidate = color
        var attempts = 0
        while (ColorUtils.calculateLuminance(candidate) > 0.22f && attempts < 6) {
            candidate = ColorUtils.blendARGB(candidate, Color.BLACK, 0.22f)
            attempts++
        }
        return candidate
    }

    private fun tileColorFor(packageName: String): Int {
        val colors = intArrayOf(
            Color.parseColor("#263238"),
            Color.parseColor("#1c2429"),
            Color.parseColor("#2b3a3a"),
            Color.parseColor("#33272b"),
            Color.parseColor("#1f2a33"),
            Color.parseColor("#2e2b26"),
        )
        return colors[(packageName.hashCode().absoluteValue) % colors.size]
    }

    private fun dp(context: android.content.Context, value: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, context.resources.displayMetrics).toInt()
}
