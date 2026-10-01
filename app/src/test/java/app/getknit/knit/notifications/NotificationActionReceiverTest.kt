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
        }
    private val identity = mockk<Identity> { coEvery { nodeId() } returns ME }

    @Before
    fun setUp() {
        coEvery { groups.find(any()) } returns null
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
    fun aNearbyReplyGoesToTheRoom() {
        reply(Conversations.NEARBY, "anyone here")
        coVerify(exactly = 1) { mesh.sendChat("anyone here", recipientId = null, group = null) }
    }

    @Test
    fun aMeshtasticReplyIsNeverSentFromTheNotification() {
        reply(Conversations.MESHTASTIC, "over the air")
        coVerify(exactly = 0) { mesh.sendChat(any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { mesh.sendPublicPost(any()) }
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

    @Test
    fun dismissDropsOnlyThatTag() {
        deliver(Intent(MessageNotifier.ACTION_DISMISS).putExtra(MessageNotifier.EXTRA_TAG, "tag-x"))
        verify(exactly = 1) { notifier.onDismissed("tag-x") }
        coVerify(exactly = 0) { mesh.sendChat(any(), any(), any(), any(), any(), any()) }
    }

    private companion object {
        const val ME = "aaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val BOB = "bbbbbbbbbbbbbbbbbbbbbbbbbb"
        val GROUP_ID = Conversations.GROUP_ID_PREFIX + "0123"
        val GROUP = GroupEntity(groupId = GROUP_ID, name = "Hikers", members = "[\"$ME\",\"$BOB\"]", createdBy = ME, createdAt = 1L)
    }
}
