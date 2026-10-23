package app.getknit.knit.mesh.spool

import app.getknit.knit.mesh.crypto.scope.ScopeCrypto

/**
 * The scope-key material for one DM peer, already exported out of the ratchet layer — these are
 * **pairwise roots** (`HKDF(sessionRoot, "knit/dm/v2/export/root")`), never raw session secrets, so
 * session state stays behind `RatchetSessions` and its mutex.
 *
 * [prevPairwiseRoot] is the retiring session's export, live until [prevRootExpiresAt]: a replaced
 * session yields a new scope, and the old one stays derivable for the ratchet's drain window
 * (`docs/SPOOL_PROTOCOL.md` §3.1) so blobs already at a spool are still reachable.
 *
 * [pendingResetInit] is set while [pairwiseRoot] is our own reset, still unanswered, over [prevPairwiseRoot]
 * (`RatchetSessions.pendingResetInit`, spec C-3.1-4): the init ephemeral of that reset, which both of the
 * peer's DM scopes then carry ([Scope.resetInit]) — the retiring one's one refill (C-3.1-5), and the only
 * flagged reset of ours either takes.
 */
class ScopeRoots(
    val peerId: String,
    val pairwiseRoot: ByteArray,
    val prevPairwiseRoot: ByteArray? = null,
    val prevRootExpiresAt: Long = 0L,
    val pendingResetInit: ByteArray? = null,
)

/**
 * The inputs of one **pair scope** (`docs/SPOOL_PROTOCOL.md` §3.5): the peer an intro is pending with (or
 * in its post-confirmation grace) and the identity-derived [pairSecret] (`ScopeCrypto.pairSecret`) the
 * scope's id and seal keys come from. The one scope input that is not a session or group secret — it is
 * what lets two contacts who have only ever exchanged a contact card find each other at a spool before a
 * ratchet session exists. Computed by the caller from the pinned bundle so this layer stays free of the
 * identity store.
 */
class PairScopeRoots(
    val peerId: String,
    val pairSecret: ByteArray,
)

/**
 * A joined **commons** (spec §7.4): the invite [secret] its scope id and seal keys derive from, the
 * [spoolUrl] of the one relay that runs it (a commons is a property of a spool, so unlike every other
 * scope it is subscribed at exactly one), and the [conversationId] its posts land in.
 */
class CommonsRoots(
    val conversationId: String,
    val spoolUrl: String,
    val secret: ByteArray,
)

/**
 * One group's scope inputs: the shared root (`docs/SPOOL_PROTOCOL.md` §3.2) with the [rootVersion] that
 * doubles as the scope epoch, the **founding** roster the frame-set rule vets senders against, and the
 * rotated-away lineage while its drain window is open.
 *
 * Assembled by the caller from the group row and [GroupRootStore], so this layer stays free of Room and
 * of the mint/gossip machinery — it only turns secrets into scopes.
 */
class GroupScopeRoots(
    val groupId: String,
    val roster: Set<String>,
    val root: ByteArray,
    val rootVersion: Int,
    val prevRoot: ByteArray? = null,
    val prevRootVersion: Int = 0,
    val prevRootExpiresAt: Long = 0L,
    // The effective members, departed ones out: whose declarations decide whether the group still needs `public-1`.
    val members: Set<String> = roster,
)

/**
 * Derives the scope table: one scope per DM peer holding an exported ratchet session and one per group
 * holding a shared root, plus each one's retiring scope while its drain window is open. Pure — identity,
 * ratchet state and group roots are all injected suspend seams, so the derivation is unit-testable with
 * fixtures and carries no Android or Room dependency.
 *
 * **The Message Requests rule deliberately does not gate this.** Filtering DM peers through
 * `Conversations.isAccepted` (as this did until ADR 032) folds a local presentation decision into relay
 * convergence, which ADR 009 forbids — and it does so *asymmetrically*, because acceptance is largely
 * "I have authored here": a thread one side has only ever received in yields a scope on the sender's
 * device and none on the receiver's. The two then never share a scope id, so the plane carries nothing
 * in either direction while both ends report a connected spool and no error. A **confirmed session** is
 * the bound that matters here — it costs a completed X3DH, which no stranger reaches unsolicited — and
 * `RatchetSessions.exportedRoots` already applies it, with one named exception: **our own reset, still
 * unanswered, over a root it replaced** (spec C-3.1-4, ADR 2026-10.j67x). The peer is still on that root and
 * can learn the new one only from the reset, so both scopes stay: the new one active, for the answer the peer
 * seals under it, and the replaced one retiring, for the peer's own frames and — the one refill C-3.1-5 allows
 * it — the reset itself. Both carry the reset's init ([Scope.resetInit]): it is the only reset of ours either
 * may take. The exception lasts while the replaced root drains: past its
 * window the session is an ordinary unconfirmed initiation, and its peer leaves the table until it answers.
 * Nothing a stranger sends starts it: a reset toward a peer we hold no session with replaces no root.
 *
 * **Pair scopes** (spec §3.5) are the third source: one per peer an intro is pending with, keyed by the
 * identity-derived pair secret rather than a session root. They are ordinary DM-form scopes — same
 * frame-set rule, same `peerId` discriminator — so everything downstream (the seal, the push half, the
 * attachment pass, the relay indicator) treats them exactly like the DM scope that supersedes them once the
 * session confirms. The [pairs] seam is what bounds their lifetime: `IntroSync` names a peer only while its
 * intro is pending or inside the post-confirmation grace, and the scope vanishes with the name.
 *
 * **Where each scope lives** is [placement] (ADR 2026-10.8kfx): read once per derivation, it gives every DM, pair
 * and group scope its relays ([Scope.spools]) by the union rule in [ScopeSpools]. Without it — every test that
 * predates homes — a scope belongs at every relay, as before. A commons stays bound to its one relay either way.
 *
 * Bounds stay the spec's §12 defaults, a stock spool clamping them to themselves: a scope config carries bounds too
 * (spec §5), but only the relays are read from it so far, and every config this build issues names the defaults.
 */
class ScopeRegistry(
    private val selfId: suspend () -> String,
    private val roots: suspend () -> List<ScopeRoots>,
    private val groupRoots: suspend () -> List<GroupScopeRoots> = { emptyList() },
    private val pairs: suspend () -> List<PairScopeRoots> = { emptyList() },
    private val commons: suspend () -> List<CommonsRoots> = { emptyList() },
    private val placement: suspend () -> ScopePlacement? = { null },
    private val bounds: ScopeBounds = DEFAULT_BOUNDS,
) {
    /** Every scope this device participates in at [now], newest-secret first, de-duplicated by id. */
    suspend fun scopes(now: Long): List<Scope> {
        val me = selfId()
        val placed = placement()
        return (dmScopes(me, now, placed) + groupScopes(me, now, placed) + pairScopes(me, placed) + commonsScopes())
            .distinctBy { it.idHex }
    }

    /**
     * One scope per joined commons (spec §7.4), bound to its relay. The bounds declared here are a
     * placeholder the spool ignores — it pins the room's own and echoes them in the `digest` — so they are
     * the daemon's documented defaults, the best guess until a HELLO says otherwise.
     */
    private suspend fun commonsScopes(): List<Scope> =
        commons().map { entry ->
            Scope(
                id = ScopeCrypto.commonsScopeId(entry.secret),
                keys = ScopeCrypto.commonsSealKeys(entry.secret),
                bounds = COMMONS_DEFAULT_BOUNDS,
                retiring = false,
                commonsId = entry.conversationId,
                spoolUrl = entry.spoolUrl,
            )
        }

    /** The pair scope for every pending-intro peer: a DM-form scope whose secret is the identity pair secret. */
    private suspend fun pairScopes(
        me: String,
        placed: ScopePlacement?,
    ): List<Scope> =
        pairs().map { entry ->
            Scope(
                id = ScopeCrypto.pairScopeId(entry.pairSecret, me, entry.peerId),
                keys = ScopeCrypto.pairSealKeys(entry.pairSecret, me, entry.peerId),
                bounds = bounds,
                retiring = false,
                peerId = entry.peerId,
                pair = true,
                spools = placed?.forDm(entry.peerId),
            )
        }

    private suspend fun dmScopes(
        me: String,
        now: Long,
        placed: ScopePlacement?,
    ): List<Scope> =
        roots()
            .flatMap { entry ->
                val spools = placed?.forDm(entry.peerId)
                val prev = entry.prevPairwiseRoot?.takeIf { entry.prevRootExpiresAt > now }
                // Our unanswered reset exports only beside the root it replaced (C-3.1-4): once that drain closes
                // it is any other unconfirmed initiation, and its peer stays off the table until the answer.
                if (entry.pendingResetInit != null && prev == null) return@flatMap emptyList()
                buildList {
                    add(dmScope(me, entry.peerId, entry.pairwiseRoot, retiring = false, entry.pendingResetInit, spools))
                    if (prev != null) add(dmScope(me, entry.peerId, prev, retiring = true, entry.pendingResetInit, spools))
                }
            }

    private suspend fun groupScopes(
        me: String,
        now: Long,
        placed: ScopePlacement?,
    ): List<Scope> =
        groupRoots().flatMap { entry ->
            val spools = placed?.forGroup(entry.groupId, entry.members, me)
            buildList {
                add(groupScope(entry, entry.root, entry.rootVersion, retiring = false, spools))
                val prev = entry.prevRoot
                if (prev != null && entry.prevRootExpiresAt > now) {
                    add(groupScope(entry, prev, entry.prevRootVersion, retiring = true, spools))
                }
            }
        }

    private fun dmScope(
        selfId: String,
        peerId: String,
        pairwiseRoot: ByteArray,
        retiring: Boolean,
        resetInit: ByteArray?,
        spools: Set<String>?,
    ) = Scope(
        id = ScopeCrypto.dmScopeId(pairwiseRoot, selfId, peerId),
        keys = ScopeCrypto.dmSealKeys(pairwiseRoot, selfId, peerId),
        bounds = bounds,
        retiring = retiring,
        peerId = peerId,
        resetInit = resetInit,
        spools = spools,
    )

    private fun groupScope(
        entry: GroupScopeRoots,
        root: ByteArray,
        version: Int,
        retiring: Boolean,
        spools: Set<String>?,
    ) = Scope(
        id = ScopeCrypto.groupScopeId(root, entry.groupId, version),
        keys = ScopeCrypto.groupSealKeys(root, entry.groupId, version),
        bounds = bounds,
        retiring = retiring,
        groupId = entry.groupId,
        roster = entry.roster,
        spools = spools,
    )

    companion object {
        /** Spec §12: 2× the mesh custody TTL — the rotation drain window. */
        const val DEFAULT_TTL_MS = 48 * 60 * 60_000L

        /** Spec §12: 2× the mesh's 200-per-sender custody bucket. */
        const val DEFAULT_MAX_FRAMES = 400

        /** Spec §12: comfortably above any mesh frame. */
        const val DEFAULT_MAX_BLOB = 64 * 1024

        val DEFAULT_BOUNDS =
            ScopeBounds(maxFrames = DEFAULT_MAX_FRAMES, ttlMs = DEFAULT_TTL_MS, maxBlob = DEFAULT_MAX_BLOB)

        /** Spec §12.2's commons defaults (`SPOOL_COMMONS_MAX_FRAMES` / `SPOOL_COMMONS_TTL_MS`): 500 frames, one day. */
        const val COMMONS_DEFAULT_MAX_FRAMES = 500
        const val COMMONS_DEFAULT_TTL_MS = 24 * 60 * 60_000L

        val COMMONS_DEFAULT_BOUNDS =
            ScopeBounds(maxFrames = COMMONS_DEFAULT_MAX_FRAMES, ttlMs = COMMONS_DEFAULT_TTL_MS, maxBlob = DEFAULT_MAX_BLOB)
    }
}
