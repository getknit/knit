package app.getknit.knit.mesh.lora

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.ceil

/**
 * Meshtastic's prices did not move when the ledger started pricing through the kind's hook (ADR 2026-11.gsqu's Stage 5a-1
 * amendment: [BoardTraits.price], [BoardTraits.phyBytes], [BoardTraits.airMs], `OutboundFrame.purpose`). The code the hook
 * replaced is kept here verbatim — `OutboundFrame.price`'s slot / packet-class / port mapping and `LoraAirtime.airMs`'s
 * formula with its 24 B of framing and 20.25-symbol preamble — and every purpose a frame can have, every payload size the
 * board takes, every [ModemPreset] (and no radio at all), every signing era and every AEAD pairing is priced both ways.
 * `LoraAirtimeParityTest`'s golden still pins the ledger's own numbers; this pins the route through the hook.
 */
class LoraAirtimeKindParityTest {
    @Test
    fun everyFramesPriceIsTheOneTheReplacedMappingGave() {
        for (facts in FACTS) {
            for (primaryAead in listOf(false, true)) {
                for (knitAead in listOf(false, true)) {
                    val air = LoraAirtime().apply { onBoard(facts, primaryAead = primaryAead, knitAead = knitAead) }
                    for (frame in frames()) {
                        assertEquals("$facts p=$primaryAead k=$knitAead ${frame.label}", oldPrice(frame, air), frame.price(air))
                    }
                    assertEquals(
                        "the ack the Meshtastic hooks price on the Knit slot",
                        air.price(AirSlot.KNIT, PacketKind.ROUTING_UNICAST, MeshtasticProto.PORT_ROUTING),
                        air.price(AirPurpose.ACK),
                    )
                    assertNull("a Meshtastic price names no purpose", frames().map { it.price(air).purpose }.firstOrNull { it != null })
                }
            }
        }
    }

    @Test
    fun everyAirtimeAtEveryPresetIsTheReplacedFormulas() {
        val prices = HashSet<PacketPrice>()
        for (facts in FACTS) {
            for (aead in listOf(false, true)) {
                val air = LoraAirtime().apply { onBoard(facts, primaryAead = aead, knitAead = aead) }
                frames().forEach { prices += it.price(air) }
            }
        }
        for (preset in listOf<ModemPreset?>(null) + ModemPreset.entries) {
            val air = LoraAirtime()
            if (preset != null) air.onRadioConfig(config(preset))
            for (price in prices) {
                for (size in 1..MeshtasticProto.MAX_PAYLOAD) {
                    assertEquals("$preset $price $size", oldAirMs(size, price, preset), air.timeOnAirMs(size, price))
                }
            }
            for (size in 1..MeshtasticProto.MAX_PAYLOAD) {
                assertEquals("$preset codec $size", oldAirMs(size, oldKnitBroadcast(air), preset), air.timeOnAirMs(size))
                assertEquals(
                    "$preset unsigned $size",
                    oldAirMs(size, PacketPrice(LoraAirtime.UNSIGNED, 0, MeshtasticProto.MAX_PAYLOAD), preset),
                    air.timeOnAirMs(size, LoraAirtime.UNSIGNED),
                )
            }
        }
    }

    @Test
    fun admissionAndBookingAtTheFullCeilingAreTheReplacedRules() {
        // The window filled by steps, a frame of each purpose and size asked at each step: the refusal the old ladder gave
        // (whole allowance → NEVER, total → TOTAL) and the booking the old formula priced.
        for (frame in frames().filter { it.ceiling == Ceiling.FULL }) {
            val air = LoraAirtime().apply { onRadioConfig(config(ModemPreset.LONG_FAST)) }
            var now = 0L
            repeat(STEPS) { _ ->
                val sizes = frame.admissionSizes()
                val price = frame.price(air)
                val cost = sizes.sumOf { oldAirMs(it, price, ModemPreset.LONG_FAST) }
                val spent = air.usedMs(now)
                val expected =
                    when {
                        cost > air.allowanceMs() -> LoraAirtime.Refusal.NEVER
                        spent + cost > air.allowanceMs() -> LoraAirtime.Refusal.TOTAL
                        else -> null
                    }
                assertEquals("${frame.label} at $spent ms", expected, air.refusal(frame.ceiling, sizes, now, price))
                air.record(sizes.first(), now, price, frame.ceiling)
                assertEquals(spent + oldAirMs(sizes.first(), price, ModemPreset.LONG_FAST), air.usedMs(now))
                now += 1_000
            }
        }
    }

    /**
     * The one refusal order that moved on Meshtastic (ADR 2026-11.gsqu's Stage 5a-1 amendment): `NEVER` is now judged
     * against a frame's own ceiling. A `HALF` frame (the Meshtastic room's post, the auto-reply) costing more than half the
     * allowance but no more than the whole read `CEILING` (or `TOTAL` once the window was full: skipped either way, never
     * aired, holding nothing) and now reads `NEVER` (dropped once the
     * duty is known, `loraAirNever`). Accepted by lora-reviewer: such a frame never aired, and the hooks ask `admits(HALF)`
     * before they queue one. Every other answer is the old ladder's. A short window puts the packets either side of half.
     */
    @Test
    fun atTheHalfCeilingOnlyAFrameOverHalfTheAllowanceMovedFromCeilingToNever() {
        var moved = 0
        for (frame in frames().filter { it.ceiling == Ceiling.HALF }) {
            val air = LoraAirtime(windowMs = SHORT_WINDOW_MS).apply { onRadioConfig(config(ModemPreset.LONG_FAST)) }
            var now = 0L
            repeat(STEPS) { _ ->
                val sizes = frame.admissionSizes()
                val price = frame.price(air)
                val cost = sizes.sumOf { oldAirMs(it, price, ModemPreset.LONG_FAST) }
                val spent = air.usedMs(now)
                val total = air.allowanceMs()
                val half = air.publicCeilingMs()
                val old =
                    when {
                        cost > total -> LoraAirtime.Refusal.NEVER
                        spent + cost > total -> LoraAirtime.Refusal.TOTAL
                        spent + cost > half -> LoraAirtime.Refusal.CEILING
                        else -> null
                    }
                val new =
                    when {
                        cost > half -> LoraAirtime.Refusal.NEVER
                        spent + cost > total -> LoraAirtime.Refusal.TOTAL
                        spent + cost > half -> LoraAirtime.Refusal.CEILING
                        else -> null
                    }
                if (cost in (half + 1)..total) {
                    // Over half, within the whole: it was CEILING, or TOTAL once the window was full — never admitted, and
                    // a HALF frame holds nothing either way.
                    assertTrue(
                        "over half, within the whole: it was $old",
                        old == LoraAirtime.Refusal.CEILING || old == LoraAirtime.Refusal.TOTAL,
                    )
                    moved++
                } else {
                    assertEquals("${frame.label} at $spent ms: unchanged", old, new)
                }
                assertEquals("${frame.label} at $spent ms", new, air.refusal(frame.ceiling, sizes, now, price))
                air.record(sizes.first(), now, price, frame.ceiling)
                now += 1_000
            }
        }
        assertTrue("the moved case was exercised", moved > 0)
    }

    /** Every shape a queued frame takes: each destination, with and without a compact DM, at each payload size. */
    private fun frames(): List<OutboundFrame> =
        buildList {
            for (destination in Destination.entries) {
                for (direct in listOf(null, DirectSend("bob", BoardAddr(2u), "f", DirectKind.MESSAGE))) {
                    for (size in listOf(1, 60, 153, 165, 166, 219, MeshtasticProto.MAX_PAYLOAD)) {
                        val klass = if (direct != null) FrameClass.DIRECT else FrameClass.ROOM
                        val ceiling = if (destination == Destination.Knit) Ceiling.FULL else Ceiling.HALF
                        add(
                            OutboundFrame(
                                listOf(ByteArray(size)),
                                "$destination/${direct != null}/$size",
                                klass,
                                ceiling,
                                destination,
                                direct = direct,
                            ),
                        )
                    }
                }
            }
        }

    /** `OutboundFrame.price` as it stood at bdc8f611f. */
    private fun oldPrice(
        frame: OutboundFrame,
        air: LoraAirtime,
    ): PacketPrice =
        air.price(
            slot = if (frame.destination == Destination.Knit) AirSlot.KNIT else AirSlot.PRIMARY,
            kind = if (frame.direct != null || frame.destination == Destination.Reply) PacketKind.DATA_UNICAST else PacketKind.BROADCAST,
            portnum = if (frame.destination == Destination.Knit) MeshtasticProto.PORT_PRIVATE_APP else MeshtasticProto.PORT_TEXT_MESSAGE,
        )

    /** `LoraAirtime.knitBroadcast` as it stood. */
    private fun oldKnitBroadcast(air: LoraAirtime): PacketPrice =
        air.price(AirSlot.KNIT, PacketKind.BROADCAST, MeshtasticProto.PORT_PRIVATE_APP)

    /** `LoraAirtime.airMs` as it stood: LongFast with no radio, 24 B of framing, the signature under the cliff. */
    private fun oldAirMs(
        payloadBytes: Int,
        price: PacketPrice,
        preset: ModemPreset?,
    ): Long {
        val p = preset ?: ModemPreset.LONG_FAST
        val sf = p.spreadFactor
        val symbolMs = Math.pow(2.0, sf.toDouble()) * 1000.0 / p.bandwidthHz
        val de = if (symbolMs > 16.0) 1 else 0
        val signature = if (payloadBytes <= price.signedUpTo) MeshtasticProto.XEDDSA_SIGNATURE_FIELD else 0
        val phyBytes = payloadBytes + price.extraBytes + 24 + signature
        val numerator = (8 * phyBytes - 4 * sf + 28 + 16).toDouble()
        val denominator = (4 * (sf - 2 * de)).toDouble()
        val payloadSymbols = 8.0 + maxOf(0.0, ceil(numerator / denominator) * p.codingRate)
        return ((20.25 + payloadSymbols) * symbolMs).toLong().coerceAtLeast(1)
    }

    private fun config(preset: ModemPreset) =
        LoraRadioConfig(usePreset = true, modemPreset = preset, region = LoraRegion.US, hopLimit = 3, overrideDutyCycle = false)

    private companion object {
        const val STEPS = 40

        /** A minute's window: a 3 s allowance and a 1.5 s half at LongFast in the US, so packets fall either side of it. */
        const val SHORT_WINDOW_MS = 60_000L

        /** One board per signing era, each policy and ham mode on the ones that read them (`LoraAirtimeParityTest`'s set). */
        val FACTS: List<SigningFacts> =
            buildList {
                add(SigningFacts())
                add(SigningFacts(firmware = "2.7.15.567b8ea", hasXeddsa = false))
                add(SigningFacts(firmware = "2.7.15.567b8ea", hasXeddsa = true))
                add(SigningFacts(firmware = "2.8", hasXeddsa = true))
                add(SigningFacts(firmware = "garbage", hasXeddsa = null))
                for (firmware in listOf("2.8.0.1", "2.8.1.abc", "2.8.2.def", "2.9.0.0", null)) {
                    for (policy in listOf<SignaturePolicy?>(null) + SignaturePolicy.entries) {
                        for (licensed in listOf(false, true)) {
                            add(SigningFacts(firmware = firmware, hasXeddsa = true, policy = policy, licensed = licensed))
                        }
                    }
                }
                add(SigningFacts(firmware = "2.8.1.abc", hasXeddsa = false, licensed = true))
            }
    }
}
