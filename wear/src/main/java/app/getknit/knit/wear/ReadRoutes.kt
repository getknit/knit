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
 * until other watches show which transport their bond carries. The route that answered last leads, so a watch
 * whose phone has no RFCOMM server does not pay for the lookup on every read. A device the stack knows only
 * over LE has no Classic link to ride.
 */
object ReadRoutes {
    fun plan(
        leOnly: Boolean,
        lastGood: Via?,
    ): List<Via> {
        val le = listOf(Via.Le, Via.Le)
        return when {
            leOnly -> le
            lastGood == Via.Le -> le + Via.Classic
            else -> listOf(Via.Classic) + le
        }
    }
}
