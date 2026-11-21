package app.getknit.knit.transfer

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Where a Wi-Fi Direct group goes (ADR 2026-11.8zm5). The lists are the two lab phones' own, read on 2026-11-20 with
 * their access point on 5500 MHz (channel 100, DFS): the Pixel 7 cannot join on DFS, the Pixel 9 can and hosts there.
 */
class DirectChannelsTest {
    private val pixel7Client =
        setOf(2412, 2417, 2422, 2427, 2432, 2437, 2442, 2447, 2452, 2457, 2462, 5180, 5200, 5220, 5240, 5745, 5765, 5785, 5805, 5825)
    private val pixel9Go =
        setOf(
            2412,
            2417,
            2422,
            2427,
            2432,
            2437,
            2442,
            2447,
            2452,
            2457,
            2462,
            5180,
            5200,
            5220,
            5240,
            5500,
            5520,
            5540,
            5560,
            5745,
            5765,
            5785,
            5805,
        )
    private val pixel9Client = pixel9Go + setOf(5260, 5280, 5300, 5320, 5580, 5600, 5620, 5640, 5660, 5680, 5700, 5720)

    @Test
    fun aMaskCarriesTheTableAndNothingElse() {
        assertEquals(pixel7Client, DirectChannels.freqs(DirectChannels.mask(pixel7Client)))
        assertEquals(pixel9Client, DirectChannels.freqs(DirectChannels.mask(pixel9Client)))
        // A frequency the table does not name is not said: 6 GHz, or an off-grid number.
        assertEquals(setOf(5180), DirectChannels.freqs(DirectChannels.mask(setOf(5180, 5955, 1234))))
        // Nothing to say is absent, never zero.
        assertNull(DirectChannels.mask(emptySet()))
        assertNull(DirectChannels.mask(setOf(5955)))
        assertNull(DirectChannels.mask(null))
        assertNull(DirectChannels.freqs(null))
        // A peer's mask is read through the table: bits past it mean nothing, and the sign bit is one of them.
        assertEquals(setOf(2412), DirectChannels.freqs(1L or Long.MIN_VALUE))
        assertTrue(DirectChannels.freqs(-1L)!!.size < DirectChannels.MAX_ENTRIES)
        assertEquals(DirectChannels.freqs(-1L), DirectChannels.freqs(DirectChannels.mask(DirectChannels.freqs(-1L))))
        assertTrue(DirectChannels.freqs(1L shl 63)!!.isEmpty())
        assertTrue(DirectChannels.freqs(1L shl 41)!!.isEmpty())
    }

    @Test
    fun aHostOnDfsMovesTheGroupWhereAPeerThatCannotJoinThereCan() {
        // The trial's pair: the Pixel 9 on 5500, the Pixel 7 unable to join there.
        val pick = DirectChannels.choose(hostSta = 5500, client = pixel7Client, hostGo = pixel9Go)
        assertEquals(GroupChannel.Frequency(5180), pick.channel)
    }

    @Test
    fun aPeerThatCanJoinOnTheHostsChannelKeepsItAndItsSpeed() {
        // Two Pixel 9s: both can use 5500, so the group stays beside the access point on one channel.
        assertEquals(GroupChannel.Auto, DirectChannels.choose(hostSta = 5500, client = pixel9Client, hostGo = pixel9Go).channel)
        // An access point outside DFS: as before.
        assertEquals(GroupChannel.Auto, DirectChannels.choose(hostSta = 5180, client = pixel7Client, hostGo = pixel9Go).channel)
    }

    @Test
    fun aPeerThatDidNotSayGetsAGroupOffDfsOnlyWhenTheHostIsOnDfs() {
        assertEquals(GroupChannel.Auto, DirectChannels.choose(hostSta = 5180, client = null, hostGo = pixel9Go).channel)
        assertEquals(GroupChannel.Frequency(5180), DirectChannels.choose(hostSta = 5500, client = null, hostGo = pixel9Go).channel)
        // A host that cannot list its own channels either: 2.4 GHz, which every phone joins.
        assertEquals(GroupChannel.Band24, DirectChannels.choose(hostSta = 5500, client = null, hostGo = null).channel)
    }

    @Test
    fun noChannelInCommonFallsToTwoPointFourAndNoWifiLeavesItToThePlatform() {
        assertEquals(GroupChannel.Band24, DirectChannels.choose(hostSta = 5500, client = setOf(5745), hostGo = setOf(5180)).channel)
        assertEquals(GroupChannel.Auto, DirectChannels.choose(hostSta = null, client = pixel7Client, hostGo = pixel9Go).channel)
        assertEquals(GroupChannel.Auto, DirectChannels.choose(hostSta = null, client = null, hostGo = null).channel)
    }

    @Test
    fun theBestCommonChannelIsFiveGigahertzOffDfsFirst() {
        assertEquals(
            GroupChannel.Frequency(5745),
            DirectChannels.choose(hostSta = 5500, client = setOf(2437, 5745, 5520), hostGo = setOf(2437, 5745, 5520)).channel,
        )
        assertEquals(
            GroupChannel.Frequency(5520),
            DirectChannels.choose(hostSta = 5500, client = setOf(2437, 5520), hostGo = setOf(2437, 5520)).channel,
        )
        assertEquals(GroupChannel.Frequency(2437), DirectChannels.choose(hostSta = 5500, client = setOf(2412, 2437), hostGo = null).channel)
    }

    @Test
    fun eachBitNamesTheFrequencyItAlwaysHas() {
        // The mask's meaning is wire: a bit that moved would have every older peer name the wrong channel. Append only.
        val frozen =
            (
                "2412 2417 2422 2427 2432 2437 2442 2447 2452 2457 2462 2467 2472 " +
                    "5180 5200 5220 5240 5260 5280 5300 5320 " +
                    "5500 5520 5540 5560 5580 5600 5620 5640 5660 5680 5700 5720 " +
                    "5745 5765 5785 5805 5825 5845 5865 5885"
            ).split(" ").map(String::toInt)
        frozen.forEachIndexed { bit, mhz -> assertEquals("bit $bit", setOf(mhz), DirectChannels.freqs(1L shl bit)) }
        assertTrue(DirectChannels.freqs(1L shl frozen.size)!!.isEmpty())
    }

    /**
     * Every pick over an alphabet holding one channel of each kind (2.4 GHz clear and overlapping, UNII-1, DFS, UNII-3,
     * UNII-4, and a 6 GHz one outside the table), for every station, every peer list as the wire delivers it (through
     * [DirectChannels.mask], so a frequency outside the table is never said) and every host list, held to the ADR's rules
     * rather than to an expected value.
     */
    @Test
    fun everyPickOverEveryKindOfChannelKeepsTheRules() {
        val alphabet = listOf(2417, 2437, 5180, 5500, 5745, 5865, 5955)
        val lists: List<Set<Int>?> =
            listOf<Set<Int>?>(null) +
                (0 until (1 shl alphabet.size)).map { bits -> alphabet.filterIndexed { i, _ -> bits and (1 shl i) != 0 }.toSet() }
        val stations = listOf(null, 2437, 2484, 5180, 5500, 5745, 5955)
        var picks = 0
        for (sta in stations) {
            for (said in lists) {
                val client = DirectChannels.freqs(DirectChannels.mask(said))
                for (go in lists) {
                    val pick = DirectChannels.choose(sta, client, go).channel
                    keepsTheRules(sta, client, go, pick)
                    picks++
                }
            }
        }
        assertEquals(stations.size * lists.size * lists.size, picks)
    }

    // What the host knew when it chose: the peer's list (null when it did not say), the host's (null likewise), the two
    // together, and the 5 GHz frequencies off DFS the host says it can own.
    private class Choice(
        val sta: Int?,
        val client: Set<Int>?,
        val go: Set<Int>?,
        val pick: GroupChannel,
    ) {
        val shared: Set<Int>? = client?.let { if (go != null) it intersect go else it }
        val ownOffDfs: List<Int> = go.orEmpty().filter(::offDfs)

        // Whether the platform's own choice, beside the station, is a group the peer can find.
        val findable: Boolean = sta == null || (shared != null && sta in shared) || (client == null && !DirectChannels.isDfs(sta))

        override fun toString() = "sta=$sta client=${client?.sorted()} go=${go?.sorted()} -> $pick"
    }

    private fun keepsTheRules(
        sta: Int?,
        client: Set<Int>?,
        go: Set<Int>?,
        pick: GroupChannel,
    ) {
        val choice = Choice(sta, client, go, pick)
        when (pick) {
            is GroupChannel.Frequency -> pinnedWhereBothCan(choice, pick.mhz)
            GroupChannel.Auto -> leftWhereThePeerFindsIt(choice)
            GroupChannel.Band24 -> onTwoPointFourOnlyWithNothingInCommon(choice)
        }
    }

    private fun pinnedWhereBothCan(
        c: Choice,
        mhz: Int,
    ) {
        if (c.client != null) assertTrue("pinned where the peer cannot join: $c", mhz in c.client)
        if (c.go != null) assertTrue("pinned where the host cannot own: $c", mhz in c.go)
        // Where the peer did not say, the host pins only from its own list, and only 5 GHz off DFS.
        if (c.client == null) assertTrue("pinned DFS or 2.4 GHz for a peer that did not say: $c", mhz in c.ownOffDfs)
        val best = (c.shared ?: c.ownOffDfs).filter(::offDfs)
        if (best.isNotEmpty()) assertTrue("passed over 5 GHz off DFS: $c", mhz in best)
        // And only where it was needed: a station's channel the peer can find keeps the group there, at its speed.
        assertFalse("pinned where the station's channel would do: $c", c.findable)
    }

    private fun leftWhereThePeerFindsIt(c: Choice) {
        assertTrue("left to the platform where the peer may not find it: $c", c.findable)
    }

    private fun onTwoPointFourOnlyWithNothingInCommon(c: Choice) {
        val sta = c.sta
        val nothingShared = c.shared?.isEmpty() ?: (sta != null && DirectChannels.isDfs(sta) && c.ownOffDfs.isEmpty())
        assertTrue("2.4 GHz with a channel in common: $c", sta != null && nothingShared)
    }

    @Test
    fun anyMaskReadsBackToTheTableAndRepacksToJustItsTableBits() {
        val table = checkNotNull(DirectChannels.freqs(-1L))
        val tableBits = checkNotNull(DirectChannels.mask(table))
        val rng = Random(SEED)
        repeat(MASKS) {
            val mask = rng.nextLong()
            val read = checkNotNull(DirectChannels.freqs(mask))
            assertTrue(table.containsAll(read))
            // A peer's mask from a newer table repacks to the bits this one names; an all-unknown one to nothing.
            assertEquals(mask and tableBits, DirectChannels.mask(read) ?: 0L)
        }
    }

    @Test
    fun aPeerNamingOnlyFrequenciesThisTableDoesNotHoldGetsTwoPointFour() {
        // A newer build's mask with only bits past this table reads as a list with nothing in it: said, and empty.
        val client = DirectChannels.freqs(1L shl FUTURE_BIT)
        assertEquals(emptySet<Int>(), client)
        assertEquals(GroupChannel.Band24, DirectChannels.choose(hostSta = 5500, client = client, hostGo = pixel9Go).channel)
        assertEquals(GroupChannel.Auto, DirectChannels.choose(hostSta = null, client = client, hostGo = pixel9Go).channel)
    }

    // ---- DirectChannels.raise: the host's one try, and the 2.4 GHz retry after a pinned one ----

    private class Radio(
        private val answer: suspend (GroupChannel) -> String?,
    ) {
        val asked: MutableList<String> = mutableListOf()

        suspend fun attempt(channel: GroupChannel): String? {
            asked += "$channel"
            return answer(channel)
        }

        fun clear() {
            asked += "clear"
        }

        suspend fun raise(channel: GroupChannel): String? =
            DirectChannels.raise(ChannelPick(channel, "test"), attempt = ::attempt, clear = ::clear)
    }

    private suspend fun refusalOf(hosting: suspend () -> Any?): DirectWifiException =
        try {
            hosting()
            throw AssertionError("raised a group, or nothing, where a refusal was due")
        } catch (e: DirectWifiException) {
            e
        }

    private val pinned = GroupChannel.Frequency(5180)
    private val refused = DirectWifiException("createGroup failed: ERROR", TransferRefusal.NoWifiDirect)

    @Test
    fun aGroupThatFormsWhereAskedIsTheOnlyTry() =
        runTest {
            for (channel in listOf(GroupChannel.Auto, pinned, GroupChannel.Band24)) {
                val radio = Radio { "group on $it" }
                assertEquals("group on $channel", radio.raise(channel))
                assertEquals(listOf("$channel"), radio.asked)
            }
        }

    @Test
    fun aPinnedFrequencyRefusedOrSilentIsRaisedOnceMoreOnTwoPointFourAndOnlyASilentOneCleared() =
        runTest {
            // Refused: nothing of ours was started, and a clear could end another app's negotiation on the phone.
            val refusing = Radio { if (it == pinned) throw refused else "group on $it" }
            assertEquals("group on ${GroupChannel.Band24}", refusing.raise(pinned))
            assertEquals(listOf("$pinned", "${GroupChannel.Band24}"), refusing.asked)

            // Nothing formed in time: a group that forms late on the pinned frequency is cleared before the retry.
            val silent = Radio { if (it == pinned) null else "group on $it" }
            assertEquals("group on ${GroupChannel.Band24}", silent.raise(pinned))
            assertEquals(listOf("$pinned", "clear", "${GroupChannel.Band24}"), silent.asked)
        }

    @Test
    fun onlyAPinnedFrequencyIsTriedTwice() =
        runTest {
            for (channel in listOf(GroupChannel.Auto, GroupChannel.Band24)) {
                val refusing = Radio { throw refused }
                assertSame(refused, refusalOf { refusing.raise(channel) })
                assertEquals(listOf("$channel"), refusing.asked)

                val silent = Radio { null }
                assertNull(silent.raise(channel))
                assertEquals(listOf("$channel"), silent.asked)
            }
        }

    @Test
    fun theTwoPointFourRetryIsTheLastAndItsRefusalIsTheOneReported() =
        runTest {
            // The retry's own refusal reaches the caller: a hotspot that took the radio says Hotspot, not NoWifiDirect.
            val busy = DirectWifiException("createGroup failed: BUSY", TransferRefusal.Hotspot)
            val refusing = Radio { if (it == pinned) throw refused else throw busy }
            assertSame(busy, refusalOf { refusing.raise(pinned) })
            assertEquals(listOf("$pinned", "${GroupChannel.Band24}"), refusing.asked)

            val silent = Radio { null }
            assertNull(silent.raise(pinned))
            assertEquals(listOf("$pinned", "clear", "${GroupChannel.Band24}"), silent.asked)
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun aCancelledTryIsNeverRetried() =
        runTest {
            // The user hung up or the call ended while the pinned group formed: no 2.4 GHz group goes up after it.
            val radio = Radio { awaitCancellation() }
            val hosting = launch { radio.raise(pinned) }
            runCurrent()
            hosting.cancelAndJoin()
            assertTrue(hosting.isCancelled)
            assertEquals(listOf("$pinned"), radio.asked)
        }

    private companion object {
        const val BAND_5_LOW = 5000

        fun offDfs(mhz: Int): Boolean = mhz >= BAND_5_LOW && !DirectChannels.isDfs(mhz)

        const val SEED = 0x8a5L
        const val MASKS = 10_000
        const val FUTURE_BIT = 50
    }
}
