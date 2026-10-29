package app.getknit.knit.mesh.lora

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
    /** Up to [limit] of our unacked DMs to [peerId], newest first. A resend carries no inline acks. */
    suspend fun unackedTo(
        peerId: String,
        limit: Int,
    ): List<DirectDm>

    /** Whether [frameId] is still one of ours without its ✓✓ (and inside the window): the at-send re-ask of a resend. */
    suspend fun isUnacked(frameId: String): Boolean

    /** Nothing is unacked: what a build without 2b runs. */
    object None : LoraDirectSource {
        override suspend fun unackedTo(
            peerId: String,
            limit: Int,
        ): List<DirectDm> = emptyList()

        override suspend fun isUnacked(frameId: String): Boolean = false
    }
}

/** One of our unacked DMs, as [LoraDirectSource] hands it to the plane to re-seal. */
internal class DirectDm(
    val frameId: String,
    val sentAtMs: Long,
    val plaintext: ByteArray,
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
}

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
 */
internal data class LoraBoardHolder(
    val addr: BoardAddr,
    val direct: Boolean,
    val profileVersion: Long = 0L,
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
