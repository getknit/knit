@file:Suppress("MagicNumber") // times, offsets and sizes are the test's subject

package app.getknit.knit.usbcontrol

import app.getknit.knit.data.message.MentionStore
import app.getknit.knit.data.message.MessageEntity
import app.getknit.knit.data.message.StyleStore
import app.getknit.knit.data.reaction.ReactionEntity
import app.getknit.knit.mesh.protocol.BodyStyle
import app.getknit.knit.mesh.protocol.Mention
import app.getknit.knit.mesh.protocol.StyleSpan
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlJsonTest {
    private val speakers = Speakers(ME, "Me Myself") { id -> if (id == BOB) "Bob (Kai)" else "?$id" }

    private fun row(
        id: String = "m1",
        sender: String = BOB,
        kind: Int = MessageEntity.KIND_NORMAL,
        body: String = "hello",
    ) = MessageEntity(id = id, senderId = sender, conversationId = BOB, body = body, sentAt = 10L, received = false, kind = kind)

    private fun JsonObject.str(key: String) = getValue(key).jsonPrimitive.content

    @Test
    fun aPlainMessageCarriesItsRowAndNamesItsSpeaker() {
        val json = ControlJson.message(row().copy(arrivedAt = 12L), speakers)
        assertEquals("m1", json.str("id"))
        assertEquals(BOB, json.str("conv"))
        assertEquals("text", json.str("kind"))
        assertEquals("Bob (Kai)", json.str("fromLabel"))
        assertFalse(json.getValue("mine").jsonPrimitive.boolean)
        assertEquals(
            12L,
            json
                .getValue("arrivedAt")
                .jsonPrimitive.content
                .toLong(),
        )
        val optional = listOf("styles", "mentions", "replyTo", "reactions", "attachment", "origin")
        assertNull("no optional block on a plain row", optional.firstOrNull { it in json })
    }

    @Test
    fun ourOwnWordsAreMineButAHeardMeshtasticPostInOurColumnIsNot() {
        val own = ControlJson.message(row(sender = ME), speakers)
        assertTrue(own.getValue("mine").jsonPrimitive.boolean)
        assertEquals("Me Myself", own.str("fromLabel"))

        val heard = ControlJson.message(row(sender = ME).copy(originNode = 0x1234L, originName = "Hilltop"), speakers)
        assertFalse(heard.getValue("mine").jsonPrimitive.boolean)
        assertEquals("Hilltop", heard.str("fromLabel"))
        assertEquals("!00001234", heard.getValue("origin").jsonObject.str("node"))
    }

    @Test
    fun aNoticeIsNamedByItsKindAndAnUnknownOneStillHasAName() {
        assertEquals("member_left", ControlJson.message(row(kind = MessageEntity.KIND_MEMBER_LEFT), speakers).str("kind"))
        assertEquals("call", ControlJson.message(row(kind = MessageEntity.KIND_CALL), speakers).str("kind"))
        assertEquals("notice_99", ControlJson.kindName(99))
        val notice = ControlJson.message(row(sender = ME, kind = MessageEntity.KIND_PEER_RENAMED), speakers)
        assertFalse("a notice is never ours", notice.getValue("mine").jsonPrimitive.boolean)
    }

    @Test
    fun whoReadItIsListedInOrderOfArrivalAndNobodyIsNoList() {
        val unread = ControlJson.message(row(sender = ME), speakers)
        assertFalse(unread.getValue("read").jsonPrimitive.boolean)
        assertFalse("nobody read it: no list", "readBy" in unread)

        val some = ControlJson.message(row(sender = ME), speakers, reads = MessageReads(read = false, listOf(BOB to 30L, "cy" to 20L)))
        assertFalse("one member of two is not the read tick", some.getValue("read").jsonPrimitive.boolean)
        val readers = some.getValue("readBy").jsonArray.map { it.jsonObject }
        assertEquals("the order given is the order listed", listOf(BOB, "cy"), readers.map { it.str("node") })
        assertEquals(listOf("30", "20"), readers.map { it.str("at") })

        val all = ControlJson.message(row(sender = ME), speakers, reads = MessageReads(read = true, listOf(BOB to 30L)))
        assertTrue(all.getValue("read").jsonPrimitive.boolean)
    }

    @Test
    fun stylesMentionsAndAQuoteComeOutAsTheirOwnObjects() {
        val styled =
            row(body = "hi @Bob (Kai)").copy(
                styles = StyleStore.encode(listOf(StyleSpan(0, 2, BodyStyle.BOLD))),
                mentions = MentionStore.encode(listOf(Mention(BOB, "Bob (Kai)"))),
                replyToId = "m0",
                replyToAuthorId = ME,
                replyToAuthor = "stale snapshot",
                replyToSnippet = "earlier",
                replyCheck = MessageEntity.REPLY_VERIFIED,
            )
        val json = ControlJson.message(styled, speakers)
        val bold = JsonArray(listOf(JsonPrimitive(0), JsonPrimitive(2), JsonPrimitive(BodyStyle.BOLD.code)))
        assertEquals(JsonArray(listOf(bold)), json.getValue("styles"))
        assertEquals(
            BOB,
            json
                .getValue("mentions")
                .jsonArray
                .single()
                .jsonObject
                .str("node"),
        )
        val quote = json.getValue("replyTo").jsonObject
        assertEquals("m0", quote.str("id"))
        assertEquals("the reader's own name for the author, not the sender's snapshot", "Me Myself", quote.str("author"))
        assertEquals("verified", quote.str("check"))
    }

    @Test
    fun onlyThisMessagesLiveReactionsAreListedByEmojiMostFirst() {
        val reactions =
            listOf(
                ReactionEntity("m1", "z", "👍", 1L),
                ReactionEntity("m1", ME, "👍", 1L),
                ReactionEntity("m1", "a", "❤️", 2L),
                ReactionEntity("m1", "gone", null, 3L),
                ReactionEntity("other", "a", "😂", 4L),
            )
        val json = ControlJson.message(row(), speakers, reactions)
        val listed = json.getValue("reactions").jsonArray.map { it.jsonObject }
        assertEquals(listOf("👍" to 2, "❤️" to 1), listed.map { it.str("emoji") to it.getValue("count").jsonPrimitive.int })
        assertEquals(listOf(true, false), listed.map { it.getValue("mine").jsonPrimitive.boolean })
        assertEquals(listOf(ME, "z"), listed[0].getValue("nodes").jsonArray.map { it.jsonPrimitive.content })
        assertFalse("reactionsTruncated" in json)
    }

    @Test
    fun aReactionFloodIsBoundedButItsCountsStayWhole() {
        // A modified client can mint as many reactors (and distinct emoji) as it likes.
        val flood = (1..2_000).map { ReactionEntity("m1", "n$it", if (it <= 1_000) "👍" else "e${it % 100}", 1L) }
        val json = ControlJson.message(row(), speakers, flood)
        val listed = json.getValue("reactions").jsonArray.map { it.jsonObject }
        assertEquals(ControlJson.MAX_REACTION_GROUPS, listed.size)
        assertEquals(1_000, listed[0].getValue("count").jsonPrimitive.int)
        assertEquals(ControlJson.MAX_REACTORS, listed[0].getValue("nodes").jsonArray.size)
        assertTrue(json.getValue("reactionsTruncated").jsonPrimitive.boolean)
        assertTrue("far under the frame cap", json.toString().length < FrameBudget.BYTES / 4)
    }

    @Test
    fun anAttachmentIsItsMetadataOnly() {
        val json =
            ControlJson.message(
                row().copy(
                    attachmentHash = "H",
                    attachmentMime = "image/jpeg",
                    attachmentName = null,
                    attachmentSize = 2048L,
                    attachmentKey = "secret",
                ),
                speakers,
            )
        val attachment = json.getValue("attachment").jsonObject
        assertEquals(setOf("hash", "mime", "name", "size", "held", "flagged"), attachment.keys)
        assertEquals(JsonNull, attachment["name"])
        assertEquals(2048, attachment.getValue("size").jsonPrimitive.int)
        assertFalse("the key never leaves the phone", json.toString().contains("secret"))
    }

    @Test
    fun anAttachmentSaysWhetherItIsHeldAndBlursAsTheChatDoes() {
        val picture = row().copy(attachmentHash = "H", attachmentMime = "image/jpeg")
        val video = row(id = "m2").copy(attachmentHash = "V", attachmentMime = "video/mp4", videoPosterFlagged = true)

        fun attachment(
            row: MessageEntity,
            facts: AttachmentFacts,
        ) = ControlJson.message(row, speakers, attachments = facts).getValue("attachment").jsonObject

        fun JsonObject.flag(key: String) = getValue(key).jsonPrimitive.boolean
        val plain = row(id = "m3").copy(attachmentHash = "P", attachmentMime = "image/png")
        val filtering = AttachmentFacts(held = setOf("H", "P"), flagged = setOf("H"), filtering = true)
        assertTrue(attachment(picture, filtering).flag("held"))
        assertTrue(attachment(picture, filtering).flag("flagged"))
        assertFalse(attachment(video, filtering).flag("held"))
        assertTrue("a flagged poster blurs its video", attachment(video, filtering).flag("flagged"))
        assertTrue(attachment(plain, filtering).flag("held"))
        assertFalse("filtering on blurs only what the filter flagged", attachment(plain, filtering).flag("flagged"))
        val off = AttachmentFacts(held = setOf("H", "P"), flagged = setOf("H"), filtering = false)
        assertFalse("with filtering off nothing is blurred", attachment(picture, off).flag("flagged"))
        assertFalse(attachment(video, off).flag("flagged"))
    }

    @Test
    fun aPreviewTrimsALongBodyAndSaysSo() {
        val long = "x".repeat(ControlJson.PREVIEW_CHARS + 5)
        val preview = ControlJson.message(row(body = long), speakers, preview = true)
        assertEquals(ControlJson.PREVIEW_CHARS, preview.str("body").length)
        assertTrue(preview.getValue("bodyTruncated").jsonPrimitive.boolean)
        assertFalse("a short body is whole", "bodyTruncated" in ControlJson.message(row(), speakers, preview = true))
    }

    @Test
    fun theBudgetKeepsAPrefixWithinTheLimitAndTheBytesButNeverNothing() {
        val items = (1..5).map { item("x".repeat(100)) }
        assertEquals(3, FrameBudget.fit(items, limit = 3).size)
        val size = items.first().toString().length + 1
        assertEquals(2, FrameBudget.fit(items, limit = 5, budget = size * 2).size)
        assertEquals("one item over the budget still goes alone", 1, FrameBudget.fit(items, limit = 5, budget = 1).size)
        assertTrue(FrameBudget.fit(emptyList(), limit = 5).isEmpty())
    }

    @Test
    fun anItemOverTheBudgetByItselfGoesAsAStubSoAPageCanMovePastIt() {
        val huge =
            buildJsonObject {
                put("id", "m1")
                put("conv", "c")
                put("sentAt", 5L)
                put("body", "x".repeat(200))
            }
        val kept = FrameBudget.fit(listOf(huge, item("y")), limit = 5, budget = 100)
        assertEquals(setOf("id", "conv", "sentAt", "oversized"), kept.first().keys)
        assertEquals("m1", kept.first().str("id"))
    }

    private fun item(body: String) = buildJsonObject { put("body", body) }

    private companion object {
        const val ME = "me-node"
        const val BOB = "bob-node"
    }
}
