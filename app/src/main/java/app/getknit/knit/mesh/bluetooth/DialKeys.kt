package app.getknit.knit.mesh.bluetooth

/**
 * The dial key (ADR 2026-11.xp6d's reader, obligation 9): what a dial, its in-flight slot and its backoff are booked
 * under once an advert can name a tag, and how the per-peer filters read that backoff. Pure, beside [DialPlan].
 */
internal object DialKeys {
    /** A blinded dial key's address part when no address is known: such a peer is never dialable anyway. */
    private const val NO_ADDRESS = "?"

    /** What starts an address streak's key: never an id, a handle or a dial key. */
    private const val ADDRESS_MARK = "@"

    /**
     * The one key a dial is booked under (obligation 9, the bluetooth design pass's Blocking finding): the unit of the
     * in-flight table, the backoff and `beginConnect`. A clear advert's is its id, so a clear peer's backoff behaves
     * exactly as it always did. A blinded advert's is its form and the [address] it airs from, the form as its handle
     * ([formKey], `FormKeys`): a failed tag dial is the tag's and the address's, never the unproven id its reply names,
     * and a replayer at another address never backs the tag's owner off under this key. (The handle's own streak,
     * [handleKey], is per tag whatever the address, so a replayer refusing dials to an unresolved tag can delay the
     * real airer's dial up to the backoff cap; contacts, whose snapshots carry an id, are never read through it.)
     * Redacted by construction: the handle shows no tag.
     */
    fun dialKey(
        snap: BlePresenceTracker.Snapshot,
        formKey: (String) -> String,
        address: String?,
    ): String = if (!snap.blinded) snap.nodeId else tagDialKey(formKey(snap.advertised), address)

    /** A blinded advert's dial key, from its form's [handle] and the [address] it airs from. */
    fun tagDialKey(
        handle: String,
        address: String?,
    ): String = "$handle@${address ?: NO_ADDRESS}"

    /**
     * What a link holds off, of the [dialKey] it was dialed under: that key only when the link is [proven] and the dial
     * went to a tag (the bluetooth design pass of 2026-11-06). A clear dial's key is an id, which `Suppressor.Linked`
     * already covers; a link the peer dialed has none. Null when it holds off nothing.
     */
    fun heldDial(
        dialKey: String?,
        proven: Boolean,
    ): String? = dialKey?.takeIf { proven && FormKeys.isForm(it) }

    /**
     * Whether [snap] is the tag at the address a held proven link of ours was dialed at ([held], from [heldDial]): that
     * device answered our own dial and proved an id, so another dial reaches the same phone and is refused. The one
     * exception to the [BlePresenceTracker.Suppressor] an answer carries (ADR 2026-11.xp6d's amendment of 2026-11-06),
     * read beside it: only our own completed dial writes a key, and it names the address, so no claim, hint, `TAG_NEXT` or
     * HELLO `t` can make one, and a replayer dialed at her own address adds only that address. [dialKeyOf] is read only
     * when it can matter.
     */
    fun dialHeld(
        snap: BlePresenceTracker.Snapshot,
        held: Set<String>,
        dialKeyOf: (BlePresenceTracker.Snapshot) -> String,
    ): Boolean = snap.blinded && held.isNotEmpty() && dialKeyOf(snap) in held

    /**
     * The address streak a failed dial to an unresolved form also advances (the bluetooth review of step 5a): the
     * address alone, so an airer that changes its tag every few seconds from one address, whose every tag is a new dial
     * key, still backs off. Null for a clear dial key, a dial key of a resolved peer ([unresolved] false), and one with no
     * address.
     */
    fun addressKey(
        dialKey: String,
        unresolved: Boolean = FormKeys.isForm(dialKey),
    ): String? {
        if (!unresolved || !FormKeys.isForm(dialKey)) return null
        val address = dialKey.substringAfterLast('@', NO_ADDRESS)
        return if (address == NO_ADDRESS) null else "$ADDRESS_MARK$address"
    }

    /**
     * The handle streak a failed dial to an unresolved form also advances (the bluetooth review of step 5a, round 2): the
     * form's handle alone, which is that form's own per-peer key, so one tag aired on the 1M and the Coded set (each its
     * own random address, so two dial keys) keeps one growing streak, and [backedOff] reads it as the snapshot's key.
     * Null as [addressKey] is.
     */
    fun handleKey(
        dialKey: String,
        unresolved: Boolean = FormKeys.isForm(dialKey),
    ): String? = if (!unresolved || !FormKeys.isForm(dialKey)) null else dialKey.substringBeforeLast('@')

    /**
     * Whether the backoff book counts [key] present for its prune, given the [present] keys (per-peer keys, dial keys,
     * address keys and blinded snapshots' handles): [key] itself, or a tag's dial key whose handle is present at any
     * address. A timed-out dial forgets the address it went to (#309), so until the tag is heard there again its snapshot's
     * dial key names no address, and the streak booked at the old one must not be pruned in that gap.
     */
    fun present(
        key: String,
        present: Set<String>,
    ): Boolean = key in present || handleKey(key, unresolved = true)?.let { it in present } == true

    /**
     * The per-peer keys of [snaps] backing off, for every filter that reads backoff by per-peer key ([PromotionPolicy],
     * the lonely pick, the boost trigger, the scan floor, the cold hunts): one whose dial key ([dialKeyOf]), whose own key
     * (a link's flap backs off the id) or, while it resolves to nobody, whose address ([addressKey]) is in [active].
     * [active] is returned with them, so an id with no snapshot still reads as backing off.
     */
    fun backedOff(
        snaps: List<BlePresenceTracker.Snapshot>,
        active: Set<String>,
        dialKeyOf: (BlePresenceTracker.Snapshot) -> String?,
    ): Set<String> {
        if (active.isEmpty()) return active
        val viaDial =
            snaps.filter { s ->
                val dialKey = if (s.nodeId in active) null else dialKeyOf(s)
                dialKey != null && (dialKey in active || addressKey(dialKey, FormKeys.isForm(s.nodeId)) in active)
            }
        return if (viaDial.isEmpty()) active else active + viaDial.map { it.nodeId }
    }

    /**
     * Whether a dial booked as [record] (under [dialKey]) that failed found its peer linked meanwhile: its resolved id
     * holds a link that came up during the dial (a lonely dial crossing the peer's own, ADR 2026-09.hj4a), so it is no
     * failure to back off from. A dial that began with its peer already held ([DialPlan.InFlight.linkedAtStart]) crossed
     * nothing: it is an ordinary refusal, and it backs off (hj4a's amendment of 2026-11-06: at zero wait those were 158
     * dials in 40 s). No record reads as a crossing, by [dialKey] alone: a clear crossing's record went with the inbound
     * link's registration, and a link's end has none. An unresolved form's handle is never linked.
     */
    fun linkedNow(
        record: DialPlan.InFlight?,
        dialKey: String,
        linked: Set<String>,
    ): Boolean = if (record == null) dialKey in linked else !record.linkedAtStart && record.peerKey in linked
}
