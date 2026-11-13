# USB control protocol v1

> **Experimental, debug builds only** (`usbcontrol/`). The host client is `knit-cli` (a separate repository). The
> server is off until the debug bridge's `USBCTL --es op enable` switches it on, and then it serves only a computer
> paired with the phone, over sealed frames. Nothing here ships in a release build, and nothing here is a
> compatibility promise yet. Changes stay additive where they can: a client ignores fields and events it does not know.

A workstation drives Knit on a phone plugged in over USB, with no adb: it reads chats, contacts and threads, sends,
reacts, marks read and handles message requests, and follows what changes through an event feed. This document is
the contract a desktop client is written against. The phone's side is `app/src/debug/java/app/getknit/knit/usbcontrol/`;
`vectors/usb-control-v1.json` pins the pairing and the seal byte for byte.

## What pairing guards

Android's "Open Knit?" accessory prompt comes first, but it guards nothing once someone ticks "Use by default": any
host that claims `getknit` / `knit-cli` gets through it. So the phone serves only a computer it paired with.

- **A host the phone never paired with** (a charging kiosk, a borrowed laptop) reads nothing and sends nothing. A
  pairing needs a code that the phone shows only while it is unlocked, typed into that computer.
- **A listener in the cable** reads nothing: every session is sealed under fresh keys.
- **A party in the cable that answers both ends** must find the 50-bit code, stretched by 200,000 PBKDF2 rounds per
  guess, from one check value within the two minutes the exchange lives.
- **A phone left plugged in while locked** serves no new plug-in until it is unlocked once.

Not covered: malware on the paired computer (it holds the pairing's key), and other apps on the phone driving the
debug bridge (`USBCTL` is exported in debug builds; it never shows a code or a key).

## Transport

The host switches the phone into Android Open Accessory mode (control requests 51, 52 × 6 with the identity
`getknit` / `knit-cli`, then 53), waits for it to re-enumerate as `18d1:2d00` (or `2d01` with adb), claims interface 0
and talks over its bulk endpoints. The first time, the phone asks "Open Knit?".

## Frames

Each frame is a big-endian `u32` length word, then that many bytes, 1 B to 64 KiB.

- **A clear frame** (the length's top bit clear) is UTF-8 JSON: the pairing and opening ops below, and the phone's
  `error` events.
- **A sealed frame** (the top bit set: `0x8000_0000 | n + 16`) is a session's: `AES-256-GCM(k, nonce, plain n, aad =
  the 4 header bytes)` with its 16-byte tag, 1 ≤ n ≤ 65,520. See *Sessions*.

A frame whose whole size (header, body, tag) would be a multiple of 64 gets one trailing space in its JSON, inside the
seal for a sealed one: neither side sends a zero-length packet, so a transfer that ends exactly on a packet boundary
would sit in the reader's buffer. A length outside the range is a framing error; the phone answers
`{"event":"error","code":"framing",…}` in the clear, ends any session, and drops what it held.

## Pairing

Before a session the phone serves three clear ops and nothing else; any other answers `session_first`. Each carries a
`nonce` (1 to 64 characters), and every reply, a refusal included, echoes it, so a client drops frames an earlier run
left queued in the link until its own come back. Byte fields are standard base64.

```
pair         {v: 1, nonce, client, e}          → {nonce, e, expiresInMs}
pair.confirm {nonce, confirm}                  → {nonce, confirm}
open         {v: 1, nonce, handles: [≤ 8], e}  → {nonce, handle, e}
```

1. The computer sends `pair` with a fresh X25519 key share `eH` and the name the phone will show (`client`, 1 to 64
   UTF-8 bytes). The phone answers its own key share `eP`, draws a code and posts a notice: "A computer calling itself
   “…” wants to pair". The notice opens a screen that is never shown over the lock screen; the code appears there only
   after a tap on Show code, beside a word to type it nowhere but the computer the person ran `knit pair` on.
2. The person types the code on the computer, which sends `pair.confirm {confirm: confirmHost}`. The phone answers
   `confirmPhone` only if `confirmHost` matches, and keeps the pairing; the computer keeps it only once
   `confirmPhone` matches.

```
code   = 10 Crockford base32 characters (50 random bits), shown "7KQFM 2XD9R"; typing reads O as 0, I and L as 1
ss     = X25519(own e, peer e)                                  a low-order point or an all-zero ss is refused
c      = PBKDF2-HMAC-SHA256(code ASCII, salt = eH ‖ eP, 200,000 iterations, 32)
okm    = HKDF-SHA256(ikm = ss, salt = c, info = "knit/usb/v1/pair" ‖ eH ‖ eP ‖ u32be(len client) ‖ client, 112)
       = confirmHost (32) ‖ confirmPhone (32) ‖ psk (32) ‖ handle (16)
```

Rules both sides keep:

- **Two minutes, each on its own clock.** The exchange lives 120 s from the computer sending `pair` (on the
  computer's clock) and from the phone receiving it (on the phone's). The computer never takes the limit from
  `expiresInMs`, never extends it for a retyped code, and keeps nothing from a `pair.confirm` reply that comes later.
- **Three tries per code.** A wrong code answers `bad_code` with `attemptsLeft`; the computer retypes on the same
  exchange. The third wrong one ends it.
- **One exchange at a time, three per plug-in and six on the phone in any ten minutes, eight computers per phone.**
  `pair_busy`, `pair_limit` and `pairings_full` (forget one on the phone).
- The handle is derived, so no handle travels in the clear during pairing, and the `client` name is bound into the
  keys: nothing on the cable can rename the pairing or hand the computer a handle the phone doesn't hold.

The phone keeps its pairings wrapped by a Keystore key (a copy is useless off the phone); the computer keeps its own in
a file only its user can read. A store the phone's Keystore refuses answers `store_unavailable`, and nothing is written
over it.

## Sessions

`open` offers the handles the computer recorded for this phone's USB serial, never another phone's (a device it
never paired learns none of them), and a fresh key share. The phone answers the handle it took and its own fresh key share, and from the next frame
on, both ways, every frame is sealed:

```
k(dir)  = HKDF-SHA256(ikm = psk ‖ ss, salt = eH ‖ eP, info = "knit/usb/v1/seal/h2p" | "knit/usb/v1/seal/p2h" ‖ handle, 32)
nonce   = 4 zero bytes ‖ u64be(counter)                         each direction counts its own frames from 0
```

- **The lock gate.** `open` answers `locked` until the phone has been unlocked once since this link opened (an unlock
  seen at an `open`, or while the link is up). After that the phone serves with its screen locked until the link
  ends. A link that ends any way (unplugged, closed, the app restarted) starts locked again. A phone with no secure
  lock reads as unlocked.
- **A session ends**, keys zeroed and the feed stopped, when a sealed frame does not open (dropped, replayed, changed
  or reordered), the stream loses its place, the computer says `bye`, the pairing is forgotten on the phone, or a
  clear frame arrives. That last one is the next client run after one that left without a bye: the phone serves the
  clear frame. The phone tells the computer in the clear with `{"event":"error","code":"session_ended","error":…}`
  where it can; a client that reads a clear frame mid-session treats it as the end.
- Inside the session come the requests, replies, objects and events below; the first is `hello {v: 1}`.
- Every change to the phone's pairings (pairing, the last-used time an `open` records, a forget) is one read and write
  under one lock, so a computer forgotten while it opens stays forgotten.

## Requests and replies

A request is `{"id": <number or string>, "op": "<op>", …args}` and gets exactly one reply with the same `id`:

```json
{"id": 7, "ok": true, …}
{"id": 7, "ok": false, "code": "not_found", "error": "no such message"}
```

Arguments never use the keys `id`, `op` or `ok`; a message is named by `messageId`. The phone serves requests one at a
time, in the order they arrive. Inside a session every request and reply is sealed.

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
| `session_first` | Before a session: pair, then `open`. |
| `off` | USB control is switched off on the phone. |
| `locked` | `open` before the phone was unlocked on this link. |
| `not_paired` | `open` with no handle the phone holds. |
| `pair_busy` | Another pair exchange is waiting on the phone. |
| `pair_limit` | Three pair exchanges on this plug-in already. |
| `pairings_full` | The phone holds eight computers. |
| `bad_code` | `pair.confirm` with the wrong code; `attemptsLeft` says how many tries remain. |
| `expired` | No pair exchange is waiting (it ran out, or ended). |
| `store_unavailable` | The phone's Keystore refused to read its pairings just now; nothing was changed. |
| `session_ended` | An event: the phone ended the session (`error` says why). |

## Hello and bye

- **`hello {v: 1, nonce, client?}`**, the session's first sealed request, opens (or resets) it. The reply echoes `nonce` and carries `v`, `ops`,
  `self` (`node`, `name`, `alias`, `avatarHash`, `app`) and `limits` (`maxFrame`, `maxText`, `maxReaction`,
  `maxHistory`, `maxList`). A previous run that exited without reading can leave frames queued in the link, so a client
  drops every frame until the reply that carries its own nonce.
- **`bye`** ends the session (back to the clear side) and stops the feed.

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

1. `open`, `hello`, then `subscribe`, and keep the cursor.
2. Read `chats`, `contacts` and the threads in view (`history`), applying events as they arrive.
3. Apply every event; on `gap`, start again from 2; on `thread.reset`, read that thread again.
4. On reconnect, `open` and `hello`, then `subscribe {since: cursor}`; on `resync: true`, start again from 2.

## Deferred

Attachment and avatar bytes, typing indicators, group create, leave and rename, search, notification suppression
while a desktop is active, pairing from the phone's settings (today a computer asks and the phone's notice answers),
and starting the mesh service on attach.
