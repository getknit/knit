package app.getknit.knit.mesh.lora

import app.getknit.knit.mesh.MeshMetrics
import app.getknit.knit.mesh.MeshPost
import app.getknit.knit.mesh.PublicPostRefusal
import app.getknit.knit.mesh.meshNodeLabel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

/**
 * What only a Meshtastic board does, behind the plane core's [BoardKindHooks] (ADR 2026-11.gsqu, Stage 1c): the inbound
 * dispatch by portnum and slot, the slot-0 Meshtastic room (ADR 2026-09.26q3), the DM auto-reply (ADR 2026-09.4n5p), the
 * send-time slot and AEAD guards, how the board signs (#243), the board setup (ADR 045), and the `add_contact` and the
 * answer to a `35` (ADR 2026-10.aajj). Made per plane instance by the core, with the Meshtastic link it adapts ([link]),
 * the neutral view of it the core reads ([board]) and the core it reaches back into ([core]).
 *
 * Its state is the kind's own and created here, never by the core: the room's post floor is an atomic, since a post
 * comes from `MeshManager`'s coroutine and the room's packets from the plane's collector; the auto-reply's memory is a
 * [DmAutoReplyPolicy] and the taught keys a [LoraContactPolicy], both `@Synchronized`, since the save loop, `stop()`,
 * the pacer and the outcomes collector reach them from different coroutines; the setup flag is volatile.
 */
@Suppress("LongParameterList") // the kind's wiring, each one thing the core hands it once
internal class MeshtasticHooks(
    private val link: MeshtasticLink,
    private val board: BoardLink,
    private val core: PlaneCore,
    private val scope: CoroutineScope,
    private val metrics: MeshMetrics,
    private val clock: () -> Long,
    private val log: (String) -> Unit,
    /** Delivers a post heard on the board's primary channel into the Meshtastic room — `MeshPostSink`. */
    onPublicPost: suspend (MeshPost) -> Unit,
    /** The bound board's node number and key, reported each time its session comes up ([BoardBinding]). */
    private val onBoardBound: suspend (BoardBinding) -> Unit,
) : BoardKindHooks {
    override val kind: BoardKind get() = BoardKind.MESHTASTIC

    /**
     * The room's inbound queue (ADR 2026-11.gsqu's Stage 6 amendment, part 6c): posts reach [onPublicPost] one at a time, in
     * the order the board handed them over — a replay at connect lands in its own order — without the packet collector
     * ever waiting on the room. Full, a post is counted (`meshPostRefused` under [MESH_POST_ROOM_BACKLOG]) and logged.
     */
    private val room =
        OrderedRoomSink<MeshPost>(
            scope,
            onDropped = {
                metrics.onMeshPostRefused(MESH_POST_ROOM_BACKLOG)
                log("lora public post dropped: room backlog full")
            },
            onFailed = { log("lora public post not delivered: ${it.javaClass.simpleName}") },
            deliver = onPublicPost,
        )

    // When this phone last put a post on the foreign public channel, for the per-phone floor. Monotonic
    // (`clock`), so it survives a wall-clock jump; NEVER so the first post of a session is never held.
    private val lastPublicPostAt = AtomicLong(NEVER)

    /**
     * Who has been told the board is unmonitored, and when the board last said so — the DM auto-reply's two caps.
     * Persisted through the plane state ([restoreState], [snapshotState]); never cleared with the board: the memory is
     * about the senders, not the board, and losing the link for a minute must not earn anybody a second reply.
     */
    private val autoReply = DmAutoReplyPolicy(clock = clock)

    /**
     * Which pinned contacts' keys our board was taught with `add_contact`, and whether it may be taught another (ADR
     * 2026-10.aajj). In memory only: a restart may write a key once more, which costs one NodeDB save.
     */
    private val contacts = LoraContactPolicy()

    /**
     * Set while a setup or restore runs (ADR 045's one admin transaction): no `add_contact` is written inside it. The
     * session's actor would serialise the two anyway; this keeps the write from landing just before the commit's reboot.
     */
    @Volatile
    override var provisioning = false
        private set

    override fun statusOf(
        state: BoardState,
        status: LoraStatus,
    ): LoraStatus = Companion.statusOf(state, status)

    // --- the inbound dispatch (Stage 1c.0) ---

    /**
     * The Meshtastic dispatch, in the order the transport always read it. A compact DM (`0x11` on the private port, ADR
     * 2026-10.x9fa) comes first, ahead of the bound-slot filter: firmware 2.5+ reports a PKI-decrypted DM on index 0
     * whatever slot it used, and 2.8 PKI-encrypts every compact DM, so it is read on the bound slot or on index 0 and
     * nowhere else. Then a `NodeInfo` (the key a board announces, ADR 2026-10.aajj) and a text (the Meshtastic room on
     * slot 0, or a user's DM to our board) — both the kind's own. A text is taken on the portnum and, for a DM, the
     * address alone, never the slot: the slot index defaults to 0 on a board that never ran Knit's setup, and such a
     * board still has a primary to mirror. Anything else is a Knit frame only on the private port and the bound slot —
     * outbound is pinned there, and a board carrying a second channel with Knit traffic must not ingest both — and one
     * under the burned `0x10` tag (an older build's OFFER) is the radio heard and the packet dropped.
     */
    override fun route(
        packet: BoardPacket,
        bound: Int?,
    ): PacketRoute {
        val raw = packet.meshtastic ?: return PacketRoute.DROPPED
        if (raw.portnum == MeshtasticProto.PORT_PRIVATE_APP && packet.payload.firstOrNull() == LORA_DIRECT_TAG) {
            val slot = packet.channel == bound || packet.channel == PublicChannelPolicy.PRIMARY_INDEX
            return if (slot) PacketRoute.DIRECT else PacketRoute.DROPPED
        }
        return when {
            raw.portnum == MeshtasticProto.PORT_NODEINFO -> PacketRoute.KIND
            packet.payload.isEmpty() -> PacketRoute.HEARD
            raw.portnum == MeshtasticProto.PORT_TEXT_MESSAGE -> PacketRoute.KIND
            raw.portnum != MeshtasticProto.PORT_PRIVATE_APP || (bound != null && packet.channel != bound) -> PacketRoute.HEARD
            packet.payload[0] == LoraMeshTransport.RETIRED_CTL_TAG -> PacketRoute.RETIRED
            else -> PacketRoute.KNIT
        }
    }

    /**
     * Our board acked a compact DM on our behalf before the phone could open it — a stranger's and a forged one too: air
     * the window owes (ADR 2026-10.x9fa). Only when the firmware did send that ACK ([LoraDirectPolicy.boardAcks]): a
     * unicast that asked for none, and named nobody's next hop as ours, drew none. A routing unicast on the channel the
     * DM came in on: unsigned but in 2.8.1's ham mode, +12 B on an AEAD slot 0 (#243, [LoraAirtime.price]).
     */
    override fun bookDirectAck(
        packet: BoardPacket,
        own: BoardAddr,
        onBound: Boolean,
    ) {
        val node = own.meshtasticNode ?: return
        val raw = packet.meshtastic ?: return
        if (!LoraDirectPolicy.boardAcks(raw, node)) return
        val slot = if (onBound) AirSlot.KNIT else AirSlot.PRIMARY
        val price = core.airtime.price(slot, PacketKind.ROUTING_UNICAST, MeshtasticProto.PORT_ROUTING)
        core.airtime.record(MeshtasticTraits.ackPayloadBytes, clock(), price)
    }

    override fun onKindPacket(packet: BoardPacket) {
        val raw = packet.meshtastic ?: return
        when (raw.portnum) {
            MeshtasticProto.PORT_NODEINFO -> onNodeInfo(raw)
            MeshtasticProto.PORT_TEXT_MESSAGE -> onTextPacket(raw)
        }
    }

    /**
     * A `TEXT_MESSAGE_APP` packet: a Meshtastic user's DM to our board (answered once), or a chat on slot 0 (the
     * Meshtastic room). A text anywhere else is not ours to read.
     *
     * A text addressed to the board rather than to the channel is a Meshtastic user's DM — which nothing on this phone
     * reads (ADR 2026-09.emd7 marks the board unmonitored to say exactly that), so the one thing to do with it is tell
     * the sender. Taken ahead of the room's branch on the address alone, never the channel index: on 2.5+ firmware a DM
     * is PKI-encrypted and reported on index 0 whatever slot the sender used, and an older board reports the slot it
     * decoded on.
     */
    private fun onTextPacket(raw: ReceivedPacket) {
        if (raw.to != MeshtasticProto.BROADCAST) {
            onDirectMessage(raw)
            return
        }
        if (raw.channelIndex == PublicChannelPolicy.PRIMARY_INDEX) {
            // The room is switched off, or hidden because the board is on a dedicated RF slot (ADR 067):
            // this packet has nowhere to go. Dropped here rather than later so the whole cost of a post the
            // user asked not to see — the judge, the signature verify, the contact lookup, the moderator, the
            // row and its notification — is never paid. Counted by reason, so `…debug.LORA` can still tell
            // "the channel is quiet" from "the channel is busy and Knit is ignoring it".
            val closed =
                when {
                    core.config?.room == false -> MESH_POST_ROOM_OFF

                    // A post on a pinned board's slot 0 is another pinned board's, never a public one, and
                    // would land in a thread with no row and ring a notification for it.
                    core.airtime.dedicated() -> MESH_POST_DEDICATED

                    else -> null
                }
            if (closed != null) metrics.onMeshPostRefused(closed) else onPrimaryPacket(raw)
        }
    }

    /**
     * One chat packet off the board's **primary** channel — what the Meshtastic room mirrors. [PublicChannelPolicy]
     * decides whether it is a post; a refusal is counted and dropped, and an accepted post is delivered into
     * this phone's room and nowhere else. Every board reads its own slot 0: there is no election and no
     * minting, because nothing about a heard post leaves the phone.
     *
     * One rule that is easy to get wrong here: **the core's `noteBoard` is not called.** `boardsHeard` means "radios
     * that have sent a *Knit* frame", which is what makes "heard nobody" the ordinary state of a solo user
     * and is why the preset-mismatch notice cannot be gated on evidence. Counting stock neighbours here would
     * quietly change all of that.
     */
    private fun onPrimaryPacket(packet: ReceivedPacket) {
        metrics.onMeshPostHeard()
        val ready = board.state.value.meshtasticReady ?: return
        val verdict =
            PublicChannelPolicy.judge(
                packet = packet,
                channels = ready.channels,
                radio = ready.radio,
                name = link.nodes.value[packet.from]?.longName,
                // Our own board's transmissions are never read back in: the outbound half puts this phone's
                // posts on this channel, and they are already in the room as our own rows.
                ownNode = ready.board.myNodeNum,
            )
        when (verdict) {
            is PublicChannelPolicy.Verdict.Refused -> {
                metrics.onMeshPostRefused(verdict.reason.name)
            }

            is PublicChannelPolicy.Verdict.Post -> {
                metrics.onMeshPostIngested(verdict.post.viaMqtt)
                log(
                    "lora public post from ${meshNodeLabel(packet.from.toLong())} " +
                        "${verdict.post.body.length}ch viaMqtt=${verdict.post.viaMqtt} " +
                        "signed=${verdict.post.signature != null} boardVerified=${verdict.post.boardVerified}",
                )
                room.offer(verdict.post)
            }
        }
    }

    // --- the DM auto-reply (Stage 1c.2) ---

    /**
     * One text addressed to the board — a Meshtastic user's DM, answered once with [DmAutoReplyPolicy.TEXT].
     *
     * The guards are all here rather than at the write, so each one can be counted and named, the same shape
     * as [postToPublicChannel] and for the same reason. Two are the room's own: a board on a **dedicated**
     * RF slot (ADR 067) has no Meshtastic neighbours to answer, and a board whose slot 0 is the Knit channel
     * has no primary to answer on. One is this path's alone: the board must be **set up for Knit**
     * ([boundSlotIsKnit]), because the setup's confirmation sheet is where the user agreed to the board
     * saying it is unmonitored (ADR 2026-09.emd7) — a paired stock board that never ran it stays as quiet
     * as a stock board. Then the policy's own caps, and last the window's half ceiling (ADR 2026-10.ug4a), asked
     * inside the policy before it stamps the sender — so a reply the window will not carry is counted now rather than
     * left to look sent, and uses up nobody's one answer.
     *
     * The reply rides the shared pacer as a [Destination.Reply] frame, priced as the PKI unicast it is
     * ([OutboundFrame.price]): unsigned, +12 B — or, on a ham-mode board, channel-encrypted and signed by the firmware's
     * rule (#243).
     */
    private fun onDirectMessage(packet: ReceivedPacket) {
        metrics.onAutoReplyHeard()
        val refusal = autoReplyRefusal(packet) ?: return
        metrics.onAutoReplyRefused(refusal)
        log("lora dm from ${meshNodeLabel(packet.from.toLong())}: auto-reply refused $refusal")
    }

    /** The gate that stopped the reply to [packet], by name — or null once the reply is queued. */
    @Suppress("ReturnCount") // one guard per line, each its own refusal
    private fun autoReplyRefusal(packet: ReceivedPacket): String? {
        val ready = board.state.value.meshtasticReady ?: return AutoReplyRefusal.NOT_READY.name
        // The once-a-day memory is not back yet: answering now could answer, again, a sender the last process already did.
        // Refused unstamped. This copy is not answered — the board's to-phone queue gives a packet up once the phone has
        // read it, so it is not delivered again — but the sender's next DM is judged against the memory.
        if (!core.restored) return AutoReplyRefusal.NOT_READY.name
        val airtime = core.airtime
        if (airtime.dedicated()) return AutoReplyRefusal.DEDICATED.name
        if (PublicChannelPolicy.isKnitPrimary(ready.channels)) return AutoReplyRefusal.KNIT_ON_PRIMARY.name
        // `boundSlotIsKnit` reads an empty table as "don't suppress"; this is a consented transmission, so the
        // unknown reads the other way, like the setup's own guard.
        val bound = core.config?.channelIndex
        if (bound == null || ready.channels.isEmpty() || !boundSlotIsKnit(ready.channels, bound)) return AutoReplyRefusal.NOT_SET_UP.name
        // The text is fixed, so its size is known before the sender is judged: a reply too long for this board (never, in
        // practice) is refused without a stamp.
        val message = DmAutoReplyPolicy.TEXT.encodeToByteArray()
        if (message.size > core.maxPayload) return AutoReplyRefusal.TOO_LARGE.name
        // Priced as what flies, the price its queued frame will book (OutboundFrame.price): a PKI unicast, unsigned and
        // +12 B, on any board not in ham mode.
        val price = airtime.price(AirSlot.PRIMARY, PacketKind.DATA_UNICAST, MeshtasticProto.PORT_TEXT_MESSAGE)
        val carried = { airtime.admits(Ceiling.HALF, listOf(message.size), clock(), price) }
        val reply =
            when (val verdict = autoReply.judge(packet, ownNode = ready.board.myNodeNum, carried = carried)) {
                is DmAutoReplyPolicy.Verdict.Refused -> return verdict.reason.name
                is DmAutoReplyPolicy.Verdict.Reply -> verdict
            }
        log("lora dm from ${meshNodeLabel(packet.from.toLong())}: auto-reply queued")
        core.enqueue(listOf(reply.text.encodeToByteArray()), "autoreply", Ceiling.HALF, Destination.Reply, to = BoardAddr(reply.to))
        core.limitersMoved() // the sender is stamped now, not at the write: persist it
        return null
    }

    /**
     * The auto-reply's once-a-day memory from the last process (ADR 2026-09.7svb): a restart that forgot who was answered
     * would answer the same sender twice in a day — their next DM, or one that queued on the board while the phone was
     * away. The board hands a packet over once (`PhoneAPI::getFromRadio`), so a DM already read never comes back (ADR
     * 2026-09.4n5p).
     */
    override fun restoreState(
        snapshot: LoraPlaneSnapshot,
        shift: WallShift,
    ) {
        autoReply.restore(snapshot.autoReplied.map { it.id to shift.toMono(it.atWall) })
    }

    override fun snapshotState(shift: WallShift): List<SeenStamp> =
        autoReply.stamps().takeLast(STATE_AUTO_REPLY_CAP).map { SeenStamp(it.first, shift.toWall(it.second)) }

    // --- the Meshtastic room's outbound half (Stage 1c.1) ---

    /**
     * Puts this phone's user's post on the board's primary channel — the core's `PublicChannelSink`, the Meshtastic
     * room's outbound half. Called from `MeshManager.sendPublicPost`'s coroutine for a post typed on this phone and no
     * other: the room posts through its own board, never through a pocket-mate's, and a refusal is something the
     * composer shows the user.
     *
     * The guards are all here rather than at the write, so each one can be counted and named. They are
     * deliberately **not** [boundSlotIsKnit]'s: that guard exists to keep Knit's cleartext frames off the
     * public channel by accident and reads an unknown channel table as "stay silent", while this is a
     * consented path *to* that channel. Same shape, opposite safe answer, so sharing code between them would
     * make one of the two wrong.
     */
    override suspend fun postToPublicChannel(body: String): PublicPostRefusal? {
        // The composer is gone with the room's row, so this is the net under a route that no longer draws one
        // (the debug SEND intent, a screen still on the back stack) — the same net `MeshManager.sendTyping`
        // keeps under the room it must not cue.
        if (core.config?.room == false) return refusePublicPost(PublicPostRefusal.ROOM_OFF)
        // A board on a dedicated RF slot (ADR 067) has no public channel under it: a post would go on the
        // air where only other pinned Knit boards could hear it, into a room this phone no longer draws.
        val airtime = core.airtime
        if (airtime.dedicated()) return refusePublicPost(PublicPostRefusal.DEDICATED)
        val ready = board.state.value.meshtasticReady ?: return refusePublicPost(PublicPostRefusal.NOT_READY)
        // A board whose slot 0 is the Knit channel has no primary to post on — the debug-bridge shape the
        // receive half refuses to read for the same reason, decided off the same table.
        if (PublicChannelPolicy.isKnitPrimary(ready.channels)) return refusePublicPost(PublicPostRefusal.KNIT_ON_PRIMARY)
        val now = clock()
        val last = lastPublicPostAt.get()
        if (last != NEVER && now - last < LoraMeshTransport.PUBLIC_POST_FLOOR_MS) return refusePublicPost(PublicPostRefusal.TOO_SOON)
        // Slot 0's own price (#243): its cliff on the text port, by the board's policy and the channel's AEAD.
        val price = airtime.price(AirSlot.PRIMARY, PacketKind.BROADCAST, MeshtasticProto.PORT_TEXT_MESSAGE)
        // The same budget the composer applied — a draft typed while the facts still said 200 (a board that
        // came up signing after the draft began) is trimmed here rather than sent past the cliff unsigned.
        val message = PublicPostPolicy.onAirText(body, PublicPostPolicy.onAirBudget(price.signedUpTo)).encodeToByteArray()
        if (message.size > minOf(core.maxPayload, price.cap)) return refusePublicPost(PublicPostRefusal.TOO_LARGE)
        // Asked before queueing rather than left to the pacer: a post the window will not carry under the foreign
        // audience's ceiling (half, ADR 2026-10.ug4a) should be counted as refused now, not sit in the queue looking
        // sent until the window rolls over. Priced at slot 0's cliff: a post at the cap is signed air, not an unsigned
        // packet.
        if (!airtime.admits(Ceiling.HALF, listOf(message.size), now, price)) {
            return refusePublicPost(PublicPostRefusal.NO_AIR)
        }
        // Claimed at enqueue, not at write: the floor is about how often this phone *decides* to speak, and
        // charging it only once the pacer drains would let a burst queue up behind one 3 s gap.
        lastPublicPostAt.set(now)
        core.enqueue(listOf(message), "public", Ceiling.HALF, Destination.Public)
        return null
    }

    private fun refusePublicPost(reason: PublicPostRefusal): PublicPostRefusal {
        metrics.onPublicPostRefused(reason.name)
        log("lora public post refused: $reason")
        return reason
    }

    /**
     * Writes one of the kind's own frames — a public post on the foreign mesh's primary channel (index 0,
     * `TEXT_MESSAGE_APP`, cleartext) or, for a [Destination.Reply], the same write addressed to one node, which the
     * firmware then encrypts to that node's key rather than to the channel — through the core's [send].
     *
     * Never fragmented — [PublicPostPolicy.MAX_ON_AIR_BYTES] is a fifth of what one packet carries — so there
     * is no resume cursor to honour and one send is the whole frame.
     */
    override suspend fun sendKindFrame(
        frame: OutboundFrame,
        send: suspend (ByteArray, SendPurpose) -> FragmentOutcome,
    ) {
        val message = frame.messages.firstOrNull() ?: return
        val reply = frame.destination == Destination.Reply
        // The auto-reply is the one direct send without `want_ack`: a stock client's board answers nobody's retries
        // for us, and the window would pay for them (ADR 2026-09.4n5p).
        val replyTo = frame.to ?: BoardAddr(MeshtasticProto.BROADCAST)
        val purpose = if (reply) SendPurpose.AutoReply(replyTo) else SendPurpose.PublicPost()
        val outcome = send(message, purpose)
        // OWED is back on the queue (the board busy, or the session gone under it): not a refusal, and its airing after
        // the reconnect is counted sent then, once (#307). Should it never air, the pacer's shed counts it
        // `loraDroppedQueue`, as it does any frame; the refusal counters are for what the board refused.
        if (outcome == FragmentOutcome.OWED) return
        if (outcome == FragmentOutcome.ENDED) {
            if (reply) metrics.onAutoReplyRefused(AutoReplyRefusal.NAK.name) else metrics.onPublicPostRefused(PublicPostRefusal.NAK.name)
            return
        }
        if (reply) {
            metrics.onAutoReplySent()
            log("lora tx auto-reply to ${meshNodeLabel((replyTo.meshtasticNode ?: MeshtasticProto.BROADCAST).toLong())} ${message.size}B")
        } else {
            metrics.onPublicPostSent()
            log("lora tx public ${message.size}B")
        }
    }

    // --- a session coming up: the radio, how the board signs, the binding (Stage 1c.4) ---

    /**
     * The Meshtastic half of a session coming up: the radio and how the board signs, for the ledger (#243), the
     * `lora ready` line, and the binding the profile advertises.
     */
    override fun onReady(ready: BoardState.Ready) {
        val state = ready.meshtasticReady ?: return
        val airtime = core.airtime
        airtime.onRadioConfig(state.radio)
        // What the firmware signs, and so what the budget charges and the codec pads past (#243): the version, the
        // build's `has_xeddsa`, the policy it streamed (none: unknown, priced as signing), ham mode, and AEAD on the
        // two slots we write to.
        val bound = core.config?.channelIndex
        airtime.onBoard(
            SigningFacts(
                firmware = state.board.firmwareVersion,
                hasXeddsa = state.board.hasXeddsa,
                policy = state.signaturePolicy,
                licensed = state.board.licensed,
            ),
            primaryAead = state.channels.any { it.index == PublicChannelPolicy.PRIMARY_INDEX && it.useAead },
            knitAead = bound != null && state.channels.any { it.index == bound && it.useAead },
        )
        val air = airtime.snapshot(clock())
        log(
            "lora ready board=${state.board.myNodeNum} mtu=${state.mtu} maxPayload=${core.maxPayload} " +
                "radio=${state.radio?.region}/${state.radio?.modemPreset} " +
                "fw=${state.board.firmwareVersion} signing=${air.signing} policy=${state.signaturePolicy} " +
                "knitSignedUpTo=${air.knitSignedUpTo} roomSignedUpTo=${air.roomSignedUpTo} licensed=${state.board.licensed}",
        )
        scope.launch { onBoardBound(BoardBinding(state.board.myNodeNum, boundKey(state.board), dialled = state.address)) }
    }

    /**
     * The key the profile advertises for [board] (`ProfileContent.loraKey`): its own `User.public_key` when it is 32 bytes
     * and its CRC32 is the board's node number — the binding firmware 2.8 makes at keygen (`NodeDB::createNewIdentity`)
     * and the one a contact's phone checks before it writes the key to its own board ([LoraContactPolicy], ADR
     * 2026-10.aajj). Whether the board signs does not enter into it (#243): the key is what lets a contact's board
     * PKI-encrypt a compact DM to ours, and a contact's phone reads an unsigned post as unsigned whatever key it holds.
     * Null on a pre-2.8 board (a MAC-derived number), on a board with no key yet (keygen waits for a region) and on a
     * malformed key.
     */
    private fun boundKey(board: BoardInfo): String? {
        val b64 = board.owner?.publicKey ?: return null
        val key = LoraContactPolicy.profileKey(b64) ?: return null
        return b64.takeIf { LoraContactPolicy.crc32(key) == board.myNodeNum }
    }

    // --- the board setup, and our board's want of a key (Stage 1c.5, ADR 2026-10.aajj) ---

    /**
     * Sets the connected board up for Knit — the one-tap alternative to configuring a Meshtastic board by hand — or puts
     * it back ([ProvisionMode.Restore]). On [ProvisionResult.Provisioned] the settings VM persists the returned index so
     * the plane binds to it, along with the intervals the board had before so a restore can put them back. Requires a
     * Ready link.
     */
    override suspend fun provision(
        mode: ProvisionMode,
        previous: BoardSettings?,
    ): ProvisionResult {
        provisioning = true
        try {
            return board.provision(ProvisionSpec(KnitChannel.NAME, KnitChannel.PSK, mode, previous))
        } finally {
            provisioning = false
        }
    }

    /**
     * An `add_contact` for a pinned contact's board ([LoraContactPolicy]), or why none may be written — no rank, no
     * binding key, the board's own key for them, one written already. The `39` is the board's word that it holds no key
     * for them now; the stream's copy of their NodeInfo is read for a key only if it raced this send ([sentAfter]), never
     * a stale or forged one from before.
     */
    override fun teachKey(
        peerId: String,
        holder: LoraBoardHolder,
        own: BoardAddr,
        sentAfter: Long,
    ): KeyTeach {
        val ownNode = own.meshtasticNode ?: return KeyTeach.Refused
        val streamed = holder.addr.meshtasticNode?.let(link::nodeUser)
        return when (val verdict = contacts.judge(peerId, holder, ownNode, streamed, sentAfter, clock())) {
            is LoraContactPolicy.Verdict.Write -> {
                KeyTeach.Write { writeContact(peerId, ownNode, verdict) }
            }

            is LoraContactPolicy.Verdict.Refused -> {
                if (verdict.reason == LoraContactPolicy.Refusal.BOARD_KEY_CONFLICT) metrics.onLoraDirectBoardKeyConflict()
                log("lora direct no contact $peerId reason=${verdict.reason}")
                KeyTeach.Refused
            }
        }
    }

    /**
     * Writes [write]'s `add_contact` to our own board [own] — a local admin message, no passkey, nothing on the air — and
     * says what came of it: the frame held for [peerId] is re-offered once the board's `queueStatus` for it says res 0:
     * the firmware handles a local admin message, and saves the NodeDB, before it answers (`Router.cpp:427-432`). A board
     * that refused it, or silently dropped it (`is_managed`, locked storage, `AdminModule.cpp:127`, `:178-181`), shows as
     * the re-offer's second `39`, which is then terminal. One that was never written to (no headroom, the link gone) is
     * asked again.
     */
    private suspend fun writeContact(
        peerId: String,
        own: UInt,
        write: LoraContactPolicy.Verdict.Write,
    ): KeyWrite {
        val result = board.send(write.admin, SendPurpose.Admin(BoardAddr(own))).result
        val now = clock()
        return when (result) {
            // Only res 0 records the write. A refused admission hands the frame to the shed, which the core's `enqueue`
            // abandons, so what was parked behind it is offered again.
            is SendResult.Queued -> {
                contacts.onWritten(peerId, own, write.node, write.key, now)
                metrics.onLoraDirectContactAdded()
                log("lora direct contact added ${meshNodeLabel(write.node.toLong())} for $peerId")
                KeyWrite.WRITTEN
            }

            // The write went out and no queueStatus came back: re-offered once, the write not recorded; the next 39 for
            // the same key is terminal (`LoraContactPolicy.onTimedOut`), so this cannot loop.
            is SendResult.Timeout -> {
                contacts.onTimedOut(own, write.node, write.key)
                log("lora direct contact unconfirmed ${meshNodeLabel(write.node.toLong())} for $peerId")
                KeyWrite.UNCONFIRMED
            }

            is SendResult.Busy, is SendResult.NotReady -> {
                log("lora direct contact not written for $peerId: $result")
                KeyWrite.NOT_WRITTEN
            }

            else -> {
                contacts.onWritten(peerId, own, write.node, write.key, now)
                log("lora direct contact refused by our board for $peerId: $result")
                KeyWrite.REFUSED
            }
        }
    }

    /** The destination's board took a PKI packet under the key ours holds: should ours lose it again, it may be taught once more. */
    override fun onDelivered(to: BoardAddr) {
        val own = (board.state.value as? BoardState.Ready)?.own?.meshtasticNode ?: return
        to.meshtasticNode?.let { contacts.onDelivered(own, it) }
    }

    /**
     * Books the `NodeInfo` our board sends a destination that answered `PKI_UNKNOWN_PUBKEY` (35): our board's own `User`
     * as its NodeInfo stream reported it, or a nominal one. Never PKI'd, and sent on the channel the NAK arrived on —
     * their primary, which is ours when we heard it at all (`ReliableRouter.cpp:162-168`). Signed by the firmware's rule
     * (#243, [LoraAirtime.price]): on 2.8.2+ under `BALANCED` or `STRICT` even unicast (`Router.cpp:1222-1227` at
     * `34cb8d5`), on 2.8.1 only in ham mode (`Router.cpp:1270`).
     */
    override fun bookKeyReply(now: Long) {
        val own = (board.state.value as? BoardState.Ready)?.own?.meshtasticNode
        val size =
            own?.let {
                link
                    .nodeUser(it)
                    ?.user
                    ?.raw
                    ?.size
            } ?: LoraDirectPolicy.NODEINFO_USER_BYTES
        val airtime = core.airtime
        airtime.record(size, now, airtime.price(AirSlot.PRIMARY, PacketKind.NODEINFO_UNICAST, MeshtasticProto.PORT_NODEINFO))
    }

    // --- the send-time slot guards (Stage 1c.3) ---

    /**
     * Slot 0 never carries a compact DM (`loraDirectSlotZero`, ADR 2026-10.x9fa): the Knit channel is where Knit's frames
     * go, though the firmware PKI-encrypts a unicast on any slot.
     */
    override fun directSlotRefused(bound: Int?): Boolean = bound == PublicChannelPolicy.PRIMARY_INDEX

    /**
     * The two guards on bound slot [ch], read off the session's channel table ([boundSlotIsKnit], [aeadBlocks]). A Ready
     * board with no Meshtastic session under it has no table these can read: no answer, which the core refuses — an
     * empty table would read as "don't suppress" and skip both. Never the case on a Meshtastic board.
     */
    override fun checkSlot(
        frame: OutboundFrame,
        ready: BoardState.Ready,
        ch: Int,
    ): SlotCheck? {
        val session = ready.meshtasticReady ?: return null
        return when {
            !boundSlotIsKnit(session.channels, ch) -> SlotCheck.Refused("slot $ch is not the Knit channel — set this board up")

            aeadBlocks(
                frame,
                session,
                ch,
            ) -> SlotCheck.Refused("slot $ch is AEAD (use_aead), which no other Knit board shares — set it up again")

            else -> SlotCheck.Clear
        }
    }

    /**
     * Whether the bound slot really is the Knit channel. Since ADR 045 a board is either set up for Knit or a
     * stock Meshtastic node, so a bound slot carrying anything else means the setup was undone or never run —
     * and transmitting there would put Knit's cleartext frames onto somebody else's channel, most likely the
     * public one every stock radio listens to. Silence is the only safe reading of that.
     *
     * A board that reports no channel table at all is given the benefit of the doubt: going mute on firmware
     * whose table we failed to read would be a worse failure than the one this guards against. The auto-reply asks it
     * with an empty table already refused.
     *
     * Takes [channels] rather than reading the link itself, because "the board is between sessions" is not an
     * answer to this question and must never be confused with one: a disconnected link used to fail here and
     * cost the frame, when the only correct handling of it is to put the frame back — the core's `sendFrame`.
     *
     * The name alone: a Knit slot another client switched to AEAD is still the board's Knit setup. Whether a frame may
     * ride it is [aeadBlocks]'s question, asked of the frames the slot's hash reaches.
     */
    private fun boundSlotIsKnit(
        channels: List<ChannelInfo>,
        index: Int,
    ): Boolean = channels.isEmpty() || channels.any { it.index == index && it.name == KnitChannel.NAME }

    /**
     * Whether [frame] must not be written to bound slot [index] because that slot is AEAD (`use_aead`, set by another
     * client; Knit's setup clears it, `MeshtasticSession.identityOnly`). The firmware folds AEAD into the channel hash
     * (`Channels.cpp:53`, `h ^= 0xAE`), so no other Knit board decodes a packet encrypted under it — but only a packet
     * that rides the slot: a broadcast, or a compact DM from a board in ham mode (no PKI). A compact DM off any other
     * board is PKI-encrypted and its channel set to 0 (`Router.cpp:1189-1210`, `:1366` at v2.8.1), so the slot's AEAD
     * and hash never touch it (#243, lora-reviewer's finding). `LoraAirtime.padTo` relies on the broadcast half: the bound slot a
     * Knit broadcast leaves on is never AEAD.
     */
    private fun aeadBlocks(
        frame: OutboundFrame,
        ready: LinkState.Ready?,
        index: Int,
    ): Boolean = ready != null && (frame.direct == null || ready.board.licensed) && ready.channels.any { it.index == index && it.useAead }

    /**
     * A board broadcast its `NodeInfo` with a key (ADR 2026-10.aajj): our board stores a key it lacks off one, which the
     * core acts on for a pinned contact's board. A `NodeInfo` with no key teaches our board nothing.
     */
    private fun onNodeInfo(packet: ReceivedPacket) {
        if (BoardOwnerRaw(packet.payload).owner.publicKey == null) return
        core.onBoardKeyHeard(BoardAddr(packet.from))
    }

    companion object {
        private const val NEVER = Long.MIN_VALUE

        /** The most answered DM senders carried across a restart — [DmAutoReplyPolicy.MAX_SENDERS], all of them. */
        const val STATE_AUTO_REPLY_CAP = DmAutoReplyPolicy.MAX_SENDERS

        /**
         * The Meshtastic half of the plane's status: the link state as it stands, and the board's own number while Ready —
         * byte-identical to what the transport wrote before the seam.
         */
        fun statusOf(
            state: BoardState,
            status: LoraStatus,
        ): LoraStatus = status.copy(state = state.meshtastic, boardNodeNum = state.meshtasticReady?.board?.myNodeNum)
    }
}

/**
 * Why a slot-0 chat packet was dropped before [PublicChannelPolicy] ever saw it: the user switched the
 * Meshtastic room off. A refusal name rather than a counter of its own, beside `BLOCKED_CONTACT`, because it
 * belongs in the same "heard on the primary and not delivered" tally the rest of the reasons share.
 */
internal const val MESH_POST_ROOM_OFF = "ROOM_OFF"

/**
 * Its sibling for a board pinned to a dedicated RF slot (ADR 067): the room is hidden there, so a slot-0 chat
 * packet has nowhere to go and is dropped at the same point.
 */
internal const val MESH_POST_DEDICATED = "DEDICATED"

/**
 * A post the policy took (so `meshPostIngested` counted it) that the room's inbound queue refused: `OrderedRoomSink.CAPACITY`
 * were waiting — a stalled room or a forged flood, past any board's replay at connect (ADR 2026-11.gsqu's Stage 6
 * amendment, part 6c). Never silent: counted here and logged.
 */
internal const val MESH_POST_ROOM_BACKLOG = "ROOM_BACKLOG"

/**
 * A Meshtastic board's [BoardPlane] (ADR 2026-11.gsqu, Stage 5a-2): [MeshtasticTraits], [link] through
 * [MeshtasticBoardLink], and [MeshtasticHooks] over both — exactly what the core built for itself before the kind was
 * injected.
 */
@Suppress("LongParameterList") // the kind's wiring, each one thing the hooks take once
internal fun meshtasticPlane(
    link: MeshtasticLink,
    scope: CoroutineScope,
    metrics: MeshMetrics,
    clock: () -> Long,
    log: (String) -> Unit,
    onPublicPost: suspend (MeshPost) -> Unit,
    onBoardBound: suspend (BoardBinding) -> Unit,
): BoardPlane {
    val board = MeshtasticBoardLink(link)
    return BoardPlane(MeshtasticTraits, board) { core ->
        MeshtasticHooks(link, board, core, scope, metrics, clock, log, onPublicPost, onBoardBound)
    }
}
