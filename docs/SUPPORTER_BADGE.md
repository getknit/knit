# The supporter badge

**A mark on the avatar of someone who supports Knit, shown to the people their profile reaches, checked on every phone that draws it.**

|                    |                                                                                              |
|--------------------|----------------------------------------------------------------------------------------------|
| Credential version | 1 (`knit-badge:v1:`)                                                                         |
| Status             | Built 2026-10-14 (ADR 2026-10.r99s); issuer: knit-spool-coordinator ADR 2026-10.g3jy         |
| Reference          | `badge/Badges.kt` (verifier, pinned keys), `badge/PeerBadges.kt` (display-time check), `badge/BadgeRefresher.kt` (holder), `data/settings/BadgeStore.kt`, `data/peer/PeerBadgeEntity.kt`, `ui/components/BadgeVisuals.kt` |
| Executable anchors | `BadgesTest` (the six checks, the `sub` vector), `CoordinatorBadgeVectorTest` (the issuer's vectors @ 7aa6de4), `PeerBadgesTest`, `BadgeRefresherTest`, `GoldenVectorTest` (`profilePayloadBadges`, `messageContentV2ProfileBadges`), `SupporterBadgeLabTest` |

The issuer owns the credential's bytes, its signing key and the route; this document owns how a phone asks for one,
carries it and shows it. Requirement ids are `C-<section>-<n>`.

## 1. What it is for

A person who supports Knit — today, anyone with a live ticket key at a Knit issuer — can turn on a small mark on their
avatar. Every phone their sealed profile reaches checks it itself, offline, against keys built into the app: a modified build cannot
forge one, and a copied one verifies for nobody else. The issuer signs a commitment, never the person's node id, so it
cannot tell who in Knit holds the badge. The credential names a badge id (`b`), so other badges are a client release,
not a wire change.

## 2. The credential

- **C-2-1** `bc` is a CBOR map in the house encoding (definite length, text keys, shortest-form integers) with exactly
  these keys in this order: `v` (uint, 1), `kid` (bstr, 8: `SHA-256(raw public key)[0..8]`), `b` (tstr,
  `^[a-z][a-z0-9-]{0,31}$`), `sub` (bstr, 32), `nbf` (uint, Unix s), `exp` (uint, Unix s,
  `nbf < exp ≤ nbf + 2678400`). At most 256 bytes.
- **C-2-2** `sig = Ed25519(badge key, "knit/badge/v1" ‖ bc)`. The label is 13 ASCII bytes, no terminator. It is not the
  ticket's `knit/spool/v1/ticket`, and the badge key is not the ticket key: neither credential verifies as the other.
- **C-2-3** The wrapper is the CBOR map `{bc: bstr, sig: bstr}`, in that order. Its text form is `knit-badge:v1:` and
  the wrapper as unpadded base64url. **A profile carries the wrapper's raw bytes**, never the text.
- **C-2-4** The issuer's vectors (RFC 8032 §7.1 TEST 1 key, `b = "supporter"`, `sub = 00 … 1f`,
  `nbf = 1800000000`, `exp = 1802592000`), copied from knit-spool-coordinator @ 7aa6de4:

  ```text
  bc   a6617601636b69644821fe31dfa154a261616269737570706f72746572637375625820000102030405060708090a0b0c0d0e0f
       101112131415161718191a1b1c1d1e1f636e62661a6b49d200636578701a6b715f00
  sig  a6aefcd8dd3689ff74b1f77cd40ee773a49cc0e21ad5d46908db49d4e91835882b20aea0a7669c547beebd14147931f99db86c5b93f7841b56c5eed9e497a506
  text knit-badge:v1:omJiY1hVpmF2AWNraWRIIf4x36FUomFhYmlzdXBwb3J0ZXJjc3ViWCAAAQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eH2Nu
       YmYaa0nSAGNleHAaa3FfAGNzaWdYQKau_NjdNon_dLH3fNQO53OknMDiGtXUaQjbSdTpGDWIKyCuoKdmnFR77r0UFHkx-Z24bFuT94QbVsXu2eSXpQY
  ```

  The same `bc` signed under `knit/spool/v1/ticket` (`92882cbd…eb5601`) must be refused.

## 3. Who may show what

- **C-3-1** A received badge is shown only when every check passes, in this order:
  1. The wrapper decodes; `bc` is at most 256 bytes and `sig` is 64.
  2. `bc` decodes with `v == 1`. Unknown extra keys are ignored.
  3. `kid` names a pinned key (§4), and `sig` verifies over `"knit/badge/v1" ‖ bc`.
  4. `b` is an id this build knows. Today: `supporter`. An unknown id is not shown.
  5. `nbf − 300 ≤ now < exp + 300`, and `exp − nbf ≤ 2678400`.
  6. `r` is 16 bytes and `SHA-256("knit/badge/v1/sub" ‖ id(sender) ‖ r) == sub`, where the sender is the peer whose
     sealed profile carried the badge and `id` is its node id's 16 raw bytes (`NodeId.toBytes`).
- **C-3-2** A failure is silent: no error, no strike, no log line naming the peer.
- **C-3-3** The checks run again whenever a badge is drawn, against the local clock, and again at the next moment a held
  badge would enter or leave its window, so a badge lapses on an offline phone too. A badge is stored unverified, so one
  under a key a later build pins, or one whose window has not opened, shows once it can.

## 4. Pinned keys

- **C-4-1** Every build pins the production badge key: kid `ea50a7de77a33985`, raw public key
  `Ky3T0yKKHnX9wCcJ2DGti16wcEyYDKGxmtXNb5A8JUE` (unpadded base64url), from the maintainer's `keygen --badge`
  (2026-10-14).
- **C-4-2** Debug builds also pin RFC 8032 §7.1 TEST 1 (`kid 21fe31dfa154a261`, public
  `11qYAYKxCrfVS_7TyWQHOg7hcvPapiMlrwIaaPcHURo`), which a local issuer and the tests sign with.
- **C-4-3** Adding a key is a list append (`BadgeKeys`), shipped in an app release before it signs anything.

## 5. The holder's phone

- **C-5-1** "Show my supporter badge" is **off by default**. It is offered on your own profile screen only while the app
  holds a customer key (`TicketKeyStore`) or still holds a badge that verifies. While it is off the app never asks the
  issuer, and the profile carries no badge.
- **C-5-2** With it on, the app asks when no held badge verifies, or the one that does expires within 7 days.
- **C-5-3** It asks the issuer of the first held customer key (by relay host name) whose origin passes `IssuerOrigin`
  and whose last ticket answer is neither `ended` nor `unknown-key`: `POST <issuer>/api/v1/badge`,
  `Authorization: Bearer kak1_…`, body `{"sub": "<unpadded base64url of 32 bytes>"}`. No redirect is followed; the
  socket is bound to the validated Internet network (the ticket swap's rules, ADR 2026-10.3sk7). It carries the
  customer key's device proof, signed for `/api/v1/badge` (`docs/VOICE_CALLS.md` C-7.6-40, C-7.6-41).
- **C-5-4** Each ask draws a fresh 16-byte `r` and sends `sub = SHA-256("knit/badge/v1/sub" ‖ id ‖ r)`. The issuer never
  sees `id` or `r`.
- **C-5-5** It runs on the ticket refresher's session and never asks more than once an hour; after `ended`, once a day.
- **C-5-6** The app keeps `{wrapper, r}` per badge (`badge_held`), out of every backup (`SettingsKeys.TRANSIENT_PREFIXES`).
  The switch itself rides a backup.
- **C-5-7** A 200 keeps only the entries this build knows and that verify for this phone (C-3-1 with our own id), at
  most 4; an empty list holds nothing. 403 `ended` drops what is held. 401, 503, 400 and no answer keep what is held
  until it lapses.
- **C-5-8** The route's answers, in the issuer's order: 503 `unavailable`, 401 `unknown-key`, 403 `ended` (with
  `endsAt`), 400 `bad-sub`, 200 `{badges: [{badge, b, nbf, exp}]}`; with a device proof, also 400 `bad-proof`, 409
  `bound-elsewhere` and 426 `upgrade-required`, read as `docs/VOICE_CALLS.md` C-7.6-42 and C-7.6-43 read them. A 401/403/503 counts only with its own `error`
  word. The JSON copies of `b`/`nbf`/`exp` are not read: the app acts on the claims inside the credential.
- **C-5-9** Turning the switch on or off, and any change in the held badges, publishes a new profile version **on the
  sealed path alone**: a persisted sealed version (`badge_sealed_version`) moves past the cleartext one and only the
  sealed profile is sent. The cleartext `profile` frame's version never moves for a badge change, because a carrier that
  saw it move with no visible field changed would learn the holder shows a badge. The badge list published last is
  fingerprinted on disk (`badge_published`), so a change made while the mesh was stopped mints its own version on the
  next start and one version never carries two badge lists. Every later edit mints past both versions. Both keys ride a
  backup. Every sealed send (the fan-out on heal, an intro) first publishes the live list if it differs from the
  fingerprint, so a send never states a version with a list that version did not carry.
- **C-5-10** A sealed profile carries at most 4 entries, in the holder's held order.
- **C-5-11** No log line, bridge reply or crash report carries the customer key, a `sub`, an `r` or a wrapper.

## 6. Carriage

- **C-6-1** The badges ride **only the two sealed profile layouts**: `ProfilePayload.badges` (named CBOR: a list of
  `{w: bstr, r: bstr}`) and `ProfileV2` label 8 (compact: a list of `{1: wrapper, 2: r}`). Never the cleartext
  `ProfileContent`: its bootstrap is 432 B of a 448 B two-packet LoRa budget, and every carrier keeps the cleartext
  frame (ADR 2026-09.g64k), even one that only relays it. Nullable, elided while none is shown; an old build ignores
  the field (`ignoreUnknownKeys`).
- **C-6-2** (Unchanged by the sealed version: the receiver's gate is the profile-version gate it always was, and a
  cleartext profile at a lower version still lands its prekey, which has its own watermark.) A receiver keeps a peer's badges in a store of their own (`peer_badges`), keyed by the sender. Only a sealed
  profile that passes the profile-version gate replaces them, whole; one without the field clears them. At most 4 are
  kept.
- **C-6-3** A cleartext profile never changes them, whatever its version. This is the one exception to "a presentation
  field rides all three profile layouts" (`docs/WIRE_COMPAT.md`), and the separate store is what keeps the cleartext
  writer from reverting it.
- **C-6-4** **Every sealed profile carries it.** While the switch is on, the badge rides every sealed profile the
  holder sends, to every recipient alike: every peer with a confirmed DM session, which includes a room stranger whose
  ticks gave the two a session. Every sealed profile of one version is the same for every recipient; there is no
  per-recipient filter. The maintainer accepted this on 2026-10-14 because showing the badge is opt-in.
- **C-6-5** A peer that gains a session after a publish gets the current sealed profile, badges included, from the
  ordinary fan-out (the next heal sends each newly confirmed session the current version).
- **C-6-6** A former recipient — a peer whose session went, a blocked or removed peer — keeps the badge until the next
  sealed profile from the holder reaches it, which replaces or clears it; if none does, until its `exp` (at most
  31 days), when it lapses on its own (C-3-3). Turning the switch off publishes a new version without the field, which
  clears it on every peer that version reaches.
- **C-6-7** The 12-hour republish refreshes only the cleartext stamp; it carries no sealed copy and changes no badge.
- **C-6-8** Size: one badge (the 161-byte wrapper of §2's vector, plus a 16-byte `r`) adds 185 B to a max-size sealed
  profile in the compact layout (211 → 396 B) and 193 B in the named one (`SupporterBadgeSizeTest`). The cleartext
  profile and its LoRa budgets do not move. **The sealed profile is not padded**, so a carrier that can pick a sealed
  profile out of the other sealed DMs, and predicts its size from the cleartext profile it holds, can see the badge's
  bytes. Padding every sealed profile to one size is a follow-up; until then the badge's privacy against carriers rests
  on the seal and on C-5-9 alone.
- **C-6-9** An entry is kept only if its `r` is 16 bytes and its wrapper at most 333 bytes, the largest `{bc, sig}` the
  credential allows (`Badges.entryFits`). An issuer that adds a key to the wrapper must raise this in a release first.

## 7. Display

- **C-7-1** A small mark on the avatar, drawn outside its circle (`Avatar`'s `badge`), on the profile sheet, the DM chat
  header, the contacts list, the chat list row and group member lists, and on your own profile screen's photo while the
  switch is on and a held badge verifies (what contacts see). Not on message-sender avatars, call screens or
  notifications.
- **C-7-2** A "Supporter" pill in the profile sheet's badge row. Tapping the mark or the pill opens a sheet:
  "<Name> supports Knit."
- **C-7-3** Copy: no link, no price, no product name, no "Get" / "Upgrade" / "Become" / "Donate", and no call to action
  shown to anyone who holds no badge. The badge says the person supports Knit, not what they bought.
- **C-7-4** The supporter mark is the Knit Anywhere logo (Yarnworld) in its own colours, never tinted, inside a ring of
  the surface colour. A mark under 16 dp draws its simplified form (`ic_badge_supporter_small`); larger ones draw the
  full logo (`ic_badge_supporter`).

## 8. The `sub` vector (owned here)

`id = NodeId.toBytes("aaaqeayeaudaocajbifqydiob4") = 00 01 … 0f`, `r = 00 01 … 0f`:

```text
sub = 1fd9eb19889d6842dd2e28bf6c64042ef673b8e5117b802823400f811ea180c7
```
