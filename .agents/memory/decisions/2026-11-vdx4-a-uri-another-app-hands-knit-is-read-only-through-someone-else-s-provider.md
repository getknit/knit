---
id: "2026-11.vdx4"
slug: a-uri-another-app-hands-knit-is-read-only-through-someone-else-s-provider
title: "A URI another app hands Knit is read only through someone else's provider"
date: 2026-11-01
topics: [security, attachments, privacy]
---

# ADR 2026-11.vdx4 — A URI another app hands Knit is read only through someone else's provider

Status: Accepted (2026-11-01)

**What was observed.** #284, found by reading the code while writing the security-reviewer persona.
`MainActivity.handleShareIntent` handed any `ACTION_SEND` `EXTRA_STREAM` URI to `ShareInbox`, and the chat read it
with `ContentResolver.openInputStream`. That read runs as Knit, not as the sender: a `file://` URI is opened by Knit's
process, and a `content://` URI on one of Knit's own authorities is served by Knit's own provider. So another app,
holding no permission at all, could name `files/datastore/knit_settings.preferences_pb` (the block list, a private
relay's bearer token, the spool ticket) or `<package>.fileprovider/fieldlog/…` and have it staged in a chat, one tap
from going to the attacker. The sender needs no grant: `ACTION_SEND` adds the read flag itself, and the grant check
passes because the target, Knit, already holds access to its own provider.

Two doors were wider than the issue said. The composer's `Modifier.contentReceiver` takes URIs from a paste, a
drag and a keyboard's commit-content, and any app can put a clip naming Knit's provider on the clipboard. And the
image route was no safe harbour: `FileIntake` holds received avatars and attachments as plaintext pictures in the
cache for a while, and those decode.

**What changed.** `ui/share/OutsideUris` is the rule: a URI from outside is read only when its scheme is exactly
`content` and its authority, taken past the last `@`, is none of this package's own. The `@` matters:
`ContentResolver` strips a `<userId>@` prefix (`ContentProvider.getAuthorityWithoutUserId`), so
`content://0@<package>.fileprovider/…` reaches Knit's provider and a plain string compare misses it. The own set is
read off the installed manifest (`PackageManager.GET_PROVIDERS`), so androidx.startup's provider and any a library
adds later are covered, with `<package>.fileprovider` added even when the lookup throws. Koin holds one instance.

The rule is applied where every outside URI the composer takes meets: the first line of `ChatViewModel.attach`
(picker, paste, drag, keyboard), `attachShared` (the share sheet's picture or video), `attachFile` (the share sheet's
file, the file picker) and `offerTransfer` (Direct file send). It runs before `AttachmentStore.isVideo`, which
already reads the URI. A refusal says what an unreadable URI says (`chat_image_capture_failed`,
`chat_video_unreadable` for a declared video, `chat_transfer_unreadable` for a file), so the sender learns nothing
about why, and no string needed translating; `attach` stays quiet, as every failed pick is.

One door is not the composer's. Add contact's Paste called `ClipData.Item.coerceToText`, which opens a `content://`
item as a `text/*` stream and reads all of it as Knit: a clip naming `<package>.fileprovider/fieldlog/…` poured the
field log into the field, and a provider serving an endless stream ran Knit out of memory. `clipText` now takes an
item's own text, or a URI that is not a stream scheme as text (a link copied with `ClipData.newRawUri`), and never
opens anything; a contact link or a relay invite is never a stream.

The issue proposed the refusal at `MainActivity`, before `ShareInbox`. That leaves the content receiver open, and
saying why in the chat would have needed a refusal flag carried through `ShareInbox`. The other proposal, a rule in
`AttachmentStore`'s ingest, would have to be told which of its callers hold an outside URI.

**What it costs.** A `file://` share from an app targeting API 23 or lower no longer attaches; apps targeting 24 and
up cannot send one anyway (`FileUriExposedException`). Not covered: `AndroidTransferFiles.fileSource` still opens a
`file://` URI, and only `offerTransfer`'s gate keeps outside ones from it. The trap is a new entry point: anything
that takes a URI from another app (a new intent filter, a new receiver, a widget) asks `OutsideUris` before the
first read, `getType` and `query` included. Tests: `OutsideUrisTest` (the rule), `OutsideUrisOfTest` (the manifest
read), `ClipTextTest` (a clip the platform would read is not), and `ChatViewModelTest`'s two #284 cases, which
check that no refused URI reaches `AttachmentStore` or `TransferManager`.

## Amendment 2026-11-02 — Knit's own logs reach a chat as bytes, never as a URI (#287)

**What was observed.** #287, found by reading the code while reviewing #284. Diagnostics shares the field log and a
crash report through Knit's own `FileProvider`, and none of its share sheets left Knit out, so a tester could pick
Knit, then a chat, and get "Knit couldn't read that file": the refusal above, doing its job on a URI it cannot tell
from a hostile one. Testers sending logs to the maintainer over Knit lost the route #284 closed. Admitting the
provider when the share is Knit's own is not workable: an `ACTION_SEND` does not reliably carry its sender
(`Activity.getReferrer` is the caller's word, `getCallingPackage` is null for `startActivity`).

**What changed.** The field log and crash report screens gain **Send to a chat**. It renders the same file the share
sheet would stage (`FieldLogExport.fileName` and `render`; `CrashReports.read` and `shareFileName`) and offers it to
`ShareInbox` as an `OwnFile`: a name, `text/plain` and the bytes, held in memory. `KnitApp` opens the share-target
picker on any pending share, so the existing route does the rest: the picked chat drains the inbox into
`ChatViewModel.attachOwnFile`, which meets `attachFile`'s refusals (`refusalForFile`, read from the repositories
because the drain runs before the state has a peer row) and stages through `AttachmentStore.ingestFileBytes`, the
URI door past its read: the same name sanitizing, installable and size refusals, image sniff and identity reuse
(`storeFile`). There is no URI to ask `OutsideUris` about, and that is the point: `SharedContent.ownFile` is the one
field no intent fills (`MainActivity.handleShareIntent` never sets it), so nothing outside the process can reach
this door.

The two Diagnostics sheets (`launchCrashShareChooser`) now pass `EXTRA_EXCLUDE_COMPONENTS` naming `MainActivity`,
so they no longer offer the route that ends in a refusal. A share that is a file and nothing else
(`SharedContent.fileOnly`) lists only DMs and groups in the picker (`shareTargets`), since a room refuses one
anyway (ADR 2026-09.qq2r); that holds for another app's file too.

**What it costs.** The copy in the chat is a sent message: the field log's Clear and a crash report's Delete do not
reach it, as they never reached a copy sent through another app, and the log still leaves the phone only by the
user's hand (ADR 2026-10.a239, ADR 028). The rendered file sits in memory until the chat drains it or the picker is
abandoned (the field log is at most 4 MiB, under `AttachmentStore.MAX_BYTES`). The APK share is unchanged: an
installable package is never sent in a chat (`FileTypes.isInstallable`), so that route was refused before #284 too.
Tests: `ShareInboxTest`, `ShareTargetScreenContentTest`, `AttachmentStoreIngestTest`'s bytes cases,
`ChatViewModelTest.aLogKnitWroteStagesFromItsBytesBehindTheFileRefusals`, `FieldLogViewModelTest`,
`CrashLogViewModelTest`, `CrashShareChooserTest`.
