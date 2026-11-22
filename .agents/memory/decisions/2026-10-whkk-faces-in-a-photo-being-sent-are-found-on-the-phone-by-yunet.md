---
id: "2026-10.whkk"
slug: faces-in-a-photo-being-sent-are-found-on-the-phone-by-yunet
title: "faces in a photo being sent are found on the phone by YuNet, at the photo's own size"
date: 2026-10-30
topics: [privacy, ml, attachments]
---

# ADR 2026-10.whkk — faces in a photo being sent are found on the phone by YuNet, at the photo's own size

Status: Accepted (2026-11-19; shipped in every build type but the slim APK from 2.13.0, the 2026-11-19 amendment).
Proposed 2026-10-30 as an experiment on `exp/face-blur`; Pixel 3 trials passed 2026-10-30 and 2026-11-12.

**What was observed.** People who share photos from rallies, and Ukrainians who post photos of people to tell a
story, hide faces so that nobody who recognises someone can target them. They do it with tools that need the
Internet, or not at all. Knit can do it offline at the moment a photo is sent. The agreed shape (2026-10-30): a global
"redact faces in photos I send" setting, off by default; a photo taken in Knit's camera (ADR 029, already held in
memory only) is stored redacted for good; a photo picked from the gallery is redacted as it is attached, and its
original stays in the person's gallery. Redaction is an opaque fill, never a blur: pixelated and blurred faces are
re-identified by neural networks (McPherson, Shokri and Shmatikov, 2016). A review screen before sending shows the
boxes, so a missed face can be added by hand and a wrong one removed. Detection is never claimed to be complete.

**What changed: the detector.** `redact/YuNetFaceDetector` runs YuNet (opencv_zoo, MIT, 75k parameters) on the
LiteRT interpreter the moderators already use: no new runtime, 239 KB in the APK. The 2026may ONNX takes any input
size; `scripts/face-model.py build` converts it with onnx2tf 2.6.9's TensorFlow-free `flatbuffer_direct` backend,
deterministically, and pins the result's SHA-256 (`scripts/face-model.py` exits 1 on a different asset).
`redact/YuNetDecoder` is OpenCV's `FaceDetectorYN` post-processing in pure Kotlin. The detector is leased like the
moderators (ADR 2026-09.cq9z) with a one-minute idle, because the arena grows to the largest photo seen, and loaded
under `ModelLoadGuard.FACES` (ADR 037); a load whose probe inference fails is a failed load, so `detect` answers null,
never an empty list that would read as "no faces".

Measured on WIDER FACE's validation set (3,226 photos, 39,112 faces; its CC BY-NC-ND licence allows this local
evaluation, and nothing of it ships). A face counts as covered when 70 % of its box is under the detector's box grown
1.3 times. The protest-like categories (Parade, Demonstration, Riot, People Marching, Street Battle: 492 photos,
15,697 faces) cover:

- faces 16 px and taller: 89 % at the photo's own size plus half its size, threshold 0.3 (the quarter pass below
  only adds large faces); 81 % at its own size
  alone, threshold 0.5; 69 % shrunk into a 640 px square, threshold 0.5 (what most sample code does); 16 % at OpenCV's
  demo threshold of 0.9. The same 0.3 setup covers 93 % of faces 24 px and taller over all photos.
- every face 16 px and taller in one photo: 48 % of protest photos and 77 % of all. The review screen is not optional.
- close-ups of 300 to 2000 px in a 1280 px frame (crops of WIDER's larger faces): 92–96 % at the photo's own size,
  98 % with the half and quarter passes, which add about a third to the full-size pass's work.

So the detector looks at the photo as it is, padded to the model's 32 px grid and never squeezed into a fixed square,
then at half and a quarter of its size, at threshold 0.3, with one NMS over all three. Faces under 10 px are about half
missed, and are also hard to recognise at the 1280 px Knit sends. Most of what the WIDER count calls false positives in
dense crowds were real faces its annotators skipped (two photos inspected). A fourth pass at twice the size covers 93 %
of protest faces of 16 px and up, against 89 %, for four times the work: a "find more faces" button, not the default.

The alternatives: ML Kit's face detection is closed and pulls Google Play services, which the app does not ship
(`context/toolchain.md` bans `camera-mlkit-vision` for the same reason); MediaPipe's BlazeFace takes a 128 or 192 px
input meant for a few faces near the camera, and `tasks-vision` was already passed over for LiteRT; InsightFace's
SCRFD weights are for non-commercial research only; `android.media.FaceDetector` finds frontal faces and misses small
ones.

**What changed: the redaction.** `redact/Redaction` is the review screen's whole state, pure and JVM-tested: each
found face becomes a cover grown 1.3 times about its centre (what the coverage above was measured with) and clipped to
the photo; a tap on a found cover uncovers it and a second covers it again, a tap on an added one removes it, a tap on
open photo adds a cover the median found face's size (8 % of the short side, 1.25 times as tall, when none was found),
and a drag adds the rectangle it swept. `redact/FaceRedaction` fills each covered cover on a copy of the photo with one
opaque colour, the average of the photo under it (`Cover.fillColor`: up to 64 by 64 points read evenly over the
cover, weighted by alpha, read from the photo and not the copy so overlapping covers never average each other), with
the same rounded corners the screen draws. Average rather than black was the user's choice (2026-10-30): softer to look
at, no easier to undo, at the cost of giving away the hidden area's mean colour (a skin or hair tone, not a face).
It encodes the copy as JPEG at the attachment pipeline's
own q85 (`PHOTO_JPEG_QUALITY`, shared with `AttachmentStore`), so `AttachmentStore.passThrough` would send it as its
own bytes: one generation of loss, no metadata. `ui/redact/RedactReviewScreenContent` shows exactly what will be sent,
a covered face in the fill it will get, computed by the same `fillColor` over the same pixels, and an uncovered one as a dashed outline; one finger taps or drags, two zoom (up to 8
times) and pan; editing waits for the search; every cover is also a semantics node, named and toggled for TalkBack.

**What changed: the setting and the doors.** `SettingsStore.coverFacesEnabled` (Settings › Chats, off by default,
rides a backup) sends every still photo through `ui/redact/PhotoReview`, the review's state holder, before anything is
staged: the camera's shot (decoded from memory, so its uncovered pixels never reach the blob store), the photo picker,
the keyboard and the share sheet (`ChatViewModel.attach`), and a photo picked with the file button (`attachFile`, after
the file door's own refusal). `AttachmentStore.pictureOf` tells a still picture from a moving one by its bytes. Through
the photo door anything that is neither a video nor an animation is reviewed, even a pick the store cannot identify; a
photo that will not decode, or whose provider throws, is dropped with "couldn't be opened", never sent uncovered. A
video or an animation goes as it is, with a notice that its faces are not covered. The Meshtastic room and a text-only
commons refuse a photo before its review rather than after. `PhotoReview.finish` hands back only the covering the
person last saw, and nothing from a review cancelled or replaced meanwhile; the bytes then enter the camera's in-memory
door, `AttachmentStore.ingest(bytes, mime)`.

**What changed: the direct transfer (2026-11-12).** A file sent over Wi-Fi Direct (`ChatViewModel.offerTransfer`)
went out uncovered, the one door the setting missed. With Cover faces on, a still photo picked there now opens the same
review, after the transfer's own refusals, and goes as its covered copy. The copy is the one size the detector and its
rules were measured at, the chat photo's 1280 px (`PHOTO_MAX_DIMENSION`), so a photo sent this way is no longer the
full-size original; running the detector on the original instead would change what the merge rules were tuned on. A
transfer reads its source by URI when the link comes up, and again on a Retry, so `redact/CoveredCopies` writes the
covered bytes, and only those, to a folder of their own under the app's cache, named after the original with the
encoded type's extension (cut to the 255 bytes a file name takes), and the offer names that `file://` copy; the
original is never copied. It is the one exception to Knit never keeping a plaintext picture in its cache, and it holds
only covered pixels. `TransferManager` lets a source go once its transfer cannot be retried (Done, or declined by its
receiver: `TransferFiles.release`), which deletes a covered copy and leaves a picked document alone; any copy older
than a week is deleted when the next is written or a chat opens; and the system may clear the cache sooner. A Retry
after that finds the source gone, as for a document deleted since the pick. Removing a chat or a contact does not
delete a copy still in its week. A video or an animation goes as it is with the notice, and any other file as
before. `PhotoReview` carries what a review is for (`Purpose.Attach` or `Purpose.Transfer`), so a review replaced by
another hands its photo to the door that opened it. Tests: `CoveredCopiesTest`, `PhotoReviewTest`, `ChatViewModelTest`'s
transfer cases, `TransferManagerTest`'s released sources.

**What changed: fewer false covers (2026-10-30).** On the Pixel 3 the user's own photos drew covers far from any face:
on a shirtless selfie, boxes the size of a chest, at scores of 0.31 to 0.46, from the full-size pass, two to three
times the largest face it is built for; on a 300 px photo, the quarter pass, run at 75 px, covered a man's stomach and
put a box around a woman's face with another inside it, because NMS by IoU keeps a small box inside a large one. Four
rules now merge the passes (`YuNetDecoder.merge`): a box of 80 px or more from the full-size or half-size pass must
score above 0.5 (98 % of those passes' large boxes on a face did; two thirds of their large boxes on no face did not);
the quarter pass reports no face under 40 px of its own (smaller ones are the full-size pass's, and there it was mostly
noise); no reduced pass runs below 320 px (`MIN_PASS_EDGE`); and a box lying more than 60 % inside a larger one is
dropped, the larger one covering it already. On WIDER FACE, measured as before (now through all three passes, as the
phone runs them, at its 1280 px bound): boxes of 80 px and more on no face fell from 1,149 to 309, faces of 16 px and
more covered went from 91.5 to 90.8 % (protest photos 89.8 to 89.1 %), and every face of 16 px and more in a photo
from 77 to 75 % of photos. Synthetic close-ups, faces of 300 to 2000 px enlarged from smaller WIDER faces and so softer
than a real close-up, went from 97–98 % to 93–97 %. The landmarks did not help: the false boxes kept their five points
inside and in a face's order. Tests: `YuNetDecoderTest`'s merge cases.

**What it costs.** The tested runtime match is LiteRT 1.4.2 on an x86_64 API 33 emulator: a warm detection of the
768 px test photo enlarged to 1280 x 1023, three passes, took 153 ms there (the cost of a photo that size). On a Pixel 3 (Snapdragon 845, 2018, Android 12; three runs) the same took
417–473 ms, the 768 px photo 145–198 ms, and the first detection, load and probe included, about 20 ms more than a
warm one: under half a second for a full-size photo on a budget-class phone, once per photo attached. The input buffer for a 1280 px photo is about
20 MB of direct buffer, which ART puts on the Java heap, plus the interpreter's native arena, for the length of one
detection and then the lease's minute. YuNet itself was trained on WIDER FACE, as most open face detectors were. The
guards: `YuNetDecoderTest` holds the decoder to OpenCV's own faces for a recorded inference
(`app/src/androidTest/assets/redact/yunet_heads.b64`), and `YuNetFaceDetectorInstrumentedTest` holds the bundled model
on ART to ONNX Runtime's outputs and the whole detector to the reference pipeline's faces in NASA's public-domain
Expedition 75 crew portrait. `RedactionTest`, `ViewTransformTest`, `FaceRedactionTest` (Robolectric's native graphics: under every cover the average of what it hides,
every pixel outside unchanged, the source untouched) and `RedactReviewScreenContentTest` (taps and drags reach the
model in the photo's pixels) run in CI; on ART, a crew portrait covered where the detector found faces has none of
them left for it to find. `PhotoReviewTest` forces each interleaving (a cancel during the encode, a second photo over the first's search) and
`ChatViewModelTest` drives the doors. Neither the instrumented package `redact` nor a phone is in CI yet.

## Amendment 2026-11-13: a build flag, off in a shipped artifact

The feature and its code are behind `-PfaceRedaction=true|false` (`BuildConfig.FACE_REDACTION`, read once as
`di/FACE_REDACTION_WIRED`): ON in debug, OFF in a release, staging and the slim APK, so F-Droid's `-P`-free rebuild
ships none of it. The model moved to `app/src/faceRedaction/assets/redact/`, which `androidComponents` adds to a variant
only where the flag is on, so a release without it does not carry the 240 KB asset. Off, `faceRedactionModule()` is null
(no `FaceDetector` is defined), `ChatViewModel` holds no `PhotoReview` and every photo door skips the review whatever
`SettingsStore.coverFacesEnabled` holds, `ChatScreen` never shows the review, Settings › Chats hides the row,
`AndroidTransferFiles.release` deletes nothing, and the Licenses list hides YuNet's notice (`Packaging.FACE_REDACTION`).
R8 then drops `redact/` and `ui/redact/`. The setting key stays in the store, so a phone that turned it on in a build
with the feature keeps the choice for one that has it again. No library is added: the detector runs on the LiteRT the
classifiers already package, so the lock state does not move.

## Amendment 2026-11-19: on in a shipped artifact, and Privacy-first turns it on

The experiment is now a feature. `-PfaceRedaction` defaults ON in every build type and OFF only in the slim APK, as
translation did at 2.11.0 (ADR 2026-10.t2h6), so F-Droid's `-P`-free rebuild ships the model and the review. The model's
weight is checked at build time beside the moderation models (`checkModerationModels`: at least 200 KB, never a Git LFS
pointer), because a stub would review with no face found, silently. Cover faces stays off by default; the Privacy-first
starting point (ADR 2026-11.j8pf) supplies it on, through `orPreset(PresetSetting.COVER_FACES)`, and its card says so.
A switch away from Privacy-first that turns it off lists that first, as it lists the screenshot block. Indonesian, which
shipped after the experiment, has its strings by hand; native review of every locale's "cover" is still owed.

A photo cut short counts as one that will not decode. The decode under the review fills what a truncated file never
reached with blank and answers a photo, so the review would have shown and sent its top. Every review door now decodes
through `decodeWholeOriented`, which first asks the platform's `ImageDecoder` (no partial-image listener, so it throws
where the stream runs out) and drops the photo with "couldn't be opened" when the answer is no.
