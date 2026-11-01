---
id: "2026-10.9qr4"
slug: an-inbound-frame-s-id-has-the-shape-its-type-is-minted-with
title: "An inbound frame's id has the shape its type is minted with"
date: 2026-10-18
topics: [wire, custody, spool, security]
---

# ADR 2026-10.9qr4 — An inbound frame's id has the shape its type is minted with

Status: Accepted (2026-10-18)

**What was observed.** knit-ios found this while reviewing its own store's text decode (#178). Room runs on
SQLCipher's driver, which binds every string parameter through `sqlite3_bind_text16`. SQLite drops one leading U+FEFF
from a UTF-16 value, and after a leading U+FFFE it byte-swaps the rest. Every in-memory set keeps `"﻿" + X` apart
from `X` (the router's `SeenSet`, `ForwardSync`'s `acked`, `PendingInbound`), and every table merges them. So a signed
frame of anyone's under the twin id passed the router's dedup and then took `X`'s custody row (`ForwardSync.onSeen`
returns at `store.has`) and its message row (`MessageDao.insertIfAbsent`). The real `X` was refused when it came, and
the digest (`ForwardDao.liveIds`, read from the column) named `X`, so no neighbour handed it over again. Custody also
re-sent the twin to every peer at every exchange: `liveFrames` decodes `"﻿" + X` from the signed bytes while every
digest lists the column's `X`, so the diff never matched. An iPhone, which keeps every byte, re-sent it to each Android
neighbour the same way. The commons door never meets the router, so a twin there could take a DM's message row. The
id was documented as opaque, and no one checked its shape.

**What changed.** Every inbound door refuses a frame whose id is not the shape its type is minted with
(`FrameId.admits`):

| `type` | `id` |
| --- | --- |
| `profile` | exactly `profile-<senderId>-<sentAt>`, the sender a canonical node id and the stamp in decimal |
| `talk` | exactly `FrameId.TALK_LENGTH` (11) base64url characters, the walkie chunk's prefix of a fresh id |
| anything else, unknown types included | what `FrameId.new` mints: 22 base64url characters that round-trip through 16 bytes (`FrameId.toBytesOrNull`) |

Every shape is ASCII, so no admitted id has a leading mark for SQLite to drop. The profile id is bound to its sender,
so nobody can file a frame under another node's profile id. Two doors ask:

- `MeshRouter.handleInbound`, first, before the ingress meter, the dedup, verify, custody and relay. Every radio plane
  and every spool frame or cast that is delivered arrives here. A refusal counts `DropReason.NONCANONICAL_ID` and is
  never marked seen.
- `ScopeFrames.open`, next to the frame-set rule (spec C-4.4-14). It is the commons door's only gate, and it
  quarantines the blob (§9.3). A refusal at the router alone would leave custody's unmarked twin answering
  `store.has`, so §9.6 would never account the blob and every connection would pull it again.

This partitions no build. Every tag from v2.0.0 to v2.9.0 mints only `FrameId.new` ids and `profile-$me-$stamp` (FrameId
arrived in be535806, before the first tag), and the iOS port mints the same two. Every vector frame complies except
`wire-v1.json`'s decode-only `"m1"`.

The issue's first option was to bind ids as UTF-8 bytes or BLOBs, as knit-ios does. That migrates every key column and
changes every query that binds an id, and the store would still hold whatever a crafted frame put there. Stripping a
leading U+FEFF on both platforms keeps the collision by design.

**Not changed, on purpose.**

- `WireCodec.decodeEnvelope`: the frozen `"m1"` vector decodes on both platforms.
- `InboundPipeline.onDeliver` and `ForwardSync.onSeen`: about 270 unit tests feed labels there, and every network path
  reaches them through a gated door. The custody replays (`replayCustodiedGroupFrames`, `replayCustodiedSeedDms`)
  read rows stored before this gate; custody keeps those for at most 24 h.
- Ids inside payloads (`ReceiptContent.ackId`, a reaction's `messageId`, `ReplyRef.messageId`) still bind
  unchecked, and the mark still drops there. They displace nothing: a receipt acts only when its acker is the
  row's recipient, who could ack the real id anyway, and a reactor writes only its own row.
- No migration: no honest client minted such an id.

**What it costs.** An id is no longer opaque on the way in. A new id shape, or a change to `FrameId`'s format, is a
wire break: every build before it drops the new frames (`docs/NEXT_WIRE_BREAK.md`, `docs/WIRE_COMPAT.md`). The iOS port
must refuse the same shapes at its doors (knit-ios#4); until it does, an iPhone still delivers and custodies such
frames.

**What it does not cover.** A frame filed under another frame's *exact* id still wins first-write wherever it lands
first. Ids are 128 random bits bound to nobody, so a squatter needs only to see `X` on the air (ADR 2026-10.gpa3). Closing
that means binding an id to its author, a wire break parked as `docs/NEXT_WIRE_BREAK.md` §10. This gate is the shape
half that §10's refusal will need anyway. A link HELLO's node id is not checked for canonical form either, and a
U+FEFF-led one reaches the `peers` table the same way (#199).

Regression: `FrameIdTest` (every minted shape admitted; marked, swapped, short, long, non-round-tripping, wrong-sender
and wrong-type ids refused), `MeshRouterTest.aFrameWhoseIdIsNotCanonicalIsRefusedBeforeDeliveryRelayAndDedup`,
`ScopeFramesTest`'s non-canonical blob case, `CommonsSyncTest`'s quarantined post, and `mesh/lab/CanonicalIdLabTest`
(a signed twin flooded ahead of the real post is refused, and the three nodes converge). The lab's other scenarios
exercise only ids the app mints, so they would fail if the gate refused an honest shape.

## Amendment 2026-10-19 — a link's node id is canonical too (#199)

The last gap above is closed. A neighbour's node id reaches us as text through three doors: the link HELLO
(`LinkHandshake.readHello`), the Wi-Fi Aware publish SSI (`WifiAwareTransport.onDiscovered`) and the Wi-Fi Aware cue
(`NanCueCodec.parseCue`). None checked the id, so a HELLO naming `"﻿" + V` got past both transports' duplicate-link
checks, which compare Kotlin strings, while it read and wrote `V`'s `peers` row. An avatar pushed over that link landed
on `V`'s profile (`InboundPipeline.pointPeerAtAvatar`) even while `V` itself was linked, and the same id stamped `V`'s
first sighting in `met_peers`.

`Protocol.parse` now answers null unless the first segment is a canonical node id (`NodeId.toBytesOrNull`: 26
lowercase base32 characters that round-trip through 16 bytes), and `parseCue` refuses the same way. `readHello` was
already nullable, and both transports close a link whose HELLO reads as null. The BLE advert carries the id as 16 raw
bytes, so it was never exposed. No build is partitioned: every Android tag and the iOS port advertise
`NodeId.derive` output. The check sits at the parse, not at the avatar or `met_peers` writes, so no later reader of a
link's id has to remember it.

What it does not cover: link identity is still unauthenticated, so a neighbour can claim `V`'s exact id while `V` is not
linked to us, and push an avatar under it. The iOS port already refuses these ids: its link session takes a HELLO's
id only through `NodeId(_:)`, the same round trip, and it has no Wi-Fi Aware plane.

Regression: `ProtocolTest` and `NanCueCodecTest` (marked, padded, uppercase, short and long ids refused) and
`LinkHandshakeTest` (a HELLO naming a marked id reads as null).

## Amendment 2026-11-01 — a link's node id is proven where the peer can prove it

The last amendment's open case ("link identity is still unauthenticated, so a neighbour can claim `V`'s exact id while
`V` is not linked to us") is closed for every `V` that can prove itself: a link proves the id its HELLO claims with the
identity key, and an unproven HELLO claiming a proof-capable id is refused (ADR 2026-11.264w). The canonical-id check
stays first: `LinkHandshake.receiveHello` refuses a non-canonical id before any proof is read. It remains open for a
peer on a build before link proof, or one we hold no profile for and that has not proven itself this run.
