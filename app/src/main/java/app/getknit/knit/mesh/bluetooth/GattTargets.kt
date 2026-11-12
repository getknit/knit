package app.getknit.knit.mesh.bluetooth

/**
 * The one rule a blinded GATT payload's read adds to a dial ([withheld]): Android's, contract §5. Pure, so the
 * transport's `dialAccepted`, its boost trigger and the scan's floor only read it.
 */
internal object GattTargets {
    /**
     * Whether a dial target built from a blinded GATT payload read in slot [readSlot] (null for a service-data advert or
     * a clear payload: never withheld here) gives no dial. Two cases, whatever the answer said (a claim, a yielded claim,
     * a hint, or none):
     *  - **At a held proven link's address** ([atProvenLinkAddress]), in any slot: a dial there meets only that link's
     *    own peer. Rule 4 spares a linked address and no read starts at one (`DialPlan.mayReadGatt`), so the payload it
     *    cached before the link is the only tag this phone learns there for the link's life. A peer that rotated and
     *    dialed in within rule 4's lag tells its new tag, and the old one resolves to nobody (a handle) or to a claim
     *    that lapses: keyed on the id alone, the Pixel tag-dialed the linked iPhone whenever the order pointed to us.
     *  - **Stale while its id holds a proven link** ([provenLinked]): read in any slot but [nowSlot], the wall clock's
     *    when the dial is decided. Any other slot, so a clock set back below the read's slot counts as well as one past
     *    its edge. Past our own slot edge `AdvertOrder.iDial` can turn to us on a cached payload's tag, and the first
     *    refused dial of the paired trial of 2026-11-12 (stranger-no-redial-while-linked) went 0.5 s after our rotation,
     *    on a snapshot last sighted before the edge: this is read at decide time, never stamped at the sighting.
     *
     * The sighting still counts for presence. The second case ends where rule 4 forgets the payload and the address is
     * read again ([GattPayloads.forgetPastEdge]): a payload read in the current slot dials as before, the same tag
     * included (a suspended iPhone may re-serve it). An unlinked peer, with no proven link under its id or at its
     * address, is still dialed at its stale tag, which its responder honours as a dialed form.
     *
     * The bound: the second case can be used only by an id that already holds a proven link here, which may withhold a
     * dial to a stranger's device its own claim or hint attributes to it, for at most rule 4's lag past each edge (the
     * 35 s slack plus up to 30 s of jitter, about 65 s), since the stranger's address is unspared and its re-read ends
     * it. The first case reads only our own links' addresses, which nothing said on a link sets.
     */
    fun withheld(
        readSlot: Long?,
        nowSlot: Long,
        provenLinked: Boolean,
        atProvenLinkAddress: Boolean,
    ): Boolean = readSlot != null && (atProvenLinkAddress || (provenLinked && readSlot != nowSlot))
}
