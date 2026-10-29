---
id: "044"
slug: pockets-bridge-over-lora
title: "Pockets bridge over LoRa: an elected gateway, an airtime budget, and a gossiped custody window"
date: 2026-08-25
topics: [lora, custody, airtime]
---

# ADR 044 — Pockets bridge over LoRa: an elected gateway, an airtime budget, and a gossiped custody window

Status: Accepted (2026-08-25; `mesh/lora/` `LoraCtl`/`LoraGatewayPolicy`/`LoraGossipPolicy`/`LoraAirtime`,
`mesh/BridgeFrameSource`, `SettingsStore.loraBridgeEnabled`)

Two groups of users, each meshed over BLE/NAN, too far apart to reach each other, one board-holder in each,
the boards in LoRa range. **Half of this already worked and was not rebuilt**: `InboundPipeline.onDeliver`
re-fans every first-seen *relayed* frame onto `fastFanout`/`longRangeFanout`, and `LoraMeshTransport.fanout`
has no authorship check, so a room post or DM authored anywhere in pocket A already crosses to pocket B and
floods it from there. What was missing was everything that is not *live*: a frame said while the far board
was off never crossed (`LoraFramePolicy.isFresh`, 15 min, refuses a custody re-serve — ADR 039 §6), the
Nearby room had no backfill at all, nothing measured airtime, and two boards in one pocket each paid for
every frame (ADR 038's accepted "one board per clique" residual, which a bridge makes structural). Decisions
worth not relitigating:

1. **A LoRa-local control packet, not a wire change.** `LoraCtl` claims first byte `0x10`, beside
   `FastFrameCodec`'s `0x03`/`0x04`. `LoraMeshTransport` already dropped an unrecognised first byte as
   `FastPathDrop.UNKNOWN_TAG`, so **a build predating the tag ignores it** — the whole feature is additive by
   construction. No frame type, field, ctl code, capability bit or DB migration; `sig`/`signed` still cross
   byte-exact. The ADR 030/038 argument, reused a third time.
2. **The OFFER announces a *window*, by 4-byte id prefixes.** One packet carries ≤ 48 prefixes (top 32 bits
   of `StoreDigest.hash64(frame.id)` — the mesh's existing id hash, not a new one), so a far gateway computes
   what we lack and serves exactly that: no request round trip, no blind re-transmission. **Never
   fragmented** — a control packet that needs reassembly to be useful is worse than a shorter one, so it
   truncates. A prefix collision (~1 in 4·10⁹) skips one frame for a round; nothing here is a trust boundary,
   every frame still carries the originator's signature.
3. **The election costs no airtime, because the composite already tells us who holds a live link.**
   `suppressDataPath` hands the LoRa child the higher-preference planes' `neighbors` — "who in my pocket can be
   handed my traffic" — and anything that publishes an OFFER has a board by definition. A publisher we are
   linked to is a co-pocket rival, one we are not is the bridge peer and is **never** suppressed. Lowest
   publisher key wins (the 64-bit id hash — all the packet has room for, and uniformly distributed, so no node
   is structurally favoured). PASSIVE suppresses the **floodable** paths — fan-out, beacon, offer, backfill —
   and nothing else, so a spare board keeps feeding its pocket. Recovery runs on a lost link, on an OFFER, and
   on the 60-s sweep, because both event sources can fall silent together and being wrongly passive is total
   silence.

   *Amendment (2026-08-25, field).* This first shipped keyed on `onForeignReachable`, and that was wrong:
   `reachable` is a **sighting**, not a data path — BLE publishes presence adverts far beyond L2CAP range,
   Wi-Fi Aware keeps a 150-s ghost, and the member's own kdoc says "not necessarily linked here". Two Pixels
   across a field sighted each other without ever linking; the higher-keyed one stood down and went completely
   silent — no room posts, no DMs, no ✓✓ — with nothing carrying its traffic. Standing down is only ever safe
   toward a board our frames can actually reach. Two rules fall out and are pinned by
   `LoraGatewayPolicyTest`/`LoraBridgeTest`: elect on **links**, and **never gate `fastSend`** — a
   `relay = false` targeted send is owed by exactly one node and never flooded, so no co-pocket gateway holds a
   copy to relay *or* to duplicate, and suppressing it only stranded AckSync's ticks for their full 24 h of
   retries.

   The same audit found ADR 039's own two `foreignReachable` guards making the identical mistake, and the
   `fastSend` one would have kept the field test's receipts stranded even after the role was fixed. "Another
   plane already carries this peer's traffic" and "custody syncs to it for real there" both describe a **data
   path**: `ForwardSync`'s digest exchange runs off `neighbors`, and a sighting never triggers it. Both now
   read the link set. `foreignReachable` is kept, recorded and surfaced as `pocketSightings` — nothing routes
   on it, and it sits beside `pocketLinks` precisely so the gap between heard and linked is visible. That gap
   being invisible is how this survived review, so `…debug.LORA` now reports `role` with both its inputs.
4. **Airtime is measured, not inferred from a refusal.** `LoraAirtime` computes time-on-air from the LoRa
   formula at the board's own modem preset, keeps a rolling hour, and holds two budgets from one allowance:
   `min(the region's duty cycle, a 10 % politeness ceiling) x 0.5`. LIVE may spend all of it; BRIDGE — gossip
   plus backfill plus the ADR 039 re-offer, which stops being unmetered — is capped at 30 %, so a busy bridge
   degrades into serving less history rather than into delaying somebody's message. `FrameClass.BOOTSTRAP` is
   always admitted and merely recorded — refusing the profile costs more air than it saves, since every frame
   from that peer afterwards is unverifiable. Region and preset come off the board: `FromRadio.config` →
   `Config.LoRaConfig`, decoded during the existing handshake, golden-vector pinned, total on malformed input,
   conservative 5 % fallback when absent. The firmware's own `override_duty_cycle` is honoured — the user took
   the regulatory call — but the politeness ceiling stays.
5. **Trickle, and "consistent" means the same set.** `LoraGossipPolicy` doubles 5 → 15 min while nothing
   changes, snaps to the floor on news (a new far gateway, a frame crossing either way), and transmits at a
   random point in each interval's second half. Suppression requires **set equality**, not coverage: our
   OFFER's job is to tell a far gateway what we *lack*, and a peer holding a superset has said the opposite on
   our behalf. That means a crowd of listeners is **not** free on the offer side — what bounds a crowd is the
   serving side. The ceiling is deliberately far below `STALE_MS`: an active gateway's OFFER is also its
   liveness beacon.
6. **The frame-set rule lives in `LoraFramePolicy`, once.** `Path.BACKFILL` admits exactly what `Path.FANOUT`
   does — the bridge re-serves history, so it must carry only what the live plane would have carried — and the
   difference is `isFresh`, applied on one and not the other. `MeshManager` implements `BridgeFrameSource` by
   calling that predicate rather than restating it: a rule stated twice drifts. Group-form chat stays refused
   (the cleartext roster costs ~200 B more than a DM, and the plane carries no group conversation).
7. **`ForwardStore.liveFrames` is already the eligible set.** It is TTL-bounded (6 h broadcast, 24 h
   otherwise) and quota-trimmed by the ADR 006 rules, identically on every node, so backfill needs no age gate
   of its own and can never serve an expired frame. Bytes are a verbatim re-wrap, exactly as
   `MeshManager.framesFor` already does — nothing stored, nothing re-encoded, no custody rule touched, so the
   content digest's inputs are unchanged.
8. **Four bounds on serving, and only one of them is the real one.** Per-offer limit (4), per-publisher hourly
   cap (12), the sig dedup, and the BRIDGE airtime budget. The cap exists because a hostile node can publish an
   empty OFFER repeatedly to walk a gateway through its whole custody set on the air; the budget is what
   actually bounds the cost. Serve priority is profile → DM-form → room, newest first, because a frame the far
   side cannot verify is airtime thrown away.
9. **Two loop bugs the change surfaced, both fixed.** `LoraPacePolicy.take` can now decline for a reason the
   inter-packet gap does not describe (budget spent, or the pre-existing `queueFree == 0`), which left the
   pacer computing a zero wait and spinning; its wait is now floored. The gossip loop had the same shape when
   the board was down, and now consumes its transmit slot before consulting the link.
10. **Dequeue is by class, then FIFO — a deliberate reversal of ADR 039 §5's "dequeue stays FIFO".** That was
    right while everything on this plane was something a human had just typed. Gossip and backfill arrive in
    bursts nobody is waiting for, and at a 3-second gap a burst of four puts a live message twelve seconds
    behind. The class order already states what matters most; running it on the way out costs nothing.
11. **A default-on switch, off in both directions.** `SettingsStore.loraBridgeEnabled` governs publishing
    offers and serving backfill together — a node that stops offering also stops being served, which is the
    honest reading of "don't use my board for other people's backlog". Live traffic crosses either way.

Multi-hop between pockets stays **Meshtastic's** job: a frame injected at a far gateway is blocked from
re-transmission by the sig dedup, and any second board in that pocket is PASSIVE, so a third pocket is reached
by the board's own 3-hop flood rather than by a second phone-level transmission.

**Radios and people are different numbers, and the row asks for radios.** `LoraStatus.heard` counts distinct
frame **authors** (`noteReachable(Peer(env.senderId))`) and always did — which is right for `reachable`, since
the question there is who this mesh can reach. The bridge makes it diverge sharply from the hardware, because
backfill serves frames authored by people nowhere near any radio: the field saw "3 peers heard over LoRa" with
two radios in existence. `boardsHeard` now counts distinct Meshtastic `packet.from` on our channel — control
packets and incomplete fragments included, so a board that only gossips still registers — and the settings row
leads with that, showing the author count beneath only when the two differ. Routing is untouched.

Counters: `loraOfferSent`/`loraOfferReceived`, `loraBridged`, `loraBridgeRefused`, `loraPassive`, plus the
airtime ledger on `LoraStatus`. Surfaced on the LoRa radio screen (role, radio, airtime percent) and in
`…debug.LORA` (`role`, `radio`, `airtime`, `--ez bridge`).

Honest residuals (accepted): 48 prefixes is a window, so a pocket busier than that under-reports its oldest
frames (a Bloom filter would hold ~190 and is the upgrade if it bites); backfill is unacknowledged, so a
served frame lost to the air waits for the next round; a passive gateway is dead weight for redundancy until
`STALE_MS` elapses if the active one dies without leaving `foreignReachable`; the offer side is O(peers), not
O(1); and the time-on-air figure is an estimate that does not know the board's preamble length or how many
neighbours repeated us. `KnitChannel`'s derivation is untouched, so `docs/NEXT_WIRE_BREAK.md`'s open question
about the LoRa rendezvous marker does not come due. Scheme + device bring-up: `context/lora-bridge.md`.

## Amendment 2026-10-04 — a live frame the total refuses holds what ranks behind it

**What was observed.** On the 2026-10-04 field test the P9 carried a LoRa board ~100 m out with an iPhone and a P8 linked
to it, all three chatting in the Nearby room. The P7 and its board were in the lab. The P9's DM to the P7 ("Seacliff
test", 16:28:33) waited behind room posts until 16:39, and its ✓✓ came home only over Bluetooth at 16:47. From the
P9's logs and `…debug.LORA`:

- The board reported 0 % channel utilization; Knit's own governor was the limit.
- The P9 aired nothing from 16:27:40 to 16:34:01: the window's total was spent.
- The window then handed air back one booking at a time, about 1.7 s each. Each time, a one-packet room post fitted
  and the two-packet DM did not. `take` skips a refused frame (this ADR) and dequeues by class, so it skipped the DM
  and sent the room post.
- Over the walk: `loraAirtimeHeld` 183 (97 LIVE), `loraDroppedQueue` 46 and 26 frames stale at send.

The class order already said the DM goes first. The skip rule let smaller, lower-ranked frames spend the air the DM was
waiting for.

**What changed.**

- **A refusal has a reason.** `LoraAirtime.refusal` returns `TOTAL`, `SHARE`, `TICK_TAIL` or `NEVER`, and `admits` is
  `refusal == null`, with every verdict unchanged.
- **A live frame refused on the total is a holder.** `LoraPacePolicy.admitBest` finds the best-ranked `AirBucket.LIVE`
  frame refused on `Refusal.TOTAL`. Nothing that ranks behind it may go while it waits: no lower class, and nothing
  of its own class queued after it. Air the window hands back accumulates until it fits. The held frames count once
  each in `loraAirtimeHeld`, like any frame waiting for air.
- **What still goes ahead.** The classes above it (the profile bootstrap on its own share, the gossip OFFER), and an
  earlier frame of its own class that fits.
- **What never holds.** A frame refused on its own share (`SHARE`): a backfilled DM on `AirBucket.BRIDGE` keeps DM
  class, and holding live chat behind it would let backfill crowd live chat, which the share exists to prevent. A tick
  refused for the tail. A frame larger than the whole allowance (`NEVER`), which no window would ever admit.
- **What ends a hold.** The frame airing, being shed by class, or going stale at send (15 min, `FRESH_MS`).

**What it costs.** Under a spent window, room posts and ticks wait behind a DM for one more booking to roll off
instead of going first. Those were the frames the window was already shedding (46 queue drops on the walk). Nothing
changes while the window has room.

**Not changed:**

- The allowance itself, 45 s per 15 min on a stock LongFast slot (5 %). The band was idle, so a governor that raises
  its ceiling on a quiet channel is the obvious next question, but it is a product decision of its own.
- The DM-form frames that finally aired at 16:39:22–36 did not reach the P7. The logs do not say why (the P7's own
  three-packet profile sends at 16:38:58 and 16:39:04 are the nearest suspect), and this change does not touch it.

Tests: `LoraPacePolicyTest`: the field case, a backfilled DM holding nothing, the classes above going, FIFO inside the
class, nothing waiting on an oversized frame, held frames reported once. `LoraAirtimeTest`: each refusal reason.
A negative control with the hold disabled fails three of them.

## Superseded in part by ADR 2026-10.rp3s (2026-10-29)

Its gateway election, OFFER gossip, backfill and serve caps are gone with the pocket bridge: the board airs only its own user's frames. The airtime allowance, the 15-minute window (ADR 054) and the 2026-10-04 live-hold amendment stay; the `BRIDGE` share does not.
