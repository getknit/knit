package app.getknit.knit.crash

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowActivityManager

/**
 * How the platform's record of the previous process's death is read for `ModelLoadGuard` (ADR 037). The
 * case that decides it is `REASON_SIGNALED`: status 11 (SIGSEGV) is a native fault, status 9 (SIGKILL) is
 * `WifiAwareTransport` killing its own wedged process, and the two must never be confused, or a NAN wedge
 * would poison a model. A ROM that never files the tombstone behind `REASON_CRASH_NATIVE` (#9, LineageOS) is
 * caught by the signal arm alone.
 */
@RunWith(AndroidJUnit4::class)
class ProcessExitReasonsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val reasons = ProcessExitReasons(context)

    private fun died(
        reason: Int,
        status: Int = 0,
    ): ProcessExitEvidence? {
        val info =
            ShadowActivityManager.ApplicationExitInfoBuilder
                .newBuilder()
                .setProcessName(context.packageName)
                .setPid(PID)
                .setReason(reason)
                .setStatus(status)
                .build()
        shadowOf(context.getSystemService(ActivityManager::class.java)).addApplicationExitInfo(info)
        return reasons.lastExit()
    }

    private fun assertEvidence(
        nativeFault: Boolean,
        explained: Boolean,
        evidence: ProcessExitEvidence?,
    ) {
        checkNotNull(evidence)
        assertEquals("nativeFault", nativeFault, evidence.nativeFault)
        assertEquals("explained", explained, evidence.explained)
    }

    @Test
    fun noRecordIsNoEvidence() = assertNull(reasons.lastExit())

    @Test
    fun aTombstonedNativeCrashIsAFault() =
        assertEvidence(nativeFault = true, explained = false, died(ApplicationExitInfo.REASON_CRASH_NATIVE))

    @Test
    fun aFaultSignalWithoutATombstoneIsStillAFault() {
        for (signal in listOf(SIGILL, SIGABRT, SIGBUS, SIGSEGV)) {
            assertEvidence(nativeFault = true, explained = false, died(ApplicationExitInfo.REASON_SIGNALED, signal))
        }
    }

    @Test
    fun ourOwnSigkillIsExplainedAndNeverAFault() =
        assertEvidence(nativeFault = false, explained = true, died(ApplicationExitInfo.REASON_SIGNALED, SIGKILL))

    @Test
    fun anotherSignalIsNeitherFaultNorExplained() =
        assertEvidence(nativeFault = false, explained = false, died(ApplicationExitInfo.REASON_SIGNALED, SIGTERM))

    @Test
    fun theOrdinaryDeathsAreExplained() {
        for (reason in listOf(
            ApplicationExitInfo.REASON_CRASH,
            ApplicationExitInfo.REASON_ANR,
            ApplicationExitInfo.REASON_LOW_MEMORY,
            ApplicationExitInfo.REASON_USER_REQUESTED,
            ApplicationExitInfo.REASON_USER_STOPPED,
            ApplicationExitInfo.REASON_PERMISSION_CHANGE,
            ApplicationExitInfo.REASON_PACKAGE_UPDATED,
            ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE,
            ApplicationExitInfo.REASON_DEPENDENCY_DIED,
            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE,
            ApplicationExitInfo.REASON_EXIT_SELF,
        )) {
            assertEvidence(nativeFault = false, explained = true, died(reason))
        }
    }

    @Test
    fun onlyTheNewestRecordCounts() {
        died(ApplicationExitInfo.REASON_SIGNALED, SIGSEGV)
        assertEvidence(nativeFault = false, explained = true, died(ApplicationExitInfo.REASON_SIGNALED, SIGKILL))
    }

    @Test
    fun anUnknownReasonExplainsNothing() = assertEvidence(nativeFault = false, explained = false, died(ApplicationExitInfo.REASON_UNKNOWN))

    private companion object {
        const val PID = 4242
        const val SIGILL = 4
        const val SIGABRT = 6
        const val SIGBUS = 7
        const val SIGKILL = 9
        const val SIGSEGV = 11
        const val SIGTERM = 15
    }
}
