package app.getknit.knit

import app.getknit.knit.identity.NodeId
import app.getknit.knit.mesh.DmAckCoalescer
import app.getknit.knit.mesh.INLINE_ACK_BYTES
import app.getknit.knit.mesh.MAX_INLINE_ACKS
import app.getknit.knit.mesh.crypto.MessageContent
import app.getknit.knit.mesh.crypto.MessageCrypto
import app.getknit.knit.mesh.crypto.PublicKeyBundle
import app.getknit.knit.mesh.crypto.TinkInit
import app.getknit.knit.mesh.crypto.ratchet.RatchetCrypto
import app.getknit.knit.mesh.crypto.ratchet.RatchetEngine
import app.getknit.knit.mesh.crypto.sealBytes
import app.getknit.knit.mesh.link.FastFrameCodec
import app.getknit.knit.mesh.lora.LoraFrameCodec
import app.getknit.knit.mesh.lora.LoraMeshTransport
import app.getknit.knit.mesh.lora.LoraSizeHint
import app.getknit.knit.mesh.lora.MeshtasticProto
import app.getknit.knit.mesh.protocol.ChatContent
import app.getknit.knit.mesh.protocol.EncEnvelope
import app.getknit.knit.mesh.protocol.FrameId
import app.getknit.knit.mesh.protocol.FrameType
import app.getknit.knit.mesh.protocol.PrekeyInfo
import app.getknit.knit.mesh.protocol.ProfileContent
import app.getknit.knit.mesh.protocol.ProfilePayload
import app.getknit.knit.mesh.protocol.Protocol
import app.getknit.knit.mesh.protocol.RatchetHeader
import app.getknit.knit.mesh.protocol.RatchetInit
import app.getknit.knit.mesh.protocol.ReactionContent
import app.getknit.knit.mesh.protocol.ReactionPayload
import app.getknit.knit.mesh.protocol.ReceiptContent
import app.getknit.knit.mesh.protocol.RelayEnvelope
import app.getknit.knit.mesh.protocol.ReplyRef
import app.getknit.knit.mesh.protocol.TypingContent
import app.getknit.knit.mesh.protocol.WireCodec
import app.getknit.knit.mesh.protocol.WireEnvelope
import app.getknit.knit.mesh.wifiaware.WifiAwareTransport
import app.getknit.knit.ui.chat.REPLY_SNIPPET_MAX
import com.google.crypto.tink.InsecureSecretKeyAccess
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.hybrid.HpkePrivateKey
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Executable size budget for the Wi-Fi Aware coordination-plane fast path ([WifiAwareTransport]'s
 * `fastFanout`/`fastSend`, one `sendMessage` of ≤ [WifiAwareTransport.COORD_MSG_MAX] bytes including
 * the 1-byte tag). Every representative frame is built with **real** crypto (Tink Ed25519 signatures,
 * a real [RatchetEngine] session for the v2 sealed forms) and real-length ids (22-char [FrameId],
 * 26-char [NodeId]), so the pinned ≤/> expectations are the measured truth about what rides the fast
 * plane and what silently no-ops — not hand-derived CBOR arithmetic. Prints a size table so codec/dict
 * work (`mesh/link/FastFrameCodec`) can be tuned against the same fixtures.
 */
class CoordinationPlaneSizeBudgetTest {
    /** A device identity: its cipher (private keys), its public bundle, and the nodeId it derives to. */
    private class Party(
        val crypto: MessageCrypto,
        val bundle: PublicKeyBundle,
        /** The raw X25519 identity scalar (the extraction IdentityKeyStore.dhIdentityPrivate performs). */
        val dhPriv: ByteArray,
    ) {
        val nodeId: String = NodeId.fromPublicKeyBundle(bundle.encoded)

        /** Wraps + signs [env] with this party's key (mirrors MeshManager.sign). */
        fun sign(
            env: RelayEnvelope,
            relay: Boolean = true,
        ): WireEnvelope {
            val signed = WireCodec.encodeEnvelope(env)
            return WireEnvelope(relay = relay, sig = crypto.signRaw(signed), signed = signed)
        }
    }

    private fun party(): Party {
        TinkInit.ensure()
        val hybrid = KeysetHandle.generateNew(KeyTemplates.get(HYBRID_TEMPLATE))
        val sig = KeysetHandle.generateNew(KeyTemplates.get(SIG_TEMPLATE))
        val dhPriv =
            (hybrid.primary.key as HpkePrivateKey).privateKeyBytes.toByteArray(InsecureSecretKeyAccess.get())
        return Party(MessageCrypto(hybrid, sig), PublicKeyBundle.fromPrivate(hybrid, sig), dhPriv)
    }

    /**
     * Author-side v2 epoch ratchet against [to] (which published signed prekey [toSpk]): real engine
     * state driving real sealed frames, distilled from InboundPipelineTest's V2Author. The first [dm]
     * carries the X3DH [RatchetInit]; later ones are the steady-state form — both budgets matter.
     */
    private class V2Sealer(
        val party: Party,
        private val to: Party,
        private val toSpk: RatchetCrypto.KeyPair,
    ) {
        private val engine = RatchetEngine()
        private var session: RatchetEngine.SessionState =
            engine
                .initiate(
                    peerId = to.nodeId,
                    ownIkPriv = party.dhPriv,
                    peerIkPub = to.bundle.dhPublicKey(),
                    peerSpk = RatchetEngine.PeerPrekey(id = SPK_ID, pub = toSpk.pub),
                    now = SESSION_AT,
                ).session

        /** Marks the session confirmed (as the peer's first reply would), so later frames drop the init. */
        fun confirm() {
            session = session.copy(confirmed = true)
        }

        fun dm(
            id: String,
            body: String,
            ctl: Int? = null,
            ack: String? = null,
            rp: ReactionPayload? = null,
            acks: List<String>? = null,
            attachmentHash: String? = null,
            attachmentKey: String? = null,
            replyTo: ReplyRef? = null,
            pr: ProfilePayload? = null,
            /** Seal crypto scheme v3 — derived nonce, compact plaintext (ADR 059) — instead of v2. */
            v3: Boolean = false,
        ): RelayEnvelope {
            val aad = MessageCrypto.header(id, party.nodeId, SENT_AT, to.nodeId)
            val (plain, scheme) =
                MessageContent(
                    body = body,
                    attachmentHash = attachmentHash,
                    attachmentMime = attachmentHash?.let { "image/jpeg" },
                    attachmentKey = attachmentKey,
                    ctl = ctl,
                    ack = ack,
                    rp = rp,
                    acks = acks,
                    replyTo = replyTo,
                    pr = pr,
                ).sealBytes(v3)
            check(!v3 || scheme == EncEnvelope.VERSION_DM_V3) { "fixture asked for v3 but the content has no compact form" }
            val sealed = checkNotNull(engine.seal(session, plain, aad, toSpk.pub, now = SESSION_AT, v3 = v3))
            session = sealed.session
            val h = sealed.header
            return RelayEnvelope(
                type = FrameType.CHAT,
                id = id,
                senderId = party.nodeId,
                sentAt = SENT_AT,
                recipientId = to.nodeId,
                payload =
                    WireCodec.encodePayload(
                        ChatContent(
                            attachmentHash = attachmentHash,
                            enc =
                                EncEnvelope(
                                    v = scheme,
                                    nonce = sealed.nonce ?: ByteArray(0),
                                    ct = sealed.ct,
                                    keys = emptyList(),
                                    r =
                                        RatchetHeader(
                                            se = h.se,
                                            ek = h.ek,
                                            pe = h.pe,
                                            n = h.n,
                                            init = h.init?.let { RatchetInit(eph = it.eph, pkid = it.pkid, at = it.at) },
                                            flags = h.flags,
                                        ),
                                ),
                        ),
                    ),
            )
        }
    }

    /** The on-air message size for [wire]: the encoded envelope plus the transport's 1-byte tag. */
    private fun legacySize(wire: WireEnvelope): Int = WireCodec.encodeWire(wire).size + 1

    /** Legacy + compact budgets for [wire]: prints the row, sanity-checks the crypto, returns the sizes. */
    private fun report(
        label: String,
        wire: WireEnvelope,
        author: Party,
    ): Sizes {
        val legacy = legacySize(wire)
        val compact = checkNotNull(FastFrameCodec.encodeCompact(wire)) { "$label must be compact-encodable" }
        val parts =
            when {
                compact.size <= WifiAwareTransport.COORD_MSG_MAX -> {
                    1
                }

                else -> {
                    checkNotNull(
                        FastFrameCodec.fragment(compact, WifiAwareTransport.COORD_MSG_MAX, fragId = 1),
                    ) { "$label must fit ${FastFrameCodec.MAX_PARTS} parts" }.size
                }
            }
        val deflated = if ((compact[1].toInt() and 0x02) != 0) "deflated" else "stored"
        val best = checkNotNull(FastFrameCodec.encodeBest(wire, transcode = true)) { "$label must encode" }
        val transcodedParts =
            if (best.frame.size <= WifiAwareTransport.COORD_MSG_MAX) {
                1
            } else {
                checkNotNull(
                    FastFrameCodec.fragment(best.frame, WifiAwareTransport.COORD_MSG_MAX, fragId = 1),
                ) { "$label transcoded fit" }.size
            }
        val form = if (best.transcoded) "0x05" else "0x03"
        val lora = transcodedLoraParts(wire)
        val esp32 = transcodedLoraParts(wire, esp32PayloadCap)
        println(
            "size-budget: $label legacy=${legacy}B compact=${compact.size}B ($deflated) parts=$parts " +
                "transcoded=${best.frame.size}B ($form) parts=$transcodedParts lora@${MeshtasticProto.MAX_PAYLOAD}=$lora " +
                "lora@$esp32PayloadCap=$esp32 (cap ${WifiAwareTransport.COORD_MSG_MAX})",
        )
        assertEquals("raw Ed25519 signature", 64, wire.sig.size)
        assertTrue("$label verifies", MessageCrypto.verify(author.bundle, wire.sig, wire.signed))
        val decoded = checkNotNull(FastFrameCodec.decodeCompact(compact)) { "$label compact round-trip" }
        assertTrue("$label signature survives the compact round-trip", MessageCrypto.verify(author.bundle, decoded.sig, decoded.signed))
        assertTrue("compact never expands past legacy", compact.size < legacy)
        assertFalse("$label reproduces through the transcoder", best.transcodeRefused)
        val rebuilt = checkNotNull(FastFrameCodec.decodeCompact(best.frame)) { "$label transcoded round-trip" }
        val rebuiltVerifies = MessageCrypto.verify(author.bundle, rebuilt.sig, rebuilt.signed)
        assertTrue("$label signature survives the transcoded round-trip", rebuiltVerifies)
        assertTrue("the transcoded pick never exceeds compact", best.frame.size <= compact.size)
        return Sizes(legacy, compact.size, parts, best.frame.size, transcodedParts)
    }

    private class Sizes(
        val legacy: Int,
        val compact: Int,
        val parts: Int,
        val transcoded: Int,
        val transcodedParts: Int,
    )

    /** Part count for [wire] on the LoRa hop in the transcoded form (ADR 060) at [cap], or null when nothing fits ≤ 3. */
    private fun transcodedLoraParts(
        wire: WireEnvelope,
        cap: Int = MeshtasticProto.MAX_PAYLOAD,
    ): Int? =
        LoraFrameCodec
            .encode(wire, fragId = 1, maxPayload = cap, transcode = true)
            ?.size

    /**
     * The unsigned form (ADR 059): the v3 live-link tick, `relay = false`, no signature — its AEAD is the
     * authenticator. Reported like [report] minus the signature checks, plus the packet count at every cap.
     */
    private fun reportUnsigned(
        label: String,
        wire: WireEnvelope,
    ): ByteArray {
        assertEquals("the unsigned form", 0, wire.sig.size)
        assertFalse(wire.relay)
        val compact = checkNotNull(FastFrameCodec.encodeCompact(wire)) { "$label must be compact-encodable" }
        val deflated = if ((compact[1].toInt() and 0x02) != 0) "deflated" else "stored"
        val lora = checkNotNull(loraParts(wire))
        val esp32 = checkNotNull(LoraFrameCodec.encode(wire, fragId = 1, maxPayload = esp32PayloadCap)).size
        val best = checkNotNull(FastFrameCodec.encodeBest(wire, transcode = true)) { "$label must encode" }
        println(
            "size-budget: $label legacy=${legacySize(wire)}B compact=${compact.size}B ($deflated) " +
                "parts@255=${if (compact.size <= WifiAwareTransport.COORD_MSG_MAX) 1 else "2+"} " +
                "parts@${MeshtasticProto.MAX_PAYLOAD}=$lora parts@$esp32PayloadCap=$esp32 transcoded=${best.frame.size}B",
        )
        assertArrayEquals(
            "$label rebuilds through the transcoder",
            wire.signed,
            checkNotNull(FastFrameCodec.decodeCompact(best.frame)).signed,
        )
        val decoded = checkNotNull(FastFrameCodec.decodeCompact(compact)) { "$label compact round-trip" }
        assertEquals(0, decoded.sig.size)
        assertArrayEquals(wire.signed, decoded.signed)
        return compact
    }

    // --- fixtures ---

    private fun cleartextReceipt(alice: Party): WireEnvelope =
        alice.sign(
            RelayEnvelope(
                type = FrameType.RECEIPT,
                id = FrameId.new(),
                senderId = alice.nodeId,
                sentAt = SENT_AT,
                payload = WireCodec.encodePayload(ReceiptContent(ackId = FrameId.new())),
            ),
        )

    private fun cleartextReaction(
        alice: Party,
        emoji: String = "👍",
    ): WireEnvelope =
        alice.sign(
            RelayEnvelope(
                type = FrameType.REACTION,
                id = FrameId.new(),
                senderId = alice.nodeId,
                sentAt = SENT_AT,
                payload = WireCodec.encodePayload(ReactionContent(messageId = FrameId.new(), emoji = emoji)),
            ),
        )

    private fun fullProfile(
        alice: Party,
        spk: RatchetCrypto.KeyPair,
    ): WireEnvelope {
        val spkSig = alice.crypto.signRaw(RatchetCrypto.spkSigningBytes(SPK_ID, spk.pub))
        return alice.sign(
            RelayEnvelope(
                type = FrameType.PROFILE,
                id = FrameId.new(),
                senderId = alice.nodeId,
                sentAt = SENT_AT,
                payload =
                    WireCodec.encodePayload(
                        ProfileContent(
                            name = "Alice Example",
                            status = "Out exploring the mesh",
                            pubKey = alice.bundle.encoded,
                            deviceTag = "0123456789abcdef",
                            protoVersion = Protocol.VERSION,
                            capabilities = Protocol.LOCAL_CAPABILITIES,
                            prekey = PrekeyInfo(id = SPK_ID, pub = spk.pub, sig = spkSig),
                            version = SENT_AT,
                            // The largest profile is one with every flag set: the open-to-chat key rides
                            // only while true, and the bound-board node only while bound (a full 32-bit
                            // number is its widest encoding), so the budget is measured with both.
                            openToChat = true,
                            loraNode = 0xFFFFFFFFL,
                        ),
                    ),
            ),
        )
    }

    private fun typingDm(
        alice: Party,
        to: Party,
    ): WireEnvelope =
        alice.sign(
            RelayEnvelope(
                type = FrameType.TYPING,
                id = FrameId.new(),
                senderId = alice.nodeId,
                sentAt = SENT_AT,
                recipientId = to.nodeId,
                payload = WireCodec.encodePayload(TypingContent()),
            ),
            relay = false,
        )

    private fun typingGroup(alice: Party): WireEnvelope =
        alice.sign(
            RelayEnvelope(
                type = FrameType.TYPING,
                id = FrameId.new(),
                senderId = alice.nodeId,
                sentAt = SENT_AT,
                payload = WireCodec.encodePayload(TypingContent(groupId = "g-" + "a".repeat(24))),
            ),
            relay = false,
        )

    // --- budgets: cleartext frames ---

    @Test
    fun cleartextReceiptFitsOneMessage() {
        val alice = party()
        val sizes = report("cleartext-receipt", cleartextReceipt(alice), alice)
        assertTrue(sizes.legacy <= WifiAwareTransport.COORD_MSG_MAX)
        assertEquals("one message, with headroom regained", 1, sizes.parts)
    }

    @Test
    fun cleartextReactionFitsOneMessage() {
        val alice = party()
        val sizes = report("cleartext-reaction", cleartextReaction(alice), alice)
        assertTrue(sizes.legacy <= WifiAwareTransport.COORD_MSG_MAX)
        assertEquals(1, sizes.parts)
    }

    @Test
    fun typingCuesFitOneMessage() {
        val alice = party()
        val bob = party()
        val dm = report("typing-dm", typingDm(alice, bob), alice)
        val group = report("typing-group", typingGroup(alice), alice)
        assertTrue(dm.legacy <= WifiAwareTransport.COORD_MSG_MAX && group.legacy <= WifiAwareTransport.COORD_MSG_MAX)
        assertEquals(1, dm.parts)
        assertEquals(1, group.parts)
    }

    @Test
    fun fullProfileBudget() {
        val alice = party()
        val sizes = report("profile-full", fullProfile(alice, RatchetCrypto.generateKeyPair()), alice)
        assertTrue(
            "a full profile (pubKey + prekey) outgrows one message — fastFanout no-ops it today",
            sizes.legacy > WifiAwareTransport.COORD_MSG_MAX,
        )
        assertTrue("compact + fragmentation carries it in <= 3", sizes.parts <= FastFrameCodec.MAX_PARTS)
    }

    // --- budgets: v2 sealed frames (the frames Tier 0 exists for) ---

    @Test
    fun sealedCtlReceiptBudget() {
        val alice = party()
        val bob = party()
        val sealer = V2Sealer(alice, bob, RatchetCrypto.generateKeyPair())
        val first = alice.sign(sealer.dm(FrameId.new(), body = "", ctl = MessageContent.CTL_RECEIPT, ack = FrameId.new()), relay = false)
        sealer.confirm()
        val steady = alice.sign(sealer.dm(FrameId.new(), body = "", ctl = MessageContent.CTL_RECEIPT, ack = FrameId.new()), relay = false)
        val initSizes = report("sealed-receipt-init", first, alice)
        val steadySizes = report("sealed-receipt-steady", steady, alice)
        assertTrue("sealed receipts outgrow one legacy message (why Tier 0 exists)", steadySizes.legacy > WifiAwareTransport.COORD_MSG_MAX)
        assertTrue("the init form is the larger of the two", initSizes.legacy > steadySizes.legacy)
        assertTrue("compact + fragmentation carries both in <= 2", initSizes.parts <= 2 && steadySizes.parts <= 2)
    }

    @Test
    fun batchedSealedReceiptNeverRidesTheFastPlane() {
        // The executable reason AckSync structurally keeps pending batches off fastSend: a batched tick
        // (the custody escalation) outgrows even the compact fragment budget well before its 64-ack cap,
        // so the coordination plane could never carry it — escalated batches ride custody (NDP) only.
        // (Not routed through report(): that helper checkNotNull's fragmentation, and failing to fit IS
        // this test's point.)
        val alice = party()
        val bob = party()
        val sealer = V2Sealer(alice, bob, RatchetCrypto.generateKeyPair())
        sealer.confirm()
        val batch =
            alice.sign(
                sealer.dm(FrameId.new(), body = "", ctl = MessageContent.CTL_RECEIPT, acks = List(16) { FrameId.new() }),
                relay = false,
            )
        val legacy = legacySize(batch)
        assertTrue("a 16-ack batch outgrows one legacy message", legacy > WifiAwareTransport.COORD_MSG_MAX)
        val compact = checkNotNull(FastFrameCodec.encodeCompact(batch)) { "batched tick must still compact-encode" }
        val frags = FastFrameCodec.fragment(compact, WifiAwareTransport.COORD_MSG_MAX, fragId = 1)
        assertTrue(
            "a 16-ack batch cannot ride the fast plane the way a single tick does (single ticks fit <= 2 fragments)",
            frags == null || frags.size > 2,
        )
    }

    @Test
    fun sealedCtlReactionBudget() {
        val alice = party()
        val bob = party()
        val sealer = V2Sealer(alice, bob, RatchetCrypto.generateKeyPair())
        sealer.confirm()
        val steady =
            alice.sign(
                sealer.dm(
                    FrameId.new(),
                    body = "",
                    ctl = MessageContent.CTL_REACTION,
                    rp = ReactionPayload(messageId = FrameId.new(), emoji = "👍"),
                ),
            )
        val sizes = report("sealed-reaction-steady", steady, alice)
        assertTrue("sealed reactions outgrow one legacy message", sizes.legacy > WifiAwareTransport.COORD_MSG_MAX)
        assertTrue(sizes.parts <= 2)
    }

    @Test
    fun shortSealedDmChatBudget() {
        val alice = party()
        val bob = party()
        val sealer = V2Sealer(alice, bob, RatchetCrypto.generateKeyPair())
        sealer.confirm()
        val steady = alice.sign(sealer.dm(FrameId.new(), body = "See you at the north gate in ten minutes"))
        val sizes = report("sealed-dm-40char-steady", steady, alice)
        assertTrue("even a short sealed DM outgrows one legacy message", sizes.legacy > WifiAwareTransport.COORD_MSG_MAX)
        assertTrue(sizes.parts <= 2)
    }

    // --- budgets: crypto scheme v3 (ADR 059) — the derived nonce, the compact plaintext, and the unsigned live-link tick ---

    /**
     * The v3 unsigned tick is the one-packet form on every plane: one Wi-Fi Aware message (255), one nominal
     * LoRa packet (233), and one packet at the MTU-255 ESP32 boards' measured cap. The signed v3 DM ✓✓ keeps
     * its signature and custody and stays two packets — round 2's transcoder is what takes that one down.
     */
    @Test
    fun theUnsignedV3TickFitsOnePacketOnEveryPlane() {
        val alice = party()
        val bob = party()
        val sealer = V2Sealer(alice, bob, RatchetCrypto.generateKeyPair())
        sealer.confirm()
        val env = sealer.dm(FrameId.new(), body = "", ctl = MessageContent.CTL_RECEIPT, ack = FrameId.new(), v3 = true)
        val unsigned = WireEnvelope(relay = false, sig = ByteArray(0), signed = WireCodec.encodeEnvelope(env))
        val compact = reportUnsigned("unsigned-v3-tick", unsigned)
        assertTrue("one Wi-Fi Aware message (was 2)", compact.size <= WifiAwareTransport.COORD_MSG_MAX)
        assertEquals("one nominal LoRa packet (was 2)", 1, loraParts(unsigned))
        assertEquals(
            "one packet at the ESP32 cap of $esp32PayloadCap (needs the measured TORADIO_OVERHEAD; was 2)",
            1,
            checkNotNull(LoraFrameCodec.encode(unsigned, fragId = 1, maxPayload = esp32PayloadCap)).size,
        )

        // The same tick signed and v2 — what a peer without the bit still gets — is the two-packet form.
        val signedV2 = alice.sign(sealer.dm(FrameId.new(), body = "", ctl = MessageContent.CTL_RECEIPT, ack = FrameId.new()), relay = false)
        assertEquals(2, report("sealed-receipt-steady-v2", signedV2, alice).parts)
    }

    @Test
    fun v3KeepsTheSignedFormsTwoPacketsButLighter() {
        val alice = party()
        val bob = party()
        val sealer = V2Sealer(alice, bob, RatchetCrypto.generateKeyPair())
        val init = alice.sign(sealer.dm(FrameId.new(), body = "a".repeat(100), v3 = true))
        report("sealed-dm-100char-init-v3", init, alice)
        assertTrue(checkNotNull(loraParts(init)) <= FastFrameCodec.MAX_PARTS)
        sealer.confirm()
        val receipt = alice.sign(sealer.dm(FrameId.new(), body = "", ctl = MessageContent.CTL_RECEIPT, ack = FrameId.new(), v3 = true))
        val receiptV2 = alice.sign(sealer.dm(FrameId.new(), body = "", ctl = MessageContent.CTL_RECEIPT, ack = FrameId.new()))
        val v3 = report("sealed-receipt-steady-v3", receipt, alice)
        val v2 = report("sealed-receipt-steady-v2", receiptV2, alice)
        assertTrue("the signed v3 ✓✓ is ≥ 25 B lighter than v2 (${v2.compact} → ${v3.compact})", v2.compact - v3.compact >= 25)
        assertTrue("…and still custody-shaped: two packets", v3.parts == 2)
        val dm = alice.sign(sealer.dm(FrameId.new(), body = "b".repeat(100), v3 = true))
        report("sealed-dm-100char-steady-v3", dm, alice)
        assertTrue("a 100-char v3 DM rides in 2 LoRa packets", checkNotNull(loraParts(dm)) <= 2)
        val acks = List(DmAckCoalescer.MAX_LORA_TICK_ACKS) { FrameId.new() }
        val batch = alice.sign(sealer.dm(FrameId.new(), body = "", ctl = MessageContent.CTL_RECEIPT, acks = acks, v3 = true))
        val batchV2 = alice.sign(sealer.dm(FrameId.new(), body = "", ctl = MessageContent.CTL_RECEIPT, acks = acks))
        val batchSizes = report("coalesced-dm-tick-12-v3", batch, alice)
        val v2Batch = report("coalesced-dm-tick-12-v2", batchV2, alice)
        assertTrue("raw ids: ≥ 72 B lighter than the v2 batch", v2Batch.compact - batchSizes.compact >= 72)
        assertTrue(checkNotNull(LoraFrameCodec.encode(batch, fragId = 1, maxPayload = esp32PayloadCap)).size <= FastFrameCodec.MAX_PARTS)
    }

    /**
     * ADR 060: the transcoder (tag `0x05`) is what puts the **signed** v3 forms in one packet on every plane —
     * the DM ✓✓ with its signature and custody intact (221 B), a sealed reaction (229 B; two at the ESP32 cap),
     * a 40-char DM on Wi-Fi Aware (244 B) — and the profile bootstrap in two at the ESP32 cap instead of three.
     * A 100-char DM is the structural floor and stays two.
     */
    @Test
    fun theTranscoderPutsTheSignedV3FormsInOnePacket() {
        val alice = party()
        val bob = party()
        val sealer = V2Sealer(alice, bob, RatchetCrypto.generateKeyPair())
        sealer.confirm()
        val tick = alice.sign(sealer.dm(FrameId.new(), body = "", ctl = MessageContent.CTL_RECEIPT, ack = FrameId.new(), v3 = true))
        val tickSizes = report("sealed-receipt-steady-v3", tick, alice)
        assertEquals("the signed ✓✓ in one Wi-Fi Aware message (was 2)", 1, tickSizes.transcodedParts)
        assertEquals("…in one nominal LoRa packet (was 2)", 1, transcodedLoraParts(tick))
        assertEquals("…and in one packet at the ESP32 cap of $esp32PayloadCap (was 2)", 1, transcodedLoraParts(tick, esp32PayloadCap))
        val reaction =
            alice.sign(
                sealer.dm(
                    FrameId.new(),
                    body = "",
                    ctl = MessageContent.CTL_REACTION,
                    rp = ReactionPayload(messageId = FrameId.new(), emoji = "👍"),
                    v3 = true,
                ),
            )
        val reactionSizes = report("sealed-reaction-steady-v3", reaction, alice)
        assertEquals("a sealed reaction in one message", 1, reactionSizes.transcodedParts)
        assertEquals("…and one nominal LoRa packet", 1, transcodedLoraParts(reaction))
        val short = alice.sign(sealer.dm(FrameId.new(), body = "See you at the north gate in ten minutes", v3 = true))
        val shortSizes = report("sealed-dm-40char-steady-v3", short, alice)
        assertEquals("a 40-char DM in one Wi-Fi Aware message (was 2)", 1, shortSizes.transcodedParts)
        assertTrue("…and still two LoRa packets (244 B: past the 231-B cap)", checkNotNull(transcodedLoraParts(short)) <= 2)
        val profile = fullProfile(alice, RatchetCrypto.generateKeyPair())
        report("profile-full-transcoded", profile, alice)
        assertEquals("the profile bootstrap in two packets at the ESP32 cap (was 3)", 2, transcodedLoraParts(profile, esp32PayloadCap))
        val acks = List(DmAckCoalescer.MAX_LORA_TICK_ACKS) { FrameId.new() }
        val batch = alice.sign(sealer.dm(FrameId.new(), body = "", ctl = MessageContent.CTL_RECEIPT, acks = acks, v3 = true))
        report("coalesced-dm-tick-12-v3-transcoded", batch, alice)
        assertTrue("a 12-ack tick in <= 2 packets at the ESP32 cap (was 3)", checkNotNull(transcodedLoraParts(batch, esp32PayloadCap)) <= 2)
        val long = alice.sign(sealer.dm(FrameId.new(), body = "b".repeat(100), v3 = true))
        report("sealed-dm-100char-steady-v3-transcoded", long, alice)
        assertTrue("a 100-char DM is the structural floor: still 2 LoRa packets", checkNotNull(transcodedLoraParts(long)) <= 2)
    }

    /**
     * The open emoji set (any RGI emoji, capped at [TextLimits.REACTION] UTF-16 units on both ends): the
     * emoji rides verbatim inside the AEAD and through the transcoder, so every extra UTF-8 byte is one
     * wire byte. The longest sequence Unicode ships (a two-person kiss with skin tones, 35 B) takes the
     * sealed v3 reaction from 229 B to 261 B — two coordination messages and two LoRa packets, the
     * pre-ADR-060 status quo — and the cap's own worst case (290 B) stays two: never three, never refused.
     * The cleartext room form has the headroom and stays one packet everywhere.
     */
    @Test
    fun theWorstCaseEmojiKeepsEveryReactionFormWithinTwoPackets() {
        val alice = party()
        val bob = party()
        val sealer = V2Sealer(alice, bob, RatchetCrypto.generateKeyPair())
        sealer.confirm()
        assertTrue("the fixture is a legal reaction", isValidReactionEmoji(LONGEST_RGI))
        assertEquals("the cap's worst case is exactly at the cap", TextLimits.REACTION, CAP_MAX.length)
        val longest =
            alice.sign(
                sealer.dm(
                    FrameId.new(),
                    body = "",
                    ctl = MessageContent.CTL_REACTION,
                    rp = ReactionPayload(FrameId.new(), LONGEST_RGI),
                    v3 = true,
                ),
            )
        val longestSizes = report("sealed-reaction-longest-rgi-v3", longest, alice)
        assertEquals("the longest RGI sequence pushes a sealed reaction back to two messages", 2, longestSizes.transcodedParts)
        assertTrue(checkNotNull(transcodedLoraParts(longest)) <= 2)
        assertTrue(checkNotNull(transcodedLoraParts(longest, esp32PayloadCap)) <= 2)
        val capMax =
            alice.sign(
                sealer.dm(
                    FrameId.new(),
                    body = "",
                    ctl = MessageContent.CTL_REACTION,
                    rp = ReactionPayload(FrameId.new(), CAP_MAX),
                    v3 = true,
                ),
            )
        report("sealed-reaction-cap-max-v3", capMax, alice)
        assertTrue("the receive cap bounds a sealed reaction at two LoRa packets", checkNotNull(transcodedLoraParts(capMax)) <= 2)
        assertTrue(checkNotNull(transcodedLoraParts(capMax, esp32PayloadCap)) <= 2)
        val room = cleartextReaction(alice, LONGEST_RGI)
        val roomSizes = report("cleartext-reaction-longest-rgi", room, alice)
        assertEquals("a room reaction keeps one message", 1, roomSizes.transcodedParts)
        assertEquals(1, transcodedLoraParts(room))
        assertEquals(1, transcodedLoraParts(room, esp32PayloadCap))
    }

    @Test
    fun fragBudgetArithmetic() {
        // 3 parts x (cap - 4 B frag header) is the ceiling for any compact frame on this plane.
        assertEquals(
            753,
            FastFrameCodec.MAX_PARTS * (WifiAwareTransport.COORD_MSG_MAX - FastFrameCodec.FRAG_HEADER_BYTES),
        )
    }

    /** A plaintext Nearby-room chat (both addressing fields null) — the frame LoRa exists to carry. */
    private fun broadcastChat(
        alice: Party,
        body: String,
        replyTo: ReplyRef? = null,
    ): WireEnvelope =
        alice.sign(
            RelayEnvelope(
                type = FrameType.CHAT,
                id = FrameId.new(),
                senderId = alice.nodeId,
                sentAt = SENT_AT,
                payload = WireCodec.encodePayload(ChatContent(body = body, replyTo = replyTo)),
            ),
        )

    // --- budgets: the LoRa hop (Meshtastic Data.payload cap = 231 B on the air, <= 3 fragments) ---

    /**
     * The largest `Data.payload` a board behind a 255-byte BLE MTU takes in one write
     * (`LoraMeshTransport.TORADIO_OVERHEAD`, measured: 228).
     */
    private val esp32PayloadCap = 255 - LoraMeshTransport.TORADIO_OVERHEAD

    /**
     * Part count for [wire] on the LoRa hop in the untranscoded `0x03` form, or null when nothing fits <= 3
     * fragments. The pre-ADR-060 baseline — a stricter bound than the plane's own, which encodes `transcode =
     * true` (see [transcodedLoraParts]); a budget the hint promises is pinned against *that*, not this.
     */
    private fun loraParts(wire: WireEnvelope): Int? = LoraFrameCodec.encode(wire, fragId = 1)?.size

    @Test
    fun broadcastChatFitsTheLoraHop() {
        val alice = party()
        val short = checkNotNull(loraParts(broadcastChat(alice, "See you at the north gate"))) { "40-char room chat must fit" }
        val long = checkNotNull(loraParts(broadcastChat(alice, "x".repeat(200)))) { "200-char room chat must fit" }
        assertTrue("a short room post fits one LoRa packet", short == 1)
        assertTrue("a 200-char room post fits <= 3 LoRa packets", long <= FastFrameCodec.MAX_PARTS)
    }

    @Test
    fun cleartextMetadataFitsOneLoraPacket() {
        val alice = party()
        assertEquals(1, loraParts(cleartextReceipt(alice)))
        assertEquals(1, loraParts(cleartextReaction(alice)))
    }

    @Test
    fun theProfileBootstrapFitsTheLoraHop() {
        val alice = party()
        val parts =
            checkNotNull(loraParts(fullProfile(alice, RatchetCrypto.generateKeyPair()))) {
                "the profile is the key bootstrap — it MUST fit <= 3 LoRa packets or first contact never verifies"
            }
        assertTrue("profile in <= 3 LoRa packets (was $parts)", parts <= FastFrameCodec.MAX_PARTS)
    }

    @Test
    fun aSealedTickFitsTheLoraHop() {
        val alice = party()
        val bob = party()
        val sealer = V2Sealer(alice, bob, RatchetCrypto.generateKeyPair())
        sealer.confirm()
        val tick = alice.sign(sealer.dm(FrameId.new(), body = "", ctl = MessageContent.CTL_RECEIPT, ack = FrameId.new()), relay = false)
        val parts = checkNotNull(loraParts(tick)) { "a single sealed tick must fit the LoRa hop" }
        assertTrue("sealed tick in <= 3 LoRa packets (was $parts)", parts <= FastFrameCodec.MAX_PARTS)
    }

    /**
     * ADR 039: the sealed DM is what the long-range plane now carries. Pins the ceilings the docs quote — a
     * 100-char DM rides in 2 packets steady-state and ≤ 3 with the X3DH init attached (every frame until the
     * peer's first reply), an attachment *reference* still fits, and a max-length message is `loraTooBig`.
     */
    @Test
    fun sealedDmsFitTheLoraHop() {
        val alice = party()
        val bob = party()
        val sealer = V2Sealer(alice, bob, RatchetCrypto.generateKeyPair())
        val init = alice.sign(sealer.dm(FrameId.new(), body = "a".repeat(100)))
        report("sealed-dm-100char-init", init, alice)
        val initParts = checkNotNull(loraParts(init)) { "a session-initial 100-char DM must fit the LoRa hop" }
        assertTrue("session-initial DM in <= 3 LoRa packets (was $initParts)", initParts <= FastFrameCodec.MAX_PARTS)

        sealer.confirm()
        val steady = alice.sign(sealer.dm(FrameId.new(), body = "b".repeat(100)))
        report("sealed-dm-100char-steady", steady, alice)
        assertTrue("a 100-char steady-state DM rides in 2 LoRa packets", checkNotNull(loraParts(steady)) <= 2)

        val withImage =
            alice.sign(sealer.dm(FrameId.new(), body = "photo", attachmentHash = "ab".repeat(32), attachmentKey = "k".repeat(44)))
        val imageParts = checkNotNull(loraParts(withImage)) { "a DM carrying an attachment reference must fit" }
        assertTrue("attachment-ref DM in <= 3 LoRa packets (was $imageParts)", imageParts <= FastFrameCodec.MAX_PARTS)

        val huge = alice.sign(sealer.dm(FrameId.new(), body = "c".repeat(TextLimits.MESSAGE)))
        assertEquals("a max-length DM is loraTooBig — it rides the radios and custody instead", null, loraParts(huge))
    }

    /**
     * ADR 054: a coalesced DM tick at its cap replaces up to twelve single ticks, so it must still cross the
     * board — checked at the real ESP32 cap (MTU 255 → a 228-B `Data.payload`), not just the nominal 231.
     */
    @Test
    fun aCoalescedDmTickFitsTheLoraHop() {
        val alice = party()
        val bob = party()
        val sealer = V2Sealer(alice, bob, RatchetCrypto.generateKeyPair())
        sealer.confirm()
        val acks = List(DmAckCoalescer.MAX_LORA_TICK_ACKS) { FrameId.new() }
        val tick = alice.sign(sealer.dm(FrameId.new(), body = "", ctl = MessageContent.CTL_RECEIPT, acks = acks))
        report("coalesced-dm-tick-${acks.size}", tick, alice)
        val parts =
            checkNotNull(LoraFrameCodec.encode(tick, fragId = 1, maxPayload = esp32PayloadCap)) {
                "a coalesced tick at its cap must fit the LoRa hop"
            }
        assertTrue("coalesced tick in <= 3 LoRa packets at the ESP32 cap (was ${parts.size})", parts.size <= FastFrameCodec.MAX_PARTS)
    }

    /**
     * ADR 054: a DM at the composer's budget that also carries its full complement of inline acks must still
     * cross the board — `MeshManager.inlineAcksFor` reserves [INLINE_ACK_BYTES] per ack out of the same budget.
     * Measured in the transcoded form `LoraMeshTransport` sends (ADR 060): this fixture is 596 B there against
     * a 681-B ceiling, but 675-683 B untranscoded, so pinning it on [loraParts] made the test a coin flip on
     * the crypto's own entropy once the measured on-air cap took [MeshtasticProto.MAX_PAYLOAD] from 233 to 231.
     */
    @Test
    fun aBudgetDmWithInlineAcksStillFitsTheLoraHop() {
        val alice = party()
        val bob = party()
        val sealer = V2Sealer(alice, bob, RatchetCrypto.generateKeyPair())
        val body = "a".repeat(LoraSizeHint.DM_BODY_BYTES - MAX_INLINE_ACKS * INLINE_ACK_BYTES)
        val dm = alice.sign(sealer.dm(FrameId.new(), body = body, acks = List(MAX_INLINE_ACKS) { FrameId.new() }))
        report("sealed-dm-budget-with-inline-acks-init", dm, alice)
        val parts = checkNotNull(transcodedLoraParts(dm)) { "a budget DM with inline acks must fit the LoRa hop" }
        assertTrue("budget DM + inline acks in <= 3 LoRa packets (was $parts)", parts <= FastFrameCodec.MAX_PARTS)
    }

    /**
     * ADR 042: the contact-card intro is a session-initial `CTL_PROFILE` DM — the X3DH init plus a full
     * presentation payload (a 32-char name, a 100-char status, an avatar hash) — and it must cross the LoRa
     * hop, since a LoRa-only pair's intro has no other path until the session exists.
     */
    @Test
    fun anIntroFitsTheLoraHop() {
        val alice = party()
        val bob = party()
        val sealer = V2Sealer(alice, bob, RatchetCrypto.generateKeyPair())
        val payload =
            ProfilePayload(
                name = "n".repeat(TextLimits.DISPLAY_NAME),
                status = "s".repeat(TextLimits.STATUS),
                avatarHash = "ab".repeat(32),
                version = 1_756_100_000_000L,
                openToChat = true,
                loraNode = 0xFFFFFFFFL,
            )
        val intro = alice.sign(sealer.dm(FrameId.new(), body = "", ctl = MessageContent.CTL_PROFILE, pr = payload))
        report("intro-ctl-profile-init", intro, alice)
        val parts = checkNotNull(loraParts(intro)) { "a session-initial intro must fit the LoRa hop" }
        assertTrue("session-initial intro in <= 3 LoRa packets (was $parts)", parts <= FastFrameCodec.MAX_PARTS)
    }

    /**
     * ADR 040: the composer's "long message" hint is sized by [LoraSizeHint]'s body budgets, which must be
     * *under* the real ceilings — a draft at the budget must still fit in ≤ 3 packets in every form the hint
     * covers: a room post (deflate-hostile body, the honest upper bound — real text compresses better), a
     * session-initial DM, and each with the largest reply and an attachment reference riding along. Measured
     * in the transcoded form the plane sends (ADR 060), where the tightest of them keeps 63 B of headroom —
     * untranscoded, the DM-with-a-photo and DM-reply forms land within a byte or two of the 681-B ceiling.
     */
    @Test
    fun theComposerHintBudgetsFitTheLoraHop() {
        val alice = party()
        val bob = party()
        val sealer = V2Sealer(alice, bob, RatchetCrypto.generateKeyPair())
        val random = Random(seed = 7)
        // Printable ASCII, uniformly random: ~6.5 bits of entropy per byte, so the codec's deflate gains nothing.
        val noise = String(CharArray(LoraSizeHint.ROOM_BODY_BYTES) { (PRINTABLE_FIRST + random.nextInt(PRINTABLE_COUNT)).toChar() })
        val reply =
            ReplyRef(
                messageId = FrameId.new(),
                authorId = bob.nodeId,
                author = "n".repeat(TextLimits.DISPLAY_NAME),
                snippet = "s".repeat(REPLY_SNIPPET_MAX),
                hasAttachment = true,
            )

        fun fits(
            label: String,
            wire: WireEnvelope,
        ) {
            val parts = checkNotNull(transcodedLoraParts(wire)) { "$label must fit the LoRa hop at its hint budget" }
            assertTrue("$label in <= 3 LoRa packets (was $parts)", parts <= FastFrameCodec.MAX_PARTS)
        }
        val room = LoraSizeHint.ROOM_BODY_BYTES
        fits("room post", broadcastChat(alice, noise.take(room)))
        fits("room reply", broadcastChat(alice, noise.take(LoraSizeHint.budget(room, replying = true, attached = false)), replyTo = reply))
        val dm = LoraSizeHint.DM_BODY_BYTES
        fits("session-initial DM", alice.sign(sealer.dm(FrameId.new(), body = noise.take(dm))))
        fits(
            "session-initial DM reply",
            alice.sign(
                sealer.dm(FrameId.new(), body = noise.take(LoraSizeHint.budget(dm, replying = true, attached = false)), replyTo = reply),
            ),
        )
        fits(
            "session-initial DM with a photo",
            alice.sign(
                sealer.dm(
                    FrameId.new(),
                    body = noise.take(LoraSizeHint.budget(dm, replying = false, attached = true)),
                    attachmentHash = "ab".repeat(32),
                    attachmentKey = "k".repeat(44),
                ),
            ),
        )
    }

    @Test
    fun loraFragmentCeilingArithmetic() {
        // 3 parts x (231-B payload - 4-B fragment header) is the most any compact frame can carry over LoRa: the
        // firmware transmits at most a 237-byte Data, and Knit's private portnum plus the payload framing take 6.
        assertEquals(231, MeshtasticProto.MAX_PAYLOAD)
        assertEquals(681, FastFrameCodec.MAX_PARTS * (MeshtasticProto.MAX_PAYLOAD - FastFrameCodec.FRAG_HEADER_BYTES))
    }

    private companion object {
        /** 👩🏽‍❤️‍💋‍👨🏼 — the longest RGI emoji sequence Unicode ships: 10 code points, 15 UTF-16 units, 35 B UTF-8. */
        const val LONGEST_RGI = "\uD83D\uDC69\uD83C\uDFFD\u200D\u2764\uFE0F\u200D\uD83D\uDC8B\u200D\uD83D\uDC68\uD83C\uDFFC"

        /** The largest string the receive cap admits: 16 × 👍 = 32 UTF-16 units, 64 B UTF-8. */
        val CAP_MAX = "👍".repeat(TextLimits.REACTION / 2)
        const val PRINTABLE_FIRST = 0x21
        const val PRINTABLE_COUNT = 0x5E
        const val HYBRID_TEMPLATE = "DHKEM_X25519_HKDF_SHA256_HKDF_SHA256_AES_256_GCM_RAW"
        const val SIG_TEMPLATE = "ED25519_RAW"
        const val SPK_ID = 1

        /** Fixed realistic clocks so every run measures identical frames. */
        const val SENT_AT = 1_755_700_000_000L
        const val SESSION_AT = 1_755_700_000_000L
    }
}
