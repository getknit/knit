package app.getknit.knit.mesh.lora.meshcore

import app.getknit.knit.identity.NodeId
import app.getknit.knit.mesh.TransportHealth
import app.getknit.knit.mesh.bluetooth.BackoffConfig
import app.getknit.knit.mesh.bluetooth.ConnectBackoffPolicy
import app.getknit.knit.mesh.crypto.MeshcoreBind
import app.getknit.knit.mesh.crypto.MeshcoreKey
import app.getknit.knit.mesh.lora.AckProof
import app.getknit.knit.mesh.lora.BoardAddr
import app.getknit.knit.mesh.lora.BoardAir
import app.getknit.knit.mesh.lora.BoardBattery
import app.getknit.knit.mesh.lora.BoardDialPolicy
import app.getknit.knit.mesh.lora.BoardLink
import app.getknit.knit.mesh.lora.BoardPacket
import app.getknit.knit.mesh.lora.BoardSend
import app.getknit.knit.mesh.lora.BoardSetupState
import app.getknit.knit.mesh.lora.BoardState
import app.getknit.knit.mesh.lora.BondSettle
import app.getknit.knit.mesh.lora.BondState
import app.getknit.knit.mesh.lora.DialMode
import app.getknit.knit.mesh.lora.GattResult
import app.getknit.knit.mesh.lora.KnitChannel
import app.getknit.knit.mesh.lora.LinkState
import app.getknit.knit.mesh.lora.LoraContactPolicy
import app.getknit.knit.mesh.lora.MeshcoreRenameResult
import app.getknit.knit.mesh.lora.PacketIdSource
import app.getknit.knit.mesh.lora.PacketOutcome
import app.getknit.knit.mesh.lora.PairingCause
import app.getknit.knit.mesh.lora.ProvisionMode
import app.getknit.knit.mesh.lora.ProvisionResult
import app.getknit.knit.mesh.lora.ProvisionSpec
import app.getknit.knit.mesh.lora.QueueInfo
import app.getknit.knit.mesh.lora.RadioParams
import app.getknit.knit.mesh.lora.ReplaceToken
import app.getknit.knit.mesh.lora.SendPurpose
import app.getknit.knit.mesh.lora.SendResult
import app.getknit.knit.mesh.lora.SetupPreview
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
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.ByteBuffer
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
 * **A compact DM** rides as a contact text DM, `COBS(frame)` ([Cobs]), on the route the firmware keeps for the contact —
 * by flood with none, then along the path a PATH return taught it — up a ladder of the session's own (ADR 2026-11.gsqu's
 * 2026-11-19 amendment, [nextRung]): zero hops before any flood, at most one flood a flight. The session runs its own
 * attempts 0..2 — the same timestamp, a new `attempt` each, since a same-attempt retry is byte-identical and dropped
 * unanswered (Q14) — on the firmware's
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
    /**
     * The setup records (SQLCipher in the app, `data/meshcore/MeshcoreSetupStore`): the single source of consent, which this
     * actor alone reads and writes (the maintainer's decision, 2026-11-13: consent has one owner).
     */
    private val records: MeshcoreSetupJournal = MemoryMeshcoreSetupJournal(),
    /** A fresh 32-byte challenge per proof ([proveKey]). */
    private val nonce: () -> ByteArray = { ByteArray(NONCE_BYTES).also(java.security.SecureRandom()::nextBytes) },
    /**
     * Our own node id (`Identity.nodeId` in the app): what the board binds its key to for the sealed profile's proof
     * (ADR 2026-11.gsqu, Stage 5d; [bind]). Null — the default, and every session test's but the bind's — binds nothing
     * and owes nothing, so the consent goes out as it did before 5d.
     */
    private val ownNode: (suspend () -> String?)? = null,
) : BoardLink,
    MeshcoreBoardOps {
    private val _state = MutableStateFlow<BoardState>(down(MeshcoreLinkState.Idle))
    override val state: StateFlow<BoardState> = _state.asStateFlow()

    override val ready: BoardState.Ready? get() = _state.value as? BoardState.Ready

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

    /**
     * Contacts a flooded DM of ours was confirmed to: their reciprocal PATH is booked once their route is read ([runRead]),
     * or at the longest path at the next Ready if the connection ended first. The actor's alone.
     */
    private val reciprocalOwed = HashSet<BoardAddr.MeshCore>()
    private val headroomPolicy = MeshcoreHeadroom()
    private val airRing = MeshcoreAirRing()
    private val waits = ElapsedWait(now)

    /** The inbox of the session now Ready, or null: one per session, retired as it ends (`MeshtasticSession`'s rule). */
    private val inbox = AtomicReference<Channel<Cmd>?>(null)
    private val poke = Channel<Unit>(Channel.CONFLATED)
    private var loopJob: Job? = null

    @Volatile
    private var address: String? = null

    /**
     * The board this plane is bound to: the address it was last started on, kept across a stop (the maintainer's decision,
     * 2026-11-14) — the only address whose consent an unbind withdraws ([unbindAway]).
     */
    @Volatile
    private var boundAddress: String? = null

    /** Anything in flight or queued on the board: the ticker runs at [BUSY_TICK_MS] rather than [IDLE_TICK_MS]. */
    @Volatile
    private var busy = false

    @Volatile
    private var slotReading: MeshcoreSlotReading? = null

    /** Whether the hooks have handed over the taught set at least once ([setKnitContacts]): the reconcile waits for it. */
    @Volatile
    private var taughtKnown = false

    private val _consent = MutableStateFlow<MeshcoreConsent?>(null)

    /** Held from a handshake's consent read through its Ready, and around an unbind's choice of writer (security B1). */
    private val consentLock = kotlinx.coroutines.sync.Mutex()

    /**
     * This phone's consent for the board each Ready is on, in the order the actor decided it (the maintainer's decision,
     * 2026-11-13: one owner). Resolved from the setup record inside the handshake, before the Ready is published, so every
     * Ready carries a known answer; moved only by this actor's own setup, Restore and unbind, each after its durable write.
     * The hooks derive the taught set, the offers and the profile's claim from it, the latest winning.
     */
    override val consent: StateFlow<MeshcoreConsent?> = _consent.asStateFlow()

    private val _setupState = MutableStateFlow<MeshcoreSetupView?>(null)

    /**
     * Whether this phone set the bound board up, for the radio screen (ADR 2026-11.gsqu's Stage 7 amendment): read off the
     * record at [start] (the board away), then by the handshake and after every setup, Restore, unbind and cleanup, on the
     * actor or under [consentLock] — [MeshcoreSetupStates]. A display only: nothing gates on it.
     */
    override val setupState: StateFlow<MeshcoreSetupView?> = _setupState.asStateFlow()

    /** A Ready's reconcile not yet run: [setKnitContacts] wakes the actor for it. Mirrors the connection's own flag. */
    @Volatile
    private var reconcilePending = false

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
        boundAddress = address
        serviceAbsentInRow = 0
        // Without waiting for the board (security N2): a bound board whose record is absent or pending is withdrawn now, so a
        // claim a past failure left behind goes at start. Under the lock, and only while nothing was published for it yet.
        scope.launch { withdrawAtStart(address) }
        loopJob = scope.launch { connectLoop(address) }
    }

    private suspend fun withdrawAtStart(address: String) {
        consentLock.withLock {
            // Only for the board still bound (security, 5b-2 tail): a start of another board since makes this one's moot.
            if (boundAddress != address) return
            val consentKnown = _consent.value?.address == address
            // The screen's setup state with the board away (Stage 7), unless a handshake already read it for this address.
            val viewKnown = _setupState.value?.address == address
            if (consentKnown && viewKnown) return
            val read = readEntry(address)
            if (!viewKnown) noteSetup(address, MeshcoreSetupStates.of(read, digest = null, consent = false, away = true))
            if (consentKnown) return
            val entry = (read ?: return).getOrNull()
            if (entry == null ||
                entry.pending
            ) {
                _consent.value = MeshcoreConsent(address, entry?.digest.orEmpty(), granted = false, key = null)
            }
        }
    }

    /** Publishes the screen's setup state for [address] (Stage 7). */
    private fun noteSetup(
        address: String,
        state: BoardSetupState,
    ) {
        _setupState.value = MeshcoreSetupView(address, state)
    }

    /**
     * Reads the record for [address] again after an op that wrote it, and publishes its setup state: [c] the connected
     * board's, or null for one away. A read that fails says [BoardSetupState.UNKNOWN], never "none".
     */
    private suspend fun refreshSetup(
        c: Conn?,
        address: String,
    ) {
        val read = readEntry(address)
        noteSetup(address, MeshcoreSetupStates.of(read, c?.digest, c?.consent == true, away = c == null))
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
                    is MeshcoreSetupResult.DifferentBoard -> ProvisionResult.DifferentBoard(r.token)
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

    /**
     * Sets the board up for Knit as one transaction (§4, the maintainer's Stage 5 decisions): reads first, then the erase —
     * every contact that is not a taught Knit one and every slot but Public and Knit's, one command at a time, each read
     * back — then the writes, each read back. Never `CMD_FACTORY_RESET`: it would format the board, mint it a new identity
     * and reset its radio (ADR 045), and the encoder cannot write it.
     */
    override suspend fun setup(): MeshcoreSetupResult =
        call({ MeshcoreSetupResult.NotReady }, { MeshcoreSetupResult.Failed(TIMED_OUT, left = null) }, setupDeadlineMs) { c ->
            edit(c) { runSetup(c, replace = null) }.also { refreshSetup(c, c.address) }
        }

    /**
     * [setup] over the other board's record [token] was issued for (ADR 2026-11.gsqu's Stage 7 amendment): refused again,
     * nothing written, unless the record at the address is still exactly that one (read in the write-ahead, on the actor).
     * Every step runs as ever: the proof, the write-ahead (pending, this board's digest), the erase, the writes, the grant;
     * a setup that leaves nothing of its own puts the other board's record back as it was.
     */
    override suspend fun setupReplacing(token: ReplaceToken): MeshcoreSetupResult =
        call({ MeshcoreSetupResult.NotReady }, { MeshcoreSetupResult.Failed(TIMED_OUT, left = null) }, setupDeadlineMs) { c ->
            edit(c) { runSetup(c, replace = token) }.also { refreshSetup(c, c.address) }
        }

    /**
     * What a setup would erase, read fresh on the actor (ADR 2026-11.gsqu's Stage 7 amendment): the board's contact count
     * (`GET_CONTACTS` past every `lastmod`, the count alone) and the slots in use besides Public and Knit's, each slot read
     * again. Counts only, each null when the board did not answer it; and whether the setup would rename the board. Nothing
     * written. Null when the board is not Ready.
     */
    override suspend fun setupPreview(): SetupPreview? =
        call({ null }, { null }) { c ->
            val contacts = countContacts(c)
            val count = requireNotNull(c.info).maxChannels.coerceIn(1, MAX_SLOTS)
            var slots: Int? = 0
            for (i in (0 until count).filter { it != PUBLIC_SLOT }) {
                val slot = readSlot(c, i)
                if (slot == null) {
                    slots = null
                    break
                }
                if (!slot.empty && !slot.knit) slots = slots?.plus(1)
            }
            // Whether the confirmation says the board is renamed (ADR 2026-11.f4d3): what the setup's own reads would decide.
            // Unknown (the board or the record unread) reads as a rename, the dialog's safe half.
            val self = selfOf(c)
            val entry = readEntry(c.address)
            val renames = self == null || entry == null || renamesAt(entry.getOrNull(), self)
            SetupPreview(contacts = contacts, slots = slots, renames = renames)
        }

    /**
     * Puts back what [record] says a setup changed, where it is still Knit's to put back (binding rule "contacts" 2), and
     * removes Knit's contacts first; what setup erased is not brought back. The taught set is forgotten on success.
     */
    override suspend fun restore(): MeshcoreRestoreResult =
        call(
            { MeshcoreRestoreResult.NotReady },
            { MeshcoreRestoreResult.Done(false, emptyList(), listOf(TIMED_OUT)) },
            setupDeadlineMs,
        ) { c -> restoreOwn(c).also { refreshSetup(c, c.address) } }

    /**
     * Renames the board [name], its owner's handle (ADR 2026-11.f4d3): refused [MeshcoreRenameResult.INVALID] with nothing
     * written unless the name passes the postable rules ([MeshcoreNames.check], the one rule the Public post asks too), and
     * [MeshcoreRenameResult.NOT_SET_UP] off a board this connection does not consent for. One flash write, believed only once
     * `SELF_INFO` reads the name back ([MeshcoreRenameResult.FAILED] otherwise), then the Ready published with it. The setup
     * record is never touched: Restore puts the name from before Knit back only while the board still reads `Knit abcd`, so a
     * handle is left as the owner chose it. The BLE name follows at the board's next boot (`main.cpp:209`); nothing is
     * advertised (the companion never adverts on its own).
     */
    override suspend fun rename(name: String): MeshcoreRenameResult {
        val bytes = name.encodeToByteArray()
        if (MeshcoreNames.check(bytes) != MeshcoreNameCheck.OK) return MeshcoreRenameResult.INVALID
        return call({ MeshcoreRenameResult.NOT_READY }, { MeshcoreRenameResult.FAILED }) { c ->
            edit(c) { runRename(c, bytes) }.also { log("meshcore rename ${it.name.lowercase()}") }
        }
    }

    private suspend fun runRename(
        c: Conn,
        name: ByteArray,
    ): MeshcoreRenameResult {
        if (!knitBoard(c)) return MeshcoreRenameResult.NOT_SET_UP
        val write = MeshcoreProto.setAdvertName(name) ?: return MeshcoreRenameResult.INVALID
        if (!writeVerified(c, write) { selfOf(c)?.name?.contentEquals(name) == true }) return MeshcoreRenameResult.FAILED
        publishReady(c)
        return MeshcoreRenameResult.DONE
    }

    /**
     * Writes [key] as a Knit contact (zero-hop, favourite, `Knit xxxx`), only into a free slot (the count the Ready's listing
     * read, kept locally; one read only when no listing was taken), and marks it taught; never evicts anyone. On a board set
     * up for Knit a contact already on the key is overwritten to Knit's record; on any other board it is left alone
     * ([ContactWrite.ALREADY_PRESENT]).
     */
    override suspend fun teach(key: ByteArray): ContactWrite =
        call({ ContactWrite.NOT_READY }, { ContactWrite.FAILED }) { c -> runTeach(c, key) }

    /**
     * Whether [key] is a key Knit taught this board and its record's route was read on this connection (the Ready's listing,
     * a teach's read-back): what a DM to it waits for. Read off the pins' own lock, never the actor's, for the mesh lab's
     * readiness wait (ADR 2026-11.gsqu's Stage 5c-2b amendment); nothing in the app reads it.
     */
    internal fun taughtAndRouted(key: ByteArray): Boolean =
        MeshcorePins.addrOf(key)?.let { pins.teaches(key) && pins.routeOf(it) != null } == true

    /** Removes [key]'s contact and reads it gone. */
    suspend fun removeContact(key: ByteArray): ContactWrite =
        call({ ContactWrite.NOT_READY }, { ContactWrite.FAILED }) { c -> runRemove(c, key) }

    /**
     * Removes [key]'s contact unless it is taught again by the time the actor runs it (lora N1: the hooks decided it left the
     * taught set earlier, and a key that came back keeps its contact). [ContactWrite.NOT_FOUND] when skipped.
     */
    override suspend fun removeUntaught(key: ByteArray): ContactWrite =
        call({ ContactWrite.NOT_READY }, { ContactWrite.FAILED }) { c ->
            if (pins.teaches(key)) ContactWrite.NOT_FOUND else runRemove(c, key)
        }

    /** Resets [key]'s route to flood (`RESET_PATH`) and reads it back; the next DM to it airs zero hops first. */
    suspend fun resetPath(key: ByteArray): ContactWrite =
        call({ ContactWrite.NOT_READY }, { ContactWrite.FAILED }) { c -> runResetPath(c, key) }

    /** Reads [key]'s contact. */
    suspend fun readContact(key: ByteArray): ContactRead =
        call({ ContactRead.NotReady }, { ContactRead.Failed }) { c ->
            readContactOn(c, key)?.let { found -> found.record?.let { ContactRead.Found(it) } ?: ContactRead.NotFound }
                ?: ContactRead.Failed
        }

    /**
     * Unbinds the board at [address] (the second part): the record's own board up now and bonded, Restore's clearing runs,
     * verified, then its record goes ([MeshcoreUnbind.CLEARED]); not up, or a Restore that does not finish, leaves the record a
     * pending cleanup run at that board's next connection ([MeshcoreUnbind.PENDING]). Either way consent goes, on the actor.
     */
    override suspend fun unbind(address: String): MeshcoreUnbind =
        // The actor is the record's only writer while the board is up (security B1): under [consentLock], which the
        // handshake holds from its consent read through its Ready, the board is either Ready here — and the actor runs the
        // unbind, waiting for it however long it is busy — or not, and no Ready can come up until the record is marked.
        consentLock.withLock {
            // A live inbox too (security N4): a session whose state already reads down but whose actor still runs is not "away".
            val readyHere = inbox.get() != null && ((_state.value as? BoardState.Ready)?.detail as? MeshcoreDetail)?.dialled == address
            // The op checks the address again (security F3): a retarget in the window falls through to the away path.
            val onActor =
                if (readyHere) {
                    call({ null }, { null }, Long.MAX_VALUE) { c ->
                        if (c.address == address) runUnbind(c).also { refreshSetup(c, address) } else null
                    }
                } else {
                    null
                }
            onActor ?: unbindAway(address).also { refreshSetup(null, address) }
        }

    /**
     * The keys Knit teaches this board, ranked (`MeshcoreContacts`): the only keys a DM is sent to or a record written for
     * (security B1), the targets of a targeted read or re-teach, and what the Ready reconcile makes the board's table on a board set up
     * for Knit. Until the first call no reconcile runs, so a Ready that beats the board map removes nothing. Any thread.
     */
    override fun setKnitContacts(
        ranked: List<ByteArray>,
        withdrawn: Boolean,
    ) {
        val added = pins.setTaught(ranked)
        // An empty set handed over for want of consent is no taught set (lora L1): the reconcile that a later consent owes
        // waits for the real one, never lists against nothing and removes every Knit contact.
        taughtKnown = !withdrawn
        // Only a reconcile waiting on the set is woken: it writes what the set holds. Past it, a key taught mid-connection is
        // written now (ADR 2026-11.gsqu's Stage 5c-2b amendment, the lab's first two-node DM): the firmware drops a DM from a
        // sender it holds no contact for, so a contact who writes first would reach us only after our next Ready or our own
        // send to them. A read of the key, a count read when the Ready took no listing, and at most one flash write a key,
        // on a board set up for Knit only (the job is dropped anywhere else); no air. At most
        // [MeshcorePins.MAX_LIVE_TEACHES_PER_DAY] a key a UTC day: past it the next Ready's reconcile writes it.
        if (reconcilePending) {
            poke.trySend(Unit)
        } else if (!withdrawn && added.isNotEmpty() && _state.value is BoardState.Ready) {
            val day = utcDay()
            added.forEach { key ->
                if (pins.queueTeach(key, day) == MeshcorePins.TeachQueue.DEFERRED) counters.liveTeachesDeferred.incrementAndGet()
            }
            poke.trySend(Unit)
        }
    }

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
        // The wrong-firmware cause (Stage 7): the screen says "not running MeshCore, or reflashed since it was paired".
        _state.value =
            down(
                if (bonded) {
                    MeshcoreLinkState.StaleBond(address, PairingCause.NO_SERVICE)
                } else {
                    MeshcoreLinkState.NeedsPairing(address, PairingCause.NO_SERVICE)
                },
            )
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
                    // Down only once the actor has stopped (security N4): never "away" while it can still publish a consent.
                    session.cancelAndJoin()
                    _state.value = down(MeshcoreLinkState.Unavailable)
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
            // The consent read and the Ready it rides, as one step against an unbind (security B1).
            consentLock.withLock {
                readConsent(c)
                ready(c)
            }
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

    /**
     * This phone's consent for the board, read off its setup record before the first Ready (the maintainer's decisions,
     * 2026-11-13): a record for this address **and this board's key digest**, not pending, on a board the system reports
     * **bonded** that **proves it holds that key** by signing a fresh challenge ([proveKey]). The digest stops a replaced or
     * reflashed board; the signed challenge stops a spoofer at the address (a board's key is in its own adverts, so a clone can
     * report it, never sign with it); the bond is belt and braces. An unbonded or unproven board is inert for Knit (counted).
     * A pending record of this board, proven, is this connection's first job ([runCleanup]). A record read that keeps failing
     * leaves the consent the hooks last had for this board standing for the claim but closes the offers ([MeshcoreConsent.unread]),
     * and is read again as an idle job with a backoff (security N2). Runs under [consentLock], with the Ready that follows.
     */
    private suspend fun readConsent(c: Conn) {
        val read = decideConsent(c)
        // The screen's setup state off the same read (Stage 7): an unanswered or refused proof is unknown, never "none".
        noteSetup(c.address, MeshcoreSetupStates.of(read, c.digest, c.consent, away = false))
    }

    /** [readConsent]'s decision; the read it rested on, or null when there was none to make or every try failed. */
    @Suppress("ReturnCount")
    private suspend fun decideConsent(c: Conn): Result<MeshcoreSetupEntry?>? {
        c.consent = false
        c.cleanup = null
        c.consentUnread = false
        val digest = c.digest ?: return null
        val read = readEntry(c.address)
        if (read == null) {
            counters.consentReadFailures.incrementAndGet()
            log("meshcore consent read failed: read again later")
            unreadLater(c)
            return null
        }
        c.consentBackoffMs = 0
        val record = read.getOrNull() ?: return read
        when {
            record.digest != digest -> {
                counters.consentMismatches.incrementAndGet()
            }

            !bonded(c) -> {
                counters.unbondedInert.incrementAndGet()
            }

            else -> {
                when (proveKey(c)) {
                    // No answer, BAD_STATE, no such command (lora N1), or a signature that does not verify though the record
                    // names this board (another client's SIGN_DATA in our buffer, security S3): no consent and the offers
                    // closed, but the claim kept and the proof asked again on the backoff. A clone gains nothing: it never
                    // gets consent; only no record, a digest mismatch, a Restore or an unbind withdraws the claim.
                    Proof.REFUSED -> {
                        // A key re-keyed mid-connection ends the session here (lora L): the next identify sees the change.
                        selfOf(c)
                        unreadLater(c)
                    }

                    Proof.UNANSWERED -> {
                        unreadLater(c)
                    }

                    // A pending record is a cleanup, never a consent: it binds nothing (Stage 5d). A consent is bound before the
                    // Ready that publishes it, under [consentLock] with it, so the Ready's consent carries the signature.
                    Proof.PROVEN -> {
                        if (record.pending) {
                            c.cleanup = record.record
                        } else {
                            c.consent = true
                            bind(c)
                        }
                    }
                }
            }
        }
        return read
    }

    /** The consent could not be decided this time: the claim stays as it was, the offers close, asked again on a backoff. */
    private fun unreadLater(c: Conn) {
        c.consentUnread = true
        c.consentBackoffMs = (c.consentBackoffMs * 2).coerceIn(CONSENT_RETRY_FIRST_MS, CONSENT_RETRY_MAX_MS)
        c.consentRetryAt = now() + c.consentBackoffMs
    }

    /** The record for [address], read up to [CONSENT_READS] times; null when every read failed. */
    private suspend fun readEntry(address: String): Result<MeshcoreSetupEntry?>? {
        repeat(CONSENT_READS) { n ->
            // Bounded (lora N2); boxed, since a record that is not there is a null of its own.
            val read =
                runCatching {
                    val box = withTimeoutOrNull(RECORD_TIMEOUT_MS) { arrayOf(records.entryOf(address)) } ?: error("record read timed out")
                    box[0]
                }
            // A cancelled session ends here, never retries.
            read.exceptionOrNull()?.let { if (it is kotlinx.coroutines.CancellationException) throw it }
            if (read.isSuccess) return read
            if (n < CONSENT_READS - 1) waits.sleep(CONSENT_RETRY_MS)
        }
        return null
    }

    private fun bonded(c: Conn): Boolean = dialer.bondState(c.address) == BondState.BONDED

    /** RFC 8032 Ed25519 (Tink's strict verifier) of [signature] over [message] under [key]. */
    private fun verifies(
        key: ByteArray,
        message: ByteArray,
        signature: ByteArray,
    ): Boolean =
        runCatching {
            com.google.crypto.tink.subtle
                .Ed25519Verify(key)
                .verify(signature, message)
        }.isSuccess

    /**
     * Proof that the connected board holds the key it reports (the maintainer's decision, 2026-11-13): it signs
     * [CONSENT_DOMAIN] ‖ a fresh 32-byte nonce through `SIGN_START` / `SIGN_DATA` / `SIGN_FINISH` (`MyMesh.cpp:1781-1815`,
     * in the companion since 2025-03, so at every firmware at or above [MeshcoreProto.MIN_FIRMWARE_VERSION_CODE]), and the
     * signature verifies as RFC 8032 Ed25519 (`ed25519_sign`) under `SELF_INFO`'s key over exactly those bytes. The board's
     * sign buffer is one for every client: another's `SIGN_START` in between only fails this proof, never passes it. Asked
     * again before a Restore or a cleanup acts, and before a setup writes.
     *
     * A key [MeshcoreKey] refuses (not an Ed25519 point, or one of low order) proves nothing and is never asked to sign
     * (Stage 5d, security N1's follow-up): Tink's verifier is cofactorless and checks no key order, so under a low-order key
     * `R` = the identity and `S = 0` would verify every challenge. Counted as a signature that does not verify.
     */
    private suspend fun proveKey(c: Conn): Proof {
        val key = c.self?.key ?: return Proof.UNANSWERED
        if (!MeshcoreKey.accepts(key)) {
            counters.keyUnproven.incrementAndGet()
            log("meshcore key not proven: the board reports no valid Ed25519 key")
            return Proof.REFUSED
        }
        val message = CONSENT_DOMAIN + nonce()
        val signature = boardSign(c, message)
        val proof =
            when {
                signature == null -> Proof.UNANSWERED
                verifies(key, message, signature) -> Proof.PROVEN
                else -> Proof.REFUSED
            }
        when (proof) {
            Proof.PROVEN -> {
                // The key the proof holds for (security F1): the only key the consent ever publishes.
                c.provenKey = key.copyOf()
            }

            Proof.REFUSED -> {
                counters.keyUnproven.incrementAndGet()
                log("meshcore key not proven: the board's signature does not verify under its key")
            }

            Proof.UNANSWERED -> {
                counters.proofUnanswered.incrementAndGet()
                log("meshcore key proof unanswered: asked again later")
            }
        }
        return proof
    }

    /** What a [proveKey] came to: a verified signature, one that does not verify, or no signature at all. */
    private enum class Proof { PROVEN, REFUSED, UNANSWERED }

    /**
     * The board's signature of [message] through `SIGN_START` / `SIGN_DATA` / `SIGN_FINISH` (`MyMesh.cpp:1781-1815`), or null
     * for no answer: none at all, `BAD_STATE`, or a `SIGN_DATA` not answered `OK`. The buffer is one for every client, so
     * what comes back is checked by the caller, never trusted: another's `SIGN_START` in between makes it a signature of
     * other bytes. Three commands, no air.
     */
    private suspend fun boardSign(
        c: Conn,
        message: ByteArray,
    ): ByteArray? =
        if (ask<McFrame.SignStart>(c, MeshcoreProto.signStart()) == null ||
            MeshcoreProto.signData(message)?.let { writeAnswered(c, it) } != Answer.OK
        ) {
            null
        } else {
            ask<McFrame.Signature>(c, MeshcoreProto.signFinish())?.value
        }

    /**
     * The bind (ADR 2026-11.gsqu, Stage 5d): the board signs [MeshcoreBind.message] of our node id ([ownNode]) and the key it
     * just proved ([Conn.provenKey]), and the signature is kept on the connection only once [MeshcoreBind.verifies] accepts
     * it — the sealed profile's `meshcoreSig`, which every receiver checks against our authenticated node id. It rides the
     * consent this actor publishes ([publishConsent]), never a second writer, so a withdrawal can never be overtaken by it.
     *
     * Run right after every proof that grants consent — the handshake's, a setup's, a re-read's — and before that consent is
     * published; never for a pending cleanup, a Restore or any withdrawal. **Signed on every such proof** (security B3(a),
     * lora N4): RFC 8032 is deterministic, so a board signs one (node, key) to one signature every time and the claim store
     * edit and the claim's fingerprint are no-ops on a reconnect; the cost is three commands a proof, no air.
     *
     * A failure — no node id, no answer, another client's command in the shared buffer, a signature that does not verify —
     * never withholds the consent: it goes out without a signature, which the claim store's same-key rule makes harmless
     * (a proof it already holds for this key stands), and the bind is owed on its own backoff ([rebindIfOwed]). A signature
     * that does not verify is read as the proof's [Proof.REFUSED] is: counted, and `SELF_INFO` read once, so a board re-keyed
     * mid-connection ends the session; never inert, never a withdrawal.
     */
    private suspend fun bind(c: Conn) {
        val input = ownNode ?: return
        val key = c.provenKey ?: return
        val node =
            runCatching { withTimeoutOrNull(RECORD_TIMEOUT_MS) { input() } }
                .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
                .getOrNull()
                ?.let(NodeId::toBytesOrNull)
        // Over our real node id or nothing: never a placeholder a receiver would refuse anyway.
        val message = node?.let { MeshcoreBind.message(it, key) }
        if (node == null || message == null) {
            counters.bindUnanswered.incrementAndGet()
            log("meshcore bind skipped: no node id to bind, asked again later")
            return bindLater(c)
        }
        val sig = boardSign(c, message)
        when {
            sig == null -> {
                counters.bindUnanswered.incrementAndGet()
                log("meshcore bind unanswered: asked again later")
                bindLater(c)
            }

            MeshcoreBind.verifies(node, key, sig) -> {
                c.bindSig = sig.copyOf()
                c.bindKey = key.copyOf()
                c.bindOwed = false
                c.bindBackoffMs = 0
                log("meshcore bind signed")
            }

            else -> {
                counters.bindRefused.incrementAndGet()
                log("meshcore bind refused: the board's signature does not verify under its key")
                bindLater(c)
                // A key re-keyed mid-connection ends the session here, as a refused proof does.
                selfOf(c)
            }
        }
    }

    /** A bind that failed: owed, asked again as an idle job after a backoff of [CONSENT_RETRY_FIRST_MS] up to [CONSENT_RETRY_MAX_MS]. */
    private fun bindLater(c: Conn) {
        c.bindOwed = true
        c.bindBackoffMs = (c.bindBackoffMs * 2).coerceIn(CONSENT_RETRY_FIRST_MS, CONSENT_RETRY_MAX_MS)
        c.bindRetryAt = now() + c.bindBackoffMs
    }

    /**
     * An owed bind, due ([Conn.bindRetryAt]): asked again on the actor, and on success the consent re-published with its
     * signature — only while this connection still consents. A consent withdrawn since (a Restore, an unbind, a failed
     * re-read) owes nothing: the next granting proof binds again. True when the tick is spent.
     */
    private suspend fun rebindIfOwed(c: Conn): Boolean {
        if (!c.bindOwed || now() < c.bindRetryAt) return false
        if (!c.consent) {
            c.bindOwed = false
            c.bindBackoffMs = 0
            return false
        }
        bind(c)
        if (!c.bindOwed && c.consent) publishConsent(c)
        poke.trySend(Unit)
        return true
    }

    /** Hands [c]'s consent to the hooks ([consent]), after anything durable it rests on was written. */
    private fun publishConsent(c: Conn) {
        val digest = c.digest ?: return
        // Only the key the board proved (security F1): never a later SELF_INFO's, and only while it consents.
        val key = c.provenKey?.takeIf { c.consent }
        // With the bind's signature only when it was made over that very key (Stage 5d); without, the store keeps its proof.
        val sig = c.bindSig?.takeIf { key != null && c.bindKey?.contentEquals(key) == true }
        _consent.value = MeshcoreConsent(c.address, digest, c.consent && key != null, key, unread = c.consentUnread, sig = sig)
    }

    /**
     * Writes [record] for the connected board, merged into one an earlier setup of the same board left (lora L1: the slot
     * written if any run wrote it, each prior the earliest known), in one write, under the record write rule
     * ([grantWrite] for a finished setup, [withdrawWrite] for a failed one's leftovers). True when a finished setup's record
     * landed and its consent is granted. A setup only runs on a bonded board that proved its key ([runSetup]).
     */
    private suspend fun keepRecord(
        c: Conn,
        record: MeshcoreSetupRecord,
        pending: Boolean,
    ): Boolean {
        // The record names the key the board proved (security S2), never a later report's.
        val digest = c.provenKey?.let(MeshcoreHooks::digest) ?: return false
        val landed: suspend MeshcoreSetupJournal.() -> Boolean = {
            entryOf(c.address)?.let { it.digest == digest && it.pending == pending } == true
        }
        val write: suspend () -> Unit = {
            val old = records.entryOf(c.address)?.takeIf { it.digest == digest }?.record
            records.put(c.address, digest, old?.let { merge(it, record) } ?: record, pending)
        }
        // The setup supersedes a cleanup still owed: its record is the one kept now, merged.
        c.cleanup = null
        if (pending) {
            withdrawWrite(c, c.address, landed, write = write)
            return false
        }
        return grantWrite(c, landed, write)
    }

    /**
     * Restore on the connected board, from the record this phone keeps for it: refused, nothing written, for no record, a
     * record of another board, an unbonded board or one that does not prove its key ([MeshcoreRestoreResult.Mismatch]); a
     * record that cannot be read, or a proof nobody answered, is no answer (`failed: record` / `proof`, never "no record");
     * else Restore's clearing, then the record's clear under the record write rule ([withdrawWrite]): consent no at once,
     * and a clear that does not land leaves the record pending and the Restore answering `failed: record` (the security
     * review's Blocking, 2026-11-14) — never a success.
     */
    private suspend fun restoreOwn(c: Conn): MeshcoreRestoreResult = restoreWith(c).done

    /** What a Restore did: the board's answer, and the record's pending mark and its clear under the rule (null: not tried). */
    private class RestoreRun(
        val done: MeshcoreRestoreResult,
        val marked: RecordOutcome? = null,
        val cleared: RecordOutcome? = null,
        /** The record is for another key than the connected board's (Stage 7: [MeshcoreUnbind.MISMATCH]). */
        val otherBoard: Boolean = false,
    )

    /**
     * [restoreOwn], with what its record writes came to. **The intent is written before acting** (security B1, N3): once the
     * record is known to be this bonded board's, it is marked pending (consent no, the claim withdrawn) before the proof and
     * every board write, so an interruption anywhere after leaves a pending cleanup, never a consent; then the Restore; then
     * the record's clear. A failed Restore is a pending cleanup with consent no; a failed clear answers `failed: record`.
     */
    private suspend fun restoreWith(c: Conn): RestoreRun {
        val read = readEntry(c.address) ?: return RestoreRun(unreadRecord())
        val entry = read.getOrNull() ?: return RestoreRun(MeshcoreRestoreResult.NoRecord)
        if (entry.digest != c.digest || !bonded(c)) {
            counters.restoreMismatches.incrementAndGet()
            log("meshcore restore refused: not the bonded board this phone set up at that address")
            return RestoreRun(MeshcoreRestoreResult.Mismatch, otherBoard = entry.digest != c.digest)
        }
        val marked = markAhead(c, c.address)
        // A proof nobody answered, or one that does not verify (maybe another client's sign buffer), is no answer (lora L3, N3).
        if (proveKey(c) != Proof.PROVEN) return RestoreRun(MeshcoreRestoreResult.Done(false, emptyList(), listOf("proof")), marked)
        val done = runRestore(c, entry.record, clearContacts = true)
        if (done !is MeshcoreRestoreResult.Done || done.failed.isNotEmpty()) return RestoreRun(done, marked)
        val cleared = withdrawWrite(c, c.address, { entryOf(c.address) == null }) { records.clear(c.address) }
        return RestoreRun(if (cleared == RecordOutcome.DONE) done else done.copy(failed = done.failed + "record"), marked, cleared)
    }

    private fun unreadRecord(): MeshcoreRestoreResult {
        counters.recordReadFailures.incrementAndGet()
        return MeshcoreRestoreResult.Done(false, emptyList(), listOf("record"))
    }

    /**
     * [unbind] for the connected board, on the actor, the intent first (security B1): the record is marked pending (consent
     * no, the claim withdrawn) before any board write, then Restore, then the clear. `CLEARED` only when the clear landed;
     * otherwise `PENDING` when a mark landed (the cleanup runs at the next connection), `FAILED` when none did.
     */
    private suspend fun runUnbind(c: Conn): MeshcoreUnbind {
        val read = readEntry(c.address)
        if (read != null && read.getOrNull() == null) {
            // No record: still withdrawn, so a claim left by an earlier failure goes too (security N2).
            revoke(c, c.address, null)
            return MeshcoreUnbind.NOTHING
        }
        val run = if (read != null) restoreWith(c) else RestoreRun(unreadRecord())
        c.steps.clear()
        pins.clearJobs()
        if (run.cleared == RecordOutcome.DONE) return MeshcoreUnbind.CLEARED
        // No mark yet (an unread record, another board's): the mark is the one write, under the rule.
        val marked = run.marked ?: markAhead(c, c.address)
        return when {
            // Another key's record (Stage 7): Knit cannot clean this board, whatever the mark did; it stays as it was, pending.
            run.otherBoard && marked != RecordOutcome.FAILED -> MeshcoreUnbind.MISMATCH

            marked == RecordOutcome.DONE || run.cleared == RecordOutcome.PENDING -> MeshcoreUnbind.PENDING

            else -> MeshcoreUnbind.FAILED
        }
    }

    /**
     * [unbind] with the board not up (not Ready on [address]): the record is marked pending. Only for the board this plane
     * is bound to ([boundAddress]) is the consent withdrawn to the hooks at once, so its claim and offers go now; any other
     * address cannot hold the claim, and a withdraw for it would close the Ready board's offers (the maintainer's decision,
     * 2026-11-14). Under [consentLock], so no handshake can read the record between.
     */
    private suspend fun unbindAway(address: String): MeshcoreUnbind {
        val read = readEntry(address)
        val entry = read?.getOrNull()
        if (read != null && entry == null) {
            // No record: still withdrawn for the bound board, so a claim left by an earlier failure goes too (security N2).
            revoke(null, address, null)
            return MeshcoreUnbind.NOTHING
        }
        if (read == null) counters.recordReadFailures.incrementAndGet()
        val marked = markAhead(null, address, entry?.digest)
        return if (marked == RecordOutcome.FAILED) MeshcoreUnbind.FAILED else MeshcoreUnbind.PENDING
    }

    /**
     * One write to the record, bounded (lora N2). A timeout cancels the wait, not a statement already running, so a write
     * that did not answer in time is read back once ([landed], itself bounded): it counts as done if the row shows it (lora
     * L2). False — counted — for a throw, or a timeout whose row does not show it.
     */
    private suspend fun recordWrite(
        landed: suspend MeshcoreSetupJournal.() -> Boolean,
        write: suspend () -> Unit,
    ): Boolean {
        val done = bounded { write() } || bounded { if (!records.landed()) error("not landed") }
        if (!done) counters.recordWriteFailures.incrementAndGet()
        return done
    }

    /** What a setup's write-ahead came to. */
    private sealed interface Ahead {
        /** The planned record is on disk, pending: the setup may write the board. */
        data object Written : Ahead

        /** Nothing proven, the record unread, or the write did not land: nothing written to the board. */
        data object Failed : Ahead

        /** Another key's record at the address, and no [ReplaceToken] for it as it is now: refused, nothing written (Stage 7). */
        class OtherBoard(
            val token: ReplaceToken,
        ) : Ahead
    }

    /**
     * The setup's write-ahead (security N1): the record before it is kept on the connection ([Conn.setupPrior]) and the
     * planned one, merged as [keepRecord] merges, written pending under the rule. [Ahead.Written] only when that write itself
     * landed.
     *
     * **A different board is refused here** (ADR 2026-11.gsqu's Stage 7 amendment, lora L5, security B5), on the actor —
     * the record's only writer while the board is up (the maintainer's decision, 2026-11-13), which is the exclusion
     * `consentLock` gives the handshake; the lock itself is never taken in an actor op, since an unbind holds it while it
     * waits on the actor — after the proof and before any write: a record at the address for another key is the same
     * hardware reset or reflashed (a new key at the same address), or another board, and replacing it silently would lose
     * the only memory of what Knit changed there. Unless [replace] was issued for exactly the record read now
     * ([MeshcoreSetupEntry.fingerprint]), the setup answers [Ahead.OtherBoard] with a token for it and writes nothing.
     */
    private suspend fun writeAhead(
        c: Conn,
        record: MeshcoreSetupRecord,
        replace: ReplaceToken?,
    ): Ahead {
        val digest = c.provenKey?.let(MeshcoreHooks::digest) ?: return Ahead.Failed
        val prior = readEntry(c.address) ?: return Ahead.Failed
        val found = prior.getOrNull()
        otherBoard(c, found, digest, replace)?.let { return Ahead.OtherBoard(it) }
        c.setupPrior = found
        val merged =
            c.setupPrior
                ?.takeIf { it.digest == digest }
                ?.record
                ?.let { merge(it, record) } ?: record
        // A re-setup of a board this connection consents for keeps its consent (and so its taught set) through the erase.
        val consented = c.consent && c.setupPrior?.let { it.digest == digest && !it.pending } == true
        // Open before the write when consent is kept over it (the 5b-2 tail's reviews): a cancellation inside the write is
        // then revoked by [runSetup]'s `finally`. Without a kept consent the write revokes first, so nothing stands over it.
        c.setupOpen = consented
        val ahead =
            withdrawWrite(
                c,
                c.address,
                { entryOf(c.address)?.let { it.digest == digest && it.pending } == true },
                keepConsent = consented,
            ) {
                records.put(c.address, digest, merged, pending = true)
            }
        // A kept consent stands only over a write-ahead that landed (lora, the tail's review): anything else revokes it now.
        if (consented && ahead != RecordOutcome.DONE) revoke(c, c.address, digest)
        c.setupOpen = ahead == RecordOutcome.DONE
        return if (ahead == RecordOutcome.DONE) Ahead.Written else Ahead.Failed
    }

    /**
     * The token for [found] when it is another key's record than [digest] and [replace] was not issued for it as it is now
     * (Stage 7: [writeAhead]'s refusal, counted and logged), else null.
     */
    private fun otherBoard(
        c: Conn,
        found: MeshcoreSetupEntry?,
        digest: String,
        replace: ReplaceToken?,
    ): ReplaceToken? {
        if (found == null || found.digest == digest || replace?.matches(c.address, found.fingerprint()) == true) return null
        counters.differentBoards.incrementAndGet()
        log("meshcore setup refused: this phone set up another board at that address")
        return found.tokenAt(c.address)
    }

    /** A setup that left nothing of its own on the board: the record goes back to what it was before its write-ahead. */
    private suspend fun restorePrior(c: Conn) {
        val prior = c.setupPrior
        c.setupPrior = null
        // The record goes back to what it was, its consent decided below. The setup stays open until that write returns (the
        // 5b-2 tail's reviews): a cancellation inside the grant leaves the write-ahead's pending record, and [runSetup]'s
        // `finally` revokes a kept consent over it; a grant that fails revokes on its own, and a withdraw write first.
        if (prior == null) {
            withdrawWrite(c, c.address, { entryOf(c.address) == null }) { records.clear(c.address) }
        } else if (!prior.pending && prior.digest == c.provenKey?.let(MeshcoreHooks::digest)) {
            // The prior record consented for this very board: put back as a grant, so the connection keeps its consent.
            grantWrite(c, { entryOf(c.address)?.let { it.digest == prior.digest && !it.pending } == true }) {
                records.put(c.address, prior.digest, prior.record, pending = false)
            }
        } else {
            withdrawWrite(c, c.address, { entryOf(c.address)?.let { it.digest == prior.digest && it.pending == prior.pending } == true }) {
                records.put(c.address, prior.digest, prior.record, prior.pending)
            }
        }
        c.setupOpen = false
    }

    /** What a record write came to under the record write rule. */
    private enum class RecordOutcome {
        /** The write landed. */
        DONE,

        /** A withdraw write did not land, and the record was marked pending: its cleanup runs at the next connection. */
        PENDING,

        /** A grant write did not land, or a withdraw write and its pending mark both did not. */
        FAILED,
    }

    /**
     * **The record write rule** (the maintainer's decision, 2026-11-14), for a write that removes or withdraws consent
     * (Restore's clear, an unbind's clear or mark, the cleanup's clear, a failed setup's leftovers): consent is no on this
     * connection whatever happens, published at once so the claim goes ([revoke]); a write that does not land (after the
     * read-back) marks the record pending so the cleanup Restore runs at the next connection; only a mark that also fails
     * is [RecordOutcome.FAILED]. Never a success unless the write landed. [isMark]: the write is the mark itself.
     */
    private suspend fun withdrawWrite(
        c: Conn?,
        address: String,
        landed: suspend MeshcoreSetupJournal.() -> Boolean,
        isMark: Boolean = false,
        digest: String? = null,
        keepConsent: Boolean = false,
        write: suspend () -> Unit,
    ): RecordOutcome {
        // Consent no first (security N2): a cancellation between the write and the revoke can never leave a consent standing.
        // The one exception, [keepConsent]: a re-setup's write-ahead on a board this connection already consents for, whose
        // taught set must survive the erase (lora, 5b-2 tail) — its record is pending on disk all the same.
        if (!keepConsent) revoke(c, address, digest)
        val done = recordWrite(landed, write)
        return when {
            done -> RecordOutcome.DONE
            isMark -> RecordOutcome.FAILED
            recordWrite({ entryOf(address)?.pending == true }, markOf(address)) -> RecordOutcome.PENDING
            else -> RecordOutcome.FAILED
        }
    }

    /** The pending mark as a write: a mark of no row is no mark (N1), never counted as landed. */
    private fun markOf(address: String): suspend () -> Unit = { if (!records.markPending(address)) error("no record to mark") }

    /** [withdrawWrite] of the pending mark alone. */
    private suspend fun markAhead(
        c: Conn?,
        address: String,
        digest: String? = null,
    ): RecordOutcome =
        withdrawWrite(c, address, { entryOf(address)?.pending == true }, isMark = true, digest = digest, write = markOf(address))

    /**
     * The record write rule for a write that grants consent (a finished setup's record): consent yes only once it landed;
     * one that did not leaves consent no (published) and the record as it was. True when it landed.
     */
    private suspend fun grantWrite(
        c: Conn,
        landed: suspend MeshcoreSetupJournal.() -> Boolean,
        write: suspend () -> Unit,
    ): Boolean {
        if (!recordWrite(landed, write)) {
            revoke(c, c.address, null)
            return false
        }
        c.consentUnread = false
        c.consent = true
        publishConsent(c)
        return true
    }

    /**
     * Consent no for [address], published at once: on the connection [c] when it is that board's, else — the board away —
     * a withdraw for the plane's bound board only (the maintainer's decision, 2026-11-14).
     */
    private fun revoke(
        c: Conn?,
        address: String,
        digest: String?,
    ) {
        if (c != null) {
            c.consent = false
            c.cleanup = null
            // A withdrawal is an answer: the claim goes now, not at a re-read an earlier lost proof had owed (security).
            c.consentUnread = false
            c.consentBackoffMs = 0
            publishConsent(c)
        } else if (address == boundAddress) {
            val known =
                digest ?: _consent.value
                    ?.takeIf { it.address == address }
                    ?.digest
                    .orEmpty()
            _consent.value = MeshcoreConsent(address, known, granted = false, key = null)
        }
    }

    /** [step] within [RECORD_TIMEOUT_MS]: false for a throw or a timeout; cancellation passes through. */
    private suspend fun bounded(step: suspend () -> Unit): Boolean =
        runCatching { withTimeoutOrNull(RECORD_TIMEOUT_MS) { step() } != null }
            .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
            .getOrDefault(false)

    /** The pending cleanup of this board ([readConsent]), as the connection's first job: Restore, and the row goes if it finished. */
    private suspend fun runCleanup(
        c: Conn,
        record: MeshcoreSetupRecord,
    ) {
        c.cleanup = null
        // Proven again before it acts: the proof at the read covered the consent, this one the writes. A proof nobody
        // answered is asked again on the backoff, whose re-read queues the cleanup again (lora L3); one that does not verify
        // leaves it for the next connection.
        when (proveKey(c)) {
            Proof.PROVEN -> Unit
            Proof.UNANSWERED -> return unreadLater(c)
            Proof.REFUSED -> return
        }
        val done = runRestore(c, record, clearContacts = true) as? MeshcoreRestoreResult.Done
        val clean = done != null && done.failed.isEmpty()
        // Under the rule: a clear that does not land leaves the record pending, so the next connection repeats the cleanup.
        if (clean) withdrawWrite(c, c.address, { entryOf(c.address) == null }) { records.clear(c.address) }
        log("lora cleanup kind=meshcore done=$clean contacts=${done?.contactsRemoved ?: 0}")
        refreshSetup(c, c.address)
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
                    c.digest = c.self?.key?.let(MeshcoreHooks::digest)
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

    /** A connection comes up: no route known (the reconcile's listing reads them), the first drain due, then [publishReady]. */
    private fun ready(c: Conn) {
        // A reciprocal PATH our board sent before the last connection ended, its route never read: booked at the longest path.
        reciprocalOwed.forEach { _ -> _air.tryEmit(MeshcoreAir.ReciprocalPath(MeshcoreProto.PATH_FIELD, now())) }
        reciprocalOwed.clear()
        headroomPolicy.reset()
        airRing.reset()
        pins.onReady(c.self?.key?.let(MeshcorePins::addrOf))
        c.steps.clear()
        c.removalFailed.clear()
        c.contacts = null
        c.reconcileWanted = true
        reconcilePending = true
        c.drainWanted = true
        c.lastPollAt = now()
        readyAt = now()
        // The consent the handshake read, before anything reads this board as Ready.
        publishConsent(c)
        publishReady(c)
        // The reconcile's decision is owed at once (a board not set up for Knit drops it there).
        poke.trySend(Unit)
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
                manualAdd = self.manualAdd,
                dialled = c.address,
                publicSlot = c.publicSlot,
                sharesPosition = self.sharesPosition,
                advertName = self.name,
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
        // Slot 0's name and whether its key is Public, for the Public mirror's gate and face (Stage 6); never its secret.
        if (index == PUBLIC_SLOT) c.publicSlot = MeshcorePublicSlot.of(info.name, info.secret)
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
        // Our record of the sender as last read here: what our board's answer is priced along, and a DM's zero-hop hearing.
        val route = pins.routeOf(BoardAddr.MeshCore(f.prefix))
        val kind =
            MeshcorePacket(
                MeshcorePacket.Type.CONTACT_TEXT,
                null,
                f.pathLen,
                f.txtType,
                null,
                f.timestamp,
                c.live,
                decoded != null,
                f.text,
                senderRoute = route,
            )
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

    /**
     * A `0x81` / `0x8F` for [key] (binding rule "sends" 2): when Knit taught it, a read of its learned route — never a write:
     * the firmware's path is kept (ADR 2026-11.gsqu's 2026-11-19 amendment) — or a rate-limited re-teach of a deleted one.
     */
    private fun onPathPush(
        c: Conn,
        key: ByteArray,
        deleted: Boolean,
    ) {
        val addr = MeshcorePins.addrOf(key) ?: return
        if (!deleted) {
            // The firmware learned a route: what the cache held is stale until the queued read lands (lora NB1), and a flight
            // to it past its reset may count the path as learned (lora NB3).
            if (pins.isTaught(addr)) pins.forgetRoute(addr)
            flights.values.filter { it.to == addr }.forEach { it.pathPushed = true }
        }
        // A contact gone frees its slot in the count the Ready's listing read (Stage 5b-2: one listing a Ready, kept here).
        if (deleted) c.contacts = c.contacts?.let { (it - 1).coerceAtLeast(0) }
        when (pins.onPush(key, deleted, now())) {
            MeshcorePins.PushAction.IGNORED -> counters.foreignPathPushes.incrementAndGet()
            MeshcorePins.PushAction.MARKED -> counters.reteachesCoalesced.incrementAndGet()
            MeshcorePins.PushAction.QUEUED -> poke.trySend(Unit)
        }
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

    /**
     * One low-priority contact job under sends and the drain: a consent re-read or an owed bind (Stage 5d) when due, the
     * Ready reconcile's listing first (a board set up for Knit, once the taught set is known), then its steps one at a time,
     * then a targeted route read or re-teach.
     */
    private suspend fun runOneJob(c: Conn) {
        if (runConsentJob(c)) return
        if (reconcileOrDrop(c)) return
        val step = c.steps.removeFirstOrNull()
        if (step != null) {
            runStep(c, step)
        } else {
            val (key, job) = pins.nextJob() ?: return
            when (job) {
                MeshcorePins.Job.READ -> runRead(c, key)
                MeshcorePins.Job.TEACH -> runLiveTeach(c, key)
            }
        }
        if (c.steps.isNotEmpty() || pins.hasJobs()) poke.trySend(Unit)
    }

    /**
     * A route read a path push (or a confirmed flood) queued: [key]'s record read back and its route cached, nothing written.
     * When a flooded DM of ours was confirmed to it, the reciprocal PATH our board sent along the route it learned is reported
     * at that route's length ([MeshcoreAir.ReciprocalPath]), or at the longest path when the read failed, so it is never
     * under-booked.
     */
    private suspend fun runRead(
        c: Conn,
        key: ByteArray,
    ) {
        val addr = MeshcorePins.addrOf(key) ?: return
        val route = readRoute(c, key)
        counters.routeReads.incrementAndGet()
        if (reciprocalOwed.remove(addr)) {
            val pathBytes = route?.takeIf { it != MeshcoreProto.PATH_UNKNOWN }?.let(MeshcoreTraits::pathBytesOf) ?: MeshcoreProto.PATH_FIELD
            _air.tryEmit(MeshcoreAir.ReciprocalPath(pathBytes, now()))
        }
    }

    /**
     * The consent's own work, first when due: a record re-read, an owed bind (Stage 5d), or the pending cleanup. True when
     * the tick is spent.
     */
    private suspend fun runConsentJob(c: Conn): Boolean {
        if (rereadConsent(c) || rebindIfOwed(c)) return true
        val record = c.cleanup ?: return false
        runCleanup(c, record)
        poke.trySend(Unit)
        return true
    }

    /** A record read that failed, due again (security N2): read on the actor, published, and the reconcile owed if it consents. */
    private suspend fun rereadConsent(c: Conn): Boolean {
        if (!c.consentUnread || now() < c.consentRetryAt) return false
        readConsent(c)
        publishConsent(c)
        if (knitBoard(c)) {
            c.reconcileWanted = true
            reconcilePending = true
        }
        poke.trySend(Unit)
        return true
    }

    /**
     * The Ready reconcile's turn, or a board Knit did not set up, whose route reads and re-teach jobs are dropped (the 5b-2
     * review's B1). True when the tick is spent.
     */
    private suspend fun reconcileOrDrop(c: Conn): Boolean {
        val knit = knitBoard(c)
        if (c.reconcileWanted && (taughtKnown || !knit)) {
            c.reconcileWanted = false
            reconcilePending = false
            if (knit) {
                reconcile(c)
            } else {
                // Owed and dropped: a Ready on a board this connection does not consent for (5c-2b's mesh-lab review).
                counters.reconcilesDropped.incrementAndGet()
                log("meshcore reconcile dropped: board not set up for Knit")
            }
            poke.trySend(Unit)
            return true
        }
        if (!knit) {
            c.steps.clear()
            pins.clearJobs()
        }
        return !knit
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

                    // A Public post is written under the timestamp fixed when it was queued (Stage 6): none, none written.
                    is SendPurpose.PublicPost -> {
                        purpose.timestampSecs?.let { sendPublic(c, payload, it, purpose.name, handle) } ?: unsupported()
                    }

                    // The bridge's raw payload (Stage 7): the room post's write, on a board this connection consents for only.
                    is SendPurpose.Debug -> {
                        sendDebug(c, payload, purpose.channel, handle)
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
     * The debug bridge's raw payload (`…debug.LORATX --es kind meshcore`, ADR 2026-11.gsqu's Stage 7 amendment): the same
     * channel data on the same bound Knit slot as an own room post ([sendRoom]), never slot 0, and only while this connection
     * consents for a board set up for Knit ([knitBoard]) — asked here, at the write, as the bridge asked it at enqueue. On the
     * actor like every send, so no other write can take its answer or a DM flight's `RESP_SENT`.
     */
    private suspend fun sendDebug(
        c: Conn,
        payload: ByteArray,
        slot: Int,
        handle: UInt,
    ): SendResult =
        when {
            slot == PUBLIC_SLOT -> SendResult.Rejected(handle, MeshcoreRefusal.SLOT_NOT_KNIT)
            !knitBoard(c) -> SendResult.Rejected(handle, MeshcoreRefusal.NOT_SET_UP)
            else -> sendRoom(c, payload, slot, handle)
        }

    /**
     * A post on the board's Public slot (0) as a channel text, under the sender [timestampSecs] fixed when it was queued (ADR
     * 2026-11.gsqu's Stage 6 amendment). The firmware prefixes the board's own `node_name` (`BaseChatMesh.cpp:523`), so the
     * gate the hooks asked at enqueue is asked again here, on this connection, at the write (lora B1, security B2): the board
     * set up for Knit ([knitBoard]: its Knit slot and consent), slot 0 holding a channel that is not Knit's, and the board's
     * name the one the post's frame carries ([consented], what the user consented and the frame was priced under) and
     * postable (ADR 2026-11.f4d3). Both are read again first (`SELF_INFO` and slot 0's `CHANNEL_INFO`, two round
     * trips a post, a post at most every 30 s): a companion serves BLE, USB and Wi-Fi clients at once
     * (`MultiSerialInterface`, `main.cpp:15-70`), so another client can rename the board or clear or re-key slot 0 under this
     * connection, and a Restore on our own actor moves the name too. No answer is `Busy`: the post is owed, never written
     * under a name or a slot nobody read. The firmware trims the whole to 160 B silently (`BaseChatMesh.cpp:527`), so
     * the encoder's refusal is the one size gate. A `NOT_FOUND` here is no free packet, not a missing slot (`getChannel`
     * takes any index under the slot count, `BaseChatMesh.cpp:926-931`).
     */
    private suspend fun sendPublic(
        c: Conn,
        payload: ByteArray,
        timestampSecs: Long,
        consented: MeshcoreHandle?,
        handle: UInt,
    ): SendResult {
        // A board with no free packet is asked nothing: its retries must not cost two reads each.
        if (headroomPolicy.holding) return SendResult.Busy
        if (selfOf(c) == null || readSlot(c, PUBLIC_SLOT) == null) return SendResult.Busy
        // A refusal here is news the Ready did not carry (another client renamed the board or cleared slot 0): the Ready is
        // published again with what was just read, so the face, `canPost` and the hooks' enqueue gate refuse up front from
        // now on rather than each post claiming the floor and ending here (lora-reviewer's 6a pass).
        if (!knitBoard(c)) {
            publishReady(c)
            return SendResult.Rejected(handle, MeshcoreRefusal.NOT_SET_UP)
        }
        val slot = c.publicSlot
        if (slot == null || slot.empty || slot.knit) {
            publishReady(c)
            return SendResult.Rejected(handle, MeshcoreRefusal.NO_PUBLIC_SLOT)
        }
        val name = requireNotNull(c.self).name
        // The name the post was consented and priced under, carried by its frame (ADR 2026-11.f4d3): another client may have
        // renamed the board while it waited, and the firmware would put the new name in front of it. The `SELF_INFO` read just
        // above already published the Ready with the new name ([takeSelf]), so the composer re-asks from it; nothing aired.
        nameRefusal(name, consented)?.let { return SendResult.Rejected(handle, it) }
        val bytes = MeshcoreProto.sendChannelText(PUBLIC_SLOT, timestampSecs, payload, name) ?: return SendResult.TooLarge
        val judged = writeJudged(c, bytes)
        return when (judged.answer) {
            Answer.OK -> queued(handle)
            Answer.ERR -> onSendErr(handle, judged.errCode, notFoundIsBusy = true)
            Answer.NONE -> SendResult.Timeout(handle)
        }
    }

    /**
     * Why a Public post may not go out under the board's [name] as just read (ADR 2026-11.f4d3), or null: it is not the
     * [consented] name the post was queued under, or it breaks the postable rules.
     */
    private fun nameRefusal(
        name: ByteArray,
        consented: MeshcoreHandle?,
    ): Int? =
        when {
            consented == null || !consented.matches(name) -> MeshcoreRefusal.NAME_CHANGED
            MeshcoreNames.check(name) != MeshcoreNameCheck.OK -> MeshcoreRefusal.NAME_UNPOSTABLE
            else -> null
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
     * A compact DM to [to] (ADR 2026-11.gsqu §2 and its 2026-11-19 amendment): refused unless the session holds its key on
     * a board set up for Knit, then attempt 0 on the route the board holds. A route this connection has not read, or holds
     * as UNKNOWN, is read first (a BLE read, no air: lora NB1); one that reads UNKNOWN — the last flight's reset with no
     * path learned since — is written to zero hops before the attempt, so a contact's first try is never a network flood
     * (the user's decision (b)); a read or a write that fails refuses it with nothing sent
     * ([MeshcoreRefusal.ROUTE_UNWRITTEN]). A record missing reads nothing and answers the send's own `NOT_FOUND`, which
     * the core teaches from. The answer is the first attempt's; the rest are the flight's ladder ([flightsDue]).
     */
    private suspend fun sendDirect(
        c: Conn,
        payload: ByteArray,
        to: BoardAddr.MeshCore,
        handle: UInt,
    ): SendResult {
        if (payload.isEmpty() || payload.size > MeshcoreProto.DIRECT_SEAL_CAP) return SendResult.TooLarge
        // Off a board set up for Knit nothing is sent or written (the 5b-2 review's B1): the user's own contacts stay theirs.
        // Refused under this send's own handle, so the core acts on every refusal, not the first (lora N2).
        val key = pins.keyFor(to)?.takeIf { knitBoard(c) } ?: return SendResult.Rejected(handle, MeshcoreRefusal.NO_RECORD)
        if (ackTable.full(now()) || headroomPolicy.holding) return SendResult.Busy
        val flight = Flight(handle, to, key, Cobs.encode(payload), wallClock() / MS_PER_SECOND, startedAt = now())
        if (!firstRoute(c, flight)) {
            val code = if (flight.budgetRefused) MeshcoreRefusal.ROUTE_BUDGET_SPENT else MeshcoreRefusal.ROUTE_UNWRITTEN
            return SendResult.Rejected(handle, code)
        }
        val result = attempt(c, flight)
        if (result is SendResult.Queued || result is SendResult.Timeout) flights[handle] = flight
        return result
    }

    /**
     * [flight]'s route before attempt 0: read over BLE whenever the cache holds nothing or UNKNOWN (lora NB1: a path the board
     * learned since a reset, its `0x81` not yet read back, is never written over with zero hops by a send replayed from a
     * backlog), and written to zero hops when it reads UNKNOWN. False when a read or that write failed.
     */
    private suspend fun firstRoute(
        c: Conn,
        flight: Flight,
    ): Boolean {
        val cached = pins.routeOf(flight.to)
        if (cached == null || cached == MeshcoreProto.PATH_UNKNOWN) {
            val found = readContactOn(c, flight.key) ?: return false
            found.record?.let { pins.noteRoute(flight.to, it.outPathLen) }
        }
        val route = pins.routeOf(flight.to)
        flight.startedOnPath = route != null && route != MeshcoreProto.PATH_UNKNOWN && !MeshcoreTraits.isZeroHopRoute(route)
        return route != MeshcoreProto.PATH_UNKNOWN || writeZeroHop(c, flight)
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
                onAttemptSent(c, flight, code, answer)
                SendResult.Queued(flight.handle, QueueInfo(lastHeadroom ?: MeshcoreHeadroom.POOL, MeshcoreHeadroom.POOL, now()))
            }

            is McFrame.Err -> {
                if (answer.code == MeshcoreProto.ERR_NOT_FOUND) pins.forgetRoute(flight.to)
                onSendErr(flight.handle, answer.code, notFoundIsBusy = false)
            }

            else -> {
                // It may be on the board: counted as flown, its code kept, so a confirmation still finds it.
                onAttemptSent(c, flight, code, null)
                SendResult.Timeout(flight.handle)
            }
        }
    }

    /**
     * An attempt the board took ([sent] its `RESP_SENT`, or null when none came): its ack window opened, and the route it
     * took reported for booking ([MeshcoreAir.DirectRouted]) when it was not zero hops, and a flood marked (the ladder's one). A
     * flood is the board's own word (`RESP_SENT` flag 1); a direct attempt's hops come back out of its `est_timeout`
     * ([MeshcoreTraits.directHopsOf]), else off the route last read. An attempt with no `SENT` is booked at the larger of a
     * flood and the path it was meant to take (the design pass's finding 3).
     */
    private fun onAttemptSent(
        c: Conn,
        flight: Flight,
        code: Int,
        sent: McFrame.Sent?,
    ) {
        flight.attemptsSent++
        flight.attemptAt = now()
        flight.estTimeoutMs = (sent?.estTimeoutMs ?: DEFAULT_EST_TIMEOUT_MS).coerceIn(MIN_EST_TIMEOUT_MS, MAX_EST_TIMEOUT_MS)
        flight.deferredSince = null
        ackTable.onSent(code, flight.handle, now(), ackWindow(flight.estTimeoutMs))
        val held = pins.routeOf(flight.to)?.takeIf { it != MeshcoreProto.PATH_UNKNOWN }
        val heldBytes = held?.let(MeshcoreTraits::pathBytesOf) ?: 0
        val air =
            when {
                sent == null -> {
                    if (pins.routeOf(flight.to) == MeshcoreProto.PATH_UNKNOWN) flight.flooded = true
                    if (MeshcoreTraits.isZeroHopRoute(pins.routeOf(flight.to))) flight.zeroAttempts++
                    MeshcoreAir.DirectRouted(flight.handle, flight.text.size, flooded = true, pathBytes = heldBytes, atMs = now())
                }

                sent.flood -> {
                    // No path on the record: the flight's one flood (the ladder's, or a reset another client wrote).
                    flight.flooded = true
                    counters.directFlooded.incrementAndGet()
                    log("meshcore direct flooded")
                    MeshcoreAir.DirectRouted(flight.handle, flight.text.size, flooded = true, pathBytes = 0, atMs = now())
                }

                else -> {
                    directAiring(c, flight, sent, held)
                }
            }
        air?.let(_air::tryEmit)
    }

    /**
     * A direct attempt's route as [sent] reports it: its hops out of the `est_timeout` ([MeshcoreTraits.directHopsOf]), else
     * off the record [held] as last read; the gauge set, and what the path adds reported, if anything.
     */
    private fun directAiring(
        c: Conn,
        flight: Flight,
        sent: McFrame.Sent,
        held: Int?,
    ): MeshcoreAir? {
        val hashSize = held?.let(MeshcoreTraits::hashSizeOf) ?: 1
        val hops =
            c.self?.let(::saneRadio)?.let { MeshcoreTraits.directHopsOf(sent.estTimeoutMs, flight.text.size, it, hashSize) }
                ?: held?.and(MeshcoreProto.PATH_HOPS_MASK)
                ?: 0
        counters.routeHops.set(hops.toLong())
        // Counted off the record it was sent on (read back just before), never the inverted guess.
        if (MeshcoreTraits.isZeroHopRoute(pins.routeOf(flight.to))) flight.zeroAttempts++
        if (hops == 0) return null
        log("meshcore direct hops=$hops")
        return MeshcoreAir.DirectRouted(flight.handle, flight.text.size, false, hops * hashSize, now())
    }

    /**
     * A `SEND_CONFIRMED` for a code of ours ends that DM's flight, whichever attempt it confirms; a late one too. A flight
     * that flooded was confirmed through its receiver's PATH return, which our board answered with a reciprocal PATH
     * (`Mesh.cpp:173-178`): owed to the ledger once the route it went along is read ([runRead]).
     */
    private fun onConfirmed(f: McFrame.SendConfirmed) {
        val handle = ackTable.onConfirmed(f.ack) ?: return
        val flight = flights.remove(handle)
        if (flight == null) {
            // The flight already ended Unreachable or with no outcome: the core reads a late ack as the board having taken it.
            _outcomes.tryEmit(PacketOutcome.Acked(handle, byDestination = true, proof = AckProof.VALID))
            return
        }
        if (flight.flooded) {
            reciprocalOwed += flight.to
            // The board just learned a route (the PATH return): the UNKNOWN the reset read back is stale (lora NB1).
            pins.forgetRoute(flight.to)
            pins.queueRead(flight.to)
            poke.trySend(Unit)
        }
        _outcomes.tryEmit(PacketOutcome.Acked(handle, byDestination = true, proof = AckProof.VALID, flown = flight.attemptsSent))
    }

    /**
     * The flights whose attempt went unanswered, each taken up its ladder ([advance]); one waiting out the grace after its
     * flood ends when that runs out, as one past [MAX_FLIGHT_MS] does.
     */
    private suspend fun flightsDue(c: Conn) {
        for (flight in flights.values.toList()) {
            if (flight.handle !in flights) continue
            val grace = flight.graceUntil
            when {
                // The session's own bound, which the core's deadline backs (Stage 5).
                now() - flight.startedAt >= MAX_FLIGHT_MS -> {
                    endFlight(flight, unreachable = true)
                }

                grace != null -> {
                    if (now() >= grace) {
                        log("meshcore direct flood unanswered")
                        endFlight(flight, unreachable = true)
                    }
                }

                now() >= flight.attemptAt + flight.estTimeoutMs + RETRY_SLACK_MS -> {
                    advance(c, flight)
                }
            }
        }
    }

    /**
     * One due [flight]: its next rung prepared once per unanswered attempt ([nextRung]; a retry held behind a full table or
     * pool keeps the rung it already wrote, as the 5c-2a amendment's round 2 kept one read a held retry), then held or
     * retried. Every rung's write runs inside `await`, which dispatches a late `SEND_CONFIRMED` inline, so the flight is
     * asked for again after each.
     */
    private suspend fun advance(
        c: Conn,
        flight: Flight,
    ) {
        if (flight.routedFor != flight.attemptsSent) {
            flight.routedFor = flight.attemptsSent
            if (!nextRung(c, flight) || flight.handle !in flights) return
        }
        if (!stillTaught(flight)) return
        if (ackTable.full(now()) || headroomPolicy.holding) hold(flight) else retry(c, flight)
    }

    /**
     * Whether [flight]'s key is still one Knit teaches (security NB2): a contact blocked or removed mid-flight leaves the taught
     * set, and nothing more airs to it — the flight ends with no outcome.
     */
    private fun stillTaught(flight: Flight): Boolean {
        if (pins.teaches(flight.key)) return true
        log("meshcore direct flight ended: no longer taught")
        endFlight(flight, unreachable = false)
        return false
    }

    /**
     * The ladder (ADR 2026-11.gsqu's 2026-11-19 amendment: the lora-reviewer design pass, and the user's decision of the same
     * day for a flight that starts at zero hops): each rung chosen from the record's state, read back first (a BLE read, no
     * air: the route may have moved silently, as the 5c-2a amendment found), and a flight flooding at most once:
     * - a flight that starts on a learned path of N > 0 hops: along it, then zero hops, then reset and flood;
     * - one that starts at zero hops or UNKNOWN: zero hops, zero hops again, then reset and flood;
     * - the zero-hop rung is one `ADD_UPDATE_CONTACT` at `out_path_len 0` a flight, never on a `0x81` ([writeZeroHop]), and
     *   only where the record does not already read zero hops;
     * - the flood rung asks the core first ([floodRung]); after the flood, one more `est_timeout` and the end ([afterFlood]).
     * A key no longer taught ends the flight with no outcome, asked before the read and after it (security NB2). False when the
     * flight ended or waits out its grace instead; a record gone or unread, or a rung's write that fails, ends it `Unreachable`.
     */
    private suspend fun nextRung(
        c: Conn,
        flight: Flight,
    ): Boolean {
        if (!flight.flooded && flight.attemptsSent >= ATTEMPTS) {
            endFlight(flight, unreachable = true)
            return false
        }
        if (!stillTaught(flight)) return false
        val route = readRoute(c, flight.key)
        if (flight.handle !in flights || !stillTaught(flight)) return false
        return when {
            flight.flooded -> {
                afterFlood(flight, route)
            }

            route == null -> {
                endFlight(flight, unreachable = true)
                false
            }

            else -> {
                beforeFlood(c, flight, route)
            }
        }
    }

    /**
     * The rung before the flight's flood on a record read at [route]: along a path a `0x81` named since the reset, once; zero
     * hops written where it reads otherwise and none was
     * this flight; zero hops again while fewer zero-hop attempts went than the flight's two (one started on a learned path);
     * else the flood rung.
     */
    private suspend fun beforeFlood(
        c: Conn,
        flight: Flight,
        route: Int,
    ): Boolean {
        val zeroHop = MeshcoreTraits.isZeroHopRoute(route)
        val zeroTries = if (flight.startedOnPath) 1 else 2
        return when {
            // A path the board learned mid-flight (a 0x81 since the reset, read back at N > 0 hops) is tried before any rung
            // writes over it (lora's follow-up NB); spent once, so a later rung falls through to the ladder.
            flight.pathPushed && !zeroHop && route != MeshcoreProto.PATH_UNKNOWN && flight.attemptsSent < ATTEMPTS -> {
                flight.pathPushed = false
                true
            }

            !zeroHop && !flight.zeroWritten -> {
                writeZeroHop(c, flight).also { if (!it) endFlight(flight, unreachable = true) }
            }

            zeroHop && flight.zeroAttempts < zeroTries -> {
                true
            }

            else -> {
                floodRung(c, flight)
            }
        }
    }

    /**
     * The ladder's zero-hop rung: [flight]'s contact written as Knit's record at `out_path_len 0` and read back — one
     * `ADD_UPDATE_CONTACT`, at most once a flight (the design pass's blocking finding), and only for a key the taught set
     * still holds ([writeKnitRecord]). True when it reads back at zero hops. Past the key's daily ladder writes (security NB3,
     * [MeshcorePins.spendLadderWrite]) nothing is written: true on a known route, sent on as read; false on UNKNOWN, which
     * would flood before zero hops — refused at send ([MeshcoreRefusal.ROUTE_BUDGET_SPENT], never a missing key), the flight
     * ended mid-ladder.
     */
    private suspend fun writeZeroHop(
        c: Conn,
        flight: Flight,
    ): Boolean {
        flight.zeroWritten = true
        // Past the budget a known route is sent on as read; an UNKNOWN one is refused, never flooded before zero hops were
        // tried (the user's decision (b), kept over the budget: the re-probe asks again the next UTC day).
        if (!spendLadderWrite(flight)) {
            flight.budgetRefused = pins.routeOf(flight.to) == MeshcoreProto.PATH_UNKNOWN
            return !flight.budgetRefused
        }
        val written =
            writeKnitRecord(c, flight.key, fresh = false) == ContactWrite.WRITTEN && MeshcoreTraits.isZeroHopRoute(pins.routeOf(flight.to))
        if (written) counters.zeroHopWrites.incrementAndGet() else log("meshcore zero-hop write failed")
        return written
    }

    /** One of [flight]'s key's daily ladder writes, or false — counted (`meshcoreLadderWritesDeferred`) — past the budget. */
    private fun spendLadderWrite(flight: Flight): Boolean {
        if (pins.spendLadderWrite(flight.to, utcDay())) return true
        counters.ladderWritesDeferred.incrementAndGet()
        log("meshcore ladder write deferred: the key's daily budget is spent")
        return false
    }

    /**
     * The ladder's flood rung: the core asked first whether the DM is still wanted (its `RideGate`: a link, a spool cover, a
     * block, its ✓✓ since — the design pass's finding 4), ending the flight with no outcome when it is not; then `RESET_PATH`,
     * read back UNKNOWN, so the next attempt floods and its PATH return teaches both boards the route. A confirmation that
     * lands while the core is asked or the reset is out ends the flight there, and nothing floods. Past the key's daily ladder
     * writes nothing is reset, and the attempt goes on the route as read.
     */
    private suspend fun floodRung(
        c: Conn,
        flight: Flight,
    ): Boolean {
        val wanted =
            runCatching { withTimeoutOrNull(FLOOD_GATE_TIMEOUT_MS) { observer?.floodWanted(flight.handle) ?: true } }
                .getOrElse { if (it is kotlinx.coroutines.CancellationException) throw it else null } == true
        if (flight.handle !in flights) return false
        if (!wanted) {
            counters.floodsSkipped.incrementAndGet()
            log("meshcore direct flood skipped: no longer wanted")
            endFlight(flight, unreachable = false)
            return false
        }
        if (!spendLadderWrite(flight)) return true
        flight.pathPushed = false
        val reset = runResetPath(c, flight.key) == ContactWrite.WRITTEN
        if (flight.handle !in flights) return false
        if (!reset) {
            log("meshcore route reset failed")
            endFlight(flight, unreachable = true)
            return false
        }
        counters.routeResets.incrementAndGet()
        log("meshcore route reset")
        return true
    }

    /**
     * After the flight's one flood went unanswered (the design pass's finding 2), its record's [route] as just read back: an
     * attempt along a path the board learned since this flight's reset — a `0x81` for it came ([Flight.pathPushed], lora
     * NB3: another flight's zero-hop write is no learned path) — while attempts remain; else one more `est_timeout` for a late
     * PATH return's ack ([Flight.graceUntil]), then the end. Never a second flood.
     */
    private fun afterFlood(
        flight: Flight,
        route: Int?,
    ): Boolean {
        val learned = route != null && route != MeshcoreProto.PATH_UNKNOWN && flight.pathPushed
        return when {
            learned && flight.attemptsSent < ATTEMPTS -> {
                true
            }

            flight.graceUntil == null -> {
                flight.graceUntil = now() + flight.estTimeoutMs
                false
            }

            else -> {
                endFlight(flight, unreachable = true)
                false
            }
        }
    }

    /** A due retry held behind a full ack table or pool; past [DEFER_LIMIT_MS] the DM ends. */
    private fun hold(flight: Flight) {
        val since = flight.deferredSince ?: now().also { flight.deferredSince = it }
        if (now() - since > DEFER_LIMIT_MS) endFlight(flight, unreachable = true)
    }

    private suspend fun retry(
        c: Conn,
        flight: Flight,
    ) {
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

    // --- contacts: the route and the plumbing ---

    /** What a contact read found: [record] null for a key the board does not hold. */
    private class Found(
        val record: ContactRecord?,
    )

    /** `GET_CONTACT_BY_KEY`, matched by key; null when nothing answered (after the barrier). */
    private suspend fun readContactOn(
        c: Conn,
        key: ByteArray,
    ): Found? {
        // Never into the send map (security B1): a contact read is not a key Knit taught.
        val bytes = MeshcoreProto.getContactByKey(key) ?: return Found(null)
        return when (val f = requestMatched(c, bytes) { it is McFrame.Contact && it.record.key.contentEquals(key) }) {
            is McFrame.Contact -> Found(f.record)
            is McFrame.Err -> if (f.code == MeshcoreProto.ERR_NOT_FOUND) Found(null) else null
            else -> null
        }
    }

    /**
     * [key]'s record read and its route cached ([MeshcorePins.noteRoute], a taught key only): its raw `out_path_len`, or null
     * when the board holds no record for it (the route forgotten) or nothing answered. A BLE read, nothing on the air.
     */
    private suspend fun readRoute(
        c: Conn,
        key: ByteArray,
    ): Int? {
        val addr = MeshcorePins.addrOf(key) ?: return null
        val found = readContactOn(c, key) ?: return null
        val record =
            found.record ?: run {
                pins.forgetRoute(addr)
                return null
            }
        if (pins.teaches(key)) pins.noteRoute(addr, record.outPathLen)
        return record.outPathLen
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
        if (!knitBoard(c)) return ContactWrite.NOT_SET_UP
        val existing = MeshcorePins.addrOf(key)?.let { readContactOn(c, key) } ?: return ContactWrite.FAILED
        val record = existing.record ?: return teachFresh(c, key)
        MeshcorePins.addrOf(key)?.takeIf { pins.teaches(key) }?.let { pins.noteRoute(it, record.outPathLen) }
        // A board set up for Knit is Knit's: a contact on a taught key is made Knit's record. Any other board's is the user's.
        // By its routing alone (ADR 2026-11.f4d3): a peer's own advert renames its contact, which is no reason to rewrite it.
        val overwrite = knitBoard(c) && pins.teaches(key) && !MeshcoreContacts.isKnitRouting(record)
        return if (overwrite) writeKnitRecord(c, key, fresh = false) else ContactWrite.ALREADY_PRESENT
    }

    /**
     * A queued teach (a key the taught set gained while Ready, or a contact the board deleted), counted: written toward the
     * key's daily bound, failed re-queued once (never looped: the next Ready's reconcile writes it otherwise). Contested on
     * this connection, nothing is written (security B3: `prefixFree` checks taught keys only).
     */
    private suspend fun runLiveTeach(
        c: Conn,
        key: ByteArray,
    ) {
        val addr = MeshcorePins.addrOf(key) ?: return
        if (pins.keyFor(addr) == null) return // no longer taught, or contested since it was queued
        c.teachWriteLanded = false
        val result = runTeach(c, key)
        // Every write the board answered OK is a flash write, whatever its read-back found (the 5c-2b review's N1).
        if (result == ContactWrite.WRITTEN || c.teachWriteLanded) pins.onLiveTeachSpent(key, utcDay(), result == ContactWrite.WRITTEN)
        when (result) {
            ContactWrite.WRITTEN -> {
                counters.liveTeachesWritten.incrementAndGet()
            }

            ContactWrite.FAILED -> {
                counters.liveTeachesFailed.incrementAndGet()
                pins.retryTeachOnce(key)
            }

            // Already Knit's, no free slot (the reconcile's count decides at the next Ready), or left the set meanwhile.
            else -> {}
        }
    }

    /** The UTC day of the wall clock, the live teach bound's ([MeshcorePins.queueTeach]). */
    private fun utcDay(): Long = wallClock() / LoraContactPolicy.DAY_MS

    /** A teach of a key the board does not hold: only into a free slot, by the local count (one count read when none). */
    private suspend fun teachFresh(
        c: Conn,
        key: ByteArray,
    ): ContactWrite {
        val capacity = requireNotNull(c.info).maxContactsHalf * 2
        if (c.contacts == null) c.contacts = countContacts(c)?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt()
        val count = c.contacts ?: return ContactWrite.FAILED
        if (count >= capacity) {
            log("meshcore teach refused: no free contact slot")
            return ContactWrite.NO_FREE_SLOT
        }
        return writeKnitRecord(c, key, fresh = true)
    }

    /**
     * Writes [key]'s Knit record (a CHAT favourite named `Knit xxxx`, zero-hop) and reads it back as exactly that; a [fresh]
     * one takes a slot in the local count. Its route is cached as read back; also the DM ladder's zero-hop rung. Only for a
     * key the taught set holds, asked right before the write and again after the read-back (the 5c-2b review's B1): the
     * hooks may drop it (a block, a removal, a contest)
     * while the reads before the write are out, and a key written for nobody would stand on the board until the next Ready,
     * its DMs decrypted and acked. One dropped meanwhile is [ContactWrite.NOT_FOUND] and never taught again here, so the
     * removal the hooks queued behind this write ([removeUntaught]) finds it untaught and removes it.
     */
    private suspend fun writeKnitRecord(
        c: Conn,
        key: ByteArray,
        fresh: Boolean,
    ): ContactWrite {
        val addr = MeshcorePins.addrOf(key)
        return when {
            // Taught and not contested on this connection (security B3: `prefixFree` reads taught keys only, so a contested
            // prefix would get a second contact; the 5c-2b review's N2). The reconcile's own writes are never contested
            // before they run: `MeshcoreContacts.plan` leaves contested keys out of its writes, and a blocked write contests
            // itself only when its sharer's removal failed, before it gets here.
            addr == null || pins.keyFor(addr) == null -> ContactWrite.NOT_FOUND

            // A prefix another taught key holds is never written: the board would send both keys' DMs to the first (B3).
            !pins.prefixFree(key) -> ContactWrite.FAILED

            !writtenAndReadBack(c, key, fresh) -> ContactWrite.FAILED

            // Dropped while the write was out: never taught back, so the queued removal removes it.
            !pins.teaches(key) -> ContactWrite.NOT_FOUND

            else -> ContactWrite.WRITTEN
        }
    }

    /** [key]'s Knit record written and read back as exactly that; a [fresh] one takes a slot in the local count. */
    private suspend fun writtenAndReadBack(
        c: Conn,
        key: ByteArray,
        fresh: Boolean,
    ): Boolean {
        val write = MeshcoreProto.addUpdateContact(ContactRecord.knit(key, MeshcoreNames.forKey(key)), withPosition = false)
        if (writeAnswered(c, write, FLASH_TIMEOUT_MS) != Answer.OK) return false
        c.teachWriteLanded = true
        if (fresh) c.contacts = c.contacts?.plus(1)
        val back = readContactOn(c, key)?.record ?: return false
        MeshcorePins.addrOf(key)?.takeIf { pins.teaches(key) }?.let { pins.noteRoute(it, back.outPathLen) }
        return MeshcoreContacts.isKnitRecord(back)
    }

    /** A removal outside setup's erase and Restore's own: only on a board set up for Knit (the 5b-2 review's B1). */
    private suspend fun runRemove(
        c: Conn,
        key: ByteArray,
    ): ContactWrite = if (knitBoard(c)) removeOn(c, key) else ContactWrite.NOT_SET_UP

    /** `REMOVE_CONTACT`, read back gone: the erase's and Restore's, under their own transaction, and [runRemove]'s. */
    private suspend fun removeOn(
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
        MeshcorePins.addrOf(key)?.let(pins::forgetRoute)
        val gone = readContactOn(c, key)?.let { it.record == null } == true
        if (gone) c.contacts = c.contacts?.let { (it - 1).coerceAtLeast(0) }
        return if (gone) ContactWrite.WRITTEN else ContactWrite.FAILED
    }

    /**
     * Whether the connected board is set up for Knit: Knit's slot on it, and this connection's consent (this phone's setup
     * record for the address and the board's key digest, proven). The board's name is no part of it since ADR 2026-11.f4d3:
     * it is the owner's handle, which they may change in any app, and it stood in for nothing the slot and the record miss.
     */
    private fun knitBoard(c: Conn): Boolean = knitLooking(c) && c.consent

    /** The board reads as Knit's (identified, its Knit slot on it): a claim any client on it can write, never consent alone. */
    private fun knitLooking(c: Conn): Boolean = c.self != null && c.knitSlot != null

    // --- the reconcile at Ready (Stage 5b-2): one listing, then one step a tick ---

    /** The board's whole contact table as one `GET_CONTACTS` listed it, and the count its `CONTACTS_START` gave. */
    private class Listing(
        val records: List<ContactRecord>,
        val count: Int,
        /** Ended by its bound or deadline, not by `END_OF_CONTACTS`. */
        val cut: Boolean = false,
    ) {
        /** A listing short of its count (another client's frames lost or mixed in, a contact never modified): never acted on whole. */
        val complete: Boolean get() = !cut && records.size == count
    }

    /** One step of a reconcile: a removal, or a write of Knit's record ([blockedBy]: a prefix sharer that must go first). */
    private sealed interface Step {
        class Remove(
            val key: ByteArray,
        ) : Step

        class Write(
            val key: ByteArray,
            val fresh: Boolean,
            val blockedBy: ByteArray?,
        ) : Step
    }

    /**
     * `GET_CONTACTS` from `since` 0: every contact on the board, once (the iterator is one for every client,
     * `MyMesh.cpp:1238-1257`; while another client lists, ours is refused `ERR BAD_STATE` and asked again after a backoff).
     * Null when the board would not list.
     */
    private suspend fun listContacts(c: Conn): Listing? {
        repeat(COUNT_ATTEMPTS) { n ->
            when (val f = requestMatched(c, MeshcoreProto.listContacts()) { it is McFrame.ContactsStart }) {
                is McFrame.ContactsStart -> {
                    return collectListing(c, f.count)
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

    private suspend fun collectListing(
        c: Conn,
        count: Long,
    ): Listing? {
        val records = ArrayList<ContactRecord>()
        // Bounded in length and in time (security N5): past the count and a slack, or the deadline, it ends short — and a
        // short listing removes nothing.
        val deadline = now() + LISTING_DEADLINE_MS
        // null while reading; true at END_OF_CONTACTS; false when the bound or the deadline cut it.
        var ended: Boolean? = null
        while (ended == null) {
            val left = minOf(REQUEST_TIMEOUT_MS, deadline - now())
            ended =
                if (left <= 0 || records.size > count + LISTING_SLACK) {
                    false
                } else {
                    when (val f = await(c, left) { it is McFrame.Contact || it == McFrame.EndOfContacts }) {
                        is McFrame.Contact -> null.also { if (records.size < MAX_LISTED) records += f.record }

                        McFrame.EndOfContacts -> true

                        // A wait the deadline clipped ran out on the deadline, not on a silent board: cut short, not failed.
                        else -> if (left < REQUEST_TIMEOUT_MS) false else return null
                    }
                }
        }
        val cut = ended == false
        counters.contactListings.incrementAndGet()
        val listing = Listing(records, count.coerceIn(0, MAX_LISTED.toLong()).toInt(), cut)
        if (!listing.complete) counters.listingsShort.incrementAndGet()
        return listing
    }

    /**
     * The Ready reconcile on a board set up for Knit (the maintainer's Stage 5 decisions): one listing, then
     * [MeshcoreContacts.plan] makes the table the taught set — removals, then overwrites, then fresh writes, one a tick,
     * bounded per Ready. Every taught key's listed route is cached as read (a learned path kept: ADR 2026-11.gsqu's
     * 2026-11-19 amendment). A listing that failed or came short removes nothing; a send reads the route it needs.
     */
    private suspend fun reconcile(c: Conn) {
        val listing = listContacts(c)
        if (listing == null || !listing.complete) {
            counters.reconcilesSkipped.incrementAndGet()
            log("meshcore reconcile skipped: the board's contacts could not be listed whole")
            return
        }
        planFrom(c, listing.records, listing.count)
    }

    /** Queues [MeshcoreContacts.plan] over [records] (the board's count [count]) as this connection's steps. */
    private fun planFrom(
        c: Conn,
        records: List<ContactRecord>,
        count: Int,
    ) {
        val capacity = requireNotNull(c.info).maxContactsHalf * 2
        val plan = MeshcoreContacts.plan(pins.taughtKeys(), records, count, capacity)
        c.contacts = maxOf(count, records.size)
        records.forEach { r -> MeshcorePins.addrOf(r.key)?.takeIf { pins.teaches(r.key) }?.let { pins.noteRoute(it, r.outPathLen) } }
        // This listing's contests replace the last connection's, which held until now (lora N3).
        pins.setContested(plan.contested.mapNotNull(MeshcorePins::addrOf))
        counters.prefixContests.addAndGet(plan.contested.size.toLong())
        counters.reconcileDeferred.addAndGet(plan.deferred.toLong())
        c.steps.clear()
        plan.removals.forEach { c.steps += Step.Remove(it) }
        plan.overwrites.forEach { c.steps += Step.Write(it, fresh = false, blockedBy = plan.blockedBy[ByteBuffer.wrap(it)]) }
        plan.teaches.forEach { c.steps += Step.Write(it, fresh = true, blockedBy = plan.blockedBy[ByteBuffer.wrap(it)]) }
        counters.reconciles.incrementAndGet()
        log("meshcore reconcile listed=${records.size} $plan")
        if (c.steps.isNotEmpty()) poke.trySend(Unit)
    }

    /** One reconcile step. A write whose prefix sharer did not go is contested instead: nothing is sent to that prefix. */
    private suspend fun runStep(
        c: Conn,
        step: Step,
    ) {
        when (step) {
            is Step.Remove -> removeStep(c, step)
            is Step.Write -> writeStep(c, step)
        }
    }

    private suspend fun removeStep(
        c: Conn,
        step: Step.Remove,
    ) {
        // Taught again since the plan (lora N1): its contact stays.
        if (pins.teaches(step.key)) return
        when (runRemove(c, step.key)) {
            ContactWrite.WRITTEN, ContactWrite.NOT_FOUND -> {
                counters.reconcileRemoved.incrementAndGet()
            }

            else -> {
                c.removalFailed += ByteBuffer.wrap(step.key)
                counters.reconcileFailed.incrementAndGet()
            }
        }
    }

    private suspend fun writeStep(
        c: Conn,
        step: Step.Write,
    ) {
        val addr = MeshcorePins.addrOf(step.key) ?: return
        // The taught set may have moved since the plan: a key no longer taught is not written.
        if (!pins.teaches(step.key)) return
        if (step.blockedBy?.let { ByteBuffer.wrap(it) in c.removalFailed } == true) {
            pins.contest(addr)
            counters.prefixContests.incrementAndGet()
            return
        }
        val capacity = requireNotNull(c.info).maxContactsHalf * 2
        if (step.fresh && (c.contacts ?: capacity) >= capacity) {
            counters.reconcileDeferred.incrementAndGet()
            return
        }
        val counter =
            when {
                writeKnitRecord(c, step.key, step.fresh) != ContactWrite.WRITTEN -> counters.reconcileFailed
                step.fresh -> counters.reconcileTaught
                else -> counters.reconcileOverwritten
            }
        counter.incrementAndGet()
    }

    private suspend fun runResetPath(
        c: Conn,
        key: ByteArray,
    ): ContactWrite {
        if (!knitBoard(c)) return ContactWrite.NOT_SET_UP
        val bytes = MeshcoreProto.resetPath(key) ?: return ContactWrite.FAILED
        var err = 0
        when (writeAnswered(c, bytes) { err = it }) {
            Answer.OK -> Unit
            Answer.ERR -> return if (err == MeshcoreProto.ERR_NOT_FOUND) ContactWrite.NOT_FOUND else ContactWrite.FAILED
            Answer.NONE -> return ContactWrite.FAILED
        }
        return if (readRoute(c, key) == MeshcoreProto.PATH_UNKNOWN) ContactWrite.WRITTEN else ContactWrite.FAILED
    }

    // --- setup and restore (§4) ---

    /**
     * Reads first — `DEVICE_INFO`, `SELF_INFO`, every slot, the default scope (reported, never written), the auto-add
     * config — and refuses with nothing written when any fails or the board has no slot but Public. Then the erase
     * ([erase]: the board becomes Knit's), then the writes, each read back: auto-add (chat and overwrite-oldest off),
     * `[38, 1]`, the `Knit abcd` name, and the channel last. A failed write puts back what was written ([runRestore]); what
     * the erase removed stays removed. The radio is never touched (ADR 045), and nothing here can factory-reset the board.
     */
    private suspend fun runSetup(
        c: Conn,
        replace: ReplaceToken?,
    ): MeshcoreSetupResult {
        // Only a bonded board that proves its key is erased and set up (security N1): an unproven link could otherwise leave
        // priors a later proven connection would keep for good.
        if (!bonded(c) || proveKey(c) != Proof.PROVEN) {
            if (!bonded(c)) counters.unbondedInert.incrementAndGet()
            return MeshcoreSetupResult.Failed("this board is not bonded, or did not prove its key: nothing written", left = null)
        }
        // Bound before the grant publishes the consent (Stage 5d): a setup that grants it publishes the signature with it.
        bind(c)
        val plan =
            when (val read = setupReads(c)) {
                is SetupRead.Planned -> read.plan
                is SetupRead.Refused -> return read.result
            }
        // The board read again must be the board that proved its key (security F1): a key that changed in between is no setup.
        // A belt: a changed key already ended the session in [takeSelf], so this guard is reached by nothing today.
        if (c.self?.key?.contentEquals(c.provenKey) != true) {
            counters.keyUnproven.incrementAndGet()
            log("meshcore setup refused: the board's key changed after its proof")
            return MeshcoreSetupResult.Failed("the board's key changed after it was proven: nothing written", left = null)
        }
        // A setup cut off from its write-ahead on (a session end, a throw, a cancellation, inside the write-ahead itself)
        // leaves its record pending: consent is revoked on the way out, so a consent kept through a re-setup's erase never
        // outlives it (lora, the tail's review; the write-ahead inside the `try`, the 5b-2 tail's reviews).
        try {
            // The intent before any board write (security N1): the planned record, merged, kept pending, so a setup cut off
            // anywhere after — a session end, a process death, a failed grant — leaves a cleanup that puts the board back.
            val refused =
                when (val ahead = writeAhead(c, plan.record, replace)) {
                    // Written: the setup goes on to the board.
                    Ahead.Written -> {
                        null
                    }

                    Ahead.Failed -> {
                        MeshcoreSetupResult.Failed(
                            "the setup's record could not be written first: nothing written",
                            left = null,
                        )
                    }

                    is Ahead.OtherBoard -> {
                        MeshcoreSetupResult.DifferentBoard(ahead.token)
                    }
                }
            return refused ?: setupWrites(c, plan)
        } finally {
            if (c.setupOpen) {
                c.setupOpen = false
                revoke(c, c.address, null)
            }
        }
    }

    /** The erase, then the setup's writes, each read back, then [finishSetup]; a failure rolls back ([rollback]). */
    private suspend fun setupWrites(
        c: Conn,
        plan: SetupPlan,
    ): MeshcoreSetupResult {
        val erased =
            when (val e = erase(c)) {
                is Erase.Done -> {
                    e
                }

                is Erase.Failed -> {
                    restorePrior(c)
                    return MeshcoreSetupResult.Failed("could not erase the board's ${e.what}", left = null)
                }
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
                SetupStep("name", plan.renames, MeshcoreProto.setAdvertName(plan.name)) {
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
        return finishSetup(c, record, erased)
    }

    /**
     * A setup whose writes all read back: the slot is Knit's, then the record first, merged into one an earlier setup of
     * this board left (lora L1), then the consent (one owner, durable write first, under the record write rule); then the
     * Ready. A Ready published here never re-reads the record.
     */
    private suspend fun finishSetup(
        c: Conn,
        record: MeshcoreSetupRecord,
        erased: Erase.Done,
    ): MeshcoreSetupResult {
        val slot = record.slot
        c.knitSlot = slot
        c.slots = c.slots.map { if (it.index == slot) MeshcoreSlot(slot, empty = false, knit = true) else it }
        slotReading = MeshcoreSlotReading(slot, knit = true, atMs = now())
        val kept = keepRecord(c, record, pending = false)
        publishReady(c)
        if (!kept) {
            // Under the rule: a grant write that did not land leaves no consent and the record as it was.
            log("meshcore setup record not kept: no consent")
            return MeshcoreSetupResult.Failed("the setup record could not be kept: no consent", left = null)
        }
        // The board is Knit's now (with consent) and its table what the erase left: the reconcile teaches the taught set from it
        // without a listing of its own.
        // The grant landed: the setup is closed, its consent standing.
        c.setupOpen = false
        c.setupPrior = null
        planFrom(c, erased.kept, erased.kept.size)
        log("meshcore setup slot=$slot written=${record.slotWritten} erased contacts=${erased.contacts} slots=${erased.slots}")
        return MeshcoreSetupResult.Done(
            slot,
            alreadyPresent = !record.slotWritten,
            record = record,
            erasedContacts = erased.contacts,
            clearedSlots = erased.slots,
            address = c.address,
            digest = c.digest,
        )
    }

    /** What the erase did, or the read or write it could not make. */
    private sealed interface Erase {
        /** [contacts] removed and [slots] cleared; [kept] the taught Knit records left in place. */
        class Done(
            val contacts: Int,
            val slots: Int,
            val kept: List<ContactRecord>,
        ) : Erase

        class Failed(
            val what: String,
        ) : Erase
    }

    /**
     * The setup's erase (the maintainer's Stage 5 decisions): `REMOVE_CONTACT` each contact on the board, then each channel
     * slot other than Public (0) and Knit's cleared, one command at a time, each read back. A contact that is already Knit's
     * record for a key Knit teaches is kept, so a setup run again writes no flash for it. A listing that fails or comes short
     * erases nothing; a removal or a clear that does not read back ends it, leaving what it removed removed.
     */
    private suspend fun erase(c: Conn): Erase {
        val listing = listContacts(c)
        if (listing == null || !listing.complete) return Erase.Failed("contacts")
        val (kept, gone) = listing.records.partition { MeshcoreContacts.isKnitRecord(it) && pins.teaches(it.key) }
        var contacts = 0
        for (record in gone) {
            when (removeOn(c, record.key)) {
                ContactWrite.WRITTEN, ContactWrite.NOT_FOUND -> contacts++
                else -> return Erase.Failed("contacts")
            }
        }
        c.contacts = kept.size
        var slots = 0
        for (slot in c.slots.filter { it.index != PUBLIC_SLOT && !it.knit && !it.empty }) {
            if (!writeVerified(c, MeshcoreProto.clearChannel(slot.index)) { readSlot(c, slot.index)?.empty == true }) {
                return Erase.Failed("channels")
            }
            c.slots = c.slots.map { if (it.index == slot.index) MeshcoreSlot(it.index, empty = true, knit = false) else it }
            slots++
        }
        counters.setupErasedContacts.addAndGet(contacts.toLong())
        counters.setupClearedSlots.addAndGet(slots.toLong())
        return Erase.Done(contacts, slots, kept)
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
        /** Whether the name step runs ([renamesAt]). */
        val renames: Boolean,
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
        readSelf(c)
        val scanned = c.self != null && scanSlots(c)
        val scope = if (scanned) ask<McFrame.DefaultScope>(c, MeshcoreProto.getDefaultScope()) else null
        val autoadd = scope?.let { ask<McFrame.Autoadd>(c, MeshcoreProto.getAutoadd()) }
        val self = c.self
        if (autoadd == null || self == null) return SetupRead.Refused(setupFailed(if (scanned) "settings" else "identity"))
        c.defaultScope = requireNotNull(scope).set
        val existing = c.slots.firstOrNull { it.knit }
        // The erase clears every slot but Public and Knit's, so any other slot is Knit's to take: an empty one first.
        val slot =
            existing?.index ?: c.slots.firstOrNull { it.empty && it.index != PUBLIC_SLOT }?.index
                ?: c.slots.firstOrNull { it.index != PUBLIC_SLOT }?.index
                ?: return SetupRead.Refused(MeshcoreSetupResult.NoFreeSlot)
        val name = MeshcoreNames.forKey(self.key)
        // A board this phone already set up keeps its name: it is the owner's handle now (ADR 2026-11.f4d3), and its record
        // already holds the name from before Knit, which [merge] keeps. Only a first setup writes the default.
        val entry = readEntry(c.address) ?: return SetupRead.Refused(setupFailed("setup record"))
        val renames = renamesAt(entry.getOrNull(), self)
        val wantAutoadd = autoadd.config and KNIT_AUTOADD_OFF.inv()
        val record =
            MeshcoreSetupRecord(
                slot = slot,
                slotWritten = existing == null,
                priorName = self.name.takeIf { renames },
                priorManualAdd = self.manualAdd.takeIf { it != MANUAL_ADD_ON },
                priorAutoadd = autoadd.config.takeIf { it != wantAutoadd },
            )
        return SetupRead.Planned(SetupPlan(record, name, wantAutoadd, renames))
    }

    /**
     * Whether a setup of the board reading [self] renames it `Knit abcd` (ADR 2026-11.f4d3): only when this phone holds no
     * standing record of that board at the address ([entry]: the same key's digest, not pending) — a re-setup keeps the
     * owner's handle — and the board does not already carry the default. A pending record is no setup that finished: a
     * re-setup over it renames, and [merge] keeps the name the first one recorded.
     */
    private fun renamesAt(
        entry: MeshcoreSetupEntry?,
        self: McFrame.SelfInfo,
    ): Boolean {
        val held = entry?.consents(MeshcoreHooks.digest(self.key)) == true
        return !held && !MeshcoreNames.isDefault(self.name, self.key)
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
        val back = runRestore(c, done, clearContacts = false) as? MeshcoreRestoreResult.Done
        val clean = back != null && back.failed.isEmpty()
        // Put back whole: the record as it was before the write-ahead. Else what could not be put back is kept, merged, as a
        // pending cleanup in one write: never a consent (N1).
        if (clean || !done.changedAnything) restorePrior(c) else keepRecord(c, done, pending = true)
        return MeshcoreSetupResult.Failed(
            "the board did not take its $what",
            left = done.takeIf { !clean && it.changedAnything },
            address = c.address,
            digest = c.digest,
        )
    }

    /** A flash write ([FLASH_TIMEOUT_MS]) believed only once [verify] reads it back: a bare `OK` may be another client's. */
    private suspend fun writeVerified(
        c: Conn,
        bytes: ByteArray,
        verify: suspend () -> Boolean,
    ): Boolean = writeAnswered(c, bytes, FLASH_TIMEOUT_MS) != Answer.NONE && verify()

    private suspend fun selfOf(c: Conn): McFrame.SelfInfo? = ask<McFrame.SelfInfo>(c, MeshcoreProto.appStart())?.also { takeSelf(c, it) }

    /** `SELF_INFO` again for a setup's reads: none without `DEVICE_INFO`, and taken as [takeSelf] takes it. */
    private suspend fun readSelf(c: Conn) {
        val self = c.info?.let { ask<McFrame.SelfInfo>(c, MeshcoreProto.appStart()) }
        if (self == null) c.self = null else takeSelf(c, self)
    }

    /**
     * Takes a `SELF_INFO` read mid-connection. A key other than the one the connection identified with (another client
     * imported one, `CMD_IMPORT_PRIVATE_KEY`) ends the session (security S2): the next connection is a new board, its
     * consent read and proved afresh, so no record is ever written under one key's digest while another's is published.
     */
    private fun takeSelf(
        c: Conn,
        self: McFrame.SelfInfo,
    ) {
        if (c.digest != null && MeshcoreHooks.digest(self.key) != c.digest) {
            counters.keyChanged.incrementAndGet()
            throw EndSession(SessionEnd("$REASON_HANDSHAKE the board's key changed"))
        }
        val moved = c.self?.sharesPosition != self.sharesPosition
        c.self = self
        // Another client turned the board's position on or off (Stage 7): the face and the consent sheet read the Ready.
        val ready = (_state.value as? BoardState.Ready)?.detail as? MeshcoreDetail
        if (moved && ready?.dialled == c.address && ready.sharesPosition != self.sharesPosition) {
            publishReady(c)
        } else if (c.edits == 0) {
            republishIfRenamed(c)
        }
    }

    /**
     * Publishes the Ready again when the board's name is not the one it carries (ADR 2026-11.f4d3): another client renamed
     * the board under this connection (a companion serves BLE, USB and Wi-Fi clients at once), so the face, the composer's
     * cap and its consent read the handle the firmware will prefix. Never inside a setup, Restore or rename ([edit]): those
     * publish their own Ready, so a name step's read-back lands no Ready, and no `lora ready` line, of its own.
     */
    private fun republishIfRenamed(c: Conn) {
        val self = c.self ?: return
        val ready = (_state.value as? BoardState.Ready)?.detail as? MeshcoreDetail ?: return
        if (ready.dialled == c.address && !ready.handle.matches(self.name)) publishReady(c)
    }

    /**
     * Runs [block] as one of the actor's board transactions (a setup, a Restore or a cleanup, a rename): no `SELF_INFO` read
     * inside it publishes a Ready on a name change ([takeSelf]); once the outermost one ends, a name it left different from the
     * last published Ready is published once.
     */
    private suspend fun <T> edit(
        c: Conn,
        block: suspend () -> T,
    ): T {
        c.edits++
        val result =
            try {
                block()
            } finally {
                c.edits--
            }
        // Only after a transaction that returned: one that ended the session publishes nothing.
        if (c.edits == 0 && _state.value is BoardState.Ready) republishIfRenamed(c)
        return result
    }

    private suspend fun autoaddIs(
        c: Conn,
        want: Int,
    ): Boolean = ask<McFrame.Autoadd>(c, MeshcoreProto.getAutoadd())?.config == want

    /**
     * Puts back what [record] says a setup changed, each only while the board still shows Knit's value there: the slot
     * is cleared only if it still holds Knit's channel and key, the name restored only if it is still Knit's, and the
     * same for the two settings. Anything else is reported, never overwritten. With [clearContacts] (a Restore, not a
     * setup's rollback) Knit's contacts go first ([removeKnitContacts]) and the taught set is forgotten.
     */
    private suspend fun runRestore(
        c: Conn,
        record: MeshcoreSetupRecord,
        clearContacts: Boolean,
    ): MeshcoreRestoreResult = edit(c) { restoreRun(c, record, clearContacts) }

    private suspend fun restoreRun(
        c: Conn,
        record: MeshcoreSetupRecord,
        clearContacts: Boolean,
    ): MeshcoreRestoreResult {
        val contactsRemoved = if (clearContacts) removeKnitContacts(c) else 0
        val done = restoreSetup(c, record)
        val failed = if (contactsRemoved == null) listOf("contacts") + done.failed else done.failed
        if (clearContacts && failed.isEmpty()) {
            pins.setTaught(emptyList())
            c.steps.clear()
        }
        log("meshcore restore cleared=${done.slotCleared} contacts=${contactsRemoved ?: "failed"} left=${done.leftAsFound} failed=$failed")
        return done.copy(failed = failed, contactsRemoved = contactsRemoved ?: 0)
    }

    /** What a setup changed, put back where it still reads as Knit's ([runRestore]'s part past the contacts). */
    private suspend fun restoreSetup(
        c: Conn,
        record: MeshcoreSetupRecord,
    ): MeshcoreRestoreResult.Done {
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
        return MeshcoreRestoreResult.Done(slotCleared, left, failed)
    }

    /**
     * Removes every contact that is Knit's — named `Knit xxxx` for its own key, or a key Knit teaches — each read back gone,
     * from one listing; a contact anyone else wrote since is left. The count removed, or null when the listing failed or
     * came short, or a removal did not read back.
     */
    private suspend fun removeKnitContacts(c: Conn): Int? {
        val listing = listContacts(c)?.takeIf { it.complete } ?: return null
        var removed = 0
        for (record in listing.records) {
            if (!record.named(MeshcoreNames.forKey(record.key)) && !pins.teaches(record.key)) continue
            when (removeOn(c, record.key)) {
                ContactWrite.WRITTEN, ContactWrite.NOT_FOUND -> removed++
                else -> return null
            }
        }
        return removed
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

        /** Slot 0 as the last read of it found it (Stage 6), or null before one. */
        var publicSlot: MeshcorePublicSlot? = null
        var defaultScope = false
        var authenticated = false
        var live = false
        var drainWanted = false
        var lastUptime: Long? = null
        var lastPollAt = 0L

        /** The board's contact count: the Ready listing's, kept as writes and removals read back; null before one. */
        var contacts: Int? = null

        /** The Ready reconcile is owed (one listing a Ready). */
        var reconcileWanted = false

        /** Whether the last Knit-record write the board answered OK: what a live teach charges its key's daily bound for. */
        var teachWriteLanded = false

        /**
         * A setup's write-ahead landed, or is being written under a kept consent, and the setup has not closed: on exit, an
         * open setup revokes ([runSetup]).
         */
        var setupOpen = false

        /** The record before a running setup's write-ahead, put back if the setup leaves nothing of its own ([restorePrior]). */
        var setupPrior: MeshcoreSetupEntry? = null

        /** The key this board proved it holds on this connection ([proveKey]): the only key a consent publishes. */
        var provenKey: ByteArray? = null

        /** The bind's verified signature on this connection ([bind]) and the key it was made over; null before one verified. */
        var bindSig: ByteArray? = null
        var bindKey: ByteArray? = null

        /** A bind that failed while the consent was being granted: asked again as an idle job at [bindRetryAt] (Stage 5d). */
        var bindOwed = false
        var bindRetryAt = 0L
        var bindBackoffMs = 0L

        /** The record could not be read at the last try: re-read as an idle job at [consentRetryAt] (security N2). */
        var consentUnread = false
        var consentRetryAt = 0L
        var consentBackoffMs = 0L

        /** The connected board's key digest (`MeshcoreHooks.digest`): whose setup record counts here. */
        var digest: String? = null

        /** This phone's consent for this board, read off its record in the handshake: never unknown at a published Ready. */
        var consent = false

        /** A pending record of this board, to clean up as the connection's first job (bonded only). */
        var cleanup: MeshcoreSetupRecord? = null

        /** How deep the actor is in a board transaction ([edit]): a setup, a Restore or cleanup, a rename. */
        var edits = 0

        /** The reconcile's steps, one a tick. */
        val steps = ArrayDeque<Step>()

        /** Keys whose removal this connection did not read back: a taught key sharing their prefix is contested. */
        val removalFailed = HashSet<ByteBuffer>()
    }

    /** One compact DM's attempts: the same [timestamp] and [text] each, a new attempt number each (Q14), up its ladder. */
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
        var deferredSince: Long? = null

        /** The attempt count the next rung was prepared for: one rung an unanswered attempt, never one a held tick. */
        var routedFor = -1

        /** Whether the zero-hop rung was written: once a flight. */
        var zeroWritten = false

        /** Whether an attempt flooded: once a flight. */
        var flooded = false

        /** The zero-hop rung found the key's daily ladder writes spent on an UNKNOWN record: refused, never flooded. */
        var budgetRefused = false

        /** Whether the flight started on a learned path of N > 0 hops: one zero-hop try then, else two (the user's decision). */
        var startedOnPath = false

        /** The attempts that went at zero hops. */
        var zeroAttempts = 0

        /** A `0x81` for the contact came since this flight's reset (lora NB3): only then is a route after the flood learned. */
        var pathPushed = false

        /** Past its flood, with no path learned: when the last wait for a late PATH return's ack ends. */
        var graceUntil: Long? = null
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

        /** The most contacts one listing keeps: past any companion's `MAX_CONTACTS` (a `DEVICE_INFO` byte, doubled). */
        private const val MAX_LISTED = 1_024

        /** [new] merged into [old], one record of the same board (lora L1): the earliest prior of each, the slot if any wrote it. */
        fun merge(
            old: MeshcoreSetupRecord,
            new: MeshcoreSetupRecord,
        ): MeshcoreSetupRecord =
            MeshcoreSetupRecord(
                slot = if (old.slotWritten) old.slot else new.slot,
                slotWritten = old.slotWritten || new.slotWritten,
                priorName = old.priorName ?: new.priorName,
                priorManualAdd = old.priorManualAdd ?: new.priorManualAdd,
                priorAutoadd = old.priorAutoadd ?: new.priorAutoadd,
            )

        private const val NONCE_BYTES = 32

        /** The longest one setup-record read or write may take on the actor before it counts as failed (lora N2). */
        private const val RECORD_TIMEOUT_MS = 2_000L

        /** Reads of the setup record a handshake tries before it reads as no consent. */
        private const val CONSENT_READS = 3

        /** The pause between two of them. */
        private const val CONSENT_RETRY_MS = 200L

        /** The first and the longest wait before a failed record read is tried again on the actor. */
        private const val CONSENT_RETRY_FIRST_MS = 10_000L
        private const val CONSENT_RETRY_MAX_MS = 600_000L

        /** The consent challenge's domain tag: never the 5d bind's (`MeshcoreBind.DOMAIN`, `knit-meshcore-bind/v1`). */
        val CONSENT_DOMAIN: ByteArray = "knit-meshcore-consent/v1".encodeToByteArray()

        /** Frames past the `CONTACTS_START` count a listing reads before it ends short: other clients' strays. */
        private const val LISTING_SLACK = 8

        /** The longest one listing may read, however steadily the frames come: then it ends short. */
        private const val LISTING_DEADLINE_MS = 60_000L

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

        /**
         * One rung at its slowest: a read, a write (a flash write for the zero-hop rung, `RESET_PATH`'s lazy one) and a
         * read-back, each timing out into a barrier.
         */
        const val ROUTE_WRITE_WORST_MS = 2 * (REQUEST_TIMEOUT_MS + BARRIER_TIMEOUT_MS) + FLASH_TIMEOUT_MS + BARRIER_TIMEOUT_MS

        /** The longest the actor waits on the core's answer before a flood rung: past it, no flood (the DM ends with no outcome). */
        const val FLOOD_GATE_TIMEOUT_MS = 5_000L

        /**
         * The longest a compact DM's flight lives in the session: every attempt at the longest `est_timeout` it takes plus
         * the retry slack, the flood's last wait for a late PATH return, two holds at [DEFER_LIMIT_MS], a rung at its
         * slowest before every attempt and after the last (the read before attempt 0, the zero-hop write, the reset, the
         * read after the flood — the design pass kept a write term for each rung that writes flash), and the core's answer
         * before the flood. Past it the flight ends `Unreachable`. Stage 5's `MeshcoreTraits.deadlineMs` is a backstop above
         * this, never below.
         */
        const val MAX_FLIGHT_MS =
            ATTEMPTS * (MAX_EST_TIMEOUT_MS + RETRY_SLACK_MS) + MAX_EST_TIMEOUT_MS + 2 * DEFER_LIMIT_MS +
                (ATTEMPTS + 1) * ROUTE_WRITE_WORST_MS + FLOOD_GATE_TIMEOUT_MS

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
