---
id: "2026-09.uc8p"
slug: a-profile-is-re-flooded-to-a-peer-once-per-seen-window
title: "A profile is re-flooded to a peer once per seen window"
date: 2026-09-20
topics: [mesh, battery]
---

# ADR 2026-09.uc8p — A profile is re-flooded to a peer once per seen window

Status: Accepted (2026-09-20; `MeshManager.watchReachable`'s `flooded` memo, work item #73)

Found by the 2026-09-18 battery review, from the code. `MeshManager.watchReachable` floods our own profile
for every newcomer to `MeshTransport.reachable` — the NAN-only bootstrap, where a small profile rides the cue
plane to a peer that never raises a data path — coalesced to one origination per `PROFILE_REFLOOD_MIN_MS`
(30 s). The frame id is stable (`profile-<me>-<stamp>`, `ownProfile()`), so every receiver holds it in its
router `SeenSet` for `SeenSet.DEFAULT_TTL_MS` (ten minutes) and drops a second copy at the door. A Wi-Fi Aware
edge peer whose sightings straddle `REACHABLE_LINGER_MS` (150 s) leaves and re-enters `reachable` on every
lapse, arrives as a "newcomer" each time, and so cost the whole mesh — the flood, the `fastFanout` copy, the
LoRa fan — one profile every 30 s that nobody kept. Not a correctness fault; airtime.

## What changed

A memo beside the collector's `known` / `lastFloodAt`: a `SeenSet` keyed on **the frame id and the peer**
(`refloodKey`), window `SeenSet.DEFAULT_TTL_MS`. Newcomers are filtered against it after the 30 s floor and
before the origination; only the owed ones are memoed, and only when the flood actually goes out. Three
consequences the key shape buys:

- A linger flap inside the window is a newcomer the memo already answers, so nothing is sent. The receiver
  would have dropped it — the same invariant ADR 2026-09.6nmy states for the link memo: the window is the
  router `SeenSet`'s, so every suppressed send is one the far end would have refused.
- A profile **edit** mints a fresh id (`broadcastProfile`), and a fresh id is unmemoed for every peer, so a
  flapping peer that was out of range for the edit's flood is served the new frame on its next epoch. Keying on
  the peer alone would have held the stale frame back for up to ten minutes on the one plane that has no
  custody exchange to fall back on.
- A newcomer the 30 s floor skipped is never memoed, so it stays owed on its next epoch — today's behaviour.
  Memoing at filter time would have turned a skipped burst into a ten-minute silence.

The memo is local to the collector, so a session restart floods once for everyone, as it always did.

The alternative a reader reaches for first is a **global** gate — don't re-flood the same id more than once
per window, whoever arrived. That drops the bootstrap for a genuinely new peer that appears within ten
minutes of the last one: on NAN alone it never links, custody never diverges into a link, and it waits for
the 12 h republish or a link that may never come. Per-peer is what the bootstrap needs.

## What it costs, what it does not cover, and the trap

One `SeenSet` (default 4096 keys) per session, a string key per (frame, peer). `pushProfileTo` on link-up
(`watchNeighbors`) is untouched — a fresh link is a fresh custody digest exchange, and that push is per link,
not a flood. LoRa's own beacon floor (`LoraMeshTransport`, ADR 039 §8) is a separate gate on a separate
plane. Nothing two nodes exchange changes, so there is no lab scenario; the regression is
`MeshManagerTest.aLingerFlapDoesNotRefloodTheProfileInsideTheSeenWindow` and
`aProfileEditIsRefloodedToAFlappingPeerOnce`, which count raw wire copies of one id (`profileCopiesOnWire`)
because the rig's `floodedProfiles()` collapses by id and would hide a re-flood.

The trap: the memo's window must stay `SeenSet.DEFAULT_TTL_MS`. Longer, and a peer whose router has already
forgotten the id is refused a copy it would keep; shorter, and the flap is back at that cadence.
