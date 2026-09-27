---
id: "2026-09.g64k"
slug: a-carrier-keeps-the-signed-profile-of-everyone-it-carries
title: "A carrier keeps the signed profile of everyone it carries"
date: 2026-09-27
topics: [custody, mesh, convergence, keys]
---

# ADR 2026-09.g64k — A carrier keeps the signed profile of everyone it carries

Status: Accepted (2026-09-27)

**What was observed.** The iOS port's `android-rings` run against a Pixel 3 (2026-09-27) never saw custody
converge. 81 of the Pixel's frames were relayed chats and receipts from senders a fresh peer had no key for, so
the peer kept none of them and the Pixel re-served them for as long as the link held. That looked like ADR
2026-09.9xuu again, but it was the case that ADR had set aside, and it was worse than a delay. 9xuu has the
carrier serve the sender's profile ahead of their frames, and a newcomer with no key parks the backlog and asks
by `keyreq`. Both need a signed copy of the profile, and the Pixel no longer had one:

- **Custody had lost it.** The per-sender quota evicts a chatty sender's oldest frame, which is their profile.
  The TTL takes a departed sender's profile up to one republish period (12 h) before their last posts, because
  a live node restamps its profile on that cadence and a departed one stops.
- **`KeyExchange`'s cache had lost it.** The cache was the only other copy, it was in memory, and the Pixel had
  been reinstalled for the run.

The pin in `peers` survived. It had to, because a carrier stores a frame only once it has verified it. But a
pin holds the key, not the sender's signature over a profile, and only that signature proves the key to anyone
else. So the newcomer's `keyreq` recursed to neighbours that could not answer either. The backlog stayed
unreadable to it, and the two digests disagreed, until the frames themselves expired, up to 24 h later.

The mesh-in-a-box lab reproduces it: Bob drops Alice's profile from custody and restarts, then links to Carol,
who has never met Alice. Carol refused all 40 posts and never converged. The 9xuu scenario that drops Bob's
profile by hand had passed only because Bob's cache still held it.

**What changed.** The carrier keeps the signed frame each pin came from, and uses it at the two points where it
hands a key on:

- **`peer_profiles`** (DB v16, `PeerProfileEntity`). One row per pinned peer holds the verbatim `signed` + `sig`
  of the newest profile it was pinned from, and that profile's publish stamp. `InboundPipeline.handleProfile`
  writes it beside `keyExchange.onProfilePinned`. The write is `ON CONFLICT … WHERE excluded.sentAt >
  peer_profiles.sentAt`, so the newest stamp wins in any arrival order and a copy already held is never
  rewritten. The stamp is clamped to the skew window first, because the peer picks it (ADR 2026-09.gdhp). Rows
  go with their pin: `PeerRepository.forgetSelf` and `sweepCap` sweep the orphans, so the table is bounded by
  the peer cap.
- **`KeyExchange.profileFor`** reads the cache first, then the store. A `keyreq` is answered from it, and so is
  the new `serveKey`.
- **`ForwardSync.onDigest`** sends the key ahead of any backlog whose sender has no live `profile` in our
  custody (`keylessSenders`). It uses `serveKey`, so the key goes point to point as a `keyreq` answer does and
  is never re-flooded (ADR 2026-09.7bu7). A newcomer then pins before the first post and refuses nothing,
  whatever the link's speed. With a `keyreq` alone, on a slow link, the answer queues behind the backlog and
  the park's two minutes run out first.

A served key the receiver's custody would refuse as dead on arrival still pins, because the pin path has no
age check. So the digests stay convergent. An expired profile is refused everywhere, and a live one is trimmed
by every node's quota alike.

**The alternatives.**

- **Columns on `peers`.** This was the first reach, and it is ruled out three ways:
  - `PeerEntity`'s equality, which Room's flow dedup relies on, breaks on a `ByteArray`.
  - Every peer flow reads `SELECT *`.
  - `PeerDao.upsertRow` overwrites every column, and the debug writers upsert fresh entities.
- **Keeping the profile in custody.** Exempting it from the quota or stretching its TTL would change the
  convergent quota rule. That is a wire change in all but name, since two app versions would hold different
  live sets, and it would do nothing for the TTL case.
- **The `keyreq` path alone.** It is correct, but it is slow on exactly the links that exposed this.

**What it costs.** One row of a few hundred bytes per pinned peer, at most 2,000 of them, carried in a backup
like `peers` so a restored phone can prove whom it carries.

On the wire, one extra point-to-point profile per keyless sender per digest reply, and only when the peer holds
none of that sender's frames we hold. A peer that custodies one of their frames verified it, so it has the key.
That rule keeps two long-lived carriers of one chatty sender from trading the key on every reconcile. On
Bluetooth, `LinkCrossings` also stops a repeat to the same link inside the seen window.

**What it does not cover, and the traps.**

- **Rows start empty.** A v15 phone's existing pins fill only as each peer's profile next arrives. A carrier
  already holding a stranded backlog when it upgrades will wait out those frames' TTL.
- **Pins without a signature.** A contact-card import, and any door that pins without a signed profile, keeps
  no proof, as before.
- **The iOS port** needs the same store for its own `KeyExchange`.
- **The lab seam.** `StrangerBacklogLabTest` drops Bob's custody copy by hand, so Carol's custody keeps the
  live profile Bob served her while Bob's does not. The scenarios therefore check "Carol is short nothing Bob
  carries", as 9xuu's do. In the field the quota or the TTL removes it on both nodes alike.

**Device check (2026-09-27).** The run was a Pixel 3 on this build and knit-peer on `hci1`:

- **Setup.** A new-identity peer posted 205 room posts until the Pixel's quota evicted its profile. The Pixel
  then restarted, and a fresh peer with keys for nobody linked to it.
- **Result.** The Pixel served two keys ahead of the back-fill (`keysServed`). The fresh peer held all 200 of
  the stranger's posts within two minutes with zero `noSenderKey` drops. It held every live frame the Pixel
  carries after 6.1 minutes. This happened on the rig and link where the field report's back-fill had never
  drained.
- **Test gotcha.** A DM addressed to the carrier cannot stage this. Custody drops a delivered DM, so it never
  pushes the sender's profile out; only room chat stays.

The tests that keep it true:

- **`StrangerBacklogLabTest`:** `aRestartedCarrierServesTheAuthorsKeyAheadOfTheirBacklog` (the key ahead) and
  `aRestartedCarrierAnswersTheKeyRequestForABacklogItServed` (the key ahead lost, the request answered).
- **`ForwardSyncTest`:** the `onDigestServes…Key…` cases, which pin which senders get a key.
- **`KeyExchangeTest`:** the store fallback and `serveKey`.
- **`PeerRepositoryTest`:** stamp order and the orphan sweep.
- **`InboundPipelineTest`:** which profiles keep a proof.
- **`KnitDatabaseMigrationTest`:** `MIGRATION_15_16`.
