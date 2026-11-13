package app.getknit.knit.mesh.lora.meshcore

import app.getknit.knit.mesh.TransportHealth
import app.getknit.knit.mesh.bluetooth.BackoffConfig
import app.getknit.knit.mesh.bluetooth.ConnectBackoffPolicy
import app.getknit.knit.mesh.lora.AckProof
import app.getknit.knit.mesh.lora.BoardAddr
import app.getknit.knit.mesh.lora.BoardAir
import app.getknit.knit.mesh.lora.BoardBattery
import app.getknit.knit.mesh.lora.BoardDialPolicy
import app.getknit.knit.mesh.lora.BoardLink
import app.getknit.knit.mesh.lora.BoardPacket
import app.getknit.knit.mesh.lora.BoardSend
import app.getknit.knit.mesh.lora.BoardState
import app.getknit.knit.mesh.lora.BondSettle
import app.getknit.knit.mesh.lora.BondState
import app.getknit.knit.mesh.lora.DialMode
import app.getknit.knit.mesh.lora.GattResult
import app.getknit.knit.mesh.lora.KnitChannel
import app.getknit.knit.mesh.lora.LinkState
import app.getknit.knit.mesh.lora.PacketIdSource
import app.getknit.knit.mesh.lora.PacketOutcome
import app.getknit.knit.mesh.lora.ProvisionMode
import app.getknit.knit.mesh.lora.ProvisionResult
import app.getknit.knit.mesh.lora.ProvisionSpec
import app.getknit.knit.mesh.lora.QueueInfo
import app.getknit.knit.mesh.lora.RadioParams
import app.getknit.knit.mesh.lora.SendPurpose
import app.getknit.knit.mesh.lora.SendResult
import app.getknit.knit.mesh.power.ElapsedWait
import app.getknit.knit.mesh.runRound
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicReference
import kotlin.random.Random

/**
 * The MeshCore companion client: a pure actor that turns a [MeshcoreDialer] into a [BoardLink] (ADR 2026-11.gsqu,
 * Stage 3b). One driver coroutine per connection owns the open [NusChannel] and issues every command, one at a time, fed
 * by its inbox (sends, setup, the contact plumbing, the ticker); all clocks are injected, so it runs on the JVM under
 * virtual time against `FakeNusChannel` (`MeshcoreSessionTest`). Not reachable from the app until Stage 5.
 *
 * **The board answers every connected client at once** (`MultiSerialInterface`, bench Q8), and the user's own MeshCore
 * app may share it. So, by the binding rules of the 2026-11-13 design passes:
 * - **Drained data is never matched to a request.** A contact text, a channel text or channel data (codes 7, 8, 16, 17,
 *   27) becomes a [BoardPacket] whenever it arrives, whoever's `SYNC_NEXT_MESSAGE` drew it: the firmware pops a message
 *   before it replies, so that reply is the only copy left. None is discarded — the user's other DMs, other slots and
 *   Public too ([MeshcorePacket] says which).
 * - **Answers are matched by content where they have any**: `SENT` by the ack code the phone computes
 *   ([MeshcoreProto.expectedAck]), `SEND_CONFIRMED` by our own table ([MeshcoreAckTable]), `CHANNEL_INFO` by index,
 *   `CONTACT` by key, a stats frame by its kind. A bare `OK` / `ERR` may be anybody's: after a timeout on one, a barrier
 *   (`GET_DEVICE_TIME` → `CURR_TIME`) drains the strays, and every write the plane relies on is verified by reading it
 *   back. Anything else nobody waited on is counted and dropped ([MeshcoreCounters.unexpected]).
 * - **Every frame handler is guarded**: one bad frame never ends the actor.
 *
 * **A compact DM** rides as a contact text DM, `COBS(frame)` ([Cobs]), and is written only after a zero-hop pin that
 * succeeded in this connection ([MeshcorePins]). The session runs its own attempts 0..2 — the same timestamp, a new
 * `attempt` each, since a same-attempt retry is byte-identical and dropped unanswered (Q14) — on the firmware's
 * `est_timeout`, and reports the exact count that flew on the outcome ([PacketOutcome.flown]).
 *
 * Android-free (the only `android.bluetooth.*` is the dialer's, Stage 3c), honouring `.agents/rules/mesh.md`. The
 * reconnect is [BoardDialPolicy]'s, exactly as `MeshtasticSession`'s, and a throw costs one pass of it, never the loop.
 */
@Suppress("LargeClass", "TooManyFunctions") // one actor owns the open channel, so every command it serialises lives here
internal class MeshcoreSession(
    private val dialer: MeshcoreDialer,
    private val scope: CoroutineScope,
    private val backoff: BackoffConfig = BackoffConfig(baseMs = BASE_BACKOFF_MS, maxMs = MAX_BACKOFF_MS),
    private val now: () -> Long,
    private val wallClock: () -> Long,
    private val rand: () -> Double = { Random.nextDouble() },
    private val ids: PacketIdSource = PacketIdSource(Random.nextLong()),
    private val log: (String) -> Unit = {},
    /** How long a send or a contact op may wait for the actor to *start* it; once started it runs to its own end. */
    private val callDeadlineMs: Long = CALL_DEADLINE_MS,
    /** The same for a setup or a restore. */
    private val setupDeadlineMs: Long = SETUP_DEADLINE_MS,
) : BoardLink,
    MeshcoreBoardOps {
    private val _state = MutableStateFlow<BoardState>(down(MeshcoreLinkState.Idle))
    override val state: StateFlow<BoardState> = _state.asStateFlow()

    /**
     * Drained messages, held until collected, so a drain before anyone collects loses nothing the firmware popped. Its
     * contract, which Stage 5 honours: one collector — the plane core — and no more; a second would split them. When that
     * collector subscribes is not part of it: the buffer keeps a drain that beats the subscription (Stage 5a-2). Bounded at
     * [PACKET_BACKLOG]: past it the oldest goes ([MeshcoreCounters.packetsDropped]), and the re-probe
     * and custody repair what it carried, as they repair a reply lost on the BLE link.
     */
    private val _packets =
        Channel<BoardPacket>(PACKET_BACKLOG, BufferOverflow.DROP_OLDEST) { counters.packetsDropped.incrementAndGet() }
    override val packets: Flow<BoardPacket> = _packets.receiveAsFlow()

    private val _outcomes = MutableSharedFlow<PacketOutcome>(extraBufferCapacity = OUTCOME_BUFFER)
    override val outcomes: Flow<PacketOutcome> = _outcomes.asSharedFlow()

    /** Every poll's headroom, never conflated: the pacer reads each one (binding rule "sends" 5). */
    private val _headroom = MutableSharedFlow<Int>(extraBufferCapacity = OUTCOME_BUFFER)
    override val headroom: Flow<Int> = _headroom.asSharedFlow()

    @Volatile
    private var lastHeadroom: Int? = null
    override val headroomNow: Int? get() = lastHeadroom

    private val _battery = MutableStateFlow<BoardBattery?>(null)
    override val battery: StateFlow<BoardBattery?> = _battery.asStateFlow()

    private val _boardAir = MutableStateFlow<BoardAir?>(null)
    override val boardAir: StateFlow<BoardAir?> = _boardAir.asStateFlow()

    private val _air = MutableSharedFlow<MeshcoreAir>(extraBufferCapacity = OUTCOME_BUFFER)

    /** Air the board spent that no send of ours booked ([MeshcoreAir]), for the core to book (Stage 5). */
    override val air: SharedFlow<MeshcoreAir> = _air.asSharedFlow()

    /** Counts only (never a key, a name or a text). */
    override val counters = MeshcoreCounters()

    /**
     * The plane's MeshCore hooks (Stage 5a-2), told on the actor what they must know before anything reads the board as
     * Ready: its key once identified, its radio and band before Ready is published, and every poll's stats. Set once, at
     * the hooks' construction; null in the session's own tests.
     */
    @Volatile
    override var observer: MeshcoreSessionObserver? = null

    private val ackTable = MeshcoreAckTable()
    private val pins = MeshcorePins()
    private val headroomPolicy = MeshcoreHeadroom()
    private val airRing = MeshcoreAirRing()
    private val waits = ElapsedWait(now)

    /** The inbox of the session now Ready, or null: one per session, retired as it ends (`MeshtasticSession`'s rule). */
    private val inbox = AtomicReference<Channel<Cmd>?>(null)
    private val poke = Channel<Unit>(Channel.CONFLATED)
    private var loopJob: Job? = null

    @Volatile
    private var address: String? = null

    /** Anything in flight or queued on the board: the ticker runs at [BUSY_TICK_MS] rather than [IDLE_TICK_MS]. */
    @Volatile
    private var busy = false

    @Volatile
    private var slotReading: MeshcoreSlotReading? = null

    // The reconnect loop's own state, as MeshtasticSession keeps it.
    private var readyAt: Long? = null

    /** Dials in a row that found no NUS service (a timeout in between neither counts nor resets): two are terminal. */
    private var serviceAbsentInRow = 0
    private var lastDirectAt: Long? = null

    /** DM flights by handle; only the actor touches them. */
    private val flights = LinkedHashMap<UInt, Flight>()
    private var lastQueue = 0

    // --- the BoardLink surface ---

    override fun start(address: String) {
        if (this.address == address && loopJob?.isActive == true) return
        stop()
        this.address = address
        serviceAbsentInRow = 0
        loopJob = scope.launch { connectLoop(address) }
    }

    override fun stop() {
        loopJob?.cancel()
        loopJob = null
        address = null
        // The cancelled session's own finally ends its flights; the table goes with the binding.
        ackTable.clear()
        _battery.value = null
        _boardAir.value = null
        lastHeadroom = null
        slotReading = null
        _state.value = down(MeshcoreLinkState.Idle)
    }

    override suspend fun send(
        payload: ByteArray,
        purpose: SendPurpose,
    ): BoardSend {
        // The handle is taken before the post, so every outcome of a started send carries the id the core holds. A send
        // whose deadline passed before the actor started it never ran: it is Busy, so the core requeues it (a Timeout would
        // read as "likely aired" and await an answer for a DM that never left). Timeout stays for a started attempt whose
        // SENT never came.
        val handle = nextHandle()
        val result =
            call(
                { SendResult.NotReady(linkStateOf(_state.value)) },
                { SendResult.Busy },
            ) { c -> doSend(c, payload, purpose, handle) }
        return BoardSend(result, preSend = 0L)
    }

    /**
     * The neutral setup entry, for a caller that holds no MeshCore record: [ProvisionMode.Setup] runs [setup]. A restore
     * needs the record a setup returned, so Stage 5's hooks call [restore] with it; the dedicated-slot modes have no
     * MeshCore form (the radio is never touched, ADR 045).
     */
    override suspend fun provision(spec: ProvisionSpec): ProvisionResult =
        when (spec.mode) {
            ProvisionMode.Setup -> {
                when (val r = setup()) {
                    is MeshcoreSetupResult.Done -> ProvisionResult.Provisioned(r.slot, r.alreadyPresent)
                    MeshcoreSetupResult.NoFreeSlot -> ProvisionResult.NoFreeSlot
                    is MeshcoreSetupResult.Failed -> ProvisionResult.Failed(r.reason)
                    MeshcoreSetupResult.NotReady -> ProvisionResult.NotReady(linkStateOf(_state.value))
                }
            }

            ProvisionMode.SetupDedicated, ProvisionMode.SetupShared -> {
                ProvisionResult.Failed(NO_DEDICATED_SLOT)
            }

            ProvisionMode.Restore -> {
                ProvisionResult.Failed(RESTORE_NEEDS_RECORD)
            }
        }

    // --- the kind's own surface (Stage 5's hooks) ---

    /** Sets the board up for Knit as one transaction: reads first, writes verified by reading back (§4). */
    override suspend fun setup(): MeshcoreSetupResult =
        call({ MeshcoreSetupResult.NotReady }, { MeshcoreSetupResult.Failed(TIMED_OUT, left = null) }, setupDeadlineMs) { c ->
            runSetup(c)
        }

    /** Puts back what [record] says a setup changed, where it is still Knit's to put back (binding rule "contacts" 2). */
    override suspend fun restore(record: MeshcoreSetupRecord): MeshcoreRestoreResult =
        call(
            { MeshcoreRestoreResult.NotReady },
            { MeshcoreRestoreResult.Done(false, emptyList(), listOf(TIMED_OUT)) },
            setupDeadlineMs,
        ) { c ->
            runRestore(c, record)
        }

    /** Writes [key] as a Knit contact (zero-hop, favourite, `Knit xxxx`), only into a free slot; never evicts anyone. */
    suspend fun teach(key: ByteArray): ContactWrite = call({ ContactWrite.NOT_READY }, { ContactWrite.FAILED }) { c -> runTeach(c, key) }

    /** Removes [key]'s contact and reads it gone. */
    suspend fun removeContact(key: ByteArray): ContactWrite =
        call({ ContactWrite.NOT_READY }, { ContactWrite.FAILED }) { c -> runRemove(c, key) }

    /** Resets [key]'s route to flood (`RESET_PATH`) and reads it back; the next DM to it pins it again. */
    suspend fun resetPath(key: ByteArray): ContactWrite =
        call({ ContactWrite.NOT_READY }, { ContactWrite.FAILED }) { c -> runResetPath(c, key) }

    /** Reads [key]'s contact. */
    suspend fun readContact(key: ByteArray): ContactRead =
        call({ ContactRead.NotReady }, { ContactRead.Failed }) { c ->
            readContactOn(c, key)?.let { found -> found.record?.let { ContactRead.Found(it) } ?: ContactRead.NotFound }
                ?: ContactRead.Failed
        }

    /**
     * The keys Knit taught this board, ranked (Stage 5's `MeshcoreContacts`): the targets of a targeted re-pin and the
     * Ready re-assert's order. Any thread.
     */
    fun setKnitContacts(ranked: List<ByteArray>) = pins.setTaught(ranked)

    /** The latest read of the bound slot, or null: `checkSlot` refuses without a recent one (binding rule "contacts" 2). */
    override fun slotReading(): MeshcoreSlotReading? = slotReading

    /**
     * Runs [op] on the Ready session's actor, or answers [notReady] when there is none or it ends first. The deadline
     * ([deadlineMs], or [callDeadlineMs]) bounds only the wait for the actor to *start* it: one not started by then never
     * runs and answers [timedOut]; one already started always gets its own result, since every step inside an op is
     * bounded (each request by its timeout, each barrier by its own, ending the session past it) and a session that ends
     * answers it [notReady]. A caller cut off from a started op would lose its handle, its record or its write.
     */
    private suspend fun <T> call(
        notReady: () -> T,
        timedOut: () -> T,
        deadlineMs: Long = callDeadlineMs,
        op: suspend (Conn) -> T,
    ): T {
        if (_state.value !is BoardState.Ready) return notReady()
        val cmd = Cmd.Op(SessionOp<Conn, T>(op, notReady))
        if (!post(cmd)) return notReady()
        val reply = cmd.op.reply
        val inTime = withTimeoutOrNull(deadlineMs) { reply.await().let { true } } ?: false
        // Claimed and answered in one step: the actor's `refuse` skips an op its deadline took, so it can never answer
        // notReady between the claim and the timeout's answer.
        if (!inTime) cmd.op.claimForTimeout(timedOut)
        return reply.await()
    }

    /** The next handle: taken by any caller's coroutine, so its source is locked. */
    private fun nextHandle(): UInt = synchronized(ids) { ids.next() }

    private suspend fun post(cmd: Cmd): Boolean {
        val box = inbox.get() ?: return false
        return try {
            box.send(cmd)
            true
        } catch (_: ClosedSendChannelException) {
            false
        }
    }

    /** Answers [cmd] as not served, if nothing has answered it yet and its deadline did not take it. */
    private fun refuse(cmd: Cmd) {
        if (cmd is Cmd.Op<*>) cmd.op.refuse()
    }

    // --- the reconnect loop (one coroutine per start), MeshtasticSession's ---

    private suspend fun connectLoop(address: String) {
        var streak = 0
        lastDirectAt = null
        try {
            while (scope.isActive) {
                runRound(onFailure = { e ->
                    log("meshcore link threw: ${e.javaClass.simpleName}")
                    streak = backoffUnlessAdapterOff("$REASON_THREW ${e.javaClass.simpleName}", streak)
                }) {
                    streak = connectOnce(address, streak) ?: return
                }
            }
        } finally {
            retireInbox()
        }
    }

    private suspend fun connectOnce(
        address: String,
        streak: Int,
    ): Int? {
        if (!dialer.adapterOn.value) {
            _state.value = down(MeshcoreLinkState.Unavailable)
            retireInbox()
            dialer.adapterOn.first { it }
            return 0
        }
        // An unbonded board is never left to the controller's background connect: it could not finish a pairing there. Nor
        // is it dialled direct in the background's place (Stage 5a-2, the bluetooth review's B1): a board that is off or out
        // of range would draw a 30-s high-duty dial holding the arbiter every backoff, forever — the #67 scan blackout ADR
        // 2026-09.hp88 exists to stop. So it waits out the background window, its bond re-read meanwhile, and the hourly
        // direct net is its only dial after the first [BoardDialPolicy.DIRECT_ATTEMPTS].
        val mode = BoardDialPolicy.mode(streak, now(), lastDirectAt)
        if (mode == DialMode.Background && dialer.bondState(address) != BondState.BONDED) {
            waitUnbonded(address, streak)
            return streak
        }
        val timeout = armDial(mode, streak)
        readyAt = null
        val result = dialer.dial(address, mode, timeout)
        if (result != NusDialResult.ServiceAbsent && result != NusDialResult.Timeout) serviceAbsentInRow = 0
        return onDialed(address, mode, streak, result)
    }

    /** What one dial's [result] does to the loop: the streak to go on with, or null to end it. */
    private suspend fun onDialed(
        address: String,
        mode: DialMode,
        streak: Int,
        result: NusDialResult,
    ): Int? =
        when (result) {
            is NusDialResult.Opened -> {
                onOpened(address, result, streak)
            }

            NusDialResult.NoHardware -> {
                _state.value = down(MeshcoreLinkState.Unavailable)
                null
            }

            NusDialResult.AdapterOff -> {
                backoffUnlessAdapterOff(REASON_ADAPTER_OFF, streak)
            }

            is NusDialResult.Failed -> {
                backoffAndWait("$REASON_DIAL ${result.phase} ${result.status}", streak)
            }

            // The dial's subscribe was the first protected op and the bond failed there: terminal, as [classify] makes a
            // first write's, never a backoff that would prompt for the PIN again (binding rule "pairing").
            is NusDialResult.BondRefused -> {
                log("meshcore dial bond refused ${result.status}")
                val bonded = dialer.bondState(address) == BondState.BONDED
                _state.value = down(if (bonded) MeshcoreLinkState.StaleBond(address) else MeshcoreLinkState.NeedsPairing(address))
                null
            }

            NusDialResult.Timeout -> {
                if (mode == DialMode.Direct) backoffAndWait(REASON_DIAL_TIMEOUT, streak) else streak
            }

            // Other firmware, or a cache from before a reflash: backed off once, terminal on a second reading in a row
            // (Stage 5a-2, as Meshtastic's `ServiceAbsent`) — `StaleBond` bonded, `NeedsPairing` not. Forgetting the board or
            // picking another is the way out; a redial is not.
            NusDialResult.ServiceAbsent -> {
                onServiceAbsent(address, streak)
            }
        }

    /** One connected session over [opened], then its end: terminal, or a backoff (from zero after a healthy one). */
    private suspend fun onOpened(
        address: String,
        opened: NusDialResult.Opened,
        streak: Int,
    ): Int? {
        val end =
            try {
                sessionWhileAdapterOn(Conn(address, opened.channel))
            } finally {
                opened.channel.close()
            }
        end.terminal?.let { terminal ->
            _state.value = down(terminal)
            return null
        }
        return backoffUnlessAdapterOff(end.reason, if (end.resetStreak) 0 else streak)
    }

    /** No NUS service behind [address]: a backoff the first time, terminal the second in a row (`StaleBond` / `NeedsPairing`). */
    private suspend fun onServiceAbsent(
        address: String,
        streak: Int,
    ): Int? {
        serviceAbsentInRow++
        if (serviceAbsentInRow < SERVICE_ABSENT_TERMINAL) return backoffAndWait("$REASON_DIAL service absent", streak)
        log("meshcore dial: no NUS service twice in a row, terminal")
        val bonded = dialer.bondState(address) == BondState.BONDED
        _state.value = down(if (bonded) MeshcoreLinkState.StaleBond(address) else MeshcoreLinkState.NeedsPairing(address))
        return null
    }

    /**
     * The background window of an unbonded board, dialled by nothing: the state says when the next direct dial is due, and
     * the bond is re-read every [UNBONDED_POLL_MS] (no radio, no arbiter) so a pairing the user finished in the system's
     * settings ends the wait early. A fresh [start] cancels the loop, as it always did.
     */
    private suspend fun waitUnbonded(
        address: String,
        streak: Int,
    ) {
        val until = now() + BoardDialPolicy.backgroundWindowMs(now(), lastDirectAt)
        _state.value = down(MeshcoreLinkState.Disconnected(WAITING_FOR_BOARD, until, streak))
        retireInbox()
        log("meshcore dial waits: unbonded, next direct dial in ${until - now()}ms")
        while (now() < until && dialer.bondState(address) != BondState.BONDED) {
            waits.sleep(minOf(UNBONDED_POLL_MS, until - now()))
        }
    }

    private fun armDial(
        mode: DialMode,
        streak: Int,
    ): Long {
        val timeout =
            when (mode) {
                DialMode.Direct -> {
                    lastDirectAt = now()
                    _state.value = down(MeshcoreLinkState.Connecting)
                    DIRECT_CONNECT_TIMEOUT_MS
                }

                DialMode.Background -> {
                    val window = BoardDialPolicy.backgroundWindowMs(now(), lastDirectAt)
                    _state.value = down(MeshcoreLinkState.Disconnected(WAITING_FOR_BOARD, now() + window, streak))
                    window
                }
            }
        log("meshcore dial mode=$mode timeout=${timeout}ms streak=$streak")
        return timeout
    }

    private suspend fun backoffAndWait(
        reason: String,
        streak: Int,
    ): Int {
        val next = streak + 1
        val wait = ConnectBackoffPolicy.nextDelayMs(next, backoff, rand)
        log("meshcore backoff streak=$next ms=$wait ($reason)")
        _state.value = down(MeshcoreLinkState.Disconnected(reason, now() + wait, next))
        retireInbox()
        delay(wait)
        return next
    }

    private suspend fun backoffUnlessAdapterOff(
        reason: String,
        streak: Int,
    ): Int {
        val from = if (BoardDialPolicy.healthy(readyAt, now())) 0 else streak
        return if (dialer.adapterOn.value) backoffAndWait(reason, from) else from
    }

    // --- one connected session ---

    private suspend fun sessionWhileAdapterOn(c: Conn): SessionEnd =
        coroutineScope {
            val session = async { runSession(c) }
            val off = async { dialer.adapterOn.first { !it } }
            select {
                session.onAwait { it }
                off.onAwait {
                    log("meshcore adapter off mid-session")
                    _state.value = down(MeshcoreLinkState.Unavailable)
                    session.cancelAndJoin()
                    SessionEnd(REASON_ADAPTER_OFF)
                }
            }.also { off.cancel() }
        }

    private suspend fun runSession(c: Conn): SessionEnd {
        var box: Channel<Cmd>? = null
        var ticker: Job? = null
        try {
            handshake(c)?.let { return it }
            box = Channel(Channel.BUFFERED, onUndeliveredElement = ::refuse)
            retire(inbox.getAndSet(box))
            ready(c)
            ticker = scope.launch { ticker(requireNotNull(box)) }
            return sessionLoop(c, box)
        } catch (e: EndSession) {
            return e.end
        } finally {
            ticker?.cancel()
            endAllFlights()
            lastHeadroom = null
            slotReading = null
        }
    }

    private fun retireInbox() = retire(inbox.getAndSet(null))

    private fun retire(box: Channel<Cmd>?) {
        box?.close()
        box?.cancel()
    }

    private suspend fun sessionLoop(
        c: Conn,
        box: Channel<Cmd>,
    ): SessionEnd {
        while (currentCoroutineContext().isActive) {
            // A drain owed (Ready, a MSG_WAITING) runs before the next event: never inside another command's wait. A round
            // is capped ([DRAIN_ROUND_STEPS]); one cut short serves one waiting command before it goes on, so a busy band
            // never starves a send, a retry's tick or a setup.
            if (c.drainWanted) {
                drainRound(c)
                if (c.drainWanted) {
                    box.tryReceive().getOrNull()?.let { serve(c, it) }
                    continue
                }
            }
            busy = flights.isNotEmpty() || headroomPolicy.holding || lastQueue > 0
            // The inbox first: select is biased to its first clause, and frames keep coming on a busy band.
            val next =
                select<Any> {
                    box.onReceive { it }
                    c.channel.frames.onReceiveCatching { it.getOrNull() ?: NusEvent.Disconnected(STATUS_CLOSED) }
                }
            when (next) {
                is NusEvent.Disconnected -> return SessionEnd("$REASON_GATT_DISCONNECT ${next.status}")
                is NusEvent.Frame -> dispatch(c, MeshcoreProto.decode(next.bytes))
                is Cmd -> serve(c, next)
            }
        }
        return SessionEnd(REASON_CANCELLED, terminal = MeshcoreLinkState.Idle)
    }

    private suspend fun serve(
        c: Conn,
        cmd: Cmd,
    ) {
        try {
            when (cmd) {
                is Cmd.Op<*> -> cmd.op.run(c)
                Cmd.Tick -> onTick(c)
            }
        } catch (_: CommandRefused) {
            // One command the link would not carry: refused alone, the session goes on (a send answers TooLarge itself).
            counters.commandsRefused.incrementAndGet()
            log("meshcore command refused: over the link's write size")
        } finally {
            refuse(cmd)
        }
    }

    private suspend fun ticker(box: Channel<Cmd>) {
        while (currentCoroutineContext().isActive) {
            runRound(onFailure = { log("meshcore tick failed: ${it.javaClass.simpleName}") }) {
                waits.receiveWithin(poke, if (busy) BUSY_TICK_MS else IDLE_TICK_MS)
                box.trySend(Cmd.Tick)
            }
        }
    }

    // --- handshake (§4) ---

    /**
     * `DEVICE_QUERY` (app version 3), `APP_START`, every channel slot, the board's clock set forward, the scope override
     * cleared, the default scope read, then stats and battery. Null once the board is Ready to be published.
     */
    private suspend fun handshake(c: Conn): SessionEnd? {
        _state.value = down(MeshcoreLinkState.Handshaking)
        slotReading = null
        _battery.value = null
        _boardAir.value = null
        if (dialer.bondState(c.address) == BondState.BONDING) _state.value = down(MeshcoreLinkState.Bonding)
        identify(c)?.let { return it }
        // Forward only: the firmware refuses a time behind its own with ILLEGAL_ARG, which is benign (its clock is ahead).
        writeAnswered(c, MeshcoreProto.setDeviceTime(wallClock() / MS_PER_SECOND))
        if (writeAnswered(c, MeshcoreProto.clearScopeOverride()) != Answer.OK) log("meshcore scope override not cleared")
        c.defaultScope = ask<McFrame.DefaultScope>(c, MeshcoreProto.getDefaultScope())?.set == true
        poll(c, drain = false)
        ask<McFrame.Battery>(c, MeshcoreProto.getBattery())?.let { onBattery(it.millivolts) }
        return null
    }

    /** `DEVICE_QUERY`, the firmware floor, `APP_START` and every slot: the board's identity, or why the session ends. */
    private suspend fun identify(c: Conn): SessionEnd? {
        val info = ask<McFrame.DeviceInfo>(c, MeshcoreProto.deviceQuery())
        val end =
            when {
                info == null -> {
                    SessionEnd("$REASON_HANDSHAKE device info")
                }

                info.versionCode < MeshcoreProto.MIN_FIRMWARE_VERSION_CODE -> {
                    log("meshcore firmware too old: version code ${info.versionCode}")
                    SessionEnd(REASON_FIRMWARE, terminal = MeshcoreLinkState.FirmwareTooOld(info.versionCode))
                }

                else -> {
                    c.info = info
                    c.self = ask<McFrame.SelfInfo>(c, MeshcoreProto.appStart())
                    // Before the first poll: whose duty law the stats belong to (Stage 5a-2).
                    c.self?.let { observer?.onIdentified(it.key.copyOf()) }
                    when {
                        c.self == null -> SessionEnd("$REASON_HANDSHAKE self info")
                        !scanSlots(c) -> SessionEnd("$REASON_HANDSHAKE channels")
                        else -> null
                    }
                }
            }
        return end
    }

    /** A connection comes up: nothing verified, the Ready re-assert queued, the first drain due, then [publishReady]. */
    private fun ready(c: Conn) {
        headroomPolicy.reset()
        airRing.reset()
        c.reassert.clear()
        c.reassert.addAll(pins.onReady(now()))
        c.drainWanted = true
        c.lastPollAt = now()
        readyAt = now()
        publishReady(c)
        if (c.reassert.isNotEmpty()) poke.trySend(Unit)
    }

    /** Publishes the board as it stands: after the handshake, and again after a setup or a restore moves its slot. */
    private fun publishReady(c: Conn) {
        val self = requireNotNull(c.self)
        val info = requireNotNull(c.info)
        val radio = saneRadio(self)
        val detail =
            MeshcoreDetail(
                publicKey = self.key,
                versionCode = info.versionCode,
                version = info.version,
                defaultScope = c.defaultScope,
                multiAcks = self.multiAcks,
                clientRepeat = info.clientRepeat,
                freqKhz = if (radio != null) self.freqKhz else null,
                contactCapacity = info.maxContactsHalf * 2,
                slots = c.slots,
                namedForKnit = self.name.contentEquals(MeshcoreNames.forKey(self.key).encodeToByteArray()),
                manualAdd = self.manualAdd,
                dialled = c.address,
            )
        val ready =
            BoardState.Ready(
                own = BoardAddr.MeshCore(self.key.copyOf(MeshcoreProto.PREFIX_BYTES)),
                payloadCap = MeshcoreProto.CHANNEL_DATA_CAP,
                radio = radio,
                channel = c.knitSlot,
                detail = detail,
            )
        // The ledger's radio and band before anything reads this board as Ready (Stage 5a-2): the pacer must never run a
        // Ready plane on the kind's 0.1 % fallback, or on the duty of the board before this one.
        observer?.beforeReady(ready)
        _state.value = ready
        log("meshcore ready v=${info.versionCode} slot=${c.knitSlot} radio=${radio != null} scope=${c.defaultScope}")
    }

    /** `SELF_INFO`'s radio when it is a radio (binding rule "client" 5), else null, which the core prices conservatively. */
    private fun saneRadio(self: McFrame.SelfInfo): RadioParams? {
        val sane =
            self.bwHz in MIN_BW_HZ..MAX_BW_HZ && self.sf in MIN_SF..MAX_SF && self.cr in MIN_CR..MAX_CR &&
                self.freqKhz in MIN_FREQ_KHZ..MAX_FREQ_KHZ
        return if (sane) RadioParams(self.sf, self.bwHz.toInt(), self.cr) else null
    }

    /** Reads every slot by index; keeps only (index, empty, is-Knit) of each, and which one is Knit's. */
    private suspend fun scanSlots(c: Conn): Boolean {
        val count = requireNotNull(c.info).maxChannels.coerceIn(1, MAX_SLOTS)
        val slots = ArrayList<MeshcoreSlot>(count)
        for (i in 0 until count) {
            val info = readSlot(c, i) ?: return false
            slots += info
        }
        c.slots = slots
        c.knitSlot = slots.firstOrNull { it.knit }?.index
        // Null with no Knit slot: a reading of another connection's board, or of a slot that is no longer Knit's, never stands.
        slotReading = c.knitSlot?.let { MeshcoreSlotReading(it, knit = true, atMs = now()) }
        return true
    }

    /** One slot, matched by its index; null when the board did not answer it. */
    private suspend fun readSlot(
        c: Conn,
        index: Int,
    ): MeshcoreSlot? {
        val f =
            requestMatched(c, MeshcoreProto.getChannel(index)) { it is McFrame.ChannelInfo && it.index == index }
        val info = f as? McFrame.ChannelInfo ?: return null
        val empty = info.name.isEmpty() && info.secret.all { it.toInt() == 0 }
        val knit = info.name.contentEquals(KnitChannel.NAME.encodeToByteArray()) && info.secret.contentEquals(KnitChannel.PSK)
        return MeshcoreSlot(index, empty, knit)
    }

    // --- the request machinery ---

    /**
     * Writes [bytes]; any failure ends the session ([classify]) but one: a frame longer than the link carries in one write
     * ([MeshcoreProto.MAX_NOTIFY], the `mtu − 3` of the lowest MTU a link opens at, or the channel's own `STATUS_TOO_LONG`)
     * is a refusal of that one command ([CommandRefused]), never a reconnect — a redial would refuse it again, and every
     * reconnect's back-off would starve the rest. The first write waits out a pairing.
     */
    private suspend fun write(
        c: Conn,
        bytes: ByteArray,
    ) {
        checkWritable(bytes)
        val unbonded = dialer.bondState(c.address) != BondState.BONDED
        val timeout = if (unbonded && !c.authenticated) BONDING_TIMEOUT_MS else WRITE_TIMEOUT_MS
        val r = c.channel.write(bytes, timeout)
        when {
            r is GattResult.Ok -> c.authenticated = true
            r is GattResult.Failed && r.status == WRITE_TOO_LONG -> throw CommandRefused()
            else -> throw EndSession(classify(c.address, r, unbonded) ?: SessionEnd("$REASON_WRITE $r"))
        }
    }

    /**
     * Asked of every frame before it is written. The pinned allowlist, at the one place bytes leave (binding rule
     * "contacts" 5): never a key, a PIN, the radio — a frame outside it ends the session. Then the size: a frame over
     * [MeshcoreProto.MAX_NOTIFY] is refused alone ([CommandRefused]).
     */
    private fun checkWritable(bytes: ByteArray) {
        if (!commandAllowed(bytes)) throw EndSession(SessionEnd("$REASON_REFUSED_COMMAND ${bytes.firstOrNull()}"))
        if (bytes.size > MeshcoreProto.MAX_NOTIFY) throw CommandRefused()
    }

    /**
     * The next frame [match] takes within [timeoutMs], dispatching everything else that arrives meanwhile: drained data
     * always (matched or not), pushes, and answers nobody waits for, counted. Null on the timeout.
     */
    private suspend fun await(
        c: Conn,
        timeoutMs: Long,
        match: (McFrame) -> Boolean,
    ): McFrame? {
        val deadline = now() + timeoutMs
        while (true) {
            val left = deadline - now()
            if (left <= 0) return null
            val event =
                try {
                    waits.receiveWithin(c.channel.frames, left)
                } catch (_: ClosedReceiveChannelException) {
                    NusEvent.Disconnected(STATUS_CLOSED)
                } ?: return null
            when (event) {
                is NusEvent.Disconnected -> {
                    throw EndSession(SessionEnd("$REASON_GATT_DISCONNECT ${event.status}"))
                }

                is NusEvent.Frame -> {
                    val f = MeshcoreProto.decode(event.bytes)
                    val matched = runCatching { match(f) }.getOrDefault(false)
                    if (f.isData || !matched) dispatch(c, f)
                    if (matched) return f
                }
            }
        }
    }

    private suspend fun request(
        c: Conn,
        bytes: ByteArray,
        timeoutMs: Long,
        match: (McFrame) -> Boolean,
    ): McFrame? {
        write(c, bytes)
        return await(c, timeoutMs, match)
    }

    /** A request answered by a frame of kind [T] alone (a stats kind, `DEVICE_INFO` …). */
    private suspend inline fun <reified T : McFrame> ask(
        c: Conn,
        bytes: ByteArray,
        timeoutMs: Long = REQUEST_TIMEOUT_MS,
    ): T? = request(c, bytes, timeoutMs) { it is T } as? T

    private enum class Answer { OK, ERR, NONE }

    /**
     * A command answered by a bare `OK` / `ERR`, with [timeoutMs] (at least 3 s for one that writes flash). On a timeout,
     * a barrier drains the strays a late answer would leave behind. [errCode] receives the `ERR`'s code.
     */
    private suspend fun writeAnswered(
        c: Conn,
        bytes: ByteArray,
        timeoutMs: Long = REQUEST_TIMEOUT_MS,
        errCode: (Int) -> Unit = {},
    ): Answer =
        when (val f = request(c, bytes, timeoutMs) { it is McFrame.Ok || it is McFrame.Err }) {
            McFrame.Ok -> {
                Answer.OK
            }

            is McFrame.Err -> {
                errCode(f.code)
                Answer.ERR
            }

            else -> {
                barrier(c)
                Answer.NONE
            }
        }

    /**
     * `GET_DEVICE_TIME`, and everything up to its `CURR_TIME` dispatched as strays (binding rule "client" 2): an answer
     * that lands after its wait ended is never taken for the next command's. A frame [late] accepts on the way is
     * returned. A board that answers not even this is gone: the session ends.
     */
    private suspend fun barrier(
        c: Conn,
        late: (McFrame) -> Boolean = { false },
    ): McFrame? {
        var caught: McFrame? = null
        write(c, MeshcoreProto.getDeviceTime())
        while (true) {
            val f = await(c, BARRIER_TIMEOUT_MS) { (caught == null && late(it)) || it is McFrame.CurrTime }
            when (f) {
                null -> throw EndSession(SessionEnd(REASON_BARRIER))
                is McFrame.CurrTime -> return caught
                else -> caught = f
            }
        }
    }

    /**
     * A request whose answer has content to match it by ([success]: a `SENT` by its code, a `CONTACT` by key, a
     * `CHANNEL_INFO` by index, `CONTACTS_START`), where a bare `ERR` may be anybody's. An `ERR` is provisional: a barrier
     * follows, and the firmware answers in order, so our success arriving before its `CURR_TIME` makes the `ERR` another
     * client's (binding: review of Stage 3b). Null when nothing answered.
     */
    private suspend fun requestMatched(
        c: Conn,
        bytes: ByteArray,
        success: (McFrame) -> Boolean,
    ): McFrame? {
        val first = request(c, bytes, REQUEST_TIMEOUT_MS) { success(it) || it is McFrame.Err }
        if (first != null && first !is McFrame.Err) return first
        // Every ERR up to the barrier's CURR_TIME is judged together, as writeJudged does: our success wins outright, one
        // ERR is ours, and more than one is ambiguous — read as no answer (null), never as a refusal.
        val errs = mutableListOf<McFrame.Err>().apply { first?.let(::add) }
        var caught: McFrame? = null
        write(c, MeshcoreProto.getDeviceTime())
        while (true) {
            val f =
                await(c, BARRIER_TIMEOUT_MS) { (caught == null && success(it)) || it is McFrame.Err || it is McFrame.CurrTime }
                    ?: throw EndSession(SessionEnd(REASON_BARRIER))
            when (f) {
                is McFrame.CurrTime -> break
                is McFrame.Err -> errs += f
                else -> caught = f
            }
        }
        return judge(caught, errs)
    }

    /** [caught], our success, wins; else one ERR is ours; more than one is ambiguous, read as no answer. */
    private fun judge(
        caught: McFrame?,
        errs: List<McFrame.Err>,
    ): McFrame? {
        if (caught != null) {
            // Every ERR was another client's; a TABLE_FULL among them is the pool's state all the same.
            errs.forEach(::onStrayErr)
            return caught
        }
        if (errs.size > 1) {
            counters.ambiguousAnswers.incrementAndGet()
            if (errs.any { it.code == MeshcoreProto.ERR_TABLE_FULL }) emitHeadroom(headroomPolicy.onTableFull())
        }
        return errs.singleOrNull()
    }

    /** One own post's bare answer, judged after a barrier ([writeJudged]). */
    private class Judged(
        val answer: Answer,
        val errCode: Int = 0,
    )

    /**
     * Writes a post whose only answer is a bare `OK` / `ERR`, and a barrier straight after it; every `OK` / `ERR` seen
     * before the barrier's `CURR_TIME` is judged together. Exactly one is ours; anything else is ambiguous and reads as
     * [Answer.NONE] (the post may have gone), never as a refusal. The others are counted as strays.
     */
    private suspend fun writeJudged(
        c: Conn,
        bytes: ByteArray,
    ): Judged {
        write(c, bytes)
        write(c, MeshcoreProto.getDeviceTime())
        val seen = ArrayList<McFrame>()
        while (true) {
            val f =
                await(c, BARRIER_TIMEOUT_MS) { it is McFrame.Ok || it is McFrame.Err || it is McFrame.CurrTime }
                    ?: throw EndSession(SessionEnd(REASON_BARRIER))
            if (f is McFrame.CurrTime) break
            seen += f
        }
        if (seen.size > 1) {
            counters.strayAnswers.addAndGet(seen.size - 1L)
            if (seen.any { it is McFrame.Err && it.code == MeshcoreProto.ERR_TABLE_FULL }) emitHeadroom(headroomPolicy.onTableFull())
        }
        return when (val only = seen.singleOrNull()) {
            McFrame.Ok -> Judged(Answer.OK)
            is McFrame.Err -> Judged(Answer.ERR, only.code)
            else -> Judged(Answer.NONE)
        }
    }

    // --- dispatch: everything that is not the answer waited for ---

    private suspend fun dispatch(
        c: Conn,
        f: McFrame,
    ) {
        runRound(onFailure = { e ->
            counters.handlerFailures.incrementAndGet()
            log("meshcore frame handler threw: ${e.javaClass.simpleName}")
        }) {
            handle(c, f)
        }
    }

    @Suppress("CyclomaticComplexMethod") // one arm per frame kind the board may push or answer unasked
    private fun handle(
        c: Conn,
        f: McFrame,
    ) {
        when (f) {
            is McFrame.ContactMessage -> onContactMessage(c, f)
            is McFrame.ChannelMessage -> onChannelMessage(c, f)
            is McFrame.ChannelData -> onChannelData(c, f)
            is McFrame.SendConfirmed -> onConfirmed(f)
            is McFrame.PathUpdated -> onPathPush(c, f.key, deleted = false)
            is McFrame.ContactDeleted -> onPathPush(c, f.key, deleted = true)
            McFrame.MsgWaiting -> c.drainWanted = true
            is McFrame.Advert -> counters.adverts.incrementAndGet()
            is McFrame.Push, McFrame.ContactsFull -> counters.otherPushes.incrementAndGet()
            is McFrame.Rejected -> counters.rejected.incrementAndGet()
            is McFrame.Sent -> onForeignSent(f)
            McFrame.NoMore -> onNoMore(c)
            is McFrame.Err -> onStrayErr(f)
            else -> counters.unexpected.incrementAndGet()
        }
    }

    /** Another client's `SENT`: it took a slot of the board's ack table. Its flood flag is not our breach. */
    private fun onForeignSent(f: McFrame.Sent) {
        counters.unexpected.incrementAndGet()
        ackTable.onSent(f.expectedAck, null, now(), ackWindow(f.estTimeoutMs))
    }

    /** An `ERR` nobody of ours waited on: a `TABLE_FULL` is the pool full whoever drew it, so the headroom holds. */
    private fun onStrayErr(f: McFrame.Err) {
        counters.unexpected.incrementAndGet()
        if (f.code == MeshcoreProto.ERR_TABLE_FULL) emitHeadroom(headroomPolicy.onTableFull())
    }

    /** A `NO_MORE` nobody of ours waited on: the board's queue was empty then, so what follows is live. */
    private fun onNoMore(c: Conn) {
        counters.unexpected.incrementAndGet()
        c.live = true
    }

    private fun onContactMessage(
        c: Conn,
        f: McFrame.ContactMessage,
    ) {
        val decoded = if (f.txtType == MeshcoreProto.TXT_TYPE_PLAIN) Cobs.decode(f.text) else null
        val kind =
            MeshcorePacket(MeshcorePacket.Type.CONTACT_TEXT, null, f.pathLen, f.txtType, null, f.timestamp, c.live, decoded != null, f.text)
        emitPacket(
            BoardPacket(
                from = BoardAddr.MeshCore(f.prefix),
                toUs = true,
                channel = DM_CHANNEL,
                payload = decoded ?: f.text,
                rxSnr = f.snr,
                rxRssi = null,
                hopsAway = hopsOf(f.pathLen),
                rxTimeMs = null,
                kind = kind,
            ),
        )
        // A flooded plain or signed-plain DM drew one flooded PATH return from our board (`BaseChatMesh.cpp:244-255`,
        // `:291-304`); one replayed from the offline queue aired while we were away.
        if (c.live && !kind.direct && f.txtType in ACKED_TXT_TYPES) _air.tryEmit(MeshcoreAir.PathReturn(now()))
    }

    private fun onChannelMessage(
        c: Conn,
        f: McFrame.ChannelMessage,
    ) {
        val kind = MeshcorePacket(MeshcorePacket.Type.CHANNEL_TEXT, f.slot, f.pathLen, f.txtType, null, f.timestamp, c.live, false, f.text)
        emitPacket(BoardPacket(null, false, f.slot, f.text, f.snr, null, hopsOf(f.pathLen), null, kind))
    }

    private fun onChannelData(
        c: Conn,
        f: McFrame.ChannelData,
    ) {
        val kind = MeshcorePacket(MeshcorePacket.Type.CHANNEL_DATA, f.slot, f.pathLen, null, f.dataType, null, c.live, false, null)
        emitPacket(BoardPacket(null, false, f.slot, f.data, f.snr, null, hopsOf(f.pathLen), null, kind))
    }

    /** Never dropped: the channel is unbounded and outlives every connection of this session. */
    private fun emitPacket(packet: BoardPacket) {
        _packets.trySend(packet)
    }

    private fun hopsOf(pathLen: Int): Int? = if (pathLen == MeshcoreProto.PATH_UNKNOWN) null else pathLen and MeshcoreProto.PATH_HOPS_MASK

    /** A `0x81` / `0x8F` for [key]: unverified, and a targeted, rate-limited job when Knit taught it (binding rule "sends" 2). */
    private fun onPathPush(
        c: Conn,
        key: ByteArray,
        deleted: Boolean,
    ) {
        val addr = MeshcorePins.addrOf(key) ?: return
        flights.values.filter { it.to == addr }.forEach { it.repinNeeded = true }
        when (pins.onPush(key, deleted, now())) {
            MeshcorePins.PushAction.IGNORED -> counters.foreignPathPushes.incrementAndGet()
            MeshcorePins.PushAction.MARKED -> counters.repinsCoalesced.incrementAndGet()
            MeshcorePins.PushAction.QUEUED -> poke.trySend(Unit)
        }
        if (c.reassert.isNotEmpty()) poke.trySend(Unit)
    }

    // --- the drain ---

    /**
     * `SYNC_NEXT_MESSAGE` until `NO_MORE`, lossy: a reply lost on the way is repaired by the re-probe and custody (Q3),
     * and one that lands after its wait is dispatched like any data. `NO_MORE` ends the round only when no reply of
     * ours is still owed; the first one of a connection makes what follows live. At most [DRAIN_ROUND_STEPS] replies a
     * round: one that ends without `NO_MORE` leaves the drain owed, for the loop to go on with after one command.
     */
    private suspend fun drainRound(c: Conn) {
        c.drainWanted = false
        if (!c.authenticated) return
        var owed = 0
        c.drainWanted = true // owed until a NO_MORE ends the round, or a lost reply does
        repeat(DRAIN_ROUND_STEPS) {
            if (owed == 0) {
                write(c, MeshcoreProto.syncNextMessage())
                owed++
            }
            val f = await(c, SYNC_TIMEOUT_MS) { answer -> answer.isData || answer == McFrame.NoMore }
            if (f == null) {
                c.drainWanted = false // a lost reply ends the round: the next poll's round goes on (Q3)
                return
            }
            owed = (owed - 1).coerceAtLeast(0)
            if (f == McFrame.NoMore) {
                c.live = true
                if (owed == 0) {
                    c.drainWanted = false
                    return
                }
            }
        }
    }

    // --- polling ---

    /**
     * CORE (`outbound_queue`, uptime, battery), PACKETS (`sent`) and RADIO (the air ring) stats, then one drain round
     * ([drain]): a reply or a push lost to the board's four-frame BLE queue is picked up here (binding rule "sends" 6).
     */
    private suspend fun poll(
        c: Conn,
        drain: Boolean = true,
    ) {
        val core = ask<McFrame.StatsCore>(c, MeshcoreProto.getStats(MeshcoreProto.STATS_CORE))
        val packets = ask<McFrame.StatsPackets>(c, MeshcoreProto.getStats(MeshcoreProto.STATS_PACKETS))
        if (core != null) {
            val last = c.lastUptime
            if (last != null && core.uptimeSecs < last) {
                log("meshcore board uptime went backwards: ack table dropped")
                ackTable.clear()
                airRing.reset()
            }
            c.lastUptime = core.uptimeSecs
            lastQueue = core.outboundQueue.coerceIn(0, MeshcoreHeadroom.POOL)
            emitHeadroom(headroomPolicy.onPoll(core.outboundQueue, packets?.sent))
            onBattery(core.batteryMv)
        }
        ask<McFrame.StatsRadio>(c, MeshcoreProto.getStats(MeshcoreProto.STATS_RADIO))?.let { radio ->
            airRing.onSample(now(), radio.txAirSecs, radio.rxAirSecs, core?.uptimeSecs)?.let {
                _boardAir.value = BoardAir(it.channelUtilPercent, it.airUtilTxPercent, atMs = now())
            }
            // The duty law's sample (Stage 5a-2): the board's own uptime and transmit counter, read in one poll.
            core?.let { observer?.onRadioStats(wallClock(), it.uptimeSecs, radio.txAirSecs) }
        }
        c.lastPollAt = now()
        if (drain) drainRound(c)
    }

    private fun emitHeadroom(free: Int) {
        lastHeadroom = free
        _headroom.tryEmit(free)
    }

    /** A board reports millivolts alone: the voltage, never a guessed charge. */
    private fun onBattery(millivolts: Int) {
        if (millivolts in MIN_BATTERY_MV..MAX_BATTERY_MV) {
            _battery.value = BoardBattery(percent = null, voltage = millivolts / MV_PER_VOLT, powered = false)
        }
    }

    private suspend fun onTick(c: Conn) {
        flightsDue(c)
        if (now() - c.lastPollAt >= if (busy) BUSY_POLL_MS else IDLE_POLL_MS) poll(c)
        runOneJob(c)
    }

    /** One low-priority contact job — the Ready re-assert first, then a targeted re-pin — under sends and the drain. */
    private suspend fun runOneJob(c: Conn) {
        val reassert = c.reassert.removeFirstOrNull()
        if (reassert != null) {
            pinKey(c, reassert)
        } else {
            val (key, job) = pins.nextJob() ?: return
            when (job) {
                MeshcorePins.Job.PIN -> pinKey(c, key)
                MeshcorePins.Job.TEACH -> runTeach(c, key)
            }
        }
        if (c.reassert.isNotEmpty() || pins.hasJobs()) poke.trySend(Unit)
    }

    // --- sends ---

    private suspend fun doSend(
        c: Conn,
        payload: ByteArray,
        purpose: SendPurpose,
        handle: UInt,
    ): SendResult {
        val result =
            try {
                when (purpose) {
                    is SendPurpose.KnitFrame -> {
                        sendRoom(c, payload, purpose.channel, handle)
                    }

                    is SendPurpose.DirectDm -> {
                        (purpose.to as? BoardAddr.MeshCore)?.let { sendDirect(c, payload, it, handle) }
                            ?: unsupported()
                    }

                    SendPurpose.PublicPost -> {
                        sendPublic(c, payload, handle)
                    }

                    is SendPurpose.AutoReply, is SendPurpose.Admin -> {
                        unsupported()
                    }
                }
            } catch (_: CommandRefused) {
                // The link would not carry this one write: the frame is too large for it, and only it is refused.
                counters.commandsRefused.incrementAndGet()
                SendResult.TooLarge
            }
        busy = flights.isNotEmpty() || headroomPolicy.holding || lastQueue > 0 || result is SendResult.Queued
        poke.trySend(Unit)
        return result
    }

    private fun unsupported(): SendResult = SendResult.Rejected(0u, MeshcoreRefusal.UNSUPPORTED)

    /**
     * An own room post: `SEND_CHANNEL_DATA` on the bound slot, flooded, tagged [MeshcoreProto.KNIT_DATA_TYPE]. The slot
     * is re-read when its last read is older than [SLOT_TTL_MS] and refused when it no longer holds Knit's channel.
     */
    private suspend fun sendRoom(
        c: Conn,
        payload: ByteArray,
        slot: Int,
        handle: UInt,
    ): SendResult {
        val bytes = MeshcoreProto.sendChannelData(slot, payload) ?: return SendResult.TooLarge
        if (headroomPolicy.holding) return SendResult.Busy
        val reading = slotReading
        if (reading == null || reading.index != slot || now() - reading.atMs > SLOT_TTL_MS) {
            // A slot nobody can vouch for is refused (ADR 2026-11.gsqu's Stage 1c amendment): an unread one too, though
            // only a read that came back is kept, so the next post reads it again.
            val read = readSlot(c, slot) ?: return SendResult.Rejected(0u, MeshcoreRefusal.SLOT_NOT_KNIT)
            slotReading = MeshcoreSlotReading(slot, read.knit, now())
        }
        if (slotReading?.knit != true) return SendResult.Rejected(0u, MeshcoreRefusal.SLOT_NOT_KNIT)
        val judged = writeJudged(c, bytes)
        return when {
            judged.answer == Answer.OK -> {
                queued(handle)
            }

            // `SEND_CHANNEL_DATA`'s NOT_FOUND is a slot index the board no longer has (`MyMesh.cpp:1225`), never a missing
            // contact: the slot is refused, and read again before the next post.
            judged.answer == Answer.ERR && judged.errCode == MeshcoreProto.ERR_NOT_FOUND -> {
                slotReading = null
                SendResult.Rejected(handle, MeshcoreRefusal.SLOT_NOT_KNIT)
            }

            judged.answer == Answer.ERR -> {
                onSendErr(handle, judged.errCode, notFoundIsBusy = false)
            }

            else -> {
                SendResult.Timeout(handle)
            }
        }
    }

    /**
     * A post on the board's Public slot (0) as a channel text: the firmware prefixes the board's name and trims the whole
     * to 160 B, so the encoder refuses one it would trim. A `NOT_FOUND` here is no free packet, not a missing slot.
     */
    private suspend fun sendPublic(
        c: Conn,
        payload: ByteArray,
        handle: UInt,
    ): SendResult {
        val name = requireNotNull(c.self).name
        val bytes = MeshcoreProto.sendChannelText(PUBLIC_SLOT, wallClock() / MS_PER_SECOND, payload, name) ?: return SendResult.TooLarge
        if (headroomPolicy.holding) return SendResult.Busy
        val judged = writeJudged(c, bytes)
        return when (judged.answer) {
            Answer.OK -> queued(handle)
            Answer.ERR -> onSendErr(handle, judged.errCode, notFoundIsBusy = true)
            Answer.NONE -> SendResult.Timeout(handle)
        }
    }

    private fun queued(handle: UInt): SendResult =
        SendResult.Queued(handle, QueueInfo(lastHeadroom ?: MeshcoreHeadroom.POOL, MeshcoreHeadroom.POOL, now()))

    /** A send's `ERR`, read by context (binding rule: misleading errors). */
    private fun onSendErr(
        handle: UInt,
        code: Int,
        notFoundIsBusy: Boolean,
    ): SendResult =
        when {
            code == MeshcoreProto.ERR_TABLE_FULL || (notFoundIsBusy && code == MeshcoreProto.ERR_NOT_FOUND) -> {
                emitHeadroom(headroomPolicy.onTableFull())
                SendResult.Busy
            }

            code == MeshcoreProto.ERR_ILLEGAL_ARG -> {
                SendResult.TooLarge
            }

            else -> {
                SendResult.Rejected(handle, code)
            }
        }

    /**
     * A compact DM to [to]: refused unless the session holds its key and a pin succeeded in this connection (written now
     * if not), then attempt 0. The answer is the first attempt's; the rest are the flight's ([flightsDue]).
     */
    private suspend fun sendDirect(
        c: Conn,
        payload: ByteArray,
        to: BoardAddr.MeshCore,
        handle: UInt,
    ): SendResult {
        if (payload.isEmpty() || payload.size > MeshcoreProto.DIRECT_SEAL_CAP) return SendResult.TooLarge
        val key = pins.keyFor(to) ?: return SendResult.Rejected(0u, MeshcoreRefusal.NO_RECORD)
        if (ackTable.full(now()) || headroomPolicy.holding) return SendResult.Busy
        if (!pins.isVerified(to) && !pinKey(c, key)) return SendResult.Rejected(handle, MeshcoreRefusal.UNPINNED)
        val flight = Flight(handle, to, key, Cobs.encode(payload), wallClock() / MS_PER_SECOND, startedAt = now())
        val result = attempt(c, flight)
        if (result is SendResult.Queued || result is SendResult.Timeout) flights[handle] = flight
        return result
    }

    /**
     * Writes [flight]'s next attempt and reads its `SENT` by the ack code the phone computes, so another client's `SENT`
     * is never taken for it. A `SENT` that never comes still leaves the attempt possibly airing ([SendResult.Timeout]).
     */
    private suspend fun attempt(
        c: Conn,
        flight: Flight,
    ): SendResult {
        val self = requireNotNull(c.self)
        val n = flight.attemptsSent
        val bytes = MeshcoreProto.sendText(flight.to.prefix, n, flight.timestamp, flight.text) ?: return SendResult.TooLarge
        val code = MeshcoreProto.expectedAck(flight.timestamp, n, flight.text, self.key)
        val answer = requestMatched(c, bytes) { it is McFrame.Sent && it.expectedAck == code }
        return when (answer) {
            is McFrame.Sent -> {
                onAttemptSent(flight, code, answer.estTimeoutMs, answer.flood)
                SendResult.Queued(flight.handle, QueueInfo(lastHeadroom ?: MeshcoreHeadroom.POOL, MeshcoreHeadroom.POOL, now()))
            }

            is McFrame.Err -> {
                if (answer.code == MeshcoreProto.ERR_NOT_FOUND) pins.unverify(flight.to)
                onSendErr(flight.handle, answer.code, notFoundIsBusy = false)
            }

            else -> {
                // It may be on the board: counted as flown, its code kept, so a confirmation still finds it.
                onAttemptSent(flight, code, DEFAULT_EST_TIMEOUT_MS, flood = false)
                SendResult.Timeout(flight.handle)
            }
        }
    }

    private fun onAttemptSent(
        flight: Flight,
        code: Int,
        estTimeoutMs: Long,
        flood: Boolean,
    ) {
        flight.attemptsSent++
        flight.attemptAt = now()
        flight.estTimeoutMs = estTimeoutMs.coerceIn(MIN_EST_TIMEOUT_MS, MAX_EST_TIMEOUT_MS)
        flight.deferredSince = null
        ackTable.onSent(code, flight.handle, now(), ackWindow(flight.estTimeoutMs))
        if (flood) {
            // Our pin did not hold: this airing flooded, and no further attempt goes until a fresh pin is read back.
            counters.directFlooded.incrementAndGet()
            log("meshcore direct flooded")
            pins.unverify(flight.to)
            flight.repinNeeded = true
            _air.tryEmit(MeshcoreAir.DirectFlooded(flight.handle, flight.text.size, now()))
        }
    }

    /** A `SEND_CONFIRMED` for a code of ours ends that DM's flight, whichever attempt it confirms; a late one too. */
    private fun onConfirmed(f: McFrame.SendConfirmed) {
        val handle = ackTable.onConfirmed(f.ack) ?: return
        val flight = flights.remove(handle)
        if (flight == null) {
            // The flight already ended Unreachable or with no outcome: the core reads a late ack as the board having taken it.
            _outcomes.tryEmit(PacketOutcome.Acked(handle, byDestination = true, proof = AckProof.VALID))
            return
        }
        _outcomes.tryEmit(PacketOutcome.Acked(handle, byDestination = true, proof = AckProof.VALID, flown = flight.attemptsSent))
    }

    /** Retries whose attempt timed out, each after a fresh pin where its route may have moved; the last one ends it. */
    private suspend fun flightsDue(c: Conn) {
        for (flight in flights.values.toList()) {
            when {
                flight.handle !in flights -> Unit

                // The session's own bound, which the core's deadline backs (Stage 5).
                now() - flight.startedAt >= MAX_FLIGHT_MS -> endFlight(flight, unreachable = true)

                now() >= flight.attemptAt + flight.estTimeoutMs + RETRY_SLACK_MS -> advance(c, flight)
            }
        }
    }

    /** One due [flight]: ended after its last attempt or a failed re-pin, held behind a full table or pool, or retried. */
    private suspend fun advance(
        c: Conn,
        flight: Flight,
    ) {
        val needsPin = flight.repinNeeded || !pins.isVerified(flight.to)
        when {
            flight.attemptsSent >= ATTEMPTS -> endFlight(flight, unreachable = true)
            needsPin && !pinKey(c, flight.key) -> endFlight(flight, unreachable = true)
            ackTable.full(now()) || headroomPolicy.holding -> hold(flight)
            else -> retry(c, flight)
        }
    }

    /** A due retry held behind a full ack table or pool; past [DEFER_LIMIT_MS] the DM ends. */
    private fun hold(flight: Flight) {
        flight.repinNeeded = false // pinned (or never needing it) by now: the hold does not repeat the pin
        val since = flight.deferredSince ?: now().also { flight.deferredSince = it }
        if (now() - since > DEFER_LIMIT_MS) endFlight(flight, unreachable = true)
    }

    private suspend fun retry(
        c: Conn,
        flight: Flight,
    ) {
        flight.repinNeeded = false
        val result =
            try {
                attempt(c, flight)
            } catch (_: CommandRefused) {
                counters.commandsRefused.incrementAndGet()
                SendResult.TooLarge
            }
        when (result) {
            is SendResult.Queued, is SendResult.Timeout -> Unit
            SendResult.Busy -> flight.deferredSince = flight.deferredSince ?: now()
            else -> endFlight(flight, unreachable = true)
        }
    }

    private fun endFlight(
        flight: Flight,
        unreachable: Boolean,
    ) {
        flights.remove(flight.handle) ?: return
        val outcome =
            if (unreachable) {
                pins.unverify(flight.to) // re-pin after any Unreachable
                PacketOutcome.Unreachable(flight.handle, flown = flight.attemptsSent)
            } else {
                PacketOutcome.NoOutcome(flight.handle, flown = flight.attemptsSent)
            }
        _outcomes.tryEmit(outcome)
    }

    /** The session ended: every flight ends with no outcome; its codes stay in the table for a confirmation after a reconnect. */
    private fun endAllFlights() {
        flights.values.toList().forEach { endFlight(it, unreachable = false) }
        busy = false
    }

    private fun ackWindow(estTimeoutMs: Long): Long = estTimeoutMs.coerceIn(MIN_EST_TIMEOUT_MS, MAX_EST_TIMEOUT_MS) + RETRY_SLACK_MS

    // --- contacts: the pin and the plumbing ---

    /** What a contact read found: [record] null for a key the board does not hold. */
    private class Found(
        val record: ContactRecord?,
    )

    /** `GET_CONTACT_BY_KEY`, matched by key; null when nothing answered (after the barrier). */
    private suspend fun readContactOn(
        c: Conn,
        key: ByteArray,
    ): Found? {
        val bytes = MeshcoreProto.getContactByKey(key) ?: return Found(null)
        pins.learn(key)
        return when (val f = requestMatched(c, bytes) { it is McFrame.Contact && it.record.key.contentEquals(key) }) {
            is McFrame.Contact -> Found(f.record)
            is McFrame.Err -> if (f.code == MeshcoreProto.ERR_NOT_FOUND) Found(null) else null
            else -> null
        }
    }

    /**
     * Pins [key]'s contact zero-hop: a read-modify-write of the 144-B form, skipped when it already reads 0, verified by
     * reading it back. False — counted, and the DM refused — when the contact is missing or anything fails.
     */
    private suspend fun pinKey(
        c: Conn,
        key: ByteArray,
    ): Boolean {
        val addr = pins.learn(key) ?: return false
        val pinned = pin(c, key)
        if (pinned) pins.onPinned(addr) else counters.pinFailures.incrementAndGet()
        return pinned
    }

    private suspend fun pin(
        c: Conn,
        key: ByteArray,
    ): Boolean {
        val record = readContactOn(c, key)?.record ?: return false
        if (record.outPathLen == 0) return true
        if (writeAnswered(c, MeshcoreProto.addUpdateContact(record.pinned(), withPosition = true), FLASH_TIMEOUT_MS) !=
            Answer.OK
        ) {
            return false
        }
        return readContactOn(c, key)?.record?.outPathLen == 0
    }

    /**
     * `GET_CONTACTS` past every `lastmod`: the board's contact count, or null when it would not say. The firmware's
     * contacts iterator is one for every client (`MyMesh.cpp:1238-1257`): while another client lists, ours is refused
     * `ERR BAD_STATE` and retried after a backoff; and every client sees our `CONTACTS_START` / `END_OF_CONTACTS` pair.
     */
    private suspend fun countContacts(c: Conn): Long? {
        repeat(COUNT_ATTEMPTS) { n ->
            when (val f = requestMatched(c, MeshcoreProto.countContacts()) { it is McFrame.ContactsStart }) {
                is McFrame.ContactsStart -> {
                    // The iterator then walks every slot and lists none; its end frees it for anyone else.
                    await(c, REQUEST_TIMEOUT_MS) { it == McFrame.EndOfContacts }
                    return f.count
                }

                is McFrame.Err -> {
                    if (f.code != MeshcoreProto.ERR_BAD_STATE) return null
                }

                else -> {
                    return null
                }
            }
            waits.sleep(COUNT_BACKOFF_MS shl n)
        }
        return null
    }

    private suspend fun runTeach(
        c: Conn,
        key: ByteArray,
    ): ContactWrite {
        val addr = MeshcorePins.addrOf(key) ?: return ContactWrite.FAILED
        val existing = readContactOn(c, key) ?: return ContactWrite.FAILED
        if (existing.record != null) return ContactWrite.ALREADY_PRESENT
        val capacity = requireNotNull(c.info).maxContactsHalf * 2
        val count = countContacts(c) ?: return ContactWrite.FAILED
        if (count >= capacity) {
            log("meshcore teach refused: no free contact slot")
            return ContactWrite.NO_FREE_SLOT
        }
        val write = MeshcoreProto.addUpdateContact(ContactRecord.knit(key, MeshcoreNames.forKey(key)), withPosition = false)
        if (writeAnswered(c, write, FLASH_TIMEOUT_MS) != Answer.OK) return ContactWrite.FAILED
        val back = readContactOn(c, key)?.record ?: return ContactWrite.FAILED
        if (back.outPathLen != 0) return ContactWrite.FAILED
        pins.noteTaught(key)
        pins.onPinned(addr)
        return ContactWrite.WRITTEN
    }

    private suspend fun runRemove(
        c: Conn,
        key: ByteArray,
    ): ContactWrite {
        val bytes = MeshcoreProto.removeContact(key) ?: return ContactWrite.FAILED
        var err = 0
        when (writeAnswered(c, bytes, FLASH_TIMEOUT_MS) { err = it }) {
            Answer.OK -> Unit
            Answer.ERR -> return if (err == MeshcoreProto.ERR_NOT_FOUND) ContactWrite.NOT_FOUND else ContactWrite.FAILED
            Answer.NONE -> return ContactWrite.FAILED
        }
        MeshcorePins.addrOf(key)?.let(pins::unverify)
        return if (readContactOn(c, key)?.let { it.record == null } == true) ContactWrite.WRITTEN else ContactWrite.FAILED
    }

    private suspend fun runResetPath(
        c: Conn,
        key: ByteArray,
    ): ContactWrite {
        val bytes = MeshcoreProto.resetPath(key) ?: return ContactWrite.FAILED
        var err = 0
        when (writeAnswered(c, bytes) { err = it }) {
            Answer.OK -> Unit
            Answer.ERR -> return if (err == MeshcoreProto.ERR_NOT_FOUND) ContactWrite.NOT_FOUND else ContactWrite.FAILED
            Answer.NONE -> return ContactWrite.FAILED
        }
        MeshcorePins.addrOf(key)?.let(pins::unverify)
        return if (readContactOn(c, key)?.record?.outPathLen == MeshcoreProto.PATH_UNKNOWN) ContactWrite.WRITTEN else ContactWrite.FAILED
    }

    // --- setup and restore (§4) ---

    /**
     * Reads first — `DEVICE_INFO`, `SELF_INFO`, every slot, the default scope (reported, never written), the auto-add
     * config — and refuses with nothing written when any fails or no slot is free. Then the writes, each read back:
     * auto-add (chat and overwrite-oldest off), `[38, 1]`, the `Knit abcd` name, and the channel last. A failure puts
     * back what was written ([runRestore]). The radio is never touched (ADR 045).
     */
    private suspend fun runSetup(c: Conn): MeshcoreSetupResult {
        val plan =
            when (val read = setupReads(c)) {
                is SetupRead.Planned -> read.plan
                is SetupRead.Refused -> return read.result
            }
        val record = plan.record
        val slot = record.slot
        val nameBytes = plan.name.encodeToByteArray()
        // Each step's record is everything written up to and including it: a rollback puts back only what reads as Knit's.
        val steps =
            listOf(
                SetupStep("autoadd", record.priorAutoadd != null, MeshcoreProto.setAutoadd(plan.wantAutoadd)) {
                    autoaddIs(c, plan.wantAutoadd)
                },
                SetupStep("manual add", record.priorManualAdd != null, MeshcoreProto.setManualAdd(MANUAL_ADD_ON)) {
                    selfOf(c)?.manualAdd == MANUAL_ADD_ON
                },
                SetupStep("name", record.priorName != null, MeshcoreProto.setAdvertName(plan.name)) {
                    selfOf(c)?.name?.contentEquals(nameBytes) == true
                },
                SetupStep("channel", record.slotWritten, MeshcoreProto.setChannel(slot, KnitChannel.NAME, KnitChannel.PSK)) {
                    readSlot(c, slot)?.knit == true
                },
            )
        steps.forEachIndexed { i, step ->
            if (step.needed && !(step.write != null && writeVerified(c, step.write, step.verify))) {
                return rollback(c, record.through(i), step.what)
            }
        }
        c.knitSlot = slot
        c.slots = c.slots.map { if (it.index == slot) MeshcoreSlot(slot, empty = false, knit = true) else it }
        slotReading = MeshcoreSlotReading(slot, knit = true, atMs = now())
        publishReady(c)
        log("meshcore setup slot=$slot written=${record.slotWritten}")
        return MeshcoreSetupResult.Done(slot, alreadyPresent = !record.slotWritten, record = record)
    }

    /** One setup write: needed or not, its bytes (null: not encodable), and the read that believes it. */
    private class SetupStep(
        val what: String,
        val needed: Boolean,
        val write: ByteArray?,
        val verify: suspend () -> Boolean,
    )

    /** What a setup will write, and what each value was. */
    private class SetupPlan(
        val record: MeshcoreSetupRecord,
        val name: String,
        val wantAutoadd: Int,
    )

    private sealed interface SetupRead {
        class Planned(
            val plan: SetupPlan,
        ) : SetupRead

        class Refused(
            val result: MeshcoreSetupResult,
        ) : SetupRead
    }

    /** The reads, all before any write, and the plan they make — or the refusal, with nothing written. */
    private suspend fun setupReads(c: Conn): SetupRead {
        c.info = ask<McFrame.DeviceInfo>(c, MeshcoreProto.deviceQuery())
        c.self = c.info?.let { ask<McFrame.SelfInfo>(c, MeshcoreProto.appStart()) }
        val scanned = c.self != null && scanSlots(c)
        val scope = if (scanned) ask<McFrame.DefaultScope>(c, MeshcoreProto.getDefaultScope()) else null
        val autoadd = scope?.let { ask<McFrame.Autoadd>(c, MeshcoreProto.getAutoadd()) }
        val self = c.self
        if (autoadd == null || self == null) return SetupRead.Refused(setupFailed(if (scanned) "settings" else "identity"))
        c.defaultScope = requireNotNull(scope).set
        val existing = c.slots.firstOrNull { it.knit }
        val slot =
            existing?.index ?: c.slots.firstOrNull { it.empty && it.index != PUBLIC_SLOT }?.index
                ?: return SetupRead.Refused(MeshcoreSetupResult.NoFreeSlot)
        val name = MeshcoreNames.forKey(self.key)
        val wantAutoadd = autoadd.config and KNIT_AUTOADD_OFF.inv()
        val record =
            MeshcoreSetupRecord(
                slot = slot,
                slotWritten = existing == null,
                priorName = self.name.takeUnless { it.contentEquals(name.encodeToByteArray()) },
                priorManualAdd = self.manualAdd.takeIf { it != MANUAL_ADD_ON },
                priorAutoadd = autoadd.config.takeIf { it != wantAutoadd },
            )
        return SetupRead.Planned(SetupPlan(record, name, wantAutoadd))
    }

    /** [this] record cut to the setup steps up to [step] (autoadd, manual add, name, channel). */
    private fun MeshcoreSetupRecord.through(step: Int): MeshcoreSetupRecord =
        MeshcoreSetupRecord(
            slot = slot,
            slotWritten = slotWritten && step >= STEP_CHANNEL,
            priorName = priorName.takeIf { step >= STEP_NAME },
            priorManualAdd = priorManualAdd.takeIf { step >= STEP_MANUAL_ADD },
            priorAutoadd = priorAutoadd,
        )

    private fun setupFailed(what: String): MeshcoreSetupResult {
        log("meshcore setup failed: $what")
        return MeshcoreSetupResult.Failed("could not read the board's $what", left = null)
    }

    private suspend fun rollback(
        c: Conn,
        done: MeshcoreSetupRecord,
        what: String,
    ): MeshcoreSetupResult {
        log("meshcore setup failed writing $what; putting back what was written")
        // A value left as found is not Knit's: nothing of Knit's is left there. Only a failed put-back leaves something.
        val back = runRestore(c, done) as? MeshcoreRestoreResult.Done
        val clean = back != null && back.failed.isEmpty()
        return MeshcoreSetupResult.Failed("the board did not take its $what", left = done.takeIf { !clean && it.changedAnything })
    }

    /** A flash write ([FLASH_TIMEOUT_MS]) believed only once [verify] reads it back: a bare `OK` may be another client's. */
    private suspend fun writeVerified(
        c: Conn,
        bytes: ByteArray,
        verify: suspend () -> Boolean,
    ): Boolean = writeAnswered(c, bytes, FLASH_TIMEOUT_MS) != Answer.NONE && verify()

    private suspend fun selfOf(c: Conn): McFrame.SelfInfo? = ask<McFrame.SelfInfo>(c, MeshcoreProto.appStart())?.also { c.self = it }

    private suspend fun autoaddIs(
        c: Conn,
        want: Int,
    ): Boolean = ask<McFrame.Autoadd>(c, MeshcoreProto.getAutoadd())?.config == want

    /**
     * Puts back what [record] says a setup changed, each only while the board still shows Knit's value there: the slot
     * is cleared only if it still holds Knit's channel and key, the name restored only if it is still Knit's, and the
     * same for the two settings. Anything else is reported, never overwritten.
     */
    private suspend fun runRestore(
        c: Conn,
        record: MeshcoreSetupRecord,
    ): MeshcoreRestoreResult {
        val left = ArrayList<String>()
        val failed = ArrayList<String>()
        val slotCleared = record.slotWritten && restoreSlot(c, record.slot, left, failed)
        val self = selfOf(c)
        val knitName = self?.let { MeshcoreNames.forKey(it.key).encodeToByteArray() }
        record.priorName?.let { prior ->
            restoreOne(
                c,
                "name",
                self?.name?.contentEquals(knitName ?: ByteArray(0)),
                MeshcoreProto.setAdvertName(prior),
                left,
                failed,
            ) {
                selfOf(c)?.name?.contentEquals(prior) == true
            }
        }
        record.priorManualAdd?.let { prior ->
            restoreOne(c, "manualAdd", self?.manualAdd?.let { it == MANUAL_ADD_ON }, MeshcoreProto.setManualAdd(prior), left, failed) {
                selfOf(c)?.manualAdd == prior
            }
        }
        record.priorAutoadd?.let { prior ->
            val knitValue = prior and KNIT_AUTOADD_OFF.inv()
            val current = ask<McFrame.Autoadd>(c, MeshcoreProto.getAutoadd())?.config
            restoreOne(
                c,
                "autoadd",
                current?.let { it == knitValue },
                MeshcoreProto.setAutoadd(prior),
                left,
                failed,
            ) { autoaddIs(c, prior) }
        }
        if (slotCleared && c.knitSlot == record.slot) {
            c.knitSlot = null
            c.slots = c.slots.map { if (it.index == record.slot) MeshcoreSlot(it.index, empty = true, knit = false) else it }
            slotReading = MeshcoreSlotReading(record.slot, knit = false, atMs = now())
            publishReady(c)
        }
        log("meshcore restore cleared=$slotCleared left=$left failed=$failed")
        return MeshcoreRestoreResult.Done(slotCleared, left, failed)
    }

    /** Clears [slot] if it still holds Knit's channel and key, read back empty; true when it did. */
    private suspend fun restoreSlot(
        c: Conn,
        slot: Int,
        left: MutableList<String>,
        failed: MutableList<String>,
    ): Boolean {
        when (readSlot(c, slot)?.knit) {
            true -> {
                if (writeVerified(c, MeshcoreProto.clearChannel(slot)) { readSlot(c, slot)?.empty == true }) {
                    return true
                } else {
                    failed +=
                        "slot"
                }
            }

            false -> {
                left += "slot"
            }

            null -> {
                failed += "slot"
            }
        }
        return false
    }

    /** One restore step: [stillKnits] null is an unread board (failed), false someone else's value (left as found). */
    @Suppress("LongParameterList") // the step's name, its guard, its write, the two report lists and its verification
    private suspend fun restoreOne(
        c: Conn,
        what: String,
        stillKnits: Boolean?,
        write: ByteArray?,
        left: MutableList<String>,
        failed: MutableList<String>,
        verify: suspend () -> Boolean,
    ) {
        when {
            stillKnits == null || write == null -> failed += what
            !stillKnits -> left += what
            !writeVerified(c, write, verify) -> failed += what
        }
    }

    // --- classification + housekeeping ---

    /**
     * A failed write's terminal pairing state, or null (backoff). An auth refusal is [MeshcoreLinkState.StaleBond] on a
     * bonded board and [MeshcoreLinkState.NeedsPairing] otherwise; a write that started unbonded and was closed under
     * us with the board still unbonded is a pairing that never happened, never a backoff (binding rule "pairing").
     */
    private suspend fun classify(
        address: String,
        result: GattResult<*>,
        startedUnbonded: Boolean,
    ): SessionEnd? {
        // A close under a write that started unbonded is judged on the settled bond: read at once it can still say
        // BONDING for a pairing the user just cancelled, and a drop redials into a second passkey prompt (BondSettle).
        val bond =
            if (result is GattResult.Closed && startedUnbonded) {
                BondSettle.settled(waits, now) { dialer.bondState(address) }
            } else {
                dialer.bondState(address)
            }
        val auth = result is GattResult.Failed && result.status in AUTH_STATUSES
        return when {
            auth && bond == BondState.BONDED -> {
                SessionEnd(REASON_AUTH, terminal = MeshcoreLinkState.StaleBond(address))
            }

            auth -> {
                SessionEnd(REASON_AUTH, terminal = MeshcoreLinkState.NeedsPairing(address))
            }

            result is GattResult.Closed && startedUnbonded && bond == BondState.NONE -> {
                SessionEnd(REASON_AUTH, terminal = MeshcoreLinkState.NeedsPairing(address))
            }

            else -> {
                null
            }
        }
    }

    private fun down(state: MeshcoreLinkState): BoardState =
        BoardState.Down(
            health =
                when (state) {
                    MeshcoreLinkState.Connecting, MeshcoreLinkState.Bonding, MeshcoreLinkState.Handshaking,
                    is MeshcoreLinkState.Disconnected,
                    -> TransportHealth.Degraded

                    else -> TransportHealth.Unavailable
                },
            detail = state,
        )

    /** The neutral `SendResult.NotReady` carries a Meshtastic [LinkState]; this is the nearest one for a MeshCore state. */
    private fun linkStateOf(state: BoardState): LinkState = meshcoreDownState(state.detail)

    /** One connection's state; only its actor touches it. */
    private class Conn(
        val address: String,
        val channel: NusChannel,
    ) {
        var info: McFrame.DeviceInfo? = null
        var self: McFrame.SelfInfo? = null
        var slots: List<MeshcoreSlot> = emptyList()
        var knitSlot: Int? = null
        var defaultScope = false
        var authenticated = false
        var live = false
        var drainWanted = false
        var lastUptime: Long? = null
        var lastPollAt = 0L
        val reassert = ArrayDeque<ByteArray>()
    }

    /** One compact DM's attempts: the same [timestamp] and [text] each, a new attempt number each (Q14). */
    private class Flight(
        val handle: UInt,
        val to: BoardAddr.MeshCore,
        val key: ByteArray,
        val text: ByteArray,
        val timestamp: Long,
        val startedAt: Long,
    ) {
        var attemptsSent = 0
        var attemptAt = 0L
        var estTimeoutMs = DEFAULT_EST_TIMEOUT_MS
        var repinNeeded = false
        var deferredSince: Long? = null
    }

    private class SessionEnd(
        val reason: String,
        val terminal: MeshcoreLinkState? = null,
        val resetStreak: Boolean = false,
    )

    /** Ends the session from inside a request: the write failed, the link dropped, or not even the barrier was answered. */
    private class EndSession(
        val end: SessionEnd,
    ) : Exception(end.reason)

    /**
     * One command the link will not carry in one write ([write]): refused alone, never the session's end. Caught where the
     * command's op answers ([doSend], [retry], [serve]).
     */
    private class CommandRefused : Exception("over the link's write size")

    private sealed interface Cmd {
        class Op<T>(
            val op: SessionOp<Conn, T>,
        ) : Cmd

        data object Tick : Cmd
    }

    companion object {
        /** The background dial's wait (ADR 2026-09.hp88), as `MeshtasticSession` words it. */
        const val WAITING_FOR_BOARD = "waiting for the board"
        const val REASON_GATT_DISCONNECT = "gatt disconnect"
        const val REASON_DIAL = "dial"
        const val REASON_DIAL_TIMEOUT = "dial timeout"
        const val REASON_HANDSHAKE = "handshake"
        const val REASON_WRITE = "write"
        const val REASON_BARRIER = "barrier unanswered"
        const val REASON_THREW = "threw"
        const val REASON_ADAPTER_OFF = "adapter off"
        const val REASON_AUTH = "auth"
        const val REASON_FIRMWARE = "firmware too old"
        const val REASON_CANCELLED = "cancelled"

        /** Then the code: a frame not in the pinned command allowlist reached the write, which ends the session. */
        const val REASON_REFUSED_COMMAND = "refused command"

        /** `BoardPacket.channel` for a contact text, which arrives on no slot. */
        const val DM_CHANNEL = -1

        /** Missing-service dials in a row that end the session loop terminal. */
        const val SERVICE_ABSENT_TERMINAL = 2

        /** How often an unbonded board's background wait re-reads its bond: a local read, nothing on the air. */
        const val UNBONDED_POLL_MS = 60_000L

        /** The firmware's Public channel, which `begin()` puts at index 0 (`MyMesh.cpp:997`). */
        const val PUBLIC_SLOT = 0

        /** How many attempts a compact DM gets: 0..2 (ADR 2026-11.gsqu §2). */
        const val ATTEMPTS = 3

        /**
         * The channel's status for a write over `mtu − 3`, refused before the stack could make it a long write
         * (`NusLinkPolicy.STATUS_TOO_LONG` in `mesh/bluetooth/meshcore/`, which this pure package cannot import; a test
         * pins the two equal).
         */
        const val WRITE_TOO_LONG = -3

        private const val NO_DEDICATED_SLOT = "a MeshCore board has no dedicated slot: Knit never moves its radio"
        private const val RESTORE_NEEDS_RECORD = "a MeshCore restore needs the record its setup returned"

        private const val BASE_BACKOFF_MS = 5_000L
        private const val MAX_BACKOFF_MS = 180_000L
        private const val DIRECT_CONNECT_TIMEOUT_MS = 30_000L
        private const val WRITE_TIMEOUT_MS = 10_000L
        private const val BONDING_TIMEOUT_MS = 90_000L

        /** An answer that needs no flash: the board writes it within its loop, behind at most four queued notifies. */
        private const val REQUEST_TIMEOUT_MS = 3_000L

        /** A flash write (`savePrefs`, `saveChannels`, a contact): ≈ 1 s on a board (Q4), so at least 3 s (binding rule). */
        private const val FLASH_TIMEOUT_MS = 5_000L
        private const val BARRIER_TIMEOUT_MS = 5_000L
        private const val SYNC_TIMEOUT_MS = 3_000L

        /** Replies one drain round reads before it serves a waiting command (a busy band never starves the inbox). */
        private const val DRAIN_ROUND_STEPS = 16

        /** How long a send or a contact op may wait on the actor before it answers a timeout. */
        private const val CALL_DEADLINE_MS = 30_000L
        private const val SETUP_DEADLINE_MS = 300_000L

        /** Drained messages held for their collector at most; past it the oldest goes. */
        const val PACKET_BACKLOG = 1_024
        private const val TIMED_OUT = "timed out"

        // A count refused BAD_STATE (another client listing) is asked again after 0.5, 1 and 2 s.
        private const val COUNT_ATTEMPTS = 4
        private const val COUNT_BACKOFF_MS = 500L

        /** Poll the board every second while anything is queued or in flight, every minute otherwise (§6). */
        private const val BUSY_POLL_MS = 1_000L
        private const val IDLE_POLL_MS = 60_000L
        private const val BUSY_TICK_MS = 1_000L
        private const val IDLE_TICK_MS = 60_000L

        /** A bound slot read longer ago than this is read again before a room post. */
        private const val SLOT_TTL_MS = 60_000L

        /** A retry is due this long after its attempt's `est_timeout`. */
        private const val RETRY_SLACK_MS = 1_000L
        private const val DEFAULT_EST_TIMEOUT_MS = 5_000L
        private const val MIN_EST_TIMEOUT_MS = 500L
        private const val MAX_EST_TIMEOUT_MS = 60_000L

        /** A retry held behind a full ack table or pool longer than this ends the DM. */
        private const val DEFER_LIMIT_MS = 30_000L

        /** A pin at its slowest: a read, a write and a read-back, each timing out into a barrier. */
        const val PIN_WORST_MS = 2 * (REQUEST_TIMEOUT_MS + BARRIER_TIMEOUT_MS) + FLASH_TIMEOUT_MS + BARRIER_TIMEOUT_MS

        /**
         * The longest a compact DM's flight lives in the session: every attempt at the longest `est_timeout` it takes
         * plus the retry slack, two holds at [DEFER_LIMIT_MS], and a pin at its slowest before each attempt. Past it the
         * flight ends `Unreachable`. Stage 5's `MeshcoreTraits.deadlineMs` is a backstop above this, never below.
         */
        const val MAX_FLIGHT_MS = ATTEMPTS * (MAX_EST_TIMEOUT_MS + RETRY_SLACK_MS) + 2 * DEFER_LIMIT_MS + ATTEMPTS * PIN_WORST_MS

        private const val BYTE_MASK = 0xFF

        /**
         * The text types our board answers (`BaseChatMesh.cpp:244-255`, `:291-304`): plain and signed plain — a flooded one
         * with a PATH return, a direct one with its acks. What the plane's hooks book (Stage 5a-2).
         */
        internal val ACKED_TXT_TYPES = setOf(MeshcoreProto.TXT_TYPE_PLAIN, MeshcoreProto.TXT_TYPE_SIGNED_PLAIN)

        /** Whether [bytes] is a command the encoder may emit: the session's last check before a write. */
        fun commandAllowed(bytes: ByteArray): Boolean =
            bytes.isNotEmpty() && (bytes[0].toInt() and BYTE_MASK) in MeshcoreProto.ENCODED_COMMANDS

        private const val MANUAL_ADD_ON = 1

        /** What the setup turns off in `autoadd_config`: chat auto-add, and overwrite-oldest (binding rule "contacts" 1). */
        private const val KNIT_AUTOADD_OFF = MeshcoreProto.AUTO_ADD_CHAT or MeshcoreProto.AUTO_ADD_OVERWRITE_OLDEST

        // The setup's steps, in order (runSetup).
        private const val STEP_MANUAL_ADD = 1
        private const val STEP_NAME = 2
        private const val STEP_CHANNEL = 3
        private const val MS_PER_SECOND = 1_000L
        private const val MV_PER_VOLT = 1_000f
        private const val MIN_BATTERY_MV = 1_000
        private const val MAX_BATTERY_MV = 20_000
        private const val MAX_SLOTS = 64
        private const val STATUS_CLOSED = -1
        private const val OUTCOME_BUFFER = 64

        // A sane radio (binding rule "client" 5), the firmware's own `constrain` bounds (`MyMesh.cpp:983-987`).
        private const val MIN_BW_HZ = 7_800L
        private const val MAX_BW_HZ = 500_000L
        private const val MIN_SF = 5
        private const val MAX_SF = 12
        private const val MIN_CR = 5
        private const val MAX_CR = 8
        private const val MIN_FREQ_KHZ = 150_000L
        private const val MAX_FREQ_KHZ = 2_500_000L

        // Android BluetoothGatt status codes read as a bond problem (the platform's values, as MeshtasticSession reads them).
        private val AUTH_STATUSES = setOf(5, 15, 137)
    }
}
