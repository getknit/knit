package app.getknit.knit.mesh.spool

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * The HTTP seam under [TicketRefresher] (experimental, docs/VOICE_CALLS.md §2.6, ADR 2026-10.3sk7): one `POST` that
 * trades a customer key for the day's ticket, the key swap coordinator ADR 2026-10.ukvm built. The one implementation
 * that speaks OkHttp (`OkHttpTicketIssuer`) binds its socket to the validated Internet network and enforces the
 * timeouts, the no-redirect rule and the body cap; everything above it runs against a fake.
 */
interface TicketIssuer {
    /**
     * Asks [origin] (already accepted by [IssuerOrigin]) for a ticket with [key] in the `Authorization` header and
     * nothing in the URL or the body. Never throws.
     */
    suspend fun swap(
        origin: String,
        key: String,
        device: DeviceSigner? = null,
    ): IssuerAnswer

    /**
     * [swap] for the relay host name [aud] (`?aud=`, knit-spool-coordinator ADR 2026-10.ftp9, ADR 2026-10.qpja): the
     * dialled relay's lowercase host, so a ticket names the name this phone dials even when the account was made under
     * the relay's other name. The query sits outside the device proof, which signs the path alone. An issuer that serves
     * one host ignores it.
     */
    suspend fun swap(
        origin: String,
        key: String,
        aud: String,
        device: DeviceSigner?,
    ): IssuerAnswer = swap(origin, key, device)

    /**
     * Moves the account to this phone (docs/VOICE_CALLS.md C-7.6-44): only ever because the person asked, never on a
     * schedule. A `POST` to [IssuerOrigin.MOVE_PATH] with [key], `?aud=` as [swap] sends it, and a proof signed by
     * [device] for that path; its 200 is the swap's. Never throws.
     */
    suspend fun move(
        origin: String,
        key: String,
        aud: String,
        device: DeviceSigner?,
    ): IssuerAnswer = IssuerAnswer.NoVerdict("unsupported")
}

/** What the issuer answered (C-7.6-28). Nothing here carries the key, and only [Issued] carries a secret. */
sealed interface IssuerAnswer {
    /**
     * 200: [ticket] is the `knit-ticket:v1:` text, still to go through [SpoolTickets.import]'s host rule; [endsAt] is the
     * account's end, epoch millis. The JSON's `aud`/`nbf`/`exp`/`flags` copies are not read: the app acts on the claims
     * inside the ticket. A plain class whose [toString] names neither.
     */
    class Issued(
        val ticket: String,
        val endsAt: Long,
    ) : IssuerAnswer {
        override fun toString(): String = "Issued(endsAt=$endsAt)"
    }

    /** 401 `unknown-key`: the key is absent, malformed or no account's. */
    data object UnknownKey : IssuerAnswer

    /** 403 `ended`: the account has ended or been revoked, at [endsAt] (epoch millis) when the answer said. */
    data class Ended(
        val endsAt: Long?,
    ) : IssuerAnswer

    /**
     * 503 `unavailable`, which only a free key meets (docs/VOICE_CALLS.md §2.6): its audience's free tier is off, or the
     * day's cap is reached and it has not been ticketed today — then with [retryAfterMs], to the next UTC midnight. No
     * verdict on the key.
     */
    data class Unavailable(
        val retryAfterMs: Long?,
    ) : IssuerAnswer

    /**
     * 409 `bound-elsewhere` (C-7.6-42): another phone holds the account. It can be moved here from [movableAt] (epoch
     * millis); null means now.
     */
    data class BoundElsewhere(
        val movableAt: Long?,
    ) : IssuerAnswer

    /** 409 `move-too-soon` (C-7.6-44), from the move route only: the account can be moved from [movableAt]; null means now. */
    data class MoveTooSoon(
        val movableAt: Long?,
    ) : IssuerAnswer

    /** 426 `upgrade-required`: the issuer wants a proof this request did not carry. Backed off from as no verdict. */
    data object UpgradeRequired : IssuerAnswer

    /** 400 `bad-proof`, after the one re-sign C-7.6-43 allows: backed off from as no verdict. */
    data object BadProof : IssuerAnswer

    /**
     * Anything else — no route, a timeout, another status, a redirect, a body that does not parse: no verdict on the
     * key. [reason] is a short word for the log (`offline`, `timeout`, `http 502`, `unparseable`…), never the body.
     */
    data class NoVerdict(
        val reason: String,
    ) : IssuerAnswer

    companion object {
        /**
         * Reads a response: [status], the (capped) [body] bytes and a numeric `retry-after` in seconds. Pure, so the JVM
         * tests pin the coordinator's answers through it. A 400, 401, 403, 409, 426 or 503 counts only with its own `error`
         * word, since a proxy or a firewall in front of the issuer answers those statuses too and they say nothing about the
         * key. A `movableAt` that is absent, null or unreadable reads as null: now.
         */
        fun read(
            status: Int,
            body: ByteArray,
            retryAfterS: Long? = null,
        ): IssuerAnswer {
            val parsed = runCatching { json.decodeFromString<Answer>(body.decodeToString()) }.getOrNull()
            return when {
                parsed == null -> NoVerdict(if (status == HTTP_OK) "unparseable" else "http $status")
                status == HTTP_OK -> issued(parsed)
                else -> refusal(status, parsed, retryAfterS) ?: NoVerdict("http $status")
            }
        }

        // A refusal the issuer named with its own `error` word, or null for one it did not.
        private fun refusal(
            status: Int,
            parsed: Answer,
            retryAfterS: Long?,
        ): IssuerAnswer? =
            when (status to parsed.error) {
                HTTP_UNAUTHORIZED to UNKNOWN_KEY -> UnknownKey

                HTTP_FORBIDDEN to ENDED -> Ended(parsed.endsAt?.let(::instant))

                HTTP_UNAVAILABLE to UNAVAILABLE -> Unavailable(retryAfterS?.takeIf { it >= 0 }?.times(MS_PER_S))

                HTTP_CONFLICT to BOUND_ELSEWHERE -> BoundElsewhere(parsed.movableAt?.let(::instant))

                HTTP_CONFLICT to MOVE_TOO_SOON -> MoveTooSoon(parsed.movableAt?.let(::instant))

                HTTP_UPGRADE_REQUIRED to UPGRADE_REQUIRED -> UpgradeRequired

                HTTP_BAD_REQUEST to BAD_PROOF -> BadProof

                // An `?aud=` the issuer does not serve for this account (ADR 2026-10.qpja): nothing this phone can fix,
                // and it cannot happen while `aud` is the dialled host; named, and backed off as no verdict.
                HTTP_BAD_REQUEST to BAD_AUDIENCE -> NoVerdict(BAD_AUDIENCE)

                else -> null
            }

        private fun issued(parsed: Answer): IssuerAnswer {
            val ticket = parsed.ticket?.takeIf { it.startsWith(HeldTicket.PREFIX) } ?: return NoVerdict("unparseable")
            val endsAt = parsed.endsAt?.let(::instant) ?: return NoVerdict("unparseable")
            return Issued(ticket, endsAt)
        }

        /** Whether [status] and [body] are a 400 `bad-proof`: the answer C-7.6-43 re-signs once, on any route. */
        fun isBadProof(
            status: Int,
            body: ByteArray,
        ): Boolean =
            status == HTTP_BAD_REQUEST &&
                runCatching { json.decodeFromString<Answer>(body.decodeToString()) }.getOrNull()?.error == BAD_PROOF

        internal fun instant(text: String): Long? =
            try {
                Instant.parse(text).toEpochMilli()
            } catch (_: DateTimeParseException) {
                null
            }

        const val HTTP_OK = 200
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_FORBIDDEN = 403
        const val HTTP_UNAVAILABLE = 503
        const val HTTP_BAD_REQUEST = 400
        const val HTTP_CONFLICT = 409
        const val HTTP_UPGRADE_REQUIRED = 426
        const val BOUND_ELSEWHERE = "bound-elsewhere"
        const val MOVE_TOO_SOON = "move-too-soon"
        const val UPGRADE_REQUIRED = "upgrade-required"
        const val BAD_PROOF = "bad-proof"
        const val BAD_AUDIENCE = "bad-audience"
        private const val UNKNOWN_KEY = "unknown-key"
        private const val ENDED = "ended"
        private const val UNAVAILABLE = "unavailable"
        private const val MS_PER_S = 1_000L

        private val json = Json { ignoreUnknownKeys = true }
    }
}

/**
 * What a move the person asked for came to (docs/VOICE_CALLS.md C-7.6-44), for the relay screen's one line. Nothing
 * here carries the key.
 */
sealed interface MoveResult {
    /** The account is this phone's; the refresher holds its ticket. */
    data object Moved : MoveResult

    /** `move-too-soon`: it can move from [movableAt] (epoch millis), null for now. */
    data class TooSoon(
        val movableAt: Long?,
    ) : MoveResult

    data object UnknownKey : MoveResult

    data object Ended : MoveResult

    /** No answer worth a verdict: no route, the Keystore refused, a status nobody named. [reason] is a word, never a body. */
    data class Failed(
        val reason: String,
    ) : MoveResult

    /** Nothing to move: no customer key for that relay, the relay off, or a build without Knit Anywhere. */
    data object Unavailable : MoveResult
}

/** Every field any of the issuer's answers carries; what a given status needs is checked in [IssuerAnswer.read]. */
@Serializable
private class Answer(
    val ticket: String? = null,
    val endsAt: String? = null,
    val error: String? = null,
    val movableAt: String? = null,
)
