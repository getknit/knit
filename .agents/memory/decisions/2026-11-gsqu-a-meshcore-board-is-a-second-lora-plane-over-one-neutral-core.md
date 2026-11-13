---
id: "2026-11.gsqu"
slug: a-meshcore-board-is-a-second-lora-plane-over-one-neutral-core
title: "a MeshCore board is a second LoRa plane over one neutral core"
date: 2026-11-12
topics: [lora, meshcore, crypto, airtime]
---

# ADR 2026-11.gsqu — a MeshCore board is a second LoRa plane over one neutral core

Status: Proposed (2026-11-12). Stage 1, the board-neutral seam with Meshtastic unchanged, is built (2026-11-13); no
MeshCore code exists yet. The five bench checks were answered on the firmware itself in a simulator (2026-11-13,
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
its own result (a send's handle is taken before it is posted, so even a timeout answers under the id its outcomes carry;
a setup keeps its record); the drained-packet backlog is 1,024, oldest dropped and counted, and the core subscribes before
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
