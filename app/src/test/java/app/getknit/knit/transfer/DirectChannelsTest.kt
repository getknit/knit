package app.getknit.knit.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

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
}
