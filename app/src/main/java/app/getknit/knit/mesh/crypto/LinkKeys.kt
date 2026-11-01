package app.getknit.knit.mesh.crypto

import app.getknit.knit.identity.NodeId
import app.getknit.knit.mesh.protocol.LinkSignal
import com.google.crypto.tink.subtle.Hkdf
import java.security.SecureRandom

/**
 * The two direction keys of one direct link's lane (ADR 2026-10.a5yj, experimental), derived from what the OFFER and
 * the ANSWER carried. Pure, Tink's HKDF-SHA256 as `call/CallLane`'s keys are:
 *
 * ```
 * key(dir) = HKDF(ikm  = kOffer (32) ‖ kAnswer (32),
 *                 salt = linkId (16),
 *                 info = label ‖ offererId (16) ‖ answererId (16),
 *                 L    = 32)
 * label    = "knit/link/v1/lane/o2a" | "knit/link/v1/lane/a2o"
 * ```
 *
 * Why this shape (the design pass on the spike's plan):
 *  - **Only the pair can derive it.** Each k rides inside a [LinkSignal] sealed on the pair's DM ratchet, so a lane key
 *    is as secret as the two seals that carried its halves, and the spool that cast them sees neither.
 *  - **No root and no scope id.** A drain window can hold two pairwise roots, and the two ends need not agree on which
 *    one cast the signal; fresh k's on both sides make the keys fresh per attempt without naming either. A lane key
 *    therefore proves nothing about a call, a transfer, a scope or the session: its label is used for nothing else.
 *  - **Bound to the roles.** Both ids, ordered offerer then answerer for both directions, and the direction in the
 *    label: the offerer's send key is the answerer's receive key and nothing else, and swapping who offered changes
 *    every key.
 *
 * The k's and the keys are memory-only, for one attempt. A lane's GCM nonce is its counter (`LinkLane`), so a (key,
 * counter) pair must never be sealed twice: the answerer draws a fresh k for every ANSWER it sends, and one link id
 * has one lane on each side. A re-punch is a new link id and two new k's, never these keys again.
 */
object LinkKeys {
    /** Which end of the link this phone is: it cast the OFFER, or it cast the ANSWER. */
    enum class Role { OFFERER, ANSWERER }

    /** The bytes of each k and of each derived key. */
    const val KEY_BYTES = LinkSignal.KEY_BYTES

    private const val HKDF_MAC = "HMACSHA256"

    /**
     * Domain separation: disjoint from `knit/call/v1/…`, `knit/xfer/v2/…`, `knit/scope/v1/…`, `knit/dm/v2/…`. The
     * `knit/link/v1/` prefix is shared with `knit/link/v1/proof` ([LinkProof]), a signature context rather than KDF info,
     * so the two never meet (ADR 2026-11.264w).
     */
    private val OFFERER_TO_ANSWERER = "knit/link/v1/lane/o2a".toByteArray(Charsets.US_ASCII)
    private val ANSWERER_TO_OFFERER = "knit/link/v1/lane/a2o".toByteArray(Charsets.US_ASCII)

    /** A fresh k for one OFFER or one ANSWER. Never reused for a second signal. */
    fun fresh(rng: SecureRandom = SecureRandom()): ByteArray = ByteArray(KEY_BYTES).also(rng::nextBytes)

    /**
     * This phone's `(send, recv)` keys for the link [linkId], where it plays [role]: [kOffer] and [offererId] are the
     * OFFER's, [kAnswer] and [answererId] the ANSWER's, the same four on both ends. Throws on a k or a link id of the
     * wrong size, a node id that is not canonical, or one node on both ends: the inbound path refuses such a signal
     * before it is handed on (`LinkSignals.decode`), so a throw here is a caller's bug.
     */
    fun lane(
        role: Role,
        kOffer: ByteArray,
        kAnswer: ByteArray,
        linkId: ByteArray,
        offererId: String,
        answererId: String,
    ): Pair<ByteArray, ByteArray> {
        require(kOffer.size == KEY_BYTES && kAnswer.size == KEY_BYTES) { "a link's k is $KEY_BYTES bytes" }
        require(linkId.size == LinkSignal.ID_BYTES) { "a link id is ${LinkSignal.ID_BYTES} bytes" }
        require(offererId != answererId) { "a link has two ends" }
        val offerer = requireNotNull(NodeId.toBytesOrNull(offererId)) { "the offerer's node id is not canonical" }
        val answerer = requireNotNull(NodeId.toBytesOrNull(answererId)) { "the answerer's node id is not canonical" }
        val ikm = kOffer + kAnswer
        val o2a = Hkdf.computeHkdf(HKDF_MAC, ikm, linkId, OFFERER_TO_ANSWERER + offerer + answerer, KEY_BYTES)
        val a2o = Hkdf.computeHkdf(HKDF_MAC, ikm, linkId, ANSWERER_TO_OFFERER + offerer + answerer, KEY_BYTES)
        return when (role) {
            Role.OFFERER -> o2a to a2o
            Role.ANSWERER -> a2o to o2a
        }
    }
}
