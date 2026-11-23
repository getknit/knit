package app.getknit.knit.mesh

import android.app.AlarmManager
import android.app.Application
import android.app.Notification
import android.app.Service
import android.content.Intent
import android.os.Looper
import android.os.SystemClock
import androidx.datastore.preferences.core.emptyPreferences
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.getknit.knit.R
import app.getknit.knit.data.settings.SettingsStore
import app.getknit.knit.mesh.power.PowerMonitor
import app.getknit.knit.mesh.power.PowerStateSource
import app.getknit.knit.moderation.MlTextModerator
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

/**
 * The notification's Pause / Resume (ADR 2026-09.wz99): the service stays foreground, the mesh goes down
 * for the span, two inexact alarms and every start are how it comes back, and the store's key is what every
 * door — the two buttons, the chat list's banner, a service born mid-pause — converges on.
 *
 * Same rig as [MeshServiceForegroundReclaimTest], with the pause key on a [MutableStateFlow] the mocked store
 * writes back to (so the service's own writes reach its collector, as the DataStore's would) and the wall
 * clock on a variable the test moves. Like the DataStore's `data`, the key's flow emits again on a write to
 * any other key ([PrefsFile.writeOtherKey]), and a test can hold the service's own write back ([heldWrite]) to
 * land another key's write before it (#292).
 */
@RunWith(AndroidJUnit4::class)
class MeshServicePauseTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val mesh = FakeMeshController()
    private val pauseKey = PrefsFile()

    // Non-null: the next writes of the pause key wait for it before they land.
    private var heldWrite: CompletableDeferred<Unit>? = null
    private var now = 1_700_000_000_000L
    private lateinit var controller: ServiceController<MeshService>

    @Before
    fun setUp() {
        val settings = mockk<SettingsStore>(relaxed = true)
        every { settings.meshEnabled } returns flowOf(true)
        every { settings.meshPausedUntil } returns pauseKey.pausedUntil
        coEvery { settings.setMeshPausedUntil(any()) } coAnswers {
            heldWrite?.await()
            pauseKey.value = firstArg()
            emptyPreferences()
        }
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

    private fun create(): MeshService = controller.create().get()

    private fun intent(action: String) = Intent(app, MeshService::class.java).setAction(action)

    private fun pause(minutes: Int) = intent(MeshService.ACTION_PAUSE).putExtra(MeshService.EXTRA_PAUSE_MINUTES, minutes)

    private val alarmManager get() = shadowOf(app.getSystemService(AlarmManager::class.java))

    // `operation` is the shadow's only handle on the alarm's PendingIntent; deprecated without a successor. A
    // listener alarm has none.
    @Suppress("DEPRECATION")
    private fun ShadowAlarmManager.ScheduledAlarm.action(): String? = operation?.let { shadowOf(it).savedIntent.action }

    @Suppress("DEPRECATION")
    private fun ShadowAlarmManager.ScheduledAlarm.startsService(): Boolean = operation?.let { shadowOf(it).isService } == true

    // Likewise `onAlarmListener`, the shadow's only handle on a listener alarm's callback.
    @Suppress("DEPRECATION")
    private fun ShadowAlarmManager.ScheduledAlarm.isListener(): Boolean = onAlarmListener != null

    /** The while-idle resume (a service start: it has no listener form) and the windowed one (a listener). */
    private fun resumeAlarms(): List<ShadowAlarmManager.ScheduledAlarm> =
        alarmManager.scheduledAlarms.filter { it.action() == MeshService.ACTION_RESUME || it.tag == MeshService.TAG_RESUME }

    private fun fire(tag: String) {
        alarmManager.fireAlarm(alarmManager.scheduledAlarms.single { it.tag == tag })
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun Notification.title() = extras.getCharSequence(Notification.EXTRA_TITLE).toString()

    private fun Notification.text() = extras.getCharSequence(Notification.EXTRA_TEXT).toString()

    private fun Notification.actionTitles() = actions.map { it.title.toString() }

    @Test
    fun `the pause action stops the mesh, says paused and arms the resume alarms`() {
        val service = create()
        assertEquals(1, mesh.startCount)

        assertEquals(Service.START_STICKY, service.onStartCommand(pause(MeshPause.SHORT_MINUTES), 0, 2))

        assertEquals(1, mesh.stopCount)
        val until = now + 15 * 60_000L
        assertEquals(until, pauseKey.value)
        val posted = shadowOf(service).lastForegroundNotification!!
        assertEquals(app.getString(R.string.mesh_notification_paused_title), posted.title())
        assertEquals(app.getString(R.string.mesh_notification_paused_until, pausedUntilLabel(app, until, now)), posted.text())
        assertEquals(
            listOf(app.getString(R.string.mesh_notification_resume), app.getString(R.string.mesh_notification_stop)),
            posted.actionTitles(),
        )
        assertFalse(shadowOf(service).isForegroundStopped)

        val alarms = resumeAlarms()
        assertEquals(2, alarms.size)
        val at = SystemClock.elapsedRealtime() + 15 * 60_000L
        alarms.forEach {
            assertEquals(AlarmManager.ELAPSED_REALTIME_WAKEUP, it.getType())
            assertEquals(at, it.triggerAtMs)
        }
        assertTrue("no listener form reaches a dozing phone", alarms.single { it.isAllowWhileIdle }.startsService())
        val windowed = alarms.single { !it.isAllowWhileIdle }
        assertEquals(10 * 60_000L, windowed.windowLengthMs)
        assertTrue("the windowed resume calls back in process (ADR 2026-11.82s7)", windowed.isListener())
        controller.destroy()
        // Already down: the pause's stop was the one stop.
        assertEquals(1, mesh.stopCount)
        assertTrue(resumeAlarms().isEmpty())
    }

    @Test
    fun `the running notification offers the two spans as distinct tokens, then Stop`() {
        val service = create()
        val posted = shadowOf(service).lastForegroundNotification!!
        assertEquals(
            listOf(
                app.getString(R.string.mesh_notification_pause_short),
                app.getString(R.string.mesh_notification_pause_long),
                app.getString(R.string.mesh_notification_stop),
            ),
            posted.actionTitles(),
        )
        val short = shadowOf(posted.actions[0].actionIntent)
        val long = shadowOf(posted.actions[1].actionIntent)
        assertEquals(MeshService.ACTION_PAUSE, short.savedIntent.action)
        assertEquals(MeshPause.SHORT_MINUTES, short.savedIntent.getIntExtra(MeshService.EXTRA_PAUSE_MINUTES, 0))
        assertEquals(MeshPause.LONG_MINUTES, long.savedIntent.getIntExtra(MeshService.EXTRA_PAUSE_MINUTES, 0))
        assertTrue(short.requestCode != long.requestCode)
        assertEquals(MeshService.ACTION_STOP, shadowOf(posted.actions[2].actionIntent).savedIntent.action)
        controller.destroy()
    }

    @Test
    fun `a span never offered is ignored`() {
        val service = create()
        service.onStartCommand(pause(720), 0, 2)
        assertEquals(0, mesh.stopCount)
        assertNull(pauseKey.value)
        assertTrue(resumeAlarms().isEmpty())
        controller.destroy()
    }

    @Test
    fun `the resume action starts the mesh, says searching and cancels the alarms`() {
        val service = create()
        service.onStartCommand(pause(MeshPause.LONG_MINUTES), 0, 2)
        assertEquals(2, resumeAlarms().size)

        assertEquals(Service.START_STICKY, service.onStartCommand(intent(MeshService.ACTION_RESUME), 0, 3))

        assertEquals(2, mesh.startCount)
        assertNull(pauseKey.value)
        assertTrue(resumeAlarms().isEmpty())
        val posted = shadowOf(service).lastForegroundNotification!!
        assertEquals(app.getString(R.string.mesh_notification_title), posted.title())
        assertEquals(app.getString(R.string.mesh_notification_searching), posted.text())
        controller.destroy()
        assertEquals(2, mesh.stopCount)
    }

    @Test
    fun `a plain start while paused re-claims but does not resume`() {
        val service = create()
        service.onStartCommand(pause(MeshPause.SHORT_MINUTES), 0, 2)
        service.stopForeground(Service.STOP_FOREGROUND_DETACH)

        // KnitApp's route effect and resume observer: every open of the app lands here.
        assertEquals(Service.START_STICKY, service.onStartCommand(Intent(app, MeshService::class.java), 0, 3))

        assertFalse(shadowOf(service).isForegroundStopped)
        assertEquals(1, mesh.startCount)
        assertEquals(app.getString(R.string.mesh_notification_paused_title), shadowOf(service).lastForegroundNotification!!.title())
        assertEquals(2, resumeAlarms().size)
        controller.destroy()
    }

    @Test
    fun `a live status change while paused keeps the paused text`() {
        val service = create()
        service.onStartCommand(pause(MeshPause.SHORT_MINUTES), 0, 2)
        mesh.neighborCount.value = 3
        assertEquals(app.getString(R.string.mesh_notification_paused_title), shadowOf(service).lastForegroundNotification!!.title())
        controller.destroy()
    }

    @Test
    fun `created with a deadline ahead comes up paused`() {
        pauseKey.value = now + 40 * 60_000L
        val service = create()
        assertEquals(0, mesh.startCount)
        assertEquals(app.getString(R.string.mesh_notification_paused_title), shadowOf(service).lastForegroundNotification!!.title())
        assertEquals(2, resumeAlarms().size)
        assertEquals(SystemClock.elapsedRealtime() + 40 * 60_000L, resumeAlarms().first().triggerAtMs)
        controller.destroy()
        assertEquals(0, mesh.stopCount)
        assertTrue(resumeAlarms().isEmpty())
    }

    @Test
    fun `created with a deadline behind comes up running`() {
        pauseKey.value = now - 1
        val service = create()
        assertEquals(1, mesh.startCount)
        assertEquals(app.getString(R.string.mesh_notification_title), shadowOf(service).lastForegroundNotification!!.title())
        assertTrue(resumeAlarms().isEmpty())
        controller.destroy()
    }

    @Test
    fun `a heartbeat past the deadline resumes`() {
        val service = create()
        service.onStartCommand(pause(MeshPause.SHORT_MINUTES), 0, 2)
        now += 16 * 60_000L

        fire(MeshService.TAG_HEARTBEAT)

        assertEquals(2, mesh.startCount)
        assertNull(pauseKey.value)
        assertTrue(resumeAlarms().isEmpty())
        // No start's re-claim follows a listener tick, so the tick posts the running text itself.
        assertEquals(app.getString(R.string.mesh_notification_title), shadowOf(service).lastForegroundNotification!!.title())
        controller.destroy()
    }

    @Test
    fun `a heal start past the deadline resumes`() {
        // The revive alarm's start, or a pre-82s7 build's heartbeat still armed after an update.
        val service = create()
        service.onStartCommand(pause(MeshPause.SHORT_MINUTES), 0, 2)
        now += 16 * 60_000L

        service.onStartCommand(intent(MeshService.ACTION_HEAL), 0, 3)

        assertEquals(2, mesh.startCount)
        assertNull(pauseKey.value)
        assertTrue(resumeAlarms().isEmpty())
        controller.destroy()
    }

    @Test
    fun `the windowed resume alarm resumes in process, without a service start`() {
        val service = create()
        service.onStartCommand(pause(MeshPause.SHORT_MINUTES), 0, 2)
        now += 15 * 60_000L

        fire(MeshService.TAG_RESUME)

        assertEquals(2, mesh.startCount)
        assertNull(pauseKey.value)
        assertTrue(resumeAlarms().isEmpty())
        assertNull("no startService, so no executing-service timer", shadowOf(app).nextStartedService)
        assertEquals(app.getString(R.string.mesh_notification_title), shadowOf(service).lastForegroundNotification!!.title())
        controller.destroy()
    }

    @Test
    fun `a pause tapped past an expired one does not bounce the mesh through running`() {
        val service = create()
        service.onStartCommand(pause(MeshPause.SHORT_MINUTES), 0, 2)
        now += 16 * 60_000L

        service.onStartCommand(pause(MeshPause.LONG_MINUTES), 0, 3)

        assertEquals(1, mesh.startCount)
        assertEquals(1, mesh.stopCount)
        assertEquals(now + 60 * 60_000L, pauseKey.value)
        assertEquals(SystemClock.elapsedRealtime() + 60 * 60_000L, resumeAlarms().first().triggerAtMs)
        controller.destroy()
    }

    @Test
    fun `a plain start past the deadline resumes`() {
        val service = create()
        service.onStartCommand(pause(MeshPause.SHORT_MINUTES), 0, 2)
        now += 16 * 60_000L

        service.onStartCommand(Intent(app, MeshService::class.java), 0, 3)

        assertEquals(2, mesh.startCount)
        assertNull(pauseKey.value)
        controller.destroy()
    }

    @Test
    fun `a store write of null resumes it`() {
        val service = create()
        service.onStartCommand(pause(MeshPause.SHORT_MINUTES), 0, 2)

        // The chat list's Resume and the debug bridge write the key and nothing else.
        pauseKey.value = null

        assertEquals(2, mesh.startCount)
        assertTrue(resumeAlarms().isEmpty())
        assertEquals(app.getString(R.string.mesh_notification_title), shadowOf(service).lastForegroundNotification!!.title())
        controller.destroy()
    }

    @Test
    fun `a store write of a deadline pauses it`() {
        val service = create()
        pauseKey.value = now + 60 * 60_000L
        assertEquals(1, mesh.stopCount)
        assertEquals(2, resumeAlarms().size)
        assertEquals(app.getString(R.string.mesh_notification_paused_title), shadowOf(service).lastForegroundNotification!!.title())
        controller.destroy()
    }

    @Test
    fun `another key's write landing before the pause's own write does not bounce the mesh`() {
        val service = create()
        val idle = { shadowOf(Looper.getMainLooper()).idle() }
        idle()
        val write = CompletableDeferred<Unit>().also { heldWrite = it }

        service.onStartCommand(pause(MeshPause.SHORT_MINUTES), 0, 2)
        idle()
        assertEquals(1, mesh.stopCount)
        assertNull(pauseKey.value) // the tap applied inline; its write has not landed

        pauseKey.writeOtherKey() // MeshManager.stop()'s ledger flush, into the same file
        idle()

        assertEquals(1, mesh.startCount)
        assertEquals(1, mesh.stopCount)
        assertEquals(2, resumeAlarms().size)

        write.complete(Unit)
        idle()

        assertEquals(now + 15 * 60_000L, pauseKey.value)
        assertEquals(1, mesh.startCount)
        assertEquals(1, mesh.stopCount)
        assertEquals(app.getString(R.string.mesh_notification_paused_title), shadowOf(service).lastForegroundNotification!!.title())
        controller.destroy()
    }

    @Test
    fun `another key's write landing before the resume's own write does not bounce the mesh`() {
        val service = create()
        val idle = { shadowOf(Looper.getMainLooper()).idle() }
        service.onStartCommand(pause(MeshPause.SHORT_MINUTES), 0, 2)
        idle()
        assertEquals(1, mesh.stopCount)
        val write = CompletableDeferred<Unit>().also { heldWrite = it }

        service.onStartCommand(intent(MeshService.ACTION_RESUME), 0, 3)
        idle()
        assertEquals(2, mesh.startCount)
        assertEquals(now + 15 * 60_000L, pauseKey.value) // resumed inline; the null has not landed

        pauseKey.writeOtherKey()
        idle()

        assertEquals(2, mesh.startCount)
        assertEquals(1, mesh.stopCount)
        assertTrue(resumeAlarms().isEmpty())

        write.complete(Unit)
        idle()

        assertNull(pauseKey.value)
        assertEquals(2, mesh.startCount)
        assertEquals(1, mesh.stopCount)
        assertEquals(app.getString(R.string.mesh_notification_title), shadowOf(service).lastForegroundNotification!!.title())
        controller.destroy()
    }

    @Test
    fun `Stop while paused clears the deadline without raising the mesh on the way out`() {
        val service = create()
        service.onStartCommand(pause(MeshPause.SHORT_MINUTES), 0, 2)
        assertEquals(1, mesh.stopCount)

        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(intent(MeshService.ACTION_STOP), 0, 3))

        assertNull(pauseKey.value)
        // The collector was cancelled before its own null write could restart the mesh.
        assertEquals(1, mesh.startCount)
        controller.destroy()
        assertEquals(1, mesh.stopCount)
        assertTrue(resumeAlarms().isEmpty())
    }
}

/**
 * The settings file as the pause key sees it: each write, to this key or to another, is a new snapshot, and the key's
 * flow re-emits on every snapshot, as DataStore's `data.map { it[KEY] }` does. A plain [MutableStateFlow] of the key
 * would hide the re-emission behind its own equality check.
 */
private class PrefsFile {
    private data class Snapshot(
        val pausedUntil: Long?,
        val writes: Int,
    )

    private val data = MutableStateFlow(Snapshot(null, 0))

    val pausedUntil: Flow<Long?> = data.map { it.pausedUntil }

    var value: Long?
        get() = data.value.pausedUntil
        set(value) = data.update { Snapshot(value, it.writes + 1) }

    /** A write to another key of the same file (`MeshManager.stop()`'s ledger flush): the pause key is emitted again. */
    fun writeOtherKey() = data.update { it.copy(writes = it.writes + 1) }
}
