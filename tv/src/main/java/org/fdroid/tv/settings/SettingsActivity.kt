package org.fdroid.tv.settings

import android.app.Activity
import android.os.Bundle
import org.fdroid.tv.R

/** Hosts the leanback preference panel used for the store settings. */
class SettingsActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.settings_fragment)
    }
}
