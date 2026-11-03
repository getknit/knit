package app.getknit.knit.blepair

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.getknit.knit.mesh.TransportKind
import app.getknit.knit.mesh.TransportStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

/**
 * The link going away under the conversation, made by the bridges (`ctl cut`, `ctl deafen`) rather than by walking
 * off: a cut the stack heals by its own dial, and a stretch out of range during which messages wait for the peer.
 * The smaller id cuts, since it never dials; the larger one, the dialer, is the one that has to go deaf to stay away.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class BlePairLinkTest : BlePairTest() {
    @Before
    fun needsTheBridge() = assumeTrue("no bridge control ($ARG_BRIDGE)", bridgePort != null)

    @Test
    fun a_aCutLinkComesBackAndCarriesADm() =
        runBlocking<Unit> {
            awaitLink()
            if (isSmaller) {
                val cutAt = System.currentTimeMillis()
                cut()
                note("relinked ${awaitBridgeRelink(cutAt)} ms after the cut")
                sendDm("after the cut from $self")
                awaitPeerDm("after the cut from $peer")
            } else {
                awaitPeerDm("after the cut from $peer", RELINK_TIMEOUT_MS + DM_TIMEOUT_MS)
                awaitTick(sendDm("after the cut from $self"))
            }
        }

    /**
     * Each side writes while the other is out of range, and each message waits for the peer and is delivered when it
     * comes back: the outbox, not a live link. "Out of range" is the dialer's bridge, for [OUT_OF_RANGE_S]: deaf (its
     * host hears no advert, so the peer fades) and refusing every dial (deafness alone is not enough: the controller's
     * initiator hears adverts the host is not shown, and a dial from a cached sighting relinked in 11 s on the first
     * run). The smaller id cuts, then never dials on its own inside the outage (hj4a's lonely dial waits 180 s).
     */
    @Test
    fun b_aDmWrittenOutOfRangeIsDeliveredOnReturn() =
        runBlocking<Unit> {
            awaitLink()
            try {
                val outAt = System.currentTimeMillis()
                if (isSmaller) {
                    awaitPeerDm("going out of range, $peer")
                    cut()
                } else {
                    bridge(
                        JSONObject()
                            .put("cmd", "refuse")
                            .put("opcode", "0x2043")
                            .put("status", "0x0c")
                            .put("count", 0),
                    )
                    bridge(JSONObject().put("cmd", "deafen").put("seconds", OUT_OF_RANGE_S + DEAF_SLACK_S))
                    sendDm("going out of range, $self")
                }
                runCatching { withTimeout(DM_TIMEOUT_MS) { mesh.transportStatuses.first { s -> s.none(::isBluetoothLink) } } }
                    .onFailure { throw AssertionError("the Bluetooth link never went down", it) }
                val mine = sendDm("written out of range by $self")
                delay(HOLD_MS)
                assertTrue(
                    "a link came back out of range: ${mesh.transportStatuses.value}",
                    mesh.transportStatuses.value.none(::isBluetoothLink),
                )
                val early = messages.observeNewestMessages(peer, WINDOW).first().any { it.body == body("written out of range by $peer") }
                assertTrue("the peer's out-of-range DM arrived with no link", !early)
                val backAt =
                    if (isSmaller) {
                        null
                    } else {
                        delay((outAt + OUT_OF_RANGE_S * MS_PER_S - System.currentTimeMillis()).coerceAtLeast(0))
                        bridge(JSONObject().put("cmd", "clear")) // back in range
                        System.currentTimeMillis()
                    }
                val wait = OUT_OF_RANGE_S * MS_PER_S + RELINK_TIMEOUT_MS + DM_TIMEOUT_MS
                awaitPeerDm("written out of range by $peer", wait)
                awaitTick(mine, wait)
                val delivered = System.currentTimeMillis()
                note(
                    "held ${(delivered - outAt) / MS_PER_S} s out of range" +
                        backAt?.let { ", delivered both ways ${delivered - it} ms after coming back" }.orEmpty(),
                )
            } finally {
                if (!isSmaller) bridge(JSONObject().put("cmd", "clear"))
            }
        }

    private fun isBluetoothLink(status: TransportStatus) = status.kind == TransportKind.Bluetooth && status.linked > 0

    private suspend fun cut() {
        val cut = bridge(JSONObject().put("cmd", "cut")).getJSONArray("cut")
        assertTrue("the bridge had no link to cut", cut.length() > 0)
    }

    /** How long after [cutAt] the bridge saw a link come up again — the controller's word, not the app's. */
    private suspend fun awaitBridgeRelink(cutAt: Long): Long =
        withTimeout(RELINK_TIMEOUT_MS) {
            while (!bridgeLinkedSince(cutAt)) delay(POLL_MS)
            System.currentTimeMillis() - cutAt
        }

    /** A link younger than the time since [cutAt]: ages are the bridge's, so the host and guest clocks never meet. */
    private suspend fun bridgeLinkedSince(cutAt: Long): Boolean {
        val sinceCutS = (System.currentTimeMillis() - cutAt) / MS_PER_S.toDouble()
        val links = bridge(JSONObject().put("cmd", "links")).getJSONArray("links")
        return (0 until links.length()).any { links.getJSONObject(it).getDouble("ageS") < sinceCutS }
    }

    private companion object {
        const val OUT_OF_RANGE_S = 40L
        const val DEAF_SLACK_S = 30L // cleared by hand at the end of the outage; this only bounds a test that died
        const val HOLD_MS = 15_000L
        const val MS_PER_S = 1_000L
    }
}
