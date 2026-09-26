---
id: "2026-09.dcah"
slug: the-scope-table-derives-on-its-inputs-events
title: "The scope table derives on its inputs' events"
date: 2026-09-20
topics: [spool, battery]
---

# ADR 2026-09.dcah — The scope table derives on its inputs' events

Status: Accepted (2026-09-20; `RatchetSessions.rootChanges`, `IntroSync.onPairsChanged`, the own-mint
notify, `RECONCILE_INTERVAL_MS` = 60 s). Work item #75, the half ADR 2026-09.wa79 deferred.

`ScopeSync.start` re-derived the whole scope table every 15 s: `ratchet.exportedRoots()` (one Room read
per confirmed peer, behind the ratchet mutex and a write transaction), `GroupRootStore.all()` +
`GroupRepository.active()`, `IntroSync.pairPeers()` with a `peers.find` per pair, `CommonsStore.roots()`,
then an HKDF per scope — 5,760 derivations a day per relay-enabled phone, nearly all of them producing the
table the worker already held. ADR 2026-09.wa79 had stopped the *heal* that used to ride every one of those
runs and left the derivation polling, because two of its inputs had no event.

## What changed

**Every input reports, and the table derives on the report.** `ScopeSync.onScopeTableChanged()` was
already the door for a commons join and a group-root adoption; it now has every other writer behind it:

- A DM session confirmed, replaced or forgotten — `RatchetSessions.rootChanges`, a `SharedFlow<String>`
  of the peer id, collected in `MeshManager.watchScopeInputs`. It is emitted only when the peer's
  *exported view* moved: null while absent or unconfirmed, else (root, prevRoot, prevRootExpiresAt) —
  exactly what `exportedRoots()` would report, compared before and after the critical section, and
  emitted after `locked { }` returns, never under the mutex. The three sites are `commitOpen` (every
  `confirmed` flip is here — responder, replacement, race, **and the initiator's**, which lands when the
  peer's reply seals against one of our epochs), `sealResetDm` (the replacement we mint is unconfirmed,
  so the peer's scopes leave the table until it answers) and `forget`. `sealDm` is not a site: an
  initiation is minted unconfirmed and a seal never moves the root — the issue text placed the initiator's
  flip there, and it is not.
- A pair peer named, evicted or lapsed — `IntroSync.onPairsChanged`, fired from `publish` when the
  `pairPeers()` set (pending ∪ live grace) differs from the last publish. A settle (pending → grace) and
  a send leave the set alone and fire nothing; `prime()` seeds the set silently, because the plane's own
  start derives the table.
- A pending pair peer's bundle pinned — the same callback, from `IntroSync.onProfilePinned` after its
  pending gate. A pair scope derives only once `peers.find(peerId)?.pubKey` exists
  (`MeshManager.pairScopeRoots`), and the pipeline upserts the row before the hook runs. Gated inside the
  driver rather than on `MeshManager`'s `onProfilePinned` lambda so a stranger's profile flood never
  costs a derivation.
- Our own group-root mint (`mintGroupRootsIfDue`), which notified nobody while an adoption did.

**The poll is the net, at 60 s.** What remains without an event is the calendar's: a retiring DM or
group root's drain window closing, a pair scope's 48 h grace lapsing, a swept root. Those wait up to a
minute now instead of fifteen seconds, and each of them only *removes* a scope the peer can no longer
use, so nothing a user is waiting for rides the poll. The poll itself is unchanged in shape: `reconcile()`
wakes a worker only when the adopted table differs, so an extra `onScopeTableChanged()` costs the
derivation and nothing else.

The alternative was to drop the poll and trust the hooks. Not here: the drain-window and grace expiries
are decided on `now` inside `ScopeRegistry`, a hook for them would be a timer keyed on the earliest
expiry — more machinery than a minute's poll — and a missed hook would strand a scope for good rather
than for a minute.

## What it costs, what it does not cover, and the traps

A derivation now runs at each event *and* once a minute — on a busy phone (many sessions confirming,
profiles pinning for pending intros) a few extra derivations, each five suspend reads and no socket. The
lab's margins are the other cost: `InternetPlaneLabTest`'s scenarios used to find a scope inside 15 s
whatever fired; now a scope found only by the poll takes up to 60 s of the 90 s `SPOOL_AWAIT_MS`, so **a
scenario that waits more than a minute for a scope is a missing hook**, not a reason to poke or to widen
the await.

**`ScopeStatus.converged` needs an anchor.** The lab found it on the first loop: `converged` was
`spoolDigests[scope] == localDigests[scope]`, and two absent anchors are equal. Under the poll a scope was
derived only after the dial that the backoff was holding had gone through, so the row never showed the
case; deriving on the confirmation put a scope on the table while its worker was still backing off from a
swallowed socket, `awaitDmScope` read the vacuous `true`, and `aRelayWhoseRouteSwallowsTheSocket…` then
found `connected = false` right after. `converged` is now false until the spool has answered an anchor
for the scope — the honest reading, and what a Diagnostics row should have said all along.

**A copy off a spool is not an overhear.** The second lab finding, three loops out of three, in the
photo-to-the-neighbour scenario: bob confirms alice's session, derives the DM scope at once, pushes her
"hello" to the spool inside `MeshRouter`'s 150 ms relay jitter, and the spool's echo of that push came back
as a duplicate from a second source — `countOverheard` read it as a neighbour having relayed the frame and
cancelled bob's pending relay, so carol, one radio hop behind him, never got it (`suppressed = 1`). Under
the poll the push was seconds after the jitter and the echo a plain dedup. The overhear rule is about radio
neighbours: a spool copy says a relay holds the frame, not that anyone near us heard it, so
`handleInbound` no longer counts a `spool:` source toward suppression (`MeshRouterTest.doesNotSuppressOnADuplicateOffASpool`).

*Amendment (2026-09-25, issue #84).* That covered the spool copy arriving second. Arriving first, it still
counted: `scheduleRelay` seeded the pending relay's `heardFrom` with its source, so a relay that beat the
radio left `{spool:<url>}` in the set, and the originator's radio copy landing inside the jitter made two
"neighbours" and cancelled the hop a relay-less carrier behind us depended on — a minute's wait for the
re-offer (chaos seeds 1000/1004 on the photo-to-the-neighbour scenario). `heardFrom` now holds radio
neighbours only: a `spool:` source seeds nothing and adds nothing. Split horizon is unchanged (a `spool:` id
never names a neighbour), and suppression still fires on two radio copies, exactly as for a relay the radio
delivered first (`MeshRouterTest.doesNotSuppressWhenTheSpoolCopyWasFirst` /
`aSpoolFirstRelayIsStillSuppressedByTwoRadioNeighbours`, `InternetPlaneLabTest.aFrameTheRelayDeliversFirst…`).

**A pair scope and the DM scope that supersedes it share a label.** The third loop finding, in the
card-holders scenario: `Scope.label` is the peer id for both, and the responder derives its DM scope on
its own confirmation while the initiator still holds only the pair scope, so a status lookup by label
compared the two and `awaitDmScope` was satisfied by a converged *pair* scope. `Scope.pair` /
`ScopeStatus.pair` name the difference (Diagnostics suffixes the row "(pair)" beside "(retiring)"), and
`MeshLab.dmScopeStatus` / `scopeStatus` skip it. Under the poll both sides derived on near-simultaneous
ticks and the window never showed.

**Reconciles serialize and coalesce.** The poll was one serial loop; `onScopeTableChanged` launched a
coroutine per call, and two derivations in flight could adopt tables in the wrong order — the older one's
`forgetScopesNotIn` dropping the anchors a newer scope had just earned. A mutex orders them, and one
queued run absorbs every request that lands before it starts (a phone coming back online confirms every
session in a burst); a request during a run queues exactly one more, since the running one may have read
its inputs first (`ScopeSyncTest`'s burst case).

The trap is a hook that fires on an unchanged value — a re-delivered frame, a chain step, a settle, a
re-flooded profile of an already-pinned peer — which is the old poll under another name. The
before/after view compare in `RatchetSessions` and the `lastPairs` diff in `IntroSync` are what keep the
hooks honest; keep them. The other trap is the emission point: `rootChanges` is emitted after the
critical section, but the caller's enclosing write transaction (`InboundPipeline`'s `commit`) may still
be open — the collector reads through `locked` on its own coroutine, so it lands after the commit, and if
it ever did not, the poll covers it. A `ScopeSyncTest` that mutates its `pairs` / `roots` lambdas mid-run
calls `sync.onScopeTableChanged()` after the mutation, as the production hook would; the old tests only
passed because a 15 s poll landed inside their pumps.

Device oracle: `MeshMetrics.spoolTablesDerived` in `…debug.STATE`, one per event and one per minute
idle. Pixel 9, 2026-09-20 (three neighbours, two relays connected, 12 scopes): +1 at the mesh start, +1
sixty seconds later with nothing happening, then `…debug.RATCHET --es reset <Pixel 3>` — +1 in the same
second and the P3's DM scope off the table (12 → 11), the P3's answer confirmed the new session (the
initiator's flip) and +1 within fifteen seconds with the table at 13 (the new scope and the old root
retiring), then back to one a minute; every scope `local == spool`, the retiring one drained.

Regressions: `MeshRouterTest` (a spool copy suppresses nothing, first or duplicate), `IntroSyncTest` (each move fires once, a settle and a send do not, prime is silent, a
stranger's pin is not a pair input), `InboundPipelineTest` (a confirmation reports once, a chain step and
a re-served frame do not, a replacement and our own reset each report, a refused replacement and an
initiation from nothing do not), `ScopeSyncTest`'s pair-scope case, and the `mesh/lab/` spool scenarios
(`awaitDmScope` after `meetOnTheRelay` is the initiator-side proof).
