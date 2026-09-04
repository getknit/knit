---
id: "2026-09.cf7a"
slug: a-meshtastic-public-post-is-a-signed-attribution-in-its-own-room
title: "A Meshtastic public post is a signed attribution in its own room"
date: 2026-09-03
topics: [lora, meshtastic, mesh]
---

# ADR 2026-09.cf7a — A Meshtastic public post is a signed attribution in its own room

Status: Accepted (2026-09-03) — phase 1 (receive-only) of work item #37.

**What was observed.** The board Knit holds already hears Meshtastic's public primary channel. ADR 045 never
touches index 0, the firmware decrypts the default-key primary itself, and `MeshtasticSession.onPacket` hands
every decoded packet up — `LoraMeshTransport.onLoraPacket` then dropped everything that was not
`PORT_PRIVATE_APP` on the bound Knit slot. So a neighbourhood's whole public conversation was arriving at the
phone and hitting the floor. The reason to pick it up is not mainly that those people are a community worth
bridging to. It is that an ESP32 takes **one** BLE client: a Meshtastic user who tries Knit goes dark on
LongFast for as long as Knit holds the board, which is a hard reason not to switch, and reading the channel
removes it. Costs no airtime — this direction is pure receive.

**What changed.** A new `RelayEnvelope.type`, `meshpost`, minted and signed by the gateway phone and flooded
over BLE/NAN like any other frame, so one board gives a whole pocket the window. It lands in a **second
public room** (`Conversations.MESHTASTIC = "m-public"`, its own `ConversationKind`) rather than in Nearby.

The alternative a reader reaches for first is a `chat` frame with an extra field — every recent feature took
that route (`CTL_RECEIPT`, `GroupKeyPayload.gr`, the inline acks), and `docs/WIRE_COMPAT.md` records the
reasoning each time. It does not work here, for two reasons about what an *older* build does with the frame.
It would attribute the text to the gateway's own user; and it would **render** it, which earns a sealed
`CTL_RECEIPT` from every recipient — ticks that then ride the LoRa plane home from far pockets for a message
nobody is waiting on. A new type is invisible to both paths: `dispatchByType` never reaches `acknowledge`, so
no receipt is ever owed (`InboundPipelineTest.aBridgedMeshPostIsNeverAcked`).

Reusing Nearby's room was the other candidate, and it fails on semantics rather than mechanics. Nearby means
people physically here, live; its authors are peers with presence, pinned keys, receipts and an open-to-chat
flag. A LongFast author has none of that, may be many hops away, and may not be on the air at all — some posts
arrive over somebody's MQTT uplink. Keeping the rooms apart is what stops any of that leaking either way.

Three mechanisms carry the design:

- **The frame id is derived, not minted.** `FrameId.forMeshPost` = `SHA-256("knit-meshpost-id-v1:$node:$packetId")`
  truncated to `ID_BYTES`. Every board in range hears the same packet; one id collapses the duplicates on
  machinery that already exists — `MeshRouter`'s `SeenSet`, `MessageDao.insertIfAbsent`, and `StoreDigest`'s
  XOR over ids, so two gateways custodying byte-different copies still hold the same digest. `(node, packetId)`
  is exactly the pair Meshtastic's own firmware dedups on, so this inherits its uniqueness rather than
  inventing any. Changing the id format is not a wire concern (`FrameId`'s kdoc, `NEXT_WIRE_BREAK.md`).
- **Only the ACTIVE gateway mints** (ADR 044's election, reused). The derived id makes duplicate minting
  harmless, not free: it doubles the BLE flood in a two-board pocket and leaves two byte-different copies of
  one post in the mesh.
- **The speaker is an attribution, never an identity.** The frame's `senderId` is the gateway — the only
  authenticated party — and `MeshPostContent` carries the node number, a `User.long_name` snapshot, the
  channel, hops, SNR and `via_mqtt` beside it. Nothing creates a `PeerEntity`; nothing counts toward
  `neighbors`, presence, open-to-chat or the contacts picker.

**Everything on the LoRa plane is refused by construction, and that is worth knowing rather than assuming.**
`shouldLongRangeFanout` is `type == CHAT` only; `LoraFramePolicy.eligible` is an allow-list on all three paths,
which the digest-driven backfill funnels through; `reofferOne` requires DM form. So a bridged post is never put
back on the band it was born on — where every radio in range already heard the original. Pinned in
`FrameFanoutTest` and `LoraFramePolicyTest` so a future widening of any one path is a decision.

**What it costs.** `FrameType.isCustodial` is a **fixed list on every build already in the field**, so a build
without this change holds none of these rows while we hold them all — two nodes with continuously different
live sets, which is precisely the custody-digest divergence ADR 006 exists to prevent and the reason nothing
has been given its own `type` since the v1 baseline. It is accepted only because the whole plane is
`BuildConfig.LORA_PLANE`-gated (debug on, release off), so no shipped build ever mints one; it rides the same
release gate the `0x05` transcoder flag-day already owes. `FrameType.isCustodial`'s own kdoc and
`FrameTypeTest` say so where the next person will change it.

A smaller residual: `sentAt` is the gateway's clock at hearing, because a Meshtastic text packet carries no
author timestamp. Two pockets that hear the same post stamp it seconds apart and so expire their copies
seconds apart — bounded, and the same magnitude as the clock skew custody already tolerates.

**What it does not cover.** Outbound (§5 of the work item) — nothing typed in this room reaches the channel,
and the composer is replaced by a line saying so. Tapbacks and `reply_id`, firmware-2.8 XEdDSA author
verification, and the unicast signpost are all deferred. A renamed **or re-keyed** primary is never ingested
(`LongFastPolicy.isStockPrimary`): a name says nothing about who can read a channel, and a group that keeps the
stock name and changes the PSK is exactly as private as one that renames. A board reporting no channel table is
refused rather than given the benefit of the doubt — the opposite reading from `boundSlotIsKnit`, because there
the worse failure is going mute and here it is ingesting somebody's private channel.

**Two traps.** `noteBoard` is deliberately **not** called for a stock neighbour: `boardsHeard` means "radios
that sent a *Knit* frame", which is what makes "heard nobody" the ordinary state of a solo user and why the
preset-mismatch notice cannot be gated on evidence. And the public-primary branch is decided off the **bound
slot**, not off ADR 045's provisioning rule — the debug bridge can bind index 0 by hand, and on such a board
index 0 is Knit's own traffic with no public primary to read
(`LoraMeshTransportTest.aBoardWithKnitBoundToIndexZeroReadsNoPublicPrimary`).

**Spoofing is unaddressed by design, so the UI carries the whole burden.** Nothing on this channel is
authenticated: any radio can claim any name. A bridged author therefore renders with its `!hex` id, an unclickable
avatar, a muted name rather than the primary colour a Knit author gets, "via *X*'s radio" naming the gateway,
and a room-level strip saying names here are not verified — pinned by `ChatMeshRoomTest`, whose
`aBridgedAuthorIsNotTappable` guards the sharp edge: routing that tap would land on the gateway, a real
contact, and offer to message somebody who never said any of this.

Phase 1's actual output is the measurement: `meshPostHeard` / `Ingested` / `ViaMqtt` / `Passive` /
`RefusedByReason`, in Diagnostics and `…debug.LORA`. Those numbers decide the quota, and whether hiding
via-MQTT posts is a setting or the default.
