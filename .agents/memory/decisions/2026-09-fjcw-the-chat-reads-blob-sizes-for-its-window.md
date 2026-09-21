---
id: "2026-09.fjcw"
slug: the-chat-reads-blob-sizes-for-its-window
title: "The chat reads blob sizes for its window, not the table"
date: 2026-09-20
topics: [chat, blobs, battery]
---

# ADR 2026-09.fjcw — The chat reads blob sizes for its window, not the table

Status: Accepted (2026-09-20; `ChatViewModel.heldSizes`, work item #71; Pixel 9-verified the same evening with a
throwaway log on `BlobRepository.observeSizes`: the Nearby room's 60-row window asked for its one attachment
hash and ran one query; a text send changed nothing; an image send into the open room re-ran the 1-hash query
once on the blob write and re-subscribed once for 2 hashes; an image send into another thread re-ran the 2-hash
query three times — the DM send path writes `blobs` three times — with no re-ask; an attachment-less DM asked for
nothing, and a blob write while it was open ran no query at all)

Found by the 2026-09-18 battery review, from the code. `ChatViewModel` held two subscriptions to
`BlobRepository.observeSizes()` — `SELECT hash, length(bytes) FROM blobs`, the whole table — one behind the row
fold and the link-card walk (`blobState`), one behind the composer's `stagedAttachmentRelay`. Room invalidates
per table, so every blob write anywhere in the app (an attachment or avatar landing, a photo sent, a group
photo set) re-ran the query twice for as long as any chat was open. `length(bytes)` never decrypts the BLOB
payload (it reads the record header's length varint), but a table walk still decrypts every *leaf page* of
`blobs`, the largest table in the database, and SQLCipher decrypts a page on every read. ADR 2026-09.hd5n named
this leftover when it bounded the messages read and did not fix it.

**What changed.** `BlobDao.observeSizes(hashes: List<String>)` is `… WHERE hash IN (:hashes)` — one
primary-key seek per asked hash. `BlobRepository.observeSizes(hashes: Set<String>)` answers an empty set with
`flowOf(emptyMap())` and never builds the query, so a thread with nothing to size holds no subscription to the
table at all (most threads). The chat derives `heldHashes` — every `attachmentHash` in the raw window plus the
hash staged in the composer, `distinctUntilChanged` — and `flatMapLatest`s it into one `heldSizes`
`StateFlow` (null until the first emission, like `windowed`), which both former subscribers now read. The same
idiom as `groupDelivery` over `MessageReceiptDao.observeDeliveredCounts(conversationId, roster)`.

The reader reaches first for `shareIn` on the unbounded flow: one scan per write instead of two, still a scan.
The second candidate is a join — `blobs JOIN messages ON attachmentHash WHERE conversationId = :id` — which
needs no parameter list but re-runs on every `messages` write as well (per-table invalidation covers both
tables), and the messages table is the busier of the two. The window-keyed `IN` list re-subscribes only when
the set of shown attachments changes, which a message write without an attachment never does.

**What it costs and does not cover.** The `IN` list is at most `ChatWindow.MAX` (5 000) entries against
SQLCipher's 32 766 host-parameter cap; the window is the one bound, so a larger `MAX` must stay under it. A
hash the window does not show is simply absent from the map — `attachmentReady` already treats absence as
"still on its way", and a hash held for a message outside the window is not asked for by design. The
`heldHashes` derivation runs on every window emission (a `mapNotNull` over the window rows); it is the
`distinctUntilChanged` after it that keeps the blob read from re-subscribing. Regression:
`ChatViewModelTest.blobSizesAreAskedForTheWindowsAttachmentsAndTheStagedOneOnly` (the exact set asked, empty
for a bare thread, widened by the staged hash), `BlobRepositoryTest` (the empty ask never reaches the DAO),
`BlobDaoTest` (the `IN` projection reports only what is held among what was asked).
