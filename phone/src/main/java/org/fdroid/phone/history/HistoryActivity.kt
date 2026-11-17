package org.fdroid.phone.history

import android.os.Bundle
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import org.fdroid.phone.R

/** The install/update history backported from F-Droid 2.0. */
class HistoryActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_phone_history)
        findViewById<android.view.View>(R.id.history_back).setOnClickListener { finish() }
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        val rows = findViewById<LinearLayout>(R.id.history_rows)
        val empty = findViewById<TextView>(R.id.history_empty)
        rows.removeAllViews()
        val events = InstallHistory.events(this)
        empty.visibility = if (events.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
        events.forEach { event ->
            val view = LayoutInflater.from(this).inflate(R.layout.item_phone_history_row, rows, false)
            view.findViewById<TextView>(R.id.history_name).text = event.name
            val action = when (event.action) {
                InstallHistory.ACTION_UPDATED -> getString(R.string.history_updated)
                InstallHistory.ACTION_UNINSTALLED -> getString(R.string.history_uninstalled)
                else -> getString(R.string.history_installed)
            }
            val version = event.versionName?.takeIf { it.isNotBlank() }
            val whenText = DateUtils.getRelativeTimeSpanString(
                event.timestamp,
                System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS,
            ).toString()
            view.findViewById<TextView>(R.id.history_meta).text = buildString {
                append(action)
                if (version != null && event.action != InstallHistory.ACTION_UNINSTALLED) {
                    append(' ')
                    append(version)
                }
                append(" · ")
                append(whenText)
            }
            rows.addView(view)
        }
    }
}
