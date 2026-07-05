package app.getknit.knit.ui.diagnostics

import app.getknit.knit.data.AttachmentStore
import app.getknit.knit.mesh.MeshController
import app.getknit.knit.mesh.MeshMetrics
import app.getknit.knit.mesh.Peer
import app.getknit.knit.mesh.TransportHealth
import app.getknit.knit.mesh.TransportKind
import app.getknit.knit.mesh.TransportStatus
import app.getknit.knit.mesh.protocol.GroupInfo
import app.getknit.knit.mesh.protocol.Mention
import app.getknit.knit.mesh.protocol.ReplyRef
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Demonstrates finding #15's payoff: with the mesh behind [MeshController], a ViewModel is now testable
 * against a hand-written fake instead of the concrete, un-constructable `MeshManager`. Verifies the
 * Diagnostics actions route to the controller.
 */
class DiagnosticsViewModelTest {
    /** Records the actions the ViewModel invokes; exposes the read flows the VM folds into its state. */
    private class FakeMeshController : MeshController {
        override val neighborCount = MutableStateFlow(0)
        override val neighbors = MutableStateFlow<Set<Peer>>(emptySet())
        override val transportHealth = MutableStateFlow(TransportHealth.Healthy)
        override val transportStatuses = MutableStateFlow<List<TransportStatus>>(emptyList())
        override val peerTransports = MutableStateFlow<Map<String, Set<TransportKind>>>(emptyMap())
        override val typing = MutableStateFlow<Map<String, Set<String>>>(emptyMap())

        var startCount = 0
        var stopCount = 0
        var healCount = 0
        var restartCount = 0
        val sentChats = mutableListOf<String>()

        override fun start() {
            startCount++
        }

        override fun stop() {
            stopCount++
        }

        override fun heal() {
            healCount++
        }

        override fun restart() {
            restartCount++
        }

        override suspend fun sendChat(
            text: String,
            attachment: AttachmentStore.Ingested?,
            mentions: List<Mention>,
            recipientId: String?,
            group: GroupInfo?,
            replyTo: ReplyRef?,
        ): Boolean {
            sentChats += text
            return true
        }

        override suspend fun sendGroupUpdate(group: GroupInfo) = Unit

        override suspend fun sendGroupLeave(groupId: String) = Unit

        override suspend fun sendReaction(
            messageId: String,
            emoji: String,
        ) = Unit

        override suspend fun sendTyping(conversationId: String) = Unit
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun rescanAndRestartRouteToTheController() =
        runTest {
            val controller = FakeMeshController()
            val vm =
                DiagnosticsViewModel(
                    peers = mockk(relaxed = true),
                    meshManager = controller,
                    identity = mockk(relaxed = true),
                    settings = mockk(relaxed = true),
                    metrics = MeshMetrics(),
                )

            vm.rescan()
            vm.restartMesh()

            assertEquals(1, controller.healCount)
            assertEquals(1, controller.restartCount)
        }
}
