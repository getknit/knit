package app.getknit.knit.mesh.spool

import app.getknit.knit.data.message.DeliveryPlane
import app.getknit.knit.mesh.crypto.scope.ScopeCrypto
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When an attachment's bytes may wait for the radios instead of crossing a spool
 * (`docs/SPOOL_PROTOCOL.md` §9.5). The property every case here is really testing is the same one: the
 * gate defers only on positive evidence and re-opens by itself, so no uncertain case can strand an
 * image. The one softening is [AttachmentDeferPolicy.ACK_GRACE_MS], and it is bounded by the frame's
 * own age: for that long after a send there has been no time for an ack, so its absence is not yet
 * evidence of anything. Cases that are about the *plane* rule therefore age their frame past the grace
 * with [settled], or they would be testing the grace instead.
 */
class AttachmentDeferPolicyTest {
    private val alice = "aaaaaaaaaaaaaaaaaaaaaaaaaa"
    private val bob = "bbbbbbbbbbbbbbbbbbbbbbbbbb"
    private val groupId = "g-00112233445566778899aabb"
    private val aHash = "a".repeat(64)
    private val custodyTtlMs = 24 * 60 * 60_000L
    private val start = 1_700_000_000_000L

    private var clock = start
    private var reachable = setOf(bob)

    // The plane this attachment's message crossed between us and bob, or null when no row names it at
    // all — a carried frame or an avatar. The production lambda is `MessageDao.attachmentCarriedByRadio`,
    // which reads our own send's ack or their send's arrival; its SQL is covered in `MessageDaoTest`.
    private var ackPlane: DeliveryPlane? = DeliveryPlane.Nearby

    // Whether the message naming this attachment is one WE authored, which is the grace's subject and
    // nothing else: a photo bob sent us is not waiting on an ack of ours, so it never needs the grace.
    private var authored = true

    // The persisted proof that bob holds the bytes, by the plane it was learnt over — the `attachment_holders` table,
    // which outlives any one policy (a "restart" is a new policy over the same map). The production lambda is
    // `AttachmentHolderDao.heldInPair`, whose SQL is covered in `AttachmentHolderDaoTest`.
    private val proofs = mutableMapOf<Pair<String, String>, DeliveryPlane>()

    // The holder queries the policy asked for, as (hash, peer).
    private val asked = mutableListOf<Pair<String, String>>()

    // Whether bob is on a live link that answers the holder query — the proof round's condition.
    private var linked = false

    // The peers a contact's direct Internet link holds right now (ADR 2026-10.a5yj): the deferral's live sighting.
    private var directLinked = emptySet<String>()

    // Attachments the holder query cannot carry to bob now: a direct link that does not carry them (ADR 2026-10.a5yj).
    private val unreachable = mutableSetOf<String>()

    private fun policy() =
        AttachmentDeferPolicy(
            reachable = { reachable },
            directLinked = { directLinked },
            carriedByRadio = { _, _ -> ackPlane?.shortRange == true },
            authoredHere = { authored },
            heldByRecipient = { hash, peer -> proofs[hash to peer]?.provesHold == true },
            askHolder = { hash, peer -> asked += hash to peer },
            canAskHolder = { hash, _ -> linked && hash !in unreachable },
            custodyTtlMs = custodyTtlMs,
            clock = { clock },
        )

    private fun dmScope() =
        Scope(
            id = ByteArray(ScopeCrypto.SCOPE_ID_BYTES) { 1 },
            keys = ScopeCrypto.dmSealKeys(ByteArray(32) { 9 }, alice, bob),
            bounds = ScopeRegistry.DEFAULT_BOUNDS,
            peerId = bob,
        )

    private fun groupScope() =
        Scope(
            id = ByteArray(ScopeCrypto.SCOPE_ID_BYTES) { 2 },
            keys = ScopeCrypto.groupSealKeys(ByteArray(32) { 9 }, groupId, 1),
            bounds = ScopeRegistry.DEFAULT_BOUNDS,
            groupId = groupId,
            roster = setOf(alice, bob),
        )

    private fun ref(sentAt: Long = start) = ScopeAttachments.Ref(aHash = aHash, mime = "image/jpeg", sentAt = sentAt)

    /** A frame old enough that an ack would have come back by now, so only the ordinary rule applies. */
    private fun settled() = ref(sentAt = start - AttachmentDeferPolicy.ACK_GRACE_MS)

    @Test
    fun `an attachment a radio carried whose peer is on the presence plane waits for the radios`() =
        runTest {
            // Both halves satisfied: a short-range receipt proves a data path worked for this pair, and the
            // peer is still in sight. This is the only shape that defers.
            assertTrue(policy().defer(dmScope(), ref()))
        }

    @Test
    fun `a peer that has gone quiet is uploaded to`() =
        runTest {
            val subject = policy()
            assertTrue(subject.defer(dmScope(), ref()))

            // The sighting is what expires, not the ack: the same acked frame now pushes.
            reachable = emptySet()
            clock = start + AttachmentDeferPolicy.RADIO_WINDOW_MS + 1
            assertFalse(subject.defer(dmScope(), ref()))
        }

    @Test
    fun `a peer still inside the window keeps deferring through ordinary radio silence`() =
        runTest {
            val subject = policy()
            assertTrue(subject.defer(dmScope(), ref()))

            // A BLE scan floored to ~2 min or a dozing NAN peer is silence, not departure.
            reachable = emptySet()
            clock = start + AttachmentDeferPolicy.RADIO_WINDOW_MS - 1
            assertTrue(subject.defer(dmScope(), ref()))
        }

    @Test
    fun `an unacked attachment is uploaded even with the peer in sight`() =
        runTest {
            // Presence is the cue plane — a peer can be reachable with no data path at all, so an
            // un-ticked frame is exactly the case the Internet plane exists for.
            ackPlane = null
            assertFalse(policy().defer(dmScope(), settled()))
        }

    @Test
    fun `an attachment acked over the spool is uploaded even with the peer in sight`() =
        runTest {
            // The evidence has to name a plane that could have carried the *bytes*. A receipt that came
            // back across a spool says the peer read us from anywhere at all, and deferring on it would
            // hold the upload back on the strength of the very plane the upload feeds.
            ackPlane = DeliveryPlane.Internet
            assertFalse(policy().defer(dmScope(), settled()))
        }

    @Test
    fun `an attachment acked over LoRa is uploaded even with the peer in sight`() =
        runTest {
            // A board carries a frame and never a blob, which is the same reason the reachable set is
            // narrowed to the short-range radios upstream.
            ackPlane = DeliveryPlane.LoRa
            assertFalse(policy().defer(dmScope(), settled()))
        }

    @Test
    fun `an attachment acked by a build too old to record a plane is uploaded`() =
        runTest {
            // `DeliveryPlane.Unknown` is what a row written before the column reads back as, and an
            // unknown plane is not evidence — the uncertain case resolves to push like every other.
            ackPlane = DeliveryPlane.Unknown
            assertFalse(policy().defer(dmScope(), settled()))
        }

    @Test
    fun `a fresh process defers nothing`() =
        runTest {
            // No sighting has been recorded yet for a peer that is not reachable right now, so a restart
            // errs toward uploading rather than toward a silent hold.
            reachable = emptySet()
            assertFalse(policy().defer(dmScope(), ref()))
        }

    @Test
    fun `a group scope never defers`() =
        runTest {
            // The sealed group tick flips on the FIRST member's receipt, so it can never mean "everyone
            // holds it" — deferring on it would strand whoever was not reached.
            reachable = setOf(alice, bob)
            assertFalse(policy().defer(groupScope(), ref()))
        }

    @Test
    fun `deferring stops before the frame leaves custody`() =
        runTest {
            val subject = policy()
            // Inside the last-call window the frame is about to stop driving a push at all, so the bytes
            // go now or never.
            val expiring = start - custodyTtlMs + AttachmentDeferPolicy.LAST_CALL_MS
            assertFalse(subject.defer(dmScope(), ref(sentAt = expiring)))
            // One millisecond earlier in the frame's life and there is still time to wait.
            assertTrue(subject.defer(dmScope(), ref(sentAt = expiring + 1)))
        }

    @Test
    fun `an attachment whose ack has not had time to come back waits`() =
        runTest {
            // The round that asks this is the one the send itself woke, so the recipient's receipt is
            // still a round trip away. A missing ack here says nothing about the radios (issue #46).
            ackPlane = null
            assertTrue(policy().defer(dmScope(), ref()))
        }

    @Test
    fun `an attachment nobody acked in time is uploaded once the grace is over`() =
        runTest {
            ackPlane = null
            val subject = policy()
            assertTrue(subject.defer(dmScope(), ref()))

            // The grace expires on the frame's own age, with no new local activity and no second signal.
            clock = start + AttachmentDeferPolicy.ACK_GRACE_MS
            assertFalse(subject.defer(dmScope(), ref()))
        }

    @Test
    fun `a radio ack carries the deferral on past the grace`() =
        runTest {
            val subject = policy()
            assertTrue(subject.defer(dmScope(), ref()))

            // The hand-over the gate depends on: the receipt lands inside the grace and the ordinary rule
            // takes over, so there is no round in which the bytes are pushable.
            clock = start + AttachmentDeferPolicy.ACK_GRACE_MS
            assertTrue(subject.defer(dmScope(), ref()))
        }

    @Test
    fun `an attachment the peer sent us over a radio waits too`() =
        runTest {
            // The other reading of the same fact: these bytes already crossed a radio between this scope's
            // only two members, so a second copy at the relay buys nobody anything. No grace is involved —
            // the arriving row's plane is known the instant the row exists.
            authored = false
            assertTrue(policy().defer(dmScope(), settled()))
        }

    @Test
    fun `an attachment whose bytes came off a radio waits before its row exists`() =
        runTest {
            // Custody asks for the blob before the sealed content is opened and persisted, so a neighbour's
            // serve can land while the row is still being written. The arrival itself is the evidence the
            // row's plane stands in for, and it is on record before the bytes are — so the round that finds
            // them in hand defers rather than pushing what a radio just handed us.
            ackPlane = null
            authored = false
            val subject = policy()
            subject.noteRadioArrival(aHash)
            assertTrue(subject.defer(dmScope(), settled()))
        }

    @Test
    fun `an attachment whose frame took the relay but whose bytes came off a radio waits`() =
        runTest {
            // A live socket beats a BLE connect, so the frame can come off the spool first and the row says
            // Internet; the sender deferred, so the bytes still crossed the radio. That crossing decides.
            ackPlane = DeliveryPlane.Internet
            authored = false
            val subject = policy()
            subject.noteRadioArrival(aHash)
            assertTrue(subject.defer(dmScope(), settled()))
        }

    @Test
    fun `a radio arrival is evidence for its own bytes only`() =
        runTest {
            ackPlane = null
            authored = false
            val subject = policy()
            subject.noteRadioArrival("b".repeat(64))
            assertFalse(subject.defer(dmScope(), settled()))
        }

    @Test
    fun `a radio arrival does not rescue a peer that was never sighted`() =
        runTest {
            // The arrival softens the evidence half only; presence still expires and still gates.
            ackPlane = null
            authored = false
            reachable = emptySet()
            val subject = policy()
            subject.noteRadioArrival(aHash)
            assertFalse(subject.defer(dmScope(), settled()))
        }

    @Test
    fun `the oldest radio arrival is forgotten past the cap`() =
        runTest {
            ackPlane = null
            authored = false
            val subject =
                AttachmentDeferPolicy(
                    reachable = { reachable },
                    carriedByRadio = { _, _ -> false },
                    authoredHere = { authored },
                    custodyTtlMs = custodyTtlMs,
                    clock = { clock },
                    maxRadioArrivals = 2,
                )
            subject.noteRadioArrival(aHash)
            subject.noteRadioArrival("b".repeat(64))
            assertTrue(subject.defer(dmScope(), settled()))
            // A third arrival evicts the first, whose bytes then push — deferring less, never more.
            subject.noteRadioArrival("c".repeat(64))
            assertFalse(subject.defer(dmScope(), settled()))
        }

    @Test
    fun `a carried attachment is uploaded inside the grace`() =
        runTest {
            // A carrier holds sealed bytes and no message row, and an avatar writes none either, so the
            // evidence query finds nothing between this pair — and neither may borrow the grace, which
            // belongs to a send of ours still waiting on its ack.
            ackPlane = null
            authored = false
            assertFalse(policy().defer(dmScope(), ref()))
        }

    @Test
    fun `the grace does not rescue a peer that was never sighted`() =
        runTest {
            // The grace softens the ack half only. Presence is still required, so a fresh process and a
            // peer we hold no sighting for both push exactly as before.
            ackPlane = null
            reachable = emptySet()
            assertFalse(policy().defer(dmScope(), ref()))
        }

    // --- proof that the recipient holds the bytes (ADR 2026-10.vy27) ---

    @Test
    fun `proof that the recipient holds the bytes settles the photo for good, across a restart`() =
        runTest {
            assertFalse("no proof yet", policy().proved(dmScope(), settled()))
            proofs[aHash to bob] = DeliveryPlane.Nearby

            // A fresh process: no sighting, the peers long parted, the frame at last call. None of it reopens the push.
            reachable = emptySet()
            clock = start + custodyTtlMs - AttachmentDeferPolicy.LAST_CALL_MS
            val restarted = policy()
            assertTrue(restarted.proved(dmScope(), ref()))
            assertFalse("the deferral itself has lapsed; the proof is what holds it", restarted.defer(dmScope(), ref()))
        }

    @Test
    fun `an ack alone defers and then uploads once the peers part`() =
        runTest {
            val p = policy()
            assertFalse("a delivery ack is not proof of bytes", p.proved(dmScope(), settled()))
            assertTrue(p.defer(dmScope(), settled()))

            reachable = emptySet()
            clock += AttachmentDeferPolicy.RADIO_WINDOW_MS + 1
            assertFalse(p.proved(dmScope(), settled()))
            assertFalse("the reversing half lapsed: upload", p.defer(dmScope(), settled()))
        }

    @Test
    fun `a group never takes a proof, so it always uploads`() =
        runTest {
            proofs[aHash to bob] = DeliveryPlane.Nearby
            assertFalse(policy().proved(groupScope(), ref()))
            assertFalse(policy().defer(groupScope(), ref()))
        }

    /**
     * A DM photo of ours to a recipient only a contact's direct link reaches (ADR 2026-10.a5yj): no radio sighting and
     * an ack over the link, which is no radio's. The link is the sighting, so the send waits out its ack grace and one
     * proof round, asking each round, rather than going up in the round the send woke; the link's proof settles it.
     */
    @Test
    fun `a DM photo to a recipient only a direct link reaches waits for its proof and is settled by it`() =
        runTest {
            reachable = emptySet()
            directLinked = setOf(bob)
            linked = true
            ackPlane = DeliveryPlane.DirectLink
            val subject = policy()

            assertTrue("the send's own round waits out the grace", subject.defer(dmScope(), ref()))
            assertEquals(listOf(aHash to bob), asked)
            clock = start + AttachmentDeferPolicy.ACK_GRACE_MS
            assertTrue("then one proof round", subject.defer(dmScope(), ref()))
            proofs[aHash to bob] = DeliveryPlane.DirectLink
            assertTrue("the link's answer settles it for good", subject.proved(dmScope(), ref()))

            directLinked = emptySet()
            assertTrue("whether or not the link stays", policy().proved(dmScope(), ref()))
        }

    /**
     * The proof round is held per attachment, only when its question reaches the peer (ADR 2026-10.a5yj): a direct link
     * drops a holder query naming a picture it does not carry, so a send whose query cannot cross holds no round on a
     * question that never left, while another attachment to the same peer, whose query does cross, still holds one.
     */
    @Test
    fun `a send whose holder query the link cannot carry holds no proof round`() =
        runTest {
            reachable = emptySet()
            directLinked = setOf(bob)
            linked = true
            ackPlane = DeliveryPlane.DirectLink
            clock = start + AttachmentDeferPolicy.ACK_GRACE_MS
            unreachable += aHash

            assertFalse("past the grace, no round on a question that never left", policy().defer(dmScope(), ref()))
            assertFalse(policy().holdsForProof(dmScope(), ref()))

            unreachable.clear()
            assertTrue("the same send, its query now carried: one proof round", policy().defer(dmScope(), ref()))
        }

    /**
     * The link half of the sighting lasts exactly as long as the link (ADR 2026-10.a5yj): it is read live and never
     * remembered, so the first round after the link goes pushes, with no radio window after it, and a link's ack alone
     * defers nothing past the grace and the proof round.
     */
    @Test
    fun `a direct link's sighting ends with the link, and the photo goes up the round after`() =
        runTest {
            reachable = emptySet()
            directLinked = setOf(bob)
            linked = true
            ackPlane = DeliveryPlane.DirectLink
            val subject = policy()
            assertTrue(subject.defer(dmScope(), ref()))

            directLinked = emptySet()
            linked = false
            clock = start + 1
            assertFalse("the link went: pushed at once, inside the grace", subject.defer(dmScope(), ref()))

            directLinked = setOf(bob)
            linked = true
            clock = start + AttachmentDeferPolicy.ACK_GRACE_MS
            assertTrue("back: the proof round holds", subject.defer(dmScope(), ref()))
            clock += AttachmentDeferPolicy.PROOF_WAIT_MS
            assertFalse("a link's ack is no radio's: past the proof round it pushes", subject.defer(dmScope(), ref()))
        }

    /**
     * The recipient's end (ADR 2026-10.a5yj): bytes off a link are noted before the store write, so a heal round in the
     * gap before the proof is recorded holds instead of uploading them — while the link that carried them is up.
     */
    @Test
    fun `a recipient holds bytes a direct link delivered until its proof lands, while the link is up`() =
        runTest {
            reachable = emptySet()
            directLinked = setOf(bob)
            authored = false
            ackPlane = DeliveryPlane.DirectLink
            val subject = policy()
            subject.noteRadioArrival(aHash)

            assertTrue(subject.defer(dmScope(), settled()))
            directLinked = emptySet()
            assertFalse("no link, no radio: nothing to hold on", subject.defer(dmScope(), settled()))
        }

    @Test
    fun `LoRa is never proof`() =
        runTest {
            proofs[aHash to bob] = DeliveryPlane.LoRa
            ackPlane = DeliveryPlane.LoRa
            assertFalse("a board carries no blob", policy().proved(dmScope(), settled()))
            assertFalse(policy().defer(dmScope(), settled()))
        }

    @Test
    fun `a send of ours asks its peer for the proof while the peer is in sight, and nothing else asks`() =
        runTest {
            policy().defer(dmScope(), settled())
            assertEquals("asked whatever the round decides", listOf(aHash to bob), asked)

            asked.clear()
            authored = false
            policy().defer(dmScope(), settled())
            assertTrue("the peer's own send: its proof is our radio arrival, not its answer", asked.isEmpty())

            authored = true
            reachable = emptySet()
            policy().defer(dmScope(), settled())
            assertTrue("no sighting, nobody to ask", asked.isEmpty())

            reachable = setOf(bob)
            policy().defer(groupScope(), settled())
            assertTrue("a group never asks", asked.isEmpty())
        }

    // --- one proof round before a push (ADR 2026-10.vy27, revision 2) ---

    @Test
    fun `a send with nothing to defer on holds one proof round for a linked peer that answers, and a yes settles it`() =
        runTest {
            // The device run's slipped photo: its receipt came back over the relay, and the grace is long gone.
            ackPlane = DeliveryPlane.Internet
            linked = true
            val p = policy()
            assertTrue("asked, so held for the answer", p.defer(dmScope(), settled()))
            assertEquals(listOf(aHash to bob), asked)

            proofs[aHash to bob] = DeliveryPlane.Nearby // bob's HAVE, a second later
            assertTrue("settled for good on the next pass", p.proved(dmScope(), settled()))
        }

    @Test
    fun `a no or silence pushes on the first pass after the proof round`() =
        runTest {
            ackPlane = DeliveryPlane.Internet
            linked = true
            val p = policy()
            assertTrue(p.defer(dmScope(), settled()))
            clock += AttachmentDeferPolicy.PROOF_WAIT_MS - 1
            assertTrue("still inside the round", p.defer(dmScope(), settled()))
            clock += 1
            assertFalse("the round is over: push", p.defer(dmScope(), settled()))
            assertFalse(p.proved(dmScope(), settled()))
        }

    @Test
    fun `a peer not linked or not answering the holder query, or a photo not ours, pushes as before`() =
        runTest {
            ackPlane = DeliveryPlane.Internet
            linked = false
            assertFalse("no live link that answers: today's rule", policy().defer(dmScope(), settled()))

            linked = true
            authored = false
            assertFalse("the peer's own send waits on no answer of ours", policy().defer(dmScope(), settled()))

            authored = true
            assertFalse("a group never holds", policy().defer(groupScope(), settled()))
        }

    @Test
    fun `on a locked relay a send of ours holds for its proof round, asks for it, and lets go when the round ends`() =
        runTest {
            linked = true
            val p = policy()
            assertTrue(p.holdsForProof(dmScope(), settled()))
            assertEquals("asked, so the round can end in a proof", listOf(aHash to bob), asked)
            clock += AttachmentDeferPolicy.PROOF_WAIT_MS
            assertFalse("the round is over: the lock may settle it", p.holdsForProof(dmScope(), settled()))
        }

    @Test
    fun `nothing else holds for proof on a locked relay`() =
        runTest {
            linked = false
            assertFalse("no live link that answers", policy().holdsForProof(dmScope(), settled()))
            linked = true
            authored = false
            assertFalse("the peer's own send", policy().holdsForProof(dmScope(), settled()))
            authored = true
            assertFalse("a group", policy().holdsForProof(groupScope(), settled()))
            clock = start + custodyTtlMs - AttachmentDeferPolicy.LAST_CALL_MS
            assertFalse("last call", policy().holdsForProof(dmScope(), ref()))
            assertTrue("none of these asked", asked.isEmpty())
        }
}
