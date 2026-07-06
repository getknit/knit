package app.getknit.knit.ui

import android.Manifest
import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Pure-JVM guard for the SDK-tiered [requiredMeshPermissions]. `Manifest.permission.*` and
 * `Build.VERSION_CODES.*` are compile-time constants (inlined into the test bytecode), so this needs no
 * Robolectric — which matters because Robolectric is pinned to `sdk=36` and could only ever exercise the
 * ≥33 branch. The load-bearing assertion is that the two API-33-only permissions are **absent** below 33,
 * since including an ungrantable permission would keep `hasAllMeshPermissions` false and wedge onboarding.
 */
class PermissionsTest {
    @Test
    fun tiramisu_isLocationFree_withNearbyWifiAndNotifications() {
        val perms = requiredMeshPermissions(Build.VERSION_CODES.TIRAMISU).toSet()
        assertEquals(
            setOf(
                Manifest.permission.NEARBY_WIFI_DEVICES,
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.POST_NOTIFICATIONS,
            ),
            perms,
        )
        assertFalse(Manifest.permission.ACCESS_FINE_LOCATION in perms)
        assertFalse(Manifest.permission.ACCESS_COARSE_LOCATION in perms)
    }

    @Test
    fun android12_requestsSplitBtPlusLocationPair_neverTheApi33Perms() {
        // S = 31, S_V2 = 32. Android 12 requires FINE + COARSE together (the precise/approximate toggle).
        for (sdk in intArrayOf(Build.VERSION_CODES.S, Build.VERSION_CODES.S_V2)) {
            val perms = requiredMeshPermissions(sdk).toSet()
            assertEquals(
                "sdk=$sdk",
                setOf(
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_ADVERTISE,
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                ),
                perms,
            )
            // The onboarding-wedge guard: neither API-33 permission may be requested pre-33.
            assertFalse("sdk=$sdk", Manifest.permission.NEARBY_WIFI_DEVICES in perms)
            assertFalse("sdk=$sdk", Manifest.permission.POST_NOTIFICATIONS in perms)
        }
    }

    @Test
    fun android10and11_requestOnlyTheLocationPair() {
        // Q = 29, R = 30 — legacy BLUETOOTH/BLUETOOTH_ADMIN are normal (auto-granted), not requested at runtime.
        for (sdk in intArrayOf(Build.VERSION_CODES.Q, Build.VERSION_CODES.R)) {
            assertEquals(
                "sdk=$sdk",
                setOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                ),
                requiredMeshPermissions(sdk).toSet(),
            )
        }
    }
}
