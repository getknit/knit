package app.getknit.knit.mesh.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.util.Log
import app.getknit.knit.mesh.power.ElapsedWait
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Asks one Bluetooth link's controller for a PHY, a connection priority or an ATT MTU, and reads back what the link
 * runs on (#120). A GATT client attached to the link's own ACL is the only handle Android gives on a link's parameters
 * — the L2CAP socket has none — so this follows [BlePhyControl]'s lifecycle: [isLive] re-checked just before
 * `connectGatt` (on a dead ACL the call would dial), a 2 s attach window, no service discovery (its unlock re-sends the
 * central's first parameters, ADR 2026-09.dqvb), and the client closed with the link.
 *
 * It has no policy. Its asks come from [BleLinkBoost] ([enqueue]) and from the debug bridge ([tune]), one tuner per
 * link shared by both, served in order by one coroutine. A bridge ask waits a bounded time for each of the controller's
 * reports, so its reply can carry them; a boost's ask waits for none — the boost hears the outcome through [onParams] —
 * so a release queued at the screen-off edge goes out at once instead of behind a wait the CPU's sleep would stretch.
 * A request the controller leaves unanswered (a priority the link already has reports nothing) is not an error; the
 * status says what was heard. Every parameter update the link reports reaches [onParams], whichever side
 * asked for it: that is how the boost learns the interval. Device-verified only — there is no host GATT stack.
 */
@SuppressLint("MissingPermission")
internal class BleLinkTuner(
    private val context: Context,
    private val device: BluetoothDevice,
    private val nodeId: String,
    private val scope: CoroutineScope,
    private val isLive: () -> Boolean,
    // The elapsed clock (`SystemClock.elapsedRealtime`): the second close's wait runs on it.
    private val now: () -> Long,
    // Every update the link reports, whoever asked (binder thread), and the loop's end: whether it had attached.
    onParams: (LinkParams, ok: Boolean) -> Unit = { _, _ -> },
    private val onEnded: (attached: Boolean) -> Unit = {},
    // When set, called instead of [onEnded] for a loop that ended without making a client (the link was gone, or
    // `connectGatt` refused): nothing can register late, so an arm's slot is free at once (#150). Unset, [onEnded] hears it.
    private val onNeverRegistered: (() -> Unit)? = null,
    // What its log lines begin with: `bt tune` for the boost's and the bridge's tuners (the drain trial reads those
    // lines), `bt acl arm` for the transport's link-up arm (#142), which asks nothing and must not read as a tuner.
    private val label: String = "bt tune",
) {
    private class Ask(
        val request: LinkTuneRequest,
        // Whether each step waits for the controller's report: the bridge's asks do, the boost's never.
        val waits: Boolean,
        val done: CompletableDeferred<Unit> = CompletableDeferred(),
    )

    private val asks = Channel<Ask>(Channel.UNLIMITED)
    private val attachedSignal = CompletableDeferred<Boolean>()
    private val phyReports = Channel<Int>(Channel.CONFLATED)
    private val paramReports = Channel<Boolean>(Channel.CONFLATED)
    private val mtuReports = Channel<Int>(Channel.CONFLATED)
    private val rssiReads = Channel<Int>(Channel.CONFLATED)
    private var job: Job? = null

    @Volatile private var gatt: BluetoothGatt? = null

    @Volatile private var attached = false

    @Volatile private var disconnected = false

    @Volatile private var closed = false

    // Set once the loop let its client go: a client that registers after that is let go again (the race below).
    @Volatile private var released = false

    @Volatile private var txPhy: LinkPhy? = null

    @Volatile private var rxPhy: LinkPhy? = null

    @Volatile private var params: LinkParams? = null

    @Volatile private var paramsStatus: Int? = null

    @Volatile private var mtu: Int? = null

    @Volatile private var mtuAsked: Int? = null

    @Volatile private var rssi: Int? = null

    /** Whether this tuner can still serve an ask: not closed, not cut off from the link, and its loop not over. */
    val alive: Boolean get() = !closed && !disconnected && job?.isCompleted != true

    fun start() {
        job = scope.launch(Dispatchers.IO) { loop() }
    }

    /** Stop, and let the GATT client go. What was asked stays on the link until the link or the other side moves it. */
    fun close() {
        closed = true
        job?.cancel()
    }

    /**
     * Queues what [request] names and serves it without waiting for any report; the reports come through [onParams].
     * False once closed.
     */
    fun enqueue(request: LinkTuneRequest): Boolean = asks.trySend(Ask(request, waits = false)).isSuccess

    /**
     * Serves [request] without waiting for its report, then lets the client go: the last ask of a tuner whose link was
     * replaced over the same ACL. Nothing after it is served, and nothing waits on the elapsed or the monotonic clock
     * but the attach itself.
     */
    fun finish(request: LinkTuneRequest) {
        if (!enqueue(request) || job == null) {
            close()
            return
        }
        asks.close() // the loop serves what is queued, then ends through its finally
    }

    /** Asks for what [request] names and returns the status once the asks are answered or given up on. */
    suspend fun tune(request: LinkTuneRequest): LinkTuneStatus {
        val ask = Ask(request, waits = true)
        if (asks.trySend(ask).isSuccess) {
            // stretches: the bridge's own give-up on one ask; a sleep only returns the status read so far.
            withTimeoutOrNull(TUNE_TIMEOUT_MS) { ask.done.await() }
        }
        return status()
    }

    fun status(): LinkTuneStatus =
        LinkTuneStatus(
            nodeId = nodeId,
            address = device.address,
            attached = attached,
            txPhy = txPhy,
            rxPhy = rxPhy,
            params = params,
            paramsStatus = paramsStatus,
            mtu = mtu,
            mtuAsked = mtuAsked,
            rssi = rssi,
        )

    @Suppress("DEPRECATION") // compileSdk 37.1 deprecates connectGatt(Context, …); see BleDoorbell.Client.open
    private suspend fun loop() {
        var everAttached = false
        try {
            if (!isLive()) return
            val started = now()
            val g = runCatching { device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE) }.getOrNull()
            if (g == null) {
                Log.i(TAG, "$label $nodeId: connectGatt refused")
                return
            }
            gatt = g
            // stretches: give-up on a GATT attach over the link's own ACL; a sleep only delays closing the client.
            if (withTimeoutOrNull(ATTACH_TIMEOUT_MS) { attachedSignal.await() } != true) {
                Log.i(TAG, "$label $nodeId: attach failed")
                return
            }
            attached = true
            everAttached = true
            Log.i(TAG, "$label $nodeId attached (${device.address}) in ${now() - started}ms")
            for (ask in asks) {
                if (!disconnected) serve(g, ask.request, ask.waits)
                ask.done.complete(Unit)
            }
        } finally {
            val registered = gatt != null
            attached = false
            asks.close()
            while (true) {
                asks
                    .tryReceive()
                    .getOrNull()
                    ?.done
                    ?.complete(Unit) ?: break
            }
            released = true
            gatt?.let {
                // disconnect() drops this client's hold on the link (the channel keeps the ACL); close() unregisters it.
                runCatching { it.disconnect() }
                runCatching { it.close() }
                if (!everAttached) closeAgainLater(it)
            }
            gatt = null
            if (!closed) {
                if (!registered && onNeverRegistered != null) onNeverRegistered.invoke() else onEnded(everAttached)
            }
        }
    }

    /**
     * A client that never attached may not have registered yet, and on Android 12 `close()` before `onClientRegistered`
     * does nothing (`unregisterApp` returns while the client id is 0): the late registration then connects and holds the
     * link, and with it the ACL. [released] makes its connect callback let go; this second close is the net for a
     * registration whose connect never reports — a direct connect to an ACL already gone, which would dial.
     */
    private fun closeAgainLater(g: BluetoothGatt) {
        scope.launch(Dispatchers.IO) {
            // On the elapsed clock: a registration's pending direct connect is a radio user, and a sleep must not
            // stretch it (ADR 2026-10.pj9w). The transport's scope is the app's and outlives a stop; were it cancelled,
            // the connect callback's let-go still holds, and a registration dies with the process's binder.
            ElapsedWait(now).sleep(SECOND_CLOSE_MS)
            runCatching { g.disconnect() }
            runCatching { g.close() }
        }
    }

    private suspend fun serve(
        g: BluetoothGatt,
        request: LinkTuneRequest,
        waits: Boolean,
    ) {
        request.phy?.let { askPhy(g, it, waits) }
        request.priority?.let { askPriority(g, it, waits) }
        request.mtu?.let { askMtu(g, it, waits) }
        if (request.read) read(g)
    }

    private suspend fun askPhy(
        g: BluetoothGatt,
        phy: TunePhy,
        waits: Boolean,
    ) {
        val mask =
            when (phy) {
                TunePhy.ONE_M -> BluetoothDevice.PHY_LE_1M_MASK
                TunePhy.TWO_M -> BluetoothDevice.PHY_LE_2M_MASK
                TunePhy.ONE_M_2M -> BluetoothDevice.PHY_LE_1M_MASK or BluetoothDevice.PHY_LE_2M_MASK
            }
        phyReports.tryReceive()
        val asked = runCatching { g.setPreferredPhy(mask, mask, BluetoothDevice.PHY_OPTION_NO_PREFERRED) }.isSuccess
        Log.i(TAG, "$label $nodeId ask phy=${phy.name} requested=$asked")
        // stretches: the bridge's give-up on one PHY update (a boost's ask never waits); a sleep only delays its reply.
        if (asked && waits) withTimeoutOrNull(REPORT_TIMEOUT_MS) { phyReports.receive() }
    }

    private suspend fun askPriority(
        g: BluetoothGatt,
        priority: TunePriority,
        waits: Boolean,
    ) {
        val code =
            when (priority) {
                TunePriority.HIGH -> BluetoothGatt.CONNECTION_PRIORITY_HIGH
                TunePriority.BALANCED -> BluetoothGatt.CONNECTION_PRIORITY_BALANCED
                TunePriority.LOW_POWER -> BluetoothGatt.CONNECTION_PRIORITY_LOW_POWER
            }
        paramReports.tryReceive()
        val asked = runCatching { g.requestConnectionPriority(code) }.getOrDefault(false)
        Log.i(TAG, "$label $nodeId ask priority=${priority.name} requested=$asked")
        // stretches: the bridge's give-up on one parameter update (a boost's ask never waits); a link already on the asked
        // values reports nothing, and a sleep only delays the bridge's reply.
        if (asked && waits) withTimeoutOrNull(REPORT_TIMEOUT_MS) { paramReports.receive() }
    }

    private suspend fun askMtu(
        g: BluetoothGatt,
        size: Int,
        waits: Boolean,
    ) {
        mtuReports.tryReceive()
        mtuAsked = size
        val asked = runCatching { g.requestMtu(size) }.getOrDefault(false)
        Log.i(TAG, "$label $nodeId ask mtu=$size requested=$asked")
        // stretches: the bridge's give-up on one ATT MTU exchange (a boost's ask never waits); a sleep only delays its reply.
        if (asked && waits) withTimeoutOrNull(MTU_TIMEOUT_MS) { mtuReports.receive() }
    }

    private suspend fun read(g: BluetoothGatt) {
        phyReports.tryReceive()
        if (runCatching { g.readPhy() }.isSuccess) {
            // stretches: give-up on one PHY read.
            withTimeoutOrNull(READ_TIMEOUT_MS) { phyReports.receive() }
        }
        rssiReads.tryReceive()
        if (runCatching { g.readRemoteRssi() }.getOrNull() == true) {
            // stretches: give-up on one RSSI read.
            withTimeoutOrNull(READ_TIMEOUT_MS) { rssiReads.receive() }?.let { rssi = it }
        }
    }

    private fun onPhy(
        tx: Int,
        rx: Int,
        status: Int,
        what: String,
    ) {
        if (status == BluetoothGatt.GATT_SUCCESS) {
            txPhy = linkPhyOf(tx)
            rxPhy = linkPhyOf(rx)
        }
        Log.i(TAG, "$label $nodeId phy tx=${linkPhyOf(tx)} rx=${linkPhyOf(rx)} status=$status ($what)")
        phyReports.trySend(status)
    }

    private val callback =
        object : LinkParamsCallback() {
            override fun onConnectionStateChange(
                g: BluetoothGatt,
                status: Int,
                newState: Int,
            ) {
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        if (released) {
                            // Registered after the loop let go (the Android 12 race in closeAgainLater): let go again.
                            Log.i(TAG, "$label $nodeId: late attach let go")
                            runCatching { g.disconnect() }
                            runCatching { g.close() }
                        }
                        attachedSignal.complete(true)
                    }

                    BluetoothProfile.STATE_DISCONNECTED -> {
                        disconnected = true
                        attachedSignal.complete(false)
                    }
                }
            }

            override fun onPhyRead(
                g: BluetoothGatt,
                txPhy: Int,
                rxPhy: Int,
                status: Int,
            ) = onPhy(txPhy, rxPhy, status, "read")

            override fun onPhyUpdate(
                g: BluetoothGatt,
                txPhy: Int,
                rxPhy: Int,
                status: Int,
            ) = onPhy(txPhy, rxPhy, status, "update")

            override fun onLinkParams(
                params: LinkParams,
                ok: Boolean,
                status: Int,
            ) {
                // A colliding update (0x2A) reports values that are not the link's (dqvb's put-back amendment).
                if (ok) this@BleLinkTuner.params = params
                paramsStatus = status
                Log.i(
                    TAG,
                    "$label $nodeId params interval=${params.intervalUs}us latency=${params.latency} " +
                        "timeout=${params.timeoutMs}ms status=$status",
                )
                paramReports.trySend(ok)
                onParams(params, ok)
            }

            override fun onMtuChanged(
                g: BluetoothGatt,
                mtu: Int,
                status: Int,
            ) {
                // The size is what the exchange settled on whatever the status says (as MeshtasticGatt reads it).
                this@BleLinkTuner.mtu = mtu
                Log.i(TAG, "$label $nodeId mtu=$mtu asked=$mtuAsked status=$status")
                mtuReports.trySend(mtu)
            }

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

        // A PHY or parameter update is one LL procedure, a few connection events; one not back by now is not coming.
        const val REPORT_TIMEOUT_MS = 3_000L

        // MeshtasticGatt's: an ATT MTU exchange over a busy link can wait behind its traffic.
        const val MTU_TIMEOUT_MS = 10_000L

        // A PHY or RSSI read is one HCI command.
        const val READ_TIMEOUT_MS = 1_000L

        // Every step of one ask, with room to spare; the bridge's reply waits no longer.
        const val TUNE_TIMEOUT_MS = 20_000L

        // How long after letting go of a client that never attached it is closed again: a registration answers in
        // milliseconds, and a direct connect it made to a gone ACL is cancelled by the close.
        const val SECOND_CLOSE_MS = 5_000L
    }
}
