---
id: "2026-09.6st4"
slug: the-heal-basket-runs-what-is-due
title: "The heal basket runs what is due"
date: 2026-09-21
topics: [mesh, battery]
---

# ADR 2026-09.6st4 — The heal basket runs what is due

Status: Accepted (2026-09-21; `MeshService.onSignificantMotion`, `MeshManager.heal` / `HealFloors`,
`ForwardStore.liveGroupChatFrames`; device trial owed — see the end)

Work item #63, the last "worth doing next" of the 2026-09-18 battery review. `MeshManager.heal()` pokes the radios and
launches a basket of fourteen maintenance steps, and it is called from four places with no floor between them: the
15-minute `ELAPSED_REALTIME_WAKEUP` heartbeat, every `TYPE_SIGNIFICANT_MOTION` trigger (one-shot, re-armed after each
fire, so a walk fires it every 30–60 s), every app resume (`KnitApp`'s `ON_RESUME` observer) and Diagnostics' Rescan.
Two of them routinely land within a second of each other — the phone leaves the pocket (motion), the app opens
(resume) — and ran two baskets at once. Per call, on an idle phone with no peer, no group and no session:

| step | what it cost |
|---|---|
| `replayUndeliveredGroupCustody()` | `SELECT *` over the whole live forward store and a CBOR decode of every row, filtered to group chat from others **in Kotlin** — on a phone carrying a hundred DMs and room posts, a hundred decodes to find nothing |
| `ratchet.sweep`, `groupRatchet.sweep`, `groupRoots.sweep` | nine SQLCipher statements, unconditional, enforcing windows of 48 h and up |
| `broadcastSealedProfile()` | six settings reads and the sealed payload built before the one query that says whether any session is owed it |
| `transport.heal()` | channel pokes — but BLE's `scanWake` cuts the idle short, so each motion trigger bought a scan window; NAN's re-arm was already floored at 15 s (ADR 2026-09.kb68) |

Ninety-six heartbeats a day is the floor; a walk was the multiplier. The remaining steps were already cheap: the
key/ack/receipt retries and four of the five TTL sweeps are in-memory maps with an empty early return, the prekey
rotation reads a cached file, the profile republish and the root mint are behind a stamp or the plane flag, and the
DataStore reads are in-memory after the first load. `forwardSync.sweepExpired()` (a DELETE, an indexed `(id, expiresAt)`
SELECT, the digest rebuild) stays on every heal on purpose: the 10-min prune loop is a coroutine `delay`, which sleeps
with the CPU under Doze, so the heartbeat's sweep is the one that runs on a phone in a drawer.

## What changed

**The motion trigger heals at most once a minute** (`MeshService.onSignificantMotion`, `MOTION_HEAL_FLOOR_MS`). The
floor is measured from the last heal it let through, on the service's injectable clock, and the sensor is re-armed
either way. A move can usefully buy one heal: a peer that came into range mid-walk is found by the next. The heartbeat,
the resume and Rescan keep their own unfloored path — the issue's ask, and necessary: a resume is what lifts the location
app-op a Wi-Fi Aware plane on API 29–32 is waiting on (`retryOffScreenBlocked` rides `heal()`, ADR 2026-09.535d) and buys
the lonely discovery loop its one aggressive re-arm (`healRearmOwed`, kb68). kb68's trial step 3 now reads "one
`heal=true` re-arm per motion trigger, ≥ 60 s apart".

**One basket at a time.** `heal()` keeps the launched `Job`; a heal that finds it active pokes the radios and returns.
The overlap it closes ran the check-then-act steps twice — `mintGroupRootsIfDue`'s find → `mintDue` → upsert on the
root version, `rotatePrekeyIfDue` — with nothing between them but luck.

**The retention sweeps and the custody replay run once an hour** (`HealFloors.RETENTION_SWEEP_MS`,
`HealFloors.GROUP_REPLAY_MS`, memos on the manager's clock, stamped by the start's own unconditional pass). The three
sweeps retire epoch privs and skipped keys past the DM ratchet's PFS window, group chains past the group window and
rotated-away roots past their drain — 48 h and up, so an hour's lag keeps every guarantee. The heal-time replay is the
net under two instant paths that already exist and are synchronous: a seed adopted replays that `(group, sender)`
(`InboundPipeline.applyCtl` → `replayGroupCustody`), a roster pinned replays the parked seed DMs (`replaySeedCustody`);
what the backstop catches is the race between a frame landing in custody and its seed's replay having already run, and
hourly is enough for a race.

**The replay reads only what it can use.** `ForwardStore.liveGroupChatFrames(excludingSender, now)` — a defaulted seam
method, so the fakes need nothing — is `SELECT … WHERE groupId IS NOT NULL AND type = 'chat' AND senderId != :me AND
expiresAt >= :now` on the Room side (`ForwardDao.liveGroupChatRows`, `groupId` indexed). A phone in no group answers
with zero rows and decodes nothing; a phone in groups decodes its group frames only. Both replay paths use it.

**Who is owed comes first.** `broadcastSealedProfile` asks `ratchet.exportedRoots()` and filters by the per-peer
`sentProfileVersions` memo before reading the avatar hash and building the payload. Same sends, same memo writes.

## The alternatives, and why not

The issue suggested **an existence check before the sweeps** ("no session or chain exists"). A receive-only group member
holds `group_recv_chains` and no send chain, so `sendChainGroupIds().isEmpty()` would skip its recv-chain TTL; and
`deletePeer` is four statements outside a transaction, so a crash between them leaves skipped-key and epoch rows with no
session that a session-gated sweep would never reap. The floor keeps every table's guarantee and saves for every phone,
idle or not. It also suggested **a seed/roster dirty flag** for the replay, with the hourly heal as the backstop. The
events that would set it are the events whose instant replay already ran, so the flag would re-run at the next heal
what just ran; the bounded query removes the cost at its root and the hourly floor is the backstop the issue asked for.

A single floor on `heal()` itself — "skip if the last one ended under a minute ago" — was the reader's first reach and
the wrong one: it would have withheld the radio poke from a resume, and kb68 and 535d both depend on that poke.

## What it costs, what it does not cover, the traps

A heal inside the hour does not sweep or replay; the first heartbeat after a start (15 min in) does neither, since the
start stamped both. `LabNode.heal()` still awaits `healsCompleted` — the basket runs to its last line with the floored
steps skipped, so nothing in `mesh/lab` changed; a scenario that *needs* the sweep or the replay from a heal advances
`lab.clock` past the floor first (every existing caller already jumps ≥ 12 h). A test rig that lets the start's seeding
coroutine die at `finishRestore()` (a relaxed `restorePending` mock) never sees the start's sweep either — the profile
watcher's `ownProfile()` seeds the row in its place, which is how `MeshManagerTest`'s other rigs never noticed;
`stubHealState()` is the fix. The motion floor is per service instance: a sticky restart heals on its first trigger.

Pinned by `MeshServiceMotionTest` (the floor is from the last heal, not the last trigger), `ForwardDaoTest`
(`liveGroupChatRows`: group chat from others, live, non-chat and own sends out), and `MeshManagerTest`'s two heal cases
(hourly steps once past the hour; three heals into a parked basket run one basket and reach the radios three times).
`TimeLabTest`'s three day-jump heals and `InternetPlaneLabTest`'s pair exercise the real basket end to end.

**Device trial owed** (a debug build, `adb logcat -s MeshService MeshManager`; disconnect network adb and go airplane
before any battery number — the host's adb keepalive alone wakes the AP every 1.2 s): walk two minutes — one
`motion: heal` per ≥ 60 s with `floored` lines between; open the app twice within a minute — the second open still
logs NAN's `re-arm` / BLE's wake and no second basket; 45 min screen-off idle — three heartbeats, one retention sweep,
one replay.
