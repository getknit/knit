---
id: "2026-09.x52a"
slug: the-meshtastic-room-is-a-switch
title: "The Meshtastic room is a switch, and off means unread"
date: 2026-09-06
topics: [lora, ui, settings]
---

# ADR 2026-09.x52a — The Meshtastic room is a switch, and off means unread

Status: Accepted (2026-09-06; `SettingsStore.loraRoomEnabled`, `LoraConfig.room`,
`LoraMeshTransport.onLoraPacket`/`postToPublicChannel`, `LoraFacts.room`, `LoraStatusRepository`,
`ChatListViewModel`, `ui/lora/LoraRadioScreen` `RoomSwitchRow`)

ADR 2026-09.26q3 gave every phone with a bound board a Meshtastic room — slot 0 mirrored into
`Conversations.MESHTASTIC` — with no way to decline it. That is the wrong default to have no escape from.
A stock `LongFast` primary in a populated region is somebody else's mesh: it carries other people's chat,
their MQTT-injected traffic, and none of it is Knit's business. A user who wants the board purely as a Knit
long-range radio had two options, both bad: unbind the board, which takes the Knit plane with it, or live
with a room that notifies. The room is also the one place in Knit where strangers can put words on the
device, so "I don't want this" deserves a real answer and not a mute.

The switch is `SettingsStore.loraRoomEnabled`, **default on** — the room stays what it is today for
everyone who has it — sitting on the LoRa settings screen beside the DM (ADR 039) and bridge (ADR 044)
switches it is shaped like.

Decisions worth not relitigating:

1. **Off is a drop at the transport, not a filter at the UI.** `LoraMeshTransport.onLoraPacket` returns on
   the slot-0 chat branch before `onPrimaryPacket`, so `PublicChannelPolicy.judge` never runs and neither
   does anything behind it: the NodeDB name lookup, the XEdDSA verify (ADR 2026-09.ggq4), the
   `findByLoraNode` contact resolution, the room moderator, the row and its notification. That is the
   difference between "you do not see it" and "your phone does not do it", and on a busy `LongFast` primary
   it is the difference that matters — the packets keep arriving whatever Knit does with them.
   *Rejected:* hiding the row and leaving ingest alone. It would still wake the moderator and write rows,
   and the promised "no notifications" would then rest on a suppression rule rather than on there being
   nothing to notify about.
2. **Counted as a refusal, not silently.** `metrics.onMeshPostRefused(MESH_POST_ROOM_OFF)` — a reason name
   beside `BLOCKED_CONTACT` rather than a counter of its own, so `…debug.LORA` can still tell "the channel
   is quiet" from "the channel is busy and Knit is ignoring it" during a trial. `meshPostHeard` stays at
   zero, which is the honest reading: `onPrimaryPacket` increments it on its first line and never runs.
3. **Off hides the row *including* its history.** `ChatListViewModel` drops the room row on
   `mesh.loraRoom` ahead of the plane-or-history rule. The rows stay in the database and come back with the
   switch — this is a display choice, never a destructive one, which is why the switch does not offer to
   delete anything (the row's own long-press clear already does, and that is a different intent).
4. **`LoraFacts.room` is the user's answer alone, never folded with the plane** — the one place it differs
   from its sibling `dms`, which reads `enabled && dms`. Whether a DM rides the board is meaningless
   without a board; whether the room exists is not, because its row is drawn from stored history as well as
   from a live radio. Fold it and switching the *plane* off would silently take a week of read history off
   the chat list. `canPost` does fold it, since posting needs both.
5. **The outbound half refuses too** (`PublicPostRefusal.ROOM_OFF`). The composer goes with the row, so this
   is only reachable from a screen that outlived its own row or from the debug SEND intent — the same net
   `MeshManager.sendTyping` keeps under the room it must not cue. A post that left anyway would put words on
   a channel the user has told Knit to stop reading, so the replies would never arrive.
6. **Switching off clears the shade.** `LoraRadioViewModel.onToggleRoom(false)` calls
   `Notifier.clearConversation(Conversations.MESHTASTIC)`. Nothing new can notify from that moment, but a
   post heard a minute earlier would otherwise sit there pointing at a thread with no row behind it.
7. **Knit's own frames are untouched.** The gate is on the slot-0 `TEXT_MESSAGE_APP` branch only; the bound
   Knit slot, the fan-out, the bridge and the DM plane all carry on. "I want the radio, not the room" is
   exactly the position this switch exists to hold.

Cost and residuals (accepted): a room switched off and back on has a gap in it — the posts heard in
between were never stored, and there is no backfill for a channel Knit does not read (nor should there be:
`onPrimaryPacket` is the only writer, and nothing about a heard post is custodied). The setting is
per-device, like every other LoRa switch, so a second phone on the same board's channel makes its own
choice. `ChatViewModel` still resolves for `Conversations.MESHTASTIC` if a screen is already on the back
stack when the switch flips: the composer swaps to `PublicPostGate.ChannelUnusable`, the thread reads as
history, and the refusal string is the net under a send. Tests: `LoraMeshTransportTest`
(`aRoomSwitchedOffDropsThePrimaryPacketWhereItLands` — asserting the *counters*, so a later refactor that
merely filters downstream fails it; `aRoomSwitchedOffStillCarriesKnitsOwnFrames`;
`aPostCannotLeaveADeviceWhoseRoomIsSwitchedOff`), `LoraStatusRepositoryTest` (decision 4 in both
directions), `ChatListViewModelTest` (`theRadioRoomIsHiddenOutrightWhenTheUserSwitchesItOff`, including
that the history comes back), `LoraRadioViewModelTest` (the switch, and the shade clear firing only on the
off edge). **Still owed:** a device trial — switch the room off on one lab phone with a `LongFast` primary
in earshot and read `meshPostRefusedByReason.ROOM_OFF` climbing while `meshPostHeard` stays put.
