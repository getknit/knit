---
id: "2026-09.qq2r"
slug: a-file-is-an-ordinary-attachment-with-an-arbitrary-mime-and-a-sealed-name
title: "A file is an ordinary attachment with an arbitrary MIME and a sealed name"
date: 2026-09-02
topics: [attachments, ui, wire, moderation]
---

# ADR 2026-09.qq2r — A file is an ordinary attachment with an arbitrary MIME and a sealed name

Status: Accepted (2026-09-02). Point 1 amended by ADR 2026-09.7ad3 (a saved file is then opened).

Knit could send a photo and a voice note and nothing else. Handing someone a PDF over the mesh meant not
doing it. ADR 034 had already established the shape: everything below the ingest seam — the `blobs` table,
`AttachmentCrypto`, `BlobExchange`, `LinkFraming`'s file records, `ForwardEntity`'s attachment pin, the whole
spool attachment plane — moves opaque SHA-256-addressed bytes with a MIME beside them and has never cared
what the bytes are. So this is again two ends and no middle: `AttachmentStore.ingestFile` and a bubble.

**Where it differs from voice notes, and the one thing worth arguing.** A voice note cost no wire field
because duration and waveform are *pure functions of bytes both ends hold* — derive, don't carry
(`docs/WIRE_COMPAT.md`). A filename is not. Nothing about `%PDF-1.7…` says `quarterly-report.pdf`, and a
document bubble with no name is most of the feature missing. So this is the first change since ADR 035 to
**spend** additive fields rather than un-spend one: `MessageContent.attachmentName` and `attachmentSize`,
compact labels 14 and 15. Both are legal under rule 1 (nullable, `encodeDefaults = false`), and both are
absent unless set — `GoldenVectorTest`'s existing fixtures are byte-identical, which is the proof that a
photo's frame did not move.

**Sealed, not cleartext, and the asymmetry is the point.** ADR 035 took the MIME *off* `ChatContent` because
it let a blind carrier tell a photo from a voice note. A filename is a strictly louder signal than a MIME —
it is often the subject of the message — so it never touches the cleartext frame. `ChatContent` is
unchanged, which also keeps `docs/NEXT_WIRE_BREAK.md`'s first parked item true: "a room attachment is an
image" still holds, because the room does not offer files.

**Not in the Nearby room, for ADR 034's reason exactly.** No on-device model classifies a spreadsheet, the
app has no cloud option, and the room floods unencrypted to everyone in range. `MODERATION_NONE` is the
honest verdict and the room is where unscreenable content is refused rather than confirmed. This is not
merely a policy: `MeshBlobStore.saveIncoming`'s screening skip is **sound only because of it**. That skip
requires the row's mime *and* a key, and the key requirement is what stops a room author — whose mime rides
in the clear straight into the row — from switching screening off for their own attachment. Offering files
in the room would take a rule that costs nothing and make it the gap.

**`CAP_FILES` gates the send, not the button — and that distinction was learned the hard way.** An old
build ignores the two new fields as unknown keys, pulls the blob, hands it to the image loader and shows a
broken bubble it cannot even name, since the name it would need is in the field it dropped. So a file is
only sent toward a pinned profile carrying the bit (every other member's, in a group), the send-time-input
pattern `CAP_RATCHET` and `CAP_CRYPTO_V3` already use.

The first cut also **hid the composer's "File" item** on the same condition, and that was wrong. The bit
reaches us only in a profile frame from a peer already running a build that has it — `pushProfileTo` on
link-up, or the 12-hour `republishProfileIfStale` — so during any rollout, and on a device pair where one
side has updated, the item simply never appeared and the long press fell through to the camera exactly as
before. The feature looked unbuilt, and nothing anywhere said why. Visibility is now the room test alone;
the capability is enforced in `ChatViewModel.attachFile`, which refuses with "They need a newer version of
Knit to receive files". **The rule this is an instance of: gate an action on a fact the peer has to tell
you, never the affordance — an invisible control cannot explain itself, and a capability that arrives late
makes "hidden" and "not built yet" the same picture.**

The roadmap's warning against capability-gating — "`Protocol.capabilities`
is unauthenticated advert data, so gating a *privacy* control on the carrier's own claim hands the adversary
the off switch" — is about `FileHeaderWire.mime` and does not reach this: a peer that lies about `CAP_FILES`
gets a file it renders badly, which is its own problem and nobody else's.

Worth not relitigating:

1. **Saving is the only exit; there is no "open".** Handing another app a readable copy needs either a
   plaintext staging file or a content provider serving decrypted bytes, and ADR 029's invariant —
   attachment plaintext lives in the encrypted blob store and nowhere else — is worth more than the
   convenience. `ACTION_CREATE_DOCUMENT` streams the decrypted bytes into the destination the user names, so
   nothing is ever written to our own storage. A received `.apk` still meets the platform's unknown-sources
   gate before anything can install it, because Knit never grants an install intent.
2. **The ingest sniffs magic bytes, and that is a safety property, not a nicety.** Screening skips by MIME,
   so a JPEG offered as `application/octet-stream` would have walked straight past the classifier. Bytes
   carrying an image signature are handed back to the image arm — downscaled, re-encoded and screened —
   whatever the provider called them. A wrong guess costs nothing: a failed decode falls through to storing
   the file as-is, and the recipient screens the decrypted plaintext of every keyed attachment anyway
   (`InboundPipeline.onObtained`, already MIME-blind before this change and the reason a mislabelled image
   still blurs on arrival).
3. **App packages are refused on send.** A mesh that moves APKs between strangers is a sideloading channel,
   and Knit already has a separate deliberate flow for sharing its *own* APK. Archives and executables are
   sent, but a save of one asks first — nothing on the device can look inside them, and saying so is the
   honest complement to never offering to open them.
4. **`IngestResult.Failed` grew a reason, because silence stopped being right.** ADR 029 let a failed pick
   pass without a word: "the picture is still sitting in the picker, so there is nothing to explain." A file
   over 8 MiB cannot be shrunk the way a photo is, and one refused for being a package is a decision rather
   than an accident. Both now say so.
5. **The size is a label, never a bound.** `attachmentSize` is what the bubble shows *before* the bytes
   land; the moment any are held, `BlobDao.observeSizes()` supersedes it. Nothing allocates on it
   (`docs/SPOOL_PROTOCOL.md` C-4.5-8), and the 8 MiB cap is still enforced on the stream itself — the
   provider's `SIZE` column only saves us from reading a file we are going to refuse.
6. **The file picker is its own button in the field, beside the mic.** The trailing composer button's two
   gestures were already spent (tap = photo, long-press = camera, ADR 029), so the first cut hung a
   Camera/File menu off that long press. That was wrong twice over: a long press has no visible affordance,
   so the feature was unfindable, and it put a *third* meaning on a gesture ADR 029 had already argued was
   at capacity. A paperclip beside the mic costs the layout nothing the mic did not already cost — both are
   inline in the field container, transparent at rest, and appear only while the composer is idle — and it
   leaves ADR 029's long press exactly as it was. It also needs no TalkBack special-casing, unlike the
   menu, which was reachable only through `onLongClickLabel`.
7. **Every surface that names an attachment needs a file case; none of them fell through gracefully.**
   Two were found only by using the feature. The **staged preview** fed every attachment to the image
   loader, so a PDF rendered as a blank 72dp square with a ✕ floating on it — indistinguishable from a
   broken attachment; it now draws the same icon/name/size tile the sent bubble will. The **message-details
   body line** called every attachment "📷 Photo", voice notes included, which had been wrong since ADR 034
   and simply had not been noticed. Both now go through one `attachmentLabel`, shared with the chat list and
   the request list, over one `attachmentKindOf` — so a fourth kind is a single edit rather than a hunt.
   The generalisable bit: **"not an image" is a case the attachment UI has to *have*, not a path it falls
   through**, and the fall-through renders as broken rather than as absent, which is why it survives review.
8. **A quoted file's label rides `ReplyRef.snippet`**, exactly as a quoted voice note's does (ADR 034 §5) —
   no new `ReplyRef` field for a cosmetic label. The stated cost is now slightly larger: a quoted file's
   *name* crosses in the snippet even if the blob is never fetched.

**The trap, and it is a rendering one.** `attachmentName` is open sender-supplied text that gets drawn in a
bubble, put in a notification, and offered as the default filename in the storage picker. It is normalized
at the **decode boundary** — both `MessageContent.decode` and `MessageContentV2.decode` run
`AttachmentName.sanitize`, so no call site downstream has to remember. It strips path separators and every
control **and Unicode-format** character; the second half is not decoration, it is where the bidi overrides
live, and a right-to-left override before an extension is the old trick that renders `evil<U+202E>fdp.exe`
as `evilexe.pdf` to whoever is about to save it. `AttachmentNameTest` pins that, the traversal cases, and
the truncate-through-the-stem rule that keeps the extension.

**What this does not cover.** No previews: a typed icon, the name and a size, no thumbnail. `PdfRenderer`
wants a *seekable* file descriptor, which the encrypted store cannot supply without the proxy descriptor or
the plaintext temp file ADR 029 refused; video poster frames are reachable through the existing
`ByteArrayMediaSource` and were simply not built. The 8 MiB cap is unchanged and is asserted in four places
that move together (`AttachmentStore.MAX_BYTES`, `FramedLink.MAX_INCOMING_FILE_BYTES`,
`ScopeAttachments.MAX_ATTACHMENT_BYTES`, `docs/SPOOL_PROTOCOL.md` §12.2) plus the `knit-spool` daemon —
raising it is its own decision. LoRa refuses files already (`LoraMeshTransport.incomingFiles`) and needed
nothing. `docs/IOS_PORT_REVIEW.md`'s open "pin the cross-platform mime set" note gets harder with an open
type set, and is recorded there.

Tests: `AttachmentNameTest` and `FileTypesTest` (pure JVM — sanitization, signatures, the two refusal sets),
`MessageContentV2Test` + `GoldenVectorTest` (round trip at labels 14/15; every prior vector byte-identical),
`KnitDatabaseMigrationTest` (7 → 8, both columns null on every existing row),
`MeshManagerTest` (name and size land on the row's *ciphertext* hash and inside the seal; an image leaves
both null), `MeshBlobStoreTest` (the skip fires for a sealed non-image and never for a key-less one),
`ChatViewModelTest` (only the room hides the item; a peer or group member without the bit is refused *with a
reason*; each ingest refusal's own message; the measured length superseding the declared one). `ScopeVectorTest` and `SpoolRecordsTest` are **untouched**, which is the
executable proof that the middle of the stack did not move.
