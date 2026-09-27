package app.getknit.knit.wearstatus

import java.util.UUID

/*
 * The phone → watch status snapshot. This package is pure Kotlin with no app imports on purpose: the `:wear`
 * module compiles these same files through a source-directory reference (wear/build.gradle.kts), so both ends
 * read one codec and one pair of UUIDs rather than two copies that can drift. Never import anything outside
 * `java.*` / `kotlin.*` here.
 */

/** One word for the mesh as a whole — the phone decides it, the watch only draws it. */
@Suppress("MagicNumber") // the codes are the wire values, pinned by the golden vector
enum class MeshState(
    internal val code: Int,
) {
    /** The user stopped the mesh (or never started it). */
    Off(0),

    /** A pause from the notification is running; the radios are down until it lapses. */
    Paused(1),

    /** Running and healthy, nobody in short range. */
    Alone(2),

    /** Running with at least one short-range peer. */
    Linked(3),

    /** Running, but no radio is fully healthy (degraded, or held to the foreground). */
    Degraded(4),

    /** Running, but every radio is switched off or absent. */
    NoRadio(5),
    ;

    internal companion object {
        fun ofCode(code: Int): MeshState? = entries.firstOrNull { it.code == code }
    }
}

/** One plane's state, two bits on the wire. */
@Suppress("MagicNumber") // the codes are the wire values, pinned by the golden vector
enum class Plane(
    internal val code: Int,
) {
    /** Not on this phone, or switched off. */
    Absent(0),

    /** Armed but not carrying anything right now (radio off, board disconnected, no relay connected). */
    Down(1),

    /** Up but impaired: degraded or foreground-only. */
    Degraded(2),

    /** Carrying traffic, or ready to. */
    Live(3),
    ;

    internal companion object {
        fun ofCode(code: Int): Plane = entries.first { it.code == (code and PLANE_MASK) }
    }
}

/**
 * What the watch shows. [nearby] is the app header's count (short-range peers only); [relayed] is the Your
 * mesh screen's all-time "passed along" total; [stampSec] is the phone's wall clock in epoch seconds when the
 * snapshot was taken, so the watch can age a cached copy. [extra] is the appended block — null from a phone
 * build that predates it.
 */
data class WearStatus(
    val state: MeshState,
    val nearby: Int,
    val ble: Plane,
    val nan: Plane,
    val lora: Plane,
    val spool: Plane,
    val relayed: Long,
    val stampSec: Long,
    val extra: WearExtra? = null,
)

/**
 * The peer map and the store: [carrying] is the Your mesh screen's "carrying for others" (live chat frames this
 * phone holds for other people); [far] is how many peers are reached only over a long-range plane (LoRa, the
 * internet relay) — never counted in [WearStatus.nearby]; [links] is one [WearLink] mask per peer the watch
 * draws, at most [WearStatusCodec.MAX_LINKS], short-range peers first. No peer identity crosses: a mask is only
 * which planes reach *a* peer, in the phone's own stable order.
 */
data class WearExtra(
    val carrying: Int,
    val far: Int,
    val links: List<Int>,
)

/** The bits of one peer's [WearExtra.links] mask. A zero mask is not a peer (it marks an empty slot). */
object WearLink {
    const val BLE = 0b0001
    const val NAN = 0b0010
    const val LORA = 0b0100
    const val SPOOL = 0b1000
    const val SHORT_RANGE = BLE or NAN
    const val MASK = 0b1111
}

/**
 * The fixed little-endian layout: [SIZE] bytes, or [EXTENDED_SIZE] with the [WearExtra] block — exactly the
 * 20-byte payload of a default (23-byte) ATT MTU, so a read never needs a long read or an MTU exchange:
 *
 * | offset | size | field |
 * |---|---|---|
 * | 0 | 1 | major version ([VERSION]) |
 * | 1 | 1 | [MeshState] code |
 * | 2 | 2 | nearby (u16, saturating) |
 * | 4 | 1 | planes: ble bits 0-1, nan 2-3, lora 4-5, spool 6-7 |
 * | 5 | 4 | relayed (u32, saturating) |
 * | 9 | 4 | stamp (u32 epoch seconds) |
 * | 13 | 2 | carrying (u16, saturating) — the [WearExtra] block from here |
 * | 15 | 1 | far peers (u8, saturating) |
 * | 16 | 4 | [MAX_LINKS] [WearLink] nibbles, low nibble first; 0 = empty slot, and the rest are empty too |
 *
 * Additive growth appends fields: [decode] ignores trailing bytes, and a read of [SIZE] to [EXTENDED_SIZE] − 1
 * bytes (an older phone) decodes with no [WearExtra]. A new major version, or a state code this build does
 * not know, decodes to null — the watch then shows "no data" rather than a guess. Growing past 20 bytes means
 * a long read; the server already honours offsets, but the watch has only ever read in one go.
 */
@Suppress("MagicNumber") // the offsets and widths of the table above, pinned by the golden vector
object WearStatusCodec {
    const val VERSION = 1
    const val SIZE = 13
    const val EXTENDED_SIZE = 20
    const val MAX_LINKS = 8

    fun encode(s: WearStatus): ByteArray {
        val out = ByteArray(if (s.extra == null) SIZE else EXTENDED_SIZE)
        out[0] = VERSION.toByte()
        out[1] = s.state.code.toByte()
        putLe(out, 2, s.nearby.toLong().coerceIn(0, U16_MAX), 2)
        out[4] =
            (
                s.ble.code or
                    (s.nan.code shl NAN_SHIFT) or
                    (s.lora.code shl LORA_SHIFT) or
                    (s.spool.code shl SPOOL_SHIFT)
            ).toByte()
        putLe(out, 5, s.relayed.coerceIn(0, U32_MAX), 4)
        putLe(out, 9, s.stampSec.coerceIn(0, U32_MAX), 4)
        s.extra?.let { extra ->
            putLe(out, 13, extra.carrying.toLong().coerceIn(0, U16_MAX), 2)
            out[15] = extra.far.coerceIn(0, U8_MAX).toByte()
            val links =
                extra.links
                    .map { it and WearLink.MASK }
                    .filter { it != 0 }
                    .take(MAX_LINKS)
            for ((i, mask) in links.withIndex()) {
                val at = LINKS_AT + i / 2
                out[at] = (out[at].toInt() or (mask shl (NIBBLE * (i % 2)))).toByte()
            }
        }
        return out
    }

    fun decode(bytes: ByteArray): WearStatus? {
        if (bytes.size < SIZE || bytes[0].toInt() and BYTE_MASK != VERSION) return null
        val state = MeshState.ofCode(bytes[1].toInt() and BYTE_MASK) ?: return null
        val planes = bytes[4].toInt() and BYTE_MASK
        return WearStatus(
            state = state,
            nearby = getLe(bytes, 2, 2).toInt(),
            ble = Plane.ofCode(planes),
            nan = Plane.ofCode(planes shr NAN_SHIFT),
            lora = Plane.ofCode(planes shr LORA_SHIFT),
            spool = Plane.ofCode(planes shr SPOOL_SHIFT),
            relayed = getLe(bytes, 5, 4),
            stampSec = getLe(bytes, 9, 4),
            extra = if (bytes.size >= EXTENDED_SIZE) decodeExtra(bytes) else null,
        )
    }

    private fun decodeExtra(bytes: ByteArray): WearExtra {
        val links = mutableListOf<Int>()
        for (i in 0 until MAX_LINKS) {
            val mask = (bytes[LINKS_AT + i / 2].toInt() shr (NIBBLE * (i % 2))) and WearLink.MASK
            if (mask == 0) break
            links += mask
        }
        return WearExtra(
            carrying = getLe(bytes, 13, 2).toInt(),
            far = bytes[15].toInt() and BYTE_MASK,
            links = links,
        )
    }

    private fun putLe(
        out: ByteArray,
        at: Int,
        value: Long,
        width: Int,
    ) {
        for (i in 0 until width) out[at + i] = (value ushr (8 * i)).toByte()
    }

    private fun getLe(
        bytes: ByteArray,
        at: Int,
        width: Int,
    ): Long {
        var v = 0L
        for (i in 0 until width) v = v or ((bytes[at + i].toLong() and 0xFF) shl (8 * i))
        return v
    }

    private const val NAN_SHIFT = 2
    private const val LORA_SHIFT = 4
    private const val SPOOL_SHIFT = 6
    private const val BYTE_MASK = 0xFF
    private const val LINKS_AT = 16
    private const val NIBBLE = 4
    private const val U8_MAX = 0xFF
    private const val U16_MAX = 0xFFFFL
    private const val U32_MAX = 0xFFFF_FFFFL
}

private const val PLANE_MASK = 0b11

/**
 * The status service's identity. Random 128-bit values, deliberately outside the `0xFE3x` block
 * `mesh/bluetooth/BleConstants` versions the mesh wire with — this service is never advertised and never
 * scanned for; the watch finds it by discovering services on the phone it is already bonded to.
 */
object WearStatusUuids {
    val SERVICE: UUID = UUID.fromString("6b1f0a3e-5d2c-4f0e-9a57-3c8e2d41b7a0")
    val STATUS: UUID = UUID.fromString("6b1f0a3e-5d2c-4f0e-9a57-3c8e2d41b7a1")
}
