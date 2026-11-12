package app.getknit.knit.mesh.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.annotation.Keep
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Rings the GATT doorbell of the peer on one Bluetooth link (ADR 2026-09.dqvb): a write without response to
 * [DoorbellPolicy.DOORBELL_UUID], over the ACL the link's L2CAP channel already runs on. The transport makes one per
 * link whose HELLO carries [app.getknit.knit.mesh.protocol.Protocol.CAP_DOORBELL] — an iPhone that dialed us — and
 * [poke]s it after each frame that rings ([DoorbellPolicy.rings]); [DoorbellPolicy.Schedule] decides when.
 *
 * One coroutine owns the GATT client and the schedule, woken through a conflated channel, so a poke never blocks the
 * writer and a ring asked for during the lookup waits for it. The trailing ring of a burst is that loop's timeout.
 *
 * **The lookup** runs at link-up, before any ring (#102). `connectGatt` on a device with an open LE link attaches a
 * client to that link rather than dialing (AOSP `gatt_connect`), which is why the attach window is [ATTACH_TIMEOUT_MS]
 * and the link is re-checked ([isLive]) just before: if the ACL is already gone, the call would dial the peer's address
 * instead, and closing the client on the timeout cancels that dial. The client holds the ACL while it is open, so it
 * never outlives the link — [close] ends it with the link. A peer with no doorbell (or one that takes no write without
 * a response, which is the only kind a suspended app can take without holding the link up) is not asked again on this
 * link, and its client is closed at once; the open CoC keeps the ACL up without it.
 *
 * **The doorbell is searched for in every `0xFE30` service** ([DoorbellPolicy.characteristicIn]): a blinding iPhone
 * serves it in a service of its own (D, added first at each bring-up and never rotated) beside the payload's (P,
 * replaced at each rotation), so `getService(UUID)` could answer P (knit-ios ADR 2026-11.prsp, ADR 2026-11.89gg). And a
 * lookup that finds none within [DoorbellPolicy.DOORBELL_SETTLE_MS] of the link's open ([openedAt], `elapsedRealtime`)
 * is a retry, never an absence ([DoorbellPolicy.missing]): the peer's server may still be coming up. A retry drops the
 * client without counting a failure (no [DoorbellPolicy.LOOKUP_RETRY_MS] wait, nothing toward
 * [DoorbellPolicy.MAX_FAILED_LOOKUPS]), so the next lookup registers a fresh client and discovers again: AOSP keeps one
 * GATT database per server per connection and clears it only when the last client on that address goes, and no Service
 * Changed may reach this phone, so a kept client would rediscover the same partial database. (Another app's client on
 * that address still pins it; nothing here can see one.) The next lookup is a ring's, or one at the bound itself
 * ([settleLookupAt]), so a link whose first lookup found nothing still gets its BALANCED ask (#102) without waiting for
 * a frame. Past the bound `absent` stands for the link's life. Nothing here waits on `onServiceChanged`.
 *
 * **Each lookup that finds the doorbell also asks for [BluetoothGatt.CONNECTION_PRIORITY_BALANCED]** (#102). An iPhone
 * that dialed us is the link's central and runs it at a 720 ms supervision timeout, which drops the link at the first
 * 0.72 s without a good packet. The stack's own update for the discovery asks for 5 s and gets it, then asks for the
 * link's first values back once discovery ends. BALANCED is AOSP's 30–50 ms at no latency with the 5 s timeout every
 * priority carries, inside Apple's accessory limits, and iOS grants it. The put-back can still land after the ask, so
 * the ask is repeated when the link reports a timeout under 5 s after it, and once more at the settle time unless the
 * 5 s was reported ([DoorbellPolicy.Balanced]). It is asked after every lookup, so a
 * re-lookup's discovery (after a wedge or a services change) is followed by the request again. That is why the lookup
 * runs at link-up: the link spends no time at 720 ms waiting for its first ring, and a replaced link, which gets no
 * link-up push, still gets it.
 *
 * Callbacks arrive on a binder thread (the four-arg `connectGatt` passes no Handler); each lookup gets its own
 * [Client], whose deferreds are made before the call, so nothing is handed between threads through a plain field.
 *
 * Device-verified only — there is no host GATT stack — like [app.getknit.knit.mesh.bluetooth.meshtastic.MeshtasticGatt];
 * the rules it follows are [DoorbellPolicy]'s, which are JVM-tested.
 */
@SuppressLint("MissingPermission")
internal class BleDoorbell(
    private val context: Context,
    private val device: BluetoothDevice,
    private val nodeId: String,
    private val scope: CoroutineScope,
    private val isLive: () -> Boolean,
    private val onRang: () -> Unit,
    private val now: () -> Long,
    // When the link's L2CAP channel opened, on [now]'s clock: the doorbell settle bound counts from here (ADR 2026-11.89gg).
    private val openedAt: Long,
    // A lookup's client attached to the link's ACL (binder thread): that attach arms the ACL to idle out when the link's
    // channel closes, as the transport's link-up arm does for every other link (#142, ADR 2026-10.b8yf).
    private val onAttached: () -> Unit = {},
) {
    private val pokes = Channel<Unit>(Channel.CONFLATED)
    private var job: Job? = null

    // The loop's own state: only the loop coroutine reads or writes these.
    private val schedule = DoorbellPolicy.Schedule()
    private val lookups = DoorbellPolicy.Lookups()
    private var client: Client? = null
    private var doorbell: BluetoothGattCharacteristic? = null
    private var absent = false

    // When the client's BALANCED ask is checked again (DoorbellPolicy.Balanced.onSettle), null when none is owed.
    private var settleAt: Long? = null

    // When the one lookup a settling retry owes runs (the link's open plus DoorbellPolicy.DOORBELL_SETTLE_MS), null when
    // none is owed: a ring before it looks first, and this one then finds the doorbell held or the absence standing.
    private var settleLookupAt: Long? = null

    fun start() {
        job = scope.launch(Dispatchers.IO) { loop() }
    }

    /** A frame that rings went out on the link. Never blocks. */
    fun poke() {
        pokes.trySend(Unit)
    }

    /** The link ended: stop, and let the GATT client go with it. */
    fun close() {
        job?.cancel()
    }

    private suspend fun loop() {
        try {
            lookUp() // at link-up, for the connection priority it asks for; it rings nothing
            while (currentCoroutineContext().isActive) {
                val due = listOfNotNull(schedule.dueAt, settleAt, settleLookupAt).minOrNull()
                val poked =
                    if (due == null) {
                        pokes.receive()
                        true
                    } else {
                        // stretches: the trailing ring (≤ 5 s; a ring buys ~9 s, dqvb), the 2 s BALANCED settle and the
                        // doorbell settle lookup (≤ 10 s from the link's open, 89gg): a settle lookup run late reads the
                        // elapsed clock, so it finds the doorbell or the absence it would have, and any ring looks first.
                        withTimeoutOrNull((due - now()).coerceAtLeast(0L)) { pokes.receive() } != null
                    }
                val t = now()
                settleAt?.let { if (t >= it) settle() }
                settleLookupAt?.let {
                    if (t >= it) {
                        settleLookupAt = null
                        lookUp()
                    }
                }
                if (if (poked) schedule.afterFrame(t) else schedule.onDue(t)) ring()
            }
        } finally {
            dropClient()
        }
    }

    private suspend fun ring() {
        val ch = lookUp() ?: return
        val c = client ?: return
        if (write(c, ch)) {
            onRang()
            Log.d(TAG, "bt ring $nodeId")
        } else {
            // Rings are seconds apart and nothing else runs on this client, so a refused write is a client whose
            // last callback never came (it stays busy for good) or that lost the link: drop it and look again.
            Log.i(TAG, "bt doorbell wedged $nodeId")
            dropClient()
            lookups.failed(now())
        }
    }

    /** The doorbell to write, looking it up first if this link has not; null when there is none to ring now. */
    private suspend fun lookUp(): BluetoothGattCharacteristic? {
        val held = client
        if (held != null && held.stale) {
            // The client dropped, or the peer's services changed: what it found no longer holds.
            dropClient()
            if (held.disconnected) lookups.failed(now())
        }
        doorbell?.let { return it }
        if (absent || !lookups.mayTry(now()) || !isLive()) return null
        val c = Client()
        client = c
        return when (val phase = c.open()) {
            null -> {
                val ch = c.doorbell()
                if (ch == null) {
                    noDoorbell()
                } else {
                    doorbell = ch
                    settleLookupAt = null
                    Log.i(TAG, "bt doorbell found $nodeId (${device.address})")
                    askForBalanced(c)
                }
                ch
            }

            else -> {
                dropClient()
                lookups.failed(now())
                Log.i(TAG, "bt doorbell lookup failed $nodeId ($phase)")
                null
            }
        }
    }

    /**
     * A lookup's discovery answered with no doorbell: inside the settle bound a retry (the client dropped, no failure
     * counted, one lookup owed at the bound), past it an absence for the link's life ([DoorbellPolicy.missing]).
     */
    private fun noDoorbell() {
        dropClient()
        val t = now()
        when (DoorbellPolicy.missing(failed = false, settlingSince = openedAt, now = t)) {
            DoorbellPolicy.Missing.RETRY -> {
                settleLookupAt = openedAt + DoorbellPolicy.DOORBELL_SETTLE_MS
                Log.i(TAG, "bt doorbell settling $nodeId (${t - openedAt}ms since link up)")
            }

            DoorbellPolicy.Missing.ABSENT -> {
                absent = true
                settleLookupAt = null
                Log.i(TAG, "bt doorbell absent $nodeId")
            }
        }
    }

    /**
     * Asks for BALANCED parameters, whose 5 s supervision timeout replaces an iPhone central's 720 ms (#102), and
     * arms the check that the stack's put-back of the central's own values did not land after it.
     */
    private fun askForBalanced(c: Client) {
        c.balanced.asked()
        settleAt = now() + DoorbellPolicy.BALANCED_SETTLE_MS
        Log.i(TAG, "bt doorbell priority $nodeId requested=${requestBalanced(c)}")
    }

    /** The settle time of the client's ask came: ask again unless the link has reported the 5 s since. */
    private fun settle() {
        settleAt = null
        val c = client ?: return
        if (c.balanced.onSettle()) askAgain(c, "settle")
    }

    /** Repeats the client's BALANCED ask ([DoorbellPolicy.Balanced]); any thread. */
    private fun askAgain(
        c: Client,
        why: String,
    ) {
        Log.i(TAG, "bt doorbell priority $nodeId again ($why) requested=${requestBalanced(c)}")
    }

    private fun requestBalanced(c: Client): Boolean =
        runCatching { c.gatt?.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_BALANCED) == true }
            .getOrDefault(false)

    private fun write(
        c: Client,
        ch: BluetoothGattCharacteristic,
    ): Boolean {
        val g = c.gatt ?: return false
        // A revoked grant or an unbound service throws; either is a refused ring, never a crash on the scope.
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeCharacteristic(ch, VALUE, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                run {
                    ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                    ch.value = VALUE
                    g.writeCharacteristic(ch)
                }
            }
        }.getOrDefault(false)
    }

    private fun dropClient() {
        client?.close()
        client = null
        doorbell = null
        settleAt = null
    }

    /** One GATT client, from its attach to its close; the callbacks complete what the loop awaits. */
    private inner class Client : BluetoothGattCallback() {
        @Volatile
        var gatt: BluetoothGatt? = null

        private val attached = CompletableDeferred<Boolean>()
        private val discovered = CompletableDeferred<Boolean>()

        /** The link under the client went down after it attached. */
        @Volatile
        var disconnected = false
            private set

        @Volatile
        private var servicesChanged = false

        /** This lookup's BALANCED ask, and whether it needs repeating. */
        val balanced = DoorbellPolicy.Balanced()

        /** What this client found can no longer be trusted. */
        val stale: Boolean get() = disconnected || servicesChanged

        /** Attaches and discovers; null on success, else the phase that failed. */
        @Suppress("DEPRECATION")
        suspend fun open(): String? {
            // `DEPRECATION`: compileSdk 37.1 deprecates every `connectGatt(Context, …)` overload in favour of the
            // API-37 `connectGatt(BluetoothGattConnectionSettings, Executor, callback)`, eight releases above minSdk 29.
            gatt = runCatching { device.connectGatt(context, false, this, BluetoothDevice.TRANSPORT_LE) }.getOrNull()
                ?: return "connectGatt"
            // stretches: give-up on a GATT attach over the link's own ACL; a sleep only delays closing the client.
            if (withTimeoutOrNull(ATTACH_TIMEOUT_MS) { attached.await() } != true) return "attach"
            if (runCatching { gatt?.discoverServices() }.getOrNull() != true) return "discover"
            // stretches: give-up on service discovery, as above.
            if (withTimeoutOrNull(DISCOVER_TIMEOUT_MS) { discovered.await() } != true) return "discover"
            return null
        }

        /**
         * The doorbell among the discovered services, if the peer serves one that takes a write without response, in
         * whichever `0xFE30` service holds it (a blinding iPhone's D, a clear one's combined service).
         */
        fun doorbell(): BluetoothGattCharacteristic? =
            DoorbellPolicy.characteristicIn(
                runCatching { gatt?.services }.getOrNull().orEmpty(),
                { it.uuid },
                { it.getCharacteristic(DoorbellPolicy.DOORBELL_UUID) },
            ) { it.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0 }

        fun close() {
            val g = gatt ?: return
            gatt = null
            // disconnect() drops this client's hold on the link (or cancels a dial the attach turned into); close()
            // unregisters it. The adapter may be off and the service unbound, so neither may throw out of here.
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
                    attached.complete(true)
                    onAttached()
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    disconnected = true
                    attached.complete(false)
                    discovered.complete(false)
                }
            }
        }

        override fun onServicesDiscovered(
            g: BluetoothGatt,
            status: Int,
        ) {
            discovered.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        // API 31+; never called below it.
        override fun onServiceChanged(g: BluetoothGatt) {
            servicesChanged = true
        }

        /**
         * The link's parameters changed: interval in 1.25 ms units, timeout in 10 ms units. Hidden since API 26, so
         * not an `override`: the framework calls it virtually, and [Keep] stops R8 dropping a method nothing here
         * calls. It logs the parameters the link settles on, and a timeout under 5 s after this lookup's BALANCED ask
         * is the stack's put-back of the central's own, answered by asking again ([DoorbellPolicy.Balanced]). The
         * settle timer covers a framework that stops calling it.
         */
        @Keep
        @Suppress("unused", "UNUSED_PARAMETER", "UnusedParameter")
        fun onConnectionUpdated(
            g: BluetoothGatt,
            interval: Int,
            latency: Int,
            timeout: Int,
            status: Int,
        ) {
            Log.i(
                TAG,
                "bt conn params $nodeId interval=${interval * INTERVAL_UNIT_US}us latency=$latency " +
                    "timeout=${timeout * TIMEOUT_UNIT_MS}ms status=$status",
            )
            val timeoutMs = timeout * TIMEOUT_UNIT_MS
            if (balanced.onParams(timeoutMs, succeeded = status == BluetoothGatt.GATT_SUCCESS)) askAgain(this, "put-back ${timeoutMs}ms")
        }
    }

    private companion object {
        const val TAG = BluetoothMeshTransport.TAG

        // An attach to a live link reports connected within milliseconds; past this the ACL is gone and the call is
        // dialing instead, which the timeout's close cancels.
        const val ATTACH_TIMEOUT_MS = 2_000L
        const val DISCOVER_TIMEOUT_MS = 10_000L

        // The HCI units `onConnectionUpdated` reports in.
        const val INTERVAL_UNIT_US = 1_250
        const val TIMEOUT_UNIT_MS = 10

        // The doorbell's value means nothing; one byte is the least a write carries.
        val VALUE = byteArrayOf(1)
    }
}
