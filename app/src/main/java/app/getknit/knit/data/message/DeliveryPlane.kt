package app.getknit.knit.data.message

/**
 * How a delivery receipt reached this device — the plane recorded with the ✓✓ tick
 * ([MessageEntity.receivedVia]), so the UI can say a message got there over the Internet rather than a
 * nearby radio.
 *
 * Stored as [code], not as the enum name: an unknown code (a row written by a newer build, or a value
 * retired later) reads back as [Unknown] via [fromCode] instead of throwing, and it matches how the rest
 * of [MessageEntity] keeps its small closed sets (`moderation`, `kind`). Codes are frozen — append new
 * planes, never renumber.
 *
 * [Bluetooth] and [WifiAware] are the finer split of [Nearby]. The inbound path records them from the child
 * transport's `TransportKind` that `CompositeMeshTransport` stamps on each `InboundFrame` (ADR 2026-10.e42d),
 * for the developer view and the field log's latency lines. [Nearby] remains for rows written before that
 * and for a frame whose kind names no radio. The chat has nothing different to say about the three, so a
 * reader treats them all as "arrived nearby" and asks [shortRange], never `== Nearby`. [LoRa] earns its own
 * face: a delivery over a Meshtastic board (ADR 038/039) is kilometre-range, slow, and photo-less, which the
 * tick is worth saying.
 */
@Suppress("MagicNumber") // the literals ARE the frozen stored codes; naming each one would only restate it
enum class DeliveryPlane(
    val code: Int,
) {
    /** No plane recorded — a row acked by a build older than the column, or a code we don't know. */
    Unknown(0),

    /** A phone radio, which one not recorded: a row written before ADR 2026-10.e42d, or a frame whose kind names none. */
    Nearby(1),

    /** The Internet plane: the receipt was pulled off a spool (`docs/SPOOL_PROTOCOL.md`). */
    Internet(2),

    /** A Bluetooth LE delivery: a link or the side channel, which one not recorded (ADR 2026-10.e42d). */
    Bluetooth(3),

    /** A Wi-Fi Aware delivery: a data path or the message plane, which one not recorded (ADR 2026-10.e42d). */
    WifiAware(4),

    /** The LoRa plane: the frame (or the receipt) reached us over a Meshtastic board (ADR 038/039). */
    LoRa(5),
    ;

    /**
     * Whether this plane is a **short-range radio** — a phone-to-phone data path, so bytes can ride it.
     * The [Nearby] family and nothing else, which is the "treat all three as arrived nearby" rule above
     * turned into a question a caller can ask; it is the [app.getknit.knit.mesh.MeshTransport.shortRange]
     * distinction, read off a stored receipt instead of a live transport.
     *
     * [LoRa] and [Internet] are false for different reasons and both matter. A board carries a frame but
     * never a blob, so a LoRa ack proves nothing about bytes; and the Internet plane is the one an
     * attachment push *feeds*, so an ack that rode it is the opposite of evidence the radios coped.
     */
    val shortRange: Boolean get() = this == Nearby || this == Bluetooth || this == WifiAware

    companion object {
        /** The plane for a stored [code], falling back to [Unknown] rather than throwing on an unknown one. */
        fun fromCode(code: Int): DeliveryPlane = entries.firstOrNull { it.code == code } ?: Unknown

        /** The [shortRange] planes' stored codes — a Room `IN (:planes)` predicate can't name the enum. */
        val shortRangeCodes: List<Int> = entries.filter { it.shortRange }.map { it.code }
    }
}
