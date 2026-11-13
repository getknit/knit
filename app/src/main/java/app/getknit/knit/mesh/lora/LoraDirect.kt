package app.getknit.knit.mesh.lora

import app.getknit.knit.mesh.crypto.MessageContent
import app.getknit.knit.mesh.crypto.MessageContentV2
import app.getknit.knit.mesh.crypto.ReadMask
import app.getknit.knit.mesh.protocol.FrameId
import kotlinx.coroutines.flow.Flow

/*
 * The seams of a compact LoRa DM (ADR 2026-10.x9fa). The transport is opaque to `MessageContent` on purpose: it is
 * handed plaintext it never reads, seals it through [LoraDirectCrypto], addresses the one packet to the recipient's
 * board and reports what the radios said. The crypto, the delivery and the "which of my DMs are still unacked" all
 * live above it, behind these four interfaces, and a test runs the plane over fakes of them.
 */

/**
 * The pair seal of a compact DM (the crypto-owner's, `mesh/crypto/LoraDirectSeal`): pure, no clock of the plane's.
 * One packet is `tag 0x11 | pairTag 4 | salt 8 | frameId 16 | sentAt u48 6 | AES-256-GCM ciphertext + 16`, 51 bytes
 * around the plaintext.
 */
internal interface LoraDirectCrypto {
    /**
     * The whole packet for [plaintext] from us to [peerId], under a fresh salt — so a second seal of the same frame
     * (a resend) never reuses a nonce — or null when there is no pair key for [peerId] (no pinned bundle).
     */
    fun seal(
        peerId: String,
        frameId: String,
        sentAtMs: Long,
        plaintext: ByteArray,
    ): ByteArray?

    /**
     * Opens a packet that starts with [LORA_DIRECT_TAG]: the sender comes from the pairTag index over pinned peers,
     * never from the packet, and AEAD authentication decides between candidates whose tags collide.
     */
    fun open(packet: ByteArray): LoraDirectOpen

    /** No pair keys anywhere: every seal fails and every packet is a stranger's. What a build without 2b runs. */
    object None : LoraDirectCrypto {
        override fun seal(
            peerId: String,
            frameId: String,
            sentAtMs: Long,
            plaintext: ByteArray,
        ): ByteArray? = null

        override fun open(packet: ByteArray): LoraDirectOpen = LoraDirectOpen.Stranger
    }
}

/** What [LoraDirectCrypto.open] made of one packet, each counted under its own name. */
internal sealed interface LoraDirectOpen {
    /** It opened, from [direct]'s `peerId`. */
    class Opened(
        val direct: OpenedDirect,
    ) : LoraDirectOpen

    /** No pinned peer's pairTag matches: a stranger, or a peer we do not hold a bundle for (`loraDirectStranger`). */
    data object Stranger : LoraDirectOpen

    /** A pairTag matched and no candidate's key authenticated it: forged, damaged or reflected (`loraDirectAuthFailed`). */
    data object AuthFailed : LoraDirectOpen

    /**
     * It authenticated, and its `sentAt` is outside the window a replay is bounded by — older than custody's TTL or past
     * the future skew (`loraDirectClockRefused`). The crypto-owner decides whether the window is asked here or at
     * delivery; either way it is counted under that name.
     */
    data object ClockRefused : LoraDirectOpen

    /** Too short to be one, or not a [LORA_DIRECT_TAG] packet at all. */
    data object Malformed : LoraDirectOpen
}

/** One compact DM opened: its author from the pair index, its frame id and stamp from the header, the content still sealed. */
internal class OpenedDirect(
    val peerId: String,
    val frameId: String,
    val sentAtMs: Long,
    val plaintext: ByteArray,
)

/** Where an opened compact DM is delivered (`InboundPipeline.deliverLoraDirect`). Never throws out: a failure is its own to count. */
internal fun interface LoraDirectInbox {
    suspend fun deliver(opened: OpenedDirect)

    companion object {
        /** Drops everything: what a build without 2b runs. */
        val None = LoraDirectInbox { }
    }
}

/**
 * Our own DMs still without their ✓✓, as the plaintext a compact copy carries (`MeshManager`, off `MessageDao.unackedDmsTo`
 * and `MessageEntity.sealedContent()`), only rows young enough for the receiver's window to take. What the resend after a
 * hearing and the re-probe re-air, asked again at send.
 */
internal interface LoraDirectSource {
    /**
     * Up to [limit] of our unacked DMs to [peerId], newest first. A resend carries no inline acks. [untakenOnly] leaves
     * out the DMs a board took ([DirectDm.boardTook]) before the limit applies, so newer board-taken ones waiting on a ✓✓
     * cannot hide an older stranded one from a catch-up.
     */
    suspend fun unackedTo(
        peerId: String,
        limit: Int,
        untakenOnly: Boolean = false,
    ): List<DirectDm>

    /**
     * [unackedTo], keeping only the DMs whose compact plaintext is at most [maxBytes] — the asking plane's own budget
     * ([LoraDirectSink.budgetFor]) — before the limit applies, so a DM too long for this board cannot hide one that fits.
     * Its default ignores [maxBytes] and asks the three-argument form, which a source sizes at Meshtastic's 168 B: a
     * forward written before the budget was per board still compiles, and is right for a Meshtastic board alone (ADR
     * 2026-11.gsqu, Stage 1a.4; Stage 5 makes it required).
     */
    suspend fun unackedTo(
        peerId: String,
        limit: Int,
        untakenOnly: Boolean,
        maxBytes: Int,
    ): List<DirectDm> = unackedTo(peerId, limit, untakenOnly)

    /** Whether [frameId] is still one of ours without its ✓✓ (and inside the window): the at-send re-ask of a resend. */
    suspend fun isUnacked(frameId: String): Boolean

    /** Nothing is unacked: what a build without 2b runs. */
    object None : LoraDirectSource {
        override suspend fun unackedTo(
            peerId: String,
            limit: Int,
            untakenOnly: Boolean,
        ): List<DirectDm> = emptyList()

        override suspend fun isUnacked(frameId: String): Boolean = false
    }
}

/**
 * One of our unacked DMs, as [LoraDirectSource] hands it to the plane to re-seal. [boardTook] says the recipient's board
 * acked a compact copy of it this process (`MeshManager.loraRadioReached`, in memory only): such a DM waits on its
 * phone, and only the re-probe asks again; a catch-up re-airs only the ones no board took (#294, ADR 2026-10.x9fa).
 */
internal class DirectDm(
    val frameId: String,
    val sentAtMs: Long,
    val plaintext: ByteArray,
    val boardTook: Boolean = false,
)

/**
 * What `MeshManager` calls with the compact copy of something it just sealed for [recipientId] — beside
 * `originateSigned`, never instead of it: the signed copy still goes to custody, the spool and the links. The transport
 * implements it and decides, on its own gates, whether one packet goes on the air.
 */
internal interface LoraDirectSink {
    fun offer(
        recipientId: String,
        frameId: String,
        sentAtMs: Long,
        plaintext: ByteArray,
        kind: DirectKind,
    )

    /**
     * How much compact content one packet to [recipientId] holds on this sink's board (ADR 2026-11.gsqu, Stage 1a.4): what
     * `MeshManager` sizes a compact copy, its inline acks and its ticks by. Total and never null, and fixed per board kind
     * — never the holder, the link's state or the live payload cap — so a copy sized here is sized as the plane will
     * check it. The default is Meshtastic's, (168, 9).
     */
    fun budgetFor(recipientId: String): LoraDirectBudget = LoraDirectBudget.MESHTASTIC
}

/**
 * A compact DM's room on one board kind (ADR 2026-11.gsqu, Stage 1a.4): [plaintextBytes] of v3 compact content in one
 * packet, and the most ids one compact ✓✓ carries there ([tickAcks], derived: [forPlaintext]).
 */
internal data class LoraDirectBudget(
    val plaintextBytes: Int,
    val tickAcks: Int,
) {
    companion object {
        /** A budget of [plaintextBytes], with the most tick acks that fit it. */
        fun forPlaintext(plaintextBytes: Int): LoraDirectBudget = LoraDirectBudget(plaintextBytes, compactTickAcks(plaintextBytes))

        /** Meshtastic's: 168 B at the MTU-255 floor, nine acks (`LORA_DIRECT_PLAINTEXT_BYTES`, `LORA_DIRECT_TICK_ACKS`). */
        val MESHTASTIC: LoraDirectBudget by lazy { BoardTraits.MESHTASTIC.directBudget() }
    }
}

/**
 * The most ids one compact ✓✓ carries inside [plaintextBytes]: the largest n whose v3 `CTL_RECEIPT` encodes within it —
 * one id as `ack`, more as `acks`, each 16 raw bytes and a one-byte header (about 5 + 17n B). Encoded rather than
 * computed, so the count follows the layout; 0 when not even one fits.
 */
internal fun compactTickAcks(plaintextBytes: Int): Int {
    var fits = 0
    while (fits < MAX_TICK_ACKS_PROBED && receiptBytes(fits + 1) <= plaintextBytes) fits++
    return fits
}

/**
 * The encoded size of a compact `CTL_RECEIPT` carrying [ids] ids, as `MeshManager.offerLoraTicks` builds it toward an
 * author who reads a mask (ADR 2026-11.z6jw): with its read mask, whose width is a function of the id count alone.
 */
private fun receiptBytes(ids: Int): Int {
    val acks = List(ids) { FrameId.new() }
    val rm = ReadMask.encode(acks, emptySet())
    val content =
        if (ids == 1) {
            MessageContent(body = "", ctl = MessageContent.CTL_RECEIPT, ack = acks.single(), rm = rm)
        } else {
            MessageContent(body = "", ctl = MessageContent.CTL_RECEIPT, acks = acks, rm = rm)
        }
    return MessageContentV2.encodeOrNull(content)?.size ?: Int.MAX_VALUE
}

/** Far past any packet: a probe bound, never a budget. */
private const val MAX_TICK_ACKS_PROBED = 64

/**
 * What a compact DM is, as far as the plane needs to know — the one thing it cannot read off sealed bytes. Every kind is
 * [FrameClass.DIRECT] (ADR 2026-10.ug4a); the kind decides where a full queue sheds it ([OutboundFrame.shedRank]) and
 * whether the re-probe tracks it.
 */
internal enum class DirectKind {
    /** A DM a person wrote: `DIRECT`, shed last, and on the re-probe's schedule until its ✓✓ lands. */
    MESSAGE,

    /** A DM reaction: `DIRECT`, shed last; no row of ours waits on a ✓✓ for it, so nothing re-airs it. */
    REACTION,

    /**
     * The coalesced ✓✓ (`CTL_RECEIPT`): `DIRECT`, dequeued in its turn and free to spend a window's last quarter, but
     * ranked first to shed among direct frames, so it never pushes out something the user typed (ADR 2026-10.ug4a).
     */
    RECEIPT,
}

/**
 * One pinned contact whose profile names a board (ADR 2026-10.x9fa): the board map's value, keyed by node id. Built by
 * `MeshManager.loraBoardMap`: a board two profiles claim goes to a contact (verified, an accepted conversation, or a DM
 * we wrote in) before a stranger, then to the newest claim, then to the larger node id, so one board is one holder; our
 * own board is never anyone's. [direct] is
 * `Protocol.CAP_LORA_DIRECT` on their pinned profile — without it their board would ACK a packet their app throws
 * away. [profileVersion] is what tells a refusing peer's new profile from the one it refused under.
 *
 * The last three are what an `add_contact` for their board is judged on (ADR 2026-10.aajj, [LoraContactPolicy]):
 * [loraKey] is the profile's `loraKey` (base64, the key their board signs and decrypts under), [contact] the rank the
 * map ranks claims by, and [contested] that another pinned profile names the same board — whose key it is then cannot
 * be told. Any of them changing is a new holder, so a missing-key hold under the old one lifts.
 */
internal data class LoraBoardHolder(
    val addr: BoardAddr,
    val direct: Boolean,
    val profileVersion: Long = 0L,
    val loraKey: String? = null,
    val contact: Boolean = false,
    val contested: Boolean = false,
)

/** The board map's source: pinned contacts with a board, as they change. Null in a transport built with no such source. */
internal typealias LoraBoardMap = Flow<Map<String, LoraBoardHolder>>

/**
 * The first byte of a compact DM (ADR 2026-10.x9fa): registered in `FastFrameCodec`'s tag registry and
 * `docs/WIRE_COMPAT.md`. LoRa-only — it never rides a link — and an older build drops it as `UNKNOWN_TAG`.
 */
internal const val LORA_DIRECT_TAG: Byte = 0x11

/** The bytes a compact DM's packet wraps around its plaintext: the 35-byte header and the 16-byte GCM tag. */
internal const val LORA_DIRECT_OVERHEAD = 51
