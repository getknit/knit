package app.getknit.knit.mesh.lora

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.Locale

/**
 * Pins [LoraAirtime]'s Meshtastic numbers across its move onto neutral `RadioParams` and a `DutyPolicy` (ADR
 * 2026-11.gsqu, Stage 1a.2): every value below was captured from the code as it stood before the move (215b059), and
 * the move must not change one of them. The golden is `src/test/resources/lora/airtime-parity.golden`: one line per
 * case for the small tables, and for the two large ones (each preset across every region; each board's prices) one
 * SHA-256 per group over the group's lines, with a few of those lines kept in full beside it to read. A missing golden is
 * written to `build/lora-airtime-parity.golden` and the test fails, which is how it was made; a group that moved prints
 * its lines as they are now.
 *
 * It covers every [ModemPreset] × [LoraRegion] (with and without `override_duty_cycle`), no radio at all (the LONG_FAST
 * and `FALLBACK_PERCENT` fallbacks), `onRadioConfig(null)` leaving the last value standing, a dedicated slot under both
 * settings of `dedicatedUnlocksDuty`, `publicCeilingMs`, `slotTimeMs`, `padTo` at each preset, and `price` over every
 * [AirSlot] × [PacketKind] × AEAD × signing era, each priced again through `timeOnAirMs`.
 */
class LoraAirtimeParityTest {
    @Test
    fun everyMeshtasticNumberIsWhatItWasBeforeTheNeutralRadio() {
        val actual = compact(lines())
        val golden = javaClass.classLoader?.getResource(GOLDEN)
        if (golden == null) {
            val out = File("build/lora-airtime-parity.golden")
            out.absoluteFile.parentFile?.mkdirs()
            out.writeText(actual.joinToString("\n", postfix = "\n"))
            fail("no golden at $GOLDEN; wrote ${actual.size} lines to ${out.absolutePath}")
            return
        }
        val expected = golden.readText().lines().filter { it.isNotEmpty() }
        if (expected.size != actual.size) {
            println("only in the golden:\n" + (expected - actual.toSet()).joinToString("\n"))
            println("only in this run:\n" + (actual - expected.toSet()).joinToString("\n"))
        }
        assertEquals("case count", expected.size, actual.size)
        expected.zip(actual).forEach { (e, a) ->
            if (e != a &&
                e.contains(" sha256=")
            ) {
                println(lines().filter { groupOf(it) == e.substringBefore(" sha256=") }.joinToString("\n"))
            }
            assertEquals(e, a)
        }
    }

    /** The lines of a hashed group folded to `<group> sha256=<hex>`, and the anchors kept whole; the rest as they are. */
    private fun compact(all: List<String>): List<String> =
        buildList {
            val groups = LinkedHashMap<String, MutableList<String>>()
            for (line in all) {
                val group = groupOf(line)
                if (group == null) add(line) else groups.getOrPut(group) { ArrayList() } += line
            }
            for ((group, members) in groups) {
                add("$group sha256=${sha256(members.joinToString("\n"))}")
                members.filter { line -> ANCHORS.any { line.startsWith(it) } }.forEach(::add)
            }
        }

    /** The hashed group [line] belongs to: a preset across every region, or one board's prices; null for the rest. */
    private fun groupOf(line: String): String? {
        val words = line.split(' ')
        return when (words.first()) {
            "radio" -> "radio ${words[1]}"
            "price", "codec", "snapshot" -> "board ${line.substringAfter(' ').substringBefore(" p=")}"
            else -> null
        }
    }

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.encodeToByteArray()).joinToString("") { "%02x".format(Locale.ROOT, it) }

    private fun lines(): List<String> =
        buildList {
            presetsByRegion()
            noRadio()
            nullReportLeavesTheLast()
            dedicated()
            prices()
        }

    private fun MutableList<String>.presetsByRegion() {
        for (preset in ModemPreset.entries) {
            for (region in LoraRegion.entries) {
                for (override in listOf(false, true)) {
                    val air = LoraAirtime()
                    air.onRadioConfig(config(preset, region, override))
                    add("radio $preset $region override=$override ${describe(air)}")
                }
            }
        }
    }

    private fun MutableList<String>.noRadio() {
        add("none ${describe(LoraAirtime())}")
        add("none-unlocked ${describe(LoraAirtime(dedicatedUnlocksDuty = true))}")
    }

    private fun MutableList<String>.nullReportLeavesTheLast() {
        val air = LoraAirtime()
        air.onRadioConfig(config(ModemPreset.MEDIUM_FAST, LoraRegion.EU_868, override = false))
        air.onRadioConfig(null)
        add("null-after ${describe(air)} region=${air.snapshot(0).region} preset=${air.snapshot(0).preset}")
    }

    private fun MutableList<String>.dedicated() {
        for (unlocks in listOf(false, true)) {
            for (region in LoraRegion.entries) {
                for (preset in ModemPreset.entries) {
                    val slot = LoraSlot.forRegion(region, preset) ?: continue
                    for (channelNum in listOf(slot, slot + 1)) {
                        val air = LoraAirtime(dedicatedUnlocksDuty = unlocks)
                        air.onRadioConfig(config(preset, region, override = false, channelNum = channelNum))
                        add(
                            "dedicated unlocks=$unlocks $region $preset ch=$channelNum " +
                                "allowance=${air.allowanceMs()} ceiling=${air.publicCeilingMs()} dedicated=${air.dedicated()}",
                        )
                    }
                }
            }
        }
    }

    private fun MutableList<String>.prices() {
        for (facts in FACTS) {
            for (primaryAead in listOf(false, true)) {
                for (knitAead in listOf(false, true)) {
                    val air = LoraAirtime()
                    air.onRadioConfig(config(ModemPreset.LONG_FAST, LoraRegion.US, override = false))
                    air.onBoard(facts, primaryAead = primaryAead, knitAead = knitAead)
                    val snap = air.snapshot(0)
                    add("snapshot $facts p=$primaryAead k=$knitAead knit=${snap.knitSignedUpTo} room=${snap.roomSignedUpTo}")
                    add("codec $facts p=$primaryAead k=$knitAead toa=${SIZES.map(air::timeOnAirMs)} pad=${padRow(air)}")
                    priceRows(air, "$facts p=$primaryAead k=$knitAead")
                }
            }
        }
    }

    private fun MutableList<String>.priceRows(
        air: LoraAirtime,
        board: String,
    ) {
        for (slot in AirSlot.entries) {
            for (kind in PacketKind.entries) {
                for (port in PORTS) {
                    val price = air.price(slot, kind, port)
                    val toa = SIZES.map { air.timeOnAirMs(it, price) }
                    add("price $board $slot $kind port=$port $price toa=$toa")
                }
            }
        }
    }

    private fun describe(air: LoraAirtime): String {
        val snap = air.snapshot(0)
        return "allowance=${air.allowanceMs()} ceiling=${air.publicCeilingMs()} slot=${air.slotTimeMs()} " +
            "dedicated=${air.dedicated()} known=${snap.known} snapAllowance=${snap.allowanceMs} snapCeiling=${snap.publicCeilingMs} " +
            "toa=${SIZES.map(air::timeOnAirMs)} unsigned=${SIZES.map { air.timeOnAirMs(it, LoraAirtime.UNSIGNED) }} " +
            "pad=${padRow(air)}"
    }

    private fun padRow(air: LoraAirtime): List<Int> = PAD_CASES.map { (size, cap) -> air.padTo(size, cap) }

    private fun config(
        preset: ModemPreset,
        region: LoraRegion,
        override: Boolean,
        channelNum: Int = 0,
    ) = LoraRadioConfig(
        usePreset = true,
        modemPreset = preset,
        region = region,
        hopLimit = 3,
        overrideDutyCycle = override,
        channelNum = channelNum,
    )

    private companion object {
        const val GOLDEN = "lora/airtime-parity.golden"

        val SIZES = listOf(1, 16, 51, 100, 153, 154, 165, 166, 200, 219, 228, 231)

        /** Lines kept in full beside their group's hash, so the golden still reads: LongFast in the US and EU_868. */
        val ANCHORS =
            listOf(
                "radio LONG_FAST US override=false",
                "radio LONG_FAST EU_868 override=false",
                "radio LONG_FAST EU_868 override=true",
                "snapshot SigningFacts(firmware=null, hasXeddsa=null, policy=null, licensed=false)",
            )

        val PAD_CASES =
            listOf(1 to 228, 100 to 228, 140 to 228, 150 to 228, 160 to 228, 165 to 228, 166 to 228, 165 to 165, 200 to 231)

        val PORTS =
            listOf(
                MeshtasticProto.PORT_PRIVATE_APP,
                MeshtasticProto.PORT_TEXT_MESSAGE,
                MeshtasticProto.PORT_NODEINFO,
                MeshtasticProto.PORT_ROUTING,
            )

        /** One board per signing era, and each policy and ham mode on the ones that read them. */
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
