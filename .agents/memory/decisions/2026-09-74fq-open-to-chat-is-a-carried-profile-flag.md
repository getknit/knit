---
id: "2026-09.74fq"
slug: open-to-chat-is-a-carried-profile-flag
title: "Open to chat is a carried profile flag, and the nearby cue is batched with per-person and hourly cooldowns"
date: 2026-09-03
topics: [profile, wire, notifications, ui]
---

# ADR 2026-09.74fq — Open to chat is a carried profile flag, and the nearby cue is batched with per-person and hourly cooldowns

Status: Accepted (2026-09-03; `ProfileContent.openToChat` / `ProfilePayload.openToChat` / `ProfileV2` label 5,
`peers.openToChat` at DB v9, `presence/OpenToChatPolicy` + `OpenToChatWatch`, the `knit_open_to_chat` channel;
amended 2026-09-03 by ADR 2026-09.3yje, which drops anyone already messaged out of the qualifying set)

**What was asked for.** A way for a user to say "I'm up for a conversation", so that two people who both say it
and end up in **direct radio range** get a nudge to post in the Nearby room, and so that anyone looking at a
profile can see the flag. Nothing in the app could carry that: a profile held a name, a status line, an avatar
and key material, `Peer` holds only a node id and unauthenticated advert bits, and no notification type existed
for presence at all. The two halves that needed deciding were *where the flag lives on the wire* and *when the
cue may fire* — the second being where a naive build spams a coworking space all day.

**Where the flag lives, and what it was not.** It is a presentation field on the profile, `openToChat: Boolean =
false`, on all three profile layouts at once: the cleartext `ProfileContent`, the sealed `ProfilePayload` (ADR
020) and the compact `ProfileV2` mirror (label 5). Defaulted rather than nullable, because `encodeDefaults =
false` then elides it while off: an unset flag costs nothing, a peer predating the field reads false, and a flip
back to off propagates by omission, the shape a newer profile with no prekey already uses to clear the pin. A
tri-state bought nothing the receiver would not coerce to false anyway, and an explicit `false` would have cost
12 B on every profile forever. The alternatives a reader reaches for first all fail here: a **capability bit**
is a claim about a build, not a person, and rides unauthenticated on the advert; a **status-string convention**
is not machine-readable; a **new frame or ctl** would flood and never be custodied by deployed builds (the ADR
016/018/020 argument a fifth time); **deriving it** (ADR 066) has nothing to derive from, so it is carried,
the ADR 2026-09.qq2r shape. All three layouts move together because the sealed path copies the whole
presentation set under a newer version, so a field carried by the cleartext frame alone would be silently
reverted by the next sealed update — the rule `.agents/rules/mesh.md` now states. The ADR 060 transcoder needed
nothing: the flag rides as passthrough text key plus `f5`, measured in `CoordinationPlaneSizeBudgetTest`.

**When the cue may fire.** The trigger is a *join*, not a transport event: the own flag, the short-range
`MeshController.neighbors` set (ADR 2026-09.2ajk — only a plane that sights the peer's own radio counts), the
peer rows carrying the flag, and the block list, re-folded whenever any of the four moves, because a peer sits
in the neighbor set before its profile row exists and a row arriving after the sighting must still count. Five
rules, one constant each, live in the pure `OpenToChatPolicy`: newcomers are held `HOLD_MS` (20 s) so walking
into a full room is one cue in arrival order; a person already named is named again only per encounter — out of
the set for `ABSENCE_MS` (15 min, well past the 90 s / 150 s sighting lingers and any sync-driven flap), and
`PEER_COOLDOWN_MS` (2 h) since their last cue, and fewer than `PEER_DAILY_CAP` (2) cues about them in a rolling
`DAY_MS`; and cues post at most once per `ALERT_GAP_MS` (60 min) overall, with later arrivals **held** for the
next post rather than refreshed quietly, so "once an hour" is literally true and every post is a real cue.
Continuous presence never re-cues. The own flag going off empties the set (everyone departs, nothing pending,
the cue is cancelled); going back on makes everyone arrive through the same gate, which is what makes "switch
it on in a full room" one batched cue with no special case. The per-person stamps and the last post time
persist in `SettingsStore` (read once at start, written through — never collected, since a DataStore write
re-emits every flow), so a process restart cannot re-buzz about someone named minutes ago.

**What it costs, and the traps.** Each flip is a profile version bump and one sealed `CTL_PROFILE` DM per
confirmed session (a chain key each), the same cost as editing the status line, absorbed for no-op writes by
`distinctUntilChanged`. The cleartext profile discloses the flag to a carrier exactly as it discloses the status
text beside it. After a restart the departure clock is unknowable, so a peer named three hours ago who never
left can be cued again: the cooldown and the daily cap alone bound it, which is accepted. The cue is suppressed
while the Nearby room is on screen and still stamps the peers as named — moot, not missed. Two traps for the next
person: forgetting one of the three profile layouts silently reverts the flag for that path
(`InboundPipelineTest.theSealedProfileCarriesTheFlagAndAStaleOneCannotRevertIt`,
`MessageContentV2Test.aProfileWithoutTheOpenToChatFlagCarriesNoLabelFive`), and applying it under the prekey
watermark instead of the presentation one would let a stale profile drag it back
(`aPrekeyOnlyAdmissionOfAnOlderProfileDoesNotRegressTheFlag`). The rules themselves are pinned by
`OpenToChatPolicyTest` and the collector by `OpenToChatWatchTest`, both on a virtual clock; the wire by three
new `GoldenVectorTest` vectors with none moved, the DB by `migrate 8 to 9`, and the outbound half by
`MeshManagerTest.switchingOpenToChatOnRepublishesAProfileCarryingTheFlag`.
