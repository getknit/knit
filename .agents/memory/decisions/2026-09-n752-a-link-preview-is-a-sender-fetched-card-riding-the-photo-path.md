---
id: "2026-09.n752"
slug: a-link-preview-is-a-sender-fetched-card-riding-the-photo-path
title: "A link preview is a sender-fetched card riding the photo path"
date: 2026-09-04
topics: [attachments, ui, wire, privacy, moderation, network]
---

# ADR 2026-09.n752 — A link preview is a sender-fetched card riding the photo path

Status: Accepted (2026-09-04)

**What was observed.** A link in a chat rendered as an underlined URL and nothing more, where every
other messenger shows the page's title and picture. The first design was the obvious one for an app
with an Internet plane: each *reader* fetches the card when it happens to have Internet, cached in a
new table. Weighed against this app's shape it was the wrong reader. Most phones on the mesh have no
route to the Internet at the moment they render a message, so a reader-side fetch gives previews to the
people who least need a mesh; every reader with the setting on would open a socket toward whatever
site a stranger in the Nearby room typed (an IP-harvesting trick that needed a tap-to-load chip to
blunt); and a cache table is a list of every link the phone ever looked at.

**What changed.** The **sender** fetches the card, once, and sends it as an ordinary attachment.
`mesh/protocol/LinkPreviewBlob` is a small CBOR container — the link as it stands in the body, the
title, the description, and a ≤512 px JPEG/WebP picture — stored as a content-addressed blob under one
new MIME, `application/vnd.knit.link-preview`, and referenced by the frame's *existing* attachment
fields: hash in the clear, MIME and key sealed for a DM or group, MIME in the clear in the Nearby room.
Nothing else on the wire moved (`GoldenVectorTest` gained two container fixtures and every prior one is
byte-identical — the additive proof), there is no DB change, and custody, the relay plane's attachment
carriage, `BlobExchange` and blob GC carry a card exactly as they carry a photo. A recipient never
fetches: `data/LinkCardStore` opens the held blob (decrypting a sealed one) into a value-equal
`LinkCard` the row carries, and the bubble draws it only when the card's link is one `findUrls` finds in
the body — a sender cannot attach one page's card to another page's link. The container is normalized
at the decode boundary like a file name, and its `url` is refused unless it is a plain http(s) address,
because it is what `openUrl` will open. The layout version `v` is required and always emitted: an
elided version cannot gate (the scheme-v3 lesson), and a new *form* would mint a new MIME string.

The sender-side half is the whole Internet-facing surface, and it is gated first. A new
`net/InternetGate` asks Android whether the **default** network is a validated route to the Internet
(`NET_CAPABILITY_VALIDATED`, so a captive portal and the Wi-Fi Aware link both read as offline) — the
seam ADR 019 deferred, now declared under `ACCESS_NETWORK_STATE`, named in `rules/mesh.md` as the second
`ConnectivityManager` user, and never calling `bindProcessToNetwork`. Data Saver is honoured explicitly
(the blocked-status callback reports a foreground app as unblocked). `OkHttpPreviewFetcher` is the second
and last `okhttp3` importer (now detekt-enforced): every socket and lookup is bound to that `Network`, a
resolver refuses a whole hostname when any address fails `PublicAddressPolicy` (loopback, the LAN, the
NAN link-local subnet, CGNAT, the embedded v4 of mapped/NAT64/6to4 forms), redirects are walked by hand
and each hop re-policed by `LinkPreviewPolicy` (https, port 443, no literals, no user-info, no
`.local`/`.onion`, no contact card), bodies are read through a cap that counts *decoded* bytes, and
nothing identifying goes out — no cookies, no `Referer`, and `User-Agent: WhatsApp/2`, Signal's choice,
because sites serve Open Graph tags to it where a browser string gets a script shell and an honest app
name gets a bot challenge and tells every site the user runs Knit. `LinkPreviewService` then screens the
picture with the send-side image classifier (flagged ⇒ the card goes without it) and the title and
description with the message body's own text gate (flagged ⇒ no card), and the recipient re-screens
both — `ImageScreeningService.screenAttachment` opens a card and folds a picture verdict and a text
verdict into **one** `blob_verdicts` row, so a card hides whole behind the content filter's tap-to-view.

The composer loop (`ChatViewModel.watchDraftForLinks`) fetches for the first eligible link once the
draft rests 600 ms, and only when the setting is on (default **off**, disclosed in its own row's subtitle:
the site sees the sender's IP; recipients never contact it), the gate is online, the one attachment slot
is free, the link was not dismissed or found empty in this draft, the thread does not ride LoRa, and — in
a DM or group — every recipient's pinned profile carries `CAP_LINK_PREVIEW = 0x400` (the `CAP_FILES`
pattern; silent, since an implicit action has no affordance to explain itself through). The Nearby room
is the deliberate exception: its listeners cannot be enumerated, so a room message carries its card
regardless, and a build older than this one shows a spinner where the card should be until it updates —
accepted, and fenced by the same release: `AttachmentImage` now has an error arm, so from here on an
attachment a build cannot render says so instead of spinning forever. Sending while a fetch is still
running sends without the card; a photo staged over a card replaces it; removing a card remembers the
link for the rest of the draft.

**What it costs.** ~80 B more on a frame that carries a card (the attachment reference) and a 10–60 KB
blob over the radios; nothing on LoRa, where a card is never staged. Two invariants the room relied on
are withdrawn and re-stated: "a room attachment is always an image" (`docs/NEXT_WIRE_BREAK.md` item 1
loses its justification, `docs/CONTENT_MODERATION.md` §7 now says *image or card*), and
`MeshBlobStore.saveIncoming` no longer skips a key-less non-image but routes a card into the stricter
screen — a hostile MIME can only move a blob *into* the card screen, never around one, and only our own
row's MIME is consulted. A room card relayed *before* its row arrives is caught by
`InboundPipeline.screenHeldAttachment`, which accepts a key-less container for exactly that case.
Deliberately not built: a reader-side fetch as a fallback for offline senders (the roadmap keeps it
parked), a receiver-side "show previews" toggle (a card is an attachment; the content filter already
hides a flagged one), Tor (the fetcher's `bound` builder is where a proxy would go, and a SOCKS proxy
bypasses `Dns`, which is why the URL-level literal refusal must stay), and a card next to a photo (one
slot, Signal's rule too). What keeps this true: `LinkPreviewBlobTest` and the two golden vectors,
`LinkPreviewPolicyTest`/`PublicAddressPolicyTest`/`OkHttpPreviewFetcherTest` (MockWebServer, the only
place the caps and the redirect walk are observable), `LinkPreviewServiceTest`, `ImageScreeningServiceTest`
and `MeshBlobStoreTest`'s card cases, `InboundPipelineTest`'s two card cases, `MeshManagerTest`'s
sealed-and-clear case, the `ChatViewModelTest` composer cases, and `ChatScreenContentTest`.
