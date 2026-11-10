package app.getknit.knit.wear

/** The transport a read of the phone went over. [label] is what the status screen's footer names. */
enum class Via(
    val label: String,
) {
    /** RFCOMM over the Classic (BR/EDR) link a paired watch already holds. */
    Classic("Classic"),

    /** The phone's GATT characteristic over an LE connection of its own. */
    Le("LE"),
}

/**
 * The order the reader tries one bonded device in (ADR 2026-09.wetm, third amendment). Pure, so it is tested.
 *
 * RFCOMM first: it rides the Classic link the watch keeps to its phone, so it needs no LE connection on a
 * phone whose GATT table the mesh may have filled, no advert, and no LE pairing. LE twice after it — the lab
 * pair's first LE connect failed with a fast 133 one read in six, and a second 750 ms later cleared it — kept
 * until other watches show which transport their bond carries. A device the stack knows only over LE has no
 * Classic link to ride.
 *
 * RFCOMM leads on every read, whichever route answered last. It once followed the last answer, so one Classic miss
 * (the phone app restarting during a read) left the lab watch on LE for good: an LE connection, a GATT slot on the
 * phone and an LE re-pairing every five minutes, the costs the third amendment moved off LE. A phone with no
 * RFCOMM record (Settings › Watch off) has no GATT service either, so LE cannot answer it, yet each LE leg would
 * still connect to the phone (its presence advert is connectable), take one of its LE slots and run a service
 * discovery: [afterClassicMiss] skips them when the Classic attempt failed fast.
 */
object ReadRoutes {
    fun plan(leOnly: Boolean): List<Via> {
        val le = listOf(Via.Le, Via.Le)
        return if (leOnly) le else listOf(Via.Classic) + le
    }

    /**
     * Whether the LE legs still run after a Classic attempt that failed in [tookMs]. A miss under [FAST_MISS_MS]
     * reached the phone over the Classic link the watch holds and found no record: the phone has the service
     * off, so its GATT has none either. A slower failure (a page or connect timeout) may be the Classic link
     * alone, which LE can still get round.
     */
    fun afterClassicMiss(tookMs: Long): Boolean = tookMs >= FAST_MISS_MS

    const val FAST_MISS_MS = 2_000L
}
