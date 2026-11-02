package app.getknit.knit.mesh.link

import app.getknit.knit.mesh.crypto.LinkProof
import app.getknit.knit.mesh.crypto.LinkProver
import app.getknit.knit.mesh.protocol.Protocol
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom

/**
 * The transport-neutral identity handshake over a fresh [LinkSocket]: a **two-way** HELLO exchange, and on a link
 * where both HELLOs carry [Protocol.CAP_LINK_PROOF], a proof of the node id each claims (ADR 2026-11.264w, #232).
 *
 * The dialer ([dial]) writes its HELLO first, so an accept-any responder — which accepts a connection without knowing
 * who dialed in — learns the peer; the responder reads it ([receiveHello]), its plane decides whether to keep it, and then
 * it [answer]s with its own HELLO so the dialer can confirm it reached the peer it intended. Both HELLOs carry a fresh
 * nonce ([Protocol.hello]). When both carry the bit, each side then writes a `PROOF` record ([LinkFraming.Type.PROOF]):
 * its two raw public keys and its identity-key signature over both HELLOs as they crossed the link
 * ([LinkProof.transcript]), the responder's straight behind its reply, the dialer's once the responder's verifies. A
 * plane registers the peer as proven only after the other side's proof verified, and nothing keyed on the peer's id
 * runs before it registers. Wi-Fi Aware (NDP) and Bluetooth (L2CAP) links use it; a direct link's HELLO is
 * [writeLaneHello] (its lane keys bind both ids already).
 *
 * Ordering is deadlock-free: the dialer writes-then-reads, the responder reads-then-writes, so neither blocks on a read
 * before the other has written. Every read here blocks: each caller **bounds** them (so a stalled peer can't pin a
 * slot) — Wi-Fi Aware with `socket.soTimeout`, Bluetooth by closing the socket from a watchdog (a `BluetoothSocket` has
 * no `soTimeout`). A read cut that way is a missing record, a failed handshake. Whether to keep a link whose peer did
 * not prove itself ([Dialed.Linked] or [Answered.Linked] with `proven = false`) is the plane's call ([LinkClaims]).
 */
object LinkHandshake {
    /**
     * A HELLO record as it crossed the link: [payload] is its bytes exactly as received (the transcript signs these,
     * never a re-encoding), [wire] what they say.
     */
    class ReceivedHello(
        val payload: ByteArray,
        val wire: Protocol.PeerWire,
    ) {
        /** The HELLO's caps carry [Protocol.CAP_LINK_PROOF]: its sender proves itself and expects a proof back. */
        val offersProof: Boolean get() = wire.offersProof

        /** It carries the bit but no valid nonce: a failed proof, never a legacy link. */
        val malformedOffer: Boolean get() = wire.offersProof && wire.nonceHex == null
    }

    /** How a dial's handshake ended. */
    sealed interface Dialed {
        /** The reply named the peer dialed; [proven] when it proved it, false when the reply lacks the bit (legacy). */
        data class Linked(
            val reply: Protocol.PeerWire,
            val proven: Boolean,
        ) : Dialed

        /** No reply, or the first record was not a HELLO naming a canonical node id. */
        data object NoReply : Dialed

        /** The reply named another node than the one dialed. */
        data class WrongPeer(
            val reply: Protocol.PeerWire,
        ) : Dialed

        /**
         * The reply carries the bit and its proof failed for [why], or, for a [dialTag], the reply carries no bit at all
         * ([WHY_UNPROVEN]). Nothing was written after our HELLO. On a tag dial, [reply]'s id is unproven: never key the
         * failure on it.
         */
        data class ProofFailed(
            val reply: Protocol.PeerWire,
            val why: String,
        ) : Dialed
    }

    /** How a responder's half ended, once its plane kept the dialer. */
    sealed interface Answered {
        /** The reply went out; [proven] when the dialer's proof verified, false when its HELLO lacks the bit (legacy). */
        data class Linked(
            val proven: Boolean,
        ) : Answered

        /** The reply could not be written. */
        data object WriteFailed : Answered

        /** The dialer offered a proof and it failed for [why]. */
        data class ProofFailed(
            val why: String,
        ) : Answered
    }

    /** [Protocol.HELLO_NONCE_BYTES] fresh bytes from the platform CSPRNG: one per connection attempt, per side. */
    fun freshNonce(): ByteArray = ByteArray(Protocol.HELLO_NONCE_BYTES).also(random::nextBytes)

    /**
     * Dialer: write our HELLO (the bit and [nonce]) and read the reply, which must name [expectedId]. When the reply
     * carries the bit too, the next record must be the responder's PROOF: it is verified, and only then is ours written.
     */
    @Suppress("ReturnCount") // one early return per outcome of the exchange, in the order the records arrive
    fun dial(
        input: InputStream,
        output: OutputStream,
        prover: LinkProver,
        expectedId: String,
        nonce: ByteArray = freshNonce(),
        told: ByteArray? = null,
        addressed: ByteArray? = null,
    ): Dialed {
        val helloD = Protocol.hello(prover.nodeId, nonce, told, addressed).encodeToByteArray()
        LinkFraming.write(output, LinkFraming.Type.HELLO, helloD)
        output.flush()
        val reply = receiveHello(input) ?: return Dialed.NoReply
        if (reply.wire.nodeId != expectedId) return Dialed.WrongPeer(reply.wire)
        if (!reply.offersProof) return Dialed.Linked(reply.wire, proven = false)
        if (reply.malformedOffer) return Dialed.ProofFailed(reply.wire, "no nonce")
        val why = readProof(input, reply.wire.nodeId, LinkProof.Role.RESPONDER, helloD, reply.payload)
        if (why != null) return Dialed.ProofFailed(reply.wire, why)
        LinkFraming.write(output, LinkFraming.Type.PROOF, prover.proof(LinkProof.Role.DIALER, helloD, reply.payload))
        output.flush()
        return Dialed.Linked(reply.wire, proven = true)
    }

    /**
     * Dialer, toward a blinded advert (ADR 2026-11.xp6d): [tag] is the advert's bytes 1–16, which name no node, so no id
     * is expected back. Our HELLO carries [tag] as its `a` field and [told] (what we air now) as its `t`. The reply must
     * carry [Protocol.CAP_LINK_PROOF] and a proof that verifies: every build that airs a tag proves itself, so a reply
     * that does not is not that advert's phone ([WHY_UNPROVEN]). The proven id is whatever the reply names; whether it
     * owns [tag] is the resolver's question (`AdvertAliases.onDialed`), never this one's. Our own HELLO is written
     * before anything is read, so whoever answers learns our id even when the dial then fails.
     *
     * A failed tag dial ([Dialed.NoReply], [Dialed.WrongPeer], [Dialed.ProofFailed]) carries the reply's node id, which
     * nothing proved: it is whatever the answerer wrote. The caller keys the failure on [tag] and the address it dialed,
     * never on that id — no backoff, claim refusal, proof-failure count or GATT payload forget for it — or a forged answer
     * would steer the penalty onto any phone (ADR 2026-11.264w, "a claim close never bumps the real peer's backoff").
     */
    @Suppress("ReturnCount") // one early return per outcome, in the order the records arrive, as [dial]
    fun dialTag(
        input: InputStream,
        output: OutputStream,
        prover: LinkProver,
        tag: ByteArray,
        told: ByteArray? = null,
        nonce: ByteArray = freshNonce(),
    ): Dialed {
        val helloD = Protocol.hello(prover.nodeId, nonce, told, addressed = tag).encodeToByteArray()
        LinkFraming.write(output, LinkFraming.Type.HELLO, helloD)
        output.flush()
        val reply = receiveHello(input) ?: return Dialed.NoReply
        if (reply.wire.nodeId == prover.nodeId) return Dialed.WrongPeer(reply.wire)
        if (!reply.offersProof) return Dialed.ProofFailed(reply.wire, WHY_UNPROVEN)
        if (reply.malformedOffer) return Dialed.ProofFailed(reply.wire, "no nonce")
        val why = readProof(input, reply.wire.nodeId, LinkProof.Role.RESPONDER, helloD, reply.payload)
        if (why != null) return Dialed.ProofFailed(reply.wire, why)
        LinkFraming.write(output, LinkFraming.Type.PROOF, prover.proof(LinkProof.Role.DIALER, helloD, reply.payload))
        output.flush()
        return Dialed.Linked(reply.wire, proven = true)
    }

    /**
     * Responder: read exactly one record and require it to be a [LinkFraming.Type.HELLO] naming a canonical node id
     * ([Protocol.parse], #199), or null (reject and close the socket). Reads from a plain [InputStream] so the caller
     * can pass whichever buffered stream it will keep reading from.
     */
    fun receiveHello(input: InputStream): ReceivedHello? {
        val first = runCatching { LinkFraming.read(input) }.getOrNull() ?: return null
        if (first.type != LinkFraming.Type.HELLO) return null
        val wire = Protocol.parse(first.payload.decodeToString()) ?: return null
        return ReceivedHello(first.payload, wire)
    }

    /**
     * Responder, once its plane kept [dialer]: reply with our HELLO (the bit and [nonce]), and when the dialer's carries
     * the bit, our PROOF behind it in the same write, then read the dialer's, which must be the next record. A
     * [ReceivedHello.malformedOffer] is a failed proof refused with no reply: nothing is written for it, here or by a
     * caller that checks it first.
     */
    fun answer(
        input: InputStream,
        output: OutputStream,
        prover: LinkProver,
        dialer: ReceivedHello,
        nonce: ByteArray = freshNonce(),
        told: ByteArray? = null,
    ): Answered {
        if (dialer.malformedOffer) return Answered.ProofFailed("no nonce")
        val helloR = Protocol.hello(prover.nodeId, nonce, told).encodeToByteArray()
        val proves = dialer.offersProof
        val wrote =
            runCatching {
                // One write and one flush: the reply and the proof go out together (a second write would cost the
                // dialer a second read on an L2CAP channel for nothing).
                val reply = LinkFraming.encode(LinkFraming.Type.HELLO, helloR)
                val bytes =
                    if (proves) {
                        reply + LinkFraming.encode(LinkFraming.Type.PROOF, prover.proof(LinkProof.Role.RESPONDER, dialer.payload, helloR))
                    } else {
                        reply
                    }
                output.write(bytes)
                output.flush()
            }.isSuccess
        if (!wrote) return Answered.WriteFailed
        if (!proves) return Answered.Linked(proven = false)
        val why = readProof(input, dialer.wire.nodeId, LinkProof.Role.DIALER, dialer.payload, helloR)
        return if (why == null) Answered.Linked(proven = true) else Answered.ProofFailed(why)
    }

    /**
     * The direct-link plane's HELLO: this build's caps, the bit among them, and a fresh nonce, as [Protocol.hello] writes
     * every HELLO, but no `PROOF` follows on either side. A direct link's lane keys already bind both ids (ADR
     * 2026-10.a5yj), so it runs no exchange; the bit there says only whether the peer's build proves its radio links,
     * which is what [LinkClaims] may then rely on.
     */
    fun writeLaneHello(
        output: OutputStream,
        localNodeId: String,
        nonce: ByteArray = freshNonce(),
    ) {
        LinkFraming.write(output, LinkFraming.Type.HELLO, Protocol.hello(localNodeId, nonce).encodeToByteArray())
        output.flush()
    }

    /** The PROOF that must be the next record, verified as [signerId]'s in [role]: null when it holds, else why not. */
    private fun readProof(
        input: InputStream,
        signerId: String,
        role: LinkProof.Role,
        helloD: ByteArray,
        helloR: ByteArray,
    ): String? {
        val record = runCatching { LinkFraming.read(input) }.getOrElse { return WHY_UNREADABLE } ?: return WHY_EOF
        if (record.type != LinkFraming.Type.PROOF) return "${record.type.name.lowercase()} record before the proof"
        return LinkProof.verify(record.payload, signerId, role, helloD, helloR)?.name?.lowercase()
    }

    /**
     * A [Dialed.ProofFailed] / [Answered.ProofFailed] `why` for a PROOF read that ended in an error: most often the
     * caller's read bound cut it (the watchdog or `soTimeout`), so the peer may be gone, not lying.
     */
    const val WHY_UNREADABLE = "unreadable"

    /** The `why` for a link the peer closed where its PROOF belonged: it was there to close it, and is gone now. */
    const val WHY_EOF = "eof"

    /**
     * The `why` for a tag dial ([dialTag]) whose reply carries no [Protocol.CAP_LINK_PROOF]: every build that airs a tag
     * proves itself, so this is not that advert's phone. Not transient: it is evidence about who answered.
     */
    const val WHY_UNPROVEN = "unproven"

    /**
     * Whether a proof failure's [why] says only that the peer went quiet or away mid-handshake ([WHY_UNREADABLE],
     * [WHY_EOF]), not that it answered with a wrong, missing or misplaced proof: a fault to retry as contention, never
     * evidence about the peer.
     */
    fun isTransient(why: String): Boolean = why == WHY_UNREADABLE || why == WHY_EOF

    private val random = SecureRandom()
}
