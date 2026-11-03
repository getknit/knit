package app.getknit.knit.transfer

import app.getknit.knit.mesh.protocol.TransferPayload

/** An OFFER as this phone admits it: a file's facts, or a models offer read through this phone's own table. */
internal class Offered(
    val name: String,
    val size: Long,
    val mime: String?,
    val pins: List<Long>?,
    /** Whether it is worth a notification: a models offer with nothing new for this phone is not. */
    val notify: Boolean,
)

/**
 * How [TransferManager] reads the peer's side of an offer (ADR 2026-11.ejn8), kept apart for detekt's LargeClass as
 * [TransferListener] is. Pure; every list a peer wrote is checked here before anything acts on it.
 */
internal object OfferReading {
    /** The most pins a models offer may name: a bound on the peer's list, well past any table's size. */
    const val MAX_MODELS = 64

    /** A pin is an unsigned 32-bit number (`TranslateDirection.pin`). */
    private const val MAX_PIN = 0xFFFF_FFFFL

    /** A list of pins this side will read at all: bounded, each a pin, none twice. */
    fun wellFormed(pins: List<Long>): Boolean =
        pins.size in 1..MAX_MODELS && pins.all { it in 0..MAX_PIN } && pins.toSet().size == pins.size

    /**
     * [payload] as an offer to admit, or null when it is not one. Without a models shelf (a build without translation)
     * a models offer is the archive file it also is. With one, a models offer is never answered by itself: what this
     * phone holds is read only by the card, so no contact can probe it. A `CTL_TRANSFER` rides every DM path, and an
     * honest sender must be in range to host, so one from further away ([nearby]) is dropped. Its name, size and type
     * are never read then.
     */
    fun read(
        payload: TransferPayload,
        models: TransferModels?,
        nearby: () -> Boolean,
        refused: (why: String) -> Unit = {},
    ): Offered? {
        val pins = payload.md
        if (pins == null || models == null) {
            val name = payload.name?.takeIf { it.isNotBlank() } ?: return null
            val size = payload.size ?: return null
            if (size !in 1..TransferManager.MAX_TRANSFER_BYTES) return null
            return Offered(name, size, payload.mime, pins = null, notify = true)
        }
        // Said, so a trial can tell a refused offer from one that never arrived; the reason only, never the peer's text.
        if (!wellFormed(pins)) return null.also { refused("a malformed pin list (${pins.size} entries)") }
        if (!nearby()) return null.also { refused("a models offer from out of range") }
        val wanted = models.wanted(pins)
        // The card sizes what would cross to this phone: with nothing new, nothing would, not an empty archive's 1 KiB.
        val size = if (wanted.take.isEmpty()) 0L else wanted.bytes
        return Offered(wanted.label, size, mime = null, pins = pins, notify = wanted.take.isNotEmpty())
    }

    /**
     * Why this phone cannot offer [source] now, or null: a phone that may not host ([host], ADR 2026-10.w3dw), a peer out
     * of range, nothing to read, or more than a transfer may carry.
     */
    fun refusal(
        host: TransferRefusal?,
        nearby: Boolean,
        source: TransferSource?,
    ): TransferRefusal? =
        host ?: when {
            !nearby -> TransferRefusal.NotNearby
            source == null || source.size <= 0L -> TransferRefusal.Unreadable
            source.size > TransferManager.MAX_TRANSFER_BYTES -> TransferRefusal.TooLarge
            else -> null
        }

    /**
     * What the sender of the models offer [offered] sends for an ACCEPT naming [md], or null to refuse it: a subset of
     * what it offered, each pin read through this offer's own list, never the table at large. An ACCEPT naming none is
     * a build taking the archive as a file, and gets every model offered.
     */
    fun taken(
        offered: List<Long>,
        md: List<Long>?,
    ): List<Long>? {
        if (md == null) return offered
        return md.takeIf { wellFormed(it) && offered.containsAll(it) }
    }
}
