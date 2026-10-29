package app.getknit.knit.ui.chat

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.getknit.knit.data.message.Conversations
import app.getknit.knit.mesh.lora.LoraSizeHint
import app.getknit.knit.mesh.protocol.ReplyRef
import app.getknit.knit.ui.theme.KnitTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/**
 * The chat's two LoRa indicators — the pinned reach notice and the composer's length hint — and the cases
 * where they must stay quiet. Sibling of [ChatRelayIndicatorTest].
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChatLoraIndicatorTest {
    @Suppress("DEPRECATION") // junit4.v2 rules swap in StandardTestDispatcher — a test-semantics migration, see roadmap.md
    @get:Rule
    val compose = createComposeRule()

    private fun render(
        conversationId: String = "ana",
        title: String = "Ana",
        loraReach: LoraReach = LoraReach.Silent,
        loraCarry: LoraCarry = LoraCarry.None,
        draft: String = "",
        replyingTo: ReplyRef? = null,
    ) {
        compose.setContent {
            KnitTheme {
                ChatScreenContent(
                    conversationId = conversationId,
                    state =
                        ChatUiState(
                            isRoom = conversationId == Conversations.NEARBY,
                            myNodeId = "me",
                            title = title,
                            loraReach = loraReach,
                            loraCarry = loraCarry,
                        ),
                    inputState = TextFieldState(draft),
                    pendingAttachment = null,
                    replyingTo = replyingTo,
                    now = 1_700_000_000_000L,
                    onBack = {},
                    onOpenProfile = {},
                    onOpenGroupDetails = {},
                    onSend = {},
                    onAttachClick = {},
                    onClearAttachment = {},
                    onReceiveImage = {},
                    onTyping = {},
                    onMentionAdded = {},
                    onStartReply = {},
                    onCancelReply = {},
                    onReact = { _, _ -> },
                    onDeleteMessage = {},
                    onBlock = {},
                    onUnblock = {},
                    onCopy = {},
                    onSaveAttachment = { _, _, _ -> },
                )
            }
        }
    }

    @Test
    fun aLoraOnlyDmShowsThePinnedNoticeAndItsExplanation() {
        render(loraReach = LoraReach.LoraOnly)
        compose.onNodeWithTag("chat_lora_notice").assertIsDisplayed()
        compose.onNodeWithText("Reachable over LoRa only").assertIsDisplayed()

        compose.onNodeWithTag("chat_lora_notice").performClick()
        compose.onNodeWithText("Over LoRa only").assertIsDisplayed()
    }

    @Test
    fun theDmsOffVariantSaysNothingReachesThem() {
        render(loraReach = LoraReach.LoraOnlyDmsOff)
        compose.onNodeWithText("In LoRa range only — private messages over LoRa are off").assertIsDisplayed()
    }

    @Test
    fun theSaturatedVariantSaysMessagesAreDelayedAndExplainsWhy() {
        render(loraReach = LoraReach.LoraOnlySaturated)
        compose.onNodeWithText("Over LoRa only — airtime is used up, messages are delayed").assertIsDisplayed()

        compose.onNodeWithTag("chat_lora_notice").performClick()
        compose.onNodeWithText("LoRa airtime is used up").assertIsDisplayed()
    }

    @Test
    fun theRoomSaysItsPostsAreSlowToDistantPeopleAndNamesNobody() {
        render(conversationId = Conversations.NEARBY, loraReach = LoraReach.RoomSaturated, loraCarry = LoraCarry.Room)
        compose.onNodeWithText("LoRa airtime is used up — posts are slow to reach distant people").assertIsDisplayed()

        // The room's explanation is about a mixed audience: it speaks of what still works and, unlike every
        // DM body, carries no peer name to format the thread title into.
        compose.onNodeWithTag("chat_lora_notice").performClick()
        compose.onNodeWithText("Some people here were last heard over the LoRa radio", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Everyone in Wi-Fi or Bluetooth range", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Ana", substring = true).assertDoesNotExist()
    }

    @Test
    fun aGroupSaysWhyItsMessagesWaitAndNamesNobody() {
        render(conversationId = "g-trailhead", title = "Trailhead Crew", loraReach = LoraReach.GroupUnsupported)
        compose.onNodeWithText("Group chats don't travel over LoRa — distant members get these later").assertIsDisplayed()

        compose.onNodeWithTag("chat_lora_notice").performClick()
        compose.onNodeWithText("carries the Nearby room and private messages, not group chats", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Nothing is lost", substring = true).assertIsDisplayed()
        // Like the room's, this body takes no argument. It cannot be checked the room's way — a group
        // header does render the thread title — so the assertion is that no *single* node carries both
        // the title and the body, which is what formatting one into the other would produce.
        compose
            .onNode(hasText("Trailhead Crew", substring = true) and hasText("Nothing is lost", substring = true))
            .assertDoesNotExist()
    }

    @Test
    fun aQuietReachShowsNoNotice() {
        render(loraReach = LoraReach.Silent, loraCarry = LoraCarry.Dm)
        compose.onNodeWithTag("chat_lora_notice").assertDoesNotExist()
    }

    @Test
    fun aDraftInsideTheBudgetShowsNoHint() {
        render(loraCarry = LoraCarry.Dm, draft = "a".repeat(LoraSizeHint.DM_BODY_BYTES))
        compose.onNodeWithTag("chat_lora_size_hint").assertDoesNotExist()
    }

    @Test
    fun aDraftPastTheBudgetShowsTheHint() {
        render(loraCarry = LoraCarry.Dm, draft = "a".repeat(LoraSizeHint.DM_BODY_BYTES + 1))
        compose.onNodeWithTag("chat_lora_size_hint").assertIsDisplayed()
        compose.onNodeWithText("Long message — it may not reach people over LoRa").assertIsDisplayed()
    }

    /**
     * The room's budget is one LoRa packet (ADR 2026-10.rp3s): past it the post may not be carried, and the room's hint
     * says so in its own words — a hedge, since prose deflates and a longer post often still fits.
     */
    @Test
    fun aRoomPostAtItsOnePacketBudgetShowsNoHint() {
        render(conversationId = Conversations.NEARBY, loraCarry = LoraCarry.Room, draft = "a".repeat(LoraSizeHint.ROOM_BODY_BYTES))
        compose.onNodeWithTag("chat_lora_size_hint").assertDoesNotExist()
    }

    @Test
    fun theRoomWarnsAPostPastItsOnePacketBudgetMayNotBeCarried() {
        render(conversationId = Conversations.NEARBY, loraCarry = LoraCarry.Room, draft = "a".repeat(LoraSizeHint.ROOM_BODY_BYTES + 1))
        compose.onNodeWithTag("chat_lora_size_hint").assertIsDisplayed()
        compose.onNodeWithText("Long posts, replies and attachments may not reach people over LoRa").assertIsDisplayed()
    }

    @Test
    fun aRoomReplyIsNeverCarriedWhateverItsLength() {
        val reply = ReplyRef(messageId = "m0", authorId = "ana", author = "Ana", snippet = "see you at the gate")
        render(conversationId = Conversations.NEARBY, loraCarry = LoraCarry.Room, draft = "ok", replyingTo = reply)
        compose.onNodeWithText("Long posts, replies and attachments may not reach people over LoRa").assertIsDisplayed()
    }

    @Test
    fun aReplyShrinksTheBudget() {
        val reply = ReplyRef(messageId = "m0", authorId = "ana", author = "Ana", snippet = "see you at the gate")
        val body = "a".repeat(LoraSizeHint.DM_BODY_BYTES - LoraSizeHint.REPLY_RESERVE_BYTES + 1)
        render(loraCarry = LoraCarry.Dm, draft = body, replyingTo = reply)
        compose.onNodeWithTag("chat_lora_size_hint").assertIsDisplayed()
    }

    @Test
    fun noHintWhenTheDraftWouldNotRideLora() {
        // A group thread, a DM with private messages kept off LoRa, or no board at all: carry is None.
        render(loraCarry = LoraCarry.None, draft = "a".repeat(LoraSizeHint.ROOM_BODY_BYTES * 2))
        compose.onNodeWithTag("chat_lora_size_hint").assertDoesNotExist()
    }
}
