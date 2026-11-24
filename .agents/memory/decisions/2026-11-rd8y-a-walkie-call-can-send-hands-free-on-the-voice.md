---
id: "2026-11.rd8y"
slug: a-walkie-call-can-send-hands-free-on-the-voice
title: "A walkie call can send hands-free on the voice"
date: 2026-11-24
topics: [calls, voice]
---

# ADR 2026-11.rd8y — A walkie call can send hands-free on the voice

Status: Accepted (2026-11-24)

**What was observed.** A walkie session (ADR 2026-10.79yb, `docs/VOICE_CALLS.md` §3.11) sends only while the talk
button is held. With the hands busy, as on a bike, at the wheel, or with the phone on a table and earbuds in, the
call cannot be used. Radios solve this with VOX: the microphone listens, and the radio transmits while it hears a
voice.

**What changed.** The walkie screen has a Hands-free switch (`WalkieTalk.setHandsFree`, C-3.11-11).

- **Armed per call.** The switch is off at the start of every session and is never carried into the next one, so an
  answered call never starts sending the room. Arming keeps the microphone open for the session
  (`TalkRecorder.monitor`). `CallService` already holds the microphone's foreground state, so this works with the
  screen off. The ongoing call's notification says the microphone sends when the user speaks, on every Android
  version; the system's microphone indicator, from Android 12, stays on besides.
- **An energy gate over an adaptive floor** (`call/Vox.kt`: `VoxGate`, `SpeechFloor`, `VoxPolicy`). A frame counts
  as speech when it is a margin over the quietest frame of the last 2 s and above a least level, both measured after
  `TalkGain`, which brings speech toward −18 dBFS on every microphone path. The floor and the speech test are the
  ones `TalkGain` already used, now shared. 60 ms of unbroken speech opens a burst with up to 300 ms of the audio
  before it, and 800 ms without speech ends it. The Calls screen's Low, Medium and High set the margin and the least
  level (`CallPreferenceStore.voxSensitivity`).
- **Commit before send.** A burst is encoded from its first frame but handed to the walkie only once it holds 200 ms
  of speech (`TalkRecorder.Monitor.commit`). Until then it has no burst number, holds no playback and costs no radio
  byte, and a cough or a door ends it unsent.
- **The loudspeaker is not the user.** While the peer's burst is open in the player, and for 500 ms after it plays
  out, the gate stays shut and neither it nor the gain learns from what it hears. A burst not yet sent gives way to
  the peer's voice. A sent one holds the player, as a pressed burst does.
- **The 30 s limit rolls over.** A hands-free burst that reaches `WalkiePolicy.MAX_BURST_MS` still speaking ends, and
  the next opens on the next frame. A long turn loses no audio, and no tone plays into the open microphone.
- **A press still talks.** On the armed microphone it takes over the capture and the gate stands aside. A press
  during a hands-free burst takes that burst over, which then ends on the lift. Mute (Telecom's, or the
  notification's, which now shows for an armed walkie) holds the gate shut.
- **No wire change.** A hands-free burst is an ordinary burst: the same chunks, reports and casts. A receiver cannot
  tell, and needs nothing new.

Rejected:

- **A model's voice detector.** Moonshine's Silero detector sits inside the transcriber behind a 142 MB download, and
  WebRTC's VAD is absent from a build without live calls. An energy gate is what radios use, costs nothing, and is
  tested on the JVM.
- **A remembered switch.** It would make an answered call send the room at once, before the user saw the screen.
- **Telling the peer.** A flag would need a wire field, and the listener's screen would show nothing different.

**What it costs.** An armed microphone runs a 16 kHz capture for the whole session, a battery cost owed a trial.
The 800 ms hang is sent too, and a noisy room can send bursts nobody meant: the sensitivity and the commit rule are
the defences. Hands-free widens what a carrier or a casting relay learns from the bursts. Their starts and ends now
follow speech near the phone (to about 60 ms and 800 ms), not a thumb, and on an encoder that ignores the
constant-bitrate request (Android 11's, issue #355) their sizes follow its loudness too. Never its words; #355's
padding is the fix for the sizes. VOX on the loudspeaker is half-duplex at both ends, so a reply
waits for the speaker's hang. A burst still counts as activity for the 10-minute hang-up only once sent, so a
forgotten, armed, quiet session still ends. The numbers are first guesses for a device trial to tune. `VoxGateTest`
pins the gate, and `WalkieOpusInstrumentedTest` pins two utterances and a click through the real encoder.
