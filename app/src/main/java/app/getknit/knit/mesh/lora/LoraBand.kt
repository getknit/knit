package app.getknit.knit.mesh.lora

/**
 * The regional duty cycle a transmission is under, read off its frequency and bandwidth: the law for a board kind whose
 * firmware enforces none (ADR 2026-11.gsqu's Stage 5a-1 amendment). A MeshCore companion has only a 50 % token bucket
 * (`Dispatcher::updateTxBudget`, spike Q4) and knows no region, so ADR 2026-10.ug4a's "the law is the firmware's
 * `DUTY_CYCLE_LIMIT`" does not hold there and this table is what the ledger's allowance ([DutyPolicy]) and the law check
 * (`MeshcoreDutyLaw`) read. Never a Meshtastic board's: its duty is its region's ([MeshtasticTraits.dutyPolicy]).
 *
 * **What a row matches.** The whole occupied channel — the centre frequency ± half the bandwidth — must sit inside the row.
 * A channel inside several rows (US and ANZ overlap at 915–928 MHz) takes the strictest of them; a channel inside none
 * (a gap between EU sub-bands, a straddle, a band this table does not know) takes [STRICTEST_PERCENT], the strictest duty
 * of any row, never `LoraAirtime.FALLBACK_PERCENT`, which would be over the law on a 1 % or 0.1 % sub-band.
 *
 * **Sources.** The EU rows are the non-specific short-range-device bands of CEPT ERC Recommendation 70-03 Annex 1, which
 * ETSI EN 300 220-2 implements. None of that text is in this repository or in the MeshCore firmware, so every EU edge,
 * every EU duty and every Annex 1 band label below is **to check against EN 300 220**, except a duty the repository
 * confirms (cited in its row). The US and ANZ rows are the repository's own [LoraRegion.US] / [LoraRegion.ANZ] band edges
 * and duty (ADR 067). The MeshCore region presets this table must cover come from the MeshCore App API, not the firmware
 * (`docs/radio_presets.md`, `docs/faq.md` at `companion-v1.18.0`): Australia (Narrow) 916.575 MHz, New Zealand (Narrow)
 * 917.375 MHz, USA/Canada 910.525 MHz, Portugal 868 and the EU/UK narrow preset at 869.618 MHz, the firmware's own build
 * default (`platformio.ini` `[arduino_base]`: 869.618 MHz, BW 62.5 kHz, SF8). Every one of those lands in a row below.
 * Asia-Pacific presets (Japan, Korea, India, South-East Asia) are not in the table and read as [STRICTEST_PERCENT] until a
 * row with a source is added; until then the plane is effectively dead there, by design.
 *
 * **Each EU duty assumes its row's power limit** — 25 mW e.r.p. for the 1 % and 0.1 % rows, 500 mW for 869.4–869.65 MHz,
 * 10 mW at 433 MHz — and that too is to check against EN 300 220. The board's transmit power is the user's call, never
 * Knit's (ADR 045: Knit never moves the radio), so a board set above its row's limit is outside what this table models.
 */
@Suppress("MagicNumber") // the table is its figures: each row's edges and duty, cited in its KDoc
internal enum class LoraBand(
    /** The row's lower edge, in Hz. */
    val startHz: Long,
    /** The row's upper edge, in Hz. */
    val endHz: Long,
    /** The duty cycle the row allows, in percent of an hour. */
    val dutyPercent: Double,
) {
    /** 863–865 MHz, 0.1 % (ERC 70-03 Annex 1 band h1.3, 25 mW e.r.p.): to check against EN 300 220. */
    EU_863(863_000_000L, 865_000_000L, 0.1),

    /**
     * 865–868 MHz, 1 % (Annex 1 band h1.4, 25 mW e.r.p.): to check against EN 300 220. Meshtastic's `EU_866` runs 2.5 % here
     * ([LoraRegion.EU_866], the 500 mW network-relay channels); the general 1 % row is the stricter and is taken.
     */
    EU_865(865_000_000L, 868_000_000L, 1.0),

    /** 868.0–868.6 MHz, 1 % (Annex 1 band h1.5, 25 mW e.r.p.): to check against EN 300 220. */
    EU_868_0(868_000_000L, 868_600_000L, 1.0),

    /** 868.7–869.2 MHz, 0.1 % (Annex 1 band h1.6, 25 mW e.r.p.): to check against EN 300 220. */
    EU_868_7(868_700_000L, 869_200_000L, 0.1),

    /**
     * 869.4–869.65 MHz, 10 % (Annex 1 band h1.7, 500 mW e.r.p.). The 10 % is the repository's: Meshtastic's `EU_868` region
     * transmits in this sub-band at [LoraRegion.EU_868]'s 10 %, and ADR 2026-11.gsqu calls MeshCore's EU default (869.525 MHz)
     * a 10 % sub-band. The edges are to check against EN 300 220. MeshCore's EU/UK narrow preset (869.618 MHz at 62.5 kHz:
     * 869.587–869.649) and its long-range one (869.525 MHz at 250 kHz: 869.400–869.650) both fit.
     */
    EU_869_4(869_400_000L, 869_650_000L, 10.0),

    /** 869.7–870.0 MHz, 1 % (Annex 1 band h1.8, 25 mW e.r.p.; no duty limit only at 5 mW): to check against EN 300 220. */
    EU_869_7(869_700_000L, 870_000_000L, 1.0),

    /**
     * 433.05–434.79 MHz, 10 % (Annex 1 band h1.2, 10 mW e.r.p.). The 10 % is the repository's ([LoraRegion.EU_433]); the
     * edges are to check against EN 300 220.
     */
    EU_433(433_050_000L, 434_790_000L, 10.0),

    /** 902–928 MHz, 100 %: [LoraRegion.US]'s band and duty (ADR 067; FCC Part 15 sets no duty cycle). */
    US_915(902_000_000L, 928_000_000L, 100.0),

    /** 915–928 MHz, 100 %: [LoraRegion.ANZ]'s band and duty (ADR 067). */
    ANZ_915(915_000_000L, 928_000_000L, 100.0),
    ;

    companion object {
        /** The strictest duty of any row: what a channel no row holds reads. */
        val STRICTEST_PERCENT: Double = entries.minOf { it.dutyPercent }

        /** Every row that holds the whole channel at [freqHz] with [bandwidthHz] occupied, or none. */
        fun holding(
            freqHz: Long,
            bandwidthHz: Long,
        ): List<LoraBand> {
            val low = freqHz - bandwidthHz / 2
            val high = freqHz + (bandwidthHz - bandwidthHz / 2)
            return entries.filter { low >= it.startHz && high <= it.endHz }
        }

        /** The duty cycle a channel at [freqHz] with [bandwidthHz] is under: its strictest holding row's, else [STRICTEST_PERCENT]. */
        fun dutyPercent(
            freqHz: Long,
            bandwidthHz: Long,
        ): Double = holding(freqHz, bandwidthHz).minOfOrNull { it.dutyPercent } ?: STRICTEST_PERCENT
    }
}
