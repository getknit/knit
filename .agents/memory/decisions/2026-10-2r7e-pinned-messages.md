---
id: "2026-10.2r7e"
slug: pinned-messages
title: "Pinned messages"
date: 2026-10-28
topics: [messages, storage]
---

# ADR 2026-10.2r7e — Pinned messages

Status: Accepted (2026-10-28)

**What was observed.** People asked to keep a message at the top of a chat (GitHub #42): a meeting point, a
plan or a link the group keeps scrolling back for. The thread on the issue agreed a first version that stays on the
phone that pins and leaves shared pins for later. The first draft of the table borrowed the reactions' convergence
shape (a `pinnedBy` column with a null tombstone, last-writer-wins on `updatedAt`, an `apply` and a 24 h orphan
grace) so a shared pin could land in it unchanged. Nothing called `apply`, and every unpin and every eviction left
a row that lived as long as its message and went into backups. That is the cost of a convergence nothing needed yet.

**What changed.** Pins are local to this phone, and the table holds only what a local pin needs.

- **Schema v26: one `pins` table** (`PinEntity`): `messageId` (the primary key) and `pinnedAt`. A row exists only
  while its message is pinned, and an unpin deletes it. There is no foreign key to `messages`, as with `reactions`:
  every read joins `messages` (`PinDao.observePinnedIn`), so a pin whose message is gone never shows.
  `KnitMigrations.MIGRATION_25_26` creates the table empty. Shared pins (deferred, `.agents/memory/roadmap.md`) will
  add what convergence needs (who pinned, a tombstone, a stamp to compare) in their own migration and amend this ADR.
- **The caller says which state it wants** (`PinRepository.setPinned(messageId, pinned, now)`), and a write that
  finds the row already in that state does nothing. The bar's ✕ asks for `false` and the long-press menu for the
  opposite of what the bubble shows. A toggle was the first draft. With it, a second tap on the ✕ that landed before
  Room, the `combine` and `stateIn` had carried the first one back to the screen pinned the message again, with a
  new stamp, and with three pins it could evict another.
- **At most three pins a chat** (`PinRepository.MAX_PER_CHAT`, as WhatsApp allows). A fourth pin unpins the chat's
  oldest, and a snackbar says so (`chat_pin_replaced_oldest`). The checks, the count and both writes run in one
  `withWriteTransaction`.
- **A new pin's stamp is never below one past the thread's newest** (`max(now, newestPinIn(thread) + 1)`), not just
  above this message's own stamp. Eviction compares stamps across messages. If the clock ran a day ahead when X was
  pinned and was then put right, a plain wall-clock stamp would keep X the "newest" pin for a day, and the pins made
  after it would be evicted first, the opposite of what the snackbar says.
- **Blocking someone unpins what they sent** (`PinRepository.unpinFrom`, from `ChatViewModel.block` and
  `ProfileDetailsViewModel.block`). Their bubbles disappear, so their pins would hold places the person cannot see or
  unpin: block Mallory with one pin each from Sam, Ana and Mallory, and the bar would read "1 of 2" while the next pin
  evicted a visible one. The alternative, leaving out blocked senders when the limit is counted, keeps the rows. It
  leaves four pins in a thread after an unblock, and the count has to be given the blocked set on every write. The
  bar still hides a blocked sender's pin, for the moment between the block and the unpin.
- **One sweep, at startup** (`PinRepository.deleteOrphans`, in `MeshManager` beside the reactions' sweep). It drops
  pins whose message is gone (a deleted thread, a retention trim) and pins on a blocked sender's message that a block
  path missed (the message-request Block, a device-tag re-block). It needs no age floor: a pin is only ever made on a
  message this phone holds. A single delete drops a pin with its message (`ChatViewModel.deleteMessage`, the debug
  bridge's `PURGE`).
- **Pins are carried in a backup** (`BackupTables.CARRIED`, beside `reactions`). A pin is the person's own choice
  about their chats, not a fact about this phone, so it moves with the chats. `docs/BACKUP_FORMAT.md` lists it.
- **Every thread can pin**, the Nearby room and the Meshtastic room included, since nothing is sent. A pinned heard
  post is named for its speaker, by the rule the thread uses (`senderName`).

**What it costs.** Nobody else in the chat sees a pin, and the CHANGELOG line says so. A restored backup brings its
pins with it. Unblocking someone does not bring their pins back. The trap: a new path that deletes a message
locally must also drop its pin. The startup sweep catches one it misses, but only at the next start. Regression:
`PinDaoTest`, `PinRepositoryTest` (the repeated tap, the limit, the clock, the blocked sender), `ChatPinnedBarTest`,
`ChatViewModelTest`'s pin cases, `PickerPositionTest` (the seven-row menu stays inside the window),
`MeshManagerTest.startupSweepsPinsWhoseMessageIsGoneOrWhoseSenderIsBlocked` and `KnitDatabaseMigrationTest`'s 25→26
case.
