package app.getknit.knit.mesh.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Reads the [BleAdvertPayload] a peer serves over GATT (companion change A3): the value of [DoorbellPolicy.PAYLOAD_UUID]
 * in the `0xFE30` service, which a foreground iPhone lists in its advert in place of the service data it cannot
 * advertise (knit-ios ADR 2026-09.xzpt). [GattPayloads] decides whom to read and when; this does one read.
 *
 * `connectGatt(TRANSPORT_LE)` → attach → `discoverServices` → the payload characteristic, looked up by UUID since the
 * doorbell shares the service → `readCharacteristic` → [BleAdvertPayload.parse]. There is no ACL to the peer yet, so
 * unlike [BleDoorbell]'s 2 s attach the read *is* a dial, and the whole of it gets [timeoutMs]. No `requestMtu`: the
 * value is 24 bytes, and at the default MTU of 23 the stack fetches its last two with a Read Blob on its own (asking
 * for an MTU races the SMP handshake on a Pixel — `MeshtasticGatt`'s settle). The client is closed after every read,
 * whatever it found: the L2CAP dial that follows makes its own ACL.
 *
 * [BleConnectArbiter] is held for the read, so both scans pause for it as they do for a board dial. Callbacks arrive
 * on a binder thread; each read has its own [Client], whose deferreds are made before the calls that complete them.
 *
 * Device-verified only — there is no host GATT stack — like [BleDoorbell] and `MeshtasticGatt`; the rules it serves
 * are [GattPayloads]', which are JVM-tested.
 */
@SuppressLint("MissingPermission")
internal class BleGattPayloadReader(
    private val context: Context,
    private val arbiter: BleConnectArbiter,
    private val timeoutMs: Long = GattPayloads.READ_TIMEOUT_MS,
) {
    /** How a read ended, and at which phase when it did not find a payload (for the log line). */
    data class Result(
        val outcome: GattPayloads.Outcome,
        val phase: String? = null,
    )

    suspend fun read(device: BluetoothDevice): Result {
        arbiter.begin(ARBITER_TAG)
        val client = Client(device)
        return try {
            withTimeoutOrNull(timeoutMs) { client.read() } ?: Result(GattPayloads.Outcome.Failed, "timeout@${client.phase}")
        } finally {
            client.close()
            arbiter.end(ARBITER_TAG)
        }
    }

    /** One GATT client, from its connect to its close; the callbacks complete what [read] awaits. */
    private inner class Client(
        private val device: BluetoothDevice,
    ) : BluetoothGattCallback() {
        @Volatile
        private var gatt: BluetoothGatt? = null

        private val connected = CompletableDeferred<Boolean>()
        private val discovered = CompletableDeferred<Boolean>()
        private val value = CompletableDeferred<ByteArray?>()

        /** Where the read stands, for a timeout's log line. */
        @Volatile
        var phase = "connect"
            private set

        @Suppress("DEPRECATION", "ReturnCount") // `DEPRECATION`: see BleDoorbell.Client.open; one return per phase
        suspend fun read(): Result {
            gatt = runCatching { device.connectGatt(context, false, this, BluetoothDevice.TRANSPORT_LE) }.getOrNull()
                ?: return failed("connectGatt")
            if (!connected.await()) return failed("connect")
            phase = "discover"
            if (runCatching { gatt?.discoverServices() }.getOrNull() != true || !discovered.await()) return failed("discover")
            val service = gatt?.getService(DoorbellPolicy.SERVICE_UUID) ?: return stranger("service")
            val ch = service.getCharacteristic(DoorbellPolicy.PAYLOAD_UUID) ?: return stranger("characteristic")
            phase = "read"
            if (runCatching { gatt?.readCharacteristic(ch) }.getOrNull() != true) return failed("read")
            val bytes = value.await() ?: return failed("read")
            val payload = BleAdvertPayload.parse(bytes) ?: return stranger("value")
            return Result(GattPayloads.Outcome.Read(payload))
        }

        private fun failed(phase: String) = Result(GattPayloads.Outcome.Failed, phase)

        private fun stranger(phase: String) = Result(GattPayloads.Outcome.Stranger, phase)

        fun close() {
            val g = gatt ?: return
            gatt = null
            // disconnect() cancels a dial still out or drops the link the read made; close() unregisters the client.
            // The adapter may be off and the service unbound, so neither may throw out of here.
            runCatching { g.disconnect() }
            runCatching { g.close() }
        }

        override fun onConnectionStateChange(
            g: BluetoothGatt,
            status: Int,
            newState: Int,
        ) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    connected.complete(true)
                }

                // A refused or dropped connection, before or during the read: every step still waiting fails.
                BluetoothProfile.STATE_DISCONNECTED -> {
                    connected.complete(false)
                    discovered.complete(false)
                    value.complete(null)
                }
            }
        }

        override fun onServicesDiscovered(
            g: BluetoothGatt,
            status: Int,
        ) {
            discovered.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        // API 33+ read; the deprecated form below covers 29–32.
        override fun onCharacteristicRead(
            g: BluetoothGatt,
            ch: BluetoothGattCharacteristic,
            bytes: ByteArray,
            status: Int,
        ) {
            value.complete(bytes.takeIf { status == BluetoothGatt.GATT_SUCCESS })
        }

        @Deprecated("Pre-33 signature", ReplaceWith(""))
        override fun onCharacteristicRead(
            g: BluetoothGatt,
            ch: BluetoothGattCharacteristic,
            status: Int,
        ) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                @Suppress("DEPRECATION")
                value.complete(ch.value?.takeIf { status == BluetoothGatt.GATT_SUCCESS })
            }
        }
    }

    private companion object {
        const val ARBITER_TAG = "gatt-read"
    }
}
