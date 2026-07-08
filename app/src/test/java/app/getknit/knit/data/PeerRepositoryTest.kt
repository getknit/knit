package app.getknit.knit.data

import app.getknit.knit.data.peer.PeerEntity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Drives [PeerRepository.sweepCap] against a real in-memory DB with a tiny cap — the bound that stops a Sybil
 * profile flood from growing the (otherwise uncapped) `peers` table, while sparing verified/known peers.
 */
class PeerRepositoryTest : RoomDbTest() {
    @Test
    fun `sweepCap evicts the oldest unverified strangers beyond the cap, sparing verified and protected`() =
        runTest {
            val dao = db.peerDao()
            dao.upsert(PeerEntity(nodeId = "verified", verified = true, updatedAt = 1L))
            dao.upsert(PeerEntity(nodeId = "known", verified = false, updatedAt = 2L)) // protected (e.g. we messaged them)
            dao.upsert(PeerEntity(nodeId = "old", verified = false, updatedAt = 3L))
            dao.upsert(PeerEntity(nodeId = "new", verified = false, updatedAt = 4L))

            PeerRepository(dao, maxPeers = 1).sweepCap(protected = setOf("known"))

            // Cappable pool = {old, new} (verified + protected spared); cap 1 → evict the 1 oldest = "old".
            assertNull(dao.findByNodeId("old"))
            assertNotNull(dao.findByNodeId("new"))
            assertNotNull(dao.findByNodeId("verified"))
            assertNotNull(dao.findByNodeId("known"))
        }
}
