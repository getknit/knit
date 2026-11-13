package app.getknit.knit.mesh.lora.meshcore

import app.getknit.knit.TextLimits
import app.getknit.knit.mesh.PublicPostRefusal
import app.getknit.knit.mesh.link.FastFrameCodec
import app.getknit.knit.mesh.lora.AirPurpose
import app.getknit.knit.mesh.lora.BoardAddr
import app.getknit.knit.mesh.lora.BoardInfo
import app.getknit.knit.mesh.lora.BoardKind
import app.getknit.knit.mesh.lora.BoardKindHooks
import app.getknit.knit.mesh.lora.BoardPacket
import app.getknit.knit.mesh.lora.BoardPlane
import app.getknit.knit.mesh.lora.BoardSettings
import app.getknit.knit.mesh.lora.BoardState
import app.getknit.knit.mesh.lora.FragmentOutcome
import app.getknit.knit.mesh.lora.KeyTeach
import app.getknit.knit.mesh.lora.LORA_DIRECT_OVERHEAD
import app.getknit.knit.mesh.lora.LORA_DIRECT_TAG
import app.getknit.knit.mesh.lora.LawSampleStamp
import app.getknit.knit.mesh.lora.LinkState
import app.getknit.knit.mesh.lora.LoraBoardHolder
import app.getknit.knit.mesh.lora.LoraMeshTransport
import app.getknit.knit.mesh.lora.LoraPlaneSnapshot
import app.getknit.knit.mesh.lora.LoraStatus
import app.getknit.knit.mesh.lora.MeshcoreLawStamp
import app.getknit.knit.mesh.lora.OutboundFrame
import app.getknit.knit.mesh.lora.PacketRoute
import app.getknit.knit.mesh.lora.PlaneCore
import app.getknit.knit.mesh.lora.ProvisionMode
import app.getknit.knit.mesh.lora.ProvisionResult
import app.getknit.knit.mesh.lora.SeenStamp
import app.getknit.knit.mesh.lora.SendPurpose
import app.getknit.knit.mesh.lora.SlotCheck
import app.getknit.knit.mesh.lora.WallShift
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/**
 * What only a MeshCore board does, behind the plane core's [BoardKindHooks] (ADR 2026-11.gsqu, Stage 5a-2): the inbound
 * dispatch by slot and data type, our board's own answers to a DM booked from one place, the send-time slot guard, the
 * band's law (the ledger's radio and duty before Ready, the trailing-hour hold, its ring persisted), the setup through the
 * session, and the board's key reported for the profile. Made per plane instance by the core, over the session it drives
 * ([session]) and the core it reaches back into ([core]).
 *
 * **The board is Knit's alone** (the maintainer's Stage 5 decisions): what is not a Knit frame — a contact DM that is not a
 * compact DM, a post on a slot that is neither Knit's nor Public, Public itself until Stage 6 — is counted
 * ([MeshcorePlaneCounters]) and discarded here, before anything decodes it: never stored, logged, shown, relayed or put in
 * custody, and never a hearing ([PacketRoute.DROPPED]). The channel key is public, so the slot and the data type are read
 * first.
 *
 * Not yet: teaching contacts and the inbox claim are Stage 5b's ([teachKey] refuses, `bindMeshcoreDirect` stays unbound),
 * two-part room posts 5c's, the Public mirror Stage 6's.
 *
 * State: the duty law ([MeshcoreDutyLaw], `@Synchronized`), the board its ring is for and the band's duty, guarded by
 * [lock], which is never held across a call into the core; counters are atomics; the setup record is volatile.
 */
@Suppress("TooManyFunctions") // one seam per kind: every behaviour the core calls, grouped as BoardKindHooks groups it
internal class MeshcoreHooks(
    private val session: MeshcoreBoardOps,
    private val core: PlaneCore,
    private val scope: CoroutineScope,
    private val clock: () -> Long,
    private val log: (String) -> Unit,
    /** The wall clock: what a pre-boot term's deadline is on ([MeshcoreDutyLaw.verdict]); never compared with [clock]. */
    private val wallClock: () -> Long = System::currentTimeMillis,
    /**
     * The bound board reported its 32-byte public key, from the session that dialled [dialled] (the `dialled` race, Stage
     * 5a-2): what `MeshcoreClaimStore.setBoardKey` writes, only while that address is still the bound one.
     */
    private val onBoardKey: suspend (key: ByteArray, dialled: String?) -> Unit = { _, _ -> },
) : BoardKindHooks,
    MeshcoreSessionObserver {
    override val kind: BoardKind get() = BoardKind.MESHCORE

    /** Counts and own-board state only, for the MeshCore dump: never a key, a prefix, a name or a text. */
    val counters = MeshcorePlaneCounters()

    private val law = MeshcoreDutyLaw()
    private val lock = Any()

    /** The board the law's ring is for ([digest] of its key), or null before one identified. Under [lock]. */
    private var lawBoard: String? = null

    /**
     * The rings of other boards, by digest, most recent last and capped at [LAW_BOARDS]: restored ones waiting for their board
     * to identify, and the one a rebind left behind — so an A→B→A rebind keeps A's ring (lora-reviewer N7). Under [lock].
     */
    private val rings = LinkedHashMap<String, MeshcoreLawStamp>()

    /** The hold the pacer was last given ([applyLaw]), so a sample that changes nothing wakes nothing. Under [lock]. */
    private var heldUntil: Long? = null

    /** The band's duty, as the last Ready read it off the frequency ([LoraBand]); the strictest before one. Under [lock]. */
    private var dutyPercent: Double = MeshcoreTraits.fallbackDutyPercent

    /** Whether the last verdict held every frame: for the edge's log line and count. Under [lock]. */
    private var lawHolding = false

    /** The uptime of the last sample that asked for a save: the ring is persisted once a spacing, never per poll. */
    private var savedAtUptime: Long? = null

    /** `multi_acks` of the Ready board: how many acks it airs for a direct DM to it. */
    @Volatile
    private var multiAcks = 0

    /** What this process's last setup changed, for a Restore in the same process; persisted from Stage 5b. */
    @Volatile
    private var setupRecord: MeshcoreSetupRecord? = null

    @Volatile
    override var provisioning = false
        private set

    init {
        session.observer = this
        // Our board's floods of a DM of ours (the pin did not hold): the surcharge over the direct airing booked at send.
        scope.launch { session.air.collect(::onAir) }
    }

    // --- the face ---

    /** The link state the settings row and the field log render, the version cleaned before anything shows it. */
    override fun statusOf(
        state: BoardState,
        status: LoraStatus,
    ): LoraStatus = status.copy(state = linkStateOf(state), boardNodeNum = null)

    /** The band's law as it stands now: the MeshCore dump's `law` block and the plane's face. */
    fun lawState(): MeshcoreLawState =
        synchronized(lock) {
            val verdict = law.verdict(dutyPercent, wallClock())
            MeshcoreLawState(
                dutyPercent = dutyPercent,
                trailingTxSecs = verdict?.trailingTxSecs,
                limitSecs = verdict?.limitSecs,
                holding = verdict?.holding == true,
                releaseInSecs = verdict?.releaseInSecs,
            )
        }

    // --- the inbound dispatch ---

    /**
     * Knit's traffic, judged on what the board says about a packet before a byte of it is decoded: the channel key is public
     * (`KnitChannel.PSK`), so anyone can write a post the board decrypts.
     * - Channel data on the Knit slot ([bound], the board's own report) under [MeshcoreProto.KNIT_DATA_TYPE] is a Knit frame
     *   ([PacketRoute.KNIT]; under the burned tag, [PacketRoute.RETIRED]).
     * - A contact DM whose text decoded as COBS to a `0x11` seal's shape is a compact DM ([PacketRoute.DIRECT]); whether it
     *   opens is the core's to count. COBS decoding is no proof: anything else falls back to the text it was and is a
     *   foreign DM — never dropped unread by a throw, never a Knit frame.
     * - Everything else is counted and discarded: a foreign DM ([MeshcorePlaneCounters.foreignDms]), a post on another slot
     *   or of another data type ([MeshcorePlaneCounters.foreignPosts]), Public slot 0 until Stage 6
     *   ([MeshcorePlaneCounters.publicDiscarded]), and a `0x04` fragment until Stage 5c's reassembler
     *   ([MeshcorePlaneCounters.fragmentsRefused]).
     *
     * Every live contact DM, Knit or not, is booked here first: our board answered it already ([bookAnswer]).
     */
    override fun route(
        packet: BoardPacket,
        bound: Int?,
    ): PacketRoute {
        val mc = packet.kind as? MeshcorePacket ?: return PacketRoute.DROPPED
        return when (mc.type) {
            MeshcorePacket.Type.CONTACT_TEXT -> {
                bookAnswer(mc)
                if (isCompactDm(packet, mc)) PacketRoute.DIRECT else discard(counters.foreignDms)
            }

            MeshcorePacket.Type.CHANNEL_DATA -> {
                routeChannelData(packet, mc, bound)
            }

            MeshcorePacket.Type.CHANNEL_TEXT -> {
                discard(if (mc.slot == PUBLIC_SLOT) counters.publicDiscarded else counters.foreignPosts)
            }
        }
    }

    /** Channel data: Knit's only on the board's Knit slot, under Knit's data type, and one whole packet until 5c. */
    private fun routeChannelData(
        packet: BoardPacket,
        mc: MeshcorePacket,
        bound: Int?,
    ): PacketRoute =
        when {
            mc.slot == PUBLIC_SLOT -> {
                discard(counters.publicDiscarded)
            }

            bound == null || mc.slot != bound || mc.dataType != MeshcoreProto.KNIT_DATA_TYPE -> {
                discard(counters.foreignPosts)
            }

            packet.payload.isEmpty() -> {
                discard(counters.foreignPosts)
            }

            packet.payload[0] == LoraMeshTransport.RETIRED_CTL_TAG -> {
                PacketRoute.RETIRED
            }

            // A fragment waits for Stage 5c's reassembler: the core's is keyed on a sender this kind never names, and the
            // key is public, so anyone could poison or flush it. No 5a build sends one.
            packet.payload[0] == FastFrameCodec.TAG_FRAG -> {
                discard(counters.fragmentsRefused)
            }

            else -> {
                // Count only, no id (lora-reviewer N11): a live zero-hop Knit post, before anyone verified who wrote it — what
                // the dump sets beside `boardsHeard`, which counts verified pinned authors alone.
                if (MeshcoreTraits.heardZeroHop(packet)) counters.zeroHopKnitPosts.incrementAndGet()
                PacketRoute.KNIT
            }
        }

    private fun discard(counter: AtomicLong): PacketRoute {
        counter.incrementAndGet()
        return PacketRoute.DROPPED
    }

    /** A plain text that decoded as COBS to at least a seal's header under the `0x11` tag: a compact DM's shape. */
    private fun isCompactDm(
        packet: BoardPacket,
        mc: MeshcorePacket,
    ): Boolean =
        mc.cobs && mc.txtType == MeshcoreProto.TXT_TYPE_PLAIN && packet.payload.size >= LORA_DIRECT_OVERHEAD &&
            packet.payload[0] == LORA_DIRECT_TAG

    /**
     * Books what our board aired answering [mc], a contact DM to it, from this one place (lora-reviewer finding 7, the
     * security pass's KIND-ack item; priced against `companion-v1.18.0` by lora-reviewer N6): every **live** plain or
     * signed-plain DM, Knit's or not — the board answers before the phone reads it. One drained from the offline queue was
     * answered while we were away and is booked by nobody (§2). The ack code is 6 B for a plain text, 4 for a signed one.
     * - Direct (`path_len 0xFF`): which answer our board aired turns on its record of the sender, which this stage does not
     *   know (5b's taught set is pinned zero-hop): one **flooded** ack with no path, or along a known path at most one
     *   multi-ack and the ack ([MeshcoreTraits.boardAckPayloads]). The larger of the two is booked, as milliseconds.
     * - Flooded: one flooded PATH return at its real size, over the path the DM came by, hash size included
     *   ([MeshcoreTraits.pathReturnPlaintext]), at [AirPurpose.PATH_RETURN]. The session's own `MeshcoreAir.PathReturn`
     *   for the same packet is counted, never booked again ([onAir]).
     *
     * Under [app.getknit.knit.mesh.lora.Ceiling.FULL]: an answer is no room post, and a foreign DM must not eat the room's
     * flood share.
     */
    private fun bookAnswer(mc: MeshcorePacket) {
        val txtType = mc.txtType ?: return
        if (txtType !in MeshcoreSession.ACKED_TXT_TYPES) return
        if (!mc.live) {
            counters.answersReplayed.incrementAndGet()
            return
        }
        val airtime = core.airtime
        val now = clock()
        if (mc.direct) {
            val flooded = airtime.airMsOfPhy(MeshcoreTraits.ackPhyBytes(MeshcoreTraits.ackBytes(txtType), flooded = true))
            val alongPath =
                MeshcoreTraits
                    .boardAckPayloads(
                        multiAcks,
                        txtType,
                    ).sumOf { airtime.airMsOfPhy(MeshcoreTraits.ackPhyBytes(it, flooded = false)) }
            airtime.recordMs(maxOf(flooded, alongPath), now)
            counters.acksBooked.incrementAndGet()
        } else {
            airtime.record(MeshcoreTraits.pathReturnPlaintext(mc.pathLen, txtType), now, airtime.price(AirPurpose.PATH_RETURN))
            counters.pathReturnsBooked.incrementAndGet()
        }
    }

    /** Booked in [route] for every live contact DM, Knit's or not: nothing more to book once a compact one is opened. */
    override fun bookDirectAck(
        packet: BoardPacket,
        own: BoardAddr,
        onBound: Boolean,
    ) = Unit

    /** [route] never answers [PacketRoute.KIND]: what is not Knit's was discarded there. */
    override fun onKindPacket(packet: BoardPacket) = Unit

    /** The board's own Knit slot, as its session read it (`Ready.channel`): there is no slot setting for this kind. */
    override fun boundSlot(
        configured: Int?,
        ready: BoardState.Ready?,
    ): Int? = ready?.channel

    /**
     * Air our board spent that no send of ours booked. A [MeshcoreAir.DirectFlooded] attempt of ours was booked at send as
     * a zero-hop DM; the flood adds its scoped transport codes, so its **surcharge** is booked here: the same packet's
     * airtime flooded less direct, through [app.getknit.knit.mesh.lora.LoraAirtime.recordMs]. Not an [AirPurpose] of its
     * own: it is the difference of two airings of one packet, not a packet (decided in ADR 2026-11.gsqu's Stage 5a-2
     * amendment). Repeaters' re-airings are not our ledger's (ug4a). A [MeshcoreAir.PathReturn] is counted only: [route]
     * booked it off the packet, at the size its path gives.
     */
    private fun onAir(air: MeshcoreAir) {
        when (air) {
            is MeshcoreAir.DirectFlooded -> {
                val airtime = core.airtime
                val surcharge =
                    airtime.airMsOfPhy(MeshcoreTraits.contactTextPhyBytes(air.bytes, flooded = true)) -
                        airtime.airMsOfPhy(MeshcoreTraits.contactTextPhyBytes(air.bytes, flooded = false))
                airtime.recordMs(surcharge, clock())
                counters.floodSurcharges.incrementAndGet()
            }

            is MeshcoreAir.PathReturn -> {
                counters.pathReturnsSeen.incrementAndGet()
            }
        }
    }

    // --- the kind's own public channel: Stage 6 ---

    /** No MeshCore Public post until Stage 6 builds the mirror and its consent. */
    override suspend fun postToPublicChannel(body: String): PublicPostRefusal? = PublicPostRefusal.NOT_READY

    /** Nothing of the kind's own is ever queued (no Public post, no auto-reply on this kind). */
    override suspend fun sendKindFrame(
        frame: OutboundFrame,
        send: suspend (ByteArray, SendPurpose) -> FragmentOutcome,
    ) = Unit

    // --- the kind's limiters that outlive the process ---

    /**
     * The duty law's rings from the last process ([LoraPlaneSnapshot.meshcoreLaws]), one per board digest: the current
     * board's applied at once if it has already identified, with the hold it implies ([applyLaw], lora-reviewer N9); the
     * others kept until their board identifies ([onIdentified]). Their samples are on the board's uptime and the wall clock,
     * so [shift] has nothing to move.
     */
    override fun restoreState(
        snapshot: LoraPlaneSnapshot,
        shift: WallShift,
    ) {
        if (snapshot.meshcoreLaws.isEmpty()) return
        val applied =
            synchronized(lock) {
                var current = false
                snapshot.meshcoreLaws.forEach { stamp ->
                    if (stamp.board == lawBoard) {
                        law.restore(stateOf(stamp))
                        current = true
                    } else {
                        keepRing(stamp)
                    }
                }
                current
            }
        if (applied) applyLaw()
    }

    /** No auto-reply on this kind. */
    override fun snapshotState(shift: WallShift): List<SeenStamp> = emptyList()

    /** The law's rings: the current board's and the other boards' kept ones, capped at [LAW_BOARDS]. */
    override fun extendSnapshot(snapshot: LoraPlaneSnapshot): LoraPlaneSnapshot {
        val stamps =
            synchronized(lock) {
                val current = lawBoard?.let { board -> stampOf(board, law.snapshot()) }
                (rings.values.filter { it.board != current?.board } + listOfNotNull(current)).takeLast(LAW_BOARDS)
            }
        return if (stamps.isEmpty()) snapshot else snapshot.copy(meshcoreLaws = stamps)
    }

    /** Keeps [stamp] among the other boards' rings, the most recent last, the oldest dropped past [LAW_BOARDS]. Under [lock]. */
    private fun keepRing(stamp: MeshcoreLawStamp) {
        rings.remove(stamp.board)
        rings[stamp.board] = stamp
        while (rings.size > LAW_BOARDS) rings.remove(rings.keys.first())
    }

    // --- the send-time slot guards ---

    /** A compact DM rides no channel slot: no slot refuses it. */
    override fun directSlotRefused(bound: Int?): Boolean = false

    /**
     * The slot guard, from the session's reading (lora-reviewer finding 6): a compact DM touches no slot, so it is always
     * clear. A room post is refused only when no reading was taken on this connection, or the last one found slot [ch]
     * holding something other than Knit's channel; the age is left to the session's own re-read before the write
     * (`sendRoom`, `SLOT_TTL_MS`), the authoritative guard.
     */
    override fun checkSlot(
        frame: OutboundFrame,
        ready: BoardState.Ready,
        ch: Int,
    ): SlotCheck {
        if (frame.direct != null) return SlotCheck.Clear
        val reading = session.slotReading() ?: return SlotCheck.Refused("slot $ch has not been read on this connection — set this board up")
        return if (reading.index == ch &&
            !reading.knit
        ) {
            SlotCheck.Refused("slot $ch is not the Knit channel — set this board up")
        } else {
            SlotCheck.Clear
        }
    }

    // --- a session coming up ---

    /**
     * The `lora ready` line and the board's key for the profile, under the address the session dialled ([onBoardKey]). The
     * ledger's radio and duty went in before Ready was published ([beforeReady]). The line says the board's inbox is
     * Knit's: what is not Knit's is drained and discarded.
     */
    override fun onReady(ready: BoardState.Ready) {
        val detail = ready.detail as? MeshcoreDetail ?: return
        val air = core.airtime.snapshot(clock())
        val duty = synchronized(lock) { dutyPercent }
        log(
            "lora ready kind=meshcore v=${detail.versionCode} slot=${ready.channel} radio=${ready.radio?.let {
                "SF${it.spreadFactor}/${it.bandwidthHz}"
            }} " +
                "duty=$duty allowanceMs=${air.allowanceMs} floodCeilingMs=${air.floodCeilingMs} multiAcks=${detail.multiAcks} " +
                "scope=${detail.defaultScope} inbox=knit-only",
        )
        val key = detail.publicKey
        scope.launch { onBoardKey(key, detail.dialled) }
    }

    // --- the session's observer: on its actor, returns at once ---

    /**
     * The board identified itself: the law's ring is for this board from now. The board before it keeps its ring among the
     * others, so a rebind back finds it; this board's own kept or restored ring is applied, else the law starts empty —
     * which bounds the hour from the counter since boot, so on a 1 % band a board up for long can be held up to an hour at
     * its first connection (a cost the 5a-2 amendment records). The old board's hold goes with it.
     */
    override fun onIdentified(publicKey: ByteArray) {
        val board = digest(publicKey)
        val changed =
            synchronized(lock) {
                if (lawBoard == board) return@synchronized false
                lawBoard?.let { previous -> keepRing(stampOf(previous, law.snapshot())) }
                val kept = rings.remove(board)
                if (kept != null) law.restore(stateOf(kept)) else law.reset()
                lawBoard = board
                lawHolding = false
                savedAtUptime = null
                true
            }
        // The old board's hold is lifted by [applyLaw] itself (a verdict of nothing yet lifts it), in the same step as its
        // record of what the pacer holds: a separate lift here could land after a restored ring's hold and undo it.
        if (changed) applyLaw()
    }

    /**
     * Before Ready is published (5a-1's owed list): the ledger prices at this board's radio and allows its band's duty, so the
     * pacer never runs a Ready plane on the kind's 0.1 % fallback or the previous board's duty. A board that reported no
     * sane radio, or no frequency, leaves the ledger on the fallbacks with its duty unknown
     * ([app.getknit.knit.mesh.lora.LoraAirtime.forgetRadio]): a frame the fallback cannot carry is skipped, never dropped.
     */
    override fun beforeReady(ready: BoardState.Ready) {
        val detail = ready.detail as? MeshcoreDetail
        multiAcks = detail?.multiAcks ?: 0
        val radio = ready.radio
        val freq = detail?.freqKhz
        val duty = if (radio != null && freq != null) MeshcoreTraits.dutyPolicy(freq, radio.bandwidthHz) else null
        if (radio != null && duty != null) core.airtime.onRadio(radio, duty) else core.airtime.forgetRadio()
        synchronized(lock) { dutyPercent = duty?.dutyCyclePercent ?: MeshcoreTraits.fallbackDutyPercent }
        applyLaw()
    }

    /** One poll's sample: the law reads it, the pacer is held or released, and the ring is saved once a spacing. */
    override fun onRadioStats(
        wallMs: Long,
        uptimeSecs: Long,
        txAirSecs: Long,
    ) {
        law.onSample(wallMs, uptimeSecs, txAirSecs)
        applyLaw()
        val save =
            synchronized(lock) {
                val last = savedAtUptime
                (last == null || uptimeSecs < last || uptimeSecs - last >= MeshcoreDutyLaw.SPACING_SECS).also {
                    if (it) savedAtUptime = uptimeSecs
                }
            }
        if (save) core.limitersMoved()
    }

    /**
     * Holds every frame while the law's verdict holds, until its `releaseInSecs` (re-asked at every sample, so a quieter
     * board lifts it sooner); lifts it when the verdict clears. A hold the verdict cannot date is re-asked at the next
     * spacing. Never a 1-s tick: the pacer sleeps to the hold's end. The pacer is told only when the hold starts, lifts or
     * moves by more than [HOLD_SLACK_MS] (lora-reviewer N8: a 1-s poll must not wake it every second). The edges are
     * logged (`lora law hold`, `lora law clear`).
     */
    private fun applyLaw() {
        val now = clock()
        // One step under [lock]: the verdict, the record of the pacer's hold and the call that sets it, so two callers (the
        // session's actor, the core's restore) can never hand the pacer their answers in the other order (lora-reviewer
        // L1). Lock order: this monitor, then the pacer's and the status lock inside `holdForLaw`; nothing takes this one
        // holding those.
        val edge =
            synchronized(lock) {
                val verdict = law.verdict(dutyPercent, wallClock())
                val until =
                    if (verdict?.holding == true) now + (verdict.releaseInSecs ?: MeshcoreDutyLaw.SPACING_SECS) * MS_PER_SECOND else null
                val edge = verdict?.let(::lawEdge)
                if (verdict?.holding == true && !lawHolding) counters.lawHolds.incrementAndGet()
                lawHolding = verdict?.holding == true
                if (holdMoved(until, heldUntil)) {
                    heldUntil = until
                    core.holdForLaw(until)
                }
                edge
            }
        edge?.let(log)
    }

    /** The `lora law hold` / `lora law clear` line for an edge of [verdict] against the last one, or null. Under [lock]. */
    private fun lawEdge(verdict: MeshcoreDutyLaw.Verdict): String? =
        when {
            verdict.holding && !lawHolding -> {
                "lora law hold trailing=${verdict.trailingTxSecs}s limit=${verdict.limitSecs}s releaseIn=${verdict.releaseInSecs}s duty=$dutyPercent"
            }

            !verdict.holding && lawHolding -> {
                "lora law clear trailing=${verdict.trailingTxSecs}s limit=${verdict.limitSecs}s"
            }

            else -> {
                null
            }
        }

    /** Whether the pacer must hear of [until]: a hold that starts or lifts, or one whose end moved past [HOLD_SLACK_MS]. */
    private fun holdMoved(
        until: Long?,
        last: Long?,
    ): Boolean =
        when {
            until == null || last == null -> (until == null) != (last == null)
            else -> kotlin.math.abs(until - last) > HOLD_SLACK_MS
        }

    // --- setup and restore, through the session (5b adds the erase step and keeps the record) ---

    /**
     * [ProvisionMode.Setup] runs the session's own setup transaction ([MeshcoreSession.setup]); its record is held for a
     * [ProvisionMode.Restore] in this process only, until Stage 5b persists it under SQLCipher. A restore with no record
     * held fails, and the dedicated-slot modes have no MeshCore form (the radio is never Knit's, ADR 045).
     */
    override suspend fun provision(
        mode: ProvisionMode,
        previous: BoardSettings?,
    ): ProvisionResult {
        provisioning = true
        try {
            return when (mode) {
                ProvisionMode.Setup -> setup()
                ProvisionMode.Restore -> restore()
                ProvisionMode.SetupDedicated, ProvisionMode.SetupShared -> ProvisionResult.Failed(NO_DEDICATED_SLOT)
            }
        } finally {
            provisioning = false
        }
    }

    private suspend fun setup(): ProvisionResult =
        when (val r = session.setup()) {
            is MeshcoreSetupResult.Done -> {
                if (r.record.changedAnything) setupRecord = r.record
                ProvisionResult.Provisioned(r.slot, r.alreadyPresent)
            }

            MeshcoreSetupResult.NoFreeSlot -> {
                ProvisionResult.NoFreeSlot
            }

            is MeshcoreSetupResult.Failed -> {
                r.left?.let { setupRecord = it }
                ProvisionResult.Failed(r.reason)
            }

            MeshcoreSetupResult.NotReady -> {
                ProvisionResult.NotReady(LinkState.Idle)
            }
        }

    private suspend fun restore(): ProvisionResult {
        val record = setupRecord ?: return ProvisionResult.Failed(NO_RECORD)
        return when (val r = session.restore(record)) {
            is MeshcoreRestoreResult.Done -> {
                if (r.failed.isEmpty()) setupRecord = null
                if (r.failed.isEmpty()) {
                    ProvisionResult.Restored
                } else {
                    ProvisionResult.Failed(
                        "restore incomplete: ${r.failed.joinToString()}",
                    )
                }
            }

            MeshcoreRestoreResult.NotReady -> {
                ProvisionResult.NotReady(LinkState.Idle)
            }
        }
    }

    /**
     * Refused until Stage 5b: the contacts our board is taught come from `MeshcoreContacts`, which teaches only keys it
     * vetted (no contested prefix, no unproven key), never a key the core asks for after a refusal. Counted and logged by
     * reason, never by key.
     */
    override fun teachKey(
        peerId: String,
        holder: LoraBoardHolder,
        own: BoardAddr,
        sentAfter: Long,
    ): KeyTeach {
        counters.teachRefused.incrementAndGet()
        log("lora direct no contact $peerId reason=MESHCORE_TEACHING_IS_5B")
        return KeyTeach.Refused
    }

    /** Nothing kept about teaching until Stage 5b. */
    override fun onDelivered(to: BoardAddr) = Unit

    /** MeshCore answers no `PEER_LACKS_KEY`: nothing of ours airs for it. */
    override fun bookKeyReply(now: Long) = Unit

    companion object {
        /** MeshCore's Public channel (gsqu §7): mirrored from Stage 6, counted and discarded until then. */
        const val PUBLIC_SLOT = 0

        /**
         * The slot a MeshCore plane's config names: none — the board reports its own Knit slot ([boundSlot]), so this is
         * never read as one.
         */
        const val NO_CONFIGURED_SLOT = -1

        /** How many boards' rings are kept (the current one included): a rebind back to a recent board keeps its baseline. */
        const val LAW_BOARDS = 4

        /** How far a hold's end may drift before the pacer is told again: under the spacing, over a poll's jitter. */
        const val HOLD_SLACK_MS = 10_000L

        /** The longest firmware version string anything shows (`DEVICE_INFO`'s is a short build tag). */
        const val VERSION_MAX = 32

        private const val MS_PER_SECOND = 1_000L
        private const val DIGEST_BYTES = 8
        private const val NO_DEDICATED_SLOT = "a MeshCore board has no dedicated slot: Knit never moves its radio"
        private const val NO_RECORD = "no setup record held: setup again first (persisted from Stage 5b)"

        /**
         * The neutral link state for a MeshCore board: Ready as a `LinkState.Ready` with no Meshtastic identity — node 0, no
         * channel table, the link's MTU floor — and the firmware version cleaned ([TextLimits.cleanReceived]) before any UI
         * or bridge shows it; anything short of Ready as [meshcoreDownState] reads it.
         */
        fun linkStateOf(state: BoardState): LinkState =
            when (state) {
                is BoardState.Ready -> {
                    val detail = state.detail as? MeshcoreDetail
                    LinkState.Ready(
                        board = BoardInfo(myNodeNum = 0u, pioEnv = null, firmwareVersion = detail?.version?.let(::cleanVersion)),
                        channels = emptyList(),
                        mtu = MeshcoreProto.MAX_NOTIFY + ATT_HEADER_BYTES,
                    )
                }

                is BoardState.Down -> {
                    meshcoreDownState(state.detail)
                }
            }

        /** The board's version string as the UI may show it: one cleaned line, or null when nothing printable is left. */
        fun cleanVersion(raw: String): String? = TextLimits.cleanReceived(raw, VERSION_MAX).ifEmpty { null }

        /** A board's key as the law's ring is filed under: the first bytes of its SHA-256, in hex — never the key. */
        fun digest(key: ByteArray): String =
            MessageDigest
                .getInstance("SHA-256")
                .digest(key)
                .take(DIGEST_BYTES)
                .joinToString("") { "%02x".format(Locale.ROOT, it) }

        private const val ATT_HEADER_BYTES = 3

        private fun stateOf(stamp: MeshcoreLawStamp): MeshcoreDutyLaw.State =
            MeshcoreDutyLaw.State(
                samples = stamp.samples.map { MeshcoreDutyLaw.Sample(it.uptimeSecs, it.txAirSecs, it.bootWall) },
                preBoot =
                    stamp.preBootTxSecs?.let { tx ->
                        stamp.preBootUntilWall?.let { MeshcoreDutyLaw.PreBoot(tx, it) }
                    },
            )

        private fun stampOf(
            board: String,
            state: MeshcoreDutyLaw.State,
        ): MeshcoreLawStamp =
            MeshcoreLawStamp(
                board = board,
                samples = state.samples.map { LawSampleStamp(it.uptimeSecs, it.txAirSecs, it.bootWallMs) },
                preBootTxSecs = state.preBoot?.txSecs,
                preBootUntilWall = state.preBoot?.untilWallMs,
            )
    }
}

/** The duty law as the MeshCore dump shows it: the band's duty, the trailing hour's bound against its limit, the hold. */
internal data class MeshcoreLawState(
    val dutyPercent: Double,
    val trailingTxSecs: Long?,
    val limitSecs: Double?,
    val holding: Boolean,
    /** While [holding], in how many of the board's seconds the hold lifts if nothing more is sent; null otherwise. */
    val releaseInSecs: Long? = null,
)

/**
 * The MeshCore plane's own counts (Stage 5a-2), read by the MeshCore dump: counts only — never a key, a prefix, a contact's
 * name or a text. Kept here rather than in `MeshMetrics`, whose `onLora*` counters stay unkeyed and whose snapshot is near
 * the JVM's slot limit. Any thread.
 */
internal class MeshcorePlaneCounters {
    /** `meshcoreForeignDms`: a contact DM that was not a compact DM, discarded. */
    val foreignDms = AtomicLong()

    /** A post on a slot that is neither Knit's nor Public, or of another data type, discarded. */
    val foreignPosts = AtomicLong()

    /** A post on Public slot 0, discarded until Stage 6 mirrors it. */
    val publicDiscarded = AtomicLong()

    /** A Knit fragment (`0x04`), discarded until Stage 5c's reassembler can read one on the public key. */
    val fragmentsRefused = AtomicLong()

    /** A live direct DM to us whose acks (the larger of one flooded ack, or a multi-ack and the ack) were booked. */
    val acksBooked = AtomicLong()

    /** A live flooded DM to us whose PATH return was booked. */
    val pathReturnsBooked = AtomicLong()

    /** A PATH return the session reported (booked once, off its packet). */
    val pathReturnsSeen = AtomicLong()

    /** A DM replayed from the offline queue: answered while we were away, booked by nobody. */
    val answersReplayed = AtomicLong()

    /** A DM attempt of ours our board flooded, whose surcharge was booked. */
    val floodSurcharges = AtomicLong()

    /** A sample that started a hold of every frame under the band's law. */
    val lawHolds = AtomicLong()

    /** `meshcoreZeroHopKnitPosts`: a live zero-hop Knit post routed, unverified — beside `boardsHeard`'s verified authors. */
    val zeroHopKnitPosts = AtomicLong()

    /** A key the core asked to teach, refused until Stage 5b. */
    val teachRefused = AtomicLong()

    /** The counts, by name: nothing here can carry a key, a prefix, a name or a text. */
    override fun toString(): String =
        "MeshcorePlaneCounters(foreignDms=$foreignDms, foreignPosts=$foreignPosts, publicDiscarded=$publicDiscarded, " +
            "fragmentsRefused=$fragmentsRefused, acksBooked=$acksBooked, pathReturnsBooked=$pathReturnsBooked, " +
            "pathReturnsSeen=$pathReturnsSeen, answersReplayed=$answersReplayed, floodSurcharges=$floodSurcharges, " +
            "lawHolds=$lawHolds, zeroHopKnitPosts=$zeroHopKnitPosts, teachRefused=$teachRefused)"
}

/**
 * A MeshCore board's [BoardPlane] (ADR 2026-11.gsqu, Stage 5a-2): [MeshcoreTraits], the
 * [session] as its link, and [MeshcoreHooks] over it. The session must be fresh: the hooks become its observer, and the
 * core is its packets' one collector (the session's contract).
 */
internal fun meshcorePlane(
    session: MeshcoreSession,
    scope: CoroutineScope,
    clock: () -> Long,
    log: (String) -> Unit,
    wallClock: () -> Long,
    onBoardKey: suspend (key: ByteArray, dialled: String?) -> Unit,
): BoardPlane =
    BoardPlane(MeshcoreTraits, session) { core ->
        MeshcoreHooks(session, core, scope, clock, log, wallClock, onBoardKey)
    }
