---
id: "2026-10.k8ge"
slug: a-name-clash-fades-a-week-after-the-look-alike-was-last-heard
title: "A name clash fades a week after the look-alike was last heard"
date: 2026-10-04
topics: [identity, ui, contacts]
---

# ADR 2026-10.k8ge — A name clash fades a week after the look-alike was last heard

Status: Accepted (2026-10-04), JVM-tested (`PeerLabelsTest`, `PeerRepositoryTest`, `PeerDaoTest`, `LabelAnchorsTest`,
`HeardFromTest`, `KnitDatabaseMigrationTest`) and lab-tested (`NameClashFadeLabTest`). No wire byte moves. Amends ADR
058's "accepted cost" and settles its deferred last-seen pruning.

**What was observed.** ADR 058 labels a peer `Name (Alias)` whenever another identity this phone knows renders to the
same name, and the identities it checks are every cached `peers` row plus self. Nothing leaves that set. A stranger
named Alice heard once at a festival suffixes the contact named Alice on every screen until the 2,000-row
`sweepCap` happens to evict the stranger, which on most phones means never. ADR 058 recorded the cost and named the
fix: a `lastSeen`-bounded universe, not per-surface sets. The ask was to phase a clash out after a while so a
contact's name reads plain again.

**What changed.** Four pieces.

- **Stale ids give way; they do not leave** (`PeerLabels.index`'s `stale`). The live ids, self always among them, are
  labelled exactly as before, as if the stale ids were not there. A stale id is still labelled apart from everyone:
  it starts at the tokens it would hold against the whole universe and grows past any text that still matches, with
  live texts held fixed. So contact "Alice" next to a stale stranger reads "Alice" beside
  "Alice (ReallyJoyfulFerret)", and the two never read alike. Starting a stale id at its full-universe count matters:
  a look-alike whose alias was ground to share the contact's first token holds two tokens while both are live, and at
  one token after going quiet it would read "Alice (T1)", the contact's own label from the day before. The reader's
  first idea, dropping stale ids from the index, fails on two quiet Alices: both outside, both plain. `labelFor`'s
  fast path now has every member's count, because a member falling through to `resolve` would be judged against the
  very stale ids the index kept out.
- **Anchors never go stale** (`data/LabelAnchors`). Accepted DM peers, DM peers we have written to, members of every
  group we have not left, verified peers and self. The set is wider than `contactIds` on purpose: an extra anchor
  keeps today's clash, but a missing one is the dangerous case. A quiet contact next to a fresh namesake would read
  suffixed while the newcomer read plain, which is the look-alike's best case. `LabelAnchorsTest` pins every contact
  inside the anchors over random universes. The set is shared on the app scope (`WhileSubscribed`, replay expiring
  with the last subscriber, never seeded empty), so the authored-in query runs once per messages write, not once per
  screen.
- **`peers.lastSeenAt`** (DB v19). The newest `sentAt` among the signed frames of the peer's that this phone
  accepted, capped at our clock, so a custody replay of an old frame never makes its author look recent. The rule is
  `mesh/HeardFrom`: any plane counts, but not a `blobreq` (which `verifyInbound` never verifies) or the unsigned
  sealed tick (judged on shape alone). The stamp is buffered in memory from `noteVerified`, ahead of the relay where
  nothing may throw or wait, and banked on the 60 s metrics tick and at stop in one write transaction. A stamp within
  six hours of the table's is skipped before any query; six is half the 12 h profile republish, so a live peer's
  republish is never the one skipped. The profile upsert stamps the row it pins, so a new peer is never born quiet.
  The upsert merges `lastSeenAt` with `MAX`, so a read-copy-write can never move it back. The migration seeds it from
  the later of the profile version and the kept proof of key's publish stamp (`peer_profiles.sentAt`).
- **The window is a week** (`LabelFade.LABEL_FADE_MS`), judged on every directory input and on an hourly tick, so a
  stranger crosses the line on the calendar without a write. `observeDirectory` builds the index only when the rows,
  our name or the stale set changed. `labelIndex()` runs the same rule, so a notification title agrees with the list.

**What it costs and does not cover.** A contact's label now also changes when the other Alice goes quiet, not only
when she appears. The appearance is still the tell, and ADR 058's "a contact's label changing is the signal" now
holds in both directions. Old messages keep the label text they were sent with (`Mention.name`, notifications
already posted; *amended by ADR 2026-11.p6dk, 2026-11-23: a received mention's highlight follows the reader's current
labels, so a fade can turn an old message's highlight on, and a new clash can turn it off*), and a conversation shortcut's title follows on its next face change, as it did for an appearance.
The contact-card preview asks for a known id under another name, and that lookup yields to stale ids too, so the
preview can show "(Alias)" before import and none after. Nearby-room regulars the user never wrote to are not
anchored, so two such strangers can swap which one reads plain as each goes quiet; both stay labelled apart.

The trap: `lastSeenAt` is sender-asserted and rides replays. It is presentation only, and must never feed a presence
tier (`ui/Reach.kt`, ADR 2026-09.2ajk), a route, custody or `sweepCap`'s eviction order. A new contact signal belongs
in `labelAnchors` as well as `contactIds`; `LabelAnchorsTest` feeds both the same random inputs, so give it the new
one too. A `PeerRepository` built without a
`LabelFade` keeps the old forever-universe and stamps nothing; production and `MeshLab` always pass one.
