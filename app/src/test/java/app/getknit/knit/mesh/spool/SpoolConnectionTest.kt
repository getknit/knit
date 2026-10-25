package app.getknit.knit.mesh.spool

import app.getknit.knit.mesh.crypto.scope.ScopeCrypto
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The §7 record state machine: hello negotiation, `q` correlation, the per-connection subscription set,
 * and the three v1 behaviors that are easy to get wrong — the spool speaks first, `blob` carries no `q`,
 * and a scoped `err` is the SUB refusal rather than a correlated response.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SpoolConnectionTest {
    private class RecordingLink : SpoolLink {
        val sent = mutableListOf<ByteArray>()
        var closedWith: Pair<Int, String>? = null

        override fun send(bytes: ByteArray): Boolean {
            sent.add(bytes)
            return true
        }

        override fun close(
            code: Int,
            reason: String,
        ) {
            closedWith = code to reason
        }

        inline fun <reified T> last(): T = SpoolCodec.decode<T>(sent.last())!!
    }

    private val scope = ByteArray(32) { it.toByte() }
    private val urls = listOf("turn:spool.example:3478?transport=udp")
    private val other = ByteArray(32) { (it + 1).toByte() }
    private val attachment = ByteArray(32) { (it + 2).toByte() }

    private fun connection(
        link: SpoolLink,
        onDigest: suspend (SpoolDigest) -> Unit = {},
        onEvent: suspend (SpoolEvent) -> Unit = {},
        onScopeError: suspend (String?, String, Long?) -> Unit = { _, _, _ -> },
    ) = SpoolConnection("ws://spool/spool/v1", link, onDigest, onEvent, onScopeError)

    private fun serverHello(
        v: Int = SPOOL_RECORD_VERSION,
        min: Int = SPOOL_RECORD_VERSION,
        powBits: Int = 0,
        limits: SpoolLimits =
            SpoolLimits(maxBlob = 64, maxRecord = 4096, maxScopes = 8, maxPull = 2, maxFramesCap = 10, maxTtlMs = 1),
        turn: Boolean? = null,
    ) = SpoolCodec.encode(SpoolHello(t = SpoolRecordType.HELLO, v = v, min = min, limits = limits, powBits = powBits, turn = turn))

    /** A spool whose advertised caps are enormous — the numbers a hostile one would send. */
    private fun greedyLimits(
        maxBlob: Int = 1 shl 20,
        maxPull: Int = Int.MAX_VALUE,
    ) = SpoolLimits(
        maxBlob = maxBlob,
        maxRecord = Int.MAX_VALUE,
        maxScopes = 8,
        maxPull = maxPull,
        maxFramesCap = 10,
        maxTtlMs = 1,
        maxAttachBytes = Int.MAX_VALUE,
        maxAChunk = Int.MAX_VALUE,
        maxAget = Int.MAX_VALUE,
    )

    private fun id(seed: Int) = ByteArray(32) { (it + seed).toByte() }

    private fun blob(
        blobId: ByteArray,
        data: ByteArray,
    ) = SpoolCodec.encode(SpoolBlob(t = SpoolRecordType.BLOB, scope = scope, blobId = blobId, data = data))

    private fun achunk(
        idx: Int,
        data: ByteArray = byteArrayOf(9),
        aid: ByteArray = attachment,
    ) = SpoolCodec.encode(
        SpoolAchunk(t = SpoolRecordType.ACHUNK, scope = scope, aid = aid, idx = idx, total = 4, cid = id(3), data = data),
    )

    private fun ok(q: Long) = SpoolCodec.encode(SpoolOk(t = SpoolRecordType.OK, q = q))

    private fun ahas(
        q: Long,
        scope: ByteArray = this.scope,
        aid: ByteArray = attachment,
    ) = SpoolCodec.encode(
        SpoolAhas(t = SpoolRecordType.AHAS, q = q, scope = scope, aid = aid, total = 4, bits = byteArrayOf(0xF0.toByte())),
    )

    private fun listing(
        q: Long,
        scope: ByteArray = this.scope,
    ) = SpoolCodec.encode(SpoolList(t = SpoolRecordType.LIST, q = q, scope = scope, blobIds = listOf(id(5)), tombstones = null))

    @Test
    fun `answers the spool's unprompted hello with nothing but the chosen version`() =
        runTest {
            val link = RecordingLink()
            val conn = connection(link)
            conn.onMessage(serverHello(powBits = 20))

            assertTrue(conn.awaitReady())
            assertEquals(1, link.sent.size)
            // Byte-identical to the spec §13 `helloClient` vector: {"t":"hello","v":1} and nothing else.
            assertEquals("a261746568656c6c6f617601", hex(link.sent.single()))
            assertEquals(20, conn.powBits)
            assertEquals(2, conn.limits?.maxPull)
        }

    @Test
    fun `closes 4002 and never sends a hello when the versions do not overlap`() =
        runTest {
            val link = RecordingLink()
            val conn = connection(link)
            conn.onMessage(serverHello(v = 99, min = 7))

            assertFalse(conn.awaitReady())
            assertEquals(SpoolCloseCode.VERSION to "no version overlap", link.closedWith)
            assertTrue(link.sent.isEmpty())
        }

    @Test
    fun `sub marks the scope subscribed and a scoped err un-marks it`() =
        runTest {
            val link = RecordingLink()
            var reported: Triple<String?, String, Long?>? = null
            val conn = connection(link, onScopeError = { s, c, r -> reported = Triple(s, c, r) })
            conn.onMessage(serverHello())

            conn.sub(listOf(ScopeSub(scope = scope, bounds = ScopeRegistry.DEFAULT_BOUNDS)))
            assertTrue(conn.isSubscribed(hex(scope)))

            // A SUB refusal carries the SUB's q, which correlates to nothing outstanding — it is routed as
            // a scope error, not silently dropped, and the scope must stop counting as subscribed.
            conn.onMessage(
                SpoolCodec.encode(SpoolErr(t = SpoolRecordType.ERR, code = SpoolErrCode.POW, q = 99, scope = scope)),
            )
            assertFalse(conn.isSubscribed(hex(scope)))
            assertEquals(Triple(hex(scope), SpoolErrCode.POW, null), reported)
        }

    @Test
    fun `correlates concurrent requests by q and attributes blobs to the pull, not a parallel list`() =
        runTest {
            val link = RecordingLink()
            val conn = connection(link)
            conn.onMessage(serverHello())

            var listing: SpoolReply.Listing? = null
            var pulled: PullOutcome? = null
            launch { listing = conn.list(scope) }
            runCurrent()
            val listQ = link.last<SpoolList>().q
            // Two ids, so the spool can serve one and report the other gone — `missing` answers *this*
            // request, so it is only meaningful for ids the request itself named.
            launch { pulled = conn.pull(scope, listOf(other, id(5))) }
            runCurrent()
            val pullQ = link.last<SpoolPull>().q
            assertTrue("q must increase monotonically per connection", pullQ > listQ)

            // A BLOB carries no q: it must land in the in-flight PULL even though a LIST for the same
            // scope is outstanding, and the two terminal responses must not cross.
            conn.onMessage(SpoolCodec.encode(SpoolBlob(t = SpoolRecordType.BLOB, scope = scope, blobId = other, data = byteArrayOf(7))))
            conn.onMessage(SpoolCodec.encode(SpoolOk(t = SpoolRecordType.OK, q = pullQ, missing = listOf(id(5)))))
            conn.onMessage(
                SpoolCodec.encode(SpoolList(t = SpoolRecordType.LIST, q = listQ, scope = scope, blobIds = listOf(other))),
            )
            runCurrent()

            assertEquals(1, pulled?.blobs?.size)
            assertEquals(hex(other), pulled?.blobs?.single()?.let { hex(it.blobId) })
            assertEquals(listOf(hex(id(5))), pulled?.missing?.map { hex(it) })
            assertEquals(listOf(hex(other)), listing?.blobIds?.map { hex(it) })
        }

    @Test
    fun `a dead socket fails every outstanding request instead of hanging the heal loop`() =
        runTest {
            val link = RecordingLink()
            val conn = connection(link)
            conn.onMessage(serverHello())

            var reply: SpoolReply? = null
            launch { reply = conn.push(scope, other, byteArrayOf(1)) }
            runCurrent()
            conn.onClosed()
            runCurrent()

            assertEquals(SpoolReply.Closed, reply)
            assertFalse(conn.isSubscribed(hex(scope)))
        }

    @Test
    fun `a request the spool never answers times out instead of wedging the heal loop`() =
        runTest {
            val link = RecordingLink()
            val conn = connection(link)
            conn.onMessage(serverHello())

            var reply: SpoolReply? = null
            launch { reply = conn.push(scope, other, byteArrayOf(1)) }
            runCurrent()
            assertNull("still waiting while the spool stays silent", reply)
            // A spool that accepts a record and answers nothing must not park this caller forever: the
            // next heal round re-derives the diff, and a re-PUSH is byte-identical anyway.
            advanceTimeBy(60_000)
            runCurrent()

            assertEquals(SpoolReply.Closed, reply)
        }

    @Test
    fun `a spool that never advertised turn is never sent a turnreq`() =
        runTest {
            val link = RecordingLink()
            val conn = connection(link)
            conn.onMessage(serverHello())
            val before = link.sent.size

            // C-7.6-3: it would skip the record unanswered, and the call would wait out the request for nothing.
            assertFalse(conn.turn)
            assertEquals(SpoolReply.Closed, conn.turnCredential(3_000))
            assertEquals(before, link.sent.size)
        }

    @Test
    fun `a turncred answers its turnreq and nothing else does`() =
        runTest {
            val link = RecordingLink()
            val conn = connection(link)
            conn.onMessage(serverHello(turn = true))
            assertTrue(conn.turn)

            var turn: SpoolReply? = null
            var push: SpoolReply? = null
            launch { turn = conn.turnCredential(3_000) }
            runCurrent()
            val turnQ = link.last<SpoolTurnReq>().q
            launch { push = conn.push(scope, other, byteArrayOf(1)) }
            runCurrent()
            val pushQ = link.last<SpoolPush>().q

            // A turncred naming the PUSH's q is no answer to it: the TURNREQ names no scope, so the marker decides.
            conn.onMessage(
                SpoolCodec.encode(SpoolTurnCred(t = SpoolRecordType.TURNCRED, q = pushQ, urls = urls, user = "u", pass = "p", exp = 9)),
            )
            runCurrent()
            assertNull(push)
            assertNull(turn)

            conn.onMessage(
                SpoolCodec.encode(
                    SpoolTurnCred(t = SpoolRecordType.TURNCRED, q = turnQ, urls = urls, user = "u", pass = "s3cret", exp = 9),
                ),
            )
            runCurrent()
            val cred = turn as SpoolReply.Turn
            assertEquals(urls, cred.urls)
            assertEquals(9L, cred.exp)
            assertEquals("s3cret", cred.pass)
            assertFalse("the password never reaches a log line", "s3cret" in cred.toString())
            assertNull(push)

            // ...and an `ok` that lands on a TURNREQ's q is a failure there, never a credential.
            var second: SpoolReply? = null
            launch { second = conn.turnCredential(3_000) }
            runCurrent()
            conn.onMessage(SpoolCodec.encode(SpoolOk(t = SpoolRecordType.OK, q = link.last<SpoolTurnReq>().q)))
            runCurrent()
            assertEquals(SpoolReply.Closed, second)
        }

    @Test
    fun `a refused turnreq reports its code and retry`() =
        runTest {
            val link = RecordingLink()
            val conn = connection(link)
            conn.onMessage(serverHello(turn = true))

            var reply: SpoolReply? = null
            launch { reply = conn.turnCredential(3_000) }
            runCurrent()
            val q = link.last<SpoolTurnReq>().q
            conn.onMessage(SpoolCodec.encode(SpoolErr(t = SpoolRecordType.ERR, code = SpoolErrCode.RATE, q = q, retryMs = 5_000)))
            runCurrent()

            val failed = reply as SpoolReply.Failed
            assertEquals(SpoolErrCode.RATE, failed.code)
            assertEquals(5_000L, failed.retryMs)
        }

    @Test
    fun `a silent turnreq costs its short wait and never a strike against the connection`() =
        runTest {
            val link = RecordingLink()
            val conn = connection(link)
            conn.onMessage(serverHello(turn = true))

            repeat(SpoolConnection.MAX_SILENT_REQUESTS + 1) {
                var reply: SpoolReply? = null
                launch { reply = conn.turnCredential(3_000) }
                runCurrent()
                advanceTimeBy(3_001)
                runCurrent()
                assertEquals(SpoolReply.Closed, reply)
            }
            // The sync plane's requests own the unresponsive counter; a call's impatience is not evidence.
            assertNull(link.closedWith)
            assertTrue(conn.isReady)
        }

    @Test
    fun `a hung-up call's turnreq leaves nothing waiting on the connection`() =
        runTest {
            val link = RecordingLink()
            val conn = connection(link)
            conn.onMessage(serverHello(turn = true))

            val asking = launch { conn.turnCredential(3_000) }
            runCurrent()
            val q = link.last<SpoolTurnReq>().q
            asking.cancel()
            runCurrent()
            // Its entry is gone, so the late answer finds nothing to complete and counts as no reply.
            conn.onMessage(
                SpoolCodec.encode(SpoolTurnCred(t = SpoolRecordType.TURNCRED, q = q, urls = urls, user = "u", pass = "p", exp = 9)),
            )
            assertFalse(conn.answered)
        }

    @Test
    fun `refuses to emit a record larger than the spool's advertised maxRecord`() =
        runTest {
            val link = RecordingLink()
            val conn = connection(link)
            conn.onMessage(serverHello())
            val before = link.sent.size

            val reply = conn.push(scope, other, ByteArray(8192))

            assertEquals(SpoolReply.Closed, reply)
            assertEquals("an oversize record must never reach the wire", before, link.sent.size)
        }

    @Test
    fun `ignores unknown record types and a post-negotiation hello rather than tearing down`() =
        runTest {
            val link = RecordingLink()
            val conn = connection(link)
            conn.onMessage(serverHello())
            val after = link.sent.size

            conn.onMessage(SpoolCodec.encode(SpoolRecordHead(t = "x-future")))
            conn.onMessage(serverHello(powBits = 30))
            conn.onMessage(byteArrayOf(0xFF.toByte(), 0x00))

            assertNull(link.closedWith)
            assertEquals(after, link.sent.size)
            assertEquals("a second hello must not renegotiate", 0, conn.powBits)
        }

    @Test
    fun `drops a blob the pull never asked for instead of handing it to the caller`() =
        runTest {
            val link = RecordingLink()
            val conn = connection(link)
            conn.onMessage(serverHello())

            var outcome: PullOutcome? = null
            launch { outcome = conn.pull(scope, listOf(other)) }
            runCurrent()
            val q = link.last<SpoolPull>().q
            conn.onMessage(blob(id(9), byteArrayOf(7)))
            conn.onMessage(ok(q))
            runCurrent()

            assertTrue("an id this pull never named is not an answer to it", outcome?.blobs.orEmpty().isEmpty())
            assertTrue("nor is it ours to quarantine — we never pulled it", outcome?.oversize.orEmpty().isEmpty())
            assertNull("a client never closes on unexpected input", link.closedWith)
        }

    @Test
    fun `a blob flood can never outgrow the id list the pull named`() =
        runTest {
            val link = RecordingLink()
            val conn = connection(link)
            conn.onMessage(serverHello())
            val wanted = listOf(id(1), id(2), id(3))

            var outcome: PullOutcome? = null
            launch { outcome = conn.pull(scope, wanted) }
            runCurrent()
            val q = link.last<SpoolPull>().q
            // The reproducer: the spool answers the three ids we named, then keeps talking — repeats of
            // those three interleaved with ids we never asked for, for as long as we would listen.
            repeat(500) { n ->
                wanted.forEach { conn.onMessage(blob(it, byteArrayOf(1))) }
                conn.onMessage(blob(id(100 + n), ByteArray(1024)))
            }
            conn.onMessage(ok(q))
            runCurrent()

            assertEquals("one slot per named id, and no more", 3, outcome?.blobs?.size)
            assertEquals(wanted.map { hex(it) }.toSet(), outcome?.blobs?.map { hex(it.blobId) }?.toSet())
        }

    @Test
    fun `reports an oversize blob for quarantine however large the spool said its own maxBlob was`() =
        runTest {
            val link = RecordingLink()
            val conn = connection(link)
            // The spool advertises a megabyte; the bound that counts is the one we declare at SUB.
            conn.onMessage(serverHello(limits = greedyLimits()))
            conn.sub(listOf(ScopeSub(scope = scope, bounds = ScopeRegistry.DEFAULT_BOUNDS)))

            var outcome: PullOutcome? = null
            launch { outcome = conn.pull(scope, listOf(other)) }
            runCurrent()
            val q = link.last<SpoolPull>().q
            conn.onMessage(blob(other, ByteArray(ScopeRegistry.DEFAULT_MAX_BLOB + 1)))
            conn.onMessage(ok(q))
            runCurrent()

            assertTrue("the bytes are never buffered", outcome?.blobs.orEmpty().isEmpty())
            // Accounted rather than merely dropped: the spool still holds it, so without §9.3 the two
            // digests would diverge forever and we would re-pull it every heal round.
            assertEquals(listOf(hex(other)), outcome?.oversize?.map { hex(it) })
        }

    @Test
    fun `keeps a blob exactly at the bound we declared at sub`() =
        runTest {
            val link = RecordingLink()
            val conn = connection(link)
            conn.onMessage(serverHello(limits = greedyLimits()))
            conn.sub(listOf(ScopeSub(scope = scope, bounds = ScopeRegistry.DEFAULT_BOUNDS)))

            var outcome: PullOutcome? = null
            launch { outcome = conn.pull(scope, listOf(other)) }
            runCurrent()
            val q = link.last<SpoolPull>().q
            conn.onMessage(blob(other, ByteArray(ScopeRegistry.DEFAULT_MAX_BLOB)))
            conn.onMessage(ok(q))
            runCurrent()

            assertEquals("a maximal legitimate blob must still arrive", 1, outcome?.blobs?.size)
            assertTrue(outcome?.oversize.orEmpty().isEmpty())
        }

    @Test
    fun `attributes a blob to the pull that named it when two pulls for one scope overlap`() =
        runTest {
            val link = RecordingLink()
            val conn = connection(link)
            conn.onMessage(serverHello())

            var first: PullOutcome? = null
            var second: PullOutcome? = null
            launch { first = conn.pull(scope, listOf(id(1))) }
            runCurrent()
            val firstQ = link.last<SpoolPull>().q
            launch { second = conn.pull(scope, listOf(id(2))) }
            runCurrent()
            val secondQ = link.last<SpoolPull>().q

            conn.onMessage(blob(id(2), byteArrayOf(2)))
            conn.onMessage(blob(id(1), byteArrayOf(1)))
            conn.onMessage(ok(firstQ))
            conn.onMessage(ok(secondQ))
            runCurrent()

            // Routing on the wanted set rather than "first collector for this scope" makes attribution
            // exact; going back to the latter would silently cross these two.
            assertEquals(listOf(hex(id(1))), first?.blobs?.map { hex(it.blobId) })
            assertEquals(listOf(hex(id(2))), second?.blobs?.map { hex(it.blobId) })
        }

    @Test
    fun `a timed-out pull collects nothing more from a spool still streaming into it`() =
        runTest {
            val link = RecordingLink()
            val conn = connection(link)
            conn.onMessage(serverHello())

            var outcome: PullOutcome? = null
            launch { outcome = conn.pull(scope, listOf(other)) }
            runCurrent()
            advanceTimeBy(60_000)
            runCurrent()
            assertNull("the request gave up", outcome)

            // The correlation entry is gone, so a spool that kept the socket open has nowhere to stream.
            repeat(100) { conn.onMessage(blob(other, ByteArray(1024))) }
            runCurrent()
            assertNull(link.closedWith)
        }

    @Test
    fun `rejects an achunk outside the index window the aget named`() =
        runTest {
            val link = RecordingLink()
            val conn = connection(link)
            conn.onMessage(serverHello())

            var outcome: AgetOutcome? = null
            launch { outcome = conn.aget(scope, attachment, from = 4, n = 2) }
            runCurrent()
            val q = link.last<SpoolAget>().q
            conn.onMessage(achunk(idx = 9))
            conn.onMessage(ok(q))
            runCurrent()

            assertTrue(outcome?.chunks.orEmpty().isEmpty())
            // Answering with something other than what was asked for quarantines the aid (C-9.5-4).
            assertTrue("the caller must be able to quarantine", outcome?.rejected == true)
        }

    @Test
    fun `drops an achunk for an attachment this aget did not name`() =
        runTest {
            val link = RecordingLink()
            val conn = connection(link)
            conn.onMessage(serverHello())

            var outcome: AgetOutcome? = null
            launch { outcome = conn.aget(scope, attachment, from = 0, n = 2) }
            runCurrent()
            val q = link.last<SpoolAget>().q
            conn.onMessage(achunk(idx = 0, aid = id(77)))
            conn.onMessage(ok(q))
            runCurrent()

            // Two attachments in one scope can be in flight at once, so an unrelated aid is not this
            // request's business at all — it is not evidence that this attachment is bad.
            assertTrue(outcome?.chunks.orEmpty().isEmpty())
            assertFalse(outcome?.rejected == true)
        }

    @Test
    fun `keeps one copy of each index however many times the spool repeats it`() =
        runTest {
            val link = RecordingLink()
            val conn = connection(link)
            conn.onMessage(serverHello())

            var outcome: AgetOutcome? = null
            launch { outcome = conn.aget(scope, attachment, from = 0, n = 2) }
            runCurrent()
            val q = link.last<SpoolAget>().q
            repeat(200) { conn.onMessage(achunk(idx = 0)) }
            conn.onMessage(achunk(idx = 1))
            conn.onMessage(ok(q))
            runCurrent()

            assertEquals(2, outcome?.chunks?.size)
            assertEquals(setOf(0, 1), outcome?.chunks?.map { it.idx }?.toSet())
            assertFalse("a repeat is noise, not a reason to quarantine", outcome?.rejected == true)
        }

    @Test
    fun `bounds an achunk at the protocol's sealed chunk size`() =
        runTest {
            val link = RecordingLink()
            val conn = connection(link)
            conn.onMessage(serverHello(limits = greedyLimits()))

            var outcome: AgetOutcome? = null
            launch { outcome = conn.aget(scope, attachment, from = 0, n = 2) }
            runCurrent()
            val q = link.last<SpoolAget>().q
            // A sealed chunk's size is structural (§12.1), so one byte over is not a tunable preference.
            conn.onMessage(achunk(idx = 0, data = ByteArray(ScopeCrypto.SEALED_CHUNK_BYTES)))
            conn.onMessage(achunk(idx = 1, data = ByteArray(ScopeCrypto.SEALED_CHUNK_BYTES + 1)))
            conn.onMessage(ok(q))
            runCurrent()

            assertEquals(listOf(0), outcome?.chunks?.map { it.idx })
            assertTrue(outcome?.rejected == true)
        }

    @Test
    fun `clamps a nonsense limits advertisement instead of believing it`() =
        runTest {
            val link = RecordingLink()
            val conn = connection(link)
            conn.onMessage(serverHello(limits = greedyLimits(maxBlob = Int.MAX_VALUE)))

            assertTrue(conn.awaitReady())
            // Every one of these is the spool's claim about itself; a check written against an unclamped
            // one lets the untrusted party set its own budget.
            assertEquals(MAX_INBOUND_RECORD, conn.limits?.maxBlob)
            assertEquals(MAX_INBOUND_RECORD, conn.limits?.maxRecord)
            assertEquals(256, conn.limits?.maxPull)
            assertEquals(ScopeCrypto.SEALED_CHUNK_BYTES, conn.limits?.maxAChunk)
            assertEquals(ScopeAttachments.MAX_CHUNKS, conn.limits?.maxAget)
            assertTrue("clamping must not collapse the all-three-or-none gate", conn.limits?.attachments == true)
        }

    @Test
    fun `a hello that advertises a commons is read, clamped to the spool's own caps, and absent means none`() =
        runTest {
            val plain = connection(RecordingLink())
            plain.onMessage(serverHello())
            assertTrue(plain.awaitReady())
            assertNull(plain.adverts)

            val link = RecordingLink()
            val conn = connection(link)
            conn.onMessage(
                SpoolCodec.encode(
                    SpoolHello(
                        t = SpoolRecordType.HELLO,
                        v = SPOOL_RECORD_VERSION,
                        limits = SpoolLimits(maxBlob = 64, maxRecord = 4096, maxScopes = 8, maxPull = 2, maxFramesCap = 10, maxTtlMs = 1),
                        commons = SpoolCommonsInfo(name = " ", maxFrames = 0, ttlMs = 0L, maxBlob = 1 shl 20, attach = true),
                    ),
                ),
            )
            assertTrue(conn.awaitReady())
            // A spool that predates `rooms`: its one commons answers for whichever room is asked about.
            val room = conn.adverts!!.advertForScope(ScopeCrypto.commonsScopeId(ByteArray(32))).info!!
            assertNull("a blank name is no name", room.name)
            assertEquals(1, room.maxFrames)
            assertEquals(1L, room.ttlMs)
            assertEquals("the room's blob cap cannot exceed the spool's", 64, room.maxBlob)
            assertTrue(room.attach)
        }

    @Test
    fun `an ahas about another scope or attachment is not an answer, however well its q matches`() =
        runTest {
            val link = RecordingLink()
            val conn = connection(link)
            conn.onMessage(serverHello(limits = greedyLimits()))

            var reply: SpoolReply.Presence? = null
            launch { reply = conn.ahave(scope, attachment) }
            runCurrent()
            val q = link.last<SpoolAhave>().q
            conn.onMessage(ahas(q, scope = other))
            runCurrent()
            assertNull("wrong scope: still waiting", reply)
            conn.onMessage(ahas(q, aid = id(9)))
            runCurrent()
            assertNull("wrong attachment: still waiting", reply)

            // A dropped mismatch leaves the question open, so the right answer still completes it.
            conn.onMessage(ahas(q))
            runCurrent()
            assertEquals(4, reply?.total)
        }

    @Test
    fun `a listing for another scope is not applied to the one we asked about`() =
        runTest {
            val link = RecordingLink()
            val conn = connection(link)
            conn.onMessage(serverHello())

            var reply: SpoolReply.Listing? = null
            launch { reply = conn.list(scope) }
            runCurrent()
            val q = link.last<SpoolList>().q
            // Applied to our scope, this would re-anchor us on a set we never asked about.
            conn.onMessage(listing(q, scope = other))
            runCurrent()
            assertNull(reply)

            conn.onMessage(listing(q))
            runCurrent()
            assertEquals(1, reply?.blobIds?.size)
        }

    @Test
    fun `three unanswered requests in a row drop the connection as unresponsive`() =
        runTest {
            val link = RecordingLink()
            val conn = connection(link)
            conn.onMessage(serverHello())

            repeat(SpoolConnection.MAX_SILENT_REQUESTS - 1) {
                launch { conn.push(scope, other, byteArrayOf(1)) }
                runCurrent()
                advanceTimeBy(SpoolConnection.REQUEST_TIMEOUT_MS + 1)
                runCurrent()
                assertNull("two strikes: still open", link.closedWith)
            }
            launch { conn.push(scope, other, byteArrayOf(1)) }
            runCurrent()
            advanceTimeBy(SpoolConnection.REQUEST_TIMEOUT_MS + 1)
            runCurrent()

            assertEquals(SpoolCloseCode.NORMAL to SpoolConnection.UNRESPONSIVE, link.closedWith)
            assertEquals(SpoolConnection.UNRESPONSIVE, conn.fault)
            assertFalse(conn.isOpen)
            // And nothing further waits on it: a request against the dropped socket fails at once.
            assertEquals(SpoolReply.Closed, conn.push(scope, other, byteArrayOf(2)))
        }

    @Test
    fun `an answer resets the strike count — silence is only a fault when it is consecutive`() =
        runTest {
            val link = RecordingLink()
            val conn = connection(link)
            conn.onMessage(serverHello())

            repeat(2) {
                launch { conn.push(scope, other, byteArrayOf(1)) }
                runCurrent()
                advanceTimeBy(SpoolConnection.REQUEST_TIMEOUT_MS + 1)
                runCurrent()
            }
            launch { conn.push(scope, other, byteArrayOf(1)) }
            runCurrent()
            conn.onMessage(ok(link.last<SpoolPush>().q))
            runCurrent()
            repeat(2) {
                launch { conn.push(scope, other, byteArrayOf(1)) }
                runCurrent()
                advanceTimeBy(SpoolConnection.REQUEST_TIMEOUT_MS + 1)
                runCurrent()
            }

            assertNull("two, an answer, two: never three in a row", link.closedWith)
            assertNull(conn.fault)
        }

    @Test
    fun `a commons pin past what one listing can carry is clamped like every other advertised number`() =
        runTest {
            val link = RecordingLink()
            val conn = connection(link)
            val room = SpoolCommonsInfo(name = "home", maxFrames = Int.MAX_VALUE, ttlMs = 1L, maxBlob = 64)
            conn.onMessage(
                SpoolCodec.encode(SpoolHello(t = SpoolRecordType.HELLO, v = SPOOL_RECORD_VERSION, limits = greedyLimits(), commons = room)),
            )

            assertTrue(conn.awaitReady())
            assertEquals(
                MAX_PINNED_FRAMES,
                conn.adverts
                    ?.advertForScope(ScopeCrypto.commonsScopeId(ByteArray(32)))
                    ?.info
                    ?.maxFrames,
            )
        }
}
