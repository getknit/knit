---
id: "2026-11.sp7k"
slug: per-chat-mute-is-a-local-notification-gate
title: "Per-chat mute is a local notification gate"
date: 2026-11-22
topics: [notifications, messages]
---

# ADR 2026-11.sp7k — Per-chat mute is a local notification gate

Status: Accepted (2026-11-22)

**Context:** nothing quieted one conversation. Knit's channels are per kind (all groups, all DMs, each room), and
Android 11+ lets a person set one conversation to Silent from the shade (ADR 2026-10.xukf keeps that choice across a
channel bump), but nothing in the app reaches it, it has no end, and a group is two system conversations there (its
messages on the Groups channel, its mentions on Mentions), so silencing one leaves the other ringing. People expect
Signal's mute: 1 hour, 8 hours, 1 day, 7 days or Always, from the chat, its details and the chat list.

**Decision:** a mute is a deadline per conversation id, `Long.MAX_VALUE` for Always, kept in the settings DataStore
as `id@deadline` entries under one key (`chat_muted_until`, `ChatMuteStore`, the `HiddenKnownStore` shape) and read
by every surface through one rule: `ChatMute.activeUntil`, so a lapsed entry is no mute anywhere and nothing sweeps
it except the next mute's write. The gate sits in `InboundPipeline`'s two notification funnels, `notifyWithBody`
(messages and reactions, the MeshCore room's included) and `notifyMention`, the only callers of `Notifier.notify` /
`notifyMention`; it reads the store with `first()` and the pipeline's clock, as the block check does. The post is
stored, acked, read-receipted and counted unread as before: a mute is presentation only, like a block (ADR
2026-09.bts9), and never the wire, custody or a receipt. What still notifies, and why:

- A **personal mention** gets through a muted group or commons the person accepted (Signal's default), and nowhere
  else: a DM's peer could break the mute with one `@`, a room's stranger with one more. Any member of that room can
  break it so, including a stranger a contact added to the group or a commons' invite let in, but only in that room,
  where they could reach the person anyway had it not been muted; a stranger's request group never reaches the gate.
  `@everyone` arrives as one mention per member named `everyone` (ADR 2026-10.6h2g), a mention of the room, so it
  does not; nor does a bridged room's `@[Name]`, which anyone on the channel can type.
- **Calls, file offers and update asks** need an answer within their window, so they ring and post as before, as in
  Signal and WhatsApp. The coalesced message-request heads-up is about strangers, not a chat, and is untouched.

The chat's ⋮ menu (every kind, the rooms included), a DM's profile, a group's and a commons' details, and the chat
list's long-press (a commons row gets a menu for Mute alone) offer Mute / Unmute; the row draws a muted mark and a
quiet unread badge. Screens read `ChatMute.active`, which re-emits as a timed mute lapses and re-checks at least
every minute, because `delay` stops while the phone sleeps and the deadline is wall-clock. `MSGNOTIF` applies the same
gate so a radio-less build can check it.

The reader reaches first for Android's per-conversation channel: an app cannot lower a channel's importance once it
exists (only the person can), a channel has no expiry, and the group split above would remain. A Room table was the
other choice; DataStore already holds per-conversation ids (`last_read_<id>`, the accepted set), so a deadline adds
no new kind of leak, and it needs no schema bump.

**Consequences / limits:** a backup carries the mute (not in `SettingsKeys.TRANSIENT_PREFIXES`, ADR 2026-09.6mj7), as
pins and drafts are carried; a restored timed mute simply lapses. A contact removal, a group leave and a commons
leave unmute; deleting a thread does not, since the chat can come back. A muted chat posts nothing, so it pushes no
conversation shortcut (ADR 2026-10.jbsa). Muting does not clear what is already in the shade: `clearConversation`
also takes a file offer's and an update ask's heads-ups, which a mute leaves alone. A DM keyed by node id loses its
mute when the peer regenerates their key, as a block or an accept does. A ViewModel test must use Always: a live timed
mute re-checks the wall clock every minute, which a virtual clock never moves, so `advanceUntilIdle` never returns.
Tests: `ChatMuteTest` (the rules, the lapse, the sleep re-check), `ChatMuteStoreTest`, the mute block of
`InboundPipelineTest` (stored but silent, the mention and `@everyone` rules, reactions, calls and offers),
`ConversationListItemMuteTest`, `MuteNotificationsRowTest`, `ChatScreenContentTest`, and the ViewModel tests.
