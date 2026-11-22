---
id: "2026-11.dgvf"
slug: media-links-and-files-lists
title: "Media, links and files lists the thread's own rows, screened as the chat screens them"
date: 2026-11-22
topics: [ui, attachments, chat]
---

# ADR 2026-11.dgvf — Media, links and files lists the thread's own rows, screened as the chat screens them

Status: Accepted (2026-11-22)

**Context:** nothing listed one chat's attachments: finding last month's photo, a file or a link meant scrolling the
thread, which reads a window of at most `ChatWindow.MAX` rows. The chat's ⋮ menu now opens "Media, links and files"
(`ui/media/`, `Routes.CHAT_MEDIA`), Signal's All media: a Media tab (photos and videos in a grid by month), a Files tab
(files and voice messages) and a Links tab (every link in a message body, with its preview card when one arrived for
that link). It is offered on every thread but a bridged LoRa room, which carries text alone. The first cut read every
attachment in one window and split it in Kotlin; a review found that a thread whose newest 300 attachments were photos
showed an empty Files tab with nothing to scroll, so its older files never loaded.

**Decision:** each tab has its own read (`ThreadMediaQueries`, inherited by `MessageDao` as `BridgedRoomQueries` is,
read through `ThreadMediaRepository`): `observeMediaIn` and `observeFilesIn` select `attachmentKindOf`'s kinds in SQL
(`MEDIA_ROW`, `LINK_CARD_ROW`), so a window counts only what its tab shows, and `observeLinkRowsIn` prefilters bodies
with `LIKE '%http%' OR LIKE '%www.%'` for `findUrls` to decide. Every read is the thread's ordinary rows (`kind = 0`:
no notice, no Direct Send record, whose bytes never enter the blob store), blocked senders left out in SQL, newest
first, bounded by a window of `ChatMediaViewModel.PAGE` that grows a page at a time to `ChatWindow.MAX`. **Only the
open tab's read runs** (`ChatMediaViewModel.selectTab` cancels the last one; each tab keeps its window). The blobs
table is read once, `blobs.observeSizes` keyed to the open tab's hashes and re-subscribed only when that set changes
(ADR 2026-09.fjcw). The reader reaches first for an index on the attachment: it would need DB v39 and a migration
(v38 is already tagged in v2.13.0-alpha.0), and with no `ANALYZE` the planner would not reliably prefer it over the ordered
walk of `(conversationId, sentAt, id)`, which needs no sort and stops at the limit. Screening follows the chat, tab by
tab: a picture is covered by its own hash's verdict (a video by its poster's too) while content filtering is on and
shows after one tap; a file shows its flag; a link from text the moderator flagged is not listed (`searchBodies`'s
clause), and a flagged card is dropped for its bare link; a bare link is drawn without control or format characters
(`displayUrl`), and what opens is the link itself, as from the bubble. A photo or video opens in the chat's own
viewer, a file through the chat's own opener, both lifted out of the chat rather than copied: `AttachmentExport`
holds every door a held attachment leaves by (player, gallery, picked document; ADR 029, ADR 2026-09.7ad3) and
`FileOpener` the risky-file question and the picker, for `ChatViewModel` and `ChatMediaViewModel` alike. Show in chat
is ADR 2026-10.4cyt's hand-back: the message id set under `Routes.SEARCH_HIT` on the entry underneath, then a pop.

**Consequences / limits:** a tab's read walks the thread backward until its window fills, so a thread that is mostly
text is walked whole on every write to `messages` while that tab is open: one walk, the cost the chat itself pays at
`ChatWindow.MAX`. A row past `ChatWindow.MAX` of one kind is not listed. A photo or video not on the phone yet, and
every voice message, opens in the chat, where tap-to-download, the progress ring and the player live. Direct Send
transfers, locations, swiping between pictures in the viewer, and selecting several to delete or share are not built
(`.agents/memory/roadmap.md`). The SQL kinds and the bubble's must agree: `MessageMediaDaoTest` ("the tabs split
attachments exactly as attachmentKindOf does", and a file older than a window of photos still listed), and a change to
`attachmentKindOf` changes `ThreadMediaQueries` with it. `ChatMediaViewModelTest` pins one read at a time, the sizes
asked, the cover rules and the windows; `ChatMediaScreenContentTest` the taps and the hand-back; `ChatScreenContentTest`
the menu item and its absence from a bridged room.
