package app.getknit.knit.data.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import app.getknit.knit.mesh.MeshcoreBoardReport
import app.getknit.knit.mesh.lora.BoardKind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The MeshCore half of the `dialled` race (ADR 2026-11.gsqu's Stage 5a-2 amendment): the board key a session reports lands
 * only under the address that session dialled while that is still the bound one, so on an A→B rebind A's late Ready writes
 * nothing into the sealed profile's claim.
 */
class MeshcoreClaimStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun aLateReportFromTheBoardBeforeWritesNothingAndTheBoundOnesWrites() =
        runTest {
            val dataStore = PreferenceDataStoreFactory.create(scope = backgroundScope) { File(tmp.root, "settings.preferences_pb") }
            val settings = SettingsStore(dataStore)
            val claims = MeshcoreClaimStore(dataStore)
            settings.bindBoard(BoardKind.MESHCORE, "BB:BB", name = "")

            claims.setBoardKey("a-key", dialled = "AA:AA")
            assertNull("A's late Ready, after the plane moved to B", claims.boardKey.first())

            claims.setBoardKey("b-key", dialled = "BB:BB")
            assertEquals("b-key", claims.boardKey.first())

            claims.setBoardKey("a-key", dialled = "AA:AA")
            assertEquals("never overwritten by the old board either", "b-key", claims.boardKey.first())
        }

    /**
     * Stage 5d (the Part B review): the no-rewrite check is asked inside the edit, of the pair the edit sees. A signature
     * another writer cleared before the edit is written again, never left cleared beside its key; a stored proof [keep]
     * accepts stands; one it refuses is replaced; and it is never asked for another key.
     */
    @Test
    fun theKeepCheckReadsTheStoredPairInsideTheEdit() =
        runTest {
            val dataStore = PreferenceDataStoreFactory.create(scope = backgroundScope) { File(tmp.root, "settings.preferences_pb") }
            val settings = SettingsStore(dataStore)
            val claims = MeshcoreClaimStore(dataStore)
            settings.bindBoard(BoardKind.MESHCORE, "BB:BB", name = "")
            claims.setBoardKey("b-key", sig = "b-sig", dialled = "BB:BB")
            val asked = mutableListOf<Pair<String, String>>()
            val keepAll = { k: String, s: String ->
                asked += k to s
                true
            }

            claims.setBoardKey("b-key", sig = "b-sig-2", dialled = "BB:BB", keep = keepAll)
            assertEquals("a proof keep accepts stands", MeshcoreBoardReport("b-key", "b-sig"), claims.board.first())
            assertEquals(listOf("b-key" to "b-sig"), asked)

            claims.setBoardKey("b-key", sig = "b-sig-2", dialled = "BB:BB", keep = { _, _ -> false })
            assertEquals("one it refuses is replaced", MeshcoreBoardReport("b-key", "b-sig-2"), claims.board.first())

            // Another writer (the bridge, the seeder) clears the signature after a caller's read and before its edit.
            dataStore.edit { it.remove(BoardKeys.MESHCORE_BIND) }
            asked.clear()
            claims.setBoardKey("b-key", sig = "b-sig", dialled = "BB:BB", keep = keepAll)
            assertEquals("written again, never left cleared", MeshcoreBoardReport("b-key", "b-sig"), claims.board.first())
            assertTrue("nothing stored to keep: not asked", asked.isEmpty())

            claims.setBoardKey("c-key", sig = "c-sig", dialled = "BB:BB", keep = keepAll)
            assertEquals(MeshcoreBoardReport("c-key", "c-sig"), claims.board.first())
            assertTrue("never asked for another key", asked.isEmpty())
        }

    /**
     * Stage 5d (crypto B3, security B3, lora B1): the key and its bind signature move in one edit by one rule, so a consent
     * re-published without a signature never erases a proof for the same key, the old board's proof never survives
     * beside a new key, and a withdrawal forgets both. The two read as one flow.
     */
    @Test
    fun theSignatureMovesWithTheKeyByOneRule() =
        runTest {
            val dataStore = PreferenceDataStoreFactory.create(scope = backgroundScope) { File(tmp.root, "settings.preferences_pb") }
            val settings = SettingsStore(dataStore)
            val claims = MeshcoreClaimStore(dataStore)
            settings.bindBoard(BoardKind.MESHCORE, "BB:BB", name = "")

            claims.setBoardKey("b-key", sig = "b-sig", dialled = "BB:BB")
            assertEquals(MeshcoreBoardReport("b-key", "b-sig"), claims.board.first())

            claims.setBoardKey("b-key", sig = null, dialled = "BB:BB")
            assertEquals("the same key without a signature keeps the proof", MeshcoreBoardReport("b-key", "b-sig"), claims.board.first())

            claims.setBoardKey("c-key", sig = null, dialled = "BB:BB")
            assertEquals("a new key without one forgets the old board's", MeshcoreBoardReport("c-key", null), claims.board.first())

            claims.setBoardKey("c-key", sig = "c-sig", dialled = "BB:BB")
            assertEquals(MeshcoreBoardReport("c-key", "c-sig"), claims.board.first())

            claims.setBoardKey("x-key", sig = "x-sig", dialled = "AA:AA")
            assertEquals("another board's report writes neither", MeshcoreBoardReport("c-key", "c-sig"), claims.board.first())

            claims.setBoardKey(null, sig = "ignored", dialled = "BB:BB")
            assertEquals("a withdrawal forgets both", MeshcoreBoardReport(null, null), claims.board.first())

            claims.setBoardKey("c-key", sig = "c-sig", dialled = "BB:BB")
            settings.bindBoard(BoardKind.MESHCORE, "DD:DD", name = "")
            assertEquals("a bind forgets both (BoardKeys.MESHCORE.reported)", MeshcoreBoardReport(null, null), claims.board.first())
        }
}
