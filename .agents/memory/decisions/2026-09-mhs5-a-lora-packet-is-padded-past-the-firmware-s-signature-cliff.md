---
id: "2026-09.mhs5"
slug: a-lora-packet-is-padded-past-the-firmware-s-signature-cliff
title: "A LoRa packet is padded past the firmware's signature cliff"
date: 2026-09-01
topics: [lora, airtime, link]
---

# ADR 2026-09.mhs5 — A LoRa packet is padded past the firmware's signature cliff

Status: Accepted (2026-09-01; `LoraAirtime.padTo` + `PacketCost`, `LoraFrameCodec.encodeBest(cost = …)`,
`FastFrameCodec.deflated`/`deflatedForm`, `MeshMetrics.onLoraPadded`, `…debug.LORA` `signing`/`loraPadded`)

Meshtastic 2.8 signs every broadcast the board originates — 66 bytes of `Data.xeddsa_signature` Knit
neither asks for nor can decline — but only while the signed encoding still fits, so a `Data.payload`
of **≤ 165 B** gets the signature and 166 B does not. Measured on a Heltec V4 / 2.8.0.7239fe8
(2026-08-31) against the firmware's own `Packet TX:` figure: at LongTurbo a 165-byte payload and a
231-byte one both cost **1655 ms**, and 166 B costs **1262 ms**. ADR 054 charges for that surcharge;
this is the other half — the cliff cuts both ways, and a frame just under it can trade the board's
66-byte signature for a handful of pad bytes.

## What changed

`LoraFrameCodec.encodeBest` now takes a `PacketCost` (the `LoraAirtime` governor, which already prices
the cliff in `timeOnAirMs`). Given one, it grows the **last** packet of a frame to
`MAX_SIGNED_PAYLOAD + 1`. Only the last: every earlier fragment is a full `cap`-sized chunk and past the
cliff already, and touching only the tail means the fragment count cannot change, so nothing downstream
re-derives. Without a `PacketCost` — the default — the encoding is byte-for-byte what it was.

**This is not a wire change and needs no capability bit.** `WireEnvelope`, `WireCodec`, the signing
input and `sig`/`signed` are untouched; the pad lives inside `Data.payload`, past the end of the frame's
deflate stream, and `FastFrameCodec`'s `inflate` loop stops at the stream marker and never reads further.
Every build already in the field contains that loop, so a padded frame decodes on all of them.

Two things make it work in practice.

1. **The gate is the DEFLATED flag, not a guess.** A *stored* body has no end marker: on 0x03 the pad
   becomes the tail of `signed` and the frame dies later at CBOR decode or signature verification — a
   silent corruption — and on 0x05 `FrameTranscoder.rebuild` rejects it outright. So `FastFrameCodec`
   now answers `deflated(frame)` and the codec pads nothing else.
2. **A stored frame is re-deflated so it *can* be padded.** This is the part the design missed until it
   was measured. The frames with most to gain are exactly the ones that store: the transcoder (ADR 060)
   has already thrown the compressible CBOR keys away, so the 157-byte unsigned v3 tick — the smallest,
   most frequent frame on the plane — has nothing left to compress and rides stored. Deflating it anyway
   costs a **measured 5 bytes** of framing (pinned by `FastFrameCodecTest`) and makes the pad legal.
   `deflatedForm` is taken only when the priced result is cheaper than the stored original, so a frame
   with nothing to gain is left exactly as it encoded. Single-packet frames only: re-deflating a
   fragmented frame changes its size and could change the part count.

Yield, from the shipped `timeOnAirMs` (the same formula that matched the bench to ≤ 1 ms):

| frame | on the air | LongFast | LongTurbo | MediumFast |
|---|---|---|---|---|
| unsigned v3 tick, `0x05` | 157 → 166 B | 2074 → **1665 ms** | 1590 → **1262 ms** | 631 → **498 ms** |
| room post ≥ 45 chars, `0x05` | 155 → 166 B | 2074 → **1665 ms** | 1590 → **1262 ms** | 621 → **498 ms** |
| room post ~23 chars, `0x05` | 141 → 166 B | 1951 → **1665 ms** | 1491 → **1262 ms** | 590 → **498 ms** |

That is **~20 %** off the two highest-frequency frames the plane carries, in a plane whose whole budget
is 45 s of air per 15-minute window.

## The alternatives, and why they are not this

- **Lean on the board's signature and drop Knit's own.** Unsound four times over — wrong key (XEdDSA
  signs with the *board's* identity, so it attests "this board transmitted these bytes", never "this
  user authored this frame"), hop-scoped rather than end-to-end (field 10 exists only on the LoRa air and
  is gone the moment the board hands the frame up over BLE, while custody, relays and spools all verify
  `sig`), inverted (the firmware refuses to sign over 165 B, so the frames that would gain most have no
  signature to borrow), and per-packet rather than per-frame. Recorded so it is not re-proposed.
- **Balance the fragment split instead of padding.** Worse, not better: greedy `[228, 80]` collects one
  signature, balanced `[154, 154]` puts **both** halves under the cliff and collects two.
- **A byte-count rule instead of an airtime one.** `padTo` compares `timeOnAirMs` at the board's own
  preset, which gets the break-even exact under symbol quantization and makes the pre-2.8 board
  (`signing = false`, nothing to dodge) and the already-over-the-cliff frame fall out of the same
  comparison rather than needing their own branches.

## What it costs, and the trap

The tolerance this rides on is an **accident of the inflate loop, not a designed property**. Hardening
`FastFrameCodec.inflate` to reject trailing input would read as a safe cleanup and would silently break
every padded sender against older receivers. `FastFrameCodecTest.aDeflatedFrameIgnoresTrailingBytesBecauseThePaddedLoraSenderDependsOnIt`
is what stops that; if it ever has to change, the padding has to be retired first.

Not covered: **fragmented stored frames**. A ~328 B stored `0x05` splits into two ~166 B packets sitting
right at the cliff — exactly where padding would pay, and exactly where it is not allowed, because
re-deflating the whole frame could change the part count. Nor are `LoraCtl` OFFER packets padded:
`decodeOffer` would tolerate the trailing bytes, but `LoraGossipPolicy`'s suppression depends on two
offers over the same set being byte-identical, and a peer on pre-2.8 firmware would not pad — a separate
decision. And `packet_signature_policy` is still unhandled: under `STRICT` a board drops every unsigned
packet, which after this is *every* Knit frame rather than most of them. It defaults to `COMPATIBLE`,
and padding makes the `STRICT` behaviour uniform rather than newly broken, but a user who sets it
deliberately still loses the plane undiagnosably from inside Knit.

`…debug.LORA` reports `signing` (does this board's firmware sign for us at all) and the `loraPadded`
counter, because a padded frame and an unpadded one are otherwise indistinguishable anywhere but the air.

## Verified on hardware (2026-09-01)

Heltec V4 / 2.8.0.7239fe8 on a Pixel 9, with a **second board** listening over the air. In two halves:

- the codec pads a real Nearby-room post — `lora pad fanout:chat +46B past the signature cliff`,
  `loraPadded` 0 → 1, no NAK, one packet;
- a 166-byte payload leaves the board **unsigned** — heard on the second board as
  `Lora RX … encrypted len=190` / `Packet RX: 1262ms`, against `len=255` / `1655ms` at 165 B.

That `encrypted len` line is the cleanest signing oracle available and beats the `Packet TX:` duration used
in the ADR 054 bench: `len = payload + 24` unsigned and 66 more signed, so it reads the signature off by
arithmetic rather than by comparing a duration to a model.

Not yet observed: **one packet being both** — the phone's plane went `PASSIVE` under ADR 044's pocket
election (deterministic, lowest publisher key wins) before that could be caught. The halves compose, since
`…debug.LORATX` and the codec path share `link.send` and `signedDataFits` is length-based, not
content-based — but that is inference. Also field-observed: a **60-character** room post does not pad, being
already past the cliff once a real `FrameId`, `NodeId` and 64-byte sig are on it; the paddable band for room
posts is narrower in practice than the JVM probe suggested.

## Amendment 2026-10-30: the cliff follows the board's real policy and channel (#243)

Draft by the LoRa owner, on the user's decision of 2026-10-30 and the lora-reviewer design pass.

**What was wrong.** `LoraAirtime.signing` read `DeviceMetadata.has_xeddsa` ahead of the version, and every XEdDSA build
sets that flag (`main.cpp:1390`) whatever it does on the send path. Firmware with upstream `34cb8d582` (PR #12191, merged
2026-10-22, reported `2.8.2.34cb8d5`, `Router.cpp:1205-1235`) signs only under `packet_signature_policy` `BALANCED` or
`STRICT`; under `COMPATIBLE`, its default, it signs nothing. Such a board was charged 66 B on every small Knit packet and
padded frames to dodge a signature that never came; the composer held its room posts to 166 B for nothing.

**What changed.** The `signing` boolean is gone. `LoraAirtime.price(slot, kind, portnum)` answers a `PacketPrice`
(`signedUpTo` — the cliff, or `UNSIGNED` — `extraBytes`, `cap`) off one snapshot the transport takes at `Ready`
(`onBoard`): `SigningFacts` (version, `has_xeddsa`, the policy, ham mode) and `use_aead` on slot 0 and on the bound Knit
slot. The rule is the pure `LoraAirtime.price(facts, aead, kind, portnum)`, pinned row by row in `LoraAirtimeTest`:

| firmware | signs | AEAD channel |
|---|---|---|
| `has_xeddsa` false, or < 2.8 | never | +12 B |
| 2.8.0 / 2.8.1 | a broadcast under every policy; in ham mode every unicast, routing included (`Router.cpp:1270`) | signs anyway: only ≤ 154 B text / 153 B Knit leaves signed; +12 B |
| 2.8.2+ | under `BALANCED`/`STRICT` (or an unknown or unrecognised policy): a broadcast, a NodeInfo or position unicast whatever the ham flag, a ham data unicast; never routing (`Router.cpp:1222-1227`) | never signs; +12 B |
| unreadable version | whenever either rule above would | as either |

A data unicast off a board not in ham mode is PKI (+12 B, never signed, never AEAD) on every version, as before. The
packet classes are `PacketKind`: `BROADCAST`, `DATA_UNICAST`, `NODEINFO_UNICAST`, `ROUTING_UNICAST`. Every booking asks
it: `OutboundFrame.price` (admission, booking and a compact DM's flight, keyed by `Destination`, so they cannot
disagree; `DirectSend.licensed`, a second copy of ham mode, is gone), `postToPublicChannel`, the auto-reply's admission,
`bookNodeInfoReply` (was a fixed `UNSIGNED`: under 2.8.2's `BALANCED`/`STRICT` our NodeInfo after a `35` is signed) and
our board's ACK of a compact DM to us (signed only by 2.8.1 in ham mode, +12 B on an AEAD slot). `padTo` dodges the
bound slot's cliff, so a board that does not sign there pads nothing. The handshake's `Config.security` is read for
field 9 alone, inside `MeshtasticProto.decodeConfig` — never captured whole, since it carries the board's private and
admin keys (`PhoneAPI.cpp:797`), and `BoardConfig` has no `security` member; a security config without field 9 is
`COMPATIBLE` (proto3), a session that streams none is unknown, priced as signing (`LinkState.Ready.signaturePolicy`
null). `want_config` streams it on both versions (`PhoneAPI.cpp:781` at v2.8.1, `:785` at `34cb8d5`); a lockdown build
sends an unauthorised client an empty one, which reads `COMPATIBLE` and costs nothing, as that client can put nothing on
the air. The `lora ready` line now ends `signing=<Knit slot> policy=… knitSignedUpTo=… roomSignedUpTo=… licensed=…`;
`…debug.LORA` keeps `signing` (the Knit slot) and adds `roomSignedUpTo`.

**2.8.1 signs on AEAD channels and overflows — a bug that exists today.** Its `signedDataFits` (`Router.cpp:1177-1185`)
ignores the 12-byte AEAD tag, and the AEAD path then refuses `numbytes + 16 + 12 > 255` as `TOO_LARGE`
(`Router.cpp:1377-1379`), so a text post of 155–166 B on a 2.8.1 AEAD slot never leaves. The composer now caps such a
board at 154. `34cb8d582` never signs on an AEAD channel. AEAD also changes the channel hash (`Channels.cpp:53`,
`h ^= 0xAE`), so a Knit slot another client switched to AEAD is a different channel from every other Knit board's for
what rides it: a Knit broadcast, and a compact DM from a board in ham mode (no PKI). Those are refused at send
(`LoraMeshTransport.aeadBlocks`, logged `lora send skipped: slot N is AEAD (use_aead) …`); `boundSlotIsKnit` stays on
the name alone. A compact DM off any other board is PKI-encrypted with its channel set to 0 (`Router.cpp:1189-1210`,
`:1366` at v2.8.1), so the slot's AEAD and hash never touch it: DMs, their ✓✓ and reactions still air, and the
auto-reply (slot 0) still answers (lora-reviewer's finding on the first draft, which refused them all). Running the setup
again repairs the slot: `MeshtasticSession.identityOnly` writes the slot's own `ChannelSettings` bytes back
(`ChannelInfo.settingsRaw`, `encodeAdminSetChannelSettings`) at its own index and role with field 8 alone removed, so
AEAD is cleared and nothing else moves — `set_channel` replaces the whole `ChannelSettings`, and a rebuilt one would drop
`module_settings`, which the firmware reads as full position precision on a slot whose key is public; the radio screen reads such a board as not set up (`LoraRadioViewModel`), so it offers the
action.

**Accepted gaps.** *The 2.8.2 dev builds from 2026-10-01 to 10-21* (develop bumped to 2.8.2 at `1a5ddec04`) sign under
`COMPATIBLE` like 2.8.1 yet read as 2.8.2: nothing in the handshake tells them apart (`34cb8d582` moved only the
protobufs submodule, the signing buffer is byte-identical), so they are under-charged by the signature on small
broadcasts and padding is skipped. Unreleased builds only; treating all of 2.8.2 as signing would over-book the release
that will carry `34cb8d582`. *Re-evaluation is per handshake only.* A security change reboots the board
(`requiresReboot`), so a new handshake follows it; but `set_channel` saves without a reboot and
`MeshtasticSession.dispatch` ignores a `Channel` after the handshake, so a third-party AEAD flip on a live session is seen
at the next handshake. *`STRICT`*: see ggq4's 2026-10-30 amendment.

Tests: `LoraAirtimeTest` (the table: 2.7, 2.8.1 and 2.8.2 under each policy and none, `has_xeddsa` false, unreadable
versions, AEAD at 154/153 and +12, the PKI unicast, ham, NodeInfo and routing rows, the per-slot snapshot),
`MeshtasticProtoTest` (each policy, field 9 absent, an unknown value, no security config, a security config carrying
keys decoding to the policy alone, `use_aead`), `MeshtasticSessionTest` (the policy per handshake, null when none is
streamed), `LoraPacePolicyTest`, `LoraDirectTransportTest.theNodeInfoAfterA35IsBookedSignedUnderABalanced282Board`,
`LoraMeshTransportTest` (a `COMPATIBLE` board neither pads nor books signed and keeps the 200-byte cap; a 2.8.1 AEAD slot 0
trims to 154; an AEAD Knit slot airs no broadcast and the auto-reply still answers),
`LoraDirectTransportTest.aDmStillAirsWhenTheKnitSlotIsAeadBecausePkiNeverTouchesTheSlot`,
`MeshtasticSessionTest.provisionOnABoardWhoseKnitSlotIsAeadRewritesTheSlotAndClearsIt`, `LoraRadioViewModelTest`,
`PublicPostPolicyTest` (154/155, 166/167). Board trials owed: (1)
`!942b7a83` (2.8.1) with slot 0 on AEAD and a second AEAD board on `meshtastic --listen`: the composer caps at 154, no
`TOO_LARGE` NAK, the 154-B post arrives signed; (2) `!1ba11230` (`34cb8d5`) under `BALANCED`, a `35` provoked: the
NodeInfo reply booked signed, matching the packet length `--listen` shows; under `COMPATIBLE`, room posts booked at the
unsigned price and no `lora pad` line; (3) a restart of each board: the prices come back from the handshake alone
(`lora ready … policy=`); (4) the stock-client legs on `!942b7a83` with slot 0 on AEAD: `meshtastic --sendtext` of 160 B
draws `TOO_LARGE` and nothing is heard on the listening board, 170 B arrives unsigned; (5) two phones, A's Knit slot
switched to AEAD by the Meshtastic app: a DM from A airs PKI and is heard on B's index 0, and B shows ✓; a room post
from A logs `lora send skipped: slot N is AEAD`; A's radio screen offers the setup, which clears AEAD (`--info` shows
`use_aead` gone after the reboot) and room posts reach B again.
