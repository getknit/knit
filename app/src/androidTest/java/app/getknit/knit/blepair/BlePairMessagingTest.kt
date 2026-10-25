package app.getknit.knit.blepair

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.getknit.knit.TextLimits
import app.getknit.knit.data.message.Conversations
import app.getknit.knit.data.message.StyleStore
import app.getknit.knit.data.message.buildReplySnippet
import app.getknit.knit.mesh.TransportKind
import app.getknit.knit.mesh.protocol.BodyStyle
import app.getknit.knit.mesh.protocol.ReplyRef
import app.getknit.knit.mesh.protocol.StyleSpan
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

/** The everyday conversation between two people in range, over a real BLE link: see [BlePairTest]. */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class BlePairMessagingTest : BlePairTest() {
    @Test
    fun a_linksThePeerOverBluetooth() =
        runBlocking<Unit> {
            val started = System.currentTimeMillis()
            awaitLink()
            note("linked $peer in ${System.currentTimeMillis() - started} ms")
            val statuses = mesh.transportStatuses.value
            assertTrue(
                "a link to the peer without a Bluetooth one: $statuses",
                statuses.any { it.kind == TransportKind.Bluetooth && it.linked > 0 },
            )
            assertEquals(
                "a link on a plane an emulator has no radio for: $statuses",
                0,
                statuses.filter { it.kind != TransportKind.Bluetooth }.sumOf { it.linked },
            )
        }

    @Test
    fun b_aDmCrossesBothWaysAndItsTickComesHome() =
        runBlocking<Unit> {
            awaitLink()
            val mine = sendDm("dm from $self")
            awaitPeerDm("dm from $peer")
            awaitTick(mine)
        }

    @Test
    fun c_aNearbyPostCrossesBothWays() =
        runBlocking<Unit> {
            awaitLink()
            assertTrue("sendChat refused the post", mesh.sendChat(body("post from $self"), recipientId = null))
            awaitRow(Conversations.NEARBY, DM_TIMEOUT_MS, "the peer's Nearby post") {
                it.senderId == peer && it.body == body("post from $peer")
            }
        }

    /** Each side quotes the other's message the way the chat's swipe-to-reply does, and sees its own quoted. */
    @Test
    fun d_aReplyQuotesThePeersMessage() =
        runBlocking<Unit> {
            awaitLink()
            val target = sendDm("quote me from $self")
            val theirs = awaitPeerDm("quote me from $peer")
            val quote =
                ReplyRef(
                    messageId = theirs.id,
                    authorId = peer,
                    author = peers.find(peer)?.name.orEmpty(),
                    snippet = buildReplySnippet(theirs.body, flagged = false),
                )
            assertTrue(
                "sendChat refused the reply",
                mesh.sendChat(body("reply from $self"), recipientId = peer, replyTo = quote),
            )
            val ours = awaitRow(peer, DM_TIMEOUT_MS, "our quoted message") { it.senderId == self && it.body == target }
            val reply = awaitPeerDm("reply from $peer")
            assertEquals("the reply quotes another message", ours.id, reply.replyToId)
            assertEquals("the reply names another author", self, reply.replyToAuthorId)
            assertEquals("the quoted snippet", buildReplySnippet(target, flagged = false), reply.replyToSnippet)
        }

    @Test
    fun e_aReactionReachesThePeer() =
        runBlocking<Unit> {
            awaitLink()
            val target = sendDm("react to me from $self")
            val theirs = awaitPeerDm("react to me from $peer")
            mesh.sendReaction(theirs.id, REACTION, recipientId = peer)
            val ours = awaitRow(peer, DM_TIMEOUT_MS, "our message to react to") { it.senderId == self && it.body == target }
            runCatching {
                withTimeout(DM_TIMEOUT_MS) {
                    reactions.observeReactionsFor(ours.id).first { list ->
                        list.any { it.reactorNodeId == peer && it.emoji == REACTION }
                    }
                }
            }.onFailure { throw AssertionError("no $REACTION from the peer on our message within $DM_TIMEOUT_MS ms", it) }
        }

    /** A typing cue is best effort and fades in seconds, so it goes out the way the composer sends it, over and over. */
    @Test
    fun f_typingShowsOnThePeer() =
        runBlocking<Unit> {
            awaitLink()
            watch.launch {
                while (true) {
                    mesh.sendTyping(peer)
                    delay(TYPING_EVERY_MS)
                }
            }
            val started = System.currentTimeMillis()
            val shown =
                runCatching {
                    withTimeout(DM_TIMEOUT_MS) { mesh.typing.first { map -> map.values.any { peer in it } } }
                }.getOrElse { throw AssertionError("the peer's typing never showed within $DM_TIMEOUT_MS ms", it) }
            note("the peer's typing showed in ${System.currentTimeMillis() - started} ms under ${shown.keys}")
        }

    /** A message at the length limit, in more than ASCII, crosses whole: nothing truncated, nothing re-encoded. */
    @Test
    fun g_aLongDmCrossesWhole() =
        runBlocking<Unit> {
            awaitLink()
            val mine = longText(self)
            assertTrue("sendChat refused the long DM", mesh.sendChat(mine, recipientId = peer))
            val theirs = awaitRow(peer, DM_TIMEOUT_MS, "the peer's long DM") { it.senderId == peer && it.body.startsWith(longHead(peer)) }
            assertEquals("the long DM arrived changed (${theirs.body.length} chars)", longText(peer), theirs.body)
            awaitTick(mine)
        }

    /**
     * A DM and a Nearby post each carry their formatting across (ADR 2026-10.p95v): the styles ride beside the words,
     * sealed in the DM and through the link's frame transcoder in the post, and land on the peer's row as sent.
     */
    @Test
    fun h_formattingCrossesWithTheMessage() =
        runBlocking<Unit> {
            awaitLink()
            val dm = body("formatted dm from $self")
            val post = body("formatted post from $self")
            assertTrue("sendChat refused the formatted DM", mesh.sendChat(dm, recipientId = peer, styles = formatting(dm)))
            assertTrue("sendChat refused the formatted post", mesh.sendChat(post, recipientId = null, styles = formatting(post)))

            val theirDm = awaitPeerDm("formatted dm from $peer")
            assertEquals("the DM's formatting", formatting(theirDm.body), StyleStore.decode(theirDm.styles))
            val theirPost =
                awaitRow(Conversations.NEARBY, DM_TIMEOUT_MS, "the peer's formatted post") {
                    it.senderId == peer && it.body == body("formatted post from $peer")
                }
            assertEquals("the post's formatting", formatting(theirPost.body), StyleStore.decode(theirPost.styles))
            awaitTick(dm)
            note("formatting crossed: ${theirDm.styles} on the DM, ${theirPost.styles} on the post")
        }

    /** "formatted" bold, the word after it italic, and the run tag monospace: three styles, one overlapping none. */
    private fun formatting(text: String): List<StyleSpan> {
        val word = text.indexOf("formatted")
        val next = word + "formatted ".length
        val nextEnd = text.indexOf(' ', next).takeIf { it >= 0 } ?: text.length
        val tag = text.indexOf(run)
        return listOf(
            StyleSpan(tag, tag + run.length, BodyStyle.MONOSPACE),
            StyleSpan(word, word + "formatted".length, BodyStyle.BOLD),
            StyleSpan(next, nextEnd, BodyStyle.ITALIC),
        ).sortedWith(compareBy({ it.start }, { it.style.code }))
    }

    private fun longHead(author: String) = body("long from $author:")

    /** Up to [TextLimits.MESSAGE] chars of numbered chunks with accents, a symbol and an emoji (a surrogate pair). */
    private fun longText(author: String): String {
        val text = StringBuilder(longHead(author))
        var i = 0
        while (true) {
            val chunk = " #${i++} ünïcødé ✓ 😀"
            if (text.length + chunk.length > TextLimits.MESSAGE) return text.toString()
            text.append(chunk)
        }
    }

    private companion object {
        const val REACTION = "👍" // 👍
        const val TYPING_EVERY_MS = 2_000L
    }
}
