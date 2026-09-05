---
id: "2026-09.26q3"
slug: the-meshtastic-room-is-a-local-mirror-of-the-bound-board-s-slot-0
title: "The Meshtastic room is a local mirror of the bound board's slot 0"
date: 2026-09-05
topics: [lora, meshtastic, mesh]
---

# ADR 2026-09.26q3 — The Meshtastic room is a local mirror of the bound board's slot 0

Status: Accepted (2026-09-05) — supersedes ADR 2026-09.cf7a and ADR 2026-09.7r4d; the `meshpost` type is
withdrawn and burned (`docs/WIRE_COMPAT.md`).

**What was observed.** The first design read the paired board's stock public primary into a room by having
the pocket's ACTIVE gateway mint every heard post as a signed, custodied `meshpost` frame and flood it over
BLE/NAN, so a board-less phone got the room too; a post typed in the room was the same frame with no speaker,
flooded the same way, and the ACTIVE gateway transmitted whatever it saw arrive. The lab trial of 2026-09-05
(Pixel 7, screenshot) showed what that buys: one post rendered **three** times — the Knit-flooded copy under
"Walter", the same words heard off the air by Alex's board as `Knit a7c3`, and heard again by Walter's board
from a third board that had also transmitted it — and the whole feature only as correct as the ADR 044
election, which the day before had been found not settling at all (ADR 2026-09.xdm2). The design also carried
a debt every earlier wire precedent had refused: the first entry on `FrameType.isCustodial` since the v1
baseline, a custody-digest divergence (ADR 006) tolerable only behind `BuildConfig.LORA_PLANE`.

The room's job was smaller than the machinery. What a user wants is their own radio's channel in Knit: read
what slot 0 hears, post to it, and see a contact's name where the speaker is one.

**What changed.** The room is a **local mirror of the bound board's slot 0**, and four things follow from
that one sentence:

- **No minting, no custody, no fan-out.** A heard post is a row written by the phone whose board heard it
  (`InboundPipeline.deliverMeshPost`, through the ordinary `deliverChat` with a synthetic local envelope that
  never reaches `originate`), not a frame. `FrameType.MESH_POST`, `MeshPostContent`, the custody bucket and
  its TTL, and both gateway gates are gone; `FrameId.forMeshPost` stays only as the row id, so the board
  replaying its queue on reconnect is a no-op. A phone with no board never sees these posts. The alternative a
  reader reaches for — keep the frame but stop custodying it — still floods a foreign channel's traffic
  across Knit's mesh and still needs the election to pick one transmitter; the row needs neither.
- **Each user posts through their own board.** `MeshManager.sendPublicPost` → the room moderator →
  `PublicChannelSink.postToPublicChannel` → channel 0, `TEXT_MESSAGE_APP`, `Alice: hello`. The own row is
  stored only once the board queued it, and every refusal (`PublicPostOutcome.Refused`: not ready, Knit at
  slot 0, the 30 s floor, too large, no air, a NAK) reaches the composer as a toast with the draft kept. The
  "no per-post confirmation" residual of ADR 2026-09.7r4d is closed by construction — the phone that typed it
  is the phone whose board answers.
- **Slot 0 as configured.** The stock-name and default-key gates went with the flood they guarded: a renamed
  or re-keyed primary is the user's own channel, on the user's own board, shown to the user, and nothing heard
  there leaves the phone. `PublicChannelPolicy` (was `LongFastPolicy` — nothing hardcodes a preset; the label
  is slot 0's own name, else the preset's `defaultChannelName`) refuses only what is not a post, plus the one
  shape with nothing to mirror: slot 0 that *is* the Knit channel (the debug bridge's lab binding),
  `KNIT_ON_PRIMARY`, decided off the channel table like `boundSlotIsKnit`. **Never off the bound index**:
  `SettingsStore.loraChannelIndex` defaults to 0, so the first design read a board that had never run Knit's
  setup as "Knit at slot 0" and mirrored nothing (`anUnprovisionedBoardStillMirrorsItsPrimary`). Routing in
  `onLoraPacket` is by portnum for the same reason.
- **A contact is lined up by the board it holds.** The "Walter" the trial showed was the flooded Knit copy,
  which this removes; nothing had ever mapped a Meshtastic node to a contact. Now the profile carries the
  bound board's node number (`ProfileContent.loraNode`, all three layouts, nullable and elided while unbound —
  the eighth additive profile change, `docs/WIRE_COMPAT.md`), `peers.loraNode` stores the claim, and
  `deliverMeshPost` resolves `packet.from` through `PeerRepository.findByLoraNode` **once, at ingest**, freezing
  the answer on `messages.originPeerId`. Boards change hands and a user may switch boards, so: the value is
  **settings-backed** (`SettingsStore.loraBoardNode`, written when a board reports `Ready`, cleared by
  `setLoraDevice`/`clearLoraDevice`) rather than read off the live link — `LoraStatus.boardNodeNum` is null on
  every BLE drop and would have bumped `profileVersion` and re-flooded the profile on each reconnect; two peers
  claiming one node resolve newest-`updatedAt`-first; and resolving at render time was rejected because it
  would put old words under whoever holds the board now (`aLaterProfileChangeNeverReattributesHistory`). A
  resolved contact wears their name and avatar with the **unverified** styling kept — muted name, untappable
  avatar, the room strip — because the match rests on a self-asserted node number, and
  `PublicPostPolicy.displayBody` drops their own `Name: ` prefix for display only.

The UI follows the radio rather than the pocket: the room row exists while a board is bound or history
remains, titled by the live board's slot 0 (`meshRoomChannel`: live → newest post's channel → "Meshtastic");
`PublicPostGate` swaps the composer for a footer with no radio (or a Knit-at-0 board) and only changes the hint
while the link is down, because that state flaps on every BLE reconnect and unmounting the field would drop
the keyboard mid-sentence; the gate runs *before* the consent sheet, since asking for a disclosure with no
radio to post through is a question about nothing.

**What it costs, and what it does not cover.** DB v11 (two nullable columns), one profile field, and a
withdrawn wire type — the ADR 006 debt is repaid rather than gated. What it does not cover, and the trap: **a
resolved contact is not a verified one.** Any radio can claim any node number, so a spoofed `from` puts words
under a contact's name, softened only by the unverified styling. Meshtastic 2.8 signs the broadcasts a board
originates (`Data.xeddsa_signature`, field 10), which is real evidence — but Knit does not decode the field,
the firmware hands the phone no verdict, and verification needs the sender board's Curve25519 key, which
`BoardQuiet` suppresses the broadcast of. Using it means advertising the board's public key in the profile
beside the node number and verifying on the phone against the exact signing input `Router::perhapsEncode`
uses; it is a roadmap item, and until then the room's strip says what it says. Pinned by
`PublicChannelPolicyTest` (slot 0 as configured, Knit-at-0 off the table), `LoraMeshTransportTest`
(`aPassiveGatewayStillReadsItsOwnBoardsChannel` / `…PostsThroughItsOwnBoard`,
`anUnprovisionedBoardStillMirrorsItsPrimary`), `InboundPipelineTest` (`aHeardPostIsNeverAckedAndNeverLeavesThePhone`,
`whenTwoPeersClaimOneNodeTheNewerProfileWins`, `aLaterProfileChangeNeverReattributesHistory`),
`MeshManagerTest` (`aRefusedPublicPostStoresNothing`, the bound-board republish), and the ViewModel gate and
refusal tests.
