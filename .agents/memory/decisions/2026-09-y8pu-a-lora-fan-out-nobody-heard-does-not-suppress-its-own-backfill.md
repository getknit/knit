---
id: "2026-09.y8pu"
slug: a-lora-fan-out-nobody-heard-does-not-suppress-its-own-backfill
title: "A LoRa fan-out nobody heard does not suppress its own backfill"
date: 2026-09-02
topics: [lora, custody, reliability]
---

# ADR 2026-09.y8pu — A LoRa fan-out nobody heard does not suppress its own backfill

Status: Superseded by ADR 2026-10.rp3s (2026-10-29) — the backfill it exempted is gone; the re-offer no longer consults `sigSeen` either. Was: Accepted (2026-09-02; `LoraMeshTransport.serveOne` no longer consults `sigSeen`)

Field-observed 2026-09-02, a Pixel 9 out of BLE/NAN range of everything with a board of its own, and a
Pixel 7 in another pocket. A Nearby-room post sent from the Pixel 9 while the two boards were out of LoRa
range of each other was **still undelivered** minutes after they came back into range, and stayed that way.

The first suspect was `LoraFramePolicy.FRESH_MS` — the 15-minute staleness gate — and it was the wrong one.
That gate guards only the fan-out path, and nothing was ever going to re-fan the frame: `onDeliver` re-fans
a *relayed* first-seen frame, so a frame we originated ourselves fans exactly once, at t=0. ADR 044's
digest-driven backfill is the only path that could have carried it afterwards, and it is deliberately exempt
from `isFresh` (`LoraFramePolicy.eligible`: "an old frame is the whole point there").

**The actual blocker was the sig dedup the unheard transmission had already spent.** At t=0 `fanout` ran
`sigSeen.add(dedupKey(…))` and handed the frame to the board. Nobody was in range. `sigSeen` is a
ten-minute set (`SIG_TTL_MS`) that answers *did we put this on the air recently* — and on a plane with no
acknowledgements that is not the same question as *did anyone hear it*. `serveOne` consulted the same set,
so for ten minutes after a transmission into an empty sky, the one path that could repair it skipped it
silently and counted `loraSuppressed`.

## What changed

`serveOne` still **records** the signature; it no longer **refuses** on it. One line, and the reason it is
safe is the same reason ADR 057 already exempted `profileSeen` one gate down: an OFFER is *positive
evidence* that the far gateway lacks this exact frame — better information than either set's guess that we
need not send it. `serveBackfill` is now bounded three ways rather than four: the per-publisher hourly cap
(`SERVE_CAP_PER_HOUR` 12), the per-offer `BACKFILL_LIMIT` (4), and the `AirBucket.BRIDGE` share of the
airtime window, which is the one that actually bites.

Recording it and not consulting it is deliberate, not a half-measure: the record is what stops a *live
fan-out* inside the window duplicating what the bridge just queued. The asymmetry is the whole point —
the fan-out is speculative and should back off, the backfill was asked for and should not.

The alternative a reader reaches for first is **not recording `sigSeen` on a fan-out when no board has been
heard** (`boardsHeardAt` empty). It is tempting because it targets the empty-sky case exactly and leaves
in-flight suppression untouched everywhere else. It is not this, for two reasons. It is a proxy — having
heard *a* board is not having been heard *by* the board that matters, so it fixes the demonstrated case and
leaves the general one (heard by a neighbour, not by the far gateway) intact. And it makes the dedup's
meaning depend on radio state at record time, which is exactly the kind of coupling that made this bug hard
to see. It stays available as a follow-on if duplicate air ever shows up in a soak.

## What it costs, and what it does not cover

The cost is at most a duplicate frame on the air: a far gateway whose OFFER was assembled seconds before our
live fan-out will be served a copy of what it is about to hear anyway. Bounded by the three limits above and
dropped by the receiver's SeenSet, so it costs bytes, never correctness — the same trade `ForwardSync.onDigest`
already makes for a stale digest.

**Not covered: `reofferOne`.** The ADR 039 re-offer on first hearing a peer keeps its `sigSeen` refusal, and
that is correct rather than an oversight — a first hearing is not evidence the peer lacks anything, so
without the dedup it would re-send on every 45-minute linger regardless. A DM caught by the same empty-sky
trap is repaired by the bridge instead: DM-form frames are backfill candidates at `backfillRank` 1.

**Not covered: latency.** The repair is still passive and still Trickle-paced. Nothing pushes on re-entry —
the far pocket's gateway must publish an OFFER (`LoraGossipPolicy`, 5 → 15 min doubling, transmitted in the
interval's second half) and we serve in reply. Room posts rank **last** in `backfillRank` behind profiles and
DMs, inside a four-frame allowance, so a busy far pocket can still crowd one out for several rounds. This ADR
removes a hard silence; it does not make the room fast.

The regression is `LoraBridgeTest.aRoomPostFannedOutToAnEmptySkyIsStillBackfilledInsideTheDedupWindow`,
which asserts the whole exchange completes inside `SIG_TTL_MS` so it cannot pass by simply waiting the
window out. `theBridgeStillServesAProfileTheFanOutHasStoppedReOffering` lost the `advanceTimeBy(SIG_TTL_MS)`
it used to need to step around this gate, and now covers both exemptions. Both fail against the previous
`serveOne`.

Device verification on the two-board lab rig is owed.
