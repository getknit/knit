---
id: "2026-10.p95v"
slug: a-message-s-formatting-is-style-ranges-beside-a-plain-body
title: "A message's formatting is style ranges beside a plain body"
date: 2026-10-24
topics: [wire, ui, data, crypto]
---

# ADR 2026-10.p95v — A message's formatting is style ranges beside a plain body

Status: Accepted (2026-10-24)

**What was observed.** A chat message was plain text, and the only styling a bubble drew was a mention's weight and
a link's underline. People asked for Signal's formatting: select words, make them bold, italic, struck through or
monospace. Markdown-lite shortcuts (typing `**bold**` or `` `code` ``) were asked for too, to come later.

**What changed.** The body stays plain. Formatting is a short list of ranges carried beside it, in one byte string.

- **Ranges, not markup in the body.** The receiver never parses the text. Markup in the body (`*bold*`, as WhatsApp
  does) was the alternative a reader reaches for first, because it needs no wire field. It does not work here:
  - every build already out, the iOS port, and every Meshtastic client in the room would show the asterisks;
  - every surface built from the body would have to strip markers: the chat-list preview, notifications, quote
    snippets, search snippets, moderation, translation and link detection (whose trailing punctuation would also
    swallow a `**`);
  - snake_case, sums and foreign Meshtastic text would be formatted by accident;
  - a selection inside a word, or one holding a marker character, cannot be expressed.

  With ranges the body every one of those surfaces reads is unchanged, and the bubble is the only renderer
  (`annotateMessageBody`).
- **Markdown-lite is an input method, never a format.** When it comes, the composer turns markers into ranges as
  they are typed (an `InputTransformation`), and paths without a rich composer (the notification's inline reply,
  the debug bridge) parse at send. A receiver still reads only the ranges.
- **No capability bit.** A build without this ignores the unknown key and shows the same text, plainly. The one
  style whose plain fallback is not harmless is a spoiler, shown in the clear. It is reserved (code 4) and not
  built, and it needs a `CAP_*` send gate when it is.
- **Android first.** iOS ignores the field until its half lands. This ADR and the `vectors/` entries are the
  contract it matches.

**The wire.** An unformatted message is byte-identical to one sent before this change: the field defaults to empty
and `encodeDefaults = false` leaves it out. This is pinned by every existing vector staying put and by a byte-exact
test. A formatted message carries one CBOR byte string:

| Layout | Field | Key bytes |
|---|---|---|
| `MessageContentV2` (v3 sealed) | label 17 | 1 (`0x11`) |
| `MessageContent` (named sealed, v1/v2) | `"s"` | 2 (`0x61 0x73`) |
| `ChatContent` (Nearby room, commons) | `"s"` | 2 (`0x61 0x73`) |

The string's bytes are the same in all three: one entry per span, `uvarint(startDelta) uvarint((length << 3) | code)`.

- Positions and lengths are UTF-16 code units over the body as sent, after the send path's trim and clamp.
- `startDelta` is the span's start minus the previous span's start (minus 0 for the first). Spans go in canonical
  order, so it is never negative, even where two styles overlap.
- A uvarint is unsigned LEB128, at most 3 bytes (a value below 2²¹).
- `code`: 0 bold, 1 italic, 2 strikethrough, 3 monospace, 4 spoiler (reserved), 5 and 6 unassigned. 7 is an
  escape: the code is 7 plus the uvarint that follows, so the list never runs out.
- A typical span (a start within 127 of the last, a length up to 15) costs 2 bytes. One bold word costs a sealed
  v3 message 4 bytes (key, header, span) and a room post 5. A flat CBOR integer list would cost 5–7, and a labelled
  struct like `MentionV2` 9–11.

**Decoding and normalisation.** Both ports apply these rules in this order (`StyleSpans.unpack` / `normalize`):

| Step | Rule |
|---|---|
| 1 | Read entries until the bytes end, at most 64. A truncated uvarint, or a third byte with its high bit set, ends the read, keeping the spans read so far. |
| 2 | Drop a span whose code this build does not know (v1: 4 and above), or whose length is 0, or whose start is at or past the body's length. The message is kept. A dropped entry still moves the running start: deltas chain over every entry read. |
| 3 | `end = min(start + length, length of body)`, computed without overflow. |
| 4 | A start inside a surrogate pair moves back one unit; an end inside one moves on one. |
| 5 | Spans of one style that overlap or touch merge into one. Different styles may overlap. |
| 6 | Sort by (start, code). |

The receiver normalises once, against the clamped body, in `InboundPipeline.deliverChat`, and stores the result. A
sealed message's styles come only from its plaintext: `plaintextContent` never takes them from the cleartext shell.
An encoder emits only normalised spans, with minimal uvarints, and nothing when there are none.

**The value's type is frozen as a CBOR byte string, forever.** Every extension rides inside its bytes: a new
code, the code-7 escape. A malformed uvarint never throws, but a value of the wrong CBOR type at the key does, as
with any typed field (the `VideoInfo` / label 16 precedent):

- a sealed message with a non-byte-string `"s"` or label 17 fails its plaintext decode and is dropped as
  `DECRYPT_FAILED`. Nothing is committed, so its custody copies are peeked until they age out;
- a room post with one is not carried by new builds.

Only a buggy sender's own message is lost.

"Inside a surrogate pair" means a high surrogate at `i - 1` and a low one at `i`, both in range. A body may end on a
lone high surrogate or begin with a lone low one, and the snap must not read past either end.

**What else is built on it.**

- **Storage:** `messages.styles` and `drafts.styles` (DB v25), readable JSON triples `[[start, length, code], …]`.
  A draft keeps its formatting (ADR 2026-09.qtg9's amendment).
- **The composer:** a `TextFieldState` tracks the ranges through every edit (`ExpandPolicy.InsideOnly`: typing
  inside a span grows it, typing at its edge does not). The floating toolbar offers Bold, Italic, Strikethrough,
  Monospace and Clear formatting on a selection, except in the Meshtastic room, whose posts go to foreign clients
  as plain text.
- **The bubble:** bold, italic, line-through, and monospace on a faint wash of the text's own colour. A struck
  link keeps its underline. Jumbo emoji and a translation are drawn plain, the second because the ranges index
  the original.

**What it costs.**

- `InsideOnly` meets autocorrect: an IME replacing "teh" with "the" inside a bold word is a minimal edit, so the
  span can end short of the word.
- Cut and paste inside the composer drops styles, because paste is plain text.
- The notification's inline reply stays plain until Markdown-lite parses it.
- A sealed message grows by the span bytes. A LoRa DM's inline-ack room (`inlineAcksFor`, ADR 054) and the
  composer's LoRa hint both count `StyleSpans.wireCost`.

Pinned by `StyleSpansTest` (the codec and every rule above), the byte-exact and drift tests in `MessageContentTest` /
`MessageContentV2Test`, `GoldenVectorTest`'s styled entries, `InboundPipelineTest` (the shell is ignored, the clamp
clips) and `StyledMessageLabTest`.
