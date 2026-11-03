package app.getknit.knit.mesh.crypto.ratchet

import app.getknit.knit.mesh.crypto.AesGcm

/**
 * The DM epoch-rekey ratchet state machine (crypto scheme v2, docs/FORWARD_SECRECY_RATCHET.md). Pure:
 * state comes in as immutable snapshots, changes go out as [SealResult]/[OpenOutcome] deltas the caller
 * persists (atomically with the message row — see `RatchetSessions`); no IO, no Android, randomness
 * only through the injected keypair and v2 IV sources. That keeps every ordering/race scenario a plain-JVM
 * test, and lets `RatchetVectorTest` replay a whole transcript byte for byte (companion change A9).
 *
 * Design invariants (the mesh's custody semantics force all of them — see the design doc):
 * - **Epochs derive independently** off a static per-session root: any subset of a peer's epochs can be
 *   processed in any order, and a wholly-evicted epoch loses only itself.
 * - **Own send-epoch numbers are monotone across the roots a peer hands us** (both-initiate races, inbound
 *   replacements), but **not across our own reset**: `RatchetSessions.sealResetDm` initiates afresh and
 *   restarts at 1, and the re-minted epoch replaces the dead era's key of the same number (ADR 027). So a
 *   peer's `se` is reused only when a session was replaced, and two eras can hold the same number at once.
 * - **Receive state is keyed by the root that derived it** ([RecvEpoch.rootTag], [SkippedKey.rootTag];
 *   ADR 2026-10.gcrd). Each root candidate reads only its own rows, so an old era's chain — drained under a kept
 *   `prevRoot` after the replacement purged everything — never decides a new-era frame of the same number
 *   (#135). The stored purges remain ([OpenDelta.purgePeerRecvState], and `sealResetDm` for its own), as
 *   hygiene; a purging open still reads nothing stored (see `Ladder.climb`).
 * - **A receive chain, once derived, never needs the root again** — `chainKey` alone advances it, which
 *   is what lets a superseded root ([SessionState.prevRoot]) drain for a bounded window and then vanish. The
 *   row keeps only a one-way tag of it.
 * - **A frame moves the session's era only when it derives a fresh epoch under the active root.** Adopting
 *   the peer's epoch as our DH base, recording which of our epochs it has sealed against, and confirming the
 *   session are all claims about the era we hold now, and a frame drained under any other root (a kept
 *   `prevRoot`, a race's other root, a late race remnant) says nothing about it. Every stored receive row
 *   was derived by one fresh derivation under the root its tag names, which already decided then, so the
 *   live-chain and skipped-key rungs never apply them: in era it would repeat what the epoch's first frame
 *   recorded, out of era it would be wrong.
 *   **One exception (rule C, ADR 2026-10.4mjm):** a race loser whose `prevRoot` is its own abandoned init
 *   ([SessionState.prevRootOwnInitAt]) moves back to that root when the peer seals under it as responder, sealed
 *   after the init we lost to — only a peer that adopted our init can, so that frame *is* the era we hold next.
 *
 * [SessionState] and [RecvEpoch] are data classes for `copy` ergonomics; their [ByteArray] fields make
 * generated equality reference-based, which nothing here relies on.
 */
class RatchetEngine(
    private val newKeyPair: () -> RatchetCrypto.KeyPair = RatchetCrypto::generateKeyPair,
    // The v2 form's carried AES-GCM IV; v3 derives its nonce instead. Only a vector test passes another.
    private val newIv: () -> ByteArray = AesGcm::randomIv,
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
        /**
         * Non-null exactly while [prevRoot] is **our own init, abandoned unconfirmed because we lost a race** to the
         * peer's (rule C, ADR 2026-10.4mjm): its own [establishedAt], our clock (ADR 026), read before the race-loser
         * branch overwrote it with the winner's `at`. Only that branch sets it; every other write of [prevRoot] clears
         * it, so a root that was confirmed and later replaced never qualifies. [reclaim] restores [establishedAt] from it.
         */
        val prevRootOwnInitAt: Long? = null,
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

    /**
     * One inbound epoch chain (mirrors a `ratchet_recv_epochs` row). [rootTag] is [RatchetCrypto.rootTag] of the
     * root it was derived under: only that root's candidate reads it.
     */
    data class RecvEpoch(
        val epoch: Int,
        val rootTag: Long,
        val chainKey: ByteArray,
        val next: Int,
        val lastUsedAt: Long,
    )

    /** A stored out-of-order message key (mirrors a `ratchet_skipped_keys` row), tagged like [RecvEpoch]. */
    class SkippedKey(
        val epoch: Int,
        val rootTag: Long,
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
     * [recvEpochs] are every stored chain for the header's `se` and [skippedMsgKeys] every stored key for its
     * `(se, n)`, whatever root derived them — the engine matches each to its candidate by tag and ignores the
     * rest. [ownBasePriv] is our local-epoch priv for the header's `pe` (when `pe >= 1`); [spkPrivForInit] is
     * our signed-prekey priv for the init's `pkid` (when an init is attached).
     */
    class OpenContext(
        val selfNodeId: String,
        val peerId: String,
        val session: SessionState?,
        val recvEpochs: List<RecvEpoch>,
        val skippedMsgKeys: List<SkippedKey>,
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
        /**
         * Whether the caller judged this frame sealed before the session we hold began — the era gate's initiator
         * rule (ADR 026): its `sentAt` (the sender's clock) more than the skew bound before our own
         * `establishedAt`. Supplied by the caller, which holds the envelope; the engine is wire-agnostic. Only
         * the replaced-era check in [resolveSession] reads it.
         */
        val predatesSession: Boolean = false,
        /**
         * Whether the caller judged this frame sealed **after the init we lost a race to** (rule C, ADR 2026-10.4mjm):
         * the session holds a [SessionState.prevRootOwnInitAt], and the frame's `sentAt` is more than the skew bound
         * past the session's `establishedAt`, which the race-loser branch set to the winning init's `at`. Both stamps
         * are the peer's clock. Supplied by the caller, which holds the envelope (`RatchetSessions.sealedSinceLostRace`),
         * so the peek and the commit judge alike; a frame with no `sentAt` (a walkie chunk) never qualifies. Only
         * [RootCandidate.reclaims] reads it.
         */
        val sealedSinceLostRace: Boolean = false,
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

            /**
             * A `FLAG_RESET` init refused by the replacement floor alone ([OpenContext.allowReplacement] false; a newer
             * `at`, an ephemeral we never resolved, our signed-prekey private held), whose frame nothing opened and whose
             * root is none we hold — not the active root, not `prevRoot`, not a race's other root (ADR 2026-11.sfms). A
             * flagged init is adopted or refused, never read (ADR 2026-09.pz9g), so a frame carrying one was never
             * opened and cannot be a re-serve of a consumed index: it outranks [DUPLICATE], which its restarted `se = 1
             * n = 0` would otherwise meet on the previous reset's row (#251). Not reset-triggering — the peer is not
             * diverged, it asked to re-root and the floor said "not yet"; the caller holds the frame and re-opens it
             * once the floor ends. An init that derives a root we hold is a re-entry of an era we already took (our own
             * re-root dropped its anchor), never this: re-opening it would adopt a root the peer has left.
             */
            RESET_FLOORED,
        }
    }

    class OpenDelta(
        val session: SessionState,
        /** Upsert; null when only a skipped key was consumed for an epoch whose row is already gone. */
        val recvEpoch: RecvEpoch?,
        val skippedInserts: List<SkippedKey> = emptyList(),
        /** The tag of the skipped key this open consumed, at the header's `(se, n)`; only that root's key goes. */
        val consumedSkippedTag: Long? = null,
        /** True when a replacement init was adopted: delete ALL recv epochs + skipped keys for this peer first. */
        val purgePeerRecvState: Boolean = false,
        /**
         * With [purgePeerRecvState], the one root whose receive state survives the purge, by its [RatchetCrypto.rootTag]:
         * the root a [reclaim] moved back to. Its chains and skipped keys are the era we hold now (an earlier frame of
         * it may have been read read-only), and the purge is for the era we abandon. Null purges everything.
         */
        val keepRecvTag: Long? = null,
        /** True when this open moved the session back to our own abandoned init ([reclaim], rule C). */
        val reclaimed: Boolean = false,
        /**
         * The [RatchetCrypto.rootTag] of the root candidate the frame opened under, whichever rung opened it. Read against
         * [session]'s own root, it says whether the frame opened under the root the session holds after this open (the
         * active root, a reclaim's included) or under another — a draining `prevRoot`, a late race loser's — which a
         * ctl that files state by era must not treat as a new era (ADR 2026-11.xp6d's presence key, the pz9g predicate).
         * Null only on a delta built outside the ladder.
         */
        val openedRootTag: Long? = null,
    )

    class Initiation(
        val session: SessionState,
        val epoch: LocalEpoch,
    )

    /**
     * Starts a session toward [peerId] (X3DH against their identity + signed prekey) and its first send
     * epoch. The X3DH ephemeral's private half is consumed by the root derivation and never retained —
     * only its public half rides the wire ([FrameHeader.init]) until the session confirms.
     *
     * [peerSpk] is also the session's DH base ([SessionState.peerBasePub], at `peerBaseEpoch = 0`) until the
     * peer contributes an epoch of its own, which [touch] adopts over it. Every `pe = 0` epoch of this session
     * is therefore based on the prekey its init names: the receiver resolves the base for such a frame by the
     * init's `pkid`, and an epoch based on whatever prekey the peer had rotated to by then was unreadable to it
     * (#174).
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
                peerBasePub = peerSpk.pub,
            )
        val started = startSendEpoch(base, basePub = peerSpk.pub, baseEpoch = 0, now = now)
        return Initiation(started.session, started.epoch)
    }

    /**
     * Seals [plaintext] in the session's current send epoch, first advancing to a fresh epoch when an
     * advance rule fires (see [needsNewEpoch]). The fresh epoch's DH base is [SessionState.peerBasePub]:
     * the peer's newest epoch once it has contributed one, else the prekey our init named ([initiate] sets
     * it). [peerSpkPub] is only the fallback for a session that holds neither — one we initiated before
     * [initiate] recorded its prekey (#174) — and callers pass it through [prekeyBase], which refuses a prekey
     * the init does not name. Returns null when a fresh epoch is needed and no base exists; the caller falls
     * back. Throws Tink's `GeneralSecurityException` when X25519 refuses the base (a low-order point a peer
     * signed as its prekey, #172); `RatchetSessions` maps that to "cannot seal".
     * [v3] seals crypto scheme v3 (ADR 059): the ratchet header is bound into the AEAD's
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
            val sealed = AesGcm.encrypt(msgKey, plaintext, aad, newIv())
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

    /**
     * The prekey to hand [seal] as its fallback base for [session], from the peer's pinned [peerSpk] — the one
     * place the #174 rule lives. A session we initiated and the peer has not answered bases every `pe = 0` epoch
     * on the prekey its init names, because that is the key the receiver resolves by `init.pkid`. [initiate]
     * records it, so this matters only for a row an older build initiated with no base recorded: the peer's
     * current prekey serves it only while it is still that one, and null otherwise makes the next epoch boundary
     * return null (the caller falls back to v1, which the peer reads) instead of sealing a frame it cannot open.
     * Any other session is based on the peer's own epoch and never reads the prekey.
     */
    fun prekeyBase(
        session: SessionState,
        peerSpk: PeerPrekey?,
    ): ByteArray? = peerSpk?.takeUnless { session.weAreInitiator && !session.confirmed && it.id != session.initPkid }?.pub

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
     * Opens one inbound v2 frame. The ladder runs over the resolved root candidates — the active root first, the
     * draining [SessionState.prevRoot] second, and, for an unanchored race winner, a late race loser's root last —
     * and each candidate reads only the stored state its own root derived ([RecvEpoch.rootTag], ADR 2026-10.gcrd):
     * a stored skipped key, then the live chain of a known receive epoch (deriving-and-storing keys across any
     * index gap), then, for a candidate holding neither, a brand-new epoch derivation. An attached init may
     * establish, idempotently re-confirm, race-tiebreak, or replace the session; every mutation rides the returned
     * [OpenDelta], and nothing is committed on failure. Only the fresh derivation, and only under the active root,
     * moves the session's era (see the class invariants). A null [nonce] is the v3 form (derived nonce, header
     * bound into the AAD — see [seal]); the ladder is otherwise identical.
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
        return Ladder(ctx, resolved, header, nonce, ct, aad, now).climb()
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
        /**
         * The flagged init the replacement floor alone refused, else null. Judged only when nothing opens ([Ladder.verdict]):
         * [OpenOutcome.Failed.RESET_FLOORED] when it derives none of the roots we hold.
         */
        val flooredReset: InitPayload? = null,
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
        /**
         * The draining `prevRoot` that is our own init, lost to a race, for a frame sealed after the init we lost to
         * ([reclaimsPrevRoot]): a frame that opens under it moves the session back to it ([reclaim]), on every rung.
         */
        val reclaims: Boolean = false,
    ) {
        /** The [RatchetCrypto.rootTag] its stored receive state carries. */
        val tag: Long = RatchetCrypto.rootTag(root)
    }

    /**
     * One frame's way down the ladder. Three passes over the candidates, each candidate leaving the ladder at the
     * first pass that holds something of its own: its stored skipped key for `(se, n)`, else its live chain for
     * `se`, else a fresh derivation. Only one root can open a frame, so the order changes the cost and never the
     * result; this one lets an old era's frame drain on its own stored chain before any DH is paid for it.
     */
    private inner class Ladder(
        private val ctx: OpenContext,
        private val resolved: ResolvedSession,
        private val header: FrameHeader,
        private val nonce: ByteArray?,
        private val ct: ByteArray,
        private val aad: ByteArray,
        private val now: Long,
    ) {
        private var consumed = false
        private var aeadFailed = false
        private var badHeader = false
        private var epochGone = false

        fun climb(): OpenOutcome {
            // A resolution that purges reads nothing stored: every row describes the era it just left, and the
            // resolving frame must reach the fresh derivation that records the new era. A stored rung would skip
            // that — a late race remnant's read banks a skipped key under the very root a flagged reset then asks
            // us to adopt, and opening the reset on it would leave the replacement without its DH base.
            val rows = if (resolved.purge) emptyList() else ctx.recvEpochs
            val keys = if (resolved.purge) emptyList() else ctx.skippedMsgKeys
            val left = resolved.rootCandidates.distinctBy { it.tag }.toMutableList()
            for (candidate in left.toList()) {
                val key = keys.firstOrNull { it.rootTag == candidate.tag } ?: continue
                left -= candidate
                openSkipped(candidate, key, rows.firstOrNull { it.rootTag == candidate.tag })?.let { return it }
            }
            for (candidate in left.toList()) {
                val row = rows.firstOrNull { it.rootTag == candidate.tag } ?: continue
                left -= candidate
                openLive(candidate, row)?.let { return it }
            }
            return openFresh(left) ?: verdict()
        }

        /** The candidate's stored key for `(se, n)`; a failure means the frame is not this root's. */
        private fun openSkipped(
            candidate: RootCandidate,
            key: SkippedKey,
            row: RecvEpoch?,
        ): OpenOutcome.Opened? {
            val outcome =
                decryptWith(key.msgKey, header, nonce, ct, aad) { plain ->
                    OpenOutcome.Opened(
                        plain,
                        OpenDelta(
                            session = settle(ctx, resolved, candidate, header, now, eraEffects = false),
                            recvEpoch = row?.copy(lastUsedAt = now),
                            consumedSkippedTag = candidate.tag,
                            purgePeerRecvState = candidate.reclaims,
                            keepRecvTag = candidate.tag.takeIf { candidate.reclaims },
                            reclaimed = candidate.reclaims,
                            openedRootTag = candidate.tag,
                        ),
                    )
                }
            if (outcome !is OpenOutcome.Opened) aeadFailed = true
            return outcome as? OpenOutcome.Opened
        }

        /** The candidate's live chain for `se`: an index below its cursor is consumed under this root. */
        private fun openLive(
            candidate: RootCandidate,
            row: RecvEpoch,
        ): OpenOutcome.Opened? {
            if (header.n < row.next) {
                consumed = true
                return null
            }
            val outcome = finishOpen(ctx, resolved, candidate, row.chainKey, row.next, header, nonce, ct, aad, now, eraEffects = false)
            if (outcome !is OpenOutcome.Opened) aeadFailed = true
            return outcome as? OpenOutcome.Opened
        }

        /** A brand-new epoch derivation under each candidate that holds nothing stored for this frame. */
        private fun openFresh(candidates: List<RootCandidate>): OpenOutcome.Opened? {
            if (candidates.isEmpty()) return null
            val basePriv = if (header.pe == 0) ctx.spkPrivForInit else ctx.ownBasePriv
            if (basePriv == null) {
                epochGone = true
                return null
            }
            val shared = runCatching { RatchetCrypto.dh(basePriv, header.ek) }.getOrNull()
            if (shared == null) {
                badHeader = true
                return null
            }
            for (candidate in candidates) {
                val keys =
                    RatchetCrypto.deriveEpoch(
                        sessionRoot = candidate.root,
                        dhShared = shared,
                        senderIsInitiator = candidate.senderIsInitiator,
                        senderEpoch = header.se,
                        baseEpoch = header.pe,
                    )
                val outcome = finishOpen(ctx, resolved, candidate, keys.chainKey, 0, header, nonce, ct, aad, now, candidate.active)
                if (outcome is OpenOutcome.Opened) return outcome
                aeadFailed = true
            }
            return null
        }

        /**
         * Nothing opened. DUPLICATE outranks the rest: it is the benign verdict, and a genuine re-serve of a consumed
         * frame also fails under every other candidate (a fresh derivation under a root that never sealed it is an
         * AEAD failure by construction), which must not walk the reset heuristic. It must not stop the ladder,
         * though — a consumed index under one root is exactly what an old era's drained chain shows the new era's
         * frame of the same number (#135). Below it, the order a single candidate always produced. Above it, a flagged
         * init the floor alone refused, for an era we never held ([floored], #251).
         */
        private fun verdict(): OpenOutcome.Failed =
            when {
                floored() -> OpenOutcome.Failed.RESET_FLOORED
                consumed -> OpenOutcome.Failed.DUPLICATE
                aeadFailed -> OpenOutcome.Failed.AEAD_FAIL
                badHeader -> OpenOutcome.Failed.BAD_HEADER
                epochGone -> OpenOutcome.Failed.EPOCH_GONE
                else -> OpenOutcome.Failed.AEAD_FAIL
            }

        /**
         * Whether the frame is a reset the floor refused for a root we never held. One X3DH, on the rare branch that
         * reaches it, generalising [derivesPrevRoot]: an init that derives the active root, the `prevRoot` (live or not)
         * or a race's other root is an era we took and left — our own re-root drops the anchor and keeps the peer's
         * root in the slot — and keeps today's verdict, or its re-open would adopt a root the peer has left.
         */
        private fun floored(): Boolean {
            val init = resolved.flooredReset ?: return false
            val root = respond(ctx, init)
            val held = resolved.rootCandidates.map { it.root } + listOfNotNull(resolved.session.root, resolved.session.prevRoot)
            return held.none { it.contentEquals(root) }
        }
    }

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
            candidates += RootCandidate(it, !session.prevRootWeAreInitiator, active = false, reclaims = reclaimsPrevRoot(ctx, session))
        }
        if (init == null) return ResolvedSession(session, candidates)
        // An init we already resolved (responded to, adopted, or archived), riding a later unconfirmed
        // frame or a custody re-serve: idempotent by its ephemeral key — NOT by timestamp, which would
        // let a re-served race loser's init masquerade as a fresh replacement for its whole custody TTL.
        if (session.peerInitEphPub?.contentEquals(init.eph) == true) return ResolvedSession(session, candidates)
        // Both-initiate race: we hold an unconfirmed session WE started and the peer's own init just
        // arrived. Resolved by nodeId tiebreak, deliberately NOT by comparing `init.at` — concurrent
        // inits can carry the identical timestamp, and both sides must pick the same winner.
        if (!session.confirmed && session.weAreInitiator) {
            // A frame from before our reset, carrying the init that minted the root we replaced, is not a race
            // (ADR 2026-10.j67x). A re-root drops the old era's anchor with the rest of the state, and while our
            // reset waits the old root's scope is subscribed, holding for 48 h every frame the peer sealed as that
            // root's unconfirmed initiator — each with its init. Raced, a smaller-id peer "wins" and we adopt the
            // root we left while it may already be on ours. Read it under the drained root, read-only. A frame
            // sealed since — the peer is *still* that root's unconfirmed initiator, it never heard our answer —
            // races as before: it wins, keeps that root, and we adopt it, or neither side would ever confirm.
            if (ctx.predatesSession && derivesPrevRoot(ctx, session, init, now)) return ResolvedSession(session, candidates)
            return resolveRace(ctx, session, init, now)
        }
        // A genuinely stale init (older than the session we already share) changes nothing.
        if (init.at <= session.establishedAt) return ResolvedSession(session, candidates + remnantCandidates(ctx, header, init))
        if (ctx.spkPrivForInit == null || !ctx.allowReplacement) {
            // A flagged init the floor alone refused is remembered for the verdict; the ladder still climbs (#251).
            val floored = init.takeIf { ctx.resetRequested && ctx.spkPrivForInit != null }
            return ResolvedSession(session, candidates + remnantCandidates(ctx, header, init), flooredReset = floored)
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
        // Replacement (peer reset / re-init after losing state): their epoch numbering restarts at 1. The
        // rows for the old numbering go (hygiene — the tag already keeps them off the new root's frames), and
        // the old root drains via prevRoot for any still-in-flight old frames, on rows tagged with it.
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
                    // A root replaced, confirmed or not, is never ours to reclaim: its drain frames would swap us back
                    // into a dead era (rule C).
                    prevRootOwnInitAt = null,
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
     * Whether [init] is the X3DH init that derived [session]'s `prevRoot` — the era we replaced, not a new one. A
     * peer's reset that crossed ours carries a fresh ephemeral and derives a root of its own, so it still races.
     */
    private fun derivesPrevRoot(
        ctx: OpenContext,
        session: SessionState,
        init: InitPayload,
        now: Long,
    ): Boolean {
        // Past its drain the root is no candidate either: the frame would race, as it always did.
        val prev = session.prevRoot?.takeIf { session.prevRootExpiresAt > now } ?: return false
        return ctx.spkPrivForInit != null && respond(ctx, init).contentEquals(prev)
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
     * under the era being abandoned. Two peers resetting each other race with inits whose sender restarted its
     * numbering, so the winner's fresh epochs share numbers with the loser's surviving rows; the root tag keeps
     * them apart (ADR 2026-10.gcrd) and the purge drops what nothing will read. The side that keeps its own root
     * changes no era and keeps its rows.
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
                        // Rule C: the root we leave is our own init, never answered — the one root a peer that adopted
                        // it can still seal under. Its own stamp (ours, ADR 026), read before the line below takes the
                        // winner's.
                        prevRootOwnInitAt = session.establishedAt,
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
                // abandoning — the replacement case. Before the root tag, a race of RESET requests (whose winner
                // restarted its numbering, `sealResetDm`) landed the winner's fresh epochs on our stale rows as a
                // DUPLICATE that never healed; now the tag keeps them apart, and the purge is what makes this
                // resolving frame read nothing stored (see `Ladder.climb`).
                purge = true,
            )
        } else {
            ResolvedSession(
                session =
                    session.copy(
                        prevRoot = peerRoot,
                        prevRootWeAreInitiator = false,
                        prevRootExpiresAt = now + PREV_ROOT_TTL_MS,
                        prevRootOwnInitAt = null,
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

    /** Advances a chain positioned at [next] to the header's index and opens it; what it stores carries [candidate]'s tag. */
    @Suppress("LongParameterList") // internal plumbing between the two open paths; a param object would just rename it
    private fun finishOpen(
        ctx: OpenContext,
        resolved: ResolvedSession,
        candidate: RootCandidate,
        chainKeyAtNext: ByteArray,
        next: Int,
        header: FrameHeader,
        nonce: ByteArray?,
        ct: ByteArray,
        aad: ByteArray,
        now: Long,
        eraEffects: Boolean,
    ): OpenOutcome {
        val rootTag = candidate.tag
        var chainKey = chainKeyAtNext
        val skipped = mutableListOf<SkippedKey>()
        for (idx in next until header.n) {
            skipped +=
                SkippedKey(epoch = header.se, rootTag = rootTag, idx = idx, msgKey = RatchetCrypto.messageKey(chainKey), createdAt = now)
            chainKey = RatchetCrypto.nextChainKey(chainKey)
        }
        val msgKey = RatchetCrypto.messageKey(chainKey)
        val nextChain = RatchetCrypto.nextChainKey(chainKey)
        return decryptWith(msgKey, header, nonce, ct, aad) { plain ->
            OpenOutcome.Opened(
                plain,
                OpenDelta(
                    session = settle(ctx, resolved, candidate, header, now, eraEffects),
                    recvEpoch =
                        RecvEpoch(epoch = header.se, rootTag = rootTag, chainKey = nextChain, next = header.n + 1, lastUsedAt = now),
                    skippedInserts = skipped,
                    purgePeerRecvState = resolved.purge || candidate.reclaims,
                    keepRecvTag = rootTag.takeIf { candidate.reclaims },
                    reclaimed = candidate.reclaims,
                    openedRootTag = rootTag,
                ),
            )
        }
    }

    /**
     * The session a frame that opened under [candidate] leaves behind: [reclaim] then the era effects when the candidate
     * reclaims our own abandoned init, else [touch] as the rung decided.
     */
    @Suppress("LongParameterList") // the ladder's own inputs, as finishOpen's
    private fun settle(
        ctx: OpenContext,
        resolved: ResolvedSession,
        candidate: RootCandidate,
        header: FrameHeader,
        now: Long,
        eraEffects: Boolean,
    ): SessionState =
        if (candidate.reclaims) {
            touch(reclaim(resolved.session, now), header, ctx, now, eraEffects = true)
        } else {
            touch(resolved.session, header, ctx, now, eraEffects)
        }

    /**
     * Whether a frame that opens under [session]'s draining `prevRoot` moves us back to it (rule C, ADR 2026-10.4mjm): the
     * slot holds our own init, abandoned unconfirmed when we lost a race ([SessionState.prevRootOwnInitAt]), the peer's
     * role under it is responder (it adopted our init), and the frame was sealed after the init we lost to
     * ([OpenContext.sealedSinceLostRace]). Only a peer that adopted our init can seal under it as responder; a race
     * winner reads the loser's root read-only and never seals under it (FSR §7). The freshness half keeps out a frame
     * the peer sealed under our init *before* it left it for the root we then lost to — its restore's reset, say — which
     * would put us back on a root the peer no longer holds.
     */
    private fun reclaimsPrevRoot(
        ctx: OpenContext,
        session: SessionState,
    ): Boolean = session.prevRootOwnInitAt != null && session.prevRootWeAreInitiator && ctx.sealedSinceLostRace

    /**
     * Rule C (ADR 2026-10.4mjm, #189): back onto our own init, which the peer has answered. The active root becomes the
     * `prevRoot` we lost the race on, held as its confirmed initiator, with its own [SessionState.establishedAt] (our
     * clock, ADR 026); the root we had adopted drains as `prevRoot` for a fresh 48 h, and the flag goes. The peer's
     * init stays the anchor ([SessionState.peerInitEphPub]), so its re-serves stay inert. The DH base and the acks are
     * claims about the era we leave and start again — [touch] takes them from the frame that reclaims — and the send
     * chain is dropped so the next seal opens a new epoch under the reclaimed root (our numbering stays monotone).
     * [SessionState.lastResetSentAt] is untouched: it holds the reset floor and qerd's after-wipe decline.
     */
    private fun reclaim(
        session: SessionState,
        now: Long,
    ): SessionState =
        session.copy(
            confirmed = true,
            weAreInitiator = true,
            root = checkNotNull(session.prevRoot),
            establishedAt = checkNotNull(session.prevRootOwnInitAt),
            initEphPub = null,
            prevRoot = session.root,
            prevRootWeAreInitiator = session.weAreInitiator,
            prevRootExpiresAt = now + PREV_ROOT_TTL_MS,
            prevRootOwnInitAt = null,
            peerBasePub = null,
            peerBaseEpoch = 0,
            highestPeAcked = 0,
            sendEpochPub = null,
            sendChainKey = null,
            sendEpochExport = null,
        )

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
            session.copy(prevRoot = null, prevRootExpiresAt = 0L, prevRootOwnInitAt = null)
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
