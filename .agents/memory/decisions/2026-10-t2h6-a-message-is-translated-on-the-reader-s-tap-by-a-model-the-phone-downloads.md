---
id: "2026-10.t2h6"
slug: a-message-is-translated-on-the-reader-s-tap-by-a-model-the-phone-downloads
title: "A message is translated on the reader's tap by a model the phone downloads"
date: 2026-10-22
topics: [ml, ui, moderation, privacy]
---

# ADR 2026-10.t2h6 — A message is translated on the reader's tap by a model the phone downloads

Status: Proposed (2026-10-22). The English ↔ Spanish spike ran on 2026-10-22 on the Pixel 7 and Pixel 9, a debug
build made with `-Ptranslate=true` (results at the end). It answered the six questions below; what is left before
acceptance is listed with them. No wire byte moves.

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
  this phone did not write whose text is in none of the languages the reader reads. A short line is where
  identifiers guess, so a low-confidence answer asks the reader which language it is rather than guessing.
- **Into the reader's language, not the app's.** The language the app is shown in says little about what its
  reader reads. Everyone who installs the APK directly, F-Droid's users included, sees English, because the
  repository carries English strings only. Google Play translates the app's strings into other languages by itself,
  so a Play install can show any of those. Someone may run the app in English and write in Spanish, or the other
  way round. The reader's languages are the ones they write in, read from their own sent messages by the same
  identifier, together with the phone's preferred languages: the system's list, not the app's resolved locale. The
  target is the language they write in most, else the first preferred language that has a model, and a setting
  overrides both. Where no model exists for the target, nothing is offered.
- **Kept on the row.** The translated text, its source and target languages and its moderation verdict are kept on
  the message's row in the encrypted database (a schema bump past `KNIT_DB_SCHEMA_VERSION`). It is keyed by the
  message, not shared across rows as a voice note's transcript is by attachment hash. A row whose stored target is
  not the reader's current language offers Translate again. It goes with the message and rides a backup with it, as
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
- **Translating on the sender, silently.** It would put machine text the author never read under their signature,
  and the sender cannot always know who will read the message, or in what language. The amendment below keeps the
  sender's translation and drops both objections: the author reads, and may edit, the text before it is sent.
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
6. **The reader's language.** Whether the language read from a reader's own sent messages names the language they
   want a translation in, including for someone who runs the app in English and writes in Spanish.

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

## Spike results (2026-10-22)

The spike translates into the pair's other language whatever the app shows, so both directions run on one phone. The
corpus is `scripts/translate-corpus/` (91 Spanish and 88 English chat lines); `scripts/translate-bench.py` drives the
debug bridge's `TRANSLATE bench` and compares each phone with the same native core built for the desktop.

- **Runtime: slimt, not bergamot-translator or ONNX Runtime.** Mozilla archived bergamot-translator in 2023, and the
  F-Droid Offline Translator moved from it to slimt. `onnxruntime-android` ships a `libonnxruntime.so` that collides
  with the one moonshine-voice packages in every debug build. The spike builds David Ventura's slimt fork (GPL-2.0-or-
  later, pinned) and CLD2 from source in a `:translate-native` module that only `-Ptranslate=true` includes. The
  stripped library is 4.4 MB on arm64-v8a and 3.4 MB on armeabi-v7a, 16 KB-aligned. A release still needs it as an AAR
  from a reproducible build on Maven Central; that is not built.
- **Models.** Mozilla's current en-es and es-en are "base-memory": 31.6 MB of weights plus a 4.2–4.6 MB shortlist and
  a 0.8 MB vocabulary per direction, MPL-2.0. slimt must be told 4 decoder layers. The older "tiny" models can no
  longer be fetched (their Git LFS objects answer 404).
- **Speed.** A cold load takes 0.2–0.45 s. Per message, p50/p95:

  | Phone, process state | short (≤ 40 chars) | medium (≤ 160) | long (~500) |
  |---|---|---|---|
  | Pixel 7, chat open | 13–14 / 23–25 ms | 16–19 / 26–33 ms | 81–82 / 92–105 ms |
  | Pixel 9, foreground service | 25–32 / 37–60 ms | 36–38 / 45–50 ms | 157–158 / 175–185 ms |
  | Pixel 7, cached in the background | 156–159 / 248–312 ms | 224–259 / 378–441 ms | 2.3–2.4 s |

  A background process runs on the little cores; a tap comes with the chat open, which is the first row.
- **Memory.** One loaded direction adds 75–90 MB of native heap over its mapped weights: slimt concatenates some
  weight matrices at load and keeps ruy's packed copies. The lease lets it go after two idle minutes.
- **A slimt trap.** slimt keeps ruy's packed weights in a per-thread cache keyed by address and never clears it for a
  model's weights, so a model freed and loaded again at the same address on the same thread translates with the old
  model's packs: the emulator wrote "sssssssSSSS" for "¿Vienes al parque más tarde?". Each loaded model now lives on a
  thread of its own that ends with it (`SlimtEngine`). That also halved the emulator's times.
- **Quality.** Ordinary chat text reads well both ways; slang does not ("qué pedo wey" came back as "what fart I'm").
  Links, positions, mentions and emoji came back unchanged on all 179 lines on both phones. One English line looped
  ("congrats!! so happy for you" as twenty-three "¡"), and `TranslationCheck` refuses it. The phones' output matched
  the desktop's except for small wording differences on four lines.
- **The identifier.** CLD2 (Apache-2.0, what Firefox uses) named a language for 80% of the corpus and was never
  confidently wrong after one rule: an English answer over Spanish marks (¿ ¡ ñ, an accented vowel) is unsure. The
  other 20%, mostly lines of one to three words and Spanish read as Galician, Catalan or Portuguese, ask the reader.
- **Calls.** During a live Wi-Fi Direct call from the Pixel 7 to the Pixel 9, the Pixel 7 translated 3,640 messages
  back to back for 48 s. Neither phone's `call_media` samples moved: no loss, jitter 2–4 ms, 1,518–1,523 packets per
  30 s, before, during and after. The samples do not count concealment, and the rooms were quiet.
- **The reader's language.** On both lab phones the rule picks English (the Pixel 9's last 50 own messages: 39
  English, 1 Spanish, 10 unsure). It has not met a reader who writes in Spanish.

Before acceptance: the release packaging (the AAR), the models hosted on getknit.app, keeping a translation on the row,
a reader who writes in Spanish, and the lab's 32-bit phone.

## Amendment 2026-10-22: the sender translates the draft

**What was asked.** Someone who writes in Spanish to a group that reads English should not leave every reader to
translate each message. They know their readers' language; the receiving side cannot.

**What changed.** The composer translates the person's own draft before it is sent, in the same debug build.

- **One tap, then read it.** A translate button sits in the field, where the mic and paperclip sit on an empty draft.
  A tap replaces the draft with its translation and shows "Translated from Spanish · Undo translation" above the
  field. The person reads it, may edit it, and sends it as any message; Undo translation puts the original back. What
  is sent is what the field holds, so the send-side screen, the LoRa byte budget, quotes, mentions and the kept draft
  work unchanged. Nothing goes on the wire and receivers cannot tell: the author has read it and it is their message.
- **A hint, never automatic.** When the draft is confidently in one language of the pair and the chat in the other,
  the button is lit and the line above the field says so ("This chat is in English. Translate your message?"). The
  chat's language comes from other people's 20 newest messages, never this phone's own, and needs three confident
  reads and a majority (`ComposePolicy`). A draft is read only after it rests for 600 ms and holds at least 12
  letters outside its links and mentions. Picking a language the identifier could not tell, or downloading a missing
  model, uses the reading side's dialogs.
- **Shared path.** `MessageTranslator.translateNow` is the message path without the screen, queued on the same mutex:
  whole first, by pieces around links, positions and mentions when the whole lost one, and refused when it loops.

**What it costs.** The sender's phone needs the model too. A pair without English still needs two models. The draft
is translated as typed: missing accents and "¿" can lose a question ("Vienes al parque mas tarde?" became "Come to
the park later?"), which is why the person reads it first. Device-verified on the Pixel 7 on 2026-10-22: the hint in
an English thread, Translate, Undo.

## Amendment 2026-10-22: settling short lines

**What was asked.** With many languages, a short line is where the identifier guesses: every language added gives it
more neighbours to confuse, and "Which language is this?" with two buttons would become a long list.

**What changed.** A message's language is resolved in order, stopping at the first that answers, with no schema change
(`LanguageResolver`, `MessageTranslator.resolve`):

1. **The words**, read by CLD2, and only a language a model translates from counts (`TranslateDirection.sources`).
   A sure read of anything else (Spanish read as Galician) is no read at all.
2. **The author's language in this chat** (`AuthorLanguages`): a majority of at least three of their 30 newest
   messages there that the words alone could read. Per chat, because a person may write one language in one group
   and another elsewhere, and because the `(conversationId, kind, senderId)` index serves it. Held in memory for ten
   minutes; derived from rows the phone has, never stored, never sent. A draft's author is this phone.
3. **The chat's language**, from other people's 20 newest messages (the composer hint's `ComposePolicy`).
4. Only then is the reader asked.

The likeliest prior also goes to CLD2 as a hint, which tips a close call between neighbours without overruling plain
text: on the Spanish corpus an `es` hint turned every Galician, Portuguese and English misread into Spanish, and on
the English corpus a wrong `es` hint flipped no line (two went unsure instead).

**What it measured.** Over each lab phone's real messages from other people (`TRANSLATE audit`): the Pixel 7's 169
went from 69 unsure by the words alone to 5 (60 settled by their author, 3 by the chat), and the Pixel 9's 264 went
from 82 to 2. Of the lines of up to 20 characters, 62 of 84 and 75 of 100 were unsure by the words alone; 4 and 2
still are. The hint changed no confident read on either phone.

**What it costs.** A prior is a guess about the person, not the line: someone who writes English to a chat and sends
one short Spanish line ("ya voy") has it read as English. The audit counts coverage, not accuracy; the lab's traffic is
almost all English. So a translation whose language a prior settled, not the words, says so: "Translated from English
· Not English?" under the message, and the same over a draft, with Undo translation set apart at the end of the line
so it never reads as undoing the question. One tap translates it again from the other language, which the reader has
now named, so it offers no second guess (`MessageTranslation.Done.guessed`). With more languages that tap would open
the short picker instead. Device-verified on the Pixel 7: Walter's "ya voy", read as English by their history in that
chat, came back as "I'm coming" after the tap.

## Amendment 2026-10-23: the runtime is our own AAR

**Decided.** The runtime leaves this repository. The knit-translate-native repository builds slimt and CLD2 behind a
JNI into `app.getknit:translate-native` and publishes it to Maven Central. That build pins the NDK
(28.2.13676358), the SDK's CMake (3.31.6) and every source commit (`sources.lock`). A release has to come out byte for
byte the same from two checkouts in two directories (`scripts/check-reproducible.sh`), because Knit's release and
F-Droid's rebuild take the AAR's bytes from Central and compile no native code. The binding class moves into the AAR
as `app.getknit.translatenative.TranslateJni`, in Java so the artifact has no dependency, with its keep rule as a
consumer rule: the native symbols and the class that binds them are versioned together. `:app` compiles against it
everywhere (`compileOnly`) and packages it only under `-Ptranslate=true` (`runtimeOnly`), the shape `moonshine-voice`
already has. `-Pknit.translateNative=<checkout>` substitutes a local checkout through a composite build.

**Upstream sources.** Nothing upstream is committed there and nothing is a submodule. A pin moves by editing
`sources.lock`, after the desktop bench's translations of `scripts/translate-corpus/` are compared with the previous
version's (`scripts/translate-bench.py --desktop --native <checkout>`). Our own changes are `git format-patch` files
under `patches/<name>/`, never commits on someone's fork. Each is sent upstream first and deleted by the bump that
brings it in. A scheduled job reports each pin's distance behind the branch it follows. slimt comes from a personal
fork, so every source is fetched from a mirror before the first release.

**Published (2026-10-24).** 0.1.0 is on Maven Central (tag `v0.1.0`, AAR sha256 `58f60e56…`, the same bytes as
the local and CI builds), signed by key `857DFEFE992B2D43BE0F6DEB24AF442CC345A16D`. github.com/getknit/knit-translate-native
is the primary repository, and its GitHub Actions is the only CI that publishes. Every source is fetched from a
`getknit/mirror-*` repository. The SentencePiece pin `8cbdf137` is a google/sentencepiece master commit (v0.1.99-17),
not a browsermt one, so it follows Google's repository.

**The lockfile and the notices.** The lockfile is written without `-Ptranslate`, so it records the AAR on the compile
classpaths only. A build with the flag adds it to every runtime classpath, so the flag unlocks those, as `-Pstt=true`
already does for release. Its notices are `Packaging.TRANSLATE` rows: slimt (GPL-2.0-or-later, the "or later" coming from
slimt's `COPYRIGHT`), the bergamot-translator and marian-dev code it derives from (MPL-2.0), ssplit, ruy, SentencePiece
and CLD2 (Apache-2.0) and cpuinfo (BSD-2-Clause). They sit in their own section of `THIRD-PARTY-NOTICES.md`, and the
in-app list shows them only where `TRANSLATE_WIRED`. `ReleaseClasspathNoticesTest` finds their coordinate on
`releaseCompileClasspath`.

## Amendment 2026-10-29: languages are data, into the reader's language, kept on the row, declared on the frame

**What was asked.** Before more languages than Spanish can ship, the spike's one pair has to stop being the shape of
the code; a translation should survive the process; and the composer's hint and the read of a short line should hear
what the other side says about its own language rather than only guess it from words.

**What changed.**

- **Languages are data.** `TranslateLang` is a code from Mozilla's registry (`en`, `es`, in time `zh_hant`).
  `TranslateDirections.kt` is generated by `scripts/translate-model.py --write` from the script's `MODELS`, which
  record each model's architecture and so its decoder depth; `--check` byte-compares the file, and `--suggest <lang>`
  prints a new language's entries from the registry. A language's name comes from the platform's locale data, not a
  string per language: every shipped template already carries the name uninflected.
- **Every model has English on one side, and nothing pivots.** The "Through English" bullet's two-model route is
  dropped. A message goes into the reader's language when a model reaches it, else into English, the one language
  every model touches; a message already in the reader's language, or English for a reader no model reaches, has no
  target (`TranslateTarget`). A draft goes into the language the chat reads, else English, else the person picks.
- **The reader's language** is the choice in Settings › Chats ("Translate messages into", Automatic by default), else
  what they write (three confident picks of their 50 newest), else the system's list, read with
  `Resources.getSystem()`: `LocaleList.getDefault()` follows the per-app language that `generateLocaleConfig`
  offers, which says what the app is shown in. Only a language a model translates into counts.
- **Translate is not offered on a message already in the reader's language, nor on one in a language no model here
  translates from.** The menu resolves the row's language as it opens. It is hidden when the words, or the language
  the message was typed in with the words unsure, name a language with no target or no model (`LanguagePick.Other`),
  or when the row's kept translation is already in the target. A language only a prior settled keeps the offer: the
  reader may know better.
- **"Not English?"** offers the other languages a model translates from, CLD2's own reads first; with one left it
  translates at once.
- **Kept on the row**, as the bullet above proposed: DB v27 adds `translation`, `translationFrom`, `translationTo`,
  `translationFlagged` and `translationGuessed` to `messages`, written only by `MessageDao.setTranslation`, never by a
  whole-row save. The verdict is applied live against Content filtering, as `videoPosterFlagged` is. It rides a
  backup with the row and stays out of `messages_fts`.
- **Declared on the frame.** A sender says what the receiver cannot read off the message itself: the language they
  typed it in and the language they read. The receiver reads the words with its own CLD2, so the sender never spends
  bytes on that read. The receiver's translation is still never carried; this is the author's own statement.
  - **Fields.** `lang`, the language the message was typed in, and `reads`, the language the author reads, each an index into
    `LanguageRegistry`, an append-only list of every language in Mozilla's registry with the likeliest first.
    `lang` is left off for English or unknown, `reads` for the same as `lang`. A message in English by an English
    reader is the bytes it was. They ride under `"l"` and `"r"` on a room or commons post's `ChatContent` and on the
    sealed `MessageContent`, and under labels 18 and 19 in the compact v3 layout. A sealed frame's shell never
    carries them, and `plaintextContent` reads only the sealed plaintext's.
  - **Cost.** For a language among the registry's first 24, two bytes for one field on v3 (label and value) and
    three under a named key, four or six for both; a later language costs a byte more per field, so at most six on
    v3 and eight named. `MeshManager.inlineAcksFor` counts them against the LoRa budget at the named size; the
    composer's LoRa size hint does not (it cannot know the declaration while the person types).
  - **What a sender declares as `lang`.** The language the composer translated the draft into, when the field holds
    that translation. Otherwise the keyboard's language (`InputMethodManager.currentInputMethodSubtype`, no
    permission), sampled at each edit and weighted by the characters it added (`InputLanguageTally`). A keyboard is a
    strong hint: a draft typed on a Greek keyboard is very likely Greek. An edit adding more than 24 characters at once
    counts for no language: a paste, a shared text, a restored draft, an applied translation or a phrase of voice
    typing. The draft declares a language only when one keyboard language typed at least two thirds of what was typed,
    and typed characters could make up most of the message, so a mostly pasted message declares none. A keyboard with
    no language (hardware, some input methods) declares none. Any registry language counts, model or not.
  - **What a sender declares as `reads`.** The language they chose, which is evidence they read it: the "Translate
    messages into" choice, else the language chosen for Knit in Settings › Apps (`LocaleManager`, Android 13+), else the
    phone's first system language (`ReaderLanguages.declaredReads`). It is never the language Knit fell back to
    showing: a Greek phone shows Knit in English, since no Greek strings ship, but its reader chose Greek. It is also
    never the local estimate from what they write, which picks only the translation's target here. Any registry
    language counts.
  - **Who declares.** Only a build with translation wired, with the switch below on; every build reads them. Stored on
    the row as `bodyLang` and `authorReads` (DB v27, written once on insert).
  - **What a receiver does with them.** A declaration is the sender's claim: a label, never a moderation, trust or
    routing input. The resolver hands the typed-in language to CLD2 as its hint, which turns a neighbour's misread
    back into it. Words the identifier is still sure of win, in any registry language: English typed on a Spanish
    keyboard is English, and a sure read of a language with no model here is that language, with nothing to offer.
    Only with the words unsure does the typed-in language answer, before the author's and the chat's usual language,
    and a translation it settled is not marked guessed. The composer's hint reads what the chat's other
    people declared reading before it reads their words: in a one-to-one chat, the peer's newest message ("Ana
    reads Spanish. Translate your message?"); in a group, only when every author among the 20 newest declared, at
    least two of them, all the same language, since a member who declared nothing may read English or run a build
    that never declares.

**A switch for all of it.** Settings › Chats › Translation, on by default, governs the whole feature: off, no bubble,
menu or composer offers translation, no hint is drawn, the identifier reads nothing for it, and nothing a message
says about its language goes on the wire (`ChatViewModel.declaredFor` reads the switch at the send). A declaration
received is still kept on its row, which costs nothing and serves the reader the day they turn it back on. Its
subtitle says what is sent, so the switch is the person's say over that disclosure.

**What it costs.** `reads` tells the people a message reaches the language its author reads, which partly gives away
a translated draft (an English message from someone who reads Spanish), and `lang` the keyboard they typed it on.
A pasted message typed around in another language, a multilingual keyboard that reports one language, or Greek
written in Latin letters on an English keyboard can declare the wrong language; sure words still overrule it on the
receiver. In a sealed chat only its members see it; in
the Nearby room it is cleartext to every neighbour, as the body is. Knit for iOS ignores the keys until it reads them.
The draft hint for a group says nothing while one member is silent.

