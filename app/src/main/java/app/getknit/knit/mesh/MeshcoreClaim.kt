package app.getknit.knit.mesh

import app.getknit.knit.data.peer.PeerEntity
import app.getknit.knit.mesh.crypto.MeshcoreKey
import kotlinx.coroutines.flow.Flow
import java.nio.ByteBuffer

/**
 * Where our sealed profile's MeshCore claim keeps its state (ADR 2026-11.gsqu, Stage 4; implemented by
 * `data/settings/MeshcoreClaimStore`): the bound board's public key, and the sealed-only version a change in what we claim
 * was published under. A change to the claim moves only that version, never the cleartext profile's — the key is not on
 * the cleartext frame, so a cleartext version that moved with no visible field changed would tell every carrier that
 * something sealed did (the badge's rule, ADR 2026-10.r99s, `badge/BadgeJournal`).
 */
interface MeshcoreClaimJournal {
    /** The bound MeshCore board's public key as its session reported it (`meshcore_board_pubkey`), unchecked; null unbound. */
    val boardKey: Flow<String?>

    /** The profile version the last claim change was published under (0 before the first). */
    val sealedVersion: Flow<Long>

    /** A local fingerprint of the claim that version published (null before the first). Never the key, never sent. */
    val publishedFingerprint: Flow<String?>

    suspend fun putSealed(
        version: Long,
        fingerprint: String,
    )
}

/**
 * What our sealed profile claims about MeshCore (ADR 2026-11.gsqu, Stage 4): the bound board's key, canonical and checked
 * ([MeshcoreKey]), only while all four hold — the LoRa plane is on ([enabled]), its DM switch is on ([dms]), a MeshCore
 * board is bound and reported a key [MeshcoreKey] accepts ([boardKey]: a board's own report is not trusted to be one), and
 * this build reads a compact DM off a MeshCore board ([readsMeshcoreDms], `MeshManager.bindMeshcoreDirect`). Else null. The
 * key's presence is the claim of the door, there being no capability bit: a profile that named a board while any of the
 * four failed would have contacts address DMs that board acks and nobody reads (`MeshManager.ownLora`'s rule). Pure.
 */
internal object MeshcoreClaimRule {
    fun claim(
        boardKey: String?,
        enabled: Boolean,
        dms: Boolean,
        readsMeshcoreDms: Boolean,
    ): String? = MeshcoreKey.canonicalOrNull(boardKey)?.takeIf { enabled && dms && readsMeshcoreDms }
}

/** How far a contact on [MeshcoreBoardMap] is trusted, highest first: the order Stage 5 ranks its board's capacity in. */
internal enum class MeshcoreRank {
    /** The person confirmed this contact's key out of band (safety number or QR). */
    VERIFIED,

    /** The person accepted this contact's conversation. */
    ACCEPTED,

    /** We wrote in a DM with this contact. */
    AUTHORED,
}

/**
 * One contact on [MeshcoreBoardMap]: the board [key] its latest sealed profile claims (canonical base64, checked), its
 * [rank], and whether another live contact on the map claims the same key ([contested]) — in which case neither is
 * taught. [latestProfileVersion] is the row's presentation watermark (`PeerEntity.updatedAt`), the version of the newest
 * profile applied from this contact, **not** the version the claim arrived under: a later rename moves it with the key
 * unchanged, so Stage 5 must never order or gate a re-teach by it. Its [toString] names no key: a map entry never reaches
 * a log line.
 */
internal data class MeshcoreBoardHolder(
    val key: String,
    val rank: MeshcoreRank,
    val latestProfileVersion: Long,
    val contested: Boolean,
) {
    override fun toString(): String = "MeshcoreBoardHolder(rank=$rank, v=$latestProfileVersion, contested=$contested)"
}

/**
 * The rule under `MeshManager.meshcoreBoardMap` (ADR 2026-11.gsqu, Stage 4): which contacts' MeshCore boards Stage 5 may
 * teach our own board, over one snapshot. Pure.
 *
 * - **Direct contacts only** ([direct], `contacts/DirectContacts`'s set): the people the user chose — an accepted
 *   conversation that is not a commons poster's automatic accept, the out-of-band key check, a DM thread they wrote in —
 *   less ourselves, the blocked and the tag-blocked. Never a stranger, and never someone a commons invite made "accepted":
 *   whoever holds an invite grants that, so a commons poster could otherwise contest a real contact's key or have an
 *   arbitrary key taught to our board. A row also needs a pinned key. Ranked verified, then accepted ([accepted], less the
 *   commons marks), then authored.
 * - **Never ourselves, and never our own board.** A row whose key is our bound board's key, compared as bytes, is dropped
 *   whoever claims it: a stale or false claim must not make our board a contact of itself.
 * - **Contested keys are marked, never resolved, among live claimants.** Two live contacts claiming one key are both
 *   marked and neither is taught: nothing on this side can tell which holds it (there is no proof of possession yet), and
 *   attribution never reads it. A claimant is **live** unless this phone has not heard from it for [LIVE_BOUND_MS] (its
 *   `lastSeenAt`, our clock, merged with `MAX`): an identity wiped or reinstalled never sends the sealed profile that
 *   would clear its old claim, and would otherwise contest its successor's key at every contact for ever; silent, its
 *   stamp ages past the bound. A claimant that is not live, beside one that is, leaves the map. Never resolved by the
 *   newer version: a profile's version moves only on an edit, so a live contact who has not edited for a month is as
 *   live as one who did today, and the two versions are two senders' clocks.
 *
 * Keys are checked again here ([MeshcoreKey]) and compared as bytes. The map is a routing hint for teaching only: Stage 5
 * never attributes an inbound DM by a board's 6-byte sender prefix (`LoraDirectSeal`'s trust boundary).
 */
internal object MeshcoreBoardMap {
    /** 30 days: the ratchet era's hard cap. A claimant this phone has not heard from for longer contests nothing. */
    const val LIVE_BOUND_MS: Long = 30L * 24 * 60 * 60 * 1000

    fun of(
        all: List<PeerEntity>,
        me: String,
        direct: Set<String>,
        accepted: Set<String>,
        ownBoardKey: String?,
        now: Long,
    ): Map<String, MeshcoreBoardHolder> {
        val own = ownBoardKey?.let(MeshcoreKey::bytesOrNull)?.let(ByteBuffer::wrap)
        val claims =
            all.mapNotNull { peer ->
                if (peer.nodeId == me || peer.pubKey == null || peer.nodeId !in direct) return@mapNotNull null
                val rank =
                    when {
                        peer.verified -> MeshcoreRank.VERIFIED
                        peer.nodeId in accepted -> MeshcoreRank.ACCEPTED
                        else -> MeshcoreRank.AUTHORED
                    }
                val key = peer.meshcoreKey ?: return@mapNotNull null
                val bytes = MeshcoreKey.bytesOrNull(key)?.let(ByteBuffer::wrap) ?: return@mapNotNull null
                if (bytes == own) return@mapNotNull null
                Claim(peer, rank, key, bytes)
            }
        return claims
            .groupBy { it.bytes }
            .values
            .flatMap { group ->
                val live = group.filter { now - it.peer.lastSeenAt <= LIVE_BOUND_MS }
                // Beside a live claimant the dead ones leave; with none live, every claimant stays as it was.
                val kept = live.ifEmpty { group }
                kept.map { it to (kept.size > 1) }
            }.associate { (claim, contested) ->
                claim.peer.nodeId to
                    MeshcoreBoardHolder(
                        key = claim.key,
                        rank = claim.rank,
                        latestProfileVersion = claim.peer.updatedAt,
                        contested = contested,
                    )
            }
    }

    /** One contact's checked claim; [bytes] is what two claims are compared by (`ByteBuffer` equality is content equality). */
    private class Claim(
        val peer: PeerEntity,
        val rank: MeshcoreRank,
        val key: String,
        val bytes: ByteBuffer,
    )
}

/**
 * What one sealed profile version was sealed for (ADR 2026-11.gsqu, Stage 5b-1): its [version], the MeshCore claim it
 * carried ([key], canonical, null unclaimed), the direct contacts that claim went to ([direct]), and a local fingerprint of
 * every other field a recipient sees ([visible]: the keyless payload with its version zeroed). `MeshManager` keeps the
 * eras of the versions it sealed this process, so a later fan-out can tell what a session already holds. Its [toString]
 * names neither the key nor the set.
 */
internal class SealedProfileEra(
    val version: Long,
    val key: String?,
    val direct: Set<String>,
    val visible: String,
) {
    /** The key [peerId]'s copy of this version carried: `MeshcoreAudience.keyFor`'s answer at this version. */
    fun keyFor(peerId: String): String? = key?.takeIf { peerId in direct }

    /** Whether [other] gives every peer the same copy as this: the same claim, the same set and the same fields. */
    fun sameCopiesAs(other: SealedProfileEra): Boolean = key == other.key && direct == other.direct && visible == other.visible

    override fun toString(): String = "SealedProfileEra(v=$version, claimed=${key != null}, contacts=${direct.size})"
}

/**
 * Which owed sessions an audience-only mint seals (ADR 2026-11.gsqu, Stage 5b-1). While a key is claimed, a change in the
 * direct-contact set mints a fresh sealed-only version (the Stage 4 rule: one version, one audience), and a full fan-out
 * of it is a burst of sealed `CHAT` frames under our own sender on every accept, verify, block or removal, which
 * custody's per-sender quota of 200 can evict our own older DMs in. So it is sealed only to the sessions whose copy
 * changed or may be gone, and every other owed session is marked sent at it unsealed. The accepted cost, the
 * maintainer's decision of 2026-11-13: a relay, carrier, radio or the spool sees one profile-sized sealed frame to the
 * peer whose direct-contact status we changed, and when.
 *
 * A session may be spared ([spares]) only when the copy it was last sealed is, field for field, the copy [next] would
 * give it but for the version (and the pad that version's length sizes): all of
 *
 * - [held] is known: the era of the version this process last recorded for the peer, with no sealed profile sent it
 *   outside the fan-out since (the contact-card intro), so it is the newest copy the peer can hold from us;
 * - [claimMinted]: [next] is the claim journal's own version, so the newest mint was a claim or audience change;
 * - the claim is the same key, and held at both (a mint that gained, dropped or changed the key fans out to all);
 * - [SealedProfileEra.visible] is equal: no name, status, photo, open-to-chat, LoRa board or badge moved;
 * - [SealedProfileEra.keyFor] answers the same for the peer at both.
 *
 * Then the peer's copy at [held] carries the key exactly when [next]'s would, so marking it sent leaves it holding what it
 * should; the receiver's gate takes a newer version only, and a spared peer is sent nothing it would have to take. A peer
 * that became a contact, or stopped being one, fails the last test and is sealed a strictly newer version: keyed for the
 * one, keyless and padded for the other, which clears the key it held. Anything not provably equal is sealed. Pure.
 */
internal object MeshcoreFanOutRule {
    fun spares(
        peerId: String,
        held: SealedProfileEra?,
        next: SealedProfileEra,
        claimMinted: Boolean,
    ): Boolean =
        claimMinted &&
            held != null &&
            held.version < next.version &&
            held.key != null &&
            held.key == next.key &&
            held.visible == next.visible &&
            held.keyFor(peerId) == next.keyFor(peerId)

    /**
     * What one fan-out of [next] does with each [owed] session, at [now] on our wall clock: a session [spares] refuses is
     * sealed, and so is one it would spare whose copy may be gone ([OwedSeal.sealedAt] [staleAfterMs] or more ago, or in
     * the future: a clock moved back). Custody drops a sealed frame at its TTL and evicts it under the per-sender quota,
     * so "last sealed" is not "held" for a peer dark that long. Every other owed session is spared.
     */
    fun plan(
        owed: List<OwedSeal>,
        next: SealedProfileEra,
        claimMinted: Boolean,
        now: Long,
        staleAfterMs: Long,
    ): FanOutPlan {
        val spareable = owed.filter { spares(it.peerId, it.held, next, claimMinted) }
        val stale = spareable.filter { now - it.sealedAt !in 0 until staleAfterMs }.map { it.peerId }.toSet()
        val spare = spareable.map { it.peerId }.toSet() - stale
        return FanOutPlan(seal = owed.map { it.peerId }.toSet() - spare, spare = spare, stale = stale.size)
    }
}

/**
 * One owed session as [MeshcoreFanOutRule.plan] judges it: the era of the copy it was last sealed ([held], null when
 * not known) and when that copy was sealed, on our wall clock ([sealedAt]: a spare never moves it).
 */
internal class OwedSeal(
    val peerId: String,
    val held: SealedProfileEra?,
    val sealedAt: Long,
)

/** [MeshcoreFanOutRule.plan]'s answer: the sessions to seal and those to mark sent unsealed. */
internal class FanOutPlan(
    val seal: Set<String>,
    val spare: Set<String> = emptySet(),
    /** Of [seal], those sealed only because the copy they were last sealed may be gone. */
    val stale: Int = 0,
)
