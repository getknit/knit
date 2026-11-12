@file:Suppress("MagicNumber") // times are the test's subject

package app.getknit.knit.usbcontrol

import androidx.test.core.app.ApplicationProvider
import app.getknit.knit.contacts.DirectContacts
import app.getknit.knit.data.GroupRepository
import app.getknit.knit.data.LocalMessageDelete
import app.getknit.knit.data.MessageRepository
import app.getknit.knit.data.PeerRepository
import app.getknit.knit.data.PinRepository
import app.getknit.knit.data.ReactionRepository
import app.getknit.knit.data.RoomDbTest
import app.getknit.knit.data.commons.CommonsRepository
import app.getknit.knit.data.draft.DraftRepository
import app.getknit.knit.data.message.Conversations
import app.getknit.knit.data.message.MessageEntity
import app.getknit.knit.data.peer.PeerEntity
import app.getknit.knit.data.ratchet.GroupRatchetRepository
import app.getknit.knit.data.settings.InboundSettings
import app.getknit.knit.data.settings.SettingsStore
import app.getknit.knit.identity.Identity
import app.getknit.knit.mesh.FakeMeshController
import app.getknit.knit.mesh.MeshController
import app.getknit.knit.mesh.lora.LoraFacts
import app.getknit.knit.mesh.lora.LoraStatusRepository
import app.getknit.knit.mesh.protocol.Mention
import app.getknit.knit.notifications.Notifier
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module

/**
 * The refusals a hostile host meets, against the real repositories over Room: every one that guards something the
 * phone's own screens never offer (a quote of another thread, a span past the text, declining an accepted thread,
 * blocking this phone). Each checks that the refused write did not happen.
 */
class AppUsbControlApiTest : RoomDbTest() {
    private val mesh = FakeMeshController()
    private val blocked = MutableStateFlow(emptySet<String>())
    private val accepted = MutableStateFlow(emptySet<String>())
    private val lastRead = MutableStateFlow(emptyMap<String, Long>())
    private val settings =
        mockk<SettingsStore>(relaxed = true) {
            every { blockedNodeIds } returns blocked
            every { acceptedConversations } returns accepted
            every { lastReadAll } returns lastRead
            every { displayName } returns flowOf("Me")
            every { ownAvatarHash } returns flowOf(null)
        }
    private val notifier = mockk<Notifier>(relaxed = true)
    private val drafts = mockk<DraftRepository>(relaxed = true)
    private val pins = mockk<PinRepository>(relaxed = true)
    private val localDelete = mockk<LocalMessageDelete>(relaxed = true)
    private lateinit var messages: MessageRepository
    private lateinit var api: AppUsbControlApi

    @Before
    fun startGraph() {
        val identity = mockk<Identity> { coEvery { nodeId() } returns ME }
        val profile = mockk<InboundSettings>(relaxed = true) { every { displayName } returns flowOf("Me") }
        messages = MessageRepository(db.messageDao())
        val peers = PeerRepository(db.peerDao(), profile, identity)
        val groups = GroupRepository(db.groupDao(), messages, db, GroupRatchetRepository(db.groupRatchetDao(), clock = { 1L }))
        startKoin {
            androidContext(ApplicationProvider.getApplicationContext())
            modules(
                module {
                    single<MeshController> { mesh }
                    single { identity }
                    single { settings }
                    single { messages }
                    single { peers }
                    single { groups }
                    single { ReactionRepository(db.reactionDao(), db) }
                    single {
                        mockk<CommonsRepository> {
                            every { observeAll() } returns flowOf(emptyList())
                            coEvery { find(any()) } returns null
                        }
                    }
                    single { mockk<LoraStatusRepository> { every { facts } returns flowOf(LoraFacts()) } }
                    single { mockk<DirectContacts> { coEvery { read() } returns setOf(BOB) } }
                    single { notifier }
                    single { localDelete }
                    single { drafts }
                    single { pins }
                },
            )
        }
        api = AppUsbControlApi()
    }

    @After
    fun stopGraph() {
        stopKoin()
    }

    private suspend fun row(
        id: String,
        conversation: String = BOB,
        sender: String = BOB,
        sentAt: Long = 10L,
        kind: Int = MessageEntity.KIND_NORMAL,
    ) = messages.save(
        MessageEntity(
            id = id,
            senderId = sender,
            conversationId = conversation,
            body = "hi",
            sentAt = sentAt,
            received = false,
            kind = kind,
        ),
    )

    private suspend fun refused(block: suspend () -> Unit): String {
        val thrown = runCatching { block() }.exceptionOrNull()
        assertTrue("expected a refusal, got $thrown", thrown is UsbControlError)
        return (thrown as UsbControlError).code
    }

    @Test
    fun aQuoteMustBeSomebodysWordsInThisThreadFromSomeoneNotBlocked() =
        runTest {
            row("elsewhere", conversation = CY, sender = CY)
            row("notice", kind = MessageEntity.KIND_PEER_RENAMED)
            row("mallory", conversation = Conversations.NEARBY, sender = MALLORY)
            blocked.value = setOf(MALLORY)
            assertEquals(Codes.BAD_REQUEST, refused { api.send(SendRequest(BOB, "x", replyTo = "elsewhere")) })
            assertEquals(Codes.BAD_REQUEST, refused { api.send(SendRequest(BOB, "x", replyTo = "notice")) })
            assertEquals(Codes.NOT_FOUND, refused { api.send(SendRequest(Conversations.NEARBY, "x", replyTo = "mallory")) })
            assertEquals(Codes.NOT_FOUND, refused { api.send(SendRequest(BOB, "x", replyTo = "never")) })
            assertTrue("nothing was sent", mesh.sentChats.isEmpty())

            row("quoted")
            val reply = api.send(SendRequest(BOB, "yes", replyTo = "quoted"))
            val sent = mesh.sentChats.single()
            assertEquals("quoted", sent.replyTo?.messageId)
            assertEquals(BOB, sent.recipientId)
            assertEquals(
                "the id the reply answers is the one the row was sent under",
                sent.frameId,
                reply["messageId"]?.jsonPrimitive?.content,
            )
            assertNotNull(sent.frameId)
        }

    @Test
    fun aStyleSpanMustLieInsideTheTextAndNameAKnownStyle() =
        runTest {
            assertEquals(Codes.BAD_REQUEST, refused { api.send(SendRequest(BOB, "abc", styles = listOf(listOf(1, 3, 0)))) })
            assertEquals(Codes.BAD_REQUEST, refused { api.send(SendRequest(BOB, "abc", styles = listOf(listOf(-1, 1, 0)))) })
            assertEquals(Codes.BAD_REQUEST, refused { api.send(SendRequest(BOB, "abc", styles = listOf(listOf(0, 0, 0)))) })
            assertEquals(Codes.BAD_REQUEST, refused { api.send(SendRequest(BOB, "abc", styles = listOf(listOf(0, 1, 99)))) })
            assertTrue(mesh.sentChats.isEmpty())
            api.send(SendRequest(BOB, "abc", styles = listOf(listOf(0, 3, 0))))
            assertEquals(
                3,
                mesh.sentChats
                    .single()
                    .styles
                    .single()
                    .end,
            )
        }

    @Test
    fun aMentionIsThisPhonesLabelAndMustBeInTheText() =
        runTest {
            db.peerDao().upsert(PeerEntity(nodeId = BOB, name = "Bob"))
            assertEquals(Codes.BAD_REQUEST, refused { api.send(SendRequest(BOB, "hi @Robert", mentions = listOf(BOB))) })
            assertTrue(mesh.sentChats.isEmpty())
            api.send(SendRequest(BOB, "hi @Bob", mentions = listOf(BOB)))
            assertEquals(listOf(Mention(BOB, "Bob")), mesh.sentChats.single().mentions)
        }

    @Test
    fun theMeshtasticRoomTakesPlainTextAndNeitherThisPhoneNorAnUnjoinedCommonsTakesAnything() =
        runTest {
            row("m1", conversation = Conversations.MESHTASTIC)
            assertEquals(Codes.BAD_REQUEST, refused { api.send(SendRequest(Conversations.MESHTASTIC, "x", replyTo = "m1")) })
            assertEquals(Codes.BAD_REQUEST, refused { api.send(SendRequest(ME, "x")) })
            assertEquals(Codes.NOT_FOUND, refused { api.send(SendRequest(Conversations.COMMONS_PREFIX + "room", "x")) })
            assertTrue(mesh.sentChats.isEmpty())
            assertTrue(mesh.sentPublicPosts.isEmpty())
        }

    @Test
    fun onlyAPeerCanBeBlockedOrUnblocked() =
        runTest {
            for (node in listOf(ME, Conversations.NEARBY, "g-group")) {
                assertEquals(node, Codes.BAD_REQUEST, refused { api.block(node) })
                assertEquals(node, Codes.BAD_REQUEST, refused { api.unblock(node) })
            }
            coVerify(exactly = 0) { settings.block(any(), any()) }
            api.block(BOB)
            coVerify { settings.block(BOB, null) }
            coVerify { pins.unpinFrom(BOB) }
        }

    @Test
    fun onlyAMessageRequestCanBeAcceptedOrDeclined() =
        runTest {
            row("from-bob")
            row("from-cy", conversation = CY, sender = CY)
            accepted.value = setOf(BOB)
            assertEquals(Codes.NOT_PENDING, refused { api.decline(BOB) })
            assertEquals(Codes.NOT_PENDING, refused { api.accept(Conversations.NEARBY) })
            assertEquals(Codes.NOT_FOUND, refused { api.decline("nobody-at-all") })
            assertNotNull("the accepted thread was left whole", messages.find("from-bob"))

            api.decline(CY)
            assertEquals("the stranger's request is gone", null, messages.find("from-cy"))
            coVerify { drafts.clear(CY) }
        }

    @Test
    fun aReadWatermarkNeverGoesBackNorPastTheNewestMessage() =
        runTest {
            row("m1", sentAt = 100L)
            val ahead = api.markRead(BOB, upTo = 500L)
            assertEquals(100L, ahead.getValue("lastReadAt").jsonPrimitive.long)
            coVerify { settings.setLastReadAt(BOB, 100L) }
            coVerify { notifier.clearConversation(BOB) }

            lastRead.value = mapOf(BOB to 90L)
            assertEquals(
                90L,
                api
                    .markRead(BOB, upTo = 50L)
                    .getValue("lastReadAt")
                    .jsonPrimitive.long,
            )
            coVerify(exactly = 0) { settings.setLastReadAt(BOB, 50L) }
            assertEquals(Codes.NOT_FOUND, refused { api.markRead("nobody-at-all", null) })
        }

    @Test
    fun aBlockedSendersMessageCannotBeReactedTo() =
        runTest {
            row("mallory", conversation = Conversations.NEARBY, sender = MALLORY)
            blocked.value = setOf(MALLORY)
            assertEquals(Codes.NOT_FOUND, refused { api.react("mallory", "👍") })
            assertEquals(Codes.NOT_FOUND, refused { api.react("never", "👍") })
            assertTrue(mesh.sentReactions.isEmpty())
        }

    private companion object {
        const val ME = "me-node"
        const val BOB = "bob-node"
        const val CY = "cy-node"
        const val MALLORY = "mallory-node"
    }
}
