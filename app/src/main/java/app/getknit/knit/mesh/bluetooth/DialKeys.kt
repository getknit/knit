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
    ): String = if (!snap.blinded) snap.nodeId else "${formKey(snap.advertised)}@${address ?: NO_ADDRESS}"

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
     * holds a link (a lonely dial crossing the peer's own, ADR 2026-09.hj4a), so it is no failure to back off from. An
     * unresolved form's handle is never linked.
     */
    fun linkedNow(
        record: DialPlan.InFlight?,
        dialKey: String,
        linked: Set<String>,
    ): Boolean = (record?.peerKey ?: dialKey) in linked
}
