package org.fdroid.tv

import android.app.Application
import com.bumptech.glide.Glide
import org.fdroid.download.DownloadRequest
import org.fdroid.download.glide.DownloadRequestLoader
import org.fdroid.tv.install.InstallService
import java.io.InputStream

class TvApp : Application() {

    override fun onCreate() {
        super.onCreate()
        Fdroid.init(this)
        // Teach Glide to fetch icons and graphics straight out of a repository,
        // so they go through the same mirror and hash handling as the index.
        Glide.get(this).registry.append(
            DownloadRequest::class.java,
            InputStream::class.java,
            DownloadRequestLoader.Factory(Fdroid.httpManager),
        )
        // Only refresh a missing or stale index; the browse screen reacts to database changes.
        if (Fdroid.shouldSyncOnStart) Fdroid.sync(this)
        InstallService.createNotificationChannel(this)
    }
}
