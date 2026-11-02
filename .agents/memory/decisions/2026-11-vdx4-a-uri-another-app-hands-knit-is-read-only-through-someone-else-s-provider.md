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
