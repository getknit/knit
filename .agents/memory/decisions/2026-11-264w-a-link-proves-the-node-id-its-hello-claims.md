---
id: "2026-11.264w"
slug: a-link-proves-the-node-id-its-hello-claims
title: "A link proves the node id its HELLO claims"
date: 2026-11-01
topics: [mesh, security]
---

# ADR 2026-11.264w — A link proves the node id its HELLO claims

Status: Accepted (2026-11-01). Lead of a paired change with knit-ios (#25 there, knit-next #232, and the claimed-id
half of #220); the contract is shared law, byte for byte, and iOS mirrors it.

**What was observed.** A link's HELLO was plain `nodeId|version|capsHex`, and both radios took the id at its word.
Every rule keyed on a link's peer — which link to replace, whose file asks to answer or cancel, whose delivery ticks
to send, whose link-up work to run — trusted a claim anyone in range could make. ADR 2026-09.shzv accepted that
cost as "at most one cut link per 30 s, frames stay signed"; it was more, because the unsigned blob control and the
link-up exchanges ride the link's id too.

**What changed.** A link proves the id its HELLO claims, with the identity key that id is the hash of.

- **The bit.** `Protocol.CAP_LINK_PROOF = 0x40000`, the next free bit, in `LOCAL_CAPABILITIES` (now `0x42FFF`). It
  rides the HELLO and every signed profile this build publishes. The profile's copy is the one that counts: it is
  signed, so nobody but its author can strip it. The Wi-Fi Aware SSI carries it too and ignores it; the BLE advert
  carries only the low byte.
- **The HELLO nonce.** `nodeId|version|capsHex|n<32 lowercase hex>` (`Protocol.hello`), 16 fresh `SecureRandom`
  bytes per connection attempt on both the dial and the reply, present exactly when the caps carry the bit. Field 3
  is a nonce only in that exact form (`PeerWire.nonceHex`). `Protocol.advertise` — also the SSI, whose digest
  `NanCueCodec.parseSsiDigest` finds by its last `|d` — never carries it. Every build since v2.0.0 reads fields 0-2.
- **The record.** `LinkFraming.Type.PROOF`, tag 8: `sigPub(32) ‖ hpkePub(32) ‖ signature(64)`, written only toward a
  HELLO carrying the bit, since every older build throws on tag 8 and drops the link. One on a live link is ignored.
- **The transcript** (`LinkProof.transcript`): `"knit/link/v1/proof" ‖ role ‖ u32be(len helloD) ‖ helloD ‖
  u32be(len helloR) ‖ helloR`, role `0x01` when the signer dialed and `0x02` when it answered, both HELLOs as the
  bytes that crossed the link, dialer first whoever signs. Each verifier's own nonce sits inside what the other side
  signs, so a proof cannot be replayed from another link, and the role byte stops one being reflected. The label
  starts with `k` (a CBOR text head), so it can never decode as a `RelayEnvelope`; it is disjoint from the identity
  key's other inputs (`knit/card/v1`, `knit/dm/v2/spk`, frame CBOR). Signed with `MessageCrypto.signRaw`
  (deterministic Tink, so Android's proofs rebuild byte for byte; iOS's are randomized and only verified).
- **The exchange** (`LinkHandshake.dial` / `answer`): D writes its HELLO; R reads it and decides admission; a HELLO
  with the bit and no valid nonce is a failed proof refused with no reply. R replies, and when D's HELLO carries the
  bit, writes its PROOF in the same write and waits up to 5 s for D's, registering nothing until it verifies. D checks
  the reply's id, verifies R's PROOF (the next record, within 5 s), writes its own and registers. A legacy HELLO on
  either end links unproven, as before. Verify (`LinkProof.verify`): 128 bytes, the bundle rebuilt from the raw keys
  as a contact card does, its `NodeId` equal to the HELLO's, the signature over the transcript for the signer's role.
- **Who must prove** (`mesh/link/LinkClaims`, one per process): an id is proof-capable when its stored profile
  carries the bit or a link proved it this run (memory only, at most `MAX_PROVEN` ids, oldest out). On every plane:
  R1 an unproven HELLO claiming a proof-capable id is refused, ahead of every older refusal; R2 a proven link is
  replaced only by one that proves the same id, under the plane's old rules; R3 a link proving X displaces every live
  unproven link claiming X on every plane (admission no longer refuses a bit-carrying dialer only because an unproven
  link holds its id); R4 when an id becomes proof-capable, every live unproven link claiming it closes. Links where
  both ends are unproven keep the old rules exactly. The seed is one `PeerDao.withCapabilities` read per mesh start; a
  plane that admitted a claim before it landed is told to close it.
- **The bit cannot be taken back by an older frame.** A stored peer's capabilities are written only from a profile no
  older than its stored presentation version (`InboundPipeline.storeProfileRow`); the separate prekey watermark used
  to let a presentation-stale frame rewrite them. And a start whose capabilities differ from those its last signed
  profile carried (`SettingsStore.ownProfileCapabilities`, none stored counts as a difference) republishes the
  profile as an edit (`MeshManager.republishProfileIfCapabilitiesChanged`): one version bump per node on the first
  start after the upgrade, so every profile from before is strictly older than the first with the bit. The same rule
  sits at the one signing choke point (`signOwnProfileLocked`): any fresh signing whose capabilities differ from the
  last signing's bumps the version and the stamp first, so a link-up's push or the stale republish that outruns the
  start-time check never signs the new bits at the old version.
- **Link-up waits for the proof.** A proven link registers only after the proof it needs verified, so nothing keyed
  on the peer's id (`registerLink`/`registerConn`, the neighbour pass, `AckSync`, the avatar push, `IngressBudget`)
  runs on a claim. On the dial side "both proofs" cannot hold: D registers once R's proof verified, before R has
  checked D's; that is safe, because D has authenticated R, and Bluetooth defers clearing D's dial backoff until R's
  first record arrives (R writes nothing before it verifies), so an R-side failure costs a growing backoff, not a
  10 s redial forever (`ProvenDialStreak`: a dialed proven link that ends inside 15 s with no record from R counts as
  a failed dial on the streak it was dialed with; one that outlives that ends as any link does). Every close before
  registration arms the LE ACL's release (`armUnlinked`, ADR 2026-10.b8yf), and a claim close is our own close, so it
  never bumps the real peer's backoff.
- **A displacement re-runs link-up.** The neighbour set keys on the id, so a proven link taking an unproven one's
  place shows no change. `LinkClaims.onDisplaced` (a plane's own replacement, or another plane's R3 close of an id
  proven this run) makes `MeshManager`'s next neighbour pass depart the id and welcome it again: the real peer gets
  its profile push, digest, key asks, blob asks and owed ticks, none of which the squatter could take for it.
- **Direct links run no exchange.** A lane's keys already bind both ids (ADR 2026-10.a5yj, `LinkKeys.lane`: each
  half rides a pair-sealed DM signal), and its HELLO crosses inside the lane. The lane HELLO carries the build's real
  caps with a nonce (`LinkHandshake.writeLaneHello`) and no PROOF follows; every lane link is proven for its plane and
  R1 never runs there. Only a peer whose lane HELLO carries the bit counts `linksProven` and feeds R3/R4: a peer on a
  direct-links build without this change would otherwise become proof-capable after one lane link and have its
  honest unproven radio links refused for the run.
- **Blob control is attributed only to a link** (contract §8). `blobreq`, `blobhave`, `blobhas`, `blobprog` and
  `blobcancel` are unsigned, so only the link they came in on says who sent one. They are acted on only when they
  arrived on a link (`InboundFrame.onLink`, set by `FramedLink`) and their `senderId` is that link's peer; otherwise
  `DropReason.BLOB_CONTROL_NOT_FROM_LINK`. The side channel, hop-less Wi-Fi Aware fast frames and LoRa name their
  sender in bytes anyone can write. Every build of both platforms sends the five on links under its own id (checked
  before the change). iOS's mirror is `isFromItsLink`.
- **Counters** `linksProven`, `linkProofFailed`, `linkClaimRefused` (`MeshMetrics`, `metricsJson`; knit-ios reports
  the same keys), and one `LinkClaims` log line per refusal naming the plane, the claimed id and the reason, never a
  key or a signature. `linkClaimRefused` counts R1 refusals plus each live unproven link closed because its id proved
  itself or became proof-capable (R3, R4), once, on whichever plane closes it.

The alternative a reader reaches for first is a link key and a MAC on every record. It also closes the relayed
handshake below, but it rewrites the framing of every link; the proof is additive and is the first half of it.

**What it costs, and what it does not cover.**

- Old Android builds (v2.10.1 and earlier) stay unproven, and their ids can still be claimed under the old rules
  until their upgraded profile, carrying the bit, reaches us.
- A peer we hold no profile for, and that has not proven itself this run, can be claimed by an unsigned HELLO. The
  squatter is evicted when the real peer dials us with its proof (R3) or its profile reaches us by any path (R4).
  Until then, if the dial order makes us the real peer's dialer, the squatter holds the id: our dial loop skips a
  peer a live link already claims, so we never dial the real one. Letting promotion dial through an unproven link is
  a follow-up. A peer we owe delivery ticks to is pinned, so its profile already says whether it proves itself: the
  squatter case is mostly peers we owe nothing. Ticks already handed to a squatter stay lost (`AckSync` forgets a tick
  once attempted); the re-run on displacement delivers what is still owed.
- A downgraded install is refused by phones holding its newer profile until its old build's profile, which lacks the
  bit, reaches them; it republishes past the version it persisted, so that profile is taken as newer.
- The bump orders frames only inside the 5-minute future-skew clamp (`InboundPipeline.clampFuture`). A sender whose
  clock runs more than 5 min fast has both its pre-upgrade profile and its bumped one clamped to the receiver's now +
  5 min, so a later re-serve of the old frame is not stale and writes capabilities without the bit: R1 stops protecting
  that id at that peer until the sender publishes another profile carrying it. Names and statuses have always ordered
  this way; iOS behaves the same.
- A relayed handshake is not prevented: the proof is not bound to the radio channel. A device in range of both peers
  can forward the HELLOs and proofs live and sit in the middle, where it can drop traffic or inject unsigned blob
  control on that link, never forge a signed frame. Closing it needs a key from the handshake and a MAC per record.
- A cloned identity holds the real key and proves (ADR 2026-09.ypcc). Fresh identities are free: the proof stops a
  device claiming someone else's id, not minting its own (link-slot capture #222, per-socket metering, relay
  suppression stay open).
- One more half round trip on a proven link, and an iPhone suspended between its HELLO and reading our reply and
  PROOF costs a link: our 5 s PROOF wait closes it and counts `linkProofFailed` between honest peers.
- Wi-Fi Aware at serve cap 1 (`singleSlotBusy`) cannot run R3: a squatter's inbound conn keeps the real peer's accept
  out until the responder's quiescence ends it (`RESPONDER_MAX_HOLD_MS`); R4 across planes covers the rest.
- On Wi-Fi Aware our own dial's R1 refusal or failed proof sets a claim-refused mark (`NanClaimRefusalPolicy`: 2, 4,
  8, 16, then 30 min), folded into the sync-wanted gates as the initiator hold is, so a refused peer that stays near
  never builds an owed episode for the wedge watchdog. A fresh discovery handle lifts the wait and keeps the growth; a
  proof elsewhere or a profile losing the bit forgets it. A peer that refuses us arrives as a reply-less dial,
  indistinguishable from contention, so it keeps the flat 10 s backoff. A refused accept marks the responder as pinning
  the NDI and, when it was the last inbound with an initiate owed, recycles it while the refused NDP is still alive. A
  proven Wi-Fi Aware link registers the reply's caps (inside the transcript), an unproven one the SSI's as before.

The mesh lab has no radio layer (`LabTransport` registers links without a HELLO), so no lab scenario covers the
handshake: `LinkHandshakeTest` (both roles over a pipe, every failure shape, the vectors' rejects), `LinkClaimsTest`,
`LinkProofTest`, `BleAdmissionPolicyTest`'s rows, `DirectLinkTransportTest` (a lane link counts as proven and is never
refused; a bit-less lane peer feeds no claim), the pipeline's profile and blob-control tests, and the device trial
do. `vectors/keyed-v1.json` → `link` pins the HELLOs, both transcripts, both PROOF records and the rejects;
`ios-emitted-v1.json` pins iOS's proofs once it exports them.

## Amendment 2026-11-01 — a same-plane displacement counts as a closed claim (contract revision 6)

A proven link that takes an unproven link's place on its own plane now counts that link under `linkClaimRefused`
(`LinkClaims.proven` with `displaced`), as iOS already did. The count had covered only another plane's R3 close and
R4. No plane's listener sees the replaced link (each skips a proven one), so it is counted once. Regression:
`LinkClaimsTest.aSameRadioDisplacementCountsOneClosedClaim`.
