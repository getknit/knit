---
id: "2026-09.adgd"
slug: removing-a-contact-undoes-our-own-signals-and-tells-no-one
title: "Removing a contact undoes our own signals and tells no one"
date: 2026-09-29
topics: [contacts, privacy, ui, commons]
---

# ADR 2026-09.adgd — Removing a contact undoes our own signals and tells no one

Status: Accepted (2026-09-29) — GitHub issue #23, branch `feat/remove-contact`. Amends ADR 2026-09.wx8e (a
commons member is accepted on first sighting only).

**What was observed.** Issue #23: a user cannot remove anyone from their contacts. The only subtraction
the app had was Block, and blocking is a different promise. A contact is not stored anywhere. It is derived
(`ui/contacts/ContactUniverse.kt` `contactIds`, the rule the picker and search share, ADR 2026-09.wnh6) from
four signals, less the blocked and ourselves:

- the explicit accept (`SettingsStore.acceptedConversations`, written by a card import, an accepted request,
  the profile's Message button and a commons sighting);
- the out-of-band verification (`peers.verified`);
- a DM thread we wrote in (the "authored" half of `Conversations.isAccepted`, ADR 009);
- co-membership of an accepted, active group.

`accept()` had no inverse and `IntroSync` had no cancel. A removal that cleared only the accept left anyone
we had written to exactly where they were.

**What changed.** `contacts/ContactRemover` (the importer's inverse, one Koin single) clears each signal of
our own, in this order:

1. accept every group whose acceptance rested on this peer alone (`groupsAcceptedOnlyThrough`);
2. withdraw a pending card intro (`MeshController.cancelIntro` → `IntroSync.cancel`, which drops the pair
   scope through the existing `lastPairs` diff and reports it once, ADR 2026-09.dcah);
3. forget the thread's notifications and conversation shortcut (`Notifier.forgetConversation`);
4. delete the DM thread and its draft;
5. clear the verification;
6. `SettingsStore.unaccept`, **last**.

Every step is idempotent and the accept is the signal most often present, so a removal cut short leaves the
person a contact, Remove still offered, and a second run finishes it. The writes run under
`NonCancellable`, and there is no transaction: the accepted set lives in DataStore and cannot join one, and
every state between two steps is one the rules already allow. What remains is a stranger — their next DM
lands in Message Requests, by ADR 009's one predicate, unchanged.

Step 1 exists because `isAccepted` admits a group when a known peer has posted in it. If the removed peer
was the only such sender, clearing them would move the group into Requests and take every member it lends
to Contacts along with it — and the confirm, which names the groups that keep the person, would be wrong the
moment it closed. Removing one person never demotes a group.

A **group co-member stays a contact**: only the member's own signed leave shrinks a roster (ADR 2026-09.v6fu),
and editing someone else out is not ours to do. The profile's confirm (`ContactRemoval.Removes.keptBy`) names
those groups, and a person held by groups alone gets an explanation with OK and no Remove
(`ContactRemoval.GroupsOnly`). The groups named are the ones `contactIds` counts (accepted, not left) —
never the profile's In common list, which includes invitations still in Requests.

A **commons member** was re-accepted by every pulled frame, and a restart re-pulls the whole room, so a
removal lasted until the next launch. `MeshManager.onCommonsMember` now accepts on a member's first sighting
in a room (`CommonsStore.isMember`), before recording them, as a card is imported once. Leaving the room
purges its members, so rejoining makes them contacts again.

The profile offers Remove only while the person is a contact by the same rule (`contactStanding`, pinned to
agree with `contactIds` over every signal combination in `ContactUniverseTest`), never for ourselves and
never until our own id resolves. When the profile sits on that person's DM — the thread the removal just
deleted — closing it pops past the chat too, as Leave does for a left group (`KnitApp.parentIsDmWith`).

**What is never touched**, and why each is the obvious alternative that does not work:

- **The peer row.** Deleting it looks like "forget them". But the row is the key pin: `canCarry` needs it to
  carry their frames (ADR 2026-09.bts9), labels are computed over it (ADR 058), and its signed profile
  (`peer_profiles`, ADR 2026-09.g64k) goes with it. Their next profile flood re-inserts it anyway.
- **The ratchet session.** `RatchetSessions.forget` sends nothing, but the peer's next DM then fails to open
  and asks for a reset — the removal becomes visible — and the DM spool scope goes with it (ADR 032).
- **Custody, `KeyExchange.want`, the block list, intro grace, `last_read_<id>`.** No per-node input enters
  custody (bts9). Grace is session plumbing that lapses on its own. The read watermark is left by every other
  delete too.
- **A stored "removed" set** overriding `contactIds` and `isAccepted`. It would keep the history but fork
  ADR 009's shared predicate, and the kept thread would fall into Requests and the stranger retention cap
  (ADR 2026-09.z58t) — history kept only to be pruned a week later. The maintainer chose to delete the DM,
  as Briar's "Delete contact" does.

**What it costs.** Nothing is sent: the peer is not told, still gets our sealed profile updates over the
session, and still gets our tick on their next DM (a request is acked like any DM). Deleting the thread
re-arms the open-to-chat cue for them (ADR 2026-09.3yje calls that honest). Their peer row loses its sweep
protection and becomes evictable under the 2,000-row cap, as any stranger's is. A launcher shortcut the user
pinned by hand stays. A thread deleted on the remover stays deleted: a re-served copy fails the ratchet as a
duplicate, which the reset heuristic ignores. The same holds for the one internal path that re-enters a
custodied frame with no message row, `MeshManager.replayCustodiedSeedDms`. The regressions are
`ContactRemoverTest` (the order, the group pin, what is never touched, the cancellation),
`ContactUniverseTest` (standing ≡ `contactIds`), `IntroSyncTest`'s cancel cases, `MeshManagerTest`'s commons
first-sighting cases, and `BlockAndRequestLabTest.aRemovedContactsNextDmIsARequestAndTheRemovalStaysInvisible`
— the full oracle over a room after the removal, since custody, profiles and the session must not move.
