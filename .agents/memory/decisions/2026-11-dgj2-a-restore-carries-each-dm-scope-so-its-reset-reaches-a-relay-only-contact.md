---
id: "2026-11.dgj2"
slug: a-restore-carries-each-dm-scope-so-its-reset-reaches-a-relay-only-contact
title: "a restore carries each DM scope so its reset reaches a relay-only contact"
date: 2026-11-14
topics: [backup, spool, ratchet]
---

# ADR 2026-11.dgj2 — a restore carries each DM scope so its reset reaches a relay-only contact

Status: Accepted (2026-11-15). Amends ADR 2026-09.6mj7 and ADR 2026-10.j67x. Issue #324.

**Context:** on 2026-11-14 a Play user on 2.11.1 reinstalled Knit and restored a backup. The P9 was that user's only
contact over the Internet relay, and it never heard from the phone again. Neither saw the other's DMs until a reset was
forced from the P9 by hand. The pair shares only public-1, and the cause is in the design:

- A backup never carries ratchet state (ADR 2026-09.6mj7), so a restored phone holds no session.
- `MeshManager.finishRestore` sends every DM peer an `AFTER_WIPE` reset. A reset from no session replaces no root, so
  `RatchetSessions.pendingResetInit` is null and no DM scope is exported. Spec C-3.1-4 says so, and so does this ADR's
  predecessor (ADR 2026-10.j67x: "a reset from no session … nothing exported").
- The reset therefore goes out only on the radios and through custody, and over a relay alone it reaches nobody.
- The holder's session is still confirmed. It keeps reading the old DM scope and never joins the §3.5 pair scope
  (`IntroSync.want` returns for a confirmed peer).
- The restored phone's later DMs register an intro and sit in the pair scope. Its profile screen reads "Waiting for …
  to answer".

The P9 first read this as the contact being offline: `highestPeAcked` stayed frozen while `sendEpoch` climbed. The
contact's own post in a shared commons room, over the same relay, disproved that.

**Decision:** a backup carries, per DM peer a restore resets, the **anchor**: the scope id and outer seal keys of that
peer's live DM scope. It never carries the pairwise root or any chain.

How the anchor is kept and used:

- **Storage:** a live Room table, `dm_scope_anchors`, classified `CARRIED`, copied by `DatabaseExport` under the same
  snapshot as every other table.
- **Writer:** `DmScopeAnchors` writes only for the person's contacts among the peers `finishRestore` resets
  (`MeshManager.anchorTargets`), and only from a confirmed session. Those are the wiped peers (`MeshManager.wipedPeers`)
  in a DM the Message Requests rule accepts (`AcceptanceSnapshot`), plus the members of an accepted group, minus every
  blocked peer. An unaccepted request or a blocked sender can hold a confirmed session with us (#290), and an anchor
  would give it a restore's subscription and re-sends for the whole window. The exposure accepted below is the
  contacts' alone. `finishRestore` still resets every wiped peer.
  - Each reconcile runs on start, on `RatchetSessions.rootChanges` and on every change to the blocked or accepted
    set, since neither of the last two moves a root. A settings projection re-emits on a write to any key, so a
    repeated value is ignored, and an unchanged unarmed row is not written again. The heal basket runs one more as a
    backstop: it catches a pass a failed round missed, and drops a row whose window has run out. The start's pass
    rides the subscription to `rootChanges`, so a confirmation landing during it is not lost. It deletes every
    unarmed row it did not write.
  - One pass runs at a time (`MeshManager.anchorPass`), the event collector's, the heal basket's and `finishRestore`'s
    alike, and a pass reads its inputs under that lock: a pass that read the sessions before a confirmation would
    otherwise prune the row a fresher pass had just written (`MeshManagerTest`'s overlapping passes). Contact removal
    and the restore's `arm` take the same lock, so a pass cannot write back a row they just changed. A removed contact, a stranger or an unaccepted request whose init we answered, or an era whose root has
    left the ratchet keeps no row.
  - A blocked peer's row goes at once, armed or not. So does the row of a peer that is no longer a target (its thread
    deleted, its group left): its re-sends would otherwise run on for the window.
  - A peer our own ordinary reset is still waiting on keeps its row as it is: it may still be on the root the row
    names, so a backup taken then still reaches it.
  - No reconcile runs while a restore is pending. Every carried row is unarmed then, so the start's reconcile would
    delete them all before `finishRestore` arms them. No row feeds the scope table then either: `restoredDmRoots`
    gives nothing until `finishRestore` has armed the rows, so a restore file's own `armedUntil` never subscribes a
    scope.
  - `ContactRemover` also deletes the row at once, after the DM thread goes: a pass starting earlier would still see a
    DM target and write it back.
- **Arming:** `finishRestore` first zeroes every row's `armedUntil`, then arms the row of each wiped peer that is an
  anchor target for `DmScopeAnchors.ARM_WINDOW_MS` (30 days), and only then sends the resets.
- **Re-sending:** while an armed peer's reset is unanswered, the heal basket sends it again every
  `DmScopeAnchors.RESEND_FLOOR_MS` (20 h) as a `RESEND_OWN` marker: the same init under `FLAG_RESET`, with a fresh
  frame id and `sentAt`, never a new root. The marker is sealed against the prekey its init recorded, whatever the pin
  has moved to since. `REMARK_OWN` would decline once the pin moved, even within the holder's retention. Custody keeps
  our frame 24 h and the spool 48 h, so without the re-send a contact offline for longer than about a day would miss
  it. A holder that already adopted that init resolves the copy by `peerInitEphPub` (`RatchetEngine.resolveSession`)
  and answers once more.
  Each copy that reaches the holder runs its reset path again, which re-seals its unacked DMs of the last 24 h, and
  each stamps our `lastResetSentAt`, which re-arms our own 6 h heuristic floor.
  The 30-day cap was the user's choice on 2026-11-14. Unbounded would cover any outage, but a contact who never
  returns (uninstalled, or a stale anchor) would then cost a push a day forever. 48 h, the drain window this first
  borrowed, misses a contact with long power cuts, which is the very case behind #324.
- **The two scopes:** while the peer's session is our own unanswered flagged reset (`RatchetSessions.ownPendingResets`),
  `ScopeRegistry`'s `restored` seam emits two scopes, de-duplicated by id:
  - the new root's live scope;
  - the armed anchor as a retiring scope.

  Both carry that reset's init. This is C-3.1-4's replaced-root shape, with the anchor standing in for the root the
  restore does not hold. When a second reset re-rooted over the restore's, the slot's previous root (the restore's
  own) is emitted retiring too, under the current init, until it drains. The restored scopes come last before the
  de-duplication, so an anchor read from a file can never shadow a group, pair or commons scope this phone derives.
- **Superseding resets:** a reset that supersedes the restore reset inherits the anchor. One example is a re-root
  because the holder's pinned prekey moved; another is the 6 h heuristic's reset. A superseded reset is refused by
  `ScopeFrames.mayRefill`, which already admits only the matching reset into a retiring scope (C-3.1-5).
- **The prekey trigger:** a profile that pins a new prekey id for a peer whose anchor is armed sends a fresh
  `AFTER_WIPE` reset (`RatchetSessions.restoreResetOwed`). It fires when the pin is at least
  `IdentityKeyStore.SPK_KEEP` (5) ids past the pending reset's `initPkid`, or when no reset could be sealed at restore.
  A pin that moved less owes nothing, because the holder still holds the prekey our reset is sealed to. Without the
  trigger, a backup older than the holder's prekey retention (current plus four, rotated weekly) leaves a reset the
  holder can only refuse as `EPOCH_GONE`. The holder's fresh profile arrives through the anchor's drain, which is why
  the anchor is subscribed and not only pushed into.
  - **Settling:** the drain delivers the holder's older profiles one after another, each pinning a newer prekey, and
    an immediate trigger re-rooted four times in a row in the lab. So the trigger waits `PREKEY_RESET_SETTLE_MS` (2 s)
    per peer and checks again before it sends.
  - **Bounds:** a peer that never answers but keeps signing profiles with new prekey ids would otherwise buy one
    flooded reset per profile for 30 days. `RatchetSessions.restoreReRoot` therefore allows one re-root per
    `RESTORE_REROOT_FLOOR_MS` (1 h) since the last re-root to that peer (the session's persisted `establishedAt`,
    which a re-root sets and a marker never does), and at most `RESTORE_REROOT_CAP` (3) per process. Not
    `lastResetSentAt`: a `RESEND_OWN` copy stamps it too, and would hold back the re-root a moved pin owes. The cap is in memory, so a restart starts it again, and the floor still bounds a peer to about one an
    hour across restarts. Each refusal on the profile path counts `DropReason.RESTORE_RESET_REFUSED`.
  - **The heal basket** takes the same re-root when the pin has moved past retention, under the same floor and cap.
    While a re-root is owed but held back it sends nothing, not even the marker, because the holder cannot open that
    init.
- **The holder:** it pulls the reset from the scope it already reads, adopts it, re-seals its unacked DMs of the last
  24 h and answers under the new root, as for any reset.
  Over a relay alone those re-seals reach nobody. `resealRecentDmsTo` floods, custody keeps the first bytes it saw
  under an id, and `ScopeSync` pushes from custody, so the new scope never gets them (ADR 2026-10.dmgw: custody serves
  only the originals). DMs sent after the answer flow normally.
- **Clearing:** on our confirmation, the next reconcile overwrites the anchor with the new scope, and the anchor leaves
  the table.
- **The spec:** gains C-3.1-6. C-3.1-4's "a reset that replaced no root" exclusion now excepts an armed anchor.

**The alternatives:**

- **The holder resets when the peer is visibly alive but silent** (#324 option 1). This covers losses the losing phone
  cannot see. It needs a heuristic that can misfire and burn a healthy session, and it waits for evidence that a quiet
  contact may never give. It remains the follow-up for those losses.
- **Carry the ratchet tables after all.** A snapshot's send chain re-seals indices its peers have consumed, and those
  frames drop silently as `DUPLICATE` (ADR 2026-09.6mj7). Still rejected.
- **Carry the pairwise root.** Every scope the session could ever derive would then ride in the file. The derived keys
  are the narrowest input that reaches the peer.
- **Make the anchor push-only, never pulled.** That narrows what a spool can re-serve, but it loses the holder's
  current profile, which the prekey trigger needs. A replayed old-era init is refused anyway, because
  `init.at > establishedAt` fails for it.
- **Fill the table at backup time.** That puts a data-layer dependency on the ratchet into `BackupWriter`, and the read
  happens outside the export's snapshot.

**Consequences / limits:**

- **What the backup now exposes** (the user accepted this on 2026-11-14). An anchor is valid until the pair's next
  reset, which is often months, not just at the moment of the backup. To whoever holds the file and its recovery key it
  gives:
  - the id and outer seal of each such contact's DM scope: the envelope of every frame sealed there for the rest of
    that era (sender, recipient, send times, types, sizes), its signal-lane typing cues and its casts, never content,
    which is inner-sealed under a session the backup does not carry;
  - the ability to recognise the contact's subscriptions to that scope on a spool;
  - with the identity, which the file already holds, relay reach: a restore of the file on another phone resets every
    such contact through the anchor. A contact reached only over a relay adopts the reset and re-seals its last 24 h of
    unacked DMs to that phone. Before this decision the same theft needed radio range. Clone detection (ADR
    2026-09.ypcc) is the backstop.

  This is the reach carried `group_roots` already give over every group scope, and it is why ADR 2026-09.6mj7's "keeps
  forward-secrecy material out of a file" now reads "keeps every ratchet chain out". Presence keys stay out
  (ADR 2026-11.xp6d) because a restore re-mints them anyway, while an anchor is the only way a restore reaches a
  relay-only contact.
- **Unlinkability:** a restore's reset links the anchor's era to the new one for the spool that carries it, as any
  C-3.1-4 reset does.
- **A stale anchor rarely helps.** If the session moved between the backup and the restore, the anchor is the scope
  of an era the holder has left. For 48 h after the holder's own reset it still drains that scope as retiring
  (C-3.1-4), so the restore's reset can still reach it there. After that the pair falls back to the radios and to #324
  option 1. Either way the restore is no worse than before.
- **A stale era with no time gap.** A backup taken minutes before a reset the holder sent can split the pair. The
  holder's re-served flagged reset wins the restored phone's race, and rule C's freshness half (ADR 2026-10.4mjm)
  cannot fire inside the 5 min skew bound, so both end confirmed as responders on different roots. That limit of rule
  C predates this decision. The lab case puts an hour between the holder's reset and the restore, as a real backup
  would.
- **The drain counts toward the heuristic.** The holder's DMs sealed after the restore and pulled from the anchor fail
  to open, and `InboundPipeline.isLiveEvidence` counts them. The 6 h reset that follows is a retry that still rides the
  anchor. `SeenSet.reopen` lets the holder's same-id re-seals through. An anchor placed at two relays can delay one of
  them by a `SeenSet` window.
- **Not covered:**
  - A DM the holder sent during the outage, over a relay alone (see the holder above). `RestoreOverRelayLabTest` parks
    that case as `@Ignore` until the custody plane serves a re-seal (#325).
  - A database wiped on a lost key (`DatabaseKey`, ADR 2026-10.47rw): the table goes with it.
  - Direct contacts with no thread, whose restore reset is link-owed (ADR 2026-11.xp6d) and never custodied.
  - A contact whose anchor no keeper pass has written yet. A DM thread's first message, or joining an accepted group,
    wakes no pass, so a backup taken before the next heal (up to 15 min) carries no anchor for a new contact and its
    restore cannot reach that contact over a relay (#330). Accepted on 2026-11-15: a long-standing contact, the likely
    case, is covered.
- **Device trial (2026-11-15, Pixel 3 restoring, Moto G relay-only on public-1):** with the anchor written, the pair
  confirmed over the relay alone 29 s after the restored phone's mesh started. The anchor was subscribed as a retiring
  scope beside the new one, then left the table. A backup taken 30 s after the pair's first DM stranded the pair (#330).
- **Hostile input:** a restore file is hostile input. The reader skips a row of the wrong lengths or armed past the
  30-day window plus `DmScopeAnchors.ARM_CLOCK_SLACK_MS` (24 h), a reconcile deletes the over-armed one, and `finishRestore` disarms every row before it arms its
  targets. The slack keeps a wall clock stepped back just after `arm` from deleting the restore's own arming, which
  would strand the pair again; it widens the file's reach by nothing, since `finishRestore` disarms every row it
  carried. A row whose id collides with a scope this phone derives loses the de-duplication.
- **A never-answering contact's cost:** the marker every 20 h and at most one re-root an hour while its pin keeps
  moving, for 30 days. With the cap, one that moves its prekey every hour costs three re-roots per process start.
- **The guards:**
  - `RatchetSessionsExportTest` (`ownPendingResets` with its slot; `restoreResetOwed` at the retention boundary; every
    `restoreReRoot` verdict; a `RESEND_OWN` under the same init after a rotation within retention).
  - `ScopeRegistryTest` (the armed anchor's two scopes; a superseding reset keeps the anchor and the restore's slot; an
    anchor colliding with a group scope never replaces it).
  - `ScopeFramesTest`'s existing retiring-scope cases (only the matching reset is admitted).
  - `DmScopeAnchorsTest` (only target rows are kept; a removed contact's row does not come back; our own unanswered
    reset keeps its row; a blocked peer's row goes, armed or not, as does an armed row of a peer that left the targets; nothing once the window ends or the session
    confirms; malformed rows).
  - `MeshManagerTest` (anchors armed before the resets, the rest disarmed; anchors for accepted contacts alone, none
    for a request or a blocked sender, and a block drops one; one re-send past the floor; re-roots floored and capped
    for a never-answering peer moving its prekey).
  - `BackupTablesTest` (`CARRIED`), `DatabaseExportTest`, `KnitDatabaseMigrationTest` (33 → 34).
  - `RestoreOverRelayLabTest`: a relay-only restore, a holder that rotated its prekey past retention while signing 50
    profiles, one that rotated within it, a second reset, a removed contact, malformed and over-armed rows planted in
    the file, a stale anchor, a holder away three days, and the 30-day negative control.
