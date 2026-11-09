package app.getknit.knit.mesh.crypto

import app.getknit.knit.identity.NodeId
import java.nio.ByteBuffer

/**
 * The proof a Bluetooth or Wi-Fi Aware link's peer gives of the node id its HELLO claims (ADR 2026-11.264w, #232;
 * the iOS port mirrors it byte for byte, knit-ios #25). Pure: Tink's Ed25519 through [MessageCrypto] and
 * [PublicKeyBundle], no Android, so it runs unchanged on the JVM.
 *
 * Each side signs, with its identity key, one transcript of both HELLOs as they crossed the link:
 *
 * ```
 * transcript = "knit/link/v1/proof" (18 ASCII) ‖ role (1) ‖ u32be(len hD) ‖ hD ‖ u32be(len hR) ‖ hR
 * role       = 0x01 the signer dialed | 0x02 the signer answered
 * payload    = sigPub (32) ‖ hpkePub (32) ‖ Ed25519 signature (64)          = 128 bytes
 * ```
 *
 * Why this shape:
 *  - **Both HELLOs, in one order.** `hD` is always the dialer's HELLO and `hR` the responder's, whoever signs, and
 *    each carries a fresh nonce, so a proof is bound to this link's exchange and replays on no other. The bytes are
 *    the records as received (`LinkHandshake.ReceivedHello.payload`), never a re-encoding, so the two ends sign the
 *    same bytes without agreeing on a canonical form.
 *  - **The role in the transcript.** The two proofs of one link sign the same HELLOs; the role byte keeps the
 *    dialer's proof from being reflected back as the responder's.
 *  - **The keys in the payload.** The verifier may never have seen the peer: the id is the hash of the bundle
 *    rebuilt from the two raw keys ([NodeId.fromPublicKeyBundle], as `ContactCard.decodeSigned` checks a card), so a
 *    proof is self-certifying and needs no lookup.
 *  - **Domain separation from the other three things the identity key signs.** A frame's signed CBOR
 *    (`WireEnvelope.signed`, a `RelayEnvelope` map: major type 5) can't be a transcript, which starts `k` (0x6B, a
 *    CBOR text head), so neither decodes as the other. A contact card signs `knit/card/v1 ‖ body` and a v2 signed
 *    prekey `knit/dm/v2/spk ‖ id ‖ pub`; both labels part from this one at byte 5 (`c`, `d` against `l`), so no
 *    label is a prefix of another and no message of one kind is a message of another. The `knit/link/v1/` prefix it
 *    shares with [LinkKeys]' HKDF lane labels is harmless: those are KDF info, never signed.
 *
 * **On a sealed link** (#286, [LinkSeal]) each side signs the v2 transcript instead ([sealedTranscript]):
 *
 * ```
 * transcript = "knit/link/v2/proof" (18 ASCII) ‖ role (1) ‖ eD (32) ‖ eR (32) ‖ u32be(len hD) ‖ hD ‖ u32be(len hR) ‖ hR
 * ```
 *
 * where `eD` and `eR` are the two `KEY_SHARE` payloads, the dialer's first. Signing both ephemeral keys is what keeps a
 * relay out of the middle: it can't compute the keys of a link it didn't open, and swapping its own key share in breaks
 * a signature. The label keeps the two forms apart, so a v1 signature never verifies on a sealed link and a v2 one never
 * on a plain link: a relay can't open the sealed form toward one peer and the plain form toward the other. It starts `k`
 * as v1 does (never a CBOR map) and parts from v1 at byte 11. The payload is unchanged. Plain links keep the v1
 * transcript byte for byte: v2.11.0 peers depend on it.
 */
object LinkProof {
    const val LABEL = "knit/link/v1/proof"

    /** The sealed link's label (#286). Its own transcript, [sealedTranscript]; never a flag on v1's. */
    const val LABEL_SEALED = "knit/link/v2/proof"

    /** An Ed25519 signature (RFC 8032). */
    private const val SIGNATURE_BYTES = 64

    /** sigPub (32) ‖ hpkePub (32) ‖ signature (64). */
    const val PAYLOAD_BYTES = 2 * PublicKeyBundle.RAW_KEY_BYTES + SIGNATURE_BYTES

    private val LABEL_BYTES = LABEL.toByteArray(Charsets.US_ASCII)
    private val LABEL_SEALED_BYTES = LABEL_SEALED.toByteArray(Charsets.US_ASCII)

    /** Which end of the link the signer is. The byte is wire: it is in the transcript. */
    enum class Role(
        val byte: Byte,
    ) {
        DIALER(1),
        RESPONDER(2),
    }

    /** label ‖ role ‖ u32be(len helloDialer) ‖ helloDialer ‖ u32be(len helloResponder) ‖ helloResponder */
    fun transcript(
        role: Role,
        helloDialer: ByteArray,
        helloResponder: ByteArray,
    ): ByteArray =
        LABEL_BYTES + byteArrayOf(role.byte) + u32be(helloDialer.size) + helloDialer + u32be(helloResponder.size) + helloResponder

    /**
     * The sealed link's transcript (#286): `"knit/link/v2/proof" ‖ role ‖ eD ‖ eR ‖ u32be(len helloDialer) ‖ helloDialer ‖
     * u32be(len helloResponder) ‖ helloResponder`, [binding] carrying both key shares as they crossed the link.
     */
    fun sealedTranscript(
        role: Role,
        binding: LinkSeal.Binding,
        helloDialer: ByteArray,
        helloResponder: ByteArray,
    ): ByteArray =
        LABEL_SEALED_BYTES + byteArrayOf(role.byte) + binding.eD + binding.eR + u32be(helloDialer.size) + helloDialer +
            u32be(helloResponder.size) + helloResponder

    /** Why a proof failed. Every one fails the handshake; the plane decides what to do with the link (`LinkClaims`). */
    enum class Failure {
        /** The payload is not exactly [PAYLOAD_BYTES]. */
        LENGTH,

        /** The two raw keys don't make a bundle. Defensive: [PublicKeyBundle.fromRaw] refuses only a wrong length. */
        KEYS,

        /** The keys hash to another node id than the HELLO claimed. */
        ID_MISMATCH,

        /** The signature does not hold over [role]'s transcript of these two HELLOs. */
        SIGNATURE,
    }

    /** null when [payload] proves [claimedId] signed [role]'s transcript; else why not. Never throws. */
    fun verify(
        payload: ByteArray,
        claimedId: String,
        role: Role,
        helloDialer: ByteArray,
        helloResponder: ByteArray,
    ): Failure? = verifyOver(payload, claimedId) { transcript(role, helloDialer, helloResponder) }

    /** As [verify], over the sealed link's transcript ([sealedTranscript]) for [binding]. Never throws. */
    fun verifySealed(
        payload: ByteArray,
        claimedId: String,
        role: Role,
        binding: LinkSeal.Binding,
        helloDialer: ByteArray,
        helloResponder: ByteArray,
    ): Failure? = verifyOver(payload, claimedId) { sealedTranscript(role, binding, helloDialer, helloResponder) }

    /** The length, then the id, then the signature over [transcript]'s bytes, as both transcripts verify. */
    private inline fun verifyOver(
        payload: ByteArray,
        claimedId: String,
        transcript: () -> ByteArray,
    ): Failure? {
        if (payload.size != PAYLOAD_BYTES) return Failure.LENGTH
        val keyBytes = PublicKeyBundle.RAW_KEY_BYTES
        val bundle =
            runCatching {
                PublicKeyBundle.fromRaw(
                    sigPub = payload.copyOfRange(0, keyBytes),
                    hpkePub = payload.copyOfRange(keyBytes, 2 * keyBytes),
                )
            }.getOrNull() ?: return Failure.KEYS
        val id = runCatching { NodeId.fromPublicKeyBundle(bundle.encoded) }.getOrNull()
        if (id != claimedId) return Failure.ID_MISMATCH
        val signature = payload.copyOfRange(2 * keyBytes, PAYLOAD_BYTES)
        // MessageCrypto.verify already maps a Tink refusal (a bad point, a bad signature) to false; the outer guard
        // keeps the "never throws" contract if the transcript build itself ever could.
        val holds =
            runCatching {
                MessageCrypto.verify(
                    bundle,
                    signature,
                    transcript(),
                )
            }.getOrDefault(false)
        return if (holds) null else Failure.SIGNATURE
    }

    /** A ByteBuffer is big-endian unless told otherwise. */
    private fun u32be(n: Int): ByteArray = ByteBuffer.allocate(Int.SIZE_BYTES).putInt(n).array()
}

/** This device's half: its public bundle's two raw keys and its identity signer (`MessageCrypto::signRaw`). */
class LinkProver(
    bundle: PublicKeyBundle,
    private val signRaw: (ByteArray) -> ByteArray,
) {
    val nodeId: String = NodeId.fromPublicKeyBundle(bundle.encoded)

    private val keys: ByteArray = bundle.sigPublicKey() + bundle.dhPublicKey()

    /** sigPub(32) ‖ hpkePub(32) ‖ Ed25519 signature(64) over transcript(role, helloDialer, helloResponder) */
    fun proof(
        role: LinkProof.Role,
        helloDialer: ByteArray,
        helloResponder: ByteArray,
    ): ByteArray = keys + signRaw(LinkProof.transcript(role, helloDialer, helloResponder))

    /** sigPub(32) ‖ hpkePub(32) ‖ Ed25519 signature(64) over the sealed link's transcript ([LinkProof.sealedTranscript]). */
    fun sealedProof(
        role: LinkProof.Role,
        binding: LinkSeal.Binding,
        helloDialer: ByteArray,
        helloResponder: ByteArray,
    ): ByteArray = keys + signRaw(LinkProof.sealedTranscript(role, binding, helloDialer, helloResponder))
}
