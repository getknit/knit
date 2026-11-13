---
id: "2026-11.gsqu"
slug: a-meshcore-board-is-a-second-lora-plane-over-one-neutral-core
title: "a MeshCore board is a second LoRa plane over one neutral core"
date: 2026-11-12
topics: [lora, meshcore, crypto, airtime]
---

# ADR 2026-11.gsqu — a MeshCore board is a second LoRa plane over one neutral core

Status: Proposed (2026-11-12). Stages 1-4 are built (2026-11-13): the board-neutral seam with Meshtastic unchanged, the
per-kind scaffolding, the MeshCore client (not wired) and the sealed `meshcoreKey`. The five bench checks were answered on the firmware itself in a simulator (2026-11-13,
below); only the BLE half of the first is still owed, on boards.

It amends four ADRs:

- **ADR 2026-10.jend**, its build shape. Its scope stays as ADR 2026-10.rp3s narrowed it.
- **ADR 2026-10.x9fa**, giving the compact DM a MeshCore carrier and per-kind traits.
- **ADR 2026-10.ug4a**, adding a flood ceiling and a ledger per board.
- **ADR 2026-10.aajj**, adding a MeshCore analogue of teaching the board a key.

**What was observed.** ADR 2026-10.jend scoped a MeshCore board kind from a two-board spike on companion v1.18.0
(`~/source/meshcore-spike/FINDINGS.md`). Two ADRs landed after it:

- ADR 2026-10.rp3s made the board an endpoint: only its user's own room posts air.
- ADR 2026-10.x9fa made a DM one compact pair-sealed `0x11` unicast, settled by the destination board's
  acknowledgement.

jend's owed list was written against the bridge both ADRs removed. A code map on 2026-11-12 found:

- **The seam.** Only `LinkDestination` and the four `PacketOutcome` cases are board-neutral.
  - `BoardAddr` is a `UInt` read through `.node` in about ten places.
  - `ReceivedPacket`, `LinkState.Ready` and `MeshtasticLink.send` are Meshtastic-shaped.
- **The transport.** `LoraMeshTransport` holds the neutral plane logic and every Meshtastic-only feature side by side:
  - the slot-0 room;
  - the auto-reply;
  - ADR 2026-10.aajj's `add_contact`;
  - the reply to a 35;
  - signing and AEAD pricing;
  - portnum dispatch.
- **Exactly-one wiring.** The app has exactly one of each:
  - Koin single;
  - settings slot;
  - profile field pair;
  - capability bit;
  - `TransportKind.LoRa`;
  - Meshtastic room;
  - field-log stream;
  - `BleConnectArbiter` tag.

  `MeshManager` sizes compact DMs to a compile-time Meshtastic constant (168 B, 9 acks).

The firmware source (tag `companion-v1.18.0`) answers the question jend left open, which is how a DM rides:

- **No binary acked unicast.**
  - `CMD_SEND_BINARY_REQ` is answered only by sensors.
  - `CMD_SEND_RAW_DATA` is unencrypted, unaddressed and unacked.
  - Channel data (`GRP_DATA`) is unacked.
- **The contact text DM is the one acked unicast** (`CMD_SEND_TXT_MSG`). It carries these limits:
  - it is `strlen`-terminated at both ends, so a `0x00` cuts it (`BaseChatMesh.cpp:237, 452`);
  - `MAX_TEXT_LEN` is 160, and the receiver's ESP32 notify cap (ATT MTU 176) leaves 157 B through a 16-byte receive
    header;
  - the receiving board drops it unread unless the sender is in its contacts (`Mesh.cpp:147-191`).
- **The ack is the board's.** The receiving board sends it whatever its phone does. The ack code hashes the plaintext
  with the sender's key (`BaseChatMesh.cpp:245, 462`), and the sender pushes `PUSH_SEND_CONFIRMED`. The firmware never
  retries (`onSendTimeout` is empty, `MyMesh.cpp:898`) and never pushes a failure.
- **A contact's route.** A DM floods whenever the sender's contact has `out_path_len` UNKNOWN (`BaseChatMesh.cpp:480`).
  - Its ACK floods on the receiver's record of the sender (:43).
  - The firmware rewrites `out_path` on any PATH packet (:353).
  - Another client's `CMD_RESET_PATH` pushes nothing.
- **Channel data reports almost nothing about the sender.** `CHANNEL_DATA_RECV` carries no sender, RSSI or receive
  time (`MyMesh.cpp:610-620`).
  - Its `path_len` holds the hash size in bits 6–7 (`Packet.h:79-80`).
  - A direct packet always reads `0xFF`, through a repeater too.
- **Sizes.** At the 164-byte broadcast cap the signed room envelope leaves about 45 B of text in one packet: Meshtastic
  has 100 B at 228, which implies about 119 B of overhead.

**What changed.**

**1. One plane instance per board kind, over one neutral core.** `LoraMeshTransport` becomes the board-neutral core. The core holds:

- the admit rule and `sigSeen`;
- the covers;
- compact-DM offers, flights, the re-probe and the catch-up;
- the pacer, the ledger and the brake;
- the reach marks and the status.

It runs over a neutral `BoardLink`. A board kind supplies three things:

- **the link itself.**
- **`BoardKindHooks`.** One object per plane instance, holding the kind's own state (amended 2026-11-13, below). They
  carry the inbound dispatch, kind-only features, refusal handling and send-time slot guards.
- **`BoardTraits`.** These hold:
  - payload caps;
  - the airtime model;
  - `AIRINGS` and the deadline;
  - the minimum gap;
  - `PacketCost`;
  - the room part limit;
  - the frame form;
  - which hearings count.

Each kind is its own `CompositeMeshTransport` child with its **own ledger**: duty is a radio's, and two boards are
two radios on two networks. A child stays quiescent until its board is configured (ADR 2026-09.5bqu), so both
children exist from the first build at no cost. v1 binds one board at a time.

Two transports, one per kind, was the alternative a reader reaches for first. It would fork x9fa's flights and the
catch-up of #294 into two copies, and every fix since 2026-10-29 would have to land twice.

**2. The compact DM rides as a MeshCore contact text DM.** In short:

- **Form.** `TXT_TYPE_PLAIN` carries `COBS(0x11 frame)` with no `0x00` byte. COBS output is ≤ 157 B, so the seal is
  ≤ 156 B and the plaintext about 105 B.
- **What the transport sees.** The seal and the frame are unchanged. COBS is the MeshCore link's framing, below the
  transport, so this is not a wire change.
- **Outcomes.**
  - `PUSH_SEND_CONFIRMED` is `Acked(byDestination = true)` with a verified proof.
  - Knit's session retries with `attempt` 0..2, and the last timeout is `Unreachable`.
  - Every attempt is booked, and an outcome carries the exact count that flew.
  - At most eight attempts await an ack at once. That is the firmware's shared circular table (`MyMesh.h:285`), which
    every attempt and every interface fill.
  - Our board's ack of a DM to us is booked as 1 + `multi_acks` packets.
- **Duplicates.** A doubled attempt is new ciphertext and reaches the receiver's phone as a second copy, which
  `lora_direct_seen` absorbs.

The alternative was an unacked zero-hop `GRP_DATA` carrying the same frame. It needs no contacts, never floods and
holds 113 B, but every send would end `NoOutcome`. That would mean:

- every send becomes `Unreachable`, and DMs to the peer serialise behind it;
- no refunds;
- no "Reached their LoRa radio" (ADR 040);
- no `after ack` catch-up;
- reach rebuilt on the phone's ✓✓, which is the guessing x9fa replaced.

It would also wake every Knit phone in range.

**3. Each phone teaches its own board, and pins the route zero-hop.** In short:

- **Which contacts.** The pinned contacts whose signed profile carries `meshcoreKey` and `CAP_MESHCORE_DIRECT` are
  written with `ADD_UPDATE_CONTACT`:
  - type CHAT, `flags = 0x01` (favourite, no permissions), `out_path_len = 0`;
  - named `Knit xxxx`, never a display name.
- **When.** At Ready and whenever the board map changes, ranked under the board's contact capacity (`DEVICE_INFO`
  reports `MAX_CONTACTS/2`).
- **The zero-hop pin.** It is re-asserted:
  - before every send to a contact not re-verified since the last Ready;
  - on `PUSH_PATH_UPDATED` (`0x81`);
  - by re-adding the contact after `PUSH_CONTACT_DELETED` (`0x8F`).

  A `RESP_SENT` that reports a flood is counted as a breach (`meshcoreDirectFlooded`).
- **What Knit removes.** Only the contacts it added. A contact that already existed for that key is snapshotted and
  put back on Restore. Both the snapshot and the list of contacts Knit added live in a SQLCipher table, never in
  DataStore, which is plaintext: the snapshot keeps only what puts a contact back (key, type, flags, name, `out_path`),
  never `adv_lat`, `adv_lon` or `last_advert`; the rows are cleared on unbind, on a cross-kind bind and on sign-out; and
  a removed or blocked Knit contact's key leaves the added list once the board drops it (ADR 2026-09.adgd).
- **Errors.** `ERR NOT_FOUND` on a send writes the contact and sends once more. Misleading error codes are read by
  context: an over-long text returns `TABLE_FULL`.

This is ADR 2026-10.aajj's analogue, simpler: no key conflict to judge, since the profile carries the full key. But
it is symmetric. The receiver's own teaching is what lets a DM land.

**4. The room floods, up to two packets, under a flood ceiling.** In short:

- **Form.** An own room post rides as `GRP_DATA` on the Knit slot:
  - with a registered `data_type` (development `0xFF4B` until MeshCore's `number_allocations.md` lists Knit's);
  - flooded under the board's default scope, which the firmware applies to every flood;
  - in the elided-id form `0x06` (`FastFrameCodec.encodeElided`, `IdElision`), resolved on receipt against pinned
    peers.
- **Size.** The room part limit is a trait: 1 on Meshtastic, 2 on MeshCore. Two elided packets carry about 200 B of
  text; one carries about 57 B. Two MeshCore packets (about 1.3 s) cost less air than one LongFast packet (about
  1.6 s).
- **Scope override.** At handshake Knit sends `[54, 0]`, which clears another client's RAM scope override. It never
  writes the default scope.
- **The flood ceiling.** Floods (Knit room posts, and the Public-channel mirror below) are admitted under a new
  `Ceiling.FLOOD`: 0.15 of the allowance, judged on the one total like `HALF`. This is jend item 4 under ug4a.
  - Each repeater retransmits a flooded packet once, so its Knit load equals our flooded airtime, about 0.75 % duty at
    a 45 s window.
  - `HALF` was the obvious translation, but it lets 22.5 s of floods through a 15-minute window, 3.3 times jend's cap.
  - ug4a justified `HALF` by the room's 30 s floor, and own room posts have none.
  - A scoped flood is priced 4 B more, for its transport codes.

**5. The hearing hook counts only zero-hop receptions on this kind.** In short:

- **The test.** `path_len != 0xFF && (path_len & 0x3F) == 0`.
- **Keying.** `CHANNEL_DATA_RECV` names no sender, so the hook keys on the decoded author. That is sound only because
  rp3s makes the author the board that aired it. "Radios heard" therefore counts authors on this kind.
- **Direct DMs.** A direct DM reads `0xFF` through a repeater too, so the hook trusts the peer's pin (item 3).
- **Live.** A reception is live once it was drained after this connection's first `NO_MORE`. Anything earlier is a
  replay of the offline queue.
- **Floods.** A flooded post still feeds the author's `reachable` (presentation), and never clears an `Unreachable`
  DM mark. This is jend item 5, at the place x9fa left for it.

**6. Pacing and the brake read `GET_STATS`.** In short:

- **The pacer.**
  - Its queue depth is CORE's `outbound_queue`, polled at 1 s while anything is queued or in flight and at 60 s
    otherwise.
  - `TABLE_FULL` is `SendResult.Busy`.
  - The minimum gap is 0.
- **The brake.**
  - It reads channel utilisation as Δ(`rx_air` + `tx_air`) from RADIO stats over a sample ring of at least 60 s.
  - The counters are whole seconds, so a 1 s poll reads 0, 100 or 200 %. The ring keeps the error to ±3.3 points at
    the 40 % brake.
  - A backwards uptime or counter resets the baseline.
- **Airtime.** Priced from `SELF_INFO`'s frequency, bandwidth, spreading factor and coding rate with the LoRa formula
  × 1.06 (spike Q5). Duty comes from the frequency band; an unknown band reads conservative.
- **The direct cap.** 157 B is a fleet constant: the receiver's notify sets it, not our own MTU.

**7. The rest follows ADR 045's shape.** In short:

- **The profile.** It gains `meshcoreKey`, the 32-byte Ed25519 key from `SELF_INFO`, and `CAP_MESHCORE_DIRECT`.
  This is additive, with `loraNode` / `loraKey` as the precedent (`docs/WIRE_COMPAT.md`), and paired with iOS to
  parse and preserve the field.
- **`BoardAddr`.** It becomes kind-tagged: a 32-bit node or a 6-byte key prefix.
- **Binding.** Binding a MeshCore board clears the Meshtastic binding, so `CAP_LORA_DIRECT` is not claimed beside it.
- **The Public channel.** Slot 0 is mirrored into its own `Conversations.MESHCORE_PUBLIC`.
  - Its posts are always unsigned and drawn as a stranger: `CHANNEL_MSG_RECV` names only a self-chosen display name.
  - A post leaves with the board's name in front, a 30 s floor and the room switch.
  - It sits behind a consent sheet that says the post floods the MeshCore network.
- **Setup.** One transaction that reads first, then writes four things:
  - the Knit channel;
  - the `Knit abcd` advert name;
  - `SET_OTHER_PARAMS` as exactly `[38, 1]`;
  - `autoadd_config` with chat auto-add off.

  Every prior value is recorded, and Restore puts it back. The radio is never touched, and nothing like
  `BoardQuiet` is needed: an idle companion transmits nothing.

**What it costs.**

- **DM length and reach.** A DM over MeshCore holds about 105 B of plaintext against Meshtastic's 168, so fewer inline
  acks ride with it. It reaches only direct neighbours.
- **The silent contact drop.** A receiving board that lacks the sender drops the DM silently, which looks exactly like
  an absent radio. The repair is that phone's own teaching, then the re-probe.
- **Contact churn.** Teaching writes to the user's board. A contact table already full of other people's contacts
  limits how many Knit peers fit, and the setup turns auto-add off for that reason.
- **Two-part room posts.** They fail more often far away, because each fragment floods on its own. ug4a's argument that
  no room post can starve ("every room post is one packet") does not hold on this kind. `DIRECT` still dequeues first,
  and a refused room post is skipped, never held.
- **The EU 1 % sub-bands.** A ceiling of `0.15 × 4.5 s` admits no two-part post at all. MeshCore's EU default,
  869.525 MHz, is a 10 % sub-band.
- **The brake's input.** `rx_air` is the firmware's estimate over the packets it parsed, with no noise or CRC errors. A
  companion with client repeat on inflates both figures and the queue depth.
- **Fleets.** The Meshtastic and MeshCore fleets stay split. A phone with one board of each would bridge only its own
  posts.

Deferred: both boards at once (the router's preference and failover, a two-board radio screen, per-kind diagnostics),
room reactions, jend's lean profile and `keyreq`, one named-repeater hop, the region-scope UX, the auto-reply, and
MeshCore on iOS.

The bench checks owed before Stage 3 were run on 2026-11-13, without boards. The unmodified v1.18.0 companion and
repeater firmware ran in a simulator, driven frame by frame as Knit would drive it
(`~/source/meshcore-spike/FINDINGS.md` Q11–Q15). Every answer held the design:

- **Bytes.** All 255 non-zero values arrive byte-exact in a 157-byte text DM. 160 B is the firmware's limit, and 161 B
  is refused as `TABLE_FULL`.
  - A zero byte is cut silently on send, and the cut text is still acked. COBS is mandatory.
  - **Still owed:** a 157-byte DM through the receiver's ESP32 notify, which only boards can show. It folds into the
    first Stage 8 trial.
- **Pinned both ways.** With both records at `out_path_len 0`, the DM and its ack both air direct at zero hops, a
  repeater in range airs nothing, and `SEND_CONFIRMED` arrives.
- **One side unpinned.**
  - If the receiver's record is UNKNOWN, its ack floods and repeaters re-air it. The sender cannot see this.
  - If the sender's record is UNKNOWN, the DM floods, and the PATH return rewrites the sender's record, pushing `0x81`
    to both phones.
  - So each phone pins its own board, and the re-pin on `0x81` is load-bearing.
- **Retries.** A retry with the same `attempt` is byte-identical and dropped by the receiver without a second ack, so
  a retry after a lost ack is never answered.
  - With `attempt` + 1 the DM is delivered and acked again, and the receiver's app sees both copies.
  - So every retry changes `attempt`, and `lora_direct_seen` is the only dedup.
- **Two-part post.** A two-part channel-data post floods through a repeater, and both parts arrive byte-exact and in
  order: `path_len 0` in range, 1 through the repeater. This confirms the hearing hook.

The invariants that keep this true, each to be written with its stage:

- a per-kind frame-policy test classifying every frame type as flood, zero-hop or refused (jend's own invariant);
- a parity test that every Meshtastic preset prices identically through the neutral `RadioParams`;
- Stage 1's gate: every existing Meshtastic test green with its expectations untouched;
- a MeshCore column in `CoordinationPlaneSizeBudgetTest`, covering the room body in one and two elided packets and the
  DM budget at 157 B after COBS;
- `MeshcoreDirectLabTest`, where path drift is re-pinned and a DM never floods.

## Amendment 2026-11-13 — the hooks are a per-instance kind object, not a stateless one (draft, Stage 1c)

§1 called `BoardKindHooks` stateless and called only on the core's collector and pacer coroutines. Building Stage 1c
showed neither holds. The Meshtastic-only features keep state of their own: the auto-reply's memory, the taught keys,
the room's post floor and the setup flag. And three of them are reached from outside the plane: `postToPublicChannel`
from `MeshManager`, `provisionKnitChannel` from the settings, and the limiter snapshot from the save loop and `stop()`.

So, as built:

- **One hooks object per plane instance** (`MeshtasticHooks`). It creates the kind's state objects itself, and the core
  names none of them (`DmAutoReplyPolicy`, `LoraContactPolicy`, `PublicChannelPolicy`, `PublicPostPolicy`).
- **Any coroutine.** A hook may be called from any plane coroutine and from those entry points. Every state object
  behind it is `@Synchronized`, atomic or volatile (`DmAutoReplyPolicy` gained `@Synchronized` for this). A hook takes the pacer's
  and the ledger's monitors only inside its own call. No hook is entered holding either, and `statusOf` runs under the
  status lock reading nothing that waits.
- **On the packet and outcomes collectors** a hook returns at once: it never suspends, waits on a launch or re-enters
  the core (the rule at `LoraMeshTransport.kt:123-127`). A write that has to wait, such as aajj's `add_contact`, is
  launched on the plane's scope.
- **The way back.** A hook reaches the core through `PlaneCore`: the ledger, the config, the payload cap, `restored`, a
  room-class `enqueue`, `limitersMoved` and `onBoardKeyHeard`. The core keeps every neutral structure (the marks, the
  flights, the re-probe, the held frame) and acts on what a hook answers (`PacketRoute`, `SlotCheck`, `KeyTeach`,
  `KeyWrite`).
- **A slot no kind can vouch for is refused.** `checkSlot` returns null when the kind cannot read the slot, and the core
  refuses the frame.

## Amendment 2026-11-13 — Stage 2: every per-kind surface addresses a plane by `BoardKind` (draft)

Stage 2 makes room for a second plane instance without building one. Meshtastic's is still the only plane; every
release-visible key, line, row and bridge key is what it was.

- **The kind.** `BoardKind { MESHTASTIC, MESHCORE }`, carried by `BoardTraits.kind` and `BoardKindHooks.kind` (the
  transport checks they agree) and reported as `boardKind`, never `kind`, which stays `MeshTransport.kind`.
  `BuildConfig.MESHCORE_PLANE` (`-PmeshcorePlane`, on in debug, off in release, never without `LORA_PLANE`) is read
  only by the guard in `di/BUILT_BOARD_KINDS`, where Stage 5 adds the child.
- **Koin.** Every per-kind single (the link, the transport, its `LoraDirectSink`, its `PublicChannelSink`, its
  `LoraPlaneStatus`) carries `loraPlane(kind)`; the composite takes one LoRa child per built kind.
- **The DM router.** `MeshManager` binds `LoraDirectRouter.sinkFor(peerId)`, asked once per offer with the recipient.
  v1's rule: the **bound** kind's plane (the kind whose address is set), Meshtastic's with none bound, and nothing when
  the bound kind has no plane in this build. It never reads the board map or a holder, so a DM offered before the map
  reports still reaches the plane's own `directHolder` and re-probe. No arrival kind on the inbound side: under one
  bound board it would be a second routing rule that could disagree with `sinkFor` (ADR 2026-09.zkma); it waits for
  the two-board router.
- **Settings.** The `lora_*` keys stay Meshtastic's with no migration; MeshCore's are their `meshcore_*` peers
  (`BoardKeys`), and `lora_enabled` / `lora_dm_enabled` stay shared. A backup leaves out the address, name, setup
  record, priors and plane state of each kind and carries its board key, room switch and post consent, named key by
  key, never one bare `meshcore_` prefix. Binding a kind (`BoardBindPolicy`, every writer of an address) clears the
  other kind's identity (address, name, reported node / key) in the same edit, never the shared switches, and keeps
  the other kind's setup record and priors so that board can still be restored (ADR 045).
- **Plane state.** One blob per instance: `lora_plane_state` byte for byte, `meshcore_plane_state` for MeshCore.
- **Status.** `LoraStatusRepository` takes each kind's `LoraPlaneStatus`, emits each kind's `LoraFacts` and, as the
  aggregate, the bound kind's. `LoraFacts.directBudget` carries the kind's constant compact budget, so the composer's
  DM hint follows the bound board without reaching the transport (Meshtastic: 168 B, a 160-B body).
  `TransportStatus.boardKind` keys a LoRa child, since every kind shares `TransportKind.LoRa`; Diagnostics shows the
  bound kind's row alone and the field log keeps one `radio` line per child.
- **Field log.** `FieldEvent.Lora*` and `Radio` carry `board`, written only where it is not Meshtastic; each kind has
  its own sampler and follower name (`lora` stays Meshtastic's) and its own relink. A kind's lines carry counts and
  own-radio facts only, nothing derived from a key, a node, a contact or the snapshot (`docs/FIELD_LOG.md`).
- **Left unkeyed.** `MeshMetrics`' `onLora*` counters and the field log's counters sample, the debug bridge's `LORA`
  (Meshtastic's instance only) and `peerTransports` / `linkSecurityByKind`: one board is bound at a time, and per-kind
  diagnostics are deferred with both boards at once. What Stage 5 adds to the bridge and `AppUsbControlApi` for MeshCore
  is counts and own-board state only: no contact key or name, no snapshot, no board key.
- **Dropped from the plan.** A per-dialer `BleConnectArbiter` tag: `MeshtasticGatt` already holds its own, and the
  MeshCore dialer picks its own in Stage 3.

## Amendment 2026-11-13 — Stage 3a/3b: the MeshCore client, under the design passes' binding rules (draft)

Stage 3a (`MeshcoreProto`, `Cobs`) and 3b (`MeshcoreSession` and its policies) are built, JVM-tested and reachable from
nothing until Stage 5. The lora-, bluetooth- and security-reviewer design passes of 2026-11-13 read the firmware and set
binding rules on the brief; where they move what this ADR said, this amendment records it. The fact under all of them:
**the board answers every connected client at once** (`MultiSerialInterface`), and the user's own MeshCore app may
share it.

- **Matching.** Drained data (codes 7, 8, 16, 17, 27) is never matched to a request and never discarded: the firmware
  pops a message before it replies, so a reply to another client's `SYNC`, or one that lands after our wait, is the only
  copy. `SENT` is ours only when its `expected_ack` is the code the phone computes (`BaseChatMesh.cpp:462`, checked
  against all eleven `SENT`s of bench Q11–Q14); `SEND_CONFIRMED` only for a code in our model of the board's table; a
  bare `OK` / `ERR` gets a barrier (`GET_DEVICE_TIME` → `CURR_TIME`) after a timeout, and every write relied on is read
  back.
- **§2, outcomes.** An outcome carries the exact count of airings that flew: `PacketOutcome.flown`, null on Meshtastic
  (unchanged), read by the core's refund on an ack and on a give-up. The core's own deadline still ends a send with no
  count, every airing kept booked. The ack table is modelled as the firmware's circular eight, advanced by every client's
  sends: `Busy` while eight codes are unconfirmed inside their window, a confirmation after `Unreachable` a late `Acked`.
- **§2, our board's answer.** To a direct DM it is `1 + multi_acks` direct acks; to a flooded one it is one flooded PATH
  return, which the session reports (`MeshcoreAir.PathReturn`) for the core to book as a flood. A DM replayed from the
  offline queue was answered while we were away and is booked by nobody.
- **§3, the pin.** A DM is written only after a pin read back in this connection; a pin that fails is a refusal
  (`RefusalClass.OWN_NO_KEY`) with nothing written. A `SENT` with the flood flag cancels the attempts not yet sent until a
  fresh pin reads back. Re-pins are targeted: a `0x81` / `0x8F` re-pins only the key it names, only if Knit taught it, at
  most once a minute; the full re-assert runs only at Ready, ranked.
- **§3 and §7, contacts and setup.** Teaching never evicts: a key is added only into a free slot confirmed by count
  (`GET_CONTACTS` past every `lastmod`, so the board lists none — the one command beyond the brief's list, a read), and
  the setup also clears `AUTO_ADD_OVERWRITE_OLDEST`, recording and restoring it. Restore re-reads before it acts and
  leaves anything that is no longer Knit's value.
- **§4, the data type.** `DATA_TYPE_DEV` (`0xFFFF`, `TxtDataHelpers.h:12`), the firmware's own development value, not
  `0xFF4B`, until MeshCore's `number_allocations.md` lists Knit's; a test keeps a release build from defaulting the plane
  on while it is.
- **The floor.** A board below `FIRMWARE_VER_CODE` 11 (companion v1.15.0, the first tag with commands 54, 62 and 64) never
  reads Ready.
- **What leaves the session.** Counts and own-board facts only: no secret, foreign slot name, contact or advert name,
  position, full key or DM text in a log or an exception; foreign slots kept as (index, empty, is-Knit); `DEVICE_INFO`'s
  BLE PIN never read.
- **The fake against the firmware.** `FakeMeshcoreAir` replays the simulator runs of Q11–Q15 command for command and
  matches the firmware's answers and airings node by node (`MeshcoreFakeVsSimTest`).

Left open for Stage 5: `BoardLink.provision(Restore)` cannot carry a MeshCore record (`ProvisionSpec.previous` is
Meshtastic's), so the hooks call `MeshcoreSession.restore(record)` directly. The core's deadline for a MeshCore DM
(`MeshcoreTraits.deadlineMs`) is a backstop the session always beats, never the clock that ends a flight: at least
3 × (the flood `est_timeout` at the board's preset + 1 s) + 2 × the session's 30-s hold limit + 3 × a pin at its slowest
(`MeshcoreSession.PIN_WORST_MS`, 26 s: a read, a flash write and a read-back, each able to time out into a 5-s barrier),
and never below `MeshcoreSession.MAX_FLIGHT_MS`, the session's own cap on a flight's life, past which it
ends the flight `Unreachable` itself. The session reports only the flood surcharge of a `DirectFlooded` attempt for
booking: the airing itself was booked at send.

Review of Stage 3a/3b (2026-11-13), applied: an `ERR` beside a content-matched answer is provisional until a barrier
(our success before its `CURR_TIME` makes it another client's); a room or Public post's bare answer is judged with every
`OK` / `ERR` seen before a barrier, ambiguity reading as a timeout and never a refusal; a stray `TABLE_FULL` holds the
headroom, and a hold lifts on an empty queue or after two polls; a slot reading never outlives its connection; a drain
round is capped at 16 replies and the inbox is served first, with a deadline on every call; drained messages are held
until collected; the encoder's allowlist is asked again at the one write; COBS decodes canonical input only.

Second review, applied: a call's deadline bounds only the wait for the actor to start it — a started op always returns
its own result (a send's handle is taken before it is posted, so every outcome of a started send carries the id the core
holds; a send its deadline caught unstarted is `Busy` since Stage 5a-1, below; a setup keeps its record); the drained-packet backlog is 1,024, oldest dropped and counted, and the core subscribes before
`start`; every `ERR` up to a content-matched request's barrier is judged together, more than one with no success being
ambiguous (no answer, never a refusal). **A known limit:** a barrier ends at the first `CURR_TIME`, and another client's
`GET_DEVICE_TIME` answer cannot be told from ours — it carries only the board's clock. One that lands first ends our
barrier early: a late answer of ours is then a stray, counted and dropped, and the request reads as unanswered (a
timeout, never a refusal).

## Amendment 2026-11-13 — Stage 3c/3d: the shared GATT core, the MeshCore GATT client and the picker (draft)

Built under the bluetooth design pass's binding rules, reachable from nothing until Stage 5.

- **One GATT core, per-kind parameters.** The connect, the MTU settle, discovery, the one-op-at-a-time serialisation
  and `stackCall` moved out of `MeshtasticGatt` into `mesh/bluetooth/BoardGatt`, which both kinds dial through. It keeps
  the four-argument, null-`Handler` `connectGatt` (callbacks inline on a binder thread, in order; `MeshtasticGatt`'s KDoc
  said the main looper, which was wrong), copies every notify and read value inside the callback, makes the MTU and
  discovery deferreds `@Volatile`, and keeps the deprecated overrides gated below SDK 33. What differs per kind is passed
  in, never shared: the numbers and the log tag (`BoardGatt.Spec`; Meshtastic's lines keep the `MeshtasticGatt` tag and
  their words), the UUID demultiplexing (`Route`), the result mapping including `Refused` → `Failed` and the
  `connectGatt`-threw status (`Outcomes`), the arbiter tag and span (`holding`), and the channel's own close on every dial
  that does not open. `GattChannel` / `GattEvent` are unchanged.
- **The arbiter counts holds per tag.** A cancelled dial's `end` can run after the next dial under the same tag began;
  a set of tags let it clear the newer hold. `BleConnectArbiter` now counts, and an `end` with no hold is ignored.
- **The MeshCore link.** `MeshcoreGatt` over NUS: MTU floor 176 (`MAX_FRAME_SIZE`, the ESP32 board's own cap), a write
  over `mtu − 3` refused rather than long-written, `WRITE_TYPE_DEFAULT` only, arbiter tag `meshcore-dial`, TX subscribed
  before the dial opens. Notifies land in an unlimited channel bounded at 1,024 frames or 256 KiB held (`NusInbox`, an
  upper bound counted from the last time the channel was seen empty); past it the connection ends under its own status
  and is counted, nothing dropped.
- **Pairing.** No bond receiver and no `MeshtasticBonder`: the bond is polled. An unbonded board is never dialled in the
  background (the session never asks, and the dialer refuses). The first protected op pairs: on an ESP32 companion the
  CCCD is open, so it is the session's first RX write, under its 90-s window and its terminal `NeedsPairing` /
  `StaleBond`; a board whose CCCD needs encryption pairs at the dial's subscribe, given the same 90 s, and a bond refused
  there (or the link closed with the board still unbonded) is the new terminal `NusDialResult.BondRefused`. That
  subscribe runs with the `meshcore-dial` hold released, so a passkey the user takes 90 s over never pauses the mesh
  scan. After a close under an op that started unbonded the bond is read settled (`BondSettle`: polled until not
  `BONDING`, at most 2 s on the elapsed clock), since the bond broadcast and the disconnect are not ordered and a
  `BONDING` read would redial into a second prompt.
- **The picker.** `BoardRef` carries its `BoardKind`; `BoardFilter.kindOf` tests the MeshCore rule first, on the
  `MeshCore-` prefix alone (the firmware's `BLE_NAME_PREFIX`; never the generic NUS UUID), so a MeshCore board is no
  longer listed as Meshtastic. Until Stage 5 a MeshCore entry is listed but not pickable. A Meshtastic dial whose
  discovery succeeded without the Meshtastic service is `DialResult.ServiceAbsent`. The first reading backs off like the
  failed discovery it used to be; a second in a row is terminal (`StaleBond` bonded, `NeedsPairing` otherwise). Neither
  cause is fixed by a redial: wrong firmware is left by forgetting the board or picking another, and only a cache from
  before a reflash is fixed by re-pairing. The "Pairing needed" wording for the wrong-firmware cause is Stage 7's
  (strings and translations).
- **Left for Stage 5.** A MeshCore dial that finds no NUS service still backs off (`Failed`, phase `service`); nothing
  dials a board the picker did not name MeshCore, and Stage 5 decides whether that ends terminal too.

## Amendment 2026-11-13 — Stage 4: the profile names a MeshCore board, sealed-only and with no bit (draft)

Built under the crypto- and security-reviewer design passes of 2026-11-13, whose rules were binding on the brief. It
amends §7's "The profile" and §3's "Which contacts" and "What Knit removes". Nothing reads the field to act yet: Stage 5
teaches from it.

- **Sealed-only.** `meshcoreKey` rides `ProfilePayload` (text key `meshcoreKey`, canonical base64) and `ProfileV2` (label
  **9**, 32 raw bytes) and **never** `ProfileContent`, so `FrameTranscoder` is untouched. §7 said "the profile"; the
  cleartext frame floods eight hops, sits in every carrier's custody and reaches spools and the LoRa transcoder, and a key
  there would tie the Knit node id, already in clear BLE and Aware adverts, to a radio identity whose adverts flood the
  MeshCore network publicly and may carry a position. **Direct contacts only**: each recipient's sealed copy carries the
  key only when it is in `DirectContacts`' set (an accept that is not a commons poster's automatic one, a verify, a DM we
  wrote in; never the blocked, the tag-blocked or us). Every other confirmed session gets the same version without it: a
  stranger gets a responder-confirmed session from one DM (#290) and a blocked person keeps theirs (#249). The accepted
  cost: a contact learns the key only once a session is confirmed.
- **No capability bit.** `CAP_MESHCORE_DIRECT` (§3, §7) is not minted. The key's presence in our sealed profile is the
  claim of the compact DM door on that board, sent only while all four hold: the LoRa plane is on, the DM switch is on, a
  MeshCore board reported a key `MeshcoreKey` accepts (`meshcore_board_pubkey`), and the build is bound to read a MeshCore
  DM (`MeshManager.bindMeshcoreDirect`, beside `bindLoraDirect`). Nothing binds the seam in Stage 4; Stage 5 binds it from
  `di/MeshModule` once the MeshCore child, the teaching and the inbox exist, so a Stage 4 build never claims a door it
  cannot open, even when a backup brings `meshcore_board_pubkey` into it (`ownLora`'s own rule).
- **Republishing.** A claim change moves a sealed-only version past the cleartext one, judged against a persisted
  fingerprint (`MeshcoreClaimStore`: `meshcore_sealed_version`, `meshcore_published`, both riding a backup), as
  `BadgeJournal.sealedVersion` does: one fresh version per change, so `sentProfileVersions` re-sends it to every
  confirmed session, and the cleartext version never moves with no visible field changed. `custodyPresentsOtherThan`
  could not judge it: it reads the cleartext custody row. **The audience is in the fingerprint**: while a key is claimed,
  a change in the direct-contact set mints a fresh version too, and a version is only sealed for the set it was minted
  for. The receiver's gate takes an equal version, so a peer that becomes a contact must get a strictly newer version
  carrying the key, or the keyless copy it holds at the same version would stand; a peer blocked gets a newer keyless one,
  which clears it.
- **`CAP_LORA_DIRECT` is per kind.** It now needs a bound Meshtastic board (`ownLora`). It read only the switches, so after
  a MeshCore bind cleared the Meshtastic identity the profile dropped `loraNode` / `loraKey` and kept the bit. §7's
  "Binding a MeshCore board clears the Meshtastic binding, so `CAP_LORA_DIRECT` is not claimed beside it" was true of the
  settings and not of the profile until this.
- **Keys.** Canonical padded standard base64 of exactly 32 bytes (`CanonicalText.base64BytesOrNull`, the v3 encoder's
  round trip), stored in that spelling and compared as bytes; an Ed25519 point by RFC 8032 §5.1.3, and not of low order,
  which refuses the all-zero key with the rest of the 8-torsion (the #172 precedent). Field arithmetic over `BigInteger`,
  as `XeddsaVerify` already does: a check, not a primitive. A key that fails drops the field and never the profile, and,
  the profile being signed and newer, clears a stored key.
- **Storage.** `peers.meshcoreKey` (DB 35 → 36 after the rebase onto main's v35, its own step; Stage 5 bumps again for its SQLCipher
  contact table rather than change v36 in place, the 83cb47ce6 precedent; both fold into the next release's single bump, as v22–v27 did on
  2026-10-18). Written only by the sealed writer under the existing gate (`version <= 0 || version < updatedAt` refused,
  after `clampFuture`; absent clears); the cleartext writer never names it and carries it through on a `copy`.
- **The board map** (`MeshManager.meshcoreBoardMap`, `MeshcoreBoardMap`), the input Stage 5 teaches from. Direct contacts
  only (`DirectContacts.observe()`, so never a stranger, a commons poster's automatic accept — anyone with an invite could
  otherwise contest a contact's key or have a key taught —, a blocked or tag-blocked peer, or ourselves), with a pinned
  key, ranked verified, accepted (less the commons marks), authored, each carrying its row's `latestProfileVersion` — the
  presentation watermark, not the claim's own version, so never what a re-teach is ordered by. Never a row whose key, as
  bytes, is our bound board's. **Contested among live claimants only**: a key two live contacts claim is marked
  `contested` on both and neither is taught; with no proof of possession nothing here can tell which holds it, and a newer
  version alone never decides. A claimant is not live once this phone has not heard from it for 30 days (`lastSeenAt`, our
  clock merged with `MAX`; the ratchet era's hard cap): a wiped or reinstalled identity never sends the sealed profile that
  would clear its old claim, and would otherwise contest its successor's key at every contact for ever. Beside a live
  claimant the dead ones leave the map. The profile version takes no part: it moves only on an edit, so a contact who has
  not edited for a month is as live as one who did today, and two claimants' versions are two senders' clocks.
- **A routing hint only.** Stage 5 never attributes an inbound DM by a board's 6-byte sender prefix: the compact door
  names its sender by the pair seal's tag and AEAD alone (`LoraDirectSeal`'s trust boundary).
- **Never in logs or exports.** No log line, field-log line, debug-bridge or USB-control reply carries the key or a map
  entry (`MeshcoreBoardHolder.toString` names no key; `MeshcoreProfileLabTest` checks every node's log).
- **Owed by Stage 5.**
  - Before it binds the seam, narrow the fan-out on an audience-only mint: seal only to the peers whose `keyFor` answer
    changed, and mark every other owed session sent at the new version. A full fan-out per contact change is a burst of
    sealed `CHAT` frames under our own sender, and custody's per-sender quota of 200 can evict our own older DMs in it.
  - `offerLoraDirect` and the compact-tick gate read the claim of the kind `LoraDirectRouter.sinkFor` answered —
    `CAP_LORA_DIRECT` for a Meshtastic sink, a stored `meshcoreKey` for a MeshCore one — never the bit alone.
  - `bindMeshcoreDirect` is bound in `di/MeshModule` **before** `start()`: bound after it, every restart mints twice (an
    unclaimed version at start, then the claim).
  - `MeshcoreKey`'s point check is memoized by key string before anything collects `meshcoreBoardMap` on every
    `observePeers` emission: it is a few hundred microseconds of `BigInteger` per row.

Review of Stage 4 (crypto- and security-reviewer, 2026-11-13), applied: the key to direct contacts only, with the
audience in the version; the map fed by the direct-contact set; contests among live claimants only;
`MeshcoreBoardHolder.latestProfileVersion` named for what it is; `PeerEntity.toString` says the key only as set or unset.

Second round (2026-11-13), applied:

- **Liveness is `lastSeenAt` alone.** The first round's "or its newest profile is more than 30 days behind a rival's"
  decided on the newer version, which the contested rule forbids: it dropped a live contact who had not edited for 35 days
  whenever anyone claimed her key today.
- **One version, one length.** With the key going to direct contacts only, a keyed copy was 35 B (compact) or 58 B
  (named) longer than the copies every other confirmed session gets, and the burst of `CHAT` frames, each naming its
  recipient, would show every relay, radio and carrier who is a contact, and date an accept or a block by a copy that grew
  or shrank. While a key is claimed, every keyless copy carries `MessageContent.pad` (`"p"` on the named layout, the
  reserved compact label 12), zero bytes sized by the sealing encoder so its plaintext is exactly the keyed copy's length
  in the recipient's layout (`MeshcoreProfilePad`; a byte long where CBOR cannot spell the gap, never short), as ADR
  2026-11.xp6d pads `CTL_PRESENCE_KEY`. Additive: old builds and iOS skip an unknown label or key, and a receiver reads
  and discards it (nothing on the receive side changed). Vectors `messageContentV2ProfilePadded` and
  `messageContentProfilePadded`. **Accepted cost:** a burst on a contact-set change still dates "the contact set
  changed" — a version minted with no visible field moved — though not whose membership moved.
- **One version, one audience.** The audience carries the version it was read at under `profileLock`, and the fan-out and
  the intro seal and record that version, re-reading once if another was minted in between; before, they re-read
  `profileVersion()` after the lock and could seal the newer version for the older set.

**§3 amended.** "Which contacts" is the board map above, not "the pinned contacts whose signed profile carries `meshcoreKey`
and `CAP_MESHCORE_DIRECT`". "What Knit removes" changes from snapshot-and-overwrite to **refusal**: Stage 5 never
overwrites a board contact Knit did not add, so there is no snapshot to put back; a key already on the board as someone
else's contact is left alone and the teaching refused (the Stage 3 amendment's "teaching never evicts" said the same of
slots). Re-teach churn is bounded as Meshtastic's is: `LoraContactPolicy`'s `MAX_KEYS_PER_DAY` (2 distinct keys per peer
per day) and a write memo per board and key, so a contact whose profile flips between keys cannot make our board rewrite
its table.

**Deferred, a decision for the maintainer: proof of possession.** A sealed `meshcoreSig`, the board's Ed25519 signature over
`"knit-meshcore-bind/v1" ‖ nodeId ‖ key` made through `CMD_SIGN_START` / `DATA` / `FINISH`, can be added later as another
optional sealed field. Until then a false claim can only misroute the claimant's own DMs to a board that drops them, since
a board drops DMs from non-contacts.

**Paired.** knit-ios parses label 9 and the text key on `ProfilePayload` only, keeps the key apart from what its cleartext
writer touches, applies the same canonical-32-byte and point checks, and never advertises a key. iOS skips unknown CBOR
labels, so an older iOS keeps the profile. The brief is written and not yet sent.

## Amendment 2026-11-13 — the maintainer's Stage 5 decisions: a MeshCore board is Knit's alone

The Stage 5 design passes (lora- and security-reviewer, 2026-11-13) left four questions only the maintainer could answer,
and five design calls. The maintainer decided them on 2026-11-13.

- **The board is Knit-only.** Knit does not share a MeshCore board with the user's own MeshCore use, which removes the
  whole class of conflicts between the two: whose contacts, whose inbox, whose deletions.
  - **Setup erases** every board contact and every channel slot except Public (slot 0), one `REMOVE_CONTACT` /
    `SET_CHANNEL` at a time, after a consent sheet that says the board becomes Knit's. Never `CMD_FACTORY_RESET`: it
    formats the board's filesystem, minting a new identity and resetting the radio, which §4 and ADR 045 forbid.
  - **Restore** puts back the advert name, `manual_add_contacts` and `autoadd_config`, and clears the Knit slot and
    Knit's contacts. It does not bring back what setup erased.
  - **Knit's taught set is the board's contact table.** At each Ready, Knit overwrites a contact on a taught key to its
    own record and removes any other contact, counted and bounded per Ready. A contact deleted from another app is
    re-taught; there is no deletion-respect rule.
  - **Non-Knit traffic** (a DM whose text is not a Knit frame, a post on a slot that is neither Knit's nor Public) is
    drained, counted and discarded: never stored, logged, shown, relayed or put in custody.
- **§3 amended again.** The Stage 4 amendment's refusal rule ("never overwrites a board contact Knit did not add") and the
  original snapshot-and-restore both go: setup leaves no contact Knit did not add, and the SQLCipher table holds only the
  setup record (the prior advert name may be the user's real name), not a contacts snapshot or an added-keys list. The
  churn bounds stay.
- **The Public mirror stays** in v1 (Stage 6).
- **Proof of possession is required before release.** A sealed `meshcoreSig` beside `meshcoreKey`: the board's Ed25519
  signature over `"knit-meshcore-bind/v1" ‖ nodeId ‖ key`, made through `CMD_SIGN_START` / `DATA` / `FINISH`. Nothing has
  shipped, so a key without a valid signature is dropped as a bad key is, and never taught. It joins Stage 4's paired
  change: the unsent iOS brief gains it before it goes out. This replaces the Stage 4 amendment's "Deferred".
- **Approved design calls**, each to be recorded where Stage 5 builds it:
  - **Regional duty is Knit's alone on MeshCore**, whose firmware has only a 50 % token bucket: a band table checked
    against ETSI EN 300 220 (EU 868 sub-bands at 1 % / 0.1 % / 10 %, 433, US, ANZ), the strictest duty for a frequency
    no row matches, and a law check off the board's Δ`tx_air` over the trailing hour. ADR 2026-10.ug4a's "the law is the
    firmware's" does not hold on this kind.
  - **`Ceiling.FLOOD` counts flood bookings only**; the total still caps everything, and a frame over its own ceiling on
    an empty window is refused and counted.
  - **Two-part room posts on a public channel key**: fragment ids seeded randomly per process, up to 3 candidates per
    part with the combination that decodes and verifies accepted, capacity sized by airtime, and a per-plane author tag
    parked in `docs/NEXT_WIRE_BREAK.md`.
  - **Pricing** through a kind hook on `BoardTraits`: size on air by purpose, AES padding, +4 B on floods only, and a
    fixed per-packet term rather than × 1.06.
  - **Hearing** only from a verified, fresh Knit frame (an opened seal, or a channel post's verified author at zero hops),
    never from a sender prefix or a non-Knit DM; the trait splits into `live` and `heardZeroHop`.

## Amendment 2026-11-13 — Stage 5a-1: pricing, the band's law, the flood ceiling and the session fixes (draft)

Built under the Stage 5 design passes (lora-reviewer findings 1, 2, 3, 9, 10 and 12) and the maintainer's Stage 5
decisions above. Pure and JVM-tested; no MeshCore child is wired (`BUILT_BOARD_KINDS` and DI are unchanged).

- **A kind price hook.** The ledger priced every booking with Meshtastic's rules: a MeshCore packet would have been
  charged the 66-B XEdDSA signature (`SigningFacts()` reads as signing) and a compact DM the PKI overhead. `BoardTraits`
  now prices: `price(AirPurpose, …)`, `phyBytes(payload, price)` and `airMs(phy, radio)`, with the kind's fallback radio
  and duty and its room ceiling. `OutboundFrame.price` asks `LoraAirtime.price(OutboundFrame.purpose)`, and every booking
  goes through `phyBytes` and `airMs`. Meshtastic's hook is the code it replaced: each purpose maps to the slot, packet
  class and port it always read, at 24 B of framing and a 20.25-symbol preamble. `LoraAirtimeKindParityTest` holds a
  verbatim copy of the replaced code and checks every frame shape, payload size, `ModemPreset`, signing era and AEAD
  pairing against it; `LoraAirtimeParityTest`'s golden is unchanged. `PacketPrice` gained a `purpose`, null on Meshtastic,
  and its text reads as before.
- **MeshCore's price** (`MeshcoreTraits`, `companion-v1.18.0`). Every packet has a header byte, then the 4 B of transport
  codes on a scoped flood only, then the path length. The payload follows:
  - a room post is the channel hash, the MAC, and AES over the 3-B data header and the payload;
  - a zero-hop DM is the two hashes, the MAC, and AES over the timestamp, the flags and the COBS text;
  - an ack is the code;
  - a PATH return is a flooded datagram over its path plaintext.

  AES pads to 16 with no extra block on a multiple of 16. MeshCore never takes Meshtastic's signing facts, and before any
  report it prices at the companion's build default (869.618 MHz, BW 62.5, SF8), not at LongFast.
- **The airtime term: the preamble, not a ratio.** (`FIXED_MS` is the 60-send measurement's uncertainty at 181 B, not a
  bound.) The firmware's preamble is 32 symbols at SF ≤ 8 and 16 above
  (`RadioLibWrappers.h:56`). Q5's 181-B packet at SF7 is then 633.3 ms by the formula, against 633 ± 17 ms measured; the
  first cut's 600 ms used a 16-symbol preamble. So the decided "fixed per-packet term rather than × 1.06" prices the
  formula at that preamble plus 17 ms per packet, the measurement's sigma, not 33 ms on top of a wrong formula. A
  constant 33 ms would have under-priced SF8 by 33 ms a packet and over-priced SF ≥ 9. **Owed:** a small-packet bench
  point (a 6-B ack and a 40-B DM, Δ`tx_air` over 60 sends at SF7 and SF8).
- **The compact DM's numbers.** `maxFlights` is 2: the board's 8 ack codes are attempts, three to a flight. Other
  clients' codes are the session's `Busy` to answer. `deadlineMs` is `MeshcoreSession.MAX_FLIGHT_MS` plus the 10-s answer
  slack, whatever the airtime: the session clamps each `est_timeout` to 60 s, so its own cap always ends a flight first.
- **The band's law is Knit's on this kind.** MeshCore's firmware has a 50 % token bucket and no region (Q4), so ug4a's
  "the law is the firmware's `DUTY_CYCLE_LIMIT`" does not hold here.
  - `LoraBand` reads the duty off the frequency and bandwidth: ERC 70-03 Annex 1's EU 863–870 sub-bands, 433.05–434.79,
    US 902–928 and ANZ 915–928. Every EU edge and Annex 1 label is to check against EN 300 220. The EU 10 % rows (869.4–869.65
    and 433) and the US and ANZ rows are confirmed by `LoraRegion`. MeshCore's own presets (869.618, 869.525, 910.525,
    916.575, 917.375 MHz) all land in a row.
  - The whole channel must sit inside a row, and the strictest holding row wins. A channel no row holds, a band before it
    is known, and the Asia-Pacific presets the table lacks all read `STRICTEST_PERCENT` (0.1 %), never `FALLBACK_PERCENT`.
  - Each EU duty assumes its row's power limit (25 mW e.r.p. for the 1 % and 0.1 % rows, 500 mW for 869.4–869.65, 10 mW
    at 433), to check against EN 300 220 with the rest. The board's power is the user's call, never Knit's (ADR 045).
  - **Cost, by design:** on a real 0.1 % sub-band, or a frequency the table does not hold (any Asia-Pacific preset today),
    the allowance is 0.45 s a window. No room post fits the flood ceiling and no three-airing DM fits the total, so the
    plane is effectively dead there until a row with a source is added.
  - `MeshcoreTraits.dutyPolicy` feeds the allowance, so a window never allows more than the band's duty; the worst
    straddling hour is 5/8 of it.
- **The law check** (`MeshcoreDutyLaw`) holds every Knit frame while the board's own trailing-hour `tx_air` reaches the
  band's duty. That counter covers every client, our acks and PATH returns, and repeats.
  - Its clock is the board's uptime. A sample whose uptime or counter went backwards, or whose boot time (wall − uptime)
    moved by more than 2 minutes, starts a new boot.
  - **What persists:** the ledger stays unpersisted, as ug4a left it. The law's ring of `(uptime, tx_air, boot)` samples is
    the state Stage 5a-2 persists in `meshcore_plane_state`. It survives an app restart, because the board's counter does,
    and is dropped at the first sample of another boot, because a reboot zeroes the counter.
  - **A board reboot never hands out a fresh hour either.** On a new boot a pre-boot term is carried on the wall clock
    (the samples already carry their boot time) and added to every bound until an hour after the new boot (or after the
    last sample, if that is later). It is the old
    boot's trailing-hour bound at our last sample of it, plus the seconds from that sample to the reboot, at most an
    hour: the radio may have sent all of them while the app was away (other clients, acks, repeats), since the counter
    rises no faster than the clock. A restored ring from a boot the board has since left becomes the same term. It is
    persisted with the ring.
  - With no baseline an hour old, it bounds the hour from what the board can have done: since boot (`tx_air` itself), or
    from a sample inside the hour plus at most the seconds before it. So a process with nothing restored reads the whole
    counter, up to an hour of it, as possibly spent. A restart can cost a hold of up to an hour on a 1 % band; it never
    hands out a fresh window.
- **`Ceiling.FLOOD`** is 0.15 of the allowance, counted over flood bookings alone. A `Booking` carries the ceiling it was
  admitted under, and `record` takes it. DMs booked for every airing at send never eat the room's share, and the total
  still caps every flood. The room's ceiling is a trait: Meshtastic `FULL`, as the core always queued its posts, and
  MeshCore `FLOOD`.
- **`NEVER` is judged against a frame's own ceiling** on an empty window. On a 1 % band a two-part post fits the
  allowance and never the 0.675-s flood ceiling. Before, it read `CEILING`, and the pacer woke on its 1-s idle tick for
  the post's whole life.
  - The pacer now drops a `NEVER` frame (`drainNever`), **once the board's duty is known** (`LoraAirtime.dutyKnown`): under
    MeshCore's 0.1 % fallback a post or a DM the real band carries reads `NEVER`, and is skipped, not dropped, until the
    duty lands. The core abandons a dropped frame, counts it under its own `loraAirNever` (never `loraRoomTooLong` or
    `loraTooBig`, whose meanings stand) and logs `lora air never <label>`.
  - **On Meshtastic this changes two corner cases:**
    - A `FULL` frame larger than the whole allowance is dropped and counted, where before it waited out class shedding
      under the idle tick.
    - A `HALF` frame larger than half the allowance is `NEVER` rather than `CEILING`. The Meshtastic hooks ask
      `admits(HALF)` before queuing, so it is never queued unless the allowance shrank after it was.
- **The holder rule's cost.** `admitBest` lets only a `DIRECT` frame refused on the total hold what ranks behind it. That
  rested on "every room post is one packet", which a two-part MeshCore post breaks.
  - A two-part post refused on the total or its flood ceiling is skipped while a later one-part post goes past it. Room
    order can invert.
  - In a window that hands back a packet's worth at a time, the two-part post may age out at `FRESH_MS`.
  - Accepted: a room post that held would let one long post freeze the room, and DMs still dequeue first.
- **The brake ring** (`MeshcoreAirRing`) also restarts when the same poll's CORE uptime goes backwards, which the counters
  alone can hide. Its reading is what 5a-2 hands `onBandReading`.
- **The refusal classifier, by context.** The session reads the firmware's codes by command before they reach
  `MeshcoreRefusal.classOf`:
  - `TABLE_FULL` is no free packet, so `Busy`. On a DM it is also an over-long text, which the encoder never writes.
  - `NOT_FOUND` on a DM is no contact, so `OWN_NO_KEY`.
  - `NOT_FOUND` on a Public post's channel text is no free packet, so `Busy`.
  - `NOT_FOUND` on a room post's channel data is a slot the board no longer has. It was `Rejected(NOT_FOUND)` and read as
    `OWN_NO_KEY`, harmless today because only a DM acts on that class. It is now `SLOT_NOT_KNIT` (`OTHER`), and the slot is
    read again.
  - `ILLEGAL_ARG` is `TooLarge`; everything else is `OTHER`. MeshCore answers no `PACE`, `DEST_REFUSED` or
    `PEER_LACKS_KEY`.
- **The session must-fixes** (the Stage 3 carry-forward):
  - A send its call deadline caught before the actor started it is `Busy`, so the core requeues it. A `Timeout` would read
    as "likely aired". `Timeout` stays for a started attempt whose `SENT` never came.
  - The deadline's claim and its answer are one step (`SessionOp`), and the actor's refusal claims in the same atomic step
    and skips what the deadline took. Before, `serve`'s `finally` could answer `notReady` between the claim and the timeout,
    and a check-then-act refusal still could; a two-thread stress test pins it.
  - A write over `MAX_NOTIFY`, or one the GATT client refuses as over `mtu − 3` (`STATUS_TOO_LONG`, pinned equal to the
    session's `WRITE_TOO_LONG`), refuses that one command: a send answers `TooLarge`, counted `commandsRefused`. It used to
    end the session into the back-off loop. A test checks every encoder's longest output against `MAX_NOTIFY` and the
    pinned command set.

Owed to Stage 5a-2:

- feed `MeshcoreTraits.dutyPolicy` and `RadioParams` into `LoraAirtime.onRadio` **before** the session reports Ready, so
  the pacer never runs a Ready plane on the 0.1 % fallback. `LoraAirtime`'s duty is never cleared, so a later board
  session reads the previous board's duty until its own `onRadio`: harmless while `onRadio` precedes Ready;
- poll `MeshcoreDutyLaw` from the session's CORE and RADIO stats, persist its ring, and hold the pacer on its verdict;
- hand `MeshcoreAirRing`'s reading to `onBandReading`;
- book the acks and PATH returns by purpose;
- construct the MeshCore plane with `MeshcoreTraits`;
- add `floodCeilingMs` / `floodUsedMs` to the face (finding 11);
- decide with Stage 6 how a Public post is admitted: on MeshCore `PUBLIC_POST` is priced as a flood but admitted under
  `HALF`, so Public posts do not count toward the 0.15 flood share.

