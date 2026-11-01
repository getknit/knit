---
id: "2026-11.b37x"
slug: hiding-a-known-node-is-presentation-only
title: "Hiding a known node is presentation only"
date: 2026-11-01
topics: [contacts, diagnostics, ui]
---

# ADR 2026-11.b37x — Hiding a known node is presentation only

Status: Accepted (2026-11-01) — GitHub issue #54. Amends ADR 2026-09.2ajk (the *Known, not reachable* section
was read-only).

**What was observed.** GitHub #54: Diagnostics lists nodes under *Known, not reachable* and offers no way to
remove them. That section is not a list of its own. It is every `peers` row that no plane reaches right now
(ADR 2026-09.2ajk): anyone whose signed profile ever arrived, contacts and group co-members included.

**What changed.** A long-press selects Known rows. *Hide* in the selection's top bar, or *Hide all* under the
section (the rows past the five shown included), records each row in `HiddenKnownStore`.
Each entry maps a node id to the profile version (`PeerEntity.updatedAt`) its row showed. `DiagnosticsViewModel`
leaves a row out of the section while the peer's profile is that version or older. A node that publishes a
newer profile, which a running phone does at least every 12 h (`PROFILE_REPUBLISH_MS`), comes back on its own.
A hidden node that is reachable shows in its own section regardless. A snackbar's Undo and *Show hidden* bring
rows back. A write prunes the entries no longer in force (the row is gone, or its profile moved on), so the set
stays bounded by what the section lists. Nothing else reads the key, and nothing is sent.

The first build deleted the peer row instead, as the Sybil cap's eviction (`PeerRepository.sweepCap`) does.
Review rejected it for two reasons:

- **The proof of key goes with the row** (`peer_profiles`, ADR 2026-09.g64k). A node this phone still carries
  posts from can no longer have its key proved to a newcomer, so the newcomer's `canCarry` refuses those posts
  and the two custodies disagree until the frames expire (24 h). The cap only evicts past 2,000 rows. *Known,
  not reachable* is exactly the absent-author case the proof exists for.
- **A node forgotten within 10 min of being heard could not pin again.** The profile a neighbour serves back
  carries the frame id this phone had just seen, and the router's `SeenSet` dropped it.

Making a deleted row stick would also need a "don't re-pin" memo, the per-node gate ADR 2026-09.bts9 forbids.
Hiding has none of those costs. Unlike a delete, it frees nothing; the peer table stays capped by `sweepCap`.

**What it costs.** A hidden contact is hidden from this list only; every other screen still shows them. The key
rides a backup like any other setting. The rule is kept true by:

- `DiagnosticsViewModelTest`: a hide holds until the profile moves on, a reachable node shows regardless, and
  dead entries are pruned;
- `DiagnosticsKnownHideTest`: selection, Hide all, Show hidden, and the accessibility action.

The trap: never let mesh code read `HiddenKnownStore`. Hiding a row must never change what this phone
carries, pins or serves.
