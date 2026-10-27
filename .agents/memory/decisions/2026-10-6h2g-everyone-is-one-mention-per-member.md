---
id: "2026-10.6h2g"
slug: everyone-is-one-mention-per-member
title: "@everyone is one mention per member"
date: 2026-10-14
topics: [mentions, groups]
---

# ADR 2026-10.6h2g — @everyone is one mention per member

Status: Accepted (2026-10-14)

**What was observed.** A group's members could already be mentioned one at a time, and a receiver notifies on the
node-id match (`List<Mention>.mention(me)` in `InboundPipeline`) on the Mentions channel, once per message, only for
an accepted thread, never for our own message (ADR 058 for the label, ADR 2026-09.hd5n for the composer's window).
Calling the whole group took one pick per member. Builds already in the field have no idea what "everyone" is, and
need none.

**What changed.** `@everyone` is sender-side sugar and no wire byte moves. In a group thread the picker offers an
`@everyone` row first; picking it writes the ASCII token `@everyone ` into the body and adds one ordinary
`Mention(nodeId, "everyone")` per roster member (minus us and the blocked, in ascending node id, never repeated) to the
draft's pending mentions. `ChatViewModel` derives the list (`everyoneMentions`, roster only, null when empty, so no
row); `mentionsStillIn` is the send filter, so editing the token away drops every entry. Groups hold up to eight
members, so a list is at most seven entries. The row is offered only for `ConversationKind.GROUP`: not a DM, the
Nearby room, the Meshtastic room or a commons. A hand-typed `@everyone` elsewhere stays plain text.

The alternatives a reader would reach for, and why not. A **token alone** (the receiver sees `@everyone` and
notifies) is a receiver change: every build already out would show the word and never alert. A **sentinel node id** (one entry, id `*`) fails the same
way, since the match is `it.nodeId == me`. An **additive field** (`mentionAll`) is a new wire field and a capability
bit for what the sender can already say in the existing list. Expanding on the sender needs none of those and works
against builds that predate it.

**What it costs.** The mention list is frozen at pick time, so a member who joins after the message was composed is not
pinged. The name `everyone` is not localised, since it lives in the sender's body. The expansion reads the roster, not
senders, so someone who left is not mentioned and someone who joined is. A restored draft keeps its text and loses its
bindings (ADR 2026-09.qtg9), so a re-sent draft's `@everyone` is plain text until picked again. A member who picks
people by hand and then `@everyone` carries both entries (`{id, label}` and `{id, everyone}`). `mentionSpans` takes
distinct tokens, so seven same-named entries are one chip and one highlight. A mention alerts; it makes no promise
about delivery or waking a phone, and the picker copy says no more. No admin-only rule, rate limit or cooldown: any
member can do what they could already do by seven picks. Pinned by `MentionTextTest` (match rule, expansion),
`ChatViewModelTest` (what the row offers), `MentionChipsTest`, `InboundPipelineTest` and the `MessageContent` round
trip. If a group's cap rises past eight, iOS's crafted-list bound needs a second look.
