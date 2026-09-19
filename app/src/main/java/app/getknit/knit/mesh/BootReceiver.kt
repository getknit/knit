package app.getknit.knit.mesh

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import app.getknit.knit.BuildConfig
import app.getknit.knit.data.settings.SettingsStore
import app.getknit.knit.di.isKoinStarted
import app.getknit.knit.ui.hasRadioPermissions
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
 * and true whenever it starts), the radio permissions, and `!SEED_DEMO`. Registered for
 * `BOOT_COMPLETED` — delivered post-unlock, so the credential-encrypted settings DataStore is readable,
 * and an exemption to the Android 12+ background foreground-service-start restrictions (the mesh's
 * `connectedDevice` type is boot-permitted). That exemption is the platform's, though, and
 * [MeshService.start]'s process-state pre-check cannot read it — a receiver process reads as
 * `IMPORTANCE_SERVICE`, which it refuses — so the start goes through [MeshService.startFromBoot], which
 * skips the pre-check and keeps the call-site catch (ADR 2026-09.29dw). The outcome is recorded in
 * [MeshStartGate] like every other caller's, so a boot start the system did refuse shows as deferred in
 * `…debug.STATE`. Suspending work (a DataStore read + the FGS start) is kept alive with [goAsync] on the
 * app-lifetime mesh [scope], mirroring [app.getknit.knit.notifications.NotificationActionReceiver].
 */
class BootReceiver :
    BroadcastReceiver(),
    KoinComponent {
    private val settings: SettingsStore by inject()
    private val scope: CoroutineScope by inject()
    private val startGate: MeshStartGate by inject()

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        // Delivered into a restricted-backup-mode process (a setup-wizard restore can straddle boot): there is
        // no graph to inject from, and the mesh is a job for a normal process — see [isKoinStarted].
        if (!isKoinStarted()) {
            Log.w(TAG, "boot broadcast in a process without the app graph — ignoring")
            return
        }
        val appContext = context.applicationContext
        val pending = goAsync()
        scope.launch {
            runCatching {
                if (shouldStartMeshOnBoot(appContext, settings)) {
                    startGate.record(MeshService.startFromBoot(appContext))
                }
            }.onFailure { Log.w(TAG, "boot mesh start failed", it) }
            pending.finish()
        }
    }

    private companion object {
        const val TAG = "KnitBoot"
    }
}

/**
 * Whether to start the mesh on boot: only if it was left enabled, the radio permissions are still granted
 * (notifications are optional and never a gate), and this isn't a seeded demo build. A top-level function
 * so the decision is unit-testable without a Koin bootstrap (the receiver just resolves its dependencies
 * and delegates here).
 */
suspend fun shouldStartMeshOnBoot(
    context: Context,
    settings: SettingsStore,
): Boolean = !BuildConfig.SEED_DEMO && settings.meshEnabled.first() && hasRadioPermissions(context)
