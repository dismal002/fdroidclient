package org.fdroid.phone.home

import android.app.Activity
import android.content.Intent
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.drawerlayout.widget.DrawerLayout
import org.fdroid.phone.R
import org.fdroid.phone.history.HistoryActivity
import org.fdroid.phone.settings.SettingsActivity

/**
 * The slide-out navigation shared by the phone's top-level screens, so the
 * drawer stays reachable from "My apps & games" as well as from the store home.
 */
internal object PhoneDrawer {

    const val APPS = 0
    const val MY_APPS = 1

    private data class Row(
        val label: Int,
        val icon: Int,
        val tint: Int,
        val highlight: Boolean = false,
        val dividerBefore: Boolean = false,
        val action: () -> Unit,
    )

    fun build(activity: Activity, drawer: DrawerLayout, container: LinearLayout, selected: Int) {
        container.removeAllViews()
        val appsSelected = selected == APPS
        val rows = listOf(
            Row(
                R.string.phone_drawer_apps,
                R.drawable.ic_drawer_apps,
                if (appsSelected) 0xff558b2f.toInt() else 0xff757575.toInt(),
                highlight = appsSelected,
            ) {
                if (activity is HomeActivity) {
                    drawer.closeDrawers()
                } else {
                    activity.startActivity(
                        Intent(activity, HomeActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    )
                    activity.finish()
                }
            },
            Row(
                R.string.phone_drawer_my_apps,
                R.drawable.all_apps,
                if (appsSelected) 0xff757575.toInt() else 0xff00796b.toInt(),
                highlight = !appsSelected,
            ) {
                if (activity is MyAppsActivity && selected == MY_APPS) {
                    drawer.closeDrawers()
                } else {
                    activity.startActivity(MyAppsActivity.intent(activity))
                    if (activity !is HomeActivity) activity.finish()
                }
            },
            Row(
                R.string.history_drawer,
                R.drawable.ic_history,
                0xff757575.toInt(),
                dividerBefore = true,
            ) {
                activity.startActivity(Intent(activity, HistoryActivity::class.java))
            },
            Row(
                R.string.phone_drawer_settings,
                R.drawable.ic_settings,
                0xff757575.toInt(),
            ) {
                activity.startActivity(Intent(activity, SettingsActivity::class.java))
            },
        )
        rows.forEach { row ->
            if (row.dividerBefore) {
                container.addView(
                    View(activity).apply { setBackgroundColor(0xffe0e0e0.toInt()) },
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        dp(activity, 1),
                    ).apply {
                        topMargin = dp(activity, 8)
                        bottomMargin = dp(activity, 8)
                    },
                )
            }
            container.addView(drawerRow(activity, row))
        }
    }

    private fun drawerRow(activity: Activity, row: Row): View {
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(activity, 20), 0, dp(activity, 16), 0)
            isClickable = true
            isFocusable = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(activity, 48),
            )
            if (row.highlight) setBackgroundColor(0xffeeeeee.toInt())
            setOnClickListener { row.action() }
        }
        container.addView(ImageView(activity).apply {
            setImageResource(row.icon)
            setColorFilter(row.tint)
            layoutParams = LinearLayout.LayoutParams(dp(activity, 22), dp(activity, 22))
        })
        container.addView(TextView(activity).apply {
            text = activity.getString(row.label)
            textSize = 15f
            setTextColor(0xff212121.toInt())
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f,
            ).apply { marginStart = dp(activity, 20) }
        })
        return container
    }

    private fun dp(activity: Activity, value: Int): Int =
        (value * activity.resources.displayMetrics.density).toInt()
}
