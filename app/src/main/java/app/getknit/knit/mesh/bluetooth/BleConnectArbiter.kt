package app.getknit.knit.mesh.bluetooth

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A tiny cross-component gate that lets one holder pause [BluetoothMeshTransport]'s LE scan while it drives
 * a GATT connect on the same controller. The mesh's own scan already starves *its* in-flight L2CAP connects
 * (`mesh-transport.md`, "the BLE scan is demand-gated"), but a connect from another class — the Meshtastic
 * board dial ([app.getknit.knit.mesh.bluetooth.meshtastic.MeshtasticGatt]) — is invisible to that check, so
 * without this the scan would black out the board's short connect→discover→MTU→subscribe window. Android-free
 * and thread-safe (begin/end are called from the GATT callback thread; [busy] is read from the scan loop).
 *
 * Holds are **counted per tag** (ADR 2026-11.gsqu, Stage 3c). Each holder (`lora-dial`, `meshcore-dial`, `gatt-read`)
 * pairs one [begin] with one [end] in a `finally`, but two holds under one tag can overlap: a dial cancelled by a
 * `stop()` runs its `end` while the next dial under the same tag may already hold, and a set of tags let that `end`
 * clear the newer dial's hold, so the mesh scan resumed inside a live connect window. An [end] with no hold left is
 * ignored.
 */
class BleConnectArbiter {
    private val lock = Any()
    private val holds = HashMap<String, Int>()
    private val _busy = MutableStateFlow(false)

    /** True while any holder is mid-connect; the scan loop treats this like one of its own in-flight connects. */
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    fun begin(tag: String) {
        synchronized(lock) {
            holds[tag] = (holds[tag] ?: 0) + 1
            _busy.value = holds.isNotEmpty()
        }
    }

    fun end(tag: String) {
        synchronized(lock) {
            val held = holds[tag] ?: return
            if (held <= 1) holds.remove(tag) else holds[tag] = held - 1
            _busy.value = holds.isNotEmpty()
        }
    }
}
