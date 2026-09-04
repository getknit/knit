package app.getknit.knit.mesh.lora

import app.getknit.knit.mesh.bluetooth.BackoffConfig
import app.getknit.knit.mesh.bluetooth.ConnectBackoffPolicy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.random.Random

/**
 * One write inside a provisioning transaction, built late because the [MeshtasticProto] `session_passkey`
 * it must echo is only known once the board has issued one — and is re-issued on a fresh-key retry.
 */
private typealias AdminStep = (ByteArray?) -> ByteArray

/**
 * The pure state machine that turns a [MeshtasticGattDialer] into a managed [MeshtasticLink]. An actor:
 * a single driver coroutine owns the open [GattChannel] and issues every GATT op sequentially, fed by one
 * inbox [Channel] that merges send commands, GATT events, and heartbeat ticks. It handles the config
 * handshake, the drain-until-empty read on every FromNum, the keep-alive heartbeat, packet-id/queueStatus
 * correlation, and reconnect-with-backoff — all against injected `now`/`rand`/`nonce`, so it runs on the
 * JVM under virtual time ([app.getknit.knit.mesh.lora.MeshtasticSessionTest]).
 *
 * Android-free by construction (the only `android.bluetooth.*` lives behind the dialer seam), honouring
 * `.agents/rules/mesh.md`.
 *
 * `LargeClass` is suppressed because one actor owns the open GATT channel, so every op it serializes —
 * handshake, sends, heartbeat, admin provisioning — has to live here; splitting it would mean a second
 * owner of the same channel.
 */
@Suppress("LargeClass")
internal class MeshtasticSession(
    private val dialer: MeshtasticGattDialer,
    private val scope: CoroutineScope,
    private val backoff: BackoffConfig = BackoffConfig(baseMs = BASE_BACKOFF_MS, maxMs = MAX_BACKOFF_MS),
    private val now: () -> Long,
    private val rand: () -> Double = { Random.nextDouble() },
    private val nonce: () -> UInt = { Random.nextInt().toUInt().let { if (it == 0u) 1u else it } },
    private val ids: PacketIdSource = PacketIdSource(Random.nextLong()),
    private val log: (String) -> Unit = {},
) : MeshtasticLink {
    private val _state = MutableStateFlow<LinkState>(LinkState.Idle)
    override val state = _state.asStateFlow()

    private val _packets = MutableSharedFlow<ReceivedPacket>(extraBufferCapacity = PACKET_BUFFER)
    override val packets = _packets.asSharedFlow()

    private val _outcomes = MutableSharedFlow<PacketOutcome>(extraBufferCapacity = OUTCOME_BUFFER)
    override val outcomes = _outcomes.asSharedFlow()

    private val _queue = MutableStateFlow<QueueInfo?>(null)
    override val queue = _queue.asStateFlow()

    private val _battery = MutableStateFlow<BoardBattery?>(null)
    override val battery = _battery.asStateFlow()

    private val inbox = Channel<Cmd>(Channel.BUFFERED)
    private var loopJob: Job? = null

    @Volatile
    private var address: String? = null

    // The board's identity + channels, set at handshake; read when building the Ready state and for logs.
    private var board: BoardInfo? = null
    private var channels: List<ChannelInfo> = emptyList()

    // The board's radio settings (region + modem preset) from the handshake's Config stream — what the
    // airtime governor needs. Null until a board reports them; kept across a re-handshake of the same board.
    private var radio: LoraRadioConfig? = null
    private var lastWriteAt = 0L

    // Pending sends awaiting their queueStatus, keyed by our packet id, so a late NAK can still be matched.
    private val pending = HashMap<UInt, CompletableDeferred<SendResult>>()

    override fun start(address: String) {
        if (this.address == address && loopJob?.isActive == true) return
        stop()
        this.address = address
        loopJob = scope.launch { connectLoop(address) }
    }

    override fun stop() {
        loopJob?.cancel()
        loopJob = null
        address = null
        failAllPending(SendResult.NotReady(LinkState.Idle))
        _battery.value = null
        _state.value = LinkState.Idle
    }

    override suspend fun send(
        payload: ByteArray,
        channelIndex: Int,
        portnum: Int,
        hopLimit: Int?,
    ): SendResult {
        if (payload.size > MeshtasticProto.MAX_PAYLOAD) return SendResult.TooLarge
        val st = _state.value
        if (st !is LinkState.Ready) return SendResult.NotReady(st)
        val reply = CompletableDeferred<SendResult>()
        inbox.send(Cmd.Send(channelIndex, portnum, hopLimit, payload, reply))
        return reply.await()
    }

    override suspend fun provisionChannel(spec: ProvisionSpec): ProvisionResult {
        val st = _state.value
        if (st !is LinkState.Ready) return ProvisionResult.NotReady(st)
        val reply = CompletableDeferred<ProvisionResult>()
        inbox.send(Cmd.Provision(spec, reply))
        return reply.await()
    }

    // --- the reconnect loop (one coroutine per start) ---

    private suspend fun connectLoop(address: String) {
        var streak = 0
        while (scope.isActive) {
            if (!dialer.adapterOn.value) {
                _state.value = LinkState.Unavailable
                dialer.adapterOn.first { it }
                streak = 0
                continue
            }
            _state.value = LinkState.Connecting
            when (val result = dialer.dial(address)) {
                is DialResult.Opened -> {
                    val end =
                        try {
                            runSession(address, result.channel, result.mtu)
                        } finally {
                            result.channel.close() // closes on a normal end AND on stop()'s cancellation
                        }
                    if (end.terminal != null) {
                        _state.value = end.terminal
                        return
                    }
                    if (end.resetStreak) streak = 0
                    streak = backoffAndWait(end.reason, streak)
                }

                DialResult.NoHardware -> {
                    _state.value = LinkState.Unavailable
                    return
                }

                // Adapter went off mid-dial: fall through and let the loop re-check adapterOn at the top.
                DialResult.AdapterOff -> {
                    Unit
                }

                is DialResult.Failed -> {
                    streak = backoffAndWait("dial ${result.phase} ${result.status}", streak)
                }

                DialResult.Timeout -> {
                    streak = backoffAndWait("dial timeout", streak)
                }
            }
        }
    }

    private suspend fun backoffAndWait(
        reason: String,
        streak: Int,
    ): Int {
        val next = streak + 1
        val wait = ConnectBackoffPolicy.nextDelayMs(next, backoff, rand)
        log("lora backoff streak=$next ms=$wait ($reason)")
        _state.value = LinkState.Disconnected(reason, now() + wait, next)
        delay(wait)
        return next
    }

    // --- one connected session ---

    private suspend fun runSession(
        address: String,
        channel: GattChannel,
        mtu: Int,
    ): SessionEnd {
        val handshake = handshake(address, channel)
        if (handshake != null) return handshake
        _state.value = LinkState.Ready(requireNotNull(board), channels, mtu, radio)
        lastWriteAt = now()
        val heartbeat = scope.launch { heartbeatTicker() }
        try {
            return sessionLoop(address, channel)
        } finally {
            heartbeat.cancel()
            failAllPending(SendResult.NotReady(_state.value))
        }
    }

    private suspend fun sessionLoop(
        address: String,
        channel: GattChannel,
    ): SessionEnd {
        while (scope.isActive) {
            val outcome =
                select {
                    channel.events.onReceive { it }
                    inbox.onReceive { it }
                }
            val end = handleOutcome(address, channel, outcome)
            if (end != null) return end
        }
        return SessionEnd(reason = "cancelled", terminal = LinkState.Idle)
    }

    /** Handles one merged event; returns a [SessionEnd] to end the session, or null to keep looping. */
    private suspend fun handleOutcome(
        address: String,
        channel: GattChannel,
        outcome: Any,
    ): SessionEnd? =
        when (outcome) {
            is GattEvent.Disconnected -> SessionEnd(reason = "gatt disconnect ${outcome.status}")
            is GattEvent.Notified -> drain(address, channel, awaitId = null)
            is Cmd.Send -> doSend(address, channel, outcome)
            is Cmd.Provision -> runProvision(channel, outcome)
            Cmd.Heartbeat -> maybeHeartbeat(address, channel)
            else -> null
        }

    // --- handshake ---

    private suspend fun handshake(
        address: String,
        channel: GattChannel,
    ): SessionEnd? {
        _state.value = LinkState.Handshaking(null)
        board = null
        channels = emptyList()
        _battery.value = null
        classify(address, channel.subscribeFromNum(SUBSCRIBE_TIMEOUT_MS))?.let { return it }
        // Drain any stale queue from a previous phone session before our want_config nonce.
        drainQuietly(channel)
        val n = nonce()
        val writeTimeout = if (dialer.bondState(address) == BondState.BONDED) WRITE_TIMEOUT_MS else BONDING_TIMEOUT_MS
        if (dialer.bondState(address) == BondState.BONDING) _state.value = LinkState.Bonding
        classify(address, channel.writeToRadio(MeshtasticProto.encodeWantConfig(n), writeTimeout))?.let { return it }
        return awaitConfigComplete(address, channel, n)
    }

    private suspend fun awaitConfigComplete(
        address: String,
        channel: GattChannel,
        nonce: UInt,
    ): SessionEnd? {
        val deadline = now() + HANDSHAKE_TIMEOUT_MS
        while (now() < deadline) {
            when (val read = channel.readFromRadio(READ_TIMEOUT_MS)) {
                is GattResult.Ok -> {
                    if (absorb(MeshtasticProto.decodeFromRadio(read.value), nonce)) return null
                }

                else -> {
                    return classify(address, read) ?: SessionEnd(reason = "handshake read $read")
                }
            }
        }
        return SessionEnd(reason = "handshake timeout")
    }

    /**
     * Folds one handshake `FromRadio` into the session's picture of the board — identity, firmware, channel
     * table, radio settings, its own battery. Returns true on the `config_complete` that matches [nonce],
     * which is what ends the handshake. Anything else (including a variant we don't read) is absorbed
     * silently: the board streams its whole config, and an unknown entry must never stall the handshake.
     */
    private fun absorb(
        fr: FromRadio?,
        nonce: UInt,
    ): Boolean {
        when (fr) {
            is FromRadio.ConfigComplete -> return fr.id == nonce
            is FromRadio.MyInfo -> board = BoardInfo(fr.myNodeNum, fr.pioEnv, board?.firmwareVersion)
            is FromRadio.Metadata -> board = (board ?: BLANK_BOARD).copy(firmwareVersion = fr.firmwareVersion)
            is FromRadio.Channel -> channels = channels + fr.channel
            is FromRadio.Config -> fr.lora?.let { radio = it }
            is FromRadio.NodeInfo -> onNodeInfo(fr)
            else -> Unit
        }
        return false
    }

    // --- draining reads ---

    /**
     * Reads FromRadio until the queue drains. When [awaitId] is set (a send), returns as soon as its
     * `queueStatus` arrives so [doSend] can complete the caller; otherwise drains fully. A `rebooted`
     * or unsolicited `my_info` ends the session for a fresh handshake.
     */
    private suspend fun drain(
        address: String,
        channel: GattChannel,
        awaitId: UInt?,
    ): SessionEnd? {
        while (true) {
            when (val read = channel.readFromRadio(READ_TIMEOUT_MS)) {
                is GattResult.Ok -> {
                    if (read.value.isEmpty()) return null
                    val signal = dispatch(MeshtasticProto.decodeFromRadio(read.value), awaitId)
                    when (signal) {
                        Signal.Rehandshake -> return SessionEnd(reason = "rebooted", resetStreak = true)
                        Signal.MatchedAwait -> return null
                        Signal.Continue -> Unit
                    }
                }

                else -> {
                    return classify(address, read) ?: SessionEnd(reason = "drain read $read")
                }
            }
        }
    }

    private suspend fun drainQuietly(channel: GattChannel) {
        repeat(MAX_STALE_READS) {
            val read = channel.readFromRadio(READ_TIMEOUT_MS)
            if (read !is GattResult.Ok || read.value.isEmpty()) return
        }
    }

    private suspend fun dispatch(
        fr: FromRadio?,
        awaitId: UInt?,
    ): Signal =
        when (fr) {
            is FromRadio.Packet -> {
                onPacket(fr.packet)
                Signal.Continue
            }

            is FromRadio.QueueStatus -> {
                onQueueStatus(fr, awaitId)
            }

            FromRadio.Rebooted -> {
                Signal.Rehandshake
            }

            is FromRadio.MyInfo -> {
                if (_state.value is LinkState.Ready) Signal.Rehandshake else Signal.Continue
            }

            is FromRadio.NodeInfo -> {
                onNodeInfo(fr)
                Signal.Continue
            }

            is FromRadio.Config -> {
                // The firmware pushes a Config when the user edits the radio on the board; keep the
                // governor current without waiting for the next handshake.
                fr.lora?.let {
                    radio = it
                    (_state.value as? LinkState.Ready)?.let { ready -> _state.value = ready.copy(radio = it) }
                }
                Signal.Continue
            }

            else -> {
                Signal.Continue
            }
        }

    private suspend fun onPacket(packet: MeshPacket) {
        // `rx_snr`/`rx_rssi` are deliberately NOT recorded here. This runs ahead of every filter below, and
        // the board hands the phone the whole air: strangers relayed off its public primary channel, and a
        // synthetic POSITION/TELEMETRY per NodeDB entry replayed at each handshake (a stale per-node SNR and
        // no RSSI at all). Keeping the last of those as "the signal" read a healthy +6 dB board-to-board link
        // as -17 dB within minutes and stuck there. Only `LoraMeshTransport.noteBoard` — past the portnum and
        // bound-channel filter, keyed per radio and aged — may record a reading.
        val data = packet.decoded ?: return // an encrypted packet on a foreign channel — not for us
        // Routing NAKs originate from our OWN board's node (it generates the error), so they must be
        // handled before the self-echo guard below — otherwise `from == myNodeNum` would swallow them.
        if (data.portnum == MeshtasticProto.PORT_ROUTING) {
            routeNak(data)
            return
        }
        // The board's own device telemetry (its battery) is addressed from itself too: read, never surfaced.
        if (data.portnum == MeshtasticProto.PORT_TELEMETRY && packet.from == board?.myNodeNum) {
            MeshtasticProto.decodeTelemetry(data.payload)?.let(::onSelfMetrics)
            return
        }
        if (board?.myNodeNum == packet.from) return // our own broadcast echoed back (belt-and-suspenders)
        _packets.tryEmit(
            ReceivedPacket(
                from = packet.from,
                to = packet.to,
                id = packet.id,
                channelIndex = packet.channel,
                portnum = data.portnum,
                payload = data.payload,
                rxSnr = packet.rxSnr,
                rxRssi = packet.rxRssi,
                hopsAway = packet.hopsAway,
            ),
        )
    }

    /** The handshake streams the whole NodeDB; only the board's own entry carries *its* battery. */
    private fun onNodeInfo(info: FromRadio.NodeInfo) {
        if (info.num != board?.myNodeNum) return
        info.metrics?.let(::onSelfMetrics)
        // The board's own name, so the setup screen can tell a board that still needs renaming (ADR 049).
        info.owner?.let { owner -> board = board?.copy(owner = owner) }
    }

    private fun onSelfMetrics(metrics: DeviceMetrics) {
        _battery.value = BoardBattery.of(metrics.batteryLevel, metrics.voltage)
    }

    private fun routeNak(data: MeshData) {
        val reason = MeshtasticProto.decodeRouting(data.payload) ?: RoutingError.UNKNOWN
        val waiter = pending.remove(data.requestId)
        if (waiter != null) {
            waiter.complete(SendResult.Nak(data.requestId, reason))
        } else {
            _outcomes.tryEmit(PacketOutcome(data.requestId, reason))
        }
    }

    private fun onQueueStatus(
        qs: FromRadio.QueueStatus,
        awaitId: UInt?,
    ): Signal {
        _queue.value = QueueInfo(qs.free, qs.maxlen, now())
        val id = qs.meshPacketId
        val waiter = if (id != 0u) pending.remove(id) else null
        if (waiter != null) {
            waiter.complete(
                if (qs.res != 0) SendResult.Rejected(id, qs.res) else SendResult.Queued(id, QueueInfo(qs.free, qs.maxlen, now())),
            )
        }
        return if (awaitId != null && id == awaitId) Signal.MatchedAwait else Signal.Continue
    }

    // --- sending ---

    private suspend fun doSend(
        address: String,
        channel: GattChannel,
        cmd: Cmd.Send,
    ): SessionEnd? {
        if (_queue.value?.free == 0) {
            cmd.reply.complete(SendResult.Busy)
            return null
        }
        val id = ids.next()
        pending[id] = cmd.reply
        val packet =
            OutboundPacket(channelIndex = cmd.channelIndex, id = id, portnum = cmd.portnum, payload = cmd.payload, hopLimit = cmd.hopLimit)
        when (val write = channel.writeToRadio(MeshtasticProto.encodePacket(packet), WRITE_TIMEOUT_MS)) {
            is GattResult.Ok -> {
                lastWriteAt = now()
            }

            else -> {
                pending.remove(id)
                cmd.reply.complete(SendResult.NotReady(_state.value))
                return classify(address, write) ?: SessionEnd(reason = "send write $write")
            }
        }
        val end = drain(address, channel, awaitId = id)
        // Still pending after the drain (no matching queueStatus came back) → time it out; the transport retries.
        pending.remove(id)?.complete(SendResult.Timeout)
        return end
    }

    // --- channel provisioning (an AdminMessage to the local node, over portnum ADMIN) ---

    /**
     * Runs one [ProvisionSpec] against the board: GET a session passkey, then begin→writes→commit echoing it.
     * The commit reboots the board to apply the edit, so on success this ends the session (resetting the
     * backoff) for a fresh handshake that reloads the channel table. Runs inside the actor, so its GATT ops
     * stay serialized with sends.
     */
    private suspend fun runProvision(
        channel: GattChannel,
        cmd: Cmd.Provision,
    ): SessionEnd? {
        val myNode = board?.myNodeNum
        if (_state.value !is LinkState.Ready || myNode == null) {
            cmd.reply.complete(ProvisionResult.NotReady(_state.value))
            return null
        }
        return when (cmd.spec.mode) {
            ProvisionMode.Setup, ProvisionMode.SetupDedicated -> runSetup(channel, myNode, cmd)
            ProvisionMode.Restore -> runRestore(channel, myNode, cmd)
        }
    }

    /**
     * Sets the board up for Knit (ADR 045). Knit goes into a free **secondary** slot, which deliberately
     * leaves the board's own primary — and therefore its RF frequency — alone: Knit shares the public
     * frequency on purpose, so that stock Meshtastic nodes (whose default `rebroadcast_mode` repeats traffic
     * "from another mesh with the same lora params") carry Knit's packets for free. Alongside the channel,
     * the board's own broadcasts are quieted and it stops relaying strangers' traffic ([BoardQuiet]).
     *
     * Every config write is a read-modify-write — the firmware assigns the whole sub-config, so a write built
     * from scratch would silently reset `role`, `gps_mode` and everything else this codec does not model. The
     * reads therefore run **before** `begin_edit_settings`, and a read that fails aborts with nothing written.
     */
    private suspend fun runSetup(
        channel: GattChannel,
        myNode: UInt,
        cmd: Cmd.Provision,
    ): SessionEnd? {
        val was = readBoardOwner(channel, myNode) ?: return failProvision(cmd, "board did not return its name")
        val identity =
            ownerStep(was, BoardName.forNode(myNode, board?.firmwareVersion))
                ?: return failProvision(cmd, MALFORMED_OWNER)
        // Read and refuse before anything is written: a region Knit will not place a slot in must leave the
        // board untouched rather than half set up (ADR 067).
        val slot = slotWrite(channel, myNode, cmd) ?: return null
        val existing = knitChannel(cmd.spec.name)
        return if (existing != null) {
            identityOnly(channel, myNode, cmd, existing, was, identity, slot)
        } else {
            writeSetup(channel, myNode, cmd, was, identity, slot)
        }
    }

    /**
     * The radio write that pins the board to the RF slot [LoraSlot] derives for its region and preset, and
     * the raw `Config.LoRaConfig` it was spliced from — which is also the only thing that knows the board's
     * own `channel_num`, for the restore to put back. [SlotWrite.NONE] for the ordinary shared-frequency
     * setup, which never reads or writes the radio at all (ADR 045).
     *
     * Null means the request has been refused and [cmd] already answered: either the region has no slot Knit
     * will place ([ProvisionResult.NoDedicatedSlot]) or the board would not return its radio config.
     */
    private suspend fun slotWrite(
        channel: GattChannel,
        myNode: UInt,
        cmd: Cmd.Provision,
    ): SlotWrite? {
        if (cmd.spec.mode != ProvisionMode.SetupDedicated) return SlotWrite.NONE
        val cfg = radio
        val slot = cfg?.let { LoraSlot.forRegion(it.region, it.modemPreset) }
        if (slot == null) {
            log("lora provision refused: no dedicated slot for region ${cfg?.region ?: LoraRegion.UNSET}")
            cmd.reply.complete(ProvisionResult.NoDedicatedSlot(cfg?.region ?: LoraRegion.UNSET))
            return null
        }
        val raw =
            readOneConfig(channel, myNode, BoardConfig.LORA) ?: run {
                failProvision(cmd, "board did not return its radio config")
                return null
            }
        val spliced =
            spliceVarintFields(raw, BoardQuiet.loraSlot(slot)) ?: run {
                failProvision(cmd, MALFORMED_CONFIG)
                return null
            }
        return SlotWrite(steps = listOf(configStep(BoardConfig.LORA, spliced)), raw = raw, slot = slot)
    }

    /**
     * The full setup, on a board that does not carry the Knit channel yet: the channel into the lowest free
     * secondary slot, the rename, and the quieting — one transaction, so the board's implicit reboot happens
     * once. The reads run **before** it, and a read that fails aborts with nothing written.
     */
    private suspend fun writeSetup(
        channel: GattChannel,
        myNode: UInt,
        cmd: Cmd.Provision,
        was: BoardOwnerRaw,
        identity: List<AdminStep>,
        slot: SlotWrite,
    ): SessionEnd? {
        val index = freeSecondarySlot() ?: return noFreeSlot(cmd)
        val raws = readBoardConfigs(channel, myNode) ?: return failProvision(cmd, "board did not return its config")
        val steps =
            buildList {
                add(
                    channelStep(
                        ChannelWrite(
                            index = index,
                            name = cmd.spec.name,
                            psk = cmd.spec.psk,
                            positionPrecision = MeshtasticProto.POSITION_PRECISION_NONE,
                        ),
                    ),
                )
                addAll(identity)
                addAll(quietSteps(raws) { config -> BoardQuiet.quiet(config) } ?: return failProvision(cmd, MALFORMED_CONFIG))
                addAll(slot.steps)
            }
        return applySteps(
            channel = channel,
            myNode = myNode,
            steps = steps,
            label = "set up ch$index '${cmd.spec.name}'${slot.label}",
            reply = cmd.reply,
            result =
                ProvisionResult.Provisioned(
                    index = index,
                    alreadyPresent = false,
                    previous = BoardQuiet.recorded(raws, was.owner, slot.raw),
                ),
        )
    }

    /**
     * The board already carries the Knit channel, so its channel table and its quieted intervals are left
     * exactly as they are — but a board an older Knit set up still gets the half of its identity that is
     * missing, on its own: its name (ADR 049), the unmonitored mark (ADR 2026-09.emd7), or both. The record
     * the caller already holds is carried forward with the old name **filled in**, because on the ADR 049
     * board there is none and this is the one moment it is still knowable; re-recording the *intervals* here
     * would write back the quieted ones and destroy the only copy of the board's own.
     */
    private suspend fun identityOnly(
        channel: GattChannel,
        myNode: UInt,
        cmd: Cmd.Provision,
        existing: ChannelInfo,
        was: BoardOwnerRaw,
        identity: List<AdminStep>,
        slot: SlotWrite,
    ): SessionEnd? {
        // The record keeps the board's own channel_num the first time a dedicated setup reads it; on the
        // shared-frequency path there is nothing read and nothing to add.
        val previous =
            cmd.spec.previous?.copy(
                // Filled in, never overwritten: on the ADR 049 board this was written for there is no
                // recorded name and [was] is the board's own, but on a board that is only missing ADR
                // 2026-09.emd7's mark [was] is already "Knit abcd" — and writing that here would destroy
                // the only copy of the name a restore has to put back.
                owner = cmd.spec.previous.owner ?: was.owner,
                channelNum = slot.recordedChannelNum ?: cmd.spec.previous.channelNum,
            )
        val steps = identity + slot.steps
        if (steps.isEmpty()) {
            log("lora provision already set up ch${existing.index} '${cmd.spec.name}'")
            cmd.reply.complete(ProvisionResult.Provisioned(existing.index, alreadyPresent = true))
            return null
        }
        return applySteps(
            channel = channel,
            myNode = myNode,
            steps = steps,
            label =
                listOfNotNull(
                    "wrote the board's Knit identity '${BoardName.forNode(myNode, board?.firmwareVersion).longName}'"
                        .takeIf { identity.isNotEmpty() },
                    slot.label.takeIf { it.isNotEmpty() },
                ).joinToString(" "),
            reply = cmd.reply,
            result =
                ProvisionResult.Provisioned(
                    index = existing.index,
                    alreadyPresent = true,
                    previous = previous,
                ),
        )
    }

    /**
     * The dedicated-slot half of a setup: the steps that pin `lora.channel_num`, the raw radio config they
     * were spliced from, and the slot itself for the log line. [NONE] is the shared-frequency setup, which
     * carries no steps and reads nothing — so every board that never asked for a dedicated slot goes through
     * exactly the writes ADR 045 always made.
     */
    private class SlotWrite(
        val steps: List<AdminStep>,
        val raw: ByteArray?,
        val slot: Int?,
    ) {
        /** The board's own `channel_num`, or null when the radio config was never read. */
        val recordedChannelNum: Int?
            get() = raw?.let { readVarintField(it, MeshtasticProto.LORA_CHANNEL_NUM)?.toInt() ?: 0 }

        val label: String get() = slot?.let { "on dedicated slot $it" }.orEmpty()

        companion object {
            val NONE = SlotWrite(steps = emptyList(), raw = null, slot = null)
        }
    }

    /**
     * Undoes [runSetup]: the Knit channel is disabled and the board's own broadcast intervals and rebroadcast
     * mode return to [ProvisionSpec.previous] (its values, recorded at setup time). The primary is never
     * touched here for the same reason it is never touched there — it was always the user's.
     *
     * Refused on a board that carries no Knit channel: there is nothing to undo, and the config writes would
     * push somebody else's board to values it never had.
     */
    private suspend fun runRestore(
        channel: GattChannel,
        myNode: UInt,
        cmd: Cmd.Provision,
    ): SessionEnd? {
        if (knitChannel(cmd.spec.name) == null) return failProvision(cmd, "this board is not set up for Knit")
        val was = readBoardOwner(channel, myNode) ?: return failProvision(cmd, "board did not return its name")
        val raws = readBoardConfigs(channel, myNode) ?: return failProvision(cmd, "board did not return its config")
        val name = cmd.spec.previous?.owner ?: BoardName.stock(myNode)
        val slot = slotRestore(channel, myNode, cmd) ?: return null
        val steps =
            buildList {
                addAll(disableKnitChannels(cmd.spec.name))
                addAll(ownerStep(was, name) ?: return failProvision(cmd, MALFORMED_OWNER))
                addAll(
                    quietSteps(raws) { config -> BoardQuiet.restore(config, cmd.spec.previous) }
                        ?: return failProvision(cmd, MALFORMED_CONFIG),
                )
                addAll(slot)
            }
        return applySteps(
            channel = channel,
            myNode = myNode,
            steps = steps,
            label = "restored the board's own settings",
            reply = cmd.reply,
            result = ProvisionResult.Restored,
        )
    }

    /**
     * The radio write that puts `lora.channel_num` back to what the board had before Knit pinned it — 0 on
     * every board the plain setup touched, which is why this is **empty unless the board's current slot
     * differs from the recorded one**. A restore of a board that was never dedicated therefore reads and
     * writes no radio config at all, exactly as before ADR 067.
     *
     * Null means the read failed and [cmd] has been answered.
     */
    private suspend fun slotRestore(
        channel: GattChannel,
        myNode: UInt,
        cmd: Cmd.Provision,
    ): List<AdminStep>? {
        val want = cmd.spec.previous?.channelNum ?: BoardQuiet.SHARED_SLOT
        if ((radio?.channelNum ?: BoardQuiet.SHARED_SLOT) == want) return emptyList()
        val raw =
            readOneConfig(channel, myNode, BoardConfig.LORA) ?: run {
                failProvision(cmd, "board did not return its radio config")
                return null
            }
        val spliced =
            spliceVarintFields(raw, BoardQuiet.loraSlot(want)) ?: run {
                failProvision(cmd, MALFORMED_CONFIG)
                return null
            }
        return listOf(configStep(BoardConfig.LORA, spliced))
    }

    /** Answers [cmd] without writing anything: every secondary slot is already spoken for. */
    private fun noFreeSlot(cmd: Cmd.Provision): SessionEnd? {
        cmd.reply.complete(ProvisionResult.NoFreeSlot)
        return null
    }

    /**
     * The `set_owner` write that gives the board the identity [want] — its two names and whether it tells
     * the mesh nobody reads it (ADR 2026-09.emd7). Empty when the board already carries that identity (the
     * firmware would treat the write as a no-op anyway, and an empty step list is what lets a re-run of the
     * setup stay a reported no-op), or null when the board sent a `User` this codec could not walk.
     *
     * A read-modify-write like every config write, but for a different reason: `handleSetOwner` merges the
     * non-empty strings rather than assigning, yet both the bools it merges are cleared by an omission — see
     * [MeshtasticProto.encodeAdminSetOwner] — so a `User` built from scratch would drop `is_licensed`, and
     * with it the firmware's `override_duty_cycle` escape hatch.
     *
     * The mark is spliced as a varint over the *string* splice's output, and a `false` clears the field
     * outright: [ProtoWriter] writes a zero by omission, which is the encoding of "this board never said"
     * and reads as messagable everywhere. That is what a restore puts back on a board Knit marked.
     */
    private fun ownerStep(
        was: BoardOwnerRaw,
        want: BoardOwner,
    ): List<AdminStep>? {
        if (was.owner == want) return emptyList()
        val named =
            spliceStringFields(
                was.raw,
                mapOf(
                    MeshtasticProto.USER_LONG_NAME to want.longName,
                    MeshtasticProto.USER_SHORT_NAME to want.shortName,
                ),
            ) ?: return null
        val spliced =
            spliceVarintFields(
                named,
                mapOf(MeshtasticProto.USER_IS_UNMESSAGABLE to if (want.unmessagable) 1L else 0L),
            ) ?: return null
        val step: AdminStep = { passkey -> MeshtasticProto.encodeAdminSetOwner(spliced, passkey) }
        return listOf(step)
    }

    /** The board's Knit channel, wherever it sits, or null on a board that was never set up. */
    private fun knitChannel(name: String): ChannelInfo? = channels.firstOrNull { it.name == name && it.role != ROLE_DISABLED }

    /** Disables every channel carrying the Knit name — an older build may have written more than one. */
    private fun disableKnitChannels(name: String): List<AdminStep> =
        channels
            .filter { it.name == name }
            .map { channelStep(ChannelWrite(it.index, name = "", psk = ByteArray(0), role = ROLE_DISABLED)) }

    /** The lowest secondary index (1..7) not already holding a live channel, or null when all are taken. */
    private fun freeSecondarySlot(): Int? {
        val used = channels.filter { it.role != ROLE_DISABLED && it.name.isNotEmpty() }.map { it.index }.toSet()
        return (FIRST_SECONDARY..LAST_SECONDARY).firstOrNull { it !in used }
    }

    /** One spliced write per sub-config, or null if the board sent one this codec could not walk. */
    private fun quietSteps(
        raws: Map<BoardConfig, ByteArray>,
        fields: (BoardConfig) -> Map<Int, Long>,
    ): List<AdminStep>? =
        raws.map { (config, raw) ->
            val spliced = spliceVarintFields(raw, fields(config)) ?: return null
            configStep(config, spliced)
        }

    private fun failProvision(
        cmd: Cmd.Provision,
        reason: String,
    ): SessionEnd? {
        cmd.reply.complete(ProvisionResult.Failed(reason))
        return null
    }

    /** Reads every sub-config the quieting touches, as raw bytes; null if the board fails to return one. */
    private suspend fun readBoardConfigs(
        channel: GattChannel,
        myNode: UInt,
    ): Map<BoardConfig, ByteArray>? {
        val out = LinkedHashMap<BoardConfig, ByteArray>()
        for (config in BoardConfig.QUIET) {
            val reply = adminRequest(channel, myNode, MeshtasticProto.encodeAdminGetConfig(config)) ?: return null
            val raw = reply.config?.takeIf { it.config == config }?.raw ?: return null
            out[config] = raw
        }
        return out
    }

    /** Reads one sub-config as raw bytes; null if the board fails to return it. */
    private suspend fun readOneConfig(
        channel: GattChannel,
        myNode: UInt,
        config: BoardConfig,
    ): ByteArray? {
        val reply = adminRequest(channel, myNode, MeshtasticProto.encodeAdminGetConfig(config)) ?: return null
        return reply.config?.takeIf { it.config == config }?.raw
    }

    /** Reads the board's own `User`, the base the rename splices into; null if it never answers. */
    private suspend fun readBoardOwner(
        channel: GattChannel,
        myNode: UInt,
    ): BoardOwnerRaw? = adminRequest(channel, myNode, MeshtasticProto.encodeAdminGetOwner())?.owner

    private fun channelStep(write: ChannelWrite): AdminStep = { passkey -> MeshtasticProto.encodeAdminSetChannel(write, passkey) }

    private fun configStep(
        config: BoardConfig,
        raw: ByteArray,
    ): AdminStep = { passkey -> MeshtasticProto.encodeAdminSetConfig(config, raw, passkey) }

    private suspend fun applySteps(
        channel: GattChannel,
        myNode: UInt,
        steps: List<AdminStep>,
        label: String,
        reply: CompletableDeferred<ProvisionResult>,
        result: ProvisionResult,
    ): SessionEnd? {
        var outcome = writeSteps(channel, myNode, adminGet(channel, myNode)?.passkey, steps)
        if (outcome == AdminOutcome.BadSessionKey) {
            outcome = writeSteps(channel, myNode, adminGet(channel, myNode)?.passkey, steps) // one fresh-key retry
        }
        return when (outcome) {
            AdminOutcome.Applied -> {
                log("lora provision $label")
                reply.complete(result)
                SessionEnd(reason = "provisioned", resetStreak = true) // the commit reboots; reconnect reloads channels
            }

            AdminOutcome.BadSessionKey -> {
                reply.complete(ProvisionResult.Failed("admin session key rejected"))
                null
            }

            AdminOutcome.Failed -> {
                reply.complete(ProvisionResult.Failed("board refused the channel write"))
                null
            }
        }
    }

    /**
     * begin_edit → every [steps] write → commit_edit, each echoing [passkey]. One transaction so the board's
     * implicit save-and-reboot happens once, at the commit, no matter how many settings the mode rewrites.
     */
    private suspend fun writeSteps(
        channel: GattChannel,
        myNode: UInt,
        passkey: ByteArray?,
        steps: List<AdminStep>,
    ): AdminOutcome {
        val begin = writeAdmin(channel, myNode, MeshtasticProto.encodeAdminBeginEdit(passkey))
        if (begin == AdminOutcome.BadSessionKey) return begin
        for (step in steps) {
            val outcome = writeAdmin(channel, myNode, step(passkey))
            if (outcome != AdminOutcome.Applied) return outcome
        }
        // commit triggers the implicit save+reboot: the routing reply may never arrive, so don't wait on it.
        writeAdmin(channel, myNode, MeshtasticProto.encodeAdminCommitEdit(passkey), expectReply = false)
        return AdminOutcome.Applied
    }

    /** Sends `get_channel_request(0)` to the local node and returns the reply carrying a fresh session passkey. */
    private suspend fun adminGet(
        channel: GattChannel,
        myNode: UInt,
    ): AdminReply? = adminRequest(channel, myNode, MeshtasticProto.encodeAdminGetChannel(0))

    /** One admin read addressed to the local node: write it, then wait for the matching admin reply. */
    private suspend fun adminRequest(
        channel: GattChannel,
        myNode: UInt,
        payload: ByteArray,
    ): AdminReply? {
        val id = ids.next()
        val packet =
            OutboundPacket(
                to = myNode,
                channelIndex = 0,
                id = id,
                portnum = MeshtasticProto.PORT_ADMIN,
                payload = payload,
                wantResponse = true,
            )
        if (channel.writeToRadio(MeshtasticProto.encodePacket(packet), WRITE_TIMEOUT_MS) !is GattResult.Ok) return null
        return (awaitAdminResponse(channel, id, now() + ADMIN_TIMEOUT_MS) as? AdminResp.Admin)?.reply
    }

    /** Writes one admin message to [myNode]; when [expectReply], classifies the routing reply (ack / bad key / error). */
    private suspend fun writeAdmin(
        channel: GattChannel,
        myNode: UInt,
        adminBytes: ByteArray,
        expectReply: Boolean = true,
    ): AdminOutcome {
        val id = ids.next()
        val packet =
            OutboundPacket(
                to = myNode,
                channelIndex = 0,
                id = id,
                portnum = MeshtasticProto.PORT_ADMIN,
                payload = adminBytes,
                wantResponse = expectReply,
            )
        if (channel.writeToRadio(MeshtasticProto.encodePacket(packet), WRITE_TIMEOUT_MS) !is GattResult.Ok) return AdminOutcome.Failed
        if (!expectReply) return AdminOutcome.Applied
        return when (val resp = awaitAdminResponse(channel, id, now() + ADMIN_TIMEOUT_MS)) {
            is AdminResp.Routing -> {
                when (resp.reason) {
                    RoutingError.ADMIN_BAD_SESSION_KEY -> AdminOutcome.BadSessionKey
                    RoutingError.NONE -> AdminOutcome.Applied
                    else -> AdminOutcome.Failed
                }
            }

            // Local admin often sends no routing ack; a reboot, an admin echo, or a quiet drain all mean "processed".
            else -> {
                AdminOutcome.Applied
            }
        }
    }

    /**
     * Reads FromRadio (waiting on FromNum notifies) until a packet addressed to us settles the admin request
     * [reqId]: an ADMIN reply, the matching ROUTING outcome, a reboot, or the deadline. Other traffic seen in
     * the window is dropped — provisioning is a rare, brief, user-initiated action.
     */
    private suspend fun awaitAdminResponse(
        channel: GattChannel,
        reqId: UInt,
        deadlineMs: Long,
    ): AdminResp {
        while (now() < deadlineMs) {
            when (val read = channel.readFromRadio(READ_TIMEOUT_MS)) {
                is GattResult.Ok -> {
                    if (read.value.isEmpty()) {
                        val wait = (deadlineMs - now()).coerceAtLeast(0)
                        when (withTimeoutOrNull(wait) { channel.events.receiveCatching().getOrNull() }) {
                            is GattEvent.Notified -> Unit

                            // more to read
                            else -> return AdminResp.None // disconnect, closed, or deadline
                        }
                    } else {
                        matchAdminResponse(read.value, reqId)?.let { return it }
                    }
                }

                else -> {
                    return AdminResp.None
                }
            }
        }
        return AdminResp.None
    }

    /** Classifies one FromRadio against admin request [reqId]; null means "not the reply — keep reading". */
    private fun matchAdminResponse(
        bytes: ByteArray,
        reqId: UInt,
    ): AdminResp? =
        when (val fr = MeshtasticProto.decodeFromRadio(bytes)) {
            FromRadio.Rebooted -> {
                AdminResp.Reboot
            }

            is FromRadio.QueueStatus -> {
                _queue.value = QueueInfo(fr.free, fr.maxlen, now())
                null
            }

            is FromRadio.Packet -> {
                val data = fr.packet.decoded
                when {
                    data == null -> {
                        null
                    }

                    data.portnum == MeshtasticProto.PORT_ADMIN -> {
                        AdminResp.Admin(MeshtasticProto.decodeAdmin(data.payload) ?: AdminReply(null, null))
                    }

                    data.portnum == MeshtasticProto.PORT_ROUTING && data.requestId == reqId -> {
                        AdminResp.Routing(MeshtasticProto.decodeRouting(data.payload) ?: RoutingError.UNKNOWN)
                    }

                    else -> {
                        null
                    }
                }
            }

            else -> {
                null
            }
        }

    private enum class AdminOutcome { Applied, BadSessionKey, Failed }

    private sealed interface AdminResp {
        data class Admin(
            val reply: AdminReply,
        ) : AdminResp

        data class Routing(
            val reason: RoutingError,
        ) : AdminResp

        data object Reboot : AdminResp

        data object None : AdminResp
    }

    // --- heartbeat ---

    private suspend fun heartbeatTicker() {
        while (scope.isActive) {
            delay(HEARTBEAT_MS)
            inbox.send(Cmd.Heartbeat)
        }
    }

    private suspend fun maybeHeartbeat(
        address: String,
        channel: GattChannel,
    ): SessionEnd? {
        if (now() - lastWriteAt < HEARTBEAT_MS) return null
        return when (val write = channel.writeToRadio(MeshtasticProto.encodeHeartbeat(), WRITE_TIMEOUT_MS)) {
            is GattResult.Ok -> {
                lastWriteAt = now()
                null
            }

            else -> {
                classify(address, write) ?: SessionEnd(reason = "heartbeat write $write")
            }
        }
    }

    // --- classification + housekeeping ---

    /** Turns a failed [GattResult] into a terminal pairing state where appropriate, else null (backoff). */
    private fun classify(
        address: String,
        result: GattResult<*>,
    ): SessionEnd? {
        if (result is GattResult.Ok) return null
        val bonded = dialer.bondState(address) == BondState.BONDED
        return when {
            result is GattResult.Failed && (result.status == GATT_AUTH_FAIL) -> {
                SessionEnd(reason = "auth", terminal = if (bonded) LinkState.StaleBond(address) else LinkState.NeedsPairing(address))
            }

            result is GattResult.Failed && (result.status == GATT_INSUFFICIENT_AUTH || result.status == GATT_INSUFFICIENT_ENC) -> {
                SessionEnd(
                    reason = "auth ${result.status}",
                    terminal = if (bonded) LinkState.StaleBond(address) else LinkState.NeedsPairing(address),
                )
            }

            else -> {
                null
            }
        }
    }

    private fun failAllPending(result: SendResult) {
        pending.values.forEach { it.complete(result) }
        pending.clear()
    }

    /** The result of a session: why it ended, whether to keep retrying, and whether the streak should reset. */
    private class SessionEnd(
        val reason: String,
        val terminal: LinkState? = null,
        val resetStreak: Boolean = false,
    )

    private enum class Signal { Continue, Rehandshake, MatchedAwait }

    private sealed interface Cmd {
        class Send(
            val channelIndex: Int,
            val portnum: Int,
            val hopLimit: Int?,
            val payload: ByteArray,
            val reply: CompletableDeferred<SendResult>,
        ) : Cmd

        class Provision(
            val spec: ProvisionSpec,
            val reply: CompletableDeferred<ProvisionResult>,
        ) : Cmd

        data object Heartbeat : Cmd
    }

    private companion object {
        const val BASE_BACKOFF_MS = 5_000L
        const val MAX_BACKOFF_MS = 180_000L
        const val SUBSCRIBE_TIMEOUT_MS = 10_000L
        const val WRITE_TIMEOUT_MS = 10_000L
        const val BONDING_TIMEOUT_MS = 90_000L
        const val READ_TIMEOUT_MS = 30_000L
        const val HANDSHAKE_TIMEOUT_MS = 120_000L
        const val HEARTBEAT_MS = 180_000L
        const val ADMIN_TIMEOUT_MS = 8_000L
        const val MAX_STALE_READS = 32

        // Channel provisioning: Knit takes a free secondary (1..7) and never touches index 0, the board's own
        // primary — which is also what keeps it on the public frequency, where stock nodes relay it (ADR 045).
        const val FIRST_SECONDARY = 1
        const val LAST_SECONDARY = 7
        const val ROLE_DISABLED = 0
        const val MALFORMED_CONFIG = "board sent a config this build cannot read"
        const val MALFORMED_OWNER = "board sent a name this build cannot read"
        const val PACKET_BUFFER = 256
        const val OUTCOME_BUFFER = 64

        // Android BluetoothGatt status codes classified as a bond problem (values match the platform).
        const val GATT_INSUFFICIENT_AUTH = 5
        const val GATT_INSUFFICIENT_ENC = 15
        const val GATT_AUTH_FAIL = 137

        val BLANK_BOARD = BoardInfo(0u, null, null)
    }
}
