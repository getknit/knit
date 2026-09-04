---
id: "066"
slug: a-status-notice-is-derived
title: "A status notice is derived, never carried — and it is furniture, not a message"
date: 2026-08-31
topics: [ui, data, wire]
---

# ADR 066 — A status notice is derived, never carried — and it is furniture, not a message

Knit surfaced identity and membership changes nowhere. A contact renamed themselves and their bubbles
silently changed name; someone renamed a group and only the title moved. One exception already shipped —
`"X left the chat"`, on `MessageEntity.kind = KIND_MEMBER_LEFT` — and this generalizes it to contact
renames and avatar changes, group renames and photo changes, group creation, and a refused key pin.

**Nothing new goes on the wire, and that is the decision.** Every one of these events is a pure function
of `(stored value, incoming value)`, and both ends already hold both. That is the rule `WIRE_COMPAT.md`
records for voice notes and for attachments over spools — *when a value is derivable from bytes both ends
already have, deriving beats carrying* — applied to a feature whose default shape would have been a new
frame type or a ctl value. So: no field, no `type`, no ctl, no capability bit, no `EncEnvelope.v`, no
`MessageContent.v`, no golden vector, and no `WIRE_COMPAT.md` precedent entry, because there is no
precedent to set. It also costs no DB migration: `kind` has been in the frozen v1 baseline schema since
launch, so new values are data rather than schema. The hooks are the five places that already compute
old-vs-new for their own reasons — `InboundPipeline.handleProfile`, `applySealedProfile`, its pin-refusal
branch, `reconcileGroup`, and the local group-creation path — and each writes inside whatever transaction
its state change already holds, never a new one (the ratchet lock order, ADR 019).

**Adopting a value is not the same question as the value having changed**, and conflating them is the
one bug this feature invites. `GroupInfo` is self-describing and rides on *every* chat frame; a profile
republishes every 12 h and re-floods on every peer-epoch. So the last-writer-wins predicates that already
existed — `takeIncoming`, `PhotoDecision`, `stalePresentation` — are all true for a frame that merely
repeats what we hold, and keying a notice off them would post a line per frame forever. `PhotoDecision`
gained a `changedTo` field precisely because none of its three existing fields could answer it: `hash`
holds the *old* photo while new bytes are in flight and `pull` empties as soon as they land.

**Ids are deterministic and keyed on a version, not on a value.** A re-served frame must upsert the same
row rather than stack a duplicate line. For avatars the key is the profile *version* rather than the
hash, which is load-bearing in a way the hash is not: `resolveAvatarHash` refuses to adopt a hash until
its blob lands, so "advertised differs from stored" stays true on every re-serve until then.

**A notice is not evidence that anyone spoke.** Its `senderId` is the event's **subject** — the peer who
renamed themselves, the member who left — so it is excluded from `MessageDao.sendersIn`, which feeds
`Conversations.isAccepted`. This was already latently wrong for `KIND_MEMBER_LEFT` and only became worth
fixing at seven kinds: without it, renaming yourself would promote your group out of a stranger's
message-request inbox without your ever having said anything in it.

**Two scope rules keep notices from becoming a feed.** A peer notice is written only into a DM thread
that already holds an ordinary message — a `profile` frame floods the whole mesh, so ungated it would
conjure a thread for everyone the device has ever heard — and notices are invisible to the chat list
entirely: never its preview, never its sort key. That second rule *changed* member-left's behaviour, on
purpose. Having one notice reorder someone's chat list while six others did not would be worse than
either rule applied uniformly, and a rename is not news the way a message is.

**The text is composed at render time, never stored.** A row carries a kind, a subject, and at most one
name; `ChatScreen.statusNoticeText` turns that into a localized string against the live peer directory,
so a line re-renders correctly after a later rename, disambiguates a colliding name for free (ADR 058),
and costs nothing to translate. The one stored name follows an asymmetric rule worth stating because it
looks like an inconsistency: a **peer** rename stores the *old* name (the new one is the live label),
while a **group** rename stores the *new* one (the old is gone from live state, and "Alice renamed the
group to Book Club" then stays a correct record after a *later* rename). The accepted cost of the peer
form: a second rename retroactively rewrites the first line's second half. *The peer half of this
paragraph is superseded by ADR 2026-09.995c: that cost turned out to read as a duplicate line, so a peer
rename now stores both names.*

**Two honest limits, recorded rather than papered over.** `KIND_KEY_PIN_REFUSED` is effectively
unreachable — a profile whose key does not derive back to its sender's nodeId is dropped before the pin
is consulted, so arriving there needs a 128-bit collision or a corrupted pin. It ships as a safety net
because it is the one profile event a user could act on, and a metric only a maintainer reads was the
wrong place to leave it. And there is no "member joined": a group's id is the hash of its founding
roster and membership only ever shrinks, so `KIND_GROUP_CREATED` at first sight is the only join-shaped
event that exists.

**Unknown kinds degrade to a bubble.** `isStatusNotice` is "not `KIND_NORMAL`" rather than a list, so a
row written by a newer build still stays out of unread counts and previews even where its text cannot be
rendered; the renderer's `else -> null` draws it as an ordinary message rather than dropping it. The
`kind` registry is append-only for the usual reason — the values are in the database, so recycling one
would re-render an old row as a different event.
