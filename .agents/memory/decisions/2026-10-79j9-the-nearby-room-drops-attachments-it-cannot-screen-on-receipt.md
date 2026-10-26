---
id: "2026-10.79j9"
slug: the-nearby-room-drops-attachments-it-cannot-screen-on-receipt
title: "The Nearby room drops attachments it cannot screen on receipt"
date: 2026-10-26
topics: [moderation, mesh]
---

# ADR 2026-10.79j9 — The Nearby room drops attachments it cannot screen on receipt

Status: Accepted (2026-10-26)

**What was observed.** The Nearby room was kept free of voice notes and files by the sender's composer alone:
`MessageInput`'s `voiceEnabled` and `fileEnabled` are off in the room. A room post's attachment is key-less, so its
MIME is the author's claim, and the receiver took any MIME it named. A modified client could post `audio/aac` to the
room and every Knit in range would pull it, describe it (`deriveVoiceMeta` reads a key-less blob as-is) and draw a
playable voice bubble with no verdict. The one screen it met, `MeshBlobStore.ingest`'s `screenImage`, fails to decode
audio and caches nothing, so it read as screened and was not. A non-image MIME drew as a file bubble the same way.
`docs/CONTENT_MODERATION.md` §7 and the `MeshBlobStore` comment both assumed "a room attachment is an image or a
card", which held only for honest senders.

**What changed.** The receiver enforces what the composer offers. `RoomAttachments.admits` takes no MIME (a picture),
`image/*`, a link-preview card and `video/*`: the kinds the room originates and the receive side screens. Anything
else is dropped from the row whole (hash, MIME, size, description, name, key) in `InboundPipeline.decryptAndDeliver`'s
cleartext branch for `Conversations.NEARBY`, so it is never pulled, described, notified as a voice message or drawn.
The body, mentions and quote land as they came. A post that was nothing but the refused attachment, as a voice note
always is, keeps no row at all (`RoomAttachments.dropsWhole`): it is acked, as a blocked sender's post is, and
nothing else, so it draws no empty bubble, raises no notification and counts as no unread. Custody leaves the hash out of its carrier column
(`ForwardRepository.store`'s `attachmentHash`), so the database re-arm (`attachmentsNeedingFetch`, run at start-up,
each link-up and the 60 s tick) never wants it and GC never pins it, and `onCarriedFrame` refuses it for the live
carrier pull. No Knit's row will ever want those bytes, and carrying them would spend the carrier budget on nothing
drawn. One predicate decides all three, from the frame alone, so it is the same on every node:
`RoomAttachments.refuses` (a chat with no recipient, no group and no seal, naming a MIME `admits` refuses).

The frame is untouched: custody and the relay carry the signed bytes as received, and nothing on the wire changes. The
custody digest folds ids only, never the carrier column, so it still converges.

The alternative a reader reaches for first is a "not supported in Nearby" stand-in, which keeps the row naming the
blob. It was rejected: a row naming the hash is what drives a pull, a re-want and a holder query, and a stand-in
keeps all three alive for bytes this phone will never show. Screening the note by its transcript (ADR 2026-10.am8d)
is not an answer either: speech is debug-only, English-only and on tap.

**What it costs.** A refused post that kept no row is invisible in the room: a reaction or a quote naming it finds no
message, exactly as for a post this phone never heard. The commons is untouched: it offers voice notes and
files, sealed, and screens them after decryption. A row custodied by an older build keeps its hash until its 6 h room
TTL, and older builds still pull and serve these bytes among themselves. The trap: a new kind the room originates must be added to `admits`
in the same change, or every receiver silently strips it. Tests: `RoomAttachmentsTest`,
`InboundPipelineTest.aNearbyRoomPostsVoiceNoteOrFileIsDroppedFromItsRowAndNeverPulled`,
`…aRoomPostThatWasOnlyAVoiceNoteKeepsNoRowAndIsStillAcked`, `…aCarriedRoomPostsVoiceNoteIsNeverPulled`,
`…aRoomVoiceNoteWhoseBytesLandedFirstIsNeverDescribed`,
`ForwardRepositoryTest`'s room voice-note case, and the lab scenario
`AttachmentLabTest.aRoomVoiceNoteOrFileFromAModifiedClientIsNeverPulled`.
