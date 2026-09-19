package app.getknit.knit.mesh

import android.app.ActivityManager
import android.app.Application
import android.os.PowerManager
import android.os.Process
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * The caller-side foreground-start guard ([MeshService.start]) and the pre-check it leans on
 * ([canReclaimForegroundService]). Since Android 12 `startForegroundService` throws at the *call site* when
 * the app is backgrounded and unexempted — a throw ADR 043's service-side catch is downstream of and cannot
 * see — so the start reports instead of throwing, and never even asks when it can predict the refusal.
 * Work item #32.
 *
 * Robolectric hosts it for the two exemptions the pre-check reads: the battery-optimization grant
 * (`ShadowPowerManager`) and process importance (`ShadowActivityManager` shadows the static
 * `getMyMemoryState`, filling from the entry whose pid is ours). `robolectric.properties` pins `sdk=36`, so
 * the API-31+ branch is the one under test. The `catch` arm can't be reached here — Robolectric never
 * throws the real `ForegroundServiceStartNotAllowedException` — and is covered on device.
 *
 * The last two pin the boot entry point ([MeshService.startFromBoot], work item #76): a process running a
 * broadcast receiver reads as `IMPORTANCE_SERVICE`, which the pre-check refuses, so the receiver's start
 * has to bypass it — `BOOT_COMPLETED` is the platform's exemption, not one the pre-check can read.
 */
@RunWith(AndroidJUnit4::class)
class MeshServiceStartTest {
    private val app: Application = ApplicationProvider.getApplicationContext()

    private fun exemptFromBatteryOptimization() =
        shadowOf(app.getSystemService(PowerManager::class.java)).setIgnoringBatteryOptimizations(app.packageName, true)

    private fun setImportance(importance: Int) {
        val info =
            ActivityManager.RunningAppProcessInfo().apply {
                pid = Process.myPid()
                processName = app.packageName
                this.importance = importance
            }
        shadowOf(app.getSystemService(ActivityManager::class.java)).setProcesses(listOf(info))
    }

    @Test
    fun `starts the service when an activity is visible`() {
        setImportance(ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND)
        assertTrue(MeshService.start(app))
        val started = shadowOf(app).nextStartedService
        assertNotNull(started)
        assertEquals(MeshService::class.java.name, started.component?.className)
    }

    @Test
    fun `starts the service when battery-exempt even though backgrounded`() {
        setImportance(ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED)
        exemptFromBatteryOptimization()
        assertTrue(MeshService.start(app))
        assertNotNull(shadowOf(app).nextStartedService)
    }

    @Test
    fun `declines rather than provoking a refusal when backgrounded and unexempted`() {
        setImportance(ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED)
        assertFalse(MeshService.start(app))
        // Nothing was even asked for — the whole point is not to reach the throwing binder call.
        assertNull(shadowOf(app).nextStartedService)
    }

    @Test
    fun `a foreground service alone is not enough to reclaim`() {
        // The service's own importance can't self-satisfy the check: it only ever reaches the weaker
        // IMPORTANCE_FOREGROUND_SERVICE, which is numerically above IMPORTANCE_FOREGROUND.
        setImportance(ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE)
        assertFalse(canReclaimForegroundService(app))
    }

    @Test
    fun `the ordinary start refuses a receiver's own importance`() {
        // PROCESS_STATE_RECEIVER maps to IMPORTANCE_SERVICE — the state a BOOT_COMPLETED receiver runs at, and
        // the reason the receiver cannot go through [MeshService.start] on an unexempted phone.
        setImportance(ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE)
        assertFalse(MeshService.start(app))
        assertNull(shadowOf(app).nextStartedService)
    }

    @Test
    fun `a boot start reaches the system at a receiver's importance with no battery exemption`() {
        setImportance(ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE)
        assertTrue(MeshService.startFromBoot(app))
        val started = shadowOf(app).nextStartedService
        assertNotNull(started)
        assertEquals(MeshService::class.java.name, started.component?.className)
    }
}
