# USB control protocol v1

> **Experimental, debug builds only** (`exp/usb-control`). The host client is `knit-cli` (a separate repository). There
> is no pairing: Android's own "Open Knit?" accessory prompt is the only gate, and the server is off until the debug
> bridge's `USBCTL --es op enable` switches it on. Nothing here ships in a release build, and nothing here is a
> compatibility promise yet. Changes stay additive where they can: a client ignores fields and events it does not know.

A workstation drives Knit on a phone plugged in over USB, with no adb: it reads chats, contacts and threads, sends,
reacts, marks read and handles message requests, and follows what changes through an event feed. This document is
the contract a desktop client is written against. The phone's side is `app/src/debug/java/app/getknit/knit/usbcontrol/`.

## Transport

The host switches the phone into Android Open Accessory mode (control requests 51, 52 × 6 with the identity
`getknit` / `knit-cli`, then 53), waits for it to re-enumerate as `18d1:2d00` (or `2d01` with adb), claims interface 0
and talks over its bulk endpoints. The first time, the phone asks "Open Knit?"; the answer gates everything.

## Frames

Each frame is a big-endian `u32` length, then that many bytes of UTF-8 JSON, 1 B to 64 KiB. A frame whose whole size
(length plus body) would be a multiple of 64 gets one trailing space in its JSON: neither side sends a zero-length
packet, so a transfer that ends exactly on a packet boundary would sit in the reader's buffer. A length outside the
range is a framing error; the phone answers `{"event":"error","code":"framing",…}` and drops what it held.

## Requests and replies

A request is `{"id": <number or string>, "op": "<op>", …args}` and gets exactly one reply with the same `id`:

```json
{"id": 7, "ok": true, …}
{"id": 7, "ok": false, "code": "not_found", "error": "no such message"}
```

Arguments never use the keys `id`, `op` or `ok`; a message is named by `messageId`. The phone serves requests one at a
time, in the order they arrive.

### Error codes

| Code | Meaning |
| --- | --- |
| `hello_first` | Nothing but `hello` is served before a hello. |
| `bad_version` | The hello asked for a version this phone does not speak. |
| `bad_request` | A missing or misshapen argument. |
| `unknown_op` | No such op. |
| `not_found` | No such chat, message or thread. |
| `blocked` | The on-device content filter refused the text (the Nearby room screens what is sent). |
| `refused` | The thread can't take it: the Meshtastic board refused it, or the thread has no reaction form. |
| `unknown_group` | A `g-…` id this phone has not joined. |
| `not_pending` | `accept` or `decline` on a thread that is no message request. |
| `rate_limited` | Too many sends and reactions; `retryAfterMs` says when the next one is allowed. |
| `internal` | The phone failed answering; the session goes on. |

## Session

- **`hello {v: 1, nonce, client?}`** opens (or resets) the session. The reply echoes `nonce` and carries `v`, `ops`,
  `self` (`node`, `name`, `alias`, `avatarHash`, `app`) and `limits` (`maxFrame`, `maxText`, `maxReaction`,
  `maxHistory`, `maxList`). A previous run that exited without reading can leave frames queued in the link, so a client
  drops every frame until the reply that carries its own nonce.
- **`bye`** ends the session and stops the feed.

## Reads

- **`status`**: `node`, `name`, `health`, `neighbors`, `meshEnabled`, `meshPausedUntil`, `reachable` (node ids).
- **`contacts {offset?, limit?}`**: the direct contacts, by label, as contact objects.
- **`chats {offset?, limit?}`**: every thread the phone's chat list shows (`state: "accepted"`) and every message
  request (`state: "request"`), as chat objects, most recent first.
- **`chat {conv}`**: one chat object; `not_found` when the list shows no such thread.
- **`history {conv, limit?, before?}`**: a page of a thread, oldest first. Without `before`, the newest `limit`
  (default 50, at most 200); with `before: {sentAt, id}`, the page just older than that row. The reply's `more` says
  there is older, and `next` is the `before` to ask with. Messages from blocked senders are left out.
- **`message {messageId}`**: one message object; `not_found` when it is gone or its sender is blocked.

A list reply holds at most `limit` items and stops early when the frame would pass about 56 KiB, with `more: true` and
`next` (an offset for `chats` and `contacts`, a cursor for `history`). An object too big for a frame by itself (no
ordinary one comes close) comes as a stub, its naming fields and `oversized: true`, in a list and in an event alike, so
paging always moves past it.

## Writes

- **`send {conv | to, text, replyTo?, mentions?, styles?, clientMsgId?}`** sends `text` (at most `maxText`) into a
  thread, or opens a DM with `to`. `replyTo` is the `messageId` of an ordinary message in the same thread. `mentions`
  are node ids, each of whose `@label` (the contact's `label`) must be in the text. `styles` are `[start, length, code]`
  triples over the text (codes: 0 bold, 1 italic, 2 strikethrough, 3 monospace, …). The Meshtastic room takes plain
  text only. The reply is `{status: "sent", messageId, conv}`, or `{status: "queued", messageId: null}` on the
  Meshtastic board. **`clientMsgId`** (1 to 64 characters) makes a send idempotent: the same id sent again, even from a
  new session, answers the first reply with `duplicate: true` and sends nothing.
- **`react {messageId, emoji}`** sets our reaction (at most `maxReaction` characters); `emoji: null` (or absent) takes
  it off. The reply says `changed`. Not in the Meshtastic room.
- **`read {conv, upTo?}`** moves the thread's read watermark to the `sentAt` `upTo` (default: its newest message),
  never back and never past the newest message, and clears the phone's notification for it. The reply carries the
  watermark as `lastReadAt`.
- **`delete {messageId}`** deletes a message from this phone only, as the chat's own delete does.
- **`accept {conv}`** / **`decline {conv}`** accept or delete a message request.
- **`block {node}`** / **`unblock {node}`**.

Sends and reactions share one budget across sessions: a burst of 10, then 30 a minute.

## Objects

**Message**: `id`, `conv`, `kind` (`text`, or a notice: `member_left`, `peer_renamed`, `peer_avatar`,
`group_renamed`, `group_photo`, `group_created`, `key_pin_refused`, `file_transfer`, `member_rejoined`, `call`, or
`notice_<n>` for one this build does not know), `from` (node id; a notice's subject), `fromLabel`, `mine`, `body`,
`sentAt` (the author's clock), `arrivedAt` (ours; null for our own), `received` (✓✓ on ours), `via` (the plane it
arrived on), `pending` (ours, waiting for the peer's key), `flagged` (the content filter flagged the text: hide it
behind a reveal), and when present `styles`, `mentions` (`[{node, name}]`), `replyTo` (`{id, authorId, author,
snippet, hasAttachment, check: "unchecked" | "verified" | "mismatch", flagged}`), `reactions` (one entry per emoji,
most reactors first: `{emoji, count, mine, nodes}`, at most 32 emoji and 16 `nodes` each, with `reactionsTruncated`
when it had to cut; `count` and `mine` are always whole), `attachment` (`{hash, mime, name, size}`: metadata only in
v1) and `origin` (`{node, name}`, a heard Meshtastic post).
`fromLabel` and `replyTo.author` are names as of the read: render names from `contacts` by node id where it matters.

**Chat**: `conv`, `kind` (`nearby`, `meshtastic`, `commons`, `group`, `dm`), `title`, `discriminator`, `avatarHash`,
`members` (groups), `state`, `unread`, `lastReadAt`, `last` (a message, its body trimmed to a preview with
`bodyTruncated`; never store it as the thread's copy) and `lastActivity`.

**Contact**: `id`, `label`, `name`, `alias`, `discriminator`, `contact` (a direct contact), `verified`, `reachable`,
`blocked`, `avatarHash`, `openToChat`, `lastSeenAt`.

## Events

**`subscribe {since?}`** starts the feed. The reply carries `cursor: {epoch, seq}` and `resync`. Every event carries
`seq`: the newest position whose changes have all reached the client. Keep the latest `{epoch, seq}`; after a dropped
link, `subscribe {since: cursor}` replays what changed since. When the phone can't (the app restarted, USB control was
switched off, or too much changed), the reply says `resync: true` and the client reads everything again.

Every event holds the object's state at the moment it was written, never a delta:

| Event | Fields | The client |
| --- | --- | --- |
| `message` | `message` | upserts it by `id`, in its thread |
| `message.deleted` | `id`, `conv` (may be null) | removes it, if held |
| `chat` | `chat` | upserts it by `conv` |
| `chat.removed` | `conv` | removes the chat |
| `thread.reset` | `conv` | reads the thread again (rows were trimmed in bulk) |
| `contact` | `contact` | upserts it; one with `contact: false` leaves the contacts list |
| `contacts.reset` | — | reads `contacts` and `chats` again |
| `self` | `self` | replaces its copy of `self` |
| `status` | `status` | replaces its copy of `status` (presence: who is `reachable`) |
| `gap` | `reason` | reads everything again |

**Ordering.** One worker on the phone builds and writes every frame, replies and events alike, so a later frame never
carries older state than an earlier one: the client simply applies frames in order.

**Settling.** The phone reads a changed object a moment after the write and again a little later, sending only when
what it reads moved, so a write still inside a transaction is caught when it commits. A row read as absent is
reported deleted: a client may see `message.deleted` for an id it never held, which it ignores.

### Sync recipe

1. `hello`, then `subscribe`, and keep the cursor.
2. Read `chats`, `contacts` and the threads in view (`history`), applying events as they arrive.
3. Apply every event; on `gap`, start again from 2; on `thread.reset`, read that thread again.
4. On reconnect, `subscribe {since: cursor}`; on `resync: true`, start again from 2.

## Deferred

Attachment and avatar bytes, typing indicators, group create, leave and rename, search, notification suppression
while a desktop is active, pairing (a code shown on the phone, a pinned host key, refusal while locked), and starting
the mesh service on attach.
