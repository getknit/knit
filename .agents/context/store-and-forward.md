# Store-and-forward message delivery (implemented)

Delay-tolerant custody, digest convergence, and the key-gap recovery paths. The **convergence rule of
thumb** (anything the content digest folds over must be bounded identically on every node) is stated as
an invariant in `rules/mesh.md`; this file is the full mechanism.

The mesh floods a frame once and forgets it, so a message whose recipient (or a path to them) isn't
connected at that instant never arrives. **`ForwardSync` + `ForwardStore`** add delay-tolerant custody
for **every floodable frame** — chat (1:1 DMs, group messages, and the plaintext broadcast room) plus the
small metadata frames (`reaction`/`receipt`/`groupupdate`/`groupleave`/`profile`) — so two phones that meet
only briefly backfill each other's ambient history (the festival case). The subsections below detail the
chat cases (the delivery-critical ones); the metadata types ride the same custody path
(`FrameType.isCustodial`). A node persists the messages it originates (`ORIGIN_SELF`) or relays
(`ORIGIN_RELAY`) into the encrypted `forward_store` table, and when a neighbor joins (`watchNeighbors`
newcomers → `ForwardSync.onNeighborAdded`) **unicasts** the carried ones to it (skipping a
per-peer-per-session memo + the message's own author). Re-served frames re-enter the existing
`handleInbound` path — deliver + relay, no separate delivery code. A stored frame keeps only its immutable
signed blob + signature (`CarriedFrame`); a fresh `WireEnvelope` (full `ttl`, `hops=0`) is stamped around
it on re-serve, so it re-floods with a full hop budget when re-served much later (the signature covers
neither ttl nor hops, which live in the unsigned wrapper).

**DMs** are carried by every node that sees them — **including the recipient** (the old `!isForMe`
exclusion is gone, ADR 018): with sealed receipts nobody vaccine-purges, so if the recipient's digest
never folded a delivered DM, every carrier would re-cue a re-serve of it for the full TTL. A DM
receipt **floods** (`originateSigned`, `relay = true`) so it reaches the sender across hops and is
custodied like any flood frame — delay-tolerant for free. Its form splits custody behavior
(docs/ENCRYPTED_RECEIPTS_REACTIONS.md):

- **Sealed receipt** (capable author: a `CTL_RECEIPT` v2 ctl chat frame, wire-indistinguishable from
  conversation) — **no vaccine-purge anywhere** (a carrier can't read it); the delivered DM + the
  receipt age out on the frame-global 24 h TTL uniformly on every node, like group/broadcast custody
  always has. The rule keys on the frame's form, identical at every observer — that is what keeps
  ADR 006 convergent.
- **Cleartext receipt** (legacy fallback, accepted inbound forever) — purges the carried copy
  mesh-wide and tombstones its id (`ForwardSync.onAck`), the recipient's own custody row included
  (the ack path self-vaccinates after originating). Because the ack must come *from* the DM's
  cleartext `recipientId`, a forged receipt can't evict an undelivered message — the same recipient
  check gates `markReceived` (fixing a prior tick-spoof), and its null arm keeps the group/broadcast
  best-effort tick working for the sealed form.

**Delivery ticks for broadcast/group** have no single recipient; `AckSync` owns their delay tolerance with
a three-way rule keyed at the deliverer (ADR 033 — a local emission choice, never a carry rule). Toward a
**live-linked** author it stays a **unicast, point-to-point (`relay = false`) tick** over that link — a
ratchet-capable author's tick sealed as a `CTL_RECEIPT` ctl DM, sealed **once** at `owe()` time and re-sent
verbatim (sealing consumes a DM chain key; per-retry re-sealing would starve real DMs of the skipped-key
budget) — zero custody load for the co-present case. Toward an **absent sealed-capable author of a group
message**, the acks batch per author (≤64 ids, 45 s debounce, heal as the backstop) and escalate as **one**
sealed ctl frame carrying every pending id (`MessageContent.acks`) originated `relay = true` — flooded,
**custodied**, and spool-eligible via the DM-scope frame-set rule — so the tick now converges exactly like
the message it acks; escalated ids are remembered so the exists-gate's re-ack on custody re-serves no-ops,
an author who links mid-debounce gets the batch over the link instead, and a failed flush falls back to
per-id cleartext entries. Toward a **legacy (cleartext) author** — and for every broadcast-room tick, which
deliberately never escalates (the ambient class; and a cleartext receipt in custody would re-leak the
delivery event ADR 018 sealed away) — the pre-033 loop is unchanged: remember the owed tick and re-send
(live link → reliable + dropped; best-effort coordination plane → kept) on every `onNeighborAdded`/`heal`
until it lands or ages out (24 h TTL, bounded, in-memory, self-repopulating on message re-serve like
`KeyExchange`). One surviving receipt flips the ✓✓ ("**≥1 person received it**" — the intended broadcast
semantic); `markReceived` and `ForwardSync.onAck` are idempotent/no-op for these, so duplicate retries and
custody re-serves are harmless and never evict custody. A sealed tick is too big for the ~255 B coordination
plane (a *batch* outgrows even the ≤2-fragment compact form — pinned by `CoordinationPlaneSizeBudgetTest` —
so batches structurally never `fastSend`), and is deliberately never downgraded to cleartext, which would
make the form an on-path link-state observable. Surfaced in Diagnostics/`…debug.STATE` as `receiptsResent` +
`receiptsSealed` + `receiptsCustodied`; JVM-tested (`AckSyncTest`).

**Group messages** carry a cleartext member roster (`RelayEnvelope.group.members`) on every frame, so custody
exploits it: a node carries a group message whether or not it is itself a member (for other members who
may be offline), but **push is member-targeted** — `onNeighborAdded` offers a carried group frame only to
a roster member; once any member receives it, the normal flood re-distributes it to the rest, so there's
no spraying group traffic at non-members. A group has no single recipient and no reliable per-member ack,
so it is **never vaccine-purged** — the TTL/cap sweep is its only bound.

**Broadcast-room messages** (`recipientId == null && group == null` — the natural discriminator, no schema
column) are carried too and offered to **every** newcomer (no destination to target), gated only by the
capture path explicitly carrying them (`isForMe(null)` is true, so the DM `!isForMe` gate would otherwise
skip them — see `MeshManager.onDeliver`). Like a group they have no ack, so a **shorter TTL** + a
**broadcast quota** are their only bound; they are never vaccine-purged. `isStorable()` = `FrameType.isCustodial`:
**every floodable type**, not just `chat` — the `isReplayable` family (`chat`/`reaction`/`receipt`/`groupupdate`/`groupleave`)
plus `profile` — so the whole mesh converges on the same state (reactions, receipts, renames, and keys included),
not only the one-hop peers that happened to be present when each first flooded.

## Bounds (`ForwardRepository`)

A per-message **TTL** sweep (startup + a 10-min loop + the heartbeat `heal()`; broadcast gets a shorter
TTL than DMs/groups), a **global cap**, a **per-sender quota**, a **per-group quota**, and a **broadcast
quota** — each enforced by evicting its **oldest frame by `sentAt`** (a frame-global key, so every node
keeps the identical newest-N and their content digests converge) rather than refusing the new one, and
applied to **our own sends too** (not just relayed traffic). Ordering by a per-node key (`receivedAt`) or
exempting `ORIGIN_SELF` breaks convergence and churns the cue plane forever — see the convergent-quota
section below. A carrier stores a message only when its sender is **pinned and its frame signature
verifies** (`InboundPipeline.canCarry` → `MessageCrypto.verify` over the received `signed` bytes,
authenticating without decrypting — a carrier holds no wrapped key). The block list is **never** read there
(ADR 010, ADR 2026-09.bts9): it is a delivery-path input, and a per-node input in the carry gate diverges that
node's digest from every peer's for as long as it holds — a blocker carries, re-serves and accounts a blocked
sender's frames like anyone else's and drops them only on delivery. Notifications fire only on first
delivery (`deliverChat` `isNew` gate, conversation-agnostic) so a re-served message (after the 10-min
`SeenSet` window, or a restart that empties it) never replays. The pure logic (`ForwardSync`,
`ForwardStore`) is JVM-tested with `FakeLoopTransport` (`ForwardSyncTest`).

## A bounded custody quota must be *convergent*, or one chatty node churns the mesh forever

The store-and-forward caps (`ForwardRepository`) bound how many carried frames a node holds per sender /
group / broadcast room. The Wi-Fi Aware cue plane advertises a **content digest** — an XOR over the held
frame-id set (`StoreDigest`) — and brings up a scarce NDP *only* when two peers' digests differ
(`DigestTracker`'s identical-digest skip). So the quota and the digest are coupled: if a node can hold a
frame set a *peer* can never match, their digests never converge and every larger peer re-attempts an NDP
on every cue — **forever**. The original quota broke this two ways, and both had to be fixed (DB v18,
`forward_store.sentAt`): (1) it applied **only to relayed traffic**, so an originator kept *all* its own
sends (`ORIGIN_SELF`) while carriers capped at the quota — field-observed: node `a4gjrq5w` authored 117
custodial frames vs. the 100 per-sender quota, peers held 100, it held 117, so it out-diverged them
permanently and the radio never idled (a slow drip of NDP setup/teardown + re-attach churn, `wanted`
never emptying); and (2) it **refused the new frame** when full and evicted by local `receivedAt`/origin —
both per-node, so even matched counts kept *different* sets. The fix: trim each over-quota bucket to its
newest-N by the **frame-global `(sentAt, id)`** on **every** origin, so all nodes keep the identical set
and the digests converge. A third breakage of the same rule was TTL expiry (work item #8): the digest
folded **all** rows while a sync exchanges only live ones, so an expired-but-unswept row (sweep ticks
phase per-node) opened a divergence window of up to a sweep period at every TTL boundary. Now **expired
rows are invisible everywhere observable**: the digest folds live ids only (`StoreDigest.current()` folds
lapsed ids out lazily at each read — no expiry timer for Doze to defer), the quota counts/evictions are
live-filtered (buckets mix TTL classes, so an expired short-TTL row can be newer by `sentAt` than a live
long-TTL row — counting it would evict a live row on the unswept node only), and a frame past its
frame-global expiry is **refused at store time** (dead-on-arrival guard, which is also what stops a
skewed-clock peer's re-serve resurrecting a swept frame); the sweep is pure storage GC and digest-neutral.

Rule of thumb: **anything the content digest is folded over must be bounded by a rule that's identical on
every node** (same key, same direction, same origins, same liveness) — which makes the **TTL constants
(`DEFAULT_TTL_MS`/`DEFAULT_BROADCAST_TTL_MS`) and the broadcast-chat classification
convergence-critical**: two app versions that disagree hold different live sets *continuously* for the
whole TTL delta, so treat changing them like a wire change. Verify with `…debug.STORE`:
**`liveFingerprint` must match across devices** (`digestVersion == liveFingerprint` is the local
invariant; `allFingerprint` legitimately lags by expired residue until the sweep and is NOT
fleet-comparable at a TTL boundary) and every sender's carried count must be ≤ its quota. (Aside: the
per-sender bucket lumps a node's profile in with its chat, so a node that sends >quota frames evicts its
own profile *frame* from custody — harmless, since the pinned key + connect-time `pushProfileTo` / edit
re-broadcast are the real profile paths, and every node evicts it alike.)

## Retransmit-on-key-arrival (outbound key gap)

A complementary path closes the most common "it never sent" for DMs: a DM composed before the recipient's
key is known is saved `pendingKey` (not flooded), and `handleProfile` re-seals + floods it once the key
arrives (`flushPendingFor`). Groups don't get this — see `memory/roadmap.md`.

## KeyExchange / `keyreq` (inbound key gap)

The **inbound** complement (`KeyExchange`, `keyreq`) closes the *receive* side: a profile floods once on
connect/edit under a one-shot `SeenSet`-deduped id, so a node that joined late or sits more than one hop
from the originator can permanently miss it and then drop every frame that peer floods with
`NO_SENDER_KEY`. When `verifyInbound` hits that drop it calls `keyExchange.want(senderId)`, which sends a
**signed, point-to-point** (`relay = false`) `keyreq` to its neighbors; a neighbor that holds the peer's
profile re-serves it verbatim (the response rides the existing self-certifying `profile` path — no new
response type, no re-signing), and one that doesn't records the requester and recurses, so the profile
walks hop-by-hop to the requester exactly like a `BlobExchange` blob pull — deliberately request/response,
not another flood. The requester **custodies** the served frame even though it arrived `relay = false`
(`InboundPipeline.onDeliver`'s gate, ADR 2026-09.7bu7): a profile is the one custodial type that is not
addressed to anyone, and without carrying it the requester's seen mark deduped the holder's next custody
re-serve for the rest of the 10-minute window, so the digests disagreed for up to eleven minutes after every
key request. The request is signed (not unsigned like `blobreq`) so a responder authenticates it
against the requester's pinned key — always present, since direct neighbors exchange profiles on connect —
and can ignore a blocked/unknown asker; signing is free precisely because the request never leaves the
direct-neighbor hop. Throttled by a per-peer cooldown + a `missing` set re-asked of each newcomer
(`onNeighborAdded`) and on `heal()`; the in-memory bookkeeping repopulates as profiles re-arrive.
Because `want`/`onRequest` are keyed by **unauthenticated** senderIds (a peer can flood forged ones), that
bookkeeping is **bounded** exactly like `PendingInbound`: `missing` and `wanters` are capped with
oldest-first eviction and `missing` is TTL-swept (`sweepExpired`, on the `heal()`/prune ticks); an outbound
batch is **chunked** (`MAX_IDS_PER_REQ`) so it can never exceed the link's 512 KiB payload ceiling and crash
the writer coroutine; and an inbound request's id list is **capped** (`MAX_REQUEST_IDS`) so it can't drive
unbounded recursion. `BlobExchange` (whose `blobreq` is unsigned) bounds its `fetching`/serve-memo the same
way (it keeps no wanter set since ADR 2026-09.4tx5 — a blob is served only to a fresh ask). Backstopping all of it, both mesh scopes (the app-lifetime scope in `di/MeshModule.kt` and
`MeshManager`'s session scope) carry a shared `meshExceptionHandler`, and a `FramedLink` writer drops (never
dies on) a record the codec rejects. Recovery is visible in Diagnostics
(`keyRequestsSent`/`keysServed`/`keysRecovered`) and JVM-tested with `FakeLoopTransport` (`KeyExchangeTest`).
`handleProfile` also gained a last-writer-wins `sentAt` guard so a re-served (older) profile can never
revert a newer name/status — the key itself is immutable per nodeId.

## PendingInbound (park-until-key)

The dropped frame that *triggered* the request is no longer lost: `verifyInbound` also **parks** it in
`PendingInbound` (an in-memory, bounded, ~2-min-TTL buffer — the inbound complement of the outbound
`flushPendingFor`), and once `handleProfile` pins the key it **replays** every parked frame for that
sender back through `onDeliver` (`pendingInbound.release(...)`, the last statement so the key + any
deviceTag block are applied first). Replay bypasses the router (no re-flood, no `SeenSet` hit) and
`deliverChat`'s `isNew`/idempotent-save gates keep a later store-and-forward re-serve a no-op. The buffer
is in-memory by design (a parked frame is unauthenticated until its key arrives, so it's never persisted)
and bounded by a global frame cap and a 4 MiB byte budget (the real bounds — the senderId is an
unauthenticated claim), a per-sender cap, and the TTL. Only the locally-delivered types are held
(`FrameType.isReplayable`).

The per-sender cap is a carrier's whole custody quota for one sender (200), and the carrier serves a
sender's `profile` ahead of the rest of a digest reply (`ForwardSync.onDigest`) — both ADR 2026-09.9xuu. A
newcomer served the backlog of someone it has never met used to park 16 frames and refuse the rest, and
the router had already marked every one of them seen, so the rest stayed undelivered and out of its custody
for the whole ten-minute window (on Bluetooth `LinkCrossings` also keeps the carrier from re-writing them).
With the profile first the newcomer refuses nothing; when the carrier no longer holds the profile (the
per-sender quota evicts a chatty sender's oldest frame, which is its profile) the key comes by `keyreq`
and the whole backlog is parked to replay. `MeshManager` injects the custody quota as the park's cap
(pinned by `PendingInboundTest`); below it, the tail of every such backlog is ten minutes late again.
Pinned end to end by `StrangerBacklogLabTest`. `PendingInbound` is now
just the fast path: DM, group, **and** broadcast frames all also degrade gracefully via store-and-forward
re-serve after the buffer expires (broadcast custody closed the old gap where a broadcast frame had the
`PendingInbound` TTL as its only recovery window). Surfaced in Diagnostics (`framesHeld`/`framesReplayed`)
and JVM-tested (`PendingInboundTest`).

## PendingGroupKeys (park-until-roster)

The group-key sibling, keyed by **group id**. A member learns of a new group from the roster on its first
frame (`reconcileGroup`), but the creator floods the sender-key seed (`CTL_GROUP_KEY`) *before* that frame,
and custody serves the two in either order — so the seed can land on a phone with no group row, where
adoption is (correctly) refused. The DM ratchet used to consume the frame anyway, which lost the seed for
good: every re-serve was `RATCHET_DUPLICATE`, the group's first message sat at `GROUP_RATCHET_NO_KEY`, and
no re-send trigger was due (lab repro 2026-09-10, Pixel 9 → Pixel 7). `decryptAndDeliverV2` now decides on
the lock-free peek: a seed for a group with no row is parked **before the ratchet commit**, so the chain
never advances past it, and `reconcileGroup` replays it (relay = false, like the custody replay) as its
last step once the row is committed — the replay then adopts, acks, and `replayGroupCustody` decrypts the
frame that carried the roster. The same hold covers the race's second shape — a sender we hold as
*departed*, whose seed for a re-created same-members group floods ahead of the frame that rejoins them
(`rejoinBy`; the log line says `sender departed`). Unlike `PendingInbound` the parked frame is already
authenticated, so the TTL is long (1 h, well inside the 48 h skipped-key retention that keeps the replay
openable); bounded by a per-group cap and a global cap. Oracles: `groupSeedsHeld`/`groupSeedsReplayed` in `…debug.STATE` and the
metrics line, and `holding group key … not held yet` under `MeshManager`. JVM-tested (`PendingGroupKeysTest`,
the three `InboundPipelineTest` seed-before-roster cases); device-verified 2026-09-10. **The park does not
survive a restart**, and a parked-then-lost seed is one nobody re-serves (we custodied it before parking, so
our digest already folds it) while the creator's re-send sits behind its 15-min floor and one unreadable
frame never reaches the key-request heuristic. So `reconcileGroup` also re-feeds, on first sight of a group
only, our own custody's ratchet-form chat DMs from that roster that produced no message row
(`MeshManager.replayCustodiedSeedDms`) — idempotent for the same reasons the group-frame replay is. Pinned by
`mesh/lab`'s restart-with-a-parked-seed scenario, which found it.

## Custody carries our own frames too — a self frame is verified, re-carried, and never pinned

Because custody carries our **own** frames too, a neighbor re-serves a carried copy of a frame we
originated and its re-flood reaches us again once our `SeenSet` window has lapsed. A node never pins its
**own** key in `peers`, so `verifyInbound` resolves a self frame against our identity's own bundle
(`verifierBundle`, commit `2cdf2332`) rather than the pinned-key lookup — it used to be a silent drop, which
after a custody wipe stopped us ever re-carrying our own sends, so digests never reconverged. The frame
then reaches `onDeliver`: custody re-takes it (idempotent), `deliverChat` no-ops on its `isNew` gate. Two
self shapes are refused *after* custody, at the type dispatch, and both must stay refused:

- **Our own `profile` never pins** (`handleProfile` returns on `senderId == me`). When it did (2026-07-04
  → 2026-09-13), the row `peers[me]` carried our key, `CAP_RATCHET` and prekey, and every path that treats
  a pinned row as a sealable peer took us for one: `flushGroupKeys(me)` sealed every group's seed to
  ourselves, the ratchet opened a session with ourselves, the echo could not be opened (no receive side)
  and tripped `maybeRequestReset(me)`, and the reset's `force` flush re-sealed the seeds — a
  self-sustaining loop of sealed self-addressed `chat` frames, one every hour or two on every phone,
  flooded, custodied by every carrier for 24 h, and carried over LoRa airtime as DM-form traffic. Found
  through the Your mesh screen's custody count (51 "for others" on the P9, 15 of them X→X). Pinned by
  `InboundPipelineTest.ourOwnProfileLoopingBackIsCustodiedButNeverPinnedAndFlushesNothing` and the
  no-self-row check in `MeshLab.assertConverged`; at mesh start `PeerRepository.forgetSelf` sweeps the row
  on a device that already has one, `RatchetSessions.forget(me)` the session the ratchet opened with
  ourselves behind it, and `GroupRepository.forgetSelf` the seed-outbox rows the group flush wrote toward
  us — the ratchet dump walks `peers`, so neither of the latter shows once the row is gone.
- **A DM from us to us is dropped before the decrypt** (`handleChat`): no thread has that shape and every
  sealed ctl DM is addressed to someone else, so the only ones in existence are that loop's residue, and
  opening one is what fed the reset heuristic. Our own broadcast posts and group frames carry no recipient
  and are untouched.
