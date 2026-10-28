---
id: "2026-10.79yb"
slug: a-call-with-no-live-path-is-a-walkie-talkie-over-the-mesh
title: "A call with no live path is a walkie-talkie over the mesh"
date: 2026-10-08
topics: [calls, voice]
---

# ADR 2026-10.79yb — A call with no live path is a walkie-talkie over the mesh

Status: Accepted (2026-10-08)

**What was observed.** A live call (ADR 2026-10.3tee) needs an IP path for its Opus: the spool's TURN or STUN, a
shared LAN, or a Wi-Fi Direct group between neighbours (ADR 2026-10.kxnw). The mesh's own radios carry none of
those. kxnw records why Wi-Fi Aware's data path cannot, and BLE moves a file at 4–18 KiB/s. Two phones a few hops
apart, or alone together with no Internet, could signal a call over the mesh and then sat in Connecting until it
ended `no_route`. Voice notes (ADR 034) already cross that same mesh. What is missing is a screen that makes them
feel like a call.

**What changed.** The call screen became a walkie-talkie when there is no live path (`docs/VOICE_CALLS.md` §3.11).

- **A walkie session is a call without media.** `CallPayload.m` gains `MEDIA_WALKIE = 4` (2 stays video's).
  - An OFFER with that bit alone and no `sdp` rings like any call, with the same staleness, glare, busy and
    tombstones. Answering connects it at once: no ICE servers, no group, no connect timer.
  - A build without the walkie declines it for its media, because the audio bit is absent.
  - Its profile carries `Protocol.CAP_WALKIE = 0x8000`, OR-ed in through `extraCapabilities` like `CAP_CALL` and
    never in `LOCAL_CAPABILITIES`. A caller offers a walkie, or the drop to one, only toward that bit.
- **The Call button picks the kind; there is no Walkie button.** `CallManager.start` runs the `CallWalkie` seam: a
  peer that runs the walkie, when no live path looks possible, gets a walkie offer. "Looks possible" means either
  of:
  - this phone has a validated Internet route (`InternetGate.isOnline`);
  - the peer is a mesh neighbour with `CAP_DIRECT_TRANSFER` and direct calls on.

  Anything else is a live call as before. The debug bridge forces either kind (`start --es kind`).
- **A live call whose path is lost, or never came, drops to a walkie instead of ending.**
  - A live OFFER carries `AUDIO|WALKIE` when the caller can drop. The callee echoes the bit on its ANSWER when it can
    too.
  - Only then do the three `no_route` endings (the connect timer, ICE `Failed`, the disconnect grace) close the media,
    end the direct leg and connect a walkie (`degraded`) instead.
  - **No frame is sent.** Each side runs its own window, and the side that drops first closes its media, which the
    other's ICE sees as a lost path, so both land in the walkie.
  - A peer whose ANSWER lacks the bit, an older build of this branch for instance, makes the call end exactly as
    before.
- **A burst is an ordinary sealed DM voice note.** `debug/call/WalkieTalk` handles the bursts:
  - **Talking.** Hold to talk: `VoiceRecorder` at 16 kbps, half a voice note's rate. Release to send through
    `MeshController.sendChat`. Under 500 ms is a slipped thumb, and 60 s releases itself, about 120 KB, which stays
    under the 128 KiB bulk-link threshold and rides whatever link is up.
  - **Hearing.** While the session is connected, the peer's voice notes play by themselves, one after another, by
    `WalkiePolicy.verdict`:
    - from the peer and not a named file;
    - arrived (receiver's clock) after the session connected and not played yet;
    - no more than 60 s between its `sentAt` and its arrival, and no more than 90 s from arrival to its clip landing.

    Anything else stays a voice note in the thread, which is the session's transcript either way.
  - **Side effects.** A played burst moves the read watermark. The session holds the notifier's visible-conversation
    slot so bursts post no notification, and ten quiet minutes hang it up.

Rejected:

- **A separate Walkie button.** Simplest, but it makes the user guess the path, and the Call button already knows
  more than the user does.
- **A new frame or MIME for bursts.** Nothing on the receiving side needs to tell a burst from a voice note: the
  session decides. Every build already plays `audio/aac`, and the iOS port may compare the MIME exactly.
- **Opus or Codec2, and streaming chunks while the button is held.** That is the v2: it needs an encoder and a
  container every receiver plays, and the chunks need a small inline frame instead of a blob ask.

**What it costs.**

- **It is a debug-build experiment, like calls.** `BuildConfig.WALKIE` (`-Pwalkie=`, on in debug and off in release)
  is read once, as `di/CallWiring.WALKIE_WIRED`. Off, `CallManager` gets `CallWalkie.None`, no `WalkieTalk` is built
  and the profile has no `CAP_WALKIE`, so every call is a live one. Release wires no calls, so its value is inert
  until calls ship.
- **Bit 0x8000 is claimed on this branch.** Main must not reuse it before this merges. The WIRE_COMPAT precedent
  entry for `CAP_WALKIE` and `MEDIA_WALKIE` is owed at merge (left out here to keep the long-lived branch off shared
  docs).
- **Not real time.** A burst lands after its whole clip has been pulled: seconds over Wi-Fi Aware, more over BLE
  hops.
- **The visible-conversation slot is single.** Opening the peer's chat mid-session takes it, and leaving that chat
  clears it, so later bursts notify as well as play.
- **There is no `mesh/lab/` scenario.** The lab cannot run calls (3tee's amendment), and a burst is a voice note the
  lab already converges.
- **The guards:**
  - `CallManagerTest`'s §3.11 cases: the auto pick, the media-less session, the decline from a build without it,
    both degrade paths with no frame, the un-echoed bit, and glare.
  - `WalkiePolicyTest`: every autoplay clause.

## Amendment 2026-10-09: two buttons in the chat

The first rejection above did not survive use: the user asked for a mesh call they can place on purpose. The chat's
top bar now shows one of two calls, and its overflow the other:

- the live **Voice call** (`CallKind.LIVE`) while a live path looks possible;
- the **Mesh call** (`CallKind.WALKIE`) while none does.

What decides is the AUTO rule, `CallManager.prefersWalkie`. `ChatViewModel.meshCallFirst` re-reads it every 5 s
while the chat is on screen, since a route or a neighbour can come or go meanwhile. A mesh call toward a peer
without `CAP_WALKIE` is refused with a toast (`chat_mesh_call_peer_too_old`).

A live call still drops to a walkie when its path fails. The profile's Call button keeps the AUTO kind for now.

On the call screen, the hold-to-talk button takes the centre where the red hang-up sits on a live call. It is green,
never red, and End steps aside to the start.

Guard: `ChatViewModelTest`'s two call cases.

## Amendment 2026-10-09: bursts stream, and never reach the thread

A whole-clip burst was too slow even between Wi-Fi Aware neighbours. Playback waited for the release, then for the
note's frame, then for its blob to be asked for and pulled. The user also did not want bursts in the thread. So a burst
now streams while it is spoken, and the voice-note path above (the autoplay rule, the notifier slot, the read
watermark) is gone. The second and third rejections above are reversed.

- **A chunk is a sealed call signal in a frame of its own type, `talk`.** `CallPayload` gains `ph = 8` (TALK) and
  `tk = TalkChunk(b, q, a, e)`: the burst, the chunk, whole AAC-ADTS frames, and the end mark. `MeshManager.sendCallTalk`
  seals the chunk on the DM ratchet exactly like a `CTL_CALL`, but signs it into a `talk` frame and hands it to the
  router alone:
  - a live neighbour gets it with `ttl = 0`;
  - anyone else gets a flood of `ttl = 3`.

  No custody, no fast plane, no LoRa, no spool. The type is the point. Carriers cannot read a sealed DM, so a chunk in
  a `chat` frame would be custodied by every node it crossed: a minute of talk would be about 190 rows against the
  sender's 200-row quota, and served back as history. `FrameType.isCustodial` is a fixed list, and a new type stays off
  it, which is the `commons` precedent: an older build relays the frame like any unknown type and delivers nothing.
  `InboundPipeline.handleTalk` opens it on the ratchet and hands it to the call layer from an accepted contact only.
  A `talk` frame that opens to anything but a `CTL_CALL` is dropped, so the type cannot put a row in a thread.
- **`CallManager` passes chunks on and does nothing else with them.** A TALK skips the `(id, ph, gen)` dedup, since
  every chunk of a call shares it. It goes to `CallManager.talk` only while it is the connected walkie session with
  that peer.
- **The sender cuts chunks from the recorder's own stream.** `VoiceRecorder.start(onBytes)` hands each pipe read to
  `debug/call/WalkieTalk`. There `AdtsFramer` recovers whole frames, and `TalkChunker` sends a chunk each time 320 ms of
  audio is in hand: five frames at 16 kHz, about 0.7 KB, and under 3 KB/s with the frame overhead. A release sends the
  rest with the end mark. A press released before its first chunk sends nothing.
- **The receiver puts the chunks back in order and plays them as they come.** `TalkQueue` is pure and JVM-tested:
  - a burst starts at two chunks, or its last, or 400 ms after its first;
  - a missing chunk is waited for 500 ms while a later one is held, then skipped;
  - a burst ends on its end mark, or after 4 s of silence.

  `debug/call/TalkPlayer` decodes with the platform AAC decoder, configured from the first header as an extractor
  would, into a streaming `AudioTrack`. Talking holds playback, and what arrives meanwhile plays after the release.

Rejected:

- **A chunk in a `chat` frame sent `relay = false`.** It needs no new type and is never custodied, but it reaches only a
  neighbour, or one more hop through `MeshRouter.handOn`.
- **A per-session stream key, instead of a ratchet step per chunk.** It saves about three seals and three committed
  opens a second, but it is new crypto beside the ratchet for a debug-only experiment.
- **A thread copy of each burst.** That sends every burst twice, and the receiver must then not autoplay a copy it
  already heard. The user was indifferent to it.
- **Opus.** It is still the better codec at this rate. AAC-ADTS keeps the recorder the chat already uses, and every
  phone decodes it.

What it costs:

- **A wire type on this branch.** `talk` is claimed here like bit 0x8000, and the WIRE_COMPAT entry is owed at merge.
  The type is cleartext, so a carrier can tell a walkie burst from chat. Its timing would tell it anyway.
- **`CAP_WALKIE` now means the streamed form.** The only builds with the voice-note form were this branch's lab phones.
- **Live or lost.** A chunk is not re-sent. A peer out of reach for a moment misses that part of a burst, and nothing
  shows it later.
- **Load.** A non-neighbour's chunks flood up to four hops, to every node there.
- **The guards:**
  - `WalkieStreamTest` covers the framing, the chunker and every rule of the queue.
  - `CallManagerTest`'s chunk case covers what reaches `talk`.
  - `WalkieTalkLabTest` covers two hops through a relay with no thread row and no custody row anywhere, and a neighbour
    that relays nothing.

## Amendment 2026-10-09 (2): sized for Coded PHY

The streamed form ran at about 3.4 KB/s: 1,076-byte frames, three a second. That is more than Coded PHY's measured
2.1 KB/s, and the 16 kbps AAC alone filled it. The user asked for frames as compact as they can be, so that a walkie
works over Coded. A chunk frame is now about 714 bytes per 480 ms, about 1.5 KB/s. `TalkSealTest` pins that budget.

- **Opus at 8 kbps instead of AAC at 16.** `debug/call/TalkRecorder` runs `AudioRecord` at 16 kHz mono into the
  platform Opus encoder and hands each 20 ms packet on as it comes out. `TalkPlayer` decodes them with the platform
  decoder, which takes the same `OpusHead` an extractor would hand it. Every lab phone has both:
  `c2.android.opus.encoder` on API 31 to 37. The burst no longer touches the chat's `VoiceRecorder`; the edits to that
  file, v1's included, are reverted.
- **A binary chunk in a binary seal.**
  - The chunk's plaintext is `call/TalkWire`: flags, a 16-bit call tag, varint burst and chunk numbers, then the Opus
    packets, each behind a one-byte length. It replaces a `CallPayload` in a `MessageContent`'s named CBOR.
  - The frame's payload is `mesh/protocol/TalkSeal`: the v3 seal, with its nonce derived, under a bare ratchet header,
    in place of a `ChatContent`'s CBOR. A header it cannot carry (an init, a reset flag) still rides the CBOR form.
  - The chunk reaches the call layer as bytes through `MeshManager`'s `onCallTalk`, and `CallManager.onTalk` decodes
    it. `CallPayload` gains no field.
- **Unsigned.** The frame carries no signature, a 66-byte saving. The ratchet's AEAD authenticates it to its addressee
  and binds its id, sender, sentAt and recipient: the ADR 059 argument for the tick, extended to a second shape
  (`InboundPipeline.isTalkShape`), flooded or not.
  - Nothing acts on the frame before it opens.
  - A failed open is never a reset trigger.
  - It counts as no evidence that its sender was heard (`isHeardEvidence` wants a signature).
  - A relay could already flood any unknown type unverified, so a spoofed chunk costs a relay nothing it could not
    already be made to spend.
- **An 11-character frame id**, since it is a dedup key for minutes and nothing else, and **480 ms chunks** instead of
  320, which amortise the roughly 200 bytes of envelope that are left.

The bytes left are the envelope's: type, id, sender, recipient and sentAt (140 bytes of named CBOR), the ratchet's
32-byte key, and the AEAD tag. Shrinking those would take a new envelope, which is a wire break.

The guards:

- `TalkSealTest` covers the layout, its refusals and the Coded budget.
- `WalkieStreamTest` covers the TOC durations, the packet join, the chunker and the chunk bytes.
- `CallManagerTest`'s chunk case covers the tag and the session.
- `WalkieTalkLabTest` covers unsigned compact frames over a relay and to a neighbour.

## Amendment 2026-10-09 (3): the call raises its own link, and says when it has none

On the device trial, a session connected while one phone had no data link to the other. Its app had just restarted,
and Bluetooth took 45–190 s to link the pair while Wi-Fi Aware discovery saw both at once. The call's own signals
crossed on Wi-Fi Aware's message plane, which is too small for a chunk. Every burst in that direction went nowhere, and
the screen still said "Sent".

- **The call asks for a Wi-Fi Aware link.** From the ring to the hang-up, every 10 s (`WalkiePolicy.LINK_ASK_MS`,
  inside the radio's 25 s bulk-want window), `WalkieTalk` gives the radios the hint an attachment fetch gives,
  `MeshTransport.expectBulkTransfer`. All its bounds stay: a fresh sighting, the initiator hold, the failure cooldown.
  Nothing new reaches the Wi-Fi Aware transport.
- **A chunk to a neighbour rides its quickest link.** `MeshTransport.sendLive` defaults to `send`. The composite
  overrides it to prefer the high-throughput child when that child holds a link to the peer; `MeshRouter.sendOwn` takes
  `live`. Without it, the link the call raised would carry nothing and idle out, because frames go Bluetooth-first.
  Every other frame keeps that order.
- **The screen says when there is no link.** `TalkLink` is direct (a link of this phone's own), through the mesh (the
  peer's frames reached this phone through another one lately: `heardIndirectly`, ADR 2026-10.fw8g), or none. The
  walkie controls show a notice for the second and a warning for the third. With no link at all,
  `MeshManager.sendCallTalk` seals nothing and returns false, and the burst's note says it did not go.

- **The call holds its link (the user's follow-up the same day).** On the phones the raised link came down five or
  six seconds into every pause, because `WifiAwareTransport.superviseLink` ends a quiet sync link, and the next ask
  raised it again. `MeshTransport.holdLink` (the default is `expectBulkTransfer`; the composite forwards it to every
  child) now carries the call's ask. On Wi-Fi Aware it notes a 25 s hold (`NanLinkHolds`), and keeps a linked peer
  bulk-wanted.
  - **What it lifts.** A held link is exempt from the idle teardown alone (`NanLinkLife.done`).
  - **What it keeps.** The absolute windows stay (30 s initiator, 45 s responder), because they are the responder's
    only defence against an initiator that died without a FIN. So a held link comes round every 30 s or so, and the
    bulk mark raises it again at once, not at the next ask.
  - **The gap.** A chunk that finds no link at that instant retries for up to 2.5 s. On the phones the recycle ran
    about 1.9 s from the window's teardown to the next link-up.
  - **Device-verified on 2026-10-09** (P7 ↔ P9, 15 s pauses): held links survived 18 s idle (`call=true`) and ended
    only at their 30 s window; 35 of 35 chunks were delivered.
  - **The cost.** It holds the initiator's single outbound NDI for the length of the call, so that phone's Wi-Fi Aware
    syncs to others wait; Bluetooth goes on carrying them. The responder side multiplexes.

Rejected: asking only while no link of any kind exists. The pair the trial found was Bluetooth-linked within minutes
anyway; what the user asked for is a call that runs on Wi-Fi Aware when it can.

What it costs: a Wi-Fi Aware link held for the length of a walkie call, which is a Wi-Fi radio kept awake.

The guards:

- `CompositeMeshTransportTest`'s live-frame and hold cases.
- `NanLinkHoldTest`: the hold's TTL and cap, and that it lifts the idle teardown but not the window.
- `WalkieStreamTest`'s link rule.
- `WalkieTalkLabTest`'s no-link case.


## Amendment 2026-10-09 (4): the loudspeaker

A walkie plays from the loudspeaker, as a walkie-talkie does. `TalkPlayer` pins its track to the built-in speaker
instead of leaving it to the media route, which follows the earpiece on a phone left in communication mode. A
connected headset is the one exception: wired, USB, Bluetooth or a hearing aid takes the burst instead, since it
would be surprising to play someone's voice aloud to a user wearing earbuds. The call screen's volume keys turn the
media stream while a walkie session is up, because that is the stream the bursts play on.

Rejected: the communication route with the speaker forced on. It would bring back the call audio mode, which the walkie
leaves alone because it has no live media.

The trial: a burst heard from the speaker on both phones, the log's `walkie out: speaker`, and a headset taking it
instead.

## Amendment 2026-10-09 (5): the walkie's diagnostics card

A walkie session gets the diagnostics card a live call has, laid out the same way. It reads the chunks rather than a
media stack:

- **Transit, jitter and loss.** Transit is arrival less the sender's stamp, the median of the latest chunks, and
  carries the two clocks' skew. Jitter is RFC 3550's, from the spacing of those stamps, so it is free of skew. Loss is
  the chunks skipped of those expected.
- **Send, receive and buffer.** The send and receive rates are frame bytes per second of speech over the latest burst
  each way. The buffer is the player's wait before the latest burst began.
- **The details.** The path a chunk takes, the codec, the chunks, the average frame each way, the time the radio took
  to accept a chunk, and the bursts.

`WalkieMeter` holds the numbers, pure and JVM-tested. To feed it:

- `sendCallTalk` returns the frame's envelope size, or null where it returned false;
- the receive hook carries the frame's stamp and size.

Neither changes a byte on the wire. The bridge's `CALL state` reports the same numbers.

The guard is `WalkieMeterTest`.


## Amendment 2026-10-10 (6): chunks cross a spool when no radio link exists

Two phones online with no shared radio got a walkie session that carried nothing, because `sendCallTalk` sealed nothing
without a link. That is the session a live call falls back to when its TURN is sold (knit-spool ADR 2026-10.bxsg) and
STUN fails behind a symmetric NAT. Call signals already cross a spool as stored DM blobs. Chunks cannot ride that way:
each costs a digest entry, an eviction, a tombstone, a listing and a pull per member per heal round, and two a second
would push a 400-frame scope's history out within minutes.

- **A chunk with no mesh path is cast.** knit-spool's new `cast` record (`docs/VOICE_CALLS.md` §2.7, knit-spool ADR
  2026-10.3wdy) fans one sealed frame out to a scope's other live subscribers. The spool never stores it, never
  answers it and never strikes it.
  - **When.** `MeshManager.sendCallTalk` casts only when the peer is neither a neighbour nor in `heardIndirectly`,
    and `ScopeSync.canCast` holds: a connected spool advertised `cast` and answered the peer's DM scope.
  - **Beside what.** The blind flood still goes when this phone has links at all. Both copies carry one id, so the
    receiver's `SeenSet` keeps the first.
  - **How.** `ScopeSync.cast` sends to every such spool, never under the round lock and never as a request.
- **The cast copy is signed; the radio copy is not.** The cast carries `messageCrypto.signRaw` over the same `signed`
  bytes, so the Internet plane keeps §4.4's rule that every frame it carries is checked against the pinned key. The
  radio copy keeps amendment (2)'s unsigned compact form and its Coded budget.
  - **Receiving.** `ScopeSync.handleCast` runs `ScopeFrames.openCast`, then the cast set (`castEligible`), then
    `canCarry`. It then delivers the frame through the router with `ttl = 0` and `relay = false`. Nothing is
    accounted, quarantined or counted as presence.
  - **Ratchet failures.** `handleTalk` now never treats a failed open as reset evidence, signed or not.
- **Its own seal.** `ScopeCrypto.sealCast` uses version `0x04`, its own nonce label and aad, and a plaintext padded
  to 128 bytes. A cast never opens as a frame blob or a chunk, and neither of those opens as a cast. A chunk's cast is
  797 bytes.
- **Sold with tickets.** On an `ent` spool a cast fans out only while some subscriber of the scope holds a ticket with
  bit 3 (`SpoolTicket.FL_CAST`), so one payer unlocks the walkie both ways.
  - `CallPayload.ct` rides a walkie OFFER or ANSWER when the sender holds one (`ScopeSync.castTicketed`), and
    `CallState.peerCastTicketed` keeps the peer's value.
  - `WalkieTalk` passes that value to `sendCallTalk`, so a free phone casts only where a payer has unlocked the scope.
- **The screen.** `TalkLink.SPOOL` is the link to a peer with no mesh path whose chunks a relay would cast. The walkie
  controls show "Going through your relay", the diagnostics path reads `Through the relay · cast`, and the bridge's
  `CALL state` reports `peerCastTicketed` and `castTicketed`.
- **Constant bitrate.** `TalkRecorder` asks the Opus encoder for CBR where it reports support, so a chunk's size says
  nothing about what was said, to a carrier or a relay. The cast's padding covers the relay where it does not.

Rejected:

- **`push`, or `pushDirect`.** Stored, folded and listed. `pushDirect` also takes the round lock, which a heal stuck on
  a silent spool holds for up to 30 s.
- **An ack per cast.** A shipped client reads a scoped `err` with no `q` as a refused `sub`, and a `q` would put a
  pending slot and the silent-request strike on every chunk.
- **Casting to a peer heard through the mesh as well.** The user chose the fallback alone: a mesh path keeps its
  mesh-only behaviour, and the spool sees only sessions that need it.
- **Unsigned casts, authenticated by the ratchet alone.** Signing keeps §4.4's invariant, and its 64 bytes land only
  on the spool's copy.
- **Casts open to any subscriber on a ticketed spool.** The relay is the paid feature (Knit Anywhere), and radio
  flooding stays free. A spool without tickets still lets any subscriber cast.
- **A ticket on every caster.** A free friend could only listen.

What it costs:

- **Data and a live signal.** About 1.7 KB/s while talking. The spool sees which side of a DM scope talks when and for
  how long, never what was said (§2.5).
- **`CAP_WALKIE` now also means "reads casts."** No new bit, for amendment (1)'s reason: the only builds with the walkie
  are this branch's lab phones. An older lab build skips the record, and a cast to it is lost.
- **`dropToWalkie` stays parked.** Its reason, a peer off every radio getting a session that carries nothing, is what
  casts fix, so un-parking it is the follow-up.
- **A correction.** The sentence in the first streaming amendment that drops a `talk` frame opening to anything but a
  `CTL_CALL` describes that amendment's form. Since amendment (2) the plaintext is `TalkWire`, and `CallManager.onTalk`
  drops what does not decode.
- **The guards:**
  - `CastSealTest`: the vector, the refusals, and a chunk's size.
  - `SpoolCastRecordsTest`: knit-spool's hex.
  - `ScopeFramesTest`: the cast set.
  - `SpoolConnectionCastTest`: the hello, the gates, the local budget, the queue.
  - `ScopeSyncTest`'s cast cases: the round lock, the carry gate, the ticket rule.
  - `InboundPipelineTest.aSignedTalkFrameThatFailsToOpenIsNeverResetEvidence`.
  - `CallManagerTest`'s two `ct` cases.
  - `WalkieStreamTest.aRelayIsThePeersLinkOnlyWhereTheMeshHasNone`.
  - `WalkieTalkLabTest`: a burst cast between two phones with no radio path, and a neighbour that is never cast.

## Amendment 2026-10-12 (7): the loudspeaker is where the call's route starts

The fourth amendment pinned a burst to the loudspeaker on the media usage, away from the communication mode. That is
replaced. A walkie session is a Telecom call like a live one (ADR 2026-10.wbua, `docs/VOICE_CALLS.md` §3.13), and it
starts on the loudspeaker where a live call would start on the earpiece.

- **Playback.** `TalkPlayer` plays on the voice-communication usage. That usage follows the call's route, which the
  user can move as on a call, so a headset takes the burst both ways.
- **Capture.** While a headset has the call, `TalkRecorder` records from `VOICE_COMMUNICATION`, which reaches that
  headset's microphone.
- **Correction.** A route that falls to the earpiece without the user picking it goes back to the loudspeaker.
- **Volume keys.** The call screen's volume keys turn the voice-call stream for both kinds.

The cost is the Bluetooth controller: a headset's call link stays up for the whole session, on the controller that
carries the chunks.

## Amendment 2026-10-12 (8): a gain before the encoder

A burst played far quieter than a live call at the same volume, on the loudspeaker or off it (the user, 2026-10-12, on
the P9 and the P7). Amendment (7) made the two comparable, since both now play on the voice-call stream. The cause
was older than that, though. `TalkRecorder` captures the phone's microphone from `VOICE_RECOGNITION`, a flat path with
no gain control, while a live call's capture passes WebRTC's.

The capture now passes `call/TalkGain` before the Opus encoder (`docs/VOICE_CALLS.md` C-3.11-5).

- **Level.** Speech is brought toward −18 dBFS RMS. It gains at most 24 dB and is never turned down.
- **Speed.** The gain falls at once and rises at most 5 dB per second of speech, so it settles near the loud
  syllables instead of pumping the quiet ones up.
- **Pauses.** It holds between words, where a frame is within 12 dB of the quietest frame of the last 2 s, and a
  steady hum becomes that floor.
- **Limiter.** No peak passes −1 dBFS.
- **State.** One gain per microphone (the phone's, a headset's), kept across bursts.

It sits on the sender, because a receiver cannot win back what the codec spent on a quiet signal. Older builds hear
the louder bursts unchanged.

Rejected: the platform's own `AutomaticGainControl` effect on the record session. Phones offer it unevenly, and the
P9's call path lists only echo cancellation and noise suppression. Also rejected: switching the phone's microphone to
`VOICE_COMMUNICATION`, which still leaves the level to the phone's call tuning and adds that path's processing to a
half-duplex burst.

Owed: a by-ear comparison against a live call on the two phones. Each burst logs `walkie gain: speech at … dBFS, gain
… dB, … frame(s) limited` under `KnitCall`, for tuning the target.


## Amendment 2026-10-13 (9): a fading direct link is cast beside

A field test (the user, 2026-10-13, the P9 and the P7 apart on cellular, the walkie over Bluetooth Coded) lost one
burst each way at the edge of range. From about 15:57:16 chunks sent 480 ms apart landed 2–3 s apart. By 15:58:02 the
P9 heard no advert from the P7 (`linkScores …:-127`), yet the link stayed in `links` until the controller's supervision
timeout closed it: `link dropped on CODED rssi=-103 (eof)` at 15:58:26 on the P9, `rssi=-82` at 15:58:31 on the P7.
Amendment (6) casts only for a peer with no mesh path, and a dying link is a path until it closes. Every chunk sent over
it in those ~20 s was lost. Once it closed, the next five bursts crossed the relay whole both ways. Android offers no
public way to shorten an LE supervision timeout, so the fix is on our side.

- **A neighbour whose live link fades gets the cast too.** `MeshManager.sendCallTalk` casts when
  `MeshTransport.liveLinkFading` holds for the peer, as well as when it has no mesh path, wherever `ScopeSync.canCast`
  holds. The radio copy still goes out. Both copies carry one id, so the receiver keeps the first (C-7.7-15).
- **The seam.** `liveLinkFading` answers for the link `sendLive` would use. In `CompositeMeshTransport` that is the
  Wi-Fi Aware child when it holds a link, which never fades, else the Bluetooth-first holder. It does not reuse
  `slowLinkTo`, which always asks the Bluetooth holder.
- **Bluetooth's verdict.** `mesh/bluetooth/LinkFade`, fed by `BlePhyControl`'s link-RSSI reads, says a link fades when:
  - its smoothed link RSSI is at or under −95,
  - or three reads in a row go unanswered (the call refused, the callback failed, or no answer within 1 s).

  It stops fading only on an answered read, once answered reads have held −90 or better for 10 s; a weaker read or an
  unanswered one restarts that hold. The verdict is sticky on purpose, because a hedge costs little.
  - **Reads that fail now count.** An unanswered read (not sent, refused, or timed out) used to be dropped silently.
    Each is now counted toward the stale-read rule.
  - **Reads are counted, never timed.** The read loop's wait stops while the CPU sleeps and the elapsed clock does not,
    and nothing on the walkie path holds a wake lock, so a rule on time since the last answer would fade a link after
    any sleep (the reviewer's finding). Only a run of unanswered reads counts.
  - **A reader that ended has no verdict.** A PHY handle whose GATT client is gone reports no fade rather than a stuck
    one. It stays in place, because the drop-at-range bookkeeping reads it at teardown (the range hunt, the fast Coded
    advert).
  - **Where it can't tell.** A link with no PHY handle (Coded off, or a peer never heard on Coded) takes no reads, so it
    never fades.
  - **Tuning.** The thresholds are `PhyTuning` fields that `…debug.PHY` tunes.
- **Seen in the logs.**
  - Bluetooth logs `bt fade <id> on (rssi=…, unanswered=…, last answer …)` and `bt fade <id> off` at each edge, and every read at the edge (`bt phy <id> rssi=… smoothed=…`).
  - The walkie's burst line names its path, `via …`. The new path reads `Bluetooth · fading, relay too`.
  - `TalkLink` stays direct, since the radio still carries the chunk. The user is shown nothing new.

Rejected:

- **Casting whenever the link is on Coded.** That would have covered the P7's side below, but in this test the link was
  on Coded from the first second, so the whole seven minutes would have been cast. The user chose to hedge near the
  floor only.
- **Casting instead of the radio copy.** The radio copy costs nothing, and the link may still carry it.
- **A keepalive on the Bluetooth link to detect silence.** The P7 and an iPhone would need a new record, and the
  walkie's own silence between bursts is normal.
- **A shorter supervision timeout.** There is no public API for it.

What it costs and what it misses:

- **Data.** About 1.7 KB/s through the relay, only while the link fades and someone talks.
- **The asymmetric edge.** The P7's own smoothed link RSSI still read −82 when its link closed, so the floor alone would
  not have hedged the P7's sends. The stale-read rule catches that side only if its reads stopped answering, which was
  never logged. The edge-read log is what the next walk-apart reads to set the threshold and to settle this.
- **Reads that may never fail.** HCI Read RSSI is answered by the local controller from the last packet it received.
  With no packets arriving it may keep answering a frozen value, and then only the floor can fire. A run of identical
  raw reads before a drop in the edge-read log would show it, and "k identical raw reads count as stale" would be the
  follow-up.
- **A deep but steady Coded link.** Coded links have held at −96 to −99, so a walkie over one hedges for its whole
  length. That follows from choosing the floor.

Owed: a P9 ↔ P7 walk-apart on Coded, both phones with a relay. `bt fade … on` should come before the eof, the burst
lines should read `via Bluetooth · fading, relay too`, and no burst should be lost across the drop.

## Amendment 2026-10-14 (10): the platform's noise suppressor on the burst's microphone

A walkie burst went out with no noise suppression at all. A live call has WebRTC's or the phone's own, but
`VOICE_RECOGNITION` runs no pre-processing (the CDD has it off there), and `TalkGain` raises the level without removing
anything. The burst's transcript (ADR 2026-10.nz3g) reads that raw audio too, so a noisy place costs both the listener
and the model.

`TalkRecorder` now attaches `android.media.audiofx.NoiseSuppressor` to the record's session and enables it, wherever
the phone implements one (`docs/VOICE_CALLS.md` C-3.11-5). It is released before the record. The source stays
`VOICE_RECOGNITION`, so the call path's echo canceller and gain still stay out of a half-duplex burst. A phone without
the effect, or one that refuses it, records as before, and each burst's `walkie in:` line says which: `noise suppressor
on`, `unavailable` or `refused`. The suppressor runs ahead of `TalkGain`, so the gain's noise floor reads the
suppressed background.

The suppressor is the vendor's, so its strength varies from phone to phone, and it is tuned for steady noise. A
neural denoiser (RNNoise and the like) on the PCM before the gain would be the same on every phone, and is the next
step if this one falls short.

Owed: a by-ear and transcript A/B in a noisy room on the P9 and the P7, using `…debug.CALL --es op ns --ez on false`
for the "before" bursts.

## Amendment 2026-10-18 (11): the walkie needs no live calls

`BuildConfig.WALKIE` is now `BuildConfig.MESH_CALLS` (`-PmeshCalls=`), still read once as `WALKIE_WIRED`, and it is no
longer off wherever live calls are (ADR 2026-10.swvp). A build with mesh calls alone packages no WebRTC and advertises
`CAP_WALKIE` without `CAP_CALL`. Its `CallManager` places every call as a walkie. A caller asks for `CAP_WALKIE` alone
to place one, and `liveLikely` is false toward a peer without `CAP_CALL`.

## Amendment 2026-10-18 (12): the radios first, the relay when they fail

A field call on 2026-10-18 connected and carried no sound either way. The P9 called the P8 twenty minutes after both it
and the P7 had walked out of the P8's range. The OFFER and the ANSWER crossed a relay, and every chunk went
`Through a neighbour · flooded` into the P7, which had no path to the P8 either. `sendCallTalk` cast only where
`meshPathTo` said the mesh had no path, and `meshPathTo` read `RelayedPresence.heard`. That table is ADR 2026-10.fw8g's
presentation tracker, with a 45-minute linger. Its own KDoc says nothing in `mesh/` may route on it. The P8 was in it
from frames the P7 had relayed before 16:30, and again from a 16:39 profile the P7 passed on, which had most likely
reached it from a spool.

The walkie should use the radios wherever they work, and a relay only when they do not. It now decides on evidence
that is short-lived and names the link, and on what the listener says it heard (`docs/VOICE_CALLS.md` C-3.11-6,
C-3.11-10):

- **The radio path is `mesh/TalkRadioPaths`, never `relayedPresence`.** A peer that is not a neighbour has a radio
  path only while one of its own frames reached this phone through a link we still hold, within two minutes
  (`WINDOW_MS`). The frames that count are a signed frame `verifyInbound` passed, stamped by its author within the
  window (custody re-serves old frames to every new link), and a `talk` frame the ratchet opened, which is the peer's
  own bytes as surely as a signature. A spool's frame and a LoRa one never count. A dropped link loses its path at once,
  and a quiet one after the window. With no radio path the chunk is cast beside the flood.
- **The listener reports each burst.** A walkie OFFER and ANSWER carry `CallPayload.hr` (additive and defaulted).
  Between two phones that both set it, the listener sends a report for each of the speaker's bursts back as a `talk`
  frame of its own. The report is a `TalkWire` plaintext with flag bit 1 holding `b`, the distinct chunks that came
  over the radios, and those that came through a relay (`HeardLedger`). It goes 500 ms after the burst's last chunk
  landed, or at the player's 4 s give-up when that chunk never came, and it travels the way a chunk does. A build before
  this one would read the report as a chunk, which is why it goes only to a peer that set `hr`.
- **The speaker acts on them.** `TalkRoute` turns on `castToo` once a report shows the radios lost more than a fifth of
  a burst, or once no report has come 7 s after the burst's last chunk went. While `castToo` is on, `sendCallTalk` casts
  every chunk beside its radio copy. Two reports in a row with the radios whole turn it off. The listener counts a
  chunk that came both ways under both planes, so a radio path that recovers shows up while the cast is still running.
  The diagnostics path reads `Radio losing chunks, relay too`.

`relayedPresence` keeps its linger for the reader alone, and that linger is 15 minutes now (fw8g's amendment).
`CallReachRule` still reads it. That rule only decides whether an unanswered OFFER stops ringing early, and the OFFER
itself rides custody and the spools.

Costs:

- *A two-hop session the peer is silent in for over two minutes casts until the peer is heard again.* Every report the
  peer sends refreshes the path, so this happens only when this phone does all the talking. The cast stops again after
  two reports showing the radios whole.
- *A radio path a spool fed.* A neighbour that passes on a peer's frame it got from a spool makes a radio path for
  two minutes. Nothing about the frame tells the two cases apart. The first lossy report turns the cast on.
- *One report frame per burst each way.* It is tiny, and it goes through a relay only when that side is casting.
- *The first burst over a dead path is lost.* Nothing judges it until its report is overdue. A burst that starts
  inside those 7 s goes by the radios too.

Regressions: `TalkRouteTest`, `TalkRadioPathsTest`, `WalkieStreamTest.aReportRoundTripsAndIsNeverReadAsAChunk`, and
`WalkieTalkLabTest`'s `aPeerHeardThroughALinkLatelyGetsItsBurstOverTheRadiosAlone`,
`aPeerTheRadiosLostTouchWithIsCastBesideTheFlood` and `aChunkTheWalkieCastsTooGoesBothWaysToASoundNeighbour`.

Owed: the 2026-10-18 shape again. P9 and P7 walk out of the P8's range, wait out the two minutes, and call the P8. The
first burst should log `via Through the relay · cast` and be heard. Then a two-hop call (P9 ↔ P7 ↔ P8, the P8 out of
the P9's range) should stay `Through a neighbour · flooded` with `walkie: reported burst … over the radios` lines and
no cast.

## Amendment 2026-10-28 (13): the phone's microphone takes the call path

A P7 → P9 walkie transcribed badly on the P9 (the user, 2026-10-28; same speaker, same distance). The saved clips
showed why: through `VOICE_RECOGNITION` the P7's microphone kept most of a voice's "s" and "f" above 4 kHz, where the
8 kbps codec cuts, over a noise floor about 15 dB under speech, while the P9's same source rolled off above 4 kHz. The
CDD holds that source flat only from 100 Hz to 4 kHz, so above that every phone differs. A trial on the two phones
compared three sources by ear and by transcript. `UNPROCESSED` (which both Pixels say they lack, yet open)
transcribed best but arrived about 25 dB under speech level, below where `TalkGain` hears speech at all.
`VOICE_COMMUNICATION` landed at −21 to −34 dBFS with a very low floor, and sounded right to the user. Every phone
that makes VoIP calls has it.

- **Source.** The phone's own microphone now follows the live call's capture profile (ADR 2026-10.kw29,
  `call/TalkCapture`). On `STANDARD` it records from `VOICE_COMMUNICATION`, then `VOICE_RECOGNITION`, then `MIC`. On
  `FALLBACK` (a generic image, or a phone where a call captured only zeros) it records from `VOICE_RECOGNITION`, then
  `MIC`, as before. `CaptureHealth` moves out of the live-call graph so a walkie-only build has it too. A headset is
  unchanged (`VOICE_COMMUNICATION`).
- **Suppressor.** Knit attaches the platform's `NoiseSuppressor` only where the source runs none of its own. On
  `VOICE_COMMUNICATION` the phone's call processing is the suppression, and `walkie in:` says `noise suppressor call
  path`. This also drops the explicit request amendment (10) made on a headset's `VOICE_COMMUNICATION`.
- **Gain.** `TalkGain` stays. The P7's call path delivered speech about 8 dB under the P9's (−28 to −34 against −21
  to −25 dBFS). The gain's slow rise and its hold across bursts make it a trim on top of the phone's own gain, not a
  second one fighting it.
- **A dead call path.** A burst from the phone's `VOICE_COMMUNICATION` with at least 1 s of audio and not one sample
  above zero, not muted and not silenced by the system, calls `CaptureHealth.learnedSilent()` (C-3.13-13's rule over
  a burst). The next bursts and calls open on the fallback. Without this, a phone whose VoIP record route is dead, but
  which is not a known generic image, would send silence for good, where the old source worked.

The 4 kHz codec band stays. Rejected for now: raising the bitrate to 12–16 kbps for wideband Opus. That costs
1.5–2× the bytes per chunk on Coded PHY and the relay, and `VOICE_COMMUNICATION` mostly brings what the model needs
under 4 kHz. Also rejected: `UNPROCESSED` with a fixed +18 dB make-up gain (the CDD's sensitivity gap). Phones need
not offer it, the Pixels say they don't, and it brings no suppression.

`…debug.CALL --es op source` and `--es op gain` stay for A/Bs, and each saved clip's sidecar records `source` and
`gain`.

Owed: a P9 ↔ P7 walkie on this build with scripted sentences, read against the 2026-10-28 clips. Then the Moto (a
generic image) should log `walkie in: source 6`, and `…debug.CALL --es op capture --es set fallback` on a Pixel should
move its next burst to source 6.
