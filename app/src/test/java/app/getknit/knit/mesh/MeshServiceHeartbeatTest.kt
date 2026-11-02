package app.getknit.knit.mesh

import android.app.AlarmManager
import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import android.os.Looper
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.getknit.knit.data.settings.SettingsStore
import app.getknit.knit.mesh.power.PowerMonitor
import app.getknit.knit.mesh.power.PowerStateSource
import app.getknit.knit.moderation.MlTextModerator
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.shadows.ShadowAlarmManager
import java.time.Duration

/**
 * The heartbeat reaches the running service as an in-process listener callback, never as a `startService` into
 * it (ADR 2026-11.82s7, #248). Each such start armed AMS's 20 s "executing service" timer against
 * `onStartCommand`, and a main thread held past it — a vendor freezer on realme Note 60/60x, Android 16 — was an
 * ANR for a tick the user never saw. A listener delivery has no timer.
 *
 * Same rig as [MeshServicePauseTest], with the pause key left at null.
 */
@RunWith(AndroidJUnit4::class)
class MeshServiceHeartbeatTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val mesh = FakeMeshController()
    private val alarmManager = app.getSystemService(AlarmManager::class.java)
    private lateinit var controller: ServiceController<MeshService>

    @Before
    fun setUp() {
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
                    single { mockk<MlTextModerator>(relaxed = true) }
                },
            )
        }
        controller = Robolectric.buildService(MeshService::class.java)
    }

    @After
    fun tearDown() = stopKoin()

    private fun create(): MeshService = controller.create().get()

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun heartbeats(): List<ShadowAlarmManager.ScheduledAlarm> =
        shadowOf(alarmManager).scheduledAlarms.filter { it.tag == MeshService.TAG_HEARTBEAT }

    // `operation` is the shadow's only handle on the alarm's PendingIntent; deprecated without a successor.
    @Suppress("DEPRECATION")
    private fun serviceStartAlarms(): List<ShadowAlarmManager.ScheduledAlarm> =
        shadowOf(alarmManager).scheduledAlarms.filter { alarm -> alarm.operation?.let { shadowOf(it).isService } == true }

    // Likewise `onAlarmListener`, the shadow's only handle on a listener alarm's callback.
    @Suppress("DEPRECATION")
    private fun ShadowAlarmManager.ScheduledAlarm.isListener(): Boolean = onAlarmListener != null

    @Suppress("DEPRECATION")
    private fun ShadowAlarmManager.ScheduledAlarm.callback(): AlarmManager.OnAlarmListener = checkNotNull(onAlarmListener)

    @Test
    fun `a started service arms one listener heartbeat and no service start`() {
        create()

        val beat = heartbeats().single()
        assertEquals(AlarmManager.ELAPSED_REALTIME_WAKEUP, beat.getType())
        assertEquals(SystemClock.elapsedRealtime() + AlarmManager.INTERVAL_FIFTEEN_MINUTES, beat.triggerAtMs)
        assertEquals(AlarmManager.INTERVAL_FIFTEEN_MINUTES * 3 / 4, beat.windowLengthMs)
        assertTrue("the tick is an in-process callback", beat.isListener())
        assertTrue("nothing left to start the running service", serviceStartAlarms().isEmpty())
        controller.destroy()
    }

    @Test
    fun `a tick heals in process and re-arms on the grid`() {
        create()
        val first = heartbeats().single()
        // Delivered five minutes into its window.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(20))

        shadowOf(alarmManager).fireAlarm(first)
        idle()

        assertEquals(1, mesh.healCount)
        assertNull("no startService, so no executing-service timer", shadowOf(app).nextStartedService)
        assertEquals(first.triggerAtMs + AlarmManager.INTERVAL_FIFTEEN_MINUTES, heartbeats().single().triggerAtMs)
        controller.destroy()
    }

    @Test
    fun `destroy cancels the heartbeat`() {
        create()

        controller.destroy()

        assertTrue(heartbeats().isEmpty())
    }

    @Test
    fun `a tick delivered after destroy neither heals nor re-arms`() {
        create()
        // On a device a cancel does not withdraw a tick already posted to the handler, so the callback can still
        // run after onDestroy: the guard, not the cancel, is what keeps a dead instance from healing and re-arming.
        val listener = heartbeats().single().callback()
        controller.destroy()

        listener.onAlarm()

        assertEquals(0, mesh.healCount)
        assertTrue(heartbeats().isEmpty())
    }

    @Test
    fun `the start clears the alarms a pre-listener build left armed on the same tokens`() {
        // The tokens the pre-82s7 build armed and an app update leaves in place: the repeating heartbeat (request
        // code 2) and the windowed resume (6). Codes and filters are what identify them.
        fun legacy(
            code: Int,
            action: String,
        ) = PendingIntent.getService(
            app,
            code,
            Intent(app, MeshService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val at = SystemClock.elapsedRealtime() + AlarmManager.INTERVAL_FIFTEEN_MINUTES
        alarmManager.setInexactRepeating(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            at,
            AlarmManager.INTERVAL_FIFTEEN_MINUTES,
            legacy(2, MeshService.ACTION_HEAL),
        )
        alarmManager.setWindow(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, 10 * 60_000L, legacy(6, MeshService.ACTION_RESUME))
        assertEquals(2, serviceStartAlarms().size)

        create()

        assertTrue(serviceStartAlarms().isEmpty())
        assertEquals(1, heartbeats().size)
        controller.destroy()
    }

    /** A tick the system never delivered: the alarm is gone and its callback never runs. */
    private fun loseTheTick() = alarmManager.cancel(heartbeats().single().callback())

    @Test
    fun `a start repairs a heartbeat chain whose tick was lost`() {
        val service = create()
        loseTheTick()
        // A whole interval past the end of the lost tick's window.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(42))

        service.onStartCommand(Intent(app, MeshService::class.java), 0, 2)

        assertEquals(SystemClock.elapsedRealtime() + AlarmManager.INTERVAL_FIFTEEN_MINUTES, heartbeats().single().triggerAtMs)
        controller.destroy()
    }

    @Test
    fun `a move repairs a heartbeat chain whose tick was lost`() {
        val service = create()
        loseTheTick()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(42))

        service.onSignificantMotion()

        assertEquals(SystemClock.elapsedRealtime() + AlarmManager.INTERVAL_FIFTEEN_MINUTES, heartbeats().single().triggerAtMs)
        controller.destroy()
    }

    @Test
    fun `a start leaves a heartbeat that is only late alone`() {
        val service = create()
        val armed = heartbeats().single()
        // Past its window but inside the next interval: Doze may still be holding it.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(30))

        service.onStartCommand(Intent(app, MeshService::class.java), 0, 2)

        assertEquals(armed.triggerAtMs, heartbeats().single().triggerAtMs)
        controller.destroy()
    }

    @Test
    fun `a heal start still heals`() {
        // The revive alarm's start, or a pre-82s7 heartbeat that fired before the first start cleared it.
        val service = create()

        service.onStartCommand(Intent(app, MeshService::class.java).setAction(MeshService.ACTION_HEAL), 0, 2)

        assertEquals(1, mesh.healCount)
        controller.destroy()
    }
}
