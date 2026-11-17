@file:Suppress("MagicNumber") // times are the test's subject

package app.getknit.knit.usbcontrol

import androidx.test.core.app.ApplicationProvider
import app.getknit.knit.data.GroupRepository
import app.getknit.knit.data.MessageRepository
import app.getknit.knit.data.PeerRepository
import app.getknit.knit.data.ReactionRepository
import app.getknit.knit.data.RoomDbTest
import app.getknit.knit.data.commons.CommonsRepository
import app.getknit.knit.data.group.GroupEntity
import app.getknit.knit.data.group.GroupMembersStore
import app.getknit.knit.data.message.Conversations
import app.getknit.knit.data.message.MessageEntity
import app.getknit.knit.data.ratchet.GroupRatchetRepository
import app.getknit.knit.data.receipt.MessageReceiptEntity
import app.getknit.knit.data.settings.InboundSettings
import app.getknit.knit.data.settings.SettingsStore
import app.getknit.knit.identity.Identity
import app.getknit.knit.mesh.FakeMeshController
import app.getknit.knit.mesh.MeshController
import app.getknit.knit.mesh.lora.LoraFacts
import app.getknit.knit.mesh.lora.LoraStatusRepository
import app.getknit.knit.notifications.Notifier
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module

/**
 * Read receipts over USB (ADR 2026-11.z6jw), against the real repositories over Room: `read` tells the authors only what
 * the watermark passed of somebody else's words, as the notification's Mark read does, and our messages say who read
 * them exactly as the chat shows it.
 */
class AppUsbControlReadsTest : RoomDbTest() {
    private val mesh = FakeMeshController()
    private val blocked = MutableStateFlow(emptySet<String>())
    private val accepted = MutableStateFlow(emptySet<String>())
    private val lastRead = MutableStateFlow(emptyMap<String, Long>())
    private val receiptsOn = MutableStateFlow(true)
    private val settings =
        mockk<SettingsStore>(relaxed = true) {
            every { blockedNodeIds } returns blocked
            every { acceptedConversations } returns accepted
            every { lastReadAll } returns lastRead
            every { displayName } returns flowOf("Me")
            every { contentFilteringEnabled } returns flowOf(true)
            every { readReceiptsEnabled } returns receiptsOn
        }
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
                    single { mockk<Notifier>(relaxed = true) }
                    single { db.messageReceiptDao() }
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

    @Test
    fun aReadCountsWhatTheWatermarkPassedAsTheNotificationsMarkReadDoes() =
        runTest {
            row("b1", sentAt = 10L)
            row("b2", sentAt = 20L)
            row("ours", sender = ME, sentAt = 25L)
            row("renamed", sentAt = 26L, kind = MessageEntity.KIND_PEER_RENAMED)
            row("b3", sentAt = 30L)
            row("b4", sentAt = 40L)
            lastRead.value = mapOf(BOB to 10L)
            api.markRead(BOB, upTo = 30L)
            assertEquals("somebody else's words past the old watermark and up to the new one", setOf("b2", "b3"), mesh.readIds())

            lastRead.value = mapOf(BOB to 30L)
            mesh.messagesRead.clear()
            api.markRead(BOB, upTo = 20L)
            assertTrue("a watermark that did not move reads nothing", mesh.messagesRead.isEmpty())
            api.markRead(BOB, upTo = null)
            assertEquals(setOf("b4"), mesh.readIds())

            mesh.messagesRead.clear()
            row("room", conversation = Conversations.NEARBY, sentAt = 50L)
            api.markRead(Conversations.NEARBY, upTo = null)
            assertTrue("a room's read tells nobody", mesh.messagesRead.isEmpty())

            group(TEAM, ME, CY, MALLORY)
            row("from-cy", conversation = TEAM, sender = CY, sentAt = 60L)
            row("from-mallory", conversation = TEAM, sender = MALLORY, sentAt = 61L)
            blocked.value = setOf(MALLORY)
            api.markRead(TEAM, upTo = null)
            assertEquals("never a blocked sender's", setOf("from-cy"), mesh.readIds())

            mesh.messagesRead.clear()
            row("far-future", conversation = CY, sender = CY, sentAt = Long.MAX_VALUE)
            accepted.value = setOf(CY)
            api.markRead(CY, upTo = null)
            assertEquals("a sentAt at the top of the clock is read too", setOf("far-future"), mesh.readIds())
        }

    @Test
    fun ourMessagesSayWhoReadThemAsTheChatDoes() =
        runTest {
            accepted.value = setOf(BOB, TEAM)
            row("dm", sender = ME, sentAt = 10L)
            row("theirs", sentAt = 11L)
            read("dm", MALLORY, at = 20L) // a row only the DM's peer could have planted: never shown
            read("theirs", BOB, at = 21L)
            delivered("dm", BOB, at = 25L)
            assertFalse("delivered is not read", shown("dm").isRead())
            assertEquals(emptyList<String>(), shown("dm").readers())
            read("dm", BOB, at = 30L)
            assertTrue(shown("dm").isRead())
            assertEquals(listOf(BOB), shown("dm").readers())
            assertEquals(listOf(30L), shown("dm").readTimes())
            assertFalse("somebody else's message carries no reads", shown("theirs").isRead())
            assertEquals(emptyList<String>(), shown("theirs").readers())

            group(TEAM, ME, BOB, CY)
            row("team", conversation = TEAM, sender = ME, sentAt = 12L)
            // Bob's row first, and first by node id too: only the read times put Cy ahead of him.
            read("team", BOB, at = 50L)
            delivered("team", CY, at = 35L)
            read("team", MALLORY, at = 41L) // no member
            assertFalse("a member it was only delivered to has not read it", shown("team").isRead())
            assertEquals(listOf(BOB), shown("team").readers())
            read("team", CY, at = 40L)
            val team = shown("team")
            assertTrue(team.isRead())
            assertEquals("members only, in order of arrival", listOf(CY, BOB), team.readers())
            assertEquals(
                "a page of history says the same",
                listOf(CY, BOB),
                api
                    .history(TEAM, before = null, limit = 10)
                    .items
                    .single()
                    .first
                    .readers(),
            )

            row("room", conversation = Conversations.NEARBY, sender = ME, sentAt = 13L)
            read("room", BOB, at = 60L)
            assertEquals("a room shows no reads", emptyList<String>(), shown("room").readers())

            receiptsOn.value = false
            assertFalse("the switch off shows nobody's read", shown("dm").isRead())
            assertEquals(emptyList<String>(), shown("team").readers())
        }

    @Test
    fun aChatsLastMessageSaysWhoReadIt() =
        runTest {
            accepted.value = setOf(BOB)
            row("dm", sender = ME, sentAt = 10L)
            read("dm", BOB, at = 30L)
            val last = checkNotNull(api.chat(BOB)).getValue("last").jsonObject
            assertEquals("dm", last.getValue("id").jsonPrimitive.content)
            assertEquals(listOf(BOB), last.readers())
            receiptsOn.value = false
            assertEquals(emptyList<String>(), checkNotNull(api.chat(BOB)).getValue("last").jsonObject.readers())
        }

    /** A group this phone is in, [members] on its roster. */
    private suspend fun group(
        id: String,
        vararg members: String,
    ) = db.groupDao().upsert(
        GroupEntity(
            groupId = id,
            name = "Team",
            members = GroupMembersStore.encode(members.toList()),
            createdBy = ME,
            createdAt = 1L,
        ),
    )

    /** [reader]'s delivery receipt for [messageId] reaching this phone at [at], with no read: the common row. */
    private suspend fun delivered(
        messageId: String,
        reader: String,
        at: Long,
    ) = db.messageReceiptDao().insertIfAbsent(MessageReceiptEntity(messageId, reader, notedAt = at))

    /** [reader]'s read of [messageId] reaching this phone at [at], written as `MessageReceiptRepository.record` writes it. */
    private suspend fun read(
        messageId: String,
        reader: String,
        at: Long,
    ) {
        db.messageReceiptDao().insertIfAbsent(MessageReceiptEntity(messageId, reader, notedAt = at, readAt = at))
        db.messageReceiptDao().markRead(messageId, reader, at)
    }

    private suspend fun shown(id: String): JsonObject = checkNotNull(api.message(id)) { "message $id was not served" }

    private fun FakeMeshController.readIds(): Set<String> = messagesRead.flatten().toSet()

    private fun JsonObject.isRead(): Boolean = getValue("read").jsonPrimitive.boolean

    private fun JsonObject.readers(): List<String> =
        this["readBy"]
            ?.jsonArray
            ?.map {
                it.jsonObject
                    .getValue("node")
                    .jsonPrimitive.content
            }.orEmpty()

    private fun JsonObject.readTimes(): List<Long> =
        this["readBy"]
            ?.jsonArray
            ?.map {
                it.jsonObject
                    .getValue("at")
                    .jsonPrimitive.long
            }.orEmpty()

    private companion object {
        const val ME = "meaaaaaaaaaaaaaaaaaaaaaaaa"
        const val BOB = "bobaaaaaaaaaaaaaaaaaaaaaaa"
        const val CY = "cyaaaaaaaaaaaaaaaaaaaaaaaa"
        const val MALLORY = "malloryaaaaaaaaaaaaaaaaaaa"
        const val TEAM = "g-team"
    }
}
