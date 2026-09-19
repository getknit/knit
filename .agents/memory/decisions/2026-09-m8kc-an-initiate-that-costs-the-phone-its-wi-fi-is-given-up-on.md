---
id: "2026-09.m8kc"
slug: an-initiate-that-costs-the-phone-its-wi-fi-is-given-up-on
title: "An initiate that costs the phone its Wi-Fi is given up on"
date: 2026-09-19
topics: [wifi-aware, reliability, mesh]
---

# ADR 2026-09.m8kc — An initiate that costs the phone its Wi-Fi is given up on

Status: Accepted (2026-09-19)

**What was observed.** On the lab Pixel 3 (blueline, API 31, WCN3990) every Wi-Fi Aware data path Knit
*initiates* fails, and the attempt costs the phone its Wi-Fi. `dumpsys wifi` on 2026-09-19 lists eight STA
disconnects between 01:48 and 03:46, every one `local_gen=true reason=3:DEAUTH_LEAVING` — the phone's own
driver tearing its STA down, not the access point and not RF — and the four the logcat buffer still covered
each landed within 40 ms of a Qualcomm HAL `NDP Cmd Type 0xa` confirm indication (`Response code 1`, REJECT):
the firmware finishing a data-path negotiation Knit had started 30–100 s earlier, well past the transport's
15 s `HANDSHAKE_TIMEOUT_MS`. It happened on a 5 GHz STA and on a 2.4 GHz one. Each drop then silently killed
the app's Aware client (ADR 2026-09.bgk3's addendum, work item #77) and discovery went blind until something
re-attached. The data paths never form at all — the Pixel 9's firmware counted `Num Data Path Request Events
0` across some twenty Pixel 3 initiates with the phones adjacent — most likely because the Pixel 3's framework
holds every peer's publish instance id byte-swapped (`16777216` for a `mPubSubId` of 1), so its requests carry
Publish ID 0 and the peer's firmware discards them. That lives in `system_server`'s peer table behind the
opaque `PeerHandle`; nothing in the app reaches it. Work item #78.

The Pixel 3 initiates because it holds the largest node id in the lab (the `localNodeId > nodeId` tie-break),
and `NanConnectPolicy` paces a failing handshake to a minute and never gives up. On this class of phone that is
a Wi-Fi drop a minute for as long as Knit runs — a user watching their Wi-Fi flap, whoever's fault it is.

**What it was not.** A `NanConnectPolicy` streak. The P7/P8/P9 fleet runs long fast-fail streaks against a
wedged responder (the 2026-09-07 watchdog livelock, `NanWatchdogPolicy`) with no STA harm whatever, and
stopping their initiates would strand syncs that the next session cycle would have carried. The evidence here
is not that our initiates fail — they fail for many reasons — but that **our own Wi-Fi drops when they do**.

**What changed.** `mesh/wifiaware/NanInitiatorPolicy` (pure, JVM-tested) beside the other three policies,
fed two facts the transport now has:

- **Our STA dropping after an initiate of ours.** The transport registers a passive
  `ConnectivityManager.registerNetworkCallback` for a plain `TRANSPORT_WIFI` request (never a `requestNetwork`;
  `mesh/wifiaware/` is already the allowed importer) and hands `onLost` / `onAvailable` to the policy. A
  **blip** is a loss followed by an available within `BLIP_MAX_MS` = 15 s — the field drops re-associated in
  four; a phone walking out of range does not come back in fifteen. A blip is a **strike** when its loss fell
  within `COINCIDENCE_WINDOW_MS` = 120 s *after* an initiate of ours that has not been linked since. The
  initiate need not have been reported failed: a drop in the first seconds of a handshake fails it
  milliseconds later, but the strike is judged when the Wi-Fi comes back, so requiring `failConnect` first
  would miss exactly that case. One initiate earns at most one strike.
- **A successful initiator link** (`registerConn` with a per-peer callback) as the one refund: the NDP path
  works on this hardware, so any coincidence was noise.

At `STRIKES_TO_LATCH` = 3 with no link between, the initiator role is **held**. Nothing is torn down: the
responder, discovery, cues, `fastFanout` / `fastSend` all keep running, nearby phones still connect to this
one, and Bluetooth carries custody (the reaudit already showed a stranded DM reaching its target by custody
relay). The hold acts through exactly one choke point — `digestSyncWanted` and the new `bulkSyncWanted`,
where the BLE `suppressed` set is already folded in — so every admission site (`driveSync`, `initiateOwed`,
`initiateOwedToReachable`) *and* every recovery site (`anyReachableSyncOwed`, `needsRediscovery`,
`rediscoverDelayMs`, `superviseLink`'s contended cut, `teardownPeer`'s recycle) stops seeing a sync it would
have to initiate. `PeerFacts.initiator` stays the tie-break, so `needsIcmRelight` (which reads `!initiator`)
does not start relighting ICM for held peers. `expectBulkTransfer` refuses a held peer outright, so a large
attachment falls back to Bluetooth at once instead of waiting the composite's 10 s grace for a link that will
not come.

The hold is **journaled** (`data/settings/NanInitiatorJournal`, on `SettingsStore`) under the same build + ROM
stamp as the attach give-up (ADR 055): `MeshService` is `START_STICKY`, and a fresh process must not re-learn
the hold by dropping the user's Wi-Fi three more times; a new build or a flashed firmware re-arms on its own.
Once a day (`PROBE_INTERVAL_MS`, on the **wall clock** because it is persisted and must survive a reboot; a
stamp in the future counts as due) `driveSync` alone takes one ordinary initiate under the hold — the
recovery sites keep the held view, so the probe window never runs the watchdog's owed clock. A probe that
links refunds everything; one that drops the Wi-Fi again is judged `AlreadyHeld` and costs one drop a day
instead of one a minute; one that merely fails keeps the hold.

**The refunds.** Only an initiator link, the user's "Try again", and the stamp. Never the Aware availability
edge (a blip produces one — the loop ADR 055 closed on the attach side), `heal()`, `stop()` (a mesh restart is
stop + start; `stop()` drops only a pending loss, because a blip cannot span the watch), a fresh session, or
the Wi-Fi coming back. Two events are voided as **not evidence**: `pause()` (the radio lent to our own Wi-Fi
Direct group, which can blip the STA on some chipsets — and a large transfer follows a bulk initiate within
the window as a matter of course) and Aware going unavailable *other than on our own session cycle* (a Wi-Fi
toggle takes the STA and Aware down together, where the Pixel 3's fault is the silent kind; but on the Pixel
3 the cycle is the responder's recovery from the very drop being judged, so it must not void it).

**Surface.** `MeshTransport.initiatorHeld` (a diagnostic-only per-plane flag on the `radioContended` pattern,
default never) rides `TransportStatus.initiatorHeld` into Diagnostics: an "on hold" tag on the Wi-Fi Aware row
and a section under Transports that says what happened and why nearby phones still connect, with "Try again
now" → `MeshTransport.releaseInitiatorHold()` through `MeshController` and the composite. `TransportHealth`
is unchanged — the plane *is* healthy. The alternative, Diagnostics writing the journal and the transport
observing it (the `ModelLoadGuard` route), was rejected: the transport writes the same record asynchronously,
and a Preferences DataStore re-emits the whole map on any unrelated write, so a collector could read `NONE`
between the in-memory latch and its own `edit {}` landing and unlatch a fresh hold. One writer.

**What it costs.** A held node's syncs to smaller peers ride Bluetooth or the relay; if it is the only bridge
to a pocket, that pocket waits for custody. The *smaller* peer still marks a bulk transfer toward a held node
and waits its `BULK_GRACE_MS` for a link the held node will not raise — there is no wire signal for the hold,
and the Pixel 3 never formed an NDP anyway, so nothing regresses. Three Wi-Fi toggles inside two minutes of
initiates can false-hold a healthy phone; it heals on the daily probe or "Try again", and the row says why.
A process death between strikes re-learns at most two drops (strikes are memory-only). The trap: a new
admission or recovery site that reads `digestTracker.reconcileWanted` or `bulkWanted.isWanted` directly
instead of through `digestSyncWanted` / `bulkSyncWanted` sees a held peer as owed — and with the hold
journaled, a Tier-2 self-kill on that owed clock would come back on restart and kill again. Route every
"is a sync wanted" question through those two.

What keeps this true: `NanInitiatorPolicyTest` (the Pixel 3 night replayed to the third drop, the wedged-
responder streak that never latches, the blip and window edges, the refund and the two voids, the probe
cadence and the clock that went back), `CompositeMeshTransportTest` (the flag rides `statuses`, the release
fans out), `DiagnosticsScreenContentTest` / `DiagnosticsViewModelTest` (the tag, the section, the button).
The `…debug.STATE` line carries `init=<strikes>/3` or `init=held probe=<m>`; `…debug.NANINIT` dumps the
policy and injects a blip, a release or a due probe.

**Left open.** ADR bgk3's follow-up — re-attaching directly when the STA drop kills the Aware client — now has
its signal in `onStaLost`, but it is a recovery for a silent client death seen only on blueline, and a
session cycle on every healthy phone that walks out of AP range is a cost the P7/P8/P9 fleet should not pay
for it. Parked in `roadmap.md`.

**Device trial, 2026-09-19 12:52–14:17, Pixel 3 (blueline, API 31), Bluetooth off, Doze lifted (Aware is disabled
in deep idle on an unexempted phone — that, not the fault, is why the P3 read `Unavailable` for the hour before).**
The fault reproduced naturally once in 26 initiates / 7 REJECT confirms toward the Pixel 9, far rarer than the
night before: at 13:47:55 `ClientModeImpl: Leaving Connected state`, the HAL's `NDP Cmd Type 0xa` / `Response
code 1` 17 ms later, our `Wi-Fi network lost (153)` 5 ms after that, `CONNECTED [154 WIFI]` at +540 ms, and the
transport logged `strike 1/3` — the real `NetworkCallback` path, judged as the network came back. The Aware
client died the same way as before (`no client exists for clientId=14950` at +7 s) and bgk3's responder curve
cycled the session at the fifth verdict. Strikes 2 and 3 were injected (`NANINIT --ez blip true`) 13 s and 1 s
after real initiates: `strike 2/3`, then `holding the initiator role` at 14:06:40. Under the hold, 5.5 min: zero
`initiating to`, zero `sync owed` episodes, zero NDP confirms, the STA disconnect count flat at 16, the state line
`disc=[ijeeg…] reach=[…] wanted=[]` (discovery up, the held peer not owed). `am force-stop` + start: the new
process logged `initiator role held under this build/ROM … probing daily`, `latched:true`, strikes 0 (memory-only,
as designed), the journal on disk `22:google/blueline/blueline:12/SP1A.210812.016.C1/…`. `--ez probe true`: the
next tick with a peer logged `daily initiator probe: one initiate to 24sd… under the hold`, consumed it (`probeInMs`
back to 24 h) and re-journaled; a blip inside its window logged `Wi-Fi dropped again during the daily initiator
probe — staying held`. Diagnostics (cold-started with `demo_route diagnostics`) showed the `on hold` tag on the
Wi-Fi Aware row and the section; tapping "Try again now" logged `initiator hold released by the user`, cleared the
journal stamp, removed the section, and the next discovered peer was initiated to. Not exercised on hardware:
the initiator-link refund (the P3 has never formed an NDP) — it is the same three assignments the unit test
pins. Left released on the lab P3, so its next three natural drops re-hold it on their own.
