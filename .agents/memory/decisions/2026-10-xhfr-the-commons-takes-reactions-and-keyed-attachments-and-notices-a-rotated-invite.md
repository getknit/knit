---
id: "2026-10.xhfr"
slug: the-commons-takes-reactions-and-keyed-attachments-and-notices-a-rotated-invite
title: "The commons takes reactions and keyed attachments and notices a rotated invite"
date: 2026-10-22
topics: [spool, commons]
---

# ADR 2026-10.xhfr — The commons takes reactions and keyed attachments and notices a rotated invite

Status: Accepted (2026-10-22) — branch `feat/commons-chat`; spec `docs/SPOOL_PROTOCOL.md` §7.4 (C-7.4-6 amended,
C-7.4-13, C-7.4-14), §4.5, §9.5. Amends ADR 2026-09.wx8e's "does not cover". Still hidden in shipped builds behind
`BuildConfig.COMMONS`; the release flip is its own step (`.agents/memory/roadmap.md`).

**What was observed.** The commons shipped text and reply quotes only, and its chat screen still offered the
reaction picker. A reaction there took `MeshManager.sendReaction` with the room's `c-…` id as the "recipient":
`sealDmReaction` found no peer, and the fallback flooded a **cleartext, custodial `reaction` frame over the radios**,
naming a private room's message id and the emoji to every node in range. The Meshtastic room's `m-public` did the
same. Beyond that leak, the room lacked what a group chat has: reactions, attachments, a list of who is in it, and any
sign that the operator had replaced the invite (a rotated room simply went quiet).

**What changed.**

- **The leak.** `sendReaction` refuses a recipient that is not a peer id (`ConversationKind.DM`). The Meshtastic room
  has no tapback form, so its long-press menu draws no emoji row (`canReact`) and `ChatViewModel.react` returns.
- **Reactions ride the room.** A `CommonsPost` now carries exactly one of `chat` and `reaction` (additive; an older
  build fails to decode a reaction-only post and quarantines it, so it never draws an empty bubble). Every member
  reads every reaction, applied latest-`sentAt`-wins per (post, reactor) by the same `ReactionRepository.apply` every
  path uses, so the room's unordered listing and a restart's re-pull converge; a target held in another thread is
  dropped (`InboundPipeline.applyCommonsReaction`). Our newer reaction to a post replaces the older one in the outbox
  in the same write (`CommonsStore.postReaction`, `commonsReactionTarget`) — no schema column. The alternative, a sealed
  reaction to the author over the pair's DM scope (the receipt route, C-7.4-12), shows the author alone: wrong for a
  group chat. A reaction costs one of the room's frames, as a post does; it is a person's act, not a per-reader echo.
- **Attachments are keyed.** A commons post's attachment is sealed to a fresh key as a group's is
  (`MeshManager.sealAttachment`), the key riding cleartext inside the room's seal (`ChatContent.attachmentKey`, beside
  `attachmentName` for a file — both additive, both read only in a commons). The first cut sent them key-less like the
  Nearby room's; blobs-reviewer caught that a key-less blob's address is its plaintext's, and `BlobExchange` serves
  any held blob to a radio ask, so anyone holding a picture could learn which nearby phones are in the private room
  that circulated it. The key closes that and makes `A` the ciphertext §4.5 always said it was.
- **Attachments live on the relay alone.** The spool plane's commons arm pushes our own (from the outbox) and fetches
  everyone else's from our record of the posts (`CommonsStore.attachmentRefs` over
  `MessageDao.commonsAttachmentsMissing`, since a received post is never custodied), only where the HELLO's commons
  said `attach` (S-7.4-5). The radios never ask for one: `attachmentFetchRows` / `attachmentFetchRowsFor` leave `c-`
  rows out, `deliverChat` arms no want, and the bubble never draws "tap to download". A fetch the spool answers
  `total = 0` keeps the 15 s retry for two minutes after the post (`ABSENT_GRACE_MS` — the author pushes post and
  chunks in one round, so the reader often asks first), then settles for the ten-minute recheck, because its author may
  never upload it; the read skips what is settled or quarantined so its limit counts live candidates. The composer
  and the sender's marker read `RelayFacts.textOnlyRooms` (a relay that takes no attachments for the room). The room
  takes everything a group takes: photos, video, voice notes, files, link cards and location.
- **Room details.** `commonsDetails/{conversationId}` lists the members this phone has seen (`commons_members`), the
  relay's host and Leave, reached from the room's header or a new overflow (which also offers location).
- **A rotated invite is noticed.** A commons SUB declares the HELLO's bounds with `ttlMs` one millisecond short. The
  spool running the room substitutes its pinned bounds (S-7.4-2); a rotated id is an ordinary scope whose store
  applies what we declared (both of knit-spool's stores do), so a DIGEST echoing anything but the HELLO's TTL — or a
  creation gate asked for it (S-7.4-3) — marks it stale (`ScopeSync.markStale`). A stale room leaves `mine()` for the
  worker's life, so we keep no junk scope alive on the operator's relay; a HELLO with no commons marks every room
  joined there. `SpoolStatus.staleCommons` → `RelayFacts.staleRooms` drives a notice in the room, on the relay row and
  in the details.

**What it costs.** Two additive `ChatContent` fields and one `CommonsPost` field (vectors `commonsPostAttachment`,
`commonsReaction`); a SUB that differs from the HELLO by a millisecond; a stale verdict held in memory (one SUB per
process start to re-learn it, and a stale room returns only after a restart if an operator ever reverts an invite). A
spool that does not substitute its pinned bounds breaks S-7.4-2 and would read every room as stale. Not covered: typing
(the cast lane is DM-scope only and experimental), carrying posts over the radios, the release flip. **The trap:** a
reaction in a commons must never reach `sendReaction`, and nothing that serves or asks for blobs may treat a commons
row like a Nearby one. Kept true by `CommonsSyncTest` (reactions, attachments, the fetch loop, the four stale cases),
`ScopeFramesTest`'s commons arms, `MessageDaoTest`, `InboundPipelineTest`'s commons door, `MeshManagerTest`
(`aReactionAddressedToARoomIdIsRefused…`) and `GoldenVectorTest`.
