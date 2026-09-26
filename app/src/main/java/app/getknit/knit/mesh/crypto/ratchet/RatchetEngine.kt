package app.getknit.knit.mesh.crypto.ratchet

import app.getknit.knit.mesh.crypto.AesGcm

/**
 * The DM epoch-rekey ratchet state machine (crypto scheme v2, docs/FORWARD_SECRECY_RATCHET.md). Pure:
 * state comes in as immutable snapshots, changes go out as [SealResult]/[OpenOutcome] deltas the caller
 * persists (atomically with the message row — see `RatchetSessions`); no IO, no Android, randomness
 * only through the injected keypair source. That keeps every ordering/race scenario a plain-JVM test.
 *
 * Design invariants (the mesh's custody semantics force all of them — see the design doc):
 * - **Epochs derive independently** off a static per-session root: any subset of a peer's epochs can be
 *   processed in any order, and a wholly-evicted epoch loses only itself.
 * - **Own send-epoch numbers are monotone across the roots a peer hands us** (both-initiate races, inbound
 *   replacements), but **not across our own reset**: `RatchetSessions.sealResetDm` initiates afresh and
 *   restarts at 1, and the re-minted epoch replaces the dead era's key of the same number (ADR 027). So
 *   `(peer, se)` is reused only when a session was replaced, and every root change on our side purges the
 *   stale receive state ([OpenDelta.purgePeerRecvState], and `sealResetDm` for its own).
 * - **A receive chain, once derived, never needs the root again** — `chainKey` alone advances it, which
 *   is what lets a superseded root ([SessionState.prevRoot]) drain for a bounded window and then vanish.
 * - **A frame moves the session's era only when it derives a fresh epoch under the active root.** Adopting
 *   the peer's epoch as our DH base, recording which of our epochs it has sealed against, and confirming the
 *   session are all claims about the era we hold now, and a frame drained under any other root (a kept
 *   `prevRoot`, a race's other root, a late race remnant) says nothing about it. Every stored receive row
 *   was derived by exactly one fresh derivation, whose root already decided — the root change that would
 *   make a row stale purges it — so the live-chain and skipped-key rungs never apply them: in era it would
 *   repeat what the epoch's first frame recorded, out of era it would be wrong.
 *
 * [SessionState] and [RecvEpoch] are data classes for `copy` ergonomics; their [ByteArray] fields make
 * generated equality reference-based, which nothing here relies on.
 */
class RatchetEngine(
    private val newKeyPair: () -> RatchetCrypto.KeyPair = RatchetCrypto::generateKeyPair,
) {
    /** Immutable snapshot of one peer session (mirrors the `ratchet_sessions` row). */
    data class SessionState(
        val peerId: String,
        val confirmed: Boolean,
        val weAreInitiator: Boolean,
        val root: ByteArray,
        val prevRoot: ByteArray? = null,
        val prevRootWeAreInitiator: Boolean = false,
        val prevRootExpiresAt: Long = 0L,
        val establishedAt: Long,
        val initEphPub: ByteArray? = null,
        val initPkid: Int = 0,
        /** The peer init we last resolved (responded / adopted / archived) — the idempotence anchor. */
        val peerInitEphPub: ByteArray? = null,
        val peerBasePub: ByteArray? = null,
        val peerBaseEpoch: Int = 0,
        val sendEpoch: Int = 0,
        val sendEpochPub: ByteArray? = null,
        val sendChainKey: ByteArray? = null,
        val sendCount: Int = 0,
        val sendEpochStartedAt: Long = 0L,
        val sendEpochBaseEpoch: Int = 0,
        val sendEpochExport: ByteArray? = null,
        val highestPeAcked: Int = 0,
        val lastResetSentAt: Long = 0L,
    )

    /** One of our own epoch keypairs (mirrors a `ratchet_local_epochs` row); peers DH against [pub]. */
    class LocalEpoch(
        val epoch: Int,
        val priv: ByteArray,
        val pub: ByteArray,
        val createdAt: Long,
    )

    /** One inbound epoch chain (mirrors a `ratchet_recv_epochs` row). */
    data class RecvEpoch(
        val epoch: Int,
        val chainKey: ByteArray,
        val next: Int,
        val lastUsedAt: Long,
    )

    /** A stored out-of-order message key (mirrors a `ratchet_skipped_keys` row). */
    class SkippedKey(
        val epoch: Int,
        val idx: Int,
        val msgKey: ByteArray,
        val createdAt: Long,
    )

    /** Wire-agnostic mirror of the v2 ratchet header (mapped to/from the CBOR DTO by the caller). */
    class FrameHeader(
        val se: Int,
        val ek: ByteArray,
        val pe: Int,
        val n: Int,
        val init: InitPayload? = null,
        val flags: Int = 0,
    )

    /** Wire-agnostic mirror of the attached X3DH initiation. */
    class InitPayload(
        val eph: ByteArray,
        val pkid: Int,
        val at: Long,
    )

    /** A peer's published signed prekey, already signature-verified by the caller. */
    class PeerPrekey(
        val id: Int,
        val pub: ByteArray,
    )

    /** A sealed frame; [nonce] is null for the v3 form, whose nonce is derived rather than carried. */
    class SealResult(
        val header: FrameHeader,
        val nonce: ByteArray?,
        val ct: ByteArray,
        val session: SessionState,
        val newLocalEpoch: LocalEpoch?,
    )

    /**
     * Everything [open] needs that lives in storage; the caller resolves rows, the engine does math.
     * [ownBasePriv] is our local-epoch priv for the header's `pe` (when `pe >= 1`); [spkPrivForInit] is
     * our signed-prekey priv for the init's `pkid` (when an init is attached).
     */
    class OpenContext(
        val selfNodeId: String,
        val peerId: String,
        val session: SessionState?,
        val recvEpoch: RecvEpoch?,
        val skippedMsgKey: ByteArray?,
        val ownBasePriv: ByteArray?,
        val ownIkPriv: ByteArray,
        val peerIkPub: ByteArray,
        val spkPrivForInit: ByteArray?,
        /**
         * Whether a session-replacing init (newer `at`, unknown eph, on a resolved session) may be
         * adopted this call. The caller rate-limits replacements (an attacker can't forge one — the
         * frame is signed — but a buggy peer could churn); false makes the init inert, so the frame
         * is judged under the existing roots only.
         *
         * The caller uses a much shorter floor for an init flagged `FLAG_RESET`: an explicit reset request
         * is the recovery path, and refusing it silently is how two peers that had *both* reset each other
         * stayed unreadable in both directions with every X3DH input present (ADR 023).
         */
        val allowReplacement: Boolean = true,
        /**
         * Whether this frame's init carries the wire's `FLAG_RESET` — the peer explicitly asking to
         * re-establish, as opposed to an incidental init riding ordinary traffic. Supplied by the caller
         * for the same reason [allowReplacement] is: the engine is wire-agnostic and must not own the bit.
         *
         * It exempts the init from the [resolveSession] race-remnant refusal. That guard reads a *stale
         * re-serve* out of a confirmed winner's history, and a reset can never be one: it is minted fresh
         * per request, and once adopted its ephemeral becomes the idempotence anchor that makes every
         * re-serve of it inert. For the same reason a flagged init is never offered as the read-only remnant
         * candidate: a reset is adopted or refused, never read without adoption.
         */
        val resetRequested: Boolean = false,
    )

    sealed interface OpenOutcome {
        class Opened(
            val plaintext: ByteArray,
            val delta: OpenDelta,
        ) : OpenOutcome

        /** Typed failures; the caller maps these to `DropReason`s and the reset heuristic. */
        enum class Failed : OpenOutcome {
            /** No session and no attached init — the peer assumes shared state we don't have. */
            NO_SESSION,

            /** The header references an own epoch or prekey priv we no longer (or never) hold. */
            EPOCH_GONE,

            /** Chain index already consumed and no skipped key held — a benign re-delivery. */
            DUPLICATE,

            /** Structurally invalid or bound-violating header. */
            BAD_HEADER,

            /** Key material resolved but the AEAD refused — wrong root era, corrupt frame, or tamper. */
            AEAD_FAIL,
        }
    }

    class OpenDelta(
        val session: SessionState,
        /** Upsert; null when only a skipped key was consumed for an epoch whose row is already gone. */
        val recvEpoch: RecvEpoch?,
        val skippedInserts: List<SkippedKey> = emptyList(),
        val consumedSkippedIdx: Int? = null,
        /** True when a replacement init was adopted: delete ALL recv epochs + skipped keys for this peer first. */
        val purgePeerRecvState: Boolean = false,
    )

    class Initiation(
        val session: SessionState,
        val epoch: LocalEpoch,
    )

    /**
     * Starts a session toward [peerId] (X3DH against their identity + signed prekey) and its first send
     * epoch. The X3DH ephemeral's private half is consumed by the root derivation and never retained —
     * only its public half rides the wire ([FrameHeader.init]) until the session confirms.
     */
    fun initiate(
        peerId: String,
        ownIkPriv: ByteArray,
        peerIkPub: ByteArray,
        peerSpk: PeerPrekey,
        now: Long,
    ): Initiation {
        val eph = newKeyPair()
        val root = RatchetCrypto.x3dhInitiate(ownIkPriv, eph.priv, peerIkPub, peerSpk.pub)
        val base =
            SessionState(
                peerId = peerId,
                confirmed = false,
                weAreInitiator = true,
                root = root,
                establishedAt = now,
                initEphPub = eph.pub,
                initPkid = peerSpk.id,
            )
        val started = startSendEpoch(base, basePub = peerSpk.pub, baseEpoch = 0, now = now)
        return Initiation(started.session, started.epoch)
    }

    /**
     * Seals [plaintext] in the session's current send epoch, first advancing to a fresh epoch when an
     * advance rule fires (see [needsNewEpoch]). [peerSpkPub] is the DH base while the peer has
     * contributed no epoch of their own yet (`pe = 0`). Returns null only if a fresh epoch is needed
     * and no base exists — callers gate on prekey presence, so that is an upstream bug, not a wire
     * condition. [v3] seals crypto scheme v3 (ADR 059): the ratchet header is bound into the AEAD's
     * associated data and the nonce is derived from the message key (`RatchetCrypto.messageNonce`) instead
     * of drawn at random and carried — the chain, epochs and header are exactly v2's.
     */
    fun seal(
        state: SessionState,
        plaintext: ByteArray,
        aad: ByteArray,
        peerSpkPub: ByteArray?,
        now: Long,
        forceNewEpoch: Boolean = false,
        v3: Boolean = false,
    ): SealResult? {
        var session = state
        var newLocal: LocalEpoch? = null
        if (forceNewEpoch || needsNewEpoch(session, now)) {
            val basePub = session.peerBasePub ?: peerSpkPub ?: return null
            val started = startSendEpoch(session, basePub, session.peerBaseEpoch, now)
            session = started.session
            newLocal = started.epoch
        }
        val chainKey = checkNotNull(session.sendChainKey)
        val msgKey = RatchetCrypto.messageKey(chainKey)
        val header =
            FrameHeader(
                se = session.sendEpoch,
                ek = checkNotNull(session.sendEpochPub),
                pe = session.sendEpochBaseEpoch,
                n = session.sendCount,
                init =
                    if (session.confirmed) {
                        null
                    } else {
                        InitPayload(checkNotNull(session.initEphPub), session.initPkid, session.establishedAt)
                    },
            )
        val nonce: ByteArray?
        val ct: ByteArray
        if (v3) {
            // Nothing random rides the wire: the header goes into the AAD, the nonce comes out of the key.
            val boundAad = aad + RatchetCrypto.headerBindingBytes(header)
            nonce = null
            ct = AesGcm.encrypt(msgKey, plaintext, boundAad, RatchetCrypto.messageNonce(msgKey, boundAad)).second
        } else {
            val sealed = AesGcm.encrypt(msgKey, plaintext, aad)
            nonce = sealed.first
            ct = sealed.second
        }
        val advanced =
            session.copy(
                sendChainKey = RatchetCrypto.nextChainKey(chainKey),
                sendCount = session.sendCount + 1,
            )
        return SealResult(header, nonce, ct, advanced, newLocal)
    }

    private fun needsNewEpoch(
        session: SessionState,
        now: Long,
    ): Boolean {
        // No epoch yet, or a root-changing resolution (race adoption, replacement) nulled the chain
        // out — the old epoch's keys live under a root the peer no longer accepts.
        if (session.sendEpoch == 0 || session.sendChainKey == null) return true
        val healing = session.peerBaseEpoch > session.sendEpochBaseEpoch
        val full = session.sendCount >= MAX_EPOCH_MESSAGES
        val aged = now - session.sendEpochStartedAt >= MAX_EPOCH_AGE_MS
        return healing || full || aged
    }

    /**
     * Opens one inbound v2 frame. The ladder: a stored skipped key, then the live chain of a known
     * receive epoch (deriving-and-storing keys across any index gap), then a brand-new epoch derivation
     * — under the active root first, the draining [SessionState.prevRoot] second, and, for an unanchored race
     * winner, a late race loser's root last. An attached init may establish, idempotently re-confirm,
     * race-tiebreak, or replace the session; every mutation rides the returned [OpenDelta], and nothing is
     * committed on failure. Only the fresh derivation, and only under the active root, moves the session's
     * era (see the class invariants). A null [nonce] is the v3 form (derived nonce, header bound into the AAD
     * — see [seal]); the ladder is otherwise identical.
     */
    fun open(
        ctx: OpenContext,
        header: FrameHeader,
        nonce: ByteArray?,
        ct: ByteArray,
        aad: ByteArray,
        now: Long,
    ): OpenOutcome {
        if (!headerSane(header)) return OpenOutcome.Failed.BAD_HEADER
        val resolved = resolveSession(ctx, header, now) ?: return sessionFailure(ctx, header)
        if (!resolved.purge && ctx.skippedMsgKey != null) {
            return decryptWith(ctx.skippedMsgKey, header, nonce, ct, aad) { plain ->
                OpenOutcome.Opened(
                    plain,
                    OpenDelta(
                        session = touch(resolved.session, header, ctx, now, eraEffects = false),
                        recvEpoch = ctx.recvEpoch?.copy(lastUsedAt = now),
                        consumedSkippedIdx = header.n,
                    ),
                )
            }
        }
        val epoch = if (resolved.purge) null else ctx.recvEpoch
        return when {
            epoch == null -> openNewEpoch(ctx, resolved, header, nonce, ct, aad, now)
            header.n < epoch.next -> OpenOutcome.Failed.DUPLICATE
            else -> finishOpen(ctx, resolved, epoch.chainKey, epoch.next, header, nonce, ct, aad, now, eraEffects = false)
        }
    }

    // ---- internals ----------------------------------------------------------------------------------

    /**
     * Structural bounds only — nothing here consults the session, so a frame this refuses is
     * `BAD_HEADER`, which is deliberately outside `RESET_TRIGGERING_DROPS`. Both epoch numbers are
     * bounded on *both* sides: a negative or absurd `pe` used to fall through to a missing-key report
     * (`EPOCH_GONE`), which is reset-triggering, so a structurally invalid header could walk the reset
     * heuristic. `pe = 0` names the receiver's SPK and is legal only while an init rides along.
     */
    private fun headerSane(header: FrameHeader): Boolean =
        header.se in 1..MAX_EPOCH_NUMBER && header.n in 0 until MAX_EPOCH_MESSAGES &&
            header.ek.size == RatchetCrypto.KEY_BYTES &&
            header.pe in 0..MAX_EPOCH_NUMBER && (header.pe >= 1 || header.init != null)

    private class ResolvedSession(
        val session: SessionState,
        /** Roots to try for a NEW epoch derivation, in order, paired with the epoch sender's session role. */
        val rootCandidates: List<RootCandidate>,
        val purge: Boolean = false,
    )

    /**
     * [active] marks the root the resolved session holds as its own: only a fresh epoch derived under it may
     * move the session's era. Every other candidate (a draining `prevRoot`, a race's other root, a late race
     * loser's) is read-only — its frames still open and deliver, and their epoch's chain is stored.
     */
    private class RootCandidate(
        val root: ByteArray,
        val senderIsInitiator: Boolean,
        val active: Boolean,
    )

    private fun sessionFailure(
        ctx: OpenContext,
        header: FrameHeader,
    ): OpenOutcome.Failed =
        if (header.init != null && ctx.spkPrivForInit == null) {
            OpenOutcome.Failed.EPOCH_GONE
        } else {
            OpenOutcome.Failed.NO_SESSION
        }

    /**
     * Applies the attached init (if any) to produce the session this frame should be read under plus
     * the ordered root candidates for a new-epoch derivation. Null means the frame is unreadable at
     * the session level (no session and no usable init). Suppressions: a decision tree over init
     * cases — early returns are the readable form, and hoisting branches out would scatter the one
     * place session-resolution order is defined.
     */
    @Suppress("ReturnCount", "CyclomaticComplexMethod")
    private fun resolveSession(
        ctx: OpenContext,
        header: FrameHeader,
        now: Long,
    ): ResolvedSession? {
        val session = ctx.session
        val init = header.init
        if (session == null) {
            if (init == null || ctx.spkPrivForInit == null) return null
            val root = respond(ctx, init)
            return ResolvedSession(
                session =
                    SessionState(
                        peerId = ctx.peerId,
                        confirmed = true,
                        weAreInitiator = false,
                        root = root,
                        establishedAt = init.at,
                        peerInitEphPub = init.eph,
                    ),
                rootCandidates = listOf(RootCandidate(root, senderIsInitiator = true, active = true)),
            )
        }
        val candidates = mutableListOf(RootCandidate(session.root, !session.weAreInitiator, active = true))
        session.prevRoot?.takeIf { session.prevRootExpiresAt > now }?.let {
            candidates += RootCandidate(it, !session.prevRootWeAreInitiator, active = false)
        }
        if (init == null) return ResolvedSession(session, candidates)
        // An init we already resolved (responded to, adopted, or archived), riding a later unconfirmed
        // frame or a custody re-serve: idempotent by its ephemeral key — NOT by timestamp, which would
        // let a re-served race loser's init masquerade as a fresh replacement for its whole custody TTL.
        if (session.peerInitEphPub?.contentEquals(init.eph) == true) return ResolvedSession(session, candidates)
        // Both-initiate race: we hold an unconfirmed session WE started and the peer's own init just
        // arrived. Resolved by nodeId tiebreak, deliberately NOT by comparing `init.at` — concurrent
        // inits can carry the identical timestamp, and both sides must pick the same winner.
        if (!session.confirmed && session.weAreInitiator) return resolveRace(ctx, session, init, now)
        // A genuinely stale init (older than the session we already share) changes nothing.
        if (init.at <= session.establishedAt) return ResolvedSession(session, candidates + remnantCandidates(ctx, header, init))
        if (ctx.spkPrivForInit == null || !ctx.allowReplacement) {
            return ResolvedSession(session, candidates + remnantCandidates(ctx, header, init))
        }
        // The confirmed-initiator race remnant: we won a both-initiate race WITHOUT ever processing the
        // loser's init (their pre-adoption frames were lost, or are still on their way), so no idempotence
        // anchor was recorded — and their init can re-serve from custody with a *newer* timestamp for a full
        // TTL. An init that loses the nodeId tiebreak in this state is that remnant, never a replacement:
        // adopting it would defect to the losing root while the peer sits on the winning one (both
        // "confirmed", permanently diverged).
        //
        // `FLAG_RESET` is exempt, and must be (ADR 024). The remnant this refuses is a re-served init from
        // an era the peer has already left; a reset is the opposite — freshly minted, deliberate, and the
        // only signal a peer that lost its state can send. Refusing it made the blackout one-directional
        // and unrecoverable from the peer's side: only our OWN 6 h heuristic could clear it, so a pair
        // that reset in the wrong order stayed dark for up to six hours per cycle while every X3DH input
        // was present. Adopting it cannot defect to a losing root, because a reset abandons the losing
        // root on the sender's side too.
        //
        // Refused is not unread: the remnant's own frame is the loser's opening DM, and it is still a real
        // message (#83) — see [remnantCandidates].
        if (isRemnantOf(ctx, session)) return ResolvedSession(session, candidates + remnantCandidates(ctx, header, init))
        // Replacement (peer reset / re-init after losing state): their epoch numbering restarts at 1,
        // so our recv rows for the old numbering must go; the old root drains via prevRoot for any
        // still-in-flight old frames.
        val newRoot = respond(ctx, init)
        return ResolvedSession(
            session =
                session.copy(
                    confirmed = true,
                    weAreInitiator = false,
                    root = newRoot,
                    prevRoot = session.root,
                    prevRootWeAreInitiator = session.weAreInitiator,
                    prevRootExpiresAt = now + PREV_ROOT_TTL_MS,
                    establishedAt = init.at,
                    peerInitEphPub = init.eph,
                    peerBasePub = null,
                    peerBaseEpoch = 0,
                    sendEpochPub = null,
                    sendChainKey = null,
                    sendEpochExport = null,
                ),
            rootCandidates = listOf(RootCandidate(newRoot, senderIsInitiator = true, active = true)),
            purge = true,
        )
    }

    /**
     * The state the race-remnant guard reads: a confirmed session we initiated with no peer init ever
     * resolved (we won a race without processing the loser's init — or simply initiated, since a responder
     * never sends one), an init from the side that loses the nodeId tiebreak, and no `FLAG_RESET`.
     */
    private fun isRemnantOf(
        ctx: OpenContext,
        session: SessionState,
    ): Boolean =
        session.confirmed && session.weAreInitiator && session.peerInitEphPub == null &&
            !ctx.resetRequested && session.peerId > ctx.selfNodeId

    /**
     * The late race loser's root, as a trailing **read-only** candidate (#83). The loser adopts our root and
     * answers under it — its tick, its intro — and those answers can overtake its own opening DM, which it
     * sealed under the losing root while the two were apart: that DM rides custody and the digest exchange,
     * the answers ride the live link. By then we have confirmed on the answers without ever seeing the loser's
     * init, so both returns that refuse it (a stale `at`, the remnant guard) used to leave the DM unreadable
     * for good — nothing re-serves or re-seals it, and one or two lost frames never trip the reset heuristic.
     *
     * Offered under exactly the state [isRemnantOf] names, for a frame sealed against our signed prekey
     * (`pe = 0`, as every frame of an unconfirmed initiator's first epoch is), and never adopted: the guard's
     * point — no defection to the losing root — stands. Nothing is recorded either. Anchoring the init's
     * ephemeral would turn a later *flagged* reset carrying the same ephemeral into an idempotent re-serve: a
     * resetter's own follow-up frames carry its reset init without the flag, so reading one of those here and
     * anchoring it is how the reset itself would open without being adopted. Re-serves of the frame end as
     * `DUPLICATE` on the receive row its first open stored.
     *
     * The same state is also every session we initiated whose peer never sent an init, so a higher-id peer
     * that lost its ratchet state and re-initiates *without* `FLAG_RESET` is now read rather than refused;
     * the pair then recovers from that peer's side (its heuristic, then its flagged reset) instead of ours.
     */
    private fun remnantCandidates(
        ctx: OpenContext,
        header: FrameHeader,
        init: InitPayload,
    ): List<RootCandidate> {
        val session = ctx.session ?: return emptyList()
        if (!isRemnantOf(ctx, session) || header.pe != 0 || ctx.spkPrivForInit == null) return emptyList()
        return listOf(RootCandidate(respond(ctx, init), senderIsInitiator = true, active = false))
    }

    /**
     * Both sides initiated concurrently. Deterministic winner on both ends: the init whose initiator
     * has the lexicographically smaller nodeId. The loser's root drains as [SessionState.prevRoot].
     *
     * The side that adopts the winner's root purges its receive state with it: those rows describe chains
     * under the era being abandoned. Send-epoch numbering continuing monotonically is *not* enough on its
     * own — that holds for an ordinary race, but two peers resetting each other race with inits whose
     * sender restarted its numbering, so the winner's fresh epochs collide with the loser's surviving rows.
     * The side that keeps its own root changes no era and keeps its rows.
     *
     * Each side's candidates put the root it now holds first and the other one read-only: the winner still
     * reads the loser's pre-adoption frames, but their epoch never becomes its DH base — it stays on the
     * loser's signed prekey (with the init attached) until the loser answers under the winning root.
     */
    private fun resolveRace(
        ctx: OpenContext,
        session: SessionState,
        init: InitPayload,
        now: Long,
    ): ResolvedSession? {
        if (ctx.spkPrivForInit == null) return null
        val peerRoot = respond(ctx, init)
        return if (session.peerId < ctx.selfNodeId) {
            ResolvedSession(
                session =
                    session.copy(
                        confirmed = true,
                        weAreInitiator = false,
                        root = peerRoot,
                        prevRoot = session.root,
                        prevRootWeAreInitiator = true,
                        prevRootExpiresAt = now + PREV_ROOT_TTL_MS,
                        establishedAt = init.at,
                        peerInitEphPub = init.eph,
                        sendEpochPub = null,
                        sendChainKey = null,
                        sendEpochExport = null,
                    ),
                rootCandidates =
                    listOf(
                        RootCandidate(peerRoot, senderIsInitiator = true, active = true),
                        RootCandidate(session.root, senderIsInitiator = false, active = false),
                    ),
                // We just swapped roots, so every recv row we hold describes a chain under the era we are
                // abandoning — exactly the replacement case, and it must purge for the same reason. The
                // numbering argument below does not save us: it holds for an ordinary race, but a race
                // whose inits are RESET requests is one where the winner restarted its epoch numbering
                // (`sealResetDm`), so its fresh epochs land straight on our stale rows and are judged
                // against a consumed chain index — a DUPLICATE, which triggers nothing and never heals.
                purge = true,
            )
        } else {
            ResolvedSession(
                session =
                    session.copy(
                        prevRoot = peerRoot,
                        prevRootWeAreInitiator = false,
                        prevRootExpiresAt = now + PREV_ROOT_TTL_MS,
                        peerInitEphPub = init.eph,
                    ),
                rootCandidates =
                    listOf(
                        RootCandidate(session.root, senderIsInitiator = false, active = true),
                        RootCandidate(peerRoot, senderIsInitiator = true, active = false),
                    ),
            )
        }
    }

    private fun respond(
        ctx: OpenContext,
        init: InitPayload,
    ): ByteArray =
        RatchetCrypto.x3dhRespond(
            ikPriv = ctx.ownIkPriv,
            spkPriv = checkNotNull(ctx.spkPrivForInit),
            peerIkPub = ctx.peerIkPub,
            peerEkPub = init.eph,
        )

    private fun openNewEpoch(
        ctx: OpenContext,
        resolved: ResolvedSession,
        header: FrameHeader,
        nonce: ByteArray?,
        ct: ByteArray,
        aad: ByteArray,
        now: Long,
    ): OpenOutcome {
        val basePriv = if (header.pe == 0) ctx.spkPrivForInit else ctx.ownBasePriv
        basePriv ?: return OpenOutcome.Failed.EPOCH_GONE
        val shared =
            runCatching { RatchetCrypto.dh(basePriv, header.ek) }.getOrNull()
                ?: return OpenOutcome.Failed.BAD_HEADER
        for (candidate in resolved.rootCandidates) {
            val keys =
                RatchetCrypto.deriveEpoch(
                    sessionRoot = candidate.root,
                    dhShared = shared,
                    senderIsInitiator = candidate.senderIsInitiator,
                    senderEpoch = header.se,
                    baseEpoch = header.pe,
                )
            val outcome = finishOpen(ctx, resolved, keys.chainKey, 0, header, nonce, ct, aad, now, candidate.active)
            if (outcome !is OpenOutcome.Failed) return outcome
        }
        return OpenOutcome.Failed.AEAD_FAIL
    }

    @Suppress("LongParameterList") // internal plumbing between the two open paths; a param object would just rename it
    private fun finishOpen(
        ctx: OpenContext,
        resolved: ResolvedSession,
        chainKeyAtNext: ByteArray,
        next: Int,
        header: FrameHeader,
        nonce: ByteArray?,
        ct: ByteArray,
        aad: ByteArray,
        now: Long,
        eraEffects: Boolean,
    ): OpenOutcome {
        var chainKey = chainKeyAtNext
        val skipped = mutableListOf<SkippedKey>()
        for (idx in next until header.n) {
            skipped += SkippedKey(epoch = header.se, idx = idx, msgKey = RatchetCrypto.messageKey(chainKey), createdAt = now)
            chainKey = RatchetCrypto.nextChainKey(chainKey)
        }
        val msgKey = RatchetCrypto.messageKey(chainKey)
        val nextChain = RatchetCrypto.nextChainKey(chainKey)
        return decryptWith(msgKey, header, nonce, ct, aad) { plain ->
            OpenOutcome.Opened(
                plain,
                OpenDelta(
                    session = touch(resolved.session, header, ctx, now, eraEffects),
                    recvEpoch = RecvEpoch(epoch = header.se, chainKey = nextChain, next = header.n + 1, lastUsedAt = now),
                    skippedInserts = skipped,
                    purgePeerRecvState = resolved.purge,
                ),
            )
        }
    }

    /**
     * Post-success bookkeeping: adopt the peer's newest epoch as our next DH base, track pe acks, confirm —
     * the era effects, applied only when [eraEffects] (a fresh epoch derived under the active root; see the
     * class invariants) — and retire an expired `prevRoot`, which is calendar, not era.
     *
     * The gate is what #87 needed: a frame drained under the `prevRoot` a reset of ours kept used to confirm
     * the *replacement* session (it names an old-era epoch of ours, whose key survives), so the next frame we
     * sealed dropped the init and was unreadable to a peer that had not yet seen the reset.
     */
    private fun touch(
        session: SessionState,
        header: FrameHeader,
        ctx: OpenContext,
        now: Long,
        eraEffects: Boolean,
    ): SessionState {
        if (!eraEffects) return retirePrevRoot(session, now)
        var out = session
        // Damp the DH-base adoption. A peer that jumps its own numbering (buggy or hostile) would
        // otherwise pin `peerBaseEpoch` somewhere no later epoch can pass the `>` test, and the
        // turnaround-rekey advance rule (`needsNewEpoch`'s `healing`) would never fire again for the
        // life of the session. An *unanchored* session accepts anything under the ceiling: after a
        // reset our anchor is 0 while the peer's numbering keeps climbing (see the replacement branch
        // in `resolveSession`), so a relative bound would refuse legitimate traffic there. Refusing to
        // adopt never drops the frame — it still decrypts and delivers; we simply keep the older base,
        // the peer eventually reports EPOCH_GONE, and that IS reset-triggering, so the pair recovers.
        val anchored = out.peerBaseEpoch >= 1
        if (header.se > out.peerBaseEpoch && (!anchored || header.se <= out.peerBaseEpoch + MAX_EPOCH_JUMP)) {
            out = out.copy(peerBasePub = header.ek, peerBaseEpoch = header.se)
        }
        if (header.pe > out.highestPeAcked) out = out.copy(highestPeAcked = header.pe)
        // The peer sealed against one of OUR epochs — they hold our contribution, so the session is
        // live both ways and the X3DH ephemeral pub no longer needs to ride the wire.
        val peerHoldsOurEpoch = header.pe >= 1 && ctx.ownBasePriv != null
        if (!out.confirmed && out.weAreInitiator && peerHoldsOurEpoch) {
            out = out.copy(confirmed = true, initEphPub = null)
        }
        return retirePrevRoot(out, now)
    }

    private fun retirePrevRoot(
        session: SessionState,
        now: Long,
    ): SessionState =
        if (session.prevRoot != null && session.prevRootExpiresAt <= now) {
            session.copy(prevRoot = null, prevRootExpiresAt = 0L)
        } else {
            session
        }

    /**
     * One AEAD attempt with [key]. A null [nonce] is the v3 form: the header is bound into the AAD and the
     * nonce derives from the very key being tried — so every rung of the ladder and every root candidate
     * derives its own, and a stored skipped key (all that `ratchet_skipped_keys` keeps) still opens.
     */
    private inline fun decryptWith(
        key: ByteArray,
        header: FrameHeader,
        nonce: ByteArray?,
        ct: ByteArray,
        aad: ByteArray,
        onSuccess: (ByteArray) -> OpenOutcome,
    ): OpenOutcome {
        val boundAad = if (nonce == null) aad + RatchetCrypto.headerBindingBytes(header) else aad
        val iv = nonce ?: RatchetCrypto.messageNonce(key, boundAad)
        val plain = runCatching { AesGcm.decrypt(key, iv, ct, boundAad) }.getOrNull()
        return if (plain == null) OpenOutcome.Failed.AEAD_FAIL else onSuccess(plain)
    }

    private class Started(
        val session: SessionState,
        val epoch: LocalEpoch,
    )

    private fun startSendEpoch(
        session: SessionState,
        basePub: ByteArray,
        baseEpoch: Int,
        now: Long,
    ): Started {
        val pair = newKeyPair()
        val se = session.sendEpoch + 1
        val keys =
            RatchetCrypto.deriveEpoch(
                sessionRoot = session.root,
                dhShared = RatchetCrypto.dh(pair.priv, basePub),
                senderIsInitiator = session.weAreInitiator,
                senderEpoch = se,
                baseEpoch = baseEpoch,
            )
        return Started(
            session =
                session.copy(
                    sendEpoch = se,
                    sendEpochPub = pair.pub,
                    sendChainKey = keys.chainKey,
                    sendCount = 0,
                    sendEpochStartedAt = now,
                    sendEpochBaseEpoch = baseEpoch,
                    sendEpochExport = keys.export,
                ),
            epoch = LocalEpoch(epoch = se, priv = pair.priv, pub = pair.pub, createdAt = now),
        )
    }

    companion object {
        /** Epoch length cap — matches the mesh's 200-per-sender custody quota (design doc §advance rules). */
        const val MAX_EPOCH_MESSAGES = 200

        /**
         * Absolute epoch-number ceiling (the `MAX_ROOT_VERSION` half of `GroupRootPolicy`'s shape).
         * Epochs advance at most once per message — the healing rule fires on every turnaround — so
         * this is ~16M messages to one peer, unreachable by a real conversation, while a header near
         * `Int.MAX_VALUE` is refused before it can pin `peerBaseEpoch`.
         */
        const val MAX_EPOCH_NUMBER = 1 shl 24

        /**
         * Per-adoption jump bound on the peer's epoch number (the `MAX_ROOT_VERSION_JUMP` half).
         * Custody holds 200 frames per sender over a 24 h TTL and the peer's advance rules cap it at
         * roughly one epoch per frame plus one per day, so nothing we can still receive is more than a
         * few hundred epochs ahead of our anchor.
         */
        const val MAX_EPOCH_JUMP = 1024

        /** Epoch age cap — matches the 24h custody TTL. */
        const val MAX_EPOCH_AGE_MS = 24 * 60 * 60_000L

        /** How long a superseded root keeps decrypting in-flight epochs (2x the custody TTL). */
        const val PREV_ROOT_TTL_MS = 48 * 60 * 60_000L
    }
}
