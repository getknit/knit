---
id: "2026-09.v66c"
slug: reactions-are-an-open-emoji-set-with-a-receive-side-length-cap
title: "Reactions are an open emoji set with a receive-side length cap"
date: 2026-09-01
topics: [wire, ui, limits]
---

# ADR 2026-09.v66c — Reactions are an open emoji set with a receive-side length cap

Status: Accepted (2026-09-01; `TextLimits.REACTION` + `isValidReactionEmoji`, `DropReason.REACTION_REFUSED`,
`data/emoji/` + `assets/emoji/emoji_en.tsv`, `EmojiPickerSheet`, `SettingsStore.recentReactions`)

Reactions were picked from a six-glyph list that lived in exactly one place — a `private val` in
`ChatScreen.kt`. Everything below it was already open: the three wire carriers (`ReactionContent.emoji`,
`ReactionPayload.emoji`, `ReactionV2.emoji`) are free-form `String?`, the `0x05` transcoder passes the
value verbatim, the `reactions.emoji` column is nullable `TEXT` unchanged since DB v1, and both inbound
paths stored whatever arrived with **no length cap at all** — a hostile peer could already put an 8 KiB
string in a reaction. So "any emoji" was never a wire question. It was three others: what the picker is
built from, what the receiver should refuse, and what a long emoji costs on LoRa.

## What changed

**The set is open; the picker is Compose-native over a vendored Unicode catalog.** `emoji-test.txt`
(Emoji 17.0, Unicode License v3) is vendored under `third_party/unicode-emoji/` exactly like the profanity
corpus, and `scripts/gen-emoji-catalog.py` (with `--check` in CI and `--update <ver>` for the next Unicode
release) emits `assets/emoji/emoji_en.tsv`: the 3,944 `fully-qualified` entries only, in Unicode order,
with a skin-tone flag. Fully-qualified only because two users' identical reactions must tally as one chip,
and the minimally-qualified forms are the same emoji minus a VS16. The loader drops, once per process,
every entry `Paint.hasGlyph` refuses, so an older phone is never offered a tofu box; the grid hides the
~2,000 skin-tone variants under their base while name search still reaches them. `androidx.emoji2`'s
`EmojiPickerView` was the alternative a reader would reach for first: it would have put `recyclerview`
into the release APK for the first time, dragged emoji2 off its pinned 1.4.0 (a `minCompileSdk` probe),
needed an AppCompat theme wrapper in a pure-Compose app and an R8 keep audit against ADR 050, and has no
search. The catalog costs ~30 KB in the APK and no dependency.

**The receiver caps length and nothing else.** `TextLimits.REACTION = 32` UTF-16 units — ~2× the longest
RGI sequence Unicode ships (15, a two-person kiss with skin tones; a tag-sequence flag is 14) — enforced
by one predicate, `isValidReactionEmoji`, on the sender (log and ignore, before the optimistic row) and on
both inbound paths, where a blank or over-long value applies nothing and counts `REACTION_REFUSED`. Three
alternatives were rejected. `.take(32)` splits a surrogate pair or ZWJ sequence into tofu that the
exact-string tally then counts as its own chip. Treating an oversized value as a retraction lets garbage
erase a valid reaction. An emoji-*class* test — code-point ranges, "one grapheme cluster" — would make
every build drop every emoji Unicode adds after it shipped, and grapheme segmentation varies with the
device ICU; the picker is the only emitter and is where "one RGI emoji" is guaranteed. The cap is the
rule-5 shape of `docs/WIRE_COMPAT.md` applied to a size: custody, relay, fan-out and the ratchet chain
advance all run before or regardless of it, and `canCarry` never reads the emoji, so the custody rule
stays identical on every build (ADR 006). No field, type, ctl, capability bit or DB change; the golden
vectors moved nothing.

**Tallies are not normalized.** `👍` and `👍🏽` are distinct reactions, as in Slack and WhatsApp; the picker
emits fully-qualified forms only, so the app never manufactures a VS16 variant of its own.

**The quick row is the user's recents.** Six most-recent picks (twelve kept, one U+001F-joined preference
string — a preference *set* would lose the order that is the whole datum), seeded with the classic six and
fronted on an add or replace, never on a retraction. The row shows as many recents as the window fits
beside the new "+" (six plus "+" is 376 dp, past a 360 dp phone, and a `Popup` can clamp but not shrink).

## What it costs

A long emoji is one wire byte per UTF-8 byte: the sealed v3 reaction goes from **229 B (👍) to 261 B**
for the longest RGI sequence and **290 B at the cap** — two LoRa packets at 228 and 231 instead of one
(the ESP32 cap already sent 👍 as two), and two Wi-Fi Aware coordination messages instead of one, never
three and never `loraTooBig`. Pinned by `theWorstCaseEmojiKeepsEveryReactionFormWithinTwoPackets`; the 👍
fixtures in `theTranscoderPutsTheSignedV3FormsInOnePacket` are untouched. A room reaction keeps one packet.
A received emoji this device's font lacks still renders as tofu in the chip — `hasGlyph` filters what we
*pick*, not what arrives; bundling `emoji2-bundled`'s ~9 MB font was not worth it.

Performance, measured on the first device tests (Pixel 9, debug build, 120 Hz) and fixed in four places.
A tab tap that used `animateScrollToItem` stuttered for a second, because foundation 1.12 animates up to
2,500 dp of content before it teleports (470 cell measures, one 1.3 s frame): tabs now `scrollToItem`. The
grid then cost ~1.4 ms per cell to compose — ~200 ms for the first screen and 13–20 ms per new row while
flinging, past the 8 ms a 120 Hz frame allows — so a *row* is the lazy item (`EmojiRow`): one draw pass
over a per-emoji `TextLayoutResult` cache, a childless semantics box per emoji for TalkBack and the tests,
and one tap detector for the whole list; a fling went from most frames janky to 0–4 of 120. The loading
skeleton's pulse recomposed 49 scopes per frame through the sheet's slide-in (26–33 ms frames): it is one
draw node reading the pulse in the draw lambda (9–11 ms). The catalog is prefetched when a chat opens, so
the sheet composes its grid on the first frame. Text draws from the system emoji font
(`EmojiSupportMatch.None`), the font `Paint.hasGlyph` filtered against, not EmojiCompat's downloaded one.
What remains is the ~100 ms creation of the sheet's dialog window plus ~80–90 ms for the first thirteen
rows, both one-off per open. The last avoidable cost, content capture's per-frame semantics walk, is an
app-wide decision of its own (ADR 2026-09.zu5t, "Content capture is off").

Two more traps. The jumbomoji detector (`EmojiText.kt`) had to widen: it rejected 92 real emoji (⭐ ✅ ©️ ↔️,
the zodiac, the subdivision flags), which the catalog contract test (`emojiOnlyCount(e) == 1` for every
entry) now pins — a Unicode bump that lands outside its ranges fails that test rather than rendering a lone
new emoji at body size. And the generator hard-fails on an emoji *group* it does not know, so a new Unicode
group must grow `EmojiGroup` in the same change.

## Amendment 2026-11-01 — search reads CLDR keywords

Name-only search missed the obvious queries: "100" or "score" never found 💯 ("hundred points"), "like"
never found 👍. The catalog now carries CLDR's English emoji keywords as an optional fifth column, generated
from `annotations/en.xml` + `annotationsDerived/en.xml` vendored at `release-48` under `third_party/unicode-cldr/`
(Unicode License v3, a second in-app notice). Base entries only: a tone variant's derived keywords are the
base's plus the tone, which would put six (or twenty-six) hits on every keyword query, and its name already
reaches it. A token matches a name substring, or the **prefix** of a keyword or of one of its words ("-1", "serve" in
"soft-serve") — never a keyword substring, or "one"
would find every "phone" and "bone". Name matches rank above keyword matches. The generator hard-fails on a base
emoji CLDR lacks, so CLDR moves in step with a Unicode bump (`--update-cldr`). The asset grows ~30 → ~52 KB
compressed in the APK.

## Amendment 2026-11-01 — emojilib after CLDR

CLDR's keywords are formal ("hundred points" has "score", not "exam"); emojilib (`muan/emojilib`, MIT) is the
community set most pickers outside Android search with, and the one people's habits come from. Its
`dist/emoji-en-US.json` is vendored at npm 4.0.3 under `third_party/emojilib/` and appended after CLDR in the
same keyword column: lower-cased, `_` read as a space (its first keyword is its snake_case name, which then
drops as the name), duplicates dropped, keys compared with U+FE0F stripped. It covers every Emoji 17.0 base
entry today, but it is community data that lags Unicode, so a missing emoji keeps CLDR's keywords rather than
failing the build. CLDR alone stays the hard requirement. The asset grows to ~74 KB compressed.

The same change fixed a parser bug the asset test shared: a line starting with `#` was a comment, so the
keycap #️⃣ row never reached the picker. A comment is now a `#` line with no tab, and the generator refuses a
tab in its header.

