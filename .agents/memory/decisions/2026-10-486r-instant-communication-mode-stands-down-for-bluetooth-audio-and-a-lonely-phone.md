---
id: "2026-10.486r"
slug: instant-communication-mode-stands-down-for-bluetooth-audio-and-a-lonely-phone
title: "Instant Communication Mode stands down for Bluetooth audio and a lonely phone, and matches the Wi-Fi channel"
date: 2026-10-24
topics: [wifi-aware, battery, reliability]
---

# ADR 2026-10.486r — Instant Communication Mode stands down for Bluetooth audio and a lonely phone, and matches the Wi-Fi channel

Status: Accepted (2026-10-24; `mesh/wifiaware/NanIcmPolicy`; device trials A–E owed, see the end; the 5 GHz picks are
debug-only until trial A passes)

**What was observed.** GitHub getknit/knit#44: on a Pixel 8a (Android 17, GrapheneOS, Knit 2.9.0) Wi-Fi throughput fell
from about 250 to about 50 Mbps while Knit ran, and Bluetooth A2DP audio cut out in crowded places such as public
transit. Both stopped with Knit stopped, and the audio cutouts stopped with Wi-Fi off while Knit kept running. An Asus
Zenfone 8 (Qualcomm, Android 13) showed no drop. The attached log held only the app's system and Bluetooth lines, so
the cause is a reading of the code, not a measurement: Instant Communication Mode. Since the plane began, every
publish and subscribe config on API 33+ asked for ICM on 2.4 GHz (`INSTANT_BAND`), which parks NAN on channel 6 for
30 s per config. Bluetooth shares the 2.4 GHz front end, and a STA on any other channel loses airtime to it. The
Bluetooth plane already backed off while A2DP streams (its scan floor, feed boost and side scan); this plane never read
audio at all. And ICM was lit far more than its demand gating suggests: a lonely screen-on phone re-arms subscribe every
15 s and each re-arm lit ICM (ADR 2026-09.kb68 relaxed only the screen-off case), each significant-motion heal bought a
re-arm (ADR 2026-10.d44b put a lone walking phone at "ICM lit about half the time"), and every digest move's
`updatePublish` refreshed the 30 s clock. The Zenfone most likely answers false to
`isInstantCommunicationModeSupported`, so it never runs ICM.

**What the framework does** (AOSP `packages/modules/Wifi`, read 2026-10-24). The band argument takes only 2.4 or 5 GHz
and throws on anything else. `WifiAwareStateManager.getAwareInstantCommunicationChannel` maps 2.4 GHz to 2437 and
5 GHz to 5745 if the chip lists it among its usable NAN instant-mode channels, else 5220, else back to 2437; the answer
is cached once. The STA's own channel plays no part. A config asked for ICM counts for 30 s from its `mUpdateTime`, and
5 GHz wins the merge if any session of any client asked for it. The power profiles in `WifiAwareNativeApi` wake a
screen-on phone for every 2.4 and 5 GHz discovery window and a screen-off or idle phone for every 8th 2.4 GHz window
(about 4 s) with the 5 and 6 GHz windows off. So two phones need no agreement for Aware to work: the cluster's windows
are the meeting points. ICM only speeds up a pair whose phones sit on the same channel, and until now every Knit phone
picked channel 6.

**What changed.** One pure policy, `NanIcmPolicy.decide`, gives every ICM-enabled config its verdict (allowed or not,
the band, a reason), and both config builders and every `updatePublish` read it through `noteIcmVerdict`, so publish and
subscribe never disagree. First match wins:

1. a debug override (`…debug.NANICM --es band 2.4|5`) — allowed on that band, past rules 2–3, so a trial can light ICM
   on a lonely or audio-playing phone;
2. Bluetooth audio streaming (`BluetoothMeshTransport.radioContended`, wired through `di/MeshModule`) — off;
3. no cue target — off, except for one 30 s window after a demand-driven Tier-1 cure (below);
4. the STA on 2437 — 2.4 GHz, the STA's own channel;
5. the STA on 5745, or on 5220 with 5745 known unusable for Aware — 5 GHz (debug builds only);
6. a watch that is registered, settled and sees no Wi-Fi network — 5 GHz (debug builds only); an unregistered or
   unsettled watch is unknown, never "no Wi-Fi";
7. otherwise 2.4 GHz, today's behaviour.

The policy says nothing about *when* ICM is lit: that stays with the demand gating (`needsIcmRelight`, the re-arm, the
digest republish). Its one reach into timing is that the relight obeys an off verdict: `icmRelightDue` and
`rediscoverDelayMs`' ICM term read it, so audio playing neither fires the 25 s keepalive nor stamps it, and the audio's
end pokes the loop once so ICM can come back on the next tick. The STA frequency comes from `WifiInfo.getFrequency()`,
which the location redaction keeps, in `onCapabilitiesChanged` on the passive Wi-Fi watch `NanInitiatorPolicy` already
holds; the frequency map is kept only in the callback object's own callbacks, never in `onStaLost` / `onStaAvailable`,
so `…debug.NANINIT`'s blip stays on the initiator accounting.

Turning ICM off pushes nothing: the next config carries the verdict, and configs already sent lapse within 30 s. A band
change pushes one `updatePublish` (`maybePushIcmBand`), only while a config demand sent is under 30 s old, at most once
per such window, never under `icmKeepaliveBroken` and never with a link, handshake or accept live; otherwise the band
rides the next config. No `updateSubscribe` is used (this fleet has never run it), so 5 → 2.4 can take up to 30 s while
the subscribe's 5 GHz config is still fresh.

**The post-cure window.** Rule 3 would make the plane's own cures come back weaker than the state they cure: every
Tier-1 cycle (the wedge watchdog, the message-plane cycle, the responder give-up) runs while a sync is owed, produces
NAN-down, and NAN-down clears `cueTarget`. Before this change the re-attach's configs lit ICM for 30 s, which is what
let a dozing pair re-find each other at more than one window in eight. Now `sessionCycleWithSettle(postCure)` stamps
`postCureCycledAt` when one of those three callers cycles with a sync owed (the watchdog always, the other two on
`anyReachableSyncOwed()`); an attach within `POST_CURE_ATTACH_WITHIN_MS` (60 s) opens a 30 s window in which a lonely
phone's configs still ask for ICM. Audio still wins over it. It closes when the window ends or `cueTarget` fills, and
is never set by `onSessionDead`, `reattach`, the NAN-down edge alone, the pinned-responder cycle or `NANICM --ez on`.
The Tier-1 caps (three per episode) bound it.

**The 5 GHz picks are debug-only** (`fiveGhzRules = BuildConfig.DEBUG` in `di/MeshModule`, the one seam the flip
changes). A phone that picks 5 GHz is cross-band with every phone left on 2.4 and every earlier build: they share only
the 2.4 GHz windows, a screen-off phone attends one in eight of those, and whether firmware keeps a 5 GHz ICM channel lit
under the screen-off profile, which turns the 5 GHz windows off, has not been measured. That is the dozing-responder
sawtooth ICM was brought in to cure (e276c246). Release therefore asks for 2.4 GHz whenever it asks for ICM at all, and
reaches only rules 2–4 and 7.

**The alternatives.** Following the STA's band was the first idea, and the framework defeats it: a band is not a
channel, so a home router on 5 GHz channel 36 would get 5 GHz ICM on 149, which still switches channels on the same
radio and may cost more than 2.4 GHz on a chip that runs the bands apart. Only an exact channel match is free. Moving ICM
to 5 GHz while audio plays also spares Bluetooth, but it is cross-band with every 2.4 GHz peer and unmeasured with the
screen off, where turning ICM off is a behaviour the plane already knows. kb68 rejected "re-arming without ICM" as a
second code path through the wedge-prone session lifecycle; this is not that path. The re-arm, its cadence and its
lifecycle are unchanged, and only the config's ICM fields differ, exactly as every config on an API 31–32 phone or a
chip without ICM always has.

**What it costs.** A phone playing Bluetooth audio, and a lonely phone, discover and exchange at the windows' pace: about
0.5 s screen-on, about 4 s screen-off. Between a streaming, pocketed smallest node and its peers, with no BLE link, that
is the pre-e276c246 state; trial C measures it. A lonely phone meeting a newcomer finds it through the windows and
Bluetooth instead of an ICM dwell; trial D re-runs kb68's bounds. `BluetoothAudioMonitor.stop()` leaves `contended` as it
was, but it runs only in the composite stop that stops this plane too, and the Bluetooth plane's next start recomputes
it; it would matter only if the Bluetooth plane ever became switchable on its own. While audio plays, a screen-off
phone with no BLE link sends its follow-ups at one 2.4 GHz window in eight, which can trip the message-plane watchdog's
starved verdict (jjhg) and spend its Tier-1 cycles, whose post-cure window audio also overrides; trial C counts them.

**What keeps it true.** `NanIcmPolicyTest` pins every rule, the post-cure window, the multi-network pick (2437 first,
then 5745, then 5220), the unknown-versus-none watch, the lit-time meter, and, exhaustively, that `fiveGhzRules = false`
never asks for 5 GHz. The field log records `nan_icm` on each change of (allowed, band, reason) and samples
`nan_icm_lit` (our own estimate of the time ICM was lit by our configs, cumulative since the plane started; the grant is
the system log's `WifiAwareNativeApi: enableAndConfigure … isInstantCommunicationEnabled=… instantModeChannel=…` line), per ADR 2026-10.a239. The transport tag logs `icm verdict: …` on each
change and `icm band push — …` / `icm band now … — rides the next config (…)` for the push; the keepalive line keeps its
prefix and gains `icm=<B24|B5|off>`.

**Device trials owed.** Never toggle Wi-Fi on a lab phone; take the STA through the bridge's blip. Oracles: the framework's
`WifiAwareNativeApi: enableAndConfigure` lines in `logcat -b main,system` (`isInstantCommunicationEnabled`,
`instantModeChannel`; `dumpsys wifiaware` does not show the instant channel), the `icm verdict:` line, `NANICM`'s
reply, and for audio the controller's `A2DP Choppy` events under `BT Quality Report Events` in `dumpsys
bluetooth_manager`.

- **A. Cross-band delivery, the gate for flipping `fiveGhzRules`.** Two API-33+ Pixels, Bluetooth off on both. The
  smallest node id screen-off and dozing on `--es band 5`; the larger on a 5 GHz STA that is not 149, on `auto`. A DM
  burst both ways, then the bands swapped, then both on 2.4 as the baseline. Pass: cross-band delivery no worse than the
  baseline, no `coordination plane stalled` cycle, and `instantModeChannel` on the screen-off phone matching the band
  asked.
- **B. Band and throughput.** A P8 or P9 on the home 5 GHz network: `--es band 2.4`, `5`, `auto`, with
  `instantModeChannel` following `icmBandAsked`; a large STA download with ICM lit on each band and with `--ez on false`.
- **C. Audio.** Headphones streaming: `why=AUDIO`, `instantModeChannel=0` within 30 s, no `icm keepalive … icm=B24|B5`
  while it plays, and `dumpsys bluetooth_manager`'s A2DP dropped and underflow counts against the shipped build. An A2DP
  start mid-serve and mid-`NANDIAL` initiate must not drop the link. The delivery arm: the smallest node streams with the
  screen off and no BLE link while the larger sends a DM burst over NAN, against the same run with audio off; every
  message delivered, no more `coordination plane stalled` lines or `nan_cycle` records than the same run with audio off,
  and the added delay recorded here. If cycles appear, the starved verdict should stop counting `NO_OTA_ACK` failures
  while the verdict is AUDIO.
- **D. Lonely (kb68's bounds).** Start from `--es band auto`. A lone phone screen-on and screen-off: `why=LONELY`, no
  ICM-enabled config, `instantModeChannel=0`, and a newcomer still discovered over NAN within kb68's bounds. The
  post-cycle arm: with a sync owed and Bluetooth off, provoke a Tier-1 cure (`NANMSG --es fault swallow`); the re-attach
  logs `postCure=true` with `on=true` and `cues=0`, and `instantModeChannel` is non-zero for about 30 s after the settle.
  `NANICM --ez on true` is the negative control (`postCure=false`).
- **E. No churn.** A STA flap through `NANINIT --ez blip true` and A2DP pause and resume while ICM is idle: no
  `updatePublish` beyond the digest republishes, `instantModeChannel=0`, at most one `icm band push` per lit window; with
  audio playing and a sync owed, no keepalive line carrying `icm=B24|B5`.

## Trial results 2026-10-24 — Pixel 9 Pro XL (API 37), one phone, three Aware peers nearby

Build 086d493 installed over main 8843359e (same schema). The STA was on 5180 (channel 36, 11ax, 1200 Mbps PHY both
ways), so the verdict read DEFAULT / 2.4 GHz. Throughput is a 300 MiB TCP pull from a wired LAN host (`nc`), one
transfer per window, inside the 30 s an arm's forced cycle lights ICM for.

| Arm | Mbit/s (three runs) | Framework reconfigures in the window |
|---|---|---|
| Wi-Fi Aware off (`NANOFF`) | 732, 742, 788 | none |
| Aware on, ICM off (`--ez on false`) | 266, 227, 266 | ICM false |
| Aware on, ICM forced 2.4 GHz | 232, 306, 308 | ICM true on 2437 |
| Aware on, ICM forced 5 GHz | 169, 76, 238 | ICM true on 5745 |
| Aware on, ICM off, **screen off** | 570, 633 | — |

**ICM is not what costs the STA here: Aware attached with the screen on is.** The PHY rate holds at 1200 Mbps
throughout, so the loss is airtime, not a downgrade to one stream, and it mostly comes back with the screen off, where
the framework's power profile drops the 5 GHz discovery window and wakes for every 8th 2.4 GHz one. ICM on 2.4 GHz
adds nothing on top. ICM on 5 GHz (channel 149, off the STA's 36) is the worst arm. Together these point at Aware's
screen-on 5 GHz discovery window on 149, which pulls the STA's radio off channel 36 twice a second. An app cannot
switch it off: the attach's `ConfigRequest` (`mSupport5gBand=true`) and the discovery-window intervals are system API.
So this change does not fix #44's throughput drop; it keeps the debug-only 5 GHz picks honest (5 GHz ICM with a STA
elsewhere on 5 GHz is measurably worse), and it removes the lonely phone's near-constant ICM. Only Wi-Fi Aware off
(low-power mode, ADR 2026-10.d44b) restored the full rate.

**Audio** (Pixel Buds Pro over A2DP, music playing, 35 s windows, controller `A2DP Choppy` events and their ReTx):
the verdict went to AUDIO at once, and a forced session cycle while playing filed no ICM. Choppy events: Aware off 0
and 0; Aware on with the AUDIO verdict (no ICM) 3 (ReTx 1856) and 9 (2709); ICM forced on 2.4 GHz 1 (1449) and 4
(5920). Small samples at home, but the same shape as the STA: Aware attached is what hurts, ICM or not. Trials A, D and
E and trial C's delivery arm are still owed.
