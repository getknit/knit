---
id: "2026-09.wa79"
slug: a-scope-heals-when-something-changed
title: "A scope heals when something changed"
date: 2026-09-18
topics: [spool, battery, reliability]
---

# ADR 2026-09.wa79 — A scope heals when something changed

Status: Accepted (2026-09-18; `ScopeSync` dirty-driven rounds, `SpoolBackoffPolicy`, the `online` seam, the
per-route dialer client)

Found by the 2026-09-18 battery review. `ScopeSync.start` runs `reconcile()` every 15 s to re-derive the
scope table, and `reconcile()` ended with `worker.wake()` for every configured relay, unconditionally — "a
scope may have appeared since this worker last subscribed". That wake broke the worker's session loop out of
its `withTimeoutOrNull(TICK_INTERVAL_MS = 60 s)` wait, so `healAll` ran four times a minute per relay,
converged or idle, and every `healLocked` began with `store.liveFrames()` — a full custody-table read and
CBOR decode — **per scope**, before the digest comparison that would have said there was nothing to do. A
commons scope read it twice. With one relay and thirty scopes that was on the order of 180,000 idle reads a
day, with the attachment pass's `blobStore.has` and `ahave` round trips on top. `TICK_INTERVAL_MS` was
effectively dead code. Two more costs sat beside it: a relay nobody answers at was dialled ~1,440 times a
day at the 60 s backoff ceiling (each a DNS lookup, a TCP connect and a TLS handshake), with no check that
the phone had a route at all; and one OkHttp client pinged every 25 s whatever the route, which on LTE keeps
the modem out of idle around the clock.

## What changed

**A round is for what changed.** Each worker keeps a `dirty` set of scopes plus an `allDirty` flag. A scope
is marked when its spool digest *moved* (`handleDigest` compares before it stores — the unsolicited fan-out
repeats the anchor we hold, and an absent anchor after a fresh session always counts as moved), when it took
a delivery (`handleEvent`, a pull) or a direct push; every scope is marked when local custody changed
(`onCustodyChanged` → `markAllDirty`). `reconcile()` wakes a worker only when the scope table it adopts
differs from the last (`adoptScopes`). The 60 s tick is a mark like any other — a per-session ticker sets
`tickDue` and wakes — so a round knows whether it is timed, and a scope busy with events still gets its full
pass. `healAll(conn, timed)` drains the marks **before** one `store.liveFrames()` for the whole round (a
mark landing after the drain keeps its token in the conflated wake channel), and hands that list to every
due scope; `commonsFrames` filters it instead of reading again. `republishPresence()` stays first in every
round, because the tick is what lets a presence stamp lapse out of the present set. A round that moved the
local view — frames pulled into custody, frames pushed, a tombstone learnt for a frame we hold — re-marks
its scope once, so the next round shows the anchor now agrees and the status reads converged without
waiting for the tick; an unanswered `list` re-marks too, so a spool that goes quiet still strikes out on the
connection's three-request rule in three request timeouts. A round that only found ids it may not take does
not re-mark, or it would spin; the tick is that case's retry, as it always was.

**Attachments settle.** Outside the digest by design, they used to be re-asked about every round: up to
32 `blobStore.has` and four `ahave` round trips per scope per round, for images both ends already held.
`pushAttachment` and `fetchAttachment` now say whether a later round could act differently; a pushed-whole,
fetched, dead, aged-out or conflicting attachment is *settled* for the connection and skipped until a timed
round finds the entry older than `ATTACHMENT_RECHECK_MS` (ten minutes — a spool may evict what it held, and
nothing on the wire says so) or the session ends. One still pending — no chunks at the spool yet, an upload
half done, a deferral in force, the per-round budget spent — re-marks its scope after `ATTACHMENT_RETRY_MS`
(15 s, the cadence the old reconcile gave every scope), paid only by a scope that has such an attachment.

**A dead relay backs off past the minute.** `SpoolBackoffPolicy` (pure) keeps the first tier every timing
test rides — a second doubling to a minute — and after `LONG_TIER_AFTER` = 10 unreached sessions doubles on
to `LONG_BACKOFF_MS` = 15 min, about 100 dials a day instead of 1,440. A reached session and a new validated
network reset it; a spool's `Retry-After` is a floor under it as before (spec C-7.1-11 mandates a client's
own backoff, the floor and the jitter, and sets no ceiling). Before any dial the worker asks `online()`
(`InternetGate.isOnline`, threaded through `MeshManager`): with no validated route it waits on the redial
channel with a 60 s recheck, dials nothing, and leaves `lastError` and `dialFailures` at the relay's last
real verdict — the relay row already says the phone is offline, and inventing an `unreachable` would blame
the relay for the phone.

**The keepalive follows the route.** `InternetGate.routeKind()` (`NONE`, `WIFI`, `CELLULAR`, `OTHER`;
`AndroidInternetGate` reads it from the validated network's transports, the one place allowed to touch
`NetworkCapabilities`) and `OkHttpSpoolDialer.clientFor`: two clients off one base — one pool, one dispatcher
— pinging every 25 s on Wi-Fi and every four minutes on cellular, picked per dial. A live session is not
re-dialled on a Wi-Fi↔cellular switch: the OS tears the old network down, the socket dies within a ping and
the reconnect picks the right client (ADR 2026-09.vej5's "a live session is left alone").

The alternative a reader reaches for first is to **move the scope derivation itself** off the 15 s poll and
onto events. Two of its four inputs have no event today (a newly confirmed DM ratchet session, a new intro
pair peer); wiring them touches the ratchet and intro code and lengthens every lab wait that rides the poll.
Deferred to the roadmap: the derivation is five suspend reads with no socket and no custody decode, and the
heal it used to trigger was the cost.

## What it costs, what it does not cover, and the traps

A change with no event — a custody sweep, a lapsed deferral, a quarantine horizon — now waits for the tick,
at most 60 s where the accidental 15 s wake used to catch it; `ScopeStatus.converged` can read stale that
long. A spool that silently drops a chunk is noticed at the ten-minute recheck or on the next session, not
the next round (`ScopeSyncTest`'s bitmap-resume and quarantine-horizon cases now reconnect to model it). The
lab's margins shrink the same way: a missed mark costs 60 s, not 15, and `InternetPlaneLabTest` still fits
its 90 s awaits. The cellular ping saves only our half: the reference daemon pings every 30 s of its own
accord (not a spec clause), OkHttp answers automatically, and the modem wakes for it until `knit-spool` takes
a keepalive hint — an additive hello field, recorded in the roadmap.

The trap is a new wake that carries no change. Every `wake()` outside a mark is a round with nothing due,
and every mark that fires on an unchanged value (a repeated digest, a repeated event) is the old 15 s heal
back under another name. The other trap is `republishPresence`: it must stay at the top of every round,
timed or not, or a stamp never lapses.

Regressions in `ScopeSyncTest`: an idle converged relay reads custody once per tick, not per reconcile; one
custody read serves every scope in a round; an unchanged digest does not wake the heal and a changed one
heals at once; a dead relay backs off to fifteen minutes and a new network starts it over; `Retry-After`
still floors the long tier; no route means no dial until one appears; an offline phone keeps the relay's
last verdict. `SpoolBackoffPolicyTest` pins the curve, `AndroidInternetGateTest` the route kinds. Device
oracle: a converged relay's logcat goes quiet between ticks, and `dumpsys netstats` shows the socket's
keepalive at four-minute spacing on cellular.
