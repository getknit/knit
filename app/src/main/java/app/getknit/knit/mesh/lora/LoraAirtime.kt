package app.getknit.knit.mesh.lora

import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil
import kotlin.math.min

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

    /**
     * [LoraAirtime.FLOOD_CEILING] (0.15) of it, judged against what the window's **flood** bookings spent (ADR 2026-11.gsqu
     * §4 and its Stage 5 decisions): a MeshCore board's own room posts, which every repeater re-airs once. The total still
     * caps everything, so a flood also needs the window's room; but a DM and its ✓✓, booked for every airing at send, never
     * eat the floods' share. Nothing on Meshtastic is admitted under it.
     */
    FLOOD,
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
 * One packet booked against the rolling window: when it went out, what it cost, and the [Ceiling] it was admitted under,
 * which is what [Ceiling.FLOOD] counts ([LoraAirtime.refusal]). The cost is carried rather than re-derived from a size,
 * because [LoraAirtime.refund] matches a booking on when and how much. In memory only since ADR 2026-10.ug4a: the
 * ledger is no longer persisted.
 */
private data class Booking(
    val atMs: Long,
    val ms: Long,
    val ceiling: Ceiling = Ceiling.FULL,
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
    /** How much of [allowanceMs] the window's flood bookings may spend ([Ceiling.FLOOD]: a MeshCore board's room posts). */
    val floodCeilingMs: Long = 0L,
    /** What the window's flood bookings spent, measured against [floodCeilingMs]. */
    val floodUsedMs: Long = 0L,
    /** Whether the board is on a dedicated RF slot, and so out from under the politeness ceiling (ADR 067). */
    val dedicated: Boolean = false,
    /**
     * The signature cliff of a Knit broadcast on the bound slot ([LoraAirtime.UNSIGNED] when the board does not sign
     * there): what the ledger charges a room post for, and what [LoraAirtime.padTo] dodges (ADR 2026-09.mhs5, #243).
     */
    val knitSignedUpTo: Int = MeshtasticProto.MAX_SIGNED_PAYLOAD,
    /**
     * The same for a Meshtastic-room post on slot 0, on the text port: the composer's cap
     * ([PublicPostPolicy.onAirBudget], `LoraFacts.roomSignedUpTo`). 154 on a 2.8.1 board whose slot 0 is AEAD.
     */
    val roomSignedUpTo: Int = MeshtasticProto.maxSignedPayload(MeshtasticProto.PORT_TEXT_MESSAGE),
    /**
     * How many full-size room packets an own post may take and still fit its kind's room ceiling on an empty window
     * ([LoraAirtime.roomPacketsFit]), up to the kind's part limit; null before the board reported its duty. What the
     * MeshCore composer's budget reads (the 5c-1 reviews' N4): 0 on a band where no post ever airs.
     */
    val roomPacketsFit: Int? = null,
    /**
     * The most text the user's public-channel post can ever carry ([LoraAirtime.publicPostBytes]): the kind's cap, less
     * where its ceiling of an empty window cannot carry it. What the MeshCore Public composer's budget reads (`LoraFacts
     * .publicPostBytes`, ADR 2026-11.gsqu's Stage 6 amendment); the Meshtastic room reads [roomSignedUpTo] instead.
     */
    val publicPostBytes: Int = 0,
) {
    /** Whether the budget charges a Knit broadcast for the board's signature — the `…debug.LORA` dump's `signing`. */
    val signing: Boolean get() = knitSignedUpTo != LoraAirtime.UNSIGNED
}

/** Which of the board's channels a packet is written to, for [LoraAirtime.price]: each has its own `use_aead`. */
internal enum class AirSlot {
    /** Index 0: the Meshtastic room's post, the auto-reply, our board's `NodeInfo` after a `35`. */
    PRIMARY,

    /** The bound Knit slot: a Knit broadcast, and the compact DM written to it (which PKI then moves to index 0). */
    KNIT,
}

/**
 * The packet classes `Router::perhapsEncode` treats differently (#243). Firmware 2.8 PKI-encrypts a [DATA_UNICAST] its
 * phone originates unless the board is in ham mode, and never the other three.
 */
internal enum class PacketKind {
    /** A broadcast: a Knit room post, a Meshtastic-room post. */
    BROADCAST,

    /** A data unicast: a compact DM, the auto-reply. */
    DATA_UNICAST,

    /** A `NODEINFO_APP` (or position) unicast: our board's NodeInfo after a destination's `35`. */
    NODEINFO_UNICAST,

    /** A `ROUTING_APP` unicast: our board's ACK of a compact DM to us. */
    ROUTING_UNICAST,
}

/**
 * What the firmware does to one packet past the payload we hand it, for the ledger: the payload size at or under which
 * it attaches a 66-byte signature ([signedUpTo], [LoraAirtime.UNSIGNED] for never), the bytes its encryption adds
 * ([extraBytes]: a PKI unicast's or an AEAD channel's 12), and the largest payload that still fits ([cap]). A kind whose
 * framing turns on what the packet is (MeshCore) carries that too ([purpose], read by its [BoardTraits.phyBytes]); a
 * Meshtastic price leaves it null, its cost being the three numbers above (ADR 2026-11.gsqu's Stage 5a-1 amendment).
 */
internal data class PacketPrice(
    val signedUpTo: Int,
    val extraBytes: Int,
    val cap: Int,
    val purpose: AirPurpose? = null,
) {
    /** The three numbers as they always read, and the purpose only where a kind set one (the parity golden reads this). */
    override fun toString(): String =
        "PacketPrice(signedUpTo=$signedUpTo, extraBytes=$extraBytes, cap=$cap" + purpose?.let { ", purpose=$it" }.orEmpty() + ")"

    companion object {
        /** No signature, no extra bytes: the price of a packet on a board that signs nothing, on a plain channel. */
        val UNSIGNED = PacketPrice(LoraAirtime.UNSIGNED, 0, MeshtasticProto.MAX_PAYLOAD)
    }
}

/**
 * What the handshake told us about how the bound board signs (#243): its firmware version, `DeviceMetadata.has_xeddsa`,
 * `SecurityConfig.packet_signature_policy` (null: no security config was streamed, read as signing) and whether its
 * owner is in ham mode (`User.is_licensed`). The default is the expensive unknown: a board that has said nothing signs.
 */
internal data class SigningFacts(
    val firmware: String? = null,
    val hasXeddsa: Boolean? = null,
    val policy: SignaturePolicy? = null,
    val licensed: Boolean = false,
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
 * **Not on MeshCore**: its firmware has only a 50 % token bucket and knows no region (`Dispatcher::updateTxBudget`, spike
 * Q4), so there the law is Knit's alone — the allowance reads the band's duty ([LoraBand], never above it) and
 * `MeshcoreDutyLaw` checks the board's own trailing-hour `tx_air` as Knit observed it rise, which outlives an app
 * restart (ADR 2026-11.gsqu's Stage 5a-1 amendment). The ledger itself stays unpersisted there too: the law check is what
 * a restart cannot reset.
 *
 * One thing the packet we hand the board is not: what leaves it. Firmware **2.8 signs the broadcasts it
 * originates** — tag 2.8.1 always, a build with upstream `34cb8d582` (2.8.2) only under a `BALANCED` or `STRICT`
 * `packet_signature_policy` (#243) — adding [MeshtasticProto.XEDDSA_SIGNATURE_FIELD] bytes to any packet small
 * enough to still fit one ([MeshtasticProto.MAX_SIGNED_PAYLOAD]). Knit neither asks for that nor can decline
 * it, but it is real air, and unbudgeted it lands hardest on exactly the frames ADR 060 shrank into one
 * packet — a 157-byte transcoded tick is 36 % more air than this class used to charge for. So every booking is
 * priced ([price], [PacketPrice]) for the slot and the packet class it is, off the board's own word at `Ready`
 * ([onBoard]) — and [padTo] turns the same cliff back into a saving, since a packet grown one byte past it sheds all
 * 66 (ADR 2026-09.mhs5).
 *
 * Those two ceilings are **independent**, which is what [dedicatedUnlocksDuty] turns on (ADR 067). The
 * regional duty cycle is law and only the firmware's own `override_duty_cycle` escape hatch lifts it; the
 * politeness ceiling is Knit's own manners toward everyone else sharing the stock frequency, and on a board
 * pinned to a dedicated RF slot ([LoraRadioConfig.dedicatedSlot], [LoraSlot]) there is no-one there to be
 * polite to. So a dedicated US board runs to the 100 % its region allows and an EU_868 one still stops at
 * 10 %, because that one was never politeness.
 *
 * **Priced from neutral inputs** (ADR 2026-11.gsqu, Stage 1a.2): the formula reads a [RadioParams] and the allowance a
 * [DutyPolicy], and a Meshtastic board's [LoraRadioConfig] becomes both in one place ([MeshtasticTraits.radioParams],
 * [MeshtasticTraits.dutyPolicy]). The framing around a payload and the contention slot are the kind's ([traits]).
 * [radio] is still kept whole: the snapshot reports its preset and region.
 */
internal class LoraAirtime(
    private val windowMs: Long = WINDOW_MS,
    private val safety: Double = SAFETY,
    private val politeCeilingPercent: Double = POLITE_CEILING_PERCENT,
    private val publicCeiling: Double = PUBLIC_CEILING,
    private val floodCeiling: Double = FLOOD_CEILING,
    /**
     * Whether a dedicated RF slot lifts the politeness ceiling. On in the transport; false by default so the
     * class can be tested in both states.
     */
    private val dedicatedUnlocksDuty: Boolean = false,
    /**
     * The board kind's price hook and contention slot ([BoardTraits.price], [BoardTraits.phyBytes], [BoardTraits.airMs],
     * [BoardTraits.slotTimeMs]) and its fallbacks before a board reports ([BoardTraits.fallbackRadio],
     * [BoardTraits.fallbackDutyPercent]).
     */
    private val traits: BoardTraits = BoardTraits.MESHTASTIC,
) : PacketCost {
    private val samples = ArrayDeque<Booking>()
    private var spentMs = 0L

    /** What the window's [Ceiling.FLOOD] bookings spent: a part of [spentMs], counted apart for the flood ceiling. */
    private var floodSpentMs = 0L

    /** The board's radio settings, or null until the handshake reports them. */
    var radio: LoraRadioConfig? = null
        private set

    /** The modulation the formula reads, or null until a board reports one ([FALLBACK_RADIO] meanwhile). */
    private var params: RadioParams? = null

    /** The duty cycle the allowance reads, or null until a board reports one ([FALLBACK_PERCENT] meanwhile). */
    private var duty: DutyPolicy? = null

    /**
     * How the bound board signs, as its last handshake said ([onBoard]). Unknown until a board says otherwise, and
     * unknown prices as signing: guessing "unsigned" against firmware that signs is the one error that spends air we
     * never budgeted — and in a duty-limited region that is somebody else's law, not our politeness.
     */
    private var facts = SigningFacts()

    /** `use_aead` on slot 0 and on the bound Knit slot, as the last handshake's channel table said. */
    private var primaryAead = false
    private var knitAead = false

    /** Records the board's radio settings from the config handshake; a null report leaves the last one standing. */
    @Synchronized
    fun onRadioConfig(config: LoraRadioConfig?) {
        if (config == null) return
        radio = config
        onRadio(MeshtasticTraits.radioParams(config.modemPreset), MeshtasticTraits.dutyPolicy(config))
    }

    /**
     * Records the modulation and the duty a board transmits under, kind-neutral: what [onRadioConfig] makes of a
     * Meshtastic config, and what another kind reports directly. Leaves [radio] as it was.
     */
    @Synchronized
    fun onRadio(
        params: RadioParams,
        duty: DutyPolicy,
    ) {
        this.params = params
        this.duty = duty
    }

    /**
     * Forgets the modulation and the duty a board reported: the kind's fallbacks price and allow again, and [dutyKnown]
     * reads false. A MeshCore session that comes up with no sane radio or frequency (ADR 2026-11.gsqu's Stage 5a-2
     * amendment): the previous board's duty must not stand for this one, and a frame the fallback cannot carry is then
     * skipped, never dropped as [Refusal.NEVER]. Leaves [radio] as it was.
     */
    @Synchronized
    fun forgetRadio() {
        params = null
        duty = null
    }

    /**
     * The length of the bound board's name, in bytes, where the kind's firmware writes it before a public post (MeshCore,
     * ADR 2026-11.f4d3): what [publicPostBytes] and the snapshot price the composer's budget from. Null before a board said,
     * priced at the kind's longest name. A queued post is priced from the name its own frame carries
     * (`OutboundFrame.publicName`), never from this.
     */
    private var publicNameBytes: Int? = null

    /** The board reported its name's length [bytes] (MeshCore's Ready, ADR 2026-11.f4d3); null forgets it. */
    @Synchronized
    fun onPublicName(bytes: Int?) {
        publicNameBytes = bytes
    }

    /**
     * What a public post costs past its text from a board whose name is [nameBytes] long ([BoardTraits.publicPrice]: on
     * MeshCore the `"<name>: "` prefix, and the cap less it; Meshtastic's is [price] of [AirPurpose.PUBLIC_POST] as ever).
     * Null prices the kind's longest name: never under the post's real cost.
     */
    @Synchronized
    fun publicPrice(nameBytes: Int?): PacketPrice = traits.publicPrice(price(AirPurpose.PUBLIC_POST), nameBytes)

    /**
     * Records how the bound board signs, off its handshake at `Ready` (#243): [facts] — firmware, `has_xeddsa`, the
     * `packet_signature_policy`, ham mode — and whether slot 0 ([primaryAead]) and the bound Knit slot ([knitAead]) are
     * AEAD channels. Every later [price] reads this one snapshot, so admission and booking cannot disagree. Asked again
     * only on a new handshake: a policy change reboots the board, and a channel edited by another client on a live
     * session is not pushed to the phone (`MeshtasticSession.dispatch`).
     */
    @Synchronized
    fun onBoard(
        facts: SigningFacts,
        primaryAead: Boolean = false,
        knitAead: Boolean = false,
    ) {
        this.facts = facts
        this.primaryAead = primaryAead
        this.knitAead = knitAead
    }

    /**
     * What a [kind] packet on [portnum] written to [slot] costs past its payload, by the snapshot [onBoard] took. A
     * Meshtastic board's terms: the Meshtastic hooks ask it for what only they send (our board's ACK on the slot the DM came
     * in on, the NodeInfo after a `35`); everything the core books goes through [price] by [AirPurpose], the kind's hook.
     */
    @Synchronized
    fun price(
        slot: AirSlot,
        kind: PacketKind,
        portnum: Int,
    ): PacketPrice = price(facts, aead = if (slot == AirSlot.PRIMARY) primaryAead else knitAead, kind, portnum)

    /**
     * What a packet for [purpose] costs past its payload on this board: the kind's price hook ([BoardTraits.price]) over the
     * snapshot [onBoard] took — which only a kind that signs reads (ADR 2026-11.gsqu's Stage 5a-1 amendment).
     */
    @Synchronized
    fun price(purpose: AirPurpose): PacketPrice = traits.price(purpose, facts, primaryAead, knitAead)

    /**
     * Milliseconds this packet will occupy the medium, from the LoRa time-on-air formula (Semtech AN1200.13)
     * at the board's preset. [payloadBytes] is the `Data.payload` we hand the board; [PACKET_OVERHEAD_BYTES]
     * covers the Meshtastic header and protobuf/crypto framing around it. This is a governor's estimate, not
     * a measurement — it does not know the board's preamble length or whether a rebroadcaster repeated us.
     *
     * Priced as a Knit broadcast on the bound slot ([knitBroadcast]): what [LoraFrameCodec] asks, through [PacketCost].
     */
    @Synchronized
    override fun timeOnAirMs(payloadBytes: Int): Long = timeOnAirMs(payloadBytes, knitBroadcast())

    /**
     * [timeOnAirMs] for a packet with no extra bytes whose signature cliff is [signedUpTo]: a payload at or under it is
     * charged the signature, whatever the board — the caller asked [price] for the cliff.
     */
    @Synchronized
    fun timeOnAirMs(
        payloadBytes: Int,
        signedUpTo: Int,
    ): Long = airMs(payloadBytes, PacketPrice(signedUpTo, 0, MeshtasticProto.MAX_PAYLOAD))

    /**
     * [timeOnAirMs] at [price]: the kind's own bytes on the air for it ([BoardTraits.phyBytes] — on Meshtastic the signature
     * when [payloadBytes] is at or under its cliff, and its extra bytes) at the kind's airtime term ([BoardTraits.airMs]).
     */
    @Synchronized
    fun timeOnAirMs(
        payloadBytes: Int,
        price: PacketPrice,
    ): Long = airMs(payloadBytes, price)

    private fun airMs(
        payloadBytes: Int,
        price: PacketPrice,
    ): Long = traits.airMs(traits.phyBytes(payloadBytes, price), params ?: traits.fallbackRadio)

    /**
     * The firmware's contention slot at this board's modulation — Meshtastic's `RadioInterface::computeSlotTimeMsec`
     * ([MeshtasticTraits.slotTimeMs]), ≈ 44.5 ms at LongFast. What a reliable unicast's retransmit gap is counted in
     * (`LoraDirectPolicy.retransmitMs`, ADR 2026-10.x9fa).
     */
    @Synchronized
    fun slotTimeMs(): Double = traits.slotTimeMs(params ?: traits.fallbackRadio)

    /**
     * The price of an own room post by the current snapshot — a Knit broadcast on the bound slot on Meshtastic, a scoped
     * flood on MeshCore: what a caller that names no price is charged.
     */
    private fun knitBroadcast(): PacketPrice = price(AirPurpose.ROOM_FLOOD)

    /**
     * The size to grow a [payloadBytes] packet to so the firmware will **not** sign it, or [payloadBytes]
     * itself when that trade does not pay (ADR 2026-09.mhs5). [cap] is the largest `Data.payload` this board takes.
     *
     * The gate the firmware applies is a cliff, so the packets just under it pay a 66-byte signature that a
     * packet one byte larger does not — a 165-byte payload and a 231-byte one cost the same air. Padding past
     * the cliff buys that back for the width of the pad, and the comparison is made with [timeOnAirMs] rather
     * than in bytes so it is exact at the board's own preset and symbol quantization. Everything else falls
     * out of that one comparison: a board that does not sign on the bound slot — pre-2.8, or 2.8.2 under `COMPATIBLE`
     * (#243) — never sees a saving, and neither does a packet already over the cliff.
     *
     * The target is the firmware's own `signedDataFits` edge, one past [MeshtasticProto.MAX_SIGNED_PAYLOAD], not the
     * priced cliff: the two differ only on a 2.8.1 AEAD channel, where the board signs up to that edge and then refuses
     * the packets the AEAD tag pushes over (`Router.cpp:1377-1379`), and no Knit broadcast is written to an AEAD bound
     * slot (`MeshtasticHooks.aeadBlocks`).
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
        val cfg = duty ?: return (windowMs * traits.fallbackDutyPercent / PERCENT * safety).toLong()
        // Law. The user set the firmware's own duty-cycle override: they have taken the regulatory call, so
        // the regional cap stops applying to us too ([MeshtasticTraits.dutyPolicy] reads it as 100 %).
        val regional = cfg.dutyCyclePercent
        // Manners. Nobody to be polite to on a slot of our own, so the ceiling lifts with the shared band.
        val polite = if (dedicatedUnlocksDuty && cfg.dedicatedSlot) FULL_PERCENT else politeCeilingPercent
        return (windowMs * min(regional, polite) / PERCENT * safety).toLong()
    }

    /**
     * Whether a board has reported the duty the allowance reads ([onRadio]). Until it has, the allowance is the kind's
     * fallback ([BoardTraits.fallbackDutyPercent]: 0.1 % on MeshCore), and a frame it refuses as [Refusal.NEVER] may fit
     * once the real duty lands — so the pacer drops nothing for NEVER before this is true.
     */
    @Synchronized
    fun dutyKnown(): Boolean = duty != null

    /** Whether the budget above is running under the dedicated-slot rules; diagnostics, the settings row and the brake. */
    @Synchronized
    fun dedicated(): Boolean = dedicatedUnlocksDuty && duty?.dedicatedSlot == true

    /** How far into [allowanceMs] a [Ceiling.HALF] frame may take the window's total. */
    @Synchronized
    fun publicCeilingMs(): Long = (allowanceMs() * publicCeiling).toLong()

    /** How much of [allowanceMs] the window's [Ceiling.FLOOD] bookings may spend. */
    @Synchronized
    fun floodCeilingMs(): Long = (allowanceMs() * floodCeiling).toLong()

    /** How far [ceiling] lets a frame go: the most it may cost on an empty window, against what its own ceiling counts. */
    private fun ceilingMs(ceiling: Ceiling): Long =
        when (ceiling) {
            Ceiling.FULL -> allowanceMs()
            Ceiling.HALF -> publicCeilingMs()
            Ceiling.FLOOD -> floodCeilingMs()
        }

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
        /** What the firmware does to these packets ([price]) — a Knit broadcast on the bound slot unless the caller says. */
        price: PacketPrice? = null,
    ): Boolean = refusal(ceiling, payloadSizes, now, price) == null

    /**
     * Why [admits] refuses a frame, or null when it fits. The pacer needs the reason, not just the verdict: a direct
     * frame the [Refusal.TOTAL] refuses holds every frame ranked below it until it has gone ([LoraPacePolicy]), while a
     * frame refused on its [Refusal.CEILING] must never hold anything, or a busy Meshtastic room would freeze the
     * pocket's own chat — the one thing the ceiling exists to prevent. A frame its own ceiling would refuse on an empty
     * window is [Refusal.NEVER], whichever ceiling: on a 1 % band a two-part MeshCore post costs more than the flood
     * ceiling of the whole allowance, and waiting on it would wake the pacer for a whole window per post (ADR 2026-11.gsqu's
     * Stage 5a-1 amendment). On [Ceiling.FULL] that is the whole allowance, as it always was.
     */
    @Synchronized
    fun refusal(
        ceiling: Ceiling,
        payloadSizes: List<Int>,
        now: Long,
        price: PacketPrice? = null,
    ): Refusal? {
        prune(now)
        val priced = price ?: knitBroadcast()
        val cost = payloadSizes.sumOf { timeOnAirMs(it, priced) }
        val total = allowanceMs()
        // A `when` rather than a ladder of early returns only because there are several answers; the order
        // is the same and load-bearing.
        return when {
            // No window, however empty, would ever admit it under its own ceiling: it must never be waited for.
            cost > ceilingMs(ceiling) -> Refusal.NEVER

            spentMs + cost > total -> Refusal.TOTAL

            // The foreign audience's ceiling is on the window's total, its own cost included: it cannot take the
            // window past half, however little of it the room itself has spent.
            ceiling == Ceiling.HALF && spentMs + cost > publicCeilingMs() -> Refusal.CEILING

            // The flood ceiling counts floods alone: DMs booked for every airing never take the room's share, and a
            // flood still needs the total's room above.
            ceiling == Ceiling.FLOOD && floodSpentMs + cost > floodCeilingMs() -> Refusal.CEILING

            else -> null
        }
    }

    /** Why [refusal] turned a frame away. */
    enum class Refusal {
        /** The window's total is spent: the frame fits once enough of the window rolls off. */
        TOTAL,

        /**
         * A [Ceiling.HALF] frame would take the total past [publicCeilingMs], or a [Ceiling.FLOOD] frame the window's
         * floods past [floodCeilingMs]. Never holds anything behind it.
         */
        CEILING,

        /**
         * Larger than its own ceiling of an empty window — the whole allowance for [Ceiling.FULL]: no window will ever
         * admit it, so nothing may wait on it. The pacer drops it ([LoraPacePolicy.drainNever]).
         */
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
                if (booking.ceiling == Ceiling.FLOOD) floodSpentMs -= ms
                left--
            }
        }
        return count - left
    }

    /**
     * Books [payloadBytes] of air at [price] — a Knit broadcast on the bound slot unless the caller says — under the
     * [ceiling] its frame was admitted at, so a [Ceiling.FLOOD] booking counts toward the floods' share. Called once the
     * board has actually accepted the write.
     */
    @Synchronized
    fun record(
        payloadBytes: Int,
        now: Long,
        price: PacketPrice? = null,
        ceiling: Ceiling = Ceiling.FULL,
    ) {
        prune(now)
        val ms = timeOnAirMs(payloadBytes, price ?: knitBroadcast())
        samples.addLast(Booking(now, ms, ceiling))
        spentMs += ms
        if (ceiling == Ceiling.FLOOD) floodSpentMs += ms
    }

    /**
     * Milliseconds [phyBytes] on the air occupy the medium at the board's modulation ([BoardTraits.airMs]): for a kind that
     * prices a difference of two airings rather than a packet ([recordMs]).
     */
    @Synchronized
    fun airMsOfPhy(phyBytes: Int): Long = traits.airMs(phyBytes, params ?: traits.fallbackRadio)

    /**
     * The least time a room packet carrying [payloadBytes] can take on the air at the board's modulation
     * ([BoardTraits.leastRoomAirMs]): a floor, for a bound sized by how fast a receiver decodes, never a booking.
     */
    @Synchronized
    fun leastRoomAirMs(payloadBytes: Int): Long = traits.leastRoomAirMs(payloadBytes, params ?: traits.fallbackRadio)

    /**
     * Books [ms] of air at [now] under [ceiling] — air that is no packet of its own: on MeshCore the surcharge of a DM our
     * board flooded instead of sending direct (ADR 2026-11.gsqu's Stage 5a-2 amendment), whose airing was booked at send.
     * Not meant for [refund], which matches a compact DM's own airings on when and how much: a surcharge is a few
     * milliseconds, never an airing's whole cost. Nothing at or under zero.
     */
    @Synchronized
    fun recordMs(
        ms: Long,
        now: Long,
        ceiling: Ceiling = Ceiling.FULL,
    ) {
        if (ms <= 0) return
        prune(now)
        samples.addLast(Booking(now, ms, ceiling))
        spentMs += ms
        if (ceiling == Ceiling.FLOOD) floodSpentMs += ms
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
            // The modulation, not the Meshtastic config: a MeshCore board reports one through [onRadio] alone.
            known = params != null,
            usedMs = spentMs,
            allowanceMs = allowanceMs(),
            publicCeilingMs = publicCeilingMs(),
            floodCeilingMs = floodCeilingMs(),
            floodUsedMs = floodSpentMs,
            dedicated = dedicated(),
            knitSignedUpTo = knitBroadcast().signedUpTo,
            roomSignedUpTo = price(AirPurpose.PUBLIC_POST).signedUpTo,
            roomPacketsFit = roomPacketsFit(),
            publicPostBytes = publicPostBytes(),
        )
    }

    /**
     * The most text the user's public-channel post can ever carry here: the kind's cap for it ([PacketPrice.cap] of
     * [AirPurpose.PUBLIC_POST]), less where its own ceiling ([BoardTraits.publicCeiling]) of an empty window could never
     * admit that much — the [Refusal.NEVER] test [refusal] makes, so the composer's budget and the enqueue's refusal are one
     * rule (ADR 2026-11.gsqu's Stage 6 amendment, lora-reviewer N3) — priced from the bound board's name as it last reported
     * it ([onPublicName], ADR 2026-11.f4d3). 0 where not one byte ever fits; the cap until the
     * board's duty is known (a post on the fallback is refused for air at the enqueue, never for its size).
     */
    @Synchronized
    fun publicPostBytes(): Int = publicPostBytes(publicNameBytes)

    /** [publicPostBytes] for a board whose name is [nameBytes] long (null: the kind's longest, ADR 2026-11.f4d3). */
    @Synchronized
    fun publicPostBytes(nameBytes: Int?): Int {
        val price = publicPrice(nameBytes)
        if (duty == null) return price.cap
        val ceiling = ceilingMs(traits.publicCeiling)
        // The cost grows with the size, so the largest that fits is found by halving: a handful of formula reads a call.
        var fits = 0
        var tooBig = price.cap + 1
        while (tooBig - fits > 1) {
            val mid = (fits + tooBig) / 2
            if (timeOnAirMs(mid, price) <= ceiling) fits = mid else tooBig = mid
        }
        return fits
    }

    /**
     * The most full-size room packets (the kind's [BoardTraits.maxPayload]) an own post may take, up to
     * [BoardTraits.roomPartLimit], whose cost is under the room ceiling ([BoardTraits.roomCeiling]) on an empty window — the
     * [Refusal.NEVER] test [refusal] makes, read at the largest post. Full-size packets over-price a post's last part, so it
     * errs toward the smaller budget. Null until the board's duty is known: the fallback's would read 0 everywhere.
     */
    private fun roomPacketsFit(): Int? {
        if (duty == null) return null
        val one = timeOnAirMs(traits.maxPayload, knitBroadcast())
        val ceiling = ceilingMs(traits.roomCeiling)
        return (traits.roomPartLimit downTo 1).firstOrNull { n -> n * one <= ceiling } ?: 0
    }

    /** Drops samples that have aged out of the rolling window. Cheap: the deque is in send order. */
    private fun prune(now: Long) {
        while (true) {
            val oldest = samples.firstOrNull() ?: return
            if (now - oldest.atMs < windowMs) return
            samples.removeFirst()
            spentMs -= oldest.ms
            if (oldest.ceiling == Ceiling.FLOOD) floodSpentMs -= oldest.ms
        }
    }

    companion object {
        /**
         * The signature cliff of a packet the firmware never signs ([PacketPrice.signedUpTo]): a PKI unicast — a compact
         * DM, the auto-reply — whatever the board, and on a board that signs nothing every packet (#243, [price]).
         */
        const val UNSIGNED = 0

        /**
         * What `Router::perhapsEncode` does to a [kind] packet on [portnum], on a channel that is or is not [aead], for
         * the board [facts] describe (#243). Pure: the whole rule, pinned row by row in `LoraAirtimeTest`.
         *
         * - A [PacketKind.DATA_UNICAST] off a board not in ham mode is PKI-encrypted (`wouldEncryptWithPKC`, any channel,
         *   ADR 2026-10.x9fa's PKI amendment): never signed, +[MeshtasticProto.PKC_OVERHEAD_BYTES], never AEAD.
         * - Anything else is channel-encrypted: +[MeshtasticProto.AEAD_OVERHEAD_BYTES] on an [aead] channel, on every
         *   version that reports `use_aead`.
         * - Whether it is signed turns on the firmware ([Era]):
         *   - `has_xeddsa` false, or a version below 2.8 (`signsPackets`'s old rule): never.
         *   - 2.8.0 and 2.8.1 ([Era.ALWAYS], `Router.cpp:1270` at v2.8.1): a broadcast, and in ham mode every unicast,
         *     routing included, under every policy — and on an AEAD channel too: `signedDataFits` ignores the AEAD tag
         *     (`Router.cpp:1177-1185`) and the AEAD path then refuses what overflows (`:1377-1379`), so only a payload
         *     12 B under the usual cliff leaves signed: 154 B of text, 153 of a Knit frame.
         *   - 2.8.2 and later ([Era.POLICY], upstream `34cb8d582`, `Router.cpp:1222-1227`): only under `BALANCED` or
         *     `STRICT` (an unknown or unrecognised policy reads as signing), never on an AEAD channel, never a routing
         *     packet: a broadcast, a NodeInfo or position unicast, and in ham mode a data unicast.
         *   - A version it cannot read ([Era.UNKNOWN]): signed whenever either rule above would sign it — the expensive
         *     reading, since over-charging costs throughput and under-charging can cost compliance.
         *
         * Not modelled: a 2.8.2 development build from before `34cb8d582` (2026-10-01 to 10-21), which signs under
         * `COMPATIBLE` like 2.8.1 yet reads as [Era.POLICY]. Nothing in the handshake tells it apart; it is under-charged
         * by the signature on its small broadcasts (#243's accepted gap).
         */
        fun price(
            facts: SigningFacts,
            aead: Boolean,
            kind: PacketKind,
            portnum: Int,
        ): PacketPrice {
            // The port's whole payload room, and the firmware's `signedDataFits` edge 66 B under it (165 / 166).
            val fullCap = MeshtasticProto.LORA_DATA_MAX - framingByPort.computeIfAbsent(portnum, MeshtasticProto::dataFraming)
            val signedEdge = fullCap - MeshtasticProto.XEDDSA_SIGNATURE_FIELD
            if (kind == PacketKind.DATA_UNICAST && !facts.licensed) {
                return PacketPrice(UNSIGNED, MeshtasticProto.PKC_OVERHEAD_BYTES, fullCap - MeshtasticProto.PKC_OVERHEAD_BYTES)
            }
            val extra = if (aead) MeshtasticProto.AEAD_OVERHEAD_BYTES else 0
            val era = era(facts)
            val always = (era == Era.ALWAYS || era == Era.UNKNOWN) && signsAlways(facts, kind)
            val byPolicy = (era == Era.POLICY || era == Era.UNKNOWN) && signsByPolicy(facts, aead, kind)
            val cliff =
                when {
                    always -> signedEdge - extra
                    byPolicy -> signedEdge
                    else -> UNSIGNED
                }
            return PacketPrice(cliff, extra, fullCap - extra)
        }

        /** 2.8.0 / 2.8.1: a broadcast, or any unicast from a board in ham mode — whatever the policy and the channel. */
        private fun signsAlways(
            facts: SigningFacts,
            kind: PacketKind,
        ): Boolean = kind == PacketKind.BROADCAST || facts.licensed

        /** 2.8.2+: a signing policy, a plain channel, and a broadcast, a NodeInfo or position, or a ham data unicast. */
        private fun signsByPolicy(
            facts: SigningFacts,
            aead: Boolean,
            kind: PacketKind,
        ): Boolean {
            if (facts.policy == SignaturePolicy.COMPATIBLE || aead) return false
            return when (kind) {
                PacketKind.BROADCAST, PacketKind.NODEINFO_UNICAST -> true
                PacketKind.DATA_UNICAST -> facts.licensed
                PacketKind.ROUTING_UNICAST -> false
            }
        }

        /** Which signing rule a board's firmware runs. */
        private enum class Era { NEVER, ALWAYS, POLICY, UNKNOWN }

        private fun era(facts: SigningFacts): Era {
            if (facts.hasXeddsa == false) return Era.NEVER
            val parts = facts.firmware?.trim()?.split('.')
            val major = parts?.getOrNull(0)?.toIntOrNull()
            val minor = parts?.getOrNull(1)?.toIntOrNull()
            val patch = parts?.getOrNull(2)?.toIntOrNull()
            return when {
                major == null || minor == null -> {
                    Era.UNKNOWN
                }

                // Below 2.8: nothing signs — unless the build says it can, which no release did; read as unknown then.
                major < SIGNING_MAJOR || (major == SIGNING_MAJOR && minor < SIGNING_MINOR) -> {
                    if (facts.hasXeddsa == true) Era.UNKNOWN else Era.NEVER
                }

                major > SIGNING_MAJOR || minor > SIGNING_MINOR -> {
                    Era.POLICY
                }

                patch == null -> {
                    Era.UNKNOWN
                }

                patch >= POLICY_PATCH -> {
                    Era.POLICY
                }

                else -> {
                    Era.ALWAYS
                }
            }
        }

        /** [MeshtasticProto.dataFraming] by port, measured once: [price] runs on every pacer wake. */
        private val framingByPort = ConcurrentHashMap<Int, Int>()

        /** The firmware release that started signing broadcasts (`Router::perhapsEncode`, 2.8.0). */
        private const val SIGNING_MAJOR = 2
        private const val SIGNING_MINOR = 8

        /** 2.8.2: the first version whose release carries upstream `34cb8d582`, the policy-aware send path (#243). */
        private const val POLICY_PATCH = 2

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
         * How much of the allowance a MeshCore board's floods may spend, counted over flood bookings alone ([Ceiling.FLOOD],
         * ADR 2026-11.gsqu §4 and the maintainer's Stage 5 decisions). Each repeater re-airs a flooded packet once, so a
         * repeater's Knit load is our flooded airtime: about 0.75 % duty at a 45 s window. `HALF` was the obvious
         * translation and let 22.5 s of floods through, 3.3 times jend's cap.
         */
        const val FLOOD_CEILING = 0.15

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

        /** Meshtastic header + protobuf/crypto framing around our `Data.payload` ([BoardTraits.packetOverheadBytes]). */
        const val PACKET_OVERHEAD_BYTES = 24

        /**
         * The LoRa time-on-air formula (Semtech AN1200.13), explicit header and CRC on, for [phyBytes] on the air at [radio]
         * behind [preambleSymbols] of preamble and sync word: what each kind's [BoardTraits.airMs] builds on. Low-data-rate
         * optimisation is on once a symbol exceeds 16 ms (both firmwares' rule), costing 2 bits a symbol. Never under 1 ms.
         */
        fun formulaMs(
            phyBytes: Int,
            radio: RadioParams,
            preambleSymbols: Double,
        ): Long {
            val sf = radio.spreadFactor
            val symbolMs = radio.symbolMs
            val de = if (symbolMs > LDO_THRESHOLD_MS) 1 else 0
            val numerator = (BITS_PER_BYTE * phyBytes - 4 * sf + PAYLOAD_CONST + CRC_BITS).toDouble()
            val denominator = (4 * (sf - 2 * de)).toDouble()
            val payloadSymbols = PAYLOAD_SYMBOL_BASE + maxOf(0.0, ceil(numerator / denominator) * radio.codingRate)
            return ((preambleSymbols + payloadSymbols) * symbolMs).toLong().coerceAtLeast(1)
        }

        private const val PERCENT = 100.0
        private const val LDO_THRESHOLD_MS = 16.0
        private const val BITS_PER_BYTE = 8
        private const val PAYLOAD_CONST = 28
        private const val CRC_BITS = 16
        private const val PAYLOAD_SYMBOL_BASE = 8.0

        /** 16 preamble symbols (the Meshtastic firmware's setting) plus the 4.25-symbol sync word ([BoardTraits.preambleSymbols]). */
        const val PREAMBLE_SYMBOLS = 20.25
    }
}
