@file:OptIn(ExperimentalCoroutinesApi::class) // UnconfinedTestDispatcher / setMain / advanceTimeBy are experimental kotlinx APIs

package app.getknit.knit.ui.devview

import app.getknit.knit.data.BlobRepository
import app.getknit.knit.data.MessageReceiptRepository
import app.getknit.knit.data.MessageRepository
import app.getknit.knit.data.blob.AttachmentHolderDao
import app.getknit.knit.data.blob.BlobVerdictDao
import app.getknit.knit.data.forward.ForwardDao
import app.getknit.knit.data.forward.ForwardEntity
import app.getknit.knit.data.message.MessageEntity
import app.getknit.knit.data.receipt.MessageReceiptEntity
import app.getknit.knit.data.settings.SettingsStore
import app.getknit.knit.identity.IdentitySource
import app.getknit.knit.mesh.ArrivingFile
import app.getknit.knit.mesh.FakeMeshController
import app.getknit.knit.mesh.ForwardStore
import app.getknit.knit.mesh.protocol.FrameType
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * The inspector's reads (ADR 2026-10.s7wg): custody and the attachment's facts re-read on the tick, and the links polled
 * for the attachment's progress only while its bytes are not here, as the chat does.
 *
 * `runCurrent`, never `advanceUntilIdle`: the tick is periodic, so idle never comes.
 */
class MessageInspectViewModelTest {
    private val row = MutableStateFlow<MessageEntity?>(null)
    private val acks = MutableStateFlow(emptyList<MessageReceiptEntity>())
    private var custody: ForwardEntity? = null
    private var held: Map<String, Long> = emptyMap()
    private val mesh = FakeMeshController()

    private val messages = mockk<MessageRepository> { every { observeMessage(ID) } returns row }
    private val receipts = mockk<MessageReceiptRepository> { every { observeForMessage(ID) } returns acks }
    private val forward =
        mockk<ForwardDao> {
            coEvery { liveRow(ID, any()) } answers { custody }
            coEvery { countByAttachmentHash(any()) } returns 0
        }
    private val blobs = mockk<BlobRepository> { coEvery { sizes(any()) } answers { held } }
    private val verdicts = mockk<BlobVerdictDao> { coEvery { find(any()) } returns null }
    private val holders = mockk<AttachmentHolderDao> { coEvery { holdersOf(any()) } returns emptyList() }
    private val settings = mockk<SettingsStore> { every { contentFilteringEnabled } returns MutableStateFlow(true) }
    private val identity =
        object : IdentitySource {
            override suspend fun nodeId(): String = ME

            override fun publicKeyBundle(): String = ""
        }

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun TestScope.vm(): MessageInspectViewModel {
        val vm =
            MessageInspectViewModel(
                ID,
                messages,
                receipts,
                forward,
                blobs,
                verdicts,
                holders,
                mesh,
                settings,
                identity,
                clock = { BASE + testScheduler.currentTime },
            )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.groups.collect {} }
        runCurrent()
        return vm
    }

    private fun MessageInspectViewModel.value(
        group: String,
        key: String,
    ): String? =
        groups.value
            .firstOrNull { it.title == group }
            ?.rows
            ?.firstOrNull { it.key == key }
            ?.value

    @Test
    fun theLinksArePolledForAnAttachmentOnlyUntilItsBytesAreHere() =
        runTest {
            row.value = message(attachmentHash = HASH)
            val vm = vm()
            assertEquals("the bytes are missing, so the links are watched", 1, mesh.arrivals.subscriptionCount.value)

            mesh.arrivals.value = mapOf(HASH to ArrivingFile(HASH, 512, 2_048))
            runCurrent()
            assertEquals("512 / 2048 B (25 %)", vm.value("attachment", "arriving"))

            // The bytes land: the next tick reads them, and the links are let go.
            held = mapOf(HASH to 2_048L)
            advanceTimeBy(TICK_MS)
            runCurrent()
            assertEquals("2048 B (2.0 KiB)", vm.value("attachment", "blobs (held here)"))
            assertEquals(InspectFormat.ABSENT, vm.value("attachment", "arriving"))
            assertEquals(0, mesh.arrivals.subscriptionCount.value)
        }

    @Test
    fun aMessageWithNoAttachmentNeverWatchesTheLinks() =
        runTest {
            row.value = message()
            val vm = vm()
            // The barrier: the rows were built, so the two negatives below read a settled pipeline.
            assertEquals(ID, vm.value("identity", "messages.id"))

            assertEquals(0, mesh.arrivals.subscriptionCount.value)
            assertEquals(null, vm.value("attachment", "arriving"))
        }

    @Test
    fun custodyIsReReadOnTheTickAndOurOwnSendIsToldApart() =
        runTest {
            row.value = message().copy(senderId = ME, recipientId = PEER)
            val vm = vm()
            assertEquals("not held (live)", vm.value("custody", "forward_store"))
            assertEquals("our own send, no receipt yet", InspectFormat.ABSENT, vm.value("delivery", "receivedVia"))

            custody = custodyRow()
            advanceTimeBy(TICK_MS - 1)
            runCurrent()
            assertEquals("not before the tick", "not held (live)", vm.value("custody", "forward_store"))

            advanceTimeBy(1)
            runCurrent()
            assertEquals("self", vm.value("custody", "origin"))
        }

    @Test
    fun aRowThatIsGoneLeavesNoRows() =
        runTest {
            val vm = vm()
            assertEquals(emptyList<InspectGroup>(), vm.groups.value)

            row.value = message()
            runCurrent()
            assertEquals(ID, vm.value("identity", "messages.id"))

            row.value = null
            runCurrent()
            assertEquals(emptyList<InspectGroup>(), vm.groups.value)
        }

    private fun message(attachmentHash: String? = null) =
        MessageEntity(
            id = ID,
            senderId = PEER,
            recipientId = ME,
            conversationId = PEER,
            body = "hello",
            sentAt = BASE - 60_000L,
            attachmentHash = attachmentHash,
        )

    private fun custodyRow() =
        ForwardEntity(
            id = ID,
            recipientId = PEER,
            groupId = null,
            senderId = ME,
            type = FrameType.CHAT,
            origin = ForwardStore.ORIGIN_SELF,
            signed = byteArrayOf(1, 2, 3),
            sig = ByteArray(64),
            sentAt = BASE - 60_000L,
            receivedAt = BASE - 60_000L,
            expiresAt = BASE + 3_600_000L,
        )

    private companion object {
        const val BASE = 1_700_000_000_000L
        const val TICK_MS = 2_000L
        const val ID = "0b4f9c2e7a1d4e6b8c3f5a2d"
        const val ME = "me5fdtq2wvzk4hn7cxgae3bjrm"
        const val PEER = "samr1vz2kq7dw4mhgc5tyxpe3n"
        const val HASH = "9f2c1e6a7b3d4c5e8f0a1b2c3d4e5f60718293a4b5c6d7e8f9a0b1c2d3e4f5a6"
    }
}
