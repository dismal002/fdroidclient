package org.fdroid.tv.install

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.widget.Toast
import androidx.localbroadcastmanager.content.LocalBroadcastManager

/**
 * Receives the result of a PackageInstaller session, forwards it to the UI and
 * shows the system confirmation dialog when the installer asks for one.
 */
class InstallResultReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val packageName = intent.getStringExtra(InstallService.EXTRA_PACKAGE) ?: return
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirmation = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                confirmation?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try {
                    context.startActivity(confirmation)
                } catch (e: Exception) {
                    notifyUi(context, packageName, InstallService.STATE_ERROR, e.message ?: "")
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                notifyUi(context, packageName, InstallService.STATE_DONE, null)
            }
            else -> {
                val detail = message ?: "status $status"
                Toast.makeText(context, detail, Toast.LENGTH_LONG).show()
                notifyUi(context, packageName, InstallService.STATE_ERROR, detail)
            }
        }
    }

    private fun notifyUi(context: Context, packageName: String, state: String, message: String?) {
        val broadcast = Intent(InstallService.ACTION_INSTALL_STATE).apply {
            putExtra(InstallService.EXTRA_PACKAGE, packageName)
            putExtra(InstallService.EXTRA_STATE, state)
            putExtra(InstallService.EXTRA_MESSAGE, message)
        }
        LocalBroadcastManager.getInstance(context).sendBroadcast(broadcast)
    }
}
