package app.getknit.knit.transfer

/**
 * What this phone knows about the Wi-Fi channels a Wi-Fi Direct group could use (the DFS trial, 2026-11-20): [sta] is
 * the frequency its own Wi-Fi sits on (MHz, null when not associated), [client] the frequencies it can join a group on
 * as a client, and [go] those it can own one on. The two sets come from `WifiManager.getUsableChannels`, API 34+, and
 * are null below that or when the platform will not say.
 */
data class DirectChannelView(
    val sta: Int? = null,
    val client: Set<Int>? = null,
    val go: Set<Int>? = null,
)

/** The frequencies this radio can join a group on, packed for an ACCEPT's `ch` (ADR 2026-11.8zm5). */
fun DirectWifi.clientMask(): Long? = DirectChannels.mask(channels().client)

/** Where a host asks the platform to put its group: wherever it likes, on one frequency, or anywhere in 2.4 GHz. */
sealed interface GroupChannel {
    data object Auto : GroupChannel

    data class Frequency(
        val mhz: Int,
    ) : GroupChannel

    data object Band24 : GroupChannel
}

/** A host's choice of [channel], with the [reason] its log line gives. */
data class ChannelPick(
    val channel: GroupChannel,
    val reason: String,
)

/**
 * Which channel a Wi-Fi Direct group goes on, negotiated (ADR 2026-11.8zm5): the receiver names the frequencies it can
 * join a group on as a client, and the host picks one both can use, staying on its own Wi-Fi's channel when the
 * receiver can join there. Pure and JVM-tested (`DirectChannelsTest`).
 *
 * Why: a group owner follows its own Wi-Fi's channel. On 2026-11-20 a Pixel 9 whose supplicant allows Wi-Fi Direct on
 * DFS channels put its group on 5500 MHz (channel 100, DFS) beside its access point, and a Pixel 7 whose supplicant
 * refuses them (`is_dfs_allowed 0`) could never find it. Every Wi-Fi Direct feature failed for that pair.
 *
 * On the wire the receiver's frequencies are one packed field, [mask]: bit `i` of a `Long` is [TABLE]`[i]`. The table
 * is append-only (a bit's meaning never changes), at most 63 entries so the mask never touches the sign bit, and a
 * frequency outside it is not said. At most 12 bytes on a frame (the key and a CBOR uint), none when absent.
 */
object DirectChannels {
    /**
     * The frequencies the mask can name, in MHz. Append only: 2.4 GHz channels 1–13, then 5 GHz channels 36–64,
     * 100–144 and 149–177.
     */
    private val TABLE: IntArray =
        (
            (2412..2472 step 5) +
                (5180..5320 step 20) +
                (5500..5720 step 20) +
                (5745..5885 step 20)
        ).toIntArray()

    /** The most entries [TABLE] may hold: bit 63 is the sign, which a CBOR encoder writes as a negative integer. */
    const val MAX_ENTRIES = 63

    private const val DFS_LOW = 5260
    private const val DFS_HIGH = 5720
    private const val BAND_5_LOW = 5000

    // Best first: 5 GHz outside DFS (UNII-1, then UNII-3), then DFS, then 2.4 GHz's three non-overlapping channels.
    private val PREFERENCE: List<Int> =
        listOf(5180, 5200, 5220, 5240, 5745, 5765, 5785, 5805, 5825, 5845, 5865, 5885) +
            ((5260..5320 step 20) + (5500..5720 step 20)) +
            listOf(2437, 2412, 2462)

    /** [freqs] packed, or null when there are none the table names (nothing to say is absent, not zero). */
    fun mask(freqs: Set<Int>?): Long? {
        if (freqs == null) return null
        var m = 0L
        TABLE.forEachIndexed { i, mhz -> if (mhz in freqs) m = m or (1L shl i) }
        return m.takeIf { it != 0L }
    }

    /** The frequencies [mask] names; a bit past the table means nothing and is dropped. Null for an absent mask. */
    fun freqs(mask: Long?): Set<Int>? {
        if (mask == null) return null
        return TABLE.filterIndexed { i, _ -> mask and (1L shl i) != 0L }.toSet()
    }

    /** Whether [mhz] is a DFS (radar) channel: UNII-2A and UNII-2C, 5260–5720 MHz. */
    fun isDfs(mhz: Int): Boolean = mhz in DFS_LOW..DFS_HIGH

    /**
     * Where a host on [hostSta] (its own Wi-Fi's frequency, null when it has none), able to own a group on [hostGo]
     * (null when the platform will not say), puts a group for a peer that can join on [client] (null when the peer
     * did not say: an older build, or one below API 34).
     */
    fun choose(
        hostSta: Int?,
        client: Set<Int>?,
        hostGo: Set<Int>?,
    ): ChannelPick {
        if (client != null) {
            val candidates = if (hostGo != null) client intersect hostGo else client
            return when {
                hostSta == null -> ChannelPick(GroupChannel.Auto, "no Wi-Fi of its own")
                hostSta in candidates -> ChannelPick(GroupChannel.Auto, "the peer can join on $hostSta")
                candidates.isEmpty() -> ChannelPick(GroupChannel.Band24, "no channel in common")
                else -> ChannelPick(GroupChannel.Frequency(best(candidates)), "the peer cannot join on $hostSta")
            }
        }
        if (hostSta == null || !isDfs(hostSta)) return ChannelPick(GroupChannel.Auto, "the peer did not say")
        // A peer that did not say may be one that cannot join on DFS: a slower group beats one nobody finds.
        val outside = hostGo?.filter { it >= BAND_5_LOW && !isDfs(it) }?.toSet().orEmpty()
        return if (outside.isEmpty()) {
            ChannelPick(GroupChannel.Band24, "on DFS $hostSta and the peer did not say")
        } else {
            ChannelPick(GroupChannel.Frequency(best(outside)), "on DFS $hostSta and the peer did not say")
        }
    }

    /**
     * A host's group on [pick]: one [attempt] there, and when a pinned frequency is refused or forms nothing in time,
     * one more [attempt] on 2.4 GHz, which every phone joins. Only a try that formed nothing in time is [clear]ed first:
     * the framework may still be creating it, and the retry would meet BUSY. A refused one started nothing, and a clear
     * then could end another app's negotiation, since the P2P state machine is the phone's, not Knit's. A pinned
     * frequency the platform will not use costs one try, never the transfer. Null when no group formed; a refused
     * `Auto` or 2.4 GHz request, and the retry's own refusal, throw as they did. Pure so the retry is JVM-tested
     * (`DirectChannelsTest`): [attempt] is the radio's (`AndroidDirectWifi.createGroup`), null for nothing formed in
     * time and a [DirectWifiException] for a refusal.
     */
    suspend fun <T : Any> raise(
        pick: ChannelPick,
        attempt: suspend (GroupChannel) -> T?,
        clear: suspend () -> Unit,
        log: (String) -> Unit = {},
    ): T? {
        if (pick.channel !is GroupChannel.Frequency) return attempt(pick.channel)
        val silent =
            try {
                attempt(pick.channel)?.let { return it }
                true
            } catch (e: DirectWifiException) {
                log("a group on ${pick.channel} was refused: ${e.message}")
                false
            }
        log("no group on ${pick.channel}; trying 2.4 GHz")
        if (silent) clear()
        return attempt(GroupChannel.Band24)
    }

    private fun best(candidates: Set<Int>): Int = PREFERENCE.firstOrNull { it in candidates } ?: candidates.min()
}
