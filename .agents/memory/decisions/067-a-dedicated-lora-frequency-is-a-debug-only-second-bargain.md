---
id: "067"
slug: a-dedicated-lora-frequency-is-a-debug-only-second-bargain
title: "A dedicated LoRa frequency is a debug-only second bargain, and it is the *politeness* ceiling it lifts"
date: 2026-08-31
topics: [lora, airtime, debug]
---

# ADR 067 — A dedicated LoRa frequency is a debug-only second bargain, and it is the *politeness* ceiling it lifts

Status: Accepted (2026-08-31; `mesh/lora/` `LoraSlot`, `LoraAirtime.dedicatedUnlocksDuty`, `ProvisionMode.SetupDedicated`,
`BoardConfig.LORA`, `LoraRegion.US`/`ANZ` bands, `SettingsStore` prior `channel_num`, the LoRa screen's
debug-only setup action, `…debug.LORA` + `--es mode dedicated`)

ADR 045 put Knit on the stock public frequency and called sharing it the feature: a stranger's node that
cannot decrypt a byte of Knit still repeats our packets, because `rebroadcast_mode = ALL` covers traffic
"from another mesh with the same lora params". That reasoning has a premise, and the premise is a
neighbourhood. On an isolated farm or a mountain house there are no organic boards to borrow, so the
shared slot buys nothing — and still costs `LoraAirtime`'s 10 % politeness ceiling, which exists for
neighbours who are not there. A household fleet in that position is paying rent on infrastructure it
does not have.

So there is now a second setup, **debug builds only and never the default**, that pins the radio to a slot
of its own. Four things make it safe to have at all:

1. **It is the politeness ceiling that lifts, not the law.** `allowanceMs()` used to fold both caps into one
   `min(region, 10 %)`; they are now computed independently — the regional duty cycle, which only the
   firmware's own `override_duty_cycle` escape hatch touches, and Knit's manners toward everyone else on the
   shared band, which a dedicated slot makes vacuous. A dedicated US board therefore runs to the 100 % its
   region permits (50 % after the 0.5 safety factor: 450 s of air a window instead of 45 s) and a dedicated
   EU_868 one still stops dead at 10 %, because that ten was never politeness. The unlock is a constructor
   flag defaulting to false, wired to `BuildConfig.DEBUG` in `LoraMeshTransport` — so `LoraAirtime` stays
   pure and JVM-testable in both states, and a release build budgets exactly as it does today even against a
   board somebody pinned by hand in the Meshtastic app.
2. **The slot is derived, not configured.** `channel_num` means Knit computes a transmit frequency, so
   `LoraSlot` does the same arithmetic the firmware does — `hash(KnitChannel.NAME) % (band / preset
   bandwidth)`, FNV-1a written out rather than borrowed from `String.hashCode`, whose value is a platform
   promise not worth pinning a frequency to. Deriving rather than prompting keeps ADR 045's best property
   inside the dedicated fleet: two boards in the same region on the same preset land on the same slot with no
   coordination. A user-entered slot number was the alternative and was rejected for the same reason ADR 045
   rejected a lighter mode — two kinds of fleet that cannot hear each other.
3. **A band Knit does not know exactly gets no slot at all.** A slot past the end of the band is out-of-band
   transmission, not a bug, and the firmware's clamping behaviour is not something to assume. `LoraRegion`
   therefore carries `bandStartKhz`/`bandEndKhz` for exactly the two regions this is worth doing in — US
   (902–928, 104 slots at LongFast) and ANZ (915–928, 52) — which un-collapses them from the `OTHER` bucket
   they used to share. Everything else, `OTHER` included, has no band and is refused as
   `ProvisionResult.NoDedicatedSlot` with nothing written; `OTHER` stays a bucket of regions with different
   bands whose narrowest (RU, ~0.5 MHz) has no room to move anyway. `MIN_CHANNELS = 2` refuses the other
   degenerate case — a band whose single slot *is* the stock slot, where "dedicated" would move the radio
   nowhere while still lifting the ceiling.
4. **The radio is still not a setup's business.** `BoardConfig.LORA` exists now, but it is deliberately
   outside `BoardConfig.QUIET`, so the ordinary setup neither reads nor writes it — ADR 045's promise that a
   plain setup never touches a legally-scoped sub-config survives the codec learning to address it. Only the
   dedicated setup reads it, and then only to splice one varint; region, preset and `tx_power` come through
   the read-modify-write verbatim, pinned by `MeshtasticSessionTest`. The board's own `channel_num` is
   recorded like every other prior value (ADR 045 §5) so a restore hands back *the user's* slot rather than
   an assumed 0, and a restore of a board that was never pinned reads and writes no radio config at all.

Honest residuals (accepted): the fleet must be set up the same way end to end, and nothing detects a
half-converted one — a board left on the shared slot simply never hears the others, which looks exactly like
being out of range; the derived slot is not checked against the stock slot the board's primary would hash to,
so a collision is possible and merely means the dedicated fleet is sharing after all; `LoraSlot` knows two
bands, so a US/ANZ-shaped feature is what this is until somebody states another band exactly; and the 0.5
safety factor still stands in for the flood amplification `LoraAirtime` does not model (`hopLimit` is parsed
and carried but never costed), which on a dedicated slot is the one place that estimate is *too*
conservative rather than not enough. The shared frequency remains the default and the only thing a release
build offers, because ADR 045's bargain is still the right one wherever there is a neighbourhood to borrow.

*Amendment (2026-09-19).* The way back off a slot was Restore and a second setup — two reboots, and the
board's Knit identity torn down and rebuilt in between for a change that is one varint. `ProvisionMode.SetupShared`
is that varint on its own: `runSetup`'s radio half runs `slotRestore` (the same write Restore makes, toward the
recorded prior) and nothing else, the record is handed back untouched so a later Restore still puts the
*user's* slot back, and a board already there is a reported no-op. Debug-only like the setup it reverses; the
screen's dedicated button gives way to it while the board is pinned (`lora_setup_shared`), and the bridge takes
`--es mode shared`.
