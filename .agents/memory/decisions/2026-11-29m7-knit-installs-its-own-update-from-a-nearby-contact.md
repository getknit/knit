---
id: "2026-11.29m7"
slug: knit-installs-its-own-update-from-a-nearby-contact
title: "Knit installs its own update from a nearby contact"
date: 2026-11-20
topics: [transfer, release, wire, security, privacy]
---

# ADR 2026-11.29m7 — Knit installs its own update from a nearby contact

Status: Proposed (2026-11-20). Narrows ADR 2026-09.qq2r point 1 ("Knit never grants an install intent") for one
package, its own. Built behind `BuildConfig.PEER_UPDATE` (on in debug, off in a shipped artifact) and JVM- and
lab-tested; the security design pass ran on this record; the device trial (below) is owed before a release turns it on.

**What was asked.** Two people in radio range, one running an older Knit, should be able to bring the older phone up
to date without an Internet route. The older phone shows a hint ("Alex has Knit 2.14.0. Ask for it?") in that
contact's chat. A tap sends a request, the newer phone's user answers Send or Not now, the APK crosses over Direct
transfer (ADR 2026-09.wtmz) at about 70 MB/s, and one tap installs it and restarts Knit. Decided with the maintainer
on 2026-11-20: the hint lives in the DM, only the older side asks (v1), only accepted contacts take part, and the
install is one tap followed by a silent self-update.

**What stood in the way.** Three standing rules keep Knit from being an install source: qq2r point 1, `TransferCard`'s
"Knit must not become the install source" (a received package never gets Open), and the roadmap's bar on any provider
exposing an install-capable grant for a package. Their reason holds: a mesh that hands APKs around is a sideloading
channel, and the platform's unknown-sources gate must stay the last word on any package Knit carries. This decision
keeps that for every package but one.

## What changes

- **Knit installs exactly one package, its own**, and only from its own private staging, through a `PackageInstaller`
  session with `setAppPackageName(own)`. Nothing is ever exposed through the FileProvider for it, no install intent
  names a file, and a received `.apk` of any other kind keeps today's treatment: saved to Downloads, a warning, no
  Open. The platform still refuses an update signed by another key (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`) and any
  versionCode at or below the installed one, so Knit's own checks are early, legible refusals, never the only guard.
- **The signal is a link-local sealed ctl, `CTL_APP_UPDATE` (12)**, payload `au`: ANNOUNCE, REQUEST and DECLINE. It
  takes the shape `CTL_PRESENCE_KEY` already has (ADR 2026-11.xp6d): sealed under a confirmed session only, signed
  `relay = false` and handed to the live link (`router.sendOwn(…, live = true)`), so the router never floods it, no
  carrier custodies it and no spool is pushed it. It reaches only a contact whose link is up, which is exactly the set
  this feature serves, and it costs the mesh nothing beyond the link it crosses. An older build that opens one drops
  the unknown code silently, as `handleCtlDm` does for every code it does not know.
- **The receiver acts only on the copy its sender's own link carried.** `relay` sits outside the signed bytes, so the
  contact, or a device in the middle of a legacy link, can mark a captured frame for relay and flood it, and a flooded
  copy would pass a "the sender is a neighbour" check whenever the contact happened to be near. So the pipeline carries
  `InboundFrame.onLink` down to the ctl dispatch, and an `au` frame counts only when it came in on a link whose peer is
  its sender, with `relay = false`, from a contact whose link is proof-capable (ADR 2026-11.264w): the
  `BLOB_CONTROL_NOT_FROM_LINK` gate, applied to a sealed frame. Anything else is dropped and counted
  (`APP_UPDATE_NOT_FROM_LINK`), and so is one from a contact this phone would not announce to (blocked, unproven, or
  taking no direct transfers). The frame's signed time is never read: it is the sender's clock, and phones offline for
  weeks drift (ADR 026), so a five-minute bound would have silenced a drifted pair. A replay never reaches the handler,
  since the ratchet refuses a consumed index as a duplicate; the own-clock TTLs and the request ids the newer phone
  keeps for the life of a request bound the rest. A blocked contact is never announced to (a blocked thread stays
  accepted, so the block list is read on its own), and a block forgets whatever was under way with that contact. There is no capability bit
  (ejn8's "app logic only"); sending is gated on the pinned `CAP_DIRECT_TRANSFER`, which iOS does not carry.
- **ANNOUNCE.** A phone that can serve tells each accepted contact on a live link its build: versionCode,
  versionName, an 8-byte pin of its signing certificate's SHA-256, the APK's size, minSdk and the slim track. It goes
  once per contact per 24 hours, and again when this phone's own versionCode changes (an update restarts the process,
  and the cooldown lives in memory). It waits until the contact has stayed on the link 30 seconds, and while the
  contact stays, an announce that could not go (the thread not yet accepted, the session not yet confirmed) is tried
  again every ten minutes. Serving needs the feature built in, a single-APK install (not a Play split install, whose
  merged copy is re-signed with a per-phone key), and a phone that may host (`DirectHostGuard`, ADR 2026-10.w3dw).
- **The hint shows only for an update that can install** (`UpdateEligibility`, pure): a higher versionCode, the same
  certificate pin, the same track (slim or full), a stable build only when this one is stable (a pre-release user takes
  any newer build), a minSdk this phone meets, the contact still in short range, and that versionCode not dismissed.
  An `au` frame from a contact out of range is dropped on arrival.
  After Ask, the line says the ask is waiting; once the newer phone answers, the line goes and the incoming transfer's
  card carries the rest (amended after the 2026-11-20 trial, which showed both saying the same thing).
- **REQUEST, then the newer phone's answer.** Ask runs the one-time direct-transfer disclosure if it has not been
  given, sends the user to "Install unknown apps" for Knit if that is off, checks free space against the announced
  size, then sends REQUEST with a fresh request id and the announced versionCode. The asking phone keeps the
  announce it asked from: the version, the size and the contact. The newer phone shows a notification that opens the
  DM, and a card in the thread with Send and Not now; the notification carries no actions of its own, so the first
  Send can still show the one-time disclosure. (Amended after the 2026-11-20 trial: a card where the ask arrived, drawn
  like a transfer card, replaced a band at the top of the chat.) One request per contact is live at a time; after Not now (a DECLINE) that
  contact is quiet for an hour, and an ask in that hour is answered Not now without asking the person again.
- **The bytes are an ordinary Direct transfer** with one additive field, `TransferPayload.ap`: the request id, the
  versionCode and the APK's full SHA-256. The sender reads its installed base APK in place. The receiver accepts an
  `ap` offer **by itself only when it answers a request this phone sent**: the sealed sender of the `CTL_TRANSFER` is
  the contact asked, the request id and versionCode are the ones asked for, the offer's size is at most the announced
  size (else the 8 GiB transfer ceiling would govern an offer nobody tapped), and it is within ten minutes. Each request
  is spent by the first offer that matches it; the tap on Ask was the answer. Any other `ap` offer is dropped. This departs from ejn8's "no automatic answer" on
  purpose: that rule stops a contact probing what a phone holds, and here the requester has already said what it
  wants.
- **The sink is the gate.** The APK stages under `noBackupFilesDir/update/<transfer id>/`, a directory named after
  the transfer (a canonical `FrameId`, checked on every phase), and a sink is refused for a directory that already
  exists, so a second update transfer can never write over one already checked, and no name read off the wire reaches a
  path. `TransferSink.check()` runs before the verdict, so any refusal
  reads as corrupt on both cards. It confirms the SHA-256 against `ap`, and from `getPackageArchiveInfo` the package
  name, the versionCode (equal to `ap`'s, above the installed one, stable for a stable phone, the same track) and
  signers equal to the installed ones. `commit()` marks it staged; it is never published to Downloads. Parsing the
  archive runs the platform's zip and manifest parsers in Knit's process over bytes the contact chose; it happens only
  after the person tapped Ask, so it is not zero-click, and it reads bytes the hash already matched to the offer.
- **Install and restart.** The installer takes only a path it builds itself under `noBackupFilesDir/update/`, never a
  URI, so nothing that arrives as a share can reach it. The card's "Install and restart" opens the staged file once and
  writes the session from that one descriptor, hashing the bytes as they go into `openWrite`; a hash that no longer
  matches `ap` abandons the session. It then writes a self-update marker (the target versionCode and the time) and
  commits with `USER_ACTION_NOT_REQUIRED` on API 31+. On 29 and 30, or when an update owner holds the package
  (API 34+), the platform's own dialog shows.
- **The install status comes back through a door only the installer can use.** Its target is a dedicated receiver,
  `android:exported="false"` with no intent filter, reached by an explicit `PendingIntent` (`FLAG_MUTABLE`, since the
  installer fills its extras; request code = the session id). It acts only on the session id Knit created and
  recorded in the staging's pending record (a status for any other session does nothing). On
  `STATUS_PENDING_USER_ACTION` it launches `EXTRA_INTENT` only when that intent's action is
  `android.content.pm.action.CONFIRM_INSTALL` and it names no component of Knit's, with its selector, every
  `FLAG_GRANT_*` flag and any `ClipData` stripped first. Without these, any app could hand Knit an intent to launch under Knit's identity, reaching its
  unexported screens and the FileProvider's crash, field-log and APK grants (lint's UnsafeIntentLaunch). Staging and
  the marker are cleared only on a status from that session, and on every failure status as well as after success, so
  a 100 MB APK never stays behind. The update kills the process; the new build's `BootReceiver`
  already restarts the mesh on `MY_PACKAGE_REPLACED` (ADR 2026-11.f8pg), and with a marker naming this versionCode,
  written within the last ten minutes, it also clears the staging and brings the UI back. When the platform refuses a
  background activity start it posts a notification ("Knit updated to 2.14.0. Tap to open"), never a full-screen intent
  or an overlay. A later update from a store finds no matching marker and relaunches nothing.
- **Never in the Play build, and dark in a shipped one for now.** Play forbids an app updating itself outside Play,
  and a Play install could not serve or take one anyway (Google's key, split APKs). `REQUEST_INSTALL_PACKAGES`,
  `UPDATE_PACKAGES_WITHOUT_USER_ACTION` and the status receiver come from a variant manifest
  (`app/src/peerUpdate/AndroidManifest.xml`) added only where `BuildConfig.PEER_UPDATE` is on: in debug by default, in
  a shipped artifact only with `-PpeerUpdate=true`, and never when a `bundle*` task is requested. The defaults live in
  source, so F-Droid's `-P`-free rebuild stays byte-identical. At runtime a Play install neither serves nor installs.

## Rejected

- **The build version in the link HELLO or the advert.** It would cost no frame, but the HELLO and the advert reach
  strangers, and an exact version is one more attribute the rotating advert tag (ADR 2026-11.xp6d) was built not to
  leak. The HELLO is also owned by two transport planes.
- **ANNOUNCE as an ordinary ctl DM** (`sendCtlDm`, the transfer signals' path). It is custodied and flooded, so every
  contact pair in range would add a custody row and a spool upload a day for a fact only the link needs.
- **A field on the sealed profile.** It floods, is custodied, and would re-publish the profile on every update.
- **A capability bit.** The ctl code is additive by itself, and `CAP_DIRECT_TRANSFER` already gates who can take the
  bytes.
- **Installing as soon as the APK verifies.** Knit would close under the person mid-sentence.
- **Opening the APK through the FileProvider with `ACTION_INSTALL_PACKAGE`.** It is the grant the roadmap bars, and it
  would make Knit an install source for whatever file the intent named.

## What it leaks, and to whom

- **ANNOUNCE** tells each accepted contact on a live link this phone's exact build and track, and that it can serve.
  Capability bits already tell any peer roughly which release a phone runs; this is the exact one, to contacts only.
  Its certificate pin also marks a debug or self-built Knit, which singles out developers and testers to their
  contacts.
- **REQUEST** tells the chosen contact the requester runs an older build.
- **On the air**, the transfer is WPA2, but release sizes are public, so a listener in range can tell which release
  crossed, and so that the requester ran an older one, perhaps one with known holes.
- **On the phone**, the installer of record becomes `app.getknit.knit`, which any app can read through
  `getInstallSourceInfo` without a permission: every app learns this phone took an update from a peer.
- **What is kept.** Announces heard, asks and requests live in memory only and end with the process. The newer phone
  keeps one row per ask it put to the person (`MessageEntity.KIND_APP_UPDATE_REQUEST`, `UpdateRequestRecord`: the
  request id, the versionCode asked for and how it ended; id `upd:<asker>:<request id>`, written through
  `UpdateRequestRows`), so the card outlives the ask: a waiting card reads as expired once the ask is gone, and Send
  shows only while the ask it answers is live. Once Send has started the transfer, that card follows it and the thread
  draws no second card: the sender's transfer row keeps the request id (`AppUpdateRecord.requestId`), and
  `ui/chat/foldAskedTransfers` folds it into the ask's card (`AskedTransfersTest`). The card's own word for Send is
  "You accepted", never "sent", since the bytes have not moved yet. An ask answered without the person (the quiet hour, a version this
  phone does not run, a replay) gets no row. The row and the transfer's ordinary `xfer:` row are local, never on the
  wire, and a contact's removal deletes both with the thread. The field log gets one line per phase with the reason
  and the contact's short id, never a version name.

## What it costs, and what it does not cover

- **Bootstrap.** Both phones need a build with this decision. The first release that carries it can only be updated by
  a later one, and every phone before it still needs "Install offline" or a store.
- **Signers.** Only installs under one key update each other: `knit-dist` to `knit-dist` (F-Droid, GitHub, sideload,
  an "Install offline" copy from one of those), and a debug build to builds from the same machine. A Play install, and
  a phone installed from a Play user's "Install offline" copy, never see the hint.
- **"Install unknown apps".** The person grants it to Knit once, in system Settings. The grant itself is for any
  package: this code only ever installs its own, but code execution inside Knit (a decoder bug, a compromised
  dependency) could now open an install session for another package. The platform still prompts for a new package, and
  the grant is the person's to revoke.
- **The installer of record becomes Knit.** After a self-update `installingPackageName` reads `app.getknit.knit`, so
  `InstallSource` maps it to its own value rather than letting an F-Droid user's surfaces change by surprise.
- **One transfer at a time**, as for files; a phone busy with a call or another transfer answers DECLINE.

## Guards

`UpdateEligibilityTest` (stable versus pre-release, track, certificate, minSdk, versionCode order),
`PeerUpdatesTest` (announce cooldown and retry, a flooded, distant or non-candidate frame dropped, a block forgetting
the contact, one live request, the hour of quiet, the request TTL, a request spent once), `TransferAppUpdateTest` (an
expected `ap` offer accepts itself; an unsolicited one, or one to a build without peer updates, is dropped; a bad hash
or a refused archive reads corrupt on both cards; a malformed pin never spends the ask), `UpdateEligibilityTest` (the
archive's checks), `UpdateStagingTest` (paths, staging and the status policy: a foreign session id and a non-installer
`EXTRA_INTENT` launch and clear nothing), and a
mesh-lab scenario from ANNOUNCE to an `ap` offer ending in `assertConverged`, with a captured REQUEST re-served flooded
after ten minutes and dropped. The golden vectors pin the `au` and `ap` bytes.

## Open risks

- **F-Droid review** may question `REQUEST_INSTALL_PACKAGES` in a build it ships. The maintainer confirms before a
  release carries it.
- **The UI relaunch is unproven.** Background-activity-start rules may leave only the notification; the trial records
  which.
- **Update ownership (API 34+).** If F-Droid's client claims ownership, the install shows the platform's prompt rather
  than completing silently.

## Trial owed

Two phones under one debug key, one on this branch and one on the same branch with a local, uncommitted versionName
bump. The hint shows on the older phone only while the two are in range; Ask reaches the newer phone as a notification
and a card in the thread; Send starts the transfer; the card offers Install and restart; one tap installs silently on API 31+ (the
platform dialog on 29 or 30 if a phone is available); afterwards the mesh is back and the UI relaunches or the
notification shows. Negative cases: a release-key build beside a debug-key build shows no hint, a stable phone beside a
pre-release shows none, Not now reads on the asker's hint and keeps that contact quiet, and
`adb shell am broadcast -n app.getknit.knit/<status receiver>` with a forged `STATUS_PENDING_USER_ACTION` fails because
the receiver is not exported.
