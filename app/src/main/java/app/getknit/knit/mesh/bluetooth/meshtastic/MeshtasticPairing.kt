package app.getknit.knit.mesh.bluetooth.meshtastic

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import app.getknit.knit.mesh.lora.BoardDirectory
import app.getknit.knit.mesh.lora.BoardFilter
import app.getknit.knit.mesh.lora.BoardRef

/**
 * Lists the bonded devices for the settings picker, each tagged with the board kind it looks like
 * ([BoardFilter.kindOf]): never a Classic-only device (which rules out most headsets outright); MeshCore on its
 * `MeshCore-` name alone, tested first; else Meshtastic on a board-like name ([BoardFilter.looksLikeBoard]) or the
 * Meshtastic service UUID in the stack's cached services — a positive-only signal, since the cache is empty for most LE
 * bonds until a first connection. A `SecurityException` (permission revoked out from under us) degrades to an empty list
 * rather than crashing, matching the mesh BLE plane's posture.
 */
@SuppressLint("MissingPermission")
internal class BondedBoardDirectory(
    context: Context,
) : BoardDirectory {
    private val adapter: BluetoothAdapter? =
        context.applicationContext.getSystemService(BluetoothManager::class.java)?.adapter

    override fun bonded(): List<BoardRef> =
        runCatching {
            adapter?.bondedDevices.orEmpty().map { device ->
                val name = device.name ?: device.address
                BoardRef(
                    address = device.address,
                    name = name,
                    kind =
                        BoardFilter.kindOf(name, classicOnly = device.type == BluetoothDevice.DEVICE_TYPE_CLASSIC) {
                            device.uuids?.any { it.uuid == MeshtasticUuids.SERVICE } == true
                        },
                )
            }
        }.getOrDefault(emptyList())
}

/** The result of a [MeshtasticBonder.bond] attempt. */
internal sealed interface BondResult {
    data object Bonded : BondResult

    data object AlreadyBonded : BondResult

    data class Failed(
        val reason: Int,
    ) : BondResult

    data object Timeout : BondResult
}

/**
 * Initiates system pairing with a board (the OLED shows a 6-digit PIN) and waits for the bond to settle.
 * Bonding happens here, in the picker — not in the session — so the session only ever deals with an
 * already-bonded device and can classify a later auth failure as a stale bond.
 */
@SuppressLint("MissingPermission")
internal class MeshtasticBonder(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val adapter: BluetoothAdapter? =
        appContext.getSystemService(BluetoothManager::class.java)?.adapter

    suspend fun bond(address: String): BondResult {
        val device = adapter?.getRemoteDevice(address) ?: return BondResult.Failed(reason = -1)
        if (device.bondState == BluetoothDevice.BOND_BONDED) return BondResult.AlreadyBonded
        val settled = kotlinx.coroutines.CompletableDeferred<BondResult>()
        val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(
                    context: Context?,
                    intent: Intent?,
                ) {
                    if (intent?.action != BluetoothDevice.ACTION_BOND_STATE_CHANGED) return
                    when (intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)) {
                        BluetoothDevice.BOND_BONDED -> settled.complete(BondResult.Bonded)
                        BluetoothDevice.BOND_NONE -> settled.complete(BondResult.Failed(reason = 0))
                    }
                }
            }
        ContextCompat.registerReceiver(
            appContext,
            receiver,
            IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        try {
            if (!device.createBond()) return BondResult.Failed(reason = -2)
            // stretches: dead code: MeshtasticBonder has no caller (and its NOT_EXPORTED bond receiver would never fire, 9utz).
            return kotlinx.coroutines.withTimeoutOrNull(BOND_TIMEOUT_MS) { settled.await() } ?: BondResult.Timeout
        } finally {
            runCatching { appContext.unregisterReceiver(receiver) }
        }
    }

    private companion object {
        const val BOND_TIMEOUT_MS = 90_000L
    }
}
