---
id: "2026-10.jend"
slug: a-meshcore-board-floods-only-the-nearby-room-and-keeps-dms-to-its-neighbours
title: "A MeshCore board floods only the Nearby room and keeps DMs to its neighbours"
date: 2026-10-18
topics: [lora, meshcore, airtime]
---

# ADR 2026-10.jend — A MeshCore board floods only the Nearby room and keeps DMs to its neighbours

Status: Accepted (2026-10-18). This is the scope for a MeshCore board kind on the LoRa plane, decided from a two-board
spike (MeshCore companion firmware v1.18.0 on two Heltec V4s). Nothing is built yet. It sits beside ADR 038 / 039 /
045 / 067, which stay the Meshtastic plane's.

**What was observed.** MeshCore (MIT, the same boards as Meshtastic, not air-compatible with it) exposes a companion
protocol over BLE (Nordic UART) that can carry Knit frames as `GRP_DATA` packets on a private channel. The spike
measured it end to end:

- **Payload:** 165 B per packet on air, but **164 B over BLE on ESP32 boards**. The firmware caps the ATT MTU at 176,
  and a 174-byte receive frame reaches the phone one byte short with no error. A full packet is 181 B on air and takes
  **633 ± 17 ms** at the US preset's SF7 / BW62.5 / CR5: the LoRa formula plus 5.5 %, about 1.85× Meshtastic
  LongFast's payload rate, at shorter range.
- **Frame sizes:** the DM ✓✓ and steady sealed reactions go from one packet to two, initial DMs and the full profile
  from two to three. Frames that need three packets on Meshtastic (≥ 509 B) don't fit `FastFrameCodec.MAX_PARTS`.
- **Control:**
  - the board holds about 16 queued packets and refuses the rest at once (`TABLE_FULL`);
  - there is no TX-done push, but polling `GET_STATS` gives queue depth, completions and airtime ground truth at no
    air cost;
  - the inbox holds 256 frames in order, and the drain pops before it replies, so a lost reply is a lost frame;
  - every response goes to every connected interface;
  - an idle companion transmits nothing;
  - a board without the Knit key hears Knit packets and hands its app none of them.
- **Dedup:** a packet's identity is its type plus payload. Channel encryption is AES-ECB with no nonce, so a
  byte-identical retransmission is dropped by every node still holding the first copy. Seen live in the spike.
- **Floods:** a sender can't bound a flood. Each repeater enforces its own hop limit (64 by default) and may scope or
  deny unscoped floods by region. A direct route through a named repeater borrows exactly one hop.

The first reading was "never flood on a shared frequency": zero-hop only, floods only on a dedicated slot as ADR 067
keeps Meshtastic's. That gives up the one thing MeshCore networks are good at, and it is stricter than MeshCore's own
norm: its public-channel chat floods network-wide, so a Knit room post costs a MeshCore network what a MeshCore public
message does. The cost that matters is Knit's **automated** traffic, not people's posts.

**What changed** (the scope a MeshCore board kind is built to):

1. **On the shared regional frequency, only two things flood:** broadcast room `chat` and broadcast `reaction` that a
   person on this phone originated, and our own **lean** profile (amended 2026-10-19, below), only right before our
   first flooded post, at most once per N hours.

   Room posts are signed, and a receiver without the author's profile parks the post (`PendingInbound`) and then
   drops it, so posts flood only as far as their author's profile did. Tying the profile to the first post, instead
   of the ordinary beacons (`ProfileFrameSource`'s session-start floor and ADR 039's first-hearing gap), avoids a
   3-packet network-wide flood each time a far peer is first heard, which on a flood network is constantly.
2. **Everything else is zero-hop**, or one hop through a named repeater the board has heard: every DM-form frame (DMs,
   sealed receipts and reactions, resets, seeds, escalated group ticks), the room's ✓✓ ticks, all machine traffic
   (`LoraCtl` offers, backfill, re-offers, beacons), and room frames relayed for someone else.
3. **Floods go scoped to the board's default region when one is set**, else unscoped. Where repeater operators scope
   or deny unscoped floods, an unscoped Knit room shrinks back to zero hop, and the setup screen says so.
4. **The flood class is budgeted as every repeater's airtime.** Each repeater retransmits each flooded packet once,
   so its Knit load equals Knit's flooded airtime. Capped at today's `AirBucket.PUBLIC` share (≈ 27 s an hour at a
   45 s / 15 min allowance), that is ≈ 0.75 % duty per repeater. Booking floods outside that bucket is the trap.
5. **DM-form traffic is gated on direct reach, not reach.** A flooded post makes its far author "reachable", which
   would arm `reofferTo` and the DM probe (ADR 2026-10.a4pm) toward a peer no zero-hop frame reaches. The plane keeps
   a second set fed only by frames received at hop count 0 (the receive frame carries flood hops vs direct), and
   `reachGate` reads that set on this board kind.
6. **The room's ✓✓ stops at one hop.** `AckSync`'s owed-tick retries toward an author heard only by flood stop on
   this plane instead of spending air for a day on ticks nobody hears.
7. **Every transmission carries a byte that varies per send** (a fresh fragment id is enough). Otherwise re-offers,
   backfill and retries are eaten by dedup network-wide.
8. **Floods off the shared frequency stay a debug-only bargain**, as ADR 067 kept the dedicated Meshtastic slot
   until its 2026-10-22 amendment shipped it; this one has not moved.

**What it costs.**

- **DMs reach only direct neighbours** (or one named hop), narrower than Meshtastic's three borrowed hops and
  MeshCore's own flood-then-path DMs. Knit can't copy MeshCore's path learning: the receive frame carries only the
  hop count, and the raw RX log, which carries the path, drops full-size packets.
- **"Nearby" grows from a neighbourhood to a whole MeshCore network,** potentially 100 km or more, on a channel whose
  key is public. The room is cleartext by design, but its copy has to say how far it now reaches.
- **A far reader who joins mid-conversation may miss posts** until the author's next flooded profile.
- **Long posts fail more often far away**, since each fragment floods independently.
- **The fleets split:** a Knit pocket on a MeshCore board and one on a Meshtastic board never hear each other, except
  through a phone paired to one of each.

Owed before release:

- the board-kind seam (`MeshtasticLink` made board-neutral);
- a MeshCore-only reassembly limit of 4–5 parts (not a wire break: only new builds ever receive from a MeshCore
  board);
- a client that matches each response to its request, survives a lost drain reply, and rejects a frame whose length
  byte exceeds what arrived;
- a pacer driven by the board's queue depth from `GET_STATS`, not the fixed 3 s `LoraPacePolicy.MIN_GAP_MS`. With
  the gap, a MeshCore board is gap-bound at 21 % duty, carrying about 197 KB of payload an hour against LongFast's
  260 KB on a dedicated slot, and every extra fragment costs a full gap (a ✓✓ takes 6 s instead of 3). Paced on
  feedback under the same 50 % allowance, it carries about 466 KB an hour, and the board's own 50 % bucket
  (`airtime_factor`) would then bind as well;
- a registered `data_type` (the spike used the development range);
- the region UX;
- trials: the one-named-hop route through a third board as a repeater, and real-range loss outdoors. Indoors the link
  never got weak enough to lose more than one packet in 250.

**Amended 2026-10-19 (the full profile goes only to a neighbour that asks for it).** The prekey is read at one
moment only: when a session starts (`RatchetEngine.initiate`, which a reset and every reset after a restore are too).
A confirmed session's epochs are based on the peer's own, so a contact with a working session never needs it. Room
readers need even less: the name and the key a post verifies against. The size test measured the cost on this board
(`CoordinationPlaneSizeBudgetTest.theLeanProfileOnTheMeshCoreHop`, ids elided by `IdElision`): the full profile is
435 B and 3 packets, of which the prekey is 107 B and the board binding (`loraNode`, `loraKey`) 68 B, while a lean
profile (name, key bundle, version) is 168–196 B and 2 packets at any name length. So:

1. **What floods is a lean profile**: name, key bundle and version, nothing else. It is a signed object of its own
   that never replaces a full profile and is never refused against one: a newer lean profile must not erase a
   prekey, and an older full one fetched after it must still be taken.
2. **The full profile goes zero-hop, and only in answer to a `keyreq`** (`KeyReqContent`, which this board kind
   carries zero-hop although `LoraFramePolicy` refuses it on the Meshtastic plane). It is never sent unasked, on a
   first hearing or beside a message.
3. **A phone asks only when it is about to start a session and holds no usable prekey**: none on file, or one from a
   profile whose `version` is more than four weeks old, since a prekey rotates weekly and is kept for the current
   week plus four. It asks when the person opens the thread or starts typing, not on sighting, so most of the round
   trip hides behind the typing; a fresh prekey on file starts the session with no request at all.
4. **Only the author answers over LoRa**, or a holder answers after a random wait and stays quiet once it hears the
   answer. Several neighbours each sending three packets for one request is the trap. Requests are rate-limited per
   peer.

The room is not served this way: a far reader cannot reach a far author zero-hop, and a flooded request plus a
flooded answer per reader costs more than one push, so the lean profile still floods with the first post. What gives
way is a profile edit on a LoRa-only link. The name rides the lean profile, and a contact with a session already gets
the presentation fields sealed inside DMs. A stranger with no session does not see a status, avatar or open-to-chat
change until it next asks for the full profile.

Owed with it: the lean profile object and its replacement rules, `keyreq` on this board kind's frame policy with the
answer suppression, the request trigger in the DM and reset paths, and a lab scenario where two neighbours start a
session from a lean profile alone.

The invariant that keeps this true is a policy test that classifies every frame type into flood, zero-hop or refused
for this board kind, like `LoraFramePolicy`'s for the Meshtastic plane.
