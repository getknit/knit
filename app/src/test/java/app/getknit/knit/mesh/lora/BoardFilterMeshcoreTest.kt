package app.getknit.knit.mesh.lora

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The picker's MeshCore rules (ADR 2026-11.gsqu, Stage 3d): tested first, on the name prefix alone. */
class BoardFilterMeshcoreTest {
    @Test
    fun aMeshcoreNameIsNoLongerAMeshtasticBoard() {
        // "mesh" used to list it as Meshtastic, and binding it redialled the wrong firmware forever.
        assertFalse(BoardFilter.looksLikeBoard("MeshCore-Knit abcd"))
        assertFalse(BoardFilter.isMeshtastic("MeshCore-WALT_ee02", classicOnly = false) { true })
        assertTrue(BoardFilter.looksLikeMeshcore("MeshCore-Base camp"))
        assertTrue(BoardFilter.looksLikeMeshcore("meshcore-a1b2c3"))
        assertFalse(BoardFilter.looksLikeMeshcore("Meshtastic_ee01"))
        assertFalse(BoardFilter.looksLikeMeshcore("MeshCore"))
    }

    @Test
    fun kindOfTestsMeshcoreFirstAndTagsEachKind() {
        assertEquals(BoardKind.MESHCORE, BoardFilter.kindOf("MeshCore-Knit abcd", classicOnly = false) { true })
        assertEquals(BoardKind.MESHTASTIC, BoardFilter.kindOf("Meshtastic_ee01", classicOnly = false) { false })
        assertEquals(BoardKind.MESHTASTIC, BoardFilter.kindOf("Base camp", classicOnly = false) { true })
        assertNull(BoardFilter.kindOf("Pixel Buds", classicOnly = false) { false })
        assertNull("a Classic-only device is never a board", BoardFilter.kindOf("MeshCore-x", classicOnly = true) { true })
    }

    @Test
    fun theNusUuidAloneNeverMakesABoardMeshcore() {
        // The directory reads only the Meshtastic service from the cache; a generic NUS peripheral with any other name is
        // left to the Meshtastic rules, which do not take it either.
        assertNull(BoardFilter.kindOf("Bike computer", classicOnly = false) { false })
    }

    @Test
    fun aMeshcoreNameNeverReadsTheCachedServices() {
        var reads = 0
        BoardFilter.kindOf("MeshCore-abcd", classicOnly = false) { reads++ > 0 }
        assertEquals(0, reads)
    }

    @Test
    fun aMeshcoreBoardIsListedAndTaggedButAMeshtasticListingIsUnchanged() {
        val meshtastic = BoardRef("AA:BB:CC:DD:EE:01", "Meshtastic_ee01")
        val meshcore = BoardRef("AA:BB:CC:DD:EE:03", "MeshCore-Knit abcd", BoardKind.MESHCORE)
        val buds = BoardRef("11:22:33:44:55:66", "Pixel Buds", meshtastic = false)
        val all = listOf(meshtastic, meshcore, buds)
        assertEquals(listOf(meshtastic, meshcore), BoardFilter.visible(all, boundAddress = null, showAll = false))
        assertEquals(1, BoardFilter.hidden(all, boundAddress = null))
        assertTrue(meshtastic.meshtastic)
        assertFalse("a MeshCore entry is never a Meshtastic one", meshcore.meshtastic)
        assertEquals(BoardKind.MESHTASTIC, meshtastic.kind)
        assertNull(buds.kind)
    }
}
