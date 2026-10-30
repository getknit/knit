package app.getknit.knit.mesh.lora

import app.getknit.knit.mesh.crypto.b64
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A tiny in-memory LoRa "air": every registered [FakeMeshtasticLink] floods each broadcast to every OTHER
 * registered link (a board never echoes the phone's own packet back), so two [LoraMeshTransport]s can be
 * exercised end-to-end on the JVM with no radio and no GATT. Single-threaded by test contract.
 *
 * A `want_ack` unicast (ADR 2026-10.x9fa) is modelled the way the firmware runs it, because the plane's every
 * failure there is one the fake used not to have: the packet reaches **only** its destination board (a third board's
 * phone never sees it); the destination's board acks it whatever its phone is doing, [ackDelayMs] later; the sender's
 * board makes [UNICAST_ATTEMPTS] attempts at an unanswered one, a firmware gap apart ([gapMs]), each an airing, and
 * reports `MAX_RETRANSMIT` one gap after the last — about a minute after the send, so it races what the plane does
 * meanwhile. These are the firmware's own numbers, kept apart from the plane's constants so a disagreement between
 * them shows up in a test. A relay's implicit ack ([implicitAcks]) stops the retries and is the last the sender's board
 * says unless the destination's own ack follows (`ReliableRouter::perhapsGenerateImplicitAckForOwnOverheard`); a lost
 * ack with the DM landed ([ackLost]), no outcome at all ([silent]) and a link that carries one way only ([lossy] is
 * directional) are switches too. [airings] records every transmission, Knit packets apart from ACKs. The exchange runs
 * on the sending link's `scope`, which a `want_ack` send requires, and starts only once that `send` has returned: on a
 * real board an outcome crosses BLE after the handle it names, and an outcome emitted inside `send` reached a transport
 * that had no flight for it yet (the mesh-lab review of 2026-10-29).
 *
 * **PKI** ([pki], on by default: every board here is 2.8.1). Firmware 2.8 PKI-encrypts every `want_ack` unicast its phone
 * originates, whatever the channel, and never falls back to the channel key (`Router.cpp:1189-1212`, `:1347-1355`):
 * - a payload past [MeshtasticProto.MAX_PAYLOAD] less the 12-byte PKC overhead is `TOO_LARGE` from our own board, and
 *   nothing airs;
 * - a sender whose NodeDB holds no key for the destination ([FakeMeshtasticLink.holdsKeyFor]) refuses it with
 *   `queueStatus.res = 39` and then a self-NAK on index 0 ([FakeMeshtasticLink.noKeyNakFirst] swaps the two), and
 *   nothing airs;
 * - a destination that holds no key for the sender cannot decrypt it: its board NAKs `PKI_UNKNOWN_PUBKEY` (35) — which
 *   stops the retries — and the sender's board answers with its `NodeInfo`, one airing, which teaches the destination
 *   the sender's key;
 * - otherwise it is delivered on the destination's index 0 with `pki_encrypted`, and acked on index 0.
 *
 * A board knows every node's key unless told to [FakeMeshtasticLink.forget] it — the NodeInfo on the shared primary
 * teaches it on a real mesh, and it keeps the key while that board is off — and learns one from an `add_contact` its
 * phone writes, unless it is
 * [FakeMeshtasticLink.isManaged], which drops it silently (`AdminModule.cpp:178-181`).
 */
internal class FakeMeshtasticAir {
    // Copy-on-write: the mesh-in-a-box lab registers boards from one node's coroutine while another node is
    // already broadcasting on Dispatchers.Default; the single-SUT rigs are single-threaded either way.
    private val links = CopyOnWriteArrayList<FakeMeshtasticLink>()

    /** Whether a packet from [from] fails to reach [to] — one direction only, so a link can be asymmetric. */
    var lossy: (from: UInt, to: UInt) -> Boolean = { _, _ -> false }

    /** How long after a unicast lands its destination's board answers. */
    var ackDelayMs = 2_000L

    /** A fixed gap between two airings of an unanswered unicast, or null for the firmware's own ([gapMs]). */
    var retransmitMs: Long? = null

    /**
     * The firmware's gap for a [payloadBytes] unicast on a quiet band (`RadioInterface::getRetransmissionMsec` at
     * `CWmin = 3`, LongFast): twice the airtime, `2^3 + 2·8 + 2^5` slots of ≈ 44.5 ms, and 4.5 s of processing.
     */
    fun gapMs(payloadBytes: Int): Long {
        retransmitMs?.let { return it }
        val air = LoraAirtime().timeOnAirMs(payloadBytes, LoraAirtime.UNSIGNED)
        val slot = (4 + 0.5) * (2048.0 / 250.0) + 7.6
        return 2 * air + ((8 + 16 + 32) * slot).toLong() + 4_500L
    }

    /** When the sender's board reports `MAX_RETRANSMIT` for a [payloadBytes] unicast nobody answers. */
    fun giveUpMs(payloadBytes: Int): Long = UNICAST_ATTEMPTS * gapMs(payloadBytes)

    /** Whether the ACK from [to] back to [from] is lost although the unicast landed: the sender retries and gives up. */
    var ackLost: (from: UInt, to: UInt) -> Boolean = { _, _ -> false }

    /** Whether the sender's board reports nothing at all for a unicast to [to] — no ACK, no give-up. */
    var silent: (from: UInt, to: UInt) -> Boolean = { _, _ -> false }

    /** A relay repeats each airing, so the sender's board reports an implicit ack before anything else. */
    var implicitAcks = false

    /** Whether every `want_ack` unicast is PKI-encrypted, as firmware 2.8 does: see the class doc. */
    var pki = true

    /**
     * One transmission on the air: a Knit packet ([ack] false; [attempt] 0 is the send, above it a retry), an ACK or a
     * NAK ([ack] true), or the `NodeInfo` a board sends a destination that lacked its key ([nodeInfo]).
     */
    data class Airing(
        val from: UInt,
        val to: UInt,
        val ack: Boolean,
        val attempt: Int = 0,
        val nodeInfo: Boolean = false,
    )

    val airings = CopyOnWriteArrayList<Airing>()

    /** The Knit packets aired, retries included — what the plane spent. ACKs and NodeInfos are counted apart. */
    fun knitAirings(from: UInt): Int = airings.count { it.from == from && !it.ack && !it.nodeInfo }

    fun ackAirings(from: UInt): Int = airings.count { it.from == from && it.ack }

    fun nodeInfoAirings(from: UInt): Int = airings.count { it.from == from && it.nodeInfo }

    fun register(link: FakeMeshtasticLink) {
        links.addIfAbsent(link)
    }

    fun unregister(link: FakeMeshtasticLink) {
        links -= link
    }

    fun broadcast(
        from: UInt,
        channelIndex: Int,
        portnum: Int,
        payload: ByteArray,
    ) {
        airings += Airing(from, MeshtasticProto.BROADCAST, ack = false)
        val sender = links.firstOrNull { it.nodeNum == from }
        links
            .filter { it.nodeNum != from && !lossy(from, it.nodeNum) }
            .forEach { it.deliver(from, indexOn(sender, it, channelIndex), portnum, payload) }
    }

    /**
     * The channel index [receiver] reports for a packet [sender] wrote at [index]: the Knit channel is matched by its
     * hash on the air, so each board reports its own slot for it.
     */
    private fun indexOn(
        sender: FakeMeshtasticLink?,
        receiver: FakeMeshtasticLink,
        index: Int,
    ): Int {
        val from = sender?.knitIndex ?: return index
        val to = receiver.knitIndex ?: return index
        return if (index == from) to else index
    }

    /** A `want_ack` unicast from [sender] to the board [to], handle [id]: see the class doc. */
    @Suppress("CyclomaticComplexMethod") // one pass over the firmware's attempts; each branch is one of its rules, in order
    fun unicast(
        sender: FakeMeshtasticLink,
        to: UInt,
        channelIndex: Int,
        portnum: Int,
        payload: ByteArray,
        id: UInt,
        sent: Deferred<Unit>,
    ) {
        val scope = checkNotNull(sender.scope) { "a want_ack unicast needs its link's scope: its outcome follows the send" }
        val events = mutableListOf<Pair<Long, () -> Unit>>()
        val gap = gapMs(payload.size)
        var at = 0L
        var landed = false
        for (attempt in 0 until UNICAST_ATTEMPTS) {
            if (attempt > 0) at += gap
            val airing = Airing(sender.nodeNum, to, ack = false, attempt = attempt)
            val dest = links.firstOrNull { it.nodeNum == to }
            val reaches = dest != null && !lossy(sender.nodeNum, to)
            // The destination's board cannot decrypt a PKI packet from a node whose key it lacks (`ReliableRouter.cpp:146-150`).
            val undecodable = pki && reaches && !checkNotNull(dest).holdsKeyFor(sender.nodeNum)
            events += at to {
                airings += airing
                if (implicitAcks) sender.emitOutcome(PacketOutcome.Acked(id, byDestination = false))
                // The firmware drops a duplicate it already took: the destination's phone sees the packet once. A PKI
                // packet is reported on index 0, flagged, whatever slot the sender used.
                if (reaches && !landed && !undecodable) {
                    landed = true
                    val index = if (pki) 0 else indexOn(sender, dest, channelIndex)
                    dest.deliver(sender.nodeNum, index, portnum, payload, to = to, wantAck = true, pkiEncrypted = pki)
                }
            }
            if (undecodable) {
                events += (at + ackDelayMs) to { peerLacksKey(sender, checkNotNull(dest), id) }
                schedule(scope, sent, events)
                return
            }
            if (reaches) {
                val ackAt = at + ackDelayMs
                val ackArrives = !ackLost(sender.nodeNum, to) && !lossy(to, sender.nodeNum)
                events += ackAt to { airings += Airing(to, sender.nodeNum, ack = true) }
                if (ackArrives) {
                    events += ackAt to {
                        // The ACK is a ROUTING packet from the destination's board: a hearing of it too.
                        sender.deliver(to, 0, MeshtasticProto.PORT_ROUTING, ByteArray(0), to = sender.nodeNum)
                        if (!silent(sender.nodeNum, to)) sender.emitOutcome(PacketOutcome.Acked(id, byDestination = true))
                    }
                    schedule(scope, sent, events)
                    return
                }
            }
            // A relay repeating it stops the retries, and the give-up never comes: only the destination's own ack could
            // still have followed (`ReliableRouter.cpp:57-87`).
            if (implicitAcks) {
                schedule(scope, sent, events)
                return
            }
        }
        at += gap
        events += at to { if (!silent(sender.nodeNum, to)) sender.emitOutcome(PacketOutcome.Unreachable(id)) }
        schedule(scope, sent, events)
    }

    /**
     * [dest]'s board could not decrypt [sender]'s packet [id]: its `PKI_UNKNOWN_PUBKEY` NAK stops the retries, and the
     * sender's board answers it with its NodeInfo, which teaches [dest]'s board its key (`ReliableRouter.cpp:162-168`).
     */
    private fun peerLacksKey(
        sender: FakeMeshtasticLink,
        dest: FakeMeshtasticLink,
        id: UInt,
    ) {
        airings += Airing(dest.nodeNum, sender.nodeNum, ack = true)
        if (lossy(dest.nodeNum, sender.nodeNum)) return
        sender.emitOutcome(PacketOutcome.Refused(id, RoutingError.PKI_UNKNOWN_PUBKEY, byDestination = true))
        // An airing whether or not it lands ([nodeInfoAirings] counts transmissions); the key is learned only where it lands.
        if (!lossy(sender.nodeNum, dest.nodeNum)) dest.learn(sender.nodeNum, sender.publicKeyOrNone)
        airings += Airing(sender.nodeNum, dest.nodeNum, ack = false, nodeInfo = true)
    }

    /** Our own board's refusal of [id] before it aired: its self-NAK on index 0, after the send returned. */
    fun selfNak(
        sender: FakeMeshtasticLink,
        id: UInt,
        reason: RoutingError,
        sent: Deferred<Unit>,
    ) {
        val scope = checkNotNull(sender.scope) { "a want_ack unicast needs its link's scope: its outcome follows the send" }
        schedule(scope, sent, listOf(0L to { sender.emitOutcome(PacketOutcome.Refused(id, reason)) }))
    }

    companion object {
        /** Firmware 2.8's `NUM_RELIABLE_UNICAST_ATTEMPTS` (`NextHopRouter.h:115`): the board's own number, not the plane's. */
        const val UNICAST_ATTEMPTS = 5
    }

    /**
     * Runs [events] on [scope] at their offsets, counted from [sent]: once the send has returned, and a turn after it.
     * Exact on a single-threaded scope (the transport rigs' `backgroundScope`), where the transport books its flight
     * before it next suspends; on the lab's multi-threaded scopes the board's ACK is at least `quickAir`'s 50 ms out,
     * and only the rigs' `implicitAcks` and `ackDelayMs = 0` answer at offset zero.
     */
    private fun schedule(
        scope: CoroutineScope,
        sent: Deferred<Unit>,
        events: List<Pair<Long, () -> Unit>>,
    ) {
        val ordered = events.sortedBy { it.first }
        scope.launch {
            sent.await()
            yield()
            var now = 0L
            for ((at, action) in ordered) {
                if (at > now) delay(at - now)
                now = at
                action()
            }
        }
    }
}

/** A [MeshtasticLink] backed by [FakeMeshtasticAir]; goes Ready on start and floods sends to the air. */
internal class FakeMeshtasticLink(
    val nodeNum: UInt,
    private val air: FakeMeshtasticAir,
    private val channelName: String = KnitChannel.NAME,
    /** What the handshake reports as the board's firmware. Pre-2.8 by default: the signature era is opt-in. */
    private val firmware: String = "2.5.0",
    /** The board's own `User.public_key` (base64), as its NodeInfo would carry it; null on a board that published none. */
    private val publicKey: String? = null,
    /**
     * Where a `want_ack` unicast's retries, ACK and give-up are timed (`FakeMeshtasticAir.unicast`): required for one, so
     * its outcome never comes before `send` returns its handle. Null for a rig that only broadcasts.
     */
    val scope: CoroutineScope? = null,
    /**
     * The Knit channel's slot when the board comes up provisioned ([readyProvisioned]) rather than on the single-channel
     * binding [ready] reports — what the mesh-in-a-box lab's boards use, since a compact DM is never sent from slot 0.
     */
    private val provisionedSlot: Int? = null,
) : MeshtasticLink {
    /** The board's own identity as the handshake reports it — named like a stock board, keyed when [publicKey] says so. */
    private val boardInfo: BoardInfo
        get() =
            BoardInfo(
                nodeNum,
                "heltec-v4",
                firmware,
                owner = publicKey?.let { BoardOwner("Meshtastic $nodeNum", "$nodeNum", publicKey = it) },
            )
    private val _state = MutableStateFlow<LinkState>(LinkState.Idle)
    override val state = _state

    private val _packets = MutableSharedFlow<ReceivedPacket>(extraBufferCapacity = 256)
    override val packets = _packets

    private val _outcomes = MutableSharedFlow<PacketOutcome>(extraBufferCapacity = 64)
    override val outcomes = _outcomes

    private val _queue = MutableStateFlow<QueueInfo?>(QueueInfo(free = 16, maxlen = 16, atMs = 0))
    override val queue = _queue

    override val battery = MutableStateFlow<BoardBattery?>(null)

    override val boardAir = MutableStateFlow<BoardAir?>(null)

    /** The mesh's NodeDB as the board reports it — what puts a name on a bridged LongFast post. */
    override val nodes = MutableStateFlow<Map<UInt, BoardOwner>>(emptyMap())

    /**
     * The raw `User`s the board's NodeInfo stream showed, by node, stamped: what [nodeUser] answers. Like the session's,
     * it is never told when the board drops a node: [forget] leaves it. A test fills it through [streamUser].
     */
    private val users = ConcurrentHashMap<UInt, StreamedUser>()
    private var userStamp = 0L

    override fun nodeUser(node: UInt): StreamedUser? = users[node]

    override fun userSeq(): Long = userStamp

    /** The board streams [raw] for [node] — a NodeDB push, or anybody's over-the-air NodeInfo claiming [node]. */
    fun streamUser(
        node: UInt,
        raw: ByteArray,
    ) {
        users[node] = StreamedUser(BoardOwnerRaw(raw), ++userStamp)
    }

    /** Run as our board refuses a PKI send to the board it names, before the refusal reaches the phone: a NodeInfo racing the `39`. */
    var onPkiRefusal: ((to: UInt) -> Unit)? = null

    /** When set, the next local admin message is applied but its `queueStatus` never comes back; it clears. */
    var adminTimeoutNext = false

    /** When set, the next send is refused with this `queueStatus.res` — and it clears. */
    var rejectNext: Int? = null

    // The NodeDB's key table: keys learned by name (an add_contact, a NodeInfo after a 35), and boards forgotten.
    private val learnedKeys = ConcurrentHashMap<UInt, String>()
    private val forgotten = ConcurrentHashMap.newKeySet<UInt>()

    /**
     * Whether this board's NodeDB holds a key for [node]: one it learned, or — the default, as a board hears every
     * NodeInfo on the shared primary and keeps a key while its board is off — any node it was not made to [forget].
     */
    fun holdsKeyFor(node: UInt): Boolean = learnedKeys.containsKey(node) || node !in forgotten

    /** What the NodeDB holds for [node]: a key it learned by name, or null for one known off the air or none. */
    fun learnedKeyOf(node: UInt): String? = learnedKeys[node]

    /** `meshtastic --remove-node`: the board forgets [node] and its key. */
    fun forget(node: UInt) {
        learnedKeys.remove(node)
        forgotten += node
    }

    /** The board stores [key] for [node]. */
    fun learn(
        node: UInt,
        key: String,
    ) {
        forgotten -= node
        learnedKeys[node] = key
    }

    /** This board's key as its NodeInfo carries it, or a stand-in for a board built with none. */
    val publicKeyOrNone: String get() = publicKey ?: "none:$nodeNum"

    /** `config.security.is_managed`: local admin is dropped silently, `add_contact` included. */
    var isManaged = false

    /** When set, a `39`'s self-NAK reaches the phone before its `queueStatus`: the send returns the NAK. */
    var noKeyNakFirst = false

    /** Every `add_contact` the phone wrote to this board, decoded: (node, key, the `User` it carried). */
    data class Contact(
        val node: UInt,
        val key: String?,
        val user: ByteArray,
    )

    val contactsWritten = CopyOnWriteArrayList<Contact>()

    /** The board's free-slot count. Assigning it also publishes a [queue] update, as a real QueueStatus does. */
    var free: Int = 16
        set(value) {
            field = value
            _queue.value = QueueInfo(free = value, maxlen = 16, atMs = 0)
        }

    /**
     * When set, each accepted send costs a queue slot, the way a real board's does — so a fragmented frame
     * can run the board out of room part-way and get [SendResult.Busy] for the rest of itself. Off by
     * default: most tests want a board that always has room.
     */
    var queueFills = false

    /**
     * When set, the next send goes on the air but its queueStatus never comes back: the session's `Timeout`, under the
     * handle it assigned. It clears.
     */
    var timeoutNext = false

    /** When set, the next send is refused with this NAK — the board's duty-cycle or rate refusal — and it clears. */
    var nakNext: RoutingError? = null
    private var nextId = 1u
    val sent = mutableListOf<ByteArray>()

    /**
     * The `hop_limit` each send asked for, positionally alongside [sent]. Recorded because the field being
     * **absent** is a bug the payload cannot show: a packet born with zero hops is on the air, decodes, and
     * is simply never repeated by anyone.
     */
    val sentHopLimits = mutableListOf<Int?>()

    /**
     * The channel index and portnum each send asked for, positionally alongside [sent]. Both are invisible
     * in the payload and both are the whole difference between a Knit frame and a post on somebody else's
     * public channel, so a transmit that went to the wrong one would otherwise look identical here.
     */
    val sentChannels = mutableListOf<Int>()
    val sentPortnums = mutableListOf<Int>()

    /** The `to` of each send, positionally alongside [sent]: the broadcast address but for a direct send. */
    val sentTos = mutableListOf<UInt>()

    /** Whether each send asked for `want_ack`, positionally alongside [sent]: a compact DM does, nothing else. */
    val sentWantAcks = mutableListOf<Boolean>()

    /** The handle each accepted send returned, positionally alongside [sent]. */
    val sentIds = mutableListOf<UInt>()

    override suspend fun send(
        payload: ByteArray,
        channelIndex: Int,
        portnum: Int,
        hopLimit: Int?,
        destination: LinkDestination,
    ): SendResult {
        if (free == 0) return SendResult.Busy
        scriptedRefusal()?.let { return it }
        val direct = destination as? LinkDestination.Direct
        // A local admin message to this board: handled before its queueStatus, never aired (`Router.cpp:427-432`).
        if (portnum == MeshtasticProto.PORT_ADMIN && direct?.addr?.node == nodeNum) return admin(payload)
        sent += payload
        sentHopLimits += hopLimit
        sentChannels += channelIndex
        sentPortnums += portnum
        sentTos += direct?.addr?.node ?: MeshtasticProto.BROADCAST
        sentWantAcks += direct?.ack == true
        val id = nextId++
        sentIds += id
        if (queueFills) free--
        val sentGate = CompletableDeferred<Unit>()
        if (direct != null && direct.ack && air.pki) pkiRefusal(direct, payload, id, sentGate)?.let { return it }
        if (direct != null && direct.ack) {
            air.unicast(this, direct.addr.node, channelIndex, portnum, payload, id, sentGate)
        } else {
            air.broadcast(nodeNum, channelIndex, portnum, payload)
        }
        val result =
            if (timeoutNext) {
                timeoutNext = false
                SendResult.Timeout(id)
            } else {
                SendResult.Queued(id, QueueInfo(free, 16, 0))
            }
        sentGate.complete(Unit)
        return result
    }

    /**
     * Our own board's refusal of a PKI unicast before it airs (`Router::perhapsEncode`): too large once PKI's 12 B are
     * added, or no key for the destination. The `queueStatus` res first, then the self-NAK — or, with [noKeyNakFirst],
     * the NAK alone. Null when it goes.
     */
    private fun pkiRefusal(
        direct: LinkDestination.Direct,
        payload: ByteArray,
        id: UInt,
        sentGate: CompletableDeferred<Unit>,
    ): SendResult? {
        val refusal =
            when {
                payload.size + MeshtasticProto.PKC_OVERHEAD_BYTES > MeshtasticProto.MAX_PAYLOAD -> RoutingError.TOO_LARGE
                !holdsKeyFor(direct.addr.node) -> RoutingError.PKI_SEND_FAIL_PUBLIC_KEY
                else -> return null
            }
        onPkiRefusal?.invoke(direct.addr.node)
        if (noKeyNakFirst) return SendResult.Nak(id, refusal)
        air.selfNak(this, id, refusal, sentGate)
        sentGate.complete(Unit)
        return SendResult.Rejected(id, refusal.code)
    }

    /** The refusal [nakNext] or [rejectNext] scripts for this send, each once; null to send. */
    private fun scriptedRefusal(): SendResult? {
        nakNext?.let { reason ->
            nakNext = null
            return SendResult.Nak(nextId++, reason)
        }
        rejectNext?.let { res ->
            rejectNext = null
            val id = nextId++
            // Every refused send draws its self-NAK after the queueStatus (`Router.cpp:591`, `:400`), want_ack or not.
            if (scope != null) air.selfNak(this, id, RoutingError.fromCode(res), CompletableDeferred(Unit))
            return SendResult.Rejected(id, res)
        }
        return null
    }

    /** An `add_contact` (or any local admin): recorded, applied unless [isManaged], answered res 0. */
    private fun admin(payload: ByteArray): SendResult {
        val id = nextId++
        decodeAddContact(payload)?.let { contact ->
            contactsWritten += contact
            if (!isManaged) contact.key?.let { learn(contact.node, it) }
        }
        if (adminTimeoutNext) {
            adminTimeoutNext = false
            return SendResult.Timeout(id)
        }
        return SendResult.Queued(id, QueueInfo(free, 16, 0))
    }

    /** `AdminMessage.add_contact` (66) → `SharedContact { node_num 1, user 2 }`, or null for any other admin message. */
    private fun decodeAddContact(payload: ByteArray): Contact? {
        val contact = readBytesField(payload, ADMIN_ADD_CONTACT) ?: return null
        val node = readVarintField(contact, 1)?.toUInt() ?: return null
        val user = readBytesField(contact, 2) ?: return null
        return Contact(node, readBytesField(user, MeshtasticProto.USER_PUBLIC_KEY)?.let(::b64), user)
    }

    /** The Knit channel's slot on this board, once [readyProvisioned] reported it; null on the lab's index-0 binding. */
    var knitIndex: Int? = null
        private set

    /** What [provisionChannel] returns; a test can script a different outcome. */
    var provisionResult: ProvisionResult = ProvisionResult.Provisioned(index = 1, alreadyPresent = false)
    val provisioned = mutableListOf<ProvisionSpec>()

    override suspend fun provisionChannel(spec: ProvisionSpec): ProvisionResult {
        provisioned += spec
        return provisionResult
    }

    /** Off to model a board still connecting after [start]: the state parks at Connecting until [ready]. */
    var readyOnStart = true

    override fun start(address: String) {
        air.register(this)
        // The real session is idempotent for a link already up (a config edit re-calls start with the same board).
        if (_state.value is LinkState.Ready) return
        when {
            !readyOnStart -> _state.value = LinkState.Connecting
            provisionedSlot != null -> readyProvisioned(provisionedSlot)
            else -> ready()
        }
    }

    /** The handshake completing (at ATT MTU 512, the ESP32 line's ceiling): what a real board reports last. */
    fun ready() {
        knitIndex = null
        _state.value = LinkState.Ready(boardInfo, listOf(ChannelInfo(0, channelName, 1)), 512)
    }

    /**
     * The handshake as a **provisioned** board reports it: the primary at index 0 and the Knit channel in a
     * secondary slot, plus the radio settings. The shape ADR 045 actually produces — [ready]'s single Knit
     * channel at index 0 is a lab binding, and on such a board there is no primary for the Meshtastic room
     * to mirror.
     */
    fun readyProvisioned(
        knitIndex: Int = 1,
        primaryName: String = "",
        primaryPsk: ByteArray = ByteArray(0),
        radio: LoraRadioConfig? =
            LoraRadioConfig(
                usePreset = true,
                modemPreset = ModemPreset.LONG_FAST,
                region = LoraRegion.US,
                hopLimit = 3,
                overrideDutyCycle = false,
            ),
    ) {
        this.knitIndex = knitIndex
        _state.value =
            LinkState.Ready(
                boardInfo,
                listOf(
                    ChannelInfo(0, primaryName, role = 1, psk = primaryPsk),
                    ChannelInfo(knitIndex, KnitChannel.NAME, role = 2, psk = KnitChannel.PSK),
                ),
                512,
                radio,
            )
    }

    override fun stop() {
        air.unregister(this)
        _state.value = LinkState.Idle
    }

    /**
     * A packet arriving from the air: another board's broadcast, or a unicast [to] this board. [rxRssi] null is the
     * NodeDB replay at a handshake (no reading); an old [rxTime] (epoch seconds) is the to-phone queue replayed at
     * reconnect — [replayNodeDb] and [replayQueued] say so by name. [wantAck], [hopLimit] and [nextHop] are the
     * `MeshPacket` fields that say whether this board ACKed it ([LoraDirectPolicy.boardAcks]).
     */
    fun deliver(
        from: UInt,
        channelIndex: Int,
        portnum: Int,
        payload: ByteArray,
        rxSnr: Float? = 6.5f,
        rxRssi: Int? = -85,
        to: UInt = MeshtasticProto.BROADCAST,
        rxTime: UInt? = null,
        viaMqtt: Boolean = false,
        wantAck: Boolean = false,
        hopLimit: Int = 0,
        nextHop: Int = 0,
        pkiEncrypted: Boolean = false,
    ) {
        _packets.tryEmit(
            ReceivedPacket(
                from = from,
                to = to,
                id = nextId++,
                channelIndex = channelIndex,
                portnum = portnum,
                payload = payload,
                rxSnr = rxSnr,
                rxRssi = rxRssi,
                hopsAway = 0,
                viaMqtt = viaMqtt,
                rxTime = rxTime,
                wantAck = wantAck,
                hopLimit = hopLimit,
                nextHop = nextHop,
                pkiEncrypted = pkiEncrypted,
            ),
        )
    }

    /** The synthetic packet a handshake replays per NodeDB entry: stale SNR, no RSSI. */
    fun replayNodeDb(from: UInt) = deliver(from, 0, MeshtasticProto.PORT_TELEMETRY, byteArrayOf(1), rxSnr = -3f, rxRssi = null)

    /** A packet the board heard [ageSeconds] ago and queued for the phone, handed over at reconnect. */
    fun replayQueued(
        from: UInt,
        nowWallMs: Long,
        ageSeconds: Long,
        portnum: Int = MeshtasticProto.PORT_TELEMETRY,
    ) = deliver(from, 0, portnum, byteArrayOf(1), rxTime = (nowWallMs / 1_000 - ageSeconds).toUInt())

    /** An outcome the board reports for a send it took. */
    fun emitOutcome(outcome: PacketOutcome) {
        _outcomes.tryEmit(outcome)
    }

    /**
     * A Meshtastic node's chat on the primary channel — what the Meshtastic room reads. Not routed through
     * [air], which only carries Knit's own channel: a stock neighbour is not a registered board and its
     * packets reach exactly the phones whose radios heard them.
     */
    fun deliverPublicText(
        from: UInt,
        body: String,
        id: UInt = nextId++,
        viaMqtt: Boolean = false,
        to: UInt = MeshtasticProto.BROADCAST,
        signature: ByteArray? = null,
        boardVerified: Boolean = false,
    ) {
        _packets.tryEmit(
            ReceivedPacket(
                from = from,
                to = to,
                id = id,
                channelIndex = PublicChannelPolicy.PRIMARY_INDEX,
                portnum = MeshtasticProto.PORT_TEXT_MESSAGE,
                payload = body.encodeToByteArray(),
                rxSnr = -6.5f,
                rxRssi = -110,
                hopsAway = 1,
                viaMqtt = viaMqtt,
                signature = signature,
                boardVerified = boardVerified,
            ),
        )
    }

    fun emitNak(
        id: UInt,
        reason: RoutingError,
    ) {
        _outcomes.tryEmit(PacketOutcome.Refused(id, reason))
    }

    fun updateHeadroom(value: Int) {
        free = value
        _queue.value = QueueInfo(value, 16, 0)
    }

    fun drop() {
        _state.value = LinkState.Disconnected("test", retryAtMs = 0, streak = 1)
    }

    private companion object {
        const val ADMIN_ADD_CONTACT = 66
    }
}
