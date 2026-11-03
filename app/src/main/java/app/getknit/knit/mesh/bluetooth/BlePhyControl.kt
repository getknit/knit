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
import kotlin.time.Duration.Companion.milliseconds

/** One link's PHY as the bridge reports it (ADR 2026-10.yvn6). */
data class PhyLinkStatus(
    val nodeId: String,
    val phy: LinkPhy,
    val linkRssi: Int?,
    val drives: Boolean,
    val attached: Boolean,
    val switches: Int,
    val gaveUp: Boolean,
    /** The link is near its floor ([LinkFade], ADR 2026-10.79yb's ninth amendment): a walkie chunk also takes a relay. */
    val fading: Boolean = false,
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
 * Every read's outcome also goes to [LinkFade], answered or not (a refused call, a failed callback, the 1 s timeout),
 * so a link whose reads stop answering is called fading rather than read silently at its last value; [status] carries
 * the verdict to `BluetoothMeshTransport.liveLinkFading`.
 *
 * One raw reading goes to [LinkSilence] beside it (#203), never the fade verdict: when the GATT client was told the
 * connection ended while it held it ([lostAt]), reported through [onLost] as it happens. Unanswered reads are not
 * evidence there: after a process freeze they timed out 27 in a row on healthy links (ADR 2026-10.697m).
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
    private val tuning: () -> PhyTuning,
    private val onStep: (toCoded: Boolean) -> Unit,
    private val onGiveUp: () -> Unit,
    private val now: () -> Long,
    // After every PHY report — the first read and each change — so the transport can republish what links are on.
    private val onPhyKnown: () -> Unit = {},
    // On the client's loss ([lostAt]), off every lock: the silent-link check probes and looks again (#203).
    onLost: () -> Unit = {},
) {
    private val stepper = PhyStepper(tuning)
    private val fade = LinkFade(tuning)
    private val pokes = Channel<Unit>(Channel.CONFLATED)

    // A read's answer: its RSSI, or null for a callback that answered with a failure status.
    private val rssiReads = Channel<Int?>(Channel.CONFLATED)
    private val attachedSignal = CompletableDeferred<Boolean>()
    private var job: Job? = null

    @Volatile private var gatt: BluetoothGatt? = null

    @Volatile private var attached = false

    @Volatile private var disconnected = false

    // The client reported itself connected once, and [loop]'s finally has begun letting it go: a disconnect is the
    // stack's word only between the two.
    @Volatile private var connected = false

    @Volatile private var closing = false

    /**
     * When the client, attached to the link's own ACL, was told the connection ended while it held it (#203); null while
     * it never was. Kept past the client's end, as the control is kept past it.
     */
    @Volatile
    var lostAt: Long? = null
        private set

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
            fading = fading(),
        )

    /**
     * Whether the link is fading now ([LinkFade]): `BluetoothMeshTransport.liveLinkFading` asks it per walkie chunk, so
     * it reads two volatile-or-monitored fields and nothing else. Only while the reader is attached: a control whose
     * GATT client went away stays in the transport's map (its last PHY and RSSI decide the range hunt when the link
     * drops, yvn6's 2026-10-02 and 2026-10-04 amendments) but has no reads to judge by, so it reports no fade rather
     * than a last verdict nothing can clear.
     */
    fun fading(): Boolean = attached && fade.fading

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
            // stretches: give-up on a GATT attach over the link's own ACL; a sleep only delays closing the client.
            if (withTimeoutOrNull(ATTACH_TIMEOUT_MS) { attachedSignal.await() } != true) {
                Log.i(TAG, "bt phy $nodeId: attach failed")
                return
            }
            attached = true
            runCatching { g.readPhy() }
            while (currentCoroutineContext().isActive && !disconnected) {
                if (drives) act(g, stepper.decide(mode(), now()))
                // stretches: the RSSI cadence (yvn6, dark): step-down counts reads, but a step-up hold spans a sleep on two reads (open).
                withTimeoutOrNull(stepper.nextReadMs()) { pokes.receive() }
                if (!disconnected) readRssi(g)
            }
        } finally {
            closing = true
            attached = false
            gatt?.let {
                // disconnect() drops this client's hold on the link (the channel keeps the ACL); close() unregisters it.
                runCatching { it.disconnect() }
                runCatching { it.close() }
            }
            gatt = null
        }
    }

    /**
     * One link-RSSI read, its outcome fed on: an answer to [onRssi]; a call the stack refused, a callback that answered
     * with a failure, or nothing back within [RSSI_TIMEOUT_MS] to [onUnanswered]. A read that a disconnect overtook is
     * neither: the loop ends, and with it the fade verdict ([fading]).
     */
    private suspend fun readRssi(g: BluetoothGatt) {
        rssiReads.tryReceive() // drop an answer that came back after its own read timed out
        val sent = runCatching { g.readRemoteRssi() }.getOrNull() == true
        // stretches: give-up on one RSSI read; LinkFade counts unanswered reads, so a sleep inside it is not a fade.
        val answer = if (sent) withTimeoutOrNull(RSSI_TIMEOUT_MS) { rssiReads.receive() ?: REFUSED } else null
        if (disconnected) return
        when (answer) {
            null -> onUnanswered(if (sent) "timeout" else "not sent")
            REFUSED -> onUnanswered("refused")
            else -> onRssi(answer)
        }
    }

    /**
     * Feeds a read to the stepper, logging the step-up hold a Coded link moves into (the link RSSI of a walk back in),
     * then to [fade]. At or under the edge each read is logged raw and smoothed: the 2026-10-13 walkie drop left a
     * smoothed −82 on one side at its end, and without the raw reads nobody could say whether they had stopped
     * changing.
     */
    private fun onRssi(rssi: Int) {
        val at = now()
        val hold = stepper.onRssi(rssi, at)
        val smoothed = stepper.smoothedRssi ?: rssi.toDouble()
        if (hold != null && stepper.phy == LinkPhy.CODED) {
            Log.i(TAG, "bt phy $nodeId step-up hold ${hold.name.lowercase()} (rssi=${smoothed.toInt()})")
        }
        if (smoothed <= tuning().edgeDbm) Log.d(TAG, "bt phy $nodeId rssi=$rssi smoothed=${smoothed.toInt()}")
        logFade(fade.onRead(smoothed, at))
    }

    /** A read that did not answer — [why] in the log — counted toward [fade]'s staleness instead of dropped. */
    private fun onUnanswered(why: String) {
        val edge = fade.onUnanswered()
        Log.d(TAG, "bt phy $nodeId rssi read unanswered (${fade.unansweredInRow} in a row, $why)")
        logFade(edge)
    }

    /** One line per fade edge, at info (the step lines' level), so a walk's logcat has it without debug lines on. */
    private fun logFade(edge: LinkFade.Edge?) {
        val rssi = stepper.smoothedRssi?.toInt()
        when (edge) {
            LinkFade.Edge.ON -> {
                val since = fade.sinceAnsweredMs(now())?.let { "${it}ms ago" } ?: "none"
                Log.i(TAG, "bt fade $nodeId on (rssi=$rssi, unanswered=${fade.unansweredInRow}, last answer $since)")
            }

            LinkFade.Edge.OFF -> {
                Log.i(TAG, "bt fade $nodeId off (rssi=$rssi)")
            }

            null -> {}
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
        val reversalsBefore = stepper.reversals
        val backoffBefore = stepper.stepUpBackoffMs
        val changed = stepper.onPhy(linkPhyOf(txPhy), status == BluetoothGatt.GATT_SUCCESS, now())
        if (changed) {
            Log.i(TAG, "bt phy $nodeId $before→${stepper.phy} rssi=${stepper.smoothedRssi?.toInt()} (${mode().wire})")
            logBackoff(reversalsBefore, backoffBefore)
            onStep(stepper.phy == LinkPhy.CODED)
        } else if (!update) {
            Log.i(TAG, "bt phy $nodeId on ${stepper.phy} (drives=$drives)")
        }
        if (stepper.gaveUp && !wasGivenUp) {
            Log.i(TAG, "bt phy $nodeId gave up: update answered ${linkPhyOf(txPhy)} status=$status")
            onGiveUp()
        }
        onPhyKnown()
    }

    /**
     * Logs a step-down that moved the step-up backoff (#169): a reversal (a step-down soon after a step-up) with the
     * backoff it set, or a reset after a link that held fast a while. A trial counts reversals from these lines, on the
     * driver's logcat (`drives=true`): the watching side logs the same steps from the updates it is sent.
     */
    private fun logBackoff(
        reversalsBefore: Int,
        backoffBefore: Long,
    ) {
        val backoff = stepper.stepUpBackoffMs
        // Whole seconds, so a trial script can parse the number (Duration's own text reads `1m 20s`).
        val seconds = backoff.milliseconds.inWholeSeconds
        when {
            stepper.reversals != reversalsBefore -> {
                Log.i(TAG, "bt phy $nodeId reversal ${stepper.reversals}: step-up backoff ${seconds}s (drives=$drives)")
            }

            backoff < backoffBefore -> {
                Log.i(TAG, "bt phy $nodeId step-up backoff reset to ${seconds}s (drives=$drives)")
            }
        }
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
                        connected = true
                        attachedSignal.complete(true)
                    }

                    BluetoothProfile.STATE_DISCONNECTED -> {
                        disconnected = true
                        attachedSignal.complete(false)
                        pokes.trySend(Unit)
                        // Not our own disconnect() in the loop's finally, and not an attach that never came: the stack
                        // says the ACL under a live link is gone (#203).
                        if (connected && !closing && lostAt == null) {
                            lostAt = now()
                            Log.i(TAG, "bt phy $nodeId client lost (status=$status)")
                            onLost()
                        }
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
                rssiReads.trySend(rssi.takeIf { status == BluetoothGatt.GATT_SUCCESS })
            }
        }

    private companion object {
        const val TAG = BluetoothMeshTransport.TAG

        // As BleDoorbell's: an attach to a live link answers in milliseconds; past this the call is dialing instead.
        const val ATTACH_TIMEOUT_MS = 2_000L

        // A link-RSSI read is one HCI command; one not back by now is skipped, not waited on.
        const val RSSI_TIMEOUT_MS = LinkFade.READ_TIMEOUT_MS

        // A read the stack's callback answered with a failure status; no RSSI reads this.
        const val REFUSED = Int.MIN_VALUE
    }
}

/** The [LinkPhy] an Android PHY constant names (`BluetoothDevice.PHY_LE_*`): [BlePhyControl]'s and [BleLinkTuner]'s. */
internal fun linkPhyOf(phy: Int): LinkPhy =
    when (phy) {
        BluetoothDevice.PHY_LE_1M -> LinkPhy.ONE_M
        BluetoothDevice.PHY_LE_2M -> LinkPhy.TWO_M
        BluetoothDevice.PHY_LE_CODED -> LinkPhy.CODED
        else -> LinkPhy.UNKNOWN
    }
