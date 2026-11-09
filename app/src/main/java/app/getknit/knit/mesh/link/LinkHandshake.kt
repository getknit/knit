package app.getknit.knit.mesh.link

import app.getknit.knit.mesh.crypto.LinkProof
import app.getknit.knit.mesh.crypto.LinkProver
import app.getknit.knit.mesh.crypto.LinkSeal
import app.getknit.knit.mesh.crypto.SealException
import app.getknit.knit.mesh.protocol.Protocol
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom

/**
 * The transport-neutral identity handshake over a fresh [LinkSocket]: a **two-way** HELLO exchange, and on a link
 * where both HELLOs carry [Protocol.CAP_LINK_PROOF], a proof of the node id each claims (ADR 2026-11.264w, #232).
 *
 * The dialer ([dialPlain]) writes its HELLO first, so an accept-any responder — which accepts a connection without knowing
 * who dialed in — learns the peer; the responder reads it ([receiveOpening]), its plane decides whether to keep it, and
 * then it answers ([answerPlain]) with its own HELLO so the dialer can confirm it reached the peer it intended. Both HELLOs carry a fresh
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
 * not prove itself ([DialOutcome.Linked] or [AnswerOutcome.Linked] with `proven = false`) is the plane's call
 * ([LinkClaims]).
 *
 * **The sealed form** (#286, [LinkSeal], [SealedLinkSocket]). A dialer whose target's advert carries the seal flag (or
 * whose Wi-Fi Aware SSI caps carry [Protocol.CAP_LINK_SEAL]) opens with [openSeal]: its `KEY_SHARE` first, then the
 * responder's. A responder reads either first record with [receiveOpening]: a `KEY_SHARE` gets ours back at once, before
 * any admission decision, and a HELLO is the plain form. Everything after the two key shares rides `SEALED` frames, and
 * the exchange above runs unchanged inside them ([dialSealed], [dialTagSealed], [receiveSealedHello], [answerSealed]),
 * except that both sealed HELLOs must carry [Protocol.CAP_LINK_PROOF], [Protocol.CAP_LINK_SEAL] and a valid nonce, and
 * each PROOF signs the v2 transcript over both key shares ([LinkProof.sealedTranscript]). Each stage is a separate call so
 * a caller bounds each wait on its own (contract §4). On a link opened plain, a HELLO with the seal bit toward a build
 * that carries it too is refused ([dialPlain]'s [DialOutcome.SealRefused], the responder's [refusesPlain]): the bit is
 * inside the signed transcript, so a relay that strips the advert flag gets no link rather than a plain one (§7).
 *
 * A failed seal ([SealOpened.Failed], [DialOutcome.SealFailed], [Opening.SealFailed], [SealedHello.SealFailed],
 * [AnswerOutcome.SealFailed]) is always bytes that arrived and were wrong: another record where a key share belongs, a
 * wrong length, a refused point, a frame that won't open, an inner record that breaks the sealed exchange's rules. An EOF,
 * a timeout or a raw read error is never one, at any stage, on either side: it is the no-reply or unreadable outcome it is
 * on a plain link. A seal failure is never a proof failure: it never feeds [LinkClaims] and never counts
 * `linkProofFailed`; a PROOF that opens and does not verify is a proof failure only.
 */
@Suppress("TooManyFunctions") // one function per stage of each form, the plain and the sealed, and their readers
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

        /** The HELLO's caps carry [Protocol.CAP_LINK_SEAL]: its sender seals links (#286). */
        val offersSeal: Boolean get() = wire.capabilities and Protocol.CAP_LINK_SEAL != 0L

        /** What a HELLO inside a seal must be: both bits and a valid nonce. Anything less is a failed seal. */
        val sealable: Boolean get() = offersProof && offersSeal && wire.nonceHex != null
    }

    /** How a dialer's [openSeal] ended. */
    sealed interface SealOpened {
        /** Both key shares crossed: [link] carries the rest of the handshake and then the link, sealed. */
        class Sealed(
            val link: SealedLinkSocket,
        ) : SealOpened

        /**
         * Nothing where the responder's `KEY_SHARE` belongs: an EOF, a timeout, a raw read error, or our own key share
         * could not be written. Today's no-reply refusal, **not** a failed seal: no byte arrived that was wrong (contract
         * revision 1). A responder that closes on our `KEY_SHARE` lands here.
         */
        data object NoReply : SealOpened

        /** Bytes arrived and were wrong (another record where the key share belongs, the wrong length, a refused point): a failed seal. */
        data class Failed(
            val why: String,
        ) : SealOpened
    }

    /** How a dial's handshake ended, sealed or plain. */
    sealed interface DialOutcome {
        /**
         * The reply named the peer dialed; [proven] when it proved it, false when the reply lacks the bit (legacy, plain
         * only). [link] is the socket the link runs on from here — the [SealedLinkSocket] on a sealed link (always proven),
         * the socket dialed otherwise — and the only one the caller registers: a plain socket under a sealed handshake
         * would write the link's records in the clear to a peer that drops them.
         */
        data class Linked(
            val reply: Protocol.PeerWire,
            val proven: Boolean,
            val link: LinkSocket,
        ) : DialOutcome {
            /** The link rides `SEALED` frames. */
            val sealed: Boolean get() = link is SealedLinkSocket
        }

        /**
         * No reply: nothing, an EOF or a timeout where the reply HELLO belongs, or a write of ours failed. On a sealed dial
         * that is the responder's refusal after its `KEY_SHARE` (contract §4), never a failed seal.
         */
        data object NoReply : DialOutcome

        /** The reply named another node than the one dialed (a tag dial: ourselves). */
        data class WrongPeer(
            val reply: Protocol.PeerWire,
        ) : DialOutcome

        /**
         * The reply carries the bit and its proof failed for [why], or, for a tag dial, the reply carries no bit at all
         * ([WHY_UNPROVEN]); on a sealed link, a PROOF that opened and did not verify, or a raw fault on its read. Nothing
         * was written after our HELLO. On a tag dial, [reply]'s id is unproven: never key the failure on it.
         */
        data class ProofFailed(
            val reply: Protocol.PeerWire,
            val why: String,
        ) : DialOutcome

        /**
         * A sealed dial whose reply broke the seal: a frame that won't open, an inner record that is not the HELLO, an inner
         * `KEY_SHARE` / `SEALED`, a HELLO without both bits and a nonce. Never a proof failure; the caller keys it on the
         * address or tag it dialed, never on an id a reply names.
         */
        data class SealFailed(
            val why: String,
        ) : DialOutcome

        /**
         * §7: a plain dial whose HELLO and reply both carry [Protocol.CAP_LINK_SEAL]. Nothing was written after our HELLO
         * (no PROOF). Checked after [WrongPeer], so a reused address still reads as the wrong peer. [reply]'s id is
         * unproven: the caller logs it as claimed and never keys a penalty on it.
         */
        data class SealRefused(
            val reply: Protocol.PeerWire,
        ) : DialOutcome
    }

    /** What a responder's first record said ([receiveOpening]). */
    sealed interface Opening {
        /** A HELLO naming a canonical node id: the plain form. Check [refusesPlain] before anything else. */
        class Plain(
            val hello: ReceivedHello,
        ) : Opening

        /** A `KEY_SHARE`: ours is written, the keys derived; [link] carries the rest, sealed. No admission ran yet. */
        class Sealed(
            val link: SealedLinkSocket,
        ) : Opening

        /** A `KEY_SHARE` of the wrong length, or a point X25519 refuses: a failed seal. Nothing was written. */
        data class SealFailed(
            val why: String,
        ) : Opening

        /** An EOF, a raw read error, a timeout, or a first record that is neither: closed as today, not a failed seal. */
        data object None : Opening
    }

    /** The dialer's HELLO inside the seal ([receiveSealedHello]). */
    sealed interface SealedHello {
        /** The first inner record: a HELLO naming a canonical id, carrying both bits and a valid nonce. */
        class Hello(
            val hello: ReceivedHello,
        ) : SealedHello

        /** Bytes arrived and were wrong: a frame that won't open, another first record, a HELLO without both bits and a nonce. */
        data class SealFailed(
            val why: String,
        ) : SealedHello

        /** An EOF, a raw read error or a timeout: closed as today, not a failed seal. */
        data object None : SealedHello
    }

    /** How a responder's half ended, sealed or plain, once its plane kept the dialer. */
    sealed interface AnswerOutcome {
        /**
         * The reply went out; [proven] when the dialer's proof verified. [link] is the socket the link runs on from here —
         * the [SealedLinkSocket] on a sealed link, the accepted socket otherwise — and the only one the caller registers.
         */
        data class Linked(
            val proven: Boolean,
            val link: LinkSocket,
        ) : AnswerOutcome {
            /** The link rides `SEALED` frames. */
            val sealed: Boolean get() = link is SealedLinkSocket
        }

        /** The reply could not be written. */
        data object WriteFailed : AnswerOutcome

        /** The dialer offered a proof and it failed for [why]: on a sealed link, one that opened and did not verify. */
        data class ProofFailed(
            val why: String,
        ) : AnswerOutcome

        /** The dialer's PROOF read broke the seal, or its HELLO is not one a seal admits. Never a proof failure. */
        data class SealFailed(
            val why: String,
        ) : AnswerOutcome

        /** §7, for a caller that skipped [refusesPlain]: nothing was written. */
        data object SealRefused : AnswerOutcome
    }

    /** [Protocol.HELLO_NONCE_BYTES] fresh bytes from the platform CSPRNG: one per connection attempt, per side. */
    fun freshNonce(): ByteArray = ByteArray(Protocol.HELLO_NONCE_BYTES).also(random::nextBytes)

    /**
     * The plain form's dialer, shared by [dialPlain] and [dialTagPlain]: [expectedId] null is a tag dial (no id expected,
     * a proof required, our own id back is the wrong peer). §7 runs after the wrong-peer check and before the proof
     * branch. Writes throw; the callers read that as no reply.
     */
    @Suppress("ReturnCount") // one early return per outcome of the exchange, in the order the records arrive
    private fun plainDial(
        link: LinkSocket,
        prover: LinkProver,
        helloD: ByteArray,
        expectedId: String?,
    ): DialOutcome {
        val input = link.input
        val output = link.output
        LinkFraming.write(output, LinkFraming.Type.HELLO, helloD)
        output.flush()
        val reply = receiveHello(input) ?: return DialOutcome.NoReply
        val wrongPeer = if (expectedId == null) reply.wire.nodeId == prover.nodeId else reply.wire.nodeId != expectedId
        if (wrongPeer) return DialOutcome.WrongPeer(reply.wire)
        if (sealsLinks && reply.offersSeal) return DialOutcome.SealRefused(reply.wire)
        if (!reply.offersProof) {
            return if (expectedId == null) {
                DialOutcome.ProofFailed(reply.wire, WHY_UNPROVEN)
            } else {
                DialOutcome.Linked(reply.wire, proven = false, link = link)
            }
        }
        if (reply.malformedOffer) return DialOutcome.ProofFailed(reply.wire, "no nonce")
        val why = readProof(input, reply.wire.nodeId, LinkProof.Role.RESPONDER, helloD, reply.payload)
        if (why != null) return DialOutcome.ProofFailed(reply.wire, why)
        LinkFraming.write(output, LinkFraming.Type.PROOF, prover.proof(LinkProof.Role.DIALER, helloD, reply.payload))
        output.flush()
        return DialOutcome.Linked(reply.wire, proven = true, link = link)
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

    // --- the plain form with §7 (#286) ---

    /**
     * Dialer, plain form, by id: write our HELLO (the bit and [nonce]) and read the reply, which must name [expectedId].
     * When the reply carries [Protocol.CAP_LINK_PROOF] too, the next record must be the responder's PROOF: it is verified
     * over the v1 transcript, and only then is ours written; a reply without it links unproven (a legacy build). §7: a reply
     * that carries [Protocol.CAP_LINK_SEAL] toward our HELLO, which carries it too, is [DialOutcome.SealRefused] (after
     * [DialOutcome.WrongPeer], before the proof branch), and nothing is written after our HELLO. A write that fails is
     * [DialOutcome.NoReply].
     */
    fun dialPlain(
        link: LinkSocket,
        prover: LinkProver,
        expectedId: String,
        nonce: ByteArray = freshNonce(),
        told: ByteArray? = null,
        addressed: ByteArray? = null,
    ): DialOutcome {
        require(link !is SealedLinkSocket) { "a sealed link dials with dialSealed" }
        val helloD = Protocol.hello(prover.nodeId, nonce, told, addressed).encodeToByteArray()
        return orNoReply { plainDial(link, prover, helloD, expectedId) }
    }

    /**
     * Dialer, plain form, toward a blinded advert (ADR 2026-11.xp6d): [tag] is the advert's bytes 1–16, which name no
     * node, so no id is expected back. Our HELLO carries [tag] as its `a` field and [told] (what we air now) as its `t`.
     * The reply must carry [Protocol.CAP_LINK_PROOF] and a proof that verifies: every build that airs a tag proves itself,
     * so a reply that does not is not that advert's phone ([WHY_UNPROVEN]). The proven id is whatever the reply names;
     * whether it owns [tag] is the resolver's question (`AdvertAliases.onDialed`), never this one's. Our own HELLO is
     * written before anything is read, so whoever answers learns our id even when the dial then fails. §7 as [dialPlain].
     *
     * A failed tag dial ([DialOutcome.NoReply], [DialOutcome.WrongPeer], [DialOutcome.ProofFailed],
     * [DialOutcome.SealRefused]) may carry the reply's node id, which nothing proved: it is whatever the answerer wrote.
     * The caller keys the failure on [tag] and the address it dialed, never on that id — no backoff, claim refusal,
     * proof-failure count or GATT payload forget for it — or a forged answer would steer the penalty onto any phone (ADR
     * 2026-11.264w, "a claim close never bumps the real peer's backoff").
     */
    fun dialTagPlain(
        link: LinkSocket,
        prover: LinkProver,
        tag: ByteArray,
        told: ByteArray? = null,
        nonce: ByteArray = freshNonce(),
    ): DialOutcome {
        require(link !is SealedLinkSocket) { "a sealed link dials with dialTagSealed" }
        val helloD = Protocol.hello(prover.nodeId, nonce, told, addressed = tag).encodeToByteArray()
        return orNoReply { plainDial(link, prover, helloD, expectedId = null) }
    }

    /**
     * §7, the responder's half: the dialer opened plain, its HELLO carries [Protocol.CAP_LINK_SEAL], and this build's would
     * too. Checked before R1 and every other admission refusal; the caller closes without a reply and counts it once.
     */
    fun refusesPlain(hello: ReceivedHello): Boolean = sealsLinks && hello.offersSeal

    /**
     * Responder, plain form, once its plane kept [dialer]: reply with our HELLO (the bit and [nonce]), and when the dialer's
     * carries [Protocol.CAP_LINK_PROOF], our PROOF behind it in the same write, then read the dialer's, which must be the
     * next record and verify over the v1 transcript. A [ReceivedHello.malformedOffer] is a failed proof refused with no
     * reply: nothing is written for it, here or by a caller that checks it first. §7 is refused here too
     * ([AnswerOutcome.SealRefused], nothing written) for a caller that did not ask [refusesPlain] first.
     */
    @Suppress("ReturnCount") // one early return per outcome, in the order the exchange runs, as the dialer's
    fun answerPlain(
        link: LinkSocket,
        prover: LinkProver,
        dialer: ReceivedHello,
        nonce: ByteArray = freshNonce(),
        told: ByteArray? = null,
    ): AnswerOutcome {
        require(link !is SealedLinkSocket) { "a sealed link answers with answerSealed" }
        if (refusesPlain(dialer)) return AnswerOutcome.SealRefused
        if (dialer.malformedOffer) return AnswerOutcome.ProofFailed("no nonce")
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
                link.output.write(bytes)
                link.output.flush()
            }.isSuccess
        if (!wrote) return AnswerOutcome.WriteFailed
        if (!proves) return AnswerOutcome.Linked(proven = false, link = link)
        val why = readProof(link.input, dialer.wire.nodeId, LinkProof.Role.DIALER, dialer.payload, helloR)
        return if (why == null) AnswerOutcome.Linked(proven = true, link = link) else AnswerOutcome.ProofFailed(why)
    }

    // --- the sealed form (#286) ---

    /**
     * Dialer, first stage of the sealed form: write our `KEY_SHARE` ([ephemeral]'s, fresh per attempt) and flush, then
     * read exactly one record, which must be the responder's. Nothing else is written before it. The caller bounds this
     * read on its own (contract §4) and then runs [dialSealed] or [dialTagSealed] on the socket under a fresh bound.
     * Reads the record's tag alone first, so another record where the key share belongs is [SealOpened.Failed] without
     * reading on, and an EOF or timeout anywhere is [SealOpened.NoReply].
     */
    @Suppress("ReturnCount") // one early return per outcome, in the order the bytes arrive
    fun openSeal(
        link: LinkSocket,
        ephemeral: LinkSeal.Ephemeral = LinkSeal.Ephemeral.fresh(),
    ): SealOpened {
        try {
            link.output.write(LinkFraming.encodeKeyShare(ephemeral.pub))
            link.output.flush()
        } catch (_: IOException) {
            return SealOpened.NoReply
        }
        val tag =
            try {
                link.input.read()
            } catch (_: IOException) {
                return SealOpened.NoReply
            }
        if (tag == -1) return SealOpened.NoReply
        if (tag !=
            LinkFraming.Type.KEY_SHARE.tag
                .toInt()
        ) {
            return SealOpened.Failed("${recordName(tag)} where a key share belongs")
        }
        val record =
            try {
                LinkFraming.readAfterTag(tag, link.input)
            } catch (e: SealFramingException) {
                return SealOpened.Failed(e.message ?: "key share length")
            } catch (_: IOException) {
                return SealOpened.NoReply
            }
        val keys =
            try {
                LinkSeal.derive(LinkProof.Role.DIALER, ephemeral, record.payload)
            } catch (e: SealException) {
                return SealOpened.Failed(e.message ?: "rejected key share")
            }
        return SealOpened.Sealed(SealedLinkSocket(link, keys, LinkProof.Role.DIALER))
    }

    /**
     * Dialer, sealed form, by id: [dialPlain]'s exchange inside the seal [link] opened. Our HELLO goes sealed; the reply must
     * be the first inner record, a HELLO carrying both bits and a valid nonce, naming [expectedId]; its PROOF must verify
     * over the v2 transcript, and only then is ours written. Nothing where the reply belongs is [DialOutcome.NoReply] (the
     * responder refused after its `KEY_SHARE`); a raw fault on the PROOF read is [DialOutcome.ProofFailed] with
     * [WHY_UNREADABLE] or [WHY_EOF], as on a plain link.
     */
    fun dialSealed(
        link: SealedLinkSocket,
        prover: LinkProver,
        expectedId: String,
        nonce: ByteArray = freshNonce(),
        told: ByteArray? = null,
        addressed: ByteArray? = null,
    ): DialOutcome {
        val helloD = Protocol.hello(prover.nodeId, nonce, told, addressed).encodeToByteArray()
        return sealedDial(link, prover, helloD, expectedId)
    }

    /**
     * Dialer, sealed form, by tag: [dialTagPlain]'s exchange inside the seal, as [dialSealed]. A sealed reply always offers a
     * proof, so [WHY_UNPROVEN] never comes back from here: a reply without the bit is [DialOutcome.SealFailed]. The caller
     * keys every failure on [tag] and the address, never on an id a reply names.
     */
    fun dialTagSealed(
        link: SealedLinkSocket,
        prover: LinkProver,
        tag: ByteArray,
        told: ByteArray? = null,
        nonce: ByteArray = freshNonce(),
    ): DialOutcome {
        val helloD = Protocol.hello(prover.nodeId, nonce, told, addressed = tag).encodeToByteArray()
        return sealedDial(link, prover, helloD, expectedId = null)
    }

    @Suppress("ReturnCount") // one early return per outcome, in the order the records arrive
    private fun sealedDial(
        link: SealedLinkSocket,
        prover: LinkProver,
        helloD: ByteArray,
        expectedId: String?,
    ): DialOutcome {
        require(link.role == LinkProof.Role.DIALER) { "a dial runs on the dialer's seal" }
        if (!sealedWrite(link) { LinkFraming.write(it, LinkFraming.Type.HELLO, helloD) }) return DialOutcome.NoReply
        val reply =
            when (val read = readSealedHello(link)) {
                is SealedHello.Hello -> read.hello
                is SealedHello.SealFailed -> return DialOutcome.SealFailed(read.why)
                SealedHello.None -> return DialOutcome.NoReply
            }
        val wrongPeer = if (expectedId == null) reply.wire.nodeId == prover.nodeId else reply.wire.nodeId != expectedId
        if (wrongPeer) return DialOutcome.WrongPeer(reply.wire)
        when (val proof = readSealedProof(link, reply.wire.nodeId, LinkProof.Role.RESPONDER, helloD, reply.payload)) {
            ProofRead.Holds -> Unit
            is ProofRead.Fails -> return DialOutcome.ProofFailed(reply.wire, proof.why)
            is ProofRead.BreaksSeal -> return DialOutcome.SealFailed(proof.why)
        }
        val ours = prover.sealedProof(LinkProof.Role.DIALER, link.binding, helloD, reply.payload)
        if (!sealedWrite(link) { LinkFraming.write(it, LinkFraming.Type.PROOF, ours) }) return DialOutcome.NoReply
        return DialOutcome.Linked(reply.wire, proven = true, link = link)
    }

    /**
     * Responder: read the first record and tell the two forms apart (ADR 004's accept-any responder can't know which it
     * accepted). A HELLO is [Opening.Plain]. A `KEY_SHARE` is answered at once with ours ([ephemeral]'s, fresh per
     * attempt), before any admission decision, and is [Opening.Sealed]; one of the wrong length or a refused point is
     * [Opening.SealFailed] with nothing written. Anything else, an EOF, a raw error or a timeout is [Opening.None].
     */
    @Suppress("ReturnCount") // one early return per outcome, in the order the bytes arrive
    fun receiveOpening(
        link: LinkSocket,
        ephemeral: LinkSeal.Ephemeral = LinkSeal.Ephemeral.fresh(),
    ): Opening {
        val tag =
            try {
                link.input.read()
            } catch (_: IOException) {
                return Opening.None
            }
        if (tag ==
            LinkFraming.Type.HELLO.tag
                .toInt()
        ) {
            val record = runCatching { LinkFraming.readAfterTag(tag, link.input) }.getOrNull() ?: return Opening.None
            val wire = Protocol.parse(record.payload.decodeToString()) ?: return Opening.None
            return Opening.Plain(ReceivedHello(record.payload, wire))
        }
        if (tag !=
            LinkFraming.Type.KEY_SHARE.tag
                .toInt()
        ) {
            return Opening.None
        }
        val record =
            try {
                LinkFraming.readAfterTag(tag, link.input)
            } catch (e: SealFramingException) {
                return Opening.SealFailed(e.message ?: "key share length")
            } catch (_: IOException) {
                return Opening.None
            }
        val keys =
            try {
                LinkSeal.derive(LinkProof.Role.RESPONDER, ephemeral, record.payload)
            } catch (e: SealException) {
                return Opening.SealFailed(e.message ?: "rejected key share")
            }
        try {
            link.output.write(LinkFraming.encodeKeyShare(ephemeral.pub))
            link.output.flush()
        } catch (_: IOException) {
            return Opening.None
        }
        return Opening.Sealed(SealedLinkSocket(link, keys, LinkProof.Role.RESPONDER))
    }

    /**
     * Responder, sealed form: the dialer's HELLO, which must be the first inner record, name a canonical id and carry both
     * bits and a valid nonce. The caller bounds this read on its own, then runs today's admission on the HELLO.
     */
    fun receiveSealedHello(link: SealedLinkSocket): SealedHello {
        require(link.role == LinkProof.Role.RESPONDER) { "the dialer's HELLO is read on the responder's seal" }
        return readSealedHello(link)
    }

    /**
     * Responder, sealed form, once its plane kept [dialer]: our HELLO and our PROOF over the v2 transcript in one sealed
     * write, then the dialer's PROOF, which must be the next inner record and verify. A refusal is the caller's close,
     * with no reply, after our `KEY_SHARE` (contract §4).
     */
    fun answerSealed(
        link: SealedLinkSocket,
        prover: LinkProver,
        dialer: ReceivedHello,
        nonce: ByteArray = freshNonce(),
        told: ByteArray? = null,
    ): AnswerOutcome {
        require(link.role == LinkProof.Role.RESPONDER) { "an answer runs on the responder's seal" }
        if (!dialer.sealable) return AnswerOutcome.SealFailed(WHY_UNSEALABLE)
        val helloR = Protocol.hello(prover.nodeId, nonce, told).encodeToByteArray()
        val proof = prover.sealedProof(LinkProof.Role.RESPONDER, link.binding, dialer.payload, helloR)
        // One write and one flush: the reply and the proof ride one frame.
        val bytes = LinkFraming.encode(LinkFraming.Type.HELLO, helloR) + LinkFraming.encode(LinkFraming.Type.PROOF, proof)
        if (!sealedWrite(link) { it.write(bytes) }) return AnswerOutcome.WriteFailed
        return when (val read = readSealedProof(link, dialer.wire.nodeId, LinkProof.Role.DIALER, dialer.payload, helloR)) {
            ProofRead.Holds -> AnswerOutcome.Linked(proven = true, link = link)
            is ProofRead.Fails -> AnswerOutcome.ProofFailed(read.why)
            is ProofRead.BreaksSeal -> AnswerOutcome.SealFailed(read.why)
        }
    }

    /** One inner record read inside a seal, its failure sorted: below the seal (no reply), or bytes that were wrong. */
    private sealed interface InnerRead {
        class Record(
            val message: LinkFraming.Message,
        ) : InnerRead

        /** A clean EOF at a frame boundary. */
        data object End : InnerRead

        /** A raw read error, a timeout, an EOF mid-frame or our close: never a failed seal. */
        data object Raw : InnerRead

        /** Bytes that arrived and were wrong: a frame that won't open, an inner `KEY_SHARE` / `SEALED`, inner garbage. */
        data class BreaksSeal(
            val why: String,
        ) : InnerRead
    }

    private fun readInner(link: SealedLinkSocket): InnerRead {
        val message =
            try {
                LinkFraming.read(link.input)
            } catch (e: SealException) {
                return InnerRead.BreaksSeal(e.message ?: "sealed frame")
            } catch (e: SealFramingException) {
                return InnerRead.BreaksSeal("inner ${e.message}")
            } catch (e: IOException) {
                // From below the seal the socket marked itself ended; otherwise it came from bytes that opened.
                return if (link.endedBelow) InnerRead.Raw else InnerRead.BreaksSeal("inner record: ${e.message}")
            } ?: return InnerRead.End
        if (message.type == LinkFraming.Type.KEY_SHARE || message.type == LinkFraming.Type.SEALED) {
            return InnerRead.BreaksSeal("inner ${message.type.name.lowercase()} record")
        }
        return InnerRead.Record(message)
    }

    private fun readSealedHello(link: SealedLinkSocket): SealedHello {
        val record =
            when (val read = readInner(link)) {
                is InnerRead.Record -> read.message
                InnerRead.End, InnerRead.Raw -> return SealedHello.None
                is InnerRead.BreaksSeal -> return SealedHello.SealFailed(read.why)
            }
        if (record.type != LinkFraming.Type.HELLO) return SealedHello.SealFailed("${record.type.name.lowercase()} record before the hello")
        val wire = Protocol.parse(record.payload.decodeToString()) ?: return SealedHello.SealFailed("hello names no canonical id")
        val hello = ReceivedHello(record.payload, wire)
        return if (hello.sealable) SealedHello.Hello(hello) else SealedHello.SealFailed(WHY_UNSEALABLE)
    }

    private sealed interface ProofRead {
        data object Holds : ProofRead

        data class Fails(
            val why: String,
        ) : ProofRead

        data class BreaksSeal(
            val why: String,
        ) : ProofRead
    }

    /** The PROOF that must be the next inner record, verified over the v2 transcript as [signerId]'s in [role]. */
    private fun readSealedProof(
        link: SealedLinkSocket,
        signerId: String,
        role: LinkProof.Role,
        helloD: ByteArray,
        helloR: ByteArray,
    ): ProofRead {
        val record =
            when (val read = readInner(link)) {
                is InnerRead.Record -> read.message
                InnerRead.End -> return ProofRead.Fails(WHY_EOF)
                InnerRead.Raw -> return ProofRead.Fails(WHY_UNREADABLE)
                is InnerRead.BreaksSeal -> return ProofRead.BreaksSeal(read.why)
            }
        if (record.type != LinkFraming.Type.PROOF) return ProofRead.Fails("${record.type.name.lowercase()} record before the proof")
        val why = LinkProof.verifySealed(record.payload, signerId, role, link.binding, helloD, helloR)
        return if (why == null) ProofRead.Holds else ProofRead.Fails(why.name.lowercase())
    }

    /** Writes through the seal and flushes; false when the socket below refused (the link is dead). */
    private inline fun sealedWrite(
        link: SealedLinkSocket,
        block: (OutputStream) -> Unit,
    ): Boolean =
        try {
            block(link.output)
            link.output.flush()
            true
        } catch (_: IOException) {
            false
        }

    private inline fun orNoReply(block: () -> DialOutcome): DialOutcome =
        try {
            block()
        } catch (_: IOException) {
            DialOutcome.NoReply
        }

    private fun recordName(tag: Int): String =
        LinkFraming.Type
            .fromTag(tag.toByte())
            ?.name
            ?.lowercase() ?: "type-$tag record"

    /** This build seals links: its HELLO carries [Protocol.CAP_LINK_SEAL]. */
    private val sealsLinks: Boolean = Protocol.LOCAL_CAPABILITIES and Protocol.CAP_LINK_SEAL != 0L

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
     * A [DialOutcome.ProofFailed] / [AnswerOutcome.ProofFailed] `why` for a PROOF read that ended in an error: most often the
     * caller's read bound cut it (the watchdog or `soTimeout`), so the peer may be gone, not lying.
     */
    const val WHY_UNREADABLE = "unreadable"

    /** The `why` for a link the peer closed where its PROOF belonged: it was there to close it, and is gone now. */
    const val WHY_EOF = "eof"

    /**
     * The `why` for a tag dial ([dialTagPlain]) whose reply carries no [Protocol.CAP_LINK_PROOF]: every build that airs a tag
     * proves itself, so this is not that advert's phone. Not transient: it is evidence about who answered.
     */
    const val WHY_UNPROVEN = "unproven"

    /** The `why` for a HELLO inside a seal without [Protocol.CAP_LINK_PROOF], [Protocol.CAP_LINK_SEAL] and a valid nonce. */
    const val WHY_UNSEALABLE = "hello without the seal bits or a nonce"

    /**
     * Whether a proof failure's [why] says only that the peer went quiet or away mid-handshake ([WHY_UNREADABLE],
     * [WHY_EOF]), not that it answered with a wrong, missing or misplaced proof: a fault to retry as contention, never
     * evidence about the peer.
     */
    fun isTransient(why: String): Boolean = why == WHY_UNREADABLE || why == WHY_EOF

    private val random = SecureRandom()
}
