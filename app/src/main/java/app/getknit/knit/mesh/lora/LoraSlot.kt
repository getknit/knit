package app.getknit.knit.mesh.lora

/**
 * Where a **dedicated** Knit fleet parks its radio (ADR 067) — an opt-in alternative to ADR 045's default,
 * which deliberately leaves the board on the stock public frequency so strangers' nodes relay Knit for free.
 *
 * That bargain is the right one wherever there is a Meshtastic community to borrow, and worthless where there
 * is not: an isolated farm or a mountain house has no organic boards to repeat anything, so sharing the public
 * slot buys nothing and costs the politeness ceiling `LoraAirtime` applies for the neighbours' sake. On a slot
 * of its own a household fleet is alone on the air, and the only limit that still means anything is the
 * region's legal duty cycle — see [LoraAirtime.allowanceMs].
 *
 * **The slot is computed, not configured.** Meshtastic derives its RF slot from `hash(primary channel name) %
 * numChannels` whenever `channel_num` is 0; pinning `channel_num` instead means Knit picks the transmit
 * frequency, so a slot past the end of the band is out-of-band transmission rather than a bug. Hence the two
 * rules here: the count comes from the band the region actually has ([LoraRegion.bandWidthKhz]) at the
 * board's own preset bandwidth, and a region whose band Knit does not know **exactly** gets no slot at all
 * ([forRegion] returns null and the setup refuses). Deriving it from [KnitChannel.NAME] rather than asking
 * the user keeps ADR 045's best property intact within the dedicated fleet: two Knit boards in the same
 * region on the same preset land on the same slot with no coordination at all.
 *
 * Pure; pinned by `LoraSlotTest`.
 */
internal object LoraSlot {
    /**
     * The 1-based `channel_num` a dedicated Knit board uses in [region] at [preset], or null when Knit will
     * not place one: an unknown band ([LoraRegion.bandWidthKhz] 0, which includes every duty-limited region
     * and the [LoraRegion.OTHER] bucket) or a band with no room to move ([MIN_CHANNELS]). Null is a refusal,
     * never a fallback — the caller must not write anything.
     */
    fun forRegion(
        region: LoraRegion,
        preset: ModemPreset,
    ): Int? {
        val channels = channelCount(region, preset)
        if (channels < MIN_CHANNELS) return null
        return 1 + (hash(KnitChannel.NAME) % channels.toUInt()).toInt()
    }

    /**
     * How many slots of [preset]'s bandwidth fit in [region]'s band — the same arithmetic the firmware does,
     * so the slot we pick is one the firmware could have picked itself. 0 whenever the band is unknown.
     */
    fun channelCount(
        region: LoraRegion,
        preset: ModemPreset,
    ): Int {
        val bandKhz = region.bandWidthKhz
        if (bandKhz <= 0) return 0
        // In Hz, not kHz: since 2.8 the preset list includes bandwidths that are not a whole number of
        // kilohertz (TinyFast/TinySlow are 15.6, the narrow pair 62.5), and truncating those to kHz first
        // would count slots the band does not have.
        return bandKhz * HZ_PER_KHZ / preset.bandwidthHz
    }

    /**
     * FNV-1a over the name's bytes. Any stable hash would do — this one is written out rather than borrowed
     * from `String.hashCode`, whose value is a platform promise Knit would rather not pin a frequency to.
     */
    private fun hash(name: String): UInt {
        var h = FNV_OFFSET
        for (byte in name.encodeToByteArray()) {
            h = h xor (byte.toInt() and BYTE_MASK).toUInt()
            h *= FNV_PRIME
        }
        return h
    }

    /**
     * Fewer slots than this and there is nowhere to go: the one slot a narrow band has *is* the stock slot,
     * so "dedicated" would move the radio nowhere while still lifting the politeness ceiling.
     */
    const val MIN_CHANNELS = 2

    private const val HZ_PER_KHZ = 1000
    private const val BYTE_MASK = 0xFF
    private const val FNV_OFFSET = 2166136261u
    private const val FNV_PRIME = 16777619u
}
