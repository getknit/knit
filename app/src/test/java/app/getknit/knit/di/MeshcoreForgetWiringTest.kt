package app.getknit.knit.di

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import app.getknit.knit.data.settings.MeshcoreClaimStore
import app.getknit.knit.data.settings.SettingsStore
import app.getknit.knit.mesh.lora.BoardAddr
import app.getknit.knit.mesh.lora.BoardKind
import app.getknit.knit.mesh.lora.Ceiling
import app.getknit.knit.mesh.lora.Destination
import app.getknit.knit.mesh.lora.LoraAirtime
import app.getknit.knit.mesh.lora.LoraConfig
import app.getknit.knit.mesh.lora.PlaneCore
import app.getknit.knit.mesh.lora.meshcore.FakeMeshcoreAir
import app.getknit.knit.mesh.lora.meshcore.FakeMeshcoreDialer
import app.getknit.knit.mesh.lora.meshcore.MemoryMeshcoreSetupJournal
import app.getknit.knit.mesh.lora.meshcore.MeshcoreForgetResult
import app.getknit.knit.mesh.lora.meshcore.MeshcoreHooks
import app.getknit.knit.mesh.lora.meshcore.MeshcoreSession
import app.getknit.knit.mesh.lora.meshcore.MeshcoreSetupRecord
import app.getknit.knit.mesh.lora.meshcore.MeshcoreTraits
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The Forget with the plane never started (security-reviewer, Stage 7a): a MeshCore session nobody ever started — its
 * `boundAddress` null, so its unbind publishes no withdrawal — and settings that still hold the binding and a published claim,
 * wired as the app wires it ([meshcoreForget]). The Forget's own edit is what removes the claim: PENDING keeps the binding for
 * the record's cleanup, CLEARED (no record) drops it, and either way no `meshcoreKey` claim is left.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MeshcoreForgetWiringTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private class Core : PlaneCore {
        override val airtime = LoraAirtime(traits = MeshcoreTraits)
        override val config: LoraConfig? = null
        override val maxPayload = 0
        override val restored = true

        override fun enqueue(
            parts: List<ByteArray>,
            label: String,
            ceiling: Ceiling,
            destination: Destination,
            to: BoardAddr?,
        ): Boolean = false

        override fun enqueuePublic(
            text: ByteArray,
            label: String,
            ceiling: Ceiling,
            timestampSecs: Long,
            name: app.getknit.knit.mesh.lora.meshcore.MeshcoreHandle,
            traceId: String?,
        ): Boolean = false

        override fun limitersMoved() = Unit

        override fun onBoardKeyHeard(from: BoardAddr) = Unit
    }

    private class Rig(
        val settings: SettingsStore,
        val claims: MeshcoreClaimStore,
        val journal: MemoryMeshcoreSetupJournal,
        val forget: suspend () -> MeshcoreForgetResult,
    )

    private suspend fun TestScope.rig(): Rig {
        val dataStore = PreferenceDataStoreFactory.create(scope = backgroundScope) { File(tmp.root, "s.preferences_pb") }
        val settings = SettingsStore(dataStore)
        val claims = MeshcoreClaimStore(dataStore)
        settings.bindBoard(BoardKind.MESHCORE, ADDRESS, name = "")
        settings.setLoraEnabled(true)
        claims.setBoardKey("a-published-key", "its-bind")
        val journal = MemoryMeshcoreSetupJournal()
        val air = FakeMeshcoreAir(backgroundScope) { testScheduler.currentTime }
        // Never started: the plane is off, nothing dialled, no consent ever published.
        val session =
            MeshcoreSession(
                dialer = FakeMeshcoreDialer(air.board("alice")),
                scope = backgroundScope,
                now = { testScheduler.currentTime },
                wallClock = { testScheduler.currentTime },
                records = journal,
            )
        // The claim writer as `di/MeshModule` wires it: with no configured address it writes nothing.
        val hooks =
            MeshcoreHooks(session, Core(), backgroundScope, { testScheduler.currentTime }, {}, onBoardKey = { key, sig, dialled ->
                bindingAddress(dialled, null)?.let { claims.setBoardKey(key?.toString(), sig?.toString(), dialled = it) }
            })
        val forget = meshcoreForget(backgroundScope, settings, hooks = { hooks }, log = {})
        return Rig(settings, claims, journal, { forget.forget() })
    }

    @Test
    fun aForgetOfABoardWithARecordKeepsTheBindingAndLeavesNoClaim() =
        runTest {
            val r = rig()
            r.journal.put(ADDRESS, "0011223344556677", RECORD, pending = false)
            assertEquals(MeshcoreForgetResult.PENDING, r.forget())
            assertNull("no claim is published", r.claims.boardKey.first())
            assertEquals("the binding is kept for the cleanup", ADDRESS, r.settings.meshcoreDeviceAddress.first())
            assertTrue("the record waits for its cleanup", r.journal.entryOf(ADDRESS)?.pending == true)
        }

    @Test
    fun aForgetOfABoardWithNoRecordClearsTheBindingAndLeavesNoClaim() =
        runTest {
            val r = rig()
            assertEquals(MeshcoreForgetResult.CLEARED, r.forget())
            assertNull(r.claims.boardKey.first())
            assertNull(r.settings.meshcoreDeviceAddress.first())
            assertEquals(false, r.settings.loraEnabled.first())
        }

    private companion object {
        const val ADDRESS = "CC:CC"
        val RECORD = MeshcoreSetupRecord(1, slotWritten = true, priorName = null, priorManualAdd = null, priorAutoadd = null)
    }
}
