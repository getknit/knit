package app.getknit.knit.mesh

import android.app.ActivityManager
import android.app.Application
import android.content.Intent
import android.os.Looper
import android.os.Process
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.getknit.knit.data.settings.SettingsStore
import app.getknit.knit.ui.requiredRadioPermissions
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.Shadows.shadowOf

/**
 * The boot-restart decision ([shouldStartMeshOnBoot]): the mesh is restored after a reboot only when it was
 * left enabled **and** the radio permissions are still granted. Robolectric hosts it for the real permission
 * check ([hasRadioPermissions] over [requiredRadioPermissions], location-free at the emulated SDK 36 — and
 * notification-free: the first test holds only the radio set, so it also pins that a declined notification
 * grant never keeps the mesh down after a reboot); the
 * enabled flag is stubbed rather than round-tripped through a DataStore (that round-trip is covered by
 * SettingsStoreTest). `SEED_DEMO` is false in the debug unit-test variant, so the demo short-circuit isn't
 * exercised here.
 *
 * The last test drives the receiver itself, the way the system does — a `BOOT_COMPLETED` broadcast to the
 * manifest-registered [BootReceiver] over a Koin graph of fakes — with the process at a receiver's own
 * importance (`IMPORTANCE_SERVICE`) and no battery exemption. That is the state [canReclaimForegroundService]
 * refuses, and before work item #76 the refusal happened in the receiver: the mesh never came back after a
 * reboot on any phone without the exemption until Knit was opened. The start has to reach the system, and
 * the receiver has to record that it did (ADR 2026-09.29dw).
 */
@RunWith(AndroidJUnit4::class)
class BootReceiverTest {
    private val app: Application = ApplicationProvider.getApplicationContext()

    @After
    fun tearDown() = stopKoin()

    private fun store(enabled: Boolean): SettingsStore {
        val settings = mockk<SettingsStore>()
        every { settings.meshEnabled } returns flowOf(enabled)
        return settings
    }

    private fun grantRadioPermissions() = shadowOf(app).grantPermissions(*requiredRadioPermissions())

    @Test
    fun `starts when enabled and the radio grants are held, notifications aside`() =
        runTest {
            grantRadioPermissions()
            assertTrue(shouldStartMeshOnBoot(app, store(enabled = true)))
        }

    @Test
    fun `does not start when the user disabled the mesh`() =
        runTest {
            grantRadioPermissions()
            assertFalse(shouldStartMeshOnBoot(app, store(enabled = false)))
        }

    @Test
    fun `does not start when radio permissions are missing`() =
        runTest {
            // No grant — Robolectric denies runtime permissions by default, so the mesh must stay down.
            assertFalse(shouldStartMeshOnBoot(app, store(enabled = true)))
        }

    @Test
    fun `a boot broadcast starts the mesh from a receiver process without the battery exemption`() {
        grantRadioPermissions()
        // What ActivityManager reports for a process whose only work is a broadcast receiver.
        val info =
            ActivityManager.RunningAppProcessInfo().apply {
                pid = Process.myPid()
                processName = app.packageName
                importance = ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE
            }
        shadowOf(app.getSystemService(ActivityManager::class.java)).setProcesses(listOf(info))
        val startGate = MeshStartGate()
        startKoin {
            androidContext(app)
            modules(
                module {
                    single { store(enabled = true) }
                    single<CoroutineScope> { CoroutineScope(SupervisorJob() + Dispatchers.Unconfined) }
                    single { startGate }
                },
            )
        }

        app.sendBroadcast(Intent(Intent.ACTION_BOOT_COMPLETED))
        shadowOf(Looper.getMainLooper()).idle()

        val started = shadowOf(app).nextStartedService
        assertNotNull(started)
        assertEquals(MeshService::class.java.name, started.component?.className)
        assertFalse(startGate.deferred.value)
    }
}
