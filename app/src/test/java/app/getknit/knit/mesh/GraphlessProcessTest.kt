package app.getknit.knit.mesh

import android.app.Application
import android.app.Service
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.getknit.knit.data.settings.SettingsStore
import app.getknit.knit.di.isKoinStarted
import app.getknit.knit.mesh.power.PowerMonitor
import app.getknit.knit.mesh.power.PowerStateSource
import app.getknit.knit.notifications.MessageNotifier
import app.getknit.knit.notifications.NotificationActionReceiver
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf

/**
 * The system-started components created in a process that never ran `KnitApplication.onCreate` — the
 * shape Android gives an app brought up for full backup/restore (*restricted backup mode*: the base
 * `android.app.Application`, no providers, so no `startKoin`), which the system will still hand a service
 * start or a broadcast to. Robolectric's `application=android.app.Application` (robolectric.properties) is
 * exactly that process, so the first test is the field crash verbatim: before the guard, `onCreate` reached
 * `by inject()` and died with `IllegalStateException("KoinApplication has not been started")` (2.5.0,
 * Android 15, in the background). See [isKoinStarted].
 *
 * The last test starts a graph of fakes to show the guard is keyed on the graph, not always declining.
 */
@RunWith(AndroidJUnit4::class)
class GraphlessProcessTest {
    private val app: Application = ApplicationProvider.getApplicationContext()

    @After
    fun tearDown() = stopKoin()

    @Test
    fun `service declines without touching the graph when Koin was never started`() {
        assertFalse(isKoinStarted())
        val controller = Robolectric.buildService(MeshService::class.java)
        // The crash site: onCreate must return without resolving a single injected field.
        val service = controller.create().get()
        val shadow = shadowOf(service)
        assertTrue(shadow.isStoppedBySelf)
        // Never claimed the foreground either — that would be a mesh notification with no mesh behind it.
        assertNull(shadow.lastForegroundNotification)
        // A start command on the same stillborn instance stays graph-free and does not re-arm the restart.
        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(Intent(app, MeshService::class.java), 0, 1))
        controller.destroy()
    }

    @Test
    fun `boot receiver ignores a broadcast delivered without the graph`() {
        assertFalse(isKoinStarted())
        BootReceiver().onReceive(app, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertNull(shadowOf(app).nextStartedService)
    }

    @Test
    fun `notification action receiver drops a tap delivered without the graph`() {
        assertFalse(isKoinStarted())
        NotificationActionReceiver().onReceive(app, Intent(MessageNotifier.ACTION_MARK_READ))
    }

    @Test
    fun `service runs normally when the graph is present`() {
        val mesh = FakeMeshController()
        val settings = mockk<SettingsStore>(relaxed = true)
        every { settings.meshEnabled } returns flowOf(true)
        every { settings.meshPausedUntil } returns flowOf(null)
        startKoin {
            androidContext(app)
            modules(
                module {
                    single<MeshController> { mesh }
                    single { PowerMonitor(app, PowerStateSource()) }
                    single { settings }
                    single<CoroutineScope> { CoroutineScope(SupervisorJob() + Dispatchers.Unconfined) }
                },
            )
        }
        assertTrue(isKoinStarted())
        val controller = Robolectric.buildService(MeshService::class.java)
        val service = controller.create().get()
        val shadow = shadowOf(service)
        assertFalse(shadow.isStoppedBySelf)
        assertNotNull(shadow.lastForegroundNotification)
        assertEquals(1, mesh.startCount)
        assertEquals(Service.START_STICKY, service.onStartCommand(Intent(app, MeshService::class.java), 0, 1))
        controller.destroy()
        assertEquals(1, mesh.stopCount)
    }
}
