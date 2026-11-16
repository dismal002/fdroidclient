package org.fdroid.tv.install

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.fdroid.index.v2.FileV1
import org.fdroid.tv.Fdroid
import org.fdroid.tv.R
import java.io.File
import java.io.IOException
import java.security.MessageDigest

data class InstallRequest(
    val packageName: String,
    val label: String,
    val repoId: Long,
    val fileName: String,
    val sha256: String?,
    val size: Long,
    val versionName: String,
)

/**
 * Downloads an APK from the repository and hands it to the system installer.
 * Progress and results go to the details screen via a local broadcast.
 */
class InstallService : Service() {

    companion object {
        const val ACTION_INSTALL_STATE = "org.fdroid.tv.INSTALL_STATE"
        const val EXTRA_PACKAGE = "org.fdroid.tv.PACKAGE"
        const val EXTRA_STATE = "org.fdroid.tv.STATE"
        const val EXTRA_PROGRESS = "org.fdroid.tv.PROGRESS"
        const val EXTRA_MESSAGE = "org.fdroid.tv.MESSAGE"

        const val STATE_DOWNLOADING = "downloading"
        const val STATE_INSTALLING = "installing"
        const val STATE_DONE = "done"
        const val STATE_ERROR = "error"

        private const val CHANNEL_ID = "installs"
        private const val NOTIFICATION_ID = 4711

        fun createNotificationChannel(context: Context) {
            if (Build.VERSION.SDK_INT < 26) return
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (manager.getNotificationChannel(CHANNEL_ID) != null) return
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.app_name),
                NotificationManager.IMPORTANCE_LOW,
            )
            manager.createNotificationChannel(channel)
        }

        fun start(context: Context, request: InstallRequest) {
            val intent = Intent(context, InstallService::class.java).apply {
                putExtra(EXTRA_PACKAGE, request.packageName)
                putExtra("label", request.label)
                putExtra("repoId", request.repoId)
                putExtra("fileName", request.fileName)
                putExtra("sha256", request.sha256)
                putExtra("size", request.size)
                putExtra("versionName", request.versionName)
            }
            context.startService(intent)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val packageName = intent?.getStringExtra(EXTRA_PACKAGE) ?: return START_NOT_STICKY
        val label = intent.getStringExtra("label") ?: packageName
        startForeground(NOTIFICATION_ID, notification(getString(R.string.install_preparing, label), -1))
        scope.launch { downloadAndInstall(packageName, label, intent) }
        return START_NOT_STICKY
    }

    private fun downloadAndInstall(packageName: String, label: String, intent: Intent) {
        var apkFile: File? = null
        try {
            val repoId = intent.getLongExtra("repoId", -1L)
            val fileName = intent.getStringExtra("fileName") ?: throw IOException("No file name")
            val sha256 = intent.getStringExtra("sha256")
            val size = intent.getLongExtra("size", 0L)
            val repo = Fdroid.repo(repoId) ?: throw IOException("Repository not found")

            broadcast(packageName, STATE_DOWNLOADING, 0)
            updateNotification(getString(R.string.install_downloading, label), 0)

            apkFile = File.createTempFile("install-", ".apk", cacheDir)
            val indexFile = FileV1(fileName, sha256 ?: "", size.takeIf { it > 0 }, null)
            val uri = Fdroid.repoUriBuilder.getUri(
                repo,
                *fileName.split("/").toTypedArray(),
            )
            val downloader = Fdroid.downloaderFactory.create(repo, uri, indexFile, apkFile)
            downloader.setListener { bytesRead, totalBytes ->
                val progress = if (totalBytes > 0) (bytesRead * 100 / totalBytes).toInt() else -1
                broadcast(packageName, STATE_DOWNLOADING, progress)
                updateNotification(getString(R.string.install_downloading, label), progress)
            }
            downloader.download()
            downloader.close()

            if (!sha256.isNullOrBlank() && !sha256(apkFile).equals(sha256, ignoreCase = true)) {
                throw IOException("Checksum mismatch")
            }

            broadcast(packageName, STATE_INSTALLING, -1)
            updateNotification(getString(R.string.install_installing, label), -1)
            commitInstallSession(packageName, apkFile)
        } catch (e: Exception) {
            broadcast(packageName, STATE_ERROR, -1, e.message ?: e.javaClass.simpleName)
        } finally {
            stopForeground(true)
            stopSelf()
        }
    }

    private fun commitInstallSession(packageName: String, apkFile: File) {
        val installer = packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(packageName)
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            apkFile.inputStream().use { input ->
                session.openWrite("package", 0, apkFile.length()).use { output ->
                    input.copyTo(output)
                    session.fsync(output)
                }
            }
            val resultIntent = Intent(this, InstallResultReceiver::class.java)
                .putExtra(EXTRA_PACKAGE, packageName)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            val pendingIntent = PendingIntent.getBroadcast(this, sessionId, resultIntent, flags)
            session.commit(pendingIntent.intentSender)
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun broadcast(
        packageName: String,
        state: String,
        progress: Int,
        message: String? = null,
    ) {
        val intent = Intent(ACTION_INSTALL_STATE).apply {
            putExtra(EXTRA_PACKAGE, packageName)
            putExtra(EXTRA_STATE, state)
            putExtra(EXTRA_PROGRESS, progress)
            putExtra(EXTRA_MESSAGE, message)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun notification(text: String, progress: Int = -1): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            packageManager.getLaunchIntentForPackage(packageName) ?: Intent(),
            PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_IMMUTABLE else 0,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_myapps)
            .setOngoing(true)
            .setProgress(100, progress.coerceAtLeast(0), progress < 0)
            .setContentIntent(openIntent)
            .build()
    }

    private fun updateNotification(text: String, progress: Int) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification(text, progress))
    }
}
