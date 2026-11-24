---
id: "2026-11.rk6d"
slug: a-sticker-is-read-off-its-bytes-and-kept-on-the-phone
title: "A sticker is read off its bytes and kept on the phone"
date: 2026-11-23
topics: [attachments, ui, data]
---

# ADR 2026-11.rk6d — A sticker is read off its bytes and kept on the phone

Status: Accepted (2026-11-23)

**What was observed.** People kept asking for sticker support. Knit already sent stickers: Gboard's sticker and Emoji
Kitchen tabs feed the composer's content receiver, and an animated sticker keeps its frames (ADR 2026-10.8hmx). But a
sticker was drawn as a 220 dp photo in a bubble, a tap opened the photo viewer, and nothing let a person keep one or
send it again. Other messengers draw a sticker without a bubble and keep a tray of saved and recent ones in the
composer, and that is what "sticker support" asks for.

**What changed.** No wire change: the sticker is a picture both ends already hold, and everything new is how each end
draws and keeps it.

- **A sticker is read off its bytes** (`StickerShape.isSticker`): a WebP with transparency (the VP8X alpha flag, or
  VP8L's alpha bit) whose canvas is at most `STICKER_MAX_EDGE` (512 px) on its long side. Every sticker reaches the
  wire as a WebP: a keyboard's transparent still goes through the still door and comes out a lossy WebP that keeps its
  alpha, and a sticker-sized animated WebP passes through stripped. A photo has no transparency (the still door writes
  a JPEG, and libwebp writes a plain `VP8 ` chunk, with no alpha, for a WebP with none). The header says all of it,
  and nothing is decoded. The sender reads it at ingest (`Ingested.sticker`, also on a reuse hit), the recipient when
  the blob lands (`InboundPipeline.deriveImageShape`, beside the GIF flag, one decrypt for both), as
  `attachmentAnimated` is read. **DB v39** adds the local column `messages.attachmentSticker` and its
  `(attachmentSticker, sentAt)` index. It is not backfilled, since SQL cannot read the bytes, so a sticker received
  before this build keeps looking like a photo.
- **A sticker with nothing beside it is drawn without a bubble** (`ChatRow.showsAsSticker`): its bytes are here,
  nothing hides it, and the message carries no words, place, card or file name. It is the same `MessageBubble` with a
  transparent surface, the picture at 160 dp (`STICKER_SIZE`), the time and tick in a pill of their own below it, and a
  reply's quote on a piece of bubble above it. A tap does nothing: a sticker opens no viewer. A sticker still arriving,
  hidden by the filter or captioned keeps the bubble. The chat list, message requests, the pinned bar, the call screen
  and message details call it "Sticker" ahead of "GIF". A quote does not: its snippet is the rule both ends rebuild
  (`quoteSnippet`, ADR 2026-10.gpa3), and changing it waits for a reason to touch both ends.
- **Saved stickers are a local table** (`stickers`, `StickerRepository`), keyed by the picture's plaintext hash so one
  picture is saved once whichever chat it came through, capped at `MAX_SAVED` (120). A save past the cap is refused and
  says so: unlike a pin, a saved sticker is never let go behind the person's back. Callers pass the state they want.
  A row **names the blob it was saved from** (hash and, for a sealed attachment, its key) and never stores bytes of its
  own. A key-less copy of a sealed sticker would be served to anyone asking for its plain hash, the confirmation oracle
  ADR 2026-10.mptm keeps closed.
- **The row is a blob reference.** `BlobDao.orphanHashes`, `BlobRepository.pinnedOutsideMessages` and
  `dropRefusedGroupPhoto` count `stickers.blobHash`, so a saved sticker's bytes outlive the message that brought them.
  Removing it calls `deleteIfUnreferenced`. `BlobDao.requestBlobCandidates` and `carrierOnlyBlobBytes` leave a saved
  sticker out too: a sticker the person saved, from a message request or while its frame is in custody, is theirs to
  keep, and it is not counted or dropped as carrier bytes. A held blob is still served to a fresh ask after its message
  is gone, under the hash this phone already pulled and served.
- **Recents are read off the messages** (`StickerDao.observeRecent`): this phone's own sends with
  `attachmentSticker`, one per picture under its newest identity, at most `RECENT_LIMIT` (24). A sticker sent from the
  keyboard counts, no table is kept, and the bytes are held as long as the messages are.
- **The tray** opens from a button at the start of the composer's field, where the thread takes images (not the bridged
  room or a text-only commons), in the chat's own window so the keyboard stays up. A tap sends at once and leaves the
  tray open, as other messengers do. `ChatViewModel.sendSticker` opens the plaintext from the named blob and sends it
  through `AttachmentStore.ingestPicked`, so it goes out under the identity this phone already holds it by (ADR
  2026-10.mptm) and is screened in the Nearby room as a pick is. `refusalOf`, shared with the composer's staging,
  refuses it where the composer would. It answers the reply being drafted and leaves the draft's words and anything
  staged alone. It takes **no Cover faces review** (ADR 2026-10.whkk): a tray sticker is a picture this phone already
  sent or received, and the review guards a photo leaving the phone for the first time. The maintainer chose this.
- **Backups carry the table** (`BackupTables.CARRIED`, after `blobs`), as pins are: the collection is the person's.

The alternatives were the obvious ones. A wire flag would have let older and iOS builds agree, but a flag on the frame
is a claim, while the bytes are what both ends hold. The bytes also carry it to every build that reads them. Copying a
saved sticker's bytes into a table of its own would have kept them through a delete without touching the blob
collection, but it doubles the bytes and, for a sealed sticker, makes a plaintext copy servable by its plain hash.
Reading recents from a table of their own would have needed every send path to write it, and the keyboard's would
have been missed.

**What it costs.**

- A tray send inherits ADR 2026-10.mptm's costs, and makes them routine: one sticker resent to many contacts is one
  hash on all those frames. A received sealed sticker goes out under its first sender's ciphertext hash and key, so
  that sender, and anyone else who ever held the key, recognises it on any later frame they carry and sees between
  whom it travels. A room sticker sent into a DM or a group keeps its key-less room name, so every phone that held the
  room post can tell which picture rides there. Only a commons refuses a key-less identity (`requireKey`). This is
  accepted for stickers as mptm accepted it for forwards. A fresh seal for tray sends would end the linkage at the cost
  of one mesh copy per send, and stays open for the maintainer.
- A saved sticker whose message is gone misses `AttachmentIdentities.find`, which reads messages only. It is sealed
  afresh and costs the mesh one new copy, with the same bytes (the pass-through keeps them). Having `find` consult
  `stickers` is blobs-reviewer territory, and the oracle question comes with it.
- A notification still says "Photo": it fires before the bytes land and goes by the MIME.
- The 512 px edge is the published sticker canvas. Gboard and Emoji Kitchen's real output has not been measured on a
  phone, and the device trial owes it.
- A transparent cut-out or logo no larger than 512 px is drawn as a sticker. That is a wrong look, never a wrong send.

The tests that keep this true:

- `StickerShapeTest`: the header read, including truncated and lying headers.
- `AttachmentStoreIngestTest`: what the encoder writes for a keyboard sticker, an opaque PNG and a 1024 px cut-out.
- `InboundPipelineTest.aPulledStickerMarksItsRowsOnceOpenedAndAPhotoDoesNot`.
- `StickerRepositoryTest`: the cap, the blob reference through a delete and both sweeps, request bytes, recents.
- `KnitDatabaseMigrationTest` (38 to 39) and `BackupTablesTest`.
- `ChatViewModelTest`: the tray send's identity, refusal and reply.
- `ChatScreenContentTest`: no bubble, no viewer, the menu and the tray.

## Amendment (2026-11-23): a sticker gets a light outline on a dark background

**What was observed.** On the lab P7, a Gboard sticker ("Nah, I'm busy", 420 × 420, still, with transparency) has black
lettering and no border of its own. It was drawn for a light page, and on Knit's dark chat background the words all but
vanished.

**What changed.** In a dark theme, a sticker is drawn with a 2 dp light outline traced around its own shape
(`Modifier.stickerOutline`, `stickerOutlineColor` in `ui/chat/StickerMessage.kt`), in the chat and in the tray. The
content is recorded into one layer and stamped at twelve points on a 2 dp circle into a second layer, which keeps a
`SrcIn` tint of `onSurface`; the content is then drawn over it. A light theme draws no outline. The theme is read off
the background the sticker sits on, so a theme Knit chose decides it. A sticker that already carries a white border
looks unchanged, because the outline hides under it.

The alternatives: a light plate behind every sticker is a bubble again, and boxes the stickers that already have a
border. Measuring each sticker's ink against the page means a stored verdict that still misjudges mixed stickers.

**What it costs.** Thirteen draws of one recorded layer per sticker on screen, every frame for an animated one. The
trap: one layer with its tint toggled around the stamps does not work. A layer is drawn with the properties it holds
when the frame renders, so every stamp came out untinted (the first build on the P7 did exactly that). Verified on the
P7 by screenshot. There is no unit test: a preview has no sticker bytes to outline.

## Amendment (2026-11-23): a 640 px edge, and a keyboard's sticker skips the face review

**What was observed.** On the lab phones, an Emoji Kitchen mash-up sent from the P9 arrived on the P7 as a photo. Its
bytes were a WebP with alpha and an ICC profile on a 534 × 534 canvas, 22 px past the 512 px edge. With Cover faces on,
the Gboard sloth sticker (420 × 420) opened the face review before it was sent, because every still picture through the
photo door does.

**What changed.**

- `StickerShape.STICKER_MAX_EDGE` is 640 px. It takes the 512 px canvas WhatsApp and Telegram publish, Emoji Kitchen's
  534 px and Apple's large Messages sticker (618 px). Messages received before the change keep the look they were given.
- A still picture the keyboard inserted whose bytes have a sticker's shape is attached with no Cover faces review, as a
  tray send is. The composer's content receiver says whether the keyboard sent it (`TransferableContent.Source.Keyboard`,
  carried as `ReceivedImage.fromKeyboard` to `ChatViewModel.attach`), and `AttachmentStore.isStickerSource` reads the
  source's first 4 KiB before the ingest: `isSticker`'s WebP, or a PNG with an alpha channel or a `tRNS` chunk before
  the first `IDAT`, no larger than the edge. Both must hold. The keyboard alone also pastes screenshots from its
  clipboard chip, and a sticker-shaped pick, paste or drag from elsewhere can be a person cut out of a photo. A head cut
  short, or a `tRNS` past the bytes read, opens the review. The bytes the ingest writes are asked again
  (`Ingested.sticker`). If they are not a sticker, the staged blob goes and the review opens after all. That covers an
  alpha the header claims and the decoder drops, an opaque picture in an RGBA file (the encoder may leave out an alpha
  that hides nothing), and a provider that serves other bytes on its second open. The maintainer chose this over two
  alternatives: running the detector and skipping only when it found no face, or reviewing every keyboard sticker.

Nothing on the keyboard's side names its sticker tab. Gboard commits stickers, Emoji Kitchen, GIFs and clipboard images
through the same `commitContent` call and one content provider, and other keyboards differ, so the bytes decide.

**What it costs.** A sticker Gboard makes from the person's own photo goes out uncovered when it is 640 px or smaller and
transparent. That is the sticker the person chose from their keyboard, and a larger cut-out is still reviewed. The tests:
`StickerShapeTest` (the PNG read, cut-short heads and a lying chunk length), and `ChatViewModelTest`'s three Cover faces
cases (the keyboard's sticker goes as it is, either signal alone is reviewed, and a sticker the ingest does not write as
one is reviewed after all).
