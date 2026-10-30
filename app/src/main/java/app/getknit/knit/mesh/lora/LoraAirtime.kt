package app.getknit.knit.mesh.lora

import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.pow

/**
 * How much of the window's one allowance a frame may bring the total to (ADR 2026-10.ug4a). Every Knit frame of ours
 * may spend the whole of it; a frame for the **foreign** audience — the Meshtastic room's post and the DM auto-reply —
 * only up to [LoraAirtime.PUBLIC_CEILING] of it, its own cost included. There are no buckets: one ledger, one total,
 * and a ceiling on how far into it the foreign audience may reach.
 */
internal enum class Ceiling {
    /** The whole allowance: every Knit frame of ours — a room post, a compact DM, its ✓✓, a reaction, a resend, a re-probe. */
    FULL,

    /**
     * Half of it, judged against the window's total: the Meshtastic room's post and the DM auto-reply. A deliberate
     * choice — up to half the window when Knit is idle — that the 30 s floors (`PUBLIC_POST_FLOOR_MS`,
     * `DmAutoReplyPolicy.FLOOR_MS`) bind long before in practice.
     */
    HALF,
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
 * One packet booked against the rolling window: when it went out and what it cost. The cost is carried rather than
 * re-derived from a size, because [LoraAirtime.refund] matches a booking on the two together. In memory only since ADR
 * 2026-10.ug4a: the ledger is no longer persisted.
 */
private data class Booking(
    val atMs: Long,
    val ms: Long,
)

/** A read-only view of the governor for the settings row, the field log and the `…debug.LORA` dump. */
internal data class AirtimeSnapshot(
    val preset: ModemPreset,
    val region: LoraRegion,
    val known: Boolean,
    /** What the window has spent, every frame of ours together: one ledger, no buckets (ADR 2026-10.ug4a). */
    val usedMs: Long,
    /** The window's one allowance — what [usedMs] is measured against. */
    val allowanceMs: Long,
    /** How far into [allowanceMs] the Meshtastic room and the DM auto-reply may take the total ([Ceiling.HALF]). */
    val publicCeilingMs: Long = 0L,
    /** Whether the board is on a dedicated RF slot, and so out from under the politeness ceiling (ADR 067). */
    val dedicated: Boolean = false,
    /** Whether the budget is charging for the signature 2.8 firmware adds to our small packets. */
    val signing: Boolean = true,
)

/**
 * The LoRa plane's airtime governor: turns a packet size into milliseconds on air, keeps a rolling
 * [WINDOW_MS] ledger of what we have spent, and answers whether one more frame fits under its [Ceiling].
 *
 * Before this existed the only regulator was reactive — the board's `DUTY_CYCLE_LIMIT` NAK, which arrives
 * *after* the medium has already been abused, and [LoraPacePolicy]'s fixed 3-second gap, which by itself
 * would allow ~1200 packets an hour (over 70 % duty).
 *
 * **One ledger and one total** (ADR 2026-10.ug4a). Every frame of ours spends the same allowance; a frame for the
 * foreign audience ([Ceiling.HALF]: the Meshtastic room's post, the DM auto-reply) may only take the total to
 * [publicCeiling] of it. The buckets (`LIVE`, `PUBLIC` and, before ADR 2026-10.rp3s, three more) and ADR 054's tick
 * tail are gone: a ✓✓ is one unsigned unicast packet now, and losing it costs a re-probe of the whole DM later, so it
 * waits with the DMs rather than behind them ([LoraPacePolicy] orders the two priorities).
 *
 * The allowance itself is `min(the region's duty cycle, a politeness ceiling) x a safety factor` of the
 * window. The region and modem preset are read off the board ([LoraRadioConfig]); until the handshake
 * reports them we assume [FALLBACK_PERCENT], which is below every real region's limit. Pure and
 * clock-driven by the caller, like [LoraPacePolicy] — the transport owns the actual clock.
 *
 * **Every entry point is `@Synchronized`.** The ledger is read and written from more than one coroutine:
 * the pacer books and asks, [LoraFrameCodec] prices a packet on whichever coroutine is sending, and the
 * status publisher takes a snapshot of it — and each of those prunes, so each of them *mutates*.
 * The monitor is taken **inside** [LoraPacePolicy]'s and never the other way round, which is the whole
 * lock order there is.
 *
 * **Not persisted** (ADR 2026-10.ug4a, superseding ADR 2026-09.7svb's ledger half): a restart starts the window empty.
 * The regional duty cycle — the law — is the firmware's to enforce (`Router::send` refuses with `DUTY_CYCLE_LIMIT`
 * over the trailing hour, which the pacer backs off on), the only frames that air without a user action are the
 * re-probes, whose backoff is persisted, and in a 100 %-duty region a restart resets politeness by at most one
 * allowance. The board's own `air_util_tx` is not a seed for it: an hour of everything the radio sent, relays
 * included, it over-books a client on a busy mesh by most of an allowance and under-books a recent burst.
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
    private val publicCeiling: Double = PUBLIC_CEILING,
    /**
     * Whether a dedicated RF slot lifts the politeness ceiling. On in the transport; false by default so the
     * class can be tested in both states.
     */
    private val dedicatedUnlocksDuty: Boolean = false,
) : PacketCost {
    private val samples = ArrayDeque<Booking>()
    private var spentMs = 0L

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

    /** The window's one allowance, in milliseconds of air — the lower of the two ceilings that still apply (see the class doc). */
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

    /** Whether the budget above is running under the dedicated-slot rules; diagnostics, the settings row and the brake. */
    @Synchronized
    fun dedicated(): Boolean = dedicatedUnlocksDuty && radio?.dedicatedSlot == true

    /** How far into [allowanceMs] a [Ceiling.HALF] frame may take the window's total. */
    @Synchronized
    fun publicCeilingMs(): Long = (allowanceMs() * publicCeiling).toLong()

    /** What the window has spent at [now], every frame of ours together. */
    @Synchronized
    fun usedMs(now: Long): Long {
        prune(now)
        return spentMs
    }

    /**
     * Whether a whole frame — [payloadSizes] is one entry per packet it fragments into — fits under [ceiling].
     * Admission is all-or-nothing per frame: half a fragmented message on the air is pure waste, so a frame that does
     * not fit entirely waits rather than starting. [refusal] says why a frame does not fit.
     */
    @Synchronized
    fun admits(
        ceiling: Ceiling,
        payloadSizes: List<Int>,
        now: Long,
        /** The signature cliff for these packets' port — a Knit frame's unless the caller says otherwise. */
        signedUpTo: Int = MeshtasticProto.MAX_SIGNED_PAYLOAD,
    ): Boolean = refusal(ceiling, payloadSizes, now, signedUpTo) == null

    /**
     * Why [admits] refuses a frame, or null when it fits. The pacer needs the reason, not just the verdict: a direct
     * frame the [Refusal.TOTAL] refuses holds every frame ranked below it until it has gone ([LoraPacePolicy]), while a
     * frame refused on its [Refusal.CEILING] must never hold anything, or a busy Meshtastic room would freeze the
     * pocket's own chat — the one thing the ceiling exists to prevent.
     */
    @Synchronized
    fun refusal(
        ceiling: Ceiling,
        payloadSizes: List<Int>,
        now: Long,
        signedUpTo: Int = MeshtasticProto.MAX_SIGNED_PAYLOAD,
    ): Refusal? {
        prune(now)
        val cost = payloadSizes.sumOf { timeOnAirMs(it, signedUpTo) }
        val total = allowanceMs()
        // A `when` rather than a ladder of early returns only because there are several answers; the order
        // is the same and load-bearing.
        return when {
            // No window, however empty, would ever admit it: it must never be waited for.
            cost > total -> Refusal.NEVER

            spentMs + cost > total -> Refusal.TOTAL

            // The foreign audience's ceiling is on the window's total, its own cost included: it cannot take the
            // window past half, however little of it the room itself has spent.
            ceiling == Ceiling.HALF && spentMs + cost > publicCeilingMs() -> Refusal.CEILING

            else -> null
        }
    }

    /** Why [refusal] turned a frame away. */
    enum class Refusal {
        /** The window's total is spent: the frame fits once enough of the window rolls off. */
        TOTAL,

        /** A [Ceiling.HALF] frame would take the total past [publicCeilingMs]. Never holds anything behind it. */
        CEILING,

        /** Larger than the whole allowance: no window will ever admit it, so nothing may wait on it. */
        NEVER,
    }

    /**
     * Gives back up to [count] bookings of exactly [ms] made at [atMs] — the airings a compact DM was booked for at send
     * and did not need, its destination having acked it sooner (ADR 2026-10.x9fa). Matched on the two together, which
     * is exact enough with one ledger: a room post is one packet at its own cost, and only a compact DM books five
     * identical airings at one instant. A booking the window has already rolled off is gone anyway. Returns how many
     * came back.
     */
    @Synchronized
    fun refund(
        atMs: Long,
        ms: Long,
        count: Int,
    ): Int {
        var left = count
        val it = samples.iterator()
        while (left > 0 && it.hasNext()) {
            val booking = it.next()
            if (booking.atMs == atMs && booking.ms == ms) {
                it.remove()
                spentMs -= ms
                left--
            }
        }
        return count - left
    }

    /** Books [payloadBytes] of air. Called once the board has actually accepted the write. */
    @Synchronized
    fun record(
        payloadBytes: Int,
        now: Long,
        signedUpTo: Int = MeshtasticProto.MAX_SIGNED_PAYLOAD,
    ) {
        prune(now)
        val ms = timeOnAirMs(payloadBytes, signedUpTo)
        samples.addLast(Booking(now, ms))
        spentMs += ms
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
            usedMs = spentMs,
            allowanceMs = allowanceMs(),
            publicCeilingMs = publicCeilingMs(),
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
            spentMs -= oldest.ms
        }
    }

    companion object {
        /**
         * The signature cliff of a packet the firmware never signs: a unicast (ADR 2026-10.x9fa). 2.8 signs only the
         * non-PKI **broadcasts** it originates, and a unicast only in ham mode (`Router.cpp:1271` at v2.8.1), so a
         * compact DM — a PKI unicast, priced with its PKC overhead — the destination's ACK and a unicast `NodeInfo` are
         * priced without a signature.
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

        /**
         * How far into the allowance the window's total may be when a frame for the **foreign** audience — the
         * Meshtastic room's post, the DM auto-reply — is admitted, its own cost included ([Ceiling.HALF], ADR 2026-10.ug4a).
         *
         * Half, on purpose larger than the 15 % share it replaces: with one ledger the room has no spend of its own to
         * be judged by, so its ceiling is on the total, and up to half the window is the room's while Knit is idle. A
         * busy Knit window leaves it less, which is the ordering that matters: the pocket's own chat comes first. In
         * practice the 30 s floors bind long before this does — nobody posts twice a minute for a quarter of an hour,
         * and a burst that did would reach it after about a dozen posts at LongFast (~1.9 s each against 22.5 s).
         */
        const val PUBLIC_CEILING = 0.5

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
