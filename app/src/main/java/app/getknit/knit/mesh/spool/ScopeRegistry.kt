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
 */
class ScopeRoots(
    val peerId: String,
    val pairwiseRoot: ByteArray,
    val prevPairwiseRoot: ByteArray? = null,
    val prevRootExpiresAt: Long = 0L,
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
)

/**
 * Derives the scope table: one scope per DM peer holding a confirmed ratchet session and one per group
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
 * `RatchetSessions.exportedRoots` already applies it.
 *
 * **Pair scopes** (spec §3.5) are the third source: one per peer an intro is pending with, keyed by the
 * identity-derived pair secret rather than a session root. They are ordinary DM-form scopes — same
 * frame-set rule, same `peerId` discriminator — so everything downstream (the seal, the push half, the
 * attachment pass, the relay indicator) treats them exactly like the DM scope that supersedes them once the
 * session confirms. The [pairs] seam is what bounds their lifetime: `IntroSync` names a peer only while its
 * intro is pending or inside the post-confirmation grace, and the scope vanishes with the name.
 *
 * Bounds are constants here rather than per-conversation state because the signed scope-config ctl
 * (`CTL_SCOPE_CONFIG`, spec §5) is not on the wire yet; when it lands, [bounds] becomes a per-scope
 * lookup and these defaults become the fallback. They are the spec's §12 defaults so a stock spool
 * clamps them to themselves.
 */
class ScopeRegistry(
    private val selfId: suspend () -> String,
    private val roots: suspend () -> List<ScopeRoots>,
    private val groupRoots: suspend () -> List<GroupScopeRoots> = { emptyList() },
    private val pairs: suspend () -> List<PairScopeRoots> = { emptyList() },
    private val commons: suspend () -> List<CommonsRoots> = { emptyList() },
    private val bounds: ScopeBounds = DEFAULT_BOUNDS,
) {
    /** Every scope this device participates in at [now], newest-secret first, de-duplicated by id. */
    suspend fun scopes(now: Long): List<Scope> {
        val me = selfId()
        return (dmScopes(me, now) + groupScopes(now) + pairScopes(me) + commonsScopes()).distinctBy { it.idHex }
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
    private suspend fun pairScopes(me: String): List<Scope> =
        pairs().map { entry ->
            Scope(
                id = ScopeCrypto.pairScopeId(entry.pairSecret, me, entry.peerId),
                keys = ScopeCrypto.pairSealKeys(entry.pairSecret, me, entry.peerId),
                bounds = bounds,
                retiring = false,
                peerId = entry.peerId,
                pair = true,
            )
        }

    private suspend fun dmScopes(
        me: String,
        now: Long,
    ): List<Scope> =
        roots()
            .flatMap { entry ->
                buildList {
                    add(dmScope(me, entry.peerId, entry.pairwiseRoot, retiring = false))
                    val prev = entry.prevPairwiseRoot
                    if (prev != null && entry.prevRootExpiresAt > now) {
                        add(dmScope(me, entry.peerId, prev, retiring = true))
                    }
                }
            }

    private suspend fun groupScopes(now: Long): List<Scope> =
        groupRoots().flatMap { entry ->
            buildList {
                add(groupScope(entry, entry.root, entry.rootVersion, retiring = false))
                val prev = entry.prevRoot
                if (prev != null && entry.prevRootExpiresAt > now) {
                    add(groupScope(entry, prev, entry.prevRootVersion, retiring = true))
                }
            }
        }

    private fun dmScope(
        selfId: String,
        peerId: String,
        pairwiseRoot: ByteArray,
        retiring: Boolean,
    ) = Scope(
        id = ScopeCrypto.dmScopeId(pairwiseRoot, selfId, peerId),
        keys = ScopeCrypto.dmSealKeys(pairwiseRoot, selfId, peerId),
        bounds = bounds,
        retiring = retiring,
        peerId = peerId,
    )

    private fun groupScope(
        entry: GroupScopeRoots,
        root: ByteArray,
        version: Int,
        retiring: Boolean,
    ) = Scope(
        id = ScopeCrypto.groupScopeId(root, entry.groupId, version),
        keys = ScopeCrypto.groupSealKeys(root, entry.groupId, version),
        bounds = bounds,
        retiring = retiring,
        groupId = entry.groupId,
        roster = entry.roster,
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
