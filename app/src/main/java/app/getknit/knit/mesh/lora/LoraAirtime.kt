package app.getknit.knit.mesh.lora

import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.pow

/**
 * Which airtime budget a queued frame spends from. Orthogonal to [FrameClass], which is about *queue shedding*: a
 * public post rides ROOM class there and spends [PUBLIC] here. Two buckets since ADR 2026-10.rp3s, which took the
 * bridge's (`BRIDGE`), the gossip OFFER's (`GOSSIP`) and the profile bootstrap's (`BOOTSTRAP`) with the traffic
 * they metered.
 */
internal enum class AirBucket {
    /**
     * This phone's user's own Knit traffic: the room post, the DM and its ✓✓, the first-hearing re-offer and the
     * re-probe. May spend the whole allowance.
     */
    LIVE,

    /**
     * A Knit user's own post on the **foreign** mesh's public channel (the Meshtastic room's outbound half), and
     * the DM auto-reply.
     *
     * Its own budget because it is the one thing on this plane that spends Knit's airtime on an audience
     * that is not Knit: a busy public room must not be able to crowd the pocket's own chat off the band,
     * and the pocket's own chat must not be able to leave a user unable to answer somebody. Judged against
     * the total as well — it is a share of the one allowance, not a second one beside it.
     */
    PUBLIC,
    ;

    companion object {
        /**
         * The bucket a persisted booking named [name] is restored onto, or null to drop it. The retired buckets of
         * ADR 2026-10.rp3s come back as [LIVE] — air the last process spent is still owed to the window it spent it
         * in, and dropping it would hand the first launch after the upgrade a fresh allowance (ADR 2026-09.7svb) —
         * except `BOOTSTRAP`, which was judged outside the total and so never spent the allowance it would now
         * be charged to. A name no build ever wrote is dropped, as it always was.
         */
        fun restoredFrom(name: String): AirBucket? =
            when (name) {
                RETIRED_BRIDGE, RETIRED_GOSSIP -> LIVE
                else -> entries.firstOrNull { it.name == name }
            }

        private const val RETIRED_BRIDGE = "BRIDGE"
        private const val RETIRED_GOSSIP = "GOSSIP"
    }
}

/**
 * How a caller prices a packet on the air, and what it would rather the packet weighed. [LoraAirtime] is the
 * implementation that matters; the interface exists so [LoraFrameCodec] can stay pure — priced against a fake
 * in its own tests, and against nothing at all (null) by a caller with no board to price for.
 */
internal interface PacketCost {
    /** Milliseconds [payloadBytes] will occupy the medium, the firmware's own signature included. */
    fun timeOnAirMs(payloadBytes: Int): Long

    /** The size [payloadBytes] is *cheaper* at, or [payloadBytes] itself; [cap] is the board's payload limit. */
    fun padTo(
        payloadBytes: Int,
        cap: Int,
    ): Int
}

/**
 * One packet booked against the rolling window: when it went out, what it cost, and out of whose share.
 *
 * The ledger's own entry type, and also what [LoraPlaneState] persists — the cost is carried rather than
 * re-derived from a packet size, because a board swapped between two processes has a different preset and
 * the air the last one spent was spent at the old one.
 */
internal data class Booking(
    val atMs: Long,
    val ms: Long,
    val bucket: AirBucket,
)

/** A read-only view of the governor for the settings row and the `…debug.LORA` dump. */
internal data class AirtimeSnapshot(
    val preset: ModemPreset,
    val region: LoraRegion,
    val known: Boolean,
    val liveUsedMs: Long,
    val liveBudgetMs: Long,
    val publicUsedMs: Long = 0L,
    val publicBudgetMs: Long = 0L,
    /** Whether the board is on a dedicated RF slot, and so out from under the politeness ceiling (ADR 067). */
    val dedicated: Boolean = false,
    /** Whether the budget is charging for the signature 2.8 firmware adds to our small packets. */
    val signing: Boolean = true,
) {
    /**
     * Every bucket's spend, which is what the window's one allowance is actually measured against — the
     * public share is how that allowance is divided, not a second allowance beside it. Callers that want "how
     * full is the window" must read this rather than adding up the buckets they happen to know about: two of them
     * once summed three of five and under-reported the plane's real utilization.
     */
    val totalUsedMs: Long get() = liveUsedMs + publicUsedMs
}

/**
 * The LoRa plane's airtime governor: turns a packet size into milliseconds on air, keeps a rolling
 * [WINDOW_MS] ledger of what we have spent, and answers whether one more packet fits its bucket's budget.
 *
 * Before this existed the only regulator was reactive — the board's `DUTY_CYCLE_LIMIT` NAK, which arrives
 * *after* the medium has already been abused, and [LoraPacePolicy]'s fixed 3-second gap, which by itself
 * would allow ~1200 packets an hour (over 70 % duty).
 *
 * Two budgets come out of one number. [AirBucket.LIVE] may spend the whole allowance; [AirBucket.PUBLIC] is capped
 * at [publicShare] of it and judged against the total as well. A [FrameClass.TICK] — our own delivery receipt — is
 * refused once a window is [tickTailShare] from spent, so the last of the air always goes to content (ADR 054): a
 * ✓✓ heals on re-delivery, a message somebody is waiting for does not. The bridge's, the gossip OFFER's and the
 * profile bootstrap's shares (ADR 044, 2026-09.t8t8, 2026-09.7c8n, 056) left with that traffic (ADR 2026-10.rp3s).
 *
 * The allowance itself is `min(the region's duty cycle, a politeness ceiling) x a safety factor` of the
 * window. The region and modem preset are read off the board ([LoraRadioConfig]); until the handshake
 * reports them we assume [FALLBACK_PERCENT], which is below every real region's limit. Pure and
 * clock-driven by the caller, like [LoraPacePolicy] — the transport owns the actual clock.
 *
 * **Every entry point is `@Synchronized`.** The ledger is read and written from more than one coroutine:
 * the pacer books and asks, [LoraFrameCodec] prices a packet on whichever coroutine is sending, and the
 * [LoraPlaneState] save takes a snapshot of it — and each of those prunes, so each of them *mutates*.
 * The monitor is taken **inside** [LoraPacePolicy]'s and never the other way round, which is the whole
 * lock order there is.
 *
 * One thing the packet we hand the board is not: what leaves it. Firmware **2.8 signs every broadcast it
 * originates**, ours included, adding [MeshtasticProto.XEDDSA_SIGNATURE_FIELD] bytes to any packet small
 * enough to still fit one ([MeshtasticProto.MAX_SIGNED_PAYLOAD]). Knit neither asks for that nor can decline
 * it, but it is real air, and unbudgeted it lands hardest on exactly the frames ADR 060 shrank into one
 * packet — a 157-byte transcoded tick is 36 % more air than this class used to charge for. So
 * [timeOnAirMs] adds the signature itself, gated on the board's firmware ([signsPackets]) — and [padTo]
 * turns the same cliff back into a saving, since a packet grown one byte past it sheds all 66 (ADR 2026-09.mhs5).
 *
 * Those two ceilings are **independent**, which is what [dedicatedUnlocksDuty] turns on (ADR 067). The
 * regional duty cycle is law and only the firmware's own `override_duty_cycle` escape hatch lifts it; the
 * politeness ceiling is Knit's own manners toward everyone else sharing the stock frequency, and on a board
 * pinned to a dedicated RF slot ([LoraRadioConfig.dedicatedSlot], [LoraSlot]) there is no-one there to be
 * polite to. So a dedicated US board runs to the 100 % its region allows and an EU_868 one still stops at
 * 10 %, because that one was never politeness.
 */
internal class LoraAirtime(
    private val windowMs: Long = WINDOW_MS,
    private val safety: Double = SAFETY,
    private val politeCeilingPercent: Double = POLITE_CEILING_PERCENT,
    private val tickTailShare: Double = TICK_TAIL_SHARE,
    private val publicShare: Double = PUBLIC_SHARE,
    /**
     * Whether a dedicated RF slot lifts the politeness ceiling. On in the transport; false by default so the
     * class can be tested in both states.
     */
    private val dedicatedUnlocksDuty: Boolean = false,
) : PacketCost {
    private val samples = ArrayDeque<Booking>()
    private var liveUsedMs = 0L
    private var publicUsedMs = 0L

    /** The board's radio settings, or null until the handshake reports them. */
    var radio: LoraRadioConfig? = null
        private set

    /**
     * Whether the bound board's firmware signs the broadcasts it sends for us, and so whether [timeOnAirMs]
     * charges for the signature. True until a board says otherwise: the unknown case has to be the expensive
     * one, since guessing "unsigned" against firmware that signs is the one error that spends air we never
     * budgeted — and in a duty-limited region that is somebody else's law, not our politeness.
     */
    var signing: Boolean = true
        private set

    /** Records the board's radio settings from the config handshake; a null report leaves the last one standing. */
    @Synchronized
    fun onRadioConfig(config: LoraRadioConfig?) {
        if (config != null) radio = config
    }

    /**
     * Records what the handshake's `DeviceMetadata` says about signing: [hasXeddsa] is the board's own word
     * (`has_xeddsa`, a build that verifies and so signs) and wins when present; a firmware too old to carry
     * the field is judged by its [version] instead, and null reads as unknown.
     */
    @Synchronized
    fun onFirmware(
        version: String?,
        hasXeddsa: Boolean? = null,
    ) {
        signing = hasXeddsa ?: signsPackets(version)
    }

    /**
     * Milliseconds this packet will occupy the medium, from the LoRa time-on-air formula (Semtech AN1200.13)
     * at the board's preset. [payloadBytes] is the `Data.payload` we hand the board; [PACKET_OVERHEAD_BYTES]
     * covers the Meshtastic header and protobuf/crypto framing around it. This is a governor's estimate, not
     * a measurement — it does not know the board's preamble length or whether a rebroadcaster repeated us.
     */
    @Synchronized
    override fun timeOnAirMs(payloadBytes: Int): Long = timeOnAirMs(payloadBytes, MeshtasticProto.MAX_SIGNED_PAYLOAD)

    /**
     * [timeOnAirMs] for a packet whose signature cliff is [signedUpTo] rather than a Knit frame's: the
     * Meshtastic room's `TEXT_MESSAGE_APP` posts sign one byte further (`MeshtasticProto.maxSignedPayload`),
     * and a post at that cap is signed air the ledger has to book, not an unsigned packet one byte past it.
     */
    @Synchronized
    fun timeOnAirMs(
        payloadBytes: Int,
        signedUpTo: Int,
    ): Long {
        val preset = radio?.modemPreset ?: ModemPreset.LONG_FAST
        val sf = preset.spreadFactor
        val symbolMs = 2.0.pow(sf) * MS_PER_SECOND / preset.bandwidthHz
        // Low-data-rate optimize: the firmware enables it once a symbol exceeds 16 ms, which costs 2 bits/symbol.
        val de = if (symbolMs > LDO_THRESHOLD_MS) 1 else 0
        val phyBytes = payloadBytes + PACKET_OVERHEAD_BYTES + signatureBytes(payloadBytes, signedUpTo)
        val numerator = (BITS_PER_BYTE * phyBytes - 4 * sf + PAYLOAD_CONST + CRC_BITS).toDouble()
        val denominator = (4 * (sf - 2 * de)).toDouble()
        val payloadSymbols = PAYLOAD_SYMBOL_BASE + maxOf(0.0, ceil(numerator / denominator) * preset.codingRate)
        return ((PREAMBLE_SYMBOLS + payloadSymbols) * symbolMs).toLong().coerceAtLeast(1)
    }

    /**
     * The firmware's contention slot at this board's preset (`RadioInterface::computeSlotTimeMsec`, firmware
     * `src/mesh/RadioInterface.cpp`, v2.8.1): a channel-activity scan of four symbols plus half a symbol on an SX126x, plus
     * 7.6 ms of propagation, turnaround and MAC time — ≈ 44.5 ms at LongFast. What a reliable unicast's retransmit gap
     * is counted in (`LoraDirectPolicy.retransmitMs`, ADR 2026-10.x9fa).
     */
    @Synchronized
    fun slotTimeMs(): Double {
        val preset = radio?.modemPreset ?: ModemPreset.LONG_FAST
        val symbolMs = 2.0.pow(preset.spreadFactor) * MS_PER_SECOND / preset.bandwidthHz
        return (CAD_SYMBOLS + CAD_TAIL_SYMBOLS) * symbolMs + SLOT_TURNAROUND_MS
    }

    /**
     * The signature the board will bolt onto this packet, or 0. Mirrors the firmware's own `signedDataFits`
     * gate: it signs what still fits signed and sends the rest as it always did, so this is a *cliff* rather
     * than a ramp — a 165-byte payload costs 66 bytes more than a 166-byte one.
     */
    private fun signatureBytes(
        payloadBytes: Int,
        signedUpTo: Int,
    ): Int = if (signing && payloadBytes <= signedUpTo) MeshtasticProto.XEDDSA_SIGNATURE_FIELD else 0

    /**
     * The size to grow a [payloadBytes] packet to so the firmware will **not** sign it, or [payloadBytes]
     * itself when that trade does not pay (ADR 2026-09.mhs5). [cap] is the largest `Data.payload` this board takes.
     *
     * The gate the firmware applies is a cliff, so the packets just under it pay a 66-byte signature that a
     * packet one byte larger does not — a 165-byte payload and a 231-byte one cost the same air. Padding past
     * the cliff buys that back for the width of the pad, and the comparison is made with [timeOnAirMs] rather
     * than in bytes so it is exact at the board's own preset and symbol quantization. Everything else falls
     * out of that one comparison: a board that does not sign ([signing] false) never sees a saving, and
     * neither does a packet already over the cliff.
     *
     * The **caller** owes the other half of the bargain — pad only a frame whose body is a deflate stream
     * ([app.getknit.knit.mesh.link.FastFrameCodec.deflated]), the only form where a receiver ignores the
     * trailing bytes.
     */
    @Synchronized
    override fun padTo(
        payloadBytes: Int,
        cap: Int,
    ): Int {
        val target = MeshtasticProto.MAX_SIGNED_PAYLOAD + 1
        if (payloadBytes >= target || target > cap) return payloadBytes
        return if (timeOnAirMs(target) < timeOnAirMs(payloadBytes)) target else payloadBytes
    }

    /**
     * The window's allowance, in milliseconds of air, before the per-bucket split — the lower of the two
     * ceilings that still apply (see the class doc).
     */
    @Synchronized
    fun allowanceMs(): Long {
        val cfg = radio ?: return (windowMs * FALLBACK_PERCENT / PERCENT * safety).toLong()
        // Law. The user set the firmware's own duty-cycle override: they have taken the regulatory call, so
        // the regional cap stops applying to us too.
        val regional = if (cfg.overrideDutyCycle) FULL_PERCENT else cfg.region.dutyCyclePercent
        // Manners. Nobody to be polite to on a slot of our own, so the ceiling lifts with the shared band.
        val polite = if (dedicatedUnlocksDuty && cfg.dedicatedSlot) FULL_PERCENT else politeCeilingPercent
        return (windowMs * min(regional, polite) / PERCENT * safety).toLong()
    }

    /** Whether the budget above is running under the dedicated-slot rules; diagnostics and the settings row. */
    fun dedicated(): Boolean = dedicatedUnlocksDuty && radio?.dedicatedSlot == true

    @Synchronized
    fun budgetMs(bucket: AirBucket): Long =
        when (bucket) {
            AirBucket.LIVE -> allowanceMs()
            AirBucket.PUBLIC -> (allowanceMs() * publicShare).toLong()
        }

    @Synchronized
    fun usedMs(
        bucket: AirBucket,
        now: Long,
    ): Long {
        prune(now)
        return when (bucket) {
            AirBucket.LIVE -> liveUsedMs
            AirBucket.PUBLIC -> publicUsedMs
        }
    }

    /**
     * Whether a whole frame — [payloadSizes] is one entry per packet it fragments into — fits [bucket]'s
     * budget. Admission is all-or-nothing per frame: half a fragmented message on the air is pure waste, so
     * a frame that does not fit entirely waits rather than starting. A [FrameClass.TICK] stops at the tail. Note
     * every bucket's spending counts against the **total** as well as its own budget: [AirBucket.PUBLIC] is a share
     * of the one allowance, not a second allowance beside it. [refusal] says why a frame does not fit.
     */
    @Synchronized
    fun admits(
        bucket: AirBucket,
        klass: FrameClass,
        payloadSizes: List<Int>,
        now: Long,
        /** The signature cliff for these packets' port — a Knit frame's unless the caller says otherwise. */
        signedUpTo: Int = MeshtasticProto.MAX_SIGNED_PAYLOAD,
    ): Boolean = refusal(bucket, klass, payloadSizes, now, signedUpTo) == null

    /**
     * Why [admits] refuses a frame, or null when it fits. The pacer needs the reason, not just the verdict: a live
     * frame the [Refusal.TOTAL] refuses holds every frame ranked below it off the total until it has gone
     * ([LoraPacePolicy]), and a frame refused on its own [Refusal.SHARE] must never hold anything, or a spent
     * public share would freeze the pocket's own chat, the one thing that share exists to prevent.
     */
    @Synchronized
    fun refusal(
        bucket: AirBucket,
        klass: FrameClass,
        payloadSizes: List<Int>,
        now: Long,
        signedUpTo: Int = MeshtasticProto.MAX_SIGNED_PAYLOAD,
    ): Refusal? {
        prune(now)
        val cost = payloadSizes.sumOf { timeOnAirMs(it, signedUpTo) }
        val used = liveUsedMs + publicUsedMs
        val total = budgetMs(AirBucket.LIVE)
        val tickCeiling = (total * (1 - tickTailShare)).toLong()
        // A `when` rather than a ladder of early returns only because there are several answers; the order
        // is the same and load-bearing.
        return when {
            // No window, however empty, would ever admit it: it must never be waited for.
            cost > total -> {
                Refusal.NEVER
            }

            used + cost > total -> {
                Refusal.TOTAL
            }

            // The public channel is judged against its own share as well as the total, in both directions: it
            // cannot crowd out the pocket's own traffic, and the pocket's own traffic leaves it a floor.
            bucket == AirBucket.PUBLIC -> {
                if (publicUsedMs + cost <= budgetMs(AirBucket.PUBLIC)) null else Refusal.SHARE
            }

            klass == FrameClass.TICK && used + cost > tickCeiling -> {
                Refusal.TICK_TAIL
            }

            else -> {
                null
            }
        }
    }

    /** Why [refusal] turned a frame away. */
    enum class Refusal {
        /** The window's total is spent: the frame fits once enough of the window rolls off. */
        TOTAL,

        /** The frame's own share is spent: [AirBucket.PUBLIC]. */
        SHARE,

        /** A [FrameClass.TICK] inside the last [tickTailShare] of the window (ADR 054). */
        TICK_TAIL,

        /** Larger than the whole allowance: no window will ever admit it, so nothing may wait on it. */
        NEVER,
    }

    /**
     * Gives back up to [count] bookings of exactly [ms] made at [atMs] against [bucket] — the airings a compact DM was
     * booked for at send and did not need, its destination having acked it sooner (ADR 2026-10.x9fa). A booking the
     * window has already rolled off is gone anyway. Returns how many came back.
     */
    @Synchronized
    fun refund(
        bucket: AirBucket,
        atMs: Long,
        ms: Long,
        count: Int,
    ): Int {
        var left = count
        val it = samples.iterator()
        while (left > 0 && it.hasNext()) {
            val booking = it.next()
            if (booking.atMs == atMs && booking.ms == ms && booking.bucket == bucket) {
                it.remove()
                book(bucket, -ms)
                left--
            }
        }
        return count - left
    }

    /** Books [payloadBytes] of air against [bucket]. Called once the board has actually accepted the write. */
    @Synchronized
    fun record(
        bucket: AirBucket,
        payloadBytes: Int,
        now: Long,
        signedUpTo: Int = MeshtasticProto.MAX_SIGNED_PAYLOAD,
    ) {
        prune(now)
        val ms = timeOnAirMs(payloadBytes, signedUpTo)
        samples.addLast(Booking(now, ms, bucket))
        book(bucket, ms)
    }

    /**
     * The live window's bookings, oldest first — what [LoraPlaneState] persists so that a process restart
     * cannot hand the plane a fresh allowance. Pruned first, so it is only ever what the window still holds.
     */
    @Synchronized
    fun bookings(now: Long): List<Booking> {
        prune(now)
        return samples.toList()
    }

    /**
     * Replaces the ledger with what a previous process spent ([bookings]), then ages it to [now].
     *
     * Sorted on the way in because [prune] walks the deque in send order and a restored list has been
     * through a clock conversion, so nothing may assume it arrives ordered. Replaces rather than merges:
     * this runs once, before the pacer is allowed to transmit at all — see `LoraMeshTransport.awaitRestored`.
     */
    @Synchronized
    fun restore(
        bookings: List<Booking>,
        now: Long,
    ) {
        samples.clear()
        liveUsedMs = 0L
        publicUsedMs = 0L
        bookings.sortedBy { it.atMs }.forEach { booking ->
            samples.addLast(booking)
            book(booking.bucket, booking.ms)
        }
        prune(now)
    }

    /** Adds [ms] to [bucket]'s running total; negative to give it back, which is what [prune] does. */
    private fun book(
        bucket: AirBucket,
        ms: Long,
    ) {
        when (bucket) {
            AirBucket.LIVE -> liveUsedMs += ms
            AirBucket.PUBLIC -> publicUsedMs += ms
        }
    }

    /**
     * When the rolling window next hands air back — the oldest booked sample's expiry — or null when the
     * ledger is empty. A caller the budget just refused has nothing to gain by asking again before this, so
     * it is what the pacer sleeps until rather than re-asking a question whose answer cannot have changed.
     */
    @Synchronized
    fun nextReleaseAt(now: Long): Long? {
        prune(now)
        return samples.firstOrNull()?.let { it.atMs + windowMs }
    }

    @Synchronized
    fun snapshot(now: Long): AirtimeSnapshot {
        prune(now)
        val cfg = radio
        return AirtimeSnapshot(
            preset = cfg?.modemPreset ?: ModemPreset.LONG_FAST,
            region = cfg?.region ?: LoraRegion.UNSET,
            known = cfg != null,
            liveUsedMs = liveUsedMs,
            liveBudgetMs = budgetMs(AirBucket.LIVE),
            publicUsedMs = publicUsedMs,
            publicBudgetMs = budgetMs(AirBucket.PUBLIC),
            dedicated = dedicated(),
            signing = signing,
        )
    }

    /** Drops samples that have aged out of the rolling window. Cheap: the deque is in send order. */
    private fun prune(now: Long) {
        while (true) {
            val oldest = samples.firstOrNull() ?: return
            if (now - oldest.atMs < windowMs) return
            samples.removeFirst()
            book(oldest.bucket, -oldest.ms)
        }
    }

    companion object {
        /**
         * The signature cliff of a packet the firmware never signs: a unicast (ADR 2026-10.x9fa). 2.8 signs only the
         * non-PKI **broadcasts** it originates, so a compact DM and the destination's ACK are priced bare.
         */
        const val UNSIGNED = 0

        /**
         * Whether [version] — the board's `DeviceMetadata.firmware_version`, e.g. `"2.8.0.7239fe8"` — is
         * firmware that signs the broadcasts it originates. Signing arrived in **2.8**; anything older adds
         * nothing to our packets and must not be charged for it, or the plane quietly loses a third of its
         * capacity on the boards everyone is actually running today.
         *
         * A version it cannot read is treated as signing, for the reason [signing] gives: over-charging
         * costs throughput, under-charging can cost compliance.
         */
        fun signsPackets(version: String?): Boolean {
            val parts = version?.trim()?.split('.') ?: return true
            val major = parts.getOrNull(0)?.toIntOrNull() ?: return true
            val minor = parts.getOrNull(1)?.toIntOrNull() ?: return true
            return major > SIGNING_MAJOR || (major == SIGNING_MAJOR && minor >= SIGNING_MINOR)
        }

        /** The firmware release that started signing broadcasts (`Router::perhapsEncode`, 2.8.0). */
        private const val SIGNING_MAJOR = 2
        private const val SIGNING_MINOR = 8

        /**
         * The rolling window every budget is expressed over. It was an hour (ADR 044) — the unit the EU duty
         * cycle is written in — which let a burst of chat spend the whole allowance in minutes and then left
         * the plane dark for the rest of it. Fifteen minutes at the same percentage keeps the hourly total
         * and the politeness figure, caps a burst at a quarter of it, and bounds a dark spell at one window.
         * Windows straddle, so the worst hour is 5/4 of the nominal — ≤ 6.25 % — still under the 10 % the
         * EU firmware refuses at (ADR 054).
         */
        const val WINDOW_MS = 15 * 60_000L

        /**
         * How much of the legal allowance Knit will use. We share the band with everyone else's Meshtastic
         * traffic, and the estimate above is an estimate — half is the honest place to sit.
         */
        const val SAFETY = 0.5

        /** The last share of a window a [FrameClass.TICK] may not spend — it is kept for content. */
        const val TICK_TAIL_SHARE = 0.25

        /**
         * The share of the allowance a Knit user's posts to the **foreign** public channel may spend.
         *
         * Small on purpose, and small enough that the per-phone floor is usually the binding limit rather
         * than this: at LongFast a 200-byte signed post is ~1.9 s, so 15 % of a 45 s window is about three
         * posts every fifteen minutes — roughly twice what a 30 s floor allows, which is the right ordering.
         * A budget that bound first would make the refusal depend on what the *rest* of the plane had been
         * doing, and "your message went nowhere because somebody nearby was syncing" is not an explanation
         * anybody can act on.
         */
        const val PUBLIC_SHARE = 0.15

        /**
         * The cap Knit applies even where the law does not. Most regions run at 100 % duty, but a phone that
         * transmits a third of every hour on a shared community band is a bad neighbour, and the Meshtastic
         * firmware itself warns above 10 % channel utilization.
         */
        const val POLITE_CEILING_PERCENT = 10.0

        /** Assumed before the board reports its region — below every real region's limit. */
        const val FALLBACK_PERCENT = 5.0

        /** "This ceiling does not apply": the whole window, left for the other ceiling to bound. */
        private const val FULL_PERCENT = 100.0

        /** Meshtastic header + protobuf/crypto framing around our `Data.payload`. */
        const val PACKET_OVERHEAD_BYTES = 24

        private const val PERCENT = 100.0
        private const val MS_PER_SECOND = 1000.0
        private const val LDO_THRESHOLD_MS = 16.0
        private const val BITS_PER_BYTE = 8
        private const val PAYLOAD_CONST = 28
        private const val CRC_BITS = 16
        private const val PAYLOAD_SYMBOL_BASE = 8.0

        /** 16 preamble symbols (the firmware's setting) plus the 4.25-symbol sync word. */
        private const val PREAMBLE_SYMBOLS = 20.25

        // The contention slot (`RadioInterface::computeSlotTimeMsec`): an SX126x CAD of four symbols, half a symbol of
        // processing, and 0.2 + 0.4 + 7 ms of propagation, turnaround and MAC time.
        private const val CAD_SYMBOLS = 4
        private const val CAD_TAIL_SYMBOLS = 0.5
        private const val SLOT_TURNAROUND_MS = 7.6
    }
}
