---
id: "2026-10.am8d"
slug: voice-notes-are-transcribed-on-tap-and-the-text-is-kept-on-the-row
title: "Voice notes are transcribed on tap and the text is kept on the row"
date: 2026-10-21
topics: [voice, ml]
---

# ADR 2026-10.am8d — Voice notes are transcribed on tap and the text is kept on the row

Status: Accepted (2026-10-21)

**What was observed.** A chat voice note (ADR 034: AAC-LC in ADTS, up to five minutes) has no text. Someone in a
loud room, or who can't hear, gets nothing from it. The walkie's speech model (ADR 2026-10.nz3g, Moonshine v2 Small
Streaming) was already on the phone where speech is built, and the note's bytes are already held and decryptable on
both ends: `VoiceAudio` decodes them to PCM for the waveform.

**What changed.** A voice bubble offers Transcribe. A tap writes the note out on this phone, and the text is kept on
the row (`messages.voiceTranscript`, DB v23).

- **On tap, never on arrival.** A note is transcribed only when someone asks. Transcribing on arrival would load
  about 140 MB of model and spend up to the note's own length in CPU for every note, on the inbound path, whether
  anyone reads the text or not.
- **Derived, not carried.** As with the waveform, each phone derives the text from the bytes it holds: the sender
  from its own copy, the receiver from the one it pulled. Nothing goes on the wire and there is no capability bit,
  so an older build sees the same note it always did. `MessageDao.setVoiceTranscript` is keyed by attachment hash,
  so every row naming the note shows the same words.
- **Kept, unlike the walkie's text.** A five-minute note costs minutes of model time on a slow phone, so its text
  is stored in the encrypted database beside the body. It goes with the message and rides a backup with it. An
  empty string means the model heard no speech, and the bubble then says so rather than offering again.
- **Whole or split by length.** A note of up to 25 s (`VoiceNotePolicy.WHOLE_MAX_MS`) is one whole-line stream.
  Whole read best in nz3g's trials (the noisy clip: 0 % word error whole, 16.7 % split), but a whole line is cut at
  30 s and loses what follows. A longer note is opened `continuous`, in runs of up to a minute
  (`VoiceNotePolicy.step`: the live captions' `CaptionPolicy.MAX_RUN_MS`, and a run silent for 30 s is dropped), their
  words joined in order. It is never split at a pause, as a call's captions are. A note is looked at only every 2 s
  of audio, so the look that finds a pause comes after the next sentence has begun. Finishing the run there lost
  that sentence's first words: on the P9 a 75 s note split at pauses dropped "Which way to" and "Look for", and one
  unsplit stream heard them.
- **Coarser looks.** A note is fed faster than it plays, in 500 ms slices, with a look every 2 s of audio
  (`VoiceNotePolicy.LOOK_MS`). A whole-line look decodes the line again, so the walkie's half-second cadence would
  cost far more than it shows. The looks fill the bubble's words-so-far.
- **One at a time.** `VoiceNoteTranscriber` serialises the notes it is asked for and ignores a second tap on a note
  that is already queued.
- **A call comes first.** No note starts while a call is up (`NoteStt.Busy`). A call that begins mid-note stops it
  between slices, closing its stream and saving nothing. Two streams on one Moonshine handle are not a shape the
  runtime promises, and a call cannot wait on a note.
- **Letting the model go.** The model is released when the queue drains, but only while no call is up, since a
  call's own streams may be open on it.
- **The download.** A tap while the model is absent opens an offer naming its size and, on a metered route, saying
  so. Download starts the same `SttModelStore.download()` the Calls screen does, with the same service, and the
  note is transcribed once the model is `Ready`, if the chat is still open.
- **The switch.** The Calls screen's "Transcripts and captions" switch now covers voice notes. Off, no bubble draws
  a control or stored text.

The alternative a reader would reach for first is to keep the text in memory, as the walkie does. It was rejected:
the walkie's text is about a call that is over, while a note stays in its thread, and re-running the model each
time the chat opens would be the expensive path every time.

**What it costs.**

- **Scope.** It needs `STT_WIRED`, so it is in debug builds only, as speech is. Release ships none of it, and the
  bubble draws exactly what it did. English only.
- **Not screened.** The text is not screened (docs/CONTENT_MODERATION.md §7). It is this phone's own reading of
  audio the person chose to hear, and Nearby-room notes don't exist.
- **Not searchable.** `messages_fts` indexes bodies only. Searching transcripts is deferred in
  `.agents/memory/roadmap.md`.
- **Never logged.** The log line carries the hash prefix, the audio length, the mode, the look count and the time.
- **Peak memory.** A five-minute note's 16 kHz PCM is about 19 MB, held for the length of the pass
  (`VoiceAudio.speechPcm`, capped at the length the frame headers state).
- **Database version.** The lab's temporary v27 downgrade shim had to move with the bump: Room never descends past
  its target and comes back up, so it is now `LAB_DOWNGRADE_27_23`, and its body is v23's one step.
- **Regression tests.** `VoiceNoteTranscriberTest`, `VoiceTranscriptViewTest`, `MessageDaoTest`'s
  `setVoiceTranscript` case, and `KnitDatabaseMigrationTest`'s 22 → 23 and lab-shim cases. The screenshots are
  `VoiceNoteTranscribed`, `VoiceNoteTranscribing` and `VoiceNoteTranscribeOffer`.
