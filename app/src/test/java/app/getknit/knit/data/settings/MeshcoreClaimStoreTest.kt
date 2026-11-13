package app.getknit.knit.data.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import app.getknit.knit.mesh.lora.BoardKind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
