package app.getknit.knit.mesh.protocol

import app.getknit.knit.data.message.Conversations
import app.getknit.knit.identity.DeviceTag
import app.getknit.knit.identity.NodeId
import app.getknit.knit.mesh.crypto.AesGcm
import app.getknit.knit.mesh.crypto.LinkProof
import app.getknit.knit.mesh.crypto.MessageContent
import app.getknit.knit.mesh.crypto.MessageContentV2
import app.getknit.knit.mesh.crypto.MessageCrypto
import app.getknit.knit.mesh.crypto.SafetyNumber
import app.getknit.knit.mesh.crypto.ratchet.GroupRatchetEngine
import app.getknit.knit.mesh.crypto.ratchet.GroupRatchetSessions
import app.getknit.knit.mesh.crypto.ratchet.RatchetCrypto
import app.getknit.knit.mesh.crypto.ratchet.RatchetEngine
import app.getknit.knit.mesh.crypto.sealBytes
import app.getknit.knit.mesh.link.LinkFraming
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Frames the iOS port emits (`vectors/ios-emitted-v1.json`, written by knit-ios): Carol's identity from fixed
 * keys, her signed profile and room post, a v1 DM she sealed to Bob from `keyed-v1.json`, and their safety
 * number. Each frame must verify with Tink, re-encode with kotlinx to the exact bytes Carol signed, and pin or
 * open the way an Android frame would. When this fails after knit-ios regenerated the file, the iOS encoder
 * and ours disagree about the wire, and that is the bug.
 *
 * The ratchet frames (companion change A10, knit-ios ADR 2026-10.5sk9) are what Carol's engine sends with the
 * ratchet on: her ratchet profile, a v3 session with Bob, and seeds and group-form frames for a group of the
 * three. The file's `ratchet.replay` is replayed reader by reader, each a fresh `RatchetSessions` holding A9's
 * prekey beside a fresh [GroupRatchetSessions], opened the way `InboundPipeline` opens a frame (peek, then commit,
 * seeds adopted inside the commit) and sealed the way `MeshManager` seals a DM. Nothing is sent back.
 */
class IosEmittedVectorTest {
    private val file: JsonObject by lazy { VectorFiles.read("ios-emitted-v1.json") }
    private val keyed: JsonObject by lazy { VectorFiles.read("keyed-v1.json") }
    private val carol by lazy { VectorParty.identity(file, "carol") }
    private val bob by lazy { VectorParty.identity(keyed, "bob") }
    private val alice by lazy { VectorParty.identity(keyed, "alice") }
    private val identities by lazy { mapOf("alice" to alice, "bob" to bob, "carol" to carol) }

    /** Node ids back to the names the file's `sealed.content` uses for them. */
    private val names by lazy { identities.entries.associate { (name, party) -> party.nodeId to name } }

    private val ratchet: JsonObject by lazy { file.getValue("ratchet").jsonObject }

    private val prekeys: Map<String, VectorPrekey> by lazy {
        ratchet.getValue("prekeys").jsonObject.mapValues { (name, entry) ->
            val e = entry.jsonObject
            VectorPrekey(name, e.getValue("id").jsonPrimitive.int, RatchetCrypto.KeyPair(e.hex("priv"), e.hex("pub")))
        }
    }

    /** Every frame with a `sealed` block: the ratchet frames, which only a replay opens. */
    private val sealedFrames: List<String> by lazy {
        file
            .getValue("frames")
            .jsonObject
            .filterValues { "sealed" in it.jsonObject }
            .keys
            .toList()
    }

    private fun frame(name: String): JsonObject =
        file
            .getValue("frames")
            .jsonObject
            .getValue(name)
            .jsonObject

    private fun field(
        entry: JsonObject,
        key: String,
    ): String = entry.getValue(key).jsonPrimitive.content

    /** The frame's relay envelope, once its signature verifies and both layers re-encode to their own bytes. */
    private fun relayOf(name: String): RelayEnvelope {
        val bytes = field(frame(name), "wire").fromHex()
        val wire = checkNotNull(WireCodec.decodeWire(bytes)) { "$name does not decode" }
        assertArrayEquals("$name's WireEnvelope re-encodes differently", bytes, WireCodec.encodeWire(wire))
        assertTrue("$name's signature does not verify", MessageCrypto.verify(carol.bundle, wire.sig, wire.signed))
        val relay = checkNotNull(WireCodec.decodeEnvelope(wire.signed)) { "$name's RelayEnvelope does not decode" }
        assertArrayEquals("$name's RelayEnvelope re-encodes differently", wire.signed, WireCodec.encodeEnvelope(relay))
        assertEquals(carol.nodeId, relay.senderId)
        return relay
    }

    @Test
    fun `carol's keys derive the bundle and node id the iOS port wrote`() {
        val entry =
            file
                .getValue("identities")
                .jsonObject
                .getValue("carol")
                .jsonObject
        assertEquals(field(entry, "bundle"), carol.bundle.encoded)
        assertEquals(field(entry, "nodeId"), carol.nodeId)
    }

    @Test
    fun `carol and bob have the safety number the iOS port computed`() {
        val expected =
            file
                .getValue("safetyNumber")
                .jsonObject
                .getValue("number")
                .jsonPrimitive.content
        assertEquals(expected, SafetyNumber.compute(carol.nodeId, carol.bundle.encoded, bob.nodeId, bob.bundle.encoded))
    }

    @Test
    fun `carol's profile pins through the ordinary door`() {
        val relay = relayOf("carolProfile")
        assertEquals(FrameType.PROFILE, relay.type)
        assertEquals("profile-${carol.nodeId}-${relay.sentAt}", relay.id)
        val profile = checkNotNull(WireCodec.decodePayload<ProfileContent>(relay.payload))
        assertArrayEquals(relay.payload, WireCodec.encodePayload(profile))
        assertEquals(carol.bundle.encoded, profile.pubKey)
        assertEquals(relay.senderId, NodeId.fromPublicKeyBundle(checkNotNull(profile.pubKey)))
        assertEquals(DeviceTag.derive("vector-device-carol"), profile.deviceTag)
        assertEquals(Protocol.VERSION, profile.protoVersion)
        val caps = checkNotNull(profile.capabilities)
        assertEquals(
            Protocol.CAP_E2E or Protocol.CAP_GROUPS or Protocol.CAP_REACTIONS or Protocol.CAP_STORE_FORWARD,
            caps,
        )
        assertEquals("no ratchet: Android must keep sealing v1 to her", 0L, caps and Protocol.CAP_RATCHET)
    }

    @Test
    fun `carol's room post carries its body`() {
        val relay = relayOf("carolRoomPost")
        assertNull(relay.recipientId)
        assertEquals(field(frame("carolRoomPost"), "body"), WireCodec.decodePayload<ChatContent>(relay.payload)?.body)
    }

    @Test
    fun `bob opens carol's DM`() {
        val relay = relayOf("carolDmToBob")
        assertEquals(bob.nodeId, relay.recipientId)
        val chat = checkNotNull(WireCodec.decodePayload<ChatContent>(relay.payload))
        assertEquals("a DM carries no cleartext body", "", chat.body)
        val header = MessageCrypto.header(relay.id, relay.senderId, relay.sentAt, bob.nodeId)
        val opened = bob.crypto.open(checkNotNull(chat.enc), header, bob.nodeId)
        assertEquals(field(frame("carolDmToBob"), "body"), opened?.body)
    }

    // ---- the ratchet frames (A10) -----------------------------------------------------------------------

    @Test
    fun `carol's ratchet profile pins her prekey and the capabilities it claims`() {
        val entry = frame("carolRatchetProfile")
        val relay = relayOf("carolRatchetProfile")
        assertEquals(FrameType.PROFILE, relay.type)
        assertEquals("profile-${carol.nodeId}-${relay.sentAt}", relay.id)
        val profile = checkNotNull(WireCodec.decodePayload<ProfileContent>(relay.payload))
        assertArrayEquals(relay.payload, WireCodec.encodePayload(profile))
        assertEquals(carol.bundle.encoded, profile.pubKey)
        assertEquals(entry.long("version"), profile.version)
        val caps = checkNotNull(profile.capabilities)
        assertEquals(entry.long("capabilities"), caps)
        assertEquals(
            Protocol.CAP_E2E or Protocol.CAP_GROUPS or Protocol.CAP_REACTIONS or Protocol.CAP_STORE_FORWARD or
                Protocol.CAP_RATCHET or Protocol.CAP_INLINE_ACK or Protocol.CAP_CRYPTO_V3,
            caps,
        )
        val prekey = checkNotNull(profile.prekey) { "the ratchet profile carries no prekey" }
        val spk = prekeys.getValue(field(entry, "prekey"))
        assertEquals(spk.id, prekey.id)
        assertArrayEquals(spk.keys.pub, prekey.pub)
        assertTrue(
            "the prekey's signature does not verify under carol's bundle",
            MessageCrypto.verify(carol.bundle, prekey.sig, RatchetCrypto.spkSigningBytes(prekey.id, prekey.pub)),
        )
        val plain = relayOf("carolProfile")
        assertTrue("it is published after her plain profile", relay.sentAt > plain.sentAt)
    }

    @Test
    fun `the readers hold A9's prekeys`() {
        val a9 =
            VectorFiles
                .read("ratchet-v1.json")
                .getValue("profiles")
                .jsonObject
                .getValue("prekeys")
                .jsonArray
                .associate { it.jsonObject.let { e -> field(e, "name") to e } }
        assertEquals(setOf("aliceSpk1", "bobSpk1", "carolSpk1"), prekeys.keys)
        for ((name, spk) in prekeys) {
            val entry =
                ratchet
                    .getValue("prekeys")
                    .jsonObject
                    .getValue(name)
                    .jsonObject
            val theirs = a9.getValue(name)
            assertEquals(name, theirs.int("id"), spk.id)
            assertArrayEquals(name, theirs.hex("priv"), spk.keys.priv)
            assertArrayEquals(name, theirs.hex("pub"), spk.keys.pub)
            assertArrayEquals(name, fixtureBytes(RatchetCrypto.KEY_BYTES, entry.int("seed")), spk.keys.priv)
            assertArrayEquals(name, RatchetCrypto.publicFromPrivate(spk.keys.priv), spk.keys.pub)
        }
    }

    @Test
    fun `carol's ratchet frames verify, re-encode and carry the headers they state`() {
        assertEquals(10, sealedFrames.size)
        for (name in sealedFrames) {
            val entry = frame(name)
            val sealed = entry.getValue("sealed").jsonObject
            val relay = relayOf(name)
            assertEquals(name, FrameType.CHAT, relay.type)
            val chat = checkNotNull(WireCodec.decodePayload<ChatContent>(relay.payload)) { "$name's ChatContent does not decode" }
            assertArrayEquals("$name's EncEnvelope re-encodes differently", relay.payload, WireCodec.encodePayload(chat))
            assertEquals("$name carries no cleartext body", "", chat.body)
            val enc = checkNotNull(chat.enc) { "$name carries no EncEnvelope" }
            assertEquals("$name's v", sealed.int("v"), enc.v)
            // InboundPipeline.nonceShapeValid: v3 derives its nonce, v2 carries 12 bytes.
            assertEquals("$name's nonce", if (enc.v == EncEnvelope.VERSION_DM_V3) 0 else AesGcm.IV_BYTES, enc.nonce.size)
            when (field(sealed, "form")) {
                "dm" -> {
                    assertTrue("$name: v2 or v3", EncEnvelope.isDmRatchetVersion(enc.v))
                    assertNull("$name: a DM has no group header", enc.g)
                    assertNull("$name: a DM names no group", relay.group)
                    assertEquals("$name's recipient", identities.getValue(field(entry, "recipient")).nodeId, relay.recipientId)
                    val r = checkNotNull(enc.r) { "$name carries no ratchet header" }
                    assertEquals("$name's se", sealed.int("se"), r.se)
                    assertEquals("$name's n", sealed.int("n"), r.n)
                    assertEquals("$name's pe", sealed.int("pe"), r.pe)
                    assertEquals(
                        "$name's init pkid",
                        sealed["initPrekey"]?.jsonPrimitive?.content?.let { prekeys.getValue(it).id },
                        r.init?.pkid,
                    )
                }

                "group" -> {
                    assertEquals("$name: the group form is v2", EncEnvelope.VERSION_RATCHET, enc.v)
                    assertNull("$name: the group form has no DM header", enc.r)
                    assertNull("$name: a group frame names no recipient", relay.recipientId)
                    val g = checkNotNull(enc.g) { "$name carries no group header" }
                    assertEquals("$name's se", sealed.int("se"), g.se)
                    assertEquals("$name's n", sealed.int("n"), g.n)
                    val group = checkNotNull(relay.group) { "$name names no group" }
                    assertEquals("$name's group", field(entry, "groupId"), group.id)
                    assertRoster(name, group, entry.getValue("members").jsonArray.map { it.jsonPrimitive.content })
                }

                else -> {
                    error("$name: unknown form ${field(sealed, "form")}")
                }
            }
        }
        val replayed =
            ratchet
                .getValue("replay")
                .jsonObject
                .values
                .flatMap { it.jsonArray }
                .map { it.jsonObject }
                .filter { field(it, "op") != "seal" }
                .map { field(it, "frame") }
        assertEquals("every ratchet frame is opened by some reader", sealedFrames.toSet(), replayed.toSet())
    }

    @Test
    fun `bob's replay opens what carol sent him and seals his reply as listed`() = replay("bob", expectAdoptions = 1)

    @Test
    fun `alice's replay adopts carol's seed and opens the group form`() = replay("alice", expectAdoptions = 1)

    /**
     * [reader]'s `ratchet.replay` steps in order, each at its frame's `sentAt`: `open` peeks then commits a DM-form
     * frame (adopting a `CTL_GROUP_KEY`'s seeds inside the commit, as `InboundPipeline.adoptGroupSeeds` runs), `seal`
     * seals the reader's own frame on its listed draws, and `openGroup` opens a group-form frame.
     */
    private fun replay(
        reader: String,
        expectAdoptions: Int,
    ) = runBlocking {
        val steps =
            ratchet
                .getValue("replay")
                .jsonObject
                .getValue(reader)
                .jsonArray
                .map { it.jsonObject }
        val seals = ratchet.getValue("seals").jsonObject
        val plan =
            steps
                .filter { field(it, "op") == "seal" }
                .flatMap {
                    seals
                        .getValue(field(it, "frame"))
                        .jsonObject
                        .getValue("draws")
                        .jsonArray
                }.map { drawOf(it.jsonObject) }
        val held =
            prekeys.values.filter {
                field(
                    ratchet
                        .getValue("prekeys")
                        .jsonObject
                        .getValue(it.name)
                        .jsonObject,
                    "owner",
                ) == reader
            }
        val me = TranscriptParty(reader, identities.getValue(reader), held, plan)
        var adoptions = 0
        for ((index, step) in steps.withIndex()) {
            val name = field(step, "frame")
            val where = "$reader step $index ($name)"
            when (field(step, "op")) {
                "open" -> if (openDm(me, name, where)) adoptions++
                "openGroup" -> openGroup(me, name, where)
                "seal" -> seal(me, seals.getValue(name).jsonObject, where)
                else -> error("$where: unknown op ${field(step, "op")}")
            }
        }
        me.draws.assertSpent()
        assertEquals("$reader's adoptions", expectAdoptions, adoptions)
    }

    /** Opens DM-form [name] as [me] the way `InboundPipeline.decryptAndDeliverV2` does; true if it adopted seeds. */
    private suspend fun openDm(
        me: TranscriptParty,
        name: String,
        where: String,
    ): Boolean {
        val relay = relayOf(name)
        val sealed = frame(name).getValue("sealed").jsonObject
        assertEquals("$where: addressed to the reader", me.nodeId, relay.recipientId)
        val enc = checkNotNull(WireCodec.decodePayload<ChatContent>(relay.payload)?.enc)
        val header = checkNotNull(enc.r)
        val v3 = enc.v == EncEnvelope.VERSION_DM_V3
        val nonce = enc.nonce.takeIf { !v3 }
        val aad = MessageCrypto.header(relay.id, relay.senderId, relay.sentAt, me.nodeId)
        val now = relay.sentAt
        val ik = carol.bundle.dhPublicKey()
        val peek = me.sessions.peekOpen(me.nodeId, carol.nodeId, ik, header, nonce, enc.ct, aad, now)
        check(peek is RatchetEngine.OpenOutcome.Opened) { "$where: the peek gave ${Transcript.outcomeName(peek)}" }
        assertEquals("$where: the plaintext", field(sealed, "plaintext"), peek.plaintext.toHex())
        assertEquals("$where: the schema", field(sealed, "plaintextSchema"), if (v3) "MessageContentV2" else "MessageContent")
        val content = checkNotNull(if (v3) MessageContentV2.decode(peek.plaintext) else MessageContent.decode(peek.plaintext))
        assertEquals("$where: the content", sealed.getValue("content").jsonObject, contentJson(content, where))
        val gk = content.gk?.takeIf { content.ctl == MessageContent.CTL_GROUP_KEY }
        if (gk != null) {
            val group = checkNotNull(gk.group) { "$where: the seed carries no roster" }
            assertEquals("$where: the seed's group", gk.groupId, group.id)
            assertRoster(where, group, listOf("alice", "bob", "carol"))
        }
        var adopted: GroupRatchetSessions.AdoptResult? = null
        val committed =
            me.sessions.commitOpen(me.nodeId, carol.nodeId, ik, header, nonce, enc.ct, aad, now) {
                if (gk != null) adopted = me.groupSessions.adoptSeeds(gk.groupId, carol.nodeId, gk.keys, now)
            }
        assertTrue("$where: commits what it peeked", committed)
        val result = adopted ?: return false
        assertEquals("$where: ackEpoch", 1, result.ackEpoch)
        assertEquals("$where: freshChains", 1, result.freshChains)
        return true
    }

    /** Opens group-form [name] as [me] the way `InboundPipeline.decryptAndDeliverGroup` does. */
    private suspend fun openGroup(
        me: TranscriptParty,
        name: String,
        where: String,
    ) {
        val relay = relayOf(name)
        val sealed = frame(name).getValue("sealed").jsonObject
        val enc = checkNotNull(WireCodec.decodePayload<ChatContent>(relay.payload)?.enc)
        val g = checkNotNull(enc.g)
        val groupId = checkNotNull(relay.group).id
        val aad = MessageCrypto.header(relay.id, relay.senderId, relay.sentAt, groupId)
        val now = relay.sentAt
        val peek = me.groupSessions.peekOpen(groupId, carol.nodeId, g, enc.nonce, enc.ct, aad, now)
        check(peek is GroupRatchetEngine.OpenOutcome.Opened) { "$where: the peek gave $peek" }
        assertEquals("$where: the plaintext", field(sealed, "plaintext"), peek.plaintext.toHex())
        assertEquals("$where: the schema", "MessageContent", field(sealed, "plaintextSchema"))
        val content = checkNotNull(MessageContent.decode(peek.plaintext))
        assertEquals("$where: the content", sealed.getValue("content").jsonObject, contentJson(content, where))
        assertTrue("$where: commits what it peeked", me.groupSessions.commitOpen(groupId, carol.nodeId, g, enc.nonce, enc.ct, aad, now) {})
    }

    /**
     * Seals [entry] as [me] to Carol the way `MeshManager.sealEnvelopeFor` seals a DM — the row's content through
     * `sealBytes`, `RatchetSessions.sealDm` against the prekey her ratchet profile pinned, a `chat` frame signed by
     * the sender — and checks the EncEnvelope and the signed frame against the listed wire.
     */
    private suspend fun seal(
        me: TranscriptParty,
        entry: JsonObject,
        where: String,
    ) {
        assertEquals("$where: the reader's own frame", me.name, field(entry, "sender"))
        val to = identities.getValue(field(entry, "recipient"))
        val id = field(entry, "id")
        val sentAt = entry.long("sentAt")
        val (plaintext, scheme) = MessageContent(body = field(entry, "body")).sealBytes(v3 = entry.int("v") == EncEnvelope.VERSION_DM_V3)
        assertEquals("$where: the scheme", entry.int("v"), scheme)
        assertEquals("$where: the plaintext", field(entry, "plaintext"), plaintext.toHex())
        val spk = prekeys.getValue(field(frame("carolRatchetProfile"), "prekey"))
        val enc =
            checkNotNull(
                me.sessions.sealDm(
                    peerId = to.nodeId,
                    peerIkPub = to.bundle.dhPublicKey(),
                    peerSpk = RatchetEngine.PeerPrekey(spk.id, spk.keys.pub),
                    plaintext = plaintext,
                    aad = MessageCrypto.header(id, me.nodeId, sentAt, to.nodeId),
                    now = sentAt,
                    scheme = scheme,
                ),
            ) { "$where: the seal failed" }
        val listed = entry.hex("wire")
        val wire = checkNotNull(WireCodec.decodeWire(listed))
        assertTrue("$where: the listed frame verifies", MessageCrypto.verify(me.identity.bundle, wire.sig, wire.signed))
        val listedEnc = checkNotNull(WireCodec.decodeEnvelope(wire.signed)?.let { WireCodec.decodePayload<ChatContent>(it.payload) }?.enc)
        assertEquals("$where: the EncEnvelope", WireCodec.encodePayload(listedEnc).toHex(), WireCodec.encodePayload(enc).toHex())
        val frame =
            RelayEnvelope(
                type = FrameType.CHAT,
                id = id,
                senderId = me.nodeId,
                sentAt = sentAt,
                recipientId = to.nodeId,
                payload = WireCodec.encodePayload(ChatContent(enc = enc)),
            )
        assertEquals("$where: the signed frame", listed.toHex(), me.identity.signedWire(frame).toHex())
    }

    /** The roster rules `InboundPipeline.vetRoster` applies on first sight, against the members the file names. */
    private fun assertRoster(
        where: String,
        group: GroupInfo,
        members: List<String>,
    ) {
        val founding = (group.members + group.departed.orEmpty()).distinct()
        assertEquals("$where: the founding roster derives the id", group.id, Conversations.groupIdFor(founding))
        assertEquals("$where: the members", members.map { identities.getValue(it).nodeId }.toSet(), founding.toSet())
        assertEquals("$where: createdBy", carol.nodeId, group.createdBy)
    }

    /**
     * [c] laid out as the file's `sealed.content` lays it out: the fields set, node ids by name, a seed in hex. Fails
     * on a field the file cannot describe, so nothing decoded passes unchecked.
     */
    private fun contentJson(
        c: MessageContent,
        where: String,
    ): JsonObject {
        assertEquals("$where: content v", MessageContent.VERSION, c.v)
        assertEquals(
            "$where: a field the vector does not describe",
            MessageContent(body = c.body, ctl = c.ctl, gk = c.gk, ack = c.ack, rp = c.rp, pr = c.pr, acks = c.acks),
            c,
        )
        return buildJsonObject {
            put("body", c.body)
            c.ctl?.let { put("ctl", it) }
            c.ack?.let { put("ack", it) }
            c.acks?.let { acks -> putJsonArray("acks") { acks.forEach { add(it) } } }
            c.rp?.let { rp ->
                putJsonObject("rp") {
                    put("messageId", rp.messageId)
                    rp.emoji?.let { put("emoji", it) }
                }
            }
            c.pr?.let { put("pr", profileJson(it)) }
            c.gk?.let { put("gk", groupKeyJson(it)) }
        }
    }

    private fun profileJson(pr: ProfilePayload): JsonObject =
        buildJsonObject {
            put("name", pr.name)
            put("status", pr.status)
            pr.avatarHash?.let { put("avatarHash", it) }
            if (pr.version != 0L) put("version", pr.version)
            if (pr.openToChat) put("openToChat", true)
            pr.loraNode?.let { put("loraNode", it) }
            pr.loraKey?.let { put("loraKey", it) }
        }

    private fun groupKeyJson(gk: GroupKeyPayload): JsonObject =
        buildJsonObject {
            put("groupId", gk.groupId)
            if (gk.keys.isNotEmpty()) {
                putJsonArray("keys") {
                    for (seed in gk.keys) {
                        add(
                            buildJsonObject {
                                put("epoch", seed.epoch)
                                put("mintedAt", seed.mintedAt)
                                put("seed", seed.seed.toHex())
                            },
                        )
                    }
                }
            }
            gk.ackEpoch?.let { put("ackEpoch", it) }
            gk.gr?.let { put("gr", "a group root the vector does not describe") }
            gk.group?.let { put("group", groupJson(it)) }
        }

    private fun groupJson(group: GroupInfo): JsonObject =
        buildJsonObject {
            fun name(nodeId: String) = names[nodeId] ?: nodeId
            put("id", group.id)
            group.name?.let { put("name", it) }
            putJsonArray("members") { group.members.forEach { add(name(it)) } }
            put("createdBy", name(group.createdBy))
            group.photoHash?.let { put("photoHash", it) }
            group.photoUpdatedAt?.let { put("photoUpdatedAt", it) }
            group.departed?.let { departed -> putJsonArray("departed") { departed.forEach { add(name(it)) } } }
        }

    /**
     * Carol's link proofs as the iPhone signs them (ADR 2026-11.264w): each HELLO record is the frozen framing of its
     * payload at caps 40009 with its nonce, the transcript is the one Android builds from those bytes, and Carol's PROOF
     * carries her raw keys and verifies for the role she played — and under the other role it fails. CryptoKit
     * randomizes Ed25519, so the signature is verified, never rebuilt.
     */
    @Test
    fun `carol's link proofs verify for her role and fail under the other`() {
        val link = file.getValue("link").jsonObject
        val parties = mapOf("alice" to alice, "carol" to carol)
        for ((case, role) in listOf("carolDialsAlice" to LinkProof.Role.DIALER, "aliceDialsCarol" to LinkProof.Role.RESPONDER)) {
            val entry = link.getValue(case).jsonObject
            assertEquals(case, role.byte.toInt(), entry.int("role"))
            assertEquals(case, "carol", field(entry, "signer"))
            val hellos =
                listOf("helloDialer" to field(entry, "dialer"), "helloResponder" to field(entry, "responder")).map { (key, name) ->
                    val hello = entry.getValue(key).jsonObject
                    val payload = field(hello, "payload").encodeToByteArray()
                    assertEquals("$case $key record", LinkFraming.encode(LinkFraming.Type.HELLO, payload).toHex(), field(hello, "record"))
                    val wire = checkNotNull(Protocol.parse(payload.decodeToString())) { "$case $key" }
                    assertEquals("$case $key id", parties.getValue(name).nodeId, wire.nodeId)
                    assertEquals("$case $key caps", 0x40009L, wire.capabilities)
                    val first = if (name == "carol") 0x20 else 0x30
                    assertEquals("$case $key nonce", ByteArray(Protocol.HELLO_NONCE_BYTES) { (first + it).toByte() }.toHex(), wire.nonceHex)
                    payload
                }
            val (helloD, helloR) = hellos
            assertEquals("$case transcript", LinkProof.transcript(role, helloD, helloR).toHex(), field(entry, "transcript"))
            val record = checkNotNull(LinkFraming.read(entry.hex("proof").inputStream())) { case }
            assertEquals(case, LinkFraming.Type.PROOF, record.type)
            assertArrayEquals(case, carol.bundle.sigPublicKey() + carol.bundle.dhPublicKey(), record.payload.copyOf(64))
            assertNull("$case proof does not verify", LinkProof.verify(record.payload, carol.nodeId, role, helloD, helloR))
            val other = if (role == LinkProof.Role.DIALER) LinkProof.Role.RESPONDER else LinkProof.Role.DIALER
            assertEquals(
                "$case proof must fail under the other role",
                LinkProof.Failure.SIGNATURE,
                LinkProof.verify(record.payload, carol.nodeId, other, helloD, helloR),
            )
        }
    }

    private fun drawOf(entry: JsonObject): Draw =
        when (val kind = field(entry, "kind")) {
            "keyPair" -> Draw.Key(entry.int("seed"))

            // Both engines draw an IV from the one source, as production wires them.
            "iv", "groupIv" -> Draw.Iv(entry.int("seed"))

            "groupSeed" -> Draw.GroupSeed(entry.int("seed"))

            else -> error("unknown draw kind $kind")
        }

    private fun JsonObject.int(key: String): Int = getValue(key).jsonPrimitive.int

    private fun JsonObject.long(key: String): Long = getValue(key).jsonPrimitive.long

    private fun JsonObject.hex(key: String): ByteArray = getValue(key).jsonPrimitive.content.fromHex()
}
