---
id: "2026-09.hd5n"
slug: the-chat-thread-reads-a-newest-anchored-window
title: "The chat thread reads a newest-anchored window, not the whole conversation"
date: 2026-09-06
topics: [ui, data, perf]
---

# ADR 2026-09.hd5n — The chat thread reads a newest-anchored window, not the whole conversation

Status: Accepted (2026-09-06)

**What was observed.** Opening a chat got slower the longer the conversation was, and this was mistaken for
render cost — the skeleton added on cold open (`233e411`) hid it rather than fixing it. The cause was the
read: `MessageDao.observeForConversation` selected the whole thread with no `LIMIT`, and the retention sweep
lets an accepted thread hold **5,000** rows and the Nearby room **2,000**, so the slow open was the designed
steady state, not a corner case. Room's invalidation is per *table*, so every write to `messages` anywhere in
the app — a DM landing in some other conversation, a receipt, the custody prune — re-ran all of it.

Four costs compounded. The `messages` table carried only `Index("conversationId")`, so SQLite found a thread's
rows through the index and then **sorted every one of them** for `ORDER BY sentAt`. `ChatViewModel` subscribed
to the same unbounded flow three times over (the link-preview walk, the read watermark, the row fold), so the
query ran and the entity list was rebuilt three times per invalidation. The fold then mapped every row to a
`ChatRow` on `Dispatchers.Main.immediate`, parsing the `mentions` JSON even for the `"[]"` default and calling
`PeerDirectory.label` up to twice per row — which, for a node id absent from the peer table (every speaker in
the Nearby and bridged rooms), re-derives alias tokens and runs a collision loop, unmemoized. And two arms of
the combine were wider than the thread: `reactions.observeReactions()` read the **entire** reactions table
across every conversation before grouping it by message, and `blobs.observeSizes()` folded every blob row into
a map, subscribed twice.

**What changed.** The screen reads the newest `ChatWindow.INITIAL` (60) messages and grows by `PAGE` (100) as
the reader scrolls back, through `MessageDao.observeNewestForConversation` — `ORDER BY sentAt DESC, id DESC
LIMIT :limit`, reversed to the ascending shape in the repository by `asReversed()` (an O(1) view) so nothing
downstream changed shape. DB v10 replaces the single-column index with `(conversationId, sentAt, id)`, which
*orders* a thread as well as finding it, so the window needs no sort and stops at its limit.

The reader a reader reaches for first is androidx.paging. It does not fit: it would be a new dependency
(catalog entry, full lockfile regen, F-Droid scanning) and `PagingData` does not compose with a 5-arm
`combine` that folds whole lists — which is what this screen is. The window is one flow and one query.

**Anchoring at the newest end is what makes it cheap.** `ChatScreen` already draws the thread with
`reverseLayout = true`, so index 0 is the newest row at the visual bottom. Older messages therefore append to
the *end* of the reversed list — the visual top — so a page never moves the scroll anchor and never trips the
follow-to-bottom effect, which keys on the last row's id. It also keeps the read watermark exact (the window's
last row *is* the thread's newest message) and self-heals against the retention sweep, which deletes from the
far end the window does not hold.

**Three invariants that are easy to break.**

- **`hasOlder` is measured on the raw window, before the blocked-sender filter.** A window made entirely of
  blocked senders folds to zero rows; measuring those would report an empty conversation with all its history
  sitting behind it. It is carried through `MessagesBundle` from `window.messages.size >= window.limit`, and
  the limit travels *paired with the rows it fetched* — reading `windowLimit` on its own compares the next
  limit against the previous rows and blinks the loading row out mid-page.
- **The read watermark also reads the raw window.** Filtering blocked senders first would stop a blocked
  peer's newest message from advancing it, and that thread would show unread forever.
- **@-mention candidates come from the table, not the window** (`MessageDao.observeSendersIn`). Deriving them
  from the loaded rows means who you can mention depends on how far you have scrolled. That query is now
  *live*, so it needed its own covering index — `(conversationId, kind, senderId)`, both constraints equalities
  with `senderId` as the scanned suffix. Unindexed it would walk a whole thread on every write to the table,
  which is worse than the read it replaced.

**What it costs.** A quoted reply older than the window used to resolve locally; it now calls
`MessageDao.depthOf` (how many rows sit at or newer than the target — over-counts on a `sentAt` tie, never
under-counts) to grow the window to exactly reach it, then scrolls. Depth 0 means the message is no longer
stored and the screen keeps its existing behaviour of ignoring the quote. `meshRoomChannel` now sees only the
window, so the bridged room falls back to its generic title if the newest 60 posts carry no `originChannel` —
a fallback of a fallback, since a connected board answers first. Link-preview cards for older messages decode
when the window reaches them rather than at open.

**Declined: moving the fold off the main thread.** `Dispatchers.setMain` does not cover `Dispatchers.Default`,
so a bare `flowOn` would race every one of `ChatViewModelTest`'s `advanceUntilIdle()` assertions. The window
caps the fold at 60–160 rows and the per-row cost was dominated by the label lookup and the `"[]"` JSON parse,
both now fixed; what is left is field copies. If it is ever wanted, cost it as an injected dispatcher.

**What keeps this true.** `MessageDaoTest` and `MessageRepositoryTest` run the real SQL over 1,000-message
threads: the window is the newest 60, growth only adds at the old end, a `sentAt` collision keeps a stable
order across the boundary (this is what the `id DESC` tiebreak is for — without it a message can appear twice
or vanish mid-scroll), an arriving message rolls the window without widening it, and trimming old history
leaves it untouched. `ChatViewModelTest` pins the paging behaviour and records every limit the ViewModel asks
Room for, so "narrowed at the query" cannot silently become "filtered in memory". `KnitDatabaseMigrationTest`
asserts the 12→13 index *shape* via `PRAGMA index_info`, since column order is what decides whether the sort
is skipped — `EXPLAIN QUERY PLAN` is unreachable from host tests (the Android driver routes only `SEL`/`PRA`/
`WIT` prefixes as row-returning, and Room 3 dropped `openHelper`).
