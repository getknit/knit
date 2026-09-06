---
id: "2026-09.6gtm"
slug: the-lora-plane-is-introduced-at-2-5-0
title: "The LoRa plane is introduced at 2.5.0"
date: 2026-09-06
topics: [lora, release, mesh]
---

# ADR 2026-09.6gtm — The LoRa plane is introduced at 2.5.0

Status: Accepted (2026-09-06; `app/build.gradle.kts`, `BuildConfig.LORA_PLANE`)

ADR 038 hid the LoRa (Meshtastic-over-BLE) plane behind `BuildConfig.LORA_PLANE`, false in release and
staging, so 2.3.0 through 2.4.x shipped every class of it and no way in. 2.5.0 flips that default to
true. Like ADR 064's flip of `INTERNET_PLANE` it is one line in `app/build.gradle.kts`, and for the same
reason: the flag was never a code strip, R8 only pruned the `if (LORA_PLANE)` branches, and the unit
suite — which builds debug — has been exercising `mesh/lora/` end to end against a fake board and fake
air the whole time. No wire, DB, protocol or vector change rides here. The two flags are a pair again,
and `-PloraPlane=false` still produces the artifact 2.4.x shipped.

**Visible is not enabled, and the gap is wider here than it was for the spool.** The flag gates the ways
in — the `lora` route, the Profile row, the LoRa-radio settings screen, and `SettingsStore.loraEnabled`'s
ability to mean what the user stored, along with `loraDmEnabled` / `loraBridgeEnabled` /
`loraRoomEnabled`, which all read false while it is off. `loraEnabled` still defaults false. Beyond that
switch, the plane needs hardware a user has to buy: an ESP32/nRF52 Meshtastic board, flashed, in radio
range, paired over GATT, and set up for Knit (ADR 045). Someone who never buys one has an app that
behaves exactly like 2.4.x, and the blast radius of a mistake here is the set of users who bought a
radio and switched it on.

**The `0x05` flag-day is settled by shipping, not blocked by it.** ADR 060 left the LoRa plane
transcoding every frame it can as tag `0x05` with no capability negotiation — a LoRa sighting carries no
capability bits and the OFFER has no field for one — and recorded that "before the plane ships to
release it needs a gate". That gate is now moot, and building it would cost more than it buys. A dark
build never joins the plane at all: `MeshModule` adds no `LoraMeshTransport` child and
`LoraStatusRepository` reports `Off`, so it neither sends nor receives a LoRa frame, and no shipped Knit
has ever spoken on the channel. The release cohort that can hear one is therefore uniformly 2.5.0 or
newer and uniformly speaks `0x05`; there is no older population to fall back for. The gate would also
have re-opened a budget the flag-day closes: `LoraSizeHint.DM_BODY_BYTES` (320) is honest only in the
transcoded form — a budget DM carrying its four inline acks is 596 B against the 681-B ceiling there but
675–683 B as `0x03` — so a per-peer fallback would have to drop the composer's promised budget by ~20 B
or eat a `loraTooBig` on a tenth of the DMs it just accepted. `CoordinationPlaneSizeBudgetTest` pins both
budgets on the transcoded form, and now nothing on the plane leaves that form. **Residual:** a build
older than 2026-08-29 (ADR 060) on the channel drops our frames as `UNKNOWN_TAG`. That is now a lab
concern only — a stale debug install, or a release-shaped reflash carrying `-PloraPlane=true` — and
reflashing it is the fix. A future tag change is a different problem and still needs the gate ADR 060
described; this decision does not retire that design, it retires its deadline.

**Two device trials are still owed, and shipping without them is the risk this decision takes.** The
four-device two-pocket bridge run (ADR 044) and the airtime-shaping three-phone run (ADR 054) in
`.agents/context/lora-bridge.md` have not been run; the two-phone broadcast and DM trials have, in both
directions, including the 166-byte signed-post path (ADR 2026-09.ggq4). Unlike a spool failure, a LoRa
failure is loud: the channel is shared with every radio in range, so a saturated plane degrades other
people's Meshtastic traffic, not only the user's own. Three things already bound that. Airtime is
budgeted and shed by class before it is dropped (`airtime.liveMs` against `liveBudgetMs`, `bridgeMs`
against `bridgeBudgetMs`, `SERVE_CAP_PER_HOUR` on backfill), and a spent bucket holds frames rather than
flooding. Board setup writes Knit into a *secondary* channel and sets `rebroadcast_mode = LOCAL_ONLY`,
so a Knit-provisioned board does not repeat other people's traffic. And Knit's own packets go out with
`hop_limit = 0`, so stock nodes never relay them — the harm a bad build can do stops at its own radio
horizon rather than propagating across a mesh. What a failure in the owed trials degrades to is "the
bridge does not backfill" or "the plane throttles", never a lost message: LoRa is a `MeshTransport`
child under `CompositeMeshTransport`, additive to Wi-Fi Aware and BLE custody, never a replacement.

**What ships known-imperfect.** The LoRa Signal row reads the last packet from any sender on any channel
before filtering, never ages it, and restores it across restarts, so a healthy link can display a stale
decayed number (measured board-to-board: +6 dB SNR / -7 dBm RSSI). It is cosmetic and it is in the
settings screen this flip makes reachable. The store description is the other open item: nothing in it
becomes *false* — it claims two radios and a fresh install making no network calls, both still true —
but at 3995 characters against Play's 4000-character cap there is no room to describe the plane, so
saying anything about LoRa on the listing means cutting existing copy first, exactly the per-release
cost ADR 064 named.
