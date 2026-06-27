# Knit — On-device Content Moderation

How Knit limits abusive content — vulgar/abusive **text** and explicit/inappropriate **images** —
entirely **on-device**. The app has no `INTERNET` permission and never talks to a server, so every
check runs locally against bundled assets/models; no cloud moderation API (Perspective, Cloud Vision
SafeSearch, ML Kit cloud) is usable.

> **Mesh threat model.** There is no central moderator. A tampered client can skip any *sender-side*
> check, so **receiver-side enforcement is the real protection**; sender-side is good-citizen UX. Knit
> therefore filters on both ends. No on-device moderation is perfect — classifiers have false
> positives/negatives, are English-centric, and are evadable; this raises the bar, it is not a
> guarantee. These adult-content NSFW models are **not** CSAM detection (a separate, regulated domain).

## 1. Module map

```
moderation/            TextModerator / ImageModerator interfaces + Verdict types
  LexicalTextFilter      pure-Kotlin profanity filter (deterministic first pass)
  HybridTextModerator    lexical first, optional ML classifier second (ml = null today)
  NsfwImageModerator     TFLite NSFW image classifier (graceful degradation if no model)
  WordList               loads assets/moderation/profanity_en.txt
di/ModerationModule.kt   Koin: binds TextModerator + ImageModerator
data/BlobRepository.kt   image-moderation hub: send/receive screening + per-hash verdict cache
data/blob/BlobVerdictEntity.kt   `blob_verdicts` table (NSFW verdict cached by content hash)
assets/moderation/       profanity_en.txt, nsfw.tflite (LFS), README.md
```

User control: a **Content filtering** toggle (default on) in Profile →
`SettingsStore.contentFilteringEnabled`; every check short-circuits to "allowed" when off.

## 2. Text moderation (implemented)

Hybrid pipeline (`HybridTextModerator`): the cheap deterministic `LexicalTextFilter` runs first and
short-circuits on a hit; text it clears is passed to an optional ML classifier (`ml`, currently
`null` → lexical-only until Phase 4).

`LexicalTextFilter` is pure Kotlin (unit-tested, no Android deps) and evasion-tolerant: lowercase +
NFKD-fold (diacritics/homoglyphs), leetspeak mapping (`$h1t`→`shit`), repeated-run collapse
(`shiiit`→`shit`), and single-letter rejoining (`f u c k`→`fuck`). It matches **whole tokens** (not
substrings) to avoid the Scunthorpe problem; `allowedTerms` is an allow-list escape hatch. Word list:
`assets/moderation/profanity_en.txt` (starter set — replace with a vetted corpus, e.g. LDNOOBW).

**Hook points**

- **Outbound (block-on-send):** `MeshManager.sendChat()` returns `false` and stores/sends nothing if
  `isTextFlagged(text)`. `ChatViewModel.send()` keeps the draft and toasts; only an accepted message
  clears the input (via the `clearInput` event).
- **Inbound (flag-on-receive):** `MeshManager.deliverChat()` stores flagged messages with
  `MessageEntity.moderation = MODERATION_TEXT_FLAGGED` (still stored, never dropped). The chat bubble
  collapses them behind tap-to-reveal; block-sender reuses the existing long-press menu.

## 3. Image moderation (implemented; model required to activate)

`NsfwImageModerator` runs a bundled TFLite model fully offline. **If `assets/moderation/nsfw.tflite`
is absent or fails to load, it degrades to allow-all** — all hooks and UI stay wired and activate
automatically once a model is present. Input shape/dtype are read from the model; scores at
`unsafeClasses` are summed and compared to `threshold`.

**Runtime choice.** The bare TFLite interpreter (`org.tensorflow:tensorflow-lite`) is used instead of
MediaPipe `tasks-vision` or `com.google.ai.edge.litert` because it depends only on
`tensorflow-lite-api` (no telemetry/`datatransport`, no Play on-demand model delivery, no Kotlin
stdlib, no manifest permissions) — it can't perturb the pinned Kotlin-2.4 graph or the no-`INTERNET`
offline design.

**Hook points** (`BlobRepository` is the hub; verdicts cached by SHA-256 in `blob_verdicts`, so
identical bytes are scanned once across send/receive):

- **Outbound (context-dependent):** `AttachmentStore.ingest()` always stores the image and reports a
  flag via `BlobRepository.isImageExplicit(bitmap)`; `ChatViewModel.attach()` then decides by
  conversation:
  - **DMs / groups — warn-and-confirm (allowed but discouraged):** a flagged image is **not staged
    until the user confirms** a "send anyway?" dialog (`confirmAttachment` → `confirmFlaggedAttachment()`;
    declining GCs the blob).
  - **Public Nearby broadcast room — hard-blocked (no confirmation bypass):** the flag drops the image
    with a toast and GCs the blob, because it broadcasts to everyone in range.

  A clean image stages immediately; GIFs are screened on their first frame; the receiver blurs flagged
  images regardless.
- **Inbound (flag-on-receive):** `MeshBlobStore.saveIncoming()` → `BlobRepository.screenImage()` caches
  a verdict; the chat UI blurs flagged attachments behind tap-to-view (`AttachmentImage`,
  `ChatRow.attachmentFlagged`). Bytes are stored regardless, so a false positive never drops content.
- **Avatars:** received avatars are screened in `saveIncoming` / `MeshManager.onAvatarReceived`; a
  flagged avatar is **not adopted** (the peer falls back to its monogram), on both the direct-push and
  multi-hop pull paths. (Own-avatar send-screening is intentionally omitted — every recipient screens
  what it receives, so an explicit own-avatar is dropped on their side.)

### 3.1 Bundled NSFW model

`assets/moderation/nsfw.tflite` is the **GantMan `nsfw_model` MobileNetV2 (140/224)** classifier:
input `[1,224,224,3]` float32 normalized `÷255`, softmax output `[1,5]` =
`drawings, hentai, neutral, porn, sexy`; `NsfwImageModerator` defaults flag on
`hentai+porn+sexy ≥ 0.7`. It is a 17 MB float model (managed via Git LFS — see §5). Vet its license
before release; tune `threshold`/`unsafeClasses` on-device. A quantized `.tflite` (~4–5 MB) drops in
unchanged — `NsfwImageModerator` auto-detects uint8 input.

## 4. Phase 4 plan — ML toxicity classifier (deferred)

**Status: deferred, blocked on a prerequisite.** The hybrid runs lexical-only today. Layering in an
on-device toxicity classifier is gated on a vetted model existing, because:

- There is **no turnkey toxicity model** — even Google's prebuilt MediaPipe text classifiers are
  *sentiment* (movie-review positive/negative), not toxicity. It must be trained or sourced and
  license-vetted.
- BERT tokenization is hard to hand-roll, so the realistic runtime is **MediaPipe `tasks-text`**,
  which transitively pulls `flogger`/`protobuf`/Google `datatransport` (telemetry). Adding it
  speculatively — for zero current functionality, no way to validate without a model, and a dent in
  the offline-purity goal — is not worth it until a model exists.

### 4.1 Model requirements

A **BERT text *classifier*** fine-tuned for toxicity, exported **with TFLite metadata** (embedded
vocab + tokenizer config + label map):

- Output `[1, N]` class scores (e.g. 2 = clean/toxic, or the 6 Jigsaw labels:
  `toxic, severe_toxic, obscene, threat, insult, identity_hate`).
- **Not** a QA model (`start_logits`/`end_logits` outputs) and **not** sentiment.
- Recommended: train with **MediaPipe Model Maker** (`BertClassifier`) on the **Jigsaw Toxic Comment**
  dataset; quantize MobileBERT to ~25 MB. Model Maker emits the metadata `TextClassifier` needs.

### 4.2 Implementation steps (when a model exists)

1. Place the model at `assets/moderation/toxicity.tflite` (or `.task`). It is LFS-tracked automatically
   (`.gitattributes` covers `*.tflite`; add a `*.task` rule via `git lfs track "*.task"` if used).
2. Version catalog (`gradle/libs.versions.toml`): add `com.google.mediapipe:tasks-text`. **Probe its
   transitive deps first** (AGENTS.md) — it must not bump the pinned `coreKtx 1.18.0` /
   `lifecycle 2.10.0` or drag a newer Kotlin stdlib. Add `androidResources { noCompress += "task" }`
   if a `.task` bundle is used.
3. Confirm the **merged manifest still has no `INTERNET`** after adding the dep (MediaPipe's
   `datatransport` should not add it; verify and, if needed, suppress with a manifest
   `<uses-permission android:name="android.permission.INTERNET" tools:node="remove"/>`).
4. Write `moderation/MlTextModerator.kt` implementing `TextModerator`: lazily build a MediaPipe
   `TextClassifier` from `BaseOptions.setModelAssetPath(...)`, run `classify(text)` on a background
   dispatcher, map the toxic category score against a threshold → `TextVerdict`. Degrade to
   `TextVerdict.ALLOWED` if the model asset is missing (mirror `NsfwImageModerator`).
5. Inject it in `di/ModerationModule.kt`:
   `HybridTextModerator(LexicalTextFilter(...), ml = MlTextModerator(androidContext()))`.
6. Regenerate the lockfile for **all** configs (AGENTS.md gotcha):
   `./gradlew :app:dependencies --write-locks` then `./gradlew lint`.
7. Verify: `./gradlew :app:testDebugUnitTest detekt :app:assembleDebug :app:lintDebug`, then on-device
   tune the toxicity threshold against representative messages.

No app-side wiring beyond step 5 is needed — `MessageEntity.moderation`, the tap-to-reveal UI, the
settings gate, and both hook points already route any `TextModerator` verdict.

## 5. Git LFS for model files

`*.tflite` is tracked via Git LFS (`.gitattributes`). Contributors and CI **must have git-lfs
installed** (`git lfs install`) or checkouts get pointer files instead of real models and the build
ships a broken classifier. Models are large binaries (the NSFW model is 17 MB); keeping them out of
the packfile keeps clones fast. `.tflite` is also kept uncompressed in the APK
(`androidResources { noCompress += "tflite" }`) so TFLite can mmap it.

## 6. Verification

- **Unit (`./gradlew :app:testDebugUnitTest`):** `LexicalTextFilterTest` (normalization/evasions/
  Scunthorpe), `HybridTextModeratorTest` (lexical short-circuit / ML fallthrough). The image
  classifier and hooks need a device + model and are not JVM-testable.
- **Build/lint:** `./gradlew :app:assembleDebug :app:lintDebug detekt`.
- **Device (two physical phones — the mesh can't form on an emulator):** send abusive text → blocked
  with toast; send an explicit image → blocked; receive both from a peer → text collapsed, image
  blurred with tap-to-view; flagged avatar → peer shows its monogram; toggle off → no filtering.
