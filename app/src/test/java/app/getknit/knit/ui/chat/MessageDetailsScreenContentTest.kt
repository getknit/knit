package app.getknit.knit.ui.chat

import androidx.compose.ui.test.assertContentDescriptionContains
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.getknit.knit.data.message.DeliveryPlane
import app.getknit.knit.ui.theme.KnitTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/**
 * What the details screen owes the person who opened it: every reactor by name (the thing a "👍 3" chip
 * cannot say), a filter row that actually narrows to one emoji, an empty state when nobody reacted, your
 * own row inert while another reactor opens their profile — and, for a message you sent, who it has
 * actually reached (the thing a single ✓✓ cannot say) beside who it hasn't.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MessageDetailsScreenContentTest {
    @get:Rule
    val compose = createComposeRule()

    private fun render(
        state: MessageDetailsUiState,
        onOpenProfile: (String) -> Unit = {},
    ) {
        compose.setContent {
            KnitTheme {
                MessageDetailsScreenContent(state = state, onBack = {}, onOpenProfile = onOpenProfile)
            }
        }
    }

    @Test
    fun `lists every reactor by name with the emoji they left`() {
        render(populated())

        compose.onNodeWithText("Sam Rivera").assertIsDisplayed()
        compose.onNodeWithText("Priya Nair").assertIsDisplayed()
        compose.onNodeWithTag("reactor_row_theo").assertIsDisplayed()
        // The local user's row is labelled "You", not their own display name.
        compose.onNodeWithText("You").assertIsDisplayed()
    }

    @Test
    fun `selecting an emoji chip narrows the list to that emoji's reactors`() {
        render(populated())

        compose.onNodeWithTag("message_details_filter_❤️").performClick()

        compose.onNodeWithTag("reactor_row_me").assertIsDisplayed()
        compose.onNodeWithTag("reactor_row_sam").assertDoesNotExist()

        compose.onNodeWithTag("message_details_filter_all").performClick()
        compose.onNodeWithTag("reactor_row_sam").assertIsDisplayed()
    }

    @Test
    fun `tapping another reactor opens their profile, your own row is inert`() {
        var opened: String? = null
        render(populated(), onOpenProfile = { opened = it })

        compose.onNodeWithTag("reactor_row_me").performClick()
        assertEquals(null, opened)

        compose.onNodeWithTag("reactor_row_sam").performClick()
        assertEquals("sam", opened)
    }

    @Test
    fun `a message with no reactions says so instead of showing an empty list`() {
        render(MessageDetailsUiState(messageId = "m1", body = "hi", senderName = "Sam Rivera"))

        compose.onNodeWithTag("message_details_no_reactions").assertIsDisplayed()
        compose.onNodeWithTag("message_details_filter_all").assertDoesNotExist()
    }

    @Test
    fun `the body, sent time and delivery state are all shown`() {
        render(populated())

        compose.onNodeWithTag("message_details_body").assertIsDisplayed()
        compose.onNodeWithTag("message_details_sent_at").assertIsDisplayed()
        compose.onNodeWithTag("message_details_delivery").assertIsDisplayed()
        compose.onNodeWithText("Delivered over the Internet").assertIsDisplayed()
    }

    @Test
    fun `a content-filtered body stays collapsed here too`() {
        render(populated().copy(body = "something nasty", moderationFlagged = true))

        compose.onNodeWithText("something nasty").assertDoesNotExist()
    }

    @Test
    fun `a group send lists who has it and who does not, under labelled headers`() {
        render(withRecipients())

        compose.onNodeWithText("Waiting on").assertIsDisplayed()
        compose.onNodeWithTag("recipient_row_sam").assertIsDisplayed()
        compose.onNodeWithTag("recipient_row_priya").assertIsDisplayed()
        compose.onNodeWithTag("recipient_row_theo").assertIsDisplayed()
    }

    @Test
    fun `the ratio lives on the delivery line, and the section header does not repeat it`() {
        // The tick alone says "at least one member has it" — the summary line is where the count belongs,
        // and saying it twice on one screen is what the plain header avoids.
        render(withRecipients())

        // onNodeWithText fails on an ambiguous match, so finding exactly one is itself the proof the
        // ratio is not also in the header — and assertTextEquals pins what the header says instead.
        compose.onNodeWithText("Delivered to 2 of 3").assertIsDisplayed()
        compose.onNodeWithTag("message_details_delivered_header").assertTextEquals("Delivered to")
    }

    @Test
    fun `a group send acked before the receipts table existed keeps the plain delivered line`() {
        // Ticked with no rows: "Delivered to 0 of 3" would contradict the tick, so both the split and the
        // ratio stand down together.
        render(populated())

        compose.onNodeWithText("Delivered over the Internet").assertIsDisplayed()
    }

    @Test
    fun `a recipient row announces its own delivery state — the header above it is not read with it`() {
        render(withRecipients())

        compose.onNodeWithTag("recipient_row_theo").assertContentDescriptionContains("Theo Diaz, not delivered yet")
    }

    @Test
    fun `tapping a recipient opens their profile`() {
        var opened: String? = null
        render(withRecipients(), onOpenProfile = { opened = it })

        compose.onNodeWithTag("recipient_row_theo").performClick()
        assertEquals("theo", opened)
    }

    @Test
    fun `everyone has it, so there is no waiting header at all`() {
        render(withRecipients().copy(waitingOn = emptyList(), recipientTotal = 2))

        compose.onNodeWithText("Delivered to 2 of 2").assertIsDisplayed()
        compose.onNodeWithTag("message_details_waiting_header").assertDoesNotExist()
    }

    @Test
    fun `the broadcast room's open list drops the denominator and says it is open`() {
        // No roster means no denominator and no "waiting on" — and the list is who answered, not who
        // received it, which the header claims and the note under the rows spells out (ADR 2026-09.aa27).
        render(withRecipients().copy(waitingOn = emptyList(), recipientTotal = 0))

        compose.onNodeWithTag("message_details_delivered_header").assertTextEquals("Confirmed delivered to")
        compose.onNodeWithTag("message_details_open_list_note").assertIsDisplayed()
        compose.onNodeWithTag("message_details_waiting_header").assertDoesNotExist()
    }

    @Test
    fun `a group send has a roster to account for, so it gets no open-list note`() {
        render(withRecipients())

        compose.onNodeWithTag("message_details_open_list_note").assertDoesNotExist()
    }

    @Test
    fun `a room post nobody has answered yet shows no list and no note`() {
        // Nothing recorded means we know nothing, not that nobody received it: with no rows to qualify,
        // the note would be the only thing on screen and would read as a failure.
        render(populated())

        compose.onNodeWithTag("message_details_delivered_header").assertDoesNotExist()
        compose.onNodeWithTag("message_details_open_list_note").assertDoesNotExist()
    }

    @Test
    fun `a message with no delivery split shows neither header`() {
        render(populated())

        compose.onNodeWithTag("message_details_delivered_header").assertDoesNotExist()
        compose.onNodeWithTag("message_details_waiting_header").assertDoesNotExist()
    }

    @Test
    fun `an inbound message shows when it arrived, beside the sender's own sent time`() {
        render(populated().copy(mine = false, senderName = "Sam Rivera", arrivedAt = 1_700_000_300_000L))

        compose.onNodeWithTag("message_details_sent_at").assertIsDisplayed()
        compose.onNodeWithTag("message_details_arrived_at").assertIsDisplayed()
    }

    @Test
    fun `a message we sent shows no arrival time — we did not receive it`() {
        // Belt and braces: `mine` alone suppresses the line, and the column is null on our sends anyway.
        render(populated().copy(arrivedAt = 1_700_000_300_000L))

        compose.onNodeWithTag("message_details_arrived_at").assertDoesNotExist()
    }

    @Test
    fun `an inbound message stored before the column shows no arrival line rather than a zero`() {
        render(populated().copy(mine = false, senderName = "Sam Rivera", arrivedAt = null))

        compose.onNodeWithTag("message_details_arrived_at").assertDoesNotExist()
    }

    @Test
    fun `a DM we sent shows when it was delivered`() {
        render(populated().copy(deliveredAt = 1_700_000_060_000L))

        compose.onNodeWithTag("message_details_delivered_at").assertIsDisplayed()
    }

    @Test
    fun `a message with no delivery time shows no delivered line`() {
        render(populated())

        compose.onNodeWithTag("message_details_delivered_at").assertDoesNotExist()
    }

    private fun withRecipients() =
        populated().copy(
            showRecipients = true,
            deliveredTo =
                listOf(
                    RecipientRow("sam", "Sam Rivera", null, 1_700_000_060_000L),
                    RecipientRow("priya", "Priya Nair", null, 1_700_000_120_000L),
                ),
            waitingOn = listOf(RecipientRow("theo", "Theo Diaz", null, null)),
            recipientTotal = 3,
        )

    private fun populated() =
        MessageDetailsUiState(
            messageId = "m1",
            body = "Works for me. I'll grab snacks.",
            mine = true,
            senderName = "You",
            senderNodeId = "me",
            sentAt = 1_700_000_000_000L,
            delivery = DeliveryStatus.Delivered,
            plane = DeliveryPlane.Internet,
            reactors =
                listOf(
                    ReactorRow("sam", "Sam Rivera", null, "👍", 10L, isSelf = false),
                    ReactorRow("priya", "Priya Nair", null, "👍", 20L, isSelf = false),
                    ReactorRow("theo", "Theo Diaz", null, "👍", 30L, isSelf = false),
                    ReactorRow("me", "Alice", null, "❤️", 40L, isSelf = true),
                ),
            filters = listOf(ReactionFilter("👍", 3), ReactionFilter("❤️", 1)),
        )

    /**
     * The body line used to read "📷 Photo" for *every* attachment, including a voice note and a file.
     * One label shared with the chat list now, so a file is named and sized in both places.
     */
    @Test
    fun `an attachment-only message is named by what it actually is`() {
        val base =
            MessageDetailsUiState(
                messageId = "m1",
                hasAttachment = true,
                senderName = "Sam Rivera",
                sentAt = 1_700_000_000_000L,
            )

        render(base.copy(attachmentMime = "image/jpeg"))
        compose.onNodeWithTag("message_details_body").assertTextEquals("📷 Photo")
    }

    @Test
    fun `a voice note says so rather than calling itself a photo`() {
        render(
            MessageDetailsUiState(
                messageId = "m1",
                hasAttachment = true,
                attachmentMime = "audio/aac",
                senderName = "Sam Rivera",
                sentAt = 1_700_000_000_000L,
            ),
        )

        compose.onNodeWithTag("message_details_body").assertTextEquals("🎤 Voice message")
    }

    @Test
    fun `a file is named and sized`() {
        render(
            MessageDetailsUiState(
                messageId = "m1",
                hasAttachment = true,
                attachmentMime = "application/pdf",
                attachmentName = "quarterly-report.pdf",
                attachmentSize = 1_400_000L,
                senderName = "Sam Rivera",
                sentAt = 1_700_000_000_000L,
            ),
        )

        compose.onNodeWithTag("message_details_body").assertTextContains("quarterly-report.pdf", substring = true)
        compose.onNodeWithTag("message_details_body").assertTextContains("MB", substring = true)
    }

    /** A non-image with no name is a shape no shipped build sends, but it must not read as a photo. */
    @Test
    fun `an unnamed non-image still reads as a file`() {
        render(
            MessageDetailsUiState(
                messageId = "m1",
                hasAttachment = true,
                attachmentMime = "application/octet-stream",
                senderName = "Sam Rivera",
                sentAt = 1_700_000_000_000L,
            ),
        )

        compose.onNodeWithTag("message_details_body").assertTextEquals("📎 File")
    }
}
