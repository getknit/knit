package app.getknit.knit.mesh.bluetooth

import android.os.ParcelUuid

/** Shared BLE identifiers for the advertiser, scanner, and transport. */
internal object BleConstants {

    /**
     * The 16-bit service UUID both peers advertise and scan-filter on. A **16-bit** UUID (not 128-bit) is
     * required so the advert stays inside the 31-byte legacy budget (a 128-bit UUID + service data would
     * overflow it — see [BleAdvertPayload]). Bumped on every breaking wire change so a build across the break
     * hard-partitions at discovery, exactly like Wi-Fi Aware's `SERVICE_NAME` `.vN`. `0xFE30` = v1.
     */
    val SERVICE_UUID: ParcelUuid = ParcelUuid.fromString("0000FE30-0000-1000-8000-00805F9B34FB")
}
