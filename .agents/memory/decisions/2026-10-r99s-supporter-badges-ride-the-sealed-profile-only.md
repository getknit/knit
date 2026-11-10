---
id: "2026-10.r99s"
slug: supporter-badges-ride-the-sealed-profile-only
title: "Supporter badges ride the sealed profile only, verified on display"
date: 2026-10-14
topics: [profile, wire, anywhere, ui]
---

# ADR 2026-10.r99s — Supporter badges ride the sealed profile only, verified on display

Status: Accepted (2026-10-14)

**What was asked.** Someone who supports Knit can show a "Supporter" mark on their avatar, off until they turn it on.
The issuer (knit-spool-coordinator ADR 2026-10.g3jy) signs a credential over a commitment to the holder's node id,
`sub = SHA-256("knit/badge/v1/sub" ‖ id ‖ r)`, so it never learns who holds it; a contact's phone checks it offline.
Spec: `docs/SUPPORTER_BADGE.md`.

**What changed.**

- **Sealed-only carriage.** `ProfilePayload.badges` and `ProfileV2` label 8 carry `{wrapper, r}` (at most 4).
  `ProfileContent` never does: its bootstrap is 432 B of the 448-B two-packet LoRa ceiling, and every carrier keeps the
  cleartext frame (2026-09.g64k), relays included. This breaks the `openToChat` rule that a presentation field rides
  all three layouts, so the receiver keeps badges in their own table (`peer_badges`, DB v22): only a sealed profile that
  passes the version gate replaces them (absent clears), and the cleartext writer never names the table. A column on
  `peers` would be reverted by the next cleartext upsert the moment someone "tidies" the writers into one.
- **Every recipient alike.** While the switch is on, every sealed profile carries the badge to every peer with a
  confirmed DM session. The first build filtered per recipient by `ContactUniverse.contactIds`, because the mesh lab
  showed the sealed fan-out reaches room strangers (the room's sealed ticks give them a session). The maintainer
  removed the filter on 2026-10-14: showing the badge is opt-in, so the people the profile reaches may see it, and one
  version stays one set of bytes for everyone. A former recipient keeps the badge until the holder's next sealed profile
  reaches it (which replaces or clears it), else until its `exp` (at most 31 days). Turning the switch off publishes a
  new version without the field.
- **A badge change moves only a sealed version.** The holder's switch, a renewal or an `ended` verdict changes the
  badge list; publishing that through `broadcastProfile` would bump the cleartext profile's version with nothing else
  changed, and every carrier holds that frame, so the version alone would say "this node shows a supporter badge"
  roughly monthly (crypto-reviewer, 2026-10-14). Instead `MeshManager.watchBadgeChanges` mints a persisted sealed
  version past both (`BadgeJournal.sealedVersion`) and sends only the sealed profile; every edit mints past both, and
  the sealed path states `max` of the two. Receivers need nothing: a sealed profile already advances `updatedAt`, and a
  cleartext one at a lower version still lands its prekey on its own watermark. The published list is fingerprinted on
  disk, so a change made while stopped still mints its own version — without it a restart re-sent a second list under
  the old version, and custody delivering the two out of order could revive a withdrawn badge.
- **Opt-in.** "Show my supporter badge" is off by default and offered only while a customer key or a verifying badge
  is held. The refresher asks on the ticket refresher's session (2026-10.3sk7), at most hourly, with a fresh `r` each
  time; held badges and `r` stay out of backups (`badge_held`).
- **Verified on display.** Rows are stored unverified and `PeerBadges` re-runs the six checks at the local clock on
  every change and at each window edge, so a badge lapses on an offline phone, and one under a key a later build pins
  shows without a resend.
- **Pinned keys are a list.** `BadgeKeys`: production (kid `ea50a7de77a33985`, from the maintainer's `keygen --badge`) in every
  build, RFC 8032 TEST 1 in debug only. A new key ships in a release before it signs anything.
- **Placements.** Profile sheet (mark, pill, sheet), DM chat header, contacts list, chat list row, group member list;
  not message-sender avatars, call screens or notifications. The mark is drawn outside the avatar's clip, so `Avatar`
  is a box around its circle; screens read badges through `LocalPeerBadges`, provided once at the app root.

**What it costs, and the traps.** One badge adds 185 B to a max-size compact sealed profile, and the sealed profile is
not padded: a carrier that can pick a sealed profile out of other sealed DMs and predicts its size from the cleartext
profile it holds can see that a badge is shown (crypto-reviewer, second pass). Padding every sealed profile to one size
(the reserved compact label 12 `pad`) is a wire addition beyond this change and is parked as a follow-up. Every sealed
send publishes the live badge list first if it differs from the fingerprint (`publishBadgesIfChanged`), so a
conflated quick toggle can't leave one peer holding a version with a list it never carried. A badge field on
`ProfileContent` or a column on `peers` would undo the store rule above. The coordinator's vectors are pinned in
`CoordinatorBadgeVectorTest` from its SHA, never recomputed; the `sub` vector is this repo's (`BadgesTest`). Kept true
by `BadgesTest`, `PeerBadgesTest`, `BadgeRefresherTest`, `GoldenVectorTest`, `SupporterBadgeSizeTest` and
`SupporterBadgeLabTest` (contact and room stranger both see it and the cleartext version never moves, switching off
clears it on both, a rename's cleartext profile does not clear it, switching off while stopped still clears it, a
copied badge verifies for no one). Received entries are bounded at intake (`Badges.entryFits`: a 16-byte `r`, a wrapper
no larger than the largest legal one). knit-ios must mirror the sealed field and its vectors.

**Amended 2026-10-14 (the mark is the Yarnworld logo):** the supporter mark is the Knit Anywhere logo, Yarnworld, in
its own blue and green, drawn untinted inside the surface ring, so no theme or Material You wallpaper recolours it. The
heart, then the Knit K on `tertiary`, read as the app's online green. The full logo is a 128 px lossless WebP
(`drawable-nodpi/ic_badge_supporter`), because a vector drawable has no dash pattern for its stitching; a mark under
16 dp (avatars under about 44 dp, and the profile pill) draws `ic_badge_supporter_small`, a vector reduction to the
ball, one swirl, one island and its spiral. The logo's green sits near the online dot's: the mark's place on the
avatar and its blue ball are what tell them apart.

**Amended 2026-11-09 (the mark follows the name):** the mark left the avatar and now follows the person's name
(`PeerNameText`'s `badge`, drawn by `NameBadgeMark`) on the same placements: the profile sheet, the DM chat header, the
contacts list, the chat list row and the group member list. On your own profile it follows the "Show my supporter
badge" switch's title, because your name there is an edit field. Two decorations on one 48 dp avatar crowded the
presence dot, and a coloured ring round the avatar was rejected because it reads as a story ring in other messengers.
The name's mark is about the name's font size (14–28 dp, font scale included), untinted and without the surface ring,
and it is never a tap target: the profile's Supporter pill opens the sheet. It sits outside the name's `Text`, so a long
name ellipsizes and the mark stays. The chat list row's spoken summary names it, since the row clears its children's
semantics. `Avatar` no longer takes a badge.
