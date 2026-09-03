---
id: "2026-09.3yje"
slug: the-open-to-chat-cue-introduces-strangers-only
title: "The open-to-chat cue introduces strangers only, gated on a two-way exchange"
date: 2026-09-03
topics: [notifications, presence, data]
---

# ADR 2026-09.3yje — The open-to-chat cue introduces strangers only, gated on a two-way exchange

Status: Accepted (2026-09-03; amends ADR 2026-09.74fq — `OpenToChatPolicy.qualifying` takes a fifth set,
fed by `MessageDao.observeAcquaintedPeers`)

**What was observed.** ADR 2026-09.74fq's cue names anyone in direct radio range whose profile carries the
flag, and its only per-person brakes are time-based: two hours between cues about one person, two a day,
one post an hour overall. For strangers that is the whole point. For people who live or work together it is
a treadmill — a couple who both leave the flag on are introduced to each other twice a day, every day, for
as long as they own the phones, and each of those cues asks them to go say hi in the Nearby room to
somebody they had breakfast with. The cooldowns cannot fix this because nothing about them is wrong: the
encounters are real, spaced out, and inside every cap. The flag being a standing declaration rather than a
one-shot makes it permanent.

**What changed.** The cue is now for introductions only, so the qualifying set subtracts everyone the user
has **already exchanged messages with** — a fifth input alongside the block list, same shape, same place in
`OpenToChatPolicy.qualifying`. "Exchanged" is deliberately two-way: a DM thread holding a message each way,
or a group both parties have posted in. The two halves collapse together rather than multiplying, because a
shared group both have spoken in *is* an exchange, so the mixed cases ("I DM'd them, they answered in the
group") already reduce to it. Deriving this costs no new state at all — `messages` already holds it, and
`MessageDao.observeAcquaintedPeers` is one `UNION` of two `EXISTS` over the table the ADR 066 rule prefers.

The reading a reader reaches for first is **any contact in either direction**, which is one operator
cheaper and wrong in both directions of a group: a stranger who posts once in a room we are both in has not
met us, and would be silently disqualified from ever being introduced; meanwhile a DM we sent that was
never answered — quite possibly never even delivered — would count as knowing someone we have never heard
from. The other candidate, **the contact list** (`verified`, or `Conversations.isAccepted`), is worse for a
reason ADR 009 already documents: acceptance is a stranger-filtering decision with its own semantics, and
hanging a second meaning on it is how the spool plane went dark for unreplied DMs (ADR 032). Message rows
are the evidence; the peer table and the accept set are not.

**What it costs, and the traps.** The query re-runs on every `messages` insert, since it is observed rather
than read once — that is the point (a reply drops its author out of the batch mid-hold, tested by
`OpenToChatWatchTest.replyingToSomeoneMidHoldDropsThemFromTheBatch`), and the watch's
`distinctUntilChanged` absorbs the re-emissions. Deleting a thread, or letting the retention sweep age one
out, makes a person a stranger again and re-arms the cue about them; that is honest — the device no longer
holds the evidence — and the ADR 2026-09.74fq cooldowns still bound it. The **Nearby room does not count**,
in either direction: it is public, everyone in radio range is in it, and counting it would disqualify a
whole coworking space from the feature the moment one person said good morning. Two traps for the next
person: status notices must stay excluded (`kind = 0`, the same sender-is-the-subject rule
`MessageDao.sendersIn` carries — a peer who only ever renamed themselves has said nothing), and the DM half
must key on the thread id rather than `recipientId`, because a DM thread *is* the other party's node id and
the inbound row is the one whose sender equals it. Pinned by `MessageDaoTest`'s three
`observeAcquaintedPeers` cases and `OpenToChatPolicyTest.qualifyingDropsAnyoneWeHaveAlreadyExchangedMessagesWith`.
