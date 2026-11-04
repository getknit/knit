package app.getknit.knit.mesh

/**
 * Whether a carrier holds back the pull of an attachment it custodies (ADR 2026-10.g24x, and its amendment of
 * 2026-10-07 for group pictures). A carrier pulls the blob a
 * carried chat frame names so a late joiner can fetch it after the author has gone (`InboundPipeline.onCarriedFrame`).
 * But when the frame is a DM whose recipient is linked to us, the recipient is a neighbour fetching it right now, and
 * every carrier pulling beside it is one more copy the author feeds at once: on 2026-10-06 three carriers took three
 * quarters of the author's Bluetooth budget while the recipient waited about 6.5 min for a 394 KB photo.
 *
 * So a pull waits while **every** frame naming the blob is held back, and it was carried less than [DEFER_MS] ago. A DM
 * is held back while its recipient is linked to us. A group's frame we are not a member of is held back while its
 * author is linked to us: the members linked to the author are fetching it from it, and
 * on 2026-10-07 a carrier's pull of a 2 MiB group file asked first and kept both members waiting through its whole
 * ~114 s copy. A member's own copy of its group's frame is never held back, nor is a room frame: a member pulls it
 * anyway, and one that heard the frame ahead of its key pulls it here (#132). A delay, never a veto: once
 * the link goes, or [DEFER_MS] has passed, the database re-arm (`MeshManager.rewantMissingBlobs`, every link-up and
 * 60 s tick) pulls it as before — by then the recipient or members hold it, answer the holder query, and are as good a
 * source as the author. A cleartext receipt purges the frame, and with it the want, sooner; a sealed one is
 * opaque to a carrier and purges nothing. A recipient or member linked to us but not to the author still gets it at
 * once: its own ask reaches us, and `BlobExchange.onRequest` pulls it through on its behalf, which this never holds
 * back.
 *
 * A recipient that holds the DM as a message request not accepted yet fetches nothing (#282, `RequestHold`), and does
 * not echo a carrier's ask either, so a carrier linked to it waits here for the whole [DEFER_MS] before it takes its
 * own copy from the author: the request delays the carriers' copies by up to that, and changes nothing this reads.
 */
internal object CarrierPullPolicy {
    // Long enough for the recipient to have fetched the picture over the link it shares with us — a 2 MB one at the
    // 4 KiB/s base pace takes about 8 min — before we take our own copy; short enough that a carrier still holds it
    // well inside the frame's lifetime for a late joiner.
    const val DEFER_MS = 10 * 60_000L

    /** Whether the pull of the blob [frames] name waits, with [linked] the neighbours we hold a live link to, at [now]. */
    fun defers(
        frames: Collection<CarriedAttachment>,
        linked: Set<String>,
        now: Long,
    ): Boolean = frames.isNotEmpty() && frames.all { f -> now - f.carriedAt < DEFER_MS && heldBack(f, linked) }

    // A DM while its recipient is linked to us; a group's frame we are not in while its author is.
    private fun heldBack(
        f: CarriedAttachment,
        linked: Set<String>,
    ): Boolean =
        when {
            f.recipientId != null -> f.recipientId in linked
            f.groupId != null -> !f.member && f.senderId != null && f.senderId in linked
            else -> false
        }
}
