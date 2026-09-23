---
id: "2026-09.7ad3"
slug: a-saved-file-opens-in-the-app-that-claims-its-type
title: "A saved file opens in the app that claims its type, and a second tap opens that copy"
date: 2026-09-22
topics: [attachments, ui]
---

# ADR 2026-09.7ad3 — A saved file opens in the app that claims its type, and a second tap opens that copy

Status: Accepted (2026-09-22). Amends point 1 of ADR 2026-09.qq2r.

**What was observed.** Tapping a received file (a PDF, say) opens the system save dialog, and once the user
picks a place they land back in the chat with a "File saved" toast and nothing else. To read what they just
saved they have to leave Knit, find a file manager and dig the file out. The direct-transfer card
(`TransferCard`, ADR 2026-09.wtmz) already opens its received file with `openSavedFile`; a mesh attachment was
the one surface that stopped short.

**What changed.** `ChatViewModel.saveAttachmentTo` emits a `SavedFile(uri, mime)` on `savedFiles` after a
successful write, and `ChatScreen` hands it to `openSavedFile` — `ACTION_VIEW` on the document URI the picker
returned, with `FLAG_GRANT_READ_URI_PERMISSION`. No app claims it → "No app can open this file". The type is
the message row's MIME, else what the provider reports for the new document.

qq2r point 1 ("saving is the only exit; there is no open") stands in its substance: its argument was against
opening **from the blob store**, which needs a plaintext staging file or a provider serving decrypted bytes,
and ADR 029's invariant forbids both. This opens neither. The bytes still go blob → the user's chosen stream
and never touch our storage; what the viewer reads is the user's own copy, served by the provider that holds
it (Downloads, Drive, …), under a grant that provider issued to us. Knit adds no provider, no staging file
and no `FileProvider` path.

**A second tap opens the copy, not the picker.** Asking where to save on every tap made "open this PDF
again" a round trip through the picker and a duplicate file each time. So a save of a non-risky file takes a
persisted read grant on the document (`takePersistableUriPermission`; the picker's result is persistable)
and records it in `saved_files` (DB v15: hash → uri, `savedAt`). `ChatViewModel.openAttachment` looks the
hash up first: a copy that is still there opens directly; a row whose copy is gone is forgotten and the tap
goes to the picker through `saveNeeded`, as if it had never been saved.

- *Still there* is a `query` for `COLUMN_DOCUMENT_ID`, not an `openFileDescriptor`: opening a cloud
  provider's document can start a download just to say yes. A moved or deleted copy comes back empty or
  throws; so does one whose grant the platform pruned (it keeps a bounded number, oldest out first), so
  pruning costs a prompt and nothing else — Knit never releases grants itself.
- *Keyed by blob hash.* A sealed attachment's hash is its ciphertext's, unique to the message, and the row
  goes with the blob: `BlobRepository`'s GC deletes it beside the verdict row, in the same transaction.
- *Encrypted DB, not the DataStore*: a document URI usually spells out the file's name.
- *Never in a backup* (`BackupTables.DEVICE_LOCAL`): the URIs name this phone's storage and the grants are
  this install's. A restored bubble asks once, as it did the first time. The migration starts the table
  empty for the same reason — a file saved on an older build asks once more.
- The bubble's click label reads "Open" (a risky file's still reads "Save").

**What it does not cover.** A risky file (`FileTypes.isRisky`: app packages, archives, executables) is saved
and left there, toast only, and asks every time — nothing on the device can look inside one, the save already
asked first, and this keeps qq2r's "Knit never grants an install intent" true for a mislabelled package.
Photos and voice notes are untouched (the gallery save and the in-app player). There is no "save another
copy" once a file is saved; deleting the copy brings the picker back. Regression: `ChatViewModelTest`
(`savingAFile…`, `aFileSavedBefore…`, `aSavedCopyThatIsGone…`, `aFileNeverSaved…`, `savingARiskyFile…`),
`BlobRepositoryTest`, `KnitDatabaseMigrationTest` (14 → 15), `BackupTablesTest`, `DatabaseExportTest`.
