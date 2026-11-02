package app.getknit.knit.mesh.bluetooth

import app.getknit.knit.identity.NodeId
import app.getknit.knit.mesh.crypto.BlindTag

/**
 * Resolves an advertised form ([AdvertOrder.Form]) to a node id (ADR 2026-11.xp6d, knit-next #285). A clear advert
 * is its own id. A blinded one names a [BlindTag], resolved in this order:
 *
 * 1. **Malformed** first: bytes without the tag prefix answer [Resolution.Malformed] before any HMAC.
 * 2. **Own:** my current or previous tag ([setOwnTags]).
 * 3. **Direct contacts' keys** ([setKeys]): one [BlindTag.Matcher] per key, built once and rebuilt only when that key
 *    changes. A tag carries no key generation, so a contact's replaced key is kept and tried until a tag under its
 *    successor is first heard, at most [Config.previousKeyMaxMs] (one key generation): a new key that lands mid-tag
 *    must not leave the tag it is airing unresolved, however long an idle phone keeps that tag.
 * 4. **Link claims** ([onDialed], [onTagNext]): a tag a proven link told us is its own.
 * 5. **Hints** ([onAnswered]): a tag a dialer's HELLO told us is its own, for presence and RSSI only (below).
 * 6. **Caches** memoise step 3 and so never change the answer the order gives: a positive LRU of
 *    [Config.positiveCapacity] key answers, and a negative cache of tags no key matched, for [Config.negativeTtlMs].
 *    Any key change clears the negative cache and the changed ids' positive entries.
 *
 * A sweep of the keys spends one HMAC per key under a per-second budget ([Config.hmacBudgetPerSecond]); a tag met
 * over budget answers [Resolution.OverBudget], is counted, and is not cached negative, so it resolves on a later
 * sighting. A sweep may overshoot by one: a key set larger than the budget still resolves, a tag a second.
 *
 * **Claims** (the design pass of 2026-11-02, every rule blocking):
 *  - a link holds at most one claim, and so does a node id; a newer one (a promoted [onTagNext]) replaces it;
 *  - a claim a held key resolves to a different id is dropped and counted, at the claim and at every resolve;
 *  - two ids claiming one tag void both, and the tag takes no claim again until it would have expired;
 *  - a claim (and an announced tag) never lapses while its link is held, sighted or not (a screen-off phone in deep
 *    suspend can go unheard for a lifetime), and lives [Config.claimLifetimeMs] (the longest tag lifetime plus the
 *    presence linger) past the link's end, so a peer whose link drops still resolves while its tag airs; a claim never
 *    comes from a link's end;
 *  - a dialer believes the responder's HELLO `t` only when it equals the `d` it dialed, or a tag that responder
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
 *    claim.
 *
 * **Residual.** A linked attacker can still name an unlinked *stranger's* tag, which no key of ours contradicts. As a
 * responder's hint it costs nothing (above). As a claim, which the dialer side records and which can suppress a dial,
 * up to one stranger per attacker link is taken for the attacker's id while that link is up, until the tag expires.
 * [nextSpotCheck] offers at most one claimed tag per [Config.spotCheckIntervalMs] for a verification dial, and
 * [onSpotCheck] voids the claimant's claims when the tag's device proves another id; a refusal with no reply is
 * consistent with an honest claim. The hook is kept but not wired (ADR 2026-11.xp6d's wiring): the hint rule covers
 * the responder case, and a claim's harm ends with its link.
 *
 * Pure: no Android, the elapsed clock injected ([nowMs], milliseconds; every expiry here is on it). Links are the
 * caller's opaque keys ([L], compared by `equals`). [counters] carry counts only, never a key or a tag.
 *
 * **Threading.** Single-threaded by contract: a [BlindTag.Matcher] holds a `Mac`, so the transport runs every call on
 * one confined worker. Never the main thread (the scan callback runs on the main looper, and a sweep is up to
 * [Config.hmacBudgetPerSecond] HMACs), and never under the transport's `lock` across a sweep. So the per-id maps a
 * sighting feeds (`sight()`'s) are keyed only after an asynchronous resolve, and link-up and sighting events must keep
 * their order across that hop: a sighting resolved after a link event it preceded must not undo it.
 */
@Suppress("TooManyFunctions") // one entry per handshake event, each a claim rule's
internal class AdvertAliases<L : Any>(
    private val nowMs: () -> Long,
    private val config: Config = Config(),
) {
    data class Config(
        val positiveCapacity: Int = 256,
        val negativeCapacity: Int = 1024,
        val negativeTtlMs: Long = 60_000,
        val hmacBudgetPerSecond: Int = 2048,
        /** The longest a tag airs (ADR 2026-11.xp6d: a jittered 600–900 s lifetime). */
        val tagLifetimeMaxMs: Long = 900_000,
        /** The presence tracker's linger past a peer's last sighting. */
        val presenceLingerMs: Long = 90_000,
        /**
         * The longest a contact's replaced key is still tried when no tag under its successor is heard: one key
         * generation (the weekly rotation). Hearing such a tag drops it at once.
         */
        val previousKeyMaxMs: Long = KEY_GENERATION_MS,
        val maxClaims: Int = 128,
        val spotCheckIntervalMs: Long = 300_000,
    ) {
        val claimLifetimeMs: Long get() = tagLifetimeMaxMs + presenceLingerMs
    }

    /**
     * A direct contact's presence key, and the one it replaced with the elapsed time it is tried until. A caller that
     * knows only the [current] key passes it alone: when it differs from the one held, the held one becomes the
     * previous key, tried until a tag under [current] is heard or [Config.previousKeyMaxMs] passes. [previousUntilMs]
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
        data class Resolved(
            val nodeId: String,
            val source: Source,
        ) : Resolution

        /** One of my own tags, current or previous. */
        data object Own : Resolution

        data object Unresolved : Resolution

        /** Not tried: the HMAC budget for this second is spent. Not cached; try again on a later sighting. */
        data object OverBudget : Resolution

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

        /** A held key already resolves the tag to the same id: nothing to record. */
        Redundant,

        /** A held key resolves the tag to a different id: dropped. */
        Contradicted,

        /** Another id claims the tag: both voided. */
        Contested,

        /** Not a claim under the rules (a `t` that is not the `d` dialed, a clear form, my own tag, no hint). */
        Ignored,

        /** The claim table is full; held claims keep their place. */
        Full,
    }

    /** Diagnostics: counts only, never a key or a tag. */
    data class Counters(
        val resolvedByKey: Long,
        val keyCacheHits: Long,
        val resolvedByClaim: Long,
        val resolvedByHint: Long,
        val unresolved: Long,
        val overBudget: Long,
        val malformed: Long,
        val hmacs: Long,
        val claimsRecorded: Long,
        val claimsDropped: Long,
        val claimsVoided: Long,
        val claimsIgnored: Long,
        val claimsRefusedFull: Long,
        val spotChecks: Long,
        /** Tag dials and spot checks that proved another id than a claim or hint named. */
        val proofMismatches: Long,
    )

    /** A claimed tag offered for a verification dial: dial [tag] by tag and pass its proven id to [onSpotCheck]. */
    data class SpotCheck(
        val tag: String,
        val claimedId: String,
    ) {
        override fun toString(): String = "SpotCheck(${tag.take(PREFIX_SHOWN)}…)"
    }

    private class KeyEntry(
        val current: ByteArray,
        val currentMatcher: BlindTag.Matcher,
        val previous: ByteArray?,
        val previousMatcher: BlindTag.Matcher?,
        val previousUntilMs: Long?,
    ) {
        val matcherCount: Int get() = if (previousMatcher == null) 1 else 2

        /** Which of this contact's keys minted [tag]: its current one, its previous one, or neither (null). */
        fun match(tag: ByteArray): Boolean? =
            when {
                currentMatcher.matches(tag) -> true
                previousMatcher?.matches(tag) == true -> false
                else -> null
            }

        fun withoutPrevious(): KeyEntry = KeyEntry(current, currentMatcher, null, null, null)

        fun sameAs(other: KeyEntry): Boolean = current.contentEquals(other.current) && previous.contentEqualsNullable(other.previous)

        fun matcherFor(key: ByteArray): BlindTag.Matcher? =
            when {
                current.contentEquals(key) -> currentMatcher
                previous != null && previous.contentEquals(key) -> previousMatcher
                else -> null
            }
    }

    private class Claim<L>(
        val link: L,
        val nodeId: String,
        var expiresAtMs: Long,
        /** Recorded from a dial to this very tag that proved [nodeId] ([onDialed]); never offered for a spot check. */
        val dialProven: Boolean = false,
        var checked: Boolean = dialProven,
    )

    private sealed interface KeyAnswer {
        data class Found(
            val nodeId: String,
        ) : KeyAnswer

        data object None : KeyAnswer

        data object OverBudget : KeyAnswer
    }

    private var ownCurrent: String? = null
    private var ownPrevious: String? = null
    private var keys: Map<String, KeyEntry> = emptyMap()

    private val positive =
        object : LinkedHashMap<String, String>(INITIAL_CAPACITY, LOAD_FACTOR, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean = size > config.positiveCapacity
        }
    private val negative =
        object : LinkedHashMap<String, Long>() {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean = size > config.negativeCapacity
        }

    private val claims = HashMap<String, Claim<L>>()
    private val pending = HashMap<String, Claim<L>>()
    private val hints = HashMap<L, Pair<String, Claim<L>>>()
    private val voided = HashMap<String, Long>()

    /**
     * Links a handshake event named and [onLinkEnded] has not ended: their claims, announced tags and hints never lapse,
     * sighted or not, and get one claim lifetime from the link's end.
     */
    private val heldLinks = HashSet<L>()

    private var windowStartMs: Long? = null
    private var spentInWindow = 0
    private var lastSpotCheckMs: Long? = null

    private var resolvedByKey = 0L
    private var keyCacheHits = 0L
    private var resolvedByClaim = 0L
    private var resolvedByHint = 0L
    private var unresolved = 0L
    private var overBudget = 0L
    private var malformed = 0L
    private var hmacs = 0L
    private var claimsRecorded = 0L
    private var claimsDropped = 0L
    private var claimsVoided = 0L
    private var claimsIgnored = 0L
    private var claimsRefusedFull = 0L
    private var spotChecks = 0L
    private var proofMismatches = 0L

    /** My own advertised tags, so my own advert (heard back, or claimed by another) is never taken for a peer. */
    fun setOwnTags(
        current: String?,
        previous: String?,
    ) {
        ownCurrent = current
        ownPrevious = previous
    }

    /**
     * The direct contacts' presence keys, `nodeId → key`, replacing the whole set. A matcher is rebuilt only for a key
     * whose bytes changed; a key replaced without an explicit previous stays tried as [ContactKey] says. Any
     * change clears the negative cache and drops the positive entries of every id whose keys changed or went.
     */
    fun setKeys(contacts: Map<String, ContactKey>) {
        val now = nowMs()
        val next = HashMap<String, KeyEntry>(contacts.size)
        val changed = HashSet<String>()
        for ((id, contact) in contacts) {
            val held = keys[id]
            val entry = entryFor(contact, held, now)
            next[id] = entry
            if (held == null || !held.sameAs(entry)) changed += id
        }
        keys.keys.filterTo(changed) { it !in contacts }
        keys = next
        if (changed.isNotEmpty()) invalidate(changed)
    }

    /** The node id [form] names, or why there is none. */
    fun resolve(form: AdvertOrder.Form): Resolution {
        if (!form.blinded) return Resolution.Resolved(form.advertised, Source.Clear)
        val tag = form.advertised
        val bytes = tagBytes(tag)
        if (bytes == null) {
            malformed++
            return Resolution.Malformed
        }
        val now = nowMs()
        expire(now)
        if (tag == ownCurrent || tag == ownPrevious) return Resolution.Own
        return when (val answer = keyAnswer(tag, bytes, now)) {
            is KeyAnswer.Found -> {
                dropContradicted(tag, answer.nodeId)
                resolvedByKey++
                Resolution.Resolved(answer.nodeId, Source.Key)
            }

            KeyAnswer.OverBudget -> {
                overBudget++
                Resolution.OverBudget
            }

            KeyAnswer.None -> {
                // An announced tag heard for the first time becomes its link's claim.
                pending.remove(tag)?.let { record(it.link, tag, it.nodeId, now, keyChecked = true) }
                linkAnswer(tag)
            }
        }
    }

    /** A claim's answer, else a hint's when every hint for [tag] names one id. */
    private fun linkAnswer(tag: String): Resolution {
        val claim = claims[tag]
        val hinted =
            hints.values
                .filter { it.first == tag }
                .map { it.second.nodeId }
                .distinct()
                .singleOrNull()
        return when {
            claim != null -> {
                resolvedByClaim++
                Resolution.Resolved(claim.nodeId, Source.Claim)
            }

            hinted != null -> {
                resolvedByHint++
                Resolution.Resolved(hinted, Source.Hint)
            }

            else -> {
                unresolved++
                Resolution.Unresolved
            }
        }
    }

    /**
     * The dialer's side of a handshake on [link]: I dialed [dialed], the responder's HELLO told [told] (its `t`,
     * decoded; null when absent) and proved [provenId]. Records [told] as the link's claim only when it equals the
     * tag dialed, or is a tag [provenId] announced and we hold pending; never when a held key resolves the dialed tag
     * to another id. A dialed tag with a different [told] is a rotation edge (the responder rotated between my sighting
     * and my dial): [told] is held pending for the link, as an [onTagNext] would be, and becomes its claim when heard.
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
        val dialedBytes = if (dialed.blinded) tagBytes(dialed.advertised) else null
        if (dialedBytes == null || told == null || told == provenId) return ignored()
        val announced = told != dialed.advertised && pending[told]?.nodeId == provenId
        val answer = keyAnswer(dialed.advertised, dialedBytes, now)
        return when {
            answer is KeyAnswer.Found -> {
                agreement(answer.nodeId, provenId)
            }

            told != dialed.advertised && !announced -> {
                onTagNext(link, provenId, told)
            }

            else -> {
                if (announced) pending.remove(told)
                // The key check above was on the dialed tag; an announced tag is checked as it is recorded.
                record(link, told, provenId, now, keyChecked = !announced && answer == KeyAnswer.None, dialProven = !announced)
            }
        }
    }

    /**
     * The responder's side: the dialer on [link] proved [provenId] and its HELLO told [told]. Held as the link's hint
     * ([Source.Hint]: presence and RSSI only, never to suppress a dial), one per link and per id, unless a held key
     * resolves the tag to another id or the tag is void. A told form equal to the id (a clear dialer) is nothing to hold.
     */
    fun onAnswered(
        link: L,
        told: String?,
        provenId: String,
    ): ClaimOutcome {
        val now = nowMs()
        expire(now)
        heldLinks += link
        val bytes = told?.takeIf { it != provenId && !isOwn(it) }?.let(::tagBytes)
        if (told == null || bytes == null) return ignored()
        if (told in voided) return contested()
        val answer = keyAnswer(told, bytes, now)
        if (answer is KeyAnswer.Found) return agreement(answer.nodeId, provenId)
        hints.values.removeAll { it.second.nodeId == provenId }
        hints[link] = told to Claim(link, provenId, now + config.claimLifetimeMs)
        return ClaimOutcome.Hinted
    }

    /**
     * A dial to [dialed] proved [provenId]. Every hint, announced tag or claim naming another id for that tag that no
     * dial of ours proved is a lie (the dial went to the address airing the tag), so the links and ids that made it lose
     * all of theirs, and the tag stays open for its owner's claim. A claim another dial to the same tag proved is a
     * contest instead (someone re-airs the tag): the tag is void, as when two ids claim it. Ignored when a held key
     * resolves the tag to an id other than [provenId]: the key outranks the proof. [onDialed] calls it first; a tag dial
     * with no claim to record, or a spot check, calls it alone. True when anything was voided.
     */
    fun onDialProved(
        dialed: AdvertOrder.Form,
        provenId: String,
    ): Boolean {
        val bytes = if (dialed.blinded) tagBytes(dialed.advertised) else null
        val now = nowMs()
        val answer = bytes?.let { keyAnswer(dialed.advertised, it, now) }
        if (bytes == null || (answer is KeyAnswer.Found && answer.nodeId != provenId)) return false
        val tag = dialed.advertised
        val rivals =
            (listOfNotNull(claims[tag], pending[tag]) + hints.values.filter { it.first == tag }.map { it.second })
                .filter { it.nodeId != provenId }
        val (proven, liars) = rivals.partition { it.dialProven }
        liars.forEach { voidLiar(it.link, it.nodeId) }
        if (liars.isNotEmpty()) proofMismatches++
        if (proven.isNotEmpty()) void(tag, now, incoming = 0)
        return rivals.isNotEmpty()
    }

    /**
     * [link], proven as [provenId], announced its next tag ([tag], `TAG_NEXT`). Held pending, resolving nothing, until
     * it is first heard ([resolve]) or a dial's HELLO names it ([onDialed]); then it replaces the link's claim.
     */
    fun onTagNext(
        link: L,
        provenId: String,
        tag: String,
    ): ClaimOutcome {
        val now = nowMs()
        expire(now)
        heldLinks += link
        val bytes = tagBytes(tag)
        if (bytes == null || isOwn(tag)) return ignored()
        if (tag in voided) return contested()
        val answer = keyAnswer(tag, bytes, now)
        if (answer is KeyAnswer.Found) return agreement(answer.nodeId, provenId)
        val rival = pending[tag] ?: claims[tag]
        if (rival != null && rival.nodeId != provenId) {
            void(tag, now)
        } else {
            pending.values.removeAll { it.link == link || it.nodeId == provenId }
            pending[tag] = Claim(link, provenId, now + config.claimLifetimeMs)
        }
        return if (tag in voided) ClaimOutcome.Contested else ClaimOutcome.Pending
    }

    /**
     * [link] ended: its hint goes, being unproven past the link. Its claim and any announced tag, which never lapse while
     * the link is held, live one [Config.claimLifetimeMs] from now. The wiring calls it for every link it named in a
     * handshake event, or that link's entries never lapse.
     */
    fun onLinkEnded(link: L) {
        hints.remove(link)
        if (heldLinks.remove(link)) {
            val until = nowMs() + config.claimLifetimeMs
            (claims.values + pending.values).filter { it.link == link }.forEach { it.expiresAtMs = maxOf(it.expiresAtMs, until) }
        }
    }

    /**
     * One claimed tag for a verification dial, at most one per [Config.spotCheckIntervalMs], each claim at most once,
     * the oldest first; null when none is due.
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

    fun counters(): Counters =
        Counters(
            resolvedByKey = resolvedByKey,
            keyCacheHits = keyCacheHits,
            resolvedByClaim = resolvedByClaim,
            resolvedByHint = resolvedByHint,
            unresolved = unresolved,
            overBudget = overBudget,
            malformed = malformed,
            hmacs = hmacs,
            claimsRecorded = claimsRecorded,
            claimsDropped = claimsDropped,
            claimsVoided = claimsVoided,
            claimsIgnored = claimsIgnored,
            claimsRefusedFull = claimsRefusedFull,
            spotChecks = spotChecks,
            proofMismatches = proofMismatches,
        )

    private fun entryFor(
        contact: ContactKey,
        held: KeyEntry?,
        now: Long,
    ): KeyEntry {
        val current = contact.current.copyOf()
        val currentMatcher = held?.matcherFor(current) ?: BlindTag.Matcher(current)
        val (previous, until) =
            when {
                contact.previous != null -> contact.previous.copyOf() to (contact.previousUntilMs ?: (now + config.previousKeyMaxMs))
                held == null -> null to null
                !held.current.contentEquals(current) -> held.current to now + config.previousKeyMaxMs
                else -> held.previous to held.previousUntilMs
            }
        val live = previous?.takeIf { until != null && until > now && !it.contentEquals(current) }
        return if (live == null) {
            KeyEntry(current, currentMatcher, null, null, null)
        } else {
            KeyEntry(current, currentMatcher, live, held?.matcherFor(live) ?: BlindTag.Matcher(live), until)
        }
    }

    private fun invalidate(changed: Set<String>) {
        negative.clear()
        positive.values.removeAll { it in changed }
    }

    /** Drops what has expired: replaced keys past their overlap, claims, announced tags, hints and voids. */
    private fun expire(now: Long) {
        val lapsed = keys.filterValues { it.previousUntilMs != null && it.previousUntilMs <= now }
        if (lapsed.isNotEmpty()) {
            keys = keys + lapsed.mapValues { (_, e) -> e.withoutPrevious() }
            positive.values.removeAll { it in lapsed.keys }
        }
        // A held link's entries never lapse: a screen-off phone in deep suspend can go unheard past a lifetime while its
        // link stands. Their lifetime starts at onLinkEnded.
        claims.values.removeAll { it.link !in heldLinks && it.expiresAtMs <= now }
        pending.values.removeAll { it.link !in heldLinks && it.expiresAtMs <= now }
        hints.values.removeAll { it.second.link !in heldLinks && it.second.expiresAtMs <= now }
        voided.values.removeAll { it <= now }
    }

    /** The keys' answer for [tag]: the caches, then a budgeted sweep whose answer they keep. */
    private fun keyAnswer(
        tag: String,
        bytes: ByteArray,
        now: Long,
    ): KeyAnswer = cachedAnswer(tag, now) ?: sweep(tag, bytes, now)

    private fun cachedAnswer(
        tag: String,
        now: Long,
    ): KeyAnswer? {
        val hit = positive[tag]
        if (hit != null) {
            keyCacheHits++
            return KeyAnswer.Found(hit)
        }
        val until = negative[tag] ?: return null
        if (until > now) return KeyAnswer.None
        negative.remove(tag)
        return null
    }

    private fun sweep(
        tag: String,
        bytes: ByteArray,
        now: Long,
    ): KeyAnswer {
        if (keys.isEmpty()) return KeyAnswer.None
        val start = windowStartMs
        if (start == null || now - start >= BUDGET_WINDOW_MS || now < start) {
            windowStartMs = now
            spentInWindow = 0
        }
        if (spentInWindow >= config.hmacBudgetPerSecond) return KeyAnswer.OverBudget
        val cost = keys.values.sumOf { it.matcherCount }
        spentInWindow += cost
        hmacs += cost
        var current = false
        val found = keys.entries.firstOrNull { (_, entry) -> entry.match(bytes)?.also { current = it } != null }
        return if (found != null) {
            val (id, entry) = found
            if (current && entry.previous != null) {
                // A tag under the successor is on air: the replaced key has nothing left to resolve.
                keys = keys + (id to entry.withoutPrevious())
                positive.values.removeAll { it == id }
            }
            positive[tag] = id
            KeyAnswer.Found(id)
        } else {
            negative[tag] = now + config.negativeTtlMs
            KeyAnswer.None
        }
    }

    /** Records [nodeId]'s claim to [tag] on [link], replacing the link's and the id's other claim. */
    private fun record(
        link: L,
        tag: String,
        nodeId: String,
        now: Long,
        keyChecked: Boolean,
        dialProven: Boolean = false,
    ): ClaimOutcome {
        val bytes = tagBytes(tag)
        if (bytes == null || isOwn(tag)) return ignored()
        if (tag in voided) return contested()
        val answer = if (keyChecked) KeyAnswer.None else keyAnswer(tag, bytes, now)
        if (answer is KeyAnswer.Found) return agreement(answer.nodeId, nodeId)
        val rival = claims[tag]
        if (rival != null && rival.nodeId != nodeId) {
            void(tag, now)
            return ClaimOutcome.Contested
        }
        return place(Claim(link, nodeId, now + config.claimLifetimeMs, dialProven), tag)
    }

    /** Places a claim the rules let through: the link's and the id's other claim go first. */
    private fun place(
        claim: Claim<L>,
        tag: String,
    ): ClaimOutcome {
        val replaced: (Claim<L>) -> Boolean = { it.link == claim.link || it.nodeId == claim.nodeId }
        if (claims.values.count { !replaced(it) } >= config.maxClaims) {
            claimsRefusedFull++
            return ClaimOutcome.Full
        }
        claims.values.removeAll(replaced)
        claims[tag] = claim
        claimsRecorded++
        return ClaimOutcome.Recorded
    }

    /**
     * Two ids claimed [tag]: neither resolves it, no hint does either, and none may claim it again until it would have
     * expired. [incoming] counts the claim that found the rival, when there is one.
     */
    private fun void(
        tag: String,
        now: Long,
        incoming: Int = 1,
    ) {
        val hinted = hints.values.count { it.first == tag }
        hints.values.removeAll { it.first == tag }
        claimsVoided += (if (claims.remove(tag) != null) 1 else 0) + (if (pending.remove(tag) != null) 1 else 0) + hinted + incoming
        voided[tag] = now + config.claimLifetimeMs
    }

    /** A key resolved [tag] to [nodeId]: a claim or an announced tag naming another id for it goes. */
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

    private fun isOwn(tag: String): Boolean = tag == ownCurrent || tag == ownPrevious

    private fun ignored(): ClaimOutcome {
        claimsIgnored++
        return ClaimOutcome.Ignored
    }

    private fun contested(): ClaimOutcome {
        claimsVoided++
        return ClaimOutcome.Contested
    }

    private companion object {
        const val BUDGET_WINDOW_MS = 1_000L

        /** One presence-key generation: the key rotates weekly with the signed prekey (ADR 2026-11.xp6d). */
        const val KEY_GENERATION_MS = 7L * 24 * 60 * 60 * 1_000
        const val INITIAL_CAPACITY = 16
        const val LOAD_FACTOR = 0.75f
        const val PREFIX_SHOWN = 4

        /** The 16 bytes of a blinded form, or null when it is not a canonical 26-char form carrying the tag prefix. */
        fun tagBytes(tag: String): ByteArray? = NodeId.toBytesOrNull(tag)?.takeIf(BlindTag::hasPrefix)

        fun ByteArray?.contentEqualsNullable(other: ByteArray?): Boolean =
            if (this ==
                null
            ) {
                other == null
            } else {
                other != null && contentEquals(other)
            }
    }
}
