package app.getknit.knit.notifications

import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.os.Looper
import androidx.core.app.RemoteInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.getknit.knit.data.BlobRepository
import app.getknit.knit.data.GroupRepository
import app.getknit.knit.data.MessageRepository
import app.getknit.knit.data.group.GroupEntity
import app.getknit.knit.data.group.toGroupInfo
import app.getknit.knit.data.message.Conversations
import app.getknit.knit.data.message.MessageEntity
import app.getknit.knit.data.settings.SettingsStore
import app.getknit.knit.identity.Identity
import app.getknit.knit.mesh.MeshController
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.Shadows.shadowOf

/**
 * The notification's inline Reply, Mark read and dismiss, delivered the way the system delivers them — a
 * broadcast to the manifest-registered [NotificationActionReceiver] over a Koin graph of fakes.
 *
 * The routing is what matters: a reply typed under a DM or a group must leave as that DM or that group,
 * never as a Nearby-room post (the room is read by everyone in range), and a reply under a group this phone
 * no longer has must send nothing at all rather than fall through to another destination.
 */
@RunWith(AndroidJUnit4::class)
class NotificationActionReceiverTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val mesh = mockk<MeshController>(relaxed = true)
    private val messages = mockk<MessageRepository>()
    private val groups = mockk<GroupRepository>()
    private val notifier = mockk<Notifier>(relaxed = true)
    private val settings =
        mockk<SettingsStore>(relaxed = true) {
            every { displayName } returns flowOf("Me")
            every { ownAvatarHash } returns flowOf(null)
            // Mark read reads the old watermark and the block list (ADR 2026-11.z6jw); a relaxed mock's flow never emits.
            every { lastReadAt(any()) } returns flowOf(0L)
            every { blockedNodeIds } returns flowOf(setOf(BLOCKED))
        }
    private val identity = mockk<Identity> { coEvery { nodeId() } returns ME }

    /** The conversations the chat list no longer offers — a blocked peer, a left group (ADR 2026-10.jbsa). */
    private val gone = mutableSetOf<String>()

    @Before
    fun setUp() {
        coEvery { groups.find(any()) } returns null
        coEvery { messages.page(any(), any(), any(), any()) } returns emptyList()
        coEvery { groups.find(GROUP_ID) } returns GROUP
        startKoin {
            androidContext(app)
            modules(
                module {
                    single { mesh }
                    single { messages }
                    single { groups }
                    single { settings }
                    single { identity }
                    single { mockk<BlobRepository>(relaxed = true) }
                    single { notifier }
                    single<ConversationGate> { ConversationGate { id -> id !in gone } }
                    single<CoroutineScope> { CoroutineScope(SupervisorJob() + Dispatchers.Unconfined) }
                },
            )
        }
    }

    @After
    fun tearDown() = stopKoin()

    private fun deliver(intent: Intent) {
        app.sendBroadcast(intent.setClass(app, NotificationActionReceiver::class.java))
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun reply(
        conversationId: String,
        text: String,
        tag: String = "tag-$conversationId",
    ) {
        val intent =
            Intent(MessageNotifier.ACTION_REPLY)
                .putExtra(MessageNotifier.EXTRA_TAG, tag)
                .putExtra(MessageNotifier.EXTRA_CONV, conversationId)
        val input = RemoteInput.Builder(MessageNotifier.KEY_TEXT_REPLY).build()
        RemoteInput.addResultsToIntent(
            arrayOf(input),
            intent,
            Bundle().apply { putCharSequence(MessageNotifier.KEY_TEXT_REPLY, text) },
        )
        deliver(intent)
    }

    @Test
    fun aDmReplyLeavesAsThatDmAndNothingElse() {
        reply(BOB, "  on my way  ")
        coVerify(exactly = 1) { mesh.sendChat("on my way", recipientId = BOB) }
        coVerify(exactly = 1) { mesh.sendChat(any(), any(), any(), any(), any(), any()) }
        verify { notifier.onReplied("tag-$BOB", "on my way", ME, "Me", null) }
    }

    @Test
    fun aGroupReplyLeavesAsThatGroup() {
        reply(GROUP_ID, "count me in")
        coVerify(exactly = 1) { mesh.sendChat("count me in", group = GROUP.toGroupInfo()) }
        coVerify(exactly = 0) { mesh.sendChat(any(), recipientId = any(), group = null) }
    }

    @Test
    fun aReplyUnderAGroupThisPhoneNoLongerHasSendsNothing() {
        reply(Conversations.GROUP_ID_PREFIX + "gone", "hello?")
        coVerify(exactly = 0) { mesh.sendChat(any(), any(), any(), any(), any(), any()) }
        // The echo still clears the notification's "sending" state.
        verify { notifier.onReplied(any(), "hello?", ME, "Me", null) }
    }

    @Test
    fun aReplyUnderABlockedPeersNotificationSendsNothingAndTheNotificationGoes() {
        // Blocked while the notification sat in the shade: a reply would message the peer the user just blocked.
        gone += BOB
        reply(BOB, "still there?")
        coVerify(exactly = 0) { mesh.sendChat(any(), any(), any(), any(), any(), any()) }
        verify(exactly = 0) { notifier.onReplied(any(), any(), any(), any(), any()) }
        verify { notifier.clearConversation(BOB) }
    }

    @Test
    fun aReplyUnderALeftGroupsNotificationSendsNothing() {
        // The row is still there (left = true), so the send path alone would post — and rejoin the group.
        gone += GROUP_ID
        reply(GROUP_ID, "back again")
        coVerify(exactly = 0) { mesh.sendChat(any(), any(), any(), any(), any(), any()) }
        verify { notifier.clearConversation(GROUP_ID) }
    }

    @Test
    fun aNearbyReplyGoesToTheRoom() {
        reply(Conversations.NEARBY, "anyone here")
        coVerify(exactly = 1) { mesh.sendChat("anyone here", recipientId = null, group = null) }
    }

    @Test
    fun aMeshtasticReplyIsNeverSentFromTheNotification() {
        reply(Conversations.MESHTASTIC, "over the air")
        coVerify(exactly = 0) { mesh.sendChat(any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { mesh.sendPublicPost(any(), any(), any()) }
    }

    @Test
    fun aMeshcoreReplyIsNeverSentFromTheNotification() {
        // The backstop asks the one bridged-room predicate ahead of every route (ADR 2026-11.gsqu's Stage 6 amendment).
        reply(Conversations.MESHCORE_PUBLIC, "over the air")
        coVerify(exactly = 0) { mesh.sendChat(any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { mesh.sendPublicPost(any(), any(), any()) }
    }

    @Test
    fun aCommonsReplyGoesToThatCommons() {
        val commons = Conversations.commonsIdFor("abcd")
        reply(commons, "hi all")
        coVerify(exactly = 1) { mesh.sendCommons(commons, "hi all", emptyList(), null) }
        coVerify(exactly = 0) { mesh.sendChat(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun aBlankReplySendsNothingButStillEchoes() {
        reply(BOB, "   ")
        coVerify(exactly = 0) { mesh.sendChat(any(), any(), any(), any(), any(), any()) }
        verify { notifier.onReplied("tag-$BOB", "", ME, "Me", null) }
    }

    @Test
    fun markReadStampsTheNewestMessageAndClearsTheThread() {
        coEvery { messages.newestSentAt(BOB) } returns 1234L
        deliver(Intent(MessageNotifier.ACTION_MARK_READ).putExtra(MessageNotifier.EXTRA_CONV, BOB))
        coVerify { settings.setLastReadAt(BOB, 1234L) }
        verify { notifier.clearConversation(BOB) }
    }

    /** ADR 2026-11.z6jw: what Mark read clears counts as read — others' unread rows only, never ours or a blocked sender's. */
    @Test
    fun markReadReportsOnlyOthersUnreadRowsAsRead() {
        // A live watermark, so taking `since` after the tap moved it would read 300 and report nothing.
        val watermark = MutableStateFlow(100L)
        every { settings.lastReadAt(GROUP_ID) } returns watermark
        coEvery { settings.setLastReadAt(GROUP_ID, any()) } answers {
            watermark.value = secondArg()
            mockk(relaxed = true)
        }
        coEvery { messages.newestSentAt(GROUP_ID) } returns 300L
        coEvery { messages.page(GROUP_ID, null, null, any()) } returns
            listOf(
                MessageEntity(id = "new", senderId = BOB, conversationId = GROUP_ID, body = "", sentAt = 300L),
                MessageEntity(id = "mine", senderId = ME, conversationId = GROUP_ID, body = "", sentAt = 250L),
                MessageEntity(id = "blocked", senderId = BLOCKED, conversationId = GROUP_ID, body = "", sentAt = 200L),
                MessageEntity(id = "old", senderId = BOB, conversationId = GROUP_ID, body = "", sentAt = 50L),
            )

        deliver(Intent(MessageNotifier.ACTION_MARK_READ).putExtra(MessageNotifier.EXTRA_CONV, GROUP_ID))

        coVerify(exactly = 1) { mesh.onMessagesRead(listOf("new")) }
        coVerify { settings.setLastReadAt(GROUP_ID, 300L) }
    }

    /** A failing read report never costs the tap its own effect: the watermark moves and the thread clears. */
    @Test
    fun markReadStillClearsTheThreadWhenTheReadReportFails() {
        coEvery { messages.newestSentAt(BOB) } returns 1234L
        coEvery { messages.page(BOB, null, null, any()) } throws IllegalStateException("db gone")

        deliver(Intent(MessageNotifier.ACTION_MARK_READ).putExtra(MessageNotifier.EXTRA_CONV, BOB))

        coVerify { settings.setLastReadAt(BOB, 1234L) }
        verify { notifier.clearConversation(BOB) }
    }

    @Test
    fun dismissDropsOnlyThatTag() {
        deliver(Intent(MessageNotifier.ACTION_DISMISS).putExtra(MessageNotifier.EXTRA_TAG, "tag-x"))
        verify(exactly = 1) { notifier.onDismissed("tag-x") }
        coVerify(exactly = 0) { mesh.sendChat(any(), any(), any(), any(), any(), any()) }
    }

    private companion object {
        const val ME = "aaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val BOB = "bbbbbbbbbbbbbbbbbbbbbbbbbb"
        const val BLOCKED = "cccccccccccccccccccccccccc"
        val GROUP_ID = Conversations.GROUP_ID_PREFIX + "0123"
        val GROUP = GroupEntity(groupId = GROUP_ID, name = "Hikers", members = "[\"$ME\",\"$BOB\"]", createdBy = ME, createdAt = 1L)
    }
}
