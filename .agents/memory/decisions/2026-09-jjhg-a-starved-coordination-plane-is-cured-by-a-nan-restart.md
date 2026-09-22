---
id: "2026-09.jjhg"
slug: a-starved-coordination-plane-is-cured-by-a-nan-restart
title: "A starved coordination plane is cured by a NAN restart, and the watchdog reads acks to know"
date: 2026-09-21
topics: [mesh, nan, recovery]
---

# ADR 2026-09.jjhg — A starved coordination plane is cured by a NAN restart, and the watchdog reads acks to know

Status: Accepted (2026-09-21)

Work item #81. The 11-hour soak `soak-20260921-bursts` (p7 Pixel 7, p8 Pixel 8, p9 Pixel 9 Pro XL, main
`d3047c87`) caught the signature the August trio run had shown once: at the end of a chat burst a phone's
`nanMsgsAcked` goes flat and `nanMsgSendsFailed` climbs by the whole send rate — 25 to 100 per ten minutes —
and stays that way for hours. Three freezes (03:11, ~09:00, 11:32), each on a burst; the P9 frozen 4 h 56 m
while active and charging. Nothing in the app noticed: `onServiceDiscovered` kept re-firing 50–85 times an hour
on the frozen phones, `disc`/`reach` stayed full, every health surface read `Healthy`, and custody converged
regardless because Bluetooth and its side channel carried what the Wi-Fi Aware message plane dropped. The cost
was the fast path being dead for hours, the duplicate storms from the upper layers' resends, and the August
7 %/h drain on the phone retrying.

**What was observed, and what it was mistaken for.** The working theory was a stale `PeerHandle` — a cue
target whose peer had re-armed or rotated its address. The forensics say otherwise. `dumpsys wifiaware` on the
P9 an hour into its first freeze:

```
mSendQueueBlocked: true
mHostQueuedSendMessages: 50 entries (262498…262547, all clientId 1210 = Knit)
mFwQueuedSendMessages: [{}]
```

That is the framework's own send state machine deadlocked. In AOSP's `WifiAwareStateManager` a firmware
`FOLLOWUP_TX_QUEUE_FULL` response re-queues the message and sets the blocked flag, but the 10 s send timeout
is armed only off messages the framework still tracks in `mFwQueuedSendMessages`; with none tracked no timer
exists and nothing ever unblocks. Fifty sends then sit host-queued with no callback at all, and once the
per-uid depth (`MESSAGE_QUEUE_DEPTH_PER_UID = 50`) is reached every further `sendMessage` fails at once with
`INTERNAL_FAILURE`. Only a firmware transmit notification or `onAwareDownCleanupSendQueueState()` clears it.

Every later frozen snapshot showed a second state: queue idle, blocked `false`, and the state-machine records
reading `NOTIFICATION_TYPE_MATCH` → `COMMAND_TYPE_ENQUEUE_SEND_MESSAGE` → `RESPONSE_TYPE_ON_MESSAGE_SEND_QUEUED_SUCCESS`
→ `NOTIFICATION_TYPE_ON_MESSAGE_SEND_FAIL` about 4.2 s later, zero successes in 256 records, on p8 to a peer
whose subscribe-side `PeerInfo` carried p9's current NAN address. The firmware accepts every follow-up and
cannot deliver any of them, while it keeps matching the same peers' publish frames. Not a stale handle: a
freshly matched one fails the same way. What triggers it in the firmware after a burst is not in the record.

**What cured it, every time.** Each phone's NAN address changed only on a NAN restart across the whole day
(never on the 30-minute `mac_random_interval_sec`), which makes restarts easy to date from the peers' tables:

| phone | restart | evidence | acks |
|---|---|---|---|
| p9 | ~05:44 | Doze (`on_idle_disable_aware=1`), address 4059CD → C93C5B, new publish session | +360 in the dark window |
| p7 | 10:24:58 | `reattach()`, address 43E265 → 7F8EAE | +23 at 10:31, then +57, +26, +24 |
| p8 | 10:27:58 | `session cycle: tearing down`, address 39701A → E47988 | first inbound frame 14 s later |
| p9 | none after 05:44 | active and charging, no cycle, no re-attach | frozen 09:00 → 13:56 |

Knit is the only Aware client on every snapshot (`mClients` size 1), so our `session.close()` is the
framework's last-client detach — `disconnectLocal` runs `onAwareDownCleanupSendQueueState()` and defers a
firmware disable; the next attach re-enables NAN with a fresh address. That one lever clears both mechanisms.
The run's SUMMARY claimed p7 cycled at 06:45 and 07:30 without recovering; p7's logcat has no new publish
session between 02:51:55 and 08:56:37. The collector's reconnects replayed the 02:51:52 Tier-1 line into later
poll segments, and `poll.sh` counts the appended bytes. There was nothing to contradict the lever.

**What changed.** `WifiAwareTransport` now remembers every coordination-plane send by its `messageId` — with
the session it went out on — until the framework answers, through one `sendCoord` that the cue path and the
fast path share; the two `DiscoverySessionCallback`s stamp the last ack and count failures since it;
`noteReachable` stamps the last sighting. Every 30 s, beside `checkWedge`, `checkMessagePlane` hands those
facts to the pure `NanMessagePlanePolicy`, whose two verdicts are the two mechanisms: **swallowed** (the
oldest send unanswered for a whole watchdog tick, and no ack since it went out — three times the framework's
own timeout, so a healthy but slow burst cannot qualify) and **starved** (four or more failures since the last
ack, no ack for two heartbeats, and a peer sighted within 90 s — the sighting is what separates a dead plane
from a peer that walked away, which fails its cues too and is pruned at 150 s). The cure is Tier 1's
`sessionCycleWithSettle()`, paced on the same `lastReattachAt` so the two watchdogs cannot stack, never with a
live link (a deferred cycle spends no budget), three per episode, and the episode ends only when an ack lands
*after* it began — not on the cycle, and not on the beat the cycle's own NAN-down leaves the node unhealthy and
alone, because either would refund the budget every cycle, which is exactly the livelock ADR 2026-09.9dnk
closed. A spent episode goes quiet and earns one more set after 15 minutes, so a plane that stays dead costs at
most three restarts a quarter-hour. `MeshMetrics` gains `nanMsgPlaneStalledPeakMs` and `nanMsgPlaneCycles`;
the state line carries `msg=<unanswered>/<failsSinceAck>/<sinceAck>`; `…debug.NANMSG` reads the bookkeeping and
arms either signature (`--es fault swallow|fail|off`) so the cure can be watched on a phone without a burst.

The alternative a reader reaches for first is "acks flat over two polls" — the harness rule. It is the right
detector for a poller and the wrong one for the transport: it cannot tell a dead plane from a lonely node,
and it has no answer for the fifty sends that never call back at all. The two verdicts here are cheaper and
each names its mechanism. The other alternative, a per-peer in-flight window so a burst cannot fill the
firmware's eight-deep follow-up queue with sends the framework has already timed out, is the precondition for
the framework deadlock and is worth building — but it is prevention for one of the two mechanisms, it needs a
device measurement of its latency cost, and it does nothing for a firmware that has stopped delivering. It is
parked in `.agents/memory/roadmap.md`.

**What it costs, what it does not cover, and the trap.** A cycle is a NAN restart: about three seconds with
no responder and no discovery, a new address the peers learn on the next cue, and the responder re-filed. The
verdicts fire only on evidence a healthy plane never produces — a healthy node acks within a second of every
cue — so the steady-state cost is one map write per send. What this does not cover: a phone that is *not* the
sole Aware client (another app holding an attach keeps NAN up through our detach, and the queue cleanup then
never runs) — the Pixels here have none; if one appears, `nanMsgPlaneCycles` climbing with the plane still
dead is the tell, and the framework bug itself belongs upstream. Two traps. First, a send on a session we have
closed is a silent no-op in the framework (no callback ever), so `sendCoord` refuses it and `rearmSubscribe`
forgets the entries the closed subscribe strands — record one of those as in flight and it reads as swallowed
forever. Second, the episode must survive the cycle's own side effects: `!healthy` and `cueTargets == 0`
*hold* the episode rather than clear it; `NanMessagePlanePolicyTest.theCyclesOwnNanDownDoesNotRefundTheBudget`
walks the real cadence and is the regression. On hardware the check is the harness's `nan-outbound-dead`
rule: a burst night with no CRIT, and `coordination plane stalled … — cycling the session` followed by acks in
the log when one does fire.
