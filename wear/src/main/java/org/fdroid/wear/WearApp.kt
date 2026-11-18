package org.fdroid.wear

import android.app.Application
import com.bumptech.glide.Glide
import org.fdroid.download.DownloadRequest
import org.fdroid.download.glide.DownloadRequestLoader
import org.fdroid.wear.install.InstallService
import java.io.InputStream

class WearApp : Application() {

    override fun onCreate() {
        super.onCreate()
        Fdroid.init(this)
        // Icons and screenshots come straight out of a repository, through the
        // same mirror and hash handling as the index.
        Glide.get(this).registry.append(
            DownloadRequest::class.java,
            InputStream::class.java,
            DownloadRequestLoader.Factory(Fdroid.httpManager),
        )
        if (Fdroid.shouldSyncOnStart) Fdroid.sync(this)
        InstallService.createNotificationChannel(this)
    }
}
