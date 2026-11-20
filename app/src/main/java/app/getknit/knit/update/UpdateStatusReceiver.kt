package app.getknit.knit.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import app.getknit.knit.di.isKoinStarted
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Where a peer update's install session reports (ADR 2026-11.29m7). Declared `android:exported="false"` with no
 * intent filter and reached only by [UpdateInstaller]'s explicit `PendingIntent`, and even then it acts only on the
 * session the staging's [PendingInstall] names ([InstallStatusPolicy]). A pending status's confirm intent is launched
 * only when it is the installer's own, with every URI grant and any clip data stripped first: launched as sent, a
 * forged one would open Knit's unexported screens and FileProvider grants to whoever wrote it.
 */
class UpdateStatusReceiver :
    BroadcastReceiver(),
    KoinComponent {
    private val staging: UpdateStaging by inject()
    private val installer: UpdateInstaller by inject()

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (!isKoinStarted()) return
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val sessionId = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
        val confirm = confirmIntentOf(intent)
        when (val action = InstallStatusPolicy.decide(status, sessionId, confirm?.action, staging.readPending())) {
            InstallStatusPolicy.Action.Ignore -> {
                Log.w(TAG, "ignored a status for session $sessionId")
            }

            InstallStatusPolicy.Action.Confirm -> {
                val safe = checkNotNull(confirm)
                // The installer's own screen only: never one of Knit's, never one a selector swaps in.
                if (safe.component?.packageName == context.packageName) return
                safe.selector = null
                safe.flags = (safe.flags and GRANT_FLAGS.inv()) or Intent.FLAG_ACTIVITY_NEW_TASK
                safe.clipData = null
                runCatching { context.startActivity(safe) }.onFailure { Log.w(TAG, "could not show the install confirmation: $it") }
            }

            is InstallStatusPolicy.Action.Failed -> {
                Log.i(TAG, "install session $sessionId ended: $status ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)}")
                installer.onFailed(action.transferId)
            }

            InstallStatusPolicy.Action.Done -> {
                Log.i(TAG, "install session $sessionId succeeded")
            }
        }
    }

    private fun confirmIntentOf(intent: Intent): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_INTENT)
        }

    companion object {
        private const val TAG = "KnitUpdate"

        private const val GRANT_FLAGS =
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
    }
}
