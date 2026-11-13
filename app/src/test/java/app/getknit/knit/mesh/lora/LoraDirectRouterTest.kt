package app.getknit.knit.mesh.lora

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * The compact DM's router (ADR 2026-11.gsqu, Stage 2): v1 sends through the bound kind's plane and Meshtastic's with none
 * bound, never a plane the bound kind does not have, and reads no board map or holder (it is handed none). With
 * [BoardKind] the one rule for which kind is bound.
 */
class LoraDirectRouterTest {
    private class Sink : LoraDirectSink {
        override fun offer(
            recipientId: String,
            frameId: String,
            sentAtMs: Long,
            plaintext: ByteArray,
            kind: DirectKind,
        ) = Unit
    }

    private val meshtastic = Sink()
    private val meshcore = Sink()

    @Test
    fun withNoKindBoundItIsMeshtasticsPlaneTodaysAnswer() =
        runTest {
            val router = LoraDirectRouter(mapOf(BoardKind.MESHTASTIC to { meshtastic }), boundKind = { null })
            assertSame(meshtastic, router.sinkFor("peer"))
        }

    @Test
    fun itIsTheBoundKindsPlaneWhateverThePeer() =
        runTest {
            var bound: BoardKind? = BoardKind.MESHCORE
            val router =
                LoraDirectRouter(
                    mapOf(BoardKind.MESHTASTIC to { meshtastic }, BoardKind.MESHCORE to { meshcore }),
                    boundKind = { bound },
                )
            assertSame(meshcore, router.sinkFor("a"))
            assertSame(meshcore, router.sinkFor("b"))
            bound = BoardKind.MESHTASTIC
            assertSame(meshtastic, router.sinkFor("a"))
        }

    /** A kind bound with no plane in this build carries nothing: a copy sized for that board never goes to another. */
    @Test
    fun aBoundKindWithNoPlaneHereAnswersNothing() =
        runTest {
            val router = LoraDirectRouter(mapOf(BoardKind.MESHTASTIC to { meshtastic }), boundKind = { BoardKind.MESHCORE })
            assertNull(router.sinkFor("peer"))
        }

    /** The plane is resolved at each ask, never at construction: the transport and the manager construct each other. */
    @Test
    fun thePlaneIsResolvedAtEachAsk() =
        runTest {
            var resolved = 0
            val router =
                LoraDirectRouter(
                    mapOf(BoardKind.MESHTASTIC to { meshtastic.also { resolved++ } }),
                    boundKind = { null },
                )
            assertEquals(0, resolved)
            router.sinkFor("peer")
            router.sinkFor("peer")
            assertEquals(2, resolved)
        }

    @Test
    fun theBoundKindIsTheOneWithAnAddressAndMeshtasticWinsATie() {
        assertNull(BoardKind.boundOf(meshtasticBound = false, meshcoreBound = false))
        assertEquals(BoardKind.MESHTASTIC, BoardKind.boundOf(meshtasticBound = true, meshcoreBound = false))
        assertEquals(BoardKind.MESHCORE, BoardKind.boundOf(meshtasticBound = false, meshcoreBound = true))
        assertEquals(BoardKind.MESHTASTIC, BoardKind.boundOf(meshtasticBound = true, meshcoreBound = true))
    }

    /** Meshtastic's field lines and stream predate the kind and stay as they were; another kind names itself. */
    @Test
    fun onlyAnotherKindTagsItsFieldLinesAndMeshtasticKeepsTheLoraStream() {
        assertNull(BoardKind.MESHTASTIC.fieldTag)
        assertEquals(BoardKind.MESHCORE, BoardKind.MESHCORE.fieldTag)
        assertEquals("lora", BoardKind.MESHTASTIC.fieldStream)
        assertEquals("meshcore", BoardKind.MESHCORE.fieldStream)
    }

    /** The UI's DM budget follows the bound kind's compact budget, and Meshtastic's still reads 160 of 168. */
    @Test
    fun theDmBodyBudgetFollowsTheKindsCompactBudget() {
        assertEquals(LoraDirectBudget(168, 9), LoraDirectBudget.MESHTASTIC)
        assertEquals(160, LoraSizeHint.dmBodyBytes(LoraDirectBudget.MESHTASTIC))
        assertEquals(97, LoraSizeHint.dmBodyBytes(LoraDirectBudget.forPlaintext(105)))
        assertEquals(LoraDirectBudget.MESHTASTIC, LoraPlaneStatus.Dark.directBudget)
        assertEquals(BoardKind.MESHTASTIC, LoraPlaneStatus.Dark.boardKind)
    }
}
