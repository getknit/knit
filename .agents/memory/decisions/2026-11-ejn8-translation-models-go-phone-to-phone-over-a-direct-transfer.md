---
id: "2026-11.ejn8"
slug: translation-models-go-phone-to-phone-over-a-direct-transfer
title: "Translation models go phone to phone over a direct transfer"
date: 2026-11-02
topics: [transfer, ml, wire, privacy]
---

# ADR 2026-11.ejn8 — Translation models go phone to phone over a direct transfer

Status: Proposed (2026-11-02). Built behind `-Ptranslate=true` and JVM-tested; the device trial (below) is owed.

**What was asked.** A translation direction (ADR 2026-10.t2h6) is a 21–36 MB download from getknit.app. Knit is
used where there is no Internet, and the phone beside you often has the model already. A person should be able to
send one model or several to a nearby contact over Direct Send (ADR 2026-09.wtmz), which moves about 70 MB/s.

**What changed.**

- **A bundle is a tar of pinned files.** The sender picks installed directions in a checklist reached from the DM's
  "Direct file transfer" item, which asks "A file / Translation models" when this phone holds a model. The bytes are
  one deterministic POSIX ustar archive, `knit-translation-models.tar`, holding `<direction id>/<file>` for every
  pinned file (`translate/ModelArchive`: mode 0644, no owner, time zero, so one plan is one byte string). The existing
  `TransferStream` carries it unchanged: proof, header, sealed chunks, trailer, verdict.
- **The sender is trusted with nothing**, as getknit.app is not. The receiver takes only directions its own generated
  table pins, writes the archive it expects from those pins, and compares what arrives against it
  (`translate/ModelBundleSink`). Every header, padding and trailer byte must match, and every file's bytes are hashed
  as they land in a staging directory. `TransferSink.check()` runs after the stream's own trailer verifies and
  before the verdict byte goes back, so a pin failure reads as corrupt on both cards. `SttModelInstaller.adopt` then
  checks each file again and renames it into place, marker last.
- **No capability bit.** A models OFFER is a whole file offer (name, size and `application/x-tar`) with one additive
  field beside it, `TransferPayload.md`: the directions' pins, each the first four bytes of the weights' SHA-256 as an
  unsigned 32-bit number, which is also the 8-hex suffix of the direction's id. A build that ignores `md` (an older
  one, a build without translation, knit-ios) takes the offer as the archive file it also is, and its ACCEPT, naming
  no pins, is sent the whole archive. A build that can install models answers with `md` set to the subset it lacks,
  so it is sent only those. Two pins cost 14 bytes on a frame (`MessageContentTest` pins it), and a plain file offer
  encodes as it did before (the golden vector `messageContentTransferOffer`).
- **No automatic answer.** A models offer always shows its card: what is new, what is already on the phone, and
  what another version of Knit uses. It offers Decline only when nothing is new. The card's counts are local reads.
  An automatic decline ("already have them") would have let any contact read which languages a phone holds, one
  offer per model, with only the misses leaving a trace.

**Once every byte is out, the verdict decides the sender's card.** This is the maintainer's call, made 2026-11-02, and
it applies to every direct transfer, a file or models alike. The receiver writes its verdict before it installs (or
publishes) what arrived. If that install fails, it fails its own card and sends a CANCEL, and before this rule that
CANCEL raced the sender's Done: the sender's card read Done or Failed depending on timing.

Now the sender raises `awaitingVerdict` as the last chunk goes out, before the trailer, so neither the verdict nor a
CANCEL sent after it can arrive first. A CANCEL that lands while it is raised is kept (`peerCancel`) and used only if
no OK verdict comes: an OK verdict ends Done, a corrupt one Failed, and none at all ends with the CANCEL's reason.
`TransferManagerTest.aCancelAfterTheLastByteWaitsForTheVerdict` holds the verdict back while a CANCEL lands.

**Rejected.**

- **A capability bit (`CAP_MODEL_SHARE`).** It was the first design. The maintainer preferred app logic only: with
  the offer shaped as a file, an older phone needs no gate, because it simply receives a file.
- **The files end to end, with nothing between them.** It is byte-cheaper, but an older phone would save a blob that
  nothing opens. The tar costs 512 bytes a file, about 1.5 KiB in 36 MB per direction, and opens anywhere.
- **Encoding the pins in the file name**, which would avoid any wire change. The receiver could not ask for a subset,
  and the model list would ride in peer-written text.
- **Pins as full ids or hex text.** They cost 50 and 21 bytes for two pins, against 14.

**Hardening from the design review.**

- A peer's transfer id never reaches a path: `onSignal` refuses any id that is not a canonical `FrameId`, in every
  phase. This also closes the `xfer:` row namespace to crafted ids, which nothing validated before
  (`MessageContent.normalized` cleans only `xf.name`). The staging directory is a UUID this phone mints, under
  `noBackupFilesDir/translate/incoming/`. The store sweeps its children at first start, never a name read from
  anywhere.
- A models OFFER from a contact out of range is dropped (`TransferManager.peerNearby`). A `CTL_TRANSFER` rides every
  DM path, mesh and spool alike, and an honest sender must be nearby to host the group.
- Pin lists are bounded at 64 entries, each in `0..0xFFFFFFFF` and none repeated, on the OFFER and the ACCEPT
  (`OfferReading`). A sender reads an ACCEPT's pins only through its own offer's list, and sizes them from its own
  pins.
- Staging never outlives its transfer. `finish` discards any uncommitted sink under `NonCancellable`, which covers a
  cancel mid-stream and a commit that throws after the verdict.
- Adopting a direction runs as its store slot's job. A download already fetching that direction finishes it instead,
  and a Download tap during the adopt returns early.

**What it leaks, and to whom.**

- An OFFER tells a chosen contact which languages the sender has models for.
- An ACCEPT, sent only after a person taps, tells the sender which offered models the receiver lacked.
- On the air the group is WPA2, but anyone in range sees roughly how many bytes cross. Direction sizes are public
  and distinct, so the archive's length can name the set sent. No padding is planned.
- MPL-2.0: each direction's `LICENSE` is one of its pinned files, so it travels in the archive unmodified.

**What it costs.**

- **Space.** The receiver stages a bundle beside its final directory (the same volume, so a rename installs it),
  and the free-space check counts the archive.
- **Volume.** One transfer at a time, as for files.
- **No ask.** The receiver cannot ask a contact for a model it lacks. That needs a request phase and a holder query,
  and is deferred.
- **Translation only.** The speech model could ride the same shape later, since `TransferModels` is pinned files.

**Trial owed.** Run it on two translate builds, P7 → P9, with no Internet route on the receiver:

1. `…debug.XFER --es to <peer> --es models all`. Confirm both phones Done and the receiver's directions Ready
   (`…debug.TRANSLATE`), then translate a message.
2. Repeat with the receiver already holding one direction. The ACCEPT names the rest, and only those cross.
3. Send to a build without translation. It saves `knit-translation-models.tar` to Downloads, and `tar -tf` lists the
   files.
4. Check the card, the sheet and the receive-side consent line by hand.
