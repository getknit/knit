package app.getknit.knit.mesh.sim

import app.getknit.knit.mesh.lab.AiredRecord.Kind
import org.junit.Assert.assertEquals
import org.junit.Test

/** [ExchangeLedger] over a hand-made run of air events, without stacks. */
class ExchangeLedgerTest {
    // A asks B three times; B answers the first with two new frames, the second with nothing, the third with one frame A
    // already had and one new. A also hands B a DM addressed to B, which answers nobody.
    private val events =
        listOf(
            digest(at = 0, from = A, to = B),
            frame(at = 10, from = B, to = A, id = "x", addressee = "q"),
            frame(at = 15, from = A, to = B, id = "w", addressee = B),
            frame(at = 20, from = B, to = A, id = "y", addressee = A),
            digest(at = 1_000, from = A, to = B),
            digest(at = 61_000, from = A, to = B),
            frame(at = 61_010, from = B, to = A, id = "x", addressee = "q"),
            frame(at = 61_020, from = B, to = A, id = "z", addressee = "r"),
        )

    private fun counts(input: List<AirEvent>) = ExchangeLedger.counts(input) { node -> if (node == A) setOf("x") else emptySet() }

    @Test
    fun exchangesAreReadOffTheDigestsAndTheAnswersThatFollowThem() {
        val c = counts(events)
        assertEquals(3, c.digests)
        assertEquals(3, c.exchanges)
        assertEquals("only the second answer moved nothing new", 1, c.emptyExchanges)
        assertEquals("the third digest came a minute after the empty answer", listOf(60_000L), c.reaskAfterEmptyMs)
    }

    @Test
    fun aFrameTheReceiverHadIsReSentAndEveryNewOneEndsSomewhere() {
        val c = counts(events)
        assertEquals(5, c.frames)
        assertEquals("x reached A twice", 1, c.resent)
        assertEquals(4, c.newFrames)
        assertEquals("y to A and w to B reached their addressee", 2, c.toRecipient)
        assertEquals("A holds x", 1, c.heldAtEnd)
        assertEquals("z was not kept", 1, c.notKept)
    }

    @Test
    fun theDigestsShareIsOfEveryByteAired() {
        val c = counts(events)
        assertEquals(3 * DIGEST_BYTES, c.digestBytes)
        assertEquals(5 * FRAME_BYTES, c.frameBytes)
        assertEquals(100.0 * 3 * DIGEST_BYTES / (3 * DIGEST_BYTES + 5 * FRAME_BYTES), checkNotNull(c.digestSharePct), 1e-9)
    }

    @Test
    fun theOrderTheEventsArriveInDoesNotMatterOnlyTheirTimes() {
        assertEquals(counts(events), counts(events.reversed()))
    }

    @Test
    fun aFrameHeldAtTheStartOrWrittenByItsReceiverIsReSent() {
        val run =
            listOf(
                digest(at = 0, from = A, to = B),
                frame(at = 10, from = B, to = A, id = "old", addressee = "q"),
                frame(at = 20, from = B, to = A, id = "mine", addressee = "q", sender = A),
            )
        val c = ExchangeLedger.counts(run, heldAtStart = { if (it == A) setOf("old") else emptySet() }) { setOf("old", "mine") }
        assertEquals("A held old before and wrote mine", 2, c.resent)
        assertEquals(0, c.newFrames)
        assertEquals("nothing new answered A", 1, c.emptyExchanges)
    }

    @Test
    fun aReSendIsSplitByHopsBetweenACustodyServeAndARouterRelay() {
        val run =
            listOf(
                frame(at = 10, from = B, to = A, id = "x", addressee = "q", hops = 0),
                frame(at = 20, from = B, to = A, id = "x", addressee = "q", hops = 0),
                frame(at = 30, from = B, to = A, id = "x", addressee = "q", hops = 2),
                frame(at = 40, from = B, to = A, id = "y", addressee = "q", hops = 1),
                frame(at = 50, from = B, to = A, id = "y", addressee = "q"),
            )
        val c = ExchangeLedger.counts(run) { emptySet() }
        assertEquals("x twice more, y once more", 3, c.resent)
        assertEquals("the hop-2 copy of x was the flood's", 1, c.resentRelayed)
        assertEquals("a hop-0 copy, and one with no hops known, were served", 2, c.resentServed)
        assertEquals("a first arrival is new whatever its hops", 2, c.newFrames)
    }

    @Test
    fun aFrameCustodyNeverHoldsCountsForItsBytesAlone() {
        val run =
            listOf(
                digest(at = 0, from = A, to = B),
                AirEvent(10 * NANOS_PER_MS, B, A, Kind.FRAME, FRAME_BYTES, frameId = "cue", custodial = false),
            )
        val c = ExchangeLedger.counts(run) { emptySet() }
        assertEquals(0, c.frames)
        assertEquals(FRAME_BYTES, c.otherBytes)
        assertEquals(DIGEST_BYTES + FRAME_BYTES, c.bytes)
        assertEquals("a typing cue answers nothing", 1, c.emptyExchanges)
    }

    private companion object {
        const val A = "node-a"
        const val B = "node-b"
        const val DIGEST_BYTES = 100L
        const val FRAME_BYTES = 200L
        const val NANOS_PER_MS = 1_000_000L

        fun digest(
            at: Long,
            from: String,
            to: String,
        ) = AirEvent(at * NANOS_PER_MS, from, to, Kind.DIGEST, DIGEST_BYTES)

        fun frame(
            at: Long,
            from: String,
            to: String,
            id: String,
            addressee: String,
            sender: String = "author",
            hops: Int? = null,
        ) = AirEvent(
            at * NANOS_PER_MS,
            from,
            to,
            Kind.FRAME,
            FRAME_BYTES,
            frameId = id,
            recipientId = addressee,
            senderId = sender,
            hops = hops,
        )
    }
}
