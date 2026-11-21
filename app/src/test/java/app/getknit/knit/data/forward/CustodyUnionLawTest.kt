package app.getknit.knit.data.forward

import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import app.getknit.knit.data.KnitDatabase
import app.getknit.knit.data.RoomDbTest
import app.getknit.knit.data.message.Conversations
import app.getknit.knit.mesh.CarriedFrame
import app.getknit.knit.mesh.ForwardStore
import app.getknit.knit.mesh.StoreDigest
import app.getknit.knit.mesh.protocol.FrameType
import app.getknit.knit.mesh.protocol.GroupInfo
import app.getknit.knit.mesh.protocol.RelayEnvelope
import app.getknit.knit.mesh.protocol.WireCodec
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Ignore
import org.junit.Test
import kotlin.random.Random
import kotlin.time.Duration.Companion.minutes

/**
 * The law gossip needs of custody below the row cap (#349), stronger than ADR 006's "two stores holding the same frames
 * keep the same ones": what a store keeps is a function of the union of the frames it was offered, whatever their order
 * and however often one comes back after its own eviction. With it, two stores that swap what the other lacks hold the
 * same set after one exchange each way, which is what lets custody's digests agree. Random fields of DMs, group posts and
 * room posts, over the real [ForwardRepository] and its SQL, at small quotas so the sender, group and room trims bind and
 * buckets and stamps tie.
 *
 * **Only below the row cap.** At the cap the global trim's choice of row reads the carry ledger, which is each node's own
 * (ADR 2026-11.bmks), so stores at the cap may differ for good by design; and before that change the cap with a room or
 * group trim already split about one field in three (#349's first finding). Every store here is far below
 * [ForwardRepository.DEFAULT_MAX_ROWS], so only the quotas trim.
 *
 * Each quota alone keeps its bucket's newest N by the frame-global `(sentAt, id)`, and the group and room buckets never
 * share a frame, so those hold. Where two quotas count one frame — a sender's room post or group post, in the sender's
 * bucket and the room's or group's — a trim of one bucket over a subset can keep a frame the union would drop: the sender
 * quota, then, splits a store below the row cap too, in about one field in six (#349 found the split with the cap; it is
 * not the cap's alone). Those three cases stay here as #349's, ignored with the rates they measured, until per-class
 * budgets with one bucket key each land; a fix un-ignores them, and none is weakened to pass.
 */
class CustodyUnionLawTest : RoomDbTest() {
    private val extra = mutableListOf<KnitDatabase>()

    @After
    fun closeExtra() {
        extra.forEach { it.close() }
        extra.clear()
    }

    @Test
    fun `below the row cap, a sender quota alone keeps the union's frames`() =
        runTest(timeout = 5.minutes) {
            assertUnionLaw(Quotas(sender = SENDER_QUOTA))
        }

    @Test
    fun `below the row cap, a group quota alone keeps the union's frames`() =
        runTest(timeout = 5.minutes) {
            assertUnionLaw(Quotas(group = GROUP_QUOTA))
        }

    @Test
    fun `below the row cap, a room quota alone keeps the union's frames`() =
        runTest(timeout = 5.minutes) {
            assertUnionLaw(Quotas(room = ROOM_QUOTA))
        }

    @Test
    fun `below the row cap, a group quota and a room quota, which share no frame, keep the union's frames`() =
        runTest(timeout = 5.minutes) { assertUnionLaw(Quotas(group = GROUP_QUOTA, room = ROOM_QUOTA)) }

    // Measured 2026-11-20 over 300 fields: (46, 55, 70) of (order, re-offer, one exchange) split.
    @Ignore("#349: a sender quota and a group quota both count a member's post, so the two split a store below the row cap")
    @Test
    fun `below the row cap, a sender quota and a group quota keep the union's frames`() =
        runTest(timeout = 5.minutes) { assertUnionLaw(Quotas(sender = SENDER_QUOTA, group = GROUP_QUOTA)) }

    // Measured 2026-11-20 over 300 fields: (40, 48, 54) of (order, re-offer, one exchange) split.
    @Ignore("#349: a sender quota and a room quota both count a sender's post, so the two split a store below the row cap")
    @Test
    fun `below the row cap, a sender quota and a room quota keep the union's frames`() =
        runTest(timeout = 5.minutes) { assertUnionLaw(Quotas(sender = SENDER_QUOTA, room = ROOM_QUOTA)) }

    // Measured 2026-11-20 over 300 fields: (47, 54, 67) of (order, re-offer, one exchange) split.
    @Ignore("#349: the sender quota shares frames with the group and room quotas, so the three split a store below the row cap")
    @Test
    fun `below the row cap, every quota at once keeps the union's frames`() =
        runTest(timeout = 5.minutes) { assertUnionLaw(Quotas(sender = SENDER_QUOTA, group = GROUP_QUOTA, room = ROOM_QUOTA)) }

    /**
     * The three faces of the law over [TRIALS] random fields under [quotas], each failure counted rather than the first
     * one thrown, so a split reads as a rate: the kept set is the same for two orders of one field; offering every frame
     * again, the evicted ones included, changes nothing; and two stores that each hold part of the field (with some
     * overlap) agree with a store that took it all, after one exchange each way.
     */
    private suspend fun assertUnionLaw(quotas: Quotas) {
        var order = 0
        var reoffer = 0
        var exchange = 0
        var first: String? = null
        repeat(TRIALS) { trial ->
            // Each trial's stores are closed with it: every trial's four databases are not kept to the end.
            closeExtra()
            val random = Random(trial)
            val field = field(random)
            val one = newStore(quotas)
            val kept = held(one, field.shuffled(random))
            if (kept != held(newStore(quotas), field.shuffled(random))) {
                order++
                first = first ?: "trial $trial: two orders of ${field.size} frames keep different sets"
            }
            if (kept != held(one, (field + field.shuffled(random).take(random.nextInt(field.size))).shuffled(random))) {
                reoffer++
                first = first ?: "trial $trial: offering the frames again moved the kept set"
            }
            val split = field.partition { random.nextBoolean() }
            val overlap = field.filter { random.nextInt(OVERLAP_ONE_IN) == 0 }
            val left = newStore(quotas)
            val right = newStore(quotas)
            held(left, (split.first + overlap).distinct().shuffled(random))
            held(right, (split.second + overlap).distinct().shuffled(random))
            swap(left, right, random)
            if (ids(left) != kept || ids(right) != kept) {
                exchange++
                first = first ?: "trial $trial: one exchange each way left ${ids(left).size} and ${ids(right).size} of ${kept.size}"
            }
        }
        assertEquals(
            "$quotas over $TRIALS fields (order, re-offer, one exchange); first: $first",
            Triple(0, 0, 0),
            Triple(order, reoffer, exchange),
        )
    }

    // Each store takes, in a random order, every frame the other holds and it does not: both read before either writes.
    private suspend fun swap(
        left: ForwardRepository,
        right: ForwardRepository,
        random: Random,
    ) {
        val leftHeld = left.liveFrames(NOW).map { it.envelope }
        val rightHeld = right.liveFrames(NOW).map { it.envelope }
        val leftIds = leftHeld.map { it.id }.toSet()
        val rightIds = rightHeld.map { it.id }.toSet()
        held(left, rightHeld.filter { it.id !in leftIds }.shuffled(random))
        held(right, leftHeld.filter { it.id !in rightIds }.shuffled(random))
    }

    private suspend fun held(
        store: ForwardRepository,
        frames: List<RelayEnvelope>,
    ): Set<String> {
        for (frame in frames) {
            store.store(CarriedFrame(frame, sig = ByteArray(0), signed = WireCodec.encodeEnvelope(frame)), ForwardStore.ORIGIN_RELAY, NOW)
        }
        return ids(store)
    }

    private suspend fun ids(store: ForwardRepository): Set<String> = store.liveIds(NOW).toSet()

    /** The quotas that bind, each off (far above any field) unless named; the row cap is always far above. */
    private data class Quotas(
        val sender: Int = OFF,
        val group: Int = OFF,
        val room: Int = OFF,
    )

    private fun newStore(quotas: Quotas): ForwardRepository {
        val database =
            Room
                .inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), KnitDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        extra += database
        return ForwardRepository(
            database.forwardDao(),
            StoreDigest(),
            database,
            maxRows = ForwardRepository.DEFAULT_MAX_ROWS,
            maxPerSender = quotas.sender,
            maxPerGroup = quotas.group,
            maxBroadcast = quotas.room,
        )
    }

    // A field of FIELD_MIN..FIELD_MAX frames: DMs to bob from six senders, posts in one group, room posts, stamped
    // within STAMPS ms of each other so stamps tie across senders.
    private fun field(random: Random): List<RelayEnvelope> =
        List(FIELD_MIN + random.nextInt(FIELD_MAX - FIELD_MIN)) { n ->
            val sentAt = T0 + random.nextInt(STAMPS)
            val id = "f%03d-%04x".format(n, random.nextInt(ID_SUFFIXES))
            when (random.nextInt(KINDS)) {
                0 -> {
                    RelayEnvelope(
                        type = FrameType.CHAT,
                        id = id,
                        senderId = MEMBERS.random(random),
                        sentAt = sentAt,
                        group = group,
                        payload = EMPTY,
                    )
                }

                1 -> {
                    RelayEnvelope(type = FrameType.CHAT, id = id, senderId = SENDERS.random(random), sentAt = sentAt, payload = EMPTY)
                }

                else -> {
                    RelayEnvelope(
                        type = FrameType.CHAT,
                        id = id,
                        senderId = SENDERS.random(random),
                        sentAt = sentAt,
                        recipientId = "bob",
                        payload = EMPTY,
                    )
                }
            }
        }

    private val group = GroupInfo(id = Conversations.groupIdFor(MEMBERS), members = MEMBERS, createdBy = MEMBERS.first())

    private companion object {
        const val TRIALS = 100
        const val T0 = 1_800_000_000_000L
        const val NOW = T0 + 1_000L
        const val STAMPS = 12
        const val FIELD_MIN = 20
        const val FIELD_MAX = 48
        const val ID_SUFFIXES = 0x10000

        // A group post, a room post, and two DMs in four.
        const val KINDS = 4
        const val OVERLAP_ONE_IN = 4
        const val SENDER_QUOTA = 7
        const val GROUP_QUOTA = 5
        const val ROOM_QUOTA = 4

        // A quota no field reaches: [FIELD_MAX] frames at most.
        const val OFF = 10_000
        val EMPTY = ByteArray(0)
        val MEMBERS = listOf("alice", "bob", "carol")
        val SENDERS = listOf("alice", "carol", "dave", "erin", "frank", "grace")
    }
}
