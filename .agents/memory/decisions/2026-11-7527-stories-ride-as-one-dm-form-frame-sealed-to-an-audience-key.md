---
id: "2026-11.7527"
slug: stories-ride-as-one-dm-form-frame-sealed-to-an-audience-key
title: "stories ride as one DM-form frame sealed to an audience key, or as a Nearby room post"
date: 2026-11-14
topics: [stories, wire, crypto, custody, privacy]
---

# ADR 2026-11.7527 — stories ride as one DM-form frame sealed to an audience key, or as a Nearby room post

Status: Proposed (2026-11-14). Nothing is built, and no wire byte moves until it is; every change below is additive.
It answers GitHub #20. The ring that marks a poster is already chosen (`.agents/memory/roadmap.md`, "Stories").

**What was asked.** Stories: a text or media post that the app shows apart from the chats, on the poster's avatar,
for a day, to the poster's contacts or to people nearby. The questions were how close a story can stay to an
ordinary Knit message, and whether one encrypted frame can reach a long contact list instead of one DM per contact.

**What is proposed.** Two forms, each an existing frame shape with a marker. The day is a display rule, not a
deletion: the app shows a story until `min(sentAt, receivedAt)` plus the form's window and then stops showing it.
Nothing self-deletes, and no copy says a story "disappears"; it is "shown for 24 hours".

## A story to your contacts

- **One frame per story.** A `chat` frame in DM form whose `recipientId` is an **audience tag**, not a node id,
  sealed under an **audience key**: a sender-key chain the poster keeps for one standing audience ("My story"), the
  group ratchet of ADR 017 (docs/GROUP_FORWARD_SECRECY.md) under DM addressing. The window is 24 h, which is also
  the custody TTL (`ForwardRepository.DEFAULT_TTL_MS`), so carriers drop their copies as the story stops showing.
- **The tag** is 16 bytes derived from the audience key's current epoch and written like a node id (26 base32
  characters, `NodeId.toBytesOrNull` canonical). In that form `FrameTranscoder` packs it as 16 raw bytes (ADR 060).
  It hides who the story is for, not that it is one: the scheme version and header (`ChatContent.enc`) are
  cleartext, so a carrier can tell an audience frame from a DM. Nobody can sign as the tag: a node id is the hash of
  a key bundle, so a bundle matching it is a 128-bit second preimage. No receipt can purge the frame early
  (`ForwardSync.onAck` and its early-ack memo take only the recipient's signature).
- **The key** goes to each member as a sealed ctl DM, re-sent until the member acks it, as a group seed is. Adding a
  member is one DM with the current key. Removing one starts a new epoch, so a new key and tag for everyone left;
  that can wait for the next post. Only contacts whose pinned profile carries `CAP_STORIES` get it (gate the send,
  the `CAP_FILES` precedent in `docs/WIRE_COMPAT.md`), and the composer says how many contacts are left out.
- **A member recognises the tag** and opens the frame. A member that got the story before its key holds it anyway,
  as a carrier, and reads it out of its own custody when the key lands (`forward_store` is indexed by `recipientId`).
  No park is needed.
- **Replies, reactions and receipts** are ordinary sealed DMs to the poster that name the one story id.

## A story to people nearby

- **A Nearby room post** with a cleartext `story` marker on `ChatContent`. Older builds show it as a room post, so it
  needs no capability bit. The room's screening, moderation and attachment rules apply unchanged (`RoomAttachments`,
  ADR 2026-10.79j9): pictures, link cards and short videos (ADR 2026-10.hmfe).
- **Shown for 6 h**, the room's custody TTL (`DEFAULT_BROADCAST_TTL_MS`): past that no carrier still holds it. In a
  busy room the newest-200 bound (`DEFAULT_MAX_BROADCAST`) can drop it sooner.
- **The composer says what it is:** cleartext, signed by you, and carried by strangers' phones for 6 h.
- **Room only.** A story goes to contacts or to people nearby, not both: posting one to both needs a shared story id.
- **Never aired on LoRa**, though a short text one would fit a packet.
- A reply goes to the poster as a DM, a message request when the two are strangers.

## What the wire gains

A paired change with knit-ios (`.agents/rules/paired-change.md`), with vectors for each item (`vectors/README.md`). The
bit, ctl value and label are taken when it is built, not reserved here.

1. A new `EncEnvelope.v` for the audience form, with its own nullable header. It keeps `nonce`, `ct` and `keys`
   (empty where unused), as v3 does, so every fielded build decodes it in `InboundPipeline.canCarry` and carries it.
   None of them reaches its version gate: the frame is not addressed to them.
2. A story marker in the sealed plaintext (`MessageContent`, and the next `MessageContentV2` label if the form uses
   the labelled plaintext).
3. A ctl that carries the audience key and its epoch, and the ack for it. Older builds ignore an unknown ctl.
4. `CAP_STORIES`.
5. The cleartext `story` marker on `ChatContent`, for the public form.

## Why older phones carry it unchanged

Checked on 2026-11-14 on Android (`feat/stories` at `2c32655bf`) and knit-ios (`e527859`). Nothing on the relay or
custody path needs `recipientId` to be a node id, so every build treats the frame as a DM to someone out of reach
and classifies it alike, which is what keeps custody convergent (ADR 006).

- **Custody.** `ForwardRepository.store` keeps any DM-form chat for 24 h in its sender's bucket and never looks the
  recipient up. `InboundPipeline.canCarry` asks only for `enc` and a good signature.
- **Ingress.** `IngressBudget.meters` meters room posts only.
- **#301's gates** (`ROW_HELD_BY_OTHER_SENDER`, `answersSealed`, `reactsIn`) judge only frames addressed to the phone,
  or rows it holds.
- **The hand to the addressee.** `MeshRouter.handOn`, the relay in `InboundPipeline.onDeliver` and `MeshManager`'s own
  sends call `fastSend(wire, Peer(recipientId))`, and every plane drops a send to a node it has no link with
  (Wi-Fi Aware's `cueTarget`, `BleFastRoutePolicy.send`, the direct link's map, LoRa's no-op). A story never takes the
  fast plane; it travels by flood and custody.
- **Back-fill.** `ForwardSync.onDigest` serves every live frame a peer's digest lacks. DMs always went to anyone, and
  ADR 2026-10.hr9f extended that to groups. Wi-Fi Aware raises a data path on a digest difference alone
  (`NanSyncPolicy.PeerFacts.digestWanted`), and Bluetooth sends a digest on every link-up.
- **knit-ios.** `RelayEnvelope.recipientId` is text, `CustodySync.missing` has no DM filter, and `Router.swift`'s
  hand-on parses the tag with `NodeId.init` and finds no neighbour.

## What was ruled out

- **One sealed DM per member**, the shape that works today with the marker alone. Each copy is a row in the poster's
  sender bucket of 200 (`DEFAULT_MAX_PER_SENDER`, own sends counted, ADR 006), so four stories to 50 contacts fill it
  and evict the poster's real DMs on every carrier. Fifty frames naming one blob (ADR 2026-10.mptm) also show every
  carrier the whole audience at every post.
- **A new `story` frame type.** `FrameType.isCustodial` is fixed on every fielded build, and a type that some builds
  carry and others do not splits the digests. `meshpost` was withdrawn for that (`docs/WIRE_COMPAT.md`).
- **A group.** Its roster rides in the clear on every frame and stops at 8 (`GroupInfo.MAX_MEMBERS`), and its id is
  the roster's hash, so every change of audience makes a new group. Hiding the roster waits for a wire break
  (`docs/NEXT_WIRE_BREAK.md` item 11).
- **One content key wrapped per member**, as scheme v1 does. About 100 B per member; each wrap either names its
  member, which puts the audience in the clear again, or every receiver tries them all; no forward secrecy; and v1 is
  retiring (item 4).
- **An audience per story.** Each would cost a key DM to every member, which is the fan-out again.

## What it costs

- **Forward secrecy is a group's** (ADR 017). Past stories stay safe when a member's phone is taken; new ones can be
  read on it until the next epoch. A new epoch on every removal and on a schedule bounds that.
- **A member whose key DM never arrived sees nothing.** The key is re-sent until acked, and the story waits in that
  member's own custody for its 24 h.
- **Carriers pull a story's picture at once.** `CarrierPullPolicy` holds back a DM's pull only while its recipient is
  linked, and nobody is ever linked to a tag. That is today's cost for any DM whose recipient is away, inside
  `CARRIER_BLOB_BUDGET_BYTES`.
- **Carriers still see** that a story was posted, by whom, how often, and who fetches the picture. What they no
  longer see is the audience.
- **A received story stays** on the receiving phone for as long as that phone keeps it. The poster is promised
  nothing else.

## What it does not cover

- **The Internet relay.** A spool DM scope admits only frames between its two ends (`ScopeFrames.eligibleForDm`), so
  stories need a scope root of their own, as a group has (`GroupKeyPayload.gr`). Until then they travel by radio.
- **A contact's direct Internet link.** `LinkPairFilter.admits` lets through only the pair's own frames, their DM
  scope and their shared groups. The rule to add (sent by one end, to an audience the other end is in) must answer
  alike at both ends while a new key is still going out, or the two filtered digests differ.
- **Reactions to a story.** `InboundPipeline.reactsIn` refuses a reactor outside the message's thread, and an audience
  member is outside it. The story path widens it to the audience; only new builds react to stories.
- **Views and retraction**, deferred. A retraction that only the phones reading it act on is additive; one that
  carriers honour needs a break (`docs/NEXT_WIRE_BREAK.md` item 15).
- **The next wire break.** Item 12 would carry DMs for 7 days. To custody a story is a DM, so its stories entry
  gives the audience scheme version a 24 h class at the same break, or every carrier holds each story for a week.
  Item 11 should settle the addressing of groups and stories as one form, and its watch-out says what hiding the
  form would cost that class.

## Still to decide

- The ring once a story is seen: does it grey or go.
- Whether stories from people nearby get their own row, and whether that row is on by default.
- Reactions to a public story: in the room, or a DM to the poster.
- What a receiving phone keeps after the window.
- The schedule for a new epoch when nobody has been removed.

## Kept true by

Nothing yet. The build owes:

- a `mesh/lab/` scenario with a poster, two members and a phone that holds no key, the path every older build takes:
  the members show the story, the keyless phone shows nothing, and `assertConverged` holds, including for a member
  whose key arrived after the story;
- a `ForwardSyncTest` case: a DM-form frame addressed to an id no phone holds is served to a peer that lacks it;
- a `FrameTranscoderTest` case: a tag rides as 16 raw bytes;
- vectors for the new scheme version, the key ctl and both markers, mirrored in knit-ios.

The audience key, its ctl and the scheme version go to crypto-owner, reviewed by crypto-reviewer and
security-reviewer. The custody read on a late key goes past blobs-reviewer.
