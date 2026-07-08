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

**DMs** are carried only when relayed *toward someone else* (gated `!isForMe` in `onDeliver`) and offered
to any newcomer: the recipient delivers + acks, anyone else relays the frame onward. A **delivery receipt
from the addressed recipient** purges the carried copy mesh-wide and tombstones its id
(`ForwardSync.onAck`); because the ack must come *from* the DM's cleartext `recipientId`, a forged receipt
can't evict an undelivered message — the same recipient check also gates `MeshManager.handleReceipt` →
`markReceived` (fixing a prior tick-spoof where any signed receipt flipped the ✓✓). A DM receipt **floods**
(`originateSigned`, `relay = true`) so it reaches the sender across hops *and* is custodied like any flood
frame — delay-tolerant for free.

**Delivery ticks for broadcast/group** have no single recipient, so their receipt is *not* flooded/custodied
(an ack storm + custody bloat): it's a **unicast, point-to-point (`relay = false`) tick** the deliverer sends
straight to the author. That one-shot best-effort send was lost whenever the author was out of range at
delivery time — the *message* converged via custody but the tick did not, leaving the author's ✓✓ missing
"even after convergence". `AckSync` makes it delay-tolerant **without** flooding: the deliverer remembers the
ticks it owes and re-sends them (over a live link when the author is a neighbor → reliable + dropped;
best-effort over the coordination plane otherwise → kept) on every `onNeighborAdded`/`heal` until one lands
or the entry ages out (24 h TTL, bounded, in-memory, self-repopulating on message re-serve like
`KeyExchange`). One surviving receipt flips the ✓✓ ("**≥1 person received it**" — the intended broadcast
semantic); `markReceived` and `ForwardSync.onAck` are idempotent/no-op for these, so duplicate retries are
harmless and never evict custody. Surfaced in Diagnostics/`…debug.STATE` as `receiptsResent`; JVM-tested
(`AckSyncTest`).

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
section below. A carrier stores a message only when its sender is **pinned, not blocked, and its frame
signature verifies** (`MeshManager.canCarry` → `MessageCrypto.verify` over the received `signed` bytes,
authenticating without decrypting — a carrier holds no wrapped key). Notifications fire only on first
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
not another flood. The request is signed (not unsigned like `blobreq`) so a responder authenticates it
against the requester's pinned key — always present, since direct neighbors exchange profiles on connect —
and can ignore a blocked/unknown asker; signing is free precisely because the request never leaves the
direct-neighbor hop. Throttled by a per-peer cooldown + a `missing` set re-asked of each newcomer
(`onNeighborAdded`) and on `heal()`; the in-memory bookkeeping repopulates as profiles re-arrive.
Because `want`/`onRequest` are keyed by **unauthenticated** senderIds (a peer can flood forged ones), that
bookkeeping is **bounded** exactly like `PendingInbound`: `missing` and `wanters` are capped with
oldest-first eviction and `missing` is TTL-swept (`sweepExpired`, on the `heal()`/prune ticks); an outbound
batch is **chunked** (`MAX_IDS_PER_REQ`) so it can never exceed the link's 512 KiB payload ceiling and crash
the writer coroutine; and an inbound request's id list is **capped** (`MAX_REQUEST_IDS`) so it can't drive
unbounded recursion. `BlobExchange` (whose `blobreq` is unsigned) bounds its `fetching`/`wanters`/serve-memo
the same way. Backstopping all of it, both mesh scopes (the app-lifetime scope in `di/MeshModule.kt` and
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
and bounded by a global cap (the real bound — the senderId is an unauthenticated claim), a per-sender cap,
and the TTL. Only the locally-delivered types are held (`FrameType.isReplayable`). `PendingInbound` is now
just the fast path: DM, group, **and** broadcast frames all also degrade gracefully via store-and-forward
re-serve after the buffer expires (broadcast custody closed the old gap where a broadcast frame had the
`PendingInbound` TTL as its only recovery window). Surfaced in Diagnostics (`framesHeld`/`framesReplayed`)
and JVM-tested (`PendingInboundTest`).

## Custody carries our own frames too — the self-frame silent drop

Because custody now carries our **own** frames too, `verifyInbound` short-circuits any frame whose
`senderId` is our own nodeId — a silent local no-op drop *before* the `NO_SENDER_KEY` path. A neighbor
re-serves a carried copy of a `chat`/`reaction` we originated, and its re-flood reaches us again once our
`SeenSet` window has lapsed; since a node never pins its **own** key in `peers` (`handleProfile` only
upserts inbound senders), that copy would otherwise be counted as `NO_SENDER_KEY`, parked in
`PendingInbound` until its TTL (no self-profile ever arrives to release it), and trigger a
`keyExchange.want(self)` no-op — pure noise for a message we already delivered at origination. We drop it
silently; the router still relays it and neighbors dedup it one hop out. (Field-observed 2026-07-02, idle
3-node mesh: a slow drip of `drop chat/reaction … no key to verify it` from the node's *own* id, with
`keyReq` stuck at 0 — the `KeyExchange.want` self-guard — and `framesHeld` climbing.)
