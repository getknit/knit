package app.getknit.knit.data.meshcore

import app.getknit.knit.data.RoomDbTest
import app.getknit.knit.mesh.lora.meshcore.MeshcoreSetupRecord
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [MeshcoreSetupStore] over the real `meshcore_setup` SQL (ADR 2026-11.gsqu's Stage 5b-2 amendment). */
class MeshcoreSetupStoreTest : RoomDbTest() {
    private val store by lazy { MeshcoreSetupStore(db.meshcoreSetupDao()) }

    private val name = "Zoë Ñúñez 🌲"

    @Test
    fun aRecordRoundTripsWithANullPriorAndANonAsciiName() =
        runTest {
            store.put("AA:BB", "0123456789abcdef", MeshcoreSetupRecord(2, true, name.encodeToByteArray(), 0, null), pending = false)
            val entry = checkNotNull(store.entryOf("AA:BB"))
            assertEquals("0123456789abcdef", entry.digest)
            assertFalse(entry.pending)
            assertEquals(2, entry.record.slot)
            assertTrue(entry.record.slotWritten)
            assertArrayEquals(name.encodeToByteArray(), entry.record.priorName)
            assertEquals(0, entry.record.priorManualAdd)
            assertNull(entry.record.priorAutoadd)
            assertNull(store.entryOf("CC:DD"))
        }

    @Test
    fun aPutReplacesMarkPendingTouchesOnlyAPresentRowAndClearRemovesIt() =
        runTest {
            store.put("AA:BB", "aa", MeshcoreSetupRecord(1, false, null, null, 7), pending = true)
            assertTrue(checkNotNull(store.entryOf("AA:BB")).pending)
            store.put("AA:BB", "bb", MeshcoreSetupRecord(3, true, null, 1, null), pending = false)
            val replaced = checkNotNull(store.entryOf("AA:BB"))
            assertEquals("bb", replaced.digest)
            assertEquals(3, replaced.record.slot)
            assertFalse(replaced.pending)
            assertTrue(store.markPending("AA:BB"))
            assertTrue(checkNotNull(store.entryOf("AA:BB")).pending)
            assertFalse("a mark of no row is no mark", store.markPending("CC:DD"))
            assertNull("no row is made for a missing address", store.entryOf("CC:DD"))
            store.clear("AA:BB")
            assertNull(store.entryOf("AA:BB"))
        }

    @Test
    fun noLineNamesAField() =
        runTest {
            store.put("AA:BB", "0123456789abcdef", MeshcoreSetupRecord(2, true, name.encodeToByteArray(), 0, null), pending = false)
            val entry = checkNotNull(store.entryOf("AA:BB"))
            val row = checkNotNull(db.meshcoreSetupDao().get("AA:BB"))
            // The exact custom lines: a ByteArray would print as [B@… even unredacted, so absence of the name alone proves nothing.
            assertEquals("MeshcoreSetupEntry(pending=false)", entry.toString())
            assertEquals("MeshcoreSetupRecord(slot=2, slotWritten=true, name=true, manualAdd=0, autoadd=null)", entry.record.toString())
            assertEquals("MeshcoreSetupEntity(pending=false)", row.toString())
            for (line in listOf(entry.toString(), entry.record.toString(), row.toString())) {
                assertFalse(line, line.contains("priorName") || line.contains("boardDigest") || line.contains("address"))
            }
        }
}
