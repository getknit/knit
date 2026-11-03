package app.getknit.knit.mesh.protocol

import app.getknit.knit.data.message.Conversations
import app.getknit.knit.mesh.DropReason
import app.getknit.knit.mesh.crypto.MessageContent
import app.getknit.knit.mesh.crypto.MessageCrypto
import app.getknit.knit.mesh.crypto.ratchet.GroupRatchetEngine
import app.getknit.knit.mesh.crypto.ratchet.GroupRatchetSessions
import app.getknit.knit.mesh.spool.GroupRootPolicy
import app.getknit.knit.mesh.spool.GroupRootState
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/** A group roster as one party holds it (the `groups` row's roster half): names, not node ids. */
internal data class HeldGroup(
    val members: List<String>,
    val departed: List<String>,
    val createdBy: String,
)

/** What a sealed group frame was and the seed DMs its mint went out in. */
internal class GroupSend(
    val frame: TranscriptFrame,
    val minted: GroupSeed?,
    val seedDms: Map<String, TranscriptFrame>,
)

/**
 * The group section's world over one [Transcript]: one group (its id `Conversations.groupIdFor` of the founding set,
 * as `ContactsViewModel.createGroup` mints it), the roster and shared root each party holds, and the step kinds the
 * group plane adds. Every seal and open goes through the parties' real `GroupRatchetSessions` and `RatchetSessions`;
 * what `MeshManager` and `InboundPipeline` decide around them (who a seed goes to, the payload builder, the adoption
 * gates, the root policy) is mirrored here from those sources, and `InboundPipelineTest` replays the receiver's half
 * to check the mirror. Roots are stated fixtures, not draws: `GroupRootPolicy.newRoot` has no seam.
 */
internal class GroupWorld(
    val t: Transcript,
    founders: List<String>,
    creator: String,
    holdsAtStart: Set<String>,
) {
    private val parties = (founders).associateWith { t.party(it) }
    private val nameOf = parties.mapKeys { it.value.nodeId }.mapValues { it.value.name }
    val groupId: String = Conversations.groupIdFor(founders.map { t.party(it).nodeId })
    private val held = HashMap<String, HeldGroup>()

    /** The sealed content of every ctl DM this world sealed, by frame id, to check what an open decrypted. */
    private val contents = HashMap<String, MessageContent>()

    /**
     * `MeshManager.lastSeedSendAt`: `(actor, member)` → when a floored seed send last went and the pairwise root
     * (as its sha256) it went under. In-memory in production too.
     */
    private val lastSeedSend = HashMap<Pair<String, String>, Pair<Long, String?>>()

    /** The parties `InboundPipelineTest` replays as, and which of them it also re-seals their own DMs for. */
    var receivers: Map<String, Boolean> = emptyMap()
        set(value) {
            field = value
            t.pipelineChecked = { actor, op, hasApplies ->
                val ownSeals = value[actor]
                when {
                    ownSeals == null -> false
                    op == "seal" -> ownSeals
                    else -> hasApplies
                }
            }
        }

    /**
     * `MeshManager.seedSendFloorOpen`: closed while a floored send to [member] went under the same pairwise root less
     * than [SEED_RESEND_FLOOR_MS] ago; an open check stamps it.
     */
    private fun floorOpen(
        actor: String,
        member: String,
        at: Long,
    ): Boolean {
        val root =
            TranscriptJson.sha256(
                t
                    .party(actor)
                    .store
                    .sessionOf(t.party(member).nodeId)
                    ?.root,
            )
        val last = lastSeedSend[actor to member]
        if (last != null && last.second == root && at - last.first < SEED_RESEND_FLOOR_MS) return false
        lastSeedSend[actor to member] = at to root
        return true
    }

    private val roots = HashMap<String, GroupRootState>()

    init {
        val founding = HeldGroup(founders, emptyList(), creator)
        for (name in holdsAtStart) held[name] = founding
        t.extraState = ::stateJson
    }

    private fun nodeIds(names: List<String>) = names.map { t.party(it).nodeId }

    /** The roster half of `GroupEntity.toFoundingInfo` / `toGroupInfo` for [of]'s row (no name, no photo). */
    fun groupInfo(of: String): GroupInfo {
        val g = checkNotNull(held[of]) { "$of holds no row for the group" }
        return GroupInfo(
            id = groupId,
            members = nodeIds(g.members),
            createdBy = t.party(g.createdBy).nodeId,
            departed = nodeIds(g.departed).ifEmpty { null },
        )
    }

    /** [who] holds [root] as version [version] minted by [minter] — what `MeshManager.mintGroupRootsIfDue` leaves. */
    fun holdRoot(
        who: String,
        root: ByteArray,
        version: Int,
        minter: String,
        at: Long,
    ) {
        roots[who] = GroupRootPolicy.rotated(roots[who], groupId, root, version, t.party(minter).nodeId, at)
    }

    /** `MeshManager.groupKeyPayload`: [keys], the held root if any, the founding roster. */
    private fun payload(
        of: String,
        keys: List<GroupSeed>,
    ): MessageContent {
        val root = roots[of]?.takeIf { it.root != null }
        return MessageContent(
            body = "",
            ctl = MessageContent.CTL_GROUP_KEY,
            gk =
                GroupKeyPayload(
                    groupId = groupId,
                    keys = keys,
                    gr = root?.let { GroupRootPayload(root = checkNotNull(it.root), version = it.version, minter = it.minter) },
                    group = groupInfo(of),
                ),
        )
    }

    /** `MeshManager.sendSeedDm`: the outbox stamp beside the seal (none for a root-only payload), then the v2 ctl DM. */
    private suspend fun seedDm(
        from: String,
        to: String,
        content: MessageContent,
        epoch: Int?,
        at: Long,
        note: String,
    ): TranscriptFrame {
        if (epoch != null) t.party(from).groupSessions.markKeySent(groupId, t.party(to).nodeId, epoch, at)
        return t.sealContent(from, to, content, at, v3 = false, SealOptions(note = note)).also { contents[it.env.id] = content }
    }

    /**
     * [actor] sends [content] to the group at [at], as `MeshManager.sealGroupRatchet` and the frame after it: the
     * group seal (minting when the advance rules fire), then — on a mint — one seed DM per other member in roster
     * order, then the signed group frame floods.
     */
    suspend fun groupSend(
        actor: String,
        content: MessageContent,
        at: Long,
        note: String? = null,
    ): GroupSend {
        val me = t.party(actor)
        val id = t.nextFrameId()
        val aad = MessageCrypto.header(id, me.nodeId, at, groupId)
        val plaintext = content.encode()
        val drawsBefore = me.draws.count
        val sealed = checkNotNull(me.groupSessions.sealGroup(groupId, me.nodeId, plaintext, aad, at))
        val env =
            RelayEnvelope(
                type = FrameType.CHAT,
                id = id,
                senderId = me.nodeId,
                sentAt = at,
                group = groupInfo(actor),
                payload = WireCodec.encodePayload(ChatContent(enc = sealed.env)),
            )
        val frame = TranscriptFrame(t.nextStep, me, env, sealed.env, content.body)
        t.record(
            buildJsonObject {
                put("step", t.nextStep)
                put("actor", actor)
                put("op", "groupSeal")
                put("now", at)
                putJsonArray("drawsUsed") { for (i in drawsBefore until me.draws.count) add(i) }
                put("contentBody", content.body)
                TranscriptJson.content(content)?.let { put("content", it) }
                put("plaintextSchema", "MessageContent")
                put("plaintext", plaintext.toHex())
                sealed.minted?.let { put("minted", seedJson(it)) } ?: put("minted", JsonNull)
                with(t) { frameFields(frame) }
                note?.let { put("note", it) }
                put("pipelineChecked", t.pipelineChecked?.invoke(actor, "groupSeal", false) == true)
                put("groupState", stateJson(me))
            },
        )
        val seedDms = LinkedHashMap<String, TranscriptFrame>()
        sealed.minted?.let { seed ->
            for (member in checkNotNull(held[actor]).members.filter { it != actor }) {
                seedDms[member] =
                    seedDm(actor, member, payload(actor, listOf(seed)), seed.epoch, at, "the seed distribution of epoch ${seed.epoch}")
            }
        }
        return GroupSend(frame, sealed.minted, seedDms)
    }

    /** `MeshManager.gossipGroupRoot`: the held root, with whatever seeds are current, to every other member. */
    suspend fun gossipRoot(
        actor: String,
        at: Long,
    ): Map<String, TranscriptFrame> {
        checkNotNull(roots[actor]?.root) { "$actor holds no root to gossip" }
        val seeds = t.party(actor).groupSessions.currentSeeds(groupId)
        return checkNotNull(held[actor]).members.filter { it != actor }.associateWith {
            check(floorOpen(actor, it, at)) { "$actor's seed floor toward $it is closed: a scenario may not cross it" }
            seedDm(actor, it, payload(actor, seeds), seeds.firstOrNull()?.epoch, at, "root gossip")
        }
    }

    /** `MeshManager.redistributeGroupKey`: the current and draining seeds, re-sealed to [requester]. */
    suspend fun redistribute(
        actor: String,
        requester: String,
        at: Long,
    ): TranscriptFrame {
        val group = checkNotNull(held[actor]) { "redistributeGroupKey: $actor holds no row" }
        check(requester in group.members) { "redistributeGroupKey: $requester is not a member" }
        val seeds = t.party(actor).groupSessions.currentSeeds(groupId)
        check(seeds.isNotEmpty()) { "redistributeGroupKey: no seed to serve" }
        check(floorOpen(actor, requester, at)) { "$actor's seed floor toward $requester is closed: a scenario may not cross it" }
        return seedDm(
            actor,
            requester,
            payload(actor, seeds),
            seeds.first().epoch,
            at,
            "the key-request answer: current seeds, no new epoch",
        )
    }

    /** `InboundPipeline.maybeRequestGroupKey`'s seal: a `CTL_GROUP_KEY_REQ` naming the group. */
    suspend fun keyRequest(
        actor: String,
        to: String,
        at: Long,
    ): TranscriptFrame {
        val content = MessageContent(body = "", ctl = MessageContent.CTL_GROUP_KEY_REQ, gk = GroupKeyPayload(groupId))
        val frame = t.sealContent(actor, to, content, at, v3 = false, SealOptions(note = "the key request, after the heuristic fired"))
        contents[frame.env.id] = content
        t.party(actor).groupSessions.markKeyRequested(groupId, t.party(to).nodeId, at)
        return frame
    }

    /** `InboundPipeline.ackGroupSeed`: a `CTL_GROUP_KEY_ACK` for [epoch], sealed with no prekey (the session exists). */
    suspend fun ackSeed(
        actor: String,
        to: String,
        epoch: Int,
        at: Long,
    ): TranscriptFrame {
        val content =
            MessageContent(body = "", ctl = MessageContent.CTL_GROUP_KEY_ACK, gk = GroupKeyPayload(groupId = groupId, ackEpoch = epoch))
        return t
            .sealContent(actor, to, content, at, v3 = false, SealOptions(peerPrekey = false, note = "the seed's acknowledgement"))
            .also { contents[it.env.id] = content }
    }

    /**
     * [actor] records [leaver]'s departure as `GroupRepository.recordDeparture` does in one transaction: the roster
     * shrinks, the send chain is retired (ADR 2026-10.xgwj), the leaver's outbox row goes.
     */
    suspend fun depart(
        actor: String,
        leaver: String,
        at: Long,
    ) {
        val g = checkNotNull(held[actor])
        held[actor] = g.copy(members = g.members - leaver, departed = (g.departed + leaver).distinct())
        val me = t.party(actor)
        me.groupStore.retireSendChains(groupId)
        me.groupStore.deleteKeySend(groupId, t.party(leaver).nodeId)
        t.record(
            buildJsonObject {
                put("step", t.nextStep)
                put("actor", actor)
                put("op", "depart")
                put("leaver", leaver)
                put("now", at)
                put(
                    "note",
                    "GroupRepository.recordDeparture: the roster shrinks, the send chain is retired (every key gone, the " +
                        "newest number kept at count MAX_EPOCH_MESSAGES) and the leaver's outbox row goes",
                )
                put("pipelineChecked", t.pipelineChecked?.invoke(actor, "depart", false) == true)
                put("groupState", stateJson(me))
            },
        )
    }

    /** A restore onto [actor]: every ratchet row gone, the group rows kept (`BackupTables` carries them, not the ratchets). */
    fun restore(
        actor: String,
        at: Long,
    ) {
        val me = t.party(actor)
        me.wipe()
        t.record(
            buildJsonObject {
                put("step", t.nextStep)
                put("actor", actor)
                put("op", "restore")
                put("now", at)
                put(
                    "note",
                    "a backup restore (ADR 2026-09.6mj7): both ratchets' tables and the in-memory floors are empty, the " +
                        "group rows and the identity and prekeys survive; MeshManager.finishRestore then resets every DM " +
                        "peer and group co-member (AFTER_WIPE)",
                )
                put("pipelineChecked", t.pipelineChecked?.invoke(actor, "restore", false) == true)
                put("groupState", stateJson(me))
            },
        )
    }

    /**
     * [actor] opens a group frame as `InboundPipeline.decryptAndDeliverGroup` does: the group as the AAD's thread, a
     * peek, the commit. A frame that fails NO_KEY or AEAD_FAIL feeds the key-request heuristic
     * (`maybeRequestGroupKey`: the age gate, then `GroupRatchetSessions.noteUndecryptable`), recorded as `keyRequestDue`.
     */
    suspend fun groupOpen(
        actor: String,
        frame: TranscriptFrame,
        at: Long,
        expect: String,
        applies: JsonObject?,
        note: String? = null,
    ) {
        val me = t.party(actor)
        val env = frame.env
        val g = checkNotNull(frame.enc.g)
        val header = GroupRatchetHeader(g.se, g.n)
        val aad = MessageCrypto.header(env.id, env.senderId, env.sentAt, groupId)
        val outcome = me.groupSessions.peekOpen(groupId, env.senderId, header, frame.enc.nonce, frame.enc.ct, aad, at)
        var plaintext: ByteArray? = null
        if (outcome is GroupRatchetEngine.OpenOutcome.Opened) {
            assertTrue(me.groupSessions.commitOpen(groupId, env.senderId, header, frame.enc.nonce, frame.enc.ct, aad, at) {})
            plaintext = outcome.plaintext
            frame.body?.let { assertEquals(it, MessageContent.decode(outcome.plaintext)?.body) }
        }
        val name = groupOutcomeName(outcome)
        assertEquals("group step ${t.nextStep}: the stated outcome", expect, name)
        val drop = groupDropFor(outcome)
        val requestDue =
            if (drop == DropReason.GROUP_RATCHET_NO_KEY || drop == DropReason.GROUP_RATCHET_AEAD_FAIL) {
                at - env.sentAt <= GroupRatchetSessions.REQUEST_MAX_FRAME_AGE_MS &&
                    me.groupSessions.noteUndecryptable(groupId, env.senderId, env.id, at)
            } else {
                null
            }
        t.record(
            buildJsonObject {
                put("step", t.nextStep)
                put("actor", actor)
                put("op", "groupOpen")
                put("peer", frame.sender.name)
                put("now", at)
                put("opens", frame.step)
                put("layer", "engine")
                put("expect", name)
                put("drop", drop?.name)
                requestDue?.let { put("keyRequestDue", it) }
                with(t) { frameFields(frame) }
                put("plaintext", plaintext?.toHex())
                applies?.let { put("applies", it) }
                note?.let { put("note", it) }
                put("pipelineChecked", t.pipelineChecked?.invoke(actor, "open", applies != null) == true)
                put("groupState", stateJson(me))
            },
        )
    }

    /**
     * [actor] opens a group-key ctl DM ([frame], whose sealed content this world recorded) and does inside the commit what
     * `InboundPipeline` does with it — for a seed or a root: pin the roster when [actor] holds no row
     * (`pinRosterFromSeed`), adopt the seeds behind the roster gate (`adoptGroupSeeds`), adopt the root behind the same
     * gate and `GroupRootPolicy.adoptable` (`MeshManager.adoptGroupRoot`); for an ack, stamp the outbox (`onKeyAck`).
     * The file's `applies` is built from what it did, and states the post-commit answers (the ack back, the custody
     * replay, the key-request answer).
     */
    suspend fun openCtl(
        actor: String,
        frame: TranscriptFrame,
        at: Long,
        note: String? = null,
    ): JsonObject {
        val recorded = checkNotNull(contents[frame.env.id]) { "not a ctl DM this world sealed" }
        val result = CtlApplied()
        t.open(
            actor,
            frame,
            at,
            RatchetDmVectors.OPENED,
            note = note,
            onOpened = { decoded ->
                // Applied from what decrypted, never from what the test meant to send: a lossy GroupKeyPayload round
                // trip fails here.
                assertArrayEquals("the ctl DM decodes to the content sealed", recorded.encode(), checkNotNull(decoded).encode())
                result.run(actor, frame.sender.name, decoded, at)
            },
            appliesFrom = { result.applies },
        )
        // Post-commit, MeshManager.onGroupRootCtl's sends: an adopted root onward, or ours back to a sender behind it.
        for (member in result.rootSends) {
            val seeds = t.party(actor).groupSessions.currentSeeds(groupId)
            seedDm(actor, member, payload(actor, seeds), seeds.firstOrNull()?.epoch, at, "root gossip (onGroupRootCtl)")
        }
        return result.applies
    }

    /** One ctl DM's in-commit half and the `applies` it leaves. */
    private inner class CtlApplied {
        lateinit var applies: JsonObject
        var rootSends: List<String> = emptyList()

        suspend fun run(
            actor: String,
            sender: String,
            content: MessageContent,
            at: Long,
        ) {
            val gk = checkNotNull(content.gk)
            applies =
                when (content.ctl) {
                    MessageContent.CTL_GROUP_KEY -> {
                        val (json, sends) = seedApplies(actor, sender, gk, at)
                        rootSends = sends
                        json
                    }

                    MessageContent.CTL_GROUP_KEY_ACK -> {
                        t.party(actor).groupSessions.onKeyAck(groupId, t.party(sender).nodeId, checkNotNull(gk.ackEpoch), at)
                        buildJsonObject {
                            put("groupKeyAcked", gk.ackEpoch)
                            base()
                        }
                    }

                    MessageContent.CTL_GROUP_KEY_REQ -> {
                        buildJsonObject {
                            put("redistribute", true)
                            base()
                        }
                    }

                    else -> {
                        error("not a group-key ctl: ${content.ctl}")
                    }
                }
        }
    }

    private suspend fun seedApplies(
        actor: String,
        sender: String,
        gk: GroupKeyPayload,
        at: Long,
    ): Pair<JsonObject, List<String>> {
        val me = t.party(actor)
        val senderId = t.party(sender).nodeId
        val pinned = held[actor] == null && pinFromSeed(actor, sender, gk)
        val group = held[actor]
        val gated = group != null && sender in group.members
        // The engine's own verdict per seed, read before the adoption writes anything.
        val outcomes =
            gk.keys.map { seed ->
                val newest = me.groupStore.recvChains(groupId, senderId, seed.epoch).firstOrNull()
                seed to adoptOutcome(GroupRatchetEngine().adoptSeed(newest, groupId, senderId, seed.epoch, seed.seed, seed.mintedAt, at))
            }
        val adopted = if (gated && gk.keys.isNotEmpty()) me.groupSessions.adoptSeeds(groupId, senderId, gk.keys, at) else null
        if (adopted != null) {
            assertEquals("ADOPT verdicts are the fresh chains", outcomes.count { it.second == "ADOPT" }, adopted.freshChains)
        }
        val ackEpoch = adopted?.ackEpoch
        val rootAdopted = gk.gr?.let { adoptRoot(actor, gated, it, at) }
        val answer = rootAnswer(actor, sender, gk, rootAdopted == true, at)
        val json =
            buildJsonObject {
                if (pinned) {
                    put("groupPinned", rosterJson(checkNotNull(held[actor])))
                    // InboundPipeline.groupNotices: a first sight of the group writes its "created" status line.
                    put("statusNotices", buildJsonArray { add("created:$groupId") })
                }
                putJsonArray("seeds") {
                    for ((seed, outcome) in outcomes) {
                        add(
                            buildJsonObject {
                                put("epoch", seed.epoch)
                                put("mintedAt", seed.mintedAt)
                                put("outcome", if (gated) outcome else "NOT_GATED")
                            },
                        )
                    }
                }
                put("groupKeyAckBack", ackEpoch)
                put("custodyReplay", ackEpoch != null)
                gk.gr?.let { gr ->
                    putJsonObject("groupRoot") {
                        put("version", gr.version)
                        put("minter", nameOf[gr.minter] ?: gr.minter)
                        put("rootSha256", TranscriptJson.sha256(gr.root))
                        put("adopted", rootAdopted == true)
                    }
                }
                answer.first?.let { put("rootGossip", it) }
                base()
            }
        return json to answer.second
    }

    /** `MeshManager.adoptGroupRoot`: behind the seed path's gate, `GroupRootPolicy.adoptable`, then `rotated`. */
    private fun adoptRoot(
        actor: String,
        gated: Boolean,
        gr: GroupRootPayload,
        at: Long,
    ): Boolean {
        if (!gated || !GroupRootPolicy.adoptable(gr, foundingRoster(actor), roots[actor])) return false
        roots[actor] = GroupRootPolicy.rotated(roots[actor], groupId, gr.root, gr.version, gr.minter, at)
        return true
    }

    /**
     * `MeshManager.onGroupRootCtl`, run for every `CTL_GROUP_KEY`: an adopted root goes onward to every other member
     * but the sender whose floor is open (`gossipGroupRoot(skip = sender)`); a sender whose root is behind ours (or who
     * sent none) gets ours back if its floor is open. Null JSON when [actor] holds no root and nothing was adopted.
     */
    private fun rootAnswer(
        actor: String,
        sender: String,
        gk: GroupKeyPayload,
        adopted: Boolean,
        at: Long,
    ): Pair<JsonObject?, List<String>> {
        val group = held[actor]
        if (adopted) {
            val others = checkNotNull(group).members.filter { it != actor && it != sender }
            val open = others.filter { floorOpen(actor, it, at) }
            return buildJsonObject {
                put("onward", buildJsonArray { open.forEach { add(it) } })
                put("floored", buildJsonArray { (others - open.toSet()).forEach { add(it) } })
            } to open
        }
        val mine = roots[actor]?.takeIf { it.root != null } ?: return null to emptyList()
        val theirs = gk.gr
        val why =
            when {
                theirs != null && theirs.version == mine.version && theirs.minter == mine.minter -> "their root is ours"

                group == null -> "no row"

                !floorOpen(
                    actor,
                    sender,
                    at,
                ) -> "seedSendFloorOpen closed: a floored send went to $sender under this pairwise root inside SEED_RESEND_FLOOR_MS"

                else -> null
            }
        return buildJsonObject {
            put("back", why == null)
            why?.let { put("why", it) }
        } to listOfNotNull(sender.takeIf { why == null })
    }

    private fun JsonObjectBuilder.base() {
        put("receipts", buildJsonArray { })
        put("acksBack", false)
    }

    private fun adoptOutcome(outcome: GroupRatchetEngine.AdoptOutcome): String =
        when (outcome) {
            is GroupRatchetEngine.AdoptOutcome.Adopt -> "ADOPT"
            GroupRatchetEngine.AdoptOutcome.AlreadyKnown -> "ALREADY_KNOWN"
            GroupRatchetEngine.AdoptOutcome.Stale -> "STALE"
        }

    /**
     * `InboundPipeline.pinRosterFromSeed` → `reconcileGroup`/`vetRoster`, first sight: the id must be the founding
     * set's hash (members then departed) and both of us in it; the whole founding set is pinned as members, because
     * tombstones are only ever our own (a frame's `departed` is never adopted — a leave lands by the leaver's frame).
     */
    private fun pinFromSeed(
        actor: String,
        sender: String,
        gk: GroupKeyPayload,
    ): Boolean {
        val roster = gk.group ?: return false
        if (roster.id != gk.groupId || roster.members.size > GroupInfo.MAX_MEMBERS) return false
        val founding = (roster.members + roster.departed.orEmpty()).distinct()
        if (founding.size > GroupInfo.MAX_MEMBERS || Conversations.groupIdFor(founding) != roster.id) return false
        if (t.party(actor).nodeId !in founding || t.party(sender).nodeId !in founding) return false
        held[actor] =
            HeldGroup(
                members = founding.map { checkNotNull(nameOf[it]) },
                departed = emptyList(),
                createdBy = checkNotNull(nameOf[roster.createdBy]),
            )
        return true
    }

    /** `MeshManager.foundingRoster`: members and departed, as node ids. */
    private fun foundingRoster(of: String): Set<String> {
        val g = checkNotNull(held[of])
        return nodeIds(g.members + g.departed).toSet()
    }

    private fun rosterJson(g: HeldGroup) =
        buildJsonObject {
            put("members", buildJsonArray { g.members.forEach { add(it) } })
            put("departed", buildJsonArray { g.departed.forEach { add(it) } })
            put("createdBy", g.createdBy)
        }

    private fun seedJson(seed: GroupSeed) =
        buildJsonObject {
            put("epoch", seed.epoch)
            put("mintedAt", seed.mintedAt)
            put("seed", seed.seed.toHex())
        }

    /** The group-plane state [p] holds: roster, shared root, send chains, receive chains, skipped keys, outbox. */
    fun stateJson(p: TranscriptParty): JsonObject =
        buildJsonObject {
            put("of", p.name)
            put("roster", held[p.name]?.let(::rosterJson) ?: JsonNull)
            put(
                "root",
                roots[p.name]?.let { r ->
                    buildJsonObject {
                        put("version", r.version)
                        put("minter", nameOf[r.minter] ?: r.minter)
                        put("rootSha256", TranscriptJson.sha256(r.root))
                        put("prevVersion", r.prevVersion)
                        put("prevRootSha256", TranscriptJson.sha256(r.prevRoot))
                    }
                } ?: JsonNull,
            )
            put("sendChains", sendChainsJson(p))
            put("recvChains", recvChainsJson(p))
            put("skippedKeys", skippedJson(p))
            put("outbox", outboxJson(p))
        }

    private fun sendChainsJson(p: TranscriptParty) =
        buildJsonArray {
            for (c in p.groupStore.allSendChains(groupId)) {
                add(
                    buildJsonObject {
                        put("epoch", c.epoch)
                        put("mintedAt", c.mintedAt)
                        put("count", c.count)
                        put("retired", c.isRetired)
                        put("seedSha256", TranscriptJson.sha256(c.seed.takeIf { it.isNotEmpty() }))
                        put("chainKeySha256", TranscriptJson.sha256(c.chainKey.takeIf { it.isNotEmpty() }))
                    },
                )
            }
        }

    private fun recvChainsJson(p: TranscriptParty) =
        buildJsonArray {
            for (c in p.groupStore.allRecvChains(groupId)) {
                add(
                    buildJsonObject {
                        put("sender", nameOf[c.senderId] ?: c.senderId)
                        put("epoch", c.epoch)
                        put("mintedAt", c.mintedAt)
                        put("next", c.next)
                        put("chainKeySha256", TranscriptJson.sha256(c.chainKey))
                        put("lastUsedAt", c.lastUsedAt)
                    },
                )
            }
        }

    private fun skippedJson(p: TranscriptParty) =
        buildJsonArray {
            for ((sender, k) in p.groupStore.allSkipped(groupId)) {
                add(
                    buildJsonObject {
                        put("sender", nameOf[sender] ?: sender)
                        put("epoch", k.epoch)
                        put("mintedAt", k.mintedAt)
                        put("idx", k.idx)
                    },
                )
            }
        }

    private fun outboxJson(p: TranscriptParty) =
        buildJsonArray {
            for (o in p.groupStore.outboxOf(groupId)) {
                add(
                    buildJsonObject {
                        put("member", nameOf[o.memberId] ?: o.memberId)
                        put("sentEpoch", o.sentEpoch)
                        put("sentAt", o.sentAt)
                        put("ackedEpoch", o.ackedEpoch)
                        put("ackedAt", o.ackedAt)
                    },
                )
            }
        }

    companion object {
        /** `MeshManager.SEED_RESEND_FLOOR_MS` (private to it): one floored seed send per member per pairwise root. */
        const val SEED_RESEND_FLOOR_MS = 15 * 60_000L

        fun groupOutcomeName(outcome: GroupRatchetEngine.OpenOutcome): String =
            when (outcome) {
                is GroupRatchetEngine.OpenOutcome.Opened -> "OPENED"
                is GroupRatchetEngine.OpenOutcome.Failed -> outcome.name
            }

        /** `InboundPipeline.onGroupRatchetFailure`'s mapping; null for a frame that opened. */
        fun groupDropFor(outcome: GroupRatchetEngine.OpenOutcome): DropReason? =
            when (outcome) {
                is GroupRatchetEngine.OpenOutcome.Opened -> null
                GroupRatchetEngine.OpenOutcome.Failed.NO_KEY -> DropReason.GROUP_RATCHET_NO_KEY
                GroupRatchetEngine.OpenOutcome.Failed.DUPLICATE -> DropReason.GROUP_RATCHET_DUPLICATE
                GroupRatchetEngine.OpenOutcome.Failed.BAD_HEADER -> DropReason.GROUP_RATCHET_BAD_HEADER
                GroupRatchetEngine.OpenOutcome.Failed.AEAD_FAIL -> DropReason.GROUP_RATCHET_AEAD_FAIL
            }
    }
}
