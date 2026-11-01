package app.getknit.knit.mesh.crypto.ratchet

import app.getknit.knit.mesh.crypto.ratchet.RatchetEngine.OpenOutcome
import app.getknit.knit.mesh.protocol.Protocol
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The epoch-ratchet state machine, driven through an in-memory two-party harness that persists deltas
 * exactly the way `RatchetSessions` will (session snapshot, local/recv epoch maps, skipped keys). Every
 * scenario the mesh forces — reordering, holes, duplicate re-serves, both-initiate races, wipe-and-reset
 * — is a plain-JVM case here.
 */
@Suppress("LargeClass") // one engine's contract over one two-sided harness; splitting it would scatter the Side fake
class RatchetEngineTest {
    private val engine = RatchetEngine()

    private class Frame(
        val header: RatchetEngine.FrameHeader,
        /** Null for a v3 frame: the nonce is derived, never carried. */
        val nonce: ByteArray?,
        val ct: ByteArray,
    )

    /** One device: identity + signed prekey + the stored ratchet state the facade would own. */
    private inner class Side(
        val nodeId: String,
    ) {
        val ik = RatchetCrypto.generateKeyPair()
        var spk = RatchetCrypto.generateKeyPair()
        var spkId = 1

        /** Prekeys this side rotated away from, by id: kept, as `IdentityKeyStore` keeps the last four. */
        val retiredSpks = mutableMapOf<Int, RatchetCrypto.KeyPair>()
        var spkResolvable = true

        /** The weekly rotation: a new current prekey under the next id, the old one still resolvable. */
        fun rotatePrekey() {
            retiredSpks[spkId] = spk
            spkId += 1
            spk = RatchetCrypto.generateKeyPair()
        }

        var session: RatchetEngine.SessionState? = null
        val localEpochs = mutableMapOf<Int, RatchetEngine.LocalEpoch>()

        /** Keyed `(se, rootTag)`, as `ratchet_recv_epochs`. */
        val recvEpochs = mutableMapOf<Pair<Int, Long>, RatchetEngine.RecvEpoch>()

        /** Keyed `(se, n, rootTag)`, as `ratchet_skipped_keys`. */
        val skipped = mutableMapOf<Triple<Int, Int, Long>, RatchetEngine.SkippedKey>()

        /** The epoch numbers we hold a receive chain for, whatever root derived them. */
        val recvNumbers: Set<Int> get() = recvEpochs.keys.mapTo(mutableSetOf()) { it.first }
        lateinit var peer: Side
        var lastDelta: RatchetEngine.OpenDelta? = null

        fun initiate(now: Long) {
            val initiation =
                engine.initiate(peer.nodeId, ik.priv, peer.ik.pub, RatchetEngine.PeerPrekey(id = peer.spkId, pub = peer.spk.pub), now)
            session = initiation.session
            localEpochs[initiation.epoch.epoch] = initiation.epoch
        }

        fun seal(
            plain: String,
            now: Long,
            force: Boolean = false,
            v3: Boolean = false,
        ): Frame {
            val result = checkNotNull(engine.seal(checkNotNull(session), plain.toByteArray(), AAD, peer.spk.pub, now, force, v3))
            session = result.session
            result.newLocalEpoch?.let { localEpochs[it.epoch] = it }
            return Frame(result.header, result.nonce, result.ct)
        }

        /** [sentAt] is the frame envelope's stamp (the sender's clock), judged as `RatchetSessions.contextFor` judges it. */
        fun open(
            frame: Frame,
            now: Long,
            resetRequested: Boolean = false,
            sentAt: Long? = null,
            // The caller's replacement floor (`RatchetSessions.contextFor`): false while it runs.
            allowReplacement: Boolean = true,
        ): OpenOutcome {
            val ctx =
                RatchetEngine.OpenContext(
                    selfNodeId = nodeId,
                    peerId = peer.nodeId,
                    session = session,
                    recvEpochs = recvEpochs.values.filter { it.epoch == frame.header.se },
                    skippedMsgKeys = skipped.values.filter { it.epoch == frame.header.se && it.idx == frame.header.n },
                    ownBasePriv = localEpochs[frame.header.pe]?.priv,
                    ownIkPriv = ik.priv,
                    peerIkPub = peer.ik.pub,
                    spkPrivForInit =
                        frame.header.init
                            ?.takeIf { spkResolvable }
                            ?.let { if (it.pkid == spkId) spk.priv else retiredSpks[it.pkid]?.priv },
                    resetRequested = resetRequested,
                    sealedSinceLostRace = RatchetSessions.sealedSinceLostRace(session, sentAt),
                    allowReplacement = allowReplacement,
                )
            val outcome = engine.open(ctx, frame.header, frame.nonce, frame.ct, AAD, now)
            if (outcome is OpenOutcome.Opened) apply(outcome.delta, frame.header)
            return outcome
        }

        private fun apply(
            delta: RatchetEngine.OpenDelta,
            header: RatchetEngine.FrameHeader,
        ) {
            lastDelta = delta
            if (delta.purgePeerRecvState) {
                // A reclaim (rule C) spares the root it moved back to, as RatchetRepository's purge does.
                recvEpochs.keys.removeAll { it.second != delta.keepRecvTag }
                skipped.keys.removeAll { it.third != delta.keepRecvTag }
            }
            session = delta.session
            delta.recvEpoch?.let { recvEpochs[it.epoch to it.rootTag] = it }
            delta.skippedInserts.forEach { skipped[Triple(it.epoch, it.idx, it.rootTag)] = it }
            delta.consumedSkippedTag?.let { skipped.remove(Triple(header.se, header.n, it)) }
        }

        /**
         * A reset request of ours, minted the way `RatchetSessions.sealResetDm` does: a fresh initiation that
         * keeps the old root draining as `prevRoot`, our own receive state purged, and send numbering restarted
         * at 1 — its epoch replaces the dead era's key of that number (ADR 027). The peer opens the returned
         * frame with `resetRequested = true`; what we seal after it carries the same init, unflagged.
         */
        fun reset(now: Long): Frame {
            val old = session
            val initiation =
                engine.initiate(peer.nodeId, ik.priv, peer.ik.pub, RatchetEngine.PeerPrekey(id = peer.spkId, pub = peer.spk.pub), now)
            session =
                initiation.session.copy(
                    prevRoot = old?.root,
                    prevRootWeAreInitiator = old?.weAreInitiator ?: false,
                    prevRootExpiresAt = if (old != null) now + RatchetEngine.PREV_ROOT_TTL_MS else 0L,
                    lastResetSentAt = now,
                )
            localEpochs[initiation.epoch.epoch] = initiation.epoch
            recvEpochs.clear()
            skipped.clear()
            return seal("reset", now)
        }

        /** A device wipe: ratchet state gone, identity + prekeys (identity.key) intact. */
        fun wipe() {
            session = null
            localEpochs.clear()
            recvEpochs.clear()
            skipped.clear()
        }
    }

    private fun pair(
        firstId: String = "aaaaaaaa",
        secondId: String = "bbbbbbbb",
    ): Pair<Side, Side> {
        val a = Side(firstId)
        val b = Side(secondId)
        a.peer = b
        b.peer = a
        return a to b
    }

    private fun text(outcome: OpenOutcome): String = String((outcome as OpenOutcome.Opened).plaintext)

    // --- establishment ---

    @Test
    fun initFirstMessageAndReplyConfirmBothSides() {
        val (a, b) = pair()
        a.initiate(NOW)

        val first = a.seal("hello", NOW)
        assertEquals(1, first.header.se)
        assertEquals(0, first.header.pe)
        assertNotNull(first.header.init)

        assertEquals("hello", text(b.open(first, NOW)))
        assertTrue(checkNotNull(b.session).confirmed)
        assertFalse(checkNotNull(b.session).weAreInitiator)

        val reply = b.seal("hi back", NOW)
        assertEquals(1, reply.header.se)
        assertEquals(1, reply.header.pe)
        assertNull(reply.header.init)

        assertEquals("hi back", text(a.open(reply, NOW)))
        assertTrue(checkNotNull(a.session).confirmed)
        assertNull(a.seal("post-confirm", NOW).header.init)
    }

    @Test
    fun bothSidesDeriveTheSamePairwiseExportRoot() {
        val (a, b) = pair()
        a.initiate(NOW)
        b.open(a.seal("x", NOW), NOW)

        assertArrayEquals(
            RatchetCrypto.exportRoot(checkNotNull(a.session).root),
            RatchetCrypto.exportRoot(checkNotNull(b.session).root),
        )
    }

    // --- reordering, duplicates, holes ---

    @Test
    fun outOfOrderArrivalUsesSkippedKeysExactlyOnce() {
        val (a, b) = pair()
        a.initiate(NOW)
        val m0 = a.seal("m0", NOW)
        val m1 = a.seal("m1", NOW)
        val m2 = a.seal("m2", NOW)

        assertEquals("m2", text(b.open(m2, NOW)))
        assertEquals(2, b.skipped.size)

        assertEquals("m0", text(b.open(m0, NOW)))
        assertEquals(1, b.skipped.size)
        assertTrue(b.open(m0, NOW) === OpenOutcome.Failed.DUPLICATE)

        assertEquals("m1", text(b.open(m1, NOW)))
        assertTrue(b.skipped.isEmpty())
    }

    @Test
    fun skippedKeyStillOpensAfterItsEpochRowWasSwept() {
        val (a, b) = pair()
        a.initiate(NOW)
        val m0 = a.seal("m0", NOW)
        b.open(a.seal("m1", NOW).also { a.seal("m2", NOW) }, NOW)

        b.recvEpochs.clear()
        assertEquals("m0", text(b.open(m0, NOW)))
        assertTrue(b.recvEpochs.isEmpty())
    }

    @Test
    fun aWhollyLostEpochLosesOnlyItself() {
        val (a, b) = pair()
        a.initiate(NOW)
        a.seal("lost-0", NOW)
        a.seal("lost-1", NOW)

        val fresh = a.seal("epoch-2", NOW, force = true)
        assertEquals(2, fresh.header.se)
        assertEquals("epoch-2", text(b.open(fresh, NOW)))
    }

    // --- epoch advance rules ---

    @Test
    fun epochAdvancesAtTheMessageCountCap() {
        val (a, _) = pair()
        a.initiate(NOW)
        repeat(RatchetEngine.MAX_EPOCH_MESSAGES) { assertEquals(1, a.seal("m$it", NOW).header.se) }
        assertEquals(2, a.seal("overflow", NOW).header.se)
    }

    @Test
    fun epochAdvancesAtTheAgeCap() {
        val (a, _) = pair()
        a.initiate(NOW)
        assertEquals(1, a.seal("young", NOW).header.se)
        assertEquals(2, a.seal("old", NOW + RatchetEngine.MAX_EPOCH_AGE_MS).header.se)
    }

    @Test
    fun epochAdvancesOnTheFirstSendAfterAPeerContribution() {
        val (a, b) = pair()
        a.initiate(NOW)
        b.open(a.seal("hello", NOW), NOW)
        a.open(b.seal("reply", NOW), NOW)

        val healed = a.seal("healed", NOW)
        assertEquals(2, healed.header.se)
        assertEquals(1, healed.header.pe)
        assertEquals("healed", text(b.open(healed, NOW)))
    }

    // --- typed failures ---

    @Test
    fun aFrameWithoutInitToAFreshDeviceIsNoSession() {
        val (a, b) = pair()
        a.initiate(NOW)
        b.open(a.seal("establish", NOW), NOW)
        val confirmed = b.seal("no init attached", NOW)

        val (_, stranger) = pair(firstId = a.nodeId, secondId = "cccccccc")
        stranger.peer = b
        assertTrue(stranger.open(confirmed, NOW) === OpenOutcome.Failed.NO_SESSION)
    }

    @Test
    fun anInitAgainstAPrunedPrekeyIsEpochGone() {
        val (a, b) = pair()
        a.initiate(NOW)
        b.spkResolvable = false
        assertTrue(b.open(a.seal("hello", NOW), NOW) === OpenOutcome.Failed.EPOCH_GONE)
    }

    @Test
    fun aFrameAgainstADeletedOwnEpochIsEpochGone() {
        val (a, b) = pair()
        a.initiate(NOW)
        b.open(a.seal("hello", NOW), NOW)
        val reply = b.seal("reply", NOW)

        a.localEpochs.clear()
        assertTrue(a.open(reply, NOW) === OpenOutcome.Failed.EPOCH_GONE)
    }

    @Test
    fun structurallyInvalidHeadersAreBadHeader() {
        val (a, b) = pair()
        a.initiate(NOW)
        val frame = a.seal("hello", NOW)

        // Rebuild the header around the real nonce/ct: only the header is under test, and every one of
        // these must be refused before the session machinery sees it.
        fun tamper(
            se: Int = 1,
            ek: ByteArray = frame.header.ek,
            pe: Int = 0,
            n: Int = 0,
            init: RatchetEngine.InitPayload? = frame.header.init,
        ) = b.open(Frame(RatchetEngine.FrameHeader(se, ek, pe, n, init), frame.nonce, frame.ct), NOW)

        assertTrue(tamper(init = null) === OpenOutcome.Failed.BAD_HEADER)
        assertTrue(tamper(n = RatchetEngine.MAX_EPOCH_MESSAGES) === OpenOutcome.Failed.BAD_HEADER)
        assertTrue(tamper(n = -1) === OpenOutcome.Failed.BAD_HEADER)
        assertTrue(tamper(se = 0) === OpenOutcome.Failed.BAD_HEADER)
        assertTrue(tamper(ek = frame.header.ek.copyOf(31)) === OpenOutcome.Failed.BAD_HEADER)

        // A negative pe used to slip through on the strength of the attached init: contextFor resolves
        // ownBasePriv only for pe >= 1 and openNewEpoch's SPK branch is `pe == 0`, so the frame landed
        // as EPOCH_GONE — which IS reset-triggering. Structurally invalid must mean BAD_HEADER.
        assertTrue(tamper(pe = -1) === OpenOutcome.Failed.BAD_HEADER)

        // Both epoch numbers are bounded above too, or an absurd one reaches the same wrong report.
        assertTrue(tamper(se = RatchetEngine.MAX_EPOCH_NUMBER + 1) === OpenOutcome.Failed.BAD_HEADER)
        assertTrue(tamper(pe = RatchetEngine.MAX_EPOCH_NUMBER + 1) === OpenOutcome.Failed.BAD_HEADER)
    }

    /**
     * A peer that jumps its own epoch numbering must not pin our DH base. The frame still decrypts and
     * delivers — refusing the *adoption* is not a drop — but `peerBaseEpoch` stays where it was, so the
     * turnaround-rekey rule keeps firing for the rest of the session.
     */
    @Test
    fun anAbsurdEpochJumpIsNotAdoptedAsOurDhBase() {
        val (a, b) = pair()
        a.initiate(NOW)
        b.open(a.seal("hello", NOW), NOW)
        b.open(a.seal("again", NOW), NOW)
        val anchor = checkNotNull(b.session).peerBaseEpoch
        assertEquals(1, anchor)

        // Force a's numbering far past anything it could legitimately reach; the frame is sealed for
        // real, so it opens — only the adoption is in question.
        a.session = checkNotNull(a.session).copy(sendEpoch = anchor + RatchetEngine.MAX_EPOCH_JUMP)
        val jumped = a.seal("still readable", NOW, force = true)
        assertEquals(anchor + RatchetEngine.MAX_EPOCH_JUMP + 1, jumped.header.se)
        assertEquals("still readable", text(b.open(jumped, NOW)))
        assertEquals("the out-of-range epoch is not adopted as our base", anchor, checkNotNull(b.session).peerBaseEpoch)

        // The session is undamaged: a's next in-range epoch is still adopted, so healing survives.
        a.session = checkNotNull(a.session).copy(sendEpoch = anchor)
        val inRange = a.seal("in range", NOW, force = true)
        assertEquals(anchor + 1, inRange.header.se)
        assertEquals("in range", text(b.open(inRange, NOW)))
        assertEquals(anchor + 1, checkNotNull(b.session).peerBaseEpoch)
    }

    /** The jump bound is inclusive at the boundary. */
    @Test
    fun anEpochExactlyAtTheJumpBoundIsAdopted() {
        val (a, b) = pair()
        a.initiate(NOW)
        b.open(a.seal("hello", NOW), NOW)
        val anchor = checkNotNull(b.session).peerBaseEpoch

        a.session = checkNotNull(a.session).copy(sendEpoch = anchor + RatchetEngine.MAX_EPOCH_JUMP - 1)
        val atBound = a.seal("edge", NOW, force = true)
        assertEquals(anchor + RatchetEngine.MAX_EPOCH_JUMP, atBound.header.se)
        assertEquals("edge", text(b.open(atBound, NOW)))
        assertEquals(anchor + RatchetEngine.MAX_EPOCH_JUMP, checkNotNull(b.session).peerBaseEpoch)
    }

    /**
     * With no anchor yet the jump bound must not apply. This is the post-reset shape: adopting a
     * replacement zeroes `peerBaseEpoch` while the peer's own numbering keeps climbing, so a relative
     * bound here would refuse the peer's very next legitimate frame — and BAD_HEADER drives no reset,
     * so the pair would stay dark with no way back.
     */
    @Test
    fun anUnanchoredSessionAdoptsAnyLegalEpoch() {
        val (a, b) = pair()
        a.initiate(NOW)
        a.session = checkNotNull(a.session).copy(sendEpoch = 5 * RatchetEngine.MAX_EPOCH_JUMP)
        val farAhead = a.seal("after a reset", NOW, force = true)
        assertEquals(5 * RatchetEngine.MAX_EPOCH_JUMP + 1, farAhead.header.se)
        assertEquals("after a reset", text(b.open(farAhead, NOW)))
        assertEquals(farAhead.header.se, checkNotNull(b.session).peerBaseEpoch)
    }

    @Test
    fun aTamperedCiphertextIsAeadFail() {
        val (a, b) = pair()
        a.initiate(NOW)
        val frame = a.seal("hello", NOW)
        val tampered = Frame(frame.header, frame.nonce, frame.ct.copyOf().also { it[0] = (it[0] + 1).toByte() })
        assertTrue(b.open(tampered, NOW) === OpenOutcome.Failed.AEAD_FAIL)
    }

    // --- crypto scheme v3: a derived nonce and a header-bound AAD on the unchanged v2 chain (ADR 059) ---

    @Test
    fun aV3FrameCarriesNoNonceAndOpensOnEveryRungOfTheLadder() {
        val (a, b) = pair()
        a.initiate(NOW)

        // A fresh epoch derivation (the init frame), then the live chain.
        val first = a.seal("v3 init", NOW, v3 = true)
        assertNull("v3 derives its nonce; nothing random rides the wire", first.nonce)
        assertEquals("v3 init", text(b.open(first, NOW)))
        assertEquals("v3 live", text(b.open(a.seal("v3 live", NOW, v3 = true), NOW)))

        // Out of order: the skipped index is opened later from its STORED message key alone, which is why
        // the nonce derives from the message key rather than the chain key.
        val skipped = a.seal("v3 skipped", NOW, v3 = true)
        val later = a.seal("v3 later", NOW, v3 = true)
        assertEquals("v3 later", text(b.open(later, NOW)))
        assertEquals("v3 skipped", text(b.open(skipped, NOW)))

        // A forced new epoch, and v2/v3 interleaved on the same session.
        assertEquals("v3 new epoch", text(b.open(a.seal("v3 new epoch", NOW, force = true, v3 = true), NOW)))
        assertEquals("v2 after v3", text(b.open(a.seal("v2 after v3", NOW), NOW)))
        assertEquals("v3 after v2", text(b.open(a.seal("v3 after v2", NOW, v3 = true), NOW)))
    }

    @Test
    fun aV3FrameOpenedAsV2OrAV2FrameOpenedAsV3IsAeadFail() {
        val (a, b) = pair()
        a.initiate(NOW)
        val v3 = a.seal("hello", NOW, v3 = true)
        // Hand it a nonce: the receiver then binds no header and uses the carried nonce — the wrong AAD and IV.
        assertTrue(b.open(Frame(v3.header, ByteArray(12), v3.ct), NOW) === OpenOutcome.Failed.AEAD_FAIL)
        val v2 = a.seal("hello again", NOW)
        // Strip the nonce off a v2 frame: the receiver derives one and binds the header — again the wrong pair.
        assertTrue(b.open(Frame(v2.header, null, v2.ct), NOW) === OpenOutcome.Failed.AEAD_FAIL)
    }

    @Test
    fun aV3FrameBindsItsHeaderFlagsAndInitClock() {
        val (a, b) = pair()
        a.initiate(NOW)
        val frame = a.seal("hello", NOW, v3 = true)
        val h = frame.header
        val init = checkNotNull(h.init)

        // The two header fields the derived key does not already bind — a flipped reset flag and a moved
        // establishment clock — must fail the AEAD rather than reach the session machinery.
        val flagged = RatchetEngine.FrameHeader(h.se, h.ek, h.pe, h.n, h.init, flags = 1)
        assertTrue(b.open(Frame(flagged, null, frame.ct), NOW) === OpenOutcome.Failed.AEAD_FAIL)
        val movedClock = RatchetEngine.FrameHeader(h.se, h.ek, h.pe, h.n, RatchetEngine.InitPayload(init.eph, init.pkid, init.at + 1))
        assertTrue(b.open(Frame(movedClock, null, frame.ct), NOW) === OpenOutcome.Failed.AEAD_FAIL)
        // And the untouched frame still opens after the two refusals (nothing was committed).
        assertEquals("hello", text(b.open(frame, NOW)))
    }

    // --- both-initiate race ---

    @Test
    fun bothInitiateRaceConvergesOnTheLowerNodeIdsSession() {
        val (a, b) = pair()
        a.initiate(NOW)
        b.initiate(NOW)
        val fromA = a.seal("from a", NOW)
        val fromB = b.seal("from b", NOW)

        // A (lower nodeId) wins on both ends: A keeps its root and archives B's; B adopts A's.
        assertEquals("from b", text(a.open(fromB, NOW)))
        assertTrue(checkNotNull(a.session).weAreInitiator)
        assertNotNull(checkNotNull(a.session).prevRoot)

        assertEquals("from a", text(b.open(fromA, NOW)))
        assertTrue(checkNotNull(b.session).confirmed)
        assertFalse(checkNotNull(b.session).weAreInitiator)
        assertArrayEquals(checkNotNull(a.session).root, checkNotNull(b.session).root)

        // Post-race traffic flows both ways under the winning root; epoch numbering stayed monotone.
        val bNext = b.seal("under the winning root", NOW)
        assertEquals(2, bNext.header.se)
        assertEquals("under the winning root", text(a.open(bNext, NOW)))
        assertTrue(checkNotNull(a.session).confirmed)
        assertEquals("ack", text(b.open(a.seal("ack", NOW), NOW)))
    }

    @Test
    fun theRaceLoserDropsTheRecvStateOfTheEraItAbandons() {
        val (a, b) = pair()
        // Consume a couple of indices under the pre-race era, so B holds recv rows for epoch 1 that a
        // reset-restarted numbering will later collide with.
        a.initiate(NOW)
        assertEquals("one", text(b.open(a.seal("one", NOW), NOW)))
        assertEquals("two", text(b.open(a.seal("two", NOW), NOW)))
        assertEquals(setOf(1), b.recvNumbers)

        // Both sides now re-initiate at each other — two peers resetting each other, which is what the
        // recovery path produces once every undecryptable outcome can request one.
        a.initiate(NOW + 1)
        b.initiate(NOW + 1)
        val fromA = a.seal("after the race", NOW + 1)

        // B loses the nodeId tiebreak and adopts A's root. The rows above describe chains under the era it
        // just abandoned; keeping them makes A's restarted epoch 1 land on a consumed index and read as a
        // duplicate — benign per frame, and a permanent one-way deadlock in aggregate.
        assertEquals("after the race", text(b.open(fromA, NOW + 1)))
        assertTrue("adopting the winner's root must drop the loser's recv state", checkNotNull(b.lastDelta).purgePeerRecvState)
        assertEquals("only the freshly-derived epoch survives", setOf(fromA.header.se), b.recvNumbers)
    }

    @Test
    fun raceLosersInFlightFramesStillDrainViaThePreviousRoot() {
        val (a, b) = pair()
        a.initiate(NOW)
        b.initiate(NOW)
        val early0 = b.seal("early 0", NOW)
        val early1 = b.seal("early 1", NOW)

        assertEquals("early 1", text(a.open(early1, NOW)))
        b.open(a.seal("from a", NOW), NOW)

        // A late copy of the loser-root epoch still opens: its receive chain was derived before the
        // race resolved, and chains never need the root again.
        assertEquals("early 0", text(a.open(early0, NOW)))
    }

    @Test
    fun aReservedRaceInitNeverReplacesTheResolvedSession() {
        val (a, b) = pair()
        a.initiate(NOW)
        b.initiate(NOW + 5_000)
        val fromB = b.seal("from b", NOW + 5_000)
        val fromA = a.seal("from a", NOW)

        assertEquals("from b", text(a.open(fromB, NOW)))
        assertEquals("from a", text(b.open(fromA, NOW)))
        assertEquals("settle", text(a.open(b.seal("settle", NOW), NOW)))
        assertTrue(checkNotNull(a.session).confirmed)
        val rootAfterRace = checkNotNull(a.session).root

        // The loser's init re-served from custody hours later. Its timestamp (NOW + 5s) is NEWER than
        // the winning session's establishedAt (NOW), so a timestamp-based rule would treat it as a
        // fresh replacement and wreck the session on every re-serve for the whole custody TTL; the
        // ephemeral-key idempotence match must classify it as already-resolved instead.
        val reServed = a.open(fromB, NOW + 60_000)
        assertTrue(reServed === OpenOutcome.Failed.DUPLICATE)
        assertArrayEquals(rootAfterRace, checkNotNull(a.session).root)
        assertTrue(checkNotNull(a.session).confirmed)
    }

    /**
     * #83. The race resolves entirely through A's frames: B adopts A's root and answers under it, and A
     * confirms on the answer WITHOUT ever having processed B's init — no idempotence anchor. B's opening DM,
     * sealed under its losing root while the two were apart, lands after that. It is a real message: it must
     * open, read-only, and never replace the session both sides share.
     */
    private fun raceLosersLateOpeningDmOpensReadOnly(loserInitiatedAt: Long) {
        val (a, b) = pair()
        a.initiate(NOW)
        b.initiate(loserInitiatedAt)
        val opening = b.seal("from b, losing root", loserInitiatedAt)

        b.open(a.seal("from a", NOW), NOW)
        a.open(b.seal("reply under the winner", NOW), NOW)
        val settled = checkNotNull(a.session)
        assertTrue(settled.confirmed)
        assertNull(settled.peerInitEphPub)

        assertEquals("from b, losing root", text(a.open(opening, NOW + 60_000)))
        val after = checkNotNull(a.session)
        assertArrayEquals("never defects to the losing root", settled.root, after.root)
        assertNull("no prevRoot is kept for it", after.prevRoot)
        assertNull("no anchor is recorded", after.peerInitEphPub)
        assertEquals(settled.peerBaseEpoch, after.peerBaseEpoch)
        assertEquals(settled.highestPeAcked, after.highestPeAcked)
        assertTrue(after.confirmed && after.weAreInitiator)

        // Custody re-serves it for a TTL: the receive row its first open stored makes every copy benign.
        assertTrue(a.open(opening, NOW + 120_000) === OpenOutcome.Failed.DUPLICATE)
        assertEquals("still talking", text(b.open(a.seal("still talking", NOW + 120_000), NOW + 120_000)))
        assertEquals("both ways", text(a.open(b.seal("both ways", NOW + 120_000), NOW + 120_000)))
    }

    /** The remnant guard's branch: the loser's init is NEWER than the session the winner confirmed. */
    @Test
    fun aRaceLosersLateOpeningDmOpensReadOnlyWhenItInitiatedLater() = raceLosersLateOpeningDmOpensReadOnly(NOW + 5_000)

    /** The stale-init branch: the loser's init is OLDER than the session the winner confirmed. */
    @Test
    fun aRaceLosersLateOpeningDmOpensReadOnlyWhenItInitiatedEarlier() = raceLosersLateOpeningDmOpensReadOnly(NOW - 5_000)

    /**
     * The winner reads the loser's pre-adoption frames — both of them, the second on the live chain — but
     * their epoch sits under the root it is abandoning, so it never becomes the winner's DH base: the winner
     * stays on the loser's signed prekey, init attached, until the loser answers under the winning root.
     */
    @Test
    fun theRaceWinnerNeverTakesTheLosersPreAdoptionEpochAsItsDhBase() {
        val (a, b) = pair()
        a.initiate(NOW)
        b.initiate(NOW)
        val early0 = b.seal("early 0", NOW)
        val early1 = b.seal("early 1", NOW)

        assertEquals("early 0", text(a.open(early0, NOW)))
        assertEquals("early 1", text(a.open(early1, NOW)))
        assertEquals(0, checkNotNull(a.session).peerBaseEpoch)
        val fromA = a.seal("from a", NOW)
        assertEquals(0, fromA.header.pe)
        assertNotNull(fromA.header.init)

        assertEquals("from a", text(b.open(fromA, NOW)))
        assertEquals("answer", text(a.open(b.seal("answer", NOW), NOW)))
        assertTrue(checkNotNull(a.session).confirmed)
        assertArrayEquals(checkNotNull(a.session).root, checkNotNull(b.session).root)
    }

    /**
     * What `FLAG_RESET` exempts from the remnant guard it also keeps out of the read-only remnant candidate: a
     * reset is adopted or refused, never read without adoption — reading one would run its re-seal under the
     * root it asks us to leave. One whose init is older than the session we confirmed stays refused.
     */
    @Test
    fun aStaleFlaggedResetIsRefusedNeverReadWithoutAdoption() {
        val (a, b) = pair()
        val staleReset = b.reset(NOW)
        a.initiate(NOW + 10_000)
        b.open(a.seal("from a", NOW + 10_000), NOW + 10_000)
        a.open(b.seal("reply under the winner", NOW + 10_000), NOW + 10_000)
        assertTrue(checkNotNull(a.session).confirmed)
        assertNull(checkNotNull(a.session).peerInitEphPub)
        val root = checkNotNull(a.session).root

        assertTrue(a.open(staleReset, NOW + 20_000, resetRequested = true) === OpenOutcome.Failed.AEAD_FAIL)
        assertArrayEquals(root, checkNotNull(a.session).root)
    }

    @Test
    fun anExplicitResetIsAdoptedEvenByTheUnanchoredRaceWinner() {
        val (a, b) = pair()
        a.initiate(NOW)
        b.initiate(NOW + 5_000)

        // A wins the race on nodeId and confirms WITHOUT ever processing B's init, so it holds no
        // idempotence anchor — the state the race-remnant guard exists for.
        b.open(a.seal("from a", NOW), NOW)
        a.open(b.seal("reply under the winner", NOW), NOW)
        assertTrue(checkNotNull(a.session).confirmed)
        assertNull(checkNotNull(a.session).peerInitEphPub)
        assertTrue("precondition: B is the higher nodeId the guard refuses", b.nodeId > a.nodeId)

        // B loses its state and explicitly asks to re-establish. Unflagged this is indistinguishable
        // from a re-served race remnant and is only ever read, never adopted (aRaceLosersLateOpeningDm...);
        // flagged, it must be adopted, because refusing it leaves B unable to recover from its own side
        // at all — only A's 6 h reset heuristic could ever clear it, and that is the six-hour
        // one-directional blackout ADR 024 was opened for.
        b.wipe()
        b.initiate(NOW + 600_000)
        val outcome = a.open(b.seal("re-establish", NOW + 600_000), NOW + 600_000, resetRequested = true)

        assertEquals("re-establish", text(outcome))
        assertFalse("adopting a reset makes us the responder", checkNotNull(a.session).weAreInitiator)
        assertArrayEquals(checkNotNull(b.session).root, checkNotNull(a.session).root)
    }

    @Test
    fun adoptingAResetAnchorsItSoItsOwnReServesAreInert() {
        val (a, b) = pair()
        a.initiate(NOW)
        b.initiate(NOW + 5_000)
        b.open(a.seal("from a", NOW), NOW)
        a.open(b.seal("reply under the winner", NOW), NOW)

        b.wipe()
        b.initiate(NOW + 600_000)
        val reset = b.seal("re-establish", NOW + 600_000)
        a.open(reset, NOW + 600_000, resetRequested = true)
        val adopted = checkNotNull(a.session).root

        // Custody re-serves the reset for a full TTL. The ephemeral recorded on adoption is what makes
        // every one of those inert, so the exemption cannot be turned into a re-rooting loop.
        val again = a.open(reset, NOW + 900_000, resetRequested = true)
        assertTrue(again === OpenOutcome.Failed.DUPLICATE)
        assertArrayEquals(adopted, checkNotNull(a.session).root)
    }

    @Test
    fun theSideThatAdoptedAReplacementCanStillSendBack() {
        val (a, b) = pair()
        a.initiate(NOW)
        b.open(a.seal("hello", NOW), NOW)
        a.open(b.seal("hi", NOW), NOW)

        // A re-initiates (a reset): B must adopt it, which nulls B's peer base epoch.
        a.initiate(NOW + 600_000)
        assertEquals("re-established", text(b.open(a.seal("re-established", NOW + 600_000), NOW + 600_000)))

        // B, the adopter, replies. Its peerBaseEpoch is 0, so the frame carries pe=0 and no init.
        val back = a.open(b.seal("reply after adopting", NOW + 601_000), NOW + 601_000)
        assertEquals("reply after adopting", text(back))
    }

    // --- #251: a reset the replacement floor refuses (ADR 2026-11.sfms) ---

    /** A confirmed pair where [resetter] reset once and the other side adopted it, starting its one-minute floor. */
    private fun adoptedOneReset(): Pair<Side, Side> {
        val (a, b) = pair()
        a.initiate(NOW)
        b.open(a.seal("hello", NOW), NOW)
        a.open(b.seal("hi", NOW), NOW)
        assertEquals("reset", text(a.open(b.reset(NOW + 10_000), NOW + 10_000, resetRequested = true)))
        return a to b
    }

    /**
     * #251: the resetter resets again a few seconds after the first was adopted. Its numbering restarted, so the second
     * reset's `se = 1 n = 0` meets the row the first reset's own `se = 1 n = 0` left, and inside the floor it used to read
     * as DUPLICATE — the benign verdict, which nothing re-runs. Refused by the floor alone, for a root the receiver never
     * held, it is its own verdict; once the floor ends the same frame is adopted.
     */
    @Test
    fun aResetTheFloorRefusesForAnEraWeNeverHeldIsFlooredNeverDuplicate() {
        val (a, b) = adoptedOneReset()
        val root = checkNotNull(a.session).root

        val second = b.reset(NOW + 13_000)
        assertEquals("the restarted numbering meets the first reset's row", 1, second.header.se)
        assertEquals(0, second.header.n)
        val floored = a.open(second, NOW + 13_000, resetRequested = true, allowReplacement = false)
        assertTrue("was $floored", floored === OpenOutcome.Failed.RESET_FLOORED)
        assertArrayEquals("refused: nothing moved", root, checkNotNull(a.session).root)

        assertEquals("reset", text(a.open(second, NOW + 71_000, resetRequested = true)))
        assertArrayEquals(checkNotNull(b.session).root, checkNotNull(a.session).root)
    }

    /** Unflagged, the same init inside the hour-long incidental floor keeps its verdict: only a reset is held. */
    @Test
    fun anUnflaggedInitTheFloorRefusesKeepsItsVerdict() {
        val (a, b) = adoptedOneReset()
        val outcome = a.open(b.reset(NOW + 13_000), NOW + 13_000, allowReplacement = false)
        assertTrue("was $outcome", outcome === OpenOutcome.Failed.DUPLICATE)
    }

    /** A re-serve of the reset we adopted is anchored by its ephemeral, inside the floor too: still a duplicate. */
    @Test
    fun anAdoptedResetsReServeInsideTheFloorStaysDuplicate() {
        val (a, b) = pair()
        a.initiate(NOW)
        b.open(a.seal("hello", NOW), NOW)
        a.open(b.seal("hi", NOW), NOW)
        val reset = b.reset(NOW + 10_000)
        a.open(reset, NOW + 10_000, resetRequested = true)

        val again = a.open(reset, NOW + 20_000, resetRequested = true, allowReplacement = false)
        assertTrue("was $again", again === OpenOutcome.Failed.DUPLICATE)
    }

    /**
     * The design pass's blocking case. Bob adopts Alice's reset, then re-roots inside the minute and Alice answers: Bob's
     * own initiation dropped the anchor on Alice's reset and keeps its root as `prevRoot`. A re-entry of that reset (her
     * clock ahead, so its `at` is past Bob's re-root), inside the floor, is an era Bob held and left — never floored, or
     * its re-open would adopt a root Alice has left.
     */
    @Test
    fun aReEntryOfAResetWeAdoptedAndLeftIsNeverFloored() {
        val (bob, alice) = pair()
        bob.initiate(NOW)
        alice.open(bob.seal("hello", NOW), NOW)
        bob.open(alice.seal("hi", NOW), NOW)
        val aliceReset = alice.reset(NOW + 50_000) // her clock 40 s ahead of his
        assertEquals("reset", text(bob.open(aliceReset, NOW + 10_000, resetRequested = true)))

        assertEquals("reset", text(alice.open(bob.reset(NOW + 20_000), NOW + 20_000, resetRequested = true)))
        assertEquals("answered", text(bob.open(alice.seal("answered", NOW + 21_000), NOW + 21_000)))
        val bobs = checkNotNull(bob.session)
        assertTrue(bobs.confirmed && bobs.weAreInitiator)
        assertNull("bob's re-root dropped the anchor", bobs.peerInitEphPub)
        assertTrue("precondition: her at is past his era", NOW + 50_000 > bobs.establishedAt)

        // The first re-entry reads under the kept prevRoot (as before this change); the next meets the row that stored and
        // nothing opens — the case the derivation check is for: the init derives that prevRoot, so a duplicate it stays.
        val reEntry = bob.open(aliceReset, NOW + 30_000, resetRequested = true, allowReplacement = false)
        assertTrue("never floored, was $reEntry", reEntry !== OpenOutcome.Failed.RESET_FLOORED)
        assertEquals("read under the kept prevRoot", "reset", text(reEntry))
        val again = bob.open(aliceReset, NOW + 31_000, resetRequested = true, allowReplacement = false)
        assertTrue("was $again", again === OpenOutcome.Failed.DUPLICATE)
        assertArrayEquals("still on his own root", bobs.root, checkNotNull(bob.session).root)
        assertArrayEquals(checkNotNull(alice.session).root, checkNotNull(bob.session).root)
    }

    // --- #174: every pe = 0 epoch is based on the prekey its init names ---

    /**
     * Alice's session stays unconfirmed past a day while Bob rotates his prekey and his profile reaches her. Her
     * next epoch used to be based on the prekey she was handed then (`peerSpkPub`, Bob's newest), under an init that
     * still names the old one — the key Bob resolves by `init.pkid` — so it failed as AEAD_FAIL for good.
     */
    @Test
    fun anUnconfirmedSessionsNextEpochAfterAPrekeyRotationIsBasedOnTheInitsPrekey() {
        val (alice, bob) = pair()
        alice.initiate(NOW)
        val first = alice.seal("first", NOW)
        val pinned = checkNotNull(alice.session)
        assertArrayEquals("initiate records the init's prekey as the base", bob.spk.pub, pinned.peerBasePub)
        assertEquals(0, pinned.peerBaseEpoch)

        bob.rotatePrekey()
        val later = alice.seal("a day later", NOW + RatchetEngine.MAX_EPOCH_AGE_MS + 1)
        assertEquals("a new epoch", 2, later.header.se)
        assertEquals(0, later.header.pe)
        assertEquals(1, checkNotNull(later.header.init).pkid)

        assertEquals("a day later", text(bob.open(later, NOW + RatchetEngine.MAX_EPOCH_AGE_MS + 2)))
        assertEquals("first", text(bob.open(first, NOW + RatchetEngine.MAX_EPOCH_AGE_MS + 3)))
    }

    /** The same, across the 200-frame boundary instead of the day. */
    @Test
    fun anUnconfirmedSessionsEpochPastTheMessageCapAfterAPrekeyRotationStillOpens() {
        val (alice, bob) = pair()
        alice.initiate(NOW)
        repeat(RatchetEngine.MAX_EPOCH_MESSAGES) { alice.seal("m$it", NOW) }
        bob.rotatePrekey()
        val next = alice.seal("the 201st", NOW + 1)
        assertEquals(2, next.header.se)
        assertEquals("the 201st", text(bob.open(next, NOW + 2)))
    }

    /** Once Bob answers, his epoch replaces the prekey as Alice's base, as before #174. */
    @Test
    fun thePeersFirstEpochReplacesTheInitsPrekeyAsTheBase() {
        val (alice, bob) = pair()
        alice.initiate(NOW)
        bob.open(alice.seal("hello", NOW), NOW)
        val reply = bob.seal("hi", NOW + 1)
        alice.open(reply, NOW + 1)
        val session = checkNotNull(alice.session)
        assertTrue(session.confirmed)
        assertEquals(reply.header.se, session.peerBaseEpoch)
        assertArrayEquals(reply.header.ek, session.peerBasePub)
    }

    /**
     * A row an older build initiated holds no base. [RatchetEngine.prekeyBase] hands its seal the peer's prekey
     * only while it is the one the init names; after a rotation the epoch boundary returns null (the caller's v1
     * fallback) instead of a frame the peer cannot open, and the current epoch keeps sealing.
     */
    @Test
    fun aLegacyUnconfirmedRowSealsInEpochAndRefusesTheBoundaryAfterARotation() {
        val (alice, bob) = pair()
        alice.initiate(NOW)
        alice.session = checkNotNull(alice.session).copy(peerBasePub = null)
        val oldPrekey = RatchetEngine.PeerPrekey(id = bob.spkId, pub = bob.spk.pub)
        assertArrayEquals(oldPrekey.pub, engine.prekeyBase(checkNotNull(alice.session), oldPrekey))

        bob.rotatePrekey()
        val newPrekey = RatchetEngine.PeerPrekey(id = bob.spkId, pub = bob.spk.pub)
        val session = checkNotNull(alice.session)
        assertNull(engine.prekeyBase(session, newPrekey))
        assertNull(engine.prekeyBase(session, null))

        val inEpoch = checkNotNull(engine.seal(session, "in epoch".toByteArray(), AAD, engine.prekeyBase(session, newPrekey), NOW + 1))
        alice.session = inEpoch.session
        assertEquals("in epoch", text(bob.open(Frame(inEpoch.header, inEpoch.nonce, inEpoch.ct), NOW + 2)))

        val past = NOW + RatchetEngine.MAX_EPOCH_AGE_MS + 1
        assertNull(engine.seal(checkNotNull(alice.session), "x".toByteArray(), AAD, engine.prekeyBase(inEpoch.session, newPrekey), past))
    }

    /** A confirmed session or a responder never reads the prekey: its base is the peer's epoch. */
    @Test
    fun prekeyBaseIgnoresTheInitPkidOnceTheSessionIsConfirmed() {
        val (alice, bob) = pair()
        alice.initiate(NOW)
        bob.open(alice.seal("hello", NOW), NOW)
        val other = RatchetEngine.PeerPrekey(id = 9, pub = RatchetCrypto.generateKeyPair().pub)
        assertArrayEquals(other.pub, engine.prekeyBase(checkNotNull(bob.session), other))
    }

    // --- #172: a key X25519 refuses throws out of the engine; RatchetSessions maps it to "cannot seal" ---

    @Test
    fun aLowOrderPrekeyThrowsOutOfInitiateAndAFreshEpoch() {
        val (alice, bob) = pair()
        val lowOrder = RatchetEngine.PeerPrekey(id = 1, pub = ByteArray(RatchetCrypto.KEY_BYTES))
        assertThrows(java.security.GeneralSecurityException::class.java) {
            engine.initiate(bob.nodeId, alice.ik.priv, bob.ik.pub, lowOrder, NOW)
        }
        alice.initiate(NOW)
        val legacy = checkNotNull(alice.session).copy(peerBasePub = null)
        assertThrows(java.security.GeneralSecurityException::class.java) {
            engine.seal(legacy, "x".toByteArray(), AAD, lowOrder.pub, NOW, forceNewEpoch = true)
        }
    }

    // --- wipe and replacement ---

    @Test
    fun aWipedPeersReInitReplacesTheSessionAndPurgesStaleRecvState() {
        val (a, b) = pair()
        a.initiate(NOW)
        b.open(a.seal("before the wipe", NOW), NOW)
        a.open(b.seal("reply", NOW), NOW)

        a.wipe()
        a.initiate(NOW + 10_000)
        val reborn = a.seal("after the wipe", NOW + 10_000)

        assertEquals("after the wipe", text(b.open(reborn, NOW + 10_000)))
        assertTrue(checkNotNull(b.lastDelta).purgePeerRecvState)
        assertEquals(NOW + 10_000, checkNotNull(b.session).establishedAt)
        assertEquals(setOf(1), b.recvNumbers)

        // B's next send reaches the reborn A; B's own epoch numbering never reset.
        val toReborn = b.seal("welcome back", NOW + 10_000)
        assertTrue(toReborn.header.se >= 2)
        assertEquals("welcome back", text(a.open(toReborn, NOW + 10_000)))
    }

    @Test
    fun oldEraFramesDrainViaPrevRootWhenEpochNumbersDoNotCollide() {
        val (a, b) = pair()
        a.initiate(NOW)
        b.open(a.seal("establish", NOW), NOW)
        a.seal("burn epoch 1", NOW)
        val oldEra = a.seal("old era, epoch 2", NOW, force = true)

        a.wipe()
        a.initiate(NOW + 10_000)
        b.open(a.seal("new era", NOW + 10_000), NOW + 10_000)

        // The pre-wipe frame's epoch (2) does not collide with the new era's (1): the purged recv state
        // forces a fresh derivation, which fails under the new root and succeeds under the draining one.
        assertEquals("old era, epoch 2", text(b.open(oldEra, NOW + 10_000)))
    }

    @Test
    fun oldEraFramesWhoseEpochNumberCollidesFailBenignly() {
        val (a, b) = pair()
        a.initiate(NOW)
        val old0 = a.seal("old era, epoch 1, n=0", NOW)
        val old1 = a.seal("old era, epoch 1, n=1", NOW)

        a.wipe()
        a.initiate(NOW + 10_000)
        b.open(a.seal("new era, epoch 1", NOW + 10_000), NOW + 10_000)

        // B never held the old era's root (none of its frames reached B before the wipe), so nothing can open
        // them: an index below the new chain's cursor reads as a duplicate, one at/above it fails the AEAD.
        // Benign by design — anything delivered pre-wipe is skipped by the exists-gate upstream, and
        // the reset path re-seals undelivered traffic; this asserts the failure is contained, not silent.
        // (Had B held it as prevRoot, both would drain — see the #135 cases below.)
        assertTrue(b.open(old0, NOW + 10_000) === OpenOutcome.Failed.DUPLICATE)
        assertTrue(b.open(old1, NOW + 10_000) === OpenOutcome.Failed.AEAD_FAIL)
    }

    // --- #135: receive state is keyed by the root that derived it (ADR 2026-10.gcrd) ---

    private class AfterReset(
        val a: Side,
        val b: Side,
        /** A's old-era epoch 2, n=0 — read by B before the reset. */
        val turnaround: Frame,
        /** A's old-era epoch 2, n=1 — still on its way when the reset lands. */
        val late: Frame,
    )

    /** B read A's epochs 1 and 2 under the first root, then adopted A's reset: numbering restarted, the root kept as prevRoot. */
    private fun resetAfterEpochTwo(): AfterReset {
        val (a, b) = pair()
        a.initiate(NOW)
        b.open(a.seal("hello", NOW), NOW)
        a.open(b.seal("hi", NOW), NOW)
        val turnaround = a.seal("turnaround", NOW)
        val late = a.seal("late", NOW)
        assertEquals(2, turnaround.header.se)
        assertEquals(2, late.header.se)
        assertEquals("turnaround", text(b.open(turnaround, NOW)))
        assertEquals("reset", text(b.open(a.reset(NOW + 1_000), NOW + 2_000, resetRequested = true)))
        assertNotNull(checkNotNull(b.session).prevRoot)
        return AfterReset(a, b, turnaround, late)
    }

    /** B answers the reset, which moves A's restarted numbering to epoch 2; returns that epoch's n=0, unopened. */
    private fun newEraEpochTwo(
        r: AfterReset,
        now: Long,
    ): Frame {
        assertEquals("answer", text(r.a.open(r.b.seal("answer", now), now)))
        return r.a.seal("new era, epoch 2", now).also { assertEquals(2, it.header.se) }
    }

    private fun tagsAt(
        side: Side,
        se: Int,
    ): Set<Long> =
        side.recvEpochs.keys
            .filter { it.first == se }
            .mapTo(mutableSetOf()) { it.second }

    /**
     * The P3→P7 shape: custody re-served A's old epoch-2 frame after the reset's purge (a receipt has no message row
     * to stop it), so it opened again under prevRoot and stored a chain at number 2 with its index consumed. A's new
     * epoch 2 must open fresh under the active root — not read DUPLICATE off the old era's cursor.
     */
    @Test
    fun aChainReDrainedUnderPrevRootNeverDecidesTheNewEraFrameOfItsNumber() {
        val r = resetAfterEpochTwo()
        val b = r.b
        assertEquals("turnaround", text(b.open(r.turnaround, NOW + 3_000)))
        val newTwo = newEraEpochTwo(r, NOW + 4_000)

        assertEquals("new era, epoch 2", text(b.open(newTwo, NOW + 4_000)))
        val session = checkNotNull(b.session)
        assertEquals("a fresh epoch under the active root moved the era", 2, session.peerBaseEpoch)
        assertEquals(
            "both eras keep a chain for number 2, one per root",
            setOf(RatchetCrypto.rootTag(session.root), RatchetCrypto.rootTag(checkNotNull(session.prevRoot))),
            tagsAt(b, 2),
        )
        // A genuine re-serve of either is still a duplicate — never an AEAD_FAIL from the other root's attempt.
        assertTrue(b.open(newTwo, NOW + 5_000) === OpenOutcome.Failed.DUPLICATE)
        assertTrue(b.open(r.turnaround, NOW + 5_000) === OpenOutcome.Failed.DUPLICATE)
    }

    /**
     * The P9→P8 shape: the drained old-era frame sat mid-epoch, so opening it banked a skipped key for the index below
     * under the old root. A's new epoch 2 n=0 must not meet that key; the old era's own n=0 still opens on it.
     */
    @Test
    fun aSkippedKeyBankedUnderPrevRootNeverDecidesTheNewEraFrameAtItsIndex() {
        val r = resetAfterEpochTwo()
        val b = r.b
        assertEquals("late", text(b.open(r.late, NOW + 3_000))) // banks (2, 0) under the old root
        val newTwo = newEraEpochTwo(r, NOW + 4_000)

        assertEquals("new era, epoch 2", text(b.open(newTwo, NOW + 4_000)))
        assertEquals("the old era's key is untouched", "turnaround", text(b.open(r.turnaround, NOW + 5_000)))
    }

    /**
     * The other order: the new era owns number 2 before the old era's late frame lands. That frame drains under prevRoot
     * on a fresh derivation — it no longer fails against the new chain — and moves nothing.
     */
    @Test
    fun anOldEraFrameOnANumberTheNewEraOwnsStillDrainsUnderPrevRoot() {
        val r = resetAfterEpochTwo()
        val b = r.b
        assertEquals("new era, epoch 2", text(b.open(newEraEpochTwo(r, NOW + 3_000), NOW + 3_000)))
        val before = checkNotNull(b.session)

        assertEquals("late", text(b.open(r.late, NOW + 4_000)))
        val after = checkNotNull(b.session)
        assertArrayEquals(before.root, after.root)
        assertEquals(before.peerBaseEpoch, after.peerBaseEpoch)
        assertArrayEquals(before.peerBasePub, after.peerBasePub)
    }

    // --- a reset's kept prevRoot (#87) ---

    /** A session that has turned around once, so A is at epoch 2 and B's next frames seal against it. */
    private fun turnedAround(): Pair<Side, Side> {
        val (a, b) = pair()
        a.initiate(NOW)
        b.open(a.seal("hello", NOW), NOW)
        a.open(b.seal("hi", NOW), NOW)
        b.open(a.seal("turnaround", NOW), NOW)
        return a to b
    }

    /**
     * A resets while B is away; B's DMs sealed meanwhile name A's dead-era epoch 2, whose key survives the
     * reset (it minted only epoch 1). Whatever order they land in, they open under the kept `prevRoot` and
     * must leave the replacement exactly as the reset minted it: unconfirmed, init still riding, no DH base
     * from the dead era, no pe ack. Then the thing #87 lost: A's answer carries the init, so B — who has not
     * seen the reset — adopts from the answer itself, and the reset landing last opens on the skipped key.
     */
    private fun oldEraFramesNeverConfirmAResetsReplacement(order: (Frame, Frame) -> List<Frame>) {
        val (a, b) = turnedAround()
        val apart0 = b.seal("apart 0", NOW + 1_000)
        val apart1 = b.seal("apart 1", NOW + 1_000)
        assertEquals(2, apart0.header.pe)
        val reset = a.reset(NOW + 2_000)

        order(apart0, apart1).forEach { assertTrue(a.open(it, NOW + 3_000) is OpenOutcome.Opened) }
        val replacement = checkNotNull(a.session)
        assertFalse("a frame from the dead era confirmed the replacement", replacement.confirmed)
        assertNotNull(replacement.initEphPub)
        assertEquals(0, replacement.peerBaseEpoch)
        assertEquals(0, replacement.highestPeAcked)

        val tick = a.seal("tick", NOW + 3_000)
        assertNotNull("the answer still carries the reset's init", tick.header.init)
        assertEquals(0, tick.header.pe)
        assertEquals("tick", text(b.open(tick, NOW + 4_000)))
        assertArrayEquals("B adopted the reset from the answer", replacement.root, checkNotNull(b.session).root)
        assertEquals("reset", text(b.open(reset, NOW + 4_000, resetRequested = true)))

        assertEquals("after", text(a.open(b.seal("after", NOW + 5_000), NOW + 5_000)))
        assertTrue(checkNotNull(a.session).confirmed)
    }

    @Test
    fun aFrameOpenedUnderAResetsKeptPrevRootNeverConfirmsTheReplacement() =
        oldEraFramesNeverConfirmAResetsReplacement { apart0, _ -> listOf(apart0) }

    /** The second frame of the dead-era epoch opens on the live chain its first one derived. */
    @Test
    fun aSecondOldEraFrameOnTheLiveChainNeverConfirmsTheReplacement() =
        oldEraFramesNeverConfirmAResetsReplacement { apart0, apart1 -> listOf(apart0, apart1) }

    /** Out of order: the first frame of the dead-era epoch opens on the skipped key the second one stored. */
    @Test
    fun anOldEraPairOpenedOutOfOrderNeverConfirmsTheReplacementThroughItsSkippedKey() =
        oldEraFramesNeverConfirmAResetsReplacement { apart0, apart1 -> listOf(apart1, apart0) }

    /**
     * The adopter's side of the same rule. B adopts A's reset, and A's dead-era epoch 2 — numbered above the
     * reset's epoch 1 — then drains in under `prevRoot`, twice. Taking it as B's DH base would seal B's next
     * frames against a key A replaces the moment its own numbering reaches 2, and the damped adoption would
     * refuse A's fresh epochs until they climbed past it.
     */
    @Test
    fun oldEraFramesDrainingAfterAnAdoptedResetNeverBecomeTheAdoptersDhBase() {
        val (a, b) = turnedAround()
        val old0 = a.seal("old 0", NOW + 1_000)
        val old1 = a.seal("old 1", NOW + 1_000)
        assertEquals(2, old0.header.se)
        val reset = a.reset(NOW + 2_000)

        assertEquals("reset", text(b.open(reset, NOW + 3_000, resetRequested = true)))
        assertEquals("old 0", text(b.open(old0, NOW + 3_000)))
        assertEquals("old 1", text(b.open(old1, NOW + 3_000)))
        assertEquals(1, checkNotNull(b.session).peerBaseEpoch)
        assertArrayEquals(reset.header.ek, checkNotNull(b.session).peerBasePub)

        val back = b.seal("back", NOW + 4_000)
        assertEquals(1, back.header.pe)
        assertEquals("back", text(a.open(back, NOW + 4_000)))
        assertTrue(checkNotNull(a.session).confirmed)
    }

    /**
     * The era is the root a frame opened under, not the number of our epoch it names. A peer that took one of
     * our dead-era epochs as its base before this rule (an older build) seals under the *new* root against it;
     * that frame is this era's, and must still confirm the replacement.
     */
    @Test
    fun anInEraFrameNamingOurRetiredEpochStillConfirmsTheReplacement() {
        val (a, b) = turnedAround()
        val deadEraEpoch2 = checkNotNull(a.localEpochs[2])
        val reset = a.reset(NOW + 1_000)
        b.open(reset, NOW + 2_000, resetRequested = true)
        b.session = checkNotNull(b.session).copy(peerBasePub = deadEraEpoch2.pub, peerBaseEpoch = 2)

        val inEra = b.seal("in era", NOW + 2_000)
        assertEquals(2, inEra.header.pe)
        assertEquals("in era", text(a.open(inEra, NOW + 2_000)))
        assertTrue(checkNotNull(a.session).confirmed)
    }

    /**
     * Why the read-only remnant candidate records nothing. A resetter's follow-up frames carry its reset's
     * init *without* the flag, and to a session we initiated whose peer never sent an init that is exactly a
     * race remnant: it is read, not adopted. Had the read anchored the init's ephemeral, the flagged reset
     * itself — same ephemeral — would then open as an idempotent re-serve on the skipped key its follow-up
     * stored, never adopted, and we would sit on the root the peer had left.
     */
    @Test
    fun anUnanchoredWinnerThatReadAResetsUnflaggedFollowerStillAdoptsTheReset() {
        val (a, b) = pair()
        a.initiate(NOW)
        b.open(a.seal("hello", NOW), NOW)
        b.seal("never delivered", NOW) // B's epoch 1, so A holds no receive row for the number B's reset reuses
        a.open(b.seal("hi", NOW, force = true), NOW)
        assertTrue(checkNotNull(a.session).confirmed)
        assertNull(checkNotNull(a.session).peerInitEphPub)
        assertTrue("precondition: B is the higher nodeId the guard reads", b.nodeId > a.nodeId)

        val reset = b.reset(NOW + 10_000)
        val followUp = b.seal("follow-up", NOW + 10_000)
        assertEquals("follow-up", text(a.open(followUp, NOW + 11_000)))
        assertTrue("read, not adopted", checkNotNull(a.session).weAreInitiator)
        assertNull(checkNotNull(a.session).peerInitEphPub)

        assertEquals("reset", text(a.open(reset, NOW + 11_000, resetRequested = true)))
        assertFalse("the reset was adopted", checkNotNull(a.session).weAreInitiator)
        assertArrayEquals(checkNotNull(b.session).root, checkNotNull(a.session).root)
    }

    /**
     * The invariant `InboundPipeline.isLiveEvidence` rests on (ADR 026): [RatchetEngine.SessionState.establishedAt]
     * is OUR clock exactly when [RatchetEngine.SessionState.weAreInitiator], and the peer's `InitPayload.at` otherwise.
     * That is what lets the era gate know whether it may compare `establishedAt` against a frame's
     * `sentAt` at all — the responder half is single-clock and exact, the initiator half is not.
     *
     * Every site that writes `establishedAt` is walked here, each with a *local* clock deliberately
     * different from the init's `at`, so a site that starts sourcing the wrong one cannot pass by
     * coincidence. A fifth site added without honouring this silently disarms the heuristic under skew.
     */
    @Test
    fun establishedAtIsOurOwnClockExactlyWhenWeAreTheInitiator() {
        val aEra = NOW
        val bLocal = NOW + 3 * 60 * 60_000L

        // 1. initiate: our own clock.
        val (a, b) = pair()
        a.initiate(aEra)
        assertTrue(checkNotNull(a.session).weAreInitiator)
        assertEquals(aEra, checkNotNull(a.session).establishedAt)

        // 2. responder establish: the peer's init.at, NOT the clock we opened it on.
        b.open(a.seal("hello", aEra), bLocal)
        assertFalse(checkNotNull(b.session).weAreInitiator)
        assertEquals(aEra, checkNotNull(b.session).establishedAt)

        // 3. replacement adopt (the peer lost its state and re-initiated): the new init.at.
        val reEra = aEra + 10_000
        a.wipe()
        a.initiate(reEra)
        b.open(a.seal("after my wipe", reEra), bLocal + 10_000)
        assertFalse(checkNotNull(b.session).weAreInitiator)
        assertEquals(reEra, checkNotNull(b.session).establishedAt)

        // 4. both-initiate race. The smaller nodeId wins, so `c` keeps its own stamp and `d` — adopting
        //    the winner's root — takes the winner's clock with it.
        val (c, d) = pair()
        val cEra = NOW + 60_000
        val dEra = NOW + 90_000
        c.initiate(cEra)
        d.initiate(dEra)
        val fromC = c.seal("mine", cEra)
        val fromD = d.seal("no, mine", dEra)
        d.open(fromC, dEra)
        c.open(fromD, cEra)

        assertFalse("the larger nodeId adopts the winner's root", checkNotNull(d.session).weAreInitiator)
        assertEquals(cEra, checkNotNull(d.session).establishedAt)
        assertTrue("the race winner stays the initiator", checkNotNull(c.session).weAreInitiator)
        assertEquals(cEra, checkNotNull(c.session).establishedAt)

        // 5. reclaim (rule C, ADR 2026-10.4mjm): back to initiator, so back to our own clock — the abandoned init's own
        //    stamp, kept on the row through the lost race, never the answer's.
        val trace = restoredLoser()
        trace.loseTheRace()
        assertFalse(checkNotNull(trace.b.session).weAreInitiator)
        assertEquals("the winner's clock while we lost", NOW, checkNotNull(trace.b.session).establishedAt)
        trace.b.open(trace.answer, RESET_AT + HOUR, sentAt = RESET_AT + 1)
        assertTrue(checkNotNull(trace.b.session).weAreInitiator)
        assertEquals(RESET_AT, checkNotNull(trace.b.session).establishedAt)
    }

    // --- rule C: a race loser reclaims its own init once the peer answers it (ADR 2026-10.4mjm, #189) ---

    /**
     * #189's trace, engine half. [a] (the smaller id) initiates `I_A` and [b] answers it; [b] then restores and resets
     * (`R_B`, from no session, so no `prevRoot`); [a], confirmed, takes `R_B` as a flagged replacement and answers under
     * it. Returned: the pair, `I_A`'s first frame (still in custody, stamped [NOW]), and [a]'s answer under `R_B`
     * (stamped [answerAt]), neither yet at [b].
     */
    private class RestoredLoser(
        val a: Side,
        val b: Side,
        val initFrame: Frame,
        val answer: Frame,
    )

    private fun restoredLoser(answerAt: Long = RESET_AT + 1): RestoredLoser {
        val (a, b) = pair()
        a.initiate(NOW)
        val initFrame = a.seal("hello, I_A", NOW)
        assertEquals("hello, I_A", text(b.open(initFrame, NOW)))
        assertEquals("hi", text(a.open(b.seal("hi", NOW + 1), NOW + 1)))
        assertTrue(checkNotNull(a.session).confirmed)

        b.wipe()
        val reset = b.reset(RESET_AT)
        assertEquals("reset", text(a.open(reset, RESET_AT, resetRequested = true)))
        assertFalse("a took R_B as a replacement", checkNotNull(a.session).weAreInitiator)
        return RestoredLoser(a, b, initFrame, a.seal("answer under R_B", answerAt))
    }

    /** [b] meets the re-served `I_A` before the answer: it loses the race against its own unanswered reset (#189). */
    private fun RestoredLoser.loseTheRace(): ByteArray {
        val resetRoot = checkNotNull(b.session).root
        assertEquals("hello, I_A", text(b.open(initFrame, RESET_AT + 2, sentAt = NOW)))
        val lost = checkNotNull(b.session)
        assertArrayEquals("b adopted I_A", checkNotNull(a.session).prevRoot, lost.root)
        assertArrayEquals("its own reset drains", resetRoot, lost.prevRoot)
        assertEquals("flagged with its own init's stamp, our clock", RESET_AT, lost.prevRootOwnInitAt)
        return resetRoot
    }

    @Test
    fun aRaceLoserReclaimsItsOwnInitWhenThePeerSealsUnderItAsResponder() {
        val trace = restoredLoser()
        val (a, b) = trace.a to trace.b
        val resetRoot = trace.loseTheRace()
        val adopted = checkNotNull(b.session).root

        assertEquals("answer under R_B", text(b.open(trace.answer, RESET_AT + 3, sentAt = RESET_AT + 1)))
        val delta = checkNotNull(b.lastDelta)
        assertTrue("the open reports the reclaim", delta.reclaimed)
        assertTrue(delta.purgePeerRecvState)
        assertEquals("the reclaimed root's receive state survives the purge", RatchetCrypto.rootTag(resetRoot), delta.keepRecvTag)
        val back = checkNotNull(b.session)
        assertArrayEquals("one root: the reset's", checkNotNull(a.session).root, back.root)
        assertTrue(back.confirmed)
        assertTrue(back.weAreInitiator)
        assertNull("no init rides any more", back.initEphPub)
        assertEquals("establishedAt is our own clock again (ADR 026)", RESET_AT, back.establishedAt)
        assertArrayEquals("the root we had adopted drains", adopted, back.prevRoot)
        assertFalse(back.prevRootWeAreInitiator)
        assertNull("the flag goes with the swap", back.prevRootOwnInitAt)
        assertArrayEquals("I_A stays the anchor", checkNotNull(trace.initFrame.header.init).eph, back.peerInitEphPub)
        assertEquals("the answer's epoch is the DH base", trace.answer.header.se, back.peerBaseEpoch)
        assertEquals(trace.answer.header.pe, back.highestPeAcked)
        assertEquals(
            "only the reclaimed era's rows remain",
            setOf(RatchetCrypto.rootTag(resetRoot)),
            b.recvEpochs.keys
                .map { it.second }
                .toSet(),
        )

        // Both ways under the one root, and I_A re-served once more changes nothing.
        assertEquals("b again", text(a.open(b.seal("b again", RESET_AT + 4), RESET_AT + 4, sentAt = RESET_AT + 4)))
        assertEquals("a again", text(b.open(a.seal("a again", RESET_AT + 5), RESET_AT + 5, sentAt = RESET_AT + 5)))
        b.open(trace.initFrame, RESET_AT + 6, sentAt = NOW)
        assertArrayEquals(checkNotNull(a.session).root, checkNotNull(b.session).root)
        assertTrue(checkNotNull(b.session).weAreInitiator)
    }

    /**
     * Design-pass finding 1: the peer sealed under our init and *then* left it — its restore's reset `R_A` — and we lost
     * the race to `R_A`. Its old frame, re-served, is responder-sealed under our init, but no later than the init we lost
     * to: reclaiming would put us on a root the peer no longer holds. Sealed at `R_A.at` plus the skew bound exactly, it
     * still is not fresh.
     */
    @Test
    fun noReclaimOnAFrameSealedNoLaterThanTheInitWeLostTo() {
        val (a, b) = pair()
        b.initiate(NOW)
        assertEquals("first", text(a.open(b.seal("first", NOW), NOW)))
        val before = a.seal("reply under I_B", NOW + 1) // never reaches b before the restore

        a.wipe()
        val reset = a.reset(RESET_AT)
        assertEquals("reset", text(b.open(reset, RESET_AT + 1, resetRequested = true, sentAt = RESET_AT)))
        assertEquals("b lost the race on its own first contact", NOW, checkNotNull(b.session).prevRootOwnInitAt)

        assertEquals(
            "reply under I_B",
            text(b.open(before, RESET_AT + 2, sentAt = RESET_AT + Protocol.MAX_FUTURE_SKEW_MS)),
        )
        assertFalse(checkNotNull(b.lastDelta).reclaimed)
        assertArrayEquals("still on the reset's root, which a holds", checkNotNull(a.session).root, checkNotNull(b.session).root)
        assertFalse(checkNotNull(b.session).weAreInitiator)
    }

    /** The trace's answer, but stamped inside the margin past `I_A.at`: read under `R_B`, read-only, no reclaim. */
    @Test
    fun noReclaimOnAResponderFrameStampedInsideTheMarginPastTheWinningInit() {
        val trace = restoredLoser(answerAt = NOW + Protocol.MAX_FUTURE_SKEW_MS)
        trace.loseTheRace()
        val adopted = checkNotNull(trace.b.session).root
        assertEquals("answer under R_B", text(trace.b.open(trace.answer, RESET_AT + 3, sentAt = NOW + Protocol.MAX_FUTURE_SKEW_MS)))
        assertFalse(checkNotNull(trace.b.lastDelta).reclaimed)
        assertArrayEquals(adopted, checkNotNull(trace.b.session).root)
        assertEquals(RESET_AT, checkNotNull(trace.b.session).prevRootOwnInitAt)
    }

    /**
     * Design-pass finding 4: a first frame of the answer's epoch stamped inside the margin is read read-only and stores a
     * chain under `R_B`; the next frames of that epoch take the live chain or a banked skipped key, never a fresh
     * derivation — and each still reclaims. The reclaim's purge spares `R_B`'s receive state: the live chain reads the
     * next frame, and a second key banked beside the one the reclaim spent still opens its frame.
     */
    @Test
    fun aFreshFrameReclaimsOnTheLiveChainAndOnASkippedKeyToo() {
        for (rung in listOf("live", "skipped")) {
            val trace = restoredLoser()
            val (a, b) = trace.a to trace.b
            val resetRoot = trace.loseTheRace()
            val resetTag = RatchetCrypto.rootTag(resetRoot)
            val second = a.seal("second", RESET_AT + 2)
            val third = a.seal("third", RESET_AT + 3)
            val fourth = a.seal("fourth", RESET_AT + 4)
            val se = trace.answer.header.se
            // The epoch's first frame, read early (its stamp moved inside the margin): read-only, a chain under R_B.
            b.open(trace.answer, RESET_AT + 5, sentAt = NOW)
            assertFalse(checkNotNull(b.lastDelta).reclaimed)
            if (rung == "skipped") {
                b.open(fourth, RESET_AT + 6, sentAt = NOW) // banks the skipped keys for "second" and "third"
                assertEquals(setOf(Triple(se, 1, resetTag), Triple(se, 2, resetTag)), b.skipped.keys)
            }
            assertFalse(checkNotNull(b.session).weAreInitiator)

            assertEquals("second", text(b.open(second, RESET_AT + 7, sentAt = RESET_AT + 2)))
            assertTrue("$rung rung reclaims", checkNotNull(b.lastDelta).reclaimed)
            assertArrayEquals(rung, resetRoot, checkNotNull(b.session).root)
            assertTrue(rung, checkNotNull(b.session).weAreInitiator)
            assertEquals(rung, setOf(se to resetTag), b.recvEpochs.keys)
            if (rung == "skipped") assertEquals("the other banked key survives", setOf(Triple(se, 2, resetTag)), b.skipped.keys)
            assertEquals(rung, "third", text(b.open(third, RESET_AT + 8, sentAt = RESET_AT + 3)))
        }
    }

    /** A frame the winner seals as initiator under the root we adopted — its own — is the era we hold: no reclaim. */
    @Test
    fun noReclaimOnAFrameThePeerSealedAsInitiator() {
        val (a, b) = pair()
        a.initiate(NOW)
        b.initiate(NOW + 1)
        assertEquals("from a", text(b.open(a.seal("from a", NOW), NOW + 2, sentAt = NOW)))
        assertEquals("b lost", NOW + 1, checkNotNull(b.session).prevRootOwnInitAt)
        // a, still the winner's unconfirmed initiator, seals again much later: under its own root, init attached.
        assertEquals("later from a", text(b.open(a.seal("later from a", NOW + HOUR), NOW + HOUR, sentAt = NOW + HOUR)))
        assertFalse(checkNotNull(b.lastDelta).reclaimed)
        assertFalse(checkNotNull(b.session).weAreInitiator)
        assertEquals(NOW + 1, checkNotNull(b.session).prevRootOwnInitAt)
    }

    /**
     * A root that was confirmed and then replaced never qualifies: [b] initiated, [a] answered, then [a] wiped and reset.
     * A frame [a] sealed under [b]'s init before the wipe drains under `prevRoot` even stamped far past the reset.
     */
    @Test
    fun noReclaimUnderARootThatWasConfirmedAndThenReplaced() {
        val (a, b) = pair()
        b.initiate(NOW)
        a.open(b.seal("hello", NOW), NOW)
        val drain = a.seal("still in custody", NOW + 2)
        b.open(a.seal("hi", NOW + 1), NOW + 1)
        assertTrue(checkNotNull(b.session).confirmed)

        a.wipe()
        b.open(a.reset(RESET_AT), RESET_AT, resetRequested = true, sentAt = RESET_AT)
        val replaced = checkNotNull(b.session)
        assertTrue("b's init drains", replaced.prevRootWeAreInitiator)
        assertNull("but a replacement never flags it", replaced.prevRootOwnInitAt)

        assertEquals("still in custody", text(b.open(drain, RESET_AT + 1, sentAt = RESET_AT + HOUR)))
        assertFalse(checkNotNull(b.lastDelta).reclaimed)
        assertArrayEquals(checkNotNull(a.session).root, checkNotNull(b.session).root)
    }

    /**
     * Flagged resets crossed (both phones reset at once): the smaller id wins and keeps its root, so it never seals under
     * the loser's — nothing reclaims however late its frames are. And the loser's own reset cleared the flag of the race
     * it had lost before it.
     */
    @Test
    fun crossedResetsMakeNoReclaimBecauseTheWinnerNeverSealsUnderTheLosersRoot() {
        val (a, b) = pair()
        a.initiate(NOW)
        b.initiate(NOW)
        b.open(a.seal("from a", NOW), NOW, sentAt = NOW)
        assertEquals("the first race's loser", NOW, checkNotNull(b.session).prevRootOwnInitAt)
        a.open(b.seal("answer", NOW + 1), NOW + 1, sentAt = NOW + 1)
        assertTrue(checkNotNull(a.session).confirmed)

        val fromB = b.reset(RESET_AT)
        assertNull("our reset clears the flag", checkNotNull(b.session).prevRootOwnInitAt)
        val fromA = a.reset(RESET_AT)
        b.open(fromA, RESET_AT + 1, resetRequested = true, sentAt = RESET_AT)
        a.open(fromB, RESET_AT + 1, resetRequested = true, sentAt = RESET_AT)
        assertEquals("the second race's loser flags its reset", RESET_AT, checkNotNull(b.session).prevRootOwnInitAt)

        for (i in 1..3) {
            val at = RESET_AT + i * HOUR
            b.open(a.seal("a $i", at), at, sentAt = at)
            assertFalse(checkNotNull(b.lastDelta).reclaimed)
            a.open(b.seal("b $i", at), at, sentAt = at)
        }
        assertArrayEquals(checkNotNull(a.session).root, checkNotNull(b.session).root)
        assertFalse(checkNotNull(b.session).weAreInitiator)
    }

    /** Every other write of `prevRoot` clears the flag: a replacement, and the drain running out. */
    @Test
    fun aReplacementOrAnExpiredDrainClearsTheFlag() {
        val replaced = restoredLoser()
        replaced.loseTheRace()
        replaced.a.wipe()
        replaced.b.open(replaced.a.reset(RESET_AT + HOUR), RESET_AT + HOUR, resetRequested = true, sentAt = RESET_AT + HOUR)
        assertNull(checkNotNull(replaced.b.session).prevRootOwnInitAt)

        val (a, b) = pair()
        a.initiate(NOW)
        b.initiate(NOW)
        b.open(a.seal("from a", NOW), NOW, sentAt = NOW)
        assertEquals(NOW, checkNotNull(b.session).prevRootOwnInitAt)
        val late = NOW + RatchetEngine.PREV_ROOT_TTL_MS + HOUR
        assertEquals("ping", text(b.open(a.seal("ping", late), late, sentAt = late)))
        val after = checkNotNull(b.session)
        assertNull(after.prevRoot)
        assertNull(after.prevRootOwnInitAt)
    }

    private companion object {
        const val NOW = 1_700_000_000_000L
        const val HOUR = 60 * 60_000L

        /** When #189's restored side resets: ten minutes after the init it will lose to, past the skew bound. */
        const val RESET_AT = NOW + 10 * 60_000L
        val AAD = "id|sender|1700000000000|thread".toByteArray()
    }
}
