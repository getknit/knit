package app.getknit.knit.mesh.lora.meshcore

import app.getknit.knit.TextLimits
import app.getknit.knit.mesh.PublicPostRefusal
import app.getknit.knit.mesh.crypto.MeshcoreKey
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
import app.getknit.knit.mesh.lora.KeyWrite
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
import app.getknit.knit.mesh.lora.PublicPostEnded
import app.getknit.knit.mesh.lora.PublicPostResult
import app.getknit.knit.mesh.lora.SeenStamp
import app.getknit.knit.mesh.lora.SendPurpose
import app.getknit.knit.mesh.lora.SlotCheck
import app.getknit.knit.mesh.lora.WallShift
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
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
 * **The board is Knit's alone, except Public** (the maintainer's Stage 5 and 6 decisions): what is not a Knit frame — a
 * contact DM that is not a compact DM, a post on a slot that is neither Knit's nor Public, channel data on Public — is
 * counted ([MeshcorePlaneCounters]) and discarded here, before anything decodes it: never stored, logged, shown, relayed or
 * put in custody, and never a hearing ([PacketRoute.DROPPED]). The channel key is public, so the slot and the data type are
 * read first.
 *
 * **Public (slot 0) is mirrored both ways** (Stage 6, ADR 2026-11.gsqu's Stage 6 amendment): a text heard there is judged
 * ([MeshcorePublicJudge]) and handed to the room ([onPublicPost]) as a stranger's, never a hearing; the user's own post
 * leaves only from a board set up for Knit, under the flood ceiling, one per 30 s ([postPublic]). Nothing about a post is
 * logged: counters only.
 *
 * **The board's contacts are Knit's taught set** (Stage 5b-2, [MeshcoreContacts]): each board map ([onBoardMap]) decides
 * the ranked keys the session may DM and pin, cut to the board's capacity; a key that leaves the set (a contact removed or
 * blocked) is removed from the board (ADR 2026-09.adgd), and a DM our board refused for want of the contact is taught only
 * for a key in the set, once per (board, key) until a DM to it is acked ([teachKey]). After a Restore nothing is taught
 * until the next setup.
 *
 * **Consent is the session's** (Stage 5b-2, second part; the maintainer's decision, 2026-11-13): the actor reads the setup
 * record in its handshake and moves it in its own setup, Restore and unbind; these hooks only observe it
 * ([MeshcoreBoardOps.consent]) and derive the taught set, the offers and the profile's claim from it.
 *
 * State: the duty law ([MeshcoreDutyLaw], `@Synchronized`), the board its ring is for and the band's duty, guarded by
 * [lock], which is never held across a call into the core; the contact policy is `@Synchronized`; counters and the Public
 * post's floor are atomics; the setup record, the board map and the board's facts are volatile.
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
     * 5a-2), with its signature binding our node id when the session's bind succeeded (Stage 5d; null otherwise): what
     * `MeshcoreClaimStore.setBoardKey` writes, as one edit, only while that address is still the bound one. Called from the
     * consent alone ([onConsent]); null key withdraws both.
     */
    private val onBoardKey: suspend (key: ByteArray?, sig: ByteArray?, dialled: String?) -> Unit = { _, _, _ -> },
    /**
     * Delivers a post heard on the board's slot 0 into the MeshCore Public room (Stage 6), launched on the plane's scope as
     * the Meshtastic room's is: the sink writes the row, never this.
     */
    onPublicPost: suspend (MeshcorePublicPost) -> Unit = {},
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

    /** Which contacts the board is taught (Stage 5b-2). */
    private val contacts = MeshcoreContacts()

    /** Serialises [reteach]; taken outside the contact policy's and the pins' monitors, never inside them. */
    private val teachLock = Any()

    /** The last board map the core handed over ([onBoardMap]), in its form; null before one. */
    @Volatile
    private var holders: Map<String, LoraBoardHolder>? = null

    /** Our board's key once it identified, and its address: never taught, and the write memo's board. */
    @Volatile
    private var ownKey: ByteArray? = null

    /** The board's contact capacity (`DEVICE_INFO`'s `MAX_CONTACTS / 2`, doubled), once a Ready reported it. */
    @Volatile
    private var capacity: Int? = null

    /**
     * This phone's consent for the Ready board, as the session's actor decided it ([MeshcoreBoardOps.consent], the
     * maintainer's decision of 2026-11-13: one owner): the offers ([offered]), the taught set and the profile's claim
     * ([onBoardKey]) follow it, applied in the actor's order. Null before the first Ready.
     */
    @Volatile
    private var consent: Boolean? = null

    /** Slot 0, mirrored both ways (Stage 6): the half of these hooks that is the Public room's. */
    private val public =
        MeshcorePublicChannel(session, core, scope, clock, wallClock, log, counters, consent = { consent }, onPublicPost)

    @Volatile
    override var provisioning = false
        private set

    init {
        session.observer = this
        // Our board's floods of a DM of ours (the pin did not hold): the surcharge over the direct airing booked at send.
        scope.launch { session.air.collect(::onAir) }
        // The consent, in the order the actor decided it, the latest winning: nothing here reads or writes the record.
        scope.launch {
            session.consent.collect { c ->
                // One throw (the claim's DataStore edit, a reteach) never ends the collector (security N3).
                if (c != null) {
                    runCatching { onConsent(c) }.onFailure {
                        if (it is kotlinx.coroutines.CancellationException) throw it
                        counters.consentApplyFailures.incrementAndGet()
                    }
                }
            }
        }
    }

    // --- the face ---

    /** The link state the settings row and the field log render, the version cleaned before anything shows it. */
    override fun statusOf(
        state: BoardState,
        status: LoraStatus,
    ): LoraStatus = status.copy(state = linkStateOf(state), boardNodeNum = null, publicChannel = public.face(state))

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
     * - A text on Public slot 0 is the kind's ([PacketRoute.KIND], Stage 6): [onKindPacket] judges it for the room. The core
     *   asks `noteHeard` first, which hears no sender on this kind (`BoardTraits.hearsSender`), so a Public post is never a
     *   hearing; and it never reaches `LoraFrameCodec`, COBS or `IdElision`.
     * - Everything else is counted and discarded: a foreign DM ([MeshcorePlaneCounters.foreignDms]), a post on another slot
     *   or of another data type ([MeshcorePlaneCounters.foreignPosts]), channel data on Public slot 0
     *   ([MeshcorePlaneCounters.publicDiscarded]), and any unbound `0x04` fragment or a bound one (`0x07`) that does not
     *   parse ([MeshcorePlaneCounters.fragmentsRefused]; Stage 5c-1 reads bound parts through the core's candidates,
     *   `LoraFragCandidates`).
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
                bookAnswer(mc, packet.from as? BoardAddr.MeshCore)
                if (isCompactDm(packet, mc)) PacketRoute.DIRECT else discard(counters.foreignDms)
            }

            MeshcorePacket.Type.CHANNEL_DATA -> {
                routeChannelData(packet, mc, bound)
            }

            MeshcorePacket.Type.CHANNEL_TEXT -> {
                if (mc.slot == PUBLIC_SLOT) PacketRoute.KIND else discard(counters.foreignPosts)
            }
        }
    }

    /** Channel data: Knit's only on the board's Knit slot, under Knit's data type, a fragment only of a set a post can be. */
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

            // An unbound fragment is the form a stranger on the public key can crowd (the 5c-1 reviews' B1): no MeshCore phone
            // sends one, so none is read. A bound part goes to the core's candidates once it parses.
            packet.payload[0] == FastFrameCodec.TAG_FRAG ||
                (packet.payload[0] == FastFrameCodec.TAG_BOUND_FRAG && FastFrameCodec.parseBoundFragment(packet.payload) == null) -> {
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
    private fun bookAnswer(
        mc: MeshcorePacket,
        from: BoardAddr.MeshCore?,
    ) {
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
            // A sender Knit taught and pinned zero-hop on this connection is answered along that path (Stage 5b-2 part 2);
            // anyone else's record may hold no path, so the larger is booked. The prefix only prices air here: a forged one
            // whose MAC fails draws no ack at all, and one whose MAC passes is answered on that very record.
            val pinned = from != null && session.pinnedZeroHop(from)
            airtime.recordMs(if (pinned) alongPath else maxOf(flooded, alongPath), now)
            if (pinned) counters.acksBookedDirect.incrementAndGet()
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

    /**
     * A text on Public slot 0 ([route]'s one [PacketRoute.KIND]), on the packet collector: [MeshcorePublicChannel.onText]
     * drops it while the MeshCore room is off, else judges it into the room. Counters only (security N8). Returns at once.
     */
    override fun onKindPacket(packet: BoardPacket) {
        val mc = packet.kind as? MeshcorePacket ?: return
        if (mc.type == MeshcorePacket.Type.CHANNEL_TEXT && mc.slot == PUBLIC_SLOT) public.onText(mc)
    }

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

    override suspend fun postToPublicChannel(body: String): PublicPostRefusal? = postPublic(body).refusal

    /** The user's post on slot 0 ([MeshcorePublicChannel.post]: the refusals in order, the stamp fixed at enqueue). */
    override suspend fun postPublic(body: String): PublicPostResult = public.post(body)

    /** Queued Public posts that ended without airing ([MeshcorePublicChannel.ended]). */
    override val publicPostsEnded: Flow<PublicPostEnded> get() = public.ended

    /** A queued Public post, written under its frame's stamp ([MeshcorePublicChannel.send]): the only kind frame queued. */
    override suspend fun sendKindFrame(
        frame: OutboundFrame,
        send: suspend (ByteArray, SendPurpose) -> FragmentOutcome,
    ) = public.send(frame, send)

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
    }

    /**
     * The session's consent for the board on [c] (one owner: the actor resolved it before the Ready, or moved it in its own
     * setup, Restore or unbind after the durable write). Applied whole, so the latest wins: the taught set, the core's offers
     * ([PlaneCore.holdersMoved]) and the profile's claim — the board's key published while consented, with the bind's
     * signature when the consent carries one (Stage 5d: the session's one writer, so a withdrawal is never overtaken by a late
     * bind), withdrawn (null, both) otherwise (security S2).
     */
    private suspend fun onConsent(c: MeshcoreConsent) {
        // Only the board this plane is bound to (the maintainer's decision, 2026-11-14): another address cannot hold the claim.
        val bound = core.config?.address
        if (bound != null && bound != c.address) return
        consent = c.granted
        // A withdrawal goes to the claim first (security N2): nothing after it can skip it. An unread record leaves the claim
        // as the last consent left it.
        if (!c.unread && !c.granted) claim(c.address, null, null)
        if (holders != null) reteach()
        core.holdersMoved()
        if (!c.unread && c.granted) claim(c.address, c.key, c.sig)
        // Whether a Public post may leave rests on this consent (the room's face, Stage 6).
        core.faceMoved()
    }

    /** The claim edit owed, the latest winning, retried until it lands ([claim]). */
    private class OwedClaim(
        val address: String,
        val key: ByteArray?,
        val sig: ByteArray?,
    )

    @Volatile
    private var owedClaim: OwedClaim? = null

    private var claimRetry: kotlinx.coroutines.Job? = null

    /**
     * Publishes ([key], with its bind [sig] when the consent carried one) or withdraws (null) the claim for [address]; an edit
     * that throws (a DataStore I/O error) is owed and retried with a backoff until it lands or a newer one replaces it, so a
     * withdrawal is never lost (security N2).
     */
    private suspend fun claim(
        address: String,
        key: ByteArray?,
        sig: ByteArray?,
    ) {
        val owed = OwedClaim(address, key, sig?.takeIf { key != null })
        owedClaim = owed
        if (tryClaim(owed)) return
        synchronized(lock) {
            claimRetry?.cancel()
            claimRetry =
                scope.launch {
                    var wait = CLAIM_RETRY_FIRST_MS
                    // The one check of whether [owed] is still the edit owed is inside [tryClaim], under the mutex.
                    while (true) {
                        kotlinx.coroutines.delay(wait)
                        if (tryClaim(owed)) return@launch
                        wait = (wait * 2).coerceAtMost(CLAIM_RETRY_MAX_MS)
                    }
                }
        }
    }

    /** Claim edits, one at a time (security and lora, 5b-2 tail): a stale retry never lands after a newer edit. */
    private val claimMutex = kotlinx.coroutines.sync.Mutex()

    /**
     * One attempt at [owed], under [claimMutex], re-checked inside it: true when it landed, or when a newer edit replaced
     * it (nothing left to do for this one).
     */
    private suspend fun tryClaim(owed: OwedClaim): Boolean {
        val landed =
            claimMutex.withLock {
                if (owedClaim !== owed) return true
                runCatching { onBoardKey(owed.key, owed.sig, owed.address) }
                    .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
                    .isSuccess
            }
        if (landed) {
            if (owedClaim === owed) owedClaim = null
        } else {
            counters.consentApplyFailures.incrementAndGet()
        }
        return landed
    }

    /** Unbinds the board at [address], through the session's actor ([MeshcoreBoardOps.unbind]). */
    suspend fun unbind(address: String): MeshcoreUnbind = session.unbind(address)

    // --- the session's observer: on its actor, returns at once ---

    /**
     * The board identified itself: the law's ring is for this board from now. The board before it keeps its ring among the
     * others, so a rebind back finds it; this board's own kept or restored ring is applied, else the law starts empty —
     * which bounds the hour from the counter since boot, so on a 1 % band a board up for long can be held up to an hour at
     * its first connection (a cost the 5a-2 amendment records). The old board's hold goes with it.
     */
    override fun onIdentified(publicKey: ByteArray) {
        ownKey = publicKey.copyOf()
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
        // The taught set cut to this board's capacity before the Ready reconcile reads it, and the core's offers with it.
        val moved = capacity != detail?.contactCapacity
        capacity = detail?.contactCapacity
        if (holders != null) reteach()
        if (moved) core.holdersMoved()
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
     * [ProvisionMode.Setup] runs the session's own setup transaction ([MeshcoreSession.setup]) and keeps its record under
     * SQLCipher ([records]), merged into one a setup before left; [ProvisionMode.Restore] puts back what the record says, on
     * the board it is for only. A restore with no record fails, and the dedicated-slot modes have no MeshCore form (the radio
     * is never Knit's, ADR 045).
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
                // The record and the consent are the session's, written and granted inside its own transaction.
                log("lora setup kind=meshcore slot=${r.slot} erased contacts=${r.erasedContacts} slots=${r.clearedSlots}")
                ProvisionResult.Provisioned(r.slot, r.alreadyPresent)
            }

            MeshcoreSetupResult.NoFreeSlot -> {
                ProvisionResult.NoFreeSlot
            }

            is MeshcoreSetupResult.Failed -> {
                ProvisionResult.Failed(r.reason)
            }

            MeshcoreSetupResult.NotReady -> {
                ProvisionResult.NotReady(LinkState.Idle)
            }
        }

    private suspend fun restore(): ProvisionResult =
        when (val r = session.restore()) {
            is MeshcoreRestoreResult.Done -> {
                log("lora restore kind=meshcore contacts=${r.contactsRemoved} slotCleared=${r.slotCleared} failed=${r.failed.size}")
                if (r.failed.isEmpty()) {
                    ProvisionResult.Restored
                } else {
                    ProvisionResult.Failed(
                        "restore incomplete: ${r.failed.joinToString()}",
                    )
                }
            }

            MeshcoreRestoreResult.Mismatch -> {
                ProvisionResult.Failed(NOT_THIS_BOARD)
            }

            MeshcoreRestoreResult.NoRecord -> {
                ProvisionResult.Failed(NO_RECORD)
            }

            MeshcoreRestoreResult.NotReady -> {
                ProvisionResult.NotReady(LinkState.Idle)
            }
        }

    /**
     * The board map changed (Stage 5b-2): the taught set is decided again ([MeshcoreContacts.wanted]) and handed to the
     * session, and every key it dropped — a contact removed or blocked, a key now contested — is removed from the board
     * (ADR 2026-09.adgd), launched on the plane's scope. Returns at once.
     */
    override fun onBoardMap(map: Map<String, LoraBoardHolder>) {
        holders = map
        reteach()
    }

    /**
     * Offers a compact DM only to a holder whose key the taught set holds (lora-reviewer N2): one cut at the capacity, over
     * its daily bound or contested would be refused `NO_RECORD` by our board every time. The core asks again when the set
     * moves without a new map ([reteach] at Ready, through `PlaneCore.holdersMoved`).
     */
    override fun offered(map: Map<String, LoraBoardHolder>): Map<String, LoraBoardHolder> {
        val wanted = contacts.current
        // Nothing while the board is unconsented or its record not read yet (lora L2, N3): every offer would be refused.
        val open = consent == true
        return map.mapValues { (peer, holder) ->
            val taught = holder.loraKey?.let(MeshcoreKey::bytesOrNull)?.let { wanted.teaches(peer, it) } == true
            if (holder.direct && !(open && taught)) holder.copy(direct = false) else holder
        }
    }

    /**
     * Decides the taught set from the last board map and hands it to the session; removes what left it. One decision at a
     * time ([teachLock]: the map's collector and the session's actor both call in), so an older set never lands last. The
     * lock is held over no wait: the session's hand-over and the launch return at once.
     */
    private fun reteach() = synchronized(teachLock) { reteachLocked() }

    private fun reteachLocked() {
        val map = holders ?: return
        val before = contacts.current
        val wanted = contacts.wanted(map, ownKey, capacity, clock())
        counters.keysWanted.set(wanted.ranked.size.toLong())
        counters.keysSkipped.set(
            wanted.skipped.values
                .sum()
                .toLong(),
        )
        if (consent == false) {
            // For want of consent, not a taught set: the session's reconcile waits for the real one (lora L1).
            session.setKnitContacts(emptyList(), withdrawn = true)
            return
        }
        session.setKnitContacts(wanted.ranked)
        val gone = before.ranked.filterNot(wanted::has)
        if (gone.isEmpty()) return
        counters.removedUntaught.addAndGet(gone.size.toLong())
        scope.launch { gone.forEach { key -> session.removeUntaught(key) } }
    }

    /**
     * Our board refused a DM to [peerId] for want of the contact ([app.getknit.knit.mesh.lora.RefusalClass.OWN_NO_KEY]): the
     * key is taught only when it is in the taught set ([MeshcoreContacts.judgeTeach]: vetted, ranked inside the capacity,
     * inside the peer's daily bound) and was not written already for our board without a DM to it acked since. Never after a
     * Restore. Counted and logged by reason, never by key.
     */
    override fun teachKey(
        peerId: String,
        holder: LoraBoardHolder,
        own: BoardAddr,
        sentAfter: Long,
    ): KeyTeach {
        val board = own as? BoardAddr.MeshCore
        val key = holder.loraKey?.let(MeshcoreKey::bytesOrNull)
        val refusal =
            when {
                board == null || key == null -> "NO_KEY"
                consent == false -> "NOT_SET_UP"
                else -> contacts.judgeTeach(peerId, key, board)?.name
            }
        if (refusal != null || board == null || key == null) {
            counters.teachRefused.incrementAndGet()
            log("lora direct no contact $peerId reason=$refusal")
            return KeyTeach.Refused
        }
        return KeyTeach.Write {
            val written = session.teach(key)
            if (written == ContactWrite.WRITTEN) {
                contacts.onWritten(board, key)
                counters.teachWritten.incrementAndGet()
            }
            keyWriteOf(written)
        }
    }

    /** A DM to [to] was acked by its board: one more reactive teach of it is allowed should our board lose it. */
    override fun onDelivered(to: BoardAddr) {
        val board = ownKey?.let(MeshcorePins::addrOf) ?: return
        (to as? BoardAddr.MeshCore)?.let { contacts.onDelivered(board, it) }
    }

    /** MeshCore answers no `PEER_LACKS_KEY`: nothing of ours airs for it. */
    override fun bookKeyReply(now: Long) = Unit

    companion object {
        /** MeshCore's Public channel (gsqu §7): its texts mirrored into the Public room since Stage 6, its data discarded. */
        const val PUBLIC_SLOT = 0

        /**
         * The slot a MeshCore plane's config names: none — the board reports its own Knit slot ([boundSlot]), so this is
         * never read as one.
         */
        const val NO_CONFIGURED_SLOT = -1

        /** How many boards' rings are kept (the current one included): a rebind back to a recent board keeps its baseline. */
        const val LAW_BOARDS = 4

        /** The first and the longest wait before an owed claim edit is tried again. */
        const val CLAIM_RETRY_FIRST_MS = 1_000L
        const val CLAIM_RETRY_MAX_MS = 60_000L

        /** How far a hold's end may drift before the pacer is told again: under the spacing, over a poll's jitter. */
        const val HOLD_SLACK_MS = 10_000L

        /** The longest firmware version string anything shows (`DEVICE_INFO`'s is a short build tag). */
        const val VERSION_MAX = 32

        private const val MS_PER_SECOND = 1_000L
        private const val DIGEST_BYTES = 8
        private const val NO_DEDICATED_SLOT = "a MeshCore board has no dedicated slot: Knit never moves its radio"
        private const val NOT_THIS_BOARD = "the connected board is not the one this phone set up at that address"
        private const val NO_RECORD = "no setup record held for this board: set it up first"

        /**
         * What a teach's answer is to the core's held frame: taken, it goes again; present already or unconfirmed, once more
         * and a second refusal is terminal; no room or no contact, the peer is keyless; not Ready, it is owed.
         */
        fun keyWriteOf(written: ContactWrite): KeyWrite =
            when (written) {
                ContactWrite.WRITTEN -> KeyWrite.WRITTEN
                ContactWrite.ALREADY_PRESENT, ContactWrite.FAILED -> KeyWrite.UNCONFIRMED
                ContactWrite.NO_FREE_SLOT, ContactWrite.NOT_FOUND, ContactWrite.NOT_SET_UP -> KeyWrite.REFUSED
                ContactWrite.NOT_READY -> KeyWrite.NOT_WRITTEN
            }

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

    /** Channel data on Public slot 0, discarded: only its texts are posts (Stage 6). */
    val publicDiscarded = AtomicLong()

    /** `meshcorePublicHeard`: a text on Public slot 0 (Stage 6), before the room switch and the judge. */
    val publicHeard = AtomicLong()

    /** `meshcorePublicRoomOff`: of [publicHeard], dropped because the MeshCore room is switched off. */
    val publicRoomOff = AtomicLong()

    /** `meshcorePublicRefused`: of [publicHeard], refused by the judge (no text, empty) or on a slot 0 that is Knit's. */
    val publicRefused = AtomicLong()

    /**
     * `meshcorePublicDelivered`: of [publicHeard], judged a post and offered to the room's queue (part 6c) — stored, or
     * a duplicate the room writes nothing for, unless [publicBacklogDropped] counts it refused.
     */
    val publicDelivered = AtomicLong()

    /**
     * `meshcorePublicBacklogDropped`: of the posts [publicDelivered] counts, refused because the room's inbound queue
     * was full (`OrderedRoomSink.CAPACITY` waiting: a stalled room or a forged flood, past any board's queue at connect).
     */
    val publicBacklogDropped = AtomicLong()

    /** `meshcorePublicPostsQueued`: the user's Public posts queued. */
    val publicPostsQueued = AtomicLong()

    /** `meshcorePublicPostsRefused`: the user's Public posts refused before the queue (the `lora public post refused` line). */
    val publicPostsRefused = AtomicLong()

    /** `meshcorePublicPostsSent`: queued Public posts the board took. */
    val publicPostsSent = AtomicLong()

    /** `meshcorePublicPostsEnded`: queued Public posts given up at the write (refused, unconfirmed, not set up any more). */
    val publicPostsEnded = AtomicLong()

    /** An unbound Knit fragment (`0x04`), or a bound one (`0x07`) that does not parse, discarded (Stage 5c-1). */
    val fragmentsRefused = AtomicLong()

    /** A live direct DM to us whose acks (the larger of one flooded ack, or a multi-ack and the ack) were booked. */
    val acksBooked = AtomicLong()

    /** A consent the hooks could not apply (the claim's write, a reteach threw): the next one is applied whole. */
    val consentApplyFailures = AtomicLong()

    /** Of [acksBooked], those booked along the pinned path alone: the sender's contact taught and pinned zero-hop. */
    val acksBookedDirect = AtomicLong()

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

    /** A key the core asked to teach, refused by the contact policy (not wanted, written already, after a Restore). */
    val teachRefused = AtomicLong()

    /** A key the core asked to teach, written and read back. */
    val teachWritten = AtomicLong()

    /** The taught set's size, as the last board map decided it (a gauge). */
    val keysWanted = AtomicLong()

    /** Holders the last decision left untaught (a gauge): no key, contested, our own, the daily bound, the capacity. */
    val keysSkipped = AtomicLong()

    /** Keys that left the taught set (a contact removed or blocked) and were removed from the board. */
    val removedUntaught = AtomicLong()

    /** The counts, by name: nothing here can carry a key, a prefix, a name or a text. */
    override fun toString(): String =
        "MeshcorePlaneCounters(foreignDms=$foreignDms, foreignPosts=$foreignPosts, publicDiscarded=$publicDiscarded, " +
            "fragmentsRefused=$fragmentsRefused, acksBooked=$acksBooked, pathReturnsBooked=$pathReturnsBooked, " +
            "pathReturnsSeen=$pathReturnsSeen, answersReplayed=$answersReplayed, floodSurcharges=$floodSurcharges, " +
            "lawHolds=$lawHolds, zeroHopKnitPosts=$zeroHopKnitPosts, acksBookedDirect=$acksBookedDirect, " +
            "teachRefused=$teachRefused, teachWritten=$teachWritten, " +
            "keysWanted=$keysWanted, keysSkipped=$keysSkipped, removedUntaught=$removedUntaught, " +
            "publicHeard=$publicHeard, publicRoomOff=$publicRoomOff, publicRefused=$publicRefused, " +
            "publicDelivered=$publicDelivered, publicBacklogDropped=$publicBacklogDropped, " +
            "publicPostsQueued=$publicPostsQueued, publicPostsRefused=$publicPostsRefused, " +
            "publicPostsSent=$publicPostsSent, publicPostsEnded=$publicPostsEnded)"
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
    onBoardKey: suspend (key: ByteArray?, sig: ByteArray?, dialled: String?) -> Unit,
    /** The MeshCore Public room's inbound sink (Stage 6): a heard post, judged, as a stranger's. */
    onPublicPost: suspend (MeshcorePublicPost) -> Unit = {},
): BoardPlane =
    BoardPlane(MeshcoreTraits, session) { core ->
        MeshcoreHooks(session, core, scope, clock, log, wallClock, onBoardKey, onPublicPost)
    }
