package app.getknit.knit.mesh

import android.app.Application
import android.app.Notification
import android.app.Service
import android.content.Intent
import android.os.Looper
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.getknit.knit.R
import app.getknit.knit.data.settings.SettingsStore
import app.getknit.knit.mesh.power.PowerMonitor
import app.getknit.knit.mesh.power.PowerStateSource
import app.getknit.knit.moderation.MlTextModerator
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Assert.assertEquals
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * `MeshService.onCreate` resolves the mesh graph on the app scope and starts the mesh on the main thread only
 * once it is built (ADR 2026-09.vztn).
 *
 * Play reported the alternative: an "executing service" ANR on 2.6.0 with the main thread inside Tink's Ed25519
 * table precompute under `IdentityKeyStore.parse`, resolved from `onCreate`. That timer (20 s) covers
 * `onCreate` and the `onStartCommand` dispatched right behind it, so the contract has three parts: `onCreate`
 * returns before the graph exists, the start command re-claims the foreground state without reading the graph,
 * and `onDestroy` during the build leaves nothing running. The slow graph here is a `MeshController` factory
 * that blocks on a latch; the scope is a real worker pool so the block stays off the test's main thread, and
 * the hop back to it lands on the paused main looper, which [awaitStart] drains.
 *
 * `MeshServiceForegroundReclaimTest` and `GraphlessProcessTest` bind the scope on `Dispatchers.Unconfined`, which
 * keeps the launch and the hop inline — the synchronous shape those tests were written against.
 */
@RunWith(AndroidJUnit4::class)
class MeshServiceGraphOffMainTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val mesh = FakeMeshController()

    /** Opened by the test to let the controller's factory return: the moment the "keystore" is done. */
    private val gate = CountDownLatch(1)

    /** Counted down by the factory once it has returned, so a test can wait for the resolution itself. */
    private val resolved = CountDownLatch(1)

    @After
    fun tearDown() {
        gate.countDown()
        stopKoin()
    }

    private fun startGraph(
        dispatcher: CoroutineDispatcher,
        controller: () -> MeshController = {
            gate.await()
            resolved.countDown()
            mesh
        },
    ) {
        val settings = mockk<SettingsStore>(relaxed = true)
        every { settings.meshEnabled } returns flowOf(true)
        startKoin {
            androidContext(app)
            modules(
                module {
                    single<MeshController> { controller() }
                    single { PowerMonitor(app, PowerStateSource()) }
                    single { settings }
                    single<CoroutineScope> { CoroutineScope(SupervisorJob() + dispatcher) }
                    single { mockk<MlTextModerator>(relaxed = true) }
                },
            )
        }
    }

    private fun plainStart() = Intent(app, MeshService::class.java)

    private fun statusText(notification: Notification?): String =
        notification!!
            .extras
            .getCharSequence(Notification.EXTRA_TEXT)
            .toString()

    /** Drain the paused main looper until the mesh has been started, or fail. */
    private fun awaitStart() {
        val deadline = SystemClock.uptimeMillis() + AWAIT_MS
        while (mesh.startCount == 0 && SystemClock.uptimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(POLL_MS)
        }
        assertEquals("mesh started once the graph resolved", 1, mesh.startCount)
    }

    @Test
    fun `a start delivered while the graph is still building neither blocks on it nor touches it`() {
        startGraph(Dispatchers.Default)
        // A regression that resolves the graph on the main thread would hang here, not fail: this opens the
        // gate late enough that such a hang turns into the startCount assertion below instead.
        thread(isDaemon = true) {
            Thread.sleep(BACKSTOP_MS)
            gate.countDown()
        }

        val controller = Robolectric.buildService(MeshService::class.java).create()
        val service = controller.get()
        val shadow = shadowOf(service)
        // onCreate came back with the foreground claimed and the graph still building.
        assertEquals(app.getString(R.string.mesh_notification_searching), statusText(shadow.lastForegroundNotification))
        assertEquals(0, mesh.startCount)

        // The start command that follows onCreate: the f69x re-claim, from the seed, without reading the graph.
        service.stopForeground(Service.STOP_FOREGROUND_REMOVE)
        assertNull(shadow.lastForegroundNotification)
        assertEquals(Service.START_STICKY, service.onStartCommand(plainStart(), 0, 1))
        assertEquals(app.getString(R.string.mesh_notification_searching), statusText(shadow.lastForegroundNotification))
        assertEquals(0, mesh.startCount)

        // The graph resolves; the start comes back to the main thread and the live status replaces the seed.
        mesh.neighborCount.value = 2
        gate.countDown()
        awaitStart()
        assertEquals(
            app.resources.getQuantityString(R.plurals.mesh_notification_connected, 2, 2),
            statusText(shadow.lastForegroundNotification),
        )

        controller.destroy()
        assertEquals(1, mesh.stopCount)
    }

    @Test
    fun `destroyed while the graph is building, the service starts nothing`() {
        startGraph(Dispatchers.Default)
        val controller = Robolectric.buildService(MeshService::class.java).create()
        assertEquals(0, mesh.startCount)

        // Taken down before the build lands: onDestroy must not block on it either.
        controller.destroy()
        assertEquals(0, mesh.stopCount)

        gate.countDown()
        assertTrue(resolved.await(AWAIT_MS, TimeUnit.MILLISECONDS))
        // Give the worker's hop to the main thread time to be posted, then run it: it must see `destroyed`.
        Thread.sleep(SETTLE_MS)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, mesh.startCount)
        assertEquals(0, mesh.stopCount)
    }

    @Test
    fun `a graph that cannot be built is still a crash on the main thread`() {
        // Unconfined: the build, its failure and the hop back all run inline in onCreate, so the only thing
        // left on the main looper is the re-throw.
        startGraph(Dispatchers.Unconfined) { throw IllegalStateException("no keystore") }
        // The controller's create() drains the paused looper on its way out, so the re-throw surfaces there;
        // the idle() is for a Robolectric that leaves it queued. Koin wraps a factory's throw in its
        // InstanceCreationException, so look down the cause chain.
        val thrown =
            runCatching {
                Robolectric.buildService(MeshService::class.java).create()
                shadowOf(Looper.getMainLooper()).idle()
            }.exceptionOrNull()
        assertEquals(0, mesh.startCount)
        assertNotNull("the build failure reached the main looper as an uncaught exception", thrown)
        assertTrue(generateSequence(thrown) { it.cause }.any { it is IllegalStateException && it.message == "no keystore" })
    }

    private companion object {
        const val AWAIT_MS = 10_000L
        const val POLL_MS = 10L
        const val SETTLE_MS = 300L
        const val BACKSTOP_MS = 5_000L
    }
}
