---
id: "2026-11.p6dk"
slug: a-received-mention-is-highlighted-only-under-a-name-the-reader-gives-that-person
title: "A received mention is highlighted only under a name the reader gives that person"
date: 2026-11-23
topics: [mentions, names]
---

# ADR 2026-11.p6dk — A received mention is highlighted only under a name the reader gives that person

Status: Accepted (2026-11-23)

**What was observed.** knit-next #247: a bubble's highlight followed the text its sender wrote for each mention. ADR
058 made that text the sender's own label so the unchanged highlighter could find the span. It now follows the reader's
labels. Detection was never in question: "mentions me" and the Mentions channel read the node id alone.

**What changed.** No wire byte moves, and nothing is stored. Where `ChatViewModel` folds a row, `vouchedMentions` (in
`ui/chat/MentionText.kt`) keeps the entries the bubble may style, as `ChatRow.styledMentions`; `ChatRow.mentions` keeps
every entry as received. Each entry is judged on its own, by the first rule that applies:

1. **Our own message** (sender id equals ours) keeps every entry: our composer wrote the names.
2. **The MeshCore room** (`Conversations.MESHCORE_PUBLIC`) keeps every entry: our receive code binds the one entry there,
   `Mention(me, "[board]")` (ADR 2026-11.gsqu's 2026-11-19 amendment), and no peer sends a list. Its rows carry our id
   as sender, so rule 1 already covers them; the rule names the room so it holds if that convention changes.
3. **`everyone`** (exactly `EVERYONE_NAME`) in a group thread is kept when its id is in the group's current roster or
   is ours, and is dropped otherwise, even when that id's label is "everyone" (ADR 2026-10.6h2g). In any other thread,
   a commons included, it goes on to rule 4, so it draws plain unless someone's label is literally "everyone".
4. **The name rule.** Two bounds come first, so a crafted list cannot make a row slow to draw: a name longer than
   `MAX_VOUCHED_NAME` (512 UTF-16 units; no stock sender writes a label past 344, a 32-unit name and ten 30-character
   tokens) is dropped, and of the entries that reach this rule and pass that bound, only those whose id is among the
   first `MAX_VOUCHED_IDS` (16) distinct ids to do so, in list order, are judged; the rest are dropped. Entries settled
   by rules 1–3 never count toward the 16. Then the entry is kept when its name, by `NameKey`, equals one of
   `PeerLabelIndex.mentionTexts(id)`:
   the reader's own label for that id (`labelFor`, the content filter's presenter applied, an id outside the index under
   the alias `labelFor` gives it), then that label grown by each further token of the id's own alias phrase, up to
   `Alias.MAX_TOKENS`, in `PeerLabel.text`'s format. A named peer's texts are `Alice`, `Alice (a0)`, `Alice (a0 a1)` and
   so on; a collided one's start at its suffixed label; a blank one's at its alias, then `a0 (a1)`. `PeerDirectory` reads
   it with the row's stored name, as `label` does.

The bubble runs the unchanged `mentionSpans` over the kept entries only, for the body and for a translation's words.
Because the function now sees fewer tokens, a kept "@Alice" inside a dropped "@Alice (zz)" is styled at its own length.

**Why "the label or longer", and nothing else.** A sender that saw a clash this phone does not writes a longer label,
and only the id's own tokens can lengthen it. The index grows two lookalikes' labels until they differ (ADR
2026-09.wuqj), so an id that shares another's first token still cannot write that id's label: an extension adds only
its own tokens. The rule accepts nothing the directory does not already show for that id. The alternatives:

- **Any name the reader holds for the id** (the profile name beside its label): when this phone sees a clash, the bare
  name is the one both identities answer to, so accepting it undoes the label.
- **A bare alias for a named peer**: a named peer's alias is not indexed, so it could collide with a blank peer's label
  unseen. A sender that lacks or hides the name writes the alias, and it draws plain.
- **A contact name**: neither platform stores one, so the issue's "private contact name" cost does not arise.
- **Judging at receive time and storing a verdict**: the rule reads labels, which change after a message is stored
  (a rename, a new clash, a clash fading, the content filter). A stored verdict would freeze one moment's labels and
  need a column, a migration and a parity test for the column. Render time reads the labels the reader sees now.

**What it costs.**

- **A genuine mention can go plain.** A sender whose directory saw no clash writes "@Alice", which draws plain on a
  phone that labels her "Alice (a0)", until ADR 2026-10.k8ge fades the clash.
- **A name the reader does not hold for that id draws plain**: the person renamed, the reader has no profile for them
  yet, the reader's content filter hides the name, or the sender wrote the bare alias because it lacked the name or hid
  it.
- **The rule reads current labels.** A rename, a new clash, a clash fading or a content-filter toggle can turn an
  earlier received message's highlight on or off. Our own messages are exempt.
- **The rule is no stronger than the directory.** It accepts nothing the directory does not already show for an id,
  so a gap in how labels grow is a gap here too; a known one is tracked in #360.
- **More than 16 people in one message.** Beyond the first 16 distinct ids that reach rule 4, a received message's
  mentions draw plain. A group holds at most eight members, and the room composer picks people one at a time.

**What does not change.** The receive path (decode, `carried`, storage, the mention notification, "mentions me" by node
id, `ChatMute`'s check on `everyone`), what a sender writes (the picker still inserts the sender's own label, ADR 058),
the composer chips (built from the pending list), translation's protection of every declared entry's span
(`mentionRanges`, `VerbatimSpans`), and the style. In a mixed fleet, phones differ only in which highlights they draw.

**The trap.** A new surface that styles a mention must take `styledMentions`, never `mentions`; the pinned bar, quotes,
search, notifications and Wear draw plain text today. Pinned by `MentionTextTest` (every vector case, the content
filter, the MeshCore binding, both bounds), `PeerLabelsTest` (`mentionTexts` for a plain, collided, blank, outside, own, stale and
hidden name), `ChatViewModelTest` (a stranger's "@Alice" dropped, the real one kept, our own row, `@everyone` in a group
and not in the Nearby room, `mentions` unchanged) and `vectors/mention-vouch-v1.json` (`MentionVouchVectorTest`), which
also pins label parity across both ports, which no vector covered before.

## Companion change for knit-ios

knit-ios #36 on `fix/mention-vouch`: `PeerLabelIndex.mentionTexts(for:)` in `KnitAppCore/Labels` and
`MentionText.vouched(...)` in `KnitAppCore/Mentions`, applied in `ChatModel` rather than inside `MentionText.spans`,
which also builds the composer's chips. iOS has no MeshCore room, so its rule has steps 1, 3 and 4. It syncs
`mention-vouch-v1.json` with `scripts/sync-vectors.py` as an `OPTIONAL` file, checks every directory's labels against its
own index, and runs every case through its own rule and `spans`.
