---
id: "2026-10.m4fk"
slug: a-scope-config-is-kept-per-issuer-and-sealed-only-under-a-confirmed-session
title: "A scope config is kept per issuer and sealed only under a confirmed session"
date: 2026-10-23
topics: [crypto, spool, wire]
---

# ADR 2026-10.m4fk — A scope config is kept per issuer and sealed only under a confirmed session

Status: Draft (2026-10-23; the first phase of ADR 2026-10.8kfx; `CTL_SCOPE_CONFIG`, `MessageContent.sc`,
`ScopeConfigPayload`, `ScopeConfigPolicy`, `ScopeConfigRepository`, `scope_configs` / `spool_declared` (DB v24),
`InboundPipeline.applyScopeConfig`, `MeshManager.sendScopeConfig`, `RatchetSessions.sealDm`'s `confirmedOnly`,
`GroupRepository.recordDeparture`; spec `docs/SPOOL_PROTOCOL.md` §5, C-5-1..6).

**What was decided before this.** ADR 2026-10.8kfx gives each phone one home relay and carries each conversation's
relay list inside the conversation, as §5's ctl 7. The maintainer chose, after the crypto design pass, that the config
is an ordinary custodied sealed ctl — DM form for a pair, group sender-key form for a group — flooded and refilled like
any other, with no re-push machinery. Spool-only delivery was rejected: `relay` is not in the sealed spool record, so
a pulled copy arrives with `relay = true` and every build floods it anyway (ADR 2026-09.y5f3). This record covers what
the wire phase itself decided.

**One row per issuer, the winner read across them.** The store keeps each issuer's newest config per conversation
(keyed by the peer's id for a DM, the group's id for a group — never a scope id, which rotates with a root) and reads
the conversation's config as the highest `(version, issuer)` among the rows (C-5-4). The design a reader reaches for
first — one row per conversation, overwritten by the winner — depends on arrival order and never recovers, because a
config has no ack (C-5-6) and nothing re-sends it: a member's config that lands *before* that member's leave replaces
the standing winner on one phone, while a phone that heard the leave first refuses it and keeps the old one. With rows
per issuer, `GroupRepository.recordDeparture` drops the leaver's row in the leave's own transaction, so both phones
read the same winner from the same remaining rows. A rejoined member's config sealed before their leave is refused by
its `sentAt` against the member's recorded `leftAt` (clamped, the v6fu clock), so a phone that never held it does not
take it after the phones that did have dropped it. One issuer's two configs under one version (no honest issuer makes
them) order by content, so both arrival orders keep the same one. The rows also let the next phase re-issue per era
without the store assuming one config per conversation for good.

**Bounded on the way in, a refusal is a delivery gate.** At most 8 URLs, `wss://` only through `SpoolUrl.canonical`,
an undialable URL dropped rather than the whole config (a later build may name a scheme this one cannot dial), and
bounds positive and clamped to §12's defaults (TTL ≤ 48 h). A group's config applies only when `sc.groupId` is the group its
frame was sealed to and the sender is in the effective roster; a DM's carries no group id. Every refusal counts
`DropReason.SCOPE_CONFIG_REFUSED`, which is not reset-triggering (the frame opened), and leaves the commit to advance
the chain, so a re-served copy is the ordinary `DUPLICATE`. The sender counts as declared (`spool_declared`) whatever
became of its config: an opened ctl 7 proves its build speaks it. Both tables are carried in a backup (`BackupTables`),
since a restored phone could never ask for a config again.

**The version is a clock, held and deferred, never refused** (C-5-4; the crypto review's second and third rounds,
the maintainer's call "hold, don't refuse"). A refused config's chain advances, so a refusal is final for that frame;
two drafts in a row refused on an input that differs between phones and stranded the phone that differed. The first
bounded the version against the receiver's held winner (64): a phone restored from an older backup, emptied of its rows
or away past custody's day refused an honest issuer for good. The second bounded it against the receiver's clock (10
minutes past the frame's clamped `sentAt`): a receiver whose clock ran slow refused every honest config while it stayed
slow, with nothing to re-send them, and an issuer more than 10 minutes slow could not send at all. So now:

- The version counts whole minutes since 2026-01-01T00:00Z (`ScopeConfigPolicy.VERSION_EPOCH_MS`). An issuer writes
  `nextVersion`, the later of one past the *eligible* winner it reads and its own minute, saturating, and nothing checks
  that against its clock on the way out: a slow issuer still sends, at a low version, and loses to a later one.
- A receiver refuses nothing for its version. It stores every version and judges it only when it reads: a row is
  `eligible` once its version is at most `VERSION_SLACK_MINUTES` (10) past this phone's minute, and `winner` reads the
  eligible rows alone. A row further ahead waits, persisted, so it survives a restart; it becomes readable as the clock
  reaches it (the flows look again at its minute, and at least every 60 s for a stepped clock). An `Int.MAX_VALUE` row
  waits some four thousand years, so a griefer cannot freeze a conversation.
- **One issuer's two rows** (the maintainer left this open). The key carries the version, and `retained` keeps, per
  issuer, the highest eligible row and the lowest `MAX_DEFERRED_PER_ISSUER` (8) deferred ones. The older readable row
  stays the issuer's config until the deferred one becomes eligible, so there is never a gap; the lowest deferred rows
  are the next the clock reaches, and a griefer's far rows are the first to go. Every eligible row below the best one is
  dropped, which is safe because it is outranked on this clock and every later one.

Cost: a phone holds a few rows per issuer instead of one. Past eight re-issues by one issuer inside one clock gap, which
no honest issuer reaches, which deferred rows a phone keeps depends on its clock until the gap closes. A clock stepped
backwards can hide a row that was readable (its lower rows already dropped) until the clock comes forward again. A slow
issuer's config loses ties to anyone with a true clock until it re-issues; a fast one's waits on every receiver until
their clocks reach it, so it cannot win early.

**Sealed only under a confirmed session.** `sendScopeConfig` never opens a session for a config and never seals one
on our own unanswered reset: `RatchetSessions.sealDm(confirmedOnly = true)` returns null unless the row is confirmed,
judged under the ratchet lock, so a session forgotten or re-rooted between a caller's check and the seal cannot turn
the send into an X3DH. A config sealed on the pending reset would carry the reset's init unflagged, which the peer
meets under its hour-long incidental floor (#90, ADR 2026-10.j67x — the rule `broadcastSealedProfile` already
follows). The brief allowed the pending reset's live root; this narrower rule is the reviewer's to confirm. A group
config rides the sender-key chain only while every other member's pairwise session is confirmed, so any seed the send
mints goes out over sessions that exist, and never takes the v1 group fallback. No v1 wrap and no cleartext form: a
null seal sends nothing. The sender writes its own row before the seal, in one transaction with the read that says it is news
(`ScopeConfigRepository.reserve`), and gives it back if the seal sends nothing (`release`). That leaves one window open: a process that dies between
the reservation and the flood keeps a row of ours that no peer ever received. Phase 3's issuing driver closes it by
re-issuing at start whenever our own row is newer than the last config it originated.

**What it costs and does not cover.** A config sealed under a session a reset later abandoned opens under the drained `prevRoot` for its 48 h
and applies like any other; LWW makes the era immaterial here, and the next phase re-issues on a root change. A group
member's config sealed while we were out of the group (rekeyed past us) is never read here, as no group frame of that
era is. Nothing issues a config yet, so no peer sees any of this until the next phase.

Regressions: `ScopeConfigPolicyTest`, `ScopeConfigRepositoryTest`, `GroupRepositoryTest`'s departure case,
`InboundPipelineTest.aDmScopeConfigLands…` / `aGroupScopeConfigFromAMemberLands…`,
`RatchetSessionsExportTest.aConfirmedOnlySealNeverOpensASessionAndNeverCarriesAnInit`, `KnitDatabaseMigrationTest`'s
23 → 24 case, `GoldenVectorTest`'s two `messageContentScopeConfig*` vectors, and `ScopeConfigLabTest` (the tie, the
departed member's config in both orders, a config across a reset, a receiver emptied of its rows and an issuer
restored stale both converging on the next config, a node 20 minutes slow as receiver and as issuer).
