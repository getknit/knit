---
id: "2026-10.hmfe"
slug: a-short-video-is-re-encoded-small-before-it-is-sent
title: "A short video is re-encoded small before it is sent"
date: 2026-10-16
topics: [attachments, wire, moderation]
---

# ADR 2026-10.hmfe — A short video is re-encoded small before it is sent

Status: Accepted (2026-10-16); amended 2026-10-17 (below: the file button, the receiver's re-label, the poster), 2026-10-19 (below: the screen's frames) and 2026-10-31 (below: a long pick asks first). Amends ADR 2026-10.js2p (a room post now declares its size, so a large room
attachment is held like a sealed one), ADR 2026-10.9rh6 (the room screens a video's frames on the way out) and ADR
2026-10.unqn (a video is never passed through). Additive wire: `ChatContent.attachmentSize` and `.video`,
`MessageContent.video` (`MessageContentV2` label 16), all null and omitted by default; three vectors added, none
moved. DB v22. No capability bit. iOS companion owed. Device trial owed.

**What was observed.** A picked `.mp4` went out through "Send a file" as an opaque named file, at most 8 MiB as
picked, and nothing played it. A phone's own clip of 30–90 s is 20–200 MB. The goal is a short social-style clip as a
first-class attachment, sized for the mesh rather than for the 8 MiB cap: over Bluetooth at the base pace a 3 MB
video is about 12 minutes per copy (ADR 2026-10.8jwn's 4 KiB/s), and every neighbour of a room post pulls one.

**What changed.**

1. **One encode, size first** (`data/video/VideoEncodePolicy`, pure). Short side 480 (never upscaled), at most
   30 fps, HEVC at 350 kbps (H.264 at 1.4× on a phone with no HEVC encoder that takes 854 × 480 at 30 fps — AOSP's
   software HEVC encoder stops at 512 × 512, and Transformer's fallback would have shrunk the picture rather than
   change codec), AAC at 48 kbps, cut at 61 s. A 61 s clip is about 3 MB, under the 5 MB tier of js2p's limit, so a
   user on that tier fetches every video unasked. The output is measured, not trusted: past 4.5 MB it is encoded once
   more at 0.7× the rate, and past it again refused ("Direct file send can take the original"). Fitting as much as
   possible into 8 MiB was rejected: the cost of a video on this mesh is per byte per neighbour, and anyone who wants
   the original has Direct file send (ADR 2026-09.wtmz).
2. **Media3 Transformer, into memory** (`VideoTranscoder`, `MemoryMp4Muxer`, `MemoryMuxerOutput`). Transformer does
   the decode, rotation, frame drop, scale and HDR tone-map (OpenGL, then MediaCodec's, then read-as-SDR on a
   frame-pipeline or format failure — the emulator's SwiftShader fails the first). Its muxer writes through a
   `SeekableMuxerOutput` that is a byte array, so the plaintext never touches the filesystem (ADR 029). Three device
   findings are load-bearing:
   - **CBR, not VBR.** Under VBR the platform's quality floor (`VQApply`, Android 12+) raised a 490 kbps request to
     1.23 Mbps at 480p on the codecs that carry its tuning, and a 28 s clip came out at 4.7 MB on both passes. CBR is
     the mode that floor leaves alone (`VideoTranscoder.bitrateModeFor`), and a static scene still undershoots it.
   - **Not streamable.** Streamable output reserves room for moov ahead of mdat and pads the rest with a `free` box:
     373 KB of a 2.3 MB encode. A video is fetched whole and played from memory, so moov goes at the end.
   - **The metadata is an allow-list.** Transformer forwards every entry the source carried (`©xyz` location,
     capture time, make and model, XMP); `MemoryMp4Muxer` writes only `Mp4OrientationData`, the `ImageStrip` rule.
3. **The bubble has a picture before the bytes** (`VideoInfo`): duration, upright size and a poster JPEG (long side
   256, at most 6 KiB, stepped down in quality until it fits) ride inside the message — sealed in a DM or group,
   cleartext in a room post. A nested type with required fields (WIRE_COMPAT rule 6 and its no-defaulted-ByteArray
   rule); raw bytes in the compact layout. The receiver keeps them as labels (`messages.video*`, the poster base64 so
   the row keeps value equality, never in the blob table, where any neighbour could ask for it by hash), bounds them
   (`isPlausible`, a poster over 8 KiB dropped), and replaces the duration and size with the bytes' own once they land
   (`deriveVideoMeta`). A separate poster blob was rejected: the bubble would wait on a second fetch, which is the
   thing the poster is for. A thumbhash was the cheaper alternative; the user chose a real picture.
4. **A room post declares its size** (`ChatContent.attachmentSize`, by `BluetoothFetchGate.declaresSize`, ≥ 1 MB),
   the second additive field js2p named, so a room video is held off Bluetooth until the tap exactly like a sealed
   one — which supersedes js2p's "a room post declares no size and is never held" and "room posts stay ungated until
   `ChatContent` carries a size". A sealed frame's shell never supplies it (`plaintextContent` clears both room
   fields). Every room node is a recipient *and* a carrier, and custody runs ahead of delivery: custody's want
   (`InboundPipeline.onCarriedFrame`) was plain for a room post, its ask went out before `deliverChat`'s fast-only
   want could hold it, and every node pulled the video over Bluetooth (found by `LargeAttachmentLabTest`). So custody
   and the tick (`MeshManager.unopenedForUs`, `CarriedAttachment.declaredSize`, read off the cleartext room frame)
   judge a carried room post by the size it declares, folded with the rows as delivery folds them. A carrier with no
   row — a blocked sender's post, a swept row — is held the same way: js2p's "a carrier's pull is never held" now
   covers DM and group frames only.
5. **Screened as pictures are** (9rh6). A Nearby-room send classifies the poster and 12 frames sampled across the
   clip (`ImageScreeningService.isVideoExplicit`) and blocks a flagged one. A receiver screens the poster the moment
   the row exists (`isPosterExplicit`, the verdict kept on the row as `messages.videoPosterFlagged` — a `blob_verdicts`
   row keyed by the poster was the first draft, and nothing would ever have collected it) and the frames
   once the bytes are held (`screenVideo`, DM and group after decryption, room in `MeshBlobStore.ingest`). Bytes with
   no frame to read fall to the image screen: a picture labelled a video picks a screen, never skips one.
6. **What makes it a video** is its description on a video MIME (`attachmentKindOf(…, video)`, the bubble's test),
   checked ahead of the name: a video is named `video.mp4` only so an older build shows its file bubble (which
   already draws a Videocam) and can save it. An `.mp4` sent through "Send a file" has no description and stays a
   file, sent and saved as-is (ADR 2026-09.7ad3).
7. **Playback** is the platform `MediaPlayer` over `ByteArrayMediaSource` on a `TextureView`, the voice note's
   in-memory path; Save files it under `Movies/Knit`. No ExoPlayer.

**What it costs.** About 4.6 MB of Media3 classes before R8, pure Java with no `.so`, minCompileSdk 36. After R8
the release dex is 8.05 MB, so ADR 050's byte ceiling goes to 9 MB. Media3's TTML image cue is a third unbounded
decode, reached only from a sender's own pick, so ADR 051's budget goes to 3 (both amendments dated 2026-10-16). An encode takes
real time. The seal does not pad, so a sealed video DM is about 6.5 KB longer than any other attachment DM: a carrier
learns from the frame length that it carries a video, the distinction ADR 035 hid by sealing the MIME — the blob's own
size hints at it anyway, and padding sealed content is a decision of its own. An encode takes real time — 18.5 s for a 61 s 720p source on the Pixel 3, 11 s for 28 s of 1080p on the emulator — behind a
cancellable progress tile. Older Android builds see a DM or group video as a file; in the room they draw a broken
image and fetch it ungated (no size field to read). A picked video is never found by its source's bytes
(ADR 2026-10.mptm): hashing a source that may be hundreds of MB to save one encode is the wrong trade. A video frame
with its poster (about 6.5 KB) never fits the 681 B LoRa hop or the Wi-Fi Aware message plane: the LoRa codec refuses
it (`loraTooBig`) and the fast fan-out size-gates it, so it rides the links and custody like any large frame, and a
room's custody, counted in rows, grows by up to a poster per post. Not built: camera capture of video, a trim UI, audio
screening (no model, as for voice notes). **iOS companion owed:** read and emit the new fields, declare the room
size, play HEVC. **Device trial owed:** HEVC encode time and bitrate overshoot on each vendor's encoder, an HDR camera
clip (the emulator cannot decode 10-bit HEVC), HE-AAC availability, playback on the Pixel 3 and the Moto. Guards:
`VideoEncodePolicyTest`, `MemoryMuxerOutputTest`, `MemoryMp4MuxerTest` (with a stock-muxer control that does write
`©xyz`), `VideoFramesTest`, `VideoAttachmentTest`, `AttachmentStoreVideoTest`, `FileTypesTest`,
`MessageContentTest` / `MessageContentV2Test` / `GoldenVectorTest`, the 24→25 migration test, `InboundPipelineTest`'s
two video cases and `onCarriedFrameJudgesARoomPostByTheSizeItDeclares`,
`MeshManagerTest.aCarriedRoomPostNoRowNamesIsJudgedByTheSizeItDeclares`, `ChatViewModelTest.aPickedVideoIsEncodedThenStaged…`,
`LargeAttachmentLabTest`'s two room-video scenarios, and
on hardware `VideoTranscoderInstrumentedTest`.

## Amendment 2026-10-17 — a video is a video whichever button picks it; the poster is chosen

The first device trial (Pixel 9 Pro XL to Pixel 7) used "Send a file", and point 6 showed three faults at once. The
sender's `.mp4` went out as picked, so one over 8 MB was refused ("files are sent as-is"), and one under it went with
no description: the sender drew a file bubble. The receiver then read the bytes' own duration and size onto that row
(`MessageDao.setVideoMeta`, keyed by hash over every row naming it), which turned the file into a video bubble with no
poster, drawn on the bubble's black.

1. **The file button takes the video door** (`ChatViewModel.attachFile`). A pick `AttachmentStore.isVideo` calls a
   video is re-encoded and staged with its poster exactly as from the photo picker, so it is never refused for its
   picked size and both sides draw the same bubble. Point 6's "an `.mp4` sent through Send a file stays a file" is
   superseded. A video the encoder cannot read or encode is still a file and goes out as-is, under the file cap
   (`attachAsFile`); one still too big after shrinking is refused as from the photo picker, since past the ceiling
   it is past the file cap too.
2. **Only a described row is re-labelled.** `setVideoMeta` touches rows whose `videoDurationMs` is already set. An
   `.mp4` that arrives as a file, from an older build or another client, keeps its file bubble.
3. **The poster is the best of eight frames** (`VideoFrames.poster`). The fixed instant, a second in, could land on a
   fade or a covered lens. Eight frames spread across the clip (`sampleTimes`, never its very start) are ranked by
   `posterOrder`: exposure (zero for a black or blown-out frame), times contrast plus capped detail, ties to the
   frame nearest mid-grey and then the earlier. The first in that order whose JPEG fits the 6 KiB budget is the
   poster. The poster is screened as before, so choosing it buys no way past the room's screen. Pure and JVM-tested
   in `VideoFramesTest`; the decode stays `getScaledFrameAtTime` at the poster's size.

The photo button's icon is the gallery (`PhotoLibrary`) and its label "Attach photo or video", since its picker has
offered videos since point 1. Guards: `ChatViewModelTest.aVideoFromTheFileButtonIsEncodedAndOneTheEncoderCannotReadGoesAsAFile`,
`MessageDaoTest`'s `setVideoMeta` case, `VideoFramesTest`'s three poster cases. Device trial owed: a file-button video
over 8 MB, P9 to P7, both sides drawing the video bubble with a picture.

## Amendment 2026-10-19 — the screen reads the frame at a random instant in each twelfth

Point 5's 12 frames were read at fixed instants (the middle of each twelfth) through `OPTION_CLOSEST_SYNC`, the
keyframe nearest each. Our own encode spaces keyframes a second apart, so a send was screened as meant, but a receiver
screens the bytes as they arrive, and their keyframes are the sender's to place. `VideoScreeningInstrumentedTest` built
a 60 s clip of twelve coloured stills with one keyframe: all 12 frames came back as the first still. A sender could
have opened a clip with one clean frame, or put clean frames at the 12 instants anyone could compute, and the
receiver's blur would never have seen the rest.

1. **The exact frame** (`VideoFrames.sample`, `OPTION_CLOSEST`): decoded on from the keyframe before the instant, never
   that keyframe. The poster keeps the nearest keyframe; it is the sender's own choice and screened as a picture.
2. **At a random instant inside each twelfth** (`VideoFrames.screenTimes`): every stretch of the clip is looked at,
   and no sender can know which frames will be. The send screen and each receiver draw their own.
3. **No longer than Knit sends.** The decode from a keyframe is what an exact frame costs, and on a clip with one
   keyframe it is the clip up to the instant. A received clip longer than `VideoAttachment.MAX_SCREENED_DURATION_MS`
   (the 61 s cut plus 2 s of slack for a container's rounding) is not decoded at all: `screenVideo` stores it as
   flagged, so it shows behind tap-to-view. What a longer video from another client should do is still open; this
   bounds the screen's cost meanwhile.

Measured on the emulator: 12 exact frames of our own encode in 0.3 s, of the one-keyframe 60 s clip in 4.7 s; every
real clip's 12 frames are now 12 different pictures (7 or 8 before, on 7 s clips). Both screens log each frame's score
in clip order. Guards: `VideoFramesTest.theScreenLooksOnceInsideEachSliceAtAnInstantNobodyCanPredict`, and on hardware
`VideoScreeningInstrumentedTest` (frames at their instants on our encode and on a one-keyframe clip, the length bound,
and a labelled corpus of real clips pushed to the device).

First explicit run (2026-10-20, emulator): four explicit videos (36 s to 39 min; 144p to 1080p) cut into 57 one-minute
segments, each through the send encode and the screen: all 57 flagged, 91% of the 684 frames over the 0.9 threshold,
4% under 0.5, and the weakest segment still 3 of its 12. A send of the 36 s one to the room through the bridge
(`SENDIMG --ez video true`) was blocked on its poster alone (0.9999). Owed: a larger licensed explicit set, the clean
half's false-positive run, and the receiver-blur trial on phones.

## Amendment 2026-10-31 — a pick past 61 s asks before it is encoded

A pick longer than 61 s was encoded, cut and staged, and only then did a toast say so ("Videos are up to 61 seconds,
so this sends the first 61"). The sender learned of the cut after waiting through the encode (18.5 s for a 61 s
source on the Pixel 3, above), and a toast cannot be declined: the only way out was to clear the staged clip.

1. **The length is read first** (`AttachmentStore.videoDurationMs`, through `VideoEncoder.probe`: `VideoTranscoder`'s
   `MediaMetadataRetriever` read of the container, on the IO dispatcher). No frame is decoded and nothing is encoded.
2. **One rule for the question and the cut** (`VideoEncodePolicy.cuts`). Past `MAX_DURATION_MS` the composer asks
   "Send the first 61 seconds?", naming the clip's own length (`ChatViewModel.longVideo`), and encodes only on Send
   (`sendLongVideo`). Cancel, Back or a tap outside stages nothing (`dismissLongVideo`). The plan's `clipEndMs` reads
   the same rule, so the question is asked exactly when the encode would cut. A length the container does not give
   falls to the encode as before, which reads the video itself and says what it makes of it.
3. **The toast and `Ingested.trimmed` are gone**: the sender agreed to the cut before it was made.

A pick from the file button asks the same question and keeps its button for the encode after a yes, so a video the
encoder then cannot read still goes as a file. Still no trim UI: the choice is the first 61 s or nothing, and in a DM
the original goes by Direct file send. Guards: `VideoEncodePolicyTest.theComposerAsksAboutExactlyTheClipsTheEncodeCuts`,
`AttachmentStoreVideoTest.aPicksLengthIsReadOffItsContainerWithoutAnEncode`,
`ChatViewModelTest.aVideoPastSixtyOneSecondsAsksBeforeAnyEncodeAndOnlyAYesEncodesIt` and
`ChatScreenContentTest.aLongVideoAsksWithItsLengthAndBothAnswersReachTheViewModel`. Device trial owed: a two-minute
pick from each button asks before the encode tile shows, Cancel leaves the composer empty, and Send stages the first
61 s.
