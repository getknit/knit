---
id: "2026-10.aajj"
slug: knit-teaches-its-board-a-pinned-contact-s-key-with-add-contact-on-a-pki-refusal
title: "Knit teaches its board a pinned contact's key with add_contact on a PKI refusal"
date: 2026-10-30
topics: [lora]
---

# ADR 2026-10.aajj — Knit teaches its board a pinned contact's key with add_contact on a PKI refusal

Status: Proposed (2026-10-30), drafted by the LoRa owner; the maintainer decided to build it on 2026-10-30. Stands
beside ADR 045 (its "one admin transaction" is about setup; this is a runtime admin write outside it) and builds ADR
2026-10.x9fa's Plan B, which that ADR's PKI amendment retires as a fallback.

**What was observed.** Firmware 2.8 PKI-encrypts every unicast its phone originates on a port it does not exclude,
whatever the channel: `wouldEncryptWithPKC` (`Router.cpp:1189-1212` at `v2.8.1.8e6a88d`) never reads the channel index
and excludes only channels named `serial`/`gpio`, broadcasts, a licensed owner and the TRACEROUTE, NODEINFO, ROUTING and
POSITION ports. It never falls back to the channel key: with no key for the destination in its NodeDB (or the warm store,
or a pending key-verification key, `Router.cpp:1330-1335`), our own board refuses the send with
`PKI_SEND_FAIL_PUBLIC_KEY` (39, `Router.cpp:1351-1355`) and nothing airs. `Router::send` aborts with a self-NAK
(`Router.cpp:587-592`) and `MeshService::sendToMesh` then reports `queueStatus{res = 39, mesh_packet_id}`
(`MeshService.cpp:393-404`), which reaches the phone first (`PhoneAPI.cpp:1029`). Knit knew no 39: the session completed
the send as `SendResult.Rejected(id, 39)`, the transport gave it up as an unknown result and dropped the offers parked
behind it, and every later offer and re-probe was refused the same way, silently. A board learns keys from NodeInfo on
the shared primary, so this is the case only for a board our board never heard, or forgot (`--remove-node`, a full
NodeDB, a factory reset). Stock clients send `add_contact` before **every** DM for exactly this reason
(`NodeDB.cpp:3755-3756`: "clients may hold a larger node database with public keys than the radio holds").

**What changed.**

- **The trigger is the 39, once per packet.** A compact DM refused with `queueStatus.res = 39` (or, the rarer order, its
  self-NAK, `RoutingError.PKI_SEND_FAIL_PUBLIC_KEY`) is acted on once per packet handle (`LoraDirectPolicy.firstNoKey`);
  the other report is attributed (`lora nak … (late, no key)`), never a pacing NAK. Only a compact DM's: firmware
  self-NAKs every refused send (`Router.cpp:591`, `:400`), so an auto-reply or room post refused with 39 draws one too,
  and that is an ordinary late NAK, never `loraDirectNoKey`. A 39 that arrives only as a late NAK
  (the send's `queueStatus` timed out) refunds every airing the flight was booked for — none flew — and is acted on the
  same way. The frame is **held**, never abandoned; nothing was booked. Counted `loraDirectNoKey`, logged
  `lora direct no key <frameId> -> <peer>`.
- **One `add_contact`, guarded** (`LoraContactPolicy`, pure). Written only when **all** hold: the peer has a contact's
  rank (verified, an accepted conversation, or a DM we wrote in: `LoraBoardHolder.contact`, the board map's own rank);
  no other pinned profile names the same board (`LoraBoardHolder.contested`); the number is a board number and not our
  own board; the profile's `loraKey` is 32 bytes and the number is its CRC32 — the firmware's own first-contact rule
  (`Router.cpp:714`; on 2.8 a node number is the CRC32 of its key, `NodeDB.cpp:537`). A rival claim contests the board
  only when the rival's own key binds it the same way, so a bare claim to a number anybody can type blocks nothing
  (`MeshManager.boardHolders`). **The 39 is the board's word that it holds no key for that node now.** The phone's copy
  of the board's NodeInfo stream (`MeshtasticLink.nodeUser`) is not its NodeDB: the firmware never says when it drops a
  node (`--remove-node`, eviction, a reset; the handshake replays only held nodes), and an over-the-air NodeInfo claiming
  any number can land there (`Router.cpp:761-768`, `788-798`, Balanced mode with no authoritative key). So a key, or ham
  mode, in that copy is read only when it was streamed **after** the refused send was issued — every entry is stamped
  (`StreamedUser.seq`, `userSeq` read just before the send) — which is the race the 39 cannot cover: a NodeInfo that
  landed between the send and its refusal. Then nothing is written, and a key other than the profile's is counted
  `loraDirectBoardKeyConflict` (the board's key wins). An older entry, stale or forged, gives the names alone. ggq4's
  trust in a profile's board key holds only together with the CRC binding: both `loraNode` and `loraKey` are
  self-asserted, and without the binding a contact could name a third board V with their own key, making our board
  encrypt to the claimant what it sends V and drop V's signed packets. Grinding about 2^32 keypairs to hit a CRC already
  beats the firmware's own bootstrap, so the binding adds no weakness.
- **The `User` it carries, an allowlist.** `CopyUserToNodeInfoLite` overwrites the names, `hw_model`, `role`,
  `is_licensed` and both unmessagable bits (`TypeConversions.cpp:108-128`). The written `User` is the `!`-id, the two
  names and the key, nothing else: the names are the ones the stream last showed for that node when it has any,
  otherwise exactly what a Knit-set-up board calls itself (`Knit abcd`, `Knit`, ADR 049) plus `is_unmessagable`
  (2026-09.emd7). Never the contact's display name — the board's next NodeInfo push would put it into `noteNodeName`,
  the directory the Meshtastic room attributes posts by — and never `role`, `hw_model`, `is_licensed` or an unknown
  field, any of which may have come off the air from anybody. The node's own next keyed NodeInfo replaces the whole
  entry (`NodeDB.cpp:3846-3848`), so its full `User` comes back. `should_ignore` and `manually_verified` are never set:
  the second is a user-facing trust mark in Meshtastic apps and would lock the key against the contact's own reflash.
- **The write.** `AdminMessage.add_contact` (field 66, `SharedContact { node_num 1, user 2 }`,
  `MeshtasticProto.encodeAdminAddContact`, golden vector in `MeshtasticProtoTest`), a local admin message to our own
  board through `MeshtasticLink.send` (`PORT_ADMIN`, `to` = our node, no `want_ack`, no session passkey: `from == 0` is
  the local branch, `AdminModule.cpp:161-182`, `:239-245`). Never while a setup or restore runs (ADR 045's transaction;
  the session's actor serialises the two anyway). The firmware handles a local admin message and saves the NodeDB
  before its `queueStatus` (`Router.cpp:427-432`), so the held frame is re-offered on the write's own `queueStatus`
  res 0 — through the pacer and the frame's own `RideGate` — never on a timer. Only res 0 records the write. A write whose
  `queueStatus` never came (`Timeout`) re-offers the frame once without recording it, and the next 39 for that key is
  terminal (`LoraContactPolicy.onTimedOut`), so it cannot loop. Counted `loraDirectContactAdded`, logged `lora direct contact added !<node> for <peer>`. A write
  that never reached the board (no headroom, the link gone) requeues the frame; one the board refused marks the peer.
  While the frame is held, the peer's other offers park behind it (`LoraDirectPolicy.holdForContact`, part of
  `mustWait` and `hasFrame`).
- **`NO_KEY` is terminal only after a write.** A 39 on the re-offer — the board dropped the write silently
  (`is_managed`, `AdminModule.cpp:178-181`; locked storage, `:127`), a PKI-excluded build, a full legacy DB — or a 39
  with nothing Knit may write marks the peer `LoraDirectPolicy.Reach.NO_KEY`: offers held (`loraDirectHeld`), the
  resend and the re-probe deferred, the DM left with the source. Lifted by a changed board-map entry (a new profile,
  board, key or rank), by a `NODEINFO_APP` from their board carrying a key (`lora direct key heard`, and the newest
  unacked DM goes once more), or after the re-probe's cap (4 h), like a refusal.
- **Memory, in process only.** One write per (our board, their board, key); one more once a DM to that board was acked
  by it since (`LoraContactPolicy.onDelivered`), so a board that took the key and later lost it is repairable; at most
  `MAX_KEYS_PER_DAY` (2) distinct keys per peer a day. Not persisted: a restart may write a key once more, one NodeDB
  save.
- **The 35 is not a refusal.** A destination whose board holds no key for ours cannot decrypt the DM and NAKs
  `PKI_UNKNOWN_PUBKEY` (35, `ReliableRouter.cpp:146-150`); our board answers with our NodeInfo (`:162-168`), which heals
  the pair within seconds. So a 35 refunds the unflown airings, books that NodeInfo (our board's own `User` off its
  stream, else `NODEINFO_USER_BYTES`; priced by `LoraAirtime.price` as a NodeInfo unicast on slot 0: signed under 2.8.2's
  `BALANCED`/`STRICT` even unicast, `Router.cpp:1222-1227` at `34cb8d5`, and by 2.8.1 only in ham mode, else unsigned), counts `loraDirectPeerLacksOurKey` and
  re-offers the frame once, one retransmit gap on (`LoraDirectPolicy.scheduleRetry`, run by the deadline loop),
  holding the peer's other offers meanwhile. A second 35 to that re-offer is a give-up: `Unreachable`, cleared by a
  hearing. `add_contact` cannot help a 35: it is their board's NodeDB, not ours. Never `REFUSED`. Their NAK goes on
  their primary (`channels.getPrimaryIndex()`), so with a primary that differs from ours we never decode it and the send
  ends `Unreachable`, as before.
- **The ACK booking is conservative.** The receiver books 18 B (8 and the 10-B ack proof), but a board attaches the
  proof only when it holds the sender's key authoritatively (`AckProof.cpp:143`, `copyPublicKeyAuthoritative`); an ACK
  without it is 10 B smaller.

**Rejected alternatives.** *Proactive writing* — teach every pinned contact's key at bind time: the session's directory
misses the warm store, the TrafficManagement cache, nameless entries and anything past 256, so it cannot tell which keys
the board lacks, and every write is a flash save of the whole NodeDB; reactive writing writes only boards we DM, once.
*Overwriting a key the board holds*: a reflashed contact gets a new node number on 2.8, so an overwrite could never fix a
stale key, and it is exactly the third-board attack above. *`remove_by_nodenum` of the node Knit wrote when a contact's
`loraNode` changes*: not built — it is a second runtime admin write that deletes, the memory that would drive it is in
process only, and a mistaken one removes a node the user may rely on; the ghost favourite below is the cost.

**What it costs.** Nothing on the air: a 39 and an `add_contact` are BLE writes to our own board. One NodeDB save per
written key. The 35's NodeInfo is one airing of about 70 B (≈ 1 s at LongFast; +66 B where the board signs it), booked.

**What it does not cover, and the traps.**

- *The favourite.* `addFromContact` marks the node a favourite to protect it from eviction (`NodeDB.cpp:3762-3779`;
  CLIENT_BASE gets a heard-now stamp instead; the protected-flag cap is `MAX_NUM_NODES - 2`, past which it falls back to
  the stamp and the key is still stored). The contact shows as a favourite in Meshtastic apps on that board. A contact
  who rebinds leaves a favourite at the old number (a ghost), removable by hand.
- *A stale profile.* A contact who reflashed has a new node number and key; until their new profile reaches us, our
  board's 39s are about a node that no longer exists, and the write (CRC-bound to the old key) is useless but harmless.
  Recovery is their new profile (a new board-map entry lifts `NO_KEY`).
- *A board that does not sign* now publishes its key too (2026-10-30, #243, ADR 2026-09.ggq4's amendment): the profile
  carries `loraKey` whenever the board's node number is the key's CRC32, whether or not the firmware signs, so a contact
  on a 2.8.2 board under `COMPATIBLE` can be taught to ours, and our board to theirs, on the first 39. Before that, the
  key rode only while `LoraAirtime.signing` read true — which a `34cb8d582` board did by accident (`has_xeddsa` is set
  on every XEdDSA build), and which fixing #243 alone would have ended. A board whose number is not its key's CRC32
  (pre-2.8, or no key yet) still publishes none, and guard 6 would refuse its key anyway. No iOS code: the port only
  round-trips the field.
- *A board in ham mode* does no PKI, so never draws a 39; its compact DM is channel-encrypted and signed by the
  firmware's rule instead (2.8.1 always, `Router.cpp:1270`; 2.8.2 under `BALANCED`/`STRICT`), priced so off the board's
  snapshot (`LoraAirtime.price`, ADR 2026-09.mhs5's 2026-10-30 amendment).

Tests: `LoraContactPolicyTest` (every guard, the CRC vector, the stream read only after the send, the memory, the
timed-out write, the daily bound, the allowlisted `User`), `MeshManagerTest.aRivalClaimContestsABoardOnlyWhenItsOwnKeyBindsIt`,
`LoraDirectPolicyTest` (`NO_KEY`, once per handle, the contact hold, the 35 re-offer), `LoraDirectTransportTest`'s PKI
section over `FakeMeshtasticAir` (a key remembered from before `--remove-node` is set aside and the contact written; a
forged NodeInfo before the send stops nothing; a keyed NodeInfo racing the 39 is a conflict; a timed-out write never
loops), which now models PKI (the 39 both orders, the 35 and our NodeInfo, `add_contact`
filling the key table, a silent `is_managed` drop, delivery on index 0 flagged `pki_encrypted`), `LoraMeshTransportTest`
(`anAutoReplyRefusedForAMissingKeyNeverWritesAContact`, `aRoomPostRefusedWithA39NeverWritesAContact`), `MeshtasticProtoTest` (`encodeAdminAddContact`, 39,
`pki_encrypted`, the raw `User`). Board trials owed on `!942b7a83` (v2.8.1, signs) and `!1ba11230` (34cb8d582,
Compatible, does not sign), in `context/lora-bridge.md`'s DM recipe: (b′) `--remove-node` the other board on the
sender's, DM: one `Rejected res=39`, one `add_contact` with its own `queueStatus` res 0, the re-offer acked
`byDestination`, the board log's `Client got add_contact`, `--info` showing the node with its key, `isFavorite` and
`Knit abcd`; a second DM writes nothing; a reboot of the sender's board keeps the key. (b″) a peer whose `loraNode` is a
real third board with another key: no `add_contact`, `loraDirectNoKey` counted, the third board's signed packets still
arrive.
