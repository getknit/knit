package app.getknit.knit.mesh.wifiaware

import app.getknit.knit.mesh.link.LinkHandshake
import app.getknit.knit.mesh.link.LinkHandshake.AnswerOutcome
import app.getknit.knit.mesh.link.LinkHandshake.DialOutcome
import app.getknit.knit.mesh.link.LinkHandshake.Opening
import app.getknit.knit.mesh.link.LinkHandshake.SealOpened
import app.getknit.knit.mesh.link.LinkHandshake.SealedHello
import app.getknit.knit.mesh.protocol.Protocol

/**
 * The decisions `WifiAwareTransport` takes around [LinkHandshake] on an NDP socket (#286, ADR 2026-11.8mhs, on top of
 * ADR 2026-11.264w): which form a dial opens with, what each dial outcome costs the peer, and, for each way an accepted
 * socket is refused, whether the dialer is known to hold its NDP up right now. Pure (no Android): the transport does the
 * IO, tells [LinkClaims][app.getknit.knit.mesh.link.LinkClaims] and the counters, and closes; this says which.
 *
 * **The form** ([opensSealed]) reads the caps of the SSI the dial is made from: the one place the SSI's caps decide
 * anything about a handshake (contract §2). They are unauthenticated and can be stale across an upgrade, which costs one
 * dial either way: a sealed dial toward a build that can't seal reads no reply (it throws on tag 10 and closes), a plain
 * dial toward a build that seals is refused (§7). Whether the link proves anything still reads the reply's caps.
 *
 * **A dial's cost** ([dialed], [opened]). Nothing that a responder's refusal or a busy NDI can produce leaves the flat
 * contention retry: a responder that refuses closes with no reply, before or after its `KEY_SHARE`, and so does one whose
 * accept loop turned us away at `beginAccept`. Bytes that arrived and were wrong — a failed seal, a non-transient failed
 * proof, an unproven reply to a proof-capable id, a §7 reply — hold the peer out of the sync gates on
 * [NanClaimRefusalPolicy]'s growing wait instead: the same peer answers the same way next time, and its lift (a fresh
 * discovery handle) is what an upgrade or a downgrade produces. A seal failure never feeds `LinkClaims` (it is not a
 * proof failure) and is counted once, here, before registration arms the live link's count.
 *
 * **The dialer alive** ([Refusal.dialerAlive]) decides whether `refuseAccept` may recycle the responder request in place:
 * an unregister while the refused NDP is alive is ghost-proof, one at zero NDPs is the `role=1 state=104` ghost (7e457907,
 * 724a3fed). Alive is a refusal on bytes the dialer just sent while it blocks on its next read: our §7 refusal, a held
 * key share it waits on, a sealed HELLO we would not admit or that broke the seal, our reply unwritable, a proof that came
 * and failed. Not alive is anything ended by its absence — no first record, no sealed HELLO, an unreadable PROOF — where
 * its `failConnect` may have released the NDP already. Tests: [app.getknit.knit.NanHandshakePolicyTest].
 */
internal object NanHandshakePolicy {
    /** Whether a dial made from an SSI carrying [advertCaps] opens sealed: the SSI says it seals, and so does this build. */
    fun opensSealed(
        advertCaps: Long,
        localCaps: Long = Protocol.LOCAL_CAPABILITIES,
    ): Boolean = advertCaps and Protocol.CAP_LINK_SEAL != 0L && localCaps and Protocol.CAP_LINK_SEAL != 0L

    /** What a dial's handshake costs, and what the transport tells whom. */
    sealed interface DialVerdict {
        /** Register [link] (the sealed socket on a sealed link, never the raw one below it). */
        data class Register(
            val linked: DialOutcome.Linked,
        ) : DialVerdict

        /**
         * The flat contention retry: no reply, the wrong peer, a write that failed, a responder gone quiet. [proofFailed]
         * is a transient proof failure's `why`, still told to `LinkClaims` as today.
         */
        data class Retry(
            val proofFailed: String? = null,
        ) : DialVerdict

        /** Held on the growing wait ([NanClaimRefusalPolicy.refused]) for [why]; [hold] says what else it costs. */
        data class Hold(
            val hold: HoldKind,
            val why: String,
        ) : DialVerdict
    }

    /** Why a dial is held, each with its own tally: one per link. */
    enum class HoldKind {
        /** A proof that came and failed (non-transient): `LinkClaims.proofFailed`. */
        PROOF_FAILED,

        /** A legacy reply for an id that must prove itself (R1): `LinkClaims.refused`. */
        UNPROVEN_REPLY,

        /** Bytes that broke the seal: `linkSealFailed`, never `LinkClaims`. */
        SEAL_FAILED,

        /** A plain reply that carries the seal bit toward our HELLO, which does too (§7): `linkSealRefused`. */
        SEAL_REFUSED,
    }

    /** The first stage of a sealed dial: null when both key shares crossed and the sealed dial runs next. */
    fun opened(outcome: SealOpened): DialVerdict? =
        when (outcome) {
            is SealOpened.Sealed -> null

            // Nothing where the key share belongs: a responder that refused before reading, or a build that threw on tag
            // 10 (a stale SSI), or a busy accept loop. Never a failed seal (contract revision 1).
            SealOpened.NoReply -> DialVerdict.Retry()

            is SealOpened.Failed -> DialVerdict.Hold(HoldKind.SEAL_FAILED, outcome.why)
        }

    /**
     * The dial's outcome, sealed or plain. [proofCapable] is whether the dialed id must prove itself (`LinkClaims`), asked
     * only for a legacy reply.
     */
    fun dialed(
        outcome: DialOutcome,
        proofCapable: () -> Boolean,
    ): DialVerdict =
        when (outcome) {
            // No reply (a responder that refused us, before or after its key share, reads exactly like contention), or a
            // wrong peer: the flat retry, as before the seal.
            DialOutcome.NoReply, is DialOutcome.WrongPeer -> {
                DialVerdict.Retry()
            }

            is DialOutcome.ProofFailed -> {
                if (LinkHandshake.isTransient(outcome.why)) {
                    DialVerdict.Retry(proofFailed = outcome.why)
                } else {
                    DialVerdict.Hold(HoldKind.PROOF_FAILED, outcome.why)
                }
            }

            is DialOutcome.SealFailed -> {
                DialVerdict.Hold(HoldKind.SEAL_FAILED, outcome.why)
            }

            is DialOutcome.SealRefused -> {
                DialVerdict.Hold(HoldKind.SEAL_REFUSED, "plain reply claiming ${outcome.reply.nodeId} carries the seal bit")
            }

            is DialOutcome.Linked -> {
                if (!outcome.proven && proofCapable()) {
                    DialVerdict.Hold(HoldKind.UNPROVEN_REPLY, "unproven reply")
                } else {
                    DialVerdict.Register(outcome)
                }
            }
        }

    /** What a refused accept counts, beside the close. */
    enum class Tally {
        NONE,

        /** `linkSealFailed`. */
        SEAL_FAILED,

        /** `linkSealRefused`, with one log line naming the claimed id. */
        SEAL_REFUSED,
    }

    /**
     * A refused accept: nothing registered, a held link stays. [dialerAlive] when the dialer is known to hold its NDP up
     * right now; [proofFailed] a failed proof's `why` for `LinkClaims`; [why] for the log.
     */
    data class Refusal(
        val dialerAlive: Boolean,
        val tally: Tally = Tally.NONE,
        val proofFailed: String? = null,
        val why: String? = null,
    )

    /** The first record's verdict: null when the handshake goes on ([Opening.Plain], [Opening.Sealed]). */
    fun refusal(opening: Opening): Refusal? =
        when (opening) {
            is Opening.Plain, is Opening.Sealed -> null

            // No record within the soTimeout, an EOF, an unreadable or unknown first record: the dialer may be gone.
            Opening.None -> Refusal(dialerAlive = false)

            // A key share we refused: nothing was written, and the dialer blocks on reading ours.
            is Opening.SealFailed -> Refusal(dialerAlive = true, tally = Tally.SEAL_FAILED, why = opening.why)
        }

    /** §7 on a plain opening ([LinkHandshake.refusesPlain]): the dialer blocks on its reply read. */
    val PLAIN_REFUSED: Refusal = Refusal(dialerAlive = true, tally = Tally.SEAL_REFUSED, why = "plain hello with the seal bit")

    /** A HELLO our admission refused (R1, the tie-break, the held link): the dialer blocks on its reply read. */
    val NOT_ADMITTED: Refusal = Refusal(dialerAlive = true)

    /** The dialer's sealed HELLO: null when it came ([SealedHello.Hello]). */
    fun refusal(hello: SealedHello): Refusal? =
        when (hello) {
            is SealedHello.Hello -> null

            // It has our key share and writes its HELLO at once: nothing within the soTimeout means it may be gone.
            SealedHello.None -> Refusal(dialerAlive = false)

            // A frame that came and would not open, or a HELLO a seal can't carry: it blocks on our sealed reply.
            is SealedHello.SealFailed -> Refusal(dialerAlive = true, tally = Tally.SEAL_FAILED, why = hello.why)
        }

    /** Our answer, sealed or plain: null when the link is up ([AnswerOutcome.Linked]). */
    fun refusal(answer: AnswerOutcome): Refusal? =
        when (answer) {
            is AnswerOutcome.Linked -> {
                null
            }

            AnswerOutcome.WriteFailed -> {
                Refusal(dialerAlive = true)
            }

            // An eof (the dialer's FIN, its release grace holding the NDP), a misplaced record or a failed verify say the
            // dialer was there a moment ago; an unreadable PROOF (our soTimeout cut it, a reset, an EOF mid-frame) does not.
            is AnswerOutcome.ProofFailed -> {
                Refusal(dialerAlive = answer.why != LinkHandshake.WHY_UNREADABLE, proofFailed = answer.why, why = answer.why)
            }

            // Its PROOF frame came and would not open, or its inner record broke the seal: bytes that arrived.
            is AnswerOutcome.SealFailed -> {
                Refusal(dialerAlive = true, tally = Tally.SEAL_FAILED, why = answer.why)
            }

            // Only for a caller that skipped refusesPlain; nothing was written.
            AnswerOutcome.SealRefused -> {
                PLAIN_REFUSED
            }
        }
}
