package app.getknit.knit.uiauto

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.Direction
import app.getknit.knit.R
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Black-box coverage of the one path issue #5 actually asks for: long-press a message → "Message info" →
 * see *who* reacted, and — for a message you sent to a group — *who has it*. It has to be driven from here
 * rather than the Compose suite because the long-press
 * menu is a `Popup` — a separate window that doesn't inherit the NavHost's `testTagsAsResourceId`, so its
 * items are addressed by their (localized) text, exactly as the group-management and overflow tests do.
 *
 * The seeded "Trailhead Crew" message is one of ours, carries three reactors across two emoji, and is
 * delivered to two of its three other members (`DemoScenarios`), so this exercises the populated screen on
 * both halves — the delivered/waiting split as well as the reactor list — and the reactor tap proves the
 * profile hop.
 */
@RunWith(AndroidJUnit4::class)
class MessageDetailsUiAutomatorTest : SeededUiAutomatorTest() {
    @Test
    fun longPress_messageInfo_listsReactorsAndOpensAProfile() {
        openGroupChat()

        // The chat opens on its newest message and the seeded one sits a few bubbles above it, so on a short
        // screen it is scrolled out — and a LazyColumn has no node for a row it has not composed.
        scrollToText(SEEDED_BODY, Direction.UP).longClick() // the bubble's long-press menu (a Popup)
        requireText(str(R.string.chat_action_details)).click() // "Message info"

        assertText(str(R.string.message_details_title))
        assertTag("screen_message_details")
        // Top to bottom, scrolling as we go: the summary, the delivered and waiting-on sections and the
        // reactors are one LazyColumn, and how much of it fits depends on the screen.
        // The ✓✓ said "delivered"; this is the part it could never say — two of the three others have it,
        // and the third is named rather than silently folded into the tick.
        scrollToTag("message_details_delivered_header")
        scrollToTag("recipient_row_$SAM")
        scrollToTag("recipient_row_$THEO")
        // The chip said "👍 3"; this is the part it could never say.
        val samReacted = scrollToTag("reactor_row_$SAM")
        assertText(SAM_NAME)

        samReacted.click()
        // The peer profile's top bar carries the contact's name, not the word "Profile" (ADR 2026-09.hknx),
        // so the name is what proves we landed on Sam's profile rather than anyone's.
        assertTag("screen_profile_details")
        assertText(SAM_NAME)
    }

    /**
     * Chat list → the "Trailhead Crew" row → the group chat, proven by its top-bar avatar. One attempt: [launch]
     * waits out the seed, so the list has stopped reflowing before the tap.
     */
    private fun openGroupChat() {
        launch()
        requireDesc(GROUP_NAME).click()
        assertTag("chat_group_avatar")
    }

    private companion object {
        const val GROUP_NAME = "Trailhead Crew"

        /** The seeded group message that carries the multi-reactor cluster (`DemoScenarios`, demo-group-4). */
        const val SEEDED_BODY = "Works for me"

        // DemoSeeder.SAM / the hiking scenario's name for that slot, spelled out so this test reads as the
        // black-box check it is (the uiauto suite never reaches into the debug seed classes).
        const val SAM = "samr1v00"
        const val SAM_NAME = "Sam Rivera"

        /**
         * The one seeded member the message has NOT reached — the "waiting on" half. `theob123` is
         * `DemoSeeder.THEO`; the id in `MessageDetailsScreen`'s @Preview fixture ("Theo Diaz",
         * `theod001`) is a different person and was never seeded, so don't copy it back from there.
         */
        const val THEO = "theob123"
    }
}
