package app.getknit.knit.mesh.bluetooth

import android.os.ParcelUuid

/** Shared BLE identifiers for the advertiser, scanner, and transport. */
internal object BleConstants {
    /**
     * The 16-bit service UUID both peers advertise (in the service-data AD) and scan-filter on. A **16-bit**
     * UUID (not 128-bit) is required so the advert stays inside the 31-byte legacy budget (a 128-bit UUID +
     * service data would overflow it — see [BleAdvertPayload]). Bumped on every breaking wire change so a build
     * across the break hard-partitions at discovery, exactly like Wi-Fi Aware's `SERVICE_NAME` `.vN`.
     * `0xFE30` = v1; `0xFE31` = v2 (the 128-bit nodeId break — new derivation + a raw-16-byte advert layout);
     * `0xFE32` = v3 (the de-Tink raw-crypto/definite-length-CBOR wire break — re-minted nodeIds, so a v2 node
     * would derive different ids and never verify). Still a 16-bit value in the SIG-assigned `0xFE00–0xFEFF`
     * block (not allocated to this app — accepted pre-release risk; a member-assigned UUID would land here).
     */
    val SERVICE_UUID: ParcelUuid = ParcelUuid.fromString("0000FE32-0000-1000-8000-00805F9B34FB")
}
