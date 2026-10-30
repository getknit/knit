# Messages, the composer and attachments — which decision governs which symbol

The symbol → ADR routing for what a message carries and how the chat shows it: link previews, location, drafts,
formatting, quoted replies, saved files, search, picture and video attachments, offline translation, and the chat's
blob-size subscription.

Each entry names the symbols that trigger it and the ADRs to read (`.agents/memory/decisions.md` maps an id to its
file, amendments included). An entry is an index into its ADRs, not a substitute: where they disagree, the ADR wins
and the entry is fixed. `grep` this file for every symbol you touch; `AGENTS.md` routes here instead of carrying these
lines.

## Entries

- **When touching typing cues — `MeshManager.sendTyping`, `InboundPipeline.handleTyping`, `TypingTracker`,
  `ChatViewModel.onUserTyping` / its typing combine, or `SettingsStore.typingIndicatorsEnabled`:** READ ADR
  2026-10.97vs. A cue goes over the radios and, for a DM or group, as a spool sig beside them (spec §7.8); the
  Typing indicators switch (on by default) stops both and hides cues already showing, and a cue from a blocked
  sender or in a message request is never shown, whatever plane carried it.
- **When touching `linkpreview/`, `net/`, `mesh/protocol/LinkPreviewBlob`, or anything that opens an
  Internet socket outside the spool plane:** READ ADR 2026-09.n752 (and 2026-09.7x8k: a send holds up to 5 s
  for the card its link is fetching, or the share sheet never carries one; a LoRa thread takes a card exactly
  as it takes a photo — no `loraCarry` gate on the fetch). A link preview is a card the
  **sender** fetches and sends as an ordinary attachment under its own MIME (no wire field, no DB change); the receiver
  never fetches, both ends screen the card's picture and text into one verdict, and the fetch is gated on
  `net/InternetGate` (a validated route, never the NAN link), bound to that `Network`, https-only, with a
  private-address DNS guard. `okhttp3` stays confined to the three files `rules/mesh.md` names.
  Anything new that can open an Internet socket, or a default that turns one on, must keep
  `scripts/egress-check.sh` green: a fresh install with default settings sends no byte over IP (the "Egress
  check" section of `.agents/context/testing.md`).
- **When touching `translate/`, `ui/translate/`, `di/TranslateWiring`, `BuildConfig.TRANSLATE`, the `translate-native`
  pin, `MessageDao.recentOwnBodies` or `scripts/translate-*`:** READ ADR 2026-10.t2h6 (Proposed; the offline translation
  spike, English ↔ Spanish) and its 2026-10-23 amendment. It is a build made with `-Ptranslate=true` only (a debug, or a
  testers' alpha built here). The runtime, slimt and CLD2 behind `TranslateJni`, is the `app.getknit:translate-native`
  AAR that the separate knit-translate-native repository builds reproducibly and publishes to Maven Central: `:app`
  compiles against it everywhere and packages it only under the flag, so no build here compiles native code. Upstream
  pins and our patches to them live in that repository; `-Pknit.translateNative=<checkout>` builds it from source in
  place of the artifact. A translation is the reader's, on tap, held in memory, never on the wire and never logged; it is screened by
  `ScopedTextModerator` in the message's scope; the app's language is never its target. The composer may also
  translate the sender's own draft (`ComposePolicy`, `translateNow`, the ADR's amendment): one tap replaces the draft,
  which the person reads and may edit before sending, never automatically. Each loaded model lives on a
  thread of its own that ends with it (`SlimtEngine`): slimt caches ruy's packed weights per thread by address and
  never clears them, so a reload on a reused thread reads the old model's packs.
- **When touching `location/`, the chat overflow's "Send location" item, or anything that reads the device's
  position:** READ ADR 2026-09.tss4. A shared position is a `geo:` line in the message body (no wire field,
  no capability bit); it is read only between the pin tap and the send, by `ChatViewModel.startLocation`,
  the one collector of `LocationSource.fixes`, and `location/AndroidLocationSource` is the one
  `android.location` importer (detekt-enforced). Never ask for the grant at onboarding.
- **When touching quoted replies — `ReplyRef`, the `replyTo*` / `replyCheck` / `replyToFlagged` columns,
  `data/message/QuoteCheck.kt`, `data/QuoteVerifier`, `InboundPipeline.deliverChat`'s quote path, `MessageDao.depthOf`,
  or `ChatViewModel.quoteViewFor` / `ChatScreen`'s `QuotedMessage`:** READ ADR 2026-10.gpa3. A quote is the sender's
  claim: the receiver judges it against the original it holds (same id, same thread, same author), rebuilds it from that
  original by the composer's own `quoteSnippet`, and stores the verdict, judged on whichever of the two lands second,
  through a once-only conditional update — never a whole-row upsert. A reply quoting words flagged on this phone is
  hidden like a flagged body (`hiddenByFilter`, non-transitive). The author is always the reader's own label for the
  id, never `ReplyRef.author`; our own replies are never rewritten. Regression: `QuoteCheckTest`, `QuoteVerifierTest`,
  `mesh/lab/QuoteLabTest`.
- **When touching `data/draft/`, what the composer keeps between visits, or the chat list's `Draft: …`
  preview:** READ ADR 2026-09.qtg9. An unsent draft is a row in the encrypted DB (never the DataStore —
  it is message text), written debounced on the *application* scope, and handed to the composer exactly
  once by `consumeRestoredDraft()`; nothing is persisted before that hand-over, because the field reports
  itself empty the moment it composes. The list shows it in place of the preview only while
  `updatedAt` beats the newest message, and never touches the row's time or sort. Text and its style ranges only
  (the 2026-10-24 amendment): the staged attachment, the reply quote and the mention bindings stay draft-local to the
  screen.
- **When touching message formatting — `mesh/protocol/StyleSpans` (`BodyStyle`, `StyleSpan`, `StyleRunsSerializer`),
  `ChatContent.styles` / `MessageContent.styles` (key `"s"`), `MessageContentV2` label 17, `messages.styles` /
  `drafts.styles` / `StyleStore`, `ui/chat/ComposerStyles`, or the styles `annotateMessageBody` draws:** READ ADR
  2026-10.p95v. Formatting is UTF-16 style ranges beside a plain body, carried as one CBOR byte string of LEB128
  varints whose type is frozen. An unformatted message costs zero bytes, and a test pins it. The receiver normalises
  once, in `deliverChat`, against the clamped body, and takes a sealed message's styles only from its plaintext.
  Only the bubble draws them; every other surface reads the plain body. Markdown-lite (deferred) is a composer input
  method, so receivers never parse markdown. There is no capability bit; a spoiler (code 4, reserved) would need one.
- **When touching `data/pin/` (`PinEntity`, `PinDao`), `PinRepository`, `ChatViewModel.pinned` / `setPinned`,
  `senderName` or `ChatScreen`'s `PinnedBar`:** READ ADR 2026-10.2r7e. Pins are local to this phone: nothing is
  framed or enters custody, and the bar jumps through the quote-jump path. A row exists only while its message is
  pinned (an unpin deletes it), keyed by message id with no foreign key, at most `PinRepository.MAX_PER_CHAT` a
  thread. Callers pass the state they want, never a toggle; a new stamp is at least one past the thread's newest;
  blocking a sender unpins what they sent; `MeshManager` sweeps orphans at startup. Carried in a backup. Shared pins
  are deferred — CHECK `.agents/memory/roadmap.md`. Tests: `PinDaoTest`, `PinRepositoryTest`, `ChatPinnedBarTest`.
- **When touching `saved_files` / `data/blob/SavedFileEntity`, `ChatViewModel.openAttachment` /
  `saveAttachmentTo`, or what a file bubble's tap does:** READ ADR 2026-09.7ad3. A received file opens from the
  copy the user saved it to (a persisted read grant on the picker's document, recorded by blob hash) and never
  from the blob store (ADR 029); a copy that is gone is forgotten and the picker comes back. A risky file is
  only ever saved. The table is device-local — never in a backup — and goes with its blob in the GC.
- **When touching `data/search/`, `messages_fts` / `MessageFtsEntity`, `ui/search/`, the chat route's
  `messageId` argument, or the shared list rules in `ui/ConversationTitles.kt` / `ui/contacts/ContactUniverse.kt`:**
  READ ADR 2026-09.wdfz (the FTS4 index: one bounded `MATCH` read, the `term*` builder, the rowid and
  no-VACUUM invariants) and ADR 2026-09.wnh6 (one screen over the chat list's own universe — its
  membership, titles, speaker and contacts rules live in those two shared files so search cannot drift
  from the list; a hit opens the thread through `chat/{id}?messageId=` and the quote-jump machinery,
  never a second thread view).
  **When touching the `search?in=` route, `Routes.SEARCH_HIT`, `SearchViewModel.inConversation`, ChatScreen's
  `handedJumpId` or the overflow's Search item:** READ ADR 2026-10.4cyt — "Search in this chat" is the same screen
  scoped to one thread (Messages only, still inside the list's universe); a hit is handed back through the previous
  entry's `savedStateHandle` and popped, never `navigate(chat(id, messageId))`, and the hand-back is cleared as it is
  taken rather than guarded by `consumedJump`, so the same message found twice jumps twice.
- **When touching `data/ImageStrip` or `AttachmentStore.passThrough`:** READ ADR 2026-10.unqn — a picture already
  bounded, upright and no sharper than the q85 re-encode goes out as its own bytes with the metadata stripped, so the
  "no GPS leaves the phone" invariant rests on the strip's allow-list: anything it does not fully understand answers
  null (the long way), and a segment is kept only if a decoder needs it. Tests: `ImageStripTest`,
  `AttachmentStoreIngestTest`.
  **Before touching `ImageStrip.animatedWebp`, `AttachmentStore.animated`, `data/webp/AnimatedFrames` or
  `WebpTranscode.shrinkAnimated`:** READ ADR 2026-10.8hmx — the bytes, not the provider's type, pick the door; an
  animated WebP within 512 px / 200 frames / 1 MiB goes out stripped under the same allow-list rule, anything past
  it and every APNG (Android cannot play one) is composited at the output size into an animated WebP, and
  `identityFor` prefers an animated row so a send flattened before #133 is not reused. Tests: `AnimatedFramesTest`.
- **When touching `data/video/` (`VideoEncodePolicy`, `VideoTranscoder`, `MemoryMp4Muxer`, `MemoryMuxerOutput`,
  `VideoFrames`), `AttachmentStore.ingestVideo`, `VideoInfo` / `ChatContent.attachmentSize`, the `messages.video*`
  columns, `ImageScreeningService.isVideoExplicit` / `screenVideo` / `isPosterExplicit`, or `ui/chat/VideoBubbles`:** READ
  ADR 2026-10.hmfe. A picked video is re-encoded to 480p, 30 fps, about 400 kbps and 61 s by Media3 Transformer into
  memory (never a file, never passed through), in **CBR** (the platform's VBR quality floor raised a 490 kbps ask to
  1.23 Mbps), **not streamable** (a `free` pad was 16 % of the file), with the source's metadata dropped except the
  orientation. Its description and poster ride inside the message; a room post declares its size so js2p holds it.
  A video is its description on a video MIME, tested ahead of the name; a video picked with either button is
  re-encoded, and only an undescribed `.mp4` that arrives (an older build's) stays a file (the amendment).
  The NSFW screen reads the exact frame at a random instant in each twelfth and screens no clip longer than Knit sends
  (the 2026-10-19 amendment). Tests: `VideoEncodePolicyTest`, `MemoryMp4MuxerTest`, `AttachmentStoreVideoTest`; on
  hardware `VideoTranscoderInstrumentedTest` and `VideoScreeningInstrumentedTest` (which also screens a labelled
  folder of real clips pushed to the device; run one test class on one device with `am instrument`, never
  `connectedDebugAndroidTest` with lab phones attached).
- **When touching `BlobDao.observeSizes`, `BlobRepository.observeSizes`, or `ChatViewModel`'s `heldHashes` /
  `heldSizes`:** READ ADR 2026-09.fjcw. The chat's one blob-table subscription is keyed to the window's
  attachment hashes plus the staged one (`IN (:hashes)`, primary-key seeks), and an empty ask never builds a
  query — don't bring back the whole-table `observeSizes()` or a second subscriber; Room invalidates per table,
  so either one re-runs on every blob write anywhere while a chat is open. Regression:
  `ChatViewModelTest.blobSizesAreAskedForTheWindowsAttachmentsAndTheStagedOneOnly`.
