package app.getknit.knit.di

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import app.getknit.knit.data.peer.PeerEntity
import app.getknit.knit.data.settings.BoardKeys
import app.getknit.knit.data.settings.MeshcoreClaimStore
import app.getknit.knit.data.settings.SettingsStore
import app.getknit.knit.identity.NodeId
import app.getknit.knit.mesh.LoraDoorClaim
import app.getknit.knit.mesh.MeshcoreBoardReport
import app.getknit.knit.mesh.MeshcorePost
import app.getknit.knit.mesh.PublicChannelAnswer
import app.getknit.knit.mesh.PublicPostEnd
import app.getknit.knit.mesh.PublicPostRefusal
import app.getknit.knit.mesh.crypto.MeshcoreBindFixture
import app.getknit.knit.mesh.crypto.b64
import app.getknit.knit.mesh.lora.BoardKind
import app.getknit.knit.mesh.lora.PublicPostEnded
import app.getknit.knit.mesh.lora.PublicPostKey
import app.getknit.knit.mesh.lora.PublicPostResult
import app.getknit.knit.mesh.lora.meshcore.MeshcoreHandle
import app.getknit.knit.mesh.lora.meshcore.MeshcorePublicPost
import app.getknit.knit.mesh.protocol.FrameId
import app.getknit.knit.mesh.protocol.Protocol
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The MeshCore plane's wiring (ADR 2026-11.gsqu, Stage 5a-2), as pure rules the module reads: which kinds a build
 * constructs (a release build, `MESHCORE_PLANE` off, builds no MeshCore child), the address a board's late report is
 * written under (the `dialled` race), and the door claim an offer is gated on per kind.
 */
class LoraPlaneWiringTest {
    @get:Rule
    val tmp = TemporaryFolder()

    /**
     * Stage 5d (the crypto review's no-rewrite rule): a stored signature that already proves (our node, the key) is kept,
     * whatever a re-sign answered, so a signer that were not deterministic could never mint a sealed version per reconnect;
     * anything short of that proof — another key, no proof stored, one that fails, no node id — takes the new signature.
     */
    @Test
    fun aStoredProofForTheSameKeyIsNeverRewritten() {
        val node = NodeId.derive("our phone")
        val other = NodeId.derive("someone else")
        val key = MeshcoreBindFixture.KEY
        assertTrue("proven already: kept", meshcoreProofKept(node, key, MeshcoreBindFixture.sig(node)))
        assertFalse("a stored signature that fails", meshcoreProofKept(node, key, MeshcoreBindFixture.sig(other)))
        val otherBoard = MeshcoreBindFixture.hex("4ccd089b28ff96da9db6c346ec114e0f5b8a319f35aba624da8cf6ed4fb8a6fb")
        assertFalse(
            "another board's key, a valid point, under this board's proof",
            meshcoreProofKept(node, b64(MeshcoreBindFixture.keyOf(otherBoard)), MeshcoreBindFixture.sig(node)),
        )
    }

    /**
     * The keep-check is asked inside the store's edit, never of a read before it: a signature another writer clears just
     * before the edit is written again by a consent that carries a valid one. The wrapped store clears it once, at whichever
     * comes first: a read of `data` (which still shows it, so a read outside the edit sees the stored proof and writes
     * nothing, or no signature) or the next update. Either way a check made of a read leaves the key unproven.
     */
    @Test
    fun aSignatureClearedJustBeforeTheEditIsWrittenAgain() =
        runTest {
            val inner = PreferenceDataStoreFactory.create(scope = backgroundScope) { File(tmp.root, "settings.preferences_pb") }
            var clearNext = false
            val dataStore =
                object : DataStore<Preferences> {
                    // A read before the edit sees the signature, then loses it in the store: so a check made of that read,
                    // early return or not, leaves the key unproven.
                    override val data: Flow<Preferences> =
                        inner.data.map { p ->
                            if (clearNext) {
                                clearNext = false
                                inner.updateData { it.toMutablePreferences().apply { remove(BoardKeys.MESHCORE_BIND) } }
                            }
                            p
                        }

                    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
                        inner.updateData { prefs ->
                            val seen =
                                if (clearNext) {
                                    clearNext = false
                                    prefs.toMutablePreferences().apply { remove(BoardKeys.MESHCORE_BIND) }.toPreferences()
                                } else {
                                    prefs
                                }
                            transform(seen)
                        }
                }
            SettingsStore(dataStore).bindBoard(BoardKind.MESHCORE, "BB:BB", name = "")
            val claims = MeshcoreClaimStore(dataStore)
            val node = NodeId.derive("our phone")
            val key = MeshcoreBindFixture.keyOf()
            writeMeshcoreClaim(claims, { node }, key, MeshcoreBindFixture.sigBytes(node), dialled = "BB:BB")
            assertEquals(MeshcoreBindFixture.sig(node), claims.board.first().sig)
            clearNext = true
            writeMeshcoreClaim(claims, { node }, key, MeshcoreBindFixture.sigBytes(node), dialled = "BB:BB")
            assertEquals(MeshcoreBoardReport(MeshcoreBindFixture.KEY, MeshcoreBindFixture.sig(node)), claims.board.first())
        }

    @Test
    fun aConsentsClaimLandsAsOneEditAndAReSignLeavesTheStoredProof() =
        runTest {
            val dataStore = PreferenceDataStoreFactory.create(scope = backgroundScope) { File(tmp.root, "settings.preferences_pb") }
            SettingsStore(dataStore).bindBoard(BoardKind.MESHCORE, "BB:BB", name = "")
            val claims = MeshcoreClaimStore(dataStore)
            val node = NodeId.derive("our phone")
            val key = MeshcoreBindFixture.keyOf()
            val proof = MeshcoreBindFixture.sigBytes(node)
            writeMeshcoreClaim(claims, { node }, key, proof, dialled = "BB:BB")
            assertEquals(MeshcoreBoardReport(MeshcoreBindFixture.KEY, MeshcoreBindFixture.sig(node)), claims.board.first())
            // Bytes that do not prove this node: the stored proof stands, nothing written.
            writeMeshcoreClaim(claims, { node }, key, MeshcoreBindFixture.sigBytes(NodeId.derive("other")), dialled = "BB:BB")
            assertEquals(MeshcoreBindFixture.sig(node), claims.board.first().sig)
            // A consent without a signature keeps it too; a withdrawal forgets both.
            writeMeshcoreClaim(claims, { node }, key, null, dialled = "BB:BB")
            assertEquals(MeshcoreBindFixture.sig(node), claims.board.first().sig)
            // No node id: no keep-check at all, so the new signature is written as given.
            writeMeshcoreClaim(claims, { null }, key, MeshcoreBindFixture.sigBytes(NodeId.derive("other")), dialled = "BB:BB")
            assertEquals(MeshcoreBindFixture.sig(NodeId.derive("other")), claims.board.first().sig)
            writeMeshcoreClaim(claims, { node }, null, null, dialled = "BB:BB")
            assertEquals(MeshcoreBoardReport(null, null), claims.board.first())
        }

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

    /**
     * The MeshCore room's seam (ADR 2026-11.gsqu's Stage 6 amendment, security N13): a post goes to the MeshCore plane only
     * while a MeshCore board is the bound one — a room whose board is not bound refuses as no board, and the plane, built
     * and willing, is never asked. Bound, the plane's queued key comes back as the row id its receivers key the post on.
     */
    @Test
    fun aMeshcorePostReachesNoPlaneUnlessAMeshcoreBoardIsBound() =
        runTest {
            val key = PublicPostKey(1_763_200_000, "Knit 1a2b: hi".encodeToByteArray())
            val asked = mutableListOf<String>()
            val consents = mutableListOf<MeshcoreHandle?>()
            val handle = MeshcoreHandle("Wren".encodeToByteArray())
            val plane: suspend (String, MeshcoreHandle?) -> PublicPostResult = { body, consented ->
                asked += body
                consents += consented
                PublicPostResult.Queued(key)
            }
            for (bound in listOf(null, BoardKind.MESHTASTIC)) {
                assertEquals(
                    "$bound",
                    PublicChannelAnswer.Refused(PublicPostRefusal.NO_BOARD),
                    postToMeshcorePublic(bound, postPublic = plane, body = "hi", consented = handle),
                )
            }
            assertEquals("a plane asked while another kind's board is bound", emptyList<String>(), asked)
            assertEquals(
                "no plane built",
                PublicChannelAnswer.Refused(PublicPostRefusal.NO_BOARD),
                postToMeshcorePublic(BoardKind.MESHCORE, postPublic = null, body = "hi", consented = handle),
            )

            assertEquals(
                PublicChannelAnswer.Queued(meshcoreRowId(key)),
                postToMeshcorePublic(BoardKind.MESHCORE, postPublic = plane, body = "hi", consented = handle),
            )
            assertEquals("asked once, with the body", listOf("hi"), asked)
            assertEquals("and the name the consent saw (ADR 2026-11.f4d3)", listOf<MeshcoreHandle?>(handle), consents)
        }

    /** Both sides of one post meet on one row: a heard post's id and our own's are [FrameId.forMeshcorePost] over its key. */
    @Test
    fun aMeshcorePostCrossesTheSeamUnderItsKeysRowId() =
        runTest {
            val key = PublicPostKey(1_763_200_000, "Knit 1a2b: hi".encodeToByteArray())
            assertEquals(FrameId.forMeshcorePost(1_763_200_000, "Knit 1a2b: hi".encodeToByteArray()), meshcoreRowId(key))

            val heard = meshcoreHeard(MeshcorePublicPost(name = "Knit 1a2b", body = "hi", key = key, live = true))
            assertEquals(MeshcorePost("Knit 1a2b", "hi", 1_763_200_000, "Knit 1a2b: hi".encodeToByteArray()), heard)

            val ends =
                meshcorePostEnds(
                    flowOf(
                        PublicPostEnded(key, PublicPostEnded.Reason.STALE),
                        PublicPostEnded(key, PublicPostEnded.Reason.ENDED_AT_WRITE),
                        // Refused before the board took it (a name changed or unpostable, ADR 2026-11.f4d3): nothing aired.
                        PublicPostEnded(key, PublicPostEnded.Reason.REFUSED_AT_WRITE),
                    ),
                ).toList()
            assertEquals(
                listOf(
                    PublicPostEnd(meshcoreRowId(key), neverSent = true),
                    PublicPostEnd(meshcoreRowId(key), neverSent = false),
                    PublicPostEnd(meshcoreRowId(key), neverSent = true),
                ),
                ends,
            )
        }

    /** What the MeshCore room's consent sheet names: the board's own `Knit xxxx`, off the claimed key; nothing off no key. */
    @Test
    fun theMeshcoreBoardIsNamedOffItsClaimedKey() {
        // RFC 8032's first test key, d75a9801…: the name is its first two bytes in lower-case hex.
        assertEquals("Knit d75a", meshcoreBoardName(MeshcoreBindFixture.KEY))
        assertNull(meshcoreBoardName(null))
        assertNull(meshcoreBoardName("not a key"))
    }
}
