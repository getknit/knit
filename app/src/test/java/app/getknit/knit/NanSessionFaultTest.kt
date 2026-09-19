package app.getknit.knit

import android.os.Build
import app.getknit.knit.mesh.wifiaware.NanSessionFault
import org.junit.Assert.assertEquals
import org.junit.Test

/** The two real framework messages, and the tier gate that keeps a 33+ refusal from being read as "off screen". */
class NanSessionFaultTest {
    // WifiPermissionsUtil.enforceLocationPermission, Android 10-12 — the work item #62 trace verbatim.
    private val locationRefused = SecurityException("UID 10207 does not have Coarse/Fine Location permission")

    // WifiAwareServiceImpl.enforceClientValidity — the terminate we missed, today's onSessionDead path.
    private val deadClient = SecurityException("Attempting to use invalid uid+clientId mapping: uid=10207, clientId=42")

    @Test
    fun aLocationRefusalOnTheAppOpTiers_isOffScreen() {
        for (sdk in intArrayOf(Build.VERSION_CODES.Q, Build.VERSION_CODES.R, Build.VERSION_CODES.S, Build.VERSION_CODES.S_V2)) {
            assertEquals("sdk=$sdk", NanSessionFault.OffScreenLocation, NanSessionFault.classify(locationRefused, sdk))
        }
    }

    @Test
    fun theSameMessageOnTheNearbyDevicesTiers_staysADeadSession() {
        for (sdk in intArrayOf(Build.VERSION_CODES.TIRAMISU, Build.VERSION_CODES.UPSIDE_DOWN_CAKE, 36)) {
            assertEquals("sdk=$sdk", NanSessionFault.DeadSession, NanSessionFault.classify(locationRefused, sdk))
        }
    }

    @Test
    fun aDeadClientMapping_isADeadSessionOnEveryTier() {
        for (sdk in intArrayOf(Build.VERSION_CODES.Q, Build.VERSION_CODES.S_V2, 36)) {
            assertEquals("sdk=$sdk", NanSessionFault.DeadSession, NanSessionFault.classify(deadClient, sdk))
        }
    }

    @Test
    fun anythingElse_isADeadSession() {
        assertEquals(NanSessionFault.DeadSession, NanSessionFault.classify(IllegalStateException("location"), Build.VERSION_CODES.S))
        assertEquals(NanSessionFault.DeadSession, NanSessionFault.classify(SecurityException(), Build.VERSION_CODES.S))
        assertEquals(
            NanSessionFault.DeadSession,
            NanSessionFault.classify(SecurityException("Not the client owning the session"), Build.VERSION_CODES.S),
        )
    }
}
