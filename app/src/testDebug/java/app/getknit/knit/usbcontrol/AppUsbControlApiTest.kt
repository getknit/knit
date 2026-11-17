@file:Suppress("MagicNumber") // times are the test's subject

package app.getknit.knit.usbcontrol

import androidx.datastore.preferences.core.emptyPreferences
import androidx.test.core.app.ApplicationProvider
import app.getknit.knit.TextLimits
import app.getknit.knit.contacts.DirectContacts
import app.getknit.knit.data.AttachmentStore
import app.getknit.knit.data.BlobRepository
import app.getknit.knit.data.GroupRepository
import app.getknit.knit.data.LocalMessageDelete
import app.getknit.knit.data.MessageRepository
import app.getknit.knit.data.PeerRepository
import app.getknit.knit.data.PinRepository
import app.getknit.knit.data.ReactionRepository
import app.getknit.knit.data.RoomDbTest
import app.getknit.knit.data.commons.CommonsRepository
import app.getknit.knit.data.draft.DraftRepository
import app.getknit.knit.data.group.GroupEntity
import app.getknit.knit.data.group.GroupMembersStore
import app.getknit.knit.data.message.Conversations
import app.getknit.knit.data.message.MessageEntity
import app.getknit.knit.data.peer.PeerEntity
import app.getknit.knit.data.ratchet.GroupRatchetRepository
import app.getknit.knit.data.relay.RelayFacts
import app.getknit.knit.data.relay.RelayStatusRepository
import app.getknit.knit.data.settings.InboundSettings
import app.getknit.knit.data.settings.SettingsStore
import app.getknit.knit.identity.Identity
import app.getknit.knit.mesh.FakeMeshController
import app.getknit.knit.mesh.MeshController
import app.getknit.knit.mesh.crypto.AttachmentCrypto
import app.getknit.knit.mesh.lora.LoraFacts
import app.getknit.knit.mesh.lora.LoraStatusRepository
import app.getknit.knit.mesh.protocol.Mention
import app.getknit.knit.mesh.sha256Hex
import app.getknit.knit.notifications.Notifier
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.util.Base64

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
    private val status = MutableStateFlow("")
    private val typingOn = MutableStateFlow(true)
    private val settings =
        mockk<SettingsStore>(relaxed = true) {
            every { blockedNodeIds } returns blocked
            every { acceptedConversations } returns accepted
            every { lastReadAll } returns lastRead
            every { displayName } returns flowOf("Me")
            every { ownAvatarHash } returns flowOf(null)
            every { contentFilteringEnabled } returns flowOf(true)
            every { typingIndicatorsEnabled } returns typingOn
            every { readReceiptsEnabled } returns flowOf(true)
            every { this@mockk.status } returns this@AppUsbControlApiTest.status
            coEvery { setStatus(any()) } answers {
                this@AppUsbControlApiTest.status.value = firstArg()
                emptyPreferences()
            }
        }
    private val notifier = mockk<Notifier>(relaxed = true)
    private val drafts = mockk<DraftRepository>(relaxed = true)
    private val pins = mockk<PinRepository>(relaxed = true)
    private val localDelete = mockk<LocalMessageDelete>(relaxed = true)
    private lateinit var messages: MessageRepository
    private lateinit var blobs: BlobRepository
    private val attachments = mockk<AttachmentStore>(relaxed = true)
    private val relayFacts = MutableStateFlow(RelayFacts())
    private val commons =
        mockk<CommonsRepository> {
            every { observeAll() } returns flowOf(emptyList())
            coEvery { find(any()) } returns null
        }
    private lateinit var api: AppUsbControlApi

    @Before
    fun startGraph() {
        val identity = mockk<Identity> { coEvery { nodeId() } returns ME }
        val profile = mockk<InboundSettings>(relaxed = true) { every { displayName } returns flowOf("Me") }
        messages = MessageRepository(db.messageDao())
        val peers = PeerRepository(db.peerDao(), profile, identity)
        val groups = GroupRepository(db.groupDao(), messages, db, GroupRatchetRepository(db.groupRatchetDao(), clock = { 1L }))
        blobs =
            BlobRepository(
                db.blobDao(),
                db.messageDao(),
                db.peerDao(),
                settings,
                db.blobVerdictDao(),
                db.groupDao(),
                db.forwardDao(),
                db,
                db.savedFileDao(),
                db.attachmentHolderDao(),
            )
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
                    single { commons }
                    single { mockk<LoraStatusRepository> { every { facts } returns flowOf(LoraFacts()) } }
                    single { mockk<DirectContacts> { coEvery { read() } returns setOf(BOB) } }
                    single { notifier }
                    single { localDelete }
                    single { drafts }
                    single { pins }
                    single { blobs }
                    single { attachments }
                    single { db.blobVerdictDao() }
                    single { db.messageReceiptDao() }
                    single { mockk<RelayStatusRepository> { every { facts } returns relayFacts } }
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
            // This phone, the rooms, a group, and ids no node has: "meshtastic", one letter short, one outside base32.
            val notPeers =
                listOf(
                    ME,
                    Conversations.NEARBY,
                    Conversations.MESHTASTIC,
                    "g-group",
                    "meshtastic",
                    BOB.drop(1),
                    BOB.dropLast(1) + "1",
                )
            for (node in notPeers) {
                assertEquals(node, Codes.BAD_REQUEST, refused { api.block(node) })
                assertEquals(node, Codes.BAD_REQUEST, refused { api.unblock(node) })
            }
            coVerify(exactly = 0) { settings.block(any(), any()) }
            api.block(BOB)
            coVerify { settings.block(BOB, null) }
            coVerify { pins.unpinFrom(BOB) }
        }

    /** Our status as `self` states it. */
    private suspend fun shownStatus(): String {
        val self = api.identity()
        return self.getValue("status").jsonPrimitive.content
    }

    @Test
    fun ourStatusIsStoredAndShownAsTheProfileFrameCarriesIt() =
        runTest {
            api.setStatus(" out\u2028for\t lunch ")
            assertEquals("out for lunch", status.value)
            assertEquals("out for lunch", shownStatus())
            // A status stored by another path, unfolded and over the cap, is shown as the frame would carry it.
            status.value = "  ${"b".repeat(TextLimits.STATUS + 20)}"
            assertEquals("b".repeat(TextLimits.STATUS), shownStatus())
            api.setStatus("x".repeat(TextLimits.STATUS + 5))
            assertEquals("x".repeat(TextLimits.STATUS), status.value)
        }

    @Test
    fun aSendToAnIdThatIsNoNodeIdIsRefusedBeforeAnythingIsStored() =
        runTest {
            val picture = byteArrayOf(4, 4)
            // Short, uppercase, outside base32, and a non-canonical spelling of BOB (its last letter's padding bits set).
            for (target in listOf("meshtastic", BOB.drop(1), BOB.uppercase(), BOB.dropLast(1) + "0", BOB.dropLast(1) + "b")) {
                assertEquals(target, Codes.BAD_REQUEST, refused { api.send(SendRequest(target, "hi")) })
                assertEquals(target, Codes.BAD_REQUEST, refused { api.send(SendRequest(target, "", picture = picture)) })
            }
            coVerify(exactly = 0) { attachments.ingestPicked(any(), any(), any(), any()) }
            assertTrue(mesh.sentChats.isEmpty())
            api.send(SendRequest(BOB, "hi"))
            assertEquals("a node id still opens a DM", BOB, mesh.sentChats.single().recipientId)
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

    /** A DM row from [sender] whose attachment is [plain] sealed as a DM's is, its ciphertext held when [held]. */
    private suspend fun sealedRow(
        id: String,
        sender: String,
        plain: ByteArray,
        held: Boolean = true,
    ): String {
        val sealed = AttachmentCrypto.seal(plain)
        val hash = sha256Hex(sealed.blob)
        messages.save(
            MessageEntity(
                id = id,
                senderId = sender,
                conversationId = sender,
                body = "",
                sentAt = 10L,
                received = false,
                attachmentHash = hash,
                attachmentKey = Base64.getEncoder().encodeToString(sealed.key),
                attachmentMime = "image/jpeg",
            ),
        )
        if (held) blobs.insertArrived(hash, "application/octet-stream", sealed.blob)
        return hash
    }

    @Test
    fun aDmsAttachmentIsOpenedOnThePhoneUnderItsRowsRules() =
        runTest {
            accepted.value = setOf(BOB)
            val plain = byteArrayOf(1, 2, 3, 4)
            val hash = sealedRow("pic", BOB, plain)
            val source = api.attachment("pic")
            assertEquals(hash, source.hash)
            assertArrayEquals(plain, source.open())
            assertEquals(Codes.NOT_FOUND, refused { api.attachment("never") })
            row("words")
            assertEquals("a message with no attachment", Codes.NOT_FOUND, refused { api.attachment("words") })
            blocked.value = setOf(BOB)
            assertEquals(Codes.NOT_FOUND, refused { api.attachment("pic") })
        }

    @Test
    fun aMessageRequestsAttachmentIsRefusedEvenWithItsBytesHere() =
        runTest {
            sealedRow("stranger-pic", CY, byteArrayOf(9))
            val source = api.attachment("stranger-pic")
            assertEquals(Codes.REFUSED, refused { source.open() })
            assertEquals(Codes.REFUSED, refused { api.fetch("stranger-pic") })
            accepted.value = setOf(CY)
            assertArrayEquals(byteArrayOf(9), api.attachment("stranger-pic").open())
        }

    @Test
    fun bytesNotHereAnswerNotHeldAndFetchAsksTheMeshOnlyForAReadableRow() =
        runTest {
            accepted.value = setOf(BOB, MALLORY)
            val hash = sealedRow("later", BOB, byteArrayOf(5), held = false)
            assertEquals(Codes.NOT_HELD, refused { api.attachment("later") })
            assertTrue(
                api
                    .fetch("later")
                    .getValue("asked")
                    .jsonPrimitive.boolean,
            )
            assertEquals(listOf(hash), mesh.downloadedAttachments)
            sealedRow("blocked", MALLORY, byteArrayOf(6), held = false)
            blocked.value = setOf(MALLORY)
            assertEquals(Codes.NOT_FOUND, refused { api.fetch("blocked") })
            assertEquals("nothing asked for a blocked sender's", listOf(hash), mesh.downloadedAttachments)
        }

    @Test
    fun anAttachmentAlreadyHereIsNotFetchedAgain() =
        runTest {
            accepted.value = setOf(BOB)
            sealedRow("here", BOB, byteArrayOf(5))
            assertTrue(
                api
                    .fetch("here")
                    .getValue("held")
                    .jsonPrimitive.boolean,
            )
            assertTrue(mesh.downloadedAttachments.isEmpty())
        }

    @Test
    fun onlyAPhotoThePhoneShowsAsSomeonesIsServedAsAnAvatar() =
        runTest {
            val face = byteArrayOf(7, 7)
            val hash = sha256Hex(face)
            blobs.insertArrived(hash, "image/jpeg", face)
            db.peerDao().upsert(PeerEntity(nodeId = BOB, name = "Bob", avatarHash = hash))
            assertArrayEquals(face, api.avatar(hash).open())
            val stray = sha256Hex(byteArrayOf(8))
            blobs.insertArrived(stray, "image/jpeg", byteArrayOf(8))
            assertEquals("a blob nobody shows as a photo", Codes.NOT_FOUND, refused { api.avatar(stray) })
            blocked.value = setOf(BOB)
            assertEquals(Codes.NOT_FOUND, refused { api.avatar(hash) })
        }

    @Test
    fun aPictureIsRefusedWhereThePickerOffersNoneBeforeAnythingIsStored() =
        runTest {
            val picture = byteArrayOf(1, 2, 3)
            assertEquals(Codes.REFUSED, refused { api.send(SendRequest(Conversations.MESHTASTIC, "", picture = picture)) })
            assertEquals(Codes.UNKNOWN_GROUP, refused { api.send(SendRequest("g-nowhere", "", picture = picture)) })
            coVerify(exactly = 0) { attachments.ingestPicked(any(), any(), any(), any()) }
            assertTrue(mesh.sentChats.isEmpty())
        }

    /** The picker's pipeline as a stub: [bytes] stored under their own hash, flagged when [flagged]. */
    private suspend fun pickerStores(
        bytes: ByteArray,
        flagged: Boolean = false,
    ): AttachmentStore.Ingested {
        val ingested = AttachmentStore.Ingested(hash = sha256Hex(bytes), mime = "image/jpeg", sizeBytes = bytes.size)
        coEvery { attachments.ingestPicked(bytes, any(), any(), any()) } coAnswers {
            blobs.insert(ingested.hash, ingested.mime, bytes)
            AttachmentStore.IngestResult.Success(ingested, flagged)
        }
        return ingested
    }

    @Test
    fun aPictureGoesToItsThreadWithItsCaptionThroughThePickersPipeline() =
        runTest {
            val picture = byteArrayOf(4, 5, 6)
            val ingested = pickerStores(picture)
            api.send(SendRequest(BOB, "look", picture = picture))
            val sent = mesh.sentChats.single()
            assertEquals(ingested, sent.attachment)
            assertEquals("look", sent.text)
            assertEquals(BOB, sent.recipientId)
            coVerify { attachments.ingestPicked(picture, any(), screen = false, fitBytes = any()) }
            assertTrue("a sent picture stays", blobs.exists(ingested.hash))
        }

    @Test
    fun theNearbyRoomScreensAPictureAndAFlaggedOneIsRefusedAndDropped() =
        runTest {
            val picture = byteArrayOf(7, 8, 9)
            val ingested = pickerStores(picture, flagged = true)
            assertEquals(Codes.BLOCKED, refused { api.send(SendRequest(Conversations.NEARBY, "", picture = picture)) })
            coVerify { attachments.ingestPicked(picture, any(), screen = true, fitBytes = null) }
            assertTrue(mesh.sentChats.isEmpty())
            assertFalse("the refused picture is not kept", blobs.exists(ingested.hash))
        }

    @Test
    fun aPictureWhoseSendDidNotGoIsDropped() =
        runTest {
            val picture = byteArrayOf(1, 1, 2)
            val ingested = pickerStores(picture)
            mesh.sendChatResult = false // the room's filter refused the caption
            assertEquals(Codes.BLOCKED, refused { api.send(SendRequest(Conversations.NEARBY, "words", picture = picture)) })
            assertFalse(blobs.exists(ingested.hash))
        }

    @Test
    fun aCommonsWhoseRelayTakesNoAttachmentsRefusesAPictureBeforeAnythingIsStored() =
        runTest {
            coEvery { commons.find(ROOM) } returns mockk(relaxed = true)
            relayFacts.value = RelayFacts(textOnlyRooms = setOf(ROOM))
            assertEquals(Codes.REFUSED, refused { api.send(SendRequest(ROOM, "", picture = byteArrayOf(3))) })
            coVerify(exactly = 0) { attachments.ingestPicked(any(), any(), any(), any()) }
        }

    @Test
    fun aTypingCueGoesOnlyWhereTheComposerSendsOne() =
        runTest {
            row("from-bob")
            row("from-cy", conversation = CY, sender = CY) // a stranger's: a message request
            row("from-mallory", conversation = MALLORY, sender = MALLORY)
            accepted.value = setOf(BOB, MALLORY, "g-left")
            blocked.value = setOf(MALLORY)
            db.groupDao().upsert(
                GroupEntity(
                    groupId = "g-left",
                    name = "Gone",
                    members = GroupMembersStore.encode(listOf(ME, BOB)),
                    createdBy = BOB,
                    createdAt = 1L,
                    left = true,
                ),
            )
            coEvery { commons.find(ROOM) } returns mockk(relaxed = true)
            assertEquals(Codes.REFUSED, refused { api.canType(Conversations.MESHTASTIC) })
            assertEquals(Codes.REFUSED, refused { api.canType(ROOM) })
            assertEquals(Codes.REFUSED, refused { api.canType(CY) })
            assertEquals("a group this phone left", Codes.NOT_FOUND, refused { api.canType("g-left") })
            assertEquals("a blocked peer's DM", Codes.NOT_FOUND, refused { api.canType(MALLORY) })
            assertEquals(Codes.NOT_FOUND, refused { api.canType("nobody-at-all") })
            assertEquals("this phone", Codes.NOT_FOUND, refused { api.canType(ME) })
            assertTrue(api.canType(BOB))
            assertTrue(api.canType(Conversations.NEARBY))
            typingOn.value = false
            assertFalse("typing indicators off stop every cue", api.canType(BOB))
            assertTrue("asking sends nothing", mesh.sentTyping.isEmpty())
            api.sendTyping(BOB)
            assertEquals(listOf(BOB), mesh.sentTyping)
        }

    /** Each typer [api.typing] shows in [conversation], as (node, label). */
    private fun JsonObject.typers(conversation: String): List<Pair<String, String>> =
        getValue(conversation).jsonArray.map { typer ->
            val fields = typer.jsonObject
            fields.getValue("node").jsonPrimitive.content to fields.getValue("label").jsonPrimitive.content
        }

    @Test
    fun typingShowsWhatTheChatShowsWithEachTypersLabel() =
        runTest {
            db.peerDao().upsert(PeerEntity(nodeId = BOB, name = "Bob"))
            blocked.value = setOf(MALLORY)
            mesh.typing.value = mapOf(Conversations.NEARBY to setOf(MALLORY, BOB), CY to setOf(MALLORY))
            val shown = api.typing()
            assertEquals("a thread whose only typer is blocked shows nobody", setOf(Conversations.NEARBY), shown.keys)
            assertEquals(listOf(BOB to "Bob"), shown.typers(Conversations.NEARBY))
            typingOn.value = false
            assertEquals("typing indicators off show nobody", emptySet<String>(), api.typing().keys)
        }

    @OptIn(ExperimentalCoroutinesApi::class) // runCurrent: the trigger's combine runs on the test's scheduler
    @Test
    fun theTypingTriggerFiresWhenWhatTheChatShowsMovesAndOnlyThen() =
        runTest {
            var fired = 0
            backgroundScope.launch { api.typingChanges().collect { fired++ } }
            runCurrent()
            assertEquals("the state at the start is no change", 0, fired)
            mesh.typing.value = mapOf(Conversations.NEARBY to setOf(BOB))
            runCurrent()
            assertEquals(1, fired)
            blocked.value = setOf(MALLORY)
            runCurrent()
            mesh.typing.value = mapOf(Conversations.NEARBY to setOf(BOB, MALLORY))
            runCurrent()
            assertEquals("a blocked typer moves nothing the chat shows", 1, fired)
            typingOn.value = false
            runCurrent()
            assertEquals(2, fired)
            typingOn.value = true
            runCurrent()
            assertEquals(3, fired)
            blocked.value = setOf(MALLORY, BOB)
            runCurrent()
            assertEquals("blocking the typer hides the cue at once", 4, fired)
        }

    private companion object {
        // Node ids' shape (26 of a-z and 2-7): a DM's peer and a blocked node must have it.
        const val ME = "meaaaaaaaaaaaaaaaaaaaaaaaa"
        const val BOB = "bobaaaaaaaaaaaaaaaaaaaaaaa"
        const val CY = "cyaaaaaaaaaaaaaaaaaaaaaaaa"
        const val MALLORY = "malloryaaaaaaaaaaaaaaaaaaa"
        const val ROOM = "c-room"
    }
}
