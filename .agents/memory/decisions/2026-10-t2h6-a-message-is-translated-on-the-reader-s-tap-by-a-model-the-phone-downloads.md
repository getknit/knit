---
id: "2026-10.t2h6"
slug: a-message-is-translated-on-the-reader-s-tap-by-a-model-the-phone-downloads
title: "A message is translated on the reader's tap by a model the phone downloads"
date: 2026-10-22
topics: [ml, ui, moderation, privacy]
---

# ADR 2026-10.t2h6 — A message is translated on the reader's tap by a model the phone downloads

Status: Proposed (2026-10-22). Nothing is built. A spike decides the runtime and the language identifier (the
last section), and the ADR is accepted or rewritten on what it measures. No wire byte moves.

**What was asked.** Can a text message be translated with no network? The rooms are where people who share no
language meet: the Nearby room is read by strangers, and the Meshtastic room's authors are strangers by definition.
The app's own text is being translated into other languages, but a message stays in its author's language. The
answer has to work at the moment of reading, offline, because the mesh is used where there is no Internet.

**What is proposed.** The reader's phone translates a message when the reader asks, with a model it downloaded
once, and keeps the text on the row. It is the shape of voice-note transcripts (ADR 2026-10.am8d): derived on tap,
kept, never carried.

- **Engine: Mozilla's Firefox Translations models.** These are Marian "tiny" student models, int8 on the CPU, about
  20–35 MB per direction. The models, the bergamot-translator code and the training pipeline are all MPL-2.0. Firefox
  ships them, and an F-Droid app (`dev.davidv.translator`) runs them offline on Android, so phones are known to
  carry them. bergamot-translator brings the whole pipeline: SentencePiece tokenising, the lexical shortlist, beam
  search and sentence splitting. That is the reason ADR 2026-10.nz3g took a runtime that carries its pipeline over
  writing log-mel, decoding and a tokenizer in Kotlin on LiteRT, and the same reason holds here.
- **Through English.** Mozilla trains pairs with English on one side. A pair without English (Spanish to German)
  runs two models, Spanish to English then English to German: twice the time and two downloads.
- **One direction per download.** A direction is fetched on the reader's tap from getknit.app, file by file, each
  file pinned by size and SHA-256. It goes through `Network.openConnection` on `AndroidInternetGate.currentNetwork()`,
  as the speech model does (`SttModelInstaller`). A script derives the pins from a pinned Mozilla model release, as
  `scripts/stt-model.py` does for speech, and it is the only place the pins come from. The offer names the size
  and says so on a metered route. The files are redistributed unmodified, and MPL-2.0's file-level copyleft is met
  by linking Mozilla's source for them from the download page.
- **On tap, never on arrival.** Translate sits in the message's action list, below Reply and Copy. Translating on
  arrival would spend CPU, on the inbound path, on every message from someone writing in another language, read or
  not.
- **Offered by language identification.** A small identifier, shipped in the APK or fetched with the first
  direction, decides whether to offer Translate and which source model applies. The action is offered on a message
  this phone did not write whose text is not in the target language. A short line is where identifiers guess, so a
  low-confidence answer asks the reader which language it is rather than guessing.
- **Into the app's language.** The target is the language the app is shown in. Where no model exists for that
  language, nothing is offered.
- **Kept on the row.** The translated text, its source and target languages and its moderation verdict are kept on
  the message's row in the encrypted database (a schema bump past `KNIT_DB_SCHEMA_VERSION`). It is keyed by the
  message, not shared across rows as a voice note's transcript is by attachment hash. A row whose stored target is
  not the app's current language offers Translate again. It goes with the message and rides a backup with it, as
  the voice transcript does. `messages_fts` does not index it.
- **Derived, never carried.** Nothing goes on the wire and there is no capability bit, so the author never learns
  that a message was translated and an older build sees nothing different.
- **Screened like the body.** The receiver classifies every incoming body by scope
  (`InboundPipeline.inboundModeration`, through `ScopedTextModerator.classify`). The word lists and the toxicity
  model read English, so a slur in Spanish passes them, and its English translation would not pass. The
  translation goes through the same `ScopedTextModerator`, in the message's scope. A flagged translation is
  collapsed by the same Content filtering switch, read beside `MessageEntity.hiddenByFilter`. A message already
  hidden by the filter offers Translate only after the reader has revealed it.
- **Leased.** The engine sits on a `ModelLease`, is built under `ModelLoadGuard` as a kind of its own and is let go
  when idle. One translation runs at a time, and a second tap on a message already queued is ignored.
- **Behind a flag.** The feature gets a build flag of its own in the shape of ADR 2026-10.swvp: on in debug, off in
  a shipped artifact, overridden by its own `-P` and read once in `di/`. Its runtime is `compileOnly`. Release
  packages none of it until the runtime comes from a Maven artifact F-Droid's rebuild can take and a device trial
  has passed.

**The open question: packaging the runtime.** There seems to be no maintained Maven artifact of
bergamot-translator for Android, and a release build compiles no native code (`.agents/context/distribution.md`:
no NDK on the APK path). Two routes:

- **Our own AAR.** Build bergamot-translator into an AAR in a separate repository, reproducibly, 16 KB-aligned for
  arm64-v8a, armeabi-v7a and x86_64, and publish it to Maven Central. This is the engine Mozilla ships and tests,
  shortlist included. Its cost is that we then own a C++ build of Marian.
- **ONNX Runtime.** Run int8 ONNX conversions of the same models (a third-party one is 32 MB for English to
  French) on `onnxruntime-android` (MIT, Maven Central). We write no native build, but we do write SentencePiece
  tokenising and the decode loop in Kotlin, and we lose the shortlist. moonshine-voice packages its own slim ONNX
  Runtime, so a build with both must not collide on a library name.

**Rejected.**

- **ML Kit's on-device translation.** It is closed source, and it fetches and manages its models through Google's
  services. This app does not ship GMS (ADR 015's rule), and F-Droid would refuse it.
- **A small LLM** (Gemma-class, through LiteRT-LM). Hundreds of megabytes, a LiteRT past the 1.4.x line
  `gradle/libs.versions.toml` pins, slow on a 32-bit phone, and an LLM paraphrases where a translation model
  translates.
- **Full OPUS-MT models.** About 75 MB per direction even at int8, and no shortlist.
- **Translating on the sender.** It would put machine text under the author's signature, and the sender cannot
  know who will read the message, or in what language.
- **Translating on arrival.** See "On tap, never on arrival" above.

**What the spike must show** before this is accepted. The pair is English and Spanish, both ways, behind the debug
flag.

1. **Speed and memory.** On a recent Pixel and on the lab's slowest phone: model load time, resident memory while
   held, and the time to translate a one-line message and a 500-character one. A one-line message should take well
   under a second on arm64.
2. **The runtime.** Which of the two routes, and its size per ABI, with 16 KB alignment checked with `readelf -lW`.
3. **The identifier.** One whose licence is compatible with GPL-3.0 and that is right on short chat lines. fastText's
   `lid.176.ftz` is about 1 MB but is CC BY-SA 3.0, which is not compatible with the GPL. CLD3 (Apache-2.0) is a
   candidate.
4. **Quality on chat.** Slang, emoji, a line of three words, a `geo:` line and a link. The last two must come back
   untouched.
5. **Calls.** Whether a translation during a live call costs the call any audio.

**What it costs.**

- **Size.** 20–35 MB per direction downloaded, and two directions for a pair without English. The runtime adds to
  the APK of every build that packages it.
- **Coverage.** Mozilla's list of languages, not every language. Languages with little training data translate
  worse.
- **Wrong translations.** A machine translation can be wrong. The bubble labels the text as a translation, and the
  original stays one tap away.
- **Notices.** When the feature ships in release, the runtime needs a row in the notices table and the in-app
  licence list (ADR 2026-09.6eb6).
- **Egress.** A fresh install sends no byte over IP: nothing is fetched until the reader taps.
