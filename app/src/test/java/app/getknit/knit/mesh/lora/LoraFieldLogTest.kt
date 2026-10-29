package app.getknit.knit.mesh.lora

import app.getknit.knit.fieldlog.FieldEvent
import app.getknit.knit.legal.repoFile
import app.getknit.knit.mesh.lora.MeshtasticSession.Companion.REASON_AUTH
import app.getknit.knit.mesh.lora.MeshtasticSession.Companion.REASON_CANCELLED
import app.getknit.knit.mesh.lora.MeshtasticSession.Companion.REASON_DIAL
import app.getknit.knit.mesh.lora.MeshtasticSession.Companion.REASON_DIAL_TIMEOUT
import app.getknit.knit.mesh.lora.MeshtasticSession.Companion.REASON_DRAIN_READ
import app.getknit.knit.mesh.lora.MeshtasticSession.Companion.REASON_GATT_DISCONNECT
import app.getknit.knit.mesh.lora.MeshtasticSession.Companion.REASON_HANDSHAKE_READ
import app.getknit.knit.mesh.lora.MeshtasticSession.Companion.REASON_HANDSHAKE_TIMEOUT
import app.getknit.knit.mesh.lora.MeshtasticSession.Companion.REASON_HEARTBEAT_WRITE
import app.getknit.knit.mesh.lora.MeshtasticSession.Companion.REASON_PROVISIONED
import app.getknit.knit.mesh.lora.MeshtasticSession.Companion.REASON_REBOOTED
import app.getknit.knit.mesh.lora.MeshtasticSession.Companion.REASON_SEND_WRITE
import app.getknit.knit.mesh.lora.MeshtasticSession.Companion.WAITING_FOR_BOARD
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The field log's LoRa edges ([LoraFieldLog], ADR 2026-10.a239's 2026-10-18 amendment) and the classifier its reasons
 * go through ([LoraLinkEnd]), pinned to the words [MeshtasticSession] actually writes.
 */
class LoraFieldLogTest {
    private var now = 0L
    private val follower = LoraFieldLog { now }

    private val radio =
        LoraRadioConfig(
            usePreset = true,
            modemPreset = ModemPreset.LONG_FAST,
            region = LoraRegion.EU_868,
            hopLimit = 3,
            overrideDutyCycle = false,
        )

    private fun ready(
        radio: LoraRadioConfig? = this.radio,
        xeddsa: Boolean? = true,
    ) = LinkState.Ready(
        board = BoardInfo(myNodeNum = 0x1234u, pioEnv = "heltec-v3", firmwareVersion = "2.8.0", hasXeddsa = xeddsa),
        channels = emptyList(),
        mtu = 247,
        radio = radio,
    )

    private fun at(
        t: Long,
        state: LinkState,
        internetCovered: Int = 0,
    ): List<FieldEvent> {
        now = t
        return follower.next(LoraStatus(state = state, internetCovered = internetCovered))
    }

    // --- the classifier, against every word the session writes ---

    /** One word the session publishes, the constant it is built from (null for one no constant names), and its class. */
    private class Case(
        val constant: String?,
        val word: String,
        val end: LoraLinkEnd,
        val status: Int? = null,
    )

    // The one list both the classifier test and the source pin read: a constant without a row here fails the pin.
    private val cases =
        listOf(
            Case("REASON_GATT_DISCONNECT", "$REASON_GATT_DISCONNECT 8", LoraLinkEnd.GATT_DROP, 8),
            Case("REASON_DIAL", "$REASON_DIAL connect 133", LoraLinkEnd.DIAL_FAILED, 133),
            Case("REASON_DIAL", "$REASON_DIAL mtu -1", LoraLinkEnd.DIAL_FAILED, -1),
            Case("REASON_DIAL_TIMEOUT", REASON_DIAL_TIMEOUT, LoraLinkEnd.DIAL_TIMEOUT),
            Case("REASON_HANDSHAKE_READ", "$REASON_HANDSHAKE_READ ${GattResult.Failed(133)}", LoraLinkEnd.HANDSHAKE, 133),
            Case("REASON_HANDSHAKE_READ", "$REASON_HANDSHAKE_READ ${GattResult.Timeout}", LoraLinkEnd.HANDSHAKE),
            Case("REASON_HANDSHAKE_TIMEOUT", REASON_HANDSHAKE_TIMEOUT, LoraLinkEnd.HANDSHAKE),
            Case("REASON_DRAIN_READ", "$REASON_DRAIN_READ ${GattResult.Closed}", LoraLinkEnd.IO),
            Case("REASON_SEND_WRITE", "$REASON_SEND_WRITE ${GattResult.Failed(8)}", LoraLinkEnd.IO, 8),
            Case("REASON_HEARTBEAT_WRITE", "$REASON_HEARTBEAT_WRITE ${GattResult.Timeout}", LoraLinkEnd.IO),
            Case("REASON_REBOOTED", REASON_REBOOTED, LoraLinkEnd.REBOOTED),
            Case("REASON_PROVISIONED", REASON_PROVISIONED, LoraLinkEnd.PROVISIONED),
            // A dial mode, and the two words whose sessions end terminal: never a published reason.
            Case("WAITING_FOR_BOARD", WAITING_FOR_BOARD, LoraLinkEnd.OTHER),
            Case("REASON_AUTH", REASON_AUTH, LoraLinkEnd.OTHER),
            Case("REASON_CANCELLED", REASON_CANCELLED, LoraLinkEnd.OTHER),
            Case(null, "something a later build says", LoraLinkEnd.OTHER),
        )

    @Test
    fun everyReasonTheSessionPublishesIsClassifiedWithItsStatus() {
        cases.forEach { assertEquals(it.word, it.end to it.status, LoraLinkEnd.of(it.word)) }
    }

    @Test
    fun theStatesThatEndALinkWithoutAReasonAreClassifiedByThemselves() {
        assertEquals(LoraLinkEnd.NEEDS_PAIRING to null, LoraLinkEnd.of(LinkState.NeedsPairing("AA")))
        assertEquals(LoraLinkEnd.STALE_BOND to null, LoraLinkEnd.of(LinkState.StaleBond("AA")))
        assertEquals(LoraLinkEnd.ADAPTER_OFF to null, LoraLinkEnd.of(LinkState.Unavailable))
        assertEquals(LoraLinkEnd.STOPPED to null, LoraLinkEnd.of(LinkState.Idle))
        assertEquals(LoraLinkEnd.OTHER to null, LoraLinkEnd.of(LinkState.Connecting))
    }

    /**
     * Every reason word is a constant the classifier test above names, and the session spells none inline — so a
     * reason added or reworded there fails here, not as a log full of [LoraLinkEnd.OTHER].
     */
    @Test
    fun theSessionBuildsEveryReasonFromAClassifiedConstant() {
        val source = repoFile("app/src/main/java/app/getknit/knit/mesh/lora/MeshtasticSession.kt").readText()
        val inline = Regex("""(reason = |backoffAndWait\(|Disconnected\()"[a-z]""").findAll(source).map { it.value }
        assertEquals("a reason spelled inline", emptyList<String>(), inline.toList())
        val declared =
            Regex("""const val ((?:REASON_\w+)|WAITING_FOR_BOARD) = """).findAll(source).map { it.groupValues[1] }.toSet()
        assertEquals(cases.mapNotNull { it.constant }.toSet(), declared)
    }

    // --- the follower ---

    @Test
    fun anAttemptThatReachesReadyIsOneUpWithItsDialTimeAndTheBoardsRadio() {
        assertEquals(emptyList<FieldEvent>(), at(0, LinkState.Idle))
        at(100, LinkState.Connecting)
        at(400, LinkState.Bonding)
        at(600, LinkState.Handshaking(null))
        val up = at(1_100, ready())
        assertEquals(
            listOf(
                FieldEvent.LoraUp(
                    dialMs = 1_000,
                    tries = 0,
                    mtu = 247,
                    region = LoraRegion.EU_868,
                    preset = ModemPreset.LONG_FAST,
                    dedicatedSlot = false,
                    hopLimit = 3,
                    xeddsa = true,
                ),
            ),
            up,
        )
        // The session fills the radio in on a ready link (`ready.copy(radio = …)`), and the transport re-publishes
        // the same state on every status change: neither is a second up.
        assertEquals(emptyList<FieldEvent>(), at(1_200, ready(radio = radio.copy(hopLimit = 4))))
        assertEquals(emptyList<FieldEvent>(), at(1_300, ready(radio = radio.copy(hopLimit = 4)), internetCovered = 2))
    }

    @Test
    fun aPresetIsWrittenOnlyWhenTheBoardUsesOne() {
        at(0, LinkState.Connecting)
        val up = at(10, ready(radio = radio.copy(usePreset = false), xeddsa = null)).single() as FieldEvent.LoraUp
        assertEquals(null, up.preset)
        assertEquals(LoraRegion.EU_868, up.region)
        assertEquals(null, up.xeddsa)
    }

    @Test
    fun aDropIsOneDownWithItsReasonAndLifeAndNotAFailureToo() {
        at(0, LinkState.Connecting)
        at(500, ready())
        val down = at(60_500, LinkState.Disconnected("$REASON_GATT_DISCONNECT 8", 60_500L + 5_000, 1))
        assertEquals(listOf(FieldEvent.LoraDown(reason = LoraLinkEnd.GATT_DROP, lifeMs = 60_000, status = 8)), down)
        // The same Disconnected re-published (any status change does it) writes nothing more.
        assertEquals(emptyList<FieldEvent>(), at(61_000, LinkState.Disconnected("$REASON_GATT_DISCONNECT 8", 65_500, 1)))
    }

    @Test
    fun failedAttemptsAreEachOneFailAndTheUpCountsThemAndTimesOnlyTheLastAttempt() {
        at(0, LinkState.Connecting)
        at(500, ready())
        at(10_000, LinkState.Disconnected(REASON_REBOOTED, 10_000L + 5_000, 1)) // the down
        at(15_000, LinkState.Connecting)
        val first = at(16_000, LinkState.Disconnected("$REASON_DIAL connect 133", 16_000L + 10_000, 2))
        assertEquals(
            listOf(FieldEvent.LoraFail(reason = LoraLinkEnd.DIAL_FAILED, streak = 2, status = 133, waitMs = 10_000)),
            first,
        )
        // Re-published with the plane's other fields moving: still one failure.
        assertEquals(
            emptyList<FieldEvent>(),
            at(17_000, LinkState.Disconnected("$REASON_DIAL connect 133", 26_000, 2), internetCovered = 1),
        )
        at(26_000, LinkState.Connecting)
        at(27_000, LinkState.Handshaking(null))
        val second = at(30_000, LinkState.Disconnected(REASON_HANDSHAKE_TIMEOUT, 30_000L + 20_000, 3))
        assertEquals(listOf(FieldEvent.LoraFail(reason = LoraLinkEnd.HANDSHAKE, streak = 3, waitMs = 20_000)), second)
        at(50_000, LinkState.Connecting)
        val up = at(50_400, ready()).single() as FieldEvent.LoraUp
        assertEquals(400L, up.dialMs) // the last attempt alone: the outage is the log's `relink`
        assertEquals(2, up.tries) // the two fails, not the session's streak of 3 (the drop counted one)
        // A later drop and up starts the count over.
        at(60_000, LinkState.Disconnected("$REASON_GATT_DISCONNECT 19", 60_000L + 5_000, 1))
        at(65_000, LinkState.Connecting)
        assertEquals(0, (at(65_300, ready()).single() as FieldEvent.LoraUp).tries)
    }

    @Test
    fun theBackgroundWaitIsADialModeNeverAFailureAndStartsTheAttempt() {
        at(0, LinkState.Connecting)
        at(100, ready())
        at(1_000, LinkState.Disconnected("$REASON_GATT_DISCONNECT 8", 1_000L + 5_000, 1))
        at(6_000, LinkState.Connecting)
        at(7_000, LinkState.Disconnected("$REASON_DIAL connect 133", 7_000L + 5_000, 2))
        assertEquals(emptyList<FieldEvent>(), at(12_000, LinkState.Disconnected(WAITING_FOR_BOARD, 12_000L + 3_600_000, 2)))
        assertEquals(emptyList<FieldEvent>(), at(13_000, LinkState.Disconnected(WAITING_FOR_BOARD, 3_612_000, 2)))
        // The controller connects twenty minutes later; the dial runs from the wait's arm.
        val up = at(1_212_000, ready()).single() as FieldEvent.LoraUp
        assertEquals(1_200_000L, up.dialMs)
        assertEquals(1, up.tries)
    }

    @Test
    fun aFirstSnapshotAlreadyReadyIsAnUpWithNoDialSoTheDownHasOne() {
        assertEquals(
            listOf(
                FieldEvent.LoraUp(
                    dialMs = null,
                    tries = 0,
                    mtu = 247,
                    region = LoraRegion.EU_868,
                    preset = ModemPreset.LONG_FAST,
                    dedicatedSlot = false,
                    hopLimit = 3,
                    xeddsa = true,
                ),
            ),
            at(5_000, ready()),
        )
        assertEquals(listOf(FieldEvent.LoraDown(reason = LoraLinkEnd.STOPPED, lifeMs = 1_000)), at(6_000, LinkState.Idle))
    }

    @Test
    fun aFirstSnapshotWaitingOutAFailureWritesNothingButTheNextFailureCounts() {
        assertEquals(emptyList<FieldEvent>(), at(0, LinkState.Disconnected("$REASON_DIAL connect 133", 5_000, 1)))
        at(5_000, LinkState.Connecting)
        val fail = at(5_500, LinkState.Disconnected(REASON_DIAL_TIMEOUT, 5_500L + 10_000, 2))
        assertEquals(listOf(FieldEvent.LoraFail(reason = LoraLinkEnd.DIAL_TIMEOUT, streak = 2, waitMs = 10_000)), fail)
    }

    @Test
    fun aBondRefusalIsAFailBeforeReadyAndADownAfterIt() {
        at(0, LinkState.Connecting)
        at(100, LinkState.Disconnected("$REASON_DIAL connect 133", 100L + 5_000, 1))
        at(5_100, LinkState.Connecting)
        at(5_200, LinkState.Handshaking(null))
        // Terminal states carry no streak: one past the last one published, and no wait.
        assertEquals(listOf(FieldEvent.LoraFail(reason = LoraLinkEnd.NEEDS_PAIRING, streak = 2)), at(5_300, LinkState.NeedsPairing("AA")))
        assertEquals(emptyList<FieldEvent>(), at(5_400, LinkState.NeedsPairing("AA")))

        at(10_000, LinkState.Connecting)
        at(10_500, ready())
        assertEquals(
            listOf(FieldEvent.LoraDown(reason = LoraLinkEnd.STALE_BOND, lifeMs = 2_000)),
            at(12_500, LinkState.StaleBond("AA")),
        )
    }

    @Test
    fun theAdapterGoingOffEndsALinkButIsNoFailedAttempt() {
        at(0, LinkState.Connecting)
        at(100, ready())
        assertEquals(listOf(FieldEvent.LoraDown(reason = LoraLinkEnd.ADAPTER_OFF, lifeMs = 900)), at(1_000, LinkState.Unavailable))
        assertEquals(emptyList<FieldEvent>(), at(2_000, LinkState.Idle))
    }

    /** The gateway role left with the pocket bridge (ADR 2026-10.rp3s): no status change writes a `lora_role` line. */
    @Test
    fun aStatusChangeWithTheLinkUnmovedWritesNoLine() {
        assertEquals(emptyList<FieldEvent>(), at(0, LinkState.Connecting))
        assertEquals(emptyList<FieldEvent>(), at(100, LinkState.Connecting, internetCovered = 3))
        assertTrue(at(300, ready()).single() is FieldEvent.LoraUp)
        assertEquals(emptyList<FieldEvent>(), at(400, ready(), internetCovered = 1))
    }

    @Test
    fun theFlowStartsAFreshFollowerForEachCollection() =
        runTest {
            val statuses = flowOf(LoraStatus(state = ready()), LoraStatus(state = LinkState.Idle))
            val edges = loraFieldEdges(statuses) { 0L }
            val once = edges.toList()
            assertEquals(listOf("lora_up", "lora_down"), once.map { it.kind })
            assertEquals(once, edges.toList())
        }
}
