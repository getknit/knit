package app.getknit.knit.notifications

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.getknit.knit.MainActivity
import app.getknit.knit.R
import app.getknit.knit.data.message.ConversationKind
import app.getknit.knit.data.message.Conversations
import app.getknit.knit.ui.theme.ThemePreferences
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * The shade as [MessageNotifier] keeps it: one MessagingStyle notification per conversation under its id as the tag,
 * a mention beside it on its own channel, one group summary over them, and the per-conversation suppression — the
 * thread on screen notifies nothing and opening it takes what it showed. Read back from the platform's notification
 * list, never from the notifier's own state, over [FakeShortcutStore] as `ConversationShortcutSyncTest` runs the
 * shortcuts every notification names (ADR 2026-10.jbsa).
 */
@RunWith(AndroidJUnit4::class)
class MessageNotifierTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val manager = app.getSystemService(NotificationManager::class.java)
    private val themePrefs = mockk<ThemePreferences> { every { dynamicColor } returns MutableStateFlow(false) }
    private val shortcuts = ConversationShortcuts(app, themePrefs, FakeShortcutStore())
    private val notifier = MessageNotifier(app, shortcuts)

    @Before
    fun setUp() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        notifier.createChannel()
    }

    private fun dm(id: String) =
        NotifConversation(id, title = id.replaceFirstChar(Char::uppercase), avatarBytes = null, kind = ConversationKind.DM)

    private fun group(id: String) = NotifConversation(id, title = "Hikers", avatarBytes = null, kind = ConversationKind.GROUP)

    private fun message(
        conversation: NotifConversation,
        body: String,
        sender: String = conversation.conversationId,
    ) = NotifMessage(
        senderId = sender,
        senderName = sender.replaceFirstChar(Char::uppercase),
        body = body,
        sentAt = 1_700_000_000_000L,
        conversationId = conversation.conversationId,
        avatarBytes = null,
    )

    private fun receive(
        conversation: NotifConversation,
        body: String = "hello",
        sender: String = conversation.conversationId,
    ) = notifier.notify(message(conversation, body, sender), conversation, ME, "Me", null)

    private fun react(
        conversation: NotifConversation,
        emoji: String,
        reactor: String = conversation.conversationId,
        messageId: String = "m1",
    ) = notifier.notify(
        message(conversation, "Reacted $emoji to “lunch?”", reactor).copy(key = reactionNotificationKey(messageId, reactor)),
        conversation,
        ME,
        "Me",
        null,
    )

    private fun lines(tag: String): List<String> =
        NotificationCompat.MessagingStyle
            .extractMessagingStyleFromNotification(conversations().getValue(tag))!!
            .messages
            .map { it.text.toString() }

    private fun mention(
        conversation: NotifConversation,
        body: String = "@me look",
        sender: String = "bob",
    ) = notifier.notifyMention(message(conversation, body, sender), conversation, ME, "Me", null)

    private fun active(): List<StatusBarNotification> = manager.activeNotifications.toList()

    private fun summary(): Notification? = active().singleOrNull { it.notification.isSummary() }?.notification

    /** The per-conversation notifications (and any standalone one), by tag; the group summary has none of its own. */
    private fun conversations(): Map<String?, Notification> =
        active().filterNot { it.notification.isSummary() }.associate { it.tag to it.notification }

    private fun Notification.isSummary() = flags and Notification.FLAG_GROUP_SUMMARY != 0

    private fun Notification.text(): String? = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()

    private fun Notification.actionTitles(): List<String> = actions.orEmpty().map { it.title.toString() }

    private fun Notification.replyAllowsSuggestions(): Boolean =
        actions.orEmpty().single { it.title.toString() == app.getString(R.string.notif_action_reply) }.allowGeneratedReplies

    @Test
    fun aDirectMessagePostsItsThreadUnderItsIdBesideTheGroupSummary() {
        val alice = dm("alice")
        receive(alice)

        val posted = conversations()
        assertEquals(setOf("alice"), posted.keys)
        assertEquals(NotificationChannels.DMS, posted.getValue("alice").channelId)
        assertNotNull("one summary groups the stack", summary())
        assertEquals(posted.getValue("alice").group, summary()!!.group)
    }

    @Test
    fun tappingAThreadsNotificationOpensThatThread() {
        receive(dm("alice"))

        val tap = shadowOf(conversations().getValue("alice").contentIntent).savedIntent
        assertEquals("chat/alice", tap.getStringExtra(MainActivity.EXTRA_ROUTE))
    }

    @Test
    fun aMessageForTheThreadOnScreenPostsNothing() {
        notifier.setVisibleConversation("alice")
        receive(dm("alice"))

        assertTrue(active().toString(), active().isEmpty())
    }

    @Test
    fun leavingTheThreadNotifiesAgain() {
        notifier.setVisibleConversation("alice")
        notifier.setVisibleConversation(null)
        receive(dm("alice"))

        assertEquals(setOf("alice"), conversations().keys)
    }

    @Test
    fun openingAThreadTakesItsMessagesAndMentionsAndLeavesTheOthers() {
        val hikers = group("g-hikers")
        receive(hikers, sender = "carol")
        mention(hikers)
        receive(dm("bob"))
        assertEquals(3, conversations().size)

        notifier.setVisibleConversation("g-hikers")

        assertEquals(setOf("bob"), conversations().keys)
        assertNotNull("the summary stays while one thread does", summary())
    }

    @Test
    fun aMentionPostsBesideItsThreadOnTheMentionsChannel() {
        val hikers = group("g-hikers")
        receive(hikers, sender = "carol")
        mention(hikers)

        val posted = conversations()
        assertEquals(2, posted.size)
        assertEquals(NotificationChannels.GROUPS, posted.getValue("g-hikers").channelId)
        val mentionTag = posted.keys.single { it != "g-hikers" }
        assertEquals(NotificationChannels.MENTIONS, posted.getValue(mentionTag).channelId)
        // The summary ranks with the most urgent child, so a mention lifts the whole stack.
        assertEquals(NotificationChannels.MENTIONS, summary()!!.channelId)
    }

    @Test
    fun theSummaryNamesTheLoneThreadUntilASecondChatJoins() {
        val alice = dm("alice")
        receive(alice, body = "first")
        receive(alice, body = "second")
        // One chat: the summary is that thread's line (its title, then its latest message), not "2 messages in 1 chat".
        assertEquals("Alice  second", summary()!!.text())

        receive(group("g-hikers"), body = "hey", sender = "carol")

        val res = app.resources
        val expected =
            res.getString(
                R.string.notif_summary,
                res.getQuantityString(R.plurals.notif_summary_messages, 3, 3),
                res.getQuantityString(R.plurals.notif_summary_chats, 2, 2),
            )
        assertEquals(expected, summary()!!.text())
    }

    @Test
    fun aDirectMessageOffersReplyAndMarkReadButTheMeshtasticRoomOnlyMarkRead() {
        receive(dm("alice"))
        val room = NotifConversation(Conversations.MESHTASTIC, title = null, avatarBytes = null, kind = ConversationKind.MESHTASTIC)
        receive(room, sender = "!a1b2c3d4")

        val reply = app.getString(R.string.notif_action_reply)
        val markRead = app.getString(R.string.notif_action_mark_read)
        assertEquals(listOf(reply, markRead), conversations().getValue("alice").actionTitles())
        // A reply from the shade would skip the room's first-use disclosure and byte budget, and go nowhere.
        assertEquals(listOf(markRead), conversations().getValue(Conversations.MESHTASTIC).actionTitles())
    }

    @Test
    fun suggestedRepliesAnswerAMessageButNotAReactionToOurOwn() {
        val alice = dm("alice")
        receive(alice, body = "lunch?")
        assertTrue(conversations().getValue("alice").replyAllowsSuggestions())

        // The reaction's line quotes our own words; a suggestion would answer them.
        react(alice, "❤️")
        assertFalse(conversations().getValue("alice").replyAllowsSuggestions())

        receive(alice, body = "see you there")
        assertTrue(conversations().getValue("alice").replyAllowsSuggestions())
    }

    @Test
    fun anInlineReplyIsEchoedIntoItsThreadAsTheLatestLine() {
        receive(dm("alice"), body = "lunch?")

        notifier.onReplied("alice", "on my way", ME, "Me", null)

        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(conversations().getValue("alice"))
        assertEquals(listOf("lunch?", "on my way"), style!!.messages.map { it.text.toString() })
        assertEquals(
            ME,
            style.messages
                .last()
                .person
                ?.key,
        )
    }

    @Test
    fun aChangedReactionReplacesItsLineAndCountsOnce() {
        val alice = dm("alice")
        receive(alice, body = "see you")
        react(alice, "👍")
        react(alice, "❤️")

        assertEquals(listOf("see you", "Reacted ❤️ to “lunch?”"), lines("alice"))
        receive(group("g-hikers"), body = "hey", sender = "carol")
        val res = app.resources
        val expected =
            res.getString(
                R.string.notif_summary,
                res.getQuantityString(R.plurals.notif_summary_messages, 3, 3),
                res.getQuantityString(R.plurals.notif_summary_chats, 2, 2),
            )
        assertEquals("a replaced reaction is still one line in the count", expected, summary()!!.text())
    }

    @Test
    fun twoPeoplesReactionsToOneMessageAreTwoLines() {
        val hikers = group("g-hikers")
        react(hikers, "👍", reactor = "bob")
        react(hikers, "🔥", reactor = "carol")

        assertEquals(listOf("Reacted 👍 to “lunch?”", "Reacted 🔥 to “lunch?”"), lines("g-hikers"))
    }

    @Test
    fun aRetractedReactionLeavesTheThreadsOtherLines() {
        val alice = dm("alice")
        receive(alice, body = "see you")
        react(alice, "👍")
        receive(group("g-hikers"), body = "hey", sender = "carol")

        notifier.retractReaction("alice", reactionNotificationKey("m1", "alice"))

        assertEquals(listOf("see you"), lines("alice"))
        val res = app.resources
        val expected =
            res.getString(
                R.string.notif_summary,
                res.getQuantityString(R.plurals.notif_summary_messages, 2, 2),
                res.getQuantityString(R.plurals.notif_summary_chats, 2, 2),
            )
        assertEquals("the withdrawn line leaves the count", expected, summary()!!.text())
    }

    @Test
    fun aRetractedReactionThatWasTheWholeNotificationTakesIt() {
        react(dm("alice"), "👍")
        receive(dm("bob"))

        notifier.retractReaction("alice", reactionNotificationKey("m1", "alice"))

        assertEquals(setOf("bob"), conversations().keys)
    }

    @Test
    fun aRetractionWithNothingPostedChangesNothing() {
        receive(dm("alice"), body = "see you")

        notifier.retractReaction("alice", reactionNotificationKey("m1", "alice"))
        notifier.retractReaction("bob", reactionNotificationKey("m1", "bob"))

        assertEquals(listOf("see you"), lines("alice"))
    }

    @Test
    fun aReactionInTheThreadOnScreenPostsNothing() {
        notifier.setVisibleConversation("alice")
        react(dm("alice"), "👍")

        assertTrue(active().toString(), active().isEmpty())
    }

    @Test
    fun aReplyToAThreadWhoseStateIsGoneClearsItsNotificationAndPostsNothingNew() {
        // The notification outlived the state behind it (here the state is dropped by a swipe; in the field, by a
        // process death): the reply's spinner must still be cleared, and nothing re-posted from an empty history.
        receive(dm("alice"))
        receive(dm("bob"))
        notifier.onDismissed("alice")
        assertTrue(conversations().containsKey("alice"))

        notifier.onReplied("alice", "late reply", ME, "Me", null)

        assertEquals(setOf("bob"), conversations().keys)
    }

    @Test
    fun dismissingTheSummaryForgetsEveryThread() {
        receive(dm("alice"))
        receive(dm("bob"))

        notifier.onDismissed(MessageNotifier.DISMISS_ALL)
        assertNull(summary())

        // Had either thread survived the dismissal, this would summarise as two chats.
        receive(dm("carol"), body = "hi")
        assertEquals("Carol  hi", summary()!!.text())
    }

    @Test
    fun clearingAThreadAlsoTakesItsTransferOffer() {
        notifier.notifyTransferOffer("alice", "Alice", null, "trip.mp4", 12_000_000L)
        receive(dm("bob"))
        assertEquals(2, conversations().size)

        notifier.clearConversation("alice")

        assertEquals(setOf("bob"), conversations().keys)
    }

    @Test
    fun aTransferOfferForTheThreadOnScreenIsNotPosted() {
        notifier.setVisibleConversation("alice")
        notifier.notifyTransferOffer("alice", "Alice", null, "trip.mp4", null)

        assertTrue(active().isEmpty())
    }

    @Test
    fun aTransferOfferNamesTheFileAndItsSizeOnlyWhenKnown() {
        notifier.notifyTransferOffer("alice", "Alice", null, "trip.mp4", null)
        assertEquals(app.getString(R.string.notif_transfer_offer, "trip.mp4"), conversations().values.single().text())

        notifier.notifyTransferOffer("alice", "Alice", null, "trip.mp4", 0L)
        assertEquals(
            "a zero size is no size",
            app.getString(R.string.notif_transfer_offer, "trip.mp4"),
            conversations().values.single().text(),
        )
    }

    @Test
    fun messageRequestsPostOneHeadsUpThatGoesAtZeroOrWhenTheInboxOpens() {
        notifier.notifyMessageRequests(2)
        notifier.notifyMessageRequests(3)
        val heads = conversations().values.single()
        assertEquals(NotificationChannels.REQUESTS, heads.channelId)
        assertEquals(app.resources.getQuantityString(R.plurals.notif_requests_body, 3, 3), heads.text())

        notifier.notifyMessageRequests(0)
        assertTrue(active().isEmpty())

        notifier.setRequestsVisible(true)
        notifier.notifyMessageRequests(4)
        assertTrue("the inbox on screen is the heads-up already", active().isEmpty())

        notifier.setRequestsVisible(false)
        notifier.notifyMessageRequests(4)
        assertEquals(1, active().size)
    }

    @Test
    fun theOpenToChatCueGoesWhenTheNearbyRoomIsOpened() {
        notifier.notifyOpenToChat(listOf("Ann"), null)
        assertEquals(NotificationChannels.OPEN_TO_CHAT, conversations().values.single().channelId)

        notifier.setVisibleConversation(Conversations.NEARBY)
        assertTrue(active().isEmpty())

        notifier.notifyOpenToChat(listOf("Ann", "Ben"), null)
        assertTrue("the room on screen is the cue taken", active().isEmpty())
    }

    @Test
    fun aRetainPassClearsThreadsTheListNoLongerOffersButNotOnePostedAfterItRead() {
        receive(dm("alice"))
        receive(dm("bob"))
        // The pass reads its inputs here; carol's message lands after, so the pass knows nothing about it.
        val since = shortcuts.snapshot().epoch
        receive(dm("carol"))

        notifier.retainConversations(offered = setOf("bob"), refreshed = emptyMap(), since = since)

        assertEquals(setOf("bob", "carol"), conversations().keys)
    }

    @Test
    fun withoutTheNotificationPermissionNothingIsPosted() {
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)

        receive(dm("alice"))
        notifier.notifyMessageRequests(1)
        notifier.notifyTransferOffer("bob", "Bob", null, "a.pdf", null)

        assertTrue(active().isEmpty())
    }

    @Test
    fun notificationsSwitchedOffPostNothing() {
        shadowOf(manager).setNotificationsEnabled(false)

        receive(dm("alice"))

        assertTrue(active().isEmpty())
    }

    private companion object {
        const val ME = "me-node"
    }
}
