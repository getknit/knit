package app.getknit.knit.di

import app.getknit.knit.data.peer.PeerEntity
import app.getknit.knit.mesh.LoraDoorClaim
import app.getknit.knit.mesh.lora.BoardKind
import app.getknit.knit.mesh.protocol.Protocol
import kotlinx.coroutines.flow.toList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The MeshCore plane's wiring (ADR 2026-11.gsqu, Stage 5a-2), as pure rules the module reads: which kinds a build
 * constructs (a release build, `MESHCORE_PLANE` off, builds no MeshCore child), the address a board's late report is
 * written under (the `dialled` race), and the door claim an offer is gated on per kind.
 */
class LoraPlaneWiringTest {
    @Test
    fun aReleaseBuildBuildsNoMeshcoreChildAndADebugBuildBuildsBoth() {
        assertEquals("release: MESHCORE_PLANE off", listOf(BoardKind.MESHTASTIC), builtBoardKinds(loraPlane = true, meshcorePlane = false))
        assertEquals(listOf(BoardKind.MESHTASTIC, BoardKind.MESHCORE), builtBoardKinds(loraPlane = true, meshcorePlane = true))
        assertEquals(emptyList<BoardKind>(), builtBoardKinds(loraPlane = false, meshcorePlane = false))
        assertTrue(runCatching { builtBoardKinds(loraPlane = false, meshcorePlane = true) }.isFailure)
        // This build's own list is the rule over its flags (unit tests build the debug variant).
        assertEquals(
            builtBoardKinds(app.getknit.knit.BuildConfig.LORA_PLANE, app.getknit.knit.BuildConfig.MESHCORE_PLANE),
            BUILT_BOARD_KINDS,
        )
    }

    @Test
    fun aLateReadyIsWrittenUnderTheAddressItsSessionDialledNeverTheNewerBinding() {
        // A→B: A's session reports after the plane moved to B. The write goes under A, which the settings edit then refuses.
        assertEquals("AA:AA", bindingAddress(dialled = "AA:AA", configured = "BB:BB"))
        assertEquals("BB:BB", bindingAddress(dialled = "BB:BB", configured = "BB:BB"))
        assertNull("unbound: nothing written", bindingAddress(dialled = "AA:AA", configured = null))
        assertEquals("a session that did not say falls back", "BB:BB", bindingAddress(dialled = null, configured = "BB:BB"))
    }

    @Test
    fun anOfferReadsTheDoorClaimOfTheKindItsSinkDrives() {
        val bitOnly = PeerEntity(nodeId = "bob", capabilities = Protocol.CAP_LORA_DIRECT)
        val keyOnly = PeerEntity(nodeId = "bob", meshcoreKey = "a".repeat(43) + "=")
        assertTrue(LoraDoorClaim.claims(BoardKind.MESHTASTIC, bitOnly))
        assertFalse("the bit is Meshtastic's door, never MeshCore's", LoraDoorClaim.claims(BoardKind.MESHCORE, bitOnly))
        assertTrue(LoraDoorClaim.claims(BoardKind.MESHCORE, keyOnly))
        assertFalse(LoraDoorClaim.claims(BoardKind.MESHTASTIC, keyOnly))
        assertFalse(LoraDoorClaim.claims(BoardKind.MESHCORE, PeerEntity(nodeId = "carol")))
    }

    @Test
    fun aReleaseBuildBindsNoMeshcoreDoorAndADebugBuildBindsIt() {
        assertFalse("release: no MeshCore child, no seam", bindsMeshcoreDirect(builtBoardKinds(loraPlane = true, meshcorePlane = false)))
        assertFalse(bindsMeshcoreDirect(builtBoardKinds(loraPlane = false, meshcorePlane = false)))
        assertTrue(bindsMeshcoreDirect(builtBoardKinds(loraPlane = true, meshcorePlane = true)))
        assertEquals(app.getknit.knit.BuildConfig.MESHCORE_PLANE, bindsMeshcoreDirect(BUILT_BOARD_KINDS))
    }

    @Test
    fun theMeshcorePlanesFirstBoardMapIsTheStoresFirstNeverAnEmptyPlaceholder() =
        kotlinx.coroutines.test.runTest {
            val key =
                java.util.Base64.getEncoder().encodeToString(
                    com.google.crypto.tink.subtle.Ed25519Sign.KeyPair
                        .newKeyPair()
                        .publicKey,
                )
            val real =
                mapOf(
                    "bob" to
                        app.getknit.knit.mesh
                            .MeshcoreBoardHolder(key, app.getknit.knit.mesh.MeshcoreRank.ACCEPTED, 1, false),
                )
            val seen = meshcoreBoardHolders(kotlinx.coroutines.flow.flowOf(real)).toList()
            assertEquals("one emission, the real one", 1, seen.size)
            assertEquals(setOf("bob"), seen.single().keys)
            assertEquals(
                "a source that has not emitted gives nothing",
                emptyList<Any>(),
                meshcoreBoardHolders(kotlinx.coroutines.flow.emptyFlow()).toList(),
            )
        }
}
