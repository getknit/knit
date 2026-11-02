package app.getknit.knit.mesh.bluetooth

import app.getknit.knit.identity.NodeId
import app.getknit.knit.mesh.crypto.BlindTag
import java.util.PriorityQueue

/**
 * Resolves an advertised form ([AdvertOrder.Form]) to a node id (ADR 2026-11.xp6d, knit-next #285). A clear advert
 * is its own id. A blinded one names a [BlindTag], resolved in this order:
 *
 * 1. **Malformed** first: bytes without the tag prefix answer [Resolution.Malformed].
 * 2. **Own:** my current or previous tag ([setOwnTags]).
 * 3. **Direct contacts' expected tags** ([setKeys], [ExpectedTagTable]): every contact key's tags over
 *    [BlindTag.window] of this phone's wall clock (the last 8 hours of 15-minute slots and the next half hour, every
 *    restart index), so a heard tag resolves with one hash lookup and an unknown one, a flood of them included, costs
 *    a miss and nothing else. A tag two contacts' keys both list resolves to nobody by key ([Counters.keyCollisions]):
 *    a contact who sends another's presence key as her own gains nothing over him. The table moves with the wall clock
 *    a slot at a time and rebuilds only a contact whose key changed. A contact's replaced key stays listed until a tag
 *    under its successor is heard, at most [Config.previousKeyMaxMs] (one key generation), since a tag carries no key
 *    generation and an idle phone can air an old-key tag for hours. A tag whose slot is outside the window (a sender's
 *    clock off by more than that) does not resolve; the phone links as a stranger and the link proves it ("Clocks").
 * 4. **Link claims** ([onDialed], [onTagNext]): a tag a proven link told us is its own.
 * 5. **Hints** ([onAnswered]): a tag a dialer's HELLO told us is its own, for presence and RSSI only (below).
 *
 * Every key check is the table's lookup, so it always runs: there is no budget, no deferral and no cache.
 *
 * **Claims** (the design pass and the security pass of 2026-11-02):
 *  - a link holds at most one claim, and so does a node id; a newer one (a promoted [onTagNext]) replaces it;
 *  - a claim a contact's expected tag contradicts is dropped and counted, at the claim and at every resolve;
 *  - two ids claiming one tag void it for both, and it takes no claim until it would have expired, except that a
 *    claim one of our own dials proved lifts a void that unproven claimants made and voids those claimants; a void
 *    stands against a dial only when two of our own dials disagreed;
 *  - a claim (and an announced tag) never lapses while its link is held, sighted or not (a screen-off phone in deep
 *    suspend can go unheard for a lifetime), and lives [Config.claimLifetimeMs] (one rotation period plus the presence
 *    linger) past the link's end, so a peer whose link drops still resolves for that long. A phone asleep longer can
 *    air the same tag for hours; past the claim's life it resolves only by key or a new link. The life is not
 *    stretched to cover that: it also bounds how long a linked stranger can follow the phone by its tag. A claim never
 *    comes from a link's end;
 *  - a dialer believes the responder's HELLO `t` only when it equals the `a` it dialed, or a tag that responder
 *    announced and we hold pending ([onDialed]); a different `t` after a tag dial is a rotation edge, held pending as
 *    an announced tag; a tag dial whose proven id differs from a key's answer records nothing;
 *  - a responder's view of the dialer's `t` is a hint ([onAnswered]): it resolves the tag to the link's proven id with
 *    [Source.Hint], for presence and RSSI attribution only, and **never suppresses a dial** [AdvertOrder.iDial] calls
 *    for. While both tags stand that costs an honest dialer nothing: it dialed me under the same antisymmetric rule, so
 *    `iDial(me, its tag)` is false. Once I rotate, my new tag sorts above its tag half the time, and I dial a peer I am
 *    already linked to; it refuses with no reply and resends its current tag over the held link (ADR 2026-11.xp6d's
 *    responder row). That resend arrives as [onTagNext], is held pending, and becomes the link's claim when the tag is
 *    heard, which does suppress the dial: the resend is what heals it, at the cost of one refused dial per such
 *    rotation. A forged hint (a linked Mallory naming Alice's tag) leaves my dial to that tag standing; it reaches
 *    Alice and proves her id, and [onDialProved] voids the liar's hint and claims. A hint goes with its link;
 *  - an announced tag ([onTagNext]) is held pending, resolving nothing, until it is first heard; then it is the link's
 *    claim. **A link sends at most one `TAG_NEXT` per [Config.tagNextMinIntervalMs]** (an honest phone sends one per
 *    rotation, 600 s or more apart, and a resend when it refuses a dial): a second inside the window is
 *    [ClaimOutcome.RateLimited] and records nothing;
 *  - the tables are bounded: [Config.maxClaims] claims (a held link's claim is never refused; when full, the ended-link
 *    claim that expires soonest goes, an unproven one first among equals), and [Config.entryCap] announced tags and
 *    voids each. At a cap, an ended link's announced tag goes before a held link's, and a void unproven claimants made
 *    before one two of our own dials made, the soonest to expire first. Expiry runs off a deadline queue, never a walk
 *    of the tables.
 *
 * **What an answer may do** ([Resolution.Resolved.suppressesDial]). A key's or a clear advert's answer may suppress a
 * dial. A claim may only while its claimant's link is held: past the link's end it still names the peer for presence,
 * but a dial [AdvertOrder.iDial] calls for goes ahead, and its proof settles the claim ([onDialed]). A hint never may,
 * and neither may an unresolved answer.
 *
 * **Wiring: a sighting's history.** Until a form resolves, the wiring keys what it learns of the sighting (its dwell,
 * its admission sighting, its cold sightings) on the advertised form; once it resolves, that history carries over to
 * the id, so a contact first heard unresolved (a key arriving late, a slot entering the window) keeps its dwell.
 *
 * **Residual.** A linked attacker can still name an unlinked *stranger's* tag, which no key of ours contradicts. As a
 * responder's hint it costs nothing (above). As a claim it suppresses our dial to that stranger while the attacker's
 * link is held, up to one stranger per attacker link; once that link ends our dial goes ahead and its proof voids the
 * attacker. [nextSpotCheck] offers at most one claimed tag per [Config.spotCheckIntervalMs] for a verification dial
 * inside that window, and [onSpotCheck] voids the claimant when the tag's device proves another id; a refusal with no
 * reply is consistent with an honest claim. The hook is kept but not wired (ADR 2026-11.xp6d's wiring): the hint rule
 * covers the responder case.
 *
 * **A replayed tag.** A tag is a function of its slot, so anyone who heard it can re-air it while its slot stays in the
 * window. A tag dial that proves an id other than the one a key resolved the tag to ([Counters.keyProofMismatches])
 * is such a replay. Nothing is voided (the key outranks the proof), and the wiring must never re-key that address
 * under the key's id for dialing.
 *
 * Pure: no Android. Two clocks are injected: the elapsed clock ([nowMs], the wiring passes
 * `SystemClock.elapsedRealtime`) for every expiry, and the wall clock ([wallMs], `System.currentTimeMillis`) for slots
 * only. Links are the caller's opaque keys ([L], compared by `equals`). [counters] carry counts only, never a key or a
 * tag.
 *
 * **Threading.** Single-threaded by contract: the table's builders hold a `Mac`, and [setKeys], [slide] or any call
 * that crosses a slot edge (every entry point slides the window first) lists up to a window's 140 HMACs per key, so
 * the transport runs every call on one confined worker. Never the main thread (the scan callback runs on the main
 * looper), and never under the transport's `lock`: any call may build. On `ACTION_TIME_CHANGED` the wiring calls
 * [slide] on that worker, so a big jump's re-list runs then and not inside a scan result's resolve. So the per-id maps
 * a sighting feeds (`sight()`'s) are keyed only after an asynchronous resolve, and link-up and sighting events must
 * keep their order across that hop: a sighting resolved after a link event it preceded must not undo it.
 */
@Suppress("TooManyFunctions", "LargeClass") // one entry per handshake event, each a claim rule's
internal class AdvertAliases<L : Any>(
    private val nowMs: () -> Long,
    private val wallMs: () -> Long,
    private val config: Config = Config(),
) {
    data class Config(
        /**
         * The rotation period: a tag's slot, 15 minutes. Not how long a tag airs, which is longer on a phone asleep
         * (rotation waits for a wake); the claim lifetime is this plus the linger, by design (see the class KDoc).
         */
        val rotationPeriodMs: Long = BlindTag.SLOT_MS,
        /** The presence tracker's linger past a peer's last sighting. */
        val presenceLingerMs: Long = 90_000,
        /**
         * The longest a contact's replaced key is still listed when no tag under its successor is heard: one key
         * generation (the weekly rotation). Hearing such a tag drops it at once.
         */
        val previousKeyMaxMs: Long = KEY_GENERATION_MS,
        val maxClaims: Int = 128,
        /** One `TAG_NEXT` per link per this window. */
        val tagNextMinIntervalMs: Long = 120_000,
        val spotCheckIntervalMs: Long = 300_000,
    ) {
        val claimLifetimeMs: Long get() = rotationPeriodMs + presenceLingerMs

        /** The cap on announced tags, and on voids. */
        val entryCap: Int get() = 2 * maxClaims
    }

    /**
     * A direct contact's presence key, and the one it replaced with the elapsed time it is listed until. A caller that
     * knows only the [current] key passes it alone: when it differs from the one held, the held one becomes the
     * previous key, listed until a tag under [current] is heard or [Config.previousKeyMaxMs] passes. [previousUntilMs]
     * caps an explicit [previous] on the same elapsed clock (default the same cap).
     */
    class ContactKey(
        val current: ByteArray,
        val previous: ByteArray? = null,
        val previousUntilMs: Long? = null,
    )

    enum class Source {
        Clear,
        Key,
        Claim,

        /** A responder's hint ([onAnswered]): for presence and RSSI only, never to suppress a dial [AdvertOrder.iDial] wants. */
        Hint,
    }

    sealed interface Resolution {
        /**
         * [nodeId] from [source]. [suppressesDial] says whether the wiring may skip a dial [AdvertOrder.iDial] calls
         * for on this answer: a clear advert's or a key's, a claim's only while its claimant's link is held, a hint's
         * never.
         */
        data class Resolved(
            val nodeId: String,
            val source: Source,
            val suppressesDial: Boolean,
        ) : Resolution

        /** One of my own tags, current or previous. */
        data object Own : Resolution

        /** No contact's expected tag, claim or hint names it. Never suppresses a dial. */
        data object Unresolved : Resolution

        /** Marked blinded, but the bytes lack the tag prefix. */
        data object Malformed : Resolution
    }

    enum class ClaimOutcome {
        /** Now the link's claim, resolving its tag. */
        Recorded,

        /** An announced tag, held until it is first heard. */
        Pending,

        /** A responder's view of the dialer's `t`, held as a [Source.Hint]. */
        Hinted,

        /** A contact's expected tag already names the same id: nothing to record. */
        Redundant,

        /** A contact's expected tag names a different id: dropped. */
        Contradicted,

        /** Another id claims the tag: both voided. */
        Contested,

        /** Not a claim under the rules (a `t` that is not the `a` dialed, a clear form, my own tag, no hint). */
        Ignored,

        /** The claim table is full of claims that outrank this one. */
        Full,

        /** A second `TAG_NEXT` from the link inside [Config.tagNextMinIntervalMs]: nothing recorded. */
        RateLimited,
    }

    /** Diagnostics: counts and table sizes only, never a key or a tag. */
    data class Counters(
        val resolvedByKey: Long,
        val resolvedByClaim: Long,
        val resolvedByHint: Long,
        val unresolved: Long,
        val malformed: Long,
        val claimsRecorded: Long,
        val claimsDropped: Long,
        val claimsVoided: Long,
        val claimsIgnored: Long,
        val claimsRefusedFull: Long,
        /** Ended-link claims evicted to make room. */
        val claimsEvicted: Long,
        /** Announced tags and voids evicted at their cap. */
        val entriesEvicted: Long,
        val tagNextRateLimited: Long,
        val spotChecks: Long,
        /** Tag dials and spot checks that proved another id than a claim or hint named. */
        val proofMismatches: Long,
        /** Tag dials that proved another id than a key resolved the tag to: a replayed tag. */
        val keyProofMismatches: Long,
        /** Expected tags listed now. */
        val tableSize: Int,
        /** Key generations listed whole, and slots listed as the window moved. */
        val tableBuilds: Long,
        val tableSlotSteps: Long,
        /** Tags computed for the table: one HMAC each. Nothing else here computes one. */
        val tableHmacs: Long,
        /** Lookups refused because two contacts' keys list the tag. */
        val keyCollisions: Long,
        /** Tags two contacts' keys list now (a walk of the table). */
        val collidedTags: Int,
        val claims: Int,
        val pending: Int,
        val voided: Int,
        val deadlines: Int,
    )

    /** A claimed tag offered for a verification dial: dial [tag] by tag and pass its proven id to [onSpotCheck]. */
    data class SpotCheck(
        val tag: String,
        val claimedId: String,
    ) {
        override fun toString(): String = "SpotCheck(${tag.take(PREFIX_SHOWN)}…)"
    }

    private class Claim<L>(
        val link: L,
        val nodeId: String,
        var expiresAtMs: Long,
        /** Recorded from a dial to this very tag that proved [nodeId] ([onDialed]); never offered for a spot check. */
        val dialProven: Boolean = false,
        var checked: Boolean = dialProven,
    )

    /** A void, and whether it came from two of our own dials ([dialProven]) or from claimants we never proved. */
    private class VoidMark<L>(
        val expiresAtMs: Long,
        val dialProven: Boolean,
        val claimants: List<Claim<L>>,
    )

    /** One entry's expiry; [lapse] removes it if it is still the entry it was queued for and still due. */
    private class Deadline(
        val atMs: Long,
        val lapse: (Long) -> Unit,
    )

    private var ownCurrent: String? = null
    private var ownPrevious: String? = null
    private val table = ExpectedTagTable(config.previousKeyMaxMs)

    private val claims = HashMap<String, Claim<L>>()
    private val pending = HashMap<String, Claim<L>>()
    private val hints = HashMap<L, Pair<String, Claim<L>>>()
    private val voided = HashMap<String, VoidMark<L>>()
    private val deadlines = PriorityQueue<Deadline>(compareBy { it.atMs })
    private val lastTagNext = HashMap<L, Long>()

    /**
     * Links a handshake event named and [onLinkEnded] has not ended: their claims and announced tags never lapse,
     * sighted or not, and get one claim lifetime from the link's end.
     */
    private val heldLinks = HashSet<L>()

    private var lastSpotCheckMs: Long? = null

    private var resolvedByKey = 0L
    private var resolvedByClaim = 0L
    private var resolvedByHint = 0L
    private var unresolved = 0L
    private var malformed = 0L
    private var claimsRecorded = 0L
    private var claimsDropped = 0L
    private var claimsVoided = 0L
    private var claimsIgnored = 0L
    private var claimsRefusedFull = 0L
    private var claimsEvicted = 0L
    private var entriesEvicted = 0L
    private var tagNextRateLimited = 0L
    private var spotChecks = 0L
    private var proofMismatches = 0L
    private var keyProofMismatches = 0L

    /** My own advertised tags, so my own advert (heard back, or claimed by another) is never taken for a peer. */
    fun setOwnTags(
        current: String?,
        previous: String?,
    ) {
        ownCurrent = current
        ownPrevious = previous
    }

    /**
     * The direct contacts' presence keys, `nodeId → key`, replacing the whole set ([ContactKey]'s rules). Only a
     * contact whose keys changed is listed again: up to a window's 140 HMACs per new key, on the confined worker.
     */
    fun setKeys(contacts: Map<String, ContactKey>) {
        table.setKeys(contacts, nowMs(), wallMs())
    }

    /**
     * Moves the expected-tag window to the wall clock now. Every entry point does this first; the wiring also calls it
     * on `ACTION_TIME_CHANGED`, on the worker, so a jump's re-list runs there.
     */
    fun slide() {
        table.slide(wallMs())
    }

    /** The node id [form] names, or why there is none. */
    fun resolve(form: AdvertOrder.Form): Resolution {
        if (!form.blinded) return Resolution.Resolved(form.advertised, Source.Clear, suppressesDial = true)
        val tag = form.advertised
        if (tagBytes(tag) == null) {
            malformed++
            return Resolution.Malformed
        }
        val now = nowMs()
        expire(now)
        if (isOwn(tag)) return Resolution.Own
        val keyId = keyAnswer(tag)
        if (keyId != null) {
            dropContradicted(tag, keyId)
            resolvedByKey++
            return Resolution.Resolved(keyId, Source.Key, suppressesDial = true)
        }
        // An announced tag heard for the first time becomes its link's claim.
        promotePending(tag, now)
        return linkAnswer(tag) ?: Resolution.Unresolved.also { unresolved++ }
    }

    /**
     * The dialer's side of a handshake on [link]: I dialed [dialed], the responder's HELLO told [told] (its `t`,
     * decoded; null when absent) and proved [provenId]. Records [told] as the link's claim only when it equals the
     * tag dialed, or is a tag [provenId] announced and we hold pending; never when a contact's expected tag names the
     * dialed tag's owner as another id. A dialed tag with a different [told] is a rotation edge (the responder rotated
     * between my sighting and my dial): [told] is held pending for the link, as an [onTagNext] would be, and becomes its
     * claim when heard.
     */
    fun onDialed(
        link: L,
        dialed: AdvertOrder.Form,
        told: String?,
        provenId: String,
    ): ClaimOutcome {
        val now = nowMs()
        expire(now)
        heldLinks += link
        onDialProved(dialed, provenId)
        val dialedTag = dialed.advertised.takeIf { dialed.blinded && tagBytes(it) != null }
        if (dialedTag == null || told == null || told == provenId) return ignored()
        val announced = told != dialedTag && pending[told]?.nodeId == provenId
        val keyId = keyAnswer(dialedTag)
        return when {
            keyId != null -> {
                agreement(keyId, provenId)
            }

            told != dialedTag && !announced -> {
                onTagNext(link, provenId, told)
            }

            else -> {
                val outcome = record(link, told, provenId, now, dialProven = !announced)
                if (announced && outcome.settles()) pending.remove(told)
                outcome
            }
        }
    }

    /**
     * The responder's side: the dialer on [link] proved [provenId] and its HELLO told [told]. Held as the link's hint
     * ([Source.Hint]: presence and RSSI only, never to suppress a dial), one per link and per id, unless a contact's
     * expected tag names another id or the tag is void. A told form equal to the id (a clear dialer) is nothing to hold.
     */
    fun onAnswered(
        link: L,
        told: String?,
        provenId: String,
    ): ClaimOutcome {
        val now = nowMs()
        expire(now)
        heldLinks += link
        val tag = told?.takeIf { it != provenId && !isOwn(it) && tagBytes(it) != null } ?: return ignored()
        if (tag in voided) return contested()
        val keyId = keyAnswer(tag)
        if (keyId != null) return agreement(keyId, provenId)
        hints.values.removeAll { it.second.nodeId == provenId }
        hints[link] = tag to Claim(link, provenId, Long.MAX_VALUE)
        return ClaimOutcome.Hinted
    }

    /**
     * A dial to [dialed] proved [provenId]. Every hint, announced tag or claim naming another id for that tag that no
     * dial of ours proved is a lie (the dial went to the address airing the tag), so the links and ids that made it lose
     * all of theirs, a void those claimants made is lifted, and the tag stays open for its owner's claim. A claim another
     * dial to the same tag proved is a contest instead (someone re-airs the tag): the tag is void, as when two ids claim
     * it. A contact's expected tag naming an id other than [provenId] outranks the proof: nothing is voided and the
     * replay is counted ([Counters.keyProofMismatches]). [onDialed] calls it first; a tag dial with no claim to record,
     * or a spot check, calls it alone. True when anything was voided or lifted.
     */
    fun onDialProved(
        dialed: AdvertOrder.Form,
        provenId: String,
    ): Boolean {
        val tag = dialed.advertised.takeIf { dialed.blinded && tagBytes(it) != null } ?: return false
        val now = nowMs()
        expire(now)
        val keyId = keyAnswer(tag)
        if (keyId != null && keyId != provenId) {
            keyProofMismatches++
            return false
        }
        return settleRivals(tag, provenId, now)
    }

    /**
     * [link], proven as [provenId], announced its next tag ([tag], `TAG_NEXT`). Held pending, resolving nothing, until
     * it is first heard ([resolve]) or a dial's HELLO names it ([onDialed]); then it replaces the link's claim. At most
     * one per link per [Config.tagNextMinIntervalMs].
     */
    fun onTagNext(
        link: L,
        provenId: String,
        tag: String,
    ): ClaimOutcome {
        val now = nowMs()
        expire(now)
        heldLinks += link
        if (tagBytes(tag) == null || isOwn(tag)) return ignored()
        if (!tagNextAllowed(link, now)) return rateLimited()
        if (tag in voided) return contested()
        val keyId = keyAnswer(tag)
        return if (keyId != null) agreement(keyId, provenId) else holdPending(link, provenId, tag, now)
    }

    /**
     * [link] ended: its hint goes, being unproven past the link. Its claim and any announced tag, which never lapse while
     * the link is held, live one [Config.claimLifetimeMs] from now. The wiring calls it for every link it named in a
     * handshake event, or that link's entries never lapse.
     */
    fun onLinkEnded(link: L) {
        hints.remove(link)
        lastTagNext.remove(link)
        if (!heldLinks.remove(link)) return
        val until = nowMs() + config.claimLifetimeMs
        claims.forEach { (tag, claim) -> if (claim.link == link) extend(claims, tag, claim, until) }
        pending.forEach { (tag, claim) -> if (claim.link == link) extend(pending, tag, claim, until) }
    }

    /**
     * One claimed tag for a verification dial, at most one per [Config.spotCheckIntervalMs], each claim at most once,
     * the oldest first; null when none is due. Unwired (see the class KDoc).
     */
    fun nextSpotCheck(): SpotCheck? {
        val now = nowMs()
        expire(now)
        lastSpotCheckMs?.let { if (now - it < config.spotCheckIntervalMs) return null }
        val (tag, claim) = claims.entries.filter { !it.value.checked }.minByOrNull { it.value.expiresAtMs } ?: return null
        claim.checked = true
        lastSpotCheckMs = now
        spotChecks++
        return SpotCheck(tag, claim.nodeId)
    }

    /**
     * The verification dial of [check] proved [provenId], or null for a refusal or no reply, which is consistent with an
     * honest claim. A different id is [onDialProved]'s mismatch.
     */
    fun onSpotCheck(
        check: SpotCheck,
        provenId: String?,
    ) {
        if (provenId != null) onDialProved(AdvertOrder.Form(check.tag, blinded = true), provenId)
    }

    fun counters(): Counters =
        Counters(
            resolvedByKey = resolvedByKey,
            resolvedByClaim = resolvedByClaim,
            resolvedByHint = resolvedByHint,
            unresolved = unresolved,
            malformed = malformed,
            claimsRecorded = claimsRecorded,
            claimsDropped = claimsDropped,
            claimsVoided = claimsVoided,
            claimsIgnored = claimsIgnored,
            claimsRefusedFull = claimsRefusedFull,
            claimsEvicted = claimsEvicted,
            entriesEvicted = entriesEvicted,
            tagNextRateLimited = tagNextRateLimited,
            spotChecks = spotChecks,
            proofMismatches = proofMismatches,
            keyProofMismatches = keyProofMismatches,
            tableSize = table.size,
            tableBuilds = table.builds,
            tableSlotSteps = table.slotSteps,
            tableHmacs = table.tagsComputed,
            keyCollisions = table.collisionHits,
            collidedTags = table.collided,
            claims = claims.size,
            pending = pending.size,
            voided = voided.size,
            deadlines = deadlines.size,
        )

    /** The contact whose expected tags list [tag], or null. */
    private fun keyAnswer(tag: String): String? = table.lookup(tag)?.nodeId

    /** A claim's answer, else a hint's when every hint for [tag] names one id; null when neither answers. */
    private fun linkAnswer(tag: String): Resolution? {
        val claim = claims[tag]
        if (claim != null) {
            resolvedByClaim++
            return Resolution.Resolved(claim.nodeId, Source.Claim, suppressesDial = claim.link in heldLinks)
        }
        val hinted =
            hints.values
                .filter { it.first == tag }
                .map { it.second.nodeId }
                .distinct()
                .singleOrNull() ?: return null
        resolvedByHint++
        return Resolution.Resolved(hinted, Source.Hint, suppressesDial = false)
    }

    /** [onDialProved]'s work once the proof stands: liars voided, their void lifted, a proven rival contested. */
    private fun settleRivals(
        tag: String,
        provenId: String,
        now: Long,
    ): Boolean {
        val rivals =
            (listOfNotNull(claims[tag], pending[tag]) + hints.values.filter { it.first == tag }.map { it.second })
                .filter { it.nodeId != provenId }
        val (proven, liars) = rivals.partition { it.dialProven }
        liars.forEach { voidLiar(it.link, it.nodeId) }
        if (liars.isNotEmpty()) proofMismatches++
        val standing = voided[tag]
        val lifted = standing != null && !standing.dialProven
        if (standing != null && lifted) liftVoid(tag, standing, provenId)
        if (proven.isNotEmpty()) void(tag, now, dialProven = true, claimants = proven, incoming = 0)
        return rivals.isNotEmpty() || lifted
    }

    /** Promotes [tag]'s announced entry to its link's claim; the entry goes only once its record settles. */
    private fun promotePending(
        tag: String,
        now: Long,
    ) {
        val announced = pending[tag] ?: return
        val outcome = record(announced.link, tag, announced.nodeId, now)
        if (outcome.settles() && pending[tag] === announced) pending.remove(tag)
    }

    private fun holdPending(
        link: L,
        provenId: String,
        tag: String,
        now: Long,
    ): ClaimOutcome {
        val incoming = Claim(link, provenId, now + config.claimLifetimeMs)
        val rival = pending[tag] ?: claims[tag]
        if (rival != null && rival.nodeId != provenId) {
            void(tag, now, dialProven = false, claimants = listOf(rival, incoming), incoming = 1)
            return ClaimOutcome.Contested
        }
        pending.values.removeAll { it.link == link || it.nodeId == provenId }
        // At the cap an ended link's announcement goes before a held link's, the soonest to expire first (N4).
        if (pending.size >= config.entryCap) {
            evict(pending, compareBy<Claim<L>>({ it.link in heldLinks }, { it.expiresAtMs }))
        }
        pending[tag] = incoming
        queueClaim(pending, tag, incoming)
        return ClaimOutcome.Pending
    }

    private fun tagNextAllowed(
        link: L,
        now: Long,
    ): Boolean {
        val last = lastTagNext[link]
        if (last != null && now - last in 0 until config.tagNextMinIntervalMs) return false
        lastTagNext[link] = now
        return true
    }

    /** Removes every claim, announced tag and hint of [link] and of [nodeId], counting each as voided. */
    private fun voidLiar(
        link: L,
        nodeId: String,
    ) {
        val lied: (Claim<L>) -> Boolean = { it.link == link || it.nodeId == nodeId }
        val before = claims.size + pending.size + hints.size
        claims.values.removeAll(lied)
        pending.values.removeAll(lied)
        hints.values.removeAll { lied(it.second) }
        claimsVoided += before - claims.size - pending.size - hints.size
    }

    /** Our dial proved [provenId] for a tag that unproven claimants voided: the void goes, and so do they. */
    private fun liftVoid(
        tag: String,
        mark: VoidMark<L>,
        provenId: String,
    ) {
        voided.remove(tag)
        mark.claimants.filter { it.nodeId != provenId }.forEach { voidLiar(it.link, it.nodeId) }
        proofMismatches++
    }

    /** Drops what has come due: replaced keys past their cap, the window's slots, then the deadline queue's entries. */
    private fun expire(now: Long) {
        table.lapse(now)
        table.slide(wallMs())
        while (true) {
            val due = deadlines.peek()?.takeIf { it.atMs <= now } ?: break
            deadlines.poll()
            due.lapse(now)
        }
    }

    /**
     * Queues [claim]'s expiry in [table] under [tag]. A held link's entry is skipped when due (it never lapses while
     * held) and queued again at [onLinkEnded].
     */
    private fun queueClaim(
        table: HashMap<String, Claim<L>>,
        tag: String,
        claim: Claim<L>,
    ) {
        queue(claim.expiresAtMs) { lapseIn(table, tag, claim, it) }
    }

    private fun extend(
        table: HashMap<String, Claim<L>>,
        tag: String,
        claim: Claim<L>,
        until: Long,
    ) {
        claim.expiresAtMs = maxOf(claim.expiresAtMs, until)
        queueClaim(table, tag, claim)
    }

    /**
     * Queues an expiry. Stale deadlines (an entry since replaced or extended) are dropped when due; if they ever
     * outnumber the live entries by far, the queue is rebuilt from the tables.
     */
    private fun queue(
        atMs: Long,
        lapse: (Long) -> Unit,
    ) {
        deadlines.add(Deadline(atMs, lapse))
        val live = claims.size + pending.size + voided.size
        if (deadlines.size > QUEUE_SLACK * live + QUEUE_FLOOR) rebuildDeadlines()
    }

    private fun rebuildDeadlines() {
        deadlines.clear()
        claims.forEach { (tag, claim) -> deadlines.add(Deadline(claim.expiresAtMs) { lapseIn(claims, tag, claim, it) }) }
        pending.forEach { (tag, claim) -> deadlines.add(Deadline(claim.expiresAtMs) { lapseIn(pending, tag, claim, it) }) }
        voided.forEach { (tag, mark) -> deadlines.add(Deadline(mark.expiresAtMs) { lapseVoid(tag, mark, it) }) }
    }

    private fun lapseIn(
        table: HashMap<String, Claim<L>>,
        tag: String,
        claim: Claim<L>,
        now: Long,
    ) {
        if (table[tag] === claim && claim.link !in heldLinks && claim.expiresAtMs <= now) table.remove(tag)
    }

    private fun lapseVoid(
        tag: String,
        mark: VoidMark<L>,
        now: Long,
    ) {
        if (voided[tag] === mark && mark.expiresAtMs <= now) voided.remove(tag)
    }

    /**
     * Records [nodeId]'s claim to [tag] on [link], replacing the link's and the id's other claim. [dialProven] when a
     * dial of ours to this very tag proved [nodeId].
     */
    private fun record(
        link: L,
        tag: String,
        nodeId: String,
        now: Long,
        dialProven: Boolean = false,
    ): ClaimOutcome {
        if (tagBytes(tag) == null || isOwn(tag)) return ignored()
        val standing = voided[tag]
        if (standing != null) {
            // Only our own dial lifts a void, and only one unproven claimants made.
            if (!dialProven || standing.dialProven) return contested()
            liftVoid(tag, standing, nodeId)
        }
        val keyId = keyAnswer(tag)
        if (keyId != null) return agreement(keyId, nodeId)
        return contestOrPlace(Claim(link, nodeId, now + config.claimLifetimeMs, dialProven), tag, now)
    }

    private fun contestOrPlace(
        claim: Claim<L>,
        tag: String,
        now: Long,
    ): ClaimOutcome {
        val rival = claims[tag]
        if (rival == null || rival.nodeId == claim.nodeId) return place(claim, tag)
        void(tag, now, dialProven = claim.dialProven && rival.dialProven, claimants = listOf(rival, claim), incoming = 1)
        return ClaimOutcome.Contested
    }

    /**
     * Places a claim the rules let through: the link's and the id's other claim go first. When the table is full, the
     * ended-link claim that expires soonest (an unproven one first among equals) makes room; a held link's claim is
     * never refused, link slots bounding those.
     */
    private fun place(
        claim: Claim<L>,
        tag: String,
    ): ClaimOutcome {
        val replaced: (Claim<L>) -> Boolean = { it.link == claim.link || it.nodeId == claim.nodeId }
        if (claims.values.count { !replaced(it) } >= config.maxClaims) {
            val victim =
                claims.entries
                    .filter { it.value.link !in heldLinks && !replaced(it.value) }
                    .minWithOrNull(compareBy<Map.Entry<String, Claim<L>>>({ it.value.expiresAtMs }, { it.value.dialProven }))
            when {
                victim != null -> claims.remove(victim.key).also { claimsEvicted++ }
                claim.link !in heldLinks -> return ClaimOutcome.Full.also { claimsRefusedFull++ }
            }
        }
        claims.values.removeAll(replaced)
        claims[tag] = claim
        queueClaim(claims, tag, claim)
        claimsRecorded++
        return ClaimOutcome.Recorded
    }

    /**
     * Two ids claimed [tag]: neither resolves it, no hint does either, and none may claim it again until it would have
     * expired. [incoming] counts the claim that found the rival, when there is one. [dialProven] when every claimant
     * was proven by our own dial: only then does the void stand against a later dial's proof.
     */
    private fun void(
        tag: String,
        now: Long,
        dialProven: Boolean,
        claimants: List<Claim<L>>,
        incoming: Int,
    ) {
        val hinted = hints.values.count { it.first == tag }
        hints.values.removeAll { it.first == tag }
        claimsVoided += (if (claims.remove(tag) != null) 1 else 0) + (if (pending.remove(tag) != null) 1 else 0) + hinted + incoming
        // At the cap a void unproven claimants made goes before one two of our dials made, the soonest first (N4).
        if (tag !in voided && voided.size >= config.entryCap) {
            evict(voided, compareBy<VoidMark<L>>({ it.dialProven }, { it.expiresAtMs }))
        }
        val mark = VoidMark(now + config.claimLifetimeMs, dialProven, claimants)
        voided[tag] = mark
        queue(mark.expiresAtMs) { lapseVoid(tag, mark, it) }
    }

    /** Evicts the entry of [table] that [order] puts first. A bounded walk: the table is at its cap. */
    private fun <V> evict(
        table: HashMap<String, V>,
        order: Comparator<V>,
    ) {
        table.entries.minWithOrNull { a, b -> order.compare(a.value, b.value) }?.let {
            table.remove(it.key)
            entriesEvicted++
        }
    }

    /** A key resolved [tag] to [nodeId]: a claim, an announced tag or a hint naming another id for it goes. */
    private fun dropContradicted(
        tag: String,
        nodeId: String,
    ) {
        if (claims[tag]?.nodeId?.let { it != nodeId } == true) {
            claims.remove(tag)
            claimsDropped++
        }
        if (pending[tag]?.nodeId?.let { it != nodeId } == true) {
            pending.remove(tag)
            claimsDropped++
        }
        val hinted = hints.values.count { it.first == tag && it.second.nodeId != nodeId }
        hints.values.removeAll { it.first == tag && it.second.nodeId != nodeId }
        claimsDropped += hinted
    }

    /** A key answered [keyId] for a tag claimed as [claimedId]: nothing to record, and a different id is counted. */
    private fun agreement(
        keyId: String,
        claimedId: String,
    ): ClaimOutcome {
        if (keyId == claimedId) return ClaimOutcome.Redundant
        claimsDropped++
        return ClaimOutcome.Contradicted
    }

    /** Whether a record's outcome settles an announced entry (it goes), rather than leaving it to try again. */
    private fun ClaimOutcome.settles(): Boolean = this != ClaimOutcome.Full

    private fun isOwn(tag: String): Boolean = tag == ownCurrent || tag == ownPrevious

    private fun ignored(): ClaimOutcome {
        claimsIgnored++
        return ClaimOutcome.Ignored
    }

    private fun contested(): ClaimOutcome {
        claimsVoided++
        return ClaimOutcome.Contested
    }

    private fun rateLimited(): ClaimOutcome {
        tagNextRateLimited++
        return ClaimOutcome.RateLimited
    }

    private companion object {
        /** One presence-key generation: the key rotates weekly with the signed prekey (ADR 2026-11.xp6d). */
        const val KEY_GENERATION_MS = 7L * 24 * 60 * 60 * 1_000
        const val PREFIX_SHOWN = 4
        const val QUEUE_SLACK = 4
        const val QUEUE_FLOOR = 256

        /** The 16 bytes of a blinded form, or null when it is not a canonical 26-char form carrying the tag prefix. */
        fun tagBytes(tag: String): ByteArray? = NodeId.toBytesOrNull(tag)?.takeIf(BlindTag::hasPrefix)
    }
}
