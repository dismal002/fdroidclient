package org.fdroid.phone.history

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Records installs, updates and removals, newest first. 2.0 keeps this as a
 * small JSON log rather than a database table, which is also the cheapest thing
 * to backport: no schema migration for the index database.
 */
object InstallHistory {

    const val ACTION_INSTALLED = "installed"
    const val ACTION_UPDATED = "updated"
    const val ACTION_UNINSTALLED = "uninstalled"

    data class Event(
        val packageName: String,
        val name: String,
        val versionName: String?,
        val action: String,
        val timestamp: Long,
    )

    private const val FILE_NAME = "install_history.json"
    private const val MAX_EVENTS = 500

    @Synchronized
    fun record(context: Context, event: Event) {
        val events = events(context).toMutableList()
        events.add(0, event)
        while (events.size > MAX_EVENTS) events.removeAt(events.lastIndex)
        write(context, events)
    }

    @Synchronized
    fun events(context: Context): List<Event> {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists()) return emptyList()
        return try {
            val array = JSONArray(file.readText())
            (0 until array.length()).mapNotNull { index ->
                val json = array.optJSONObject(index) ?: return@mapNotNull null
                Event(
                    packageName = json.optString("packageName"),
                    name = json.optString("name"),
                    versionName = json.optString("versionName").takeIf { it.isNotBlank() },
                    action = json.optString("action"),
                    timestamp = json.optLong("timestamp"),
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun write(context: Context, events: List<Event>) {
        val array = JSONArray()
        events.forEach { event ->
            array.put(
                JSONObject().apply {
                    put("packageName", event.packageName)
                    put("name", event.name)
                    put("versionName", event.versionName ?: "")
                    put("action", event.action)
                    put("timestamp", event.timestamp)
                },
            )
        }
        try {
            File(context.filesDir, FILE_NAME).writeText(array.toString())
        } catch (e: Exception) {
            // The log is a convenience, never worth failing an install over.
        }
    }
}
