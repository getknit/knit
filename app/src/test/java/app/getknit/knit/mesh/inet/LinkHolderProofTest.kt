package app.getknit.knit.mesh.inet

import app.getknit.knit.data.MessageRepository
import app.getknit.knit.data.RoomDbTest
import app.getknit.knit.data.forward.ForwardRepository
import app.getknit.knit.data.message.MessageEntity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sender's proof of a DM photo a contact's direct link carried because no spool would take it (ADR 2026-10.a5yj
 * L-7-7, vy27), against the real SQL: every round asks each link-only peer about our stuck DM attachments to it, read
 * afresh from the database — until the proof, the link's going or the custody TTL — and never a radio's peer, a group's
 * picture, someone else's photo or one a spool takes.
 */
class LinkHolderProofTest : RoomDbTest() {
    private val messages by lazy { MessageRepository(db.messageDao()) }
    private var now = NOW

    // What `MeshManager.attachmentStuck` says: refused by every spool carrying the pair, and not proved held.
    private val refused = mutableSetOf("H1", "H2", "H4", "G1")
    private val proved = mutableSetOf<Pair<String, String>>()
    private val radio = mutableSetOf<String>()
    private val asks = mutableListOf<Pair<String, String>>()

    private val proof by lazy {
        LinkHolderProof(
            messages = messages,
            selfId = { ME },
            radioLinked = { it in radio },
            stuck = { peer, hash -> hash in refused && (peer to hash) !in proved },
            ask = { hash, peer -> asks += peer to hash },
            clock = { now },
        )
    }

    private fun dm(
        id: String,
        to: String,
        hash: String?,
        sentAt: Long = NOW - 1_000,
        from: String = ME,
    ) = MessageEntity(
        id = id,
        senderId = from,
        recipientId = to,
        conversationId = if (from == ME) to else from,
        body = "x",
        sentAt = sentAt,
        attachmentHash = hash,
    )

    @Test
    fun aStuckUnprovedPhotoIsAskedEveryRoundUntilItIsProved() =
        runTest {
            db.messageDao().upsert(dm("m1", "bob", "H1"))

            proof.round(setOf("bob"))
            now += LinkDemand.ROUND_MS
            // A FETCHING answer proves nothing: no holder row, so the photo is still stuck and the next round asks again.
            proof.round(setOf("bob"))
            assertEquals(listOf("bob" to "H1", "bob" to "H1"), asks)

            proved += "bob" to "H1"
            now += LinkDemand.ROUND_MS
            assertTrue("a proved photo was asked again", proof.round(setOf("bob")).isEmpty())
            assertEquals(2, asks.size)
        }

    @Test
    fun onlyAPeerALinkAloneHoldsIsAsked() =
        runTest {
            db.messageDao().upsert(dm("m1", "bob", "H1"))
            db.messageDao().upsert(dm("m2", "carol", "H2"))
            db.messageDao().upsert(dm("m3", "dave", "H4"))
            radio += "carol"

            // Carol's radio asks for her proof on the spool plane's own rounds; Dave's link is gone, or never was, so his
            // stuck photo waits for one.
            proof.round(setOf("carol"))
            assertTrue("a radio's peer was asked over the link round: $asks", asks.isEmpty())
            proof.round(setOf("bob", "carol"))
            assertEquals(listOf("bob" to "H1"), asks)
        }

    @Test
    fun aGroupPictureSomeoneElsesPhotoAndOneASpoolTakesAreNeverAsked() =
        runTest {
            val dao = db.messageDao()
            val post = MessageEntity(id = "p1", senderId = ME, conversationId = "g-1", body = "x", sentAt = NOW - 1_000)
            dao.upsert(post.copy(attachmentHash = "G1"))
            dao.upsert(dm("m1", ME, "H1", from = "bob"))
            dao.upsert(dm("m2", "bob", "H3"))
            dao.upsert(dm("m3", "bob", null))

            assertTrue("asked: $asks", proof.round(setOf("bob")).isEmpty())
        }

    @Test
    fun aPhotoIsAskedUntilCustodysTtlNotJustTheDemandsWindow() =
        runTest {
            val dao = db.messageDao()
            // A link that came up hours late on its backoff still proves the photo it then carried: past the demand's own
            // window, up to custody's edge and not past it.
            dao.upsert(dm("m1", "bob", "H1", sentAt = NOW - LinkDemandPolicy.WINDOW_MS - 1))
            dao.upsert(dm("m2", "bob", "H2", sentAt = NOW - ForwardRepository.DEFAULT_TTL_MS - 1))
            dao.upsert(dm("m3", "bob", "H4", sentAt = NOW - ForwardRepository.DEFAULT_TTL_MS + 1))

            assertEquals(setOf("bob" to "H1", "bob" to "H4"), proof.round(setOf("bob")).toSet())
        }

    @Test
    fun onePhotoInTwoMessagesIsOneAsk() =
        runTest {
            db.messageDao().upsert(dm("m1", "bob", "H1"))
            db.messageDao().upsert(dm("m2", "bob", "H1", sentAt = NOW - 2_000))

            assertEquals(listOf("bob" to "H1"), proof.round(setOf("bob")))
        }

    private companion object {
        const val ME = "me"
        const val NOW = 2_000_000_000_000L
    }
}
