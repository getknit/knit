# Content moderation — which decision governs which symbol

The symbol → ADR routing for the on-device models, the word lists, and where text and pictures are screened on the
way out and on the way in. The mechanism lives in `docs/CONTENT_MODERATION.md`.

Each entry names the symbols that trigger it and the ADRs to read (`.agents/memory/decisions.md` maps an id to its
file, amendments included). An entry is an index into its ADRs, not a substitute: where they disagree, the ADR wins
and the entry is fixed. `grep` this file for every symbol you touch; `AGENTS.md` routes here instead of carrying these
lines.

## Entries

- **When touching `moderation/MlTextModerator`, `NsfwImageModerator`, `ModelLease`, `TfLiteModels`,
  `ModelLoadGuard`, the `…debug.MODEL` bridge, or `noCompress` in `app/build.gradle.kts`:** READ ADR 037 and
  ADR 2026-09.cq9z. Each interpreter is mmapped from the APK's **Stored** `.tflite` entry (`noCompress "tflite"`
  is load-bearing; no `tensorflow-lite-support`), built with explicit options (2 threads, XNNPACK), and held
  on a `ModelLease`: the lease owns the mutex, the ten-minute idle reaper and the attempted/resident split — a
  load that returned nothing is spent for the process (that is how the poison-pill survives an unload), a
  reload goes through `ModelLoadGuard` again, and `Interpreter.close()` is never called outside the lease.
  Never put `NsfwImageModerator` on a warm-up path. Regression: `ModelLeaseTest`, `ModelLoadGuardTest`,
  `MlTextModeratorWarmUpTest`; on hardware `ToxicityInstrumentedTest` / `NsfwInstrumentedTest`.
- **When touching `LexicalTextFilter`, `ScopedTextModerator`, `WordList`, `MlTextModerator`'s block thresholds,
  `scripts/gen-profanity-list.py` or a list under `assets/moderation/`:** READ ADR 2026-10.d62w and
  `docs/CONTENT_MODERATION.md` §2. Profanity is the Nearby room's alone; identity slurs are flagged in every
  scope by `slurs_en.txt`, because the toxicity model scores a slur aimed at someone as an insult (a label it
  ignores) and its `severe_toxicity` never fires. Both lists are generated — change the script's dials, never
  the file — and `SlurListTest` pins what a private chat must not block (`nigga`, `coon`, `fag`, `dyke`).
- **When touching a send-side screen — `MeshManager.sendChat` / `sendPublicPost` / `sendCommons`'s
  `isTextFlagged`, `AttachmentStore.ingest`'s `screen`, `ChatViewModel.stage`, `LinkPreviewService.fetchCard`,
  `AvatarStore.saveOwnAvatar` — or the "Message hidden" stand-ins (`InboundPipeline.notificationBody`,
  `messagePreview`'s `hideFlagged`, all reading `MessageEntity.hiddenByFilter`):** READ ADR 2026-10.9rh6. Only the
  Nearby and Meshtastic rooms screen what is sent; a DM, a group and the commons never do, and the receiver's Content
  filtering toggle decides what is hidden. Avatars and group photos are the exception, screened when set. A spool
  requiring moderation (`docs/SPOOL_PROTOCOL.md` §7.5, client half unbuilt) is what would bring the private screen back.
- **When touching receive-side screening — `MeshBlobStore.ingest` / `saveIncoming`, `MeshManager.scopeBlobs()`,
  `ScopeBlobs.refused`, or what adopts a received avatar or group photo (`InboundPipeline.avatarAdoptable`,
  `adoptAdvertisedAvatar`, `settleArrivedGroupPhoto`):** READ `docs/CONTENT_MODERATION.md` §3 and §7. Every blob
  that arrives from someone else, over the radios or a spool, goes through `ingest` before its obtained hook runs,
  and a key-less one is always screened there, before it is stored, so "held" means "screened" (#109). A held avatar is adopted only if screening has not refused
  it: a refused avatar's bytes can stay, pinned in custody by the sealed profile that names them.
