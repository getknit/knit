package app.getknit.knit.mesh.protocol

import app.getknit.knit.mesh.DropReason
import app.getknit.knit.mesh.InboundPipeline
import app.getknit.knit.mesh.crypto.AesGcm
import app.getknit.knit.mesh.crypto.MessageContent
import app.getknit.knit.mesh.crypto.MessageContentV2
import app.getknit.knit.mesh.crypto.MessageCrypto
import app.getknit.knit.mesh.crypto.ratchet.GroupRatchetCrypto
import app.getknit.knit.mesh.crypto.ratchet.GroupRatchetEngine
import app.getknit.knit.mesh.crypto.ratchet.GroupRatchetSessions
import app.getknit.knit.mesh.crypto.ratchet.RatchetCrypto
import app.getknit.knit.mesh.crypto.ratchet.RatchetEngine
import app.getknit.knit.mesh.crypto.ratchet.RatchetEngine.OpenOutcome
import app.getknit.knit.mesh.crypto.ratchet.RatchetSessions
import app.getknit.knit.mesh.crypto.ratchet.RatchetStore
import app.getknit.knit.mesh.crypto.sealBytes
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.security.MessageDigest

/*
 * The machinery behind `ratchet-v1.json`'s `dm` and `negative` transcripts (companion change A9): two parties, each a
 * real [RatchetSessions] over an in-memory [RatchetStore], whose engines draw every key pair and v2 IV from a stated
 * plan of [fixtureBytes] seeds. Frames are built the way `MeshManager.sealEnvelopeFor` builds a DM — the row's content
 * through `sealBytes`, `RatchetSessions.sealDm`, a `chat` frame signed by the sender — and opened the way
 * `InboundPipeline.decryptAndDeliverV2` opens one: from the signed wire, peek then commit. So every byte is rebuilt
 * on every run, and the file can state only what the code does.
 */

/** One injected draw, in the order an engine consumes it. */
internal sealed class Draw(
    val seed: Int,
) {
    /** An X25519 pair: `priv = fixtureBytes(32, seed)`. */
    class Key(
        seed: Int,
    ) : Draw(seed)

    /** A v2 AES-GCM IV: `fixtureBytes(12, seed)`. */
    class Iv(
        seed: Int,
    ) : Draw(seed)

    /** A group epoch seed (`GroupRatchetEngine`'s `newSeed`): `fixtureBytes(32, seed)`. */
    class GroupSeed(
        seed: Int,
    ) : Draw(seed)
}

internal fun keyPairFrom(seed: Int): RatchetCrypto.KeyPair {
    val priv = fixtureBytes(RatchetCrypto.KEY_BYTES, seed)
    return RatchetCrypto.KeyPair(priv, RatchetCrypto.publicFromPrivate(priv))
}

/**
 * Pops [plan] in order and fails loudly on a draw of the wrong kind or a plan left unspent, so a change to the
 * engine's draw order cannot pass silently, in write mode as much as check mode.
 */
internal class DrawSource(
    private val owner: String,
    private val plan: List<Draw>,
) {
    private var taken = 0

    val count: Int get() = taken

    fun keyPair(): RatchetCrypto.KeyPair {
        val draw = next()
        check(draw is Draw.Key) { "$owner drew a key pair where its plan has an IV (seed ${draw.seed})" }
        return keyPairFrom(draw.seed)
    }

    fun iv(): ByteArray {
        val draw = next()
        check(draw is Draw.Iv) { "$owner drew an IV where its plan has another kind (seed ${draw.seed})" }
        return fixtureBytes(AesGcm.IV_BYTES, draw.seed)
    }

    fun groupSeed(): ByteArray {
        val draw = next()
        check(draw is Draw.GroupSeed) { "$owner drew a group seed where its plan has another kind (seed ${draw.seed})" }
        return fixtureBytes(GroupRatchetCrypto.SEED_BYTES, draw.seed)
    }

    private fun next(): Draw {
        check(taken < plan.size) { "$owner drew more than its plan's ${plan.size} draws" }
        return plan[taken++]
    }

    fun assertSpent() = check(taken == plan.size) { "$owner consumed $taken of its ${plan.size} planned draws" }

    fun json(): JsonArray =
        buildJsonArray {
            plan.forEachIndexed { index, draw ->
                add(
                    buildJsonObject {
                        put("index", index)
                        put("seed", draw.seed)
                        when (draw) {
                            is Draw.Key -> {
                                val pair = keyPairFrom(draw.seed)
                                put("kind", "keyPair")
                                put("priv", pair.priv.toHex())
                                put("pub", pair.pub.toHex())
                            }

                            is Draw.Iv -> {
                                put("kind", "iv")
                                put("iv", fixtureBytes(AesGcm.IV_BYTES, draw.seed).toHex())
                            }

                            is Draw.GroupSeed -> {
                                put("kind", "groupSeed")
                                put("groupSeed", fixtureBytes(GroupRatchetCrypto.SEED_BYTES, draw.seed).toHex())
                            }
                        }
                    },
                )
            }
        }
}

/**
 * [RatchetStore] in memory, with `RatchetRepository`'s row semantics over `RatchetDao`: sessions, local epochs and
 * receive chains upsert (REPLACE), skipped keys insert-or-ignore on `(epoch, idx, rootTag)`, a consumed skipped key
 * goes by its own root's tag only, and local epochs list most recently minted first.
 */
internal class MemoryRatchetStore : RatchetStore {
    private val sessions = LinkedHashMap<String, RatchetEngine.SessionState>()
    private val localEpochs = LinkedHashMap<Pair<String, Int>, RatchetEngine.LocalEpoch>()
    private val recv = LinkedHashMap<Triple<String, Int, Long>, RatchetEngine.RecvEpoch>()
    private val skipped = LinkedHashMap<SkippedKeyId, RatchetEngine.SkippedKey>()

    private data class SkippedKeyId(
        val peerId: String,
        val epoch: Int,
        val idx: Int,
        val rootTag: Long,
    )

    override suspend fun session(peerId: String) = sessions[peerId]

    override suspend fun sessionPeerIds() = sessions.keys.toList()

    override suspend fun upsertSession(state: RatchetEngine.SessionState) {
        sessions[state.peerId] = state
    }

    override suspend fun localEpochPriv(
        peerId: String,
        epoch: Int,
    ): ByteArray? = localEpochs[peerId to epoch]?.priv

    override suspend fun lowestLocalEpoch(peerId: String): Int? = localEpochs.keys.filter { it.first == peerId }.minOfOrNull { it.second }

    override suspend fun recvEpochs(
        peerId: String,
        epoch: Int,
    ) = recv.filterKeys { it.first == peerId && it.second == epoch }.values.toList()

    override suspend fun skippedKeys(
        peerId: String,
        epoch: Int,
        idx: Int,
    ) = skipped.filterKeys { it.peerId == peerId && it.epoch == epoch && it.idx == idx }.values.toList()

    override suspend fun applyOpen(
        peerId: String,
        delta: RatchetEngine.OpenDelta,
        headerSe: Int,
        headerN: Int,
    ) {
        if (delta.purgePeerRecvState) {
            // A reclaim (ADR 2026-10.4mjm) spares the one root it moved back to, as RatchetRepository's purge does.
            val keep = delta.keepRecvTag
            recv.keys.removeAll { it.first == peerId && it.third != keep }
            skipped.keys.removeAll { it.peerId == peerId && it.rootTag != keep }
        }
        sessions[peerId] = delta.session
        delta.recvEpoch?.let { recv[Triple(peerId, it.epoch, it.rootTag)] = it }
        for (key in delta.skippedInserts) skipped.putIfAbsent(SkippedKeyId(peerId, key.epoch, key.idx, key.rootTag), key)
        delta.consumedSkippedTag?.let { skipped.remove(SkippedKeyId(peerId, headerSe, headerN, it)) }
    }

    override suspend fun commitSend(
        state: RatchetEngine.SessionState,
        newLocalEpoch: RatchetEngine.LocalEpoch?,
    ) {
        sessions[state.peerId] = state
        newLocalEpoch?.let { localEpochs[state.peerId to it.epoch] = it }
    }

    override suspend fun purgePeerRecvState(peerId: String) {
        recv.keys.removeAll { it.first == peerId }
        skipped.keys.removeAll { it.peerId == peerId }
    }

    override suspend fun deletePeer(peerId: String) {
        sessions.remove(peerId)
        localEpochs.keys.removeAll { it.first == peerId }
        purgePeerRecvState(peerId)
    }

    override suspend fun debugLocalEpochs(peerId: String): List<Pair<Int, Long>> = localEpochsOf(peerId).map { it.epoch to it.createdAt }

    // Retention is a stage-3b transcript of its own (the pruned prekey and epoch cases need it); until then no
    // transcript may lean on a sweep this fake does not perform.
    override suspend fun sweep(now: Long): Unit = throw UnsupportedOperationException("no transcript sweeps yet")

    fun sessionOf(peerId: String): RatchetEngine.SessionState? = sessions[peerId]

    fun localEpochsOf(peerId: String): List<RatchetEngine.LocalEpoch> =
        localEpochs
            .filterKeys { it.first == peerId }
            .values
            .sortedWith(compareByDescending<RatchetEngine.LocalEpoch> { it.createdAt }.thenByDescending { it.epoch })

    fun recvOf(peerId: String): List<RatchetEngine.RecvEpoch> =
        recv.filterKeys { it.first == peerId }.values.sortedWith(compareBy({ it.epoch }, { it.rootTag }))

    fun skippedOf(peerId: String): List<RatchetEngine.SkippedKey> =
        skipped
            .filterKeys { it.peerId == peerId }
            .values
            .sortedWith(compareBy({ it.epoch }, { it.rootTag }, { it.idx }))
}

/** A signed prekey a transcript party holds (its private half) or pins for its peer (its public half). */
internal class VectorPrekey(
    val name: String,
    val id: Int,
    val keys: RatchetCrypto.KeyPair,
)

/** One side of a transcript: an identity, the prekeys it holds, its draw plan and its own session service. */
internal class TranscriptParty(
    val name: String,
    val identity: VectorParty,
    val holds: List<VectorPrekey>,
    plan: List<Draw>,
    /** The `identities` entry [identity] is. */
    val identityName: String = name,
) {
    val draws = DrawSource(name, plan)

    // The one ratchet lock both facades share, as production wires them (`ratchetMutex`).
    private var mutex = Mutex()
    var store = MemoryRatchetStore()
        private set
    var sessions = newSessions()
        private set
    var groupStore = MemoryGroupRatchetStore()
        private set
    var groupSessions = newGroupSessions()
        private set

    private fun newSessions() =
        RatchetSessions(
            store = store,
            dhIdentityPriv = { identity.agreementScalar },
            spkPrivFor = { id -> holds.firstOrNull { it.id == id }?.keys?.priv },
            engine = RatchetEngine(newKeyPair = draws::keyPair, newIv = draws::iv),
            mutex = mutex,
        )

    private fun newGroupSessions() = GroupRatchetSessions(groupStore, GroupRatchetEngine(draws::groupSeed, draws::iv), mutex)

    /**
     * What a database wipe leaves (the `DatabaseKey` loss ADR 2026-10.47rw describes): every ratchet table and the
     * in-memory floors gone, the identity and the prekey privates kept (`IdentityKeyStore` survives it). The draw
     * plan runs on.
     */
    fun wipe() {
        mutex = Mutex()
        store = MemoryRatchetStore()
        sessions = newSessions()
        groupStore = MemoryGroupRatchetStore()
        groupSessions = newGroupSessions()
    }

    val nodeId: String get() = identity.nodeId
}

/** A DM frame as it went on the air: its wire bytes, and what built them. */
internal class TranscriptFrame(
    val step: Int,
    val sender: TranscriptParty,
    val env: RelayEnvelope,
    val enc: EncEnvelope,
    /** The content it carries, or null for a frame a test built by hand (a mutation). */
    val body: String?,
    /** What a mutation changed, for the file; null for a frame exactly as the sender sealed it. */
    val mutation: String? = null,
    /**
     * False for the unsigned point-to-point form (ADR 059): `relay = false` and an empty signature, as
     * `MeshManager.unsigned` sends a v3 live-link tick.
     */
    val signed: Boolean = true,
) {
    val wire: ByteArray =
        if (signed) {
            sender.identity.signedWire(env)
        } else {
            WireCodec.encodeWire(WireEnvelope(relay = false, sig = ByteArray(0), signed = WireCodec.encodeEnvelope(env)))
        }

    /** The same frame with [enc] swapped in and re-signed by the sender — a buggy (never a forging) peer. */
    fun mutated(
        description: String,
        newEnc: EncEnvelope,
        group: GroupInfo? = null,
    ): TranscriptFrame {
        val content = WireCodec.decodePayload<ChatContent>(env.payload)!!.copy(enc = newEnc)
        val reAddressed =
            RelayEnvelope(
                type = env.type,
                id = env.id,
                senderId = env.senderId,
                sentAt = env.sentAt,
                recipientId = if (group == null) env.recipientId else null,
                group = group,
                payload = WireCodec.encodePayload(content),
            )
        return TranscriptFrame(step, sender, reAddressed, newEnc, body = null, mutation = description)
    }

    /** These very envelope bytes, signed and flooded as an ordinary frame: what a captured tick's signed twin is. */
    fun signedCopy(): TranscriptFrame = TranscriptFrame(step, sender, env, enc, body, mutation = "the same envelope, signed")
}

/** Where a refusal happens, and what the pipeline records for it. */
internal enum class Layer { ENGINE, PIPELINE }

/** What a seal sends besides its content: the knobs `MeshManager`'s other seal sites turn. */
internal class SealOptions(
    /** The frame id; null mints the transcript's next one. A re-seal passes the original's. */
    val id: String? = null,
    /** `sentAt` when it differs from the seal's `now` (a re-seal keeps the row's). */
    val sentAt: Long? = null,
    val signed: Boolean = true,
    /** The cleartext `ChatContent.attachmentHash` (a sealed profile's avatar hint). */
    val attachmentHash: String? = null,
    /** The step whose frame this re-seals (same id, same sentAt, new bytes). */
    val resealOf: Int? = null,
    val note: String? = null,
    /** False for a seal site that passes no prekey (`InboundPipeline.ackGroupSeed`: the session exists by construction). */
    val peerPrekey: Boolean = true,
    /**
     * A frame the receiving party's own pipeline seals in answer to the open before it (a delivery tick,
     * `InboundPipeline.sealDmReceipt`), not one a user or MeshManager sends: recorded as `sealedByPipeline`.
     */
    val byPipeline: Boolean = false,
)

/**
 * One recorded transcript: the steps run as they are declared, each leaving a digest of its actor's state, and
 * [json] lays it out. A scenario that adds a step kind adds a function here, and the scenarios in
 * `RatchetDmVectors` stay a list of calls. [recordFloors] adds `floorsBefore` to every step — the in-memory
 * floors `RatchetSessions` decides resets and replacements by, which the state digest does not hold.
 */
internal class Transcript(
    private val name: String,
    private val note: String,
    parties: List<TranscriptParty>,
    private val recordFloors: Boolean = false,
) {
    private val parties = parties.associateBy { it.name }
    private val pins = HashMap<Pair<String, String>, VectorPrekey>()
    private val steps = mutableListOf<JsonObject>()
    private val caseFields = LinkedHashMap<String, JsonElement>()

    /**
     * A second state digest every step adds under `groupState` — the group section's per-party group-ratchet and
     * roster state. Null for the `dm` and `negative` transcripts, whose steps it never touches.
     */
    var extraState: ((TranscriptParty) -> JsonObject)? = null

    /**
     * Whether `InboundPipelineTest` checks a step of [actor]'s ([op], whether it states `applies`) — the group
     * section's `pipelineChecked`. Null for the `dm` and `negative` transcripts, which carry no such key.
     */
    var pipelineChecked: ((actor: String, op: String, hasApplies: Boolean) -> Boolean)? = null

    /** The index the next recorded step takes. */
    val nextStep: Int get() = steps.size

    /** The frame id a step kind outside this class mints for its next frame, as [sealContent] mints its own. */
    fun nextFrameId(): String = frameIdFor("$name/${steps.size}")

    /** Appends a step a step kind outside this class built (the group section's). */
    fun record(step: JsonObject) {
        steps += step
    }

    private fun JsonObjectBuilder.extra(party: TranscriptParty) {
        extraState?.let { put("groupState", it(party)) }
    }

    fun party(name: String): TranscriptParty = parties.getValue(name)

    /** [who] seals to [peer] against [prekey], the peer's pinned prekey (passed on every seal, as production does). */
    fun pin(
        who: String,
        peer: String,
        prekey: VectorPrekey,
    ) {
        pins[who to peer] = prekey
    }

    /** A case-level field for the file (the negative cases' summary). */
    fun field(
        key: String,
        value: JsonElement,
    ) {
        caseFields[key] = value
    }

    private fun prekeyFor(
        actor: String,
        peer: String,
    ) = pins[actor to peer]?.let { RatchetEngine.PeerPrekey(it.id, it.keys.pub) }

    /** Seals [body] from [actor] to [peer] at [at] (the sender's clock: `sentAt` and the seal's `now` alike). */
    suspend fun seal(
        actor: String,
        peer: String,
        body: String,
        at: Long,
        v3: Boolean,
    ): TranscriptFrame =
        // MeshManager.sealEnvelopeFor: the row's sealed content, no inline acks, laid out for the peer's scheme.
        sealContent(actor, peer, MessageContent(body = body).copy(acks = null), at, v3)

    /**
     * Seals [content] from [actor] to [peer] the way every DM-form seal site in `MeshManager` and `InboundPipeline`
     * does — `sealBytes` for the peer's scheme, `RatchetSessions.sealDm`, a `chat` frame addressed to the peer — with
     * [options] for what a particular site adds.
     */
    suspend fun sealContent(
        actor: String,
        peer: String,
        content: MessageContent,
        at: Long,
        v3: Boolean,
        options: SealOptions = SealOptions(),
    ): TranscriptFrame {
        val floors = floorsBefore(actor, peer)
        val from = party(actor)
        val to = party(peer)
        val id = options.id ?: frameIdFor("$name/${steps.size}")
        val sentAt = options.sentAt ?: at
        val aad = MessageCrypto.header(id, from.nodeId, sentAt, to.nodeId)
        val (plaintext, scheme) = content.sealBytes(v3)
        val drawsBefore = from.draws.count
        val prekey = pins[actor to peer]?.takeIf { options.peerPrekey }
        val enc =
            checkNotNull(
                from.sessions.sealDm(
                    peerId = to.nodeId,
                    peerIkPub = to.identity.bundle.dhPublicKey(),
                    peerSpk = prekeyFor(actor, peer)?.takeIf { options.peerPrekey },
                    plaintext = plaintext,
                    aad = aad,
                    now = at,
                    scheme = scheme,
                ),
            ) { "$name: $actor could not seal to $peer" }
        val env = chatTo(id, from, sentAt, to, ChatContent(enc = enc, attachmentHash = options.attachmentHash))
        val frame = TranscriptFrame(steps.size, from, env, enc, content.body, signed = options.signed)
        steps +=
            buildJsonObject {
                put("step", steps.size)
                put("actor", actor)
                put("op", "seal")
                put("peer", peer)
                put("now", at)
                put("peerPrekey", prekey?.name)
                putJsonArray("drawsUsed") { for (i in drawsBefore until from.draws.count) add(i) }
                put("contentBody", content.body)
                put("plaintextSchema", if (scheme == EncEnvelope.VERSION_DM_V3) "MessageContentV2" else "MessageContent")
                put("plaintext", plaintext.toHex())
                frameFields(frame)
                put("state", TranscriptJson.state(from, to))
                TranscriptJson.content(content)?.let { put("content", it) }
                options.attachmentHash?.let { put("cleartextAttachmentHash", it) }
                options.resealOf?.let { put("resealOf", it) }
                options.note?.let { put("note", it) }
                floors?.let { put("floorsBefore", it) }
                if (options.byPipeline) put("sealedByPipeline", true)
                pipelineChecked?.let { put("pipelineChecked", it(actor, if (options.byPipeline) "pipelineSeal" else "seal", false)) }
                extra(from)
            }
        return frame
    }

    /**
     * [actor] re-seals [original]'s content to [peer] at [at] as `MeshManager.resealAndFlood` does after the peer's
     * reset: the row's own id and `sentAt` — so the same AAD — under whatever session the actor holds now.
     */
    suspend fun reseal(
        actor: String,
        peer: String,
        original: TranscriptFrame,
        at: Long,
        v3: Boolean,
        note: String? = null,
    ): TranscriptFrame =
        sealContent(
            actor,
            peer,
            MessageContent(body = checkNotNull(original.body)),
            at,
            v3,
            SealOptions(id = original.env.id, sentAt = original.env.sentAt, resealOf = original.step, note = note),
        )

    /**
     * [actor] asks `RatchetSessions.sealResetDm` for a session reset toward [peer] at [at], as
     * `InboundPipeline.sendSessionReset` does: the `CTL_SESSION_RESET` marker, always the named v2 plaintext, and on
     * success a signed `chat` frame. [expect] is `REROOT`, `MARK` or `DECLINED`; a declined reset seals nothing.
     */
    suspend fun reset(
        actor: String,
        peer: String,
        at: Long,
        cause: RatchetSessions.ResetCause,
        expect: String,
        // The field (and floor) that decided the plan, for the file: a port keys its plan on the same one.
        decidedBy: String,
        note: String? = null,
    ): TranscriptFrame? {
        val floors = floorsBefore(actor, peer)
        val from = party(actor)
        val to = party(peer)
        val id = frameIdFor("$name/${steps.size}")
        val plaintext = MessageContent(body = "", ctl = MessageContent.CTL_SESSION_RESET).encode()
        val drawsBefore = from.draws.count
        val seal =
            from.sessions.sealResetDm(
                peerId = to.nodeId,
                peerIkPub = to.identity.bundle.dhPublicKey(),
                peerSpk = prekeyFor(actor, peer),
                plaintext = plaintext,
                aad = MessageCrypto.header(id, from.nodeId, at, to.nodeId),
                now = at,
                cause = cause,
            )
        val result =
            when (seal) {
                is RatchetSessions.ResetSeal.Sealed -> if (seal.reRooted) "REROOT" else "MARK"
                is RatchetSessions.ResetSeal.Declined -> "DECLINED"
                RatchetSessions.ResetSeal.NoPrekey -> "NO_PREKEY"
            }
        assertEquals("$name step ${steps.size}: the reset's plan", expect, result)
        val frame =
            (seal as? RatchetSessions.ResetSeal.Sealed)?.let {
                // A reset always seals v2 (RatchetSessions.resetEnvelope), toward a v3 reader too.
                assertEquals("$name: a reset seals v2", EncEnvelope.VERSION_RATCHET, it.envelope.v)
                val env = chatTo(id, from, at, to, ChatContent(enc = it.envelope))
                TranscriptFrame(steps.size, from, env, it.envelope, body = "")
            }
        steps +=
            buildJsonObject {
                put("step", steps.size)
                put("actor", actor)
                put("op", "reset")
                put("peer", peer)
                put("now", at)
                put("cause", cause.name)
                put("peerPrekey", pins[actor to peer]?.name)
                put("result", result)
                put("reason", (seal as? RatchetSessions.ResetSeal.Declined)?.reason)
                put("decidedBy", decidedBy)
                putJsonArray("drawsUsed") { for (i in drawsBefore until from.draws.count) add(i) }
                if (frame != null) {
                    put("plaintextSchema", "MessageContent")
                    put("plaintext", plaintext.toHex())
                    frameFields(frame)
                }
                put("state", TranscriptJson.state(from, to))
                note?.let { put("note", it) }
                floors?.let { put("floorsBefore", it) }
                pipelineChecked?.let { put("pipelineChecked", it(actor, "reset", false)) }
                extra(from)
            }
        return frame
    }

    /**
     * Opens [frame] as [actor], the way `InboundPipeline.decryptAndDeliverV2` does — from the wire, the v1 header as
     * AAD, the nonce dropped for v3, a lock-free peek and, when it opens, the commit — and asserts the outcome is
     * [expect] (an `OpenOutcome` name, `OPENED` for success). [applies] states what the pipeline does with what
     * opened (a pipeline replay checks it); the session layer has no opinion on it.
     */
    suspend fun open(
        actor: String,
        frame: TranscriptFrame,
        at: Long,
        expect: String,
        applies: JsonObject? = null,
        note: String? = null,
        // What production runs inside the commit with the opened content (a seed's adoption, an ack's outbox stamp).
        onOpened: (suspend (MessageContent?) -> Unit)? = null,
        // [applies] when it is only known once [onOpened] has run.
        appliesFrom: (() -> JsonObject)? = null,
    ) {
        val floors = floorsBefore(actor, frame.sender.name)
        val me = party(actor)
        val sender = frame.sender
        val wire = checkNotNull(WireCodec.decodeWire(frame.wire))
        if (frame.signed) {
            assertTrue("$name: the frame verifies", MessageCrypto.verify(sender.identity.bundle, wire.sig, wire.signed))
        } else {
            assertTrue("$name: an unsigned frame carries no signature", wire.sig.isEmpty() && !wire.relay)
        }
        val env = checkNotNull(WireCodec.decodeEnvelope(wire.signed))
        val enc = checkNotNull(WireCodec.decodePayload<ChatContent>(env.payload)?.enc)
        val header = checkNotNull(enc.r)
        val aad = MessageCrypto.header(env.id, env.senderId, env.sentAt, env.recipientId.orEmpty())
        val v3 = enc.v == EncEnvelope.VERSION_DM_V3
        val nonce = enc.nonce.takeIf { !v3 }
        val peerIk = sender.identity.bundle.dhPublicKey()
        val outcome = me.sessions.peekOpen(me.nodeId, env.senderId, peerIk, header, nonce, enc.ct, aad, at)
        var plaintext: ByteArray? = null
        if (outcome is OpenOutcome.Opened) {
            val decoded = if (v3) MessageContentV2.decode(outcome.plaintext) else MessageContent.decode(outcome.plaintext)
            assertTrue(
                "$name: commits what it peeked",
                me.sessions.commitOpen(me.nodeId, env.senderId, peerIk, header, nonce, enc.ct, aad, at) { onOpened?.invoke(decoded) },
            )
            plaintext = outcome.plaintext
            frame.body?.let { assertEquals("$name: the body survives", it, decoded?.body) }
        }
        val name = outcomeName(outcome)
        assertEquals("$name step ${steps.size}: the stated outcome", expect, name)
        steps +=
            openStep(actor, sender.name, frame, at, Layer.ENGINE, name, dropFor(outcome)) {
                put("plaintext", plaintext?.toHex())
                put("state", TranscriptJson.state(me, sender))
                val stated = applies ?: appliesFrom?.invoke()
                stated?.let { put("applies", it) }
                note?.let { put("note", it) }
                floors?.let { put("floorsBefore", it) }
                pipelineChecked?.let { put("pipelineChecked", it(actor, "open", stated != null)) }
                extra(me)
            }
    }

    /**
     * A frame refused above the engine, by `InboundPipeline` (a nonce of the wrong shape for its version, a v3 frame
     * addressed to a group, a key the X25519 layer refuses to use, an unsigned frame that is not a tick). The file
     * states the drop the pipeline records; `InboundPipelineTest` replays it there. [atSessions] checks what the
     * session layer itself does with it, if anything is worth checking, and the actor's state is recorded after it.
     */
    suspend fun refusedByPipeline(
        actor: String,
        frame: TranscriptFrame,
        at: Long,
        drop: DropReason,
        // False where a port may name the drop differently (the refusal itself — no commit, no reset — is normative).
        dropNormative: Boolean = true,
        dropNote: String? = null,
        atSessions: (suspend (TranscriptParty) -> Unit)? = null,
    ) {
        val floors = floorsBefore(actor, frame.sender.name)
        val me = party(actor)
        atSessions?.invoke(me)
        steps +=
            openStep(actor, frame.sender.name, frame, at, Layer.PIPELINE, "REFUSED", drop, dropNormative) {
                dropNote?.let { put("dropNote", it) }
                put("plaintext", JsonNull)
                put("state", TranscriptJson.state(me, frame.sender))
                floors?.let { put("floorsBefore", it) }
            }
    }

    /**
     * [frame] reaches [actor]'s pipeline after a message row with its id already exists: `InboundPipeline.decryptAndDeliver`'s
     * exists-gate never decrypts it, so no receive key is spent, and acknowledges it ([acksBack]: a DM to us gets
     * `acknowledge`'s sealed `CTL_RECEIPT`, the actor's own outbound seal, which the transcript does not list).
     */
    fun skip(
        actor: String,
        frame: TranscriptFrame,
        at: Long,
        note: String,
        acksBack: Boolean = true,
    ) {
        val floors = floorsBefore(actor, frame.sender.name)
        val me = party(actor)
        steps +=
            buildJsonObject {
                put("step", steps.size)
                put("actor", actor)
                put("op", "skip")
                put("peer", frame.sender.name)
                put("now", at)
                put("opens", frame.step)
                put("layer", "pipeline")
                put("expect", "SKIPPED_EXISTS")
                put("drop", JsonNull)
                put("acksBack", acksBack)
                frameFields(frame)
                put("state", TranscriptJson.state(me, frame.sender))
                put("note", note)
                floors?.let { put("floorsBefore", it) }
            }
    }

    /**
     * [actor] seals [count] v3 frames to [peer] at [at] that the file does not list: the chain advances and nothing
     * else moves (v3 draws nothing, and the AAD reaches only the ciphertext). A port replays it as [count] seals
     * of any content.
     */
    suspend fun advance(
        actor: String,
        peer: String,
        count: Int,
        at: Long,
    ) {
        val from = party(actor)
        val to = party(peer)
        val drawsBefore = from.draws.count
        val startN = from.store.session(to.nodeId)?.sendCount
        repeat(count) { i ->
            val id = frameIdFor("$name/${steps.size}/$i")
            val (plaintext, scheme) = MessageContent(body = "filler $i").sealBytes(v3 = true)
            check(scheme == EncEnvelope.VERSION_DM_V3)
            checkNotNull(
                from.sessions.sealDm(
                    to.nodeId,
                    to.identity.bundle.dhPublicKey(),
                    prekeyFor(actor, peer),
                    plaintext,
                    MessageCrypto.header(id, from.nodeId, at, to.nodeId),
                    at,
                    scheme,
                ),
            )
        }
        check(from.draws.count == drawsBefore) { "$name: an advance must not draw (it would start an epoch unlisted)" }
        steps +=
            buildJsonObject {
                put("step", steps.size)
                put("actor", actor)
                put("op", "advance")
                put("peer", peer)
                put("now", at)
                put("count", count)
                put("fromN", startN)
                put("scheme", "v3")
                put(
                    "note",
                    "seal $count v3 frames of any content and discard them: they advance the send chain from n $startN " +
                        "and move nothing else",
                )
                put("state", TranscriptJson.state(from, to))
            }
    }

    fun floorsBefore(
        actor: String,
        peer: String,
    ): JsonObject? = if (recordFloors) TranscriptJson.floors(party(actor), party(peer)) else null

    private fun chatTo(
        id: String,
        from: TranscriptParty,
        sentAt: Long,
        to: TranscriptParty,
        content: ChatContent,
    ) = RelayEnvelope(
        type = FrameType.CHAT,
        id = id,
        senderId = from.nodeId,
        sentAt = sentAt,
        recipientId = to.nodeId,
        payload = WireCodec.encodePayload(content),
    )

    private fun openStep(
        actor: String,
        peer: String,
        frame: TranscriptFrame,
        at: Long,
        layer: Layer,
        outcome: String,
        drop: DropReason?,
        dropNormative: Boolean = true,
        rest: JsonObjectBuilder.() -> Unit,
    ): JsonObject =
        buildJsonObject {
            put("step", steps.size)
            put("actor", actor)
            put("op", "open")
            put("peer", peer)
            put("now", at)
            put("opens", frame.step)
            put("mutation", frame.mutation)
            put("layer", layer.name.lowercase())
            put("expect", outcome)
            put("drop", drop?.name)
            put("dropNormative", dropNormative)
            put("resetTriggering", drop != null && drop in InboundPipeline.RESET_TRIGGERING_DROPS)
            frameFields(frame)
            rest()
        }

    fun json(): JsonObject {
        for (p in parties.values) p.draws.assertSpent()
        return buildJsonObject {
            put("name", name)
            put("note", note)
            for ((key, value) in caseFields) put(key, value)
            putJsonObject("parties") {
                for (p in parties.values) {
                    putJsonObject(p.name) {
                        put("identity", p.identityName)
                        put("nodeId", p.nodeId)
                        putJsonArray("holdsPrekeys") { p.holds.forEach { add(it.name) } }
                        putJsonObject("pins") {
                            for ((key, prekey) in pins) if (key.first == p.name) put(key.second, prekey.name)
                        }
                    }
                }
            }
            putJsonObject("draws") { for (p in parties.values) put(p.name, p.draws.json()) }
            put("steps", JsonArray(steps))
        }
    }

    fun JsonObjectBuilder.frameFields(frame: TranscriptFrame) {
        val enc = frame.enc
        put("frameId", frame.env.id)
        put("sentAt", frame.env.sentAt)
        // A group-form frame's AAD names the group (InboundPipeline.decryptAndDeliverGroup); every DM shape, the
        // negatives' group-addressed v3 included, names the recipient.
        val thread = if (enc.g != null) checkNotNull(frame.env.group).id else frame.env.recipientId.orEmpty()
        val aadText = "${frame.env.id}|${frame.env.senderId}|${frame.env.sentAt}|$thread"
        put("aadText", aadText)
        put("aad", aadText.encodeToByteArray().toHex())
        put("scheme", if (enc.v == EncEnvelope.VERSION_DM_V3) "v3" else "v${enc.v}")
        put("v", enc.v)
        enc.r?.let { put("header", TranscriptJson.header(it)) }
        enc.g?.let {
            putJsonObject("groupHeader") {
                put("se", it.se)
                put("n", it.n)
            }
        }
        put("nonce", enc.nonce.toHex())
        put("ct", enc.ct.toHex())
        put("encEnvelope", WireCodec.encodePayload(enc).toHex())
        frame.env.group?.let { put("groupId", it.id) }
        put("wire", frame.wire.toHex())
        if (!frame.signed) put("signed", false)
    }

    companion object {
        /** A frame id as `FrameId.new` mints one (16 bytes, base64url), drawn from a hash of [label]. */
        fun frameIdFor(label: String): String =
            FrameId.fromBytes(MessageDigest.getInstance("SHA-256").digest("ratchet-v1/$label".encodeToByteArray()).copyOf(16))

        fun outcomeName(outcome: OpenOutcome): String =
            when (outcome) {
                is OpenOutcome.Opened -> "OPENED"
                is OpenOutcome.Failed -> outcome.name
            }

        /** `InboundPipeline.onRatchetFailure`'s mapping; null for a frame that opened. */
        fun dropFor(outcome: OpenOutcome): DropReason? =
            when (outcome) {
                is OpenOutcome.Opened -> null
                OpenOutcome.Failed.NO_SESSION -> DropReason.RATCHET_NO_SESSION
                OpenOutcome.Failed.EPOCH_GONE -> DropReason.RATCHET_EPOCH_GONE
                OpenOutcome.Failed.DUPLICATE -> DropReason.RATCHET_DUPLICATE
                OpenOutcome.Failed.BAD_HEADER -> DropReason.RATCHET_BAD_HEADER
                OpenOutcome.Failed.AEAD_FAIL -> DropReason.RATCHET_AEAD_FAIL
                OpenOutcome.Failed.RESET_FLOORED -> DropReason.RATCHET_RESET_FLOORED
            }
    }
}
