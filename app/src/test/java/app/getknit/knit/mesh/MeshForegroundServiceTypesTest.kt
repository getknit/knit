package app.getknit.knit.mesh

import android.content.pm.ServiceInfo
import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure-JVM pin for [meshForegroundServiceTypes], the SDK-tiered bitmask the mesh service claims (ADR
 * 2026-09.535d). `ServiceInfo.FOREGROUND_SERVICE_TYPE_*` and `Build.VERSION_CODES.*` are compile-time
 * constants, so no Robolectric — which could only ever exercise the `sdk=36` branch. Two things are
 * load-bearing: `location` is **present** on 29-32 (the app-op tiers, where a typeless service goes blind
 * off screen — work item #62) and **absent** from 34 (where a runtime `location` type without
 * `FOREGROUND_SERVICE_LOCATION` is a `SecurityException` at `startForeground`).
 */
class MeshForegroundServiceTypesTest {
    private val connectedDevice = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
    private val location = ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION

    @Test
    fun locationTiers_carryTheLocationType() {
        for (sdk in intArrayOf(Build.VERSION_CODES.Q, Build.VERSION_CODES.R, Build.VERSION_CODES.S, Build.VERSION_CODES.S_V2)) {
            assertEquals("sdk=$sdk", connectedDevice or location, meshForegroundServiceTypes(sdk))
        }
    }

    @Test
    fun nearbyDevicesTiers_areConnectedDeviceOnly() {
        for (sdk in intArrayOf(Build.VERSION_CODES.TIRAMISU, Build.VERSION_CODES.UPSIDE_DOWN_CAKE, 35, 36)) {
            assertEquals("sdk=$sdk", connectedDevice, meshForegroundServiceTypes(sdk))
        }
    }
}
