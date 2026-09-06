---
id: "2026-09.sre4"
slug: a-resolved-meshtastic-author-s-avatar-opens-the-caveat
title: "A resolved Meshtastic author's avatar opens the caveat, not the profile"
date: 2026-09-05
topics: [lora, meshtastic, ui]
---

# ADR 2026-09.sre4 — A resolved Meshtastic author's avatar opens the caveat, not the profile

Status: Accepted (2026-09-05) — amends ADR 2026-09.26q3, whose "untappable avatar" now holds only for a
heard *stranger*. Amended by ADR 2026-09.ggq4 the same day: the caveat covers the *unverified* match only —
a match the radio's XEdDSA signature verified opens the profile directly.

**What was observed.** ADR 2026-09.26q3 gave every heard author an inert avatar, on one argument: the name
beside it is an unauthenticated claim off an open channel, so a tap that reached `profileDetails` would
offer to message somebody who may never have said any of it. That argument is exactly right for a stranger
and only half right for a **resolved contact** — a speaker whose node number matched a contact's
`ProfileContent.loraNode`. There the bubble already shows that contact's name and face, pulled from a real
Knit profile the user already has, and the avatar is the one element on screen that looks tappable
everywhere else in the app and is not here. In use it reads as a dead control rather than as a statement
about verification: the styling that carries the caveat (the muted name, the room strip) is doing its work
somewhere other than under the finger that just tapped.

**What changed.** Three cases, not two, in `MessageBubble`'s avatar:

- **A Knit author** opens their profile, unchanged.
- **A heard stranger** (`origin != null`, `origin.peerId == null`) stays inert, unchanged — there is no
  profile behind that face to reach.
- **A heard resolved contact** (`origin.peerId != null`) is tappable, and the tap opens an `AlertDialog`
  naming the caveat: their profile claims that radio, the post carried that radio's number, that is the
  whole match, and the channel is unsigned. Its confirm button — a second, deliberate tap — opens the
  profile; dismissing leaves the reader where the inert avatar left them.

The alternative a reader reaches for first is the straight-through tap, gated on nothing but `peerId != null`
now that the bubble already trusts the match enough to wear the face. It is rejected for the same reason
26q3 rejected it: `profileDetails` is a *messaging* surface, and arriving there from a spoofed `from` is how
an open channel turns into a conversation with the wrong person. Routing through the caveat keeps the
affordance without ever letting a tap assert an identity — and puts the sentence about verification at the
moment the user asked the question, rather than only in the room strip they scrolled past.

The tap opens the profile by **`origin.peerId`**, never `row.senderNodeId`: on a heard post the sender
column is this phone by convention, so the obvious field would open our own profile.

**What it costs, and what it does not cover.** Three strings and one dialog; no data, wire or DB change,
and the resolution itself is untouched (still frozen at ingest on `messages.originPeerId`). What it does
not cover is the thing 26q3 already named: **a resolved contact is still not a verified one.** This ADR
makes the caveat easier to reach, not truer — Meshtastic 2.8's `Data.xeddsa_signature` remains the roadmap
item that would let a tap mean something, and until then the dialog says what it says. The separate content
description is load-bearing for the same reason: a tap target that announced "View Sam's profile" would
assert with TalkBack exactly what the visual styling refuses to. Pinned by `ChatMeshRoomTest`
(`aResolvedContactWearsTheirAvatarAndTapsThroughToTheCaveatFirst`,
`theTapOpensTheCaveatAndOnlyItsOwnButtonOpensTheProfile`, and `aBridgedAuthorIsNotTappable` /
`aKnitAuthorStaysTappable` holding the other two cases still).
