@file:Suppress("MagicNumber") // times and positions are the test's subject

package app.getknit.knit.usbcontrol

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChangeFeedTest {
    /** The app as the feed reads it: rows the test puts and takes away. */
    private class FakeHydrator : Hydrator {
        val rows = mutableMapOf<String, String>() // message id -> conv
        val bodies = mutableMapOf<String, String>()
        val chats = mutableMapOf<String, String>() // conv -> title
        val groups = mutableMapOf<String, List<String>>()
        var reads = 0
        var chatReads = 0

        override suspend fun messages(ids: Collection<String>): Map<String, JsonObject> {
            reads += ids.size
            return ids.filter { it in rows }.associateWith { id ->
                buildJsonObject {
                    put("id", id)
                    put("conv", rows.getValue(id))
                    put("body", bodies[id].orEmpty())
                }
            }
        }

        override suspend fun chats(conversations: Collection<String>): Map<String, JsonObject> {
            chatReads++
            return conversations.filter { it in chats }.associateWith { conv ->
                buildJsonObject {
                    put("conv", conv)
                    put("title", chats.getValue(conv))
                }
            }
        }

        override suspend fun contact(nodeId: String): JsonObject = buildJsonObject { put("id", nodeId) }

        override suspend fun groupsWith(nodeId: String): List<String> = groups[nodeId].orEmpty()

        /** Attachment hash -> the message ids naming it (their threads from [rows]). */
        val naming = mutableMapOf<String, List<String>>()

        override suspend fun namingAttachment(
            hash: String,
            limit: Int,
        ): List<Pair<String, String>> = naming[hash].orEmpty().take(limit).map { it to rows.getValue(it) }

        override suspend fun identity(): JsonObject = buildJsonObject { put("node", "me") }

        override suspend fun status(): JsonObject = buildJsonObject { put("health", "Healthy") }

        /** Who the app shows typing: each thread to its typers. */
        val typers = mutableMapOf<String, String>()

        override suspend fun typing(): JsonObject = buildJsonObject { typers.forEach { (conv, node) -> put(conv, node) } }
    }

    private var now = 0L
    private var epochs = 0
    private val log = ChangeLog(capacity = 64) { "e${++epochs}" }.apply { setRecording(true) }
    private val app = FakeHydrator()
    private val feed = ChangeFeed(log, app, clock = { now }, maxPending = 8)

    private val JsonObject.name get() = getValue("event").jsonPrimitive.content

    /** Advances to the next due read and runs it; what it sent. */
    private suspend fun step(): List<JsonObject> {
        feed.pull()
        val due = feed.nextDueAt() ?: return emptyList()
        now = maxOf(now, due)
        return feed.runDue()
    }

    /** Runs every read until nothing is due; everything sent. */
    private suspend fun drain(): List<JsonObject> {
        val out = mutableListOf<JsonObject>()
        repeat(100) {
            feed.pull()
            if (feed.nextDueAt() == null) return out
            out += step()
        }
        error("the feed never settled")
    }

    /** Subscribes and takes the opening status and typing events. */
    private suspend fun subscribe(since: ChangeCursor? = null): SubscribeResult {
        val result = feed.start(since)
        val opening = feed.runDue()
        assertEquals(CURRENT, opening.map { it.name })
        return result
    }

    @Test
    fun aMessageIsReadAShortWaitAfterItsWriteAndSentWithItsThread() =
        runTest {
            subscribe()
            app.rows["m1"] = "c"
            app.chats["c"] = "Chat"
            log.record(ChangeKey.Message("m1", "c"))
            feed.pull()
            assertEquals("nothing is read before the wait", emptyList<JsonObject>(), feed.runDue())
            assertEquals(100L, feed.nextDueAt())
            val sent = step()
            assertEquals(listOf("message", "chat"), sent.map { it.name })
            assertEquals(
                "m1",
                sent[0]
                    .getValue("message")
                    .jsonObject
                    .getValue("id")
                    .jsonPrimitive.content,
            )
        }

    @Test
    fun aRowNotYetCommittedAtTheFirstReadIsSentWhenALaterReadFindsIt() =
        runTest {
            subscribe()
            log.record(ChangeKey.Message("m1", null))
            val first = step()
            assertEquals("absent is the committed truth: deleted, or never was", listOf("message.deleted"), first.map { it.name })
            app.rows["m1"] = "c" // the caller's transaction commits
            assertEquals(listOf("message"), step().map { it.name })
            val later = drain().map { it.name }.filter { it.startsWith("message") }
            assertEquals("the last read finds nothing new", emptyList<String>(), later)
        }

    @Test
    fun aRowThatChangesBetweenReadsIsSentAgainAndAnUnchangedOneIsNot() =
        runTest {
            subscribe()
            app.rows["m1"] = "c"
            log.record(ChangeKey.Message("m1", "c"))
            assertEquals(listOf("message", "chat.removed"), step().map { it.name })
            app.bodies["m1"] = "✓✓" // a receipt's tick, committed after the first read
            assertEquals(listOf("message"), step().map { it.name })
            assertEquals(emptyList<String>(), drain().map { it.name })
        }

    @Test
    fun aDeletedRowIsSentAsDeleted() =
        runTest {
            subscribe()
            app.rows["m1"] = "c"
            log.record(ChangeKey.Message("m1", "c"))
            drain()
            app.rows.remove("m1")
            log.record(ChangeKey.Message("m1", "c"))
            val sent = step().filter { it.name.startsWith("message") }
            assertEquals(listOf("message.deleted"), sent.map { it.name })
            assertEquals(
                "c",
                sent
                    .single()
                    .getValue("conv")
                    .jsonPrimitive.content,
            )
        }

    @Test
    fun writesToOneObjectInABurstAreReadOnce() =
        runTest {
            subscribe()
            app.rows["m1"] = "c"
            repeat(5) { log.record(ChangeKey.Message("m1", "c")) }
            step()
            assertEquals(1, app.reads)
        }

    @Test
    fun aSteadyStreamCannotPostponeItsThreadsFirstRead() =
        runTest {
            subscribe()
            app.chats["c"] = "Chat"
            for (i in 0 until 5) {
                app.rows["m$i"] = "c"
                log.record(ChangeKey.Message("m$i", "c"))
                feed.pull()
                now += 50
            }
            // At 250 the thread's first read (due at 100) has come; a merge that pushed it to the last write's 300 would not.
            assertTrue("chat" in step().map { it.name })
        }

    @Test
    fun aKeyWithoutItsThreadLearnsItFromTheRow() =
        runTest {
            subscribe()
            app.rows["m1"] = "c"
            app.chats["c"] = "Chat"
            log.record(ChangeKey.Message("m1", null))
            val names = drain().map { it.name }
            assertTrue(names.toString(), "chat" in names)
        }

    @Test
    fun aContactsChangeRetitlesTheirDmAndTheirGroups() =
        runTest {
            subscribe()
            app.chats["bob"] = "Bob"
            app.chats["g-1"] = "Bob, Cy"
            app.groups["bob"] = listOf("g-1")
            log.record(ChangeKey.Contact("bob"))
            val sent = drain()
            assertEquals(listOf("contact", "chat", "chat"), sent.map { it.name })
            assertEquals("the two threads due together are read in one pass", 3, app.chatReads)
            val chats =
                sent.drop(1).map {
                    it
                        .getValue("chat")
                        .jsonObject
                        .getValue("conv")
                        .jsonPrimitive.content
                }
            assertEquals(setOf("bob", "g-1"), chats.toSet())
        }

    @Test
    fun aTrimmedThreadIsResetAtOnce() =
        runTest {
            subscribe()
            log.record(ChangeKey.Thread("c"))
            feed.pull()
            assertEquals(0L, feed.nextDueAt())
            assertEquals(listOf("thread.reset"), feed.runDue().map { it.name })
        }

    @Test
    fun tooManyWaitingChangesSendAGapAndStartOver() =
        runTest {
            subscribe()
            repeat(9) { log.record(ChangeKey.Chat("c$it")) }
            feed.pull()
            val sent = feed.runDue()
            assertEquals(listOf("gap") + CURRENT, sent.map { it.name })
            assertEquals(log.latest(), feed.watermark())
            assertNull("nothing left waiting", feed.nextDueAt())
        }

    @Test
    fun aResyncKeySendsAGap() =
        runTest {
            subscribe()
            log.record(ChangeKey.Chat("c"))
            log.record(ChangeKey.Resync("the blocked set changed"))
            feed.pull()
            val sent = feed.runDue()
            assertEquals(listOf("gap") + CURRENT, sent.map { it.name })
            assertEquals(
                "the blocked set changed",
                sent
                    .first()
                    .getValue("reason")
                    .jsonPrimitive.content,
            )
        }

    @Test
    fun theWatermarkWaitsForEveryReadOfEveryKeyBeforeIt() =
        runTest {
            val start = subscribe().cursor
            app.rows["m1"] = "c"
            log.record(ChangeKey.Message("m1", "c"))
            val sent = step()
            assertEquals(
                "held at the start until m1's last read",
                start.seq,
                sent
                    .first()
                    .getValue("seq")
                    .jsonPrimitive.long,
            )
            drain()
            assertEquals(log.latest(), feed.watermark())
        }

    @Test
    fun aStatusTouchNeverHoldsTheWatermark() =
        runTest {
            subscribe()
            feed.touch(ChangeKey.Status)
            assertEquals(log.latest(), feed.watermark())
            assertEquals(listOf("status"), feed.runDue().map { it.name })
        }

    @Test
    fun aTypingTouchSendsWhoIsTypingNowAndNeverHoldsTheWatermark() =
        runTest {
            subscribe()
            app.typers["bob"] = "bob-node"
            feed.touch(ChangeKey.Typing)
            assertEquals(log.latest(), feed.watermark())
            val sent = feed.runDue().single()
            assertEquals("typing", sent.name)
            assertEquals(
                "bob-node",
                sent
                    .getValue("typing")
                    .jsonObject
                    .getValue("bob")
                    .jsonPrimitive.content,
            )
            assertNull("read once, at once", feed.nextDueAt())
        }

    @Test
    fun aTypingChangeAGapDroppedIsSentAfterTheGapAsItStandsThen() =
        runTest {
            subscribe()
            app.typers["bob"] = "stale"
            feed.touch(ChangeKey.Typing)
            repeat(9) { log.record(ChangeKey.Chat("c$it")) }
            app.typers["bob"] = "fresh"
            feed.pull()
            val sent = feed.runDue()
            assertEquals(listOf("gap") + CURRENT, sent.map { it.name })
            assertEquals(
                "fresh",
                sent
                    .last()
                    .getValue("typing")
                    .jsonObject
                    .getValue("bob")
                    .jsonPrimitive.content,
            )
        }

    @Test
    fun aReconnectWithinTheRingReplaysWhatItMissed() =
        runTest {
            val cursor = subscribe().cursor
            feed.stop()
            app.rows["m1"] = "c"
            log.record(ChangeKey.Message("m1", "c"))
            val result = subscribe(cursor)
            assertFalse(result.resync)
            assertEquals(cursor, result.cursor)
            assertTrue("message" in drain().map { it.name })
        }

    @Test
    fun aReconnectFromAnotherEpochIsToldToResync() =
        runTest {
            val result = subscribe(ChangeCursor("old", 3))
            assertTrue(result.resync)
            assertEquals(log.latest(), result.cursor)
            assertFalse("a fresh subscribe is not a resync", feed.start(null).resync)
        }

    @Test
    fun aLogThatMovesOnWithoutTheFeedIsAGap() =
        runTest {
            subscribe()
            log.setRecording(false)
            log.setRecording(true)
            feed.pull()
            assertEquals(listOf("gap") + CURRENT, feed.runDue().map { it.name })
        }

    @Test
    fun aStoppedFeedReadsNothing() =
        runTest {
            subscribe()
            feed.stop()
            log.record(ChangeKey.Chat("c"))
            feed.pull()
            feed.touch(ChangeKey.Status)
            assertNull(feed.nextDueAt())
            assertNull(feed.watermark())
            assertFalse(feed.active)
        }

    @Test
    fun aBlobLandingReadsEveryMessageNamingItAgain() =
        runTest {
            app.rows["m1"] = "c"
            app.rows["m2"] = "d"
            app.naming["h"] = listOf("m1", "m2")
            subscribe()
            log.record(ChangeKey.Attachment("h"))
            val sent = drain()
            val ids =
                sent.filter { it.name == "message" }.map {
                    it
                        .getValue("message")
                        .jsonObject
                        .getValue("id")
                        .jsonPrimitive.content
                }
            assertEquals(setOf("m1", "m2"), ids.toSet())
            app.bodies["m1"] = "held" // the next landing moved m1
            log.record(ChangeKey.Attachment("h"))
            val again = drain().filter { it.name == "message" }.map { it.getValue("message").jsonObject }
            assertEquals(
                "held",
                again
                    .single { it.getValue("id").jsonPrimitive.content == "m1" }
                    .getValue("body")
                    .jsonPrimitive.content,
            )
        }

    @Test
    fun aBlobNamedByMoreMessagesThanTheBoundIsAGapAndOneAtTheBoundIsNot() =
        runTest {
            // Room for every key the bound lets through, so only the bound can turn this into a gap.
            val roomy = ChangeFeed(log, app, clock = { now }, maxPending = 4 * ChangeFeed.MAX_NAMING)
            val ids = (0..ChangeFeed.MAX_NAMING).map { "m$it" }
            ids.forEach { app.rows[it] = "c" }

            app.naming["at"] = ids.take(ChangeFeed.MAX_NAMING)
            roomy.start(null)
            log.record(ChangeKey.Attachment("at"))
            val atBound = roomy.drainAll()
            assertFalse("gap" in atBound.map { it.name })
            assertEquals(
                ChangeFeed.MAX_NAMING,
                atBound
                    .filter {
                        it.name == "message"
                    }.map { it.getValue("message").jsonObject.getValue("id") }
                    .toSet()
                    .size,
            )

            app.naming["over"] = ids
            app.reads = 0
            log.record(ChangeKey.Attachment("over"))
            val over = roomy.drainAll()
            assertEquals(listOf("gap") + CURRENT, over.map { it.name }.distinct())
            assertEquals("no message was read for it", 0, app.reads)
        }

    private companion object {
        /** The current state the log never holds, which opens every subscribe and follows every gap. */
        val CURRENT = listOf("status", "typing")
    }

    /** Runs [this] feed's reads until nothing is due; everything it sent. */
    private suspend fun ChangeFeed.drainAll(): List<JsonObject> {
        val out = mutableListOf<JsonObject>()
        repeat(1_000) {
            pull()
            val due = nextDueAt() ?: return out
            now = maxOf(now, due)
            out += runDue()
        }
        error("the feed never settled")
    }
}
