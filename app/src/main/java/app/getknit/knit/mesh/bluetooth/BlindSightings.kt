package app.getknit.knit.mesh.bluetooth

/**
 * What the reader does with a blinded advert once [AdvertAliases] has answered (ADR 2026-11.xp6d's reader, obligations 8
 * and 11): which per-peer key the sighting lands under, whether it may suppress a dial, what happens to the history the
 * form gathered while it resolved to nobody, and how far the sighting reaches. Pure, so the transport's worker only
 * carries it out.
 *
 * - **Unresolved:** under the form's handle ([FormKeys]), never suppressing a dial.
 * - **A key's answer** attributes the sighting to the contact's id and carries the form's history (its dwell, its
 *   admission sighting, its addresses) to the id: a contact first heard unresolved (a key that arrived late, a slot that
 *   entered the window) keeps its dwell. It suppresses a dial only while that id holds a **proven** link here
 *   ([BlePresenceTracker.Suppressor.Proven], read when the dial is decided, never frozen at the sighting): nothing
 *   counts as linked to X without X's proof on a link, so a contact who passes another's key as hers gains nothing.
 *   Except a **replay** ([replayed]): a tag a key resolves to X, heard from an address whose tag dial proved another id
 *   (`AdvertAliases.Counters.keyProofMismatches`), attributes nothing and carries nothing, so the replayer's address is
 *   never one the transport dials as X.
 * - **A claim** attributes from then on and never backwards: a linked liar naming a stranger's tag must not import its
 *   dwell, admission sighting or RSSI (which feeds `linkRssi` and eviction). The form's own entries are dropped. A claim
 *   one of our own dials proved carried the history already, when the transport recorded it at the dial's proof.
 *   It suppresses a dial only while its claimant's own link is the one held under the id
 *   ([BlePresenceTracker.Suppressor.Claim]), read when the dial is decided: a new link from the same id does not, so a
 *   liar who relinks cannot keep blocking the dial whose proof voids it.
 * - **A hint** feeds presence and RSSI (and the address a dial would need), never the Aware candidates, the side
 *   channel's audience or the boost trigger, and never suppresses a dial: the dial [AdvertOrder.iDial] calls for still
 *   happens, and its proof is what voids a liar.
 * - **My own tag**, and a malformed one, attribute nothing.
 *
 * "Suppressing a dial" here is what an answer may do. Whatever it says, a tag at the address our own held proven dial
 * reached is not dialed again ([DialKeys.dialHeld], ADR 2026-11.xp6d's amendment of 2026-11-06): that rests on our dial, not
 * on any answer.
 */
internal object BlindSightings {
    private val PROVEN = BlePresenceTracker.Suppressor.Proven
    private val NONE = BlePresenceTracker.Suppressor.None

    /** What becomes of the entries the form's handle held while it resolved to nobody. */
    enum class History {
        /** Nothing to move: the sighting lands under the handle itself. */
        NONE,

        /** Moved to the id (a key's answer): the form's dwell, admission sighting, addresses and RSSI go with it. */
        CARRY,

        /** Dropped (a claim's or a hint's answer): the id is attributed from now on, never backwards. */
        DROP,
    }

    data class Attribution(
        /** The per-peer key: the resolved id, or the form's handle. */
        val key: String,
        val suppressor: BlePresenceTracker.Suppressor,
        val history: History,
        /** False for a hint: presence, RSSI and the address only. */
        val full: Boolean,
    )

    /**
     * The attribution of a blinded sighting [resolution] answered, held under [formKey] while unresolved. [replayed] when
     * the sighting's (tag, address) is a replay. Null drops the sighting.
     */
    fun attribute(
        resolution: AdvertAliases.Resolution,
        formKey: String,
        replayed: Boolean,
    ): Attribution? =
        when (resolution) {
            AdvertAliases.Resolution.Own, AdvertAliases.Resolution.Malformed -> {
                null
            }

            AdvertAliases.Resolution.Unresolved -> {
                Attribution(formKey, NONE, History.NONE, full = true)
            }

            is AdvertAliases.Resolution.Resolved -> {
                resolved(resolution, replayed)
            }
        }

    /** How far a blinded sighting reaches in the transport's `sight`. */
    data class Reach(
        /** The addresses a dial would use. */
        val devices: Boolean,
        /** The boost trigger, the Coded tallies, the Aware candidates and the side channel's audience. */
        val reach: Boolean,
        /** The held link's notes: its PHY control, its sighted mark and its recorded form. */
        val linkNotes: Boolean,
    )

    /**
     * How far a sighting whose attribution is [full] (not a hint) reaches: nothing past presence and RSSI unless it is
     * [onTime] (captured after its peer's last link event, `LinkEventStamps`); a hint never reaches the boost, the Aware
     * sets or the side audience, but notes its own link ([ownLink]: the link held under the id is the one whose `t`
     * named this tag), so a blinding dialer's link still gets its PHY control and leaves the never-sighted floor.
     */
    fun reach(
        full: Boolean,
        onTime: Boolean,
        ownLink: Boolean,
    ): Reach = Reach(devices = onTime, reach = onTime && full, linkNotes = onTime && (full || ownLink))

    /**
     * Whether a handshake event for [link] (`onDialed`, `onAnswered`, `onTagNext`) may reach the resolver: only while
     * [registered], the link held under its id now, is that very link. The transport enqueues `onLinkEnded` only after
     * it removes a link from its table, so an event that passes this on the worker is ordered before its link's end; one
     * that fails it would otherwise hold a dead link, and its never-lapsing hint or claim, in `AdvertAliases` for good (a
     * fork proving and closing at once under fresh ids, the security review of step 5a).
     */
    fun <L : Any> current(
        registered: L?,
        link: L,
    ): Boolean = registered === link

    private fun resolved(
        r: AdvertAliases.Resolution.Resolved,
        replayed: Boolean,
    ): Attribution? =
        when (r.source) {
            AdvertAliases.Source.Key -> {
                if (replayed) null else Attribution(r.nodeId, PROVEN, History.CARRY, full = true)
            }

            AdvertAliases.Source.Claim -> {
                // Only while the claimant's own link is the one held under the id, read when the dial is decided.
                Attribution(r.nodeId, r.claimant?.let { BlePresenceTracker.Suppressor.Claim(it) } ?: NONE, History.DROP, full = true)
            }

            AdvertAliases.Source.Hint -> {
                Attribution(r.nodeId, NONE, History.DROP, full = false)
            }

            AdvertAliases.Source.Clear -> {
                Attribution(r.nodeId, BlePresenceTracker.Suppressor.Linked, History.NONE, full = true)
            }
        }
}

/**
 * The (tag, address) pairs whose tag dial proved an id other than the one a contact's key resolves the tag to: a replay
 * ([BlindSightings]). Bounded, the oldest out first; a replay outlives its tag's slot by little, and a new tag or a new
 * address is a new pair. Thread-safe.
 */
internal class ReplayMarks(
    cap: Int = DEFAULT_CAP,
) {
    private val marks =
        object : LinkedHashMap<String, Unit>(INITIAL, LOAD, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Unit>?) = size > cap
        }

    @Synchronized
    fun mark(
        tag: String,
        address: String,
    ) {
        marks[key(tag, address)] = Unit
    }

    @Synchronized
    fun replayed(
        tag: String,
        address: String?,
    ): Boolean = address != null && marks.containsKey(key(tag, address))

    @Synchronized
    fun clear() = marks.clear()

    private fun key(
        tag: String,
        address: String,
    ) = "$tag@$address"

    private companion object {
        const val DEFAULT_CAP = 64
        const val INITIAL = 16
        const val LOAD = 0.75f
    }
}
