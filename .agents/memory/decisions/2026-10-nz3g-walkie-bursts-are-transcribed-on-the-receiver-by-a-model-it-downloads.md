---
id: "2026-10.nz3g"
slug: walkie-bursts-are-transcribed-on-the-receiver-by-a-model-it-downloads
title: "Walkie bursts are transcribed on the receiver by a model it downloads"
date: 2026-10-09
topics: [calls, voice, ml]
---

# ADR 2026-10.nz3g — Walkie bursts are transcribed on the receiver by a model it downloads

Status: Accepted (2026-10-09)

**What was observed.** A walkie burst (ADR 2026-10.79yb) plays once, from the loudspeaker. Someone who missed it,
who can't hear it in a loud place, or who can't hear at all is left with nothing, and the call screen had no way to
show what was said. The audio is all on the receiver already: `TalkPlayer` decodes it to PCM just before the track.

**What changed.** The receiver writes each burst out as text after it has played (`docs/VOICE_CALLS.md`
C-3.11-9). It is debug-only, as the walkie is, and nothing reaches the wire.

- **Model.** The model is Moonshine tiny EN v2 (int8 ORT, MIT, 44 MB), run through sherpa-onnx 1.13.8 as a
  `debugImplementation` from JitPack, with an exclusive filter for its one group.
  - Whisper tiny was the first thing tried, and it is the wrong shape for push-to-talk. It pads every input to
    30 s, so a 3 s burst costs as much as a 30 s one.
  - Moonshine's cost tracks the audio. On the x86_64 emulator a 3.8 s burst took 375 ms end to end, and that
    included building the recognizer. A 36 s stretch took 571 ms.
  - Accuracy at our 8 kbps Opus was the same as on clean audio for intelligible speech (desktop A/B on
    LibriSpeech-style clips).
- **Engine.** Running the model on the LiteRT runtime we already ship would have meant writing log-mel, decoding
  and the tokenizer in Kotlin, and Whisper exports often need the Flex delegate as well. sherpa-onnx carries all
  of that.
  - Its `.so` files are 16 KB-aligned, with RELRO filling the first RW LOAD. On a ps16k image,
    `pageSizeCompat` reads 0.
- **Download.** The model is not in the APK. It is downloaded on the user's tap.
  - It is fetched from getknit.app file by file. Each file is pinned by size and SHA-256 in `SttModel`, so the
    host is trusted with nothing.
  - The download runs through `Network.openConnection` on `AndroidInternetGate.currentNetwork()`, not okhttp.
    The host is fixed and serves pinned bytes, so it needs none of the preview fetcher's hostile-URL guards, and
    `rules/mesh.md` still has exactly two `okhttp3` importers.
  - `scripts/stt-model.py` derives the pins from the pinned upstream archive and stages the files for upload. It
    is the only place the pins come from.

**What it costs.**

- **Size and memory.** The debug APK is about 32 MB larger per ABI (uncompressed `.so`). While held, the recognizer takes about 60 MB of
  native heap (192 to 132 MB on unload, emulator-measured). It sits on a `ModelLease` that lets go when the walkie
  session ends (`WalkieTranscriber.release`, queued behind the session's last finish and stream closes, since a close
  after the unload would load the model again) or after 2 idle minutes, and is built under `ModelLoadGuard` as `stt`.
- **The ~9 s limit.** Moonshine v2 returns *nothing* for input longer than about 9 s (540 encoder frames). It
  fails silently: an empty string, not an error. `segments` therefore cuts a long burst at its quietest 50 ms
  between 5 s and 8 s, and the parts are transcribed separately.
  - Never raise `SttPolicy.SEGMENT_MAX_MS` past 9 s.
  - Never hand the engine a whole burst.
  - `BurstPcmTest` and `WalkieTranscriberTest` pin the cut.
- **A spent lease.** A lease whose load failed stays spent for the process. `SherpaSpeech` makes a fresh lease
  when the store's `generation` moves, so a reinstall gets its own try.
- **What it does not cover.** English only. Release builds have none of this.
- **Amendment 2026-10-09: the sender's own bursts.** Each burst spoken is also transcribed, from `TalkRecorder`'s PCM
  before the encoder, as a `mine` line in the same transcript (a second open slot: talking holds the playback
  mid-burst, it does not end it). It is there to measure: the sender's line against the receiver's for the same
  burst shows what 8 kbps Opus costs the model, apart from the model's own errors. The bridge's `loop` op writes
  both lines from one file.
- **Amendment 2026-10-09: Moonshine base beside tiny.** On the phones, the sender's clean line was only a little
  better than the receiver's, so the codec was not the bottleneck; the model was. Moonshine base EN v2 (int8 ORT, MIT,
  same package family) is offered beside tiny on the card's picker (`SttModel.ALL`, the pick kept in
  `models/stt-model`, each model in its own directory). It costs 141 MB to fetch and about 170 MB of native heap while
  held (emulator: 208 → 35 MB on unload), against tiny's 44 MB and 60 MB. It shares the ~9 s empty-output limit (an
  ORT broadcast error inside sherpa's v2 decoder, swallowed as an empty result), so the same ≤ 8 s cut applies. On the
  desktop it read clean LibriSpeech as well as or a little better than tiny, but returned nothing on an old, noisy
  recording that tiny handled, so it is a choice, not a replacement. Its files go under
  `https://getknit.app/models/stt/moonshine-base-en-2026-02-27/` (`scripts/stt-model.py --model base --out`).
- **Amendment 2026-10-09: Whisper small.en as a third pick.** Base on the P3 and the Moto G was better, but not good
  enough. Whisper small.en (int8 ONNX, sherpa-onnx's 2024-07-13 package; MIT, its licence fetched from OpenAI's
  repository at a pinned commit, since the package carries none) is the third choice: `SttModel.engine` says how the
  files are wired, and each model carries its own cut (`SttModel.segmentMaxMs`; Whisper reads 30 s, so 20–28 s, and
  every stretch costs a full 30 s encode however short). It read every desktop and emulator clip best, including the
  noisy one base returned nothing for, and it named a Spanish clip `[SPEAKING SPANISH]` instead of inventing English.
  The cost is 375 MB to fetch, ~920 MB of native heap while held (emulator), and 1.5–3.5 s a burst at emulator
  (desktop) speed. That is far past what a release could ship to a budget phone; it is here to learn what accuracy
  the walkie needs.
- **Hosting.** The files must be uploaded, unchanged, to `https://getknit.app/models/stt/moonshine-tiny-en-2026-02-27/`.
  Until they are, the download fails with "answered 404". The bridge's `STT --es op sideload` installs from pushed
  files through the same pins.
- **Amendment 2026-10-14: streaming, through Moonshine's own runtime.** Whisper small.en read well on the Pixel 9, but
  its text came only after the release, and every pass costs a full 30 s encode however short the audio, so there is
  no cheap way to show words while a burst plays. Moonshine v2's *streaming* models are built for exactly that: the
  encoder caches what it has seen, the library's voice detector closes a stretch at each pause, and a look costs the
  new audio and one decode. They also read better: Small Streaming scores 7.84 % on the Open ASR average against
  Whisper Small's 8.59, and Medium 6.65 % against Whisper Large v3's 7.44 (Moonshine's own table, float reference; the
  shipped int8 files are within 0.31 % on LibriSpeech-clean). So:
  - **Models.** The picks are Small (default, 142 MB), Tiny (45 MB) and Medium (269 MB) Streaming EN, Moonshine's own
    files from its CDN's `quantized_26_08_21` directory (the per-channel quantization; the sherpa-onnx exports we used
    predate it, and Moonshine measured the old scheme at 7.57 % against 4.83 % for Tiny on LibriSpeech-clean). The
    Whisper, Moonshine tiny and base offline picks are gone, and with them `segments` and its ≤ 8 s cut: the stream's
    own voice detector cuts at pauses, at most every 15 s.
  - **Runtime.** sherpa-onnx has no streaming Moonshine, and keeping it beside `moonshine-voice` (Maven Central,
    0.1.5) would put two different `libonnxruntime.so` in one APK. sherpa-onnx is removed; `moonshine-voice` is taken
    with `isTransitive = false` (its appcompat, material, okhttp and WorkManager serve wrappers we never call) and
    called through its `JNI` class alone (`MoonshineSpeech`), so no thread or cadence of the library's runs. Its
    native half binds by name, hence `src/calls/keepRules/moonshine.keep`.
  - **Flow.** `WalkieTranscriber` opens one `SpeechStream` per burst at its first audio, feeds it as the tap delivers,
    and looks on `PassGate`'s cadence — every 500 ms of new audio, or as much audio as the last look took, up to 5 s
    (the library's own `isUpdateDue`, so a slow phone batches instead of falling behind). The line carries the words
    so far (`TranscriptLine.live`, dimmed and not read out) and settles on the stream's last look at the burst's end.
    Every stream call runs inside the lease's `use`, so the idle reaper never frees the model under a burst, and a
    stream whose model was freed or replaced fails instead of reaching a dead handle.
  - **Measured** (x86_64 emulator on a 16 KB-page image, `…debug.STT`): Small loaded in 345 ms; a 10 s clip streamed
    at full speed took 2.3 s of model time over 19 looks and read verbatim; a 44 s one 22.6 s over 88 looks, and through
    the `loop` op's 8 kbps Opus in real time it read near-verbatim, the received line's final text 2–3 ms after the
    burst ended. Tiny took about half Small's time. On phones (`pcm`, Small, the same clips): the Pixel 9 Pro XL loaded
    it in 314 ms and spent 2.7 s and 23.8 s over the 10 s and 44 s clips, the Pixel 7 586 ms, 3.4 s and 27.3 s — both
    near 60 % of real time at two looks a second, both verbatim, the last look 1–5 ms. Still to measure: live bursts
    phone to phone, and the P3 and Moto G, against the Whisper build this replaces.
  - **Hosting.** As before, each model's nine files must be uploaded unchanged to
    `https://getknit.app/models/stt/<model id>/` (`scripts/stt-model.py --out`); until then the card's Download answers
    404 and `…debug.STT --es op sideload` installs from pushed files through the same pins.

- **Amendment 2026-10-14: one line a burst.** The model's voice detector no longer splits a burst at its pauses
  (`MoonshineSpeech.splitAtPauses`, off; `vad_threshold=0`, a line up to 30 s): the talk button already bounds the
  speech, and a split decodes each half without the other. On the phones, Small, the noisy 10 s clip read 16.7 % word
  error split and 0 % whole; the cost is 1.5–2.3× the model time and 170–390 ms more from a burst's end to its text.
  The 44 s clip ran 104–157 % of real time whole and lost its last words at the 30 s cut (cause not yet known).
  `…debug.STT --es op split --ez on true` brings the split back for an A/B.
- **Amendment 2026-10-14: clips for the lab's eval.** To score the models on real conversations rather than read
  clips, a debug build has a Diagnostics switch, "Save walkie clips" (`SettingsStore.debugWalkieClips`, off by
  default, out of backups by its `debug_` prefix). While it is on, every burst the transcript finishes, heard or
  spoken (a slipped thumb and a dropped burst excepted, one heard with no model included), is handed to
  `WalkieClips` with the 16 kHz mono audio the model got and its final line; `WalkieClipFiles` writes a WAV and a JSON
  sidecar (the line, its text included, and the model, the split setting, the call id and the mic's suppressor) to the
  app's external files, unencrypted so `adb pull` reads them, capped at 256 MiB. This is the one place the
  transcript's text, or its audio, is written down, and only a debug build, on the user's switch, does it; release has
  no switch and its store key reads false. `…debug.STT --es op clips` reports, sets and clears them, and `pcm` reads a
  clip's WAV back.
- **Amendment 2026-10-14: the hybrid, tried and not taken.** The proposal was to keep a split transcriber's cheap looks
  for the live words and decode the whole burst once at the release for the text. The runtime sets the voice detector
  per transcriber, not per stream (and keeps one encoder state per transcriber), so the hybrid is two transcribers over
  the same files, both fed every burst, the whole-burst one with `decode_incomplete_lines=false` so its looks only
  encode. `MoonshineSpeech.lines` (`LineMode`, replacing the split boolean) has it as `HYBRID`, beside `WHOLE` and
  `SPLIT`. Measured with `pcm` on the Pixel 9 and 7 (Small, six clips, then the noisy 10 s clip three rounds with the
  modes alternating, both phones dozing): it read exactly as `WHOLE` did, and cost more on every count — native heap
  633 against 452 MB (P9) and 621 against 350 MB (P7); model time 132 % and 122 % of real time against 101 % and 115 %;
  and 2.1 s and 2.0 s from the release to the text against 0.75 s and 1.8 s, because its one decode starts from
  nothing where `WHOLE`'s last look verifies its previous guess in one pass. `SPLIT` stayed cheapest (93 % and 83 %,
  under 10 ms to the text) and worst on noise. `WHOLE` stays the default and `HYBRID` was removed; `LineMode`
  keeps `WHOLE` and `SPLIT` (`…debug.STT --es op lines`). Both `WHOLE` and `HYBRID` drop the 44 s clip's last words at the
  30 s line cut, where `SPLIT` keeps them; that is the next thing to chase.
- **Amendment 2026-10-14: the names a call carries.** Proper nouns are where the model fails most, and Moonshine's
  streaming models take a list of key terms at runtime, nudging the decode towards them (a bonus on the logits along
  each term's subword path, `keyterm_boost` 2.0 by default; at that strength Moonshine measured about a quarter fewer
  errors on the terms for at most a quarter of a point elsewhere, and a hundred listed terms nobody says costing half
  a point). As a call begins, `SttKeytermFeed` reads the peer's and this phone's names, the latest twenty threads'
  names (DMs and groups) and the thread with the peer's last hundred messages, and `sttKeyterms` ranks them in that
  order — a multi-word name with its first word too, the messages' contribution being their capitalised mid-sentence
  words by frequency, at most twelve — cleaned to letters, digits and the marks names use (one term the tokenizer
  cannot spell fails the whole list, and a comma splits one) and capped at forty. `MoonshineSpeech` hands the list to
  the model as a burst opens, when it differs from what the model has (a load starts it empty); the call's end clears
  it. Held in memory only, never logged. The runtime's `set_context` would pick terms from a passage by the model's own
  tokenizer, but it *replaces* the key terms rather than adding to them (only the load options combine the two), so
  the merge is ours. On the phones (`pcm`, Small, whole lines): forty Irish and English names left the Beckett and
  noisy Dickens clips exactly as they were, so no false hits; and listing "incredulity" did not turn the noisy clip's
  "insecurity" back, the bias at its default not outweighing what the audio sounded like. Repeat runs of one clip at
  one setting also differ by up to four points of word error, since the cadence of looks moves the speculative
  decode, so any gain from the names has to be scored on many real bursts (the clip switch) rather than one.
- **Amendment 2026-10-14: Small alone, and a download that outlives the call.** Moonshine Small Streaming is the
  walkie's one model (`SttModel.MOONSHINE_SMALL_STREAMING_EN`). Tiny read too poorly to keep, and Medium cost twice
  Small's fetch and model time for a gain the clips have not shown, so the card's picker, `SttModel.ALL`, the
  `models/stt-model` pick file and the bridge's `use` op are gone. `SttModelStore.start` deletes whatever else sits
  under `noBackupFilesDir/models`, which is the other picks' files and the pick itself.
  <!-- doc-lint: allow MOONSHINE_SMALL_STREAMING_EN — the 2026-10-14 pick, superseded by the 2026-10-15 Medium amendment -->
  The download could only be started on a walkie call, from the card. It is now also a row in Settings ("Walkie
  transcripts": Download, progress and Cancel, Try again, Remove), shown only where `WALKIE_WIRED` is on, through
  `SettingsViewModel`'s nullable store. Either door starts the same job on the app scope. As it starts, the store asks
  its `keepAlive` to start `SttDownloadService`, a `dataSync` foreground service declared in the calls manifest. That
  way neither a hang-up nor a screen left behind, nor the cached-app freezer when the mesh is stopped, stalls the
  fetch half way. The service does no work: it shows the progress and Cancel in a low-importance notification and
  stops as soon as the store's state is anything but `Downloading`. The store sets that state before it asks, so the
  service never reads the state it would stop on. On Android 15's `dataSync` time limit the service cancels the
  download, and the next tap fetches only the files still missing. A start the platform refuses (Android 12+, from
  the background, so only the debug bridge's) is logged, and the download runs without the service.
- **Amendment 2026-10-15: the Calls screen, a switch, and an offer that closes.** The speech model's row moved from
  Settings to the Calls screen (`ui/callsettings/`), beside the call servers, under a "Walkie transcripts" switch
  (`TranscriptStore.enabled`, on by default, riding a backup; a store of its own beside `OwnIceStore`, as
  `SettingsStore` is already past detekt's size limit). Off, `WalkieTranscriber` takes no burst: its
  `enabled` is asked as each burst begins, and a burst begun while off still clears the slot, so the one before never
  takes its audio. The call screen draws no card then. The model row stays while the switch is off, so a model can be
  removed or fetched ahead. The walkie card's offer (Download or Try again) gains Dismiss, which writes
  `TranscriptStore.offerDismissed` for good: while the model is absent no card is drawn on any later call,
  and the download is the Calls screen's alone. Once the model is in, the card is the transcript, and the switch is
  how it goes away. The dismiss is a button beside Download rather than a close in the card's title, because a 48 dp
  title row pushed the first line off a 360 × 640 screen (`WalkieTranscriptScreenTest`).
- **Amendment 2026-10-15: Medium after all.** The walkie's one model is now Moonshine Medium Streaming
  (`SttModel.MOONSHINE_MEDIUM_STREAMING_EN`, id `moonshine-medium-streaming-en-2026-08-21`, 245M parameters, 6.65 %
  on the Open ASR average against Small's 7.84), taken for its accuracy at about twice Small's fetch (269 MB against
  142 MB) and model time. Nothing else moves: the same runtime, the same per-channel quantization
  (`medium-streaming-en/quantized_26_08_21`), pins from `scripts/stt-model.py`, and files rehosted under getknit.app.
  A phone holding the Small model has it swept by `SttModelStore.start` (it is not the shipped id) and sees the model
  as absent until the Medium one is fetched.
- **Amendment 2026-10-16: live calls are captioned too, split at pauses.** A live call's screen gains a CC button
  (Mute, Captions, the audio route, then End, in one row so the caller's card keeps its room), off by default and
  remembered from call to call (`TranscriptStore.liveCaptions`), drawn only while the Calls screen's switch — now
  "Transcripts and captions" — is on. On, `LiveCaptions` fills the card slot with the peer's words as they play
  (docs/VOICE_CALLS.md C-3.7-5); the walkie's card and the live captions share the line rendering (`Streaming`, a
  line's settled words as they stay and the rest dimmed, `TranscriptLine.settled`) and the model panel. The audio is
  the received track's, through `AudioTrack.addSink` on a sink `WebRtcSession.listen` adds under a lock of its own and
  takes off before `pc.dispose()`; `CallCaptioner` takes the newest session's `source()` alone, begun and ended with the media's
  first and last session. A live call's streams always split at pauses (`SpeechToText.open(continuous = true)`;
  `MoonshineSpeech` keys its lease on the mode, so a walkie after a live call reloads the model): `WHOLE` re-decodes
  the open line on every look, which a walkie bounds by its talk button and a live call cannot, and the 2026-10-14
  measures had `WHOLE` at 101–132 % of real time on Small against `SPLIT`'s 83–93 %. Each run ends at the first look
  that finds every stretch closed, its words a settled line and a fresh stream taking what follows
  (`CaptionPolicy.step`); a run silent for 30 s is dropped, one talking at 60 s finished where it stands. Captions stop
  for the call, with a note, when over 8 s of audio waits on an open stream (`CaptionStatus.BEHIND`) or the model will
  not run (`FAILED`); CC off and on retries. The phone's own voice is not captioned: `setSamplesReadyCallback` hands the
  recorded PCM before WebRTC's echo canceller, so on the loudspeaker (and on the `VOICE_RECOGNITION` profile, ADR
  2026-10.kw29) it carries the peer's words too, and a second stream would halve the cadence. On the earpiece the card
  says captions read better on the loudspeaker; the model runs on regardless. The STT graph moved from `WALKIE_WIRED`
  to `CALLS_WIRED` (the runtime was already packaged wherever calls are).
- **Amendment 2026-10-16: Small for everything, after the live-call trial.** The one model is Moonshine Small Streaming
  again (`SttModel.MOONSHINE_SMALL_STREAMING_EN`, 142 MB), for the walkie and a live call's captions alike: Medium could
  not caption a live call on the lab's two oldest phones. The trial fed a 10-minute speech track (about 65 % talk, real
  pauses) into one phone's call microphone (`…debug.CALL --es op capture --es pcm`, `MicFeed`) with the other muted and
  captioning. On Medium the Moto G (GSI, API 34) stopped `BEHIND` 25–30 s into the first unbroken stretch, twice and at
  27 °C — the model ran at about 130 % of real time split at pauses — and the Pixel 3 kept up at 82 % until it heated
  from 33.6 to 36.2 °C and stopped at seven minutes. On Small both captioned the whole ten minutes with no stop: the Moto
  at 75 % of real time (27.0 → 28.5 °C) and the Pixel 3 at 74 % (30.4 → 36.8 °C, 7 % of its battery over the
  11-minute call). The text read about as well as Medium's; the one noisy clip garbled on both. Whole-line streams on
  the `pcm` bench ran at 236 % (Moto) and 444 % (P3) on Medium against split's 132 % and 198 %, which is why a live
  call splits. A phone that held the Medium model has it swept by `SttModelStore.start` and fetches Small. The walkie
  gives up 1.2 points of word error on the Open ASR average (6.65 → 7.84 %) for one model that keeps up everywhere it
  was tried. The trial also found two deadlocks in `WebRtcSession`, fixed: a stats read held the session's lock across
  `pc.getStats`, which waits on the signaling thread, where both the captions' `listen` and the existing
  `onIceCandidate` took that lock. Calls into the connection now hold `pcLock`, which the signaling thread never takes.

## Amendment (2026-10-18): speech has its own flag

The speech graph moved again, from `CALLS_WIRED` to `STT_WIRED` (`BuildConfig.STT`, `-Pstt=`), which is on only where
a call kind is (ADR 2026-10.swvp). Moonshine, `SttDownloadService` and `FOREGROUND_SERVICE_DATA_SYNC` are packaged from
`src/stt/` only there, and `moonshine.keep` moved there with them. The transcriber is built only with the walkie, the
captioner only with live calls.

## Amendment (2026-10-21): chat voice notes use the same model

A chat voice note can now be written out with this model on the person's tap. The text is kept on the message row,
unlike a walkie burst's (ADR 2026-10.am8d). It shares the model, its download and the Calls screen's switch, and a
call always comes first: no note runs while a call is up.

