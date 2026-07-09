package app.getknit.knit.mesh

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import app.getknit.knit.BuildConfig
import app.getknit.knit.data.settings.SettingsStore
import app.getknit.knit.ui.hasAllMeshPermissions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Restarts the mesh foreground service after a device reboot — **unless the user manually stopped it
 * beforehand**. The mesh is otherwise dark until the app is next opened: it is only started from the UI
 * ([app.getknit.knit.ui.KnitApp]) and `START_STICKY` doesn't survive a reboot.
 *
 * Gated on the persisted [SettingsStore.meshEnabled] flag (which the service sets false on a manual Stop
 * and true whenever it starts), the current mesh permissions, and `!SEED_DEMO`. Registered for
 * `BOOT_COMPLETED` — delivered post-unlock, so the credential-encrypted settings DataStore is readable,
 * and an exemption to the Android 12+ background foreground-service-start restrictions (the mesh's
 * `connectedDevice` type is boot-permitted). Suspending work (a DataStore read + the FGS start) is kept
 * alive with [goAsync] on the app-lifetime mesh [scope], mirroring
 * [app.getknit.knit.notifications.NotificationActionReceiver].
 */
class BootReceiver :
    BroadcastReceiver(),
    KoinComponent {
    private val settings: SettingsStore by inject()
    private val scope: CoroutineScope by inject()

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val appContext = context.applicationContext
        val pending = goAsync()
        scope.launch {
            runCatching {
                if (shouldStartMeshOnBoot(appContext, settings)) MeshService.start(appContext)
            }.onFailure { Log.w(TAG, "boot mesh start failed", it) }
            pending.finish()
        }
    }

    private companion object {
        const val TAG = "KnitBoot"
    }
}

/**
 * Whether to start the mesh on boot: only if it was left enabled, the mesh permissions are still granted,
 * and this isn't a seeded demo build. A top-level function so the decision is unit-testable without a Koin
 * bootstrap (the receiver just resolves its dependencies and delegates here).
 */
suspend fun shouldStartMeshOnBoot(
    context: Context,
    settings: SettingsStore,
): Boolean = !BuildConfig.SEED_DEMO && settings.meshEnabled.first() && hasAllMeshPermissions(context)
