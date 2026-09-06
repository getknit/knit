package app.getknit.knit.mesh.lora

import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.pow

/**
 * Which airtime budget a queued frame spends from. Orthogonal to [FrameClass], which is about *queue
 * shedding* — a backfilled DM keeps its DM class (so a room post never evicts it) while spending from the
 * [BRIDGE] budget (so backfill can never crowd live chat off the air).
 */
internal enum class AirBucket {
    /** Somebody is waiting for this right now: the live fan-out and the targeted tick. */
    LIVE,

    /**
     * Nobody is waiting: the gossip offer, the digest-driven backfill, and the first-hearing re-offer. All
     * three **book** their air here, but only serving is judged against the share — a [FrameClass.GOSSIP]
     * offer is exempt, for the reason [LoraAirtime] gives.
     */
    BRIDGE,

    /**
     * The key bootstrap — a `profile` frame on the live fan-out, ours or a relayed one. Its own budget
     * rather than a share of somebody else's, because it is the one class judged *outside* the total: see
     * [LoraAirtime.admits]. The backfilled profile is not here — it keeps [BRIDGE], since a re-served
     * profile is history like everything else the bridge carries.
     */
    BOOTSTRAP,

    /**
     * A Knit user's own post on the **foreign** mesh's public channel (the LongFast bridge's outbound half).
     *
     * Its own budget because it is the one thing on this plane that spends Knit's airtime on an audience
     * that is not Knit: a busy public room must not be able to crowd the pocket's own chat off the band,
     * and the pocket's own chat must not be able to leave a user unable to answer somebody. Judged against
     * the total as well, like [BRIDGE] — it is a share of the one allowance, not a second one beside it.
     */
    PUBLIC,
    ;

    companion object {
        /**
         * Which budget a frame of [klass] spends from unless its caller says otherwise. Only the bootstrap
         * is implied by its class: a backfilled DM keeps DM class while spending [BRIDGE], so every other
         * pairing stays a decision at the call site.
         */
        fun defaultFor(klass: FrameClass): AirBucket = if (klass == FrameClass.BOOTSTRAP) BOOTSTRAP else LIVE
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

/** A read-only view of the governor for the settings row and the `…debug.LORA` dump. */
internal data class AirtimeSnapshot(
    val preset: ModemPreset,
    val region: LoraRegion,
    val known: Boolean,
    val liveUsedMs: Long,
    val liveBudgetMs: Long,
    val bridgeUsedMs: Long,
    val bridgeBudgetMs: Long,
    val bootstrapUsedMs: Long,
    val bootstrapBudgetMs: Long,
    val publicUsedMs: Long = 0L,
    val publicBudgetMs: Long = 0L,
    /** Whether the board is on a dedicated RF slot, and so out from under the politeness ceiling (ADR 067). */
    val dedicated: Boolean = false,
    /** Whether the budget is charging for the signature 2.8 firmware adds to our small packets. */
    val signing: Boolean = true,
)

/**
 * The LoRa plane's airtime governor: turns a packet size into milliseconds on air, keeps a rolling
 * [WINDOW_MS] ledger of what we have spent, and answers whether one more packet fits its bucket's budget.
 *
 * Before this existed the only regulator was reactive — the board's `DUTY_CYCLE_LIMIT` NAK, which arrives
 * *after* the medium has already been abused, and [LoraPacePolicy]'s fixed 3-second gap, which by itself
 * would allow ~1200 packets an hour (over 70 % duty). That was survivable while the plane only carried
 * frames a human had just typed; it is not once the bridge starts serving backfill nobody asked for.
 *
 * Three budgets come out of one number. [AirBucket.LIVE] may spend the whole allowance; [AirBucket.BRIDGE] is
 * capped at [bridgeShare] of it, so a busy bridge degrades into serving less history rather than into
 * delaying somebody's message. A [FrameClass.TICK] — our own delivery receipt — is refused once a window is
 * [tickTailShare] from spent, so the last of the air always goes to content (ADR 054): a ✓✓ heals on
 * re-delivery, a message somebody is waiting for does not.
 *
 * That [bridgeShare] cap is on **serving**, and a [FrameClass.GOSSIP] frame is exempt from it. The OFFER is
 * not backfill: it is the one packet that decides whether any backfill happens at all — including the far
 * pocket's, whose air this budget does not pay for — so a gateway whose offers never fly silences the other
 * pocket rather than merely serving it less. Serving is the right thing to shed under pressure; the packet
 * that unlocks the reverse direction is not. It is still charged against the **total** and still *recorded*
 * against [AirBucket.BRIDGE], so heavy gossip costs serving its headroom and never the other way round.
 *
 * Both exemptions here are bounded, but not by the same thing, and the difference is the point.
 * [AirBucket.BOOTSTRAP] is bounded by a **share** because nothing else bounds it — a relayed profile arrives
 * as often as the horizon sends one. Gossip is bounded by a **timer**: `LoraGossipPolicy` is Trickle, one
 * transmit slot per interval over a five-minute floor, which is a harder ceiling than a share and is already
 * the mechanism that exists for exactly this. Giving it a slice of [bridgeShare] instead was rejected as
 * unsizeable: a full 48-prefix OFFER is ~2 s at LongFast and ~13 s at LongSlow against the same budget, so a
 * fractional reserve starves at slow presets and a time-sized one blocks serving outright at them — on both
 * gateways at once, which is the same deadlock wearing the other hat.
 *
 * [AirBucket.BOOTSTRAP] is the odd one, and the reason is worth stating. Nothing a peer sends verifies
 * without its author's `profile`, so a window that refuses the profile costs more airtime than it saves:
 * every frame that peer sends afterwards is undecodable and re-served forever. Until ADR 056 that argument
 * bought the bootstrap a blanket exemption — always admitted, still *recorded* — which is a budget with no
 * floor under it. On the lab gateway 79 % of every LoRa frame ever sent was a profile: a relayed one is
 * gated only by the 10-minute signature dedup, and the router's SeenSet lapses on the same 10 minutes, so
 * the same profile re-fanned every 10 minutes forever and blanked the plane for traffic a human had typed.
 * So the exemption is now bounded rather than removed: a bootstrap frame is judged against
 * [bootstrapShare] of the allowance **alone**, never against the total — it still rides when live is spent,
 * but it can never take more than its quarter, and [LoraPacePolicy] holds the refused ones in the queue for
 * the next window rather than dropping them.
 *
 * The allowance itself is `min(the region's duty cycle, a politeness ceiling) x a safety factor` of the
 * window. The region and modem preset are read off the board ([LoraRadioConfig]); until the handshake
 * reports them we assume [FALLBACK_PERCENT], which is below every real region's limit. Pure and
 * clock-driven by the caller, like [LoraPacePolicy] — the transport owns the actual clock.
 *
 * One thing the packet we hand the board is not: what leaves it. Firmware **2.8 signs every broadcast it
 * originates**, ours included, adding [MeshtasticProto.XEDDSA_SIGNATURE_FIELD] bytes to any packet small
 * enough to still fit one ([MeshtasticProto.MAX_SIGNED_PAYLOAD]). Knit neither asks for that nor can decline
 * it, but it is real air, and unbudgeted it lands hardest on exactly the frames ADR 060 shrank into one
 * packet — a 157-byte transcoded tick is 36 % more air than this class used to charge for. So
 * [timeOnAirMs] adds the signature itself, gated on the board's firmware ([signsPackets]) — and [padTo]
 * turns the same cliff back into a saving, since a packet grown one byte past it sheds all 66 (ADR 2026-09.mhs5).
 *
 * Those two ceilings are **independent**, which is what [dedicatedUnlocksDuty] turns on (ADR 067, debug
 * builds only). The regional duty cycle is law and only the firmware's own `override_duty_cycle` escape
 * hatch lifts it; the politeness ceiling is Knit's own manners toward everyone else sharing the stock
 * frequency, and on a board pinned to a dedicated RF slot ([LoraRadioConfig.dedicatedSlot], [LoraSlot])
 * there is no-one there to be polite to. So a dedicated US board runs to the 100 % its region allows and an
 * EU_868 one still stops at 10 %, because that one was never politeness.
 */
internal class LoraAirtime(
    private val windowMs: Long = WINDOW_MS,
    private val safety: Double = SAFETY,
    private val bridgeShare: Double = BRIDGE_SHARE,
    private val politeCeilingPercent: Double = POLITE_CEILING_PERCENT,
    private val tickTailShare: Double = TICK_TAIL_SHARE,
    private val bootstrapShare: Double = BOOTSTRAP_SHARE,
    private val publicShare: Double = PUBLIC_SHARE,
    /**
     * Whether a dedicated RF slot lifts the politeness ceiling. False everywhere but a debug build — the
     * setup that pins the slot is itself debug-only, and a release build must budget exactly as it does
     * today even against a board somebody pinned by hand in the Meshtastic app.
     */
    private val dedicatedUnlocksDuty: Boolean = false,
) : PacketCost {
    private class Sample(
        val atMs: Long,
        val ms: Long,
        val bucket: AirBucket,
    )

    private val samples = ArrayDeque<Sample>()
    private var liveUsedMs = 0L
    private var bridgeUsedMs = 0L
    private var bootstrapUsedMs = 0L
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
    fun onRadioConfig(config: LoraRadioConfig?) {
        if (config != null) radio = config
    }

    /**
     * Records what the handshake's `DeviceMetadata` says about signing: [hasXeddsa] is the board's own word
     * (`has_xeddsa`, a build that verifies and so signs) and wins when present; a firmware too old to carry
     * the field is judged by its [version] instead, and null reads as unknown.
     */
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
    override fun timeOnAirMs(payloadBytes: Int): Long = timeOnAirMs(payloadBytes, MeshtasticProto.MAX_SIGNED_PAYLOAD)

    /**
     * [timeOnAirMs] for a packet whose signature cliff is [signedUpTo] rather than a Knit frame's: the
     * Meshtastic room's `TEXT_MESSAGE_APP` posts sign one byte further (`MeshtasticProto.maxSignedPayload`),
     * and a post at that cap is signed air the ledger has to book, not an unsigned packet one byte past it.
     */
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

    fun budgetMs(bucket: AirBucket): Long =
        when (bucket) {
            AirBucket.LIVE -> allowanceMs()
            AirBucket.BRIDGE -> (allowanceMs() * bridgeShare).toLong()
            AirBucket.BOOTSTRAP -> (allowanceMs() * bootstrapShare).toLong()
            AirBucket.PUBLIC -> (allowanceMs() * publicShare).toLong()
        }

    fun usedMs(
        bucket: AirBucket,
        now: Long,
    ): Long {
        prune(now)
        return when (bucket) {
            AirBucket.LIVE -> liveUsedMs
            AirBucket.BRIDGE -> bridgeUsedMs
            AirBucket.BOOTSTRAP -> bootstrapUsedMs
            AirBucket.PUBLIC -> publicUsedMs
        }
    }

    /**
     * Whether a whole frame — [payloadSizes] is one entry per packet it fragments into — fits [bucket]'s
     * budget. Admission is all-or-nothing per frame: half a fragmented message on the air is pure waste, so
     * a frame that does not fit entirely waits rather than starting. A [FrameClass.TICK] stops at the tail,
     * an [AirBucket.BOOTSTRAP] frame is judged against its own share alone and a [FrameClass.GOSSIP] one is
     * not judged against [AirBucket.BRIDGE] at all (see the class doc for both). Note [AirBucket.BRIDGE] and
     * [AirBucket.BOOTSTRAP] spending counts against the **total** as well as its own budget: each is a share
     * of the one allowance, not a second allowance beside it.
     */
    fun admits(
        bucket: AirBucket,
        klass: FrameClass,
        payloadSizes: List<Int>,
        now: Long,
        /** The signature cliff for these packets' port — a Knit frame's unless the caller says otherwise. */
        signedUpTo: Int = MeshtasticProto.MAX_SIGNED_PAYLOAD,
    ): Boolean {
        prune(now)
        val cost = payloadSizes.sumOf { timeOnAirMs(it, signedUpTo) }
        val used = liveUsedMs + bridgeUsedMs + bootstrapUsedMs + publicUsedMs
        val tickCeiling = (budgetMs(AirBucket.LIVE) * (1 - tickTailShare)).toLong()
        // A `when` rather than a ladder of early returns only because there are now five answers; the order
        // is the same and load-bearing. Note the TICK arm refuses but does not admit — a tick under the tail
        // still has to pass the bridge test below it, exactly as it did when this was a guard clause.
        return when {
            // The bootstrap alone is judged outside the total: a window that has spent itself on chat must
            // still be able to hand a far pocket the key that makes that chat readable. Its own share is what
            // stops that exemption from becoming the whole allowance (ADR 056).
            bucket == AirBucket.BOOTSTRAP -> {
                bootstrapUsedMs + cost <= budgetMs(AirBucket.BOOTSTRAP)
            }

            used + cost > budgetMs(AirBucket.LIVE) -> {
                false
            }

            // The public channel is judged against its own share as well as the total, in both directions: it
            // cannot crowd out the pocket's own traffic, and the pocket's own traffic leaves it a floor.
            bucket == AirBucket.PUBLIC -> {
                publicUsedMs + cost <= budgetMs(AirBucket.PUBLIC)
            }

            klass == FrameClass.TICK && used + cost > tickCeiling -> {
                false
            }

            // The OFFER is not backfill: it is the one packet that decides whether any backfill happens at
            // all, including the far pocket's, whose air this budget does not pay for. So serving must not be
            // able to starve it — see the class doc.
            else -> {
                bucket != AirBucket.BRIDGE ||
                    klass == FrameClass.GOSSIP ||
                    bridgeUsedMs + cost <= budgetMs(AirBucket.BRIDGE)
            }
        }
    }

    /** Books [payloadBytes] of air against [bucket]. Called once the board has actually accepted the write. */
    fun record(
        bucket: AirBucket,
        payloadBytes: Int,
        now: Long,
        signedUpTo: Int = MeshtasticProto.MAX_SIGNED_PAYLOAD,
    ) {
        prune(now)
        val ms = timeOnAirMs(payloadBytes, signedUpTo)
        samples.addLast(Sample(now, ms, bucket))
        when (bucket) {
            AirBucket.LIVE -> liveUsedMs += ms
            AirBucket.BRIDGE -> bridgeUsedMs += ms
            AirBucket.BOOTSTRAP -> bootstrapUsedMs += ms
            AirBucket.PUBLIC -> publicUsedMs += ms
        }
    }

    /**
     * When the rolling window next hands air back — the oldest booked sample's expiry — or null when the
     * ledger is empty. A caller the budget just refused has nothing to gain by asking again before this, so
     * it is what the pacer sleeps until rather than re-asking a question whose answer cannot have changed.
     */
    fun nextReleaseAt(now: Long): Long? {
        prune(now)
        return samples.firstOrNull()?.let { it.atMs + windowMs }
    }

    fun snapshot(now: Long): AirtimeSnapshot {
        prune(now)
        val cfg = radio
        return AirtimeSnapshot(
            preset = cfg?.modemPreset ?: ModemPreset.LONG_FAST,
            region = cfg?.region ?: LoraRegion.UNSET,
            known = cfg != null,
            liveUsedMs = liveUsedMs,
            liveBudgetMs = budgetMs(AirBucket.LIVE),
            bridgeUsedMs = bridgeUsedMs,
            bridgeBudgetMs = budgetMs(AirBucket.BRIDGE),
            bootstrapUsedMs = bootstrapUsedMs,
            bootstrapBudgetMs = budgetMs(AirBucket.BOOTSTRAP),
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
            when (oldest.bucket) {
                AirBucket.LIVE -> liveUsedMs -= oldest.ms
                AirBucket.BRIDGE -> bridgeUsedMs -= oldest.ms
                AirBucket.BOOTSTRAP -> bootstrapUsedMs -= oldest.ms
                AirBucket.PUBLIC -> publicUsedMs -= oldest.ms
            }
        }
    }

    companion object {
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

        /** The share of the allowance reserved for gossip + backfill; live traffic may use all of it. */
        const val BRIDGE_SHARE = 0.30

        /** The last share of a window a [FrameClass.TICK] may not spend — it is kept for content. */
        const val TICK_TAIL_SHARE = 0.25

        /**
         * The share of the allowance the key bootstrap may spend, and the only budget it is judged against.
         * A quarter is two `profile` frames per window (a profile is ~4.75 s of the 45 s a LongFast window
         * allows) and eight an hour — far more than a bootstrap needs, since our own beacon already has a
         * 5-minute floor and a relayed one a 10-minute dedup, while leaving three quarters of every window
         * to traffic somebody is actually waiting for.
         */
        const val BOOTSTRAP_SHARE = 0.25

        /**
         * The share of the allowance a Knit user's posts to the **foreign** public channel may spend.
         *
         * Small on purpose, and small enough that the per-gateway floor is usually the binding limit rather
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
    }
}
