package app.getknit.knit.mesh

import android.app.Application
import androidx.datastore.preferences.core.emptyPreferences
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.getknit.knit.data.settings.SettingsStore
import app.getknit.knit.mesh.power.PowerMonitor
import app.getknit.knit.mesh.power.PowerStateSource
import app.getknit.knit.moderation.MlTextModerator
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.Robolectric
import org.robolectric.android.controller.ServiceController

/**
 * The significant-motion trigger heals at most once a minute (work item #63): the sensor is one-shot and re-armed
 * after every fire, so a walk used to run the whole heal — a scan window, a re-arm and the basket's database reads —
 * every 30-60 s. Same rig as [MeshServicePauseTest]; the shadow sensor manager has no trigger delivery, so the test
 * drives the listener's body directly.
 */
@RunWith(AndroidJUnit4::class)
class MeshServiceMotionTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val mesh = FakeMeshController()
    private var now = 1_700_000_000_000L
    private lateinit var controller: ServiceController<MeshService>

    @Before
    fun setUp() {
        val settings = mockk<SettingsStore>(relaxed = true)
        every { settings.meshEnabled } returns flowOf(true)
        every { settings.meshPausedUntil } returns MutableStateFlow<Long?>(null)
        coEvery { settings.setMeshPausedUntil(any()) } returns emptyPreferences()
        startKoin {
            androidContext(app)
            modules(
                module {
                    single<MeshController> { mesh }
                    single { PowerMonitor(app, PowerStateSource()) }
                    single { settings }
                    single<CoroutineScope> { CoroutineScope(SupervisorJob() + Dispatchers.Unconfined) }
                    single { mockk<MlTextModerator>(relaxed = true) }
                },
            )
        }
        controller = Robolectric.buildService(MeshService::class.java)
        controller.get().clock = { now }
    }

    @After
    fun tearDown() = stopKoin()

    @Test
    fun `a second motion trigger inside the floor is dropped, one past it heals again`() {
        val service = controller.create().get()
        assertEquals(0, mesh.healCount)

        service.onSignificantMotion()
        assertEquals(1, mesh.healCount)

        now += MeshService.MOTION_HEAL_FLOOR_MS / 2
        service.onSignificantMotion()
        assertEquals("a trigger 30 s after the last heal is floored", 1, mesh.healCount)

        now += MeshService.MOTION_HEAL_FLOOR_MS / 2
        service.onSignificantMotion()
        assertEquals("the floor is measured from the last heal, not the last trigger", 2, mesh.healCount)

        now += MeshService.MOTION_HEAL_FLOOR_MS - 1
        service.onSignificantMotion()
        assertEquals(2, mesh.healCount)

        now += 1
        service.onSignificantMotion()
        assertEquals(3, mesh.healCount)
    }
}
