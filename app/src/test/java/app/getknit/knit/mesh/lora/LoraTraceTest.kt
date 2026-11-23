package app.getknit.knit.mesh.lora

import app.getknit.knit.mesh.InboundFrame
import app.getknit.knit.mesh.MeshMetrics
import app.getknit.knit.mesh.MeshPost
import app.getknit.knit.mesh.protocol.ChatContent
import app.getknit.knit.mesh.protocol.FrameId
import app.getknit.knit.mesh.protocol.FrameType
import app.getknit.knit.mesh.protocol.RelayEnvelope
import app.getknit.knit.mesh.protocol.WireCodec
import app.getknit.knit.mesh.protocol.WireEnvelope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The developer view's LoRa trace from the Meshtastic plane's side (ADR 2026-10.s7wg's slice-3 brief): write-only, noted at
 * points the plane already passes, under the row id the inspector looks up, built only while the trace is on, and never able
 * to stop the pacer or the packet collector by throwing. Over the fake air: what the trace says, never what the air did.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LoraTraceTest {
    private var seq = 0

    private class Rig(
        val transport: LoraMeshTransport,
        val link: FakeMeshtasticLink,
        val received: MutableList<InboundFrame>,
        val posts: MutableList<MeshPost>,
        val logs: MutableList<String>,
    )

    private fun TestScope.rig(
        air: FakeMeshtasticAir,
        nodeNum: UInt,
        self: String,
        trace: LoraTrace,
        scope: CoroutineScope = backgroundScope,
    ): Rig {
        val link = FakeMeshtasticLink(nodeNum, air, scope = scope)
        val received = mutableListOf<InboundFrame>()
        val posts = mutableListOf<MeshPost>()
        val logs = mutableListOf<String>()
        val transport =
            LoraMeshTransport(
                selfId = { self },
                link = link,
                config = MutableStateFlow(LoraConfig("AA:$nodeNum", 1)),
                onPublicPost = { posts += it },
                scope = scope,
                metrics = MeshMetrics(),
                clock = { testScheduler.currentTime },
                wallClock = { WALL + testScheduler.currentTime },
                processStartWall = WALL,
                log = { logs += it },
                pace = LoraPacePolicy(minGapMs = 0),
                trace = trace,
            )
        scope.launch { transport.inbound.collect { received += it } }
        transport.start()
        runCurrent()
        // Provisioned: the primary at 0, Knit at 1, LongFast — the shape the room and a compact DM need.
        link.readyProvisioned(knitIndex = 1)
        runCurrent()
        return Rig(transport, link, received, posts, logs)
    }

    private fun chat(
        sender: String,
        body: String = "north gate, ten minutes",
        sentAt: Long = WALL,
        // A minted id by default: the shape `FrameId.admits` takes, as every honest build writes.
        id: String = FrameId.new().also { seq++ },
    ): WireEnvelope {
        val env =
            RelayEnvelope(
                type = FrameType.CHAT,
                id = id,
                senderId = sender,
                sentAt = sentAt,
                payload = WireCodec.encodePayload(ChatContent(body = body)),
            )
        val sig = ByteArray(64)
        sig[0] = (seq shr 8).toByte()
        sig[1] = seq.toByte()
        return WireEnvelope(relay = true, sig = sig, signed = WireCodec.encodeEnvelope(env))
    }

    private fun idOf(wire: WireEnvelope): String = WireCodec.decodeEnvelope(wire.signed)!!.id

    /** A high-entropy body that will not deflate below the packet cap, so the frame truly fragments. */
    private fun incompressible(chars: Int): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        val rng = kotlin.random.Random(1234)
        return buildString(chars) { repeat(chars) { append(alphabet[rng.nextInt(alphabet.length)]) } }
    }

    /** [wire]'s packets as another board airs them on the Knit slot, one by one. */
    private fun packetsOf(wire: WireEnvelope): List<ByteArray> = LoraFrameCodec.encodeBest(wire, fragId = seq and 0xFFFF)!!.parts

    private fun FakeMeshtasticLink.hear(
        payload: ByteArray,
        from: UInt = 9u,
    ) = deliver(from = from, channelIndex = 1, portnum = MeshtasticProto.PORT_PRIVATE_APP, payload = payload, rxSnr = 6.5f, rxRssi = -85)

    @Test
    fun anOwnRoomPostIsQueuedThenAiredWithItsHandleAndWhatTheLedgerBooked() =
        runTest {
            val trace = RecordingTrace()
            val a = rig(FakeMeshtasticAir(), 1u, "alice", trace)
            val wire = chat("alice")
            a.transport.fastFanout(wire)
            runCurrent()

            val id = idOf(wire)
            val events = trace.of(id)
            assertEquals("queued, then aired: ${events.map { it::class.simpleName }}", 2, events.size)
            val queued = events[0] as LoraTraceEvent.Queued
            assertEquals(TraceRoute.FRAME, queued.route)
            assertEquals(1, queued.parts)
            assertEquals(
                a.link.sent
                    .single()
                    .size,
                queued.bytes,
            )
            assertNull("a broadcast names no board", queued.to)
            assertEquals(BoardKind.MESHTASTIC, queued.board)
            val aired = events[1] as LoraTraceEvent.Aired
            assertEquals("the bound Knit slot", 1, aired.slot)
            assertEquals(a.link.sentIds.map { it.toLong() }, aired.handles)
            assertEquals("the one booking the ledger made for it", a.transport.airtimeUsedMs(), aired.bookedMs)
            assertTrue(aired.bookedMs > 0)
            assertEquals(0, aired.part)
            assertEquals(1, aired.parts)
            assertFalse(aired.heldForAir)
            assertEquals("stamped on the wall clock", WALL, aired.atMs)
            assertTrue("nothing by handle for a broadcast that drew no answer", trace.byHandle.isEmpty())
        }

    @Test
    fun aBroadcastsLateNakIsNotedByTheHandleItsAiringNamed() =
        runTest {
            val trace = RecordingTrace()
            val a = rig(FakeMeshtasticAir(), 1u, "alice", trace)
            a.transport.fastFanout(chat("alice"))
            runCurrent()
            val handle = a.link.sentIds.single()
            a.link.emitNak(handle, RoutingError.NO_CHANNEL)
            runCurrent()
            val ended = trace.ofHandle(handle.toLong()).single() as LoraTraceEvent.Ended
            assertEquals(TraceOutcome.NAK, ended.outcome)
            assertTrue(ended.late)
            assertEquals(RoutingError.NO_CHANNEL.name, ended.nak)
        }

    @Test
    fun theFanOutsRefusalsAreNotedUnderTheFramesId() =
        runTest {
            val trace = RecordingTrace()
            val a = rig(FakeMeshtasticAir(), 1u, "alice", trace)

            val long = chat("alice", body = incompressible(400))
            a.transport.fastFanout(long)
            val stale = chat("alice", sentAt = WALL - LoraFramePolicy.FRESH_MS - 1)
            a.transport.fastFanout(stale)
            val twice = chat("alice")
            a.transport.fastFanout(twice)
            a.transport.fastFanout(twice)
            runCurrent()

            assertEquals(TraceReason.ROOM_TOO_LONG, trace.typed<LoraTraceEvent.Refused>(idOf(long)).single().reason)
            assertTrue("nothing of it queued", trace.typed<LoraTraceEvent.Queued>(idOf(long)).isEmpty())
            assertEquals(TraceReason.NOT_FRESH, trace.typed<LoraTraceEvent.Refused>(idOf(stale)).single().reason)
            assertEquals("aired once", 1, trace.typed<LoraTraceEvent.Aired>(idOf(twice)).size)
            assertEquals(TraceReason.ALREADY_QUEUED, trace.typed<LoraTraceEvent.Refused>(idOf(twice)).single().reason)
            // Somebody else's frame is no row of ours to explain: the admit rule refuses it unnoted.
            val theirs = chat("bob")
            a.transport.fastFanout(theirs)
            runCurrent()
            assertTrue(trace.of(idOf(theirs)).isEmpty())
        }

    @Test
    fun aFragmentedFrameIsNotedOnceItCompletesWithTheCompletingPacketsReceptionAndItsCount() =
        runTest {
            val trace = RecordingTrace()
            val b = rig(FakeMeshtasticAir(), 2u, "bob", trace)
            val wire = chat("alice", body = incompressible(400))
            val parts = packetsOf(wire)
            assertTrue("it fragments", parts.size > 1)

            parts.dropLast(1).forEach { b.link.hear(it) }
            runCurrent()
            assertTrue("no fragment is keyed before its frame completes", trace.byId.isEmpty())

            b.link.hear(parts.last())
            runCurrent()
            assertTrue("and the router still has it", b.received.any { it.envelope.id == idOf(wire) })
            val heard = trace.of(idOf(wire)).single() as LoraTraceEvent.Heard
            assertEquals(TraceRoute.FRAME, heard.route)
            assertEquals(parts.size, heard.parts)
            assertEquals(1, heard.slot)
            assertEquals("the radio's claim, as Meshtastic writes it", "!00000009", heard.from)
            assertFalse(heard.fromVouched)
            assertEquals(65, heard.radio.snrDeci)
            assertEquals(-85, heard.radio.rssi)
            assertEquals(0, heard.radio.hops)
            assertTrue(heard.radio.live)
            assertNull("a Knit frame carries no room tag", heard.payloadTag)
            assertTrue("fresh: its author's presence", HeardNote.PRESENCE in heard.notes)
            assertFalse(HeardNote.OWN_BOARD in heard.notes)
            assertTrue("nothing on a Meshtastic plane checks the signature", HeardNote.AUTHOR_UNVERIFIED in heard.notes)
            assertFalse(HeardNote.AUTHOR_VERIFIED in heard.notes)
        }

    /**
     * The id a heard frame is noted under is the sender's claim: a whole `0x03` with a junk signature, from somebody else,
     * filed under an id we already queued, is noted as heard and marked unverified — the plane never verifies for the trace,
     * and the book keeps it off our own row.
     */
    @Test
    fun aForgedFrameUnderAnIdWeQueuedIsNotedUnverified() =
        runTest {
            val trace = RecordingTrace()
            val a = rig(FakeMeshtasticAir(), 1u, "alice", trace)
            val own = chat("alice")
            a.transport.fastFanout(own)
            runCurrent()
            val id = idOf(own)
            assertEquals(1, trace.typed<LoraTraceEvent.Queued>(id).size)

            val forged = chat("mallory", body = "not alice", id = id)
            val packet = packetsOf(forged).single()
            assertEquals("a whole 0x03", app.getknit.knit.mesh.link.FastFrameCodec.TAG_COMPACT, packet[0])
            a.link.hear(packet)
            runCurrent()
            val heard = trace.typed<LoraTraceEvent.Heard>(id).single()
            assertTrue(HeardNote.AUTHOR_UNVERIFIED in heard.notes)
            assertFalse(HeardNote.AUTHOR_VERIFIED in heard.notes)
        }

    /** An id of no shape any door admits (`FrameId.admits`) is never noted, whatever the frame carried. */
    @Test
    fun aFrameWhoseIdNoDoorAdmitsIsNeverNoted() =
        runTest {
            val trace = RecordingTrace()
            val b = rig(FakeMeshtasticAir(), 2u, "bob", trace)
            val odd = chat("alice", id = "not-a-minted-id")
            packetsOf(odd).forEach { b.link.hear(it) }
            runCurrent()
            assertTrue(
                "it reached the router, so the admission guard is what kept it out",
                b.received.any {
                    it.envelope.id ==
                        "not-a-minted-id"
                },
            )
            assertTrue(trace.byId.isEmpty())
        }

    @Test
    fun ourOwnFrameEchoedBackIsNeverNoted() =
        runTest {
            val trace = RecordingTrace()
            val a = rig(FakeMeshtasticAir(), 1u, "alice", trace)
            val wire = chat("alice")
            packetsOf(wire).forEach { a.link.hear(it) }
            runCurrent()
            assertTrue(trace.of(idOf(wire)).isEmpty())
        }

    @Test
    fun aMeshtasticRoomPostIsNotedUnderTheRowItsRoomWritesWithItsPayloadTag() =
        runTest {
            val trace = RecordingTrace()
            val a = rig(FakeMeshtasticAir(), 1u, "alice", trace)
            a.link.deliverPublicText(from = 0xdeadbeefu, body = "anyone around?", id = 77u, boardVerified = true)
            runCurrent()
            assertEquals(1, a.posts.size)
            val heard = trace.of(FrameId.forMeshPost(0xdeadbeefL, 77L)).single() as LoraTraceEvent.Heard
            assertEquals(TraceRoute.ROOM, heard.route)
            assertEquals(0, heard.slot)
            assertEquals("!deadbeef", heard.from)
            assertTrue("our board verified its signature", heard.fromVouched)
            assertEquals(77L, heard.packetId)
            assertEquals(1, heard.radio.hops)
            assertEquals(-65, heard.radio.snrDeci)
            assertEquals(tracePayloadTag("anyone around?".encodeToByteArray()), heard.payloadTag)
            assertEquals(1, heard.parts)
        }

    @Test
    fun withTheTraceOffNothingIsNotedOrBuilt() =
        runTest {
            val trace = RecordingTrace(enabled = false)
            val air = FakeMeshtasticAir()
            val a = rig(air, 1u, "alice", trace)
            val b = rig(air, 2u, "bob", RecordingTrace(enabled = false))
            val wire = chat("alice")
            a.transport.fastFanout(wire)
            a.transport.fastFanout(chat("alice", body = incompressible(400)))
            a.link.deliverPublicText(from = 0xdeadbeefu, body = "anyone around?", id = 77u)
            runCurrent()
            assertTrue("it aired and landed all the same", b.received.any { it.envelope.id == idOf(wire) })
            assertEquals(0, trace.notedWhileOff)
            assertTrue(trace.byId.isEmpty())
            assertTrue(trace.byHandle.isEmpty())
        }

    /**
     * The design pass's Blocking finding: a trace that throws must never end the pacer or the packet collector. Every note
     * throws here; frames after the first throw still air, and still reach the router.
     */
    @Test
    fun aThrowingTraceLeavesThePacerAndThePacketCollectorWorking() =
        runTest {
            val broken = RecordingTrace(failing = true)
            val air = FakeMeshtasticAir()
            val a = rig(air, 1u, "alice", broken)
            val b = rig(air, 2u, "bob", broken)

            val first = chat("alice")
            a.transport.fastFanout(first)
            runCurrent()
            assertTrue("it threw", broken.throws > 0)
            val second = chat("alice", body = "and again")
            a.transport.fastFanout(second)
            runCurrent()

            assertEquals("both aired", 2, a.link.sent.size)
            assertTrue("both reached bob's router", b.received.map { it.envelope.id }.containsAll(listOf(idOf(first), idOf(second))))
            assertTrue("logged by class name alone", a.logs.any { it == "lora trace failed: IllegalStateException" })
            assertTrue(b.logs.any { it == "lora trace failed: IllegalStateException" })
        }

    private companion object {
        const val WALL = 1_700_000_000_000L
    }
}
