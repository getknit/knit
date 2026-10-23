package app.getknit.knit.mesh.spool

import app.getknit.knit.mesh.protocol.ScopeConfigPayload

/**
 * One issuer's scope config for one conversation, as this phone holds it (`docs/SPOOL_PROTOCOL.md` §5): the payload's
 * facts after [ScopeConfigPolicy.validate], keyed by the conversation (a peer's node id for a DM, the group's id for a
 * group — never a scope id, which rotates with a root) and the issuer, the authenticated sender of the frame that
 * carried it (C-5-2). [spools] are canonical `wss://` URLs in the issuer's order.
 */
data class ScopeConfig(
    val conversationId: String,
    val groupId: String?,
    val issuer: String,
    val spools: List<String>,
    val maxFrames: Int,
    val ttlMs: Long,
    val maxBlob: Int,
    val version: Int,
)

/**
 * The receive rules of the scope-config ctl (`CTL_SCOPE_CONFIG`, `docs/SPOOL_PROTOCOL.md` §5). Pure: no IO and no clock
 * of its own — every rule that reads a time takes it — so the convergence story is tested against fixtures
 * (`ScopeConfigPolicyTest`).
 *
 * **Rows per issuer, the winner read across them.** The store keeps each issuer's configs for a conversation apart and
 * [winner] picks the highest `(version, issuer)` among the eligible ones (C-5-4). One row per conversation, overwritten
 * by the winner, is what a reader would reach for, and it makes the result depend on arrival order: in a group, a
 * member's config that lands before that member's leave would replace the standing winner on one phone, while another
 * phone that heard the leave first refuses it and keeps the old one, and nothing ever reconciles them (no ack, C-5-6).
 * With rows per issuer the leave drops the leaver's (`GroupRepository.recordDeparture`), so both phones read the same
 * winner from the same remaining rows.
 *
 * **A refusal reads the frame alone, never a local input.** A refused config's chain still advances (the additive-ctl
 * contract), so a refusal is final for that frame; one judged on anything that differs between phones — held rows, the
 * local clock — strands the phone that differs, for good (the crypto review's two rounds). So [validate] reads only the
 * payload and the conversation it rode in: at most [MAX_SPOOLS] URLs of at most [MAX_URL_LENGTH] characters, `wss://`
 * only, bounds positive and clamped to §12's defaults. Nothing refuses a config for its version.
 *
 * **The version is a clock, held and deferred** (C-5-4). It counts minutes since [VERSION_EPOCH_MS]; an issuer writes
 * [nextVersion], the later of one past the eligible winner it holds and the minute it issues. A receiver stores every
 * version, and judges it only when it reads: a row is [eligible] once its version is at most [VERSION_SLACK_MINUTES]
 * past this phone's minute. A row further ahead — a peer's clock running fast, or this phone's running slow — is kept,
 * persisted, and read from the moment this clock reaches it; one at `Int.MAX_VALUE` waits some four thousand years,
 * so nobody can freeze a conversation by sending one. Until a deferred row of an issuer becomes eligible, that issuer's newest
 * eligible row stays readable beside it ([retained]), so there is never a gap.
 */
object ScopeConfigPolicy {
    /** The most URLs one config may name; a config naming more is refused whole, before any URL is read. */
    const val MAX_SPOOLS = 8

    /** The longest URL kept; a longer one is dropped from the list like any other URL this build cannot dial. */
    const val MAX_URL_LENGTH = 512

    /** The version clock's zero: 2026-01-01T00:00Z (`docs/SPOOL_PROTOCOL.md` C-5-4). */
    const val VERSION_EPOCH_MS = 1_767_225_600_000L

    /**
     * How many minutes a held version may run past this phone's minute and still be read ([eligible]). An issuer at
     * the clock writes its minute, or one past a winner already there, so a burst of re-issues inside one minute, or a
     * peer whose clock runs a few minutes ahead of ours, is read at once; anything further waits for our clock.
     */
    const val VERSION_SLACK_MINUTES = 10

    /**
     * How many deferred rows (not yet [eligible]) one issuer keeps per conversation, the lowest first — they are the
     * next to become eligible, and a griefer's far-future rows are the first to go. Past this many re-issues inside one
     * clock gap, which no honest issuer reaches, the rows a phone keeps depend on its clock until the gap closes.
     */
    const val MAX_DEFERRED_PER_ISSUER = 8

    /** §12: a scope keeps at most this many frames; a config may lower it, never raise it. */
    const val MAX_FRAMES = ScopeRegistry.DEFAULT_MAX_FRAMES

    /** §12: a scope keeps a frame at most 48 h, twice mesh custody's TTL; a config may lower it, never raise it. */
    const val MAX_TTL_MS = ScopeRegistry.DEFAULT_TTL_MS

    /** §12: a scope takes a blob of at most 64 KiB; a config may lower it, never raise it. */
    const val MAX_BLOB = ScopeRegistry.DEFAULT_MAX_BLOB

    /** Why a config was refused, for the log line; every refusal counts `DropReason.SCOPE_CONFIG_REFUSED`. */
    enum class Refusal {
        /** No URL this build can dial, more than [MAX_SPOOLS], a non-positive bound, or a version below 1. */
        MALFORMED,

        /** A DM config carrying a group id, or a group config naming a group other than the one its frame rode. */
        WRONG_CONVERSATION,

        /** A group config from a sender outside the group's effective roster, or sealed before that sender's leave. */
        NOT_A_MEMBER,
    }

    /** What [decide] made of a valid config against the rows held for its conversation. */
    enum class Decision {
        /** News: the candidate is written, and the issuer's rows it supersedes ([Plan.drop]) go. */
        STORE,

        /** Already held, or superseded by what its issuer has here: nothing is written, and nothing is refused. */
        KEEP,
    }

    /** [decide]'s answer: the [decision], and the issuer's held rows a [Decision.STORE] replaces. */
    data class Plan(
        val decision: Decision,
        val drop: List<ScopeConfig> = emptyList(),
    )

    /**
     * [payload] as the config [issuer] issued for [conversationId], or the reason it is refused. [expectedGroupId] is
     * the group the carrying frame was sealed to (`RelayEnvelope.group.id`), null for a DM: the payload's own
     * [ScopeConfigPayload.groupId] must equal it, so a config cannot be replayed into another conversation by a member
     * of both. URLs this build cannot dial — not `wss://`, too long, no host — are dropped; the rest are canonicalised
     * ([SpoolUrl.canonical]) and de-duplicated in order, and a config left with none is refused. Bounds are clamped,
     * not refused, above §12's defaults. Nothing here reads a clock or the store: the version is judged when read.
     */
    fun validate(
        payload: ScopeConfigPayload,
        conversationId: String,
        expectedGroupId: String?,
        issuer: String,
    ): Result {
        if (payload.groupId != expectedGroupId) return Result.Refused(Refusal.WRONG_CONVERSATION)
        if (payload.spools.size > MAX_SPOOLS) return Result.Refused(Refusal.MALFORMED)
        if (!numbersPositive(payload)) return Result.Refused(Refusal.MALFORMED)
        val spools =
            payload.spools
                .filter(::dialable)
                .map(SpoolUrl::canonical)
                .distinct()
        if (spools.isEmpty()) return Result.Refused(Refusal.MALFORMED)
        return Result.Valid(
            ScopeConfig(
                conversationId = conversationId,
                groupId = expectedGroupId,
                issuer = issuer,
                spools = spools,
                maxFrames = payload.maxFrames.coerceAtMost(MAX_FRAMES),
                ttlMs = payload.ttlMs.coerceAtMost(MAX_TTL_MS),
                maxBlob = payload.maxBlob.coerceAtMost(MAX_BLOB),
                version = payload.version,
            ),
        )
    }

    /** The outcome of [validate]. */
    sealed interface Result {
        /** A config the store may take, subject to [decide]. */
        data class Valid(
            val config: ScopeConfig,
        ) : Result

        /** Refused before the store is read. */
        data class Refused(
            val why: Refusal,
        ) : Result
    }

    /**
     * Whether a group config from [issuer] may apply: the issuer is in the group's effective roster ([members], which
     * never holds a departed member), and the frame was sealed after the issuer's last recorded leave ([leftAt], 0 when
     * none; [sentAt] already clamped to our clock's skew bound). The second half is the rejoin case: a member who left
     * and came back by their own frame is in the roster again, and a config they sealed before leaving, re-served by
     * custody, must not land on a phone that never held it while the phones that did dropped it at the leave.
     */
    fun groupIssuerAdmitted(
        issuer: String,
        members: Collection<String>,
        sentAt: Long,
        leftAt: Long,
    ): Boolean = issuer in members && sentAt > leftAt

    /**
     * What to do with the valid [candidate] given [held], every row this phone holds for its conversation, at [now]:
     * only the candidate's own issuer's rows are read. The candidate is news when it is not already held and survives
     * [retained] over those rows plus itself; the rows [retained] then leaves out go. Nothing here refuses: what this
     * phone holds never decides whether a peer's config is acceptable, only whether it is news.
     */
    fun decide(
        candidate: ScopeConfig,
        held: Collection<ScopeConfig>,
        now: Long,
    ): Plan {
        val own = held.filter { it.issuer == candidate.issuer }
        if (candidate in own) return Plan(Decision.KEEP)
        val kept = retained(own + candidate, now)
        if (candidate !in kept) return Plan(Decision.KEEP)
        return Plan(Decision.STORE, drop = own.filter { it !in kept })
    }

    /**
     * Which of one issuer's [rows] stay held at [now]: the highest [eligible] one — it outranks every lower eligible row
     * of that issuer now and on every clock later — and the lowest [MAX_DEFERRED_PER_ISSUER] rows not yet eligible, the
     * next ones the clock reaches. So the issuer's readable config never disappears while a deferred one waits beside
     * it, and the deferred one takes over as the clock passes it. One version is one row: of two configs an issuer
     * sealed under one version, the one [outranks] picks.
     */
    fun retained(
        rows: Collection<ScopeConfig>,
        now: Long,
    ): List<ScopeConfig> {
        val perVersion = rows.groupBy { it.version }.values.map { same -> same.maxWith(ORDER) }
        val (ready, deferred) = perVersion.partition { eligible(it, now) }
        val best = ready.maxWithOrNull(ORDER)
        return listOfNotNull(best) + deferred.sortedWith(ORDER).take(MAX_DEFERRED_PER_ISSUER)
    }

    /** Whether [config] may be read at [now]: its version at most [VERSION_SLACK_MINUTES] past this phone's minute. */
    fun eligible(
        config: ScopeConfig,
        now: Long,
    ): Boolean = config.version.toLong() <= minutesOf(now).toLong() + VERSION_SLACK_MINUTES

    /**
     * When [config] becomes [eligible]: the first instant of the minute its version minus the slack names. An
     * `Int.MAX_VALUE` row's is some four thousand years out, which no phone's clock reaches.
     */
    fun eligibleAt(config: ScopeConfig): Long = VERSION_EPOCH_MS + maxOf(0L, config.version.toLong() - VERSION_SLACK_MINUTES) * MINUTE_MS

    /**
     * The conversation's config at [now]: the highest `(version, issuer)` among the [eligible] [rows] (C-5-4: a tie on
     * version goes to the lexicographically highest issuer), or null when none is.
     */
    fun winner(
        rows: Collection<ScopeConfig>,
        now: Long,
    ): ScopeConfig? = rows.filter { eligible(it, now) }.maxWithOrNull(ORDER)

    /**
     * Whether [a] outranks [b] in the one total order every phone applies: `(version, issuer)` per C-5-4, then — for
     * one issuer that sealed two different configs under one version, which no honest issuer does — the content, so
     * that two phones holding the pair in either arrival order keep the same one.
     */
    fun outranks(
        a: ScopeConfig,
        b: ScopeConfig,
    ): Boolean = ORDER.compare(a, b) > 0

    /**
     * The version a config issued at [now] on top of [winner] — the *eligible* winner this phone reads ([winner]) —
     * carries: the minute of [now] ([minutesOf]), or one past the winner if that is higher, saturating at
     * `Int.MAX_VALUE`. Never checked against the clock on the way out: an issuer whose clock runs slow still sends, at
     * a low version, and loses to anyone later rather than staying silent.
     */
    fun nextVersion(
        winner: ScopeConfig?,
        now: Long,
    ): Int {
        val current = winner?.version ?: 0
        val stepped = if (current == Int.MAX_VALUE) Int.MAX_VALUE else current + 1
        return maxOf(stepped, minutesOf(now))
    }

    /** Whole minutes from [VERSION_EPOCH_MS] to [at], at least 0 and at most `Int.MAX_VALUE`. */
    fun minutesOf(at: Long): Int =
        if (at <=
            VERSION_EPOCH_MS
        ) {
            0
        } else {
            ((at - VERSION_EPOCH_MS) / MINUTE_MS).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        }

    private const val MINUTE_MS = 60_000L

    private fun numbersPositive(payload: ScopeConfigPayload): Boolean =
        payload.version >= 1 && payload.maxFrames >= 1 && payload.ttlMs >= 1L && payload.maxBlob >= 1

    private fun dialable(url: String): Boolean =
        url.length <= MAX_URL_LENGTH &&
            url.none { it.isWhitespace() || it.isISOControl() } &&
            SpoolUrl.isAcceptable(url, allowCleartext = false) &&
            SpoolUrl.hostname(url) != null

    private val ORDER: Comparator<ScopeConfig> =
        compareBy<ScopeConfig> { it.version }
            .thenBy { it.issuer }
            .thenBy { it.spools.joinToString("\n") }
            .thenBy { it.maxFrames }
            .thenBy { it.ttlMs }
            .thenBy { it.maxBlob }
}
