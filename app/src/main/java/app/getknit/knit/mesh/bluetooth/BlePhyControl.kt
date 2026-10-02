package app.getknit.knit.mesh.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** One link's PHY as the bridge reports it (ADR 2026-10.yvn6). */
data class PhyLinkStatus(
    val nodeId: String,
    val phy: LinkPhy,
    val linkRssi: Int?,
    val drives: Boolean,
    val attached: Boolean,
    val switches: Int,
    val gaveUp: Boolean,
)

/**
 * Moves one Bluetooth link between 1M and the Coded PHY (S=8) as its link RSSI says (ADR 2026-10.yvn6): the transport
 * makes one per link whose peer was heard on Coded, while the experiment is on. A GATT client attached to the link's
 * own ACL is the only handle Android gives a link's PHY and RSSI — the L2CAP socket has neither — so this follows
 * [BleDoorbell]'s lifecycle: [isLive] re-checked just before `connectGatt` (on a dead ACL the call would dial), a
 * 2 s attach window, and the client closed with the link. The 2026-10-01 spike measured the attach at 13–25 ms and
 * a switch at 5–700 ms, with the L2CAP channel streaming through it.
 *
 * Only the side that [drives] (the larger node id, [CodedPhyPolicy.drives]) asks for a PHY; the other side attaches
 * too, to read and report what the link is on. One coroutine reads the RSSI and asks [PhyStepper]; the controller's
 * reports arrive on a binder thread and go straight into the stepper, which is synchronized.
 *
 * Device-verified only — there is no host GATT stack; the rules are [PhyStepper]'s, which are JVM-tested.
 */
@SuppressLint("MissingPermission")
internal class BlePhyControl(
    private val context: Context,
    private val device: BluetoothDevice,
    private val nodeId: String,
    private val drives: Boolean,
    private val scope: CoroutineScope,
    private val isLive: () -> Boolean,
    private val mode: () -> CodedPhyMode,
    tuning: () -> PhyTuning,
    private val onStep: (toCoded: Boolean) -> Unit,
    private val onGiveUp: () -> Unit,
    private val now: () -> Long,
    // After every PHY report — the first read and each change — so the transport can republish what links are on.
    private val onPhyKnown: () -> Unit = {},
) {
    private val stepper = PhyStepper(tuning)
    private val pokes = Channel<Unit>(Channel.CONFLATED)
    private val rssiReads = Channel<Int>(Channel.CONFLATED)
    private val attachedSignal = CompletableDeferred<Boolean>()
    private var job: Job? = null

    @Volatile private var gatt: BluetoothGatt? = null

    @Volatile private var attached = false

    @Volatile private var disconnected = false

    fun start() {
        job = scope.launch(Dispatchers.IO) { loop() }
    }

    /** The mode changed: decide now rather than at the next read. */
    fun poke() {
        pokes.trySend(Unit)
    }

    /**
     * Stop, and let the GATT client go. The link stays on whatever PHY it is on: the experiment switched off under a
     * live Coded link used to ask it back to 1M, and at the range Coded was holding it that dropped the link (the
     * 2026-10-01 walk). The open channel keeps the ACL up.
     */
    fun close() {
        job?.cancel()
    }

    fun status(): PhyLinkStatus =
        PhyLinkStatus(
            nodeId = nodeId,
            phy = stepper.phy,
            linkRssi = stepper.smoothedRssi?.toInt(),
            drives = drives,
            attached = attached,
            switches = stepper.switches,
            gaveUp = stepper.gaveUp,
        )

    @Suppress("DEPRECATION") // compileSdk 37.1 deprecates connectGatt(Context, …); see BleDoorbell.Client.open
    private suspend fun loop() {
        try {
            if (!isLive()) return
            val g = runCatching { device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE) }.getOrNull()
            if (g == null) {
                Log.i(TAG, "bt phy $nodeId: connectGatt refused")
                return
            }
            gatt = g
            if (withTimeoutOrNull(ATTACH_TIMEOUT_MS) { attachedSignal.await() } != true) {
                Log.i(TAG, "bt phy $nodeId: attach failed")
                return
            }
            attached = true
            runCatching { g.readPhy() }
            while (currentCoroutineContext().isActive && !disconnected) {
                if (drives) act(g, stepper.decide(mode(), now()))
                withTimeoutOrNull(stepper.nextReadMs()) { pokes.receive() }
                if (runCatching { g.readRemoteRssi() }.getOrNull() == true) {
                    withTimeoutOrNull(RSSI_TIMEOUT_MS) { rssiReads.receive() }?.let { stepper.onRssi(it, now()) }
                }
            }
        } finally {
            attached = false
            gatt?.let {
                // disconnect() drops this client's hold on the link (the channel keeps the ACL); close() unregisters it.
                runCatching { it.disconnect() }
                runCatching { it.close() }
            }
            gatt = null
        }
    }

    private fun act(
        g: BluetoothGatt,
        action: PhyStepper.Action,
    ) {
        when (action) {
            PhyStepper.Action.STAY -> {}

            PhyStepper.Action.REQUEST_CODED -> {
                ask(g, BluetoothDevice.PHY_LE_CODED_MASK, BluetoothDevice.PHY_OPTION_S8, "CODED")
            }

            PhyStepper.Action.REQUEST_ONE_M -> {
                ask(g, BluetoothDevice.PHY_LE_1M_MASK, BluetoothDevice.PHY_OPTION_NO_PREFERRED, "1M")
            }

            PhyStepper.Action.REQUEST_FAST -> {
                val mask = BluetoothDevice.PHY_LE_1M_MASK or BluetoothDevice.PHY_LE_2M_MASK
                ask(g, mask, BluetoothDevice.PHY_OPTION_NO_PREFERRED, "1M|2M")
            }

            PhyStepper.Action.GIVE_UP -> {
                Log.i(TAG, "bt phy $nodeId gave up: no PHY update answered (on ${stepper.phy})")
                onGiveUp()
            }
        }
    }

    /**
     * Asks the controller for the PHYs in [mask] both ways, [what] in the log: Coded at S=8, the range end; 1M alone;
     * or 1M and 2M, leaving the pick to the controller (which takes 2M when both ends have it).
     */
    private fun ask(
        g: BluetoothGatt,
        mask: Int,
        option: Int,
        what: String,
    ) {
        Log.i(TAG, "bt phy $nodeId ask $what (rssi=${stepper.smoothedRssi?.toInt()} ${mode().wire})")
        runCatching { g.setPreferredPhy(mask, mask, option) }
    }

    private fun onReport(
        txPhy: Int,
        status: Int,
        update: Boolean,
    ) {
        val before = stepper.phy
        val wasGivenUp = stepper.gaveUp
        val changed = stepper.onPhy(phyOf(txPhy), status == BluetoothGatt.GATT_SUCCESS, now())
        if (changed) {
            Log.i(TAG, "bt phy $nodeId $before→${stepper.phy} rssi=${stepper.smoothedRssi?.toInt()} (${mode().wire})")
            onStep(stepper.phy == LinkPhy.CODED)
        } else if (!update) {
            Log.i(TAG, "bt phy $nodeId on ${stepper.phy} (drives=$drives)")
        }
        if (stepper.gaveUp && !wasGivenUp) {
            Log.i(TAG, "bt phy $nodeId gave up: update answered ${phyOf(txPhy)} status=$status")
            onGiveUp()
        }
        onPhyKnown()
    }

    private val callback =
        object : BluetoothGattCallback() {
            override fun onConnectionStateChange(
                g: BluetoothGatt,
                status: Int,
                newState: Int,
            ) {
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        attachedSignal.complete(true)
                    }

                    BluetoothProfile.STATE_DISCONNECTED -> {
                        disconnected = true
                        attachedSignal.complete(false)
                        pokes.trySend(Unit)
                    }
                }
            }

            override fun onPhyRead(
                g: BluetoothGatt,
                txPhy: Int,
                rxPhy: Int,
                status: Int,
            ) = onReport(txPhy, status, update = false)

            override fun onPhyUpdate(
                g: BluetoothGatt,
                txPhy: Int,
                rxPhy: Int,
                status: Int,
            ) = onReport(txPhy, status, update = true)

            override fun onReadRemoteRssi(
                g: BluetoothGatt,
                rssi: Int,
                status: Int,
            ) {
                if (status == BluetoothGatt.GATT_SUCCESS) rssiReads.trySend(rssi)
            }
        }

    private companion object {
        const val TAG = BluetoothMeshTransport.TAG

        // As BleDoorbell's: an attach to a live link answers in milliseconds; past this the call is dialing instead.
        const val ATTACH_TIMEOUT_MS = 2_000L

        // A link-RSSI read is one HCI command; one not back by now is skipped, not waited on.
        const val RSSI_TIMEOUT_MS = 1_000L

        fun phyOf(phy: Int): LinkPhy =
            when (phy) {
                BluetoothDevice.PHY_LE_1M -> LinkPhy.ONE_M
                BluetoothDevice.PHY_LE_2M -> LinkPhy.TWO_M
                BluetoothDevice.PHY_LE_CODED -> LinkPhy.CODED
                else -> LinkPhy.UNKNOWN
            }
    }
}
