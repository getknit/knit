package app.getknit.knit.mesh.lora.meshcore

import app.getknit.knit.mesh.bluetooth.meshcore.NusLinkPolicy
import app.getknit.knit.mesh.lora.QueueInfo
import app.getknit.knit.mesh.lora.RefusalClass
import app.getknit.knit.mesh.lora.SendResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

/**
 * Stage 5a-1's checks on the MeshCore client (ADR 2026-11.gsqu's Stage 5a-1 amendment): every refusal the session can
 * answer has one meaning, a call's deadline never loses its answer to the actor's refusal, the session and the GATT
 * client agree on the too-long status, and every encoder's output fits one write.
 */
class MeshcoreRefusalStage5Test {
    @Test
    fun everyCodeTheSessionCanAnswerHasOneMeaning() {
        val own = listOf(MeshcoreRefusal.NO_RECORD, MeshcoreRefusal.UNPINNED, MeshcoreProto.ERR_NOT_FOUND)
        for (code in own) assertEquals("$code", RefusalClass.OWN_NO_KEY, MeshcoreRefusal.classOf(SendResult.Rejected(1u, code)))
        // The firmware's other codes and the session's own: refused, never paced (MeshCore refuses nothing for duty) and never
        // a destination's word (it reports none).
        val other =
            listOf(
                MeshcoreRefusal.SLOT_NOT_KNIT,
                MeshcoreRefusal.UNSUPPORTED,
                MeshcoreProto.ERR_UNSUPPORTED_CMD,
                MeshcoreProto.ERR_BAD_STATE,
                MeshcoreProto.ERR_FILE_IO,
                MeshcoreProto.ERR_ILLEGAL_ARG,
                MeshcoreProto.ERR_TABLE_FULL,
            )
        for (code in other) assertEquals("$code", RefusalClass.OTHER, MeshcoreRefusal.classOf(SendResult.Rejected(1u, code)))
        for (result in listOf(SendResult.Busy, SendResult.TooLarge, SendResult.Timeout(1u), SendResult.Queued(1u, QueueInfo(3, 16, 0)))) {
            assertNull("$result refused nothing", MeshcoreRefusal.classOf(result))
        }
        assertEquals(
            "the GATT client's too-long status is the one the session reads",
            NusLinkPolicy.STATUS_TOO_LONG,
            MeshcoreSession.WRITE_TOO_LONG,
        )
    }

    @Test
    fun everyEncodersLongestOutputFitsOneWrite() {
        val key = ByteArray(MeshcoreProto.KEY_BYTES) { 7 }
        val longestName = ByteArray(31) { 'k'.code.toByte() }
        val outputs =
            mapOf(
                "deviceQuery" to MeshcoreProto.deviceQuery(),
                "appStart" to MeshcoreProto.appStart(),
                "getChannel" to MeshcoreProto.getChannel(255),
                "setChannel" to MeshcoreProto.setChannel(255, "k".repeat(31), ByteArray(16)),
                "sendChannelData" to MeshcoreProto.sendChannelData(255, ByteArray(MeshcoreProto.CHANNEL_DATA_CAP) { 1 }),
                "sendChannelText" to
                    MeshcoreProto.sendChannelText(
                        0,
                        0xFFFFFFFFL,
                        ByteArray(MeshcoreProto.channelTextCap(byteArrayOf(1))) { 1 },
                        byteArrayOf(1),
                    ),
                "sendText" to MeshcoreProto.sendText(key.copyOf(6), 3, 0xFFFFFFFFL, ByteArray(MeshcoreProto.DIRECT_TEXT_CAP) { 1 }),
                "syncNextMessage" to MeshcoreProto.syncNextMessage(),
                "signStart" to MeshcoreProto.signStart(),
                "signData" to MeshcoreProto.signData(ByteArray(MeshcoreProto.MAX_NOTIFY - 1) { 1 }),
                "signFinish" to MeshcoreProto.signFinish(),
                "getContactByKey" to MeshcoreProto.getContactByKey(key),
                "removeContact" to MeshcoreProto.removeContact(key),
                "resetPath" to MeshcoreProto.resetPath(key),
                "addUpdateContact" to MeshcoreProto.addUpdateContact(ContactRecord.knit(key, "Knit 0707"), withPosition = true),
                "countContacts" to MeshcoreProto.countContacts(),
                "getStats" to MeshcoreProto.getStats(MeshcoreProto.STATS_RADIO),
                "getBattery" to MeshcoreProto.getBattery(),
                "setDeviceTime" to MeshcoreProto.setDeviceTime(0xFFFFFFFFL),
                "getDeviceTime" to MeshcoreProto.getDeviceTime(),
                "clearScopeOverride" to MeshcoreProto.clearScopeOverride(),
                "getDefaultScope" to MeshcoreProto.getDefaultScope(),
                "setManualAdd" to MeshcoreProto.setManualAdd(1),
                "getAutoadd" to MeshcoreProto.getAutoadd(),
                "setAutoadd" to MeshcoreProto.setAutoadd(0),
                "setAdvertName" to MeshcoreProto.setAdvertName(longestName),
            )
        for ((name, bytes) in outputs) {
            val out = checkNotNull(bytes) { "$name refused its longest input" }
            assertTrue("$name: ${out.size} B over ${MeshcoreProto.MAX_NOTIFY}", out.size <= MeshcoreProto.MAX_NOTIFY)
            assertTrue("$name is in the pinned command set", MeshcoreSession.commandAllowed(out))
        }
        // Every command the encoder may emit has a row above (the pinned allowlist, MeshcoreProtoTest).
        val covered = outputs.values.mapNotNull { it?.first()?.toInt()?.and(0xFF) }.toSet()
        assertEquals(MeshcoreProto.ENCODED_COMMANDS.toSet(), covered)
    }

    @Test
    fun aDeadlineThatTookTheOpKeepsItsAnswerWhateverTheActorDoes() =
        runBlocking {
            val op = SessionOp<Unit, String>({ "ran" }, { "notReady" })
            assertTrue(op.claimForTimeout { "timedOut" })
            op.refuse() // the actor's `serve` finally, finding the op claimed
            op.run(Unit) // never starts late
            assertEquals("timedOut", op.reply.await())

            val started = SessionOp<Unit, String>({ "ran" }, { "notReady" })
            started.run(Unit)
            assertFalse("a started op is the actor's", started.claimForTimeout { "timedOut" })
            started.refuse()
            assertEquals("ran", started.reply.await())

            val threw = SessionOp<Unit, String>({ error("the op threw") }, { "notReady" })
            runCatching { threw.run(Unit) }
            threw.refuse()
            assertEquals("a started op that threw is answered notReady", "notReady", threw.reply.await())
        }

    /**
     * One interleaving the must-fix closes, made deterministic with no threads: the actor's refusal runs inside the
     * deadline's window — after its claim, before its answer — which the `timedOut` lambda is. A refusal that claims and
     * answers unconditionally answers notReady there, and this test catches it. A **check-then-act** refusal (it reads
     * "unclaimed", then answers) is not caught here: on one thread its check already sees the deadline's claim. Only the
     * two-thread race below can catch that shape, and only probabilistically, when the threads interleave inside the gap.
     */
    @Test
    fun aRefusalBetweenTheDeadlinesClaimAndItsAnswerChangesNothing() =
        runBlocking {
            val op = SessionOp<Unit, String>({ "ran" }, { "notReady" })
            assertTrue(
                op.claimForTimeout {
                    op.refuse() // the actor's `serve` finally, landing inside the deadline's window
                    "timedOut"
                },
            )
            assertEquals("timedOut", op.reply.await())
        }

    /**
     * The race the must-fix closes: the caller's deadline and the actor's refusal on two threads. Whenever the deadline
     * claims the op, its answer is the deadline's — never the actor's notReady slipped in between claim and answer. The one
     * test of a check-then-act refusal, and a probabilistic one: it fails only on a run whose threads interleave inside
     * the check-to-answer gap, so a green run is evidence, not proof.
     */
    @Test
    fun aDeadlineRacingTheActorsRefusalNeverAnswersNotReady() =
        runBlocking {
            repeat(RACES) {
                val op = SessionOp<Unit, String>({ "ran" }, { "notReady" })
                val go = CountDownLatch(1)
                var claimed = false
                val caller =
                    thread {
                        go.await()
                        claimed = op.claimForTimeout { "timedOut" }
                    }
                val actor =
                    thread {
                        go.await()
                        op.refuse()
                    }
                go.countDown()
                caller.join()
                actor.join()
                val answer = op.reply.await()
                if (claimed) {
                    assertEquals("the deadline claimed it", "timedOut", answer)
                } else {
                    assertEquals("the actor refused it first", "notReady", answer)
                }
            }
        }

    @Test
    fun theBrakeRingStartsAgainWhenTheBoardsUptimeGoesBackwards() {
        val ring = MeshcoreAirRing()
        ring.onSample(0, txAirSecs = 100, rxAirSecs = 100, uptimeSecs = 5_000)
        assertTrue(ring.onSample(60_000, txAirSecs = 106, rxAirSecs = 118, uptimeSecs = 5_060) != null)
        // The counters read higher, the clock moved on — but the board's uptime fell: a reboot the counters hide.
        assertNull("no reading across a reboot", ring.onSample(61_000, txAirSecs = 200, rxAirSecs = 200, uptimeSecs = 3))
        val reading = ring.onSample(121_000, txAirSecs = 224, rxAirSecs = 200, uptimeSecs = 63)!!
        assertEquals("40 % of the minute since the reboot: the brake's figure", 40f, reading.channelUtilPercent, 0.01f)
    }

    private companion object {
        const val RACES = 2_000
    }
}
