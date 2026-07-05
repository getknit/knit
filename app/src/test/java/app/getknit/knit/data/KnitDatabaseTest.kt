package app.getknit.knit.data

import app.getknit.knit.data.forward.ForwardEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Smoke test proving the Robolectric + in-memory Room wiring end-to-end — the keystone every DAO test builds
 * on. It asserts the DB opens on framework SQLite (no SQLCipher), all seven DAO accessors resolve, and a
 * `suspend` insert→read round-trips through real SQLite.
 *
 * If this fails, suspect the AGP-9.2.1 ↔ Robolectric-4.16 resource-config integration (plan risk #1): DAO
 * tests need only a Context + SQLite, so this is the narrowest possible reproduction — start debugging here
 * (and if it's the SDK-36 runtime, try `sdk=35` in robolectric.properties).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class KnitDatabaseTest : RoomDbTest() {
    @Test
    fun `all seven DAO accessors resolve`() {
        assertNotNull(db.messageDao())
        assertNotNull(db.peerDao())
        assertNotNull(db.reactionDao())
        assertNotNull(db.blobDao())
        assertNotNull(db.groupDao())
        assertNotNull(db.blobVerdictDao())
        assertNotNull(db.forwardDao())
    }

    @Test
    fun `insert then read round-trips through real SQLite`() =
        runTest {
            val dao = db.forwardDao()
            dao.insert(
                ForwardEntity(
                    id = "f1",
                    recipientId = "peer",
                    groupId = null,
                    senderId = "peer",
                    type = "chat",
                    origin = 0,
                    signed = ByteArray(0),
                    sig = ByteArray(0),
                    sentAt = 1L,
                    receivedAt = 1L,
                    expiresAt = 1_000L,
                ),
            )

            assertTrue(dao.exists("f1"))
            assertEquals(1, dao.count())
            assertEquals(listOf("f1"), dao.allIds())
        }
}
