---
id: "2026-09.ursc"
slug: the-nearby-room-says-when-lora-airtime-is-spent
title: "The Nearby room says when LoRa airtime is spent"
date: 2026-09-01
topics: [lora, ui]
---

# ADR 2026-09.ursc — The Nearby room says when LoRa airtime is spent

Status: Accepted (2026-09-01; `ui/chat/LoraReach.kt` `LoraReach.RoomSaturated`/`loraRoomReachFor`/`isLoraOnly`,
`ChatViewModel.LoraAudience`, `LoraMeshTransport.sendMessage`)

ADR 054 §5 gave a **DM** the saturated notice: a peer only the board has heard, plus
`LoraFacts.airtimeSpent` (≥ 90 % of the rolling 15-minute window), renders "airtime is used up, messages are
delayed". The Nearby room got nothing — `loraReachFor` returned `Silent` for `Conversations.NEARBY` on its
first line — and the room is where a spent window is most visible: it is the plane's busiest traffic, and a
post that reaches the people in Bluetooth range instantly can be minutes late to the person over the hill.
The user's report was simply that a spent budget has no obvious feedback; half of it was already built, and
the room was the half that was not.

Two further facts, one of them a defect. `LoraStatus.airtime` was republished only by `publishStatus()`,
whose nearest periodic caller is `recomputeReachable` off the 60-s `lingerSweepLoop` — and **never after a
send**, which is the only thing that spends the ledger. So `airtimeSpent` flipped up to a minute after the
send that spent the window, which had been blunting ADR 054's own notice since it shipped.

Decisions worth not relitigating:

1. **The room's audience test is existential, not addressed.** `loraRoomReachFor(facts, loraOnlyPeer)` where
   `loraOnlyPeer = peerTransports.values.any(::isLoraOnly)` — *is there anyone out there a full queue would
   delay*. A DM asks "can we reach **them**"; the room is addressed to nobody, so the same question has no
   subject. *Rejected:* firing on `plane == Live && airtimeSpent` alone. If the phone radios reach everyone
   we have heard, the queue holds nothing anybody is waiting for and the notice is a lie — a solo user whose
   board just spent its window on a profile bootstrap would be told their posts are delayed when they are
   not. The predicate itself is extracted as `isLoraOnly` so the DM rule and the room rule cannot drift on
   what "the board alone has heard them" means.
2. **`RoomSaturated` is the room's only LoRa state.** No `LoraOnly` counterpart. The room's audience is
   always a mix, so a standing "some people here are only on LoRa" strip would sit in Nearby permanently for
   anyone with a board — permanent chrome carrying nothing the user can act on. Only the *change* in what to
   expect earns a line.
3. **No `RelayReach` gate and no `facts.dms` gate**, unlike the DM rule. The room is never scope-eligible on
   the Internet plane (`RelayReach.Room` is permanent by design, `SPOOL_PROTOCOL` §4.4), so no better carrier
   can exist to silence it; and `loraCarryFor` already returns `LoraCarry.Room` whatever the DM switch says,
   so a room post rides a spent window regardless. A test pins the DMs-off case explicitly.
4. **Not dismissible**, where the room's *relay* notice is dismissed for good (`dismissRelayRoomNotice`).
   That one states a permanent structural fact and would otherwise never retire itself; this one clears
   itself as the rolling window ages air back, so a "never show again" would hide it on the one occasion it
   matters. This is the same in-memory-vs-DataStore split the chat-list radio banner already makes.
5. **The status is republished on every accepted send.** One `publishStatus()` after
   `pace.airtime.record(...)` in `LoraMeshTransport.sendMessage`'s `Queued` arm, so the send that crosses
   90 % publishes the fact instead of waiting for the sweep. Safe and cheap: it is derived read-only state,
   `LoraPacePolicy`'s 3-s floor bounds how often it can run, and `LoraStatusRepository` reduces the snapshot
   to a **threshold** before `distinctUntilChanged`, so per-packet churn never reaches a chat. The clearing
   edge stays on the 60-s sweep, which is nothing against a 15-minute window. Bonus: the radio screen's
   `lora_airtime` percentage becomes live rather than minute-stale.

Cost and residuals (accepted): the room can only speak about peers it has **heard** — a distant listener
whose board has been silent past the 45-min linger is not in `peerTransports`, so the first burst of a
session can be delayed with the room saying nothing; this is the same dependence ADR 054's DM notice already
has, and the alternative (firing unconditionally) is decision 1's rejected option. `heard` here is *reach*
and not proximity (ADR 2026-09.2ajk), so a board-less peer behind somebody's gateway counts — correctly: a
spent window delays a post to them over the very same hop. The seeded demo build never shows this, because
`DemoLoraPlane` pins a deliberately unsaturated `AirtimeSnapshot` to keep the degraded state out of
marketing capture. Tests: `LoraReachTest` (the room rule in all four directions, the DMs-off case, and
`isLoraOnly` as an exact-set test), `ChatViewModelTest` (`theRoomSaysWhenLoraAirtimeIsSpentAndSomeoneIsOnlyOnTheBoard`
and `aDmIgnoresAnotherPeerSittingBehindTheBoard` — the existential rule must not leak into a DM thread),
`ChatLoraIndicatorTest` (the room strip, its explanation, and that the body formats **no** peer name into
itself — the room header renders `nearby_title`, so that assertion has teeth). **Still owed:** the room leg
of the three-phone airtime trial in `context/lora-bridge.md`.

## Amendment 2026-10-29 — a second room state for the busy-band brake

Decision 2 named `RoomSaturated` the room's only LoRa state. ADR 2026-10.ug4a's shared-band brake adds a second
change in what to expect: while the board reports the channel at or over 40 % busy, Knit room posts wait in the LoRa
queue and drop from LoRa (`STALE`) if the band is still busy past `LoraFramePolicy.FRESH_MS` from the post's send
(the age-out is `sentAt + FRESH_MS`, so a post already queued when the band went busy drops after fewer busy
minutes). `LoraFacts.bandBusy` (`LoraStatus.braked`, only while the plane is Live) feeds `loraRoomReachFor`, which returns `LoraReach.RoomBandBusy`
under the same existential audience test as decision 1 — braked with nobody behind the board stays `Silent`.

1. **The brake outranks the spent window.** A spent window delays a post; a braked one may never cross LoRa, so
   braked-and-spent reads `RoomBandBusy`.
2. **Room only.** The brake never holds a DM, the Meshtastic room or the auto-reply, so `loraReachFor` and
   `loraGroupReachFor` ignore `bandBusy`.
3. **Still no `LoraOnly` counterpart and no dismissal** (decisions 2 and 4): the state clears itself when a reading
   under 40 % arrives or the last one goes stale. `LoraStatus.braked` is as of the last publish, so the strip can
   outlast a lifted brake by up to the linger sweep's minute.

Tests: `LoraReachTest` (busy with and without a LoRa-only peer, busy and spent, not Live, DM and group unchanged),
`LoraStatusRepositoryTest` (`bandBusy` follows `braked`, false off Live), `ChatViewModelTest`
(`theRoomSaysWhenTheBusyBandHoldsItsPostsAndClearsWhenTheBrakeLifts`), `ChatLoraIndicatorTest` (the strip and its
explanation name nobody). **Owed:** a device trial on a board reporting `channel_utilization` ≥ 40 % (GitLab #240).
