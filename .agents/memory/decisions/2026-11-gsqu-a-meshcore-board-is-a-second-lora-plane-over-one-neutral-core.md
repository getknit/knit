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
  - Our board's ack of a DM to us is booked as 1 + `multi_acks` packets (corrected by the Stage 5a-2 review: at most one multi-ack, below).
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
  changed" — a version minted with no visible field moved — though not whose membership moved. *(Superseded by the Stage 5b-1
  amendment, the maintainer's decision of 2026-11-13: an audience-only mint now seals only the peers whose answer
  changed, so whose membership moved is shown too.)*
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


## Amendment 2026-11-13 — Stage 5a-2: the hooks, the hearing, the law wired, and the child behind `MESHCORE_PLANE` (draft)

Built under the Stage 5 design passes' 5a-2 part (lora-reviewer findings 4, 6, 7, 11; the security pass's B6, replay,
`cleanReceived`, KIND-ack and `meshcore_device_name` items) and the 5a-1 amendment's owed list. A debug build
(`MESHCORE_PLANE`) builds a MeshCore child; a release build builds none (`di/builtBoardKinds`).

- **The kind is injected.** The core takes a `BoardPlane` (traits, link, hooks factory); `meshtasticPlane` builds the
  Meshtastic one exactly as the core built it for itself, and the old `MeshtasticLink` constructor stays as a secondary one.
  `di/loraPlaneOf` defines each kind's transport, sinks and face in one call.
- **`route` reads the slot and the data type first** (the channel key is public). Knit's is channel data on the board's own
  Knit slot (`BoardKindHooks.boundSlot`: `Ready.channel`, no slot setting) under `KNIT_DATA_TYPE`, and a contact DM in a
  COBS-decoded `0x11` seal's shape; everything else is counted and discarded as `DROPPED` — never a hearing, never stored,
  logged, shown, relayed or in custody. COBS decoding is no proof: a text in any other shape falls back to a foreign DM. Public
  slot 0 is counted and discarded until Stage 6. **A `0x04` fragment is discarded too, and `roomPartLimit` answers 1 until
  Stage 5c**: the core's reassembler keys on a sender MeshCore never names, so a two-part post on the public key waits for 5c's.
- **Our board's answers are booked from one place** (`route`), for every live plain or signed-plain contact DM, Knit's or not:
  priced against `companion-v1.18.0`
  (lora-reviewer N6): the ack code is 6 B for a plain text and 4 for a signed one (`BaseChatMesh.cpp:244-256`). Direct:
  `sendAckTo` sends at most **one** multi-ack, whatever `multi_acks` reads (stored unclamped, `MyMesh.cpp:1498`), then the ack,
  along a known path — or one flooded ack with transport codes when our record of the sender has no path. Until 5b's taught set
  says which, the larger of the two is booked (as milliseconds). Flooded: one PATH return at
  `pathReturnPlaintext(pathLen, txtType)` = 1 + count × hash size + 1 + the ack (`Mesh.cpp:448-486`) at `PATH_RETURN` (the
  session's own `PathReturn` event is only counted); replayed, nothing.
  Under `FULL`, never the room's flood share. **`DirectFlooded` has no `AirPurpose` of its own**: its surcharge is the same
  packet flooded less direct, booked as milliseconds (`LoraAirtime.recordMs`), since it is a difference of two airings.
- **`checkSlot`** is clear for a compact DM and refuses a room post only on a reading of `knit=false`, or none this connection.
- **The hearing.** `heardLive` splits into `live` (any path; the cover's lift) and `heardZeroHop`, and `hearsSender` says
  whether a packet's sender may key one (false on MeshCore). A room post's author is heard only when the post is live and
  zero-hop, fresh (`-MAX_FUTURE_SKEW_MS..FRESH_MS`) and signed by the author's pinned key (`LoraAuthorCheck`); its radio is
  counted by author. An opened DM hears its board only at zero hops. `noteHeard`, `noteBoard` and `onBoardKeyHeard` never key
  on a MeshCore prefix.
- **The law and the radio.** The session tells the hooks before it publishes Ready (`MeshcoreSessionObserver`): the ledger
  takes the board's radio and band (`onRadio`), or `forgetRadio` with none — so a later board never runs on the earlier one's
  duty. Each poll feeds `MeshcoreDutyLaw`; while it holds, the pacer holds every frame to `releaseInSecs`
  (`LoraPacePolicy.holdForLaw`, a floor on `nextDueAt`). The ring persists in `meshcore_plane_state` under a SHA-256 digest of the board's key (never the key), saved once a spacing, applied when that board identifies (one per board since the review, below).
- **The `dialled` race.** A session's Ready carries the address it dialled (`LinkState.Ready.address`, `MeshcoreDetail.dialled`),
  and the binding is written under it (`BoardBinding.dialled`, `di/bindingAddress`), so on an A→B rebind A's late Ready writes
  nothing under B. `BoardBinding` stays where it is: moving it into the Meshtastic kind changes nothing about the race.
- **The door claim per kind.** `MeshManager` gates an offer on the claim of the kind its sink drives (`LoraDoorClaim`:
  `CAP_LORA_DIRECT`, or a stored `meshcoreKey`).
- **Not yet, by design.** `teachKey` refuses and the MeshCore board map is empty until 5b, so no DM goes; `bindMeshcoreDirect`
  stays unbound; the setup record is held in memory only (5b persists it in SQLCipher); `meshcore_device_name` is never
  written (`BoardBindPolicy`), nor any `meshcore_setup_*` / `meshcore_prior_*` key.
- **The face.** `LoraFacts.floodSpent` (the room's saturated notice at 90 % of the flood share), `roomBodyBytes` (40 B on
  MeshCore in the `0x05` form, pinned; 5c raises it with elision), `directReach` (the acked boards a MeshCore DM notice reads,
  never the flood-fed `reachable`), and the firmware version cleaned (`TextLimits.cleanReceived`) before anything shows it. The
  dump is `…debug.LORA --es kind meshcore`: counts and own-board state only. MeshCore's counters live in
  `MeshcorePlaneCounters`, not `MeshMetrics`.

Review of Stage 5a-2 (lora-, bluetooth- and security-reviewer, 2026-11-13), applied:

- **An unbonded board is never dialled in the background's place** (bluetooth B1): once the dial policy says Background
  and the board is not bonded, the session dials nothing and waits out the background window, re-reading the bond each
  minute, so after the first three direct dials only the hourly direct net dials it — never a 30-s high-duty dial holding
  `meshcore-dial` every backoff (the #67 blackout, ADR 2026-09.hp88). Chosen over ending terminal: a board the user pairs
  later in the system's settings comes up without a re-pick.
- **A board without NUS is terminal on the second reading in a row** (bluetooth N1, the 3c amendment's open item): discovery
  that succeeded with no NUS service is `NusDialResult.ServiceAbsent`, backed off once, then `StaleBond` bonded or
  `NeedsPairing` not, as Meshtastic's `ServiceAbsent`. A discovery that failed still backs off.
- **The packets' collector**: one collector is the contract, not when it subscribes; the session's buffered channel keeps an
  early drain, and the core's launch order is not relied on (bluetooth N2).
- **An opened DM is a hearing only fresh** on a kind with `hearsSender` false (security N4): its `sentAt` within the room
  post's `-MAX_FUTURE_SKEW_MS..FRESH_MS`, since the seal opens for a day and a DM re-aired direct after MeshCore's dedup forgot
  it would otherwise lift a mark for a board that may be gone.
- **Release gate** (security N5): on a `hearsSender`-false kind, `onFramePacket`'s presence (`isPresenceEvidence` →
  `noteReachable`) and the router's counting of a re-aired signed frame under its author are not verified; this widens #283 to
  the MeshCore plane, debug-only. Before MeshCore ships in release, `noteReachable` there waits for the author check and
  #283's fix covers every LoRa kind.
- **The law on the face** (lora N7): the hold rides `LoraStatus.lawHeld` / `LoraFacts.lawHeld`, the room's notice reads it as
  `RoomSaturated` and a LoRa-only DM's as `LoraOnlySaturated`, and the dump carries `law.holding` and `releaseInSecs`. The rings
  are kept per board digest, the last four boards (`LoraPlaneSnapshot.meshcoreLaws`), so an A→B→A rebind keeps A's.
  **Cost, by design:** with no ring for a board (its first bind, a fifth board since, a wall clock moved back) the hour is
  bounded from the counter since boot, so a board up over an hour that sent more than its band's hourly duty (36 s on a 1 %
  band) holds every frame for up to an hour at its first connection. The pre-boot term and a ring's samples live on the
  phone's wall clock: a clock moved back only holds longer, but a forward move of J (an automatic time correction, a manual
  change) shortens a carried pre-boot term by J, so after a reboot or restart Knit frames can leave up to J early while an
  earlier boot's hour is still spent. A hold already set does not move: it runs on the monotonic clock.
- **The pacer is told only of a change** (lora N8): a hold that starts, lifts or moves by more than 10 s; a 1-s poll no
  longer wakes it every second. A ring restored after its board identified holds at once (lora N9). The verdict, the record of the pacer's hold and the call that sets it are one step under the hooks' monitor, so the session's actor and the core's restore can never hand the pacer their answers in the other order (the follow-up's L1); a board change's lift goes through the same step. Before any sample of a boot the law answers from a live pre-boot term alone (`MeshcoreDutyLaw.verdict` on the wall clock), so a restore that left no usable ring never reads as "nothing known" and lifts a hold while an earlier boot's hour is still spent; only a board with nothing known lifts it.
- **`directReach` is acked boards** (lora N10): a zero-hop hearing never marks a board reachable — it shows their radio reaching
  us, not ours reaching them. If 5b wants a notice for heard-but-never-DM'd peers, it is a separate set.
- **`heard` and `boardsHeard` read differently on MeshCore** (lora N11): `heard` counts fresh-frame authors at any hop count,
  unverified; `boardsHeard` counts verified pinned zero-hop authors only. The dump's `meshcoreZeroHopKnitPosts` counts live
  zero-hop Knit posts, unverified and with no id, beside it.
- **Owner choices accepted:** `roomPartLimit` 1 and `0x04` discarded until 5c; `DirectFlooded` as `recordMs` with no
  `AirPurpose`; every answer under `Ceiling.FULL`; `boardsHeard` pinned and verified only; a 40-B one-packet room budget.

Owed to Stage 5b: narrow the audience-only mint's fan-out, memoize `MeshcoreKey`'s point check, then bind
`bindMeshcoreDirect` before `start()` and feed the MeshCore plane `meshcoreBoardMap`.

## Amendment 2026-11-13 — Stage 5b-1: an audience-only mint seals only whom it changed, the point check is memoized (draft)

Three of the items the Stage 4 amendment owed Stage 5, built before 5b-2 binds the seam, under two crypto- and
security-reviewer rounds (2026-11-13) and the maintainer's decision after the review stop rule. It amends Stage 4's
"Republishing" and supersedes the second round's accepted-cost sentence. Nothing on the wire, in the vectors or in the
schema moved, and nothing the iOS port mirrors (a sender's fan-out schedule is local; every receiver takes what it took
before).

- **An audience-only mint seals only the peers whose answer changed** (`MeshcoreFanOutRule.plan`, pure). While a key is
  claimed, a change in the direct-contact set mints a fresh sealed-only version (one version, one audience, unchanged). A
  full fan-out of it, one sealed `CHAT` frame per confirmed session under our own sender on every accept, verify, block
  or removal, let custody's per-sender quota of 200 evict our own older DMs. It is now sealed to the owed sessions whose
  `keyFor` answer changed — a new contact a strictly newer keyed version, a blocked peer a strictly newer keyless, padded
  one, which clears it —, to those whose copy may be gone (below) and to those whose last copy is not known; every other
  owed session is marked sent at the new version unsealed.
- **The maintainer's decision (2026-11-13): accept the leak.** Sealing the changed peers alone names them: the recipient
  is in the clear in the `RelayEnvelope`, `ChatContent.attachmentHash` is our avatar hash, also in the signed cleartext
  profile, and every copy of a version is one padded length. So a relay, carrier, radio or the spool learns whose
  direct-contact status we changed and when: an accept, verify, block or removal shows as one profile-sized sealed frame
  to that peer. This **supersedes** the Stage 4 second round's "though not whose membership moved", marked so where it
  stands. The review rounds' two designs to hide it both drew a new Blocking finding — a fixed-size burst padded with
  random cover seals let the changed peer stand out at 65–85 % once stale re-seals took cover places —, which tripped
  `review-rounds.md` §5; the options put to the maintainer were the corrected cover, the key to every session, a full
  fan-out per change, and accepting the leak. `SECURITY.md` states the trade-off, scoped to builds that claim a MeshCore
  key (debug builds behind `MESHCORE_PLANE` today).
- **Marked sent only where provably safe** (`MeshcoreFanOutRule.spares`). A session is spared only when the copy it was
  last sealed **in this process** is the new version's copy for it but for the number: the version is the claim
  journal's own mint, the claim is the same key at both, every other field the copy shows (name, status, photo,
  open-to-chat, LoRa board, badges; a fingerprint of the keyless payload with its version zeroed,
  `SealedProfileEra.visible`) is equal, and the peer's answer is equal. Then the copy it holds carries the key exactly
  when the new one would, and its length is the one its own version padded to. A key gained, dropped or changed, or any
  visible field moved, fans out to all. A peer whose last copy is not known — nothing recorded, its era past the eight
  kept (`MAX_SEALED_ERAS`), a contact-card intro sent it since (`sealedOutsideFanOut`, below), or a version two fan-outs
  built different copies of (a field written before its edit's version bump, read by a heal in between;
  `ambiguousEras`) — is sealed.
- **"Last sealed" is not "held".** A copy sealed to a peer dark past custody's TTL (24 h, `ForwardRepository.DEFAULT_TTL_MS`)
  or evicted by the quota is gone, and sparing that peer on the next contact change would leave it keyless until a
  visible edit or a restart; the first claim at 5b-2 with half the contacts away is exactly this. The record keeps when
  the copy a peer holds was sealed (`SealedRecord.sealedAt`, our wall clock, the clock custody expires by), a spare
  carries that time on and never refreshes it (the 8e041ba9 re-park lesson), and a spare-eligible session whose copy was
  sealed `SEALED_PROFILE_STALE_MS` or more ago — the custody TTL, the earlier of the two bounds a copy outlives (the spool
  keeps it 48 h, `RatchetSessions.STRANDED_TAIL_MS`) — or is stamped in the future (a clock moved back) is sealed again.
- **One writer of the record.** The fan-out holds `sealedFanOutLock` (outside `profileLock`, never inside), so the record
  a spare reads cannot move between the decision and the mark: before, two fan-outs could overlap, and a slower one's
  older seal landing after a newer one's mark would leave a peer holding a copy the record does not name. The intro never
  takes it: it answers an init from inside the inbound pipeline, which must never wait behind a fan-out that may itself be
  waiting on the ratchet. Instead the intro marks its peer, with a fresh token, before it seals (`sealedOutsideFanOut`); a
  marked peer is owed the next fan-out's seal whatever the record says and is never spared, and the mark is cleared only
  by a fan-out seal that read the same token before sealing.
- **A reset or a restore needs no more.** A fresh root does not clear `sentProfileVersions`: a peer that wiped, restored
  or reset re-forms the session with an init, which the intro answers with the current copy and a mark, so the next
  fan-out seals it (the crypto-reviewer's answer, 2026-11-13; `MeshcoreProfileLabTest`'s reset scenario).
- **A mint the intro makes is fanned out.** The intro reads the audience through the same minting path the fan-out does,
  so a claim or badge change it reaches before the watcher is minted there, and the watcher's own pass then finds
  nothing to publish and never fans out: the confirmed sessions waited for the next heal (the chaos sweep's find, on the
  contact-set scenario; it predates 5b-1). The intro now launches a fan-out when its read moved the version.
- **Counted, never logged.** The spares and the stale re-seals are `MeshMetrics` counters in the seals group
  (`sealedProfilesSpared`, `sealedProfilesStale`, in the debug bridge's `STATE`). No log line names them: the version is
  a wall-clock stamp, so a line would date a contact-set change and size our session list for whoever holds a bug report.
- **Restart.** `sentProfileVersions` and the eras are in memory, as before: after a restart nothing is known, so the first
  fan-out seals the current version to every confirmed session once (what it did before) and spares nobody, and
  `meshcore_sealed_version` / `meshcore_published` keep a restart from minting at all.
- **Accepted cost, besides the leak.** A spared peer keeps the older version number for our presentation
  (`PeerEntity.updatedAt`, the board map's `latestProfileVersion`), which only ever orders presentations; the lab's
  convergence oracle has a parity mode for it (`MeshLab.assertConverged`'s `sealedTo`). The badge mint's path is unchanged
  and safe as built (the crypto-reviewer, 2026-11-13): it always fans out to every owed session.
- **The point check is memoized** (`MeshcoreKey.bytesOrNull`): a bounded LRU (`MEMO_ENTRIES`, 256) of canonical key string
  → verdict. Only a canonical spelling reaches it, and canonical text and its bytes are one to one; it caches a pure
  function, so an eviction only costs the check again. `accepts` stays uncached.
- **Bind before start.** `bindMeshcoreDirect`'s KDoc says it is bound before `MeshManager.start()`; a bind after it logs a
  warning (the start then mints twice: unclaimed, then the claim).

Owed to Stage 5b-2: bind the seam in `di/MeshModule` before `start()`, feed the plane `meshcoreBoardMap`, and the per-kind
offer gate (`LoraDoorClaim`, built in 5a-2).

## Amendment 2026-11-13 — Stage 5b-2, first part: the taught set, the Ready reconcile and the erase (draft)

Built under the Stage 5 design passes' 5b part (security B1, B3 and the `GET_CONTACTS` iterator; the no-logs item) and the
maintainer's Stage 5 decisions (the board is Knit's alone). The persisted setup record, the unbind / sign-out / cross-kind
rules, the backup class and the door (`meshcoreBoardMap` fed to the plane, `bindMeshcoreDirect` bound) are the second part:
until it lands the MeshCore board map stays empty in the app and no DM goes, so what is below runs in the JVM tests alone.

- **`MeshcoreContacts`** (pure, the hooks hold one) decides the taught set from the board map (`holdersOf`: each holder
  addressed by its key's 6-byte prefix, carrying the key and its rank; `direct` false and `contested` when two claims share
  the key or two keys share the prefix). Skipped and counted: no valid key, not a contact, contested (Stage 4's or the
  prefix's, security B3), our own key, past `LoraContactPolicy.MAX_KEYS_PER_DAY` distinct keys per peer a day, ranked past
  the board's capacity (`DEVICE_INFO`'s `MAX_CONTACTS / 2`, doubled). A key that leaves the set (a contact removed or
  blocked) is removed from the board (ADR 2026-09.adgd). A DM our board refused for want of the contact is taught only for a
  key in the set, once per (our board, key) until a DM to it is acked — `LoraContactPolicy`'s write memo.
- **The session sends to and pins only taught keys** (security B1): `MeshcorePins.keyFor` answers null for any other key
  (`NO_RECORD`), a contact read never feeds the send map, and `learn` refuses a prefix another key holds (security B3).
- **The Ready reconcile**, on a board set up for Knit only (Knit's slot and the `Knit abcd` name: a board nobody set up keeps
  its owner's contacts) and only once the taught set is known (a Ready that beats the board map removes nothing): **one**
  `GET_CONTACTS` listing from `since` 0 (`MeshcoreProto.listContacts`), never one per key; then every contact on an untaught
  key is removed, every taught key's contact that is not Knit's record is overwritten to it, and a missing taught key is
  written while the local count (the listing's, kept as writes and removals read back, and on `0x8F`) leaves room —
  removals and overwrites at most 16 each per Ready (`MAX_REMOVALS_PER_READY`, `MAX_OVERWRITES_PER_READY`, proposed
  defaults), one step a tick. A taught key listed as Knit's record is pinned as read, so the per-key re-assert is skipped. A
  taught key whose prefix a listed contact of another key shares is written only once that removal reads back; else the
  prefix is contested for the connection (`MeshcorePins.contest`) and nothing is sent to it. A listing that fails or comes
  short of its `CONTACTS_START` count removes nothing and leaves the per-key re-assert.
- **The record** is type CHAT, `flags = 0x01`, `Knit xxxx` from the key, `out_path_len = 0` (`MeshcoreContacts.isKnitRecord`).
- **Setup's erase** runs after the reads and before the writes: one listing, `REMOVE_CONTACT` each contact (a taught key
  already holding Knit's record is kept, so a setup run again writes no flash for it), then each slot other than Public and
  Knit's cleared, one command at a time, each read back. A board whose slots are all taken is no longer `NoFreeSlot`: its
  first non-Public slot becomes Knit's (only a board with no slot but Public is refused). Never `CMD_FACTORY_RESET` (51): not
  in `ENCODED_COMMANDS`. The setup's line counts what it erased (`lora setup kind=meshcore … erased contacts=N slots=M`).
- **Restore** removes Knit's contacts first (named `Knit xxxx` for their own key, or taught), from one listing, each read
  back, then the slot and the settings as before, and forgets the taught set; it does not bring back what the erase removed.
  After a Restore the hooks teach nothing until the next setup. A setup's own rollback does not clear contacts.
- **Never in a line.** `BoardAddr.MeshCore` and `LoraBoardHolder` print no prefix or key; every new count is a count.

Review of Stage 5b-2's first part (lora- and security-reviewer, 2026-11-13), applied:

- **Every contact write and every MeshCore DM is gated on a board set up for Knit** (both reviewers' Blocking finding: B1
  came back through the taught set, which is the policy's wish, not the board's state). Off such a board — setup never ran
  or failed, or a Restore and then a process restart — the session sends nothing (`NO_RECORD`), pins nothing (the Ready
  re-assert, the `0x81` / `0x8F` jobs and `sendDirect`'s pin are dropped), teaches and resets nothing and removes nothing
  (`ContactWrite.NOT_SET_UP`, which a held frame reads as refused). Only setup's erase and Restore's own removals, each
  inside its own transaction, remove off that gate. **Part 2 tightens it**: the gate also needs this phone's persisted setup
  record for that board address — the record is the consent; a board's name and slot are a claim any client can write — and
  the hooks' `restored` is derived from that record rather than held in memory.
- **A removal decided earlier is re-checked when it runs** (lora N1): the hooks' removals of keys that left the taught set
  (`removeUntaught`) and the reconcile's removal steps skip a key taught again since.
- **`NO_RECORD` carries the send's own handle** (lora N2), as `UNPINNED` does, so the core's once-per-handle `onNoKey` acts
  on every refusal (the hooks refuse an untaught key, the peer is marked keyless) and never leaves a frame unaccounted.
  Telling the core which holders are untaught so it never offers to them waits for part 2's door.
- **A listing's prefix contests stand across a Ready** until the next whole listing replaces them (lora N3): before, a new
  Ready cleared them and a queued DM could go to the prefix before the reconcile listed. They are kept per our own board
  (the follow-up's shape 9): a Ready on another board (a rebind) clears them, while a short or cut listing on the same board
  keeps them — else a peer would stay refused `NO_RECORD`, and held keyless, on a board where nothing shares its prefix.
- **The firmware rewrites a contact behind Knit** (lora N4): a heard advert sets its name and type
  (`BaseChatMesh.cpp:191-193`) and a PATH its `out_path` (`:357`). So a taught peer whose own board is not named `Knit xxxx`
  fails `isKnitRecord` after each advert and is overwritten at each Ready (bounded by `MAX_OVERWRITES_PER_READY`), and Restore
  counts it as Knit's only while it is taught — a contact taught in an earlier process and renamed since is left by a Restore.
- **A listing is bounded in length and time** (security N5): past its count and 8 frames, or 60 s, it ends short, and a short
  listing removes nothing.
- **The placeholder map never emits** (security's interim caution): until the second part feeds `meshcoreBoardMap`,
  `di/MeshModule` hands the MeshCore plane `emptyFlow()`, not an empty map. An empty map is a taught set of nothing, and the
  Ready reconcile would remove every contact (16 a Ready) on any board passing the Knit check — one another client or another
  Knit install dressed up. With no map the session is never given a taught set, so no reconcile runs. **Part 2 must make the
  first map the plane sees the real one**, never a placeholder or a transient empty emission.
- **Security N6 does not apply on this tree**: `…debug.LORAPROV` drives the Meshtastic plane instance only
  (`DebugBridgeReceiver`'s `lora` is `loraPlane(BoardKind.MESHTASTIC)`), so it cannot reach, let alone erase, a MeshCore
  board. A MeshCore setup action, with an explicit erase extra and the counts in its reply, is part 2's.

Owed to the second part: the SQLCipher setup record (DB 36 → 37 after the rebase onto main), unbind / sign-out / cross-kind, the backup class, the door,
`bookAnswer` priced direct for a taught and pinned sender, and a MeshCore setup action on the debug bridge naming what it
erases. The lab's first MeshCore DM case needs 5c's `MeshLab.node(board = MESHCORE)`.

## Amendment 2026-11-13 — Stage 5b-2, second part: the setup record, the consent gate and the door (draft)

Built on the first part under its reviewers' obligations. A debug build (`MESHCORE_PLANE`) now teaches its MeshCore board,
claims the door and sends compact DMs; a release build builds no MeshCore child and binds no seam (`bindsMeshcoreDirect`).

- **The setup record** is the SQLCipher table `meshcore_setup` (DB 36 → 37, `MIGRATION_36_37`; v33–v37 fold into the next
  release's single bump), keyed by board address: the slot and whether setup wrote it, the prior advert name (possibly the
  user's real name), `manual_add_contacts`, `autoadd_config`, and `pendingCleanup`. No contacts snapshot. Never in a backup
  (`BackupTables.DEVICE_LOCAL`, ADR 2026-09.6mj7); sign-out clears it with the whole database
  (`clearApplicationUserData`); a cross-kind bind keeps it (`BoardBindPolicy` edits DataStore alone). Its `toString` names no
  field. Every finished setup writes it, even one that changed nothing: it is the consent. A setup that failed and could not
  put back what it wrote leaves it pending, never as consent.
- **The consent gate** (security, first part's rounds): `knitBoard(c)` also needs this phone's record for the dialled
  address (`MeshcoreBoardOps.setConsent` at the time — superseded by the maintainer's decisions below —, set by the hooks from the record at each connection and by a setup the session
  ran). A board another client or another Knit install dressed up with Knit's slot and `Knit abcd` name gets no removal,
  overwrite, pin, path reset, teach or DM. A consent that arrives after Ready re-arms that connection's one reconcile.
  `MeshcoreHooks.unconsented` replaces the in-memory `restored`: it is read from the record at every connection.
- **Unbind** (`MeshcoreHooks.unbind`): the board up now, Restore's clearing runs, verified, then the record goes (`CLEARED`);
  not reachable, or a Restore that does not finish, the record is marked pending (`PENDING`) and its Restore runs at that
  board's next connection (`lora cleanup kind=meshcore done=… contacts=…`), clearing the row only when it finishes. Nothing
  in the app calls it yet but the debug bridge; Stage 7's screen will, then clear the binding.
- **The door.** The MeshCore plane is fed `MeshManager.meshcoreBoardMap()` (`meshcoreBoardHolders`, which adds no start
  value: the first map is the stores' real combined state, never a placeholder), and `bindMeshcoreDirect` is bound in
  `di/MeshModule` before anything starts the manager, only where the MeshCore child is built. **Stage 5b-1's accepted leak is
  live from here**: an audience-only mint seals only the peers whose answer changed (the maintainer, 2026-11-13); nothing
  adds cover.
- **Never offered what cannot go** (lora N2): `BoardKindHooks.offered` lets a kind say which holders the core may offer; on
  MeshCore one whose key the taught set lacks (cut at capacity, over the daily cap, contested) reads `direct` false, and
  `PlaneCore.holdersMoved` re-asks when the capacity a Ready reports moves the set.
- **Our board's ack priced direct where it is** (5a-2's worst case narrowed): a live direct DM from a sender whose contact is
  taught and pinned zero-hop on this connection (`MeshcoreBoardOps.pinnedZeroHop`) books only the path's multi-ack and ack;
  anyone else's the larger as before. The prefix only prices air: a forged one whose MAC fails draws no ack at all, and one
  whose MAC passes is answered on that very record.
- **The bridge** (`…debug.LORA --es kind meshcore --es prov setup|restore|unbind`): a setup is refused without
  `--ez erase true` and answers the erase's counts; the dump carries the contacts' and the reconcile's counters.

Proposed defaults: a record written for every finished setup; a failed setup's leftovers kept pending; the unbind leaves the
binding for its caller. Owed: Stage 7's unbind and consent UI; the first two-MeshCore-node lab DM scenario (5c's
`MeshLab.node(board = MESHCORE)`); 5d's `meshcoreSig`.

Review of Stage 5b-2's second part (security- and lora-reviewer, 2026-11-13), applied as one design: **the setup record,
for that address and that board's key and not pending, is the single source of consent**, and the session gate, the offers,
the profile's claim, Restore and the cleanup all read it.

- **The record names its board** (security S1): `meshcore_setup.boardDigest`, the first bytes of the SHA-256 of the board's
  own key (`MeshcoreHooks.digest`, the law ring's key), amended into v37 in place (unshipped). Consent counts only for the
  board the digest names; the session's answer is per (address, digest) and **three-state** — yes, no, or unknown, which is
  any address with no answer or an answer for another board. `MeshcoreSession.restore(address, digest, record)` refuses
  (`MeshcoreRestoreResult.Mismatch`, `restoreMismatches`) unless the live connection is that address and that key, writing
  nothing, so a replacement, reflashed or other-install board at the same address never gets another board's prior name,
  Restore or cleanup (a spoofer is stopped by the bond, below, not the digest: a board's key is in its own adverts); a record for another board is no consent, stays as it was and is counted
  (`consentMismatches`). The setup result carries the board it ran on (`MeshcoreSetupResult.Done` / `Failed`'s `address` and
  `digest`), and the hooks' `readyBoard` is cleared the moment the board is down.
- **The claim follows the record** (security S2): the board's key is published (`MeshcoreClaimStore.setBoardKey`) only once a
  matching record consents — written by a setup or read back at a connection — and withdrawn (null) for a board never set
  up, by a finished Restore and by an unbind; the claim watcher then mints the unclaimed version under 5b-1's fan-out rules,
  unchanged.
- **A second setup never forgets the first** (lora L1): the record a setup writes is merged into the one kept for the same
  board (the slot written if any run wrote it; each prior the earliest known), and a failed setup's leftovers are merged
  and written pending in one write (N1).
- **No refusal before the record is read** (lora L2, N3): while consent is unknown the session answers a compact DM `Busy`
  (the core requeues it) and a teach `NOT_READY`, never `NO_RECORD`, so no peer is held keyless for the re-probe's four hours;
  the offers are closed (`direct` false) until consent is yes, and every consent move calls `PlaneCore.holdersMoved`.
- **Consent only once durable** (security N2): a setup in the session no longer consents; the hooks grant it after the
  record is written.
- **Follow-up for Stage 7** (security): a backup restore onto the same phone empties `meshcore_setup`, so a pending cleanup
  is lost and the board keeps Knit's name and taught contacts; the restore UI should say so.

**The maintainer's decisions, 2026-11-13, after the review stop rule** (`.agents/rules/review-rounds.md` §5: the consent
rework drew a new Blocking finding in a second round running — setup's and Restore's mid-transaction Ready launched an async
record read in the hooks that could land after the transaction's own grant):

1. **Consent has one owner: the session's actor**, as the plane core owns its own state. The session holds the setup journal
   (`MeshcoreSession(records = …)`) and reads consent for (dialled address, board-key digest) inside its handshake, before
   it publishes Ready, so every Ready carries a known yes or no; the three-state "unknown" and its `Busy` are gone. Setup,
   Restore and unbind write or clear the record and then set consent inside their own actor operation (durable write first);
   a failed setup's merged leftovers are written there, pending, in one write; a setup supersedes a cleanup still owed. A
   Ready published inside a transaction never re-reads. The pending cleanup runs on the actor as the connection's first job,
   only for a record whose digest names this board. The hooks never read or write the record: they observe
   `MeshcoreBoardOps.consent` (a `StateFlow` of `MeshcoreConsent`, set by the actor in its order) and derive the taught set,
   the offers (`holdersMoved`) and the profile's claim (the key published while yes, withdrawn otherwise) from it, the latest
   winning. A record read that keeps failing in the handshake (three tries, 200 ms apart) publishes Ready with no consent and
   a counter (`consentReadFailures`) — the owner's choice over ending the session, so room posts and the drain never wait on
   SQLCipher; the next connection reads again.
2. **A bond is required**, as belt and braces (the next decision is what stops a spoofer). Consent is granted, and Restore
   and the pending cleanup run, only when the dialer reports the address `BONDED`; an unbonded board is inert for Knit
   (`unbondedInert`). The digest stops a replaced or reflashed board. *(Corrected by the decision below: the bond is the
   phone's bond table for the address, not proof the connected peer holds it — a clone asking for no encryption passes it.)*

**The maintainer's decision, 2026-11-13: a signed challenge.** The full review of the session-owned design (security B1 and
B2, lora's B1) found the bond check is the phone's bond table for the address, not proof the connected peer holds it, and an
unbind with a second writer:

- **Proof of possession.** Before the handshake may grant consent, and again before a Restore, a pending cleanup or a setup
  writes, the board signs `knit-meshcore-consent/v1` ‖ a fresh 32-byte `SecureRandom` nonce through `CMD_SIGN_START` (33),
  `CMD_SIGN_DATA` (34) and `CMD_SIGN_FINISH` (35) → `RESP_SIGN_START` (19) / `RESP_SIGNATURE` (20)
  (`companion-v1.18.0` `MyMesh.cpp:1781-1815`; in the companion since 2025-03-15, long before `FIRMWARE_VER_CODE` 11 of
  2026-04-15, so at every supported firmware), and the phone verifies it as RFC 8032 Ed25519 (Tink's strict verifier;
  the firmware's `ed25519_sign`, `Identity.cpp:151`) under `SELF_INFO`'s key over exactly those bytes. The tag is distinct
  from 5d's planned `knit-meshcore-bind/v1`. A board that fails or cannot sign is inert for Knit (`keyUnproven`). The
  board's sign buffer is one for every client, so another's `SIGN_START` in between only fails a proof, never passes one.
  The digest stops a replaced or reflashed board, the signed challenge stops a spoofer, the bond is belt and braces. The
  simulator confirms the v1.18 firmware's `CMD_SIGN` output verifies as standard RFC 8032 Ed25519 under the `SELF_INFO` key
  over exactly the bytes sent, for a one-write and a two-write message, and refuses a flipped byte
  (`~/source/meshcore-spike/FINDINGS.md` Q16, 2026-11-14); a check on real hardware is still owed.
- **One writer** (security B1). The actor is the record's only writer while the board is up: one session `Mutex`
  (`consentLock`) covers a handshake's consent read through its Ready, and an unbind's choice — Ready on that address, then
  the actor runs it, waiting with no start deadline however long it is busy (a setup erasing many contacts), else the record
  is marked pending and a withdrawn consent (no key) published at once, so the hooks drop the claim and the offers.
- **Setup is refused before any write** on a board that is not bonded or does not prove its key (security N1).
- **A failed record read** keeps the claim as the hooks last had it and closes the offers (`MeshcoreConsent.unread`), and is
  read again on the actor as an idle job with a backoff (10 s up to 10 min), never once per connection (N2). A Restore or an
  unbind whose read fails is no answer (`failed: record`, `PENDING`), never "no record" (N4). The hooks' consent collector
  survives a throw (`consentApplyFailures`, N3).
- **Follow-ups (security):** `MeshcoreClaimStore.setBoardKey` withdraws nothing once the configured address is null, so
  Stage 7's unbind must withdraw the key before clearing the binding; `restoreSetup` re-reads `selfOf(c)` without checking
  the digest again (a key imported mid-connection by another client).

**The maintainer's decision, 2026-11-14: withdraw only the bound board** (the review stop rule again: lora-reviewer's new
Blocking finding). Consent is one `StateFlow` and one hooks field, so an unbind of address A while board B was Ready on
another address published a withdraw that closed B's offers and emptied its taught set until B's next handshake. Now an
unbind publishes a withdraw only for the board this plane is bound to (`MeshcoreSession.boundAddress`, the address it was
last started on, kept across a stop), Ready or not; for any other address it only marks the record pending — that address
cannot hold the claim, since `bindingAddress` drops an address that is not the configured one. As a belt, the hooks ignore a
consent whose address is not the plane's configured one.

Applied with it (security F1-F3, lora N1-N3):

- **The key published is the key proven** (F1): the proof keeps its key on the connection (`provenKey`), the consent
  publishes only it, and a setup whose `SELF_INFO` read after the proof reports another key is refused, nothing written.
- **The refusals are on the dump** (F2): `meshcoreKeyUnproven`, `meshcoreProofUnanswered`, `meshcoreUnbondedInert`,
  `meshcoreConsentMismatches`, `meshcoreConsentReadFailures`, `meshcoreRecordReadFailures`, `meshcoreRecordWriteFailures`,
  `meshcoreRestoreMismatches`, `meshcoreConsentApplyFailures`.
- **An unbind's op checks the address again** (F3), so a retarget in the window falls through to the away path.
- **An unanswered proof is not a clone** (lora N1): no answer, `BAD_STATE` or no such command goes down the unread backoff
  (`proofUnanswered`); only a signature that does not verify makes the board inert (`keyUnproven`).
- **Record reads and writes are bounded** (lora N2): two seconds each on the actor, a timeout counting as a failure.
- **A mark that did not land is said so** (lora N3): an unbind whose pending mark failed answers `MeshcoreUnbind.FAILED`, so
  the caller keeps the binding and asks again. Known window: an adapter that goes off in the middle of a setup's record write
  leaves whatever the journal kept (the write is one statement: the whole record, or none).

Residuals, recorded (security):

- The signed challenge stops a spoofer short of one holding the board's pairing: a live relay paired with the real board can
  pass it, and such a party already controls the board. Like #274, the proof is not bound to the connection.
- A setup can replace another board's record at the same address (trust on first use); a "different board" refusal or prompt
  is a Stage 7 item.
- For 5d: a bind statement the board signs proves only that some client holding the board's PIN asked for it.

Follow-ups of the bound-board round (lora L1-L3, security S1-S3, 2026-11-14), applied:

- **An empty set for want of consent is no taught set** (L1): the hooks hand it over marked `withdrawn`, which leaves the
  session's taught set unknown, so a reconcile owed by a later consent waits for the hooks' real set and never lists against
  nothing.
- **A write that timed out is read back once** (L2): a timeout cancels the wait, not a statement already running, so the row
  is checked before a write counts as failed; each failure is counted once.
- **An unanswered proof is no answer everywhere** (L3): a Restore answers `failed: proof`, a cleanup is asked again on the
  backoff; `Mismatch` and `keyUnproven` are for a signature that does not verify.
- **A key that changes on the connection ends the session** (S2): a `SELF_INFO` read mid-connection with another key than
  the one the connection identified with (`keyChanged`) is a new board at the next connection; the record a setup writes
  names the proven key's digest.
- **A non-verifying signature keeps the claim** (S3) when the record names this board: no consent, the offers closed, the
  proof asked again on the backoff (another client's `SIGN_DATA` in the shared buffer fails a genuine board's proof); only
  no record, a digest mismatch, a Restore or an unbind withdraws the claim. A clone still never gets consent.
- Tests: a board that never answers the proof (S1), the reconcile after an unanswered proof (L1), a key changed on the
  connection (S2).

The unit-test review's round (2026-11-14), applied: a finished Restore or unbind clears an earlier lost proof's unread state,
so its withdrawal reaches the claim at once; a consent publishes the proven key only while it consents; a signature that
fails is followed by one `SELF_INFO` read, so a board re-keyed mid-connection ends the session (lora L). The interleaving
tests now force their orders (a handshake held in its consent read under the lock, a setup queued behind a held backoff
re-read, the consent ordered before its Ready), and the mutants that survived the round before — no supersede in
`keepRecord`, no `consentLock` around the consent read and its Ready, the Ready published before its consent — each turn a
test red. The setup store has a real-SQL test (`MeshcoreSetupStoreTest`).

**The maintainer's decision, 2026-11-14: one failure rule for record writes** (the review stop rule a third time: a Restore
whose record clear failed answered success, so an unbind read `CLEARED` with nothing marked, and the next connection granted
consent again and republished the withdrawn key). Every record write goes through one place in `MeshcoreSession` whose
failure handling is fixed by what the write was for; no call site maps a result to consent by hand:

- **A write that removes or withdraws consent** (Restore's clear, an unbind's clear or pending mark, the cleanup's clear, a
  failed setup's leftovers; `withdrawWrite`): consent is no on this connection whatever happens (one exception, the 5b-2 tail's `keepConsent`), published at once so the
  claim is withdrawn; a write that does not land, after `recordWrite`'s read-back, marks the record pending so the cleanup
  Restore runs at the next connection; only a mark that also fails is `FAILED`. It is never reported as success — a Restore
  answers `failed: record`, an unbind `PENDING`.
- **A write that grants consent** (a finished setup's record; `grantWrite`): consent yes only once it landed; one that did
  not leaves consent no and the record as it was — which, since the write-ahead below, is the setup's own pending record —
  and the setup reports `Failed`.

Tests, one per purpose: a withdraw write that fails (Restore `failed: record`, the record pending, an unbind `PENDING`, no
grant and no key after a reconnect), a grant write that fails, a withdraw write and its mark both failing (`FAILED`).

**Write the intent before acting** (the security enumeration pass, 2026-11-14: one Blocking and four Non-blocking findings
with one cause — the record was written after the board writes, so an interruption between them lost the intent):

- **An unbind** marks the record pending (`withdrawWrite`, consent no and the claim withdrawn at once) before any board
  write, then runs Restore, then clears the record: `CLEARED` only when the clear lands, `PENDING` when a mark landed,
  `FAILED` when none did. A process death mid-Restore leaves a pending cleanup, never a consent.
- **A Restore is a withdrawal** (the coordinator's decision, consistent with a finished Restore clearing the record): the
  same mark comes first, so a Restore that fails partway is a pending cleanup with consent no. A proof that does not verify
  in a Restore is `failed: proof`, as `readConsent` reads it (maybe another client's sign buffer); `Mismatch` is for another
  board's record or an unbonded board.
- **A setup** writes its planned record, merged, as pending before the erase or any write; the grant is the flip to
  not-pending. A setup cut off anywhere (a failed grant, a session end, a process death) leaves that pending record, and the
  next connection's cleanup puts the board back. A setup that leaves nothing of its own (an erase that failed, a write put
  back whole) puts the record back as it was before.
- **A pending mark of no row is no mark**: `MeshcoreSetupJournal.markPending` answers whether a row was there.
- **A withdrawal is retried and goes first**: the hooks edit the claim before anything else on a withdrawal and retry an edit
  that throws, with a backoff, until it lands or a newer one replaces it; an unbind with no record still withdraws for the
  bound board; at start the session withdraws the bound board when its record is absent or pending, without waiting for the
  board; the revoke comes before the write.
- **An unbind's writer** is chosen on a live inbox too, and an adapter-off session reads down only after its actor stopped.
- **Owed by Stage 7:** the unbind's withdrawal must land before `meshcore_device_address` is cleared
  (`setBoardKey(null, dialled)` is a no-op afterwards, and the hooks' claim retry counts that skip as landed), or the pubkey
  is cleared in the same edit.
- **Cost, by design:** a re-run setup of a board without consent withdraws and republishes the claim (its write-ahead
  revokes), so two sealed-profile mints under 5b-1's fan-out. A re-setup of a consented board does not (the 5b-2 tail's
  `keepConsent` exception, below).

The 5b-2 tail (non-blocking follow-ups after the part 2 commit, 2026-11-14), applied:

- **A re-setup of a consented board keeps its consent** through its write-ahead (`withdrawWrite(keepConsent)`, the rule's
  one exception, only when this connection consents for a non-pending record of this board), so the hooks never empty the
  taught set and the erase keeps Knit's taught records; the record is pending on disk all the same. A setup that leaves
  nothing of its own puts a granted prior of this board back as a grant (`grantWrite`), and a rollback that put nothing
  back but changed nothing restores the prior record instead of leaving the planned one pending. A kept consent never outlives a setup that did not finish (the tail's review):
  a write-ahead that does not land revokes it, and a setup cut off after its write-ahead — a session end, a throw, a
  cancellation — revokes it on the way out (`runSetup`'s `finally`, on `Conn.setupOpen`), leaving its record pending for the
  next connection's cleanup; a process death is withdrawn at start (`withdrawAtStart`). Consent is kept from the
  write-ahead to the grant — over the erase, the step writes and a rollback's put-back — on the board the user already
  consented to, and nowhere else: a cancellation inside the write-ahead or the put-back's grant revokes it too (the Stage
  5c-1 amendment's first item).
- **Claim edits are serialised** (`claimMutex`), each checked inside it — its retry's one check — so a stale retry never
  lands after a newer edit. The rollback's `!changedAnything` branch is a belt: every needed setup step carries its own
  prior, so a rollback that changed nothing is unreachable today (no test can reach it).
- **A start's withdrawal is for the board still bound**: re-checked against `boundAddress` under `consentLock`.
- Tests: the write-ahead before the erase (a cut at the first erasure leaves a pending record), a re-setup keeping its
  consent and taught contacts, a stale grant retry, a start of another board; the cut-off tests assert the setup's writes
  landed before the cut, and the mid-Restore test drops its unbind with the actor.

## Amendment 2026-11-14 — Stage 5c-1: two-part room posts, and a reassembler a stranger on the key cannot poison (draft)

Built under the Stage 5 design passes' 5c part (the security pass's B2 and `IdElision` items, lora-reviewer finding 5),
the maintainer's decision 7 above, and the 5b-2 tail reviews' two carried items. Debug builds only (`MESHCORE_PLANE`).
The MeshLab MeshCore node and its two-node scenarios are Stage 5c-2's.

- **The two cancellation windows closed first.** A consented re-setup's kept consent could stand over a pending record in
  two places: `restorePrior` cleared `Conn.setupOpen` before its put-back's grant, so a cancellation inside that write
  skipped `runSetup`'s revoke; and a cancellation inside the write-ahead's `withdrawWrite(keepConsent = true)` came before
  `setupOpen` was set and outside the `try`. Now the write-ahead runs inside the `try` with `setupOpen = consented` set
  before it, and `restorePrior` clears `setupOpen` only once its write returned (a failed grant revokes on its own, a
  withdraw write first). Tests cancel the actor inside each write and assert the last consent is not granted over the
  pending record; each fails against the old order.
- **Own room posts in two elided packets** (§4): `MeshcoreTraits.roomPartLimit` is `ROOM_PART_LIMIT` (2), the staged 1
  gone; `BoardTraits.elidesRoom` (MeshCore true, Meshtastic false) has `LoraFrameCodec.encodeBest(elide = true)` take
  `FastFrameCodec.encodeElided` (the sender a prefix) whenever it reproduces, else the transcoder's pick
  (`lora elide refused`). Each part is booked under the kind's room ceiling, `Ceiling.FLOOD`. Measured with random
  printable ASCII at 164 B a packet (2026-11-14): **57 B of text in one packet, 213 B in two**; the composer's budget
  (`LoraSizeHint.MESHCORE_ROOM_BODY_BYTES`) is 200 B. Meshtastic stays at one packet and never emits or reads `0x06`.
- **`fragSeq` is seeded at random per process**, on both kinds (not a wire change; Meshtastic's room never fragments).
- **The reassembler** (`LoraFragCandidates`, a pure policy; Meshtastic keeps its sender-keyed `FragReassembler`). On a kind
  that elides, every fragment set and every whole `0x06` frame is read only once a signature verifies against a pinned
  author (`LoraAuthorCheck`); a whole `0x03`/`0x05` packet goes to the router as in 5a-2.
  - Entries keyed on `(fragId, count)`: another count is another entry, never a reset.
  - Up to three distinct candidates a part, in arrival order; **a fourth is refused and counted, never kept over one** —
    the owner's choice over evicting: the honest parts leave back to back, so a forger who learns the id from part 0 fits a
    packet or two in before the honest part 1, and an evicting store would let them push it out afterwards.
  - Every arrival answers only the combinations it completes; the first that verifies is settled, and only its candidates
    leave the entry, so two authors under one colliding id both complete. (Superseded by the review round below: a part 1
    is now kept only beside the part 0 that committed to it, and an entry tries at most 24 checks.)
  - A `0x04` part naming more than `roomPartLimit` parts, or none that parses, is refused at `route`
    (`meshcoreFragmentsRefused`, the 5a-2 counter's new meaning), so no entry on the public key costs 27 tries.
  - **Capacity by airtime**: the 60-s window over the shortest fragment's airtime at the board's preset now, plus one,
    between 64 and 2048 (`capacityFor`; 177 at the companion's default SF8 / 62.5 kHz, its shortest fragment 341 ms), so one radio cannot flush an
    honest entry inside the window. Two radios can: the cost accepted until the author tag below.
  - Each drop is its own `FastPathDrop`: `FRAG_TIMEOUT`, `FRAG_OVERFLOW`, `FRAG_CANDIDATES_FULL`, `FRAG_UNVERIFIED`,
    `ELIDED_UNVERIFIED` (the last three new, on the MeshCore dump as `meshcoreFrag*` / `meshcoreElidedUnverified`).
  - The check suspends the packet collector (a DB read and an Ed25519 verify, at most 8 an arrival): ordering stays one
    collector's, and LoRa packets arrive seconds apart.
- **Resolution, contacts first** (the security pass's `IdElision` item): a prefix resolves against `LoraAuthorIds`, the
  direct contacts (`DirectContacts`) first and every other peer whose key the table holds only on a miss — **a miss read
  as "no contact's candidate verified"**, the owner's reading, which also delivers a non-contact author whose prefix a
  contact happens to share. Four ground identities sharing a contact's prefix therefore cannot censor the contact unless
  they are contacts. A stranger's prefix resolves to nobody, and their post is dropped. Node ids only, never a key or a name.
- **The author tag** a fragment header would need to make the candidates unnecessary is parked as `docs/NEXT_WIRE_BREAK.md`
  item 16, to agree with iOS before MeshCore reaches iOS.
- **`maxBytes` is required on `LoraDirectSource.unackedTo`** (the Stage 1a.4 carry-forward): the four-argument form is the
  one every source implements; the three-argument form is a convenience at Meshtastic's budget, which the core never calls.
- **Tests:** `LoraFragCandidatesTest` (a forged part, a flush past the old capacity, colliding ids, the bound on tries, the sweep, the
  bounds, the two tiers), `MeshcoreRoomPostTest` (the plane end to end: two elided parts under `FLOOD`, a third too long, a
  forged part with an observed id, a fresh-id flush, two authors under one id, a stranger, a pinned non-contact, a stale
  replay that is no hearing), `BoardKindFramePolicyTest` (jend's invariant: every frame type flood, zero-hop or refused on
  each kind), `CoordinationPlaneSizeBudgetTest`'s MeshCore column (the room in one and two elided packets, the DM at 156 B,
  157 after COBS), and `MeshtasticHooksTest`'s two deferred cases.

**Cost, by design:** a hearing of a two-part post reads the last part's packet (its path length and liveness); a part
heard through a repeater beside a zero-hop last part still counts. A stranger can make each entry try up to 24 verifications (the review round below),
and two radios can still flush the store inside its window.

Owed: Stage 5c-2's MeshLab MeshCore node and its scenarios (the security pass's (a)–(f), the lab follow-ups); the device
trial — two MeshCore boards in range, a 150-200 B room post from one phone: two parts on the air, `lora tx … parts=2` and
`lora rx chat` on the other, `meshcoreFragUnverified` 0.

### The review round (security- and lora-reviewer, 2026-11-14), applied

The maintainer decided the censorship finding on 2026-11-14: **bind part 1 to part 0.** What changed against the text above:

- **B1 — the bound form, `0x07`.** Refusing a fourth part-1 let a stranger censor every two-part post: hearing part 0 they
  learn the id, and three ~21-B junk part-1s (≈ 0.24 s each at SF8 / 62.5) fit while the ≈ 1 s honest part 1 is still on the
  air, which then arrives fourth. Now part 0 carries `COMMITMENT_BYTES` = 8 bytes of SHA-256 over part 1's slice
  (`FastFrameCodec.fragmentBound`): eight because a forger would need a 64-bit second preimage within the second before part
  1 airs, and six (2^48) is a few GPU-hours offline for two bytes of text. A part 1 is kept only beside the part 0 that
  committed to it; junk is dropped unread (`FRAG_UNBOUND`) and takes no slot. Only before any part 0 has arrived is a part 1
  held unbound (three at most), for a flood that reordered the two. Part 0 kept refuse-the-fourth, on the belief that the
  honest part 0 is always held because a forger learns the id from it — **wrong behind a repeater, and superseded by the
  second round below**. **A distinct tag rather than a flag in `0x04`**: every shipped reader drops an unknown tag as `UNKNOWN_TAG`,
  while a flagged `0x04` would reach `parseFragment`, which reads every byte past the header as slice. Additive because only
  the MeshCore plane reads or writes it and that plane has no fielded readers (debug-only); recorded in
  `docs/WIRE_COMPAT.md`, vectors `lora-frag-v1.json` (`LoraFragVectorTest`; no other vector moved). **A MeshCore plane reads
  no unbound `0x04` at all** (`meshcoreFragmentsRefused`): no MeshCore phone sends one, and it is the form a forger can
  crowd. To agree with iOS before MeshCore reaches iOS (paired later). `docs/NEXT_WIRE_BREAK.md` item 16 now says an author
  tag alone does not close this race (a forger copies it from the part 0 it heard); the commitment does. **Room budget:**
  part 0 holds 152 B of the frame, so two packets hold **205 B** of noise (57 B in one); `MESHCORE_ROOM_BODY_BYTES` is 192.
- **B2 — a fresh id per frame.** Seeded once and then counted, one observed post predicted the author's next ids, so a
  forger could pre-fill their part-0 slots, or open an entry early for the sweep to age out. On a kind that elides each
  fragmented frame now draws its id from `SecureRandom` (`LoraMeshTransport.fragIds`, injectable for the tests); Meshtastic
  keeps its seeded counter. And every candidate ages out on its own arrival, never its entry's first, so a junk part that
  opened an entry early leaves alone.
- **N1 — presence verified.** On a kind that vouches for no sender, `onFramePacket` notes `reachable` only for a frame whose
  signature verifies; a whole `0x03`/`0x05`, read unverified, is checked for it.
- **N2 — a fresh replay (recorded, not fixed).** A post captured and re-aired at `path_len 0` within `FRESH_MS` still verifies
  and is fresh, so it is a hearing of its author's radio and counted under the author in relay suppression. Recorded against
  #283, whose fix must cover every LoRa kind before MeshCore ships in release (the 5a-2 release gate).
- **N3 — the authors stay across a board change.** `quiesce` no longer clears `LoraAuthorIds`: they are the phone's, and
  the collector restarts only on a configured edge a conflated `StateFlow` can skip.
- **N4 — the budget follows the band.** `AirtimeSnapshot.roomPacketsFit` is how many full packets fit the room ceiling on an
  empty window (the `NEVER` test, at full size, so it errs small; null before the duty is known); `LoraFacts.roomBodyBytes`
  reads 192, 50 or 0 from it on MeshCore. Meshtastic's never moves.
- **N5 — the checks, corrected.** Not "nine an entry": up to three pairings, each up to `IdElision.MAX_CANDIDATES` × 2 tiers =
  8 resolutions, so at most **8 checks an arrival and 24 an entry**, each a `PeerRepository.find` and an Ed25519 verify.
  `meshcoreZeroHopKnitPosts` counts packets: a two-part post counts 2.
- **N6 — the Koin graph.** `KoinGraphVerifyTest` had failed since 5b-2's second part: `MeshcoreSetupStore`'s DAO is now its
  own definition, as every other DAO is.

The "Cost, by design" above stands with "nine verifications" read as 24.

### The second review round (lora- and security-reviewer, 2026-11-14): bind both ways

Both reviewers found one new Blocking hole: the one-way form assumed the honest part 0 reaches a receiver first. Behind a
MeshCore repeater it usually does not: the repeater holds a packet a random `nextInt(0, 5·t + 1)` before re-airing it, `t`
its airtime × `tx_delay_factor` (0.5 by default, up to 2.0; `examples/simple_repeater/MyMesh.cpp:547`, `:893`,
`CommonCLI.cpp:109`), so 0–2.5 s for a 1-s part 0, while a forger in range of the author airs a 13-B part 0 of about 0.25 s
under the observed id. One fake copying the commitment then took the honest part 1's pairing (and a second copy of part 1
is stopped by the board's `hasSeen`); three filled the slots. **The maintainer's decision, 2026-11-14: bind both ways.**

- **The form, changed in place** (unshipped, debug-only): part 0 = `07|fragId|02|H(slice1)[8]|slice0`, part 1 =
  `07|fragId|12|H(slice0)[8]|slice1`, each H the first 8 bytes of SHA-256 over the other part's slice. A head and a tail
  pair only when both hashes match, so each part 1 names exactly one part 0 and one verification runs a matched pair. A
  fake head cannot match the honest tail's `H(slice0)`, nor a fake tail the honest head's `H(slice1)` (64-bit second
  preimages). Each part now holds 152 B of the frame. `vectors/lora-frag-v1.json` regenerated (`commitmentBack` added).
- **Fakes cost memory, never a check.** Each side of an entry (heads, and tails waiting for their head) is capped
  (`LoraFragCandidates.perPartFor`; sized first by one repeater's hold, then by the whole window — the third round below).
  Past the cap a newcomer replaces a **random** waiting part (`SecureRandom`, injectable), counted
  `FRAG_CANDIDATES_FULL`, so a flood cannot aim at the honest slot. A pairing leaves the entry whether it verifies or not,
  so nothing a forger pairs lingers. The store also holds at most `MAX_BYTES` = 256 KiB of slices (one radio fills about
  8.5 KB a minute), evicting the oldest entry past it, beside `capacityFor`'s entry cap (177 at the default).
  `FRAG_UNBOUND` is gone: a part that names nobody yet may be the honest one reordered, so it waits.
- **Budget:** 197 B of noise in two packets (57 in one); `MESHCORE_ROOM_BODY_BYTES` is **184**.
- **Never padded** (the round's security follow-up): a frame that does not elide keeps the transcoder's pick, which may be
  deflated, and `LoraFrameCodec`'s pad would grow the last bound part after both commitments were made. Bound parts are
  never padded. A MeshCore board signs nothing, so the pad never paid there anyway.
- **Doc drift fixed:** the `authors` KDoc (kept across a board change) and `verifiedWhole`'s figure.
- **Tests, each against the hole:** a fake part 0 copying the commitment ahead of the honest one; three distinct fake
  part-0s first; more fakes than one radio airs in the window with a pinned replacement source (the cap checked against the
  formula); a reordered honest part 1 beside fake tails; a frame that does not elide, never padded.

### The third review round (2026-11-14): a side holds the whole window's input

Both reviewers, 0 Blocking. The round-2 cap — what one radio airs inside one repeater's longest hold, (5 × 2.0 + 1) ×
the longest bound packet's airtime ÷ the shortest's + 1 = 38 — covered one hop at `tx_delay_factor` 2.0. The firmware can
hold a flood far longer: the score delay `calcRxDelay`, `(rx_delay_base^(0.85 − score) − 1) × air`, with `rx_delay_base`
settable to 20 (an old persisted default of 10) and capped at 32 s (`MyMesh.cpp:542-545`, `Dispatcher.cpp:11, 243-253`,
`CommonCLI.cpp:108`); CAD retries under a busy channel or the 50 % tx budget (`Dispatcher.cpp:281-302`); and up to
(5·f + 1) airtimes for every further hop. So a forger who saturated our receiver for 13–20 s a post won a 1-in-38 lottery
a fake. **The coordinator's decision (security's fix):** each side keeps what our one receiver can decode within the
whole `FRAG_TIMEOUT_MS` at the shortest bound part's airtime, + 1 — `capacityFor`'s figure, **177** at SF8 / 62.5 kHz
(60 s ÷ 341 ms). The board decodes one packet at a time, so within the window no radio input, from one forger or many,
fills a side and no replacement happens at all; the random replacement stays as the backstop past it, and memory stays
bounded by `MAX_BYTES` and the receive rate. `REPEAT_DELAY_AIRTIMES` is gone.


## Amendment 2026-11-14 — Stage 5c-2a: a MeshCore fake the field would agree with, and the physics it proves (draft)

Built under the Stage 5c-2 design pass (lora-reviewer's six Blocking findings, mesh-lab-reviewer's thread-safety, hold and
draw-seed items). Test code, one production finding fixed (below). The MeshLab MeshCore node and its scenarios are 5c-2b's.

- **The fake's channel, opt-in** (`FakeMeshcoreAir(channel = MeshcoreChannel(…))`). Without one every Stage 3b behaviour
  `MeshcoreFakeVsSimTest` pins stands unchanged. With one, each behaviour is modelled from the `companion-v1.18.0` source:
  - **airtime**: the Semtech formula written in the fake (never `LoraAirtime`), the firmware's preamble (32 symbols at
    SF ≤ 8, `RadioLibWrappers.h:56`): 633 ms for 181 B at SF7 / 62.5, Q5's measured 633 ± 17. Delivery at the airing's end;
    one reception at a time, half duplex. **An overlap corrupts both packets** and the radio stays busy to the later end,
    which its CAD sees: radio behaviour the source is silent on, after the capture literature (an equal-power, same-SF
    overlap usually kills the first too); a per-link `capture` alone keeps the first (round 1's Blocking finding);
  - **the radio**: one packet at a time from `PacketQueue`'s order (soonest eligible, lowest priority, first in), the 50 %
    budget over an hour, listen-before-talk (200-ms CAD retries, forced after 4 s; `Dispatcher.cpp:57-62, 270-302`);
  - **the repeater**: pool 32 (`simple_repeater/MyMesh.cpp:864`), a flood re-aired `nextInt(0, 5·t + 1)` after the end of
    reception, `t` = airtime(path + payload + 2) × `tx_delay_factor` 0.5, priority by hop count (`Mesh.cpp:344-356`); a direct
    packet whose next hop it is forwarded after the `direct_tx_delay_factor` 0.3 draw, itself stripped (`Mesh.cpp:78-108`,
    `MyMesh.cpp:547-553`); `rx_delay_base` settable, under 50 ms ignored, 32 s cap (`Dispatcher.cpp:240-252`). Each draw is
    `Random(mix(runSeed, repeater, origin, n))`, `n` the origin's nth packet of the route kind, logged;
  - **dedup**: every node, repeaters included, a 160-entry ring of SHA-256(type + payload), path excluded
    (`SimpleMeshTables.h:9`, `Packet.cpp:41-50`), cleared on reboot;
  - **paths**: per-contact `out_path`; a direct packet followed along it and dropped by every node that is not the next hop,
    the destination included; through a repeater it lands reported `0xFF` (`MyMesh.cpp:453`); an ack offered to whoever
    hears it on its way (`Mesh.cpp:79-87`); a PATH rewrites its receiver's record and pushes `0x81` (`BaseChatMesh.cpp:353-359`),
    a flooded one answered by a reciprocal direct PATH (`Mesh.cpp:178-184`); a flooded matched ack draws one direct PATH 3 s
    later while the sender's record is pinned (`:372-390`);
  - **boards**: `powered`, a `reboot()` that clears the offline queue, ack table and ring and keeps contacts, the offline cap
    at the source's 16 (Q6), an RTC that ticks with the air's clock, `tx_air` in ms for every airing, `lossy(from, to)`,
    `hold` / `release`, synchronous `listeners`, `pathHashMode`. `quickMeshcoreAir` is the Stage 3b air ÷ 10.
  - **one serial context**: given `serial`, every write, delivery and timer runs on it; `airings` is copy-on-write.
  - **Where it is not the firmware**: no capture unless stated, no SNR (a stated `score` alone), a packet a lossy link
    loses never reaches the receiver's CAD, no `TRACE`, adverts or multi-acks, no flood limit or region map. A scoped
    flood carries its 4 B of transport codes, and a forwarded direct packet's delay is priced before the strip
    (`Mesh.cpp:90`), both fixed in round 1.
- **Divergences from the simulator, named in `MeshcoreFakeVsSimTest`**: the Q13 PATH (the simulator aired none in either
  run; a board check is owed), the drift to one hop that PATH can carry (no simulator run reached it), and the airtime
  (the simulator's SX1262 takes a 16-symbol preamble at every SF, `SimRadio.h:50`, so Q15's parts sat 606 ms apart where
  the firmware's sit 633).
- **The production finding, fixed: the per-side cap sat under one radio's input.** `candidatesPerPart` and
  `candidateCapacity` priced the shortest bound part at the booked figure — 25 B (a scope's 4 B of transport codes) at the
  formula plus `FIXED_MS`, 341 ms at the companion default — so the cap was 177 while a back-to-back forger lands 197 of
  its 21-B, 304-ms parts in the window. Over the channel, a rogue radio for the window plus one airtime made 24
  replacements, each a 1-in-177 chance at the honest head. Now `BoardTraits.leastRoomAirMs` (MeshCore: an unscoped
  `GRP_DATA` at the formula, no `FIXED_MS`; Meshtastic: the unsigned broadcast at the formula, read by nothing yet) through
  `LoraAirtime.leastRoomAirMs` sizes both. The booking is unchanged. **Round 1's decision:** the floor is priced at CR 4/5
  whatever the board reports (the explicit header carries the rate) and behind the shortest preamble a receiver locks on,
  6 + 4.25 symbols — a stated assumption, not a bound: 21 B in 197 ms, a cap of **306** at the companion default. The
  full-rate case now runs its forger at that preamble and lands at least 304 parts in the window without a replacement. Whether a
  v1.18 receiver locks on a 6-symbol preamble is owed to a board.
- **Findings, and what became of each:**
  - **A DM through a repeater was a zero-hop hearing** — fixed in round 1. It lands `0xFF`, which
    `MeshcoreTraits.heardZeroHop` reads as direct (it trusts the pin), so an out-of-range peer whose DM a repeater forwarded
    was counted in `boardsHeard`. **Decision:** on a kind that vouches for no sender a DM never counts a radio
    (`noteAuthorRadio` is called for verified zero-hop room posts alone); it stays the unreachable-lift trigger
    (`onBoardHeard`), since the catch-up is gated by `reprobe.onHeard` and in the pinned case the hearing is true.
    Meshtastic is untouched. `MeshcoreHearingTest.aFreshOpenedDmAtZeroHopsHearsItsBoard` now expects 0 radios. **The
    accepted cost:** two MeshCore users who only DM each other read "0 other radios in range" (`LoraRadioScreen`), since
    `boardsHeardAt` is fed by verified zero-hop room posts alone; a DM could count once a zero-hop proof exists (Stage 5d
    or later).
  - **A silent drift to one hop cost a DM its flight** — shortened in round 1. Another client's write (or a PATH whose `0x81`
    the phone missed) leaves the record one hop through a repeater; with the repeater gone the attempt goes direct along
    the dead route and the in-range destination drops it. Before, all three attempts did, the peer was marked unreachable
    and `endFlight`'s `pins.unverify` re-pinned at the next send, some 15 minutes on. Now an attempt whose ack window
    passes sets `repinNeeded`, so the next attempt reads the record back, writes zero hops and lands: one retry gap and a
    BLE read, no air. Once per unanswered attempt (round 2): a retry held behind a full table or pool (`deferredSince`) is
    not re-pinned every tick, so a held flight costs one read, not one a second for the hold.
  - **A `0x81` inside a minute of the last pin job is coalesced** (`MeshcorePins.REPIN_GAP_MS`) — not a defect: the push
    still unverifies the key and marks every flight to it `repinNeeded`, so the next send re-pins. A Q13 PATH that left the
    record at zero hops draws a read-back and no write; one that left one hop draws the `ADD_UPDATE_CONTACT` at
    `out_path_len` 0.
- **Tests**: `FakeMeshcoreAirTest` (each modelled behaviour, the serial context under real threads), `MeshcoreAirPhysicsTest`
  over `MeshcoreAirRig` (the production stack per node): the cap's whole window at full rate (the memory bound only) and
  against a rogue forger idle between its own parts (measured, not tuned, per post: the first post's head starts the
  forger, so it says nothing about a rate; at one honest part's airtime plus a CAD retry of idle no post aired under the
  running schedule lands, part 1 running into the forger's next head; at two parts plus a CAD retry a post under the
  schedule lands, and every post whose part 0 took a gap at its start (within one CAD retry of a forger packet's end)
  lands, where one queued mid-gap on an idle channel airs at once and may not fit; at 50 % with one airtime idle none of
  three did), the overtake behind a held repeater (a copied-hash head; forty short
  heads), the replay once the ring forgets (it reaches the plane, `meshcoreZeroHopKnitPosts` moves, no hearing), the Q13
  PATH and its re-pin, the 1-hop drift, the drift's re-pin on the second attempt, a DM through a repeater counting no radio; `MeshcoreFlushSwitchTest` (a ✓✓
  chunked at the kind's floor airs whole on the board switched in under it: it pins that the budget does not depend on the
  board, and cannot show a per-board budget, since none varies yet); `MeshcoreFakeVsSimTest`'s channel replays.
  Security (c), a shared 6-byte prefix, was already covered in `MeshcoreContactsTest`.

Owed on boards (the design pass's Answer 4): the Q13 PATH (unpin the receiver, DM it, a third board listening: one direct
PATH about 3 s after the flooded ack); the drift to one hop and its re-pin; a stock repeater's delay spread at
`tx_delay_factor` 0.5 and 2.0; a real forger board airing junk (its gaps and collisions at R; a shorter-preamble forger);
the offline queue's real size and its loss on reboot; a 157-B DM through the ESP32 notify; `CMD_SIGN` on hardware (Q16);
`tx_air` against Knit's ledger over an hour.

## Amendment 2026-11-14 — Stage 5c-2b: a MeshCore node in the mesh lab, and what two nodes exchange (draft)

Built under the Stage 5c-2 design pass (mesh-lab-reviewer's findings, and lora-reviewer's two seam findings: no injected
pace, the production claim path). Test code, and one production finding fixed (below). Debug builds only.

- **The node.** `MeshLab.node(meshcore = air)`, a parameter beside `air`: a node given one never takes the boards sweep's
  Meshtastic air, so the `mesh-lab-boards` job runs `Meshcore*LabTest` with one LoRa plane a node. Its plane is the
  production child, as `di/MeshModule` and `di/LoraWiring` build it: the `MeshcoreSession` over the node's fake board, the
  hooks `meshcorePlane` builds, the transport at **its own pace** (MeshCore's `LoraAirtime`, gap 0 — the Meshtastic lab's
  injected `LoraPacePolicy` would have priced a cap production never computes), the board key reaching `MeshcoreClaimStore`
  through `bindingAddress` under the hooks' `claimMutex`, the setup records under SQLCipher (`MeshcoreSetupStore`), the
  pinned author check, the author ids, the plane state and `LoraDirectRouter` as production binds them,
  `bindMeshcoreDirect` before `start`. The first boot sets the board up through the real path (`provisionKnitChannel(Setup)`:
  the session's transaction, its record, its signed consent); `driveMeshcoreBoard` refuses a node with a board. Every board
  reports the preset the air uses (SF7 / 250 kHz, inside the 869.4–869.65 MHz 10 % sub-band).
- **Three clocks.** The session's `now` is monotonic (`System.nanoTime`, as production's `elapsedRealtime`), so a
  `lab.clock.advance` never ends its `ElapsedWait`s; the plane, the hooks and the session's `wallClock` are the node's
  `LabClock`, so a scenario can jump past the re-probe gap or the fragment window; the board's uptime is the lab's calendar,
  so the duty law's boot date (`wallClock − uptime`) stays put across a jump. The air runs on a lab-owned scope, one at a
  time on the chaos dispatcher; the fake's dials, writes and channel closes all hop onto it.
- **Readiness.** Boot returns once the session is Ready, its record consents, the claim store holds the key and the claim
  is minted (sealed-only: the cleartext profile does not move for it). `MeshLab.awaitTaught(a, b)` returns once b's key is a
  contact on a's board and a's session holds it taught and pinned (`MeshcoreSession.taughtAndPinned`); `awaitAcquainted`
  calls it for every MeshCore pair that is each other's direct contact (the board map's rule: a link makes no contact), and
  `MeshLab.meetOverMeshcore` makes a pair contacts over a link, heals, and parts them. A restart refuses a redial, closes the
  old NUS channel and joins the old session before the next dials, then waits for Ready and every contact taught again.
- **The production finding, fixed: a key taught mid-connection reached the board only at the next Ready.** The taught set
  was written by the Ready reconcile and, after it, only reactively, by our own send's `OWN_NO_KEY`. The firmware drops a
  DM from a sender it holds no contact for (`BaseChatMesh`'s contact lookup), so a new contact who wrote first was dropped,
  unanswered, until our next Ready or our own DM to them: the lab's first two-node DM went unreachable. Now
  `MeshcoreSession.setKnitContacts` queues a `TEACH` job for every key the set gained (`MeshcorePins.setTaught` answers them,
  `queueTeach`) while a connection is Ready and no reconcile is pending: a read of the key, a count read when the Ready took
  no listing, and at most one flash write a key, on a board set up for Knit only (the job is dropped elsewhere), no air. A
  Ready reconcile still writes whatever the set held when it runs. **Round 1 (lora-reviewer), applied:**
  - **B1, a write for a key the set no longer holds.** The hooks may drop a key (a block, a removal, a contest) while a
    teach's reads are out; the write then taught it back, so the removal they queued behind it skipped it and a blocked
    contact stood on the board, its DMs decrypted and acked, until the next Ready (ADR 2026-09.adgd). Every Knit-record write
    — the live teach, the reactive teach, the reconcile's — now asks the taught set right before the write and again after
    the read-back, never teaches a key back, and answers `NOT_FOUND` for one dropped meanwhile, which the queued removal
    then removes. A teach writes only a key the set holds.
  - **The live teach is outside §3's once-a-minute re-pin gap** (nothing pushed), so its flash wear is bounded on its own:
    at most `MeshcorePins.MAX_LIVE_TEACHES_PER_DAY` = 4 live writes a key a UTC day (the wall clock's day); past it the key
    is left to the next Ready's reconcile (`meshcoreLiveTeachesDeferred`), never dropped. A flapping board map costs a
    contact at most four writes and four removals a day; the removal of a key that left the set is never bounded.
  - **Never for a contested prefix** (security B3): `prefixFree` reads taught keys only, so a contested address is refused
    at the queue and again when the job runs.
  - **Counted, retried once:** `meshcoreLiveTeachesWritten` / `…Failed` / `…Deferred`; a failed live teach is re-queued
    once (`retryTeachOnce`), never looped.

  **Round 2 (lora-, mesh-lab- and unit-test-reviewer), applied:**
  - **Every write the board took is charged** to the key's daily bound — written, or answered OK and then failing its
    read-back or finding the key dropped — and only a whole write spends the retry anew. The bound is in memory on the one
    session (a process restart resets it): accepted, the next Ready's reconcile being the backstop either way.
  - **A Knit-record write needs `MeshcorePins.keyFor`** (taught and not contested), which closes security B3 on the reactive
    teach too: a send refused `NO_RECORD` for a contested prefix reached `teach`, whose old guard asked `teaches` and
    `prefixFree` (taught keys only) and wrote a second contact on the prefix. The reconcile's own writes are never
    contested before they run (`MeshcoreContacts.plan` leaves contested keys out; a blocked write contests itself only once
    its sharer's removal failed). `MeshcorePins.noteTaught` is gone: nothing teaches a key but the taught set.
  - **A Ready reconcile dropped on a board this connection does not consent for is counted** (`meshcoreReconcilesDropped`,
    `meshcore reconcile dropped: …`).
  - **A board refusing somebody else's frame is counted and named** (`loraNotOurs`, `lora not ours <label>:<id>`): the
    event a pocket-mate scenario waits on before its fence.
  - **The mesh-lab review's "a restart's reconcile never runs" was the DM after it.** Replayed over 200 seeds (50861270…,
    the reviewer's three among them) with the wait named, the reconcile ran every time; every failure was the next DM, sent
    before the restarted plane's own collectors had caught up — its board map still without Bob direct, or its pair seal's
    index not yet rebuilt (`lora direct unsealed`). `awaitTaught` and the restart now wait for both
    (`LabNode.meshcoreReadyFor`), as the Meshtastic lab's `boardReadyFor` does. The production cost is the existing one: a
    compact DM offered in the moment after a start, before the seal's index is built, goes unsealed and waits for the
    re-probe or a hearing; the signed copy is unaffected.
  - **B, closed: a board's ack that outran the plane's own registration of its send was dropped.** Replayed (seed
    50861270, 200 runs; and 5 failures of the next 200-run sweep, across three scenarios): Alice's session wrote the DM,
    read `SENT` and then the board's `SEND_CONFIRMED`, and emitted `Acked`; Alice's plane logged `lora tx` only after Bob's
    reply had landed and kept the flight (`inFlight=1, parked=1`), with no `lora direct acked`. The outcomes collector and
    the pacer are two coroutines on a multi-threaded dispatcher — `Dispatchers.Default` under the app scope
    (`di/MeshModule`), the node's chaos-lagged `Dispatchers.Default` in the lab — so an outcome can reach `onOutcome` before
    the pacer resumes from `board.send` into `onTaken` → `launchFlight`; `direct.isWaiting` was false, `onUnawaited` found
    no ended send, and the ack was lost: the session never retries a confirmed send, so the flight and everything parked
    behind it waited out its deadline. **The fix, the same for both kinds, no `BoardLink` change:**
    `LoraDirectPolicy.arrive` decides, under the policy's own monitor, whether a flight waits on the handle; if none does,
    nor an ended one, a destination's ack, an `Unreachable` or a `NoOutcome` is held, at most `EARLY_OUTCOME_TTL_MS` (5 s,
    under any direct deadline) and `MAX_EARLY_OUTCOMES` (16); `launchClaiming` registers the flight and takes a fresh record
    under the same monitor, and the pacer settles it through the waiting flight. Whichever side takes the record processes
    it, so nothing is lost or settled twice; a record past its time is dropped unread, so a reused handle (Meshtastic's
    packet ids, MeshCore's session handles) never settles a new send on an old answer. Unchanged: a refusal is never held
    (a broadcast's late NAK, `onLateNak`, and the `OWN_NO_KEY` already handled go as before; a direct send's early
    refusal, which neither kind has been seen to emit before `send` returns, is still read as a late NAK), an implicit ack
    is never held, and a late answer to an ended send still takes `direct.ended`. `onRadioReached` now runs on the outcome
    collector, or on the pacer for an answer held for its send — the first time it runs off the collector. Oracle: `lora direct early
    <outcome> id=…: held for its send` / `…: settled at its send`. Tests: `MeshcoreFlushSwitchTest` (an ack emitted inside
    `board.send` settles its send; a held ack past its time never settles a send reusing its handle; an implicit ack ahead
    of the destination's never replaces it), `LoraDirectPolicyTest` (held and taken once; only a holdable answer for a
    handle neither waiting nor ended; expiry and the bound).
  - **A late ack after the session's own give-up is a late ack** (lora-reviewer's round-3 follow-up). `MeshcoreSession`
    emits `Acked` under the same handle when a confirmation comes after it ended the flight `Unreachable`, but the core
    settled a waiting outcome through `LoraDirectPolicy.resolve`, which never remembered the flight, so the ack found
    neither a flight nor an ended send and was dropped (since B, held 5 s under a misleading line, then dropped): the peer
    stayed unreachable and "reached their radio" never showed. Now `resolve(handle, outcome)` remembers a flight it ends on
    `Unreachable` or `NoOutcome`, as a deadline's expiry always did, and the ack takes the existing late-ack path
    (`lora direct late ack`, the marks moved, `onRadioReached(late = true)`). **On Meshtastic** no ack changes: the session
    turns `MAX_RETRANSMIT` / `NO_RESPONSE` into `Unreachable` and forgets the destination, so a later Routing `NONE` ends
    there and never reaches the core (and it emits no `NoOutcome`), and a refusal after that give-up reaches the core as our
    board's (`byDestination` false, the destination forgotten), classifies as no send-attached class and stays a late NAK
    (`lora nak … (late)`). What changes is a **refusal for a flight that ended on the transport's own deadline**
    (`NoOutcome`) while the session still waited on it: it now finds the ended send, so a `35` books the key reply and a
    destination's refusal holds the peer, where both were a pacing late NAK; any other class (pacing, a missing key not
    handled) keeps the late NAK's handling (`onUnawaitedRefusal`'s `else`). `ended` is bounded on its own
    (`BoardTraits.maxEnded`): Meshtastic's stays `MAX_FLIGHTS`; MeshCore's is `EXPECTED_ACK_TABLE_SIZE` (8), since the board
    keeps the last eight codes and a flight that ended after one attempt holds one, where `maxFlights` (8 / 3 = 2) left a
    give-up three endings back unanswerable. A launch clears `ended` for its handle, so a reused handle's older send is never
    what an answer speaks for. A late answer is never held (it is ended, `UNAWAITED`), so the 5-s record never takes its
    handle. Tests: `MeshcoreFlushSwitchTest.aConfirmationAfterTheSessionsGiveUpIsALateAck`, `LoraDirectPolicyTest`
    (three endings then the first's ack; a reused handle), `LoraDirectTransportTest` (a pacing refusal after a give-up still
    paces). Device trial: on Meshtastic, no `lora direct late ack` or `lora direct early` after `MAX_RETRANSMIT`, and a
    refusal after that give-up logs `lora nak … (late)`.
  - **A post sent the moment a pair met was dropped unverified** (the final gate's 20-run sweep; 3 of 8 room scenarios
    over 200 seeds). The plane resolves an elided sender against its author ids, which it collects off the peer table on a
    coroutine of its own; `awaitAcquainted` had waited for the peer row, not for that snapshot, so the post arrived while it
    still held none of the author's: `lora elided unverified: candidates=0` (a new line, counts only) and
    `ELIDED_UNVERIFIED` / `FRAG_UNVERIFIED` 1, though thirty seconds later the plane resolved the author and the row was
    pinned. A lab readiness gap, not a production change: a pairing that fails leaves the entry by design (round 2 of 5c-1).
    `LoraMeshTransport.resolvesAuthor` reads the snapshot; `awaitAcquainted` waits for every MeshCore pair to resolve each
    other, and a restart's readiness (`LabNode.meshcoreReadyFor`) does too.
  - **The board-off scenario's remaining 6 s** is the session's own field timing, not the reconnect backoff (the
    500-ms knob reaches Bob: back to Ready in about half a second): three attempts, each awaited for the `est_timeout` floor
    (500 ms) and the 1-s slack, read on the 1-s busy tick, before the give-up. Not shortened: those are the session's
    constants, and a knob for them would be a production seam for a lab's sake.
  - **The lab**: a restart waits for the plane and the seal; the reconcile scenario names its wait and restarts once; `meshcoreWritesEver`
    reads every boot's writes; `LabLimits.meshcoreBackoffMs` shortens the session's reconnect backoff for the board-off
    scenario; the drift's Q13 gap is checked against 3.0–4.3 s (two chaos tail stalls); the report reads each air on its
    serial context. The fake gains `FakeNusChannel.delivered` (every frame a board handed a session, a diagnostic) and
    regression tests for both lagged-timer fixes.
- **Counters and seams added** (the waits read an event, never a state): `meshcoreRepinsDone` (`MeshcoreCounters.repinsDone`:
  a re-pin a push queued, run and read back zero-hop), `loraBoundPartsTaken` (`MeshMetrics`, the lora group: a bound part the
  reassembler absorbed — what a forger case waits on at the receiver, never `air.airings`), both in the debug dumps;
  `MeshManager.onDmAcksInlined` (the ✓✓ ids a DM carried inline) and `onIntroMarked`'s token, which each fan-out copy names
  (`SealedProfileSend.mark`), test seams only.
- **The scenarios.** `MeshcoreRoomLabTest`: a two-part post through a repeater to a node out of range; "once" on a diamond
  with the receiver's ring evicted, against its router's dedupe; a one-part post whole as `0x06`; a pocket-mate's post never
  asked of the board (no `SEND_CHANNEL_DATA` for it, fenced by an own post that aired); the junk-part-1 race (the author
  held, part 0 released, three junk part-1s naming it, then part 1; the junk counted `FRAG_TIMEOUT` after a jump past the
  window and one fresh fragment); the overtake behind a held repeater (a copied-hash head and five short heads absorbed
  first); the deep line through two repeaters; junk pre-filled under a sequential id. `MeshcoreDirectLabTest`: a DM, its ✓✓
  and a reaction over LoRa; a far board off (deaf and mute) and back, the held DM re-aired by the hearing's resend or the
  re-probe; path drift end to end (the DM direct, the flooded ack and its repeat, the Q13 PATH about 3 s later, the
  receiver's re-pin, then both directions direct); a ✓✓ in the field's 45-s hold riding the reply sent inside it; security
  (b), a contact Knit did not teach removed at the next Ready and never pinned; security (d)/(f), a non-Knit DM from the
  peer's own board counted, discarded, lifting no mark. `MeshcoreProfileLabTest`'s intro marks are counted through the token,
  and its watch names the peer's key-exchange deltas and stamps marks and copies on a monotonic clock.
- **The fake on the wall clock** (the chaos sweep's find, test code): a lagged end timer let a radio start its next airing,
  or a receiver lock on a newcomer, before the last airing's end had run, losing it unmarked. `FakeMeshcoreAir` now holds
  the radio until its end runs and ends an overdue reception before locking on another; under virtual time neither moves.
- **Where the lab is not the field**: the air's figures on the quick air are the Stage 3b ones ÷ 10; the channel air's
  preset is a fast one, so a scenario is no measure of a rate; the cap's whole window, the rates and the Q13 timing are
  `MeshcoreAirPhysicsTest`'s and owed to boards (the 5c-2a amendment's list).
